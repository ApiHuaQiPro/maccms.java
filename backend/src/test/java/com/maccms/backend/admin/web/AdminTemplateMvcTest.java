package com.maccms.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.admin.application.LegacyInput;
import com.maccms.backend.admin.config.AdminConfiguration;
import com.maccms.backend.common.clientip.ClientIpConfiguration;
import com.maccms.backend.content.application.ContentCacheMaintenance;
import com.maccms.backend.sitetemplate.application.SiteTemplateService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 后台的 jte 模板在线编辑（路线图第 5.6 节，24 文档第 7 节）：开启在线编辑的应用上下文（启动时导出内置模板并整体编译），经后台的
 * template/info 保存、编译失败（提示模板名与行号、内容不写入、给新令牌）、并发冲突、恢复上一版、删除被引用的模板。
 * 静态文件与目录列表的旧版对照在 {@link AdminSystemGoldenTest}（template 场景）；编译与渲染在 SiteTemplateServiceTest。
 */
@WebMvcTest(controllers = AdminController.class, properties = {
        "maccms.admin.enabled=true",
        "maccms.admin.entry=/_admin.php",
        "maccms.admin.base-url=http://www.example.test/",
        "maccms.site-template.enabled=true"})
@Import({AdminConfiguration.class, ClientIpConfiguration.class, AdminTemplateMvcTest.Fakes.class})
class AdminTemplateMvcTest {

    private static final String E = "/_admin.php";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path SITE = site();
    private static final Path WORK = temp("maccms-b15-tpl-work");
    private static final String HOME = "template/default/html/site/pages/home.jte";
    private static final String INFO = "fpath=" + enc("./template/default/html/site/pages") + "&fname=home.jte";

    @TestConfiguration
    static class Fakes {
        @Bean
        FakeAdminTable fakeAdminTable() {
            FakeAdminTable t = new FakeAdminTable();
            t.put(1, "admin", LegacyInput.md5Hex("admin888".getBytes(StandardCharsets.UTF_8)), 1, "");
            return t;
        }
    }

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry r) {
        r.add("maccms.legacy-config.dir", () -> SITE.resolve("application").toString());
        r.add("maccms.site-template.work-dir", WORK::toString);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    SiteTemplateService templates;
    @MockitoBean
    ContentCacheMaintenance contentCache;

    private static Path site() {
        Path site = temp("maccms-b15-tpl-admin");
        try {
            AdminSystemGoldenTest.reset(site, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return site;
    }

    private static Path temp(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterAll
    static void cleanUp() throws IOException {
        AdminSystemGoldenTest.deleteTree(SITE);
        AdminSystemGoldenTest.deleteTree(WORK);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private MockHttpServletResponse send(String sid, String method, String pathAndQuery, String form) throws Exception {
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), URI.create(E + pathAndQuery))
                .header("X-Requested-With", "XMLHttpRequest");
        if (sid != null) {
            b.header("Cookie", "mac_admin_sid=" + sid);
        }
        if (form != null) {
            b.contentType("application/x-www-form-urlencoded").content(form.getBytes(StandardCharsets.UTF_8));
        }
        return mvc.perform(b).andReturn().getResponse();
    }

    private String login() throws Exception {
        MockHttpServletResponse r = send(null, "POST", "/admin/index/login", "admin_name=admin&admin_pwd=admin888");
        assertThat(r.getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":1");
        for (String h : r.getHeaders("Set-Cookie")) {
            if (h.startsWith("mac_admin_sid=")) {
                return h.substring("mac_admin_sid=".length(), h.indexOf(';'));
            }
        }
        throw new AssertionError("no session");
    }

    /** 编辑页（Ajax 取页面时是整页 HTML 的 JSON 字符串）。 */
    private String page(String sid, String pathAndQuery) throws Exception {
        return JSON.readValue(send(sid, "GET", pathAndQuery, null).getContentAsString(StandardCharsets.UTF_8), String.class);
    }

    private static String field(String html, String name) {
        Matcher m = Pattern.compile("name=\"" + name + "\" value=\"([^\"]*)\"").matcher(html);
        assertThat(m.find()).as(name).isTrue();
        return m.group(1);
    }

    private JsonNode post(String sid, String token, String version, String op, String content) throws Exception {
        String body = "__token__=" + token + "&fversion=" + version + "&op=" + op + "&fpath=" + enc("./template/default/html/site/pages")
                + "&fname=home.jte&fcontent=" + enc(content);
        return JSON.readTree(send(sid, "POST", "/admin/template/info?" + INFO, body).getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void editCompileRestoreAndConflictThroughTheAdmin() throws Exception {
        // 启动时已导出内置模板并以外部目录为准
        assertThat(templates.generation()).isPositive();
        assertThat(templates.notice()).isNull();
        String original = Files.readString(SITE.resolve(HOME), StandardCharsets.UTF_8);
        String sid = login();

        String html = page(sid, "/admin/template/info?" + INFO);
        assertThat(html).contains("@import 只能是内置模板用到的", "前台当前使用外部模板目录 ./template/default/html/");
        String token = field(html, "__token__");
        String version = field(html, "fversion");
        assertThat(version).hasSize(64);

        // ① 保存即生效（这里看编译版本号与文件；前台页面的现场演示见 24 文档第 7.6 节）
        long g = templates.generation();
        String edited = original + "<%-- 在线编辑 --%>\n";
        JsonNode ok = post(sid, token, version, "", edited);
        assertThat(ok.get("code").asInt()).isEqualTo(1);
        assertThat(ok.get("msg").asString()).isEqualTo("保存成功!");
        assertThat(templates.generation()).isGreaterThan(g);
        assertThat(Files.readString(SITE.resolve(HOME), StandardCharsets.UTF_8)).isEqualTo(edited);
        assertThat(Files.readString(SITE.resolve(SiteTemplateService.BACKUP_DIR + HOME), StandardCharsets.UTF_8)).isEqualTo(original);

        // ② 语法错误：不写入，提示模板名与行号，并给新的表单令牌（编辑页留在原处再保存）
        html = page(sid, "/admin/template/info?" + INFO);
        token = field(html, "__token__");
        version = field(html, "fversion");
        assertThat(html).contains("恢复上一版");
        g = templates.generation();
        JsonNode bad = post(sid, token, version, "", edited + "${page.noSuchMethod()}\n");
        assertThat(bad.get("code").asInt()).isZero();
        assertThat(bad.get("msg").asString()).contains("模板编译失败，未保存", "site/pages/home.jte 第");
        String renewed = bad.get("data").get("token").asString();
        assertThat(renewed).hasSize(32).isNotEqualTo(token);
        assertThat(templates.generation()).isEqualTo(g);
        assertThat(Files.readString(SITE.resolve(HOME), StandardCharsets.UTF_8)).isEqualTo(edited);

        // ④ 禁止的内容
        JsonNode evil = post(sid, renewed, version, "", "@import java.io.File\n" + edited);
        assertThat(evil.get("msg").asString()).startsWith("模板含有不允许的内容").contains("java.io.File");
        renewed = evil.get("data").get("token").asString();

        // ⑥ 编辑页打开以后文件被别人改了：后保存的一方收到冲突提示
        JsonNode stale = post(sid, renewed, "0".repeat(64), "", edited + "<%-- x --%>\n");
        assertThat(stale.get("msg").asString()).isEqualTo(SiteTemplateService.CONFLICT);
        renewed = stale.get("data").get("token").asString();

        // ③ 恢复上一版
        JsonNode restored = post(sid, renewed, version, "restore", "");
        assertThat(restored.get("code").asInt()).isEqualTo(1);
        assertThat(Files.readString(SITE.resolve(HOME), StandardCharsets.UTF_8)).isEqualTo(original);

        // 删除被引用的组件：拒绝；列表页说明前台用的是哪一版
        JsonNode del = JSON.readTree(send(sid, "GET", "/admin/template/del?fname="
                + enc("./template/default/html/site/components/pagination.jte"), null).getContentAsString(StandardCharsets.UTF_8));
        assertThat(del.get("code").asInt()).isZero();
        assertThat(del.get("msg").asString()).startsWith("删除后整套模板无法编译");
        assertThat(Files.exists(SITE.resolve("template/default/html/site/components/pagination.jte"))).isTrue();
        String list = page(sid, "/admin/template/index?current=1");
        assertThat(list).contains(">site<", "前台当前使用外部模板目录");
        // 启动正常时欢迎页没有提示
        assertThat(page(sid, "/admin/index/welcome")).doesNotContain("回退到内置模板");
        // 标签向导列出页面模板的参数与写法
        assertThat(page(sid, "/admin/template/wizard")).contains("@param HomePage page", "@for(var item : page.");
    }
}
