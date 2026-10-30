package com.maccms.backend.member.application;

import com.maccms.backend.content.application.AdminIds;
import com.maccms.backend.content.application.AdminPaging;
import com.maccms.backend.content.application.AdminRejectedException;
import com.maccms.backend.content.application.PhpForm;
import com.maccms.backend.content.application.PhpLoose;
import com.maccms.backend.content.application.PhpText;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import com.maccms.backend.member.infrastructure.MemberAdminDataException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * B14 后台会员与财务服务共用的旧参数语义（23 文档第 4 节），与 B13 的后台内容服务相同：{@code input()} 的参数表 {@link PhpForm}、
 * PHP {@code empty()}、{@code in_array} 严格/宽松比较、{@code htmlspecialchars(urldecode(wd))} 关键字与 {@code LIKE} 的转写。
 */
final class MemberAdminSupport {

    private MemberAdminSupport() {
    }

    /** {@code in_array($param[k], ['0','1'], true)}：严格等于 {@code 0} 或 {@code 1} 的原值，否则 null。 */
    static String strictZeroOne(PhpForm p, String k) {
        if (p.isArray(k) || p.scalar(k) == null) {
            return null;
        }
        String v = text(p.scalar(k));
        return v.equals("0") || v.equals("1") ? v : null;
    }

    /** {@code !empty($param[k])} 时的原值（交给 MySQL 比较）；数组按没有处理（旧版会当作 ThinkPHP 查询表达式）。 */
    static String nonEmpty(PhpForm p, String k) {
        return p.empty(k) || p.isArray(k) ? null : text(p.scalar(k));
    }

    /** {@code $param[k] != ''} 时的原值（缺失与空串不筛选，{@code '0'} 筛选）；数组按没有处理。 */
    static String notBlank(PhpForm p, String k) {
        if (p.isArray(k) || p.scalar(k) == null || p.scalar(k).length == 0) {
            return null;
        }
        return text(p.scalar(k));
    }

    /** 列表关键字：{@code !empty(wd)} 时 {@code htmlspecialchars(urldecode(wd))}，否则 null。 */
    static String wd(PhpForm p) {
        return p.empty("wd") || p.isArray("wd") ? null
                : text(PhpText.htmlspecialcharsCompat(PhpText.urldecode(p.scalar("wd"))));
    }

    /** 旧 {@code LIKE '%词%'}（{@code %}、{@code _} 照旧是通配符）。 */
    static String like(String word) {
        return word == null ? null : PhpText.legacyLike(word);
    }

    /** 旧 {@code LIKE '前缀%'}。 */
    static String prefixLike(String word) {
        return PhpText.legacyLike(word).substring(1);
    }

    /** {@code $v == 1} 一类（PHP 8 宽松比较，缺失为假）。 */
    static boolean looseEquals(PhpForm p, String k, long n) {
        return !p.isArray(k) && PhpLoose.equalsNumber(p.scalar(k), n);
    }

    /** 删除、修改的 {@code ids}（缺失或 {@code empty()} 时为 null；每项必须是十进制数字，D-B13-4 的同一规则）。 */
    static List<Long> ids(PhpForm p) {
        return AdminIds.ids(p);
    }

    /** 按条件列出一页（页码过大时不查询，返回空页）。 */
    static AdminRowPage page(AdminPaging paging, String wd, LongSupplier count,
                             java.util.function.BiFunction<Long, Long, List<Map<String, String>>> list) {
        long total = count.getAsLong();
        List<AdminRow> rows = new ArrayList<>();
        if (!paging.beyond()) {
            for (Map<String, String> r : list.apply(paging.offset(), (long) paging.limit())) {
                rows.add(new AdminRow(r));
            }
        }
        return new AdminRowPage(rows, total, paging.page(), paging.limit(), wd);
    }

    /** 参数不合规、MySQL 严格模式拒绝的取值 → 旧版 {@code error()} 的"参数错误"一类提示。 */
    static AdminResult guard(Supplier<AdminResult> action) {
        try {
            return action.get();
        } catch (AdminRejectedException e) {
            return AdminResult.error(e.reason());
        } catch (MemberAdminDataException e) {
            return AdminResult.error("param_err");
        }
    }

    static String text(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }
}
