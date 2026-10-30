package com.maccms.backend.common.write;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 可写数据源打开时才注册（与 {@link WriteDataSourceConfiguration#writeDatabase} 同一条件，B09 起供各模块的写 Mapper 装配使用）。
 * 按属性判定，不依赖配置类的处理顺序；{@code maccms.write} 配置名只出现在 common.write（WriteGuardTest）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
@ConditionalOnProperty(prefix = "maccms.write", name = "enabled", havingValue = "true")
public @interface ConditionalOnWriteEnabled {
}
