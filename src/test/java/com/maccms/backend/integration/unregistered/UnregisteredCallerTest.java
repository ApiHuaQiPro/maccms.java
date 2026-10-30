package com.maccms.backend.integration.unregistered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.integration.application.IntegrationPackages;
import com.maccms.backend.integration.http.OutboundException;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundPolicy;
import com.maccms.backend.integration.http.OutboundRequest;
import com.maccms.backend.integration.testing.LoopbackMockServer;
import com.maccms.backend.integration.testing.LoopbackMockServer.MockResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 出站客户端的运行期调用方检查（B23 ①）：直接调用方所在的包没有登记时拒绝，不发出请求。
 * 本类所在的 {@code integration.unregistered} 不在登记表里；{@code integration.payx} 这类名字相近的包也不算已登记。
 */
class UnregisteredCallerTest {

    @Test
    void unregisteredCallerIsRejectedBeforeAnyRequest() {
        try (LoopbackMockServer mock = LoopbackMockServer.start()) {
            mock.stub("GET", "/x", MockResponse.text(200, "x"));
            for (Set<String> registered : List.of(Set.<String>of(), Set.of("integration.demo"), Set.of("integration.unreg"),
                    Set.copyOf(IntegrationPackages.REGISTERED.keySet()))) {
                OutboundHttpClient client = new OutboundHttpClient(new OutboundPolicy(OutboundPolicy.Mode.LOOPBACK_ONLY),
                        registered, Duration.ofSeconds(1), Duration.ofSeconds(2), 1024, List.of());
                assertThatThrownBy(() -> client.send(OutboundRequest.get("demo.x", mock.uri("/x")))).as(registered.toString())
                        .isInstanceOfSatisfying(OutboundException.class,
                                e -> assertThat(e.reason()).isEqualTo(OutboundException.Reason.CALLER_NOT_REGISTERED));
            }
            // 登记本包后同一调用通过
            OutboundHttpClient ok = new OutboundHttpClient(new OutboundPolicy(OutboundPolicy.Mode.LOOPBACK_ONLY),
                    Set.of("integration.unregistered"), Duration.ofSeconds(1), Duration.ofSeconds(2), 1024, List.of());
            assertThat(ok.send(OutboundRequest.get("demo.x", mock.uri("/x"))).status()).isEqualTo(200);
            assertThat(mock.requests()).hasSize(1);
        }
    }

    @Test
    void registryMatching() {
        Set<String> r = Set.of("integration.pay");
        assertThat(IntegrationPackages.isRegistered("integration.pay", r)).isTrue();
        assertThat(IntegrationPackages.isRegistered("integration.pay.alipay", r)).isTrue();
        assertThat(IntegrationPackages.isRegistered("integration.payx", r)).isFalse();
        assertThat(IntegrationPackages.isRegistered("integration", r)).isFalse();
        assertThat(IntegrationPackages.isRegistered(null, r)).isFalse();
        assertThat(IntegrationPackages.modulePackage("com.maccms.backend.integration.pay")).isEqualTo("integration.pay");
        assertThat(IntegrationPackages.modulePackage("com.maccms.backendx.integration.pay")).isNull();
        assertThat(IntegrationPackages.modulePackage("org.junit.Foo")).isNull();
    }
}
