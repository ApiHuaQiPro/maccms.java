package com.maccms.backend.common.clientip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 客户端 IP 组件（B06，D2 ④，16 文档第 2 节）：伪造头、可信代理、无代理、IPv4/IPv6、多跳、多个头与无效输入。
 */
class ClientIpResolverTest {

    /** 旧 mac_get_client_ip 信任的全部请求头，外加 XFF 与 Forwarded。 */
    private static final List<String> SPOOFABLE = List.of("CF-Connecting-IP", "Ali-CDN-Real-IP", "Client-IP", "Proxy-User",
            "X-Real-IP", "X-Forwarded-For", "Forwarded", "True-Client-IP");

    private static ClientIpResolver resolver(String trusted, String header) {
        return ClientIpResolver.from(new ClientIpProperties(trusted, header));
    }

    private static Map<String, List<String>> headers(String... nameValue) {
        Map<String, List<String>> m = new HashMap<>();
        for (int i = 0; i < nameValue.length; i += 2) {
            m.computeIfAbsent(nameValue[i].toLowerCase(Locale.ROOT), k -> new java.util.ArrayList<>()).add(nameValue[i + 1]);
        }
        return m;
    }

    private static String resolve(ClientIpResolver r, String peer, Map<String, List<String>> h) {
        return r.resolve(peer, name -> h.getOrDefault(name.toLowerCase(Locale.ROOT), List.of()));
    }

    @Test
    void withoutTrustedProxiesEveryForgedHeaderIsIgnored() {
        ClientIpResolver r = resolver("", "");
        for (String name : SPOOFABLE) {
            assertThat(resolve(r, "203.0.113.7", headers(name, "127.0.0.1"))).as(name).isEqualTo("203.0.113.7");
        }
        assertThat(resolve(ClientIpResolver.direct(), "203.0.113.7", headers("X-Forwarded-For", "127.0.0.1")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    void untrustedPeerIgnoresHeadersEvenWhenProxiesAreConfigured() {
        ClientIpResolver r = resolver("10.0.0.0/8", "");
        for (String name : SPOOFABLE) {
            assertThat(resolve(r, "203.0.113.7", headers(name, "127.0.0.1"))).as(name).isEqualTo("203.0.113.7");
        }
    }

    @Test
    void trustedPeerUsesOnlyTheConfiguredHeader() {
        ClientIpResolver r = resolver("10.0.0.0/8", "");
        // 可信代理转发的 XFF 被采用；同时伪造的 CF-Connecting-IP 等不起作用
        assertThat(resolve(r, "10.1.2.3", headers("X-Forwarded-For", "198.51.100.9", "CF-Connecting-IP", "127.0.0.1",
                "X-Real-IP", "127.0.0.1"))).isEqualTo("198.51.100.9");
        // 没有该头时用对端
        assertThat(resolve(r, "10.1.2.3", headers("CF-Connecting-IP", "127.0.0.1"))).isEqualTo("10.1.2.3");
    }

    @Test
    void multipleHopsAreWalkedFromTheRightSkippingTrustedProxies() {
        ClientIpResolver r = resolver("10.0.0.0/8, 192.168.0.0/16", "");
        // 客户端伪造的最左项不被采用：最右边第一个非可信地址才是客户端
        assertThat(resolve(r, "10.0.0.1", headers("X-Forwarded-For", "127.0.0.1, 198.51.100.9, 192.168.1.1")))
                .isEqualTo("198.51.100.9");
        // 全部可信时取最左项
        assertThat(resolve(r, "10.0.0.1", headers("X-Forwarded-For", "192.168.1.5, 10.2.2.2"))).isEqualTo("192.168.1.5");
        // 同名头出现多次：按顺序以逗号连接
        assertThat(resolve(r, "10.0.0.1", headers("X-Forwarded-For", "127.0.0.1", "X-Forwarded-For", "198.51.100.9",
                "X-Forwarded-For", "10.3.3.3"))).isEqualTo("198.51.100.9");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // 头值 | 期望（对端 10.0.0.1，可信 10.0.0.0/8）
            "unknown | 10.0.0.1",
            "198.51.100.9, unknown | 10.0.0.1",
            "unknown, 10.2.2.2 | 10.2.2.2",
            "198.51.100.9:443 | 10.0.0.1",
            "[2001:db8::1] | 10.0.0.1",
            "example.test | 10.0.0.1",
            "198.51.100.9,, 10.2.2.2 | 10.2.2.2",
            "' ' | 10.0.0.1",
            "01.2.3.4 | 10.0.0.1",
            "256.1.1.1 | 10.0.0.1",
            "fe80::1%eth0 | 10.0.0.1",
            "1.2.3.4, 2001:DB8:0:0:0:0:0:1 | 2001:db8::1",
    })
    void invalidEntriesStopTheWalkAtTheNearestKnownHop(String header, String expected) {
        ClientIpResolver r = resolver("10.0.0.0/8", "X-Forwarded-For");
        assertThat(resolve(r, "10.0.0.1", headers("X-Forwarded-For", header.strip()))).isEqualTo(expected);
    }

    @Test
    void oversizedHeadersAreIgnored() {
        ClientIpResolver r = resolver("10.0.0.0/8", "");
        String many = String.join(",", java.util.Collections.nCopies(ClientIpResolver.MAX_HOPS + 1, "198.51.100.9"));
        assertThat(resolve(r, "10.0.0.1", headers("X-Forwarded-For", many))).isEqualTo("10.0.0.1");
        String longValue = "198.51.100.9" + " ".repeat(ClientIpResolver.MAX_HEADER_LENGTH);
        assertThat(resolve(r, "10.0.0.1", headers("X-Forwarded-For", longValue))).isEqualTo("10.0.0.1");
    }

    @Test
    void singleValueHeader() {
        ClientIpResolver r = resolver("127.0.0.1", "cf-connecting-ip");
        assertThat(r.headerName()).isEqualTo("cf-connecting-ip");
        assertThat(resolve(r, "127.0.0.1", headers("CF-Connecting-IP", " 198.51.100.9 "))).isEqualTo("198.51.100.9");
        // 只认配置的头：XFF 不看
        assertThat(resolve(r, "127.0.0.1", headers("X-Forwarded-For", "198.51.100.9"))).isEqualTo("127.0.0.1");
        // 出现多次、不合法、列表形式 → 对端
        assertThat(resolve(r, "127.0.0.1", headers("CF-Connecting-IP", "198.51.100.9", "CF-Connecting-IP", "1.1.1.1")))
                .isEqualTo("127.0.0.1");
        assertThat(resolve(r, "127.0.0.1", headers("CF-Connecting-IP", "198.51.100.9, 1.1.1.1"))).isEqualTo("127.0.0.1");
        assertThat(resolve(r, "127.0.0.1", headers("CF-Connecting-IP", "evil"))).isEqualTo("127.0.0.1");
    }

    @Test
    void ipv6PeersAndProxies() {
        ClientIpResolver r = resolver("::1/128, 2001:db8:100::/48", "");
        assertThat(resolve(r, "0:0:0:0:0:0:0:1", headers("X-Forwarded-For", "2001:DB8::abcd"))).isEqualTo("2001:db8::abcd");
        assertThat(resolve(r, "2001:db8:100::5", headers("X-Forwarded-For", "203.0.113.4"))).isEqualTo("203.0.113.4");
        assertThat(resolve(r, "2001:db8:200::5", headers("X-Forwarded-For", "203.0.113.4"))).isEqualTo("2001:db8:200::5");
        // IPv4 映射的 IPv6 对端归一为 IPv4，并按 IPv4 网段判定
        ClientIpResolver v4 = resolver("127.0.0.0/8", "");
        assertThat(resolve(v4, "::ffff:127.0.0.1", headers("X-Forwarded-For", "198.51.100.9"))).isEqualTo("198.51.100.9");
        assertThat(resolve(v4, "::ffff:203.0.113.1", headers())).isEqualTo("203.0.113.1");
        // IPv4 网段不包含 IPv6 地址
        assertThat(resolve(v4, "::1", headers("X-Forwarded-For", "198.51.100.9"))).isEqualTo("::1");
    }

    @Test
    void unparsablePeerYieldsUnknown() {
        ClientIpResolver r = ClientIpResolver.direct();
        assertThat(resolve(r, null, headers())).isEqualTo(ClientIpResolver.UNKNOWN);
        assertThat(resolve(r, "", headers())).isEqualTo(ClientIpResolver.UNKNOWN);
        assertThat(resolve(r, "localhost", headers("X-Forwarded-For", "1.2.3.4"))).isEqualTo(ClientIpResolver.UNKNOWN);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "1.2.3.4 | 1.2.3.4",
            "0.0.0.0 | 0.0.0.0",
            "255.255.255.255 | 255.255.255.255",
            "::1 | ::1",
            ":: | ::",
            "2001:0DB8:0000:0000:0000:0000:0000:0001 | 2001:db8::1",
            "2001:db8:0:1:0:0:0:1 | 2001:db8:0:1::1",
            "2001:db8:0:0:1:0:0:1 | 2001:db8::1:0:0:1",
            "2001:db8:1:1:1:1:0:1 | 2001:db8:1:1:1:1:0:1",
            "1:2:3:4:5:6:7:: | 1:2:3:4:5:6:7:0",
            "::ffff:1.2.3.4 | 1.2.3.4",
            "::ffff:0102:0304 | 1.2.3.4",
            "64:ff9b::1.2.3.4 | 64:ff9b::102:304",
    })
    void canonicalForms(String input, String expected) {
        assertThat(IpAddress.parse(input)).hasValueSatisfying(a -> assertThat(a.toString()).isEqualTo(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.2.3", "1.2.3.4.5", "1.2.3.04", "1.2.3.256", "+1.2.3.4", " 1.2.3.4", "1.2.3.4 ", "a.b.c.d",
            "1:2:3:4:5:6:7:8:9", "1::2::3", ":1", "1:", "12345::", "::g", "fe80::1%1", "[::1]", "1.2.3.4:80", "1.2.3.4/32",
            "::1.2.3", "1.2.3.4::", "localhost", "0x7f.0.0.1", "2130706433"})
    void invalidLiterals(String input) {
        assertThat(IpAddress.parse(input)).isEmpty();
    }

    @Test
    void networks() {
        assertThat(IpNetwork.parse("10.0.0.0/8").orElseThrow().contains(IpAddress.parse("10.255.0.1").orElseThrow())).isTrue();
        assertThat(IpNetwork.parse("10.0.0.0/8").orElseThrow().contains(IpAddress.parse("11.0.0.1").orElseThrow())).isFalse();
        assertThat(IpNetwork.parse("0.0.0.0/0").orElseThrow().contains(IpAddress.parse("8.8.8.8").orElseThrow())).isTrue();
        assertThat(IpNetwork.parse("::ffff:10.0.0.0/104")).hasValue(IpNetwork.parse("10.0.0.0/8").orElseThrow());
        for (String bad : List.of("10.0.0.1/8", "10.0.0.0/33", "10.0.0.0/", "10.0.0.0/08", "::1/129", "::ffff:10.0.0.0/95",
                "10.0.0.0/-1", "x/8")) {
            assertThat(IpNetwork.parse(bad)).as(bad).isEmpty();
        }
    }

    @Test
    void invalidConfigurationFailsWithoutEchoingValues() {
        assertThatThrownBy(() -> resolver("10.0.0.0/8, 10.0.0.1/8", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MACCMS_TRUSTED_PROXIES").hasMessageContaining("第 2 项")
                .hasMessageNotContaining("10.0.0.1");
        assertThatThrownBy(() -> resolver("secret-host.example", "")).hasMessageNotContaining("secret-host");
        assertThatThrownBy(() -> resolver("", "X-Evil")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MACCMS_CLIENT_IP_HEADER").hasMessageNotContaining("X-Evil");
        assertThatThrownBy(() -> resolver(String.join(",", java.util.Collections.nCopies(65, "10.0.0.1")), ""))
                .hasMessageContaining("最多");
        assertThat(resolver(" , ".substring(0, 0), "").trustedProxyCount()).isZero();
        assertThat(resolver("127.0.0.1, ::1", "x-forwarded-for").trustedProxyCount()).isEqualTo(2);
    }
}
