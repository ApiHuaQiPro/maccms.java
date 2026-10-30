package com.maccms.backend.admin.web;

import java.util.random.RandomGenerator;

/** 后台的随机源（验证码、表单令牌）。包一层，避免以 {@link RandomGenerator} 类型注册 Bean（站点验证码按这个类型注入）。 */
public record AdminRandom(RandomGenerator generator) {
}
