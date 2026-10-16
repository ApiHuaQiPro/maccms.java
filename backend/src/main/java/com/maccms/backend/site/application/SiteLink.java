package com.maccms.backend.site.application;

/**
 * 站内地址 = 站内路径 + 可选查询串（09 方案第 7.2.2 节，SSR 搜索页的"固定路径 + 查询串"链接）。
 * 构造时校验：路径走 {@link SitePaths}（规则不变），查询串走 {@link SiteQueries}，整个 {@code path?query} 与站内路径共用
 * 同一预算 {@link SitePaths#MAX_LENGTH}，所以任何合法 base-url 拼出的结果都不超过 {@link SafeUrl#MAX_LENGTH}。
 * 不合法时抛 {@link IllegalArgumentException}，信息不含取值。由 {@link SiteSettings#publicPath(SiteLink)} /
 * {@link SiteSettings#canonical(SiteLink)} 转成 {@link SafeUrl}。
 *
 * @param path  相对站点根的路径（不以 / 开头）
 * @param query 查询串（不含 ?）；空串表示没有查询串
 */
public record SiteLink(String path, String query) {

    public SiteLink {
        SitePaths.check(path);
        if (query == null) {
            throw new IllegalArgumentException("站内查询串不合法");
        }
        if (!query.isEmpty()) {
            SiteQueries.check(query);
        }
        if (path.length() + (query.isEmpty() ? 0 : 1 + query.length()) > SitePaths.MAX_LENGTH) {
            throw new IllegalArgumentException("站内地址不合法");
        }
    }

    /** 相对站点根的地址：{@code path} 或 {@code path?query}。 */
    public String relative() {
        return query.isEmpty() ? path : path + "?" + query;
    }
}
