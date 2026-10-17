package com.maccms.backend.common.clientip;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 客户端 IP 解析（B06，D2 ④，docs/architecture/16-b06-misc-pages-provide.md 第 2 节）。取代旧 {@code mac_get_client_ip}
 * （application/common.php:950-990：依次信任 CF-Connecting-IP、Ali-CDN-Real-IP、Client-IP、Proxy-User、X-Real-IP，任何访问者都能伪造）。
 *
 * <ol>
 * <li>默认只用连接对端。对端不能解析为 IP 字面量时结果为 {@value #UNKNOWN}。</li>
 * <li>对端不在可信网段内（含未配置可信代理）时结果就是对端，任何请求头都不看。</li>
 * <li>对端在可信网段内时只看配置的一个头：{@code X-Forwarded-For} 按列表处理（多次出现按顺序以逗号连接，从右往左跳过可信代理，
 * 第一个非可信的合法地址即客户端；遇到不能解析的项立即停止，取它右边最近的一跳；全部可信时取最左项；超过 {@value #MAX_HOPS} 项或
 * {@value #MAX_HEADER_LENGTH} 字节整个头无效）；单值头恰好出现一次且整值合法时采用，否则用对端。</li>
 * </ol>
 * 结果是 {@link IpAddress} 的规范形式。本类不记录任何地址。
 */
public final class ClientIpResolver {

    /** 没有合法地址时的结果（与旧版 {@code mac_get_client_ip} 的兜底值相同）。 */
    public static final String UNKNOWN = "0.0.0.0";
    public static final String DEFAULT_HEADER = "X-Forwarded-For";
    static final int MAX_PROXIES = 64;
    static final int MAX_HOPS = 32;
    static final int MAX_HEADER_LENGTH = 2048;
    /** 可配置的单值头（不区分大小写）。 */
    static final Set<String> SINGLE_VALUE_HEADERS = Set.of("x-real-ip", "cf-connecting-ip", "true-client-ip",
            "ali-cdn-real-ip");

    private final List<IpNetwork> trusted;
    private final String header;
    private final boolean list;

    private ClientIpResolver(List<IpNetwork> trusted, String header, boolean list) {
        this.trusted = List.copyOf(trusted);
        this.header = header;
        this.list = list;
    }

    /** 不信任任何代理。 */
    public static ClientIpResolver direct() {
        return new ClientIpResolver(List.of(), DEFAULT_HEADER, true);
    }

    /**
     * 校验部署参数。不合法时抛 {@link IllegalStateException}，信息只含参数名与项序号，不含取值。
     */
    public static ClientIpResolver from(ClientIpProperties p) {
        String raw = p == null || p.trustedProxies() == null ? "" : p.trustedProxies().strip();
        List<IpNetwork> nets = new ArrayList<>();
        if (!raw.isEmpty()) {
            String[] items = raw.split(",", -1);
            if (items.length > MAX_PROXIES) {
                throw new IllegalStateException("maccms.client-ip.trusted-proxies（MACCMS_TRUSTED_PROXIES）最多 "
                        + MAX_PROXIES + " 项", null);
            }
            for (int i = 0; i < items.length; i++) {
                Optional<IpNetwork> n = IpNetwork.parse(items[i].strip());
                if (n.isEmpty()) {
                    throw new IllegalStateException("maccms.client-ip.trusted-proxies（MACCMS_TRUSTED_PROXIES）第 " + (i + 1)
                            + " 项不是合法的 IP 地址或 CIDR（前缀长度在范围内、主机位为 0）", null);
                }
                nets.add(n.get());
            }
        }
        String h = p == null || p.header() == null ? "" : p.header().strip();
        if (h.isEmpty() || h.equalsIgnoreCase(DEFAULT_HEADER)) {
            return new ClientIpResolver(nets, DEFAULT_HEADER, true);
        }
        if (!SINGLE_VALUE_HEADERS.contains(h.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("maccms.client-ip.header（MACCMS_CLIENT_IP_HEADER）只能是 X-Forwarded-For、X-Real-IP、"
                    + "CF-Connecting-IP、True-Client-IP、Ali-CDN-Real-IP 之一", null);
        }
        return new ClientIpResolver(nets, h, false);
    }

    public int trustedProxyCount() {
        return trusted.size();
    }

    public String headerName() {
        return header;
    }

    public String resolve(HttpServletRequest request) {
        return resolve(request.getRemoteAddr(), name -> {
            Enumeration<String> e = request.getHeaders(name);
            return e == null ? List.of() : Collections.list(e);
        });
    }

    /**
     * @param peer    连接对端地址（Servlet {@code getRemoteAddr()}）
     * @param headers 按头名取该头的全部出现（顺序与请求中相同），没有时为空列表
     */
    public String resolve(String peer, Function<String, List<String>> headers) {
        Optional<IpAddress> p = IpAddress.parse(peer);
        if (p.isEmpty()) {
            return UNKNOWN;
        }
        IpAddress peerAddr = p.get();
        if (!isTrusted(peerAddr)) {
            return peerAddr.toString();
        }
        List<String> values = headers.apply(header);
        if (values == null || values.isEmpty()) {
            return peerAddr.toString();
        }
        return list ? fromList(values, peerAddr).toString() : fromSingle(values, peerAddr).toString();
    }

    private IpAddress fromSingle(List<String> values, IpAddress peer) {
        if (values.size() != 1 || values.get(0) == null || values.get(0).length() > MAX_HEADER_LENGTH) {
            return peer;
        }
        return IpAddress.parse(values.get(0).strip()).orElse(peer);
    }

    private IpAddress fromList(List<String> values, IpAddress peer) {
        int length = 0;
        for (String v : values) {
            length += v == null ? 0 : v.length() + 1;
        }
        if (length > MAX_HEADER_LENGTH + 1) {
            return peer;
        }
        String joined = String.join(",", values.stream().map(v -> v == null ? "" : v).toList());
        String[] hops = joined.split(",", -1);
        if (hops.length > MAX_HOPS) {
            return peer;
        }
        IpAddress nearest = peer;
        for (int i = hops.length - 1; i >= 0; i--) {
            Optional<IpAddress> hop = IpAddress.parse(hops[i].strip());
            if (hop.isEmpty()) {
                return nearest;
            }
            if (!isTrusted(hop.get())) {
                return hop.get();
            }
            nearest = hop.get();
        }
        return nearest;
    }

    private boolean isTrusted(IpAddress a) {
        for (IpNetwork n : trusted) {
            if (n.contains(a)) {
                return true;
            }
        }
        return false;
    }
}
