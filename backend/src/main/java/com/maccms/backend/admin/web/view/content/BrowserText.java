package com.maccms.backend.admin.web.view.content;

import org.jsoup.parser.Parser;

/**
 * 旧后台模板原样输出的文字（ThinkPHP 默认不转义）在浏览器里的值（22 文档第 8.2 节）：浏览器会把其中的字符引用解码一次。新版先按浏览器的
 * 规则解码（属性值与文本的规则略有不同），再由模板转义输出，显示与提交的值与旧版相同；文字里的标签不会再被浏览器当作标签解析。
 */
public interface BrowserText {

    /** 原样输出到属性值里的文字在浏览器里的值。 */
    default String attr(String raw) {
        return raw == null ? "" : Parser.unescapeEntities(raw, true);
    }

    /** 原样输出到文本里的文字在浏览器里的值。 */
    default String text(String raw) {
        return raw == null ? "" : Parser.unescapeEntities(raw, false);
    }
}
