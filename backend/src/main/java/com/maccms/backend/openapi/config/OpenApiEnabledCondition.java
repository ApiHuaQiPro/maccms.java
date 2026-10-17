package com.maccms.backend.openapi.config;

import java.util.Locale;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * {@code maccms.openapi.enabled}（B06，16 文档第 3.1 节）：未设置或空 → 关闭；只接受 true / false（不区分大小写）。
 * 其他取值让启动失败，而不是悄悄当作关闭；错误信息不回显取值。
 */
public class OpenApiEnabledCondition implements Condition {

    public static final String PROPERTY = "maccms.openapi.enabled";

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
