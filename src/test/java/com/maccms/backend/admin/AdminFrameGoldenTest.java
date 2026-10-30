package com.maccms.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.admin.application.AdminMenus;
import com.maccms.backend.admin.application.LegacyAdminUrl;
import com.maccms.backend.admin.application.view.AdminPrincipal;
import com.maccms.backend.admin.web.jte.StaticTemplates;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.FramePage;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.legacyconfig.application.VarExportParser;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import gg.jte.output.StringOutput;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 框架页菜单与 PHP 黄金逐项相同（旧 Index::index + index.html）：哪些分组与条目出现（show 与 check_auth）、顺序、名称（含 zh-tw）、
 * 地址（{@code url()} 与 {@code ?param}）、{@code data-id}、快捷菜单各种写法、顶部导航序号与侧栏的隐藏。新版按同一份 HTML 取法从
 * 渲染出的页面里取，模板与 {@link AdminMenus} 一起覆盖。唯一的差异是快捷菜单中 {@code javascript:} 开头的地址（D-B12-13）。
 */
class AdminFrameGoldenTest {

    static List<Arguments> frames() {
        List<Arguments> out = new ArrayList<>();
        for (String version : List.of(AdminGolden.PHP80, AdminGolden.PHP73)) {
            AdminGolden golden = AdminGolden.load(version);
            for (AdminGolden.Scenario s : golden.scenarios()) {
                for (AdminGolden.Step st : s.steps()) {
                    if (st.isRequest() && st.path().equals("/admin/index/index") && st.status() == 200 && st.body() != null
                            && !st.ajax()) {
                        out.add(Arguments.of(golden.php() + " " + s.label() + "/" + st.label(), s, st));
                    }
                }
            }
        }
        assertThat(out).hasSizeGreaterThanOrEqualTo(16);
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("frames")
    void menusMatchPhp(String name, AdminGolden.Scenario s, AdminGolden.Step frame) throws Exception {
        AdminGolden.Row admin = loggedIn(s);
        LegacyLang lang = LegacyLang.select(s.config().getOrDefault("lang", "zh-cn")).pack();
        List<String> quick = s.quickmenu() == null ? repositoryQuickmenu() : s.quickmenu();
        LegacyAdminUrl url = new LegacyAdminUrl("/_admin.php", "Index", "index");
        AdminPrincipal p = new AdminPrincipal(admin.id(), admin.name(), admin.auth(), 0, 0, 0);
        FramePage page = new FramePage(new AdminHead("t", "", "/_admin.php", ""), lang, AdminMenus.build(p, lang, quick, url),
                url.build("index/clear"), url.build("index/logout"), url.build("index/welcome"), url.build("checkcache"));
        StringOutput out = new StringOutput();
        new StaticTemplates().adminPagesIndex(page).render(out);

        List<String> expected = AdminHtml.menus(frame.body()).stream()
                // D-B12-13：快捷菜单里 javascript: 开头的地址新版写成固定的 javascript:void(0);
                .map(line -> line.contains(" javascript:alert(1) ") ? line.replace(" javascript:alert(1) ", " javascript:void(0); ")
                        : line)
                .toList();
        assertThat(AdminHtml.menus(out.toString())).containsExactlyElementsOf(expected);
        // 框架页上其余由服务端写入的地址
        assertThat(out.toString()).contains("href=\"/_admin.php/admin/index/clear.html\"",
                "href=\"/_admin.php/admin/index/logout.html\"", "src=\"/_admin.php/admin/index/welcome.html\"",
                "data-checkcache-url=\"/_admin.php/admin/index/checkcache.html\"");
        assertThat(frame.body()).contains("href=\"/_admin.php/admin/index/clear.html\"",
                "href=\"/_admin.php/admin/index/logout.html\"", "src=\"/_admin.php/admin/index/welcome.html\"",
                "url: \"/_admin.php/admin/index/checkcache.html\"");
    }

    /** 场景里登录的管理员：本步之前最后一次登录请求的账号。 */
    static AdminGolden.Row loggedIn(AdminGolden.Scenario s) {
        String account = null;
        for (AdminGolden.Step st : s.steps()) {
            if (st.isRequest() && st.path().equals("/admin/index/login") && st.method().equals("POST")) {
                for (String kv : st.post().split("&")) {
                    if (kv.startsWith("admin_name=")) {
                        account = URLDecoder.decode(kv.substring("admin_name=".length()), StandardCharsets.UTF_8);
                    }
                }
            }
        }
        String a = account;
        return s.rows0().stream().filter(r -> r.name().equals(a)).findFirst().orElseThrow();
    }

    static List<String> repositoryQuickmenu() throws Exception {
        List<String> out = new ArrayList<>();
        for (PhpValue v : VarExportParser.parse(Files.readAllBytes(Path.of("php", "application", "extra", "quickmenu.php")))
                .entries().values()) {
            out.add(PhpValues.text(v));
        }
        return out;
    }
}
