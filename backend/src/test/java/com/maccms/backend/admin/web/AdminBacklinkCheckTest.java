package com.maccms.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.common.lang.LegacyLang;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 回链检测的判定（B28，旧 {@code mac_check_back_link}，30 文档第 4 节）：最终的 code 只看手机域名；抓取失败按空页面；域名为空时按
 * PHP 7.3（"异常"）；按字节查找。与 PHP 黄金的逐步对照在 {@code UrlsendBacklinkGoldenMySqlIT}。
 */
class AdminBacklinkCheckTest {

    private static final LegacyLang LANG = LegacyLang.of("zh-cn");

    private static AdminUrlsendActions.Result check(String html, String site, String wap) {
        return AdminUrlsendActions.check(html == null ? null : html.getBytes(StandardCharsets.UTF_8), site, wap, LANG);
    }

    @Test
    void finalCodeOnlyDependsOnTheMobileDomain() {
        assertThat(check("<a href=\"http://www.a.test/\">x</a><a href=\"http://m.a.test/\">", "www.a.test", "m.a.test"))
                .isEqualTo(new AdminUrlsendActions.Result(1, "[www.a.test]反向链接正常，[m.a.test]反向链接正常"));
        assertThat(check("www.a.test", "www.a.test", "m.a.test"))
                .isEqualTo(new AdminUrlsendActions.Result(101, "[www.a.test]反向链接正常，[m.a.test]反向链接异常"));
        assertThat(check("m.a.test", "www.a.test", "m.a.test"))
                .isEqualTo(new AdminUrlsendActions.Result(1, "[www.a.test]反向链接异常，[m.a.test]反向链接正常"));
        assertThat(check(null, "www.a.test", "m.a.test"))
                .isEqualTo(new AdminUrlsendActions.Result(101, "[www.a.test]反向链接异常，[m.a.test]反向链接异常"));
    }

    @Test
    void emptyDomainsAreNeverFoundAsInPhp73() {
        assertThat(check("www.a.test", "www.a.test", ""))
                .isEqualTo(new AdminUrlsendActions.Result(101, "[www.a.test]反向链接正常，[]反向链接异常"));
        assertThat(check("anything", "", ""))
                .isEqualTo(new AdminUrlsendActions.Result(101, "[]反向链接异常，[]反向链接异常"));
    }

    @Test
    void byteSearch() {
        assertThat(AdminUrlsendActions.contains("中文域名.测试/".getBytes(StandardCharsets.UTF_8), "域名.测试")).isTrue();
        assertThat(AdminUrlsendActions.contains("abc".getBytes(StandardCharsets.UTF_8), "abcd")).isFalse();
        assertThat(AdminUrlsendActions.contains("aab".getBytes(StandardCharsets.UTF_8), "ab")).isTrue();
        assertThat(AdminUrlsendActions.contains(new byte[0], "a")).isFalse();
    }
}
