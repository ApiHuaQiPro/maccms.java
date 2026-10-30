package com.maccms.backend.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/**
 * 从 B13 后台页面取出要与 PHP 黄金比对的结构（PHP 与新版用同一套取法，22 文档第 9 节）：标题、表单控件（名称、类型、值、勾选/选中、
 * 下拉框的各项）、带 {@code data-href} 的操作链接、普通链接、表格各格的文字、徽标文字与分页参数。只比较数据与 DOM 钩子，
 * 不比较空白与新版移到外部文件的脚本；属性值按 HTML 解析后的文字比较（旧版原样输出、新版转义输出时浏览器看到的值相同）。
 */
public final class AdminContentHtml {

    private AdminContentHtml() {
    }

    public static Document parse(String html) {
        return Parser.htmlParser().parseInput(html, "");
    }

    /** 页面结构（每项一行）。 */
    public static List<String> structure(String html) {
        Document d = parse(html);
        List<String> out = new ArrayList<>();
        out.add("title " + d.title());
        for (Element e : d.select("input, select, textarea, button")) {
            out.add(control(e));
        }
        for (Element e : d.select("[data-href]")) {
            out.add("href " + e.tagName() + "." + e.className().trim().replaceAll("\\s+", ".") + " " + e.attr("data-href")
                    + attrs(e, "data-full", "data-width", "data-height", "data-checkbox", "refresh", "target") + " "
                    + norm(e.text()));
        }
        for (Element e : d.select("a[href]")) {
            String href = e.attr("href");
            if (!href.startsWith("javascript:")) {
                out.add("a " + href + attrs(e, "target") + " " + norm(e.text()));
            }
        }
        for (Element tr : d.select("table tr")) {
            List<String> cells = new ArrayList<>();
            for (Element td : tr.select("> td, > th")) {
                cells.add(norm(td.text()));
            }
            out.add("tr " + String.join(" | ", cells));
        }
        for (Element e : d.select(".layui-badge, .layui-badge-rim, font, label.layui-form-label, legend, blockquote, "
                + ".layui-elem-quote, p, span, h1, h2, h3")) {
            String t = norm(e.ownText());
            if (!t.isEmpty()) {
                out.add("text " + e.tagName() + " " + t);
            }
        }
        // 页面脚本依赖的 DOM 钩子（分页容器、列表 id 等）
        for (Element e : d.select("body [id]")) {
            if (!e.tagName().equals("script")) {
                out.add("id " + e.tagName() + "#" + e.id());
            }
        }
        String paging = paging(html);
        if (paging != null) {
            out.add("paging " + paging);
        }
        return out;
    }

    private static String control(Element e) {
        StringBuilder sb = new StringBuilder(e.tagName()).append(' ').append(e.attr("name"));
        String type = e.attr("type");
        if (!type.isEmpty()) {
            sb.append(" type=").append(type);
        }
        switch (e.tagName()) {
            case "select" -> {
                for (Element o : e.select("option")) {
                    sb.append(" [").append(o.attr("value")).append('=').append(norm(o.text()))
                            .append(o.hasAttr("selected") ? "*" : "").append(']');
                }
            }
            case "textarea" -> sb.append(" text=").append(e.wholeText().replace("\r\n", "\n").replace("\r", "\n"));
            case "button" -> sb.append(attrs(e, "lay-filter", "refresh", "data-child")).append(' ').append(norm(e.text()));
            default -> {
                if (e.hasAttr("value")) {
                    sb.append(" value=").append(e.attr("value"));
                }
                if (e.hasAttr("checked")) {
                    sb.append(" checked");
                }
                sb.append(attrs(e, "id", "lay-skin", "lay-filter", "lay-text", "lay-verify", "title", "data-href",
                        "readonly", "placeholder"));
            }
        }
        return sb.toString();
    }

    private static String attrs(Element e, String... names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (e.hasAttr(n)) {
                sb.append(' ').append(n).append('=').append(e.attr(n));
            }
        }
        return sb.toString();
    }

    private static String norm(String s) {
        return s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    private static final Pattern LAYPAGE = Pattern.compile(
            "count: (.*?)\\s*,limit: (.*?)\\s*,curr: (.*?)\\s*,", Pattern.DOTALL);
    private static final Pattern CUR_URL = Pattern.compile("var curUrl=\"(.*?)\"");

    /** 列表页的分页参数：旧版写在内联脚本里，新版在 admin-list.js 的 data-* 属性上；没有分页时为 null。 */
    public static String paging(String html) {
        Matcher m = LAYPAGE.matcher(html);
        Matcher u = CUR_URL.matcher(html);
        if (m.find() && u.find()) {
            return "count=" + m.group(1).trim() + " limit=" + m.group(2).trim() + " curr=" + m.group(3).trim()
                    + " url=" + u.group(1);
        }
        Element s = parse(html).selectFirst("script[src*=admin-list.js]");
        if (s == null) {
            return null;
        }
        return "count=" + s.attr("data-count") + " limit=" + s.attr("data-limit") + " curr=" + s.attr("data-curr")
                + " url=" + s.attr("data-cur-url");
    }
}
