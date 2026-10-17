package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.legacyconfig.application.VarExportParser;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 旧配置按字段并入站点设置（B02，11 文档第 4.4 节）：优先级、取值换算、不支持的写法与 URL 形态、错误信息不含取值。 */
class SiteLegacyConfigTest {

    static final String SECRET = "SYNTHETIC-SECRET-51c9";

    static final SiteProperties EMPTY_SITE = new SiteProperties("true", "", "https://www.maccms.test/", "", "", "", "",
            "", "", "false", "false");
    static final SiteUrlProperties EMPTY_URL = new SiteUrlProperties("", "");
    static final SiteImageProperties EMPTY_IMAGE = new SiteImageProperties("", "", "", "", "");

    /** 满足 URL 前提的最小旧配置，{@code extra} 是追加到 site 组之外的 PHP 片段。 */
    static PhpArray php(String site, String rewrite, String extra) {
        String src = "<?php\nreturn array (\n"
                + "  'site' => array ('install_dir' => '/', " + site + "),\n"
                + "  'app' => array ('pathinfo_depr' => '/'),\n"
                + "  'rewrite' => array ('route_status' => '0', 'vod_id' => '0', 'type_id' => '0', " + rewrite + "),\n"
                + "  'view' => array ('index' => '0', 'vod_type' => '0', 'vod_detail' => '0'),\n"
                + extra + ");";
        return VarExportParser.parse(src.getBytes(StandardCharsets.UTF_8));
    }

    static SiteLegacyConfig.Merged merge(SiteProperties site, PhpArray legacy) {
        return SiteLegacyConfig.merge(site, EMPTY_URL, EMPTY_IMAGE, legacy);
    }

    @Test
    void emptyEnvironmentTakesEveryFieldFromLegacy() {
        PhpArray legacy = php("'site_name' => '旧站', 'site_keywords' => 'k', 'site_description' => 'd', "
                        + "'site_icp' => 'icp', 'site_logo' => '/logo.png', 'site_status' => '0', 'site_close_tip' => 'tip'",
                "'status' => '1', 'suffix_hide' => '1'",
                "  'upload' => array ('protocol' => 'https', 'mode' => 'remote', 'remoteurl' => 'https://cdn.test/', "
                        + "'img_key' => 'a\\\\.test', 'img_api' => '/img.php?url='),\n");
        SiteLegacyConfig.Merged m = merge(EMPTY_SITE, legacy);

        assertThat(m.site()).isEqualTo(new SiteProperties("true", "旧站", "https://www.maccms.test/", "k", "d", "icp",
                "/logo.png", "closed", "tip", "false", "false"));
        assertThat(m.url()).isEqualTo(new SiteUrlProperties("true", "true"));
        assertThat(m.image()).isEqualTo(new SiteImageProperties("https", "remote", "https://cdn.test/", "a\\.test",
                "/img.php?url="));
        assertThat(m.fromLegacy()).containsEntry("maccms.site.name", "site.site_name")
                .containsEntry("maccms.site.status", "site.site_status")
                .containsEntry("maccms.site.url.rewrite", "rewrite.status")
                .containsEntry("maccms.site.image.img-key", "upload.img_key")
                .doesNotContainKey("maccms.site.base-url");
    }

    @Test
    void nonBlankEnvironmentValueWinsFieldByField() {
        PhpArray legacy = php("'site_name' => '旧站', 'site_keywords' => 'k', 'site_status' => '0', "
                        + "'site_close_tip' => 'tip'",
                "'status' => '1', 'suffix_hide' => '1'",
                "  'upload' => array ('protocol' => 'https', 'mode' => 'local'),\n");
        SiteProperties env = new SiteProperties("true", "环境站", "https://www.maccms.test/", " ", null, "", "", "open",
                "", "false", "false");
        SiteLegacyConfig.Merged m = SiteLegacyConfig.merge(env, new SiteUrlProperties("false", ""),
                new SiteImageProperties("http", "", "", "", ""), legacy);

        assertThat(m.site().name()).isEqualTo("环境站");
        assertThat(m.site().keywords()).isEqualTo("k");
        assertThat(m.site().status()).isEqualTo("open");
        assertThat(m.site().closedMessage()).isEqualTo("tip");
        assertThat(m.url()).isEqualTo(new SiteUrlProperties("false", "true"));
        assertThat(m.image().protocol()).isEqualTo("http");
        assertThat(m.image().mode()).isEqualTo("local");
        assertThat(m.fromLegacy()).doesNotContainKeys("maccms.site.name", "maccms.site.status",
                "maccms.site.url.rewrite", "maccms.site.image.protocol");
    }

    @Test
    void missingLegacyKeysKeepEnvironmentDefaults() {
        SiteLegacyConfig.Merged m = merge(EMPTY_SITE, php("'site_name' => 'x'", "'status' => '0'", ""));
        assertThat(m.site().status()).isEmpty();
        assertThat(m.url().suffixHide()).isEmpty();
        assertThat(m.image()).isEqualTo(EMPTY_IMAGE);
    }

    @Test
    void noLegacyConfigKeepsEverything() {
        SiteLegacyConfig.Merged m = SiteLegacyConfig.none(EMPTY_SITE, EMPTY_URL, EMPTY_IMAGE);
        assertThat(m.site()).isSameAs(EMPTY_SITE);
        assertThat(m.fromLegacy()).isEmpty();
        assertThat(m.validate(() -> "ok")).isEqualTo("ok");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "'0'|closed", "'1'|open", "''|open", "0|closed", "1|open", "' 0'|closed", "'0 '|closed", "'0.0'|closed",
            "'00'|closed", "'-0'|closed", "'2'|open", "'1e0'|open", "'.0'|closed"})
    void siteStatusFollowsLegacyEqualsZero(String php, String expected) {
        SiteLegacyConfig.Merged m = merge(EMPTY_SITE, php("'site_status' => " + php, "'status' => '0'", ""));
        assertThat(m.site().status()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"'abc'", "' '", "'0abc'", "true", "NULL", "array ()", "1.5"})
    void siteStatusUnsupportedValuesAreRejected(String php) {
        assertThatThrownBy(() -> merge(EMPTY_SITE, php("'site_status' => " + php, "'status' => '0'", "")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("site.site_status");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {"'0'|false", "'1'|true", "0|false", "1|true", "' 1'|true", "'1.0'|true"})
    void rewriteStatusAcceptsZeroOrOne(String php, String expected) {
        assertThat(merge(EMPTY_SITE, php("", "'status' => " + php, "")).url().rewrite()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"'2'", "''", "'x'", "-1"})
    void rewriteStatusOtherValuesAreRejected(String php) {
        assertThatThrownBy(() -> merge(EMPTY_SITE, php("", "'status' => " + php, "")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("rewrite.status");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {"'0'|false", "'1'|true", "''|false", "'2'|false", "1|true"})
    void suffixHideIsLegacyEqualsOne(String php, String expected) {
        assertThat(merge(EMPTY_SITE, php("", "'status' => '0', 'suffix_hide' => " + php, "")).url().suffixHide())
                .isEqualTo(expected);
    }

    @Test
    void suffixHideNonNumericIsRejected() {
        assertThatThrownBy(() -> merge(EMPTY_SITE, php("", "'status' => '0', 'suffix_hide' => 'yes'", "")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("rewrite.suffix_hide");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "'rewrite' => array ('route_status' => '1')|rewrite.route_status",
            "'app' => array ('pathinfo_depr' => '-')|app.pathinfo_depr",
            "'site' => array ('install_dir' => '/sub/')|site.install_dir",
            "'site' => array ('install_dir' => '')|site.install_dir",
            "'rewrite' => array ('vod_id' => '1')|rewrite.vod_id",
            "'rewrite' => array ('type_id' => '2')|rewrite.type_id",
            "'view' => array ('vod_detail' => '2')|view.vod_detail",
            "'view' => array ('index' => '2')|view.index",
            "'view' => array ('vod_type' => 'x')|view.vod_type"})
    void unsupportedUrlShapesFailInsteadOfGeneratingDifferentUrls(String override, String key) {
        // 后写的同名组整体覆盖前面的组（PHP 数组重复键后者覆盖），其余前提由 php() 给出
        PhpArray legacy = VarExportParser.parse(("<?php return array ('site' => array ('install_dir' => '/'), "
                + "'app' => array ('pathinfo_depr' => '/'), " + override + ");").getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> merge(EMPTY_SITE, legacy))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(key)
                .hasMessageContaining("extra/maccms.php");
    }

    @ParameterizedTest
    @ValueSource(strings = {"''", "'0'", "NULL"})
    void emptyPathinfoDeprMeansSlash(String php) {
        PhpArray legacy = VarExportParser.parse(("<?php return array ('site' => array ('install_dir' => '/'), "
                + "'app' => array ('pathinfo_depr' => " + php + "));").getBytes(StandardCharsets.UTF_8));
        assertThat(merge(EMPTY_SITE, legacy).fromLegacy()).isEmpty();
    }

    @Test
    void errorsNameLegacyKeysButNeverValues() {
        PhpArray legacy = VarExportParser.parse(("<?php return array ('site' => array ('install_dir' => '" + SECRET
                + "', 'site_status' => '" + SECRET + "', 'site_name' => array ('" + SECRET + "')), "
                + "'rewrite' => array ('route_status' => '" + SECRET + "', 'status' => '" + SECRET + "'));")
                .getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> merge(EMPTY_SITE, legacy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("site.install_dir").hasMessageContaining("site.site_status")
                .hasMessageContaining("site.site_name").hasMessageContaining("rewrite.route_status")
                .hasMessageContaining("rewrite.status")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void validationFailureNamesTheLegacySourceWithoutValues() {
        SiteLegacyConfig.Merged m = merge(EMPTY_SITE, php("'site_name' => '', 'site_logo' => 'javascript:" + SECRET + "'",
                "'status' => '0'", ""));
        assertThatThrownBy(() -> m.validate(() -> SiteSettings.from(m.site())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maccms.site.name").hasMessageContaining("maccms.site.logo-url")
                .hasMessageContaining("maccms.site.logo-url←site.site_logo")
                .hasMessageContaining("maccms.site.name←site.site_name")
                .hasMessageNotContaining(SECRET);
    }

    /** 仓库自带的 maccms.php（旧站默认值）满足全部 URL 前提，各项按旧版语义换算。 */
    @Test
    void repositoryMaccmsPhpMergesWithLegacyDefaults() throws Exception {
        PhpArray repo = VarExportParser.parse(Files.readAllBytes(Path.of("..", "application", "extra", "maccms.php")));
        SiteLegacyConfig.Merged m = merge(EMPTY_SITE, repo);

        assertThat(m.site().name()).isEmpty();
        assertThat(m.site().icp()).isEqualTo("京ICP证000001");
        assertThat(m.site().status()).isEqualTo("open");
        assertThat(m.site().closedMessage()).isEqualTo("站点临时维护，10分钟后恢复。");
        assertThat(m.url()).isEqualTo(new SiteUrlProperties("false", "false"));
        assertThat(m.image()).isEqualTo(new SiteImageProperties("http", "local", "http://img.test.com/", "",
                "/img.php?url="));
        // 旧默认 site_name 为空：仍须由环境变量给出站名，否则启动失败并指出来源
        assertThatThrownBy(() -> m.validate(() -> SiteSettings.from(m.site())))
                .hasMessageContaining("maccms.site.name←site.site_name");
        SiteLegacyConfig.Merged named = merge(new SiteProperties("true", "合成站", "https://www.maccms.test/", "", "", "",
                "", "", "", "false", "false"), repo);
        SiteSettings settings = named.validate(() -> SiteSettings.from(named.site()));
        assertThat(settings.closed()).isFalse();
        assertThat(named.validate(() -> named.url().toStyle())).isEqualTo(LegacyUrlStyle.REPO_DEFAULT);
        assertThat(named.validate(() -> named.image().toNormalizer(settings))).isNotNull();
    }
}
