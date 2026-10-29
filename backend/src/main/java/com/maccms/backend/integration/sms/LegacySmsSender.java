package com.maccms.backend.integration.sms;

import com.maccms.backend.integration.application.SendResult;
import com.maccms.backend.integration.application.SmsSender;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import java.util.regex.Pattern;

/**
 * 旧 {@code mac_send_sms}（{@code application/common.php:379-403}）：前置检查与返回码照旧，再按 {@code ucfirst(sms.type)} 交给
 * {@link AliyunSms} 或 {@link QcloudSms}（旧版按类名自动加载 {@code common/extend/sms/<类>.php}，Linux 下文件名区分大小写，
 * 所以只有 {@code aliyun}/{@code Aliyun}、{@code qcloud}/{@code Qcloud} 能找到）。
 */
final class LegacySmsSender implements SmsSender {

    /** 旧版的正则（PCRE 默认：{@code $} 也匹配最后一个 {@code \n} 之前）。 */
    private static final Pattern PHONE = Pattern.compile("^1[345789][0-9]{9}$", Pattern.UNIX_LINES);

    private final ThirdPartyConfig config;
    private final AliyunSms aliyun;
    private final QcloudSms qcloud;

    LegacySmsSender(ThirdPartyConfig config, AliyunSms aliyun, QcloudSms qcloud) {
        this.config = config;
        this.aliyun = aliyun;
        this.qcloud = qcloud;
    }

    @Override
    public SendResult send(String to, String code, String typeFlag) {
        if (config.empty("sms", "type")) {
            return new SendResult(9005, "未配置短信发送服务");
        }
        if (to == null || !PHONE.matcher(to).find()) {
            return new SendResult(999, "手机号格式不正确");
        }
        if (phpEmpty(code)) {
            return new SendResult(998, "标题不能为空");
        }
        if (phpEmpty(typeFlag)) {
            return new SendResult(997, "模板编号不能为空");
        }
        return switch (ucfirst(config.text("sms", "type"))) {
            case "Aliyun" -> aliyun.submit(to, code, typeFlag);
            case "Qcloud" -> qcloud.submit(to, code, typeFlag);
            default -> new SendResult(991, "未找到该短信发送方式");
        };
    }

    static boolean phpEmpty(String s) {
        return s == null || s.isEmpty() || s.equals("0");
    }

    static String ucfirst(String s) {
        return s.isEmpty() || s.charAt(0) < 'a' || s.charAt(0) > 'z' ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
