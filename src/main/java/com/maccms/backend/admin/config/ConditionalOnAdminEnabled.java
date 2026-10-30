package com.maccms.backend.admin.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * 后台的每个 Spring 组件（Controller、ControllerAdvice、配置类）都必须带这个注解：{@code maccms.admin.enabled} 不为 true 时一个都不注册，
 * 没有任何后台路由（AdminDisabledTest 检查）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(AdminEnabledCondition.class)
public @interface ConditionalOnAdminEnabled {
}
