package com.maccms.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 入库与断点续采跳转地址的本站检查（27 文档 D-B19-12）与旧 {@code mac_get_refer} 的 {@code urldecode}。 */
class AdminCollectJumpTest {

    private static final String ORIGIN = "http://www.example.test";

    @Test
    void onlySameSiteTargetsAreAccepted() {
        assertThat(AdminCollectActions.sameSite(ORIGIN, "/_admin.php/admin/collect/api?ac=list")).isEqualTo("/_admin.php/admin/collect/api?ac=list");
        assertThat(AdminCollectActions.sameSite(ORIGIN, "http://www.example.test/_admin.php/x")).isEqualTo("http://www.example.test/_admin.php/x");
        assertThat(AdminCollectActions.sameSite(ORIGIN, "HTTP://WWW.EXAMPLE.TEST")).isEqualTo("HTTP://WWW.EXAMPLE.TEST");
        assertThat(AdminCollectActions.sameSite(ORIGIN, "")).isEmpty();
        assertThat(AdminCollectActions.sameSite(ORIGIN, null)).isNull();
        for (String bad : new String[] {"//evil.test/x", "/\\evil.test", "javascript:alert(1)", "http://evil.test/", "https://www.example.test/x",
                "http://www.example.test.evil.test/", "http://www.example.test@evil.test/", "http://www.example.test:81/", "/a\nb", "x/y"}) {
            assertThat(AdminCollectActions.sameSite(ORIGIN, bad)).as(bad).isNull();
        }
    }

    @Test
    void phpUrldecode() {
        assertThat(AdminCollectActions.phpUrldecode("a+b%41%e4%b8%ad%zz%4")).isEqualTo("a bA中%zz%4");
    }
}
