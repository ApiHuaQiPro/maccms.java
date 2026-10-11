package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.site.config.SiteConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 默认（未设置 maccms.site.enabled）：不注册任何 site 组件与页面路由，请求落到全局 JSON 404。
 * 站点配置即使不合法也不会被校验（这里故意给出非法 base-url）。
 */
@WebMvcTest(controllers = {SitePlaceholderController.class, SiteTestRoutes.class},
        properties = {"maccms.site.base-url=javascript:alert(1)", "maccms.site.status=bogus"})
@Import({SiteConfiguration.class, SiteTestRoutes.class})
class SiteDisabledMvcTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void noSiteBeansAreRegistered() {
        assertThat(Arrays.stream(context.getBeanDefinitionNames())
                .map(context::getType)
                .filter(t -> t != null && t.getName().startsWith("com.maccms.backend.site")))
                .isEmpty();
    }

    @Test
    void noSiteRoutesAreRegistered() {
        assertThat(handlerMapping.getHandlerMethods().values())
                .noneMatch(h -> h.getBeanType().getName().startsWith("com.maccms.backend.site"));
    }

    @Test
    void placeholderPathFallsThroughToGlobalJson404() throws Exception {
        MvcResult r = mvc.perform(get("/_site/placeholder")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        assertThat(r.getResponse().getContentType()).startsWith("application/json");
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":\"NOT_FOUND\"");
    }
}
