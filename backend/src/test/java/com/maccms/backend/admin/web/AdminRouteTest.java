package com.maccms.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 入口之后的路径按 ThinkPHP 5.0 解析（黄金 {@code anonymous} 场景的各种地址写法）。 */
class AdminRouteTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/admin/index/login|index/login|Index",
            "/admin/index/login.html|index/login|Index",
            "/admin/Index/LOGIN.html|index/login|Index",
            "/admin/index/login/|index/login|Index",
            "/admin/index/login.htm|index/login|Index",
            "/admin/index/login.shtml|index/login|Index",
            "/admin/index/login.shtm|index/login|Index",
            "/admin/index/login.XML|index/login|Index",
            "/admin/index/login.json|index/login.json|Index",
            "/admin|index/index|Index",
            "/admin/|index/index|Index",
            "/admin/index|index/index|Index",
            "/admin//welcome|index/welcome|Index",
            "/admin/admin/info/id/2.html|admin/info|Admin",
            "/admin/vod_server/x|vod_server/x|VodServer"})
    void parsesModuleControllerAction(String path, String code, String controllerName) {
        AdminRoute r = AdminRoute.parse(path);
        assertThat(r.redirectHome()).isFalse();
        assertThat(r.code()).isEqualTo(code);
        assertThat(r.controllerName()).isEqualTo(controllerName);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"''", "/", "/ADMIN/index/welcome", "/index/index/index", "/Admin", "/admin.html2/x"})
    void nonAdminModulesGoHome(String path) {
        // 旧 Begin 行为：模块为空或不是（原样的）admin → 302 到 admin/index/index
        assertThat(AdminRoute.parse(path.equals("''") ? "" : path).redirectHome()).isTrue();
    }

    @Test
    void extraSegmentsArePairs() {
        assertThat(AdminRoute.parse("/admin/admin/index/page/2/limit/2.html").vars())
                .isEqualTo(Map.of("page", "2", "limit", "2"));
        assertThat(AdminRoute.parse("/admin/admin/field/col/admin_status/ids/1.html").vars())
                .isEqualTo(Map.of("col", "admin_status", "ids", "1"));
        // 键必须是 \w+；值 strip_tags；落单的段没有值
        assertThat(AdminRoute.parse("/admin/a/b/k-1/v/x/<b>y</b>/odd").vars()).isEqualTo(Map.of("1", "v", "x", "y"));
        assertThatThrownBy(() -> AdminRoute.parse("/admin/a/b/x").vars().put("a", "b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
