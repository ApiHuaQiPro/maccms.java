package com.maccms.spike.thymeleaf.model;

/** 导航项。URL 由（将来的）URL 生成器给出，模板不拼 URL。 */
public record NavItem(String name, String url) {
}
