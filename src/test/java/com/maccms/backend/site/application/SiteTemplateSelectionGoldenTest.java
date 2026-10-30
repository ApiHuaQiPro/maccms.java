package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.legacyconfig.application.VarExportParser;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 手机模板、站群与语言的选择和旧 Init 行为逐项相同（B12 承接 B05 D-B05-8；黄金由 template_select_golden.php 在 PHP 8.0、7.3 上生成，
 * 每个用例一个 php 进程执行旧 {@code common/behavior/Init.php}）。有意差异：没有 User-Agent 时旧版整页报错、新版按桌面（D-B12-L2）；
 * {@code app.lang} 不是 zh-cn/zh-tw 时旧版显示键名（Windows 上大小写不同的 ZH-TW 因文件名不分大小写而命中繁体）、新版回退简体（D-B12-L1）。
 */
class SiteTemplateSelectionGoldenTest {

    private static final Set<String> SITE_KEYS = Set.of("template_dir", "html_dir", "ads_dir", "mob_template_dir",
            "mob_html_dir", "mob_ads_dir", "site_url", "site_wapurl", "site_name", "install_dir");

    @ParameterizedTest
    @ValueSource(strings = {"site/template-select/php80-golden.json", "site/template-select/php73-golden.json"})
    void selectionMatchesLegacyInit(String resource) throws Exception {
        JsonNode golden;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            golden = JsonMapper.builder().build().readTree(in);
        }
        PhpArray repoSite = (PhpArray) VarExportParser.parse(Files.readAllBytes(
                Path.of("php", "application", "extra", "maccms.php"))).get("site");
        List<String> mismatches = new ArrayList<>();
        int compared = 0;
        for (JsonNode c : golden.get("cases")) {
            String label = c.get("label").asString();
            JsonNode in = c.get("input");
            JsonNode r = c.get("result");
            Map<Object, PhpValue> site = new LinkedHashMap<>(repoSite.entries());
            in.get("site").properties().forEach(e -> site.put(e.getKey(), php(e.getValue())));
            PhpValue domain = in.has("domain") ? php(in.get("domain")) : new PhpArray(Map.of());
            String ua = in.get("ua").isNull() ? null : in.get("ua").asString();
            SiteTemplateSelection.Result j = SiteTemplateSelection.resolve(new PhpArray(site), domain, ua,
                    in.get("host").asString());

            if (r.has("error")) {
                // D-B12-L2：没有 User-Agent 按桌面
                assertThat(label).isEqualTo("ua-missing");
                assertThat(j.wap()).isFalse();
                assertThat(j.pathTpl()).isEqualTo("/template/default/html/");
                continue;
            }
            List<String> php = List.of("mob=" + r.get("mob").asInt(), "tpl=" + r.get("path_tpl").asString(),
                    "ads=" + r.get("path_ads").asString(), "template=" + r.get("path_template").asString(),
                    "root=" + r.get("root_template").asString());
            List<String> java = List.of("mob=" + (j.wap() ? 1 : 0), "tpl=" + j.pathTpl(), "ads=" + j.pathAds(),
                    "template=" + j.pathTemplate(), "root=template/" + j.templateDir() + "/" + j.htmlDir() + "/");
            if (!php.equals(java)) {
                mismatches.add(label + " php=" + php + " java=" + java);
            }
            for (String k : SITE_KEYS) {
                String p = r.get("site").has(k) ? r.get("site").get(k).asString() : null;
                if (p != null && !p.equals(j.site().get(k))) {
                    mismatches.add(label + " site." + k + " php=" + p + " java=" + j.site().get(k));
                }
            }
            // 语言：app.lang → 语言包与 lang() 的结果
            String configured = in.has("lang") ? in.get("lang").asString() : "zh-cn";
            LegacyLang.Selection sel = LegacyLang.select(configured);
            JsonNode sample = r.get("lang_sample");
            if (Set.of("zh-cn", "zh-tw", "").contains(configured)) {
                assertThat(sel.fallback()).isFalse();
                assertThat(sel.pack().code()).as(label).isEqualTo(r.get("default_lang").asString().isEmpty()
                        ? "zh-cn" : r.get("default_lang").asString());
                assertThat(List.of(sel.pack().get("permission_denied"), sel.pack().get("menu/admin"), sel.pack().get("vod")))
                        .as(label).containsExactly(sample.get(0).asString(), sample.get(1).asString(), sample.get(2).asString());
            } else {
                // D-B12-L1：旧版 en-us 显示键名（ZH-TW 在 Windows 上命中繁体、Linux 上显示键名）；新版回退简体并告警
                assertThat(sel).isEqualTo(new LegacyLang.Selection(LegacyLang.of("zh-cn"), true));
                assertThat(sample.get(0).asString()).isIn("permission_denied", "您沒有權限訪問此頁面");
            }
            compared++;
        }
        assertThat(mismatches).isEmpty();
        assertThat(compared).isEqualTo(92);
    }

    private static PhpValue php(JsonNode v) {
        if (v.isTextual()) {
            return new PhpValue.PhpString(v.asString());
        }
        if (v.isIntegralNumber()) {
            return new PhpValue.PhpInt(v.asLong());
        }
        if (v.isObject()) {
            Map<Object, PhpValue> m = new LinkedHashMap<>();
            v.properties().forEach(e -> m.put(PhpArray.normalizeKey(e.getKey()), php(e.getValue())));
            return new PhpArray(m);
        }
        if (v.isArray() && v.isEmpty()) {
            return new PhpArray(Map.of());
        }
        throw new IllegalArgumentException("unsupported");
    }
}
