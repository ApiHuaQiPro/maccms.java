package com.maccms.backend.admin.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 旧 {@code Base::check_auth} 的文本语义（Base.php:42-58）。{@code admin_auth} 写法的结论与 PHP 黄金 {@code auth-string-*} 一致
 * （AdminGoldenMySqlIT 经真实请求复放）。
 */
class AdminAuthorizationTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // admin_auth | 控制器 | 动作 | 结果
            ",admin/index,|admin|index|true",
            ",admin/index,|Admin|INDEX|true",
            "admin/index|admin|index|false",          // 缺首逗号：第一项不生效（auth-string-no-commas）
            ",ADMIN/INDEX,|admin|index|false",        // admin_auth 本身不转小写（auth-string-upper）
            ",admin/index|admin|index|true",          // 缺尾逗号：靠默认追加的 ,index/index… 补上（auth-string-no-trailing-comma）
            "admin/index,|admin|index|false",         // auth-string-no-leading-comma
            ", admin/index ,|admin|index|false",      // 带空格不生效（auth-string-spaces）
            "''|index|welcome|true",                  // 默认权限
            "''|index|index|true",
            "''|index|clear|false",
            "''|index|unlocked|false",                // 解锁也要权限（旧版同样如此）
            "''|index|checkcache|false",
            ",vod/data,|vod|data|true",
            ",vod/data,|vod|dat|false",
            ",make/index?ac2=wap,|make|index?ac2=wap|true",
            ",make/index?ac2=wap,|make|index|false"})
    void textSemanticsMatchTheLegacyCheck(String auth, String controller, String action, boolean allowed) {
        String a = auth.equals("''") ? "" : auth;
        assertThat(AdminAuthorization.allowed(7, a, controller, action)).isEqualTo(allowed);
    }

    @Test
    void superAdminIsAlwaysAllowed() {
        assertThat(AdminAuthorization.allowed(1, "", "database", "sql")).isTrue();
        assertThat(AdminAuthorization.allowed(1, null, "anything", "goes")).isTrue();
        assertThat(AdminAuthorization.allowed(11, "", "database", "sql")).isFalse();
    }

    @Test
    void logoutIsNeverDenied() {
        // D-B12-6：旧版非超级管理员没有 index/logout 权限，点"退出登陆"只得到"您没有权限访问此页面"（黄金 auth-viewer、auth-editor）
        assertThat(AdminAuthorization.allowed(3, "", "index", "logout")).isTrue();
        assertThat(AdminAuthorization.allowed(3, null, "Index", "LOGOUT")).isTrue();
    }

    @Test
    void asciiLowerOnlyTouchesAsciiLetters() {
        assertThat(AdminAuthorization.asciiLower("AbC/ÄÖ_Z")).isEqualTo("abc/ÄÖ_z");
    }
}
