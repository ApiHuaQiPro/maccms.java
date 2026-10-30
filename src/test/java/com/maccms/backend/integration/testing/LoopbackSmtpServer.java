package com.maccms.backend.integration.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * 本机模拟 SMTP 服务（B25 的邮件测试；32 文档第 8 节）：只绑定 {@code 127.0.0.1} 随机端口，按配置声明扩展（AUTH 机制、STARTTLS）、
 * 校验账号（LOGIN、PLAIN、CRAM-MD5）、可以拒绝收件人或不发问候，记下每个会话的命令与收到的信件。TLS 用测试时现生成的自签名证书
 * （{@link TestTls}），不在仓库里保存任何私钥。
 */
public final class LoopbackSmtpServer implements AutoCloseable {

    /** 一个会话：收到的命令（AUTH 的应答行原样，测试自己判断）、信件正文（DATA 到 {@code .} 之间，已去掉点填充）、投递时是否已加密。 */
    public record Session(List<String> commands, List<byte[]> messages, boolean tlsAtMail) {
    }

    /** 服务端行为。 */
    public static final class Options {
        String auth = "LOGIN PLAIN CRAM-MD5";
        boolean startTls;
        boolean implicitTls;
        boolean greet = true;
        boolean rejectRcpt;
        String user = "sender@example.test";
        String pass = "test-only-smtp-password";
        TestTls tls;

        public Options auth(String mechanisms) {
            this.auth = mechanisms;
            return this;
        }

        public Options startTls(TestTls tls) {
            this.startTls = true;
            this.tls = tls;
            return this;
        }

        public Options implicitTls(TestTls tls) {
            this.implicitTls = true;
            this.tls = tls;
            return this;
        }

        public Options silent() {
            this.greet = false;
            return this;
        }

        public Options rejectRcpt() {
            this.rejectRcpt = true;
            return this;
        }

        public Options account(String user, String pass) {
            this.user = user;
            this.pass = pass;
            return this;
        }
    }

    private final ServerSocket server;
    private final Options options;
    private final List<Session> sessions = new CopyOnWriteArrayList<>();
    private final Thread thread;

    private LoopbackSmtpServer(Options options) throws IOException {
        this.options = options;
        this.server = options.implicitTls ? options.tls.serverContext().getServerSocketFactory().createServerSocket()
                : new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        thread = new Thread(this::loop, "loopback-smtp");
        thread.setDaemon(true);
        thread.start();
    }

    public static LoopbackSmtpServer start(Options options) {
        try {
            return new LoopbackSmtpServer(options);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Options options() {
        return new Options();
    }

    public int port() {
        return server.getLocalPort();
    }

    /** 已结束的会话。服务端在会话结束后才登记，客户端返回时可能还没登记完，最多等 5 秒直到至少有 {@code min} 个。 */
    public List<Session> sessions(int min) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (sessions.size() < min && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        return Collections.unmodifiableList(sessions);
    }

    /** 已结束的会话（不等待）。 */
    public List<Session> sessions() {
        return Collections.unmodifiableList(sessions);
    }

    private void loop() {
        while (!server.isClosed()) {
            try (Socket s = server.accept()) {
                s.setSoTimeout(10_000);
                handle(s);
            } catch (IOException e) {
                // 会话结束或服务关闭
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        List<String> commands = new ArrayList<>();
        List<byte[]> messages = new ArrayList<>();
        boolean tlsAtMail = false;
        Socket s = socket;
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        boolean secured = options.implicitTls;
        try {
            if (!options.greet) {
                in.read();
                return;
            }
            reply(out, "220 loopback ESMTP");
            boolean authed = false;
            while (true) {
                String line = readLine(in);
                if (line == null) {
                    return;
                }
                commands.add(line);
                String upper = line.toUpperCase();
                if (upper.startsWith("EHLO")) {
                    List<String> ext = new ArrayList<>(List.of("250-loopback"));
                    if (options.startTls && !secured) {
                        ext.add("250-STARTTLS");
                    }
                    if (!options.auth.isEmpty()) {
                        ext.add("250-AUTH " + options.auth);
                    }
                    ext.add("250 8BITMIME");
                    reply(out, String.join("\r\n", ext));
                } else if (upper.startsWith("HELO")) {
                    reply(out, "250 loopback");
                } else if (upper.equals("STARTTLS") && options.startTls && !secured) {
                    reply(out, "220 go ahead");
                    SSLSocket t = (SSLSocket) options.tls.serverContext().getSocketFactory()
                            .createSocket(s, null, s.getPort(), true);
                    t.setUseClientMode(false);
                    t.startHandshake();
                    s = t;
                    in = t.getInputStream();
                    out = t.getOutputStream();
                    secured = true;
                } else if (upper.startsWith("AUTH LOGIN")) {
                    reply(out, "334 " + b64("Username:"));
                    String u = unb64(readLine(in));
                    reply(out, "334 " + b64("Password:"));
                    String p = unb64(readLine(in));
                    authed = u.equals(options.user) && p.equals(options.pass);
                    reply(out, authed ? "235 ok" : "535 bad credentials");
                } else if (upper.startsWith("AUTH PLAIN")) {
                    reply(out, "334 ");
                    String[] parts = unb64(readLine(in)).split("\0", -1);
                    authed = parts.length == 3 && parts[1].equals(options.user) && parts[2].equals(options.pass);
                    reply(out, authed ? "235 ok" : "535 bad credentials");
                } else if (upper.startsWith("AUTH CRAM-MD5")) {
                    String challenge = "<" + new SecureRandom().nextInt(1_000_000) + ".1@loopback>";
                    reply(out, "334 " + b64(challenge));
                    String[] parts = unb64(readLine(in)).split(" ", 2);
                    authed = parts.length == 2 && parts[0].equals(options.user)
                            && parts[1].equals(hmacMd5(options.pass, challenge));
                    reply(out, authed ? "235 ok" : "535 bad credentials");
                } else if (upper.startsWith("MAIL FROM:")) {
                    tlsAtMail = secured;
                    reply(out, authed ? "250 ok" : "530 auth required");
                } else if (upper.startsWith("RCPT TO:")) {
                    reply(out, options.rejectRcpt ? "550 no such user" : "250 ok");
                } else if (upper.equals("DATA")) {
                    reply(out, "354 end with .");
                    messages.add(readData(in));
                    reply(out, "250 queued");
                } else if (upper.equals("QUIT")) {
                    reply(out, "221 bye");
                    return;
                } else {
                    reply(out, "502 unknown");
                }
            }
        } catch (SocketException e) {
            // 客户端断开
        } finally {
            sessions.add(new Session(List.copyOf(commands), List.copyOf(messages), tlsAtMail));
        }
    }

    private static void reply(OutputStream out, String text) throws IOException {
        out.write((text + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c < 0) {
                return b.size() == 0 ? null : b.toString(StandardCharsets.UTF_8);
            }
            if (c == '\n') {
                return b.toString(StandardCharsets.UTF_8);
            }
            if (c != '\r') {
                b.write(c);
            }
        }
    }

    private static byte[] readData(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(in);
            if (line == null || line.equals(".")) {
                return b.toByteArray();
            }
            String l = line.startsWith("..") ? line.substring(1) : line;
            b.write((l + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        return s == null ? "" : new String(Base64.getDecoder().decode(s.strip()), StandardCharsets.UTF_8);
    }

    private static String hmacMd5(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacMD5");
            mac.init(new SecretKeySpec(key.isEmpty() ? new byte[64] : key.getBytes(StandardCharsets.UTF_8), "HmacMD5"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            server.close();
            thread.join(2000);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 测试用 TLS：用 JDK 的 keytool 现生成 {@code CN=localhost}（SAN 含 {@code localhost} 与 {@code 127.0.0.1}）的自签名证书，
     * 口令随机、文件在临时目录，用完删除。{@link #clientFactory()} 只信任这张证书。
     */
    public static final class TestTls {

        private final SSLContext server;
        private final SSLContext client;

        private TestTls(SSLContext server, SSLContext client) {
            this.server = server;
            this.client = client;
        }

        public static TestTls generate() {
            try {
                Path dir = Files.createTempDirectory("b25-tls");
                Path ks = dir.resolve("ks.p12");
                char[] pass = HexFormat.of().formatHex(SecureRandom.getSeed(12)).toCharArray();
                Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                        "-genkeypair", "-alias", "loopback", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
                        "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2", "-storetype", "PKCS12",
                        "-keystore", ks.toString(), "-storepass", new String(pass), "-keypass", new String(pass))
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
                    throw new IllegalStateException("keytool failed");
                }
                KeyStore store = KeyStore.getInstance("PKCS12");
                try (InputStream in = Files.newInputStream(ks)) {
                    store.load(in, pass);
                }
                Files.delete(ks);
                Files.delete(dir);
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(store, pass);
                SSLContext server = SSLContext.getInstance("TLS");
                server.init(kmf.getKeyManagers(), null, null);
                KeyStore trust = KeyStore.getInstance("PKCS12");
                trust.load(null, null);
                trust.setCertificateEntry("loopback", store.getCertificate("loopback"));
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trust);
                SSLContext client = SSLContext.getInstance("TLS");
                client.init(null, tmf.getTrustManagers(), null);
                return new TestTls(server, client);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        SSLContext serverContext() {
            return server;
        }

        /** 只信任本证书的客户端工厂。 */
        public SSLSocketFactory clientFactory() {
            return client.getSocketFactory();
        }
    }
}
