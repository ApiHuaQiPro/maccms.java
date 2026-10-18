package com.maccms.backend.openapi.application;

import java.util.function.Supplier;

/**
 * Provide 旧配置的来源（B06）。生产中没有这个 Bean，用启动时读出的固定 {@link ProvideSettings}；测试注册它来按用例重放多组旧配置。
 */
@FunctionalInterface
public interface ProvideSettingsSource extends Supplier<ProvideSettings> {
}
