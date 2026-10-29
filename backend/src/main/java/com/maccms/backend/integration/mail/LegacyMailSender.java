package com.maccms.backend.integration.mail;

import com.maccms.backend.integration.application.MailSender;
import com.maccms.backend.integration.application.SendResult;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import java.util.regex.Pattern;

/**
 * 旧 {@code mac_send_mail}（{@code application/common.php:406-431}）与 {@code Phpmailer::submit}：前置检查与返回码照旧，SMTP 由
 * {@link SmtpMailer} 完成（旧版依赖的 PHPMailer 在仓库与备份中都缺失，旧版走到这里会报致命错误，D-B25-1）。
 */
final class LegacyMailSender implements MailSender {

    /** 旧版的正则：不锚定，{@code \w} 只有 ASCII（PCRE 未开 u 修饰）。 */
    static final Pattern EMAIL = Pattern.compile("\\w+([-+.]\\w+)*@\\w+([-.]\\w+)*\\.\\w+([-.]\\w+)*");

    private final ThirdPartyConfig config;
    private final SmtpMailer smtp;

    LegacyMailSender(ThirdPartyConfig config, SmtpMailer smtp) {
        this.config = config;
        this.smtp = smtp;
    }

    @Override
    public SendResult send(String to, String title, String body) {
        return send(to, title, body, new SmtpMailer.Server(config.text("email", "phpmailer", "host"),
                config.text("email", "phpmailer", "port"), config.text("email", "phpmailer", "secure"),
                config.text("email", "phpmailer", "username"), config.secret("email", "phpmailer", "password").reveal(),
                config.text("email", "nick")));
    }

    @Override
    public SendResult sendTest(String type, String nick, String to, String title, String body) {
        // 旧 test_email：$conf 的 host/port/username/password/secure 取 email.<type>，nick 取提交值
        return send(to, title, body, new SmtpMailer.Server(config.text("email", type, "host"),
                config.text("email", type, "port"), config.text("email", type, "secure"),
                config.text("email", type, "username"), config.secret("email", type, "password").reveal(),
                nick == null ? "" : nick));
    }

    private SendResult send(String to, String title, String body, SmtpMailer.Server server) {
        if (config.empty("email", "type")) {
            return new SendResult(9005, "未配置邮箱发送服务");
        }
        if (to == null || !EMAIL.matcher(to).find()) {
            return new SendResult(999, "邮箱格式不正确");
        }
        if (phpEmpty(title)) {
            return new SendResult(998, "标题不能为空");
        }
        if (phpEmpty(body)) {
            // 旧语言包 body_not_empty 的文字就是"标题不能为空"
            return new SendResult(997, "标题不能为空");
        }
        if (!ucfirst(config.text("email", "type")).equals("Phpmailer")) {
            return new SendResult(991, "未找到该邮件发送方式");
        }
        SmtpMailer.Outcome o = smtp.send(server, to, title, body);
        return o.ok() ? new SendResult(1, "发送成功") : new SendResult(102, "发生错误：" + o.error());
    }

    private static boolean phpEmpty(String s) {
        return s == null || s.isEmpty() || s.equals("0");
    }

    private static String ucfirst(String s) {
        return s.isEmpty() || s.charAt(0) < 'a' || s.charAt(0) > 'z' ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
