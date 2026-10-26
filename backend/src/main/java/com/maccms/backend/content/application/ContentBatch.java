package com.maccms.backend.content.application;

import com.maccms.backend.content.application.view.AdminMessage;
import com.maccms.backend.content.application.view.AdminProgress;
import com.maccms.backend.content.application.view.AdminProgress.Line;
import com.maccms.backend.content.application.view.AdminProgress.Part;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.infrastructure.AdminCriteria;
import com.maccms.backend.content.infrastructure.ContentAdminWrites;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.function.UnaryOperator;

/**
 * 视频、文章、网址的批量处理（旧 {@code Vod/Art/Website::batch}，B13 22 文档第 7.7 节）：按筛选条件逐页处理，每页最多 {@code limit}
 * （默认 100）条，输出每条的处理结果后 3 秒跳到下一页（参数原样带上，页码 +1，首页时补上 total、page_count）。删除（{@code ck_del=1}）
 * 一次删完全部符合条件的行（先删它们在 upload/ 下的图片）。
 */
final class ContentBatch {

    /** 旧批量页输出的第一行（mac_echo 一段固定的样式）。 */
    static final String STYLE = "<style type=\"text/css\">body{font-size:12px;color: #333333;line-height:21px;}span{font-weight:bold;"
            + "color:#FF0000}</style>";

    /**
     * 一类内容的批量处理参数。
     *
     * @param table     表
     * @param prefix    列前缀（{@code vod}、{@code art}、{@code website}）
     * @param reversed  从最后一页往前处理（旧视频、文章：{@code $page = page_count - page + 1}）；网址按页码顺序
     * @param doneKey   处理完毕的提示（视频、网址 {@code multi_opt_ok}，文章 {@code multi_set_ok}）
     * @param unlockKey 解锁的提示键（网址的旧代码写成了 {@code ublock}，语言包里没有，输出键名原文）
     * @param hitsAllowZero 人气区间判断：视频是 {@code != ''}，文章与网址是 {@code !empty()}（0 不算）
     */
    record Spec(AdminTable table, String prefix, boolean reversed, String doneKey, String unlockKey, boolean hitsAllowZero) {
    }

    /** 读写的入口（各表的 Mapper 语句不同）。 */
    interface Access {
        long count(AdminCriteria c);

        List<Map<String, String>> list(AdminCriteria c, long offset, long limit);
    }

    private ContentBatch() {
    }

    /**
     * 处理一次请求。{@code criteria} 按参数生成筛选条件；{@code deleteMatching} 删除符合条件的全部行（ck_del=1）；{@code extra}
     * 处理视频独有的勾选项（积分、版权、删播放组/下载组），文章与网址为 null。
     */
    static AdminProgress run(Spec spec, PhpForm param, UnaryOperator<String> lang, Access access,
                             Function<PhpForm, AdminCriteria> criteria, ToLongFunction<AdminCriteria> deleteMatching,
                             ContentAdminWrites writer, VodBatchExtras extra) {
        List<Line> out = new ArrayList<>();
        out.add(Line.of(Part.markup(STYLE)));
        boolean any = !param.empty("ck_del") || !param.empty("ck_level") || !param.empty("ck_status")
                || !param.empty("ck_lock") || !param.empty("ck_hits")
                || (extra != null && (!param.empty("ck_points") || !param.empty("ck_copyright")));
        if (!any) {
            return new AdminProgress(out, null, AdminMessage.lang("param_err"));
        }
        if (extra != null) {
            AdminMessage err = extra.precheck(param);
            if (err != null) {
                return new AdminProgress(out, null, err);
            }
        }
        AdminCriteria c = criteria.apply(param);
        LinkedHashMap<String, String> q = query(param);
        if (!param.empty("wd") && !param.isArray("wd")) {
            // 旧版把处理后的关键字写回 $param['wd']，下一页的地址带的是它
            q.put("wd", new String(PhpText.htmlspecialcharsCompat(PhpText.urldecode(param.scalar("wd"))), StandardCharsets.UTF_8));
        }
        if (PhpLoose.equalsNumber(param.scalar("ck_del"), 1) && !param.isArray("ck_del")) {
            deleteMatching.applyAsLong(c);
            out.add(Line.of(Part.text(lang.apply("multi_del_ok"))));
            return new AdminProgress(out, Map.of(), null);
        }
        long page = param.empty("page") ? 1 : PhpIntval.of(text(param, "page"));
        long limit = param.empty("limit") ? 100 : PhpIntval.of(text(param, "limit"));
        if (param.empty("page")) {
            q.put("page", "1");
        }
        if (param.empty("limit")) {
            q.put("limit", "100");
        }
        if (limit < 1) {
            // 旧版 limit 为非数字或负数时除零/取到负数报错；新版按非法参数
            return new AdminProgress(out, null, AdminMessage.lang("param_err"));
        }
        long total;
        Long pageCount;
        if (param.empty("total")) {
            total = access.count(c);
            pageCount = (total + limit - 1) / limit;
            q.put("total", Long.toString(total));
            q.put("page_count", Long.toString(pageCount));
        } else {
            total = PhpIntval.of(text(param, "total"));
            // 旧版 total 已给而 page_count 缺失时 page_count 为 null，"page > null" 为真
            pageCount = param.has("page_count") && !param.isArray("page_count") ? PhpIntval.of(text(param, "page_count")) : null;
        }
        if (pageCount == null || page > pageCount) {
            out.add(Line.of(Part.text(lang.apply(spec.doneKey()))));
            return new AdminProgress(out, Map.of(), null);
        }
        out.add(Line.of(Part.markup("<font color=red>"), Part.text(sprintf(lang.apply("admin/batch_tip"),
                rawOr(param, "total", Long.toString(total)), rawOr(param, "limit", Long.toString(limit)),
                rawOr(param, "page_count", Long.toString(pageCount)), rawOr(param, "page", Long.toString(page)))),
                Part.markup("</font>")));
        long listPage = spec.reversed() ? pageCount - page + 1 : page;
        String p = spec.prefix();
        for (Map<String, String> raw : access.list(c, (listPage - 1) * limit, limit)) {
            AdminRow v = new AdminRow(raw);
            Map<String, String> update = new LinkedHashMap<>();
            List<Part> des = new ArrayList<>();
            des.add(Part.text(v.s(p + "_id") + "," + v.s(p + "_name")));
            if (!param.empty("ck_level") && !param.empty("val_level")) {
                update.put(p + "_level", value(spec, p + "_level", param.scalar("val_level")));
                des.add(Part.markup("&nbsp;"));
                des.add(Part.text(lang.apply("level") + "：" + text(param, "val_level") + "；"));
            }
            if (!param.empty("ck_status") && present(param, "val_status")) {
                update.put(p + "_status", value(spec, p + "_status", param.scalar("val_status")));
                des.add(Part.markup("&nbsp;"));
                des.add(Part.text(lang.apply("status") + "：" + (PhpLoose.equalsOne(param.scalar("val_status"))
                        ? "[" + lang.apply("reviewed") + "]" : "[" + lang.apply("reviewed_not") + "]") + "；"));
            }
            if (extra != null) {
                extra.copyright(param, lang, update, des);
            }
            if (!param.empty("ck_lock") && present(param, "val_lock")) {
                update.put(p + "_lock", value(spec, p + "_lock", param.scalar("val_lock")));
                des.add(Part.markup("&nbsp;"));
                des.add(Part.text(lang.apply("lock") + "：" + (PhpLoose.equalsOne(param.scalar("val_lock"))
                        ? "[" + lang.apply("lock") + "]" : "[" + lang.apply(spec.unlockKey()) + "]") + "；"));
            }
            boolean hits = spec.hitsAllowZero()
                    ? !param.empty("ck_hits") && notBlank(param, "val_hits_min") && notBlank(param, "val_hits_max")
                    : !param.empty("ck_hits") && !param.empty("val_hits_min") && !param.empty("val_hits_max");
            if (hits) {
                long h = PhpRand.between(text(param, "val_hits_min"), text(param, "val_hits_max"));
                update.put(p + "_hits", Long.toString(h));
                des.add(Part.markup("&nbsp;"));
                des.add(Part.text(lang.apply("hits") + "：" + h + "；"));
            }
            if (extra != null) {
                extra.row(param, lang, v, update, des);
            }
            out.add(new Line(des));
            writer.update(spec.table().table(), update, v.n(p + "_id"));
        }
        q.put("page", Long.toString(page + 1));
        return new AdminProgress(out, q, null);
    }

    /** {@code isset($param[k])}：有这个参数（数组也算）。 */
    private static boolean present(PhpForm p, String k) {
        return p.has(k) && !p.isNull(k);
    }

    /** {@code $param[k] != ''}：缺失（null）与空串都不算。 */
    private static boolean notBlank(PhpForm p, String k) {
        byte[] v = p.scalar(k);
        return v != null && v.length > 0;
    }

    private static String value(Spec spec, String col, byte[] raw) {
        if (raw == null) {
            throw AdminRejectedException.paramError();
        }
        return AdminRowValues.value(spec.table().column(col), raw);
    }

    static String text(PhpForm p, String k) {
        if (p.isArray(k)) {
            throw AdminRejectedException.paramError();
        }
        byte[] v = p.scalar(k);
        return v == null ? "" : new String(v, StandardCharsets.UTF_8);
    }

    private static String rawOr(PhpForm p, String k, String computed) {
        return p.empty(k) ? computed : text(p, k);
    }

    /** {@code $param} 的标量参数（下一页的查询参数，旧版 http_build_query 原样带上）。 */
    static LinkedHashMap<String, String> query(PhpForm p) {
        LinkedHashMap<String, String> q = new LinkedHashMap<>();
        for (String k : p.names()) {
            if (!p.isArray(k) && !p.isNull(k)) {
                q.put(k, new String(p.scalar(k), StandardCharsets.UTF_8));
            }
        }
        return q;
    }

    /** ThinkPHP {@code lang($key, [$a, $b, …])}：按 {@code sprintf} 依次替换 {@code %s}、{@code %d}。 */
    static String sprintf(String format, String... args) {
        StringBuilder sb = new StringBuilder();
        int a = 0;
        for (int i = 0; i < format.length(); i++) {
            char ch = format.charAt(i);
            if (ch == '%' && i + 1 < format.length()) {
                char n = format.charAt(i + 1);
                if (n == '%') {
                    sb.append('%');
                    i++;
                    continue;
                }
                if (n == 's' || n == 'd') {
                    String v = a < args.length ? args[a++] : "";
                    sb.append(n == 'd' ? Long.toString(PhpIntval.of(v)) : v);
                    i++;
                    continue;
                }
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    /** 视频批量处理独有的部分（旧 Vod::batch）。 */
    interface VodBatchExtras {
        AdminMessage precheck(PhpForm param);

        void copyright(PhpForm param, UnaryOperator<String> lang, Map<String, String> update, List<Part> des);

        void row(PhpForm param, UnaryOperator<String> lang, AdminRow v, Map<String, String> update, List<Part> des);
    }
}
