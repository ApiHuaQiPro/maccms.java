package com.maccms.backend.integration.application;

/**
 * 邮件发送（E07，B25；32 文档第 4 节）：旧 {@code mac_send_mail($to, $title, $body, $conf)}（{@code application/common.php:406-431}）
 * 与 {@code common/extend/email/Phpmailer.php}。实现在登记子包 {@code integration.mail}（SMTP，不走 HTTP）；其他模块只经本接口使用。
 */
public interface MailSender {

    /**
     * 用旧配置 {@code email.phpmailer.*} 与 {@code email.nick} 发信（会员验证码）。判定顺序与旧版相同：{@code email.type} 为空 → 9005；
     * 地址不含 {@code \w+([-+.]\w+)*@\w+([-.]\w+)*\.\w+([-.]\w+)*} → 999；标题为空 → 998；正文为空 → 997；{@code email.type} 不是
     * {@code phpmailer}（不分大小写的首字母）→ 991；否则发信，成功 1 "发送成功"，失败 102 "发生错误：" + 原因。
     *
     * @param body HTML 正文
     */
    SendResult send(String to, String title, String body);

    /**
     * 后台"测试发信"（旧 {@code admin/controller/System.php:12-40}）：SMTP 参数取旧配置 {@code email.<type>.*}（{@code type} 是测试请求
     * 提交的发送方式，小写），发件人昵称用提交的 {@code nick}；其余同 {@link #send}。
     */
    SendResult sendTest(String type, String nick, String to, String title, String body);
}
