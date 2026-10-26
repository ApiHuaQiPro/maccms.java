package com.maccms.backend.member.application;

import static com.maccms.backend.member.application.MemberAdminSupport.guard;
import static com.maccms.backend.member.application.MemberAdminSupport.like;
import static com.maccms.backend.member.application.MemberAdminSupport.looseEquals;
import static com.maccms.backend.member.application.MemberAdminSupport.nonEmpty;
import static com.maccms.backend.member.application.MemberAdminSupport.strictZeroOne;
import static com.maccms.backend.member.application.MemberAdminSupport.text;
import static com.maccms.backend.member.application.MemberAdminSupport.wd;

import com.maccms.backend.content.application.AdminIds;
import com.maccms.backend.content.application.AdminPaging;
import com.maccms.backend.content.application.AdminRejectedException;
import com.maccms.backend.content.application.AdminRowValues;
import com.maccms.backend.content.application.AdminTable;
import com.maccms.backend.content.application.PhpForm;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.content.application.PhpLoose;
import com.maccms.backend.content.application.view.AdminMessage;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import com.maccms.backend.member.application.view.MemberRewardStats;
import com.maccms.backend.member.infrastructure.MemberAdminCriteria;
import com.maccms.backend.member.infrastructure.MemberAdminMapper;
import com.maccms.backend.member.infrastructure.MemberAdminWrites;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 后台会员管理（B14，docs/architecture/23-b14-admin-member.md 第 7.1 节）：旧 {@code admin/controller/User.php} →
 * {@code common/model/User.php}。列表（第 1 页先执行到期降级）、表单、保存（整个 POST 写进表里有的列，密码 {@code md5(原值)}）、删除、状态、
 * 推广会员。
 */
public final class UserAdminService {

    private final MemberAdminMapper reader;
    private final MemberAdminWrites writer;
    private final MemberExpiryService expiry;
    private final Clock clock;

    public UserAdminService(MemberAdminMapper reader, MemberAdminWrites writer, MemberExpiryService expiry, Clock clock) {
        this.reader = reader;
        this.writer = writer;
        this.expiry = expiry;
        this.clock = clock;
    }

    /**
     * 会员列表（旧 User::data，User.php:12-55）：{@code $param['page'] == 1}（缺省、小于 1 都按 1；{@code 1.0} 也算）时先执行
     * {@link MemberExpiryService#expireAll()}（与 B10 共用 {@code group_id > 2 AND user_end_time <= now} 的批量降级语句）；状态严格
     * {@code '0'/'1'}、会员组（{@code !empty}）、会员名关键字（{@code htmlspecialchars(urldecode())}），按编号倒序。
     */
    public AdminRowPage list(PhpForm p, AdminPaging paging) {
        if (firstPage(p)) {
            expiry.expireAll();
        }
        MemberAdminCriteria c = new MemberAdminCriteria();
        c.status = strictZeroOne(p, "status");
        c.group = nonEmpty(p, "group");
        String wd = wd(p);
        c.like = like(wd);
        return MemberAdminSupport.page(paging, wd, () -> reader.countUsers(c), (o, l) -> reader.listUsers(c, o, l));
    }

    /** 旧 {@code intval($param['page']) < 1 ? 1 : $param['page']} 之后 {@code $param['page'] == 1}（PHP 8 宽松比较）。 */
    static boolean firstPage(PhpForm p) {
        if (p.isArray("page") || p.scalar("page") == null) {
            return true;
        }
        byte[] raw = p.scalar("page");
        if (PhpIntval.of(new String(raw, StandardCharsets.ISO_8859_1)) < 1) {
            return true;
        }
        return PhpLoose.equalsNumber(raw, 1);
    }

    /** 表单页（旧 {@code infoData(['user_id' => ['eq', $id]])}，密码置空）：没有这一行时为空。 */
    public Optional<AdminRow> info(byte[] id) {
        return AdminIds.lookupId(id).map(reader::selectUser).map(AdminRow::new);
    }

    /**
     * 保存（旧 User::info 的 POST → {@code User::saveData}，common/model/User.php:58-94）：
     * <ol>
     * <li>{@code user_end_time} 存在且不是数字串时 {@code strtotime}（{@link LegacyTime#formDateTime}；{@code user_start_time} 不是表里的列，
     * 旧版换算后也不写，新版不处理）；</li>
     * <li>验证（{@code edit} 场景，新增也用它）：会员名 {@code require}、{@code min:6}（{@code mb_strlen}）——"参数错误：名称必须"、
     * "参数错误：名称最少不能低于6个字符"；</li>
     * <li>有 {@code user_id}（{@code !empty}）时：密码 {@code empty()} 则不改，否则 {@code md5(原值)}（不 trim、不 urldecode、不
     * htmlspecialchars，只经 ThinkPHP {@code filterExp}，03 草稿第 4.3 (1) 节）；按主键把提交的、表里有的列整行覆盖（含积分、冻结积分、推广人
     * 等，与旧版相同）。没有时新增，密码 {@code md5(原值)}（缺失为 {@code md5('')}）。</li>
     * </ol>
     * 旧版严格模式下报错（整页 500）的取值（超长、非数字、负数等）返回"参数错误"（D-B13-3 的同一规则）。
     */
    public AdminResult save(PhpForm post) {
        return guard(() -> {
            PhpForm data = post.copy();
            if (data.has("user_end_time") && !data.isNull("user_end_time")) {
                if (data.isArray("user_end_time")) {
                    throw AdminRejectedException.paramError();
                }
                String end = text(data.scalar("user_end_time"));
                if (!PhpLoose.isNumeric(end)) {
                    data.put("user_end_time", Long.toString(LegacyTime.formDateTime(end, clock.instant().getEpochSecond())));
                }
            }
            if (data.isArray("user_name")) {
                throw AdminRejectedException.paramError();
            }
            if (!data.require("user_name")) {
                return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_name"));
            }
            String name = text(data.scalar("user_name"));
            if (name.codePointCount(0, name.length()) < 6) {
                return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_name_min"));
            }
            if (data.isArray("user_pwd")) {
                throw AdminRejectedException.paramError();
            }
            if (!data.empty("user_id")) {
                long id = AdminIds.primaryKey(data.scalar("user_id"));
                if (data.empty("user_pwd")) {
                    data.remove("user_pwd");
                } else {
                    data.put("user_pwd", md5(data.scalar("user_pwd")));
                }
                writer.updateUser(AdminRowValues.of(AdminTable.USER, data, Set.of()), id);
            } else {
                byte[] pwd = data.scalar("user_pwd");
                data.put("user_pwd", md5(pwd == null ? new byte[0] : pwd));
                writer.insertUser(AdminRowValues.of(AdminTable.USER, data, Set.of("user_id")));
            }
            return AdminResult.ok("save_ok");
        });
    }

    /** 删除（旧 User::del）：{@code ids} 为空时"参数错误"。 */
    public AdminResult delete(PhpForm p) {
        return guard(() -> {
            List<Long> ids = AdminIds.ids(p);
            if (ids == null) {
                return AdminResult.error("param_err");
            }
            writer.deleteUsers(ids);
            return AdminResult.ok("del_ok");
        });
    }

    /** 状态（旧 User::field）：{@code ids} 非空、{@code col} 为 {@code user_status}、{@code val} 宽松等于 0 或 1。 */
    public AdminResult field(PhpForm p) {
        return guard(() -> {
            List<Long> ids = AdminIds.ids(p);
            String col = p.isArray("col") ? null : p.text("col");
            boolean val = looseEquals(p, "val", 0) || looseEquals(p, "val", 1);
            if (ids == null || !"user_status".equals(col) || !val) {
                return AdminResult.error("param_err");
            }
            Map<String, String> set = new LinkedHashMap<>();
            set.put("user_status", AdminRowValues.value(AdminTable.USER.column("user_status"), p.scalar("val")));
            writer.updateUsers(set, ids);
            return AdminResult.ok("set_ok");
        });
    }

    /**
     * 推广会员（旧 User::reward，User.php:57-135）：{@code uid = intval(uid)}；{@code level} 非空时宽松等于 1/2/3 只看对应的一列（其他值不加
     * 推广条件，列出全部会员，照旧），为空时三列任一等于推广人；会员名关键字同列表。另有三级人数与 {@code plog_type} 4/5/6 的奖励积分合计。
     */
    public MemberRewardStats reward(PhpForm p, AdminPaging paging) {
        long uid = p.isArray("uid") || p.scalar("uid") == null ? 0
                : PhpIntval.of(new String(p.scalar("uid"), StandardCharsets.ISO_8859_1));
        MemberAdminCriteria c = new MemberAdminCriteria();
        if (!p.empty("level")) {
            for (int level = 1; level <= 3; level++) {
                if (looseEquals(p, "level", level)) {
                    c.pid = uid;
                    c.pidLevel = level;
                }
            }
        } else {
            c.pid = uid;
            c.pidLevel = 0;
        }
        String wd = wd(p);
        c.like = like(wd);
        AdminRowPage list = MemberAdminSupport.page(paging, wd, () -> reader.countUsers(c), (o, l) -> reader.listUsers(c, o, l));
        return new MemberRewardStats(list, uid,
                reader.countInvitees(1, uid), reader.countInvitees(2, uid), reader.countInvitees(3, uid),
                reader.sumPlogPoints(uid, 4), reader.sumPlogPoints(uid, 5), reader.sumPlogPoints(uid, 6));
    }

    /** 全部会员组（旧 {@code Group::getCache('group_list')} / {@code listData([], 'group_id asc')}），按编号升序。 */
    public List<AdminRow> groups() {
        return reader.listGroups(new MemberAdminCriteria()).stream().map(AdminRow::new).toList();
    }

    static String md5(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(raw));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
