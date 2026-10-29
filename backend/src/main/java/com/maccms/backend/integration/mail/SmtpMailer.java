package com.maccms.backend.integration.mail;

import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.integration.http.OutboundPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 最小的 SMTP 发信客户端（E07，B25；32 文档第 4 节），代替旧 {@code common/extend/email/Phpmailer.php} 依赖的 PHPMailer（仓库与备份的
 * {@code extend/} 里都没有这个库）。只做验证码与测试邮件需要的部分：一封 HTML 正文、一个收件人。
 *
 * <ul>
 * <li>目标检查（26 文档第 3.2 节的同一套规则，SMTP 版）：主机非空、只含主机名字符；{@code loopback-only} 模式下只能是回环字面地址
 * （{@link OutboundPolicy#isLoopbackLiteral}），不合规即失败、不建立连接；端口 1～65535。</li>
 * <li>加密：{@code secure=ssl} 直接 TLS；{@code tls} 必须 STARTTLS；其他值（含仓库默认的 {@code tsl}）服务器支持 STARTTLS 时自动升级
 * （PHPMailer 6 的 {@code SMTPAutoTLS}）。TLS 校验证书与主机名。主机可写成 {@code ssl://主机}、{@code tls://主机}（PHPMailer 的写法）。</li>
 * <li>认证：总是认证（旧版 {@code SMTPAuth = true}），按服务器声明依次选 CRAM-MD5、LOGIN、PLAIN；服务器没有声明 AUTH 时失败。</li>
 * <li>信件：{@code From: 昵称 <用户名>}、{@code To}、{@code Subject}（非 ASCII 按 RFC 2047 编码，去掉换行）、{@code Date}、
 * {@code Message-ID}、{@code MIME-Version}、{@code Content-Type: text/html; charset=UTF-8}，正文 base64。</li>
 * <li>超时：连接与每次读取都用出站客户端的参数（默认 15 秒、30 秒）。</li>
 * <li>失败信息用 PHPMailer 的英文提示（旧版显示 {@code $mail->ErrorInfo}）；日志只记主机、端口、结果与耗时，不记地址、内容与凭证。</li>
 * </ul>
 */
final class SmtpMailer {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailer.class);

    static final String CONNECT_FAILED = "SMTP connect() failed. https://github.com/PHPMailer/PHPMailer/wiki/Troubleshooting";
    static final String AUTH_FAILED = "SMTP Error: Could not authenticate.";
    static final String NO_RECIPIENT = "You must provide at least one recipient email address.";
    static final String DATA_REJECTED = "SMTP Error: data not accepted.";

    /** 发件人与收件人：ASCII 的 {@code 本地部分@域名}，不含空白、控制字符、{@code <>()[],;:"\}。 */
    private static final Pattern ADDRESS = Pattern.compile(
            "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}|\\[[0-9A-Fa-f:.]{2,45}\\]");
    private static final int MAX_LINE = 4096;
    private static final int MAX_REPLY_LINES = 100;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ROOT);

    /** 一次发信的 SMTP 参数（旧 {@code $config} 数组）。 */
    record Server(String host, String port, String secure, String username, String password, String nick) {
        @Override
        public String toString() {
            return "Server[***]";
        }
    }

    /** 发信结果：{@code error} 为 null 表示成功，否则是给用户看的失败原因（PHPMailer 的 {@code ErrorInfo}）。 */
    record Outcome(String error) {
        boolean ok() {
            return error == null;
        }
    }

    private final OutboundPolicy.Mode mode;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final SSLSocketFactory tls;
    private final Clock clock;
    private final ZoneId zone;
    private final RandomGenerator random;

    SmtpMailer(OutboundPolicy.Mode mode, Duration connectTimeout, Duration readTimeout, SSLSocketFactory tls, Clock clock,
               ZoneId zone, RandomGenerator random) {
        this.mode = mode;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.tls = tls;
        this.clock = clock;
        this.zone = zone;
        this.random = random;
    }

    Outcome send(Server server, String to, String subject, String htmlBody) {
        if (to == null || !ADDRESS.matcher(to).matches()) {
            return new Outcome(NO_RECIPIENT);
        }
        String from = server.username() == null ? "" : server.username();
        if (!ADDRESS.matcher(from).matches()) {
            return new Outcome("Invalid address:  (From)");
        }
        String host = server.host() == null ? "" : server.host().strip();
        String secure = server.secure() == null ? "" : server.secure().strip().toLowerCase(Locale.ROOT);
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.startsWith("ssl://") || lower.startsWith("tls://")) {
            secure = lower.substring(0, 3);
            host = host.substring(6);
        }
        int port = intval(server.port());
        if (!HOST.matcher(host).matches() || port < 1 || port > 65535
                || (mode == OutboundPolicy.Mode.LOOPBACK_ONLY && !OutboundPolicy.isLoopbackLiteral(host))) {
            log.info("mail.smtp rejected target");
            return new Outcome(CONNECT_FAILED);
        }
        String target = host + ":" + port;
        long started = System.nanoTime();
        String error;
        try (Session s = new Session(host, port, secure.equals("ssl"))) {
            error = s.deliver(secure, server, from, to, message(server, from, to, subject, htmlBody));
        } catch (SmtpFailure e) {
            error = e.getMessage();
        } catch (SocketTimeoutException e) {
            error = "SMTP Error: timeout";
        } catch (IOException | RuntimeException e) {
            log.info("mail.smtp {} failed: {}", target, SafeExceptionLog.typeChain(e));
            error = CONNECT_FAILED;
        }
        log.info("mail.smtp {} {} {}ms", target, error == null ? "sent" : "failed", (System.nanoTime() - started) / 1_000_000);
        return new Outcome(error);
    }

    // ------------------------------------------------------------------ 信件

    byte[] message(Server server, String from, String to, String subject, String htmlBody) {
        StringBuilder h = new StringBuilder();
        h.append("Date: ").append(DATE.format(clock.instant().atZone(zone))).append("\r\n");
        h.append("To: ").append(to).append("\r\n");
        String nick = secureHeader(server.nick());
        h.append("From: ").append(nick.isEmpty() ? "" : phrase(nick) + " ").append('<').append(from).append(">\r\n");
        h.append("Subject: ").append(encodeHeader(secureHeader(subject), "Subject: ".length())).append("\r\n");
        byte[] id = new byte[16];
        random.nextBytes(id);
        String domain = from.substring(from.indexOf('@') + 1);
        h.append("Message-ID: <").append(HexFormat.of().formatHex(id)).append('@').append(domain).append(">\r\n");
        h.append("MIME-Version: 1.0\r\n");
        h.append("Content-Type: text/html; charset=UTF-8\r\n");
        h.append("Content-Transfer-Encoding: base64\r\n\r\n");
        String b64 = Base64.getMimeEncoder(76, new byte[] {'\r', '\n'})
                .encodeToString((htmlBody == null ? "" : htmlBody).getBytes(StandardCharsets.UTF_8));
        h.append(b64).append("\r\n");
        return h.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** PHPMailer {@code secureHeader}：去掉首尾空白与全部 CR、LF。 */
    static String secureHeader(String s) {
        return s == null ? "" : s.strip().replace("\r", "").replace("\n", "");
    }

    /** 显示名：纯 ASCII 可打印且不含特殊字符时原样，含特殊字符时加引号，非 ASCII 用 RFC 2047。 */
    static String phrase(String s) {
        if (!s.chars().allMatch(c -> c >= 0x20 && c < 0x7f)) {
            return encodeHeader(s, "From: ".length());
        }
        if (s.matches("[A-Za-z0-9!#$%&'*+/=?^_`{|}~ -]*")) {
            return s;
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 非 ASCII 时按 RFC 2047 的 B 编码分成多个 encoded-word（每个不超过 75 字符，不拆开一个字符），以 CRLF + 空格折行。 */
    static String encodeHeader(String s, int used) {
        if (s.chars().allMatch(c -> c >= 0x20 && c < 0x7f)) {
            return s;
        }
        List<String> words = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int n = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            // "=?UTF-8?B?" + base64 + "?=" ≤ 75 → base64 ≤ 63 → 原文 ≤ 45 字节
            if (bytes + n > 45) {
                words.add(chunk.toString());
                chunk.setLength(0);
                bytes = 0;
            }
            chunk.appendCodePoint(cp);
            bytes += n;
            i += Character.charCount(cp);
        }
        if (!chunk.isEmpty()) {
            words.add(chunk.toString());
        }
        StringBuilder out = new StringBuilder();
        for (String w : words) {
            if (!out.isEmpty()) {
                out.append("\r\n ");
            }
            out.append("=?UTF-8?B?").append(Base64.getEncoder().encodeToString(w.getBytes(StandardCharsets.UTF_8))).append("?=");
        }
        return out.toString();
    }

    static int intval(String s) {
        if (s == null) {
            return 0;
        }
        String t = s.strip();
        int i = 0;
        long v = 0;
        while (i < t.length() && i < 6 && t.charAt(i) >= '0' && t.charAt(i) <= '9') {
            v = v * 10 + (t.charAt(i) - '0');
            i++;
        }
        return (int) v;
    }

    // ------------------------------------------------------------------ 会话

    /** 协议失败：信息是给用户看的 PHPMailer 提示。 */
    static final class SmtpFailure extends Exception {
        SmtpFailure(String msg) {
            super(msg, null, false, false);
        }
    }

    private record Reply(int code, List<String> lines) {
    }

    private final class Session implements AutoCloseable {

        private final String host;
        private Socket socket;
        private InputStream in;
        private OutputStream out;
        private boolean secured;

        Session(String host, int port, boolean implicitTls) throws IOException {
            this.host = host;
            Socket plain = new Socket();
            try {
                plain.connect(new InetSocketAddress(host.startsWith("[") ? host.substring(1, host.length() - 1) : host, port),
                        (int) connectTimeout.toMillis());
                plain.setSoTimeout((int) readTimeout.toMillis());
                this.socket = plain;
                if (implicitTls) {
                    upgrade();
                }
                this.in = socket.getInputStream();
                this.out = socket.getOutputStream();
            } catch (IOException | RuntimeException e) {
                plain.close();
                throw e;
            }
        }

        private void upgrade() throws IOException {
            SSLSocket s = (SSLSocket) tls.createSocket(socket, host, socket.getPort(), true);
            SSLParameters p = s.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            s.setSSLParameters(p);
            s.setSoTimeout((int) readTimeout.toMillis());
            s.startHandshake();
            socket = s;
            in = s.getInputStream();
            out = s.getOutputStream();
            secured = true;
        }

        String deliver(String secure, Server server, String from, String to, byte[] message) throws IOException, SmtpFailure {
            expect(read(), 220, CONNECT_FAILED);
            Set<String> caps = hello();
            boolean startTls = caps.contains("STARTTLS");
            if (!secured && (secure.equals("tls") || startTls)) {
                if (!startTls) {
                    throw new SmtpFailure("SMTP Error: STARTTLS not supported by server");
                }
                command("STARTTLS");
                expect(read(), 220, "SMTP Error: STARTTLS command failed");
                upgrade();
                caps = hello();
            }
            authenticate(caps, server.username(), server.password() == null ? "" : server.password());
            command("MAIL FROM:<" + from + ">");
            expect(read(), 250, "The following From address failed");
            command("RCPT TO:<" + to + ">");
            Reply rcpt = read();
            if (rcpt.code() != 250 && rcpt.code() != 251) {
                throw new SmtpFailure("SMTP Error: The following recipients failed");
            }
            command("DATA");
            expect(read(), 354, DATA_REJECTED);
            out.write(dotStuff(message));
            out.write(".\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            expect(read(), 250, DATA_REJECTED);
            try {
                command("QUIT");
                read();
            } catch (IOException ignored) {
                // 已投递；QUIT 的结果不影响
            }
            return null;
        }

        /** EHLO，失败时 HELO；返回服务器声明的扩展（大写，AUTH 的机制写成 {@code AUTH <机制>}）。 */
        private Set<String> hello() throws IOException, SmtpFailure {
            command("EHLO localhost.localdomain");
            Reply r = read();
            Set<String> caps = new LinkedHashSet<>();
            if (r.code() != 250) {
                command("HELO localhost.localdomain");
                expect(read(), 250, CONNECT_FAILED);
                return caps;
            }
            caps.add("EHLO");
            for (int i = 1; i < r.lines().size(); i++) {
                String line = r.lines().get(i);
                String[] parts = (line.length() > 4 ? line.substring(4) : "").strip().toUpperCase(Locale.ROOT).split("[ =]+");
                if (parts.length == 0) {
                    continue;
                }
                caps.add(parts[0]);
                if (parts[0].equals("AUTH")) {
                    for (int j = 1; j < parts.length; j++) {
                        caps.add("AUTH " + parts[j]);
                    }
                }
            }
            return caps;
        }

        private void authenticate(Set<String> caps, String user, String pass) throws IOException, SmtpFailure {
            if (!caps.contains("AUTH")) {
                throw new SmtpFailure("SMTP Error: Could not authenticate.");
            }
            String u = user == null ? "" : user;
            if (caps.contains("AUTH CRAM-MD5")) {
                command("AUTH CRAM-MD5");
                Reply r = read();
                expect(r, 334, AUTH_FAILED);
                String challenge = new String(Base64.getMimeDecoder().decode(r.lines().get(0).substring(4)), StandardCharsets.UTF_8);
                command(b64(u + " " + hmacMd5(pass, challenge)));
            } else if (caps.contains("AUTH LOGIN")) {
                command("AUTH LOGIN");
                expect(read(), 334, AUTH_FAILED);
                command(b64(u));
                expect(read(), 334, AUTH_FAILED);
                command(b64(pass));
            } else if (caps.contains("AUTH PLAIN")) {
                command("AUTH PLAIN");
                expect(read(), 334, AUTH_FAILED);
                command(b64("\0" + u + "\0" + pass));
            } else {
                throw new SmtpFailure(AUTH_FAILED);
            }
            expect(read(), 235, AUTH_FAILED);
        }

        private void command(String line) throws IOException {
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private Reply read() throws IOException {
            List<String> lines = new ArrayList<>();
            while (true) {
                String line = readLine();
                if (line.length() < 3 || lines.size() >= MAX_REPLY_LINES) {
                    throw new IOException("bad reply");
                }
                lines.add(line);
                if (line.length() == 3 || line.charAt(3) != '-') {
                    int code;
                    try {
                        code = Integer.parseInt(line.substring(0, 3));
                    } catch (NumberFormatException e) {
                        throw new IOException("bad reply");
                    }
                    List<String> texts = new ArrayList<>();
                    for (String l : lines) {
                        texts.add(l.length() > 4 ? l.substring(0, 3) + " " + l.substring(4) : l);
                    }
                    return new Reply(code, texts);
                }
            }
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            while (true) {
                int c = in.read();
                if (c < 0) {
                    throw new IOException("connection closed");
                }
                if (c == '\n') {
                    break;
                }
                if (c != '\r') {
                    if (b.size() >= MAX_LINE) {
                        throw new IOException("line too long");
                    }
                    b.write(c);
                }
            }
            return b.toString(StandardCharsets.UTF_8);
        }

        private void expect(Reply r, int code, String failure) throws SmtpFailure {
            if (r.code() != code) {
                throw new SmtpFailure(failure);
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacMd5(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacMD5");
            // HMAC 的空密钥等价于全零密钥（SecretKeySpec 不接受空数组）
            mac.init(new SecretKeySpec(key.isEmpty() ? new byte[64] : key.getBytes(StandardCharsets.UTF_8), "HmacMD5"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacMD5 不可用", null);
        }
    }

    /** 以 {@code .} 开头的行前面再加一个 {@code .}（RFC 5321 第 4.5.2 节）。 */
    static byte[] dotStuff(byte[] message) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(message.length + 16);
        boolean lineStart = true;
        for (byte c : message) {
            if (lineStart && c == '.') {
                b.write('.');
            }
            b.write(c);
            lineStart = c == '\n';
        }
        return b.toByteArray();
    }
}
