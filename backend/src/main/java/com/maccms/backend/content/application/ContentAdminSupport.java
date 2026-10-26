package com.maccms.backend.content.application;

import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import com.maccms.backend.content.infrastructure.AdminCriteria;
import com.maccms.backend.content.infrastructure.ContentAdminWrites;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * 视频、文章、专题、演员、角色、网址后台共有的列表、字段修改、查重删除与 updateToday（B13，22 文档第 7 节），逐句对应旧控制器。
 */
final class ContentAdminSupport {

    static final ZoneId PRC = ZoneId.of("Asia/Shanghai");
    /** 查重删除时每次读取的同名组数。 */
    private static final int REPEAT_PAGE = 5000;

    private ContentAdminSupport() {
    }

    /** 列表的一页：{@code count}/{@code list} 为各表的 Mapper 语句。 */
    static AdminRowPage page(AdminCriteria c, AdminPaging paging, String wd, ToLongFunction<AdminCriteria> count,
                             ListFn list) {
        long total = count.applyAsLong(c);
        List<AdminRow> rows = new ArrayList<>();
        if (!paging.beyond()) {
            for (Map<String, String> r : list.list(c, paging.offset(), paging.limit())) {
                rows.add(new AdminRow(r));
            }
        }
        return new AdminRowPage(rows, total, paging.page(), paging.limit(), wd);
    }

    interface ListFn {
        List<Map<String, String>> list(AdminCriteria c, long offset, long limit);
    }

    /** {@code in_array($param[k], ['0','1'])}（宽松比较）时的原值，否则 null。 */
    static String zeroOne(PhpForm p, String k) {
        if (p.isArray(k)) {
            return null;
        }
        byte[] v = p.scalar(k);
        return v != null && (PhpLoose.equalsNumber(v, 0) || PhpLoose.equalsNumber(v, 1)) ? new String(v, StandardCharsets.UTF_8)
                : null;
    }

    /** {@code !empty($param[k])} 时的原值，否则 null。 */
    static String nonEmpty(PhpForm p, String k) {
        return p.empty(k) || p.isArray(k) ? null : new String(p.scalar(k), StandardCharsets.UTF_8);
    }

    /** 图片筛选：{@code $param['pic']} 宽松等于 '1'、'2'、'3' 时的取值，否则 null。 */
    static Integer pic(PhpForm p) {
        if (p.empty("pic") || p.isArray("pic")) {
            return null;
        }
        byte[] v = p.scalar("pic");
        for (int i = 1; i <= 3; i++) {
            if (PhpLoose.equalsNumber(v, i)) {
                return i;
            }
        }
        return null;
    }

    /** 列表关键字：{@code htmlspecialchars(urldecode(wd))}（多数列表）。 */
    static String wdHtml(PhpForm p) {
        return p.empty("wd") || p.isArray("wd") ? null
                : new String(PhpText.htmlspecialcharsCompat(PhpText.urldecode(p.scalar("wd"))), StandardCharsets.UTF_8);
    }

    /** 列表关键字：{@code mac_filter_xss(urldecode(wd))}（视频、文章列表）。 */
    static String wdXss(PhpForm p) {
        return p.empty("wd") || p.isArray("wd") ? null
                : new String(PhpText.filterXss(PhpText.urldecode(p.scalar("wd"))), StandardCharsets.UTF_8);
    }

    static String like(String wd) {
        return wd == null ? null : PhpText.legacyLike(wd);
    }

    /**
     * 字段修改（旧 Vod/Art/Actor/Website::field，Role 的列/值版同样适用）：{@code ids} 非空且 {@code col} 在白名单内；{@code start} 为空时
     * 把这些行的 {@code col} 改成 {@code val}（{@code col = type_id} 时另写 {@code type_id_1 = intval(分类的上级)}）；{@code start} 非空时
     * {@code end} 为空取 9999，对 {@code ids} 逐个写 {@code rand(start, end)}（旧版此时不改 type_id_1）。
     *
     * @param typeList 分类表（{@code col = type_id} 时取上级）；没有分类列的表为 null
     */
    static AdminResult field(PhpForm param, AdminTable table, List<String> cols, Map<Long, AdminRow> typeList,
                             ContentAdminWrites writer) {
        return AdminWrites.guard(() -> {
            List<Long> ids = AdminIds.ids(param);
            String col = param.isArray("col") ? null : param.text("col");
            if (ids == null || col == null || !cols.contains(col)) {
                return AdminResult.error("param_err");
            }
            if (param.empty("start")) {
                if (!param.has("val") || param.isArray("val")) {
                    // 旧版 val 为 null 时写 NULL（NOT NULL 列报错）或 fieldData 的 isset 检查不通过
                    return AdminResult.error("param_err");
                }
                byte[] val = param.scalar("val");
                Map<String, String> update = new LinkedHashMap<>();
                update.put(col, AdminRowValues.value(table.column(col), val));
                if (col.equals("type_id") && typeList != null) {
                    Long key = PhpForm.Arr.intKey(new String(val, StandardCharsets.UTF_8));
                    AdminRow t = key == null ? null : typeList.get(key);
                    update.put("type_id_1", Long.toString(t == null ? 0 : PhpIntval.of(t.s("type_pid"))));
                }
                writer.updateByIds(table.table(), update, ids);
            } else {
                if (param.isArray("ids") || param.isArray("start") || param.isArray("end")) {
                    // 旧版对数组 explode 报错
                    return AdminResult.error("param_err");
                }
                String start = param.text("start");
                String end = param.empty("end") ? "9999" : param.text("end");
                for (Long id : ids) {
                    Map<String, String> update = new LinkedHashMap<>();
                    update.put(col, AdminRowValues.value(table.column(col),
                            Long.toString(PhpRand.between(start, end)).getBytes(StandardCharsets.US_ASCII)));
                    writer.updateByIds(table.table(), update, List.of(id));
                }
            }
            return AdminResult.ok("set_ok");
        });
    }

    /**
     * 查重删除（旧 Vod/Art/Website::del 的 {@code repeat}）：旧版按查重列表第 1 页时建的临时表删除——保留最小编号时删除"名称在同名组里且编号
     * 不是组内最小"的行（{@code not in}），{@code retain=max} 时删除各组的最小编号（{@code in}，同名超过两条时只删一条）。新版不建临时表，
     * 删除时按当前数据先取全部同名组再逐组删除（22 文档 D-B13-7）。提示总是"删除成功"。
     */
    static AdminResult deleteRepeat(boolean retainMax, BiFunction<Long, Long, List<Map<String, Object>>> groups,
                                    BiConsumer<String, Long> deleteKeep, Function<List<Long>, Integer> deleteIds) {
        return AdminWrites.guard(() -> {
            List<Map<String, Object>> all = new ArrayList<>();
            for (long offset = 0; ; offset += REPEAT_PAGE) {
                List<Map<String, Object>> page = groups.apply(offset, (long) REPEAT_PAGE);
                all.addAll(page);
                if (page.size() < REPEAT_PAGE) {
                    break;
                }
            }
            if (retainMax) {
                List<Long> mins = new ArrayList<>();
                for (Map<String, Object> g : all) {
                    mins.add(((Number) g.get("id")).longValue());
                    if (mins.size() == AdminIds.MAX_IDS) {
                        deleteIds.apply(List.copyOf(mins));
                        mins.clear();
                    }
                }
                if (!mins.isEmpty()) {
                    deleteIds.apply(mins);
                }
            } else {
                for (Map<String, Object> g : all) {
                    deleteKeep.accept(String.valueOf(g.get("name")), ((Number) g.get("id")).longValue());
                }
            }
            return AdminResult.ok("del_ok");
        });
    }

    /** updateToday：{@code x_time} 晚于今天零点（PRC）的编号或分类，去重后以逗号连接。 */
    static String today(Clock clock, BiFunction<Long, Boolean, List<Long>> query, boolean type) {
        long since = LocalDate.now(clock.withZone(PRC)).atStartOfDay(PRC).toEpochSecond();
        LinkedHashSet<Long> ids = new LinkedHashSet<>(query.apply(since, type));
        List<String> out = new ArrayList<>();
        ids.forEach(i -> out.add(Long.toString(i)));
        return String.join(",", out);
    }
}
