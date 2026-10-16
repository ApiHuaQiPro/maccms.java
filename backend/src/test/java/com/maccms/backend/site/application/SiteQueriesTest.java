package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 站内查询串白名单（09 方案第 7.2.2 节）：只放行 vodSearchLink 的输出形态，路径规则（SitePaths）不受影响。 */
class SiteQueriesTest {

    @ParameterizedTest
    @ValueSource(strings = {"wd=a", "page=2", "area=%E5%A4%A7&wd=C%2B%2B+a", "wd=a%2Fb%5Cc%2Ed%25", "wd=..",
            "wd=a.html", "wd=-_.+", "area=1&by=hits&class=2&lang=3&level=9&order=asc&page=1000&wd=4&year=2020",
            "wd=%C3%A9", "year=2020&area=a"})
    void acceptsGeneratorOutput(String q) {
        assertThat(SiteQueries.isValid(q)).isTrue();
        assertThat(SiteQueries.check(q)).isEqualTo(q);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?wd=a", "wd=a#f", "wd=a?b", "wd=%e4", "wd=%2f", "wd=%00", "wd=%1F", "wd=%7F", "wd=%",
            "wd=%4", "wd=%G0", "wd=a b", "wd=a\tb", "wd=中", "wd=a'b", "wd=a\"b", "wd=a<b", "wd=a\\b", "wd=a/b",
            "wd=a~b", "wd=a*b", "wd=a=b", "foo=1", "keyword=a", "WD=a", "s=vod/detail/id/1", "actor=x", "wd=a&wd=b",
            "wd=", "wd", "=a", "wd=a&", "&wd=a", "wd=a&&page=2", "wd=a;page=2", "pageSize=10"})
    void rejectsEverythingElse(String q) {
        assertThat(SiteQueries.isValid(q)).as(q).isFalse();
        assertThatThrownBy(() -> SiteQueries.check(q)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("站内查询串不合法");
    }

    @ParameterizedTest
    @ValueSource(strings = {"index.php/vod/search.html?wd=a", "index.php/vod/search.html%2Fx", "index.php/a%2Eb"})
    void sitePathsStillRejectQueriesAndEncodedSeparators(String p) {
        assertThat(SitePaths.isValid(p)).isFalse();
    }

    @Test
    void nullAndOverlongAreRejected() {
        assertThat(SiteQueries.isValid(null)).isFalse();
        assertThat(SiteQueries.isValid("wd=" + "a".repeat(SitePaths.MAX_LENGTH))).isFalse();
    }
}
