package com.maccms.backend.integration.http;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 出站目标地址检查（B23，路线图第 4.3 节 ④；26 文档第 3.2 节）：每次请求与每次跳转都检查一次。
 *
 * <ul>
 * <li>只允许 {@code http}/{@code https}，必须有主机，不允许 URL 里带用户名密码（凭证不进 URL）；</li>
 * <li>{@link Mode#LOOPBACK_ONLY}（测试配置）下主机只能是 {@code localhost}、{@code 127.x.x.x} 或 {@code [::1]}，
 * 判断只看字面，不做 DNS 查询。</li>
 * <li>{@link Mode#OPEN} 下另按 APPK-2 OUT-1（SSRF）检查：不允许 {@code #} 片段（OUT-1-1）；拒绝常见内部服务端口（OUT-1-2）；
 * 主机解析出的全部 A/AAAA 地址只要有一个落在禁止类别（回环、私网、链路本地含云元数据、CGNAT、保留与文档段、组播、
 * IPv4 映射/NAT64 的 IPv6 按内嵌 IPv4 再判）即整体拒绝，解析失败也拒绝（OUT-1-3、OUT-1-4）；十六进制、八进制、省略段等
 * 非常规 IPv4 写法一律拒绝。</li>
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

    /** 规范点分十进制 IPv4（每段 0～255、无前导零）。 */
    private static final Pattern IPV4_CANONICAL =
            Pattern.compile("(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
    /** 看起来是数字地址（只有数字与点，或某段是 0x 十六进制）：必须是规范点分十进制，否则拒绝。 */
    private static final Pattern IPV4_LIKE = Pattern.compile("[0-9.]+|(?:[^.]*\\.)*0x[0-9a-f]*(?:\\.[^.]*)*");
    /** OUT-1-2 拒绝的常见内部服务端口。 */
    static final Set<Integer> BLOCKED_PORTS = Set.of(0, 22, 23, 25, 3306, 5432, 6379, 11211, 27017);
    /** OUT-1-4 拒绝的主机名（另含 {@code *.localhost}）。 */
    static final Set<String> BLOCKED_NAMES = Set.of("localhost", "metadata.google.internal", "metadata");
    /** OUT-1-4 的 IPv4 禁止段：{起始地址, 前缀长度}。 */
    private static final List<int[]> BLOCKED_V4 = List.of(cidr(0, 0, 0, 0, 8), cidr(127, 0, 0, 0, 8), cidr(10, 0, 0, 0, 8),
            cidr(172, 16, 0, 0, 12), cidr(192, 168, 0, 0, 16), cidr(100, 64, 0, 0, 10), cidr(169, 254, 0, 0, 16),
            cidr(192, 0, 0, 0, 24), cidr(192, 0, 2, 0, 24), cidr(198, 18, 0, 0, 15), cidr(198, 51, 100, 0, 24),
            cidr(203, 0, 113, 0, 24), cidr(224, 0, 0, 0, 4), cidr(240, 0, 0, 0, 4));

    /** 主机名解析（OUT-1-3），测试可替换。 */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final Mode mode;
    private final Resolver resolver;

    public OutboundPolicy(Mode mode) {
        this(mode, InetAddress::getAllByName);
    }

    public OutboundPolicy(Mode mode, Resolver resolver) {
        this.mode = mode;
        this.resolver = resolver;
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
        if (uri.getRawFragment() != null) {
            throw new OutboundException(OutboundException.Reason.TARGET_REJECTED, target);
        }
        if (mode == Mode.LOOPBACK_ONLY) {
            if (!isLoopbackLiteral(host)) {
                throw new OutboundException(OutboundException.Reason.TARGET_REJECTED, target);
            }
            return target;
        }
        if (uri.getPort() >= 0 && (BLOCKED_PORTS.contains(uri.getPort()) || uri.getPort() > 65535)) {
            throw new OutboundException(OutboundException.Reason.TARGET_REJECTED, target);
        }
        for (InetAddress a : resolveChecked(host, target)) {
            if (forbidden(a)) {
                throw new OutboundException(OutboundException.Reason.TARGET_REJECTED, target);
            }
        }
        return target;
    }

    /** OUT-1-3：主机名禁止表、非常规数字写法，然后解析出全部地址（解析失败或无地址 → 拒绝）。 */
    private InetAddress[] resolveChecked(String host, String target) {
        String h = host.toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.isEmpty() || BLOCKED_NAMES.contains(h) || h.endsWith(".localhost")) {
            throw new OutboundException(OutboundException.Reason.TARGET_REJECTED, target);
        }
        boolean v6 = h.startsWith("[");
        if (!v6 && IPV4_LIKE.matcher(h).matches() && !IPV4_CANONICAL.matcher(h).matches()) {
            throw new OutboundException(OutboundException.Reason.TARGET_REJECTED, target);
        }
        InetAddress[] all;
        try {
            all = resolver.resolve(v6 ? h.substring(1, h.length() - 1) : h);
        } catch (UnknownHostException | RuntimeException e) {
            throw new OutboundException(OutboundException.Reason.TARGET_REJECTED, target);
        }
        if (all == null || all.length == 0) {
            throw new OutboundException(OutboundException.Reason.TARGET_REJECTED, target);
        }
        return all;
    }

    /** OUT-1-4：地址是否落在禁止类别（IPv6 中内嵌 IPv4 的形式按内嵌地址再判）。 */
    static boolean forbidden(InetAddress a) {
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            return forbiddenV4(b);
        }
        if (!(a instanceof Inet6Address) || b.length != 16) {
            return true;
        }
        int first = b[0] & 0xff;
        if ((first & 0xfe) == 0xfc || first == 0xff || (first == 0xfe && (b[1] & 0xc0) == 0x80)) {
            return true; // fc00::/7、ff00::/8、fe80::/10
        }
        boolean zero80 = true;
        for (int i = 0; i < 10; i++) {
            zero80 &= b[i] == 0;
        }
        if (zero80 && ((b[10] == 0 && b[11] == 0) || (b[10] == (byte) 0xff && b[11] == (byte) 0xff))) {
            // ::ffff:0:0/96 与 ::/96（含 ::、::1）：按内嵌 IPv4 再判（:: → 0.0.0.0、::1 → 0.0.0.1，都在 0.0.0.0/8）
            return forbiddenV4(new byte[] {b[12], b[13], b[14], b[15]});
        }
        if (b[0] == 0 && b[1] == 0x64 && b[2] == (byte) 0xff && b[3] == (byte) 0x9b) {
            boolean nat64 = true;
            for (int i = 4; i < 12; i++) {
                nat64 &= b[i] == 0;
            }
            if (nat64) {
                return forbiddenV4(new byte[] {b[12], b[13], b[14], b[15]}); // 64:ff9b::/96
            }
        }
        return false;
    }

    private static boolean forbiddenV4(byte[] b) {
        int v = ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
        if (v == 0xffffffff) {
            return true;
        }
        for (int[] c : BLOCKED_V4) {
            int mask = c[1] == 0 ? 0 : -1 << (32 - c[1]);
            if ((v & mask) == (c[0] & mask)) {
                return true;
            }
        }
        return false;
    }

    private static int[] cidr(int a, int b, int c, int d, int prefix) {
        return new int[] {(a << 24) | (b << 16) | (c << 8) | d, prefix};
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
