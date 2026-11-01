package com.maccms.backend.integration.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** APPK-2 OUT-1-5（NET-7 ③，V-33 ⑦）：一跳专用解析器只认这一跳的主机，返回已检查地址的副本，不调用系统 DNS。 */
class PinnedDnsResolverTest {

    private static InetAddress ip(String name, int... b) throws UnknownHostException {
        byte[] bytes = new byte[b.length];
        for (int i = 0; i < b.length; i++) {
            bytes[i] = (byte) b[i];
        }
        return InetAddress.getByAddress(name, bytes);
    }

    @Test
    void returnsACopyOfThePinnedAddressesForThePinnedHostOnly() throws Exception {
        InetAddress a = ip("pin.invalid", 93, 184, 216, 34);
        InetAddress b = ip("pin.invalid", 93, 184, 216, 35);
        PinnedDnsResolver r = new PinnedDnsResolver("pin.invalid", List.of(a, b));

        InetAddress[] first = r.resolve("pin.invalid");
        assertThat(first).containsExactly(a, b);
        first[0] = null;
        assertThat(r.resolve("pin.invalid")).as("每次返回新的副本").containsExactly(a, b);
        // 与 OutboundPolicy.Target#host 同一规范化规则：大小写、末尾点
        assertThat(r.resolve("PIN.Invalid")).containsExactly(a, b);
        assertThat(r.resolve("pin.invalid.")).containsExactly(a, b);
        assertThat(r.resolveCanonicalHostname("pin.invalid")).isEqualTo("pin.invalid");

        for (String other : List.of("other.invalid", "pin.invalid.other", "xpin.invalid", "pin", "localhost", "127.0.0.1",
                "93.184.216.34", "")) {
            assertThatThrownBy(() -> r.resolve(other)).as(other).isInstanceOf(UnknownHostException.class);
        }
        assertThatThrownBy(() -> r.resolve("other.invalid")).hasMessageNotContaining("other.invalid");
        assertThatThrownBy(() -> r.resolve(null)).isInstanceOf(UnknownHostException.class);
        // 端口版本（引擎实际调用的入口）同样只走固定地址
        assertThat(r.resolve("pin.invalid", 8443)).extracting(s -> s.getAddress()).containsExactly(a, b);
        assertThatThrownBy(() -> r.resolve("other.invalid", 8443)).isInstanceOf(UnknownHostException.class);
    }

    @Test
    void ipv6HostIsComparedWithoutBrackets() throws Exception {
        InetAddress v6 = InetAddress.getByAddress("2606:2800:220:1:248:1893:25c8:1946",
                new byte[] {0x26, 0x06, 0x28, 0x00, 0x02, 0x20, 0x00, 0x01, 0x02, 0x48, 0x18, (byte) 0x93, 0x25, (byte) 0xc8,
                    0x19, 0x46});
        PinnedDnsResolver r = new PinnedDnsResolver("[2606:2800:220:1:248:1893:25c8:1946]", List.of(v6));
        assertThat(r.resolve("2606:2800:220:1:248:1893:25c8:1946")).containsExactly(v6);
    }

    /** V-33 ⑦ 的检索：解析器源码里不出现系统解析调用。 */
    @Test
    void sourceNeverCallsTheSystemResolver() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/maccms/backend/integration/http/PinnedDnsResolver.java"),
                StandardCharsets.UTF_8);
        assertThat(src).doesNotContain("getAllByName").doesNotContain("getByName");
    }
}
