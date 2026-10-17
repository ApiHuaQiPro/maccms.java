package com.maccms.backend.common.clientip;

import java.util.Optional;

/**
 * 网段（CIDR）或单个地址（B06，16 文档第 2.1 节）：{@code 10.0.0.0/8}、{@code ::1/128}、{@code 127.0.0.1}（等于 /32）。
 * 前缀长度越界、主机位非零（如 {@code 10.0.0.1/8}）不合法。IPv4 映射的 IPv6 网段（前缀 ≥ 96）归一为 IPv4 网段，与
 * {@link IpAddress} 的归一一致；IPv4 网段不包含 IPv6 地址，反之亦然。
 */
public record IpNetwork(IpAddress base, int prefix) {

    public static Optional<IpNetwork> parse(String s) {
        if (s == null) {
            return Optional.empty();
        }
        int slash = s.indexOf('/');
        String addr = slash < 0 ? s : s.substring(0, slash);
        boolean mapped = addr.indexOf(':') >= 0;
        Optional<IpAddress> base = IpAddress.parse(addr);
        if (base.isEmpty()) {
            return Optional.empty();
        }
        IpAddress a = base.get();
        int prefix;
        if (slash < 0) {
            prefix = a.bitLength();
        } else {
            String p = s.substring(slash + 1);
            if (p.isEmpty() || p.length() > 3 || !p.chars().allMatch(c -> c >= '0' && c <= '9')
                    || (p.length() > 1 && p.charAt(0) == '0')) {
                return Optional.empty();
            }
            prefix = Integer.parseInt(p);
            if (a.isV4() && mapped) {
                // ::ffff:a.b.c.d/n：IPv6 前缀，换成 IPv4 前缀
                if (prefix < 96 || prefix > 128) {
                    return Optional.empty();
                }
                prefix -= 96;
            } else if (prefix > a.bitLength()) {
                return Optional.empty();
            }
        }
        for (int i = prefix; i < a.bitLength(); i++) {
            if (a.bit(i) != 0) {
                return Optional.empty();
            }
        }
        return Optional.of(new IpNetwork(a, prefix));
    }

    public boolean contains(IpAddress address) {
        if (address == null || address.isV4() != base.isV4()) {
            return false;
        }
        for (int i = 0; i < prefix; i++) {
            if (address.bit(i) != base.bit(i)) {
                return false;
            }
        }
        return true;
    }
}
