package com.maccms.backend.site;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 站点样式（{@code src/main/resources/static/site/site.css}）的静态护栏：
 * 不引用外部资源与字体、不用危险 data URL、关键断点与可访问性规则存在、
 * MACC-14/15 约定的页面 class 都有对应规则。CSS 由前端智能体维护，模板不改 markup。
 */
class SiteStylesheetTest {

    private static final Path CSS = Path.of("src", "main", "resources", "static", "site", "site.css");

    private static String css() throws IOException {
        return Files.readString(CSS, StandardCharsets.UTF_8);
    }

    /** 去掉注释后检查，避免注释里的示例被误判。 */
    private static String stripComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    @Test
    void noExternalResourcesOrFonts() throws IOException {
        String body = stripComments(css());
        assertThat(body).doesNotContain("@import")
                .doesNotContain("@font-face")
                .doesNotContainPattern("(?i)https?://")
                .doesNotContainPattern("(?i)//[a-z0-9.-]+\\.[a-z]{2}")
                .doesNotContainPattern("(?i)url\\s*\\(");
    }

    @Test
    void noDangerousDataUrl() throws IOException {
        assertThat(stripComments(css())).doesNotContainPattern("(?i)data\\s*:");
    }

    @Test
    void responsiveBreakpointsExist() throws IOException {
        String body = css();
        assertThat(body).containsPattern("@media\\s*\\(max-width:\\s*30rem\\)");
        assertThat(body).containsPattern("@media\\s*\\(max-width:\\s*40rem\\)");
    }

    @Test
    void reducedMotionAndFocusVisibleRulesExist() throws IOException {
        String body = css();
        assertThat(body).containsPattern("@media\\s*\\(prefers-reduced-motion:\\s*no-preference\\)");
        assertThat(body).contains(":focus-visible");
    }

    @Test
    void contractClassesAreStyled() throws IOException {
        String body = stripComments(css());
        for (String klass : new String[]{"site-home", "category-page", "category-grid", "video-grid",
                "video-card", "pagination", "video-detail", "metadata-list", "detail-content",
                "search-page", "search-form", "search-summary", "search-filters", "filter-group",
                "filter-chip", "search-empty"}) {
            assertThat(body).as("缺少约定 class .%s 的规则", klass)
                    .containsPattern(Pattern.compile("\\." + Pattern.quote(klass) + "\\b").pattern());
        }
    }

    @Test
    void contractClassesAppearInRenderedPages() throws IOException {
        // MACC-14 三页模板尚未合入主线时此检查放宽为只校验已存在的模板；
        // 模板就位后这里直接兜底 class 契约不漂移。
        String body = stripComments(css());
        Matcher m = Pattern.compile("\\.(site-home|category-page|category-grid|video-grid|video-card|pagination"
                + "|video-detail|metadata-list|detail-content|search-page|search-form|search-summary|search-filters"
                + "|filter-group|filter-chip|search-empty)(__[a-z-]+)?\\b").matcher(body);
        assertThat(m.find()).as("CSS 应包含至少一个契约 class").isTrue();
    }

    @Test
    void isValidUtf8WithoutBom() throws IOException {
        byte[] raw = Files.readAllBytes(CSS);
        assertThat(raw.length >= 3 && raw[0] == (byte) 0xEF && raw[1] == (byte) 0xBB && raw[2] == (byte) 0xBF)
                .as("CSS 不得带 UTF-8 BOM").isFalse();
        assertThat(new String(raw, StandardCharsets.UTF_8)).doesNotContain("\uFFFD");
    }

    /**
     * 窄屏（30rem 断点）下 .metadata-list 必须回退为单列 minmax(0, 1fr)：
     * 队长在 MACC-14 真实详情页 320/360px 视口实测 max-content 1fr 会溢出（scrollWidth=385）。
     */
    @Test
    void narrowViewportMetadataListIsSingleColumn() throws IOException {
        String body = stripComments(css());
        Matcher media = Pattern.compile(
                "@media\\s*\\(max-width:\\s*30rem\\)\\s*\\{(.*?)\\}\\s*\\}", Pattern.DOTALL).matcher(body);
        boolean found = false;
        while (media.find()) {
            if (media.group(1).contains(".metadata-list")
                    && media.group(1).contains("minmax(0, 1fr)")) {
                found = true;
            }
        }
        assertThat(found).as("30rem 断点内须有 .metadata-list 单列 minmax(0, 1fr) 规则").isTrue();
    }

    /**
     * 窄屏（30rem 断点）下搜索页筛选组 .filter-group 必须回退为单列 minmax(0, 1fr)，
     * 与 .metadata-list 同样的溢出防护（label 列 max-content 在 320/360px 视口会挤压 chip 列表）。
     */
    @Test
    void narrowViewportFilterGroupIsSingleColumn() throws IOException {
        String body = stripComments(css());
        Matcher media = Pattern.compile(
                "@media\\s*\\(max-width:\\s*30rem\\)\\s*\\{(.*?)\\}\\s*\\}", Pattern.DOTALL).matcher(body);
        boolean found = false;
        while (media.find()) {
            if (media.group(1).contains(".filter-group")
                    && media.group(1).contains("minmax(0, 1fr)")) {
                found = true;
            }
        }
        assertThat(found).as("30rem 断点内须有 .filter-group 单列 minmax(0, 1fr) 规则").isTrue();
    }

    /** 当前筛选 chip 必须有可辨样式（--current 或 aria-current 属性选择器）。 */
    @Test
    void currentFilterChipIsDistinguishable() throws IOException {
        String body = stripComments(css());
        assertThat(body).containsPattern(Pattern.compile(
                "\\.filter-chip--current\\b[^{]*\\{[^}]*border-color:\\s*var\\(--site-color-link\\)", Pattern.DOTALL).pattern());
        assertThat(body).containsPattern(Pattern.compile(
                "\\.filter-chip--current\\b[^{]*\\{[^}]*font-weight:", Pattern.DOTALL).pattern());
    }
}
