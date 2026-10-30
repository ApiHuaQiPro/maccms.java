package com.maccms.backend.integration.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.integration.application.SendResult;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import com.maccms.backend.integration.http.OutboundPolicy;
import com.maccms.backend.integration.testing.LoopbackSmtpServer;
import com.maccms.backend.integration.testing.LoopbackSmtpServer.Options;
import com.maccms.backend.integration.testing.LoopbackSmtpServer.TestTls;
import com.maccms.backend.integration.testing.TestOutboundGuard;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.net.ssl.SSLSocketFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * SMTP 发信（E07，B25；32 文档第 4 节），对本机模拟 SMTP 服务：三种认证、STARTTLS（含自动升级）、直接 TLS、证书校验、
 * {@code loopback-only} 下的外部主机、收件人与头部注入、超时；{@code mac_send_mail} 的前置检查与返回码；地址、内容、口令不进日志。
 */
@ExtendWith(OutputCaptureExtension.class)
class SmtpMailerTest {

    private static final String USER = "sender@example.test";
    private static final String PASS = "test-only-smtp-password";
    private static final String TO = "member@example.test";
    private static TestTls tls;

    private LoopbackSmtpServer smtp;

    @BeforeAll
    static void certificate() {
        tls = TestTls.generate();
    }

    @AfterEach
    void tearDown() {
        if (smtp != null) {
            smtp.close();
        }
        assertThat(TestOutboundGuard.drainViolations()).as("用例只访问回环地址").isEmpty();
    }

    private static SmtpMailer mailer(SSLSocketFactory factory, Duration read) {
        return new SmtpMailer(OutboundPolicy.Mode.LOOPBACK_ONLY, Duration.ofSeconds(2), read, factory,
                Clock.fixed(Instant.parse("2026-10-29T05:00:00Z"), ZoneOffset.UTC), ZoneId.of("Asia/Shanghai"), new Random(1));
    }

    private static SmtpMailer mailer() {
        return mailer(tls.clientFactory(), Duration.ofSeconds(5));
    }

    private SmtpMailer.Server server(String secure) {
        return new SmtpMailer.Server("127.0.0.1", String.valueOf(smtp.port()), secure, USER, PASS, "测试站");
    }

    private static String decodedBody(byte[] message) {
        String m = new String(message, StandardCharsets.US_ASCII);
        String b64 = m.substring(m.indexOf("\r\n\r\n") + 4).replace("\r\n", "");
        return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
    }

    private static String headers(byte[] message) {
        String m = new String(message, StandardCharsets.US_ASCII);
        return m.substring(0, m.indexOf("\r\n\r\n"));
    }

    @Test
    void deliversOneHtmlMessageWithAuthLogin(CapturedOutput output) {
        smtp = LoopbackSmtpServer.start(LoopbackSmtpServer.options().auth("LOGIN"));
        SmtpMailer.Outcome o = mailer().send(server("tsl"), TO, "【测试站】验证码", "<p>验证码：123456</p>\r\n.\r\nend");
        assertThat(o.ok()).as(String.valueOf(o.error())).isTrue();
        LoopbackSmtpServer.Session s = smtp.sessions(1).get(0);
        assertThat(s.commands()).startsWith("EHLO localhost.localdomain", "AUTH LOGIN");
        assertThat(s.commands()).contains("MAIL FROM:<" + USER + ">", "RCPT TO:<" + TO + ">", "DATA", "QUIT");
        assertThat(s.tlsAtMail()).as("服务器没有 STARTTLS 时按明文（PHPMailer 的自动 TLS 也是如此）").isFalse();
        String h = headers(s.messages().get(0));
        assertThat(h).contains("Date: Thu, 29 Oct 2026 13:00:00 +0800", "To: " + TO,
                "From: =?UTF-8?B?" + Base64.getEncoder().encodeToString("测试站".getBytes(StandardCharsets.UTF_8)) + "?= <" + USER + ">",
                "Subject: =?UTF-8?B?" + Base64.getEncoder().encodeToString("【测试站】验证码".getBytes(StandardCharsets.UTF_8)) + "?=",
                "MIME-Version: 1.0", "Content-Type: text/html; charset=UTF-8", "Content-Transfer-Encoding: base64");
        assertThat(h).containsPattern("Message-ID: <[0-9a-f]{32}@example\\.test>");
        assertThat(decodedBody(s.messages().get(0))).isEqualTo("<p>验证码：123456</p>\r\n.\r\nend");
        assertThat(output.getAll()).contains("mail.smtp 127.0.0.1:" + smtp.port() + " sent")
                .doesNotContain(TO, USER, PASS, "123456");
    }

    @Test
    void plainAndCramMd5AuthAndWrongPassword() {
        smtp = LoopbackSmtpServer.start(LoopbackSmtpServer.options().auth("PLAIN"));
        assertThat(mailer().send(server(""), TO, "t", "b").ok()).isTrue();
        assertThat(smtp.sessions(1).get(0).commands()).contains("AUTH PLAIN");
        smtp.close();
        smtp = LoopbackSmtpServer.start(LoopbackSmtpServer.options().auth("LOGIN PLAIN CRAM-MD5"));
        assertThat(mailer().send(server(""), TO, "t", "b").ok()).isTrue();
        assertThat(smtp.sessions(1).get(0).commands()).as("CRAM-MD5 优先（PHPMailer 的顺序）").contains("AUTH CRAM-MD5");
        assertThat(mailer().send(new SmtpMailer.Server("127.0.0.1", String.valueOf(smtp.port()), "", USER, "wrong", ""), TO,
                "t", "b").error()).isEqualTo(SmtpMailer.AUTH_FAILED);
        smtp.close();
        smtp = LoopbackSmtpServer.start(LoopbackSmtpServer.options().auth(""));
        assertThat(mailer().send(server(""), TO, "t", "b").error()).as("没有 AUTH 扩展").isEqualTo(SmtpMailer.AUTH_FAILED);
        smtp.close();
        smtp = LoopbackSmtpServer.start(LoopbackSmtpServer.options().rejectRcpt());
        assertThat(mailer().send(server(""), TO, "t", "b").error()).isEqualTo("SMTP Error: The following recipients failed");
    }

    @Test
    void startTlsIsUsedWhenOfferedAndRequiredForTls() {
        smtp = LoopbackSmtpServer.start(LoopbackSmtpServer.options().startTls(tls));
        assertThat(mailer().send(server(""), TO, "t", "b").ok()).as("自动升级").isTrue();
        assertThat(smtp.sessions(1).get(0).tlsAtMail()).isTrue();
        assertThat(smtp.sessions(1).get(0).commands()).containsSubsequence("EHLO localhost.localdomain", "STARTTLS",
                "EHLO localhost.localdomain", "AUTH CRAM-MD5");
        assertThat(mailer().send(server("tls"), TO, "t", "b").ok()).isTrue();
        // 证书不受信任（JDK 默认信任库里没有这张自签名证书）时失败，不发送凭证
        SmtpMailer.Outcome untrusted = mailer((SSLSocketFactory) SSLSocketFactory.getDefault(), Duration.ofSeconds(5))
                .send(server("tls"), TO, "t", "b");
        assertThat(untrusted.ok()).isFalse();
        assertThat(smtp.sessions(3).get(2).commands()).doesNotContain("AUTH CRAM-MD5", "AUTH LOGIN", "AUTH PLAIN");
        smtp.close();
        smtp = LoopbackSmtpServer.start(LoopbackSmtpServer.options());
        assertThat(mailer().send(server("tls"), TO, "t", "b").error()).as("要求 STARTTLS 而服务器不支持")
                .isEqualTo("SMTP Error: STARTTLS not supported by server");
        assertThat(smtp.sessions(1).get(0).commands()).doesNotContain("AUTH CRAM-MD5");
    }

    @Test
    void implicitTlsWithSslOrHostPrefix() {
        smtp = LoopbackSmtpServer.start(LoopbackSmtpServer.options().implicitTls(tls));
        assertThat(mailer().send(server("ssl"), TO, "t", "b").ok()).isTrue();
        assertThat(smtp.sessions(1).get(0).tlsAtMail()).isTrue();
        assertThat(mailer().send(new SmtpMailer.Server("ssl://localhost", String.valueOf(smtp.port()), "", USER, PASS, ""), TO,
                "t", "b").ok()).as("PHPMailer 的 ssl://主机 写法").isTrue();
        assertThat(mailer().send(new SmtpMailer.Server("127.0.0.2", String.valueOf(smtp.port()), "ssl", USER, PASS, ""), TO,
                "t", "b").ok()).as("证书主机名不符").isFalse();
    }

    @Test
    void targetAndAddressChecksHappenBeforeConnecting() throws Exception {
        SmtpMailer m = mailer();
        for (String host : List.of("smtp.example.test", "203.0.113.5", "", "a b", "x\r\nRCPT TO:<y@z>", "user@host")) {
            assertThat(m.send(new SmtpMailer.Server(host, "25", "", USER, PASS, ""), TO, "t", "b").error()).as(host)
                    .isEqualTo(SmtpMailer.CONNECT_FAILED);
        }
        for (String port : List.of("0", "", "70000", "abc")) {
            assertThat(m.send(new SmtpMailer.Server("127.0.0.1", port, "", USER, PASS, ""), TO, "t", "b").error()).as(port)
                    .isEqualTo(SmtpMailer.CONNECT_FAILED);
        }
        for (String to : List.of("a@b.com>\r\nRCPT TO:<x@y.com", "a b@c.com", "中文@c.com", "a@b.com,c@d.com", "")) {
            assertThat(m.send(new SmtpMailer.Server("127.0.0.1", "25", "", USER, PASS, ""), to, "t", "b").error()).as(to)
                    .isEqualTo(SmtpMailer.NO_RECIPIENT);
        }
        assertThat(m.send(new SmtpMailer.Server("127.0.0.1", "25", "", "not an address", PASS, ""), TO, "t", "b").error())
                .isEqualTo("Invalid address:  (From)");
        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        assertThat(m.send(new SmtpMailer.Server("127.0.0.1", String.valueOf(closed), "", USER, PASS, ""), TO, "t", "b").error())
                .isEqualTo(SmtpMailer.CONNECT_FAILED);
    }

    @Test
    void silentServerTimesOut() {
        smtp = LoopbackSmtpServer.start(LoopbackSmtpServer.options().silent());
        long t0 = System.nanoTime();
        assertThat(mailer(tls.clientFactory(), Duration.ofMillis(500)).send(server(""), TO, "t", "b").error())
                .isEqualTo("SMTP Error: timeout");
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void headerInjectionIsStrippedAndLongSubjectsAreFolded() {
        smtp = LoopbackSmtpServer.start(LoopbackSmtpServer.options());
        String subject = "注册验证码".repeat(12) + "\r\nBcc: evil@example.test";
        assertThat(mailer().send(new SmtpMailer.Server("127.0.0.1", String.valueOf(smtp.port()), "", USER, PASS,
                "nick\r\nBcc: evil@example.test"), TO, subject, "b").ok()).isTrue();
        String h = headers(smtp.sessions(1).get(0).messages().get(0));
        assertThat(h).doesNotContain("\r\nBcc:");
        assertThat(h.lines().filter(l -> l.startsWith("From:"))).singleElement().asString()
                .isEqualTo("From: \"nickBcc: evil@example.test\" <" + USER + ">");
        for (String line : h.split("\r\n")) {
            assertThat(line.length()).as(line).isLessThanOrEqualTo(998);
        }
        assertThat(smtp.sessions(1).get(0).commands()).filteredOn(c -> c.startsWith("RCPT")).hasSize(1);
    }

    // ------------------------------------------------------------------ mac_send_mail

    private static ThirdPartyConfig config(String type, int port) {
        Map<Object, PhpValue> email = new LinkedHashMap<>();
        email.put("type", new PhpValue.PhpString(type));
        email.put("nick", new PhpValue.PhpString("站点"));
        email.put("phpmailer", new PhpArray(Map.of("host", new PhpValue.PhpString("127.0.0.1"),
                "port", new PhpValue.PhpString(String.valueOf(port)), "secure", new PhpValue.PhpString(""),
                "username", new PhpValue.PhpString(USER), "password", new PhpValue.PhpString(PASS))));
        return ThirdPartyConfig.from(new PhpArray(Map.of("email", new PhpArray(email))));
    }

    @Test
    void legacyPreconditionsAndResults() {
        smtp = LoopbackSmtpServer.start(LoopbackSmtpServer.options());
        LegacyMailSender ok = new LegacyMailSender(config("Phpmailer", smtp.port()), mailer());
        assertThat(new LegacyMailSender(config("", smtp.port()), mailer()).send(TO, "t", "b"))
                .isEqualTo(new SendResult(9005, "未配置邮箱发送服务"));
        assertThat(ok.send("not-an-address", "t", "b")).isEqualTo(new SendResult(999, "邮箱格式不正确"));
        assertThat(ok.send(TO, "", "b")).isEqualTo(new SendResult(998, "标题不能为空"));
        assertThat(ok.send(TO, "t", "0")).isEqualTo(new SendResult(997, "标题不能为空"));
        assertThat(new LegacyMailSender(config("Foo", smtp.port()), mailer()).send(TO, "t", "b"))
                .isEqualTo(new SendResult(991, "未找到该邮件发送方式"));
        assertThat(smtp.sessions()).isEmpty();
        assertThat(ok.send(TO, "t", "b")).isEqualTo(new SendResult(1, "发送成功"));
        assertThat(new LegacyMailSender(config("phpmailer", smtp.port()), mailer()).send(TO, "t", "b"))
                .as("ucfirst").isEqualTo(new SendResult(1, "发送成功"));
        // 旧正则不锚定：前后有别的字符也算"格式正确"，交给 SMTP 时再按完整地址校验
        assertThat(ok.send("x " + TO, "t", "b")).isEqualTo(new SendResult(102, "发生错误：" + SmtpMailer.NO_RECIPIENT));
        // 后台测试：SMTP 参数取 email.<type>，昵称用提交值
        assertThat(ok.sendTest("phpmailer", "后台昵称", TO, "t", "b")).isEqualTo(new SendResult(1, "发送成功"));
        assertThat(headers(smtp.sessions(3).get(2).messages().get(0)))
                .contains("From: =?UTF-8?B?" + Base64.getEncoder().encodeToString("后台昵称".getBytes(StandardCharsets.UTF_8)));
        assertThat(ok.sendTest("other", "n", TO, "t", "b")).as("email.other 不存在：发件人为空")
                .isEqualTo(new SendResult(102, "发生错误：Invalid address:  (From)"));
    }
}
