package com.maccms.backend.member.application;

import static com.maccms.backend.member.application.MemberAdminSupport.guard;
import static com.maccms.backend.member.application.MemberAdminSupport.like;
import static com.maccms.backend.member.application.MemberAdminSupport.looseEquals;
import static com.maccms.backend.member.application.MemberAdminSupport.strictZeroOne;
import static com.maccms.backend.member.application.MemberAdminSupport.wd;

import com.maccms.backend.content.application.AdminConfig;
import com.maccms.backend.content.application.AdminIds;
import com.maccms.backend.content.application.AdminRejectedException;
import com.maccms.backend.content.application.AdminRowValues;
import com.maccms.backend.content.application.AdminTable;
import com.maccms.backend.content.application.PhpForm;
import com.maccms.backend.content.application.PhpJson;
import com.maccms.backend.content.application.PhpLoose;
import com.maccms.backend.content.application.view.AdminMessage;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import com.maccms.backend.member.infrastructure.MemberAdminCriteria;
import com.maccms.backend.member.infrastructure.MemberAdminMapper;
import com.maccms.backend.member.infrastructure.MemberAdminWrites;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 后台会员组管理（B14，23 文档第 7.2 节）：旧 {@code admin/controller/Group.php} → {@code common/model/Group.php}。列表、表单、保存
 * （{@code group_type} 以逗号包起、{@code group_popedom} 按 PHP {@code json_encode}）、删除（组下还有会员时拒绝）、状态。
 *
 * <p>旧控制器用 {@code $GLOBALS['config']['user']['reg_group']}（注册默认会员组）：保存时宽松等于提交的 {@code group_id} 就强制启用，删除时
 * 出现在 {@code ','.$ids.','} 里就拒绝。仓库与旧后台的系统配置页都没有这一项（值为 null），所以新增会员组（{@code group_id} 为空或缺失）
 * 总是被强制启用、{@code ids} 里有空段（如 {@code 5,}）时"注册默认会员组无法删除!"——新版照旧（黄金 {@code group-save/new-status-0}、
 * {@code group-del/reg-group-blank}），配置里有这一项时同样按它判断。
 */
public final class GroupAdminService {

    private final MemberAdminMapper reader;
    private final MemberAdminWrites writer;
    private final AdminConfig config;

    public GroupAdminService(MemberAdminMapper reader, MemberAdminWrites writer, AdminConfig config) {
        this.reader = reader;
        this.writer = writer;
        this.config = config;
    }

    /** 会员组列表（旧 Group::index）：状态严格 {@code '0'/'1'}、名称关键字，按编号升序，不分页。 */
    public List<AdminRow> list(PhpForm p) {
        MemberAdminCriteria c = new MemberAdminCriteria();
        c.status = strictZeroOne(p, "status");
        c.like = like(wd(p));
        return reader.listGroups(c).stream().map(AdminRow::new).toList();
    }

    public Optional<AdminRow> info(byte[] id) {
        return AdminIds.lookupId(id).map(reader::selectGroup).map(AdminRow::new);
    }

    /**
     * 保存（旧 Group::info 的 POST，Group.php:36-53 → {@code Group::saveData}，common/model/Group.php:54-86）。
     *
     * @param post     {@code input('post.')}
     * @param popedom  {@code $param['group_popedom']} 的 PHP 值：缺失为 null，标量为字节串，数组为按出现顺序的多维 {@link Map}
     *                 （{@code AdminParams.nested}）
     */
    public AdminResult save(PhpForm post, Object popedom) {
        return guard(() -> {
            PhpForm data = post.copy();
            if (regGroupEquals(config.value("user", "reg_group"), data.isArray("group_id") ? null : data.scalar("group_id"))) {
                data.put("group_status", "1");
            }
            if (!data.empty("group_type")) {
                if (!data.isArray("group_type")) {
                    // 旧版 join(',', 字符串)：PHP 8 报错（500），PHP 7.3 告警后写成 ',,'
                    throw AdminRejectedException.paramError();
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                out.write(',');
                List<byte[]> items = new ArrayList<>(data.array("group_type").entries().values());
                for (int i = 0; i < items.size(); i++) {
                    if (i > 0) {
                        out.write(',');
                    }
                    out.writeBytes(items.get(i));
                }
                out.write(',');
                data.put("group_type", out.toByteArray());
            } else {
                data.put("group_type", "");
            }
            if (!emptyValue(popedom)) {
                data.put("group_popedom", PhpJson.encode(jsonValue(popedom)));
            } else {
                data.put("group_popedom", "");
            }
            if (!data.require("group_name")) {
                return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_name"));
            }
            if (!data.empty("group_id")) {
                long id = AdminIds.primaryKey(data.scalar("group_id"));
                writer.updateGroup(AdminRowValues.of(AdminTable.GROUP, data, Set.of()), id);
            } else {
                writer.insertGroup(AdminRowValues.of(AdminTable.GROUP, data, Set.of("group_id")));
            }
            return AdminResult.ok("save_ok");
        });
    }

    /**
     * 删除（旧 Group::del，Group.php:66-86 → {@code Group::delData}）：{@code ids} 为空 → "参数错误"；注册默认会员组 → 拒绝；组下还有会员 →
     * "删除失败：用户组下还有用户"；否则删除。删除语句另带"组下没有会员"的条件（检查与删除之间被分配了会员的组不删，同样返回上述提示）。
     */
    public AdminResult delete(PhpForm p) {
        if (p.empty("ids")) {
            return AdminResult.error("param_err");
        }
        byte[] haystack = concat(",", p.isArray("ids") ? "Array".getBytes(StandardCharsets.US_ASCII) : p.scalar("ids"), ",");
        byte[] needle = concat(",", regGroupText(config.value("user", "reg_group")), ",");
        if (indexOf(haystack, needle) >= 0) {
            return AdminResult.error("admin/group/reg_group_del_err");
        }
        AdminMessage haveUser = AdminMessage.lang("del_err").thenText("：").thenLang("model/group/have_user");
        return guard(() -> {
            List<Long> ids = AdminIds.ids(p);
            if (reader.countUsersInGroups(ids) > 0) {
                return AdminResult.error(haveUser);
            }
            writer.deleteEmptyGroups(ids);
            if (reader.countGroups(ids) > 0 && reader.countUsersInGroups(ids) > 0) {
                return AdminResult.error(haveUser);
            }
            return AdminResult.ok("del_ok");
        });
    }

    /** 状态（旧 Group::field）：{@code ids} 非空、{@code col} 为 {@code group_status}、{@code val} 宽松等于 0 或 1（不限会员组编号，照旧）。 */
    public AdminResult field(PhpForm p) {
        return guard(() -> {
            List<Long> ids = AdminIds.ids(p);
            String col = p.isArray("col") ? null : p.text("col");
            boolean val = looseEquals(p, "val", 0) || looseEquals(p, "val", 1);
            if (ids == null || !"group_status".equals(col) || !val) {
                return AdminResult.error("param_err");
            }
            Map<String, String> set = new LinkedHashMap<>();
            set.put("group_status", AdminRowValues.value(AdminTable.GROUP.column("group_status"), p.scalar("val")));
            writer.updateGroups(set, ids);
            return AdminResult.ok("set_ok");
        });
    }

    /**
     * 旧 {@code json_decode($row['group_popedom'], true)} 的取值 {@code [分类][位]}（表单勾选：{@code !empty(...)}）。不是对象或数组的 JSON
     * （空串、损坏的值、JSON 字符串）按没有权限处理。
     */
    public static boolean popedomChecked(Object decoded, String typeId, String bit) {
        if (!(decoded instanceof Map<?, ?> m)) {
            return listChecked(decoded, typeId, bit);
        }
        Object perType = m.get(typeId);
        if (perType instanceof Map<?, ?> pm) {
            return !phpEmpty(pm.get(bit));
        }
        return listChecked(perType, bit, null);
    }

    private static boolean listChecked(Object v, String index, String next) {
        if (!(v instanceof List<?> l) || !index.matches("0|[1-9][0-9]{0,8}")) {
            return false;
        }
        int i = Integer.parseInt(index);
        if (i >= l.size()) {
            return false;
        }
        Object x = l.get(i);
        if (next == null) {
            return !phpEmpty(x);
        }
        if (x instanceof Map<?, ?> xm) {
            return !phpEmpty(xm.get(next));
        }
        return listChecked(x, next, null);
    }

    private static boolean phpEmpty(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof String s) {
            return s.isEmpty() || s.equals("0");
        }
        if (v instanceof Map<?, ?> m) {
            return m.isEmpty();
        }
        if (v instanceof List<?> l) {
            return l.isEmpty();
        }
        if (v instanceof Boolean b) {
            return !b;
        }
        return false;
    }

    /** PHP {@code empty($v)}（字节串、多维数组）。 */
    private static boolean emptyValue(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof byte[] b) {
            return PhpForm.phpEmpty(b);
        }
        return v instanceof Map<?, ?> m && m.isEmpty();
    }

    /**
     * 多维数组 → {@link PhpJson#encode} 的值：键恰好是 0、1、2…（按顺序）的数组按 JSON 数组输出，否则按对象（同 PHP {@code json_encode}）；
     * 叶子按 UTF-8（不是合法 UTF-8 时"参数错误"：旧版 {@code json_encode} 失败、写入 false）。
     */
    static Object jsonValue(Object v) {
        if (v instanceof byte[] b) {
            if (!com.maccms.backend.content.application.PhpText.validUtf8(b)) {
                throw AdminRejectedException.paramError();
            }
            return new String(b, StandardCharsets.UTF_8);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        boolean list = true;
        int i = 0;
        for (String k : m.keySet()) {
            if (!k.equals(Integer.toString(i++))) {
                list = false;
                break;
            }
        }
        if (list) {
            List<Object> out = new ArrayList<>();
            m.values().forEach(x -> out.add(jsonValue(x)));
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, x) -> out.put(k, jsonValue(x)));
        return out;
    }

    /** {@code $reg_group == $param['group_id']}（PHP 8 宽松比较；配置缺失为 null，参数缺失为 null）。 */
    static boolean regGroupEquals(PhpValue reg, byte[] groupId) {
        if (reg == null || reg instanceof PhpValue.PhpNull) {
            return groupId == null || groupId.length == 0;
        }
        if (reg instanceof PhpValue.PhpString s) {
            if (groupId == null) {
                return s.value().isEmpty();
            }
            String g = new String(groupId, StandardCharsets.ISO_8859_1);
            String r = new String(s.value().getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
            if (PhpLoose.isNumeric(r) && PhpLoose.isNumeric(g)) {
                return Double.parseDouble(r.strip()) == Double.parseDouble(g.strip());
            }
            return r.equals(g);
        }
        if (reg instanceof PhpValue.PhpInt n) {
            if (groupId == null) {
                return n.value() == 0;
            }
            String g = new String(groupId, StandardCharsets.ISO_8859_1);
            return PhpLoose.isNumeric(g) ? Double.parseDouble(g.strip()) == n.value() : g.equals(Long.toString(n.value()));
        }
        return false;
    }

    /** {@code ','.$reg_group.','} 里的配置值（PHP 字符串转换；null 为空串）。 */
    private static byte[] regGroupText(PhpValue reg) {
        return reg == null ? new byte[0] : PhpValues.text(reg).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(String a, byte[] b, String c) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(a.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(b == null ? new byte[0] : b);
        out.writeBytes(c.getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
