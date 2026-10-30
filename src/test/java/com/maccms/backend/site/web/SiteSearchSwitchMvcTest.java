package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.site.config.SiteConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 搜索页开关 {@code maccms.site.search.enabled}（09 方案第 6.4、7.1 节）：默认关闭时路由不注册（搜索地址落到全局 404，
 * 与 MACC-14 现状一致），三页不受影响；取值只接受 true/false；站点关闭时搜索页同样 503 且不查库。
 */
class SiteSearchSwitchMvcTest {

    @Test
    void conditionAcceptsOnlyTrueOrFalseAndDoesNotEchoValue() {
        assertThat(SiteSearchEnabledCondition.isEnabled(null)).isFalse();
        assertThat(SiteSearchEnabledCondition.isEnabled(" ")).isFalse();
        assertThat(SiteSearchEnabledCondition.isEnabled("FALSE")).isFalse();
        assertThat(SiteSearchEnabledCondition.isEnabled(" True ")).isTrue();
        assertThatThrownBy(() -> SiteSearchEnabledCondition.isEnabled("yes-SYNTHETIC"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("maccms.site.search.enabled 只能是 true 或 false");
    }

    @Test
    void siteDisabledSkipsSearchValueValidation() {
        MockEnvironment env = new MockEnvironment().withProperty("maccms.site.search.enabled", "bogus");
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(env);
        assertThat(new SiteSearchEnabledCondition().matches(context, null)).isFalse();
        env.setProperty("maccms.site.enabled", "true");
        assertThatThrownBy(() -> new SiteSearchEnabledCondition().matches(context, null))
                .isInstanceOf(IllegalStateException.class);
        env.setProperty("maccms.site.search.enabled", "true");
        assertThat(new SiteSearchEnabledCondition().matches(context, null)).isTrue();
    }

    /** site 开启、搜索未设置（默认）：搜索控制器不存在。 */
    @Nested
    @WebMvcTest(controllers = {SiteSearchController.class, SiteContentController.class}, properties = {
            "maccms.site.enabled=true",
            "maccms.site.name=合成站",
            "maccms.site.base-url=http://maccms.local.test/"})
    @Import(SiteConfiguration.class)
    class DefaultOff {

        @Autowired
        MockMvc mvc;

        @Autowired
        ApplicationContext context;

        @Autowired
        RequestMappingHandlerMapping handlerMapping;

        @MockitoBean
        ContentReadService content;

        @Test
        void searchControllerIsNotRegistered() {
            assertThat(context.getBeanNamesForType(SiteSearchController.class)).isEmpty();
            assertThat(handlerMapping.getHandlerMethods().values())
                    .noneMatch(h -> h.getBeanType() == SiteSearchController.class);
            assertThat(handlerMapping.getHandlerMethods().keySet().toString()).doesNotContain("vod/search");
        }

        @ParameterizedTest
        @ValueSource(strings = {"/index.php/vod/search.html", "/index.php/vod/search.html?wd=x", "/index.php/vod/search",
                "/vod/search/"})
        void searchPathsFallThroughToGlobal404(String path) throws Exception {
            MvcResult r = mvc.perform(get(URI.create(path))).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(404);
            assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("data-site-page");
            verifyNoInteractions(content);
        }
    }

    /** 显式 false 同样不注册。 */
    @Nested
    @WebMvcTest(controllers = SiteSearchController.class, properties = {
            "maccms.site.enabled=true",
            "maccms.site.search.enabled=false",
            "maccms.site.name=合成站",
            "maccms.site.base-url=http://maccms.local.test/"})
    @Import(SiteConfiguration.class)
    class ExplicitOff {

        @Autowired
        ApplicationContext context;

        @MockitoBean
        ContentReadService content;

        @Test
        void searchControllerIsNotRegistered() {
            assertThat(context.getBeanNamesForType(SiteSearchController.class)).isEmpty();
        }
    }

    /** 站点关闭：搜索页 503 维护页，控制器不执行（不查库）。 */
    @Nested
    @WebMvcTest(controllers = SiteSearchController.class, properties = {
            "maccms.site.enabled=true",
            "maccms.site.search.enabled=true",
            "maccms.site.name=合成站",
            "maccms.site.base-url=http://maccms.local.test/",
            "maccms.site.status=closed"})
    @Import(SiteConfiguration.class)
    class SiteClosed {

        @Autowired
        MockMvc mvc;

        @MockitoBean
        ContentReadService content;

        @Test
        void searchIs503MaintenancePageWithoutQueries() throws Exception {
            MvcResult r = mvc.perform(get(URI.create("/index.php/vod/search.html?wd=x"))).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(503);
            assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo("3600");
            assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).selectFirst("h1").text())
                    .isEqualTo("站点维护中");
            verifyNoInteractions(content);
        }
    }
}
