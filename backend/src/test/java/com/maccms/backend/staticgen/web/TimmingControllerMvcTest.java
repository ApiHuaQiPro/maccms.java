package com.maccms.backend.staticgen.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.common.clientip.ClientIpConfiguration;
import com.maccms.backend.common.clientip.ClientIpResolver;
import com.maccms.backend.legacyconfig.application.LegacyConfigConfiguration;
import com.maccms.backend.staticgen.config.StaticgenConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 定时任务入口的部署级鉴权（D2 ③，路线图第 5.5 节验收）：非回环地址、无口令的请求（含 {@code enforce=1}）→ 403 且 {@code timming.php}
 * 逐字节不变；回环地址但带转发头（对端不是可信代理）→ 同样拒绝；本机请求或口令正确 → 执行并回写 runtime；口令不出现在输出与日志中。
 */
@WebMvcTest(controllers = TimmingController.class, properties = {
        "maccms.timming.enabled=true",
        "maccms.timming.token=" + TimmingControllerMvcTest.TOKEN})
@Import({StaticgenConfiguration.class, LegacyConfigConfiguration.class, ClientIpConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class TimmingControllerMvcTest {

    static final String TOKEN = "b16-synthetic-token-0123456789abcdefABCDEF";
    static final Path ROOT = createRoot();
    static final Path FILE = ROOT.resolve("application/extra/timming.php");
    static final String SOURCE = """
            <?php
            return array (
              'cc' =>\s
              array (
                'id' => 'cc',
                'status' => '1',
                'name' => 'cc',
                'des' => '推送',
                'file' => 'urlsend',
                'param' => '',
                'weeks' => '1,2,3,4,5,6,0',
                'hours' => '00,01,02,03,04,05,06,07,08,09,10,11,12,13,14,15,16,17,18,19,20,21,22,23',
                'runtime' => 0,
              ),
              'dd' =>\s
              array (
                'id' => 'dd',
                'status' => '0',
                'name' => 'dd',
                'des' => '关闭的任务',
                'file' => 'cache',
                'param' => '',
                'weeks' => '1',
                'hours' => '00',
                'runtime' => 0,
              ),
            );""";

    @Autowired
    MockMvc mvc;

    private static Path createRoot() {
        try {
            Path r = Files.createTempDirectory("maccms-b16-timming");
            Files.createDirectories(r.resolve("application/extra"));
            return r;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("maccms.legacy-config.dir", () -> ROOT.resolve("application").toString());
    }

    @BeforeEach
    void reset() throws IOException {
        Files.writeString(FILE, SOURCE, StandardCharsets.UTF_8);
    }

    private MvcResult call(MockHttpServletRequestBuilder b, String remote) throws Exception {
        return mvc.perform(b.with(req -> {
            req.setRemoteAddr(remote);
            return req;
        })).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void assertRefusedAndUnchanged(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(r)).isEqualTo(TimmingController.REFUSED);
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(Files.readString(FILE, StandardCharsets.UTF_8)).isEqualTo(SOURCE);
    }

    @Test
    void remoteRequestsWithoutTokenAreRefusedEvenWithEnforce() throws Exception {
        assertRefusedAndUnchanged(call(get("/api.php/timming/index"), "203.0.113.9"));
        assertRefusedAndUnchanged(call(get("/api.php/timming/index.html?enforce=1&name=cc"), "203.0.113.9"));
        assertRefusedAndUnchanged(call(get("/api.php/timming/index?enforce=1"), "2001:db8::1"));
        // 口令放在查询串里不认
        assertRefusedAndUnchanged(call(get("/api.php/timming/index?enforce=1&token=" + TOKEN), "203.0.113.9"));
        // 口令不对
        assertRefusedAndUnchanged(call(get("/api.php/timming/index?enforce=1")
                .header(TimmingAccess.TOKEN_HEADER, TOKEN + "x"), "203.0.113.9"));
    }

    @Test
    void loopbackPeerWithForwardingHeadersFromUntrustedProxyIsRefused() throws Exception {
        // 本机 nginx 转发公网请求但没有登记为可信代理：对端是 127.0.0.1，转发头说明请求来自别处
        assertRefusedAndUnchanged(call(get("/api.php/timming/index?enforce=1")
                .header("X-Forwarded-For", "203.0.113.9"), "127.0.0.1"));
        assertRefusedAndUnchanged(call(get("/api.php/timming/index?enforce=1")
                .header("X-Forwarded-For", "127.0.0.1"), "127.0.0.1"));
        assertRefusedAndUnchanged(call(get("/api.php/timming/index").header("X-Real-IP", "127.0.0.1"), "127.0.0.1"));
    }

    @Test
    void loopbackRequestRunsTheFirstDueTaskAndWritesRuntime() throws Exception {
        long before = System.currentTimeMillis() / 1000;
        MvcResult r = call(get("/api.php/timming/index"), "127.0.0.1");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).isEqualTo("text/html;charset=UTF-8");
        assertThat(body(r)).isEqualTo("任务：cc，状态：开启，上次执行时间：从未---执行<br>推送任务由后续批次（B28）提供，本次未执行<br>");
        String after = Files.readString(FILE, StandardCharsets.UTF_8);
        assertThat(after).doesNotContain("\r").contains("'runtime' => ");
        long written = Long.parseLong(after.replaceAll("(?s).*'cc' => \n  array \\(.*?'runtime' => (\\d+),.*", "$1"));
        assertThat(written).isBetween(before, before + 60);
        // 同一小时再请求：cc 跳过，dd 关闭跳过，不再回写
        MvcResult again = call(get("/api.php/timming/index"), "::1");
        assertThat(body(again)).startsWith("任务：cc，状态：开启，上次执行时间：").contains("---跳过<br>任务：dd，状态：关闭，上次执行时间：从未---跳过<br>");
        assertThat(Files.readString(FILE, StandardCharsets.UTF_8)).isEqualTo(after);
    }

    @Test
    void tokenAllowsRemoteTriggerAndNeverLeaks(CapturedOutput output) throws Exception {
        MvcResult r = call(get("/api.php/timming/index?enforce=1&name=dd").header(TimmingAccess.TOKEN_HEADER, TOKEN),
                "203.0.113.9");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        // dd 是关闭的任务：enforce 也不执行（status 先于 enforce 判断，照旧）
        assertThat(body(r)).isEqualTo("任务：dd，状态：关闭，上次执行时间：从未---跳过<br>");
        MvcResult cc = call(get("/api.php/timming/index?enforce=1&name=cc").header(TimmingAccess.TOKEN_HEADER, TOKEN),
                "203.0.113.9");
        assertThat(body(cc)).contains("---执行<br>");
        assertThat(body(r) + body(cc)).doesNotContain(TOKEN);
        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    @Test
    void invalidTokenConfigurationFailsWithoutEchoingIt() {
        assertThatThrownBy(() -> new TimmingAccess(ClientIpResolver.direct(), "short-token"))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("short-token");
        assertThatThrownBy(() -> new TimmingAccess(ClientIpResolver.direct(), "0123456789abcdef 0123456789abcdef"))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("0123456789abcdef");
        assertThat(new TimmingAccess(ClientIpResolver.direct(), "").tokenConfigured()).isFalse();
    }

    @Test
    void parametersAreTrimmedThenUrldecodedLikeInput() {
        assertThat(TimmingController.urldecode("a%2Bb+c%zz%4".getBytes(StandardCharsets.ISO_8859_1))).isEqualTo("a+b c%zz%4");
    }
}
