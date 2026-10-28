package com.maccms.backend.integration.application;

/**
 * 第三方配置中的一个密钥值（B23）。{@link #toString()} 不输出内容，取值必须显式调用 {@link #reveal()}，
 * 拿到的明文只能用于计算签名或放进发往第三方的请求，不得写入日志、异常信息或页面（后台配置页的显示方式由各批按旧版处理）。
 */
public final class Secret {

    private static final Secret EMPTY = new Secret("");

    private final String value;

    private Secret(String value) {
        this.value = value;
    }

    static Secret of(String value) {
        return value == null || value.isEmpty() ? EMPTY : new Secret(value);
    }

    /** 明文（PHP {@code (string)} 后的值；未配置时为空串）。 */
    public String reveal() {
        return value;
    }

    /** 是否为空串（旧版 {@code empty()} 的判断由调用方按需要另做）。 */
    public boolean isEmpty() {
        return value.isEmpty();
    }

    @Override
    public String toString() {
        return value.isEmpty() ? "Secret[empty]" : "Secret[" + LogRedactor.MASK + "]";
    }
}
