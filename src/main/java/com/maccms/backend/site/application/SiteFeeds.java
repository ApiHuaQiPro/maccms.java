package com.maccms.backend.site.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 站点级 RSS 与搜索引擎地图（B06，旧 {@code rss/index|baidu|google|so|sogou|bing|sm}，index/controller/Rss.php:13-53，
 * 16 文档第 4.2 节）。旧版输出完全由模板决定，仓库模板缺失（D9-a），新版固定为：
 * <ul>
 * <li>{@code rss/index}：RSS 2.0，一个 channel + 最近更新的视频（标题、链接、简介纯文本、发布时间、guid）；</li>
 * <li>其余六种：Sitemap 0.9（{@code urlset}），站点首页 + 最近更新的视频详情页（loc、lastmod、changefreq、priority）。</li>
 * </ul>
 * 文本与属性按 XML 转义，XML 1.0 不允许的字符删除（{@link RssDocument#escape}）。
 */
public final class SiteFeeds {

    /** RSS 的条目数。 */
    public static final int RSS_ITEMS = 50;
    /** 地图的视频条目数（首页另计）。 */
    public static final int SITEMAP_ITEMS = 1000;
    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private SiteFeeds() {
    }

    /**
     * @param title       标题（已解码一次的纯文本）
     * @param link        详情页 canonical 绝对地址
     * @param description 简介纯文本，可为空串
     * @param updatedAt   更新时间（Unix 秒），0 时不输出 pubDate
     */
    public record Item(String title, SafeUrl link, String description, long updatedAt) {
    }

    /**
     * @param link      canonical 绝对地址
     * @param updatedAt 更新时间（Unix 秒），0 时不输出 lastmod
     */
    public record Entry(SafeUrl link, long updatedAt) {
    }

    public static String rss(String channelTitle, SafeUrl channelLink, String channelDescription, List<Item> items) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rss version=\"2.0\">\n<channel>\n");
        element(sb, "title", channelTitle);
        element(sb, "link", channelLink.href());
        element(sb, "description", channelDescription);
        for (Item item : items) {
            sb.append("<item>\n");
            element(sb, "title", item.title());
            element(sb, "link", item.link().href());
            element(sb, "description", item.description());
            if (item.updatedAt() > 0) {
                element(sb, "pubDate", RFC_1123.format(Instant.ofEpochSecond(item.updatedAt())));
            }
            sb.append("<guid isPermaLink=\"true\">").append(RssDocument.escape(item.link().href())).append("</guid>\n");
            sb.append("</item>\n");
        }
        sb.append("</channel>\n</rss>\n");
        return sb.toString();
    }

    /**
     * @param home    站点首页（canonical 绝对地址），{@code changefreq=daily}、{@code priority=1.0}
     * @param entries 详情页，{@code changefreq=weekly}、{@code priority=0.8}
     */
    public static String sitemap(SafeUrl home, List<Entry> entries) {
        StringBuilder sb = new StringBuilder(64 * (entries.size() + 2));
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        url(sb, home, 0, "daily", "1.0");
        for (Entry e : entries) {
            url(sb, e.link(), e.updatedAt(), "weekly", "0.8");
        }
        sb.append("</urlset>\n");
        return sb.toString();
    }

    private static void url(StringBuilder sb, SafeUrl loc, long updatedAt, String changefreq, String priority) {
        sb.append("<url>");
        sb.append("<loc>").append(RssDocument.escape(loc.href())).append("</loc>");
        if (updatedAt > 0) {
            sb.append("<lastmod>").append(DATE.format(Instant.ofEpochSecond(updatedAt))).append("</lastmod>");
        }
        sb.append("<changefreq>").append(changefreq).append("</changefreq>");
        sb.append("<priority>").append(priority).append("</priority>");
        sb.append("</url>\n");
    }

    private static void element(StringBuilder sb, String name, String text) {
        sb.append('<').append(name).append('>').append(RssDocument.escape(text)).append("</").append(name).append(">\n");
    }
}
