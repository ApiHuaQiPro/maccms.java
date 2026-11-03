package com.maccms.backend.integration.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.apache.hc.client5.http.DnsResolver;

/**
 * 一跳专用的名称解析（APPK-2 OUT-1-5，DEC-14 / NET-7 ③）：只对这一跳的主机返回 {@link OutboundPolicy#check} 已检查过的地址，
 * 其他名字一律 {@link UnknownHostException}。不调用系统 DNS，所以检查与建连之间不存在重新解析（DNS 重绑定）的窗口。
 * 主机名按 {@link OutboundPolicy#normalizeHost} 的规则比较（大小写、IPv6 方括号、末尾点）。
 */
final class PinnedDnsResolver implements DnsResolver {

    private final String host;
    private final List<InetAddress> addresses;

    PinnedDnsResolver(String host, List<InetAddress> addresses) {
        this.host = OutboundPolicy.normalizeHost(host);
        this.addresses = List.copyOf(addresses);
    }

    @Override
    public InetAddress[] resolve(String name) throws UnknownHostException {
        if (name != null && OutboundPolicy.normalizeHost(name).equals(host)) {
            return addresses.toArray(new InetAddress[0]);
        }
        // 信息里不带名字：异常日志只记类型链，这里也不留主机名
        throw new UnknownHostException("host is not pinned for this hop");
    }

    @Override
    public String resolveCanonicalHostname(String name) {
        return name;
    }
}
