package com.maccms.backend.integration.backlink;

import com.maccms.backend.integration.application.BacklinkPages;
import com.maccms.backend.integration.http.OutboundHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 友链回链检测的页面抓取的装配（B28）。超时与响应大小用出站客户端的统一上限，不新增配置项（路线图 B28 行）。 */
@Configuration(proxyBeanMethods = false)
public class BacklinkConfiguration {

    @Bean
    BacklinkPages backlinkPages(OutboundHttpClient client) {
        return new BacklinkFetcher(client);
    }
}
