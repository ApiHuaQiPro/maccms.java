package com.maccms.backend.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/**
 * 从后台页面的 HTML 里取出要与 PHP 黄金比对的结构（PHP 与新版用同一套取法）：框架页菜单、管理员列表、管理员表单、跳转页、登录页。
 * 只比较数据与 DOM 钩子（类名、data-*、表单字段），不比较空白与新版移到外部文件的脚本。
 */
public final class AdminHtml {

    private AdminHtml() {
    }

    public static Document parse(String html) {
        return Parser.htmlParser().parseInput(html, "");
    }

    /** 框架页菜单：顶部导航（名称、data-i）、侧栏各组（是否隐藏、名称、图标）与各项（data-id、href、名称；分隔线为 "---"）。 */
    public static List<String> menus(String html) {
        Document d = parse(html);
        List<String> out = new ArrayList<>();
        for (Element li : d.select("ul.main-nav > li")) {
            Element a = li.selectFirst("a");
            out.add("TOP " + a.text() + " i=" + a.attr("data-i") + " this=" + li.hasClass("layui-this")
                    + " li-i=" + li.attr("data-i"));
        }
        for (Element ul : d.select("#navBar > ul.layui-nav-tree")) {
            Element head = ul.selectFirst("li.layui-nav-item > a");
            out.add("GROUP " + head.text() + " icon=" + head.selectFirst("i").className() + " hidden="
                    + ul.attr("style").contains("display:none"));
            for (Element dd : ul.select("dl.layui-nav-child > dd")) {
                Element a = dd.selectFirst("a.admin-nav-item");
                out.add(a == null ? "  ---" : "  " + a.attr("data-id") + " " + a.attr("href") + " " + a.text());
            }
        }
        return out;
    }

    /** 管理员列表：各行与分页参数。 */
    public static List<String> adminList(String html) {
        Document d = parse(html);
        List<String> out = new ArrayList<>();
        for (Element tr : d.select("#pageListForm table > tbody > tr, #pageListForm table > tr")) {
            List<Element> td = tr.select("> td");
            Element box = td.get(0).selectFirst("input.checkbox-ids");
            Element sw = td.get(3).selectFirst("input[lay-filter=switchStatus]");
            Element edit = td.get(7).selectFirst("a.j-iframe");
            Element del = td.get(7).selectFirst("a.j-tr-del");
            Element font = td.get(4).selectFirst("font");
            out.add(String.join(" | ", box == null ? "-" : box.attr("value"), td.get(1).text(), td.get(2).text(),
                    "checked=" + sw.hasAttr("checked") + " value=" + sw.attr("value") + " " + sw.attr("data-href")
                            + " " + sw.attr("lay-text"),
                    td.get(4).text() + (font == null ? "" : " [red]"), td.get(5).text(), td.get(6).text(),
                    edit.attr("data-href"), del == null ? "-" : del.attr("data-href")));
        }
        out.add("toolbar " + d.select(".my-toolbar-box a").eachAttr("data-href"));
        return out;
    }

    private static final Pattern LAYPAGE = Pattern.compile(
            "count: (.*?)\\s*,limit: (.*?)\\s*,curr: (.*?)\\s*,", Pattern.DOTALL);
    private static final Pattern CUR_URL = Pattern.compile("var curUrl=\"(.*?)\"");

    /** 列表页的分页参数：旧版写在内联脚本里，新版在 admin-list.js 的 data-* 属性上。 */
    public static String paging(String html) {
        Matcher m = LAYPAGE.matcher(html);
        Matcher u = CUR_URL.matcher(html);
        if (m.find() && u.find()) {
            return "count=" + m.group(1) + " limit=" + m.group(2) + " curr=" + m.group(3) + " url=" + u.group(1);
        }
        Element s = parse(html).selectFirst("script[src$=admin-list.js]");
        return "count=" + s.attr("data-count") + " limit=" + s.attr("data-limit") + " curr=" + s.attr("data-curr")
                + " url=" + s.attr("data-cur-url");
    }

    /** 管理员表单：字段值、状态单选、权限树（组与各项的勾选、只读）。 */
    public static List<String> adminInfo(String html) {
        Document d = parse(html);
        List<String> out = new ArrayList<>();
        out.add("id=" + d.selectFirst("input#admin_id").attr("value") + " name=" + d.selectFirst("input#admin_name").attr("value")
                + " pwd=" + d.selectFirst("input#admin_pwd").attr("value")
                + " token=" + (d.selectFirst("input[name=__token__]").attr("value").isEmpty() ? "empty" : "set"));
        for (Element r : d.select("input[name=admin_status]")) {
            out.add("status " + r.attr("value") + " " + r.attr("title") + " checked=" + r.hasAttr("checked"));
        }
        for (Element dl : d.select("dl.role-list-form-top")) {
            Element top = dl.selectFirst("dt input");
            out.add("GROUP " + top.attr("title") + " id=" + top.attr("data-id") + " checked=" + top.hasAttr("checked"));
            for (Element in : dl.select("dd input")) {
                out.add("  " + in.attr("value") + " " + in.attr("title") + " pid=" + in.attr("data-pid") + " checked="
                        + in.hasAttr("checked") + " readonly=" + in.hasAttr("readonly"));
            }
        }
        return out;
    }

    /** 跳转提示页：成功/失败、提示文字、跳转地址与等待秒数。 */
    public static String jump(String html) {
        Document d = parse(html);
        Element ok = d.selectFirst("p.success");
        Element err = d.selectFirst("p.error");
        return (ok != null ? "success " + ok.text() : "error " + err.text()) + " href=" + d.selectFirst("#href").attr("href")
                + " wait=" + d.selectFirst("#wait").text() + " title=" + d.title();
    }

    /** 登录页：表单字段与验证码。 */
    public static String login(String html) {
        Document d = parse(html);
        return "title=" + d.title() + " fields=" + d.select("form input").eachAttr("name") + " verify="
                + (d.selectFirst("#verify_img") != null) + " legend=" + d.select("legend").text();
    }

    /** 欢迎页：提示与表格左列。 */
    public static List<String> welcome(String html) {
        Document d = parse(html);
        List<String> out = new ArrayList<>();
        out.add("title=" + d.title());
        out.add(d.selectFirst("p.text-success").text());
        for (Element tr : d.select("table.layui-table tr")) {
            out.add("row " + tr.selectFirst("td").text());
        }
        return out;
    }

    /** 快捷菜单配置页：文本框内容与令牌。 */
    public static String quickmenu(String html) {
        Document d = parse(html);
        Element ta = d.selectFirst("textarea[name=quickmenu]");
        // HTML 解析把 CR、CRLF 都规范成 LF（旧版用 chr(13) 拼接各行）
        String text = ta.wholeText().replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n");
        return "title=" + d.title() + " text=" + text + " token="
                + !d.selectFirst("input[name=__token__]").attr("value").isEmpty();
    }
}
