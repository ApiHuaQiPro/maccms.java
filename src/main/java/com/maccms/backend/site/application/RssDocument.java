package com.maccms.backend.site.application;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 单条内容的 RSS 2.0 文档（B05，旧 vod/rss、art/rss、topic/rss、actor/rss、role/rss、website/rss、plot/rss，15 文档第 7 节）。
 * 旧模板缺失，新版固定为一个 channel + 一个 item：标题、链接、描述（简介纯文本）、发布时间（更新时间）、guid（与链接相同）。
 * 正文不输出。所有文本按 XML 转义；XML 1.0 不允许的字符（C0 控制字符、U+FFFE/U+FFFF、孤立的代理项）删除。
 * 站点级 RSS（rss/index、rss/baidu 等）归 B06。
 */
public final class RssDocument {

    /** RFC 822 / RFC 1123 时间（RSS 2.0 pubDate），按 GMT 输出，与时区设置无关。 */
    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private RssDocument() {
    }

    /**
     * @param channelTitle       站名
     * @param channelLink        站点首页（canonical 绝对地址）
     * @param channelDescription 站点描述，可为空串
     * @param title              内容标题（已解码一次的纯文本）
     * @param link               内容详情页的 canonical 绝对地址
     * @param description        简介纯文本（已解码一次），可为空串
     * @param updatedAt          更新时间（Unix 秒），0 时不输出 pubDate
     */
    public static String of(String channelTitle, SafeUrl channelLink, String channelDescription, String title,
                             SafeUrl link, String description, long updatedAt) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rss version=\"2.0\">\n<channel>\n");
        element(sb, "title", channelTitle);
        element(sb, "link", channelLink.href());
        element(sb, "description", channelDescription);
        sb.append("<item>\n");
        element(sb, "title", title);
        element(sb, "link", link.href());
        element(sb, "description", description);
        if (updatedAt > 0) {
            element(sb, "pubDate", RFC_1123.format(Instant.ofEpochSecond(updatedAt)));
        }
        sb.append("<guid isPermaLink=\"true\">").append(escape(link.href())).append("</guid>\n");
        sb.append("</item>\n</channel>\n</rss>\n");
        return sb.toString();
    }

    private static void element(StringBuilder sb, String name, String text) {
        sb.append('<').append(name).append('>').append(escape(text)).append("</").append(name).append(">\n");
    }

    /** XML 文本与属性值转义；删除 XML 1.0 不允许的字符。 */
    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                sb.append(c).append(s.charAt(++i));
                continue;
            }
            if (Character.isSurrogate(c) || c == '￾' || c == '￿'
                    || (c < 0x20 && c != '\t' && c != '\n' && c != '\r')) {
                continue;
            }
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
