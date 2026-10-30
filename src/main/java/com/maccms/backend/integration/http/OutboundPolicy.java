package com.maccms.backend.integration.http;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 出站目标地址检查（B23，路线图第 4.3 节 ④；26 文档第 3.2 节）：每次请求与每次跳转都检查一次。
 *
 * <ul>
 * <li>只允许 {@code http}/{@code https}，必须有主机，不允许 URL 里带用户名密码（凭证不进 URL）；</li>
 * <li>{@link Mode#LOOPBACK_ONLY}（测试配置）下主机只能是 {@code localhost}、{@code 127.x.x.x} 或 {@code [::1]}，
 * 判断只看字面，不做 DNS 查询。</li>
 * </ul>
 */
public final class OutboundPolicy {

    /** 出站模式（{@code maccms.integration.outbound.mode}）。 */
    public enum Mode {
        /** 允许任意外部主机（生产；仍做上面的格式检查）。 */
        OPEN("open"),
        /** 只允许回环地址（测试与本机模拟服务联调）。 */
        LOOPBACK_ONLY("loopback-only");

        private final String value;

        Mode(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        /** 解析配置值；留空为 {@link #OPEN}，其他值启动失败。 */
        public static Mode parse(String s) {
            String v = s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
            if (v.isEmpty()) {
                return OPEN;
            }
            for (Mode m : values()) {
                if (m.value.equals(v)) {
                    return m;
                }
            }
            throw new IllegalStateException("maccms.integration.outbound.mode 只能是 open 或 loopback-only");
        }
    }

    private static final Pattern IPV4_LOOPBACK = Pattern.compile("127\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    private final Mode mode;

    public OutboundPolicy(Mode mode) {
        this.mode = mode;
    }

    public Mode mode() {
        return mode;
    }

    /**
     * 检查目标地址，返回用于日志的 {@code host[:port]}（不含路径、查询串与凭证）。
     *
     * @throws OutboundException {@link OutboundException.Reason#TARGET_REJECTED}
     */
    public String check(URI uri) {
        String host = uri == null ? null : uri.getHost();
        String scheme = uri == null || uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (host == null || host.isEmpty() || !(scheme.equals("http") || scheme.equals("https")) || uri.isOpaque()) {
            throw new OutboundException(OutboundException.Reason.TARGET_REJECTED, "");
        }
        String target = uri.getPort() < 0 ? host : host + ":" + uri.getPort();
        if (uri.getRawUserInfo() != null) {
            throw new OutboundException(OutboundException.Reason.TARGET_REJECTED, target);
        }
        if (mode == Mode.LOOPBACK_ONLY && !isLoopbackLiteral(host)) {
            throw new OutboundException(OutboundException.Reason.TARGET_REJECTED, target);
        }
        return target;
    }

    /** {@code localhost}、{@code 127.0.0.0/8} 的点分十进制字面、{@code [::1]}/{@code ::1}。 */
    public static boolean isLoopbackLiteral(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.equals("[::1]") || h.equals("::1")) {
            return true;
        }
        var m = IPV4_LOOPBACK.matcher(h);
        if (!m.matches()) {
            return false;
        }
        for (int i = 1; i <= 3; i++) {
            if (Integer.parseInt(m.group(i)) > 255) {
                return false;
            }
        }
        return true;
    }
}
