package com.maccms.backend.integration.testing;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 测试 JVM 的名称解析（{@link TestOutboundGuard} 的第二道）：经 {@code META-INF/services/java.net.spi.InetAddressResolverProvider}
 * 在第一次解析时由 JDK 装入。
 * <ul>
 * <li>{@code localhost}、{@code *.localhost}：交给 JDK 内置解析（hosts 文件），结果必须全是回环地址；</li>
 * <li>不带点的单段名称（本机名，{@code InetAddress.getLocalHost()} 会用到）：交给内置解析，结果必须全是本机网卡上的地址；</li>
 * <li>其他名称：不查询，直接失败并登记违规。</li>
 * </ul>
 * 反向解析只对回环地址交给内置解析，其余直接失败（不登记：反查不建立连接）。
 */
public final class LoopbackOnlyResolverProvider extends InetAddressResolverProvider {

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                String h = host.toLowerCase(Locale.ROOT);
                if (h.equals("localhost") || h.endsWith(".localhost")) {
                    List<InetAddress> out = builtin.lookupByName(host, policy).toList();
                    if (out.stream().allMatch(InetAddress::isLoopbackAddress)) {
                        return out.stream();
                    }
                } else if (!h.contains(".") && !h.contains(":")) {
                    List<InetAddress> out = builtin.lookupByName(host, policy).toList();
                    if (out.stream().allMatch(LoopbackOnlyResolverProvider::isLocal)) {
                        return out.stream();
                    }
                }
                TestOutboundGuard.record("解析外部主机名 " + host);
                throw new UnknownHostException("测试期出站护栏：不解析外部主机名 " + host);
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                InetAddress a = InetAddress.getByAddress(addr);
                if (a.isLoopbackAddress()) {
                    return builtin.lookupByAddress(addr);
                }
                throw new UnknownHostException("测试期出站护栏：不反查非回环地址");
            }
        };
    }

    @Override
    public String name() {
        return "maccms-test-loopback-only";
    }

    private static boolean isLocal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress()) {
            return true;
        }
        try {
            return NetworkInterface.getByInetAddress(a) != null;
        } catch (SocketException e) {
            return false;
        }
    }
}
