package com.maccms.backend.integration.http;

/**
 * 出站请求失败（B23）。信息只含原因与目标主机（{@code host[:port]}），不含路径、查询串、请求头、请求体与响应内容；
 * cause 只保留类型链与栈帧的用途，写日志一律经 {@code SafeExceptionLog}。
 */
public class OutboundException extends RuntimeException {

    /** 失败原因。 */
    public enum Reason {
        /** 调用方所在的包没有登记（{@code IntegrationPackages.REGISTERED}）。 */
        CALLER_NOT_REGISTERED,
        /** 目标地址不合规：协议不是 http/https、缺主机、带用户名密码，或测试配置下不是回环地址。 */
        TARGET_REJECTED,
        /** 连接失败。 */
        CONNECT_FAILED,
        /** 连接或整体读取超时。 */
        TIMEOUT,
        /** 响应体超过上限。 */
        RESPONSE_TOO_LARGE,
        /** 跳转次数超过上限或跳转地址无效。 */
        REDIRECT_FAILED,
        /** 其他读写错误。 */
        IO_ERROR,
        /** 等待期间线程被中断。 */
        INTERRUPTED
    }

    private final Reason reason;
    private final String host;

    public OutboundException(Reason reason, String host) {
        this(reason, host, null);
    }

    public OutboundException(Reason reason, String host, Throwable cause) {
        super(reason + (host == null || host.isEmpty() ? "" : " host=" + host), cause);
        this.reason = reason;
        this.host = host == null ? "" : host;
    }

    public Reason reason() {
        return reason;
    }

    /** 目标主机（{@code host[:port]}），未解析出时为空串。 */
    public String host() {
        return host;
    }
}
