package com.maccms.spike.jte.model;

/** 合成站点设置（P4 的最小形态）：站名与站点根 URL，均由代码给出，不读任何外部配置。 */
public record SiteSettings(String name, String baseUrl) {
}
