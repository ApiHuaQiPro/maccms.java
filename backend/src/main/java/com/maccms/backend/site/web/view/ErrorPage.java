package com.maccms.backend.site.web.view;

/**
 * 错误页与维护页（404、500、503、站点关闭）。文案是固定文本或配置的维护文案，按文本转义输出，
 * 从不包含异常信息或请求内容。
 */
public record ErrorPage(PageChrome chrome, String heading, String message) {
}
