package com.maccms.backend.member.application;

import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 短信/邮件验证码用到的旧配置（B25，32 文档第 5 节），启动时从 {@code extra/maccms.php} 读一次（重启生效，D-B23-5）。
 *
 * @param smsContent  {@code sms.content}：记进 {@code mac_msg.msg_content} 的短信文字模板（{@code [用户]}、{@code [类型]}、{@code [时长]}、
 *                    {@code [验证码]}；旧版不把它发给通道，通道只用模板编号）
 * @param emailTime   {@code email.time} 原文（邮件模板的 {@code {$time}}）
 * @param emailTpl    {@code email.tpl.*}（{@code user_reg_title}、{@code user_reg_body} 等）
 * @param site        {@code site.*} 的标量（邮件模板的 {@code {$maccms.<键>}}）
 */
public record VerificationSettings(String smsContent, String emailTime, Map<String, String> emailTpl, Map<String, String> site) {

    /** 仓库值的主要部分（没有配置旧配置目录时用；{@code email.time=5}、默认的邮件模板）。 */
    public static final VerificationSettings REPOSITORY = new VerificationSettings("", "5", Map.of(), Map.of());

    public VerificationSettings {
        smsContent = smsContent == null ? "" : smsContent;
        emailTime = emailTime == null ? "" : emailTime;
        emailTpl = emailTpl == null ? Map.of() : Map.copyOf(emailTpl);
        site = site == null ? Map.of() : Map.copyOf(site);
    }

    /** @param maccms 旧配置 {@code extra/maccms.php}，没有配置旧配置目录时为 null */
    public static VerificationSettings from(PhpArray maccms) {
        if (maccms == null) {
            return REPOSITORY;
        }
        return new VerificationSettings(PhpValues.text(maccms.path("sms", "content")),
                PhpValues.text(maccms.path("email", "time")), scalars(maccms.path("email", "tpl")),
                scalars(maccms.get("site")));
    }

    private static Map<String, String> scalars(PhpValue v) {
        if (!(v instanceof PhpArray a)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<Object, PhpValue> e : a.entries().entrySet()) {
            if (!(e.getValue() instanceof PhpArray)) {
                out.put(String.valueOf(e.getKey()), PhpValues.text(e.getValue()));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** 验证码的有效期与发送间隔（秒）：邮箱且 {@code intval(email.time) > 0} 时为该分钟数，否则 5 分钟（旧 {@code User.php:611-614}）。 */
    long windowSeconds(boolean email) {
        long minutes = email ? PhpIntval.of(emailTime) : 0;
        return (minutes > 0 ? minutes : 5) * 60;
    }

    String emailTemplate(String key) {
        return emailTpl.getOrDefault(key, "");
    }

    @Override
    public String toString() {
        return "VerificationSettings[***]";
    }
}
