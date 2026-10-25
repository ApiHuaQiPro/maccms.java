package com.maccms.backend.admin.application;

import com.maccms.backend.admin.application.view.AdminFormData;
import com.maccms.backend.admin.application.view.AdminListPage;
import com.maccms.backend.admin.application.view.AdminListRow;
import com.maccms.backend.admin.application.view.Reply;
import com.maccms.backend.admin.infrastructure.AdminMapper;
import com.maccms.backend.admin.infrastructure.AdminRow;
import com.maccms.backend.admin.infrastructure.AdminWriteMapper;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.common.web.PhpCookies;
import com.maccms.backend.content.application.PhpIntval;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 管理员管理（B12，21 文档第 4.4 节）：旧 {@code admin/controller/Admin.php} 的 index、info、del、field 与 {@code common/model/Admin.php}
 * 的 listData、infoData、saveData、delData、fieldData。表单令牌与权限检查在 web 层；这里的入参都是 {@code input()} 之后（已经
 * {@link LegacyInput#filterExp}）的原始字节。
 */
public class AdminAccountService {

    /** 每页条数上限（旧版不限；新版防止一次读出整表，21 文档 D-B12-11）。 */
    public static final int MAX_LIMIT = 1000;
    /** 页码上限：更大的页码一定是空页，不再查库。 */
    static final long MAX_PAGE = 1_000_000;
    /** 一次删除或改状态最多的 id 数。 */
    static final int MAX_IDS = 1000;

    private final AdminMapper reader;
    private final AdminWriteMapper writer;
    private final LegacyLang lang;

    public AdminAccountService(AdminMapper reader, AdminWriteMapper writer, LegacyLang lang) {
        this.reader = reader;
        this.writer = writer;
        this.lang = lang;
    }

    /**
     * 列表（旧 Admin::index）：{@code wd} 非空（PHP {@code empty()}）时按 {@code admin_name LIKE '%' . htmlspecialchars(urldecode(wd)) . '%'}
     * 筛选（{@code %}、{@code _} 照旧是通配符），按 {@code admin_id} 倒序分页。
     */
    public AdminListPage list(byte[] wd, byte[] page, byte[] limit, int pageSize) {
        long p = page == null ? 0 : PhpIntval.of(text(page));
        p = p < 1 ? 1 : p;
        long l = limit == null ? 0 : PhpIntval.of(text(limit));
        int size = (int) (l < 1 ? pageSize : Math.min(l, MAX_LIMIT));
        String word = null;
        String like = null;
        if (!LegacyInput.empty(wd)) {
            word = LegacyInput.htmlspecialchars(PhpCookies.urlDecode(wd));
            like = legacyLike(word);
        }
        long total = reader.count(like);
        List<AdminListRow> rows = new ArrayList<>();
        if (p <= MAX_PAGE) {
            for (AdminRow r : reader.list(like, (p - 1) * size, size)) {
                rows.add(new AdminListRow(r.adminId(), r.adminName(), r.adminStatus(), r.adminLastLoginTime(),
                        r.adminLastLoginIp(), r.adminLoginNum()));
            }
        }
        return new AdminListPage(List.copyOf(rows), total, p, size, word);
    }

    /**
     * 表单（旧 Admin::info 的 GET）：按 {@code intval(id)} 读一行；没有 id、id 小于 1 或这一行不存在时为空（显示空表单，提交后是新增）。
     */
    public Optional<AdminFormData> form(byte[] id) {
        long v = id == null ? 0 : PhpIntval.of(text(id));
        if (v < 1) {
            return Optional.empty();
        }
        AdminRow r = reader.selectById(v);
        return r == null ? Optional.empty()
                : Optional.of(new AdminFormData(r.adminId(), r.adminName(), r.adminStatus(), r.adminAuth()));
    }

    /**
     * 保存（旧 Admin::info 的 POST，令牌已由调用方校验 → Admin::saveData）。
     *
     * @param id     {@code admin_id}：PHP {@code empty()} 时新增，否则编辑该 id（新版只接受十进制数字，D-B12-10）
     * @param auth   {@code admin_auth[]} 的各项（已补上 {@code index/welcome}，由调用方按旧控制器的顺序先补再校验令牌）
     * @param status {@code admin_status}：缺失时新增取表默认值 1、编辑不改；只接受宽松等于 0 或 1 的值（D-B12-9）
     */
    public Reply save(byte[] id, byte[] name, byte[] pwd, byte[] status, List<byte[]> auth) {
        // 旧版 validate 场景 edit：admin_name require（!empty || '0' == 值），新增与编辑都只校验名称（Admin.php:52-71）
        if (name == null || name.length == 0) {
            return Reply.error(lang.get("param_err") + "：" + lang.get("validate/require_name"));
        }
        String adminName = LegacyInput.utf8Bmp(name);
        if (adminName == null || adminName.codePointCount(0, adminName.length()) > 30) {
            return Reply.error(lang.get("param_err"));
        }
        StringBuilder joined = new StringBuilder(",");
        for (byte[] item : auth) {
            String s = LegacyInput.utf8Bmp(item);
            if (s == null) {
                return Reply.error(lang.get("param_err"));
            }
            joined.append(s).append(',');
        }
        String adminAuth = joined.toString();
        if (adminAuth.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65535) {
            return Reply.error(lang.get("param_err"));
        }
        Integer st = null;
        if (status != null) {
            String s = text(status);
            if (LegacyInput.looseEqualsNumber(s, 0)) {
                st = 0;
            } else if (LegacyInput.looseEqualsNumber(s, 1)) {
                st = 1;
            } else {
                return Reply.error(lang.get("param_err"));
            }
        }
        if (LegacyInput.empty(id)) {
            writer.insert(adminName, LegacyInput.md5Hex(pwd == null ? new byte[0] : pwd), st == null ? 1 : st, adminAuth);
        } else {
            Long adminId = digits(id);
            if (adminId == null) {
                return Reply.error(lang.get("param_err"));
            }
            // 密码 empty()（空串或 "0"）时不改（Admin.php:61-62）
            String newPwd = LegacyInput.empty(pwd) ? null : LegacyInput.md5Hex(pwd);
            writer.update(adminId, adminName, newPwd, st, adminAuth);
        }
        return Reply.success(lang.get("save_ok"));
    }

    /**
     * 删除（旧 Admin::del）：{@code ids} 为空 → {@code param_err}；含当前管理员 → {@code admin/admin/del_cur_err}；否则按 id 删除。
     *
     * @param ids 逗号分隔的字符串拆开后的各项，或 {@code ids[]} 的各项；缺失为 null
     */
    public Reply delete(List<byte[]> ids, long currentAdminId) {
        if (ids == null || ids.isEmpty() || (ids.size() == 1 && LegacyInput.empty(ids.get(0)))) {
            return Reply.error(lang.get("param_err"));
        }
        List<Long> parsed = parseIds(ids);
        if (parsed == null) {
            return Reply.error(lang.get("param_err"));
        }
        if (parsed.contains(currentAdminId)) {
            return Reply.error(lang.get("admin/admin/del_cur_err"));
        }
        writer.delete(parsed);
        return Reply.success(lang.get("del_ok"));
    }

    /**
     * 状态（旧 Admin::field）：{@code ids} 非空、{@code col} 为 {@code admin_status}、{@code val} 宽松等于 {@code '0'} 或 {@code '1'}
     * 时设置，否则 {@code param_err}。不检查是不是当前管理员（旧版同样允许停用自己，新版下一个请求即被要求重新登录）。
     */
    public Reply setStatus(List<byte[]> ids, byte[] col, byte[] val) {
        if (ids == null || ids.isEmpty() || (ids.size() == 1 && LegacyInput.empty(ids.get(0))) || col == null
                || !text(col).equals("admin_status") || val == null) {
            return Reply.error(lang.get("param_err"));
        }
        String v = text(val);
        int status;
        if (LegacyInput.looseEqualsNumber(v, 0)) {
            status = 0;
        } else if (LegacyInput.looseEqualsNumber(v, 1)) {
            status = 1;
        } else {
            return Reply.error(lang.get("param_err"));
        }
        List<Long> parsed = parseIds(ids);
        if (parsed == null) {
            return Reply.error(lang.get("param_err"));
        }
        writer.setStatus(parsed, status);
        return Reply.success(lang.get("set_ok"));
    }

    /**
     * 旧版 {@code ['in', $ids]}：每项都必须是十进制数字（可带首尾空格）。旧版交给 MySQL 隐式转换（{@code abc} 当 0、{@code 1abc} 当 1），
     * 而 PHP 8 的 {@code in_array} 认为 {@code 1abc} 不等于当前管理员的 1，于是 {@code ids=1abc} 会删掉当前登录的超级管理员（黄金
     * {@code admin-del/self-loose}）。新版不接受这类值（D-B12-10）。
     */
    static List<Long> parseIds(List<byte[]> ids) {
        if (ids.size() > MAX_IDS) {
            return null;
        }
        List<Long> out = new ArrayList<>();
        for (byte[] raw : ids) {
            Long v = digits(raw);
            if (v == null) {
                return null;
            }
            out.add(v);
        }
        return out;
    }

    /** 十进制数字（可带首尾空格，最多 9 位）；其他为 null。 */
    static Long digits(byte[] raw) {
        if (raw == null) {
            return null;
        }
        String s = text(raw).strip();
        if (s.isEmpty() || s.length() > 9 || !s.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        return Long.parseLong(s);
    }

    /**
     * 旧版 {@code LIKE '%词%'}（未转义：{@code %}、{@code _} 是通配符，反斜线让下一个字符按字面量匹配）转写成 {@code ESCAPE '!'} 下
     * 匹配同样字符串的模式（与 B11 的视频搜索缓存相同，content.application.VodSearchCache）。
     */
    static String legacyLike(String word) {
        StringBuilder sb = new StringBuilder(word.length() + 4).append('%');
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c == '\\' && i + 1 < word.length()) {
                char next = word.charAt(++i);
                sb.append('!').append(next);
                if (Character.isHighSurrogate(next) && i + 1 < word.length()) {
                    sb.append(word.charAt(++i));
                }
            } else if (c == '!') {
                sb.append("!!");
            } else {
                sb.append(c);
            }
        }
        return sb.append('%').toString();
    }

    /** 原始字节按 ISO-8859-1 逐字节转成字符（数字与关键字的判断只看 ASCII）。 */
    static String text(byte[] raw) {
        return new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}
