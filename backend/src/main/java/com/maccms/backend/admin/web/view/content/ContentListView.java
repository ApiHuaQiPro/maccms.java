package com.maccms.backend.admin.web.view.content;

import com.maccms.backend.admin.application.LegacyAdminUrl;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import com.maccms.backend.content.application.view.AdminTypeNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 内容列表页（旧 admin/view/{topic,actor,role,website,art,vod,comment,gbook,annex}/index.html）共用的视图模型。
 *
 * @param param   {@code input()} 的标量参数（旧模板 {@code $param['x']}，筛选框的选中状态与隐藏字段）
 * @param wd      搜索框的值（处理后的关键字）
 * @param curUrl  分页地址（页码与条数为占位）
 * @param types   分类树（旧 {@code type_tree}；没有分类筛选的页面为空）
 * @param detail  旧 {@code view.<模块>_detail}（大于 0 时按生成时间判断"浏览"列是否已生成静态页）
 * @param today   今天的日期（{@code Y-m-d}，旧 {@code mac_day(…,'color')} 标红）
 * @param extra   各页面另用的数据
 */
public record ContentListView(AdminHead head, LegacyLang lang, LegacyAdminUrl url, AdminRowPage list, Map<String, String> param,
                              String wd, String curUrl, FrontLinks links, List<AdminTypeNode> types, long detail, String today,
                              Map<String, Object> extra) {

    private static final ZoneId PRC = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public List<AdminRow> rows() {
        return list.rows();
    }

    /** 旧 {@code $param['k']}（没有时为空串）。 */
    public String p(String key) {
        String v = param.get(key);
        return v == null ? "" : v;
    }

    /** 旧 {@code $param['k'] eq 'v'} / {@code == 'v'}：都是数字串时按数值比较，否则按文字；参数缺失时为 null，只等于空串。 */
    public boolean pEq(String key, String v) {
        String a = param.get(key);
        if (a == null) {
            return v.isEmpty();
        }
        return TypeInfoView.looseEqStr(a, v);
    }

    /** 旧 {@code mac_day($t, 'color')} 的文字（0 为空串）。 */
    public String day(long t) {
        return t == 0 ? "" : DATE_TIME.format(Instant.ofEpochSecond(t).atZone(PRC));
    }

    /** 时间是今天（旧版包一层 {@code <font color="#FF0000">}）。 */
    public boolean isToday(long t) {
        return t != 0 && day(t).startsWith(today);
    }

    /** "浏览"列（旧 {@code ismake}）：{@code view.x_detail > 0} 且生成时间早于更新时间时为未生成。 */
    public boolean made(AdminRow r, String timeCol, String makeCol) {
        return !(detail > 0 && r.n(makeCol) < r.n(timeCol));
    }

    /** 旧 {@code $vo.type.type_name}：行的分类名称（分类表里没有时为空串）。 */
    public String typeName(long typeId) {
        Object list = extra.get("typeList");
        if (list instanceof Map<?, ?> m) {
            Object r = m.get(typeId);
            return r instanceof AdminRow row ? row.s("type_name") : "";
        }
        for (AdminTypeNode t : types) {
            if (t.row().n("type_id") == typeId) {
                return t.row().s("type_name");
            }
            for (AdminTypeNode c : t.children()) {
                if (c.row().n("type_id") == typeId) {
                    return c.row().s("type_name");
                }
            }
        }
        return "";
    }

    /** 视频、文章列表搜索框（旧 {@code {$param['wd']|mac_restore_htmlfilter}}）在浏览器里显示的文字。 */
    public String wdText() {
        return com.maccms.backend.comment.application.LegacyHtml.displayText(wd);
    }

    /** 指向库里网址的链接（旧模板原样输出 {@code href="{$vo.website_jumpurl}"}）。 */
    public String outLink(String url) {
        return url;
    }

    /** PHP {@code parse_url($url)['host']}：有协议与 {@code //} 时取主机部分，否则为空串。 */
    public String host(String url) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://(?:[^/?#@]*@)?(\\[[^\\]]*\\]|[^/?#:]*)")
                .matcher(url == null ? "" : url);
        return m.find() ? m.group(1) : "";
    }

    /**
     * 旧模板 {@code {$x|mac_filter_xss|mac_restore_htmlfilter}} 在浏览器里显示的文字：{@code mac_filter_xss} 后含 {@code &amp;amp;} 时解码一次，
     * 旧版原样输出、浏览器再按 HTML 解码；新版先算出浏览器看到的文字，再由模板转义输出。
     */
    public String xssText(String s) {
        String filtered = new String(com.maccms.backend.content.application.PhpText.filterXss(
                s.getBytes(java.nio.charset.StandardCharsets.UTF_8)), java.nio.charset.StandardCharsets.UTF_8);
        return com.maccms.backend.comment.application.LegacyHtml.displayText(filtered);
    }

    /** 视频列表"播放器"列（旧 {@code vod_play_from}、{@code vod_down_from} 的 {@code $$$} 换成逗号，以 {@code -} 连接）。 */
    public String playGroups(AdminRow vo) {
        return vo.s("vod_play_from").replace("$$$", ",") + "-" + vo.s("vod_down_from").replace("$$$", ",");
    }

    /** 旧 {@code $param['k']} 的原值（没有时为 null，用于 {@code eq} 比较）。 */
    public String raw(String key) {
        return param.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T x(String key) {
        return (T) extra.get(key);
    }

    /** 今天（PRC）。 */
    public static String todayOf(java.time.Clock clock) {
        return LocalDate.now(clock.withZone(PRC)).toString();
    }
}
