package com.maccms.backend.admin.web.view.member;

import com.maccms.backend.admin.application.LegacyAdminUrl;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.content.BrowserText;
import com.maccms.backend.admin.web.view.content.ContentListView;
import com.maccms.backend.admin.web.view.content.TypeListView;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.PhpText;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 后台会员与财务列表页（B14，旧 admin/view/{user,card,order,cash,plog,ulog,visit}/index.html、user/reward.html、group/index.html）共用的
 * 视图模型。
 *
 * @param param  {@code input()} 的标量参数（旧模板 {@code $param['x']}：筛选框的选中状态、隐藏字段）
 * @param wdText 搜索框在浏览器里显示的文字（旧 {@code {$param['wd']|mac_filter_xss}}，见 {@link #searchBox}）
 * @param curUrl 分页地址（页码与条数为占位）
 * @param today  今天（PRC，{@code Y-m-d}；旧 {@code mac_day(…,'color')} 标红）
 * @param groups 会员组编号 → 名称（旧 {@code Group::getCache('group_list')}）
 */
public record MemberListView(AdminHead head, LegacyLang lang, LegacyAdminUrl url, AdminRowPage list, Map<String, String> param,
                             String wdText, String curUrl, String today, Map<Long, String> groups,
                             List<AdminRow> groupRows) implements BrowserText {

    static final ZoneId PRC = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public List<AdminRow> rows() {
        return list.rows();
    }

    /** 旧 {@code $param['k']}（没有时为空串）。 */
    public String p(String key) {
        String v = param.get(key);
        return v == null ? "" : v;
    }

    /** 旧 {@code $param['k'] eq 'v'}：都是数字串时按数值比较，否则按文字；参数缺失时为 null，只等于空串。 */
    public boolean pEq(String key, String v) {
        String a = param.get(key);
        if (a == null) {
            return v.isEmpty();
        }
        return looseEq(a, v);
    }

    /** 旧 {@code mac_day($t, 'color')} 的文字（0 为空串）。 */
    public String day(long t) {
        return t == 0 ? "" : dateTime(t);
    }

    /** 时间是今天（旧版包一层 {@code <font color="#FF0000">}）。 */
    public boolean isToday(long t) {
        return t != 0 && day(t).startsWith(today);
    }

    /** 旧 {@code long2ip}。 */
    public String ip(long ip) {
        return ContentListView.ip(ip);
    }

    /** 旧 {@code mac_get_mid_text}。 */
    public String midText(long mid) {
        return TypeListView.midText(lang, mid);
    }

    /** 会员组名称（取不到时为空串）。 */
    public String groupName(long id) {
        String n = groups.get(id);
        return n == null ? "" : n;
    }

    /** 旧 {@code mac_get_ulog_type_text}（application/common.php:1150-1160；没有的类型为空串）。 */
    public String ulogType(long t) {
        return switch ((int) t) {
            case 1 -> lang.get("browse");
            case 2 -> lang.get("collect");
            case 3 -> lang.get("want_see");
            case 4 -> lang.get("play");
            case 5 -> lang.get("down");
            default -> "";
        };
    }

    /** 旧 {@code mac_get_plog_type_text}（application/common.php:1162-1176）。 */
    public String plogType(long t) {
        return switch ((int) t) {
            case 1 -> lang.get("integral_recharge");
            case 2 -> lang.get("registration_promotion");
            case 3 -> lang.get("visit_promotion");
            case 4 -> lang.get("one_level_distribution");
            case 5 -> lang.get("two_level_distribution");
            case 6 -> lang.get("three_level_distribution");
            case 7 -> lang.get("points_upgrade");
            case 8 -> lang.get("integral_consumption");
            case 9 -> lang.get("integral_withdrawal");
            default -> "";
        };
    }

    /** 旧 {@code mac_get_order_status_text}（application/common.php:1196-1203）。 */
    public String orderStatus(long s) {
        return switch ((int) s) {
            case 0 -> lang.get("not_paid");
            case 1 -> lang.get("paid");
            default -> "";
        };
    }

    /** 旧 {@code in_array($vo.plog_type, [1,2,3,4,5,6])}：收入类显示 {@code +}，其余 {@code -}。 */
    public boolean income(long plogType) {
        return plogType >= 1 && plogType <= 6;
    }

    /** 推广访问的模块（旧 Visit::listData：有会员为 6 会员，否则 11 网址）。 */
    public long visitMid(AdminRow vo) {
        return vo.n("user_id") == 0 ? 11 : 6;
    }

    /**
     * 旧模板 {@code {$x|mac_filter_xss}}（{@code trim(htmlspecialchars(strip_tags($x), ENT_QUOTES))}）原样写进 {@code value="…"} 时浏览器
     * 看到的文字；新版先算出这段文字，再由模板转义输出。
     */
    public static String searchBox(String raw) {
        if (raw == null) {
            return "";
        }
        String filtered = new String(PhpText.filterXss(raw.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        return org.jsoup.parser.Parser.unescapeEntities(filtered, true);
    }

    /** PHP {@code date('Y-m-d H:i:s', $t)}（PRC）。 */
    public static String dateTime(long t) {
        return DATE_TIME.format(Instant.ofEpochSecond(t).atZone(PRC));
    }

    static boolean looseEq(String a, String b) {
        try {
            return Double.parseDouble(a.strip()) == Double.parseDouble(b.strip());
        } catch (NumberFormatException e) {
            return a.equals(b);
        }
    }
}
