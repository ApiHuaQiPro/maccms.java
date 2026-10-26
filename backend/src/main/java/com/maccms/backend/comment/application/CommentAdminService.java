package com.maccms.backend.comment.application;

import com.maccms.backend.comment.infrastructure.CommentAdminCriteria;
import com.maccms.backend.comment.infrastructure.CommentAdminMapper;
import com.maccms.backend.comment.infrastructure.CommentAdminWrites;
import com.maccms.backend.comment.infrastructure.CommentDataException;
import com.maccms.backend.content.application.AdminIds;
import com.maccms.backend.content.application.AdminPaging;
import com.maccms.backend.content.application.AdminRejectedException;
import com.maccms.backend.content.application.AdminRowValues;
import com.maccms.backend.content.application.AdminTable;
import com.maccms.backend.content.application.PhpForm;
import com.maccms.backend.content.application.PhpLoose;
import com.maccms.backend.content.application.PhpText;
import com.maccms.backend.content.application.view.AdminMessage;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 后台评论与留言管理（B13，22 文档第 7.9 节）：旧 {@code admin/controller/Comment.php}、{@code Gbook.php} →
 * {@code common/model/Comment.php}、{@code Gbook.php}。列表、编辑（回复）、删除与清空、审核状态。表与列的取值规则同 content 模块的后台
 * 服务（{@code AdminTable}、{@code AdminRowValues}）。
 */
public class CommentAdminService {

    private final CommentAdminMapper reader;
    private final CommentAdminWrites writer;
    private final Clock clock;

    public CommentAdminService(CommentAdminMapper reader, CommentAdminWrites writer, Clock clock) {
        this.reader = reader;
        this.writer = writer;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 评论

    /**
     * 评论列表（旧 Comment::data）：状态（严格 '0'/'1'）、模块（1/2/3）、用户、举报（1 未举报，其他已举报）、关键字（昵称或内容，
     * {@code htmlspecialchars(urldecode())}），按编号倒序。
     */
    public AdminRowPage comments(PhpForm p, AdminPaging paging) {
        CommentAdminCriteria c = new CommentAdminCriteria();
        c.status = strictZeroOne(p, "status");
        c.mid = looseIn(p, "mid", 3);
        c.uid = nonEmpty(p, "uid");
        if (!p.empty("report")) {
            if (!p.isArray("report") && PhpLoose.equalsOne(p.scalar("report"))) {
                c.reportZero = true;
            } else {
                c.reportPositive = true;
            }
        }
        String wd = wd(p);
        c.wdLike = wd == null ? null : PhpText.legacyLike(wd);
        long total = reader.countComments(c);
        List<AdminRow> rows = new ArrayList<>();
        if (!paging.beyond()) {
            for (Map<String, String> r : reader.listComments(c, paging.offset(), paging.limit())) {
                rows.add(new AdminRow(r));
            }
        }
        return new AdminRowPage(rows, total, paging.page(), paging.limit(), wd);
    }

    public Optional<AdminRow> comment(byte[] id) {
        return AdminIds.lookupId(id).map(reader::selectComment).map(AdminRow::new);
    }

    /**
     * 保存评论（旧 Comment::info 的 POST，{@code input()} → {@code Comment::saveData}）：昵称、内容、模块、对象必填；昵称与内容
     * {@code mac_filter_xss}；有 {@code comment_id} 时按提交的列整行覆盖，否则新增并记录时间。
     */
    public AdminResult saveComment(PhpForm post) {
        return guard(() -> {
            PhpForm data = post.copy();
            for (String[] r : new String[][] {{"comment_name", "validate/require_nick"},
                {"comment_content", "validate/require_content"}, {"comment_mid", "validate/require_mid"},
                {"comment_rid", "validate/require_rid"}}) {
                if (!data.require(r[0])) {
                    return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang(r[1]));
                }
            }
            filterXss(data, List.of("comment_name", "comment_content"));
            if (!data.empty("comment_id")) {
                long id = AdminIds.primaryKey(data.scalar("comment_id"));
                writer.update(true, AdminRowValues.of(AdminTable.COMMENT, data, Set.of("comment_id")), id);
            } else {
                data.put("comment_time", Long.toString(clock.instant().getEpochSecond()));
                writer.insert(true, AdminRowValues.of(AdminTable.COMMENT, data, Set.of("comment_id")));
            }
            return AdminResult.ok("save_ok");
        });
    }

    /** 删除评论（旧 Comment::del）：{@code all == 1} 时清空，否则按 {@code ids}；两者都为空时"参数错误"。 */
    public AdminResult deleteComments(PhpForm param) {
        return delete(param, true);
    }

    /** 评论审核状态（旧 Comment::field，只接受 {@code comment_status}）。 */
    public AdminResult commentField(PhpForm param) {
        return field(param, true, "comment_status", AdminTable.COMMENT);
    }

    // ------------------------------------------------------------------ 留言

    /**
     * 留言列表（旧 Gbook::data）：状态（严格 '0'/'1'）、类型（1 留言、2 报错）、已回复、用户、关键字（昵称或内容），按编号倒序。
     */
    public AdminRowPage gbooks(PhpForm p, AdminPaging paging) {
        CommentAdminCriteria c = new CommentAdminCriteria();
        c.status = strictZeroOne(p, "status");
        String type = looseIn(p, "type", 2);
        if (type != null) {
            if (PhpLoose.equalsOne(type.getBytes(StandardCharsets.UTF_8))) {
                c.ridZero = true;
            } else {
                c.ridPositive = true;
            }
        }
        c.replied = !p.empty("reply");
        c.uid = nonEmpty(p, "uid");
        String wd = wd(p);
        c.wdLike = wd == null ? null : PhpText.legacyLike(wd);
        long total = reader.countGbooks(c);
        List<AdminRow> rows = new ArrayList<>();
        if (!paging.beyond()) {
            for (Map<String, String> r : reader.listGbooks(c, paging.offset(), paging.limit())) {
                rows.add(new AdminRow(r));
            }
        }
        return new AdminRowPage(rows, total, paging.page(), paging.limit(), wd);
    }

    public Optional<AdminRow> gbook(byte[] id) {
        return AdminIds.lookupId(id).map(reader::selectGbook).map(AdminRow::new);
    }

    /**
     * 保存留言（旧 Gbook::info 的 POST，{@code input()} → {@code Gbook::saveData}）：昵称、内容必填；昵称、内容、回复
     * {@code mac_filter_xss}；有 {@code gbook_id} 时（回复非空则记录回复时间）按提交的列整行覆盖，否则新增并记录时间。
     */
    public AdminResult saveGbook(PhpForm post) {
        return guard(() -> {
            PhpForm data = post.copy();
            if (!data.require("gbook_name")) {
                return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_nick"));
            }
            if (!data.require("gbook_content")) {
                return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_content"));
            }
            filterXss(data, List.of("gbook_name", "gbook_content", "gbook_reply"));
            String now = Long.toString(clock.instant().getEpochSecond());
            if (!data.empty("gbook_id")) {
                if (!data.empty("gbook_reply")) {
                    data.put("gbook_reply_time", now);
                }
                long id = AdminIds.primaryKey(data.scalar("gbook_id"));
                writer.update(false, AdminRowValues.of(AdminTable.GBOOK, data, Set.of("gbook_id")), id);
            } else {
                data.put("gbook_time", now);
                writer.insert(false, AdminRowValues.of(AdminTable.GBOOK, data, Set.of("gbook_id")));
            }
            return AdminResult.ok("save_ok");
        });
    }

    public AdminResult deleteGbooks(PhpForm param) {
        return delete(param, false);
    }

    public AdminResult gbookField(PhpForm param) {
        return field(param, false, "gbook_status", AdminTable.GBOOK);
    }

    // ------------------------------------------------------------------ 公共

    private AdminResult delete(PhpForm param, boolean comment) {
        if (param.empty("ids") && param.empty("all")) {
            return AdminResult.error("param_err");
        }
        return guard(() -> {
            if (!param.isArray("all") && param.scalar("all") != null && PhpLoose.equalsOne(param.scalar("all"))) {
                writer.deleteAll(comment);
            } else {
                List<Long> ids = AdminIds.ids(param);
                if (ids != null) {
                    writer.delete(comment, ids);
                }
            }
            return AdminResult.ok("del_ok");
        });
    }

    /** 旧 {@code field()}：{@code ids} 非空且 {@code col} 是唯一可改的状态列；{@code val} 未设置时"参数错误"。 */
    private AdminResult field(PhpForm param, boolean comment, String allowed, AdminTable table) {
        return guard(() -> {
            List<Long> ids = AdminIds.ids(param);
            String col = param.isArray("col") ? null : param.text("col");
            if (ids == null || !allowed.equals(col)) {
                return AdminResult.error("param_err");
            }
            if (!param.has("val") || param.isNull("val")) {
                return AdminResult.error("param_err");
            }
            if (param.isArray("val")) {
                throw AdminRejectedException.paramError();
            }
            writer.updateByIds(comment, Map.of(col, AdminRowValues.value(table.column(col), param.scalar("val"))), ids);
            return AdminResult.ok("set_ok");
        });
    }

    private static AdminResult guard(Supplier<AdminResult> action) {
        try {
            return action.get();
        } catch (AdminRejectedException e) {
            return AdminResult.error(e.reason());
        } catch (CommentDataException e) {
            return AdminResult.error("param_err");
        }
    }

    /** 列出的字段（存在且不是 null 时）{@code mac_filter_xss}；是数组时"参数错误"。 */
    private static void filterXss(PhpForm data, List<String> fields) {
        for (String f : fields) {
            if (data.has(f) && !data.isNull(f)) {
                if (data.isArray(f)) {
                    throw AdminRejectedException.paramError();
                }
                data.put(f, PhpText.filterXss(data.scalar(f)));
            }
        }
    }

    /** {@code in_array($param[k], ['0','1'], true)}：严格等于 {@code 0} 或 {@code 1} 的原值，否则 null。 */
    private static String strictZeroOne(PhpForm p, String k) {
        if (p.isArray(k) || p.scalar(k) == null) {
            return null;
        }
        String v = new String(p.scalar(k), StandardCharsets.UTF_8);
        return v.equals("0") || v.equals("1") ? v : null;
    }

    /** {@code in_array($param[k], ['1', …, 'n'])}（宽松比较）时的原值，否则 null。 */
    private static String looseIn(PhpForm p, String k, int n) {
        if (p.isArray(k) || p.scalar(k) == null) {
            return null;
        }
        for (int i = 1; i <= n; i++) {
            if (PhpLoose.equalsNumber(p.scalar(k), i)) {
                return new String(p.scalar(k), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String nonEmpty(PhpForm p, String k) {
        return p.empty(k) || p.isArray(k) ? null : new String(p.scalar(k), StandardCharsets.UTF_8);
    }

    /** 列表关键字：{@code htmlspecialchars(urldecode(wd))}。 */
    private static String wd(PhpForm p) {
        return p.empty("wd") || p.isArray("wd") ? null
                : new String(PhpText.htmlspecialcharsCompat(PhpText.urldecode(p.scalar("wd"))), StandardCharsets.UTF_8);
    }
}
