package com.maccms.backend.openapi.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * openapi 的每个 Spring 组件都带这个注解：{@code maccms.openapi.enabled} 不为 true 时一个都不注册，Provide 路由不存在
 * （交还 PHP，本地 404）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OpenApiEnabledCondition.class)
public @interface ConditionalOnOpenApiEnabled {
}
