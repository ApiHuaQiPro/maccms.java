package com.maccms.backend.site.config;

import java.util.Locale;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * {@code maccms.site.enabled}：未设置或空 → 关闭；只接受 true / false（不区分大小写）。
 * 其他取值直接让启动失败，而不是悄悄当作关闭；错误信息不回显取值。
 */
public class SiteEnabledCondition implements Condition {

    public static final String PROPERTY = "maccms.site.enabled";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return isEnabled(context.getEnvironment().getProperty(PROPERTY));
    }

    static boolean isEnabled(String raw) {
        String v = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "", "false" -> false;
            case "true" -> true;
            default -> throw new IllegalStateException(PROPERTY + " 只能是 true 或 false");
        };
    }
}
