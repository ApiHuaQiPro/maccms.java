package com.maccms.backend.collect.application;

import com.maccms.backend.content.application.PhpIntval;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * 旧 {@code Cj::col_all}（{@code Cj.php:131-135}）的一轮：采集一页网址 →"URL采集完成"→ 采集一批内容 → 导入这一批（每页 999 条）→"内容入库完成"，
 * 不跳转。后台 {@code cj/col_all} 与定时任务的执行文件 {@code cj}（旧 {@code Timming::cj}，B16）共用（28 文档第 6.2 节）。
 */
public final class CjColAll {

    public static final String NOT_CONFIGURED = "未配置本地 HTML 目录（maccms.collect.local.html-dir），无法采集";
    public static final String STYLE = "<style type=\"text/css\">body{font-size:12px;color: #333333;line-height:21px;}"
            + "span{font-weight:bold;color:#FF0000}</style>";
    /** 旧 {@code content_into} 里 {@code col_all} 的每页条数。 */
    static final int INTO_LIMIT = 999;

    private CjColAll() {
    }

    /**
     * 定时任务 {@code cj}：参数按 {@code parse_str} 解析后的 {@code id}、{@code page}。没有这个节点时"获取失败"，未配置本地 HTML 目录时提示，
     * 都不写库，只输出一行（旧版 {@code $this->error()} 会结束整个定时任务请求，新版只结束这一项，25 文档 D-B16-16）。
     */
    public static String timming(CjRunService cj, Map<String, String> param, UnaryOperator<String> lang) {
        Optional<Map<String, String>> node = cj.node(bytes(param.get("id")));
        if (node.isEmpty()) {
            return escape(lang.apply("obtain_err")) + "<br>";
        }
        if (!cj.htmlConfigured()) {
            return escape(NOT_CONFIGURED) + "<br>";
        }
        return round(cj, node.get(), bytes(param.get("page")), lang);
    }

    /** 一轮的输出。节点已取得、本地 HTML 目录已配置。 */
    public static String round(CjRunService cj, Map<String, String> node, byte[] page, UnaryOperator<String> lang) {
        cj.colUrl(node, page, lang);
        StringBuilder sb = new StringBuilder();
        sb.append(escape(lang.apply("admin/cj/url_cj_complete"))).append("<br>");
        CjRunService.ColContent r = cj.colContent(node, page);
        sb.append(STYLE).append("<br>");
        sb.append(escape(format(lang.apply("admin/cj/content/tip"), r.total(), r.totalPage(), CjRunService.CONTENT_BATCH, r.page())))
                .append("<br>");
        if (r.empty()) {
            sb.append(escape(lang.apply("admin/cj/content_cj_complete"))).append("<br>");
            return sb.toString();
        }
        for (CjRunService.ContentLine line : r.lines()) {
            sb.append(escape(line.url())).append("&nbsp;&nbsp;");
            sb.append(line.note() == null ? "ok" : "<span>" + escape(line.note()) + "</span>").append("<br>");
        }
        sb.append(escape(lang.apply("admin/cj/content_cj_complete"))).append("<br>");
        CjRunService.Into into = cj.contentInto(node, r.ids(), false, pageOf(page), INTO_LIMIT, null, 0, lang);
        sb.append(intoHtml(into, lang));
        sb.append(escape(lang.apply("admin/cj/content_into/complete"))).append("<br>");
        return sb.toString();
    }

    /** 旧 {@code content_into} 的提示与每条入库结果的说明。 */
    public static String intoHtml(CjRunService.Into r, UnaryOperator<String> lang) {
        StringBuilder sb = new StringBuilder();
        sb.append(STYLE).append("<br>");
        sb.append(escape(format(lang.apply("admin/cj/content_into/tip"), r.total(), r.totalPage(), r.limit(), r.page()))).append("<br>");
        for (CjRunService.IntoLine line : r.lines()) {
            sb.append(escape(line.msg())).append("<br>");
        }
        return sb.toString();
    }

    /** {@code lang(key, [a, b, c, d])}：按顺序替换 {@code %s}（ThinkPHP 的 {@code sprintf}）。 */
    public static String format(String t, Object... args) {
        StringBuilder sb = new StringBuilder();
        int a = 0;
        int i = 0;
        while (i < t.length()) {
            if (t.startsWith("%s", i) && a < args.length) {
                sb.append(args[a++]);
                i += 2;
            } else {
                sb.append(t.charAt(i++));
            }
        }
        return sb.toString();
    }

    /** 旧 {@code intval($param['page']) < 1 ? 1 : $param['page']}。 */
    public static long pageOf(byte[] page) {
        long p = page == null ? 0 : PhpIntval.of(new String(page, StandardCharsets.UTF_8));
        return p < 1 ? 1 : p;
    }

    private static byte[] bytes(String s) {
        return s == null ? null : s.getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
