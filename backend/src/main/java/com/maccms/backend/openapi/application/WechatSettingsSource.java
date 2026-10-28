package com.maccms.backend.openapi.application;

import java.util.function.Supplier;

/**
 * 微信公众号接口旧配置的来源（B27）。生产中没有这个 Bean，用启动时读出的固定 {@link WechatSettings}；测试注册它来按用例重放多组旧配置。
 */
@FunctionalInterface
public interface WechatSettingsSource extends Supplier<WechatSettings> {
}
