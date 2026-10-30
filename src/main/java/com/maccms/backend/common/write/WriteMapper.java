package com.maccms.backend.common.write;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记写 Mapper 接口（B07）。写 Mapper 只放在 {@code com.maccms.backend.<module>.infrastructure} 下，SQL 只写在
 * {@code src/main/resources/mapper-write/<module>/*.xml}，经 {@link WriteDatabase#mapper(Class)} 取得，绑定可写数据源。
 *
 * <p>不得同时标 MyBatis 的 {@code @Mapper}：那样会被自动扫描并绑定到只读连接池。规则由 {@code WriteGuardTest} 检查。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface WriteMapper {
}
