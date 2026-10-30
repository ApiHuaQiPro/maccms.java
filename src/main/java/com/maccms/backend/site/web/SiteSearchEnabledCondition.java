package com.maccms.backend.site.web;

import com.maccms.backend.site.config.SiteEnabledCondition;
import java.util.Locale;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * {@code maccms.site.search.enabled}（09 方案第 6.4、7.1 节）：是否注册 SSR 搜索页路由，需同时开启
 * {@code maccms.site.enabled}。未设置或空 → 关闭；只接受 true / false（不区分大小写），其他取值让启动失败，错误信息不回显取值。
 * site 关闭时不检查本项（与 site 其他配置一样，关闭时不校验）。
 */
public class SiteSearchEnabledCondition implements Condition {

    public static final String PROPERTY = "maccms.site.search.enabled";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        if (!new SiteEnabledCondition().matches(context, metadata)) {
            return false;
        }
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
