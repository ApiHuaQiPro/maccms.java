package com.maccms.backend.site.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * site 的每个 Spring 组件（Controller、ControllerAdvice、配置类）都必须带这个注解：
 * {@code maccms.site.enabled} 不为 true 时一个都不注册，应用保持"只提供 API"的运行方式。
 * SiteDisabledTest 检查关闭时没有任何 site 组件与页面路由。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(SiteEnabledCondition.class)
public @interface ConditionalOnSiteEnabled {
}
