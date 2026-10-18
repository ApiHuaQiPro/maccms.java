package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * B05 页面开关（独立验收返工第 3 项）：环境变量优先于旧配置；旧 show_verify/search_verify=1 在验证码接线（B08/B11）之前按临时关闭处理，
 * 不能被环境变量绕过（旧 Base.php:29-43、:53-71）。
 */
class SiteFeaturesTest {

    private static PhpArray app(String... kv) {
        Map<Object, PhpValue> app = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            app.put(kv[i], new PhpValue.PhpString(kv[i + 1]));
        }
        Map<Object, PhpValue> root = new LinkedHashMap<>();
        root.put("app", new PhpArray(app));
        return new PhpArray(root);
    }

    @Test
    void environmentOverridesLegacyConfigButNotTheVerifySettings() {
        SiteFeatures repo = SiteFeatures.from(new SiteFeatures.Properties(null, null),
                app("ajax_page", "0", "show", "0", "show_verify", "0", "search_verify", "0"));
        assertThat(repo).isEqualTo(SiteFeatures.CLOSED);
        assertThat(SiteFeatures.from(new SiteFeatures.Properties("true", "true"), app("show", "0")))
                .isEqualTo(new SiteFeatures(true, true, false));
        assertThat(SiteFeatures.from(null, app("ajax_page", "1", "show", "1")))
                .isEqualTo(new SiteFeatures(true, true, false));
        // show_verify=1：筛选页与 ajax_show 一律关闭，环境变量打开也不行
        assertThat(SiteFeatures.from(new SiteFeatures.Properties("true", "true"), app("show", "1", "show_verify", "1"))
                .show()).isFalse();
        // search_verify=1：搜索页（非 ajax）关闭，ajax_search 照常（旧版 ajax 入口不验证）
        SiteFeatures sv = SiteFeatures.from(null, app("search_verify", "1"));
        assertThat(sv.searchPageClosed(false)).isTrue();
        assertThat(sv.searchPageClosed(true)).isFalse();
        assertThat(SiteFeatures.from(null, null)).isEqualTo(SiteFeatures.CLOSED);
        assertThat(SiteFeatures.CLOSED.searchPageClosed(false)).isFalse();
        assertThatThrownBy(() -> SiteFeatures.from(new SiteFeatures.Properties("yes", null), null))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("yes");
    }
}
