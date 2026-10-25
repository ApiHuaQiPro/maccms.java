package com.maccms.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 后台部署参数的校验（21 文档第 7 节）：不合法即启动失败，信息只含配置项名称，不回显取值。 */
class AdminEndpointTest {

    @Test
    void rootDeploymentAndSubdirectory() {
        AdminEndpoint a = AdminEndpoint.from("/_admin.php", "http://Admin.Example.test/");
        assertThat(a).isEqualTo(new AdminEndpoint("/_admin.php", "", "http://admin.example.test", false));
        assertThat(a.root()).isEqualTo("/_admin.php");
        AdminEndpoint b = AdminEndpoint.from(" /m4cAdm1n-x_.php ", "https://cms.example.test:8443/sub/dir/");
        assertThat(b).isEqualTo(new AdminEndpoint("/m4cAdm1n-x_.php", "/sub/dir", "https://cms.example.test:8443", true));
        assertThat(b.root()).isEqualTo("/sub/dir/m4cAdm1n-x_.php");
    }

    @ParameterizedTest
    @CsvSource({"https://a.test:443/, https://a.test", "http://a.test:80/, http://a.test", "http://a.test:443/, http://a.test:443",
            "https://a.test:80/, https://a.test:80"})
    void defaultPortsAreDroppedFromTheOriginLikeBrowsersDo(String baseUrl, String origin) {
        assertThat(AdminEndpoint.from("/_admin.php", baseUrl).origin()).isEqualTo(origin);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "_admin.php", "/admin.php", "/ADMIN.php", "/index.php", "/api.php", "/install.php",
            "/a/b.php", "/x.php5", "/x y.php", "/../x.php", "/x.PHP"})
    void rejectsBadEntries(String entry) {
        assertThatThrownBy(() -> AdminEndpoint.from(entry, "http://a.test/"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("maccms.admin.entry");
    }

    @ParameterizedTest
    @CsvSource({"''", "a.test", "ftp://a.test/", "http://a.test", "http://u:p@a.test/", "http://a.test/?q=1",
            "http://a.test/#x", "http:///x/", "http://a.test//", "http://a.test/a%20b/", "javascript:alert(1)//"})
    void rejectsBadBaseUrls(String url) {
        String u = url.equals("''") ? "" : url;
        assertThatThrownBy(() -> AdminEndpoint.from("/_admin.php", u))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("maccms.admin.base-url")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(u.isEmpty() ? "\u0000" : u));
    }
}
