package com.maccms.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.maccms.backend.admin.application.AdminSettings;
import com.maccms.backend.admin.application.LegacyInput;
import com.maccms.backend.admin.config.AdminConfiguration;
import com.maccms.backend.common.captcha.Captchas;
import com.maccms.backend.common.clientip.ClientIpConfiguration;
import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.content.application.ContentCacheMaintenance;
import com.maccms.backend.content.application.StaticSourceService;
import com.maccms.backend.content.application.view.StaticSourceTopic;
import com.maccms.backend.content.application.view.StaticSourceType;
import com.maccms.backend.staticgen.application.MakeOutput;
import com.maccms.backend.staticgen.application.MakeService;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * B16 后台生成静态（25 文档第 8 节）：{@code make/opt} 页面按旧模板的结构输出（下拉框、按钮、编号串，脚本改为 data 属性）；
 * {@code make/make} 把参数（多选数组按逗号连接）交给 {@link MakeService}（后台入口），输出它的内容并按旧版跳转（progress.js）或接着输出错误页；
 * {@code ref=1} 只跳回同源来源页。生成本身由 {@code MakeServiceTest}/{@code StaticTimmingMySqlIT} 覆盖，这里是替身。
 */
@WebMvcTest(controllers = AdminController.class, properties = {
        "maccms.admin.enabled=true",
        "maccms.admin.entry=/_admin.php",
        "maccms.admin.base-url=http://admin.local.test/"})
@Import({AdminConfiguration.class, ClientIpConfiguration.class, AdminMakeMvcTest.Fakes.class})
class AdminMakeMvcTest {

    static final String E = "/_admin.php";
    private static final Path SITE = copySite();

    private static Path copySite() {
        try {
            Path site = Files.createTempDirectory("maccms-admin-make-site");
            Path from = Path.of("..", "application");
            try (Stream<Path> s = Files.walk(from)) {
                for (Path p : s.toList()) {
                    Path to = site.resolve("application").resolve(from.relativize(p).toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(to);
                    } else {
                        Files.copy(p, to, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
            return site;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void legacyDir(DynamicPropertyRegistry r) {
        r.add("maccms.legacy-config.dir", () -> SITE.resolve("application").toString());
    }

    @AfterAll
    static void deleteSite() throws IOException {
        AdminSystemGoldenTest.deleteTree(SITE);
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        FakeAdminTable fakeAdminTable() {
            return new FakeAdminTable();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeAdminTable table;
    @Autowired
    AdminSessions sessions;
    @Autowired
    AdminSettings settings;
    @MockitoBean
    ContentCacheMaintenance contentCache;
    @MockitoBean
    MakeService make;
    @MockitoBean
    StaticSourceService source;

    @BeforeEach
    void seed() {
        table.rows.clear();
        table.writes.clear();
        table.put(1, "admin", LegacyInput.md5Hex("admin888".getBytes(StandardCharsets.UTF_8)), 1, "");
        when(source.types()).thenReturn(List.of(new StaticSourceType(1, "电影", "dy", 0, 1),
                new StaticSourceType(6, "动作<片>", "dz", 1, 1), new StaticSourceType(21, "资讯", "zx", 0, 2)));
        when(source.typeIdsSince(eq("vod"), anyLong())).thenReturn(List.of(6L));
        when(source.typeIdsSince(eq("art"), anyLong())).thenReturn(List.of());
        when(source.topics()).thenReturn(List.of(new StaticSourceTopic(7, "专题七", "zt7")));
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String sid(MvcResult r) {
        for (String h : r.getResponse().getHeaders("Set-Cookie")) {
            if (h.startsWith(AdminSessions.COOKIE + "=")) {
                return h.substring(AdminSessions.COOKIE.length() + 1, h.indexOf(';'));
            }
        }
        return null;
    }

    private String login() throws Exception {
        String pre = sid(mvc.perform(get(E + "/admin/index/verify.html")).andReturn());
        SessionState s = sessions.find(pre).orElseThrow();
        Deque<Integer> idx = new ArrayDeque<>();
        for (char c : "1234".toCharArray()) {
            idx.add(settings.captcha().codeSet().indexOf(c));
        }
        SplittableRandom rest = new SplittableRandom(1);
        Captchas.issue(s, Captchas.DEFAULT_SLOT, settings.captcha(), sessions.nowEpochSeconds(), new RandomGenerator() {
            @Override
            public long nextLong() {
                return rest.nextLong();
            }

            @Override
            public int nextInt(int bound) {
                return idx.isEmpty() ? rest.nextInt(bound) : idx.poll();
            }
        });
        MvcResult r = mvc.perform(post(E + "/admin/index/login.html").header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("admin_name=admin&admin_pwd=admin888&verify=1234").cookie(new Cookie(AdminSessions.COOKIE, pre)))
                .andReturn();
        assertThat(body(r)).contains("\"code\":1");
        return sid(r);
    }

    private MvcResult as(String sid, MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.cookie(new Cookie(AdminSessions.COOKIE, sid))).andReturn();
    }

    @Test
    void optPageKeepsTheLegacyFormWithDataAttributesInsteadOfInlineHandlers() throws Exception {
        MvcResult r = as(login(), get(E + "/admin/make/opt.html"));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Document d = Jsoup.parse(body(r));
        assertThat(d.title()).startsWith("生成静态管理");
        assertThat(d.select("form#form1.layui-form.layui-form-pane")).hasSize(1);
        assertThat(d.select("select[name=vodtype[]] option").eachAttr("value")).containsExactly("1", "6");
        assertThat(d.select("select[name=vodtype[]] option").eachText()).containsExactly("电影", "动作<片>");
        assertThat(d.select("select[name=arttype[]] option").eachAttr("value")).containsExactly("21");
        assertThat(d.select("select[name=topic[]] option").eachText()).containsExactly("专题七");
        assertThat(d.select("select[name=label[]] option")).isEmpty();
        assertThat(d.select("input[data-make]").eachAttr("data-make")).contains("ac=type&tab=vod&vodtype=1,6",
                "ac=type&tab=vod&vodtype=6&ac2=day", "ac=info&tab=art&arttype=21", "ac=topic_info&topic=7",
                "ac=rss&ac2=sm", "ac=label   ").hasSize(28);
        assertThat(d.select("[onclick]")).isEmpty();
        assertThat(d.select("script[src$=make-opt.js]").attr("data-cur-url")).isEqualTo("/_admin.php/admin/make/make.html");
        assertThat(d.select("input[name=ps]").attr("value")).isEqualTo("1");
    }

    @Test
    void makeRunsTheServiceAndJumpsLikeMacJump() throws Exception {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("ac", "type");
        q.put("start", "3");
        when(make.run(eq("make"), any(), eq(MakeService.Entrance.ADMIN), any()))
                .thenReturn(new MakeOutput("<font color=>x</font><br>", new MakeOutput.Jump("make/make", q, 3, null), null));
        String sid = login();
        MvcResult r = as(sid, post(E + "/admin/make/make.html?ac=type&tab=vod").header("Origin", "http://admin.local.test")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("vodtype%5B%5D=1&vodtype%5B%5D=6&ps=1"));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).isEqualTo("<font color=>x</font><br><script src=\"/admin-assets/progress.js\" "
                + "data-url=\"/_admin.php/admin/make/make.html?ac=type&amp;start=3\" data-seconds=\"3\"></script><span>暂停3秒后继续"
                + "  >>>  </span><a href=\"/_admin.php/admin/make/make.html?ac=type&amp;start=3\" >如果您的浏览器没有自动跳转，请点击这里</a><br>");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
        verify(make).run(eq("make"), params.capture(), eq(MakeService.Entrance.ADMIN), any());
        assertThat(params.getValue()).containsEntry("ac", "type").containsEntry("tab", "vod").containsEntry("vodtype", "1,6")
                .containsEntry("ps", "1");
    }

    @Test
    void errorsAppendTheJumpPageAndRefJumpsOnlyToSameOrigin() throws Exception {
        String sid = login();
        when(make.run(eq("label"), any(), any(), any())).thenReturn(new MakeOutput("", null, "参数错误2"));
        assertThat(body(as(sid, get(E + "/admin/make/label.html?label=../x")))).contains("参数错误2");
        when(make.run(eq("info"), any(), any(), any()))
                .thenReturn(new MakeOutput("", new MakeOutput.Jump(null, Map.of(), 2, "http://evil.test/x"), null));
        assertThat(body(as(sid, get(E + "/admin/make/info.html?ref=1"))))
                .contains("data-url=\"/_admin.php/admin/make/opt.html\"").doesNotContain("evil.test");
        when(make.run(eq("topic_info"), any(), any(), any())).thenReturn(new MakeOutput("", new MakeOutput.Jump(null, Map.of(), 2,
                "http://admin.local.test/_admin.php/admin/topic/index.html"), null));
        assertThat(body(as(sid, get(E + "/admin/make/topic_info.html?ref=1"))))
                .contains("data-url=\"http://admin.local.test/_admin.php/admin/topic/index.html\" data-seconds=\"2\"");
    }
}
