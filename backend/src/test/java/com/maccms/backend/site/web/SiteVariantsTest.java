package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.site.web.view.SiteVariant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@link SiteVariants}：旧配置到每个请求的选择（B12 承接 D-B05-8，21 文档第 12.3 节）。选择本身的逐项语义由
 * SiteTemplateSelectionGoldenTest 对 PHP 黄金锁定，这里只验证接线：过滤器写入请求属性、{@link SiteVariants#current()} 的取值与
 * 默认值、JSON {@code msg} 的语言、{@code Vary} 判定与手机 Logo 校验。
 */
class SiteVariantsTest {

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static PhpArray maccms(String mobStatus, String lang, String waplogo) {
        Map<Object, PhpValue> site = new LinkedHashMap<>();
        site.put("install_dir", new PhpValue.PhpString("/"));
        site.put("site_wapurl", new PhpValue.PhpString("m.test"));
        site.put("site_waplogo", new PhpValue.PhpString(waplogo));
        site.put("mob_status", new PhpValue.PhpString(mobStatus));
        site.put("template_dir", new PhpValue.PhpString("default"));
        site.put("html_dir", new PhpValue.PhpString("html"));
        site.put("ads_dir", new PhpValue.PhpString("ads"));
        site.put("mob_template_dir", new PhpValue.PhpString("wapt"));
        site.put("mob_html_dir", new PhpValue.PhpString("html"));
        site.put("mob_ads_dir", new PhpValue.PhpString("wads"));
        Map<Object, PhpValue> app = new LinkedHashMap<>();
        app.put("lang", new PhpValue.PhpString(lang));
        Map<Object, PhpValue> root = new LinkedHashMap<>();
        root.put("site", new PhpArray(site));
        root.put("app", new PhpArray(app));
        return new PhpArray(root);
    }

    private static SiteVariant through(SiteVariants v, String ua, String host) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/");
        if (ua != null) {
            req.addHeader("User-Agent", ua);
        }
        req.addHeader("Host", host);
        SiteVariant[] seen = new SiteVariant[1];
        v.filter().doFilter(req, new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes((MockHttpServletRequest) request));
                seen[0] = SiteVariants.current();
            }
        });
        return seen[0];
    }

    @Test
    void filterResolvesEachRequestFromUserAgentAndHost() throws Exception {
        SiteVariants v = SiteVariants.from(maccms("1", "zh-tw", "/wap.png"), null);
        SiteVariant wap = through(v, "Mozilla/5.0 (Linux; Android 14)", "m.test");
        assertThat(wap.wap()).isTrue();
        assertThat(wap.langCode()).isEqualTo("zh-tw");
        assertThat(wap.pathTpl()).isEqualTo("/template/wapt/html/");
        assertThat(wap.pathAds()).isEqualTo("/template/wapt/wads/");
        assertThat(through(v, "Mozilla/5.0 (Linux; Android 14)", "www.test").wap()).isFalse();
        assertThat(through(v, "Mozilla/5.0 (Windows NT 10.0)", "m.test").wap()).isFalse();
        // 没有 UA：按桌面（D-B12-L2）
        SiteVariant none = through(v, null, "m.test");
        assertThat(none.wap()).isFalse();
        assertThat(none.pathTpl()).isEqualTo("/template/default/html/");
        assertThat(v.dependsOnUserAgent()).isTrue();
        assertThat(v.wapLogo()).isPresent();
    }

    @Test
    void outsideARequestOrWithoutLegacyConfigItIsDesktopSimplified() throws Exception {
        assertThat(SiteVariants.current()).isEqualTo(SiteVariant.DEFAULT);
        SiteVariants none = SiteVariants.none();
        assertThat(through(none, "iPhone", "m.test")).isEqualTo(new SiteVariant(false, false, SiteVariant.DEFAULT.lang(), "", ""));
        assertThat(none.dependsOnUserAgent()).isFalse();
        assertThat(SiteVariants.from(maccms("0", "", ""), null).dependsOnUserAgent()).isFalse();
        assertThat(SiteVariants.from(maccms("2", "", ""), null).dependsOnUserAgent()).isTrue();
        assertThat(SiteVariants.from(maccms("abc", "", ""), null).dependsOnUserAgent()).isFalse();
    }

    @Test
    void jsonMsgFollowsTheSelectedPack() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 1001);
        body.put("msg", "参数错误");
        body.put("data", List.of("参数错误"));
        assertThat(SiteVariants.localizeMsg(body)).isSameAs(body);
        RequestContextHolder.currentRequestAttributes().setAttribute(SiteVariants.ATTRIBUTE,
                SiteVariants.from(maccms("0", "zh-tw", ""), null).resolve("x", "h"), 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> tw = (Map<String, Object>) SiteVariants.localizeMsg(body);
        assertThat(tw).containsExactly(Map.entry("code", 1001), Map.entry("msg", "參數錯誤"),
                Map.entry("data", List.of("参数错误")));
        assertThat(body.get("msg")).as("不改调用方的对象").isEqualTo("参数错误");
        assertThat(SiteVariants.localizeMsg(List.of("参数错误"))).isEqualTo(List.of("参数错误"));
        assertThat(SiteVariants.text("验证码错误")).isEqualTo("驗證碼錯誤");
    }

    @Test
    void wapLogoFollowsTheLogoRules() {
        assertThatThrownBy(() -> SiteVariants.from(maccms("2", "", "javascript:alert(1)"), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("site_waplogo")
                .hasMessageNotContaining("javascript");
        assertThat(SiteVariants.from(maccms("2", "", "https://cdn.test/w.png"), null).wapLogo()).isPresent();
        assertThat(SiteVariants.from(maccms("2", "", "  "), null).wapLogo()).isEmpty();
    }
}
