package com.maccms.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.maccms.backend.admin.AdminHtml;
import com.maccms.backend.admin.application.AdminPermissions;
import com.maccms.backend.admin.application.AdminSettings;
import com.maccms.backend.admin.application.LegacyInput;
import com.maccms.backend.admin.config.AdminConfiguration;
import com.maccms.backend.common.captcha.Captchas;
import com.maccms.backend.common.clientip.ClientIpConfiguration;
import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.content.application.ContentCacheMaintenance;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * 后台入口的 HTTP 契约（21 文档第 4、8 节）：路由与旧地址写法、登录（验证码、会话轮换、cookie 属性）、D2 ⑤（停用/改密/删除/改权限
 * 在下一个请求生效）、auth.php 每个权限码对非超级管理员的拒绝、后续批次的提示、表单令牌、来源校验、响应头与 JSON 字节。
 * 库用内存替身 {@link FakeAdminTable}；与 PHP 黄金的逐项对照在 AdminGoldenMySqlIT。旧配置目录用仓库 application/ 的临时副本（登录要验证码；
 * B15 起后台的动作会写回配置文件与 static/js/playerconfig.js，不能指向仓库本身）。
 */
@WebMvcTest(controllers = AdminController.class, properties = {
        "maccms.admin.enabled=true",
        "maccms.admin.entry=/_admin.php",
        "maccms.admin.base-url=http://admin.local.test/"})
@Import({AdminConfiguration.class, ClientIpConfiguration.class, AdminMvcTest.Fakes.class})
class AdminMvcTest {

    static final String E = "/_admin.php";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path SITE = copySite();

    /** 仓库的 application/、static/js/、addons/ 复制到临时目录（站点根目录 = application 的上一级）。 */
    private static Path copySite() {
        try {
            Path site = Files.createTempDirectory("maccms-admin-mvc-site");
            for (String dir : List.of("application", "static/js", "addons")) {
                Path from = Path.of("php").resolve(dir);
                try (Stream<Path> s = Files.walk(from)) {
                    for (Path p : s.toList()) {
                        Path to = site.resolve(dir).resolve(from.relativize(p).toString());
                        if (Files.isDirectory(p)) {
                            Files.createDirectories(to);
                        } else {
                            Files.copy(p, to, StandardCopyOption.COPY_ATTRIBUTES);
                        }
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

    @BeforeEach
    void seed() {
        table.rows.clear();
        table.writes.clear();
        table.put(1, "admin", md5("admin888"), 1, "");
        table.put(2, "editor", md5("ed pass"), 1, ",vod/data,admin/index,index/unlocked,");
        table.put(3, "viewer", md5("viewer1"), 1, "");
    }

    private static String md5(String s) {
        return LegacyInput.md5Hex(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static MockHttpServletRequestBuilder ajax(MockHttpServletRequestBuilder b) {
        return b.header("X-Requested-With", "XMLHttpRequest");
    }

    private static MockHttpServletRequestBuilder form(String path, String raw) {
        return post(E + path).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(raw.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** 给会话写入已知的验证码（码表是仓库 captcha.php 的数字）。 */
    private void captcha(String sid, String code) {
        SessionState s = sessions.find(sid).orElseThrow();
        Deque<Integer> idx = new ArrayDeque<>();
        for (char c : code.toCharArray()) {
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
    }

    private static String sid(MvcResult r) {
        for (String h : r.getResponse().getHeaders("Set-Cookie")) {
            if (h.startsWith(AdminSessions.COOKIE + "=")) {
                return h.substring(AdminSessions.COOKIE.length() + 1, h.indexOf(';'));
            }
        }
        return null;
    }

    /** 登录（取验证码会话 → 写入已知码 → 提交），返回登录后的会话标识。 */
    private String login(String name, String pwd) throws Exception {
        MvcResult img = mvc.perform(get(E + "/admin/index/verify.html")).andReturn();
        String pre = sid(img);
        captcha(pre, "1234");
        MvcResult r = mvc.perform(ajax(form("/admin/index/login.html", "admin_name=" + name + "&admin_pwd=" + pwd
                + "&verify=1234")).cookie(new Cookie(AdminSessions.COOKIE, pre))).andReturn();
        assertThat(body(r)).contains("\"code\":1");
        String sid = sid(r);
        assertThat(sid).isNotNull().isNotEqualTo(pre);
        return sid;
    }

    private MvcResult as(String sid, MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.cookie(new Cookie(AdminSessions.COOKIE, sid))).andReturn();
    }

    // ------------------------------------------------------------------ 路由

    @Test
    void entryRootAndOtherModulesGoToTheFrameAndUnknownControllersAre404() throws Exception {
        for (String p : List.of("", "/", "/index/index/index", "/ADMIN/index/welcome")) {
            MvcResult r = mvc.perform(get(E + p)).andReturn();
            assertThat(r.getResponse().getStatus()).as(p).isEqualTo(302);
            assertThat(r.getResponse().getHeader("Location")).isEqualTo(E + "/admin/index/index.html");
        }
        MvcResult nf = mvc.perform(get(E + "/admin/nosuch/thing")).andReturn();
        assertThat(nf.getResponse().getStatus()).isEqualTo(404);
        assertThat(nf.getResponse().getHeader("Content-Security-Policy")).isEqualTo(AdminResponses.CONTENT_SECURITY_POLICY);
        // 未登录：已知控制器（含不存在的动作、update）一律先要求登录
        for (String p : List.of("/admin/index/welcome", "/admin/index/nosuch", "/admin/update/step2", "/admin/vod/data",
                "/admin/admin/info/id/2.html", "/admin/index/login.json")) {
            MvcResult r = mvc.perform(ajax(get(E + p))).andReturn();
            assertThat(r.getResponse().getStatus()).as(p).isEqualTo(302);
            assertThat(r.getResponse().getHeader("Location")).isEqualTo(E + "/admin/index/login.html");
            assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        }
    }

    @Test
    void loginPageVariantsRender() throws Exception {
        for (String p : List.of("/admin/index/login", "/admin/index/login.html", "/admin/Index/LOGIN.html",
                "/admin/index/login/", "/admin/index/login.shtml", "/admin/index/login.xml")) {
            MvcResult r = mvc.perform(get(E + p)).andReturn();
            assertThat(r.getResponse().getStatus()).as(p).isEqualTo(200);
            assertThat(AdminHtml.login(body(r))).isEqualTo("title=后台管理中心 - Copyright by 苹果CMS内容管理系统 "
                    + "fields=[admin_name, admin_pwd, verify] verify=true legend=免责声明");
            assertThat(body(r)).contains("src=\"/_admin.php/admin/index/verify.html\"",
                    "data-login-url=\"/_admin.php/admin/index/login.html\"",
                    "data-index-url=\"/_admin.php/admin/index/index.html\"", "src=\"/static/layui/layui.js\"",
                    "src=\"/admin-assets/login.js\"");
            assertThat(body(r)).doesNotContain("onclick", "<script>", "/index.php/verify");
        }
    }

    // ------------------------------------------------------------------ 登录

    @Test
    void loginChecksParamsThenCaptchaThenAccountAndRotatesTheSession() throws Exception {
        MvcResult img = mvc.perform(get(E + "/admin/index/verify.html")).andReturn();
        assertThat(img.getResponse().getContentType()).isEqualTo("image/png");
        String pre = sid(img);
        assertThat(img.getResponse().getHeaders("Set-Cookie").get(0))
                .isEqualTo(AdminSessions.COOKIE + "=" + pre + "; Path=/_admin.php; HttpOnly; SameSite=Strict");
        Cookie c = new Cookie(AdminSessions.COOKIE, pre);

        // 账号或密码 empty() → 参数错误（不碰验证码）
        captcha(pre, "2222");
        assertThat(body(mvc.perform(ajax(form("/admin/index/login", "admin_name=admin&admin_pwd=0&verify=2222")).cookie(c))
                .andReturn())).isEqualTo("{\"code\":0,\"msg\":\"参数错误\",\"data\":\"\",\"url\":\"\",\"wait\":3}");
        // 验证码错 → 验证码错误（猜错即作废，D-B08-4）
        assertThat(body(mvc.perform(ajax(form("/admin/index/login", "admin_name=admin&admin_pwd=admin888&verify=1111"))
                .cookie(c)).andReturn())).contains("\"msg\":\"验证码错误\"");
        assertThat(body(mvc.perform(ajax(form("/admin/index/login", "admin_name=admin&admin_pwd=admin888&verify=2222"))
                .cookie(c)).andReturn())).contains("\"msg\":\"验证码错误\"");
        // 码对、密码错 → 账号或密码错误
        captcha(pre, "3333");
        assertThat(body(mvc.perform(ajax(form("/admin/index/login", "admin_name=admin&admin_pwd=bad&verify=3333")).cookie(c))
                .andReturn())).contains("\"msg\":\"账号或密码错误\"");
        // 库里的 md5 值不能当密码
        captcha(pre, "4444");
        assertThat(body(mvc.perform(ajax(form("/admin/index/login", "admin_name=admin&admin_pwd=" + md5("admin888")
                + "&verify=4444")).cookie(c)).andReturn())).contains("\"msg\":\"账号或密码错误\"");
        // 成功：JSON 字节同旧版；新会话标识；登录记录（IP 只认连接对端）
        captcha(pre, "5555");
        MvcResult ok = mvc.perform(ajax(form("/admin/index/login", "admin_name=ADMIN+&admin_pwd=admin888&verify=5555"))
                .cookie(c).header("X-Forwarded-For", "9.9.9.9")).andReturn();
        assertThat(ok.getResponse().getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(body(ok)).isEqualTo("{\"code\":1,\"msg\":\"登录成功\",\"data\":\"\","
                + "\"url\":\"\\/_admin.php\\/admin\\/index\\/login.html\",\"wait\":3}");
        String sid = sid(ok);
        assertThat(sid).matches("[0-9a-f]{32}").isNotEqualTo(pre);
        assertThat(sessions.find(pre)).as("登录前的会话已作废").isEmpty();
        assertThat(table.writes).containsExactly("login 1 2130706433 32");
        assertThat(as(sid, get(E + "/admin/index/welcome.html")).getResponse().getStatus()).isEqualTo(200);
        // 没有会话时验证码一定不通过
        assertThat(body(mvc.perform(ajax(form("/admin/index/login", "admin_name=admin&admin_pwd=admin888&verify=5555")))
                .andReturn())).contains("\"msg\":\"验证码错误\"");
    }

    @Test
    void filterExpAppliesToPasswordsAndArrayFieldsAreRejected() throws Exception {
        table.put(9, "likeuser", md5("like "), 1, "");
        assertThat(login("likeuser", "like")).isNotNull();
        MvcResult img = mvc.perform(get(E + "/admin/index/verify.html")).andReturn();
        String pre = sid(img);
        captcha(pre, "1234");
        assertThat(body(mvc.perform(ajax(form("/admin/index/login", "admin_name=admin&admin_pwd%5B%5D=admin888&verify=1234"))
                .cookie(new Cookie(AdminSessions.COOKIE, pre))).andReturn())).contains("\"msg\":\"参数错误\"");
    }

    // ------------------------------------------------------------------ D2 ⑤

    @Test
    void disablingChangingPasswordOrDeletingKicksTheSessionOnTheNextRequest() throws Exception {
        for (String change : List.of("disable", "password", "delete")) {
            seed();
            String sid = login("editor", "ed+pass");
            assertThat(as(sid, ajax(get(E + "/admin/admin/index"))).getResponse().getStatus()).isEqualTo(200);
            switch (change) {
                case "disable" -> table.update(2, r -> new com.maccms.backend.admin.infrastructure.AdminRow(r.adminId(),
                        r.adminName(), r.adminPwd(), 0, r.adminAuth(), 0, 0, 0, 0, 0));
                case "password" -> table.update(2, r -> new com.maccms.backend.admin.infrastructure.AdminRow(r.adminId(),
                        r.adminName(), md5("changed"), 1, r.adminAuth(), 0, 0, 0, 0, 0));
                default -> table.rows.remove(2L);
            }
            MvcResult r = as(sid, get(E + "/admin/index/welcome.html"));
            assertThat(r.getResponse().getStatus()).as(change).isEqualTo(302);
            assertThat(r.getResponse().getHeader("Location")).isEqualTo(E + "/admin/index/login.html");
            assertThat(String.join("\n", r.getResponse().getHeaders("Set-Cookie")))
                    .contains(AdminSessions.COOKIE + "=; Path=/_admin.php; Max-Age=0");
            assertThat(sessions.find(sid)).as("会话已作废").isEmpty();
            // 恢复原状也回不去：要重新登录
            if (!change.equals("delete")) {
                table.put(2, "editor", md5("ed pass"), 1, ",vod/data,admin/index,index/unlocked,");
            }
            assertThat(as(sid, get(E + "/admin/index/welcome.html")).getResponse().getStatus()).isEqualTo(302);
        }
    }

    @Test
    void permissionChangesApplyToTheNextRequest() throws Exception {
        String sid = login("editor", "ed+pass");
        assertThat(as(sid, ajax(get(E + "/admin/admin/info"))).getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("您没有权限访问此页面");
        table.update(2, r -> new com.maccms.backend.admin.infrastructure.AdminRow(2, r.adminName(), r.adminPwd(), 1,
                ",admin/info,", 0, 0, 0, 0, 0));
        assertThat(body(as(sid, ajax(get(E + "/admin/admin/info"))))).startsWith("\"<!DOCTYPE html>");
        assertThat(body(as(sid, ajax(get(E + "/admin/admin/index"))))).contains("您没有权限访问此页面");
    }

    // ------------------------------------------------------------------ 权限

    @Test
    void everyAuthCodeIsDeniedToAnAdminWithoutPermissions() throws Exception {
        String sid = login("viewer", "viewer1");
        List<String> wrong = new ArrayList<>();
        for (String code : AdminPermissions.codes()) {
            if (code.contains("?")) {
                continue;
            }
            MvcResult r = as(sid, ajax(get(E + "/admin/" + code)));
            String b = body(r);
            if (code.equals("index/welcome")) {
                if (!b.startsWith("\"<!DOCTYPE html>")) {
                    wrong.add(code + " " + b);
                }
            } else if (!b.equals("{\"code\":0,\"msg\":\"您没有权限访问此页面\",\"data\":\"\",\"url\":\"\",\"wait\":3}")) {
                wrong.add(code + " " + r.getResponse().getStatus() + " " + b);
            }
        }
        assertThat(wrong).isEmpty();
        // 非 Ajax：跳转提示页，返回上一页
        MvcResult html = as(sid, get(E + "/admin/system/config.html"));
        assertThat(AdminHtml.jump(body(html)))
                .isEqualTo("error 您没有权限访问此页面 href=javascript:history.back(-1); wait=3 title=跳转提示");
        assertThat(html.getResponse().getHeader("Content-Security-Policy")).isEqualTo(AdminResponses.CONTENT_SECURITY_POLICY);
        // D-B12-6：退出不需要权限
        MvcResult out = as(sid, get(E + "/admin/index/logout.html"));
        assertThat(out.getResponse().getStatus()).isEqualTo(302);
        assertThat(sessions.find(sid)).isEmpty();
    }

    @Test
    void superAdminSeesLaterBatchNoticesForEveryNonB12Action() throws Exception {
        String sid = login("admin", "admin888");
        List<String> wrong = new ArrayList<>();
        for (String ca : AdminPermissions.knownActions()) {
            String[] p = ca.split("/", 2);
            String batch = AdminPermissions.batchOf(p[0], p[1]);
            if (batch.equals("B12")) {
                continue;
            }
            String b = body(as(sid, ajax(get(E + "/admin/" + ca))));
            if (AdminPermissions.notProvided(p[0], p[1])) {
                // 用户决定不提供的入口（collect/timing，27 文档 D-B19-15）：固定提示，不指向任何批次
                if (!b.equals("{\"code\":0,\"msg\":\"" + AdminController.NOT_PROVIDED_MSG + "\",\"data\":\"\",\"url\":\"\",\"wait\":3}")) {
                    wrong.add(ca + " " + b);
                }
                continue;
            }
            if (batch.equals("B15")) {
                // B15 的动作已实现（与 PHP 的对照在 AdminSystemGoldenTest）：这里只确认不再是批次提示。数据库工具要库（本测试没有
                // DatabaseToolMapper，新版此时仍给批次提示；真实库上的对照在 AdminSystemGoldenMySqlIT）
                if (b.contains("批次提供") && !p[0].equals("database")) {
                    wrong.add(ca + " " + b);
                }
                continue;
            }
            if (!b.equals("{\"code\":0,\"msg\":\"该功能尚未在新系统中实现（由 " + batch + " 批次提供）\",\"data\":\"\","
                    + "\"url\":\"\",\"wait\":3}")) {
                wrong.add(ca + " " + b);
            }
        }
        assertThat(wrong).isEmpty();
        MvcResult html = as(sid, get(E + "/admin/vod/data.html"));
        assertThat(html.getResponse().getStatus()).isEqualTo(501);
        assertThat(body(html)).contains("由 B13 批次提供");
        MvcResult timing = as(sid, get(E + "/admin/collect/timing.html"));
        assertThat(timing.getResponse().getStatus()).isEqualTo(501);
        assertThat(body(timing)).contains(AdminController.NOT_PROVIDED_MSG).doesNotContain("批次提供");
        // 不存在的动作（含旧版的空控制器 safety、update）是 404
        for (String p : List.of("/admin/index/nosuch", "/admin/safety/file", "/admin/update/step2", "/admin/index/_cache_clear")) {
            assertThat(as(sid, get(E + p)).getResponse().getStatus()).as(p).isEqualTo(404);
        }
    }

    // ------------------------------------------------------------------ 框架页的操作

    @Test
    void frameActions() throws Exception {
        String sid = login("admin", "admin888");
        // Ajax 取页面：整页 HTML 的 JSON 字符串（旧版行为，B13 的 j-select 弹窗依赖）
        MvcResult page = as(sid, ajax(get(E + "/admin/index/welcome")));
        assertThat(page.getResponse().getContentType()).isEqualTo("application/json;charset=UTF-8");
        // 整个正文是一个 JSON 字符串；模板行尾随检出方式可能是 LF 或 CRLF（.gitattributes 固定为 LF），断言只看结构不看行尾
        String json = body(page);
        assertThat(json).startsWith("\"").endsWith("\"");
        String html = JSON.readValue(json, String.class);
        assertThat(html.replace("\r\n", "\n")).startsWith("<!DOCTYPE html>\n<html lang=\"en\">").contains("</html>");
        MvcResult frame = as(sid, get(E + "/admin/index/index.html"));
        assertThat(frame.getResponse().getHeader("X-Frame-Options")).isEqualTo("SAMEORIGIN");
        assertThat(AdminHtml.menus(body(frame))).contains("GROUP 首页 icon=xe625 hidden=false",
                "  111 /_admin.php/admin/index/welcome.html 欢迎页面");

        assertThat(body(as(sid, ajax(get(E + "/admin/index/checkcache"))))).isEqualTo("no");
        assertThat(body(as(sid, ajax(get(E + "/admin/index/clear"))))).contains("\"msg\":\"缓存清理成功\"");
        verify(contentCache).clearVodSearchCache();

        MvcResult on = as(sid, ajax(form("/admin/index/iframe", "val=1")));
        // MockMvc 会重写 Set-Cookie 的属性名大小写，这里只比较 名=值 与 Path（逐字节在真实 HTTP 测试里）
        assertThat(on.getResponse().getHeaders("Set-Cookie")).hasSize(1);
        assertThat(on.getResponse().getHeaders("Set-Cookie").get(0)).startsWith("is_iframe=yes;").containsIgnoringCase("path=/");
        assertThat(body(on)).contains("\"msg\":\"布局切换成功，跳转中\"");
        MvcResult off = as(sid, ajax(form("/admin/index/iframe", "")));
        assertThat(off.getResponse().getHeaders("Set-Cookie").get(0)).startsWith("is_iframe=deleted;");
        assertThat(body(as(sid, ajax(form("/admin/index/iframe", "val=2"))))).contains("\"code\":0", "缓存清理成功");

        assertThat(body(as(sid, ajax(form("/admin/index/unlocked", "password=admin889"))))).contains("\"msg\":\"密码错误\"");
        assertThat(body(as(sid, ajax(form("/admin/index/unlocked", ""))))).contains("\"msg\":\"密码错误\"");
        assertThat(body(as(sid, ajax(get(E + "/admin/index/unlocked?password=admin888")))))
                .isEqualTo("{\"code\":1,\"msg\":\"解锁成功\",\"data\":\"\",\"url\":\"\\/_admin.php\\/admin\\/index\\/unlocked.html\",\"wait\":3}");

        MvcResult q = as(sid, get(E + "/admin/index/quickmenu.html"));
        assertThat(AdminHtml.quickmenu(body(q))).startsWith("title=快捷菜单配置 - 安全第一请勿泄露后台地址")
                .contains("text=测试系统模块菜单,system/config\\n测试远程地址菜单,//www.baidu.com/").endsWith("token=true");
        // 保存归 B15：没有表单令牌时拒绝、不写文件；带上页面的令牌后写回（临时副本里的）quickmenu.php
        Path quickmenu = SITE.resolve("application/extra/quickmenu.php");
        byte[] before = Files.readAllBytes(quickmenu);
        assertThat(body(as(sid, ajax(form("/admin/index/quickmenu", "quickmenu=a,b"))))).contains("\"code\":0", "非法请求");
        assertThat(Files.readAllBytes(quickmenu)).isEqualTo(before);
        Matcher tk = Pattern.compile("name=\"__token__\" value=\"([0-9a-f]+)\"").matcher(body(q));
        assertThat(tk.find()).isTrue();
        assertThat(body(as(sid, ajax(form("/admin/index/quickmenu", "__token__=" + tk.group(1) + "&quickmenu=a,b")))))
                .contains("\"code\":1");
        assertThat(Files.readString(quickmenu, StandardCharsets.UTF_8)).isEqualTo("<?php\nreturn array (\n  0 => 'a,b',\n);");

        MvcResult out = as(sid, get(E + "/admin/index/logout"));
        assertThat(out.getResponse().getHeader("Location")).isEqualTo(E + "/admin/index/login.html");
        assertThat(as(sid, get(E + "/admin/index/welcome")).getResponse().getStatus()).isEqualTo(302);
    }

    @Test
    void crossOriginPostsAreRejectedBeforeAnythingElse() throws Exception {
        MvcResult r = mvc.perform(form("/admin/index/login", "admin_name=admin&admin_pwd=admin888")
                .header("Origin", "http://evil.test")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(table.writes).isEmpty();
        MvcResult ref = mvc.perform(form("/admin/index/login", "admin_name=admin&admin_pwd=admin888")
                .header("Referer", "http://evil.test/x")).andReturn();
        assertThat(ref.getResponse().getStatus()).isEqualTo(403);
        MvcResult same = mvc.perform(ajax(form("/admin/index/login", "admin_name=admin&admin_pwd=0"))
                .header("Origin", "http://admin.local.test")).andReturn();
        assertThat(body(same)).contains("\"msg\":\"参数错误\"");
    }

    @Test
    void writeGetsFromAnotherOriginOfTheSameSiteAreRejected() throws Exception {
        // D-B12-3：SameSite=Strict 挡不住同站点的其他子域名或端口，旧后台的删除、改状态、清缓存又都是 GET
        String sid = login("admin", "admin888");
        List<MockHttpServletRequestBuilder> forged = List.of(
                get(E + "/admin/admin/del?ids=3").header("Sec-Fetch-Site", "same-site"),
                get(E + "/admin/admin/del?ids=3").header("Sec-Fetch-Site", "cross-site"),
                head(E + "/admin/admin/del?ids=3").header("Referer", "http://evil.local.test/x"),
                get(E + "/admin/admin/field?col=admin_status&ids=3&val=0").header("Origin", "http://admin.local.test:8080"),
                get(E + "/admin/index/clear").header("Sec-Fetch-Site", "same-site"),
                get(E + "/admin/index/logout").header("Sec-Fetch-Site", "same-site"),
                form("/admin/index/unlocked", "password=admin888").header("Sec-Fetch-Site", "same-site"));
        for (MockHttpServletRequestBuilder b : forged) {
            assertThat(as(sid, b).getResponse().getStatus()).isEqualTo(403);
        }
        assertThat(table.writes).containsExactly("login 1 2130706433 32");
        verify(contentCache, never()).clearVodSearchCache();
        assertThat(sessions.find(sid)).as("退出没有生效").isPresent();
        // 本站页面发出的（浏览器标为 same-origin，或带本站 Referer）与地址栏直接打开的照常执行
        assertThat(body(as(sid, ajax(get(E + "/admin/admin/field?col=admin_status&ids=3&val=0"))
                .header("Sec-Fetch-Site", "same-origin").header("Referer", "http://admin.local.test/_admin.php/admin/admin/index"))))
                .contains("设置成功");
        assertThat(body(as(sid, ajax(get(E + "/admin/admin/del?ids=3")).header("Sec-Fetch-Site", "none")))).contains("删除成功");
        // 登录页与框架页可以从别的网站或书签直接打开
        assertThat(mvc.perform(get(E + "/admin/index/login").header("Sec-Fetch-Site", "cross-site")).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(as(sid, get(E + "/admin/index/index").header("Sec-Fetch-Site", "same-site")).getResponse().getStatus())
                .isEqualTo(200);
        // 其余页面同样要求同源
        assertThat(as(sid, get(E + "/admin/index/welcome").header("Sec-Fetch-Site", "same-site")).getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    void logoutNeedsNoPermissionButStillChecksTheSourceAndDestroysTheSession() throws Exception {
        // D-B12-6（队长 2026-10-25 接受）：非超级管理员（有部分权限、完全没有权限）都能退出；退出照样做来源校验（D-B12-3），
        // 生效时作废服务端会话并删除 cookie，之后的请求回登录页
        String toLogin = E + "/admin/index/login.html";
        for (String[] who : List.of(new String[] {"editor", "ed+pass"}, new String[] {"viewer", "viewer1"})) {
            String sid = login(who[0], who[1]);
            // 锁屏中（旧前端脚本把锁屏状态放在 sessionStorage，服务端只有解锁）：解锁失败不影响退出
            as(sid, ajax(form("/admin/index/unlocked", "password=wrong")));
            MvcResult forged = as(sid, get(E + "/admin/index/logout").header("Origin", "http://evil.local.test"));
            assertThat(forged.getResponse().getStatus()).isEqualTo(403);
            assertThat(sessions.find(sid)).as(who[0] + "：别的来源发起的退出不生效").isPresent();
            MvcResult out = as(sid, get(E + "/admin/index/logout.html").header("Sec-Fetch-Site", "same-origin"));
            assertThat(out.getResponse().getStatus()).isEqualTo(302);
            assertThat(out.getResponse().getHeader("Location")).isEqualTo(toLogin);
            assertThat(out.getResponse().getHeaders("Set-Cookie")).anySatisfy(c ->
                    assertThat(c).startsWith(AdminSessions.COOKIE + "=;").containsIgnoringCase("Max-Age=0"));
            assertThat(sessions.find(sid)).as(who[0] + "：会话已销毁").isEmpty();
            assertThat(as(sid, get(E + "/admin/index/welcome")).getResponse().getHeader("Location")).isEqualTo(toLogin);
        }
        // 未登录（没有 cookie、只有登录页验证码的会话）时退出：与旧版相同，回登录页
        assertThat(mvc.perform(get(E + "/admin/index/logout")).andReturn().getResponse().getHeader("Location"))
                .isEqualTo(toLogin);
        String anon = sid(mvc.perform(get(E + "/admin/index/verify.html")).andReturn());
        assertThat(as(anon, get(E + "/admin/index/logout")).getResponse().getHeader("Location")).isEqualTo(toLogin);
        assertThat(table.writes).allMatch(w -> w.startsWith("login "));
    }

    @Test
    void anonymousCaptchaFloodDoesNotEvictLoggedInSessions() throws Exception {
        String sid = login("admin", "admin888");
        for (int i = 0; i <= AdminSessions.MAX_SESSIONS; i++) {
            mvc.perform(get(E + "/admin/index/verify.html"));
        }
        assertThat(sessions.find(sid)).isPresent();
        assertThat(as(sid, get(E + "/admin/index/welcome")).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void anUppercaseStoredHashLogsInAndStaysLoggedInLikeLegacy() throws Exception {
        // admin_pwd 按 utf8_general_ci 比较：库里是大写的 md5 时旧版也能登录
        table.put(2, "editor", md5("ed pass").toUpperCase(java.util.Locale.ROOT), 1, ",admin/index,index/unlocked,");
        String sid = login("editor", "ed+pass");
        assertThat(as(sid, get(E + "/admin/admin/index")).getResponse().getStatus()).isEqualTo(200);
        assertThat(as(sid, get(E + "/admin/index/welcome")).getResponse().getStatus()).isEqualTo(200);
        // D-B12-18：能登录的密码也能解锁（旧版区分大小写，总是"密码错误"）；库里的哈希原文不是密码
        assertThat(body(as(sid, ajax(form("/admin/index/unlocked", "password=ed+pass"))))).contains("\"msg\":\"解锁成功\"");
        assertThat(body(as(sid, ajax(form("/admin/index/unlocked", "password=ed+pas"))))).contains("\"msg\":\"密码错误\"");
        assertThat(body(as(sid, ajax(form("/admin/index/unlocked",
                "password=" + md5("ed pass").toUpperCase(java.util.Locale.ROOT)))))).contains("\"msg\":\"密码错误\"");
        // 改密（写成小写的新哈希）后下一个请求即回登录页
        table.put(2, "editor", md5("other"), 1, ",admin/index,index/unlocked,");
        assertThat(as(sid, get(E + "/admin/index/welcome")).getResponse().getHeader("Location"))
                .isEqualTo(E + "/admin/index/login.html");
    }

    // ------------------------------------------------------------------ 管理员

    private static final Pattern TOKEN = Pattern.compile("name=\"__token__\" value=\"([0-9a-f]{32})\"");

    private static String token(MvcResult r) throws Exception {
        Matcher m = TOKEN.matcher(body(r));
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void adminFormTokenAndSave() throws Exception {
        String sid = login("admin", "admin888");
        MvcResult f = as(sid, get(E + "/admin/admin/info?id=2"));
        List<String> info = AdminHtml.adminInfo(body(f));
        assertThat(info.get(0)).isEqualTo("id=2 name=editor pwd= token=set");
        assertThat(info).contains("  vod/data 视频数据 pid=5 checked=true readonly=false",
                "  index/welcome 欢迎页面 pid=1 checked=true readonly=true",
                "  admin/index 管理员 pid=7 checked=true readonly=false");
        String t = token(f);
        assertThat(body(as(sid, ajax(form("/admin/admin/info", "admin_id=&admin_name=n1&admin_pwd=x&admin_status=1")))))
                .contains("\"msg\":\"非法请求\"");
        assertThat(body(as(sid, ajax(form("/admin/admin/info", "admin_id=&__token__=" + t
                + "&admin_name=n1&admin_pwd=%20x%20&admin_status=1&admin_auth%5B%5D=vod%2Fdata")))))
                .contains("\"msg\":\"保存成功!\"");
        assertThat(table.writes).last().isEqualTo("insert 101 n1 " + md5(" x ") + " 1 ,vod/data,index/welcome,");
        // 同一令牌再交一次
        assertThat(body(as(sid, ajax(form("/admin/admin/info", "admin_id=&__token__=" + t + "&admin_name=n2&admin_pwd=x")))))
                .contains("\"msg\":\"请不要重复提交表单\"");
        // 编辑：密码留空不改；状态只接受 0/1；名称必填
        String t2 = token(as(sid, get(E + "/admin/admin/info?id=2")));
        assertThat(body(as(sid, ajax(form("/admin/admin/info", "admin_id=2&__token__=" + t2
                + "&admin_name=&admin_pwd=&admin_status=1"))))).contains("\"msg\":\"参数错误：名称必须\"");
        String t3 = token(as(sid, get(E + "/admin/admin/info?id=2")));
        assertThat(body(as(sid, ajax(form("/admin/admin/info", "admin_id=2&__token__=" + t3
                + "&admin_name=editor&admin_pwd=&admin_status=5&admin_login_num=99"))))).contains("\"msg\":\"参数错误\"");
        String t4 = token(as(sid, get(E + "/admin/admin/info?id=2")));
        assertThat(body(as(sid, ajax(form("/admin/admin/info", "admin_id=2&__token__=" + t4
                + "&admin_name=editor&admin_pwd=&admin_status=0&admin_auth%5B%5D=index%2Fwelcome&admin_auth%5B%5D=a%2Cb")))))
                .contains("\"msg\":\"保存成功!\"");
        assertThat(table.writes).last().isEqualTo("update 2 editor null 0 ,index/welcome,a,b,");
    }

    @Test
    void deleteAndStatus() throws Exception {
        String sid = login("admin", "admin888");
        assertThat(body(as(sid, ajax(get(E + "/admin/admin/del?ids=3,1"))))).contains("\"msg\":\"禁止删除当前登录账号\"");
        // D-B12-10：旧版 ids=1abc 会删掉当前登录的超级管理员（PHP 8 的 in_array 认为 1abc != 1，MySQL 却按 1 删）
        assertThat(body(as(sid, ajax(get(E + "/admin/admin/del?ids=1abc"))))).contains("\"msg\":\"参数错误\"");
        assertThat(body(as(sid, ajax(get(E + "/admin/admin/del"))))).contains("\"msg\":\"参数错误\"");
        assertThat(body(as(sid, ajax(form("/admin/admin/del", "ids%5B%5D=3&ids%5B%5D=2"))))).contains("\"msg\":\"删除成功\"");
        assertThat(table.writes).last().isEqualTo("delete [3, 2]");
        table.put(4, "e4", md5("x"), 1, "");
        assertThat(body(as(sid, ajax(get(E + "/admin/admin/field/col/admin_status/ids/4.html?val=1.0")))))
                .contains("\"msg\":\"设置成功\"");
        assertThat(table.writes).last().isEqualTo("status [4] 1");
        assertThat(body(as(sid, ajax(get(E + "/admin/admin/field?col=admin_status&ids=4&val=2"))))).contains("参数错误");
        assertThat(body(as(sid, ajax(get(E + "/admin/admin/field?col=admin_name&ids=4&val=1"))))).contains("参数错误");
        // 停用自己：下一个请求即要求重新登录（D2 ⑤）
        assertThat(body(as(sid, ajax(get(E + "/admin/admin/field?col=admin_status&ids=1&val=0"))))).contains("设置成功");
        assertThat(as(sid, get(E + "/admin/index/welcome")).getResponse().getStatus()).isEqualTo(302);
    }

    @Test
    void adminListRendersLegacyStructure() throws Exception {
        String sid = login("admin", "admin888");
        MvcResult r = as(sid, get(E + "/admin/admin/index?wd=edi&limit=2abc"));
        List<String> rows = AdminHtml.adminList(body(r));
        assertThat(rows.get(0)).startsWith("3 | 3 | viewer | checked=true value=1 /_admin.php/admin/admin/field/col/admin_status/ids/3.html 启用|禁用");
        assertThat(rows).hasSize(3);
        assertThat(AdminHtml.adminList(body(as(sid, get(E + "/admin/admin/index")))))
                .anySatisfy(row -> assertThat(row).startsWith("- | 1 | admin |").endsWith("| -"));
        assertThat(AdminHtml.paging(body(r))).isEqualTo("count=3 limit=2 curr=1 url="
                + "/_admin.php/admin/admin/index/wd/edi/limit/%7Blimit%7D/page/%7Bpage%7D.html");
    }
}
