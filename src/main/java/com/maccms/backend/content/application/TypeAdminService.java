package com.maccms.backend.content.application;

import com.maccms.backend.content.application.view.AdminMessage;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminTypeNode;
import com.maccms.backend.content.infrastructure.ContentAdminMapper;
import com.maccms.backend.content.infrastructure.ContentAdminWrites;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 后台分类（B13，22 文档第 7.1 节）：旧 {@code admin/controller/Type.php} → {@code common/model/Type.php}。旧版的分类缓存
 * （{@code getCache('type_list')}/{@code type_tree}）新版每次从库里读（分类表很小，读到的总是最新的，等同于旧版每次保存后 {@code setCache}）。
 */
public class TypeAdminService {

    /** 旧 infoData 在 type_extend 为空时的默认值（common/model/Type.php:224）。 */
    private static final String DEFAULT_EXTEND =
            "{\"type\":\"\",\"area\":\"\",\"lang\":\"\",\"year\":\"\",\"star\":\"\",\"director\":\"\",\"state\":\"\",\"version\":\"\"}";
    private static final List<String> XSS_FIELDS = List.of("type_name", "type_en", "type_tpl", "type_tpl_list", "type_tpl_detail",
            "type_tpl_play", "type_tpl_down", "type_key", "type_des", "type_title", "type_union", "type_logo", "type_pic",
            "type_jumpurl");

    private final ContentAdminMapper reader;
    private final ContentAdminWrites writer;
    private final AdminConfig config;

    public TypeAdminService(ContentAdminMapper reader, ContentAdminWrites writer, AdminConfig config) {
        this.reader = reader;
        this.writer = writer;
        this.config = config;
    }

    // ------------------------------------------------------------------ 读

    /**
     * 分类列表（旧 Type::index）：{@code listData([], 'type_sort asc', 'tree')}，每个分类附上视频、文章、演员、网址的数据数——按
     * {@code type_id_1、type_id} 分组后依次累加：{@code $c[type_id_1] += cc; $c[type_id] = cc}（同一分类在后面的表里出现时覆盖前面的值，
     * 与旧版相同）。
     */
    public List<AdminTypeNode> index() {
        Map<Long, Long> count = new HashMap<>();
        for (List<Map<String, Object>> rows : List.of(reader.countVodByType(), reader.countArtByType(),
                reader.countActorByType(), reader.countWebsiteByType())) {
            for (Map<String, Object> r : rows) {
                long p = ((Number) r.get("p")).longValue();
                long t = ((Number) r.get("t")).longValue();
                long cc = ((Number) r.get("cc")).longValue();
                count.merge(p, cc, Long::sum);
                count.put(t, cc);
            }
        }
        return tree(rows(reader.listTypes(false)), count);
    }

    /** 分类树（旧 {@code getCache('type_tree')}：{@code listData([], 'type_id asc')} 再 {@code mac_list_to_tree}）。 */
    public List<AdminTypeNode> cacheTree() {
        return tree(rows(reader.listTypesById()), Map.of());
    }

    /** 分类表（旧 {@code getCache('type_list')}，按 type_id）。 */
    public Map<Long, AdminRow> cacheList() {
        Map<Long, AdminRow> out = new LinkedHashMap<>();
        for (AdminRow r : rows(reader.listTypesById())) {
            out.put(r.n("type_id"), r);
        }
        return out;
    }

    /** 表单与上级（旧 Type::info 的 GET）：{@code input('id')} 与 {@code input('pid')} 各读一行。 */
    public Optional<AdminRow> info(byte[] id) {
        return AdminIds.lookupId(id).map(reader::selectType).map(AdminRow::new);
    }

    /** 顶级分类（旧 Type::info：{@code where type_pid = '0'}，{@code type_sort asc}）。 */
    public List<AdminRow> parents() {
        return rows(reader.listTypes(true));
    }

    /**
     * 表单里扩展字典的显示值（旧 infoData：{@code json_decode(type_extend, true)}，为空时是固定的默认字典）：键 → PHP 打印的文字；
     * JSON 不合法时为空表（旧版得到 null，模板输出空串）。
     */
    public static Map<String, String> extendValues(AdminRow info) {
        String raw = info == null ? "" : info.s("type_extend");
        Object decoded = PhpJson.decode(raw.isEmpty() ? DEFAULT_EXTEND : raw);
        Map<String, String> out = new LinkedHashMap<>();
        if (decoded instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v instanceof String s ? s : v == null ? "" : String.valueOf(v)));
        }
        return out;
    }

    // ------------------------------------------------------------------ 写

    /** 保存（旧 Type::info 的 POST：令牌已由调用方校验；{@code Type::saveData}）。 */
    public AdminResult save(PhpForm param) {
        return AdminWrites.guard(() -> saveData(param.copy()));
    }

    /**
     * 批量修改（旧 Type::batch）：{@code ids[]} 的每一项与 {@code type_name_<id>}、{@code type_sort_<id>}、{@code type_en_<id>}、
     * {@code type_tpl_<id>}、{@code type_tpl_list_<id>}、{@code type_tpl_detail_<id>} 组成一行交给 {@code saveData}（没有的参数是 null），
     * 名称为空时用 {@code lang('unknown')}；某一行失败时立即返回该提示（之前的行已保存）。
     */
    public AdminResult batch(PhpForm param, String unknownName) {
        PhpForm.Arr ids = param.array("ids");
        if (ids == null) {
            return AdminResult.error("param_err");
        }
        return AdminWrites.guard(() -> {
            AdminResult last = AdminResult.ok("save_ok");
            for (byte[] raw : ids.entries().values()) {
                String id = new String(raw, StandardCharsets.UTF_8);
                PhpForm data = PhpForm.empty();
                data.put("type_id", Long.toString(PhpIntval.of(new String(raw, StandardCharsets.ISO_8859_1))));
                for (String col : List.of("type_name", "type_sort", "type_en", "type_tpl", "type_tpl_list", "type_tpl_detail")) {
                    byte[] v = param.scalar(col + "_" + id);
                    if (v == null) {
                        if (param.isArray(col + "_" + id)) {
                            throw AdminRejectedException.paramError();
                        }
                        data.putNull(col);
                    } else {
                        data.put(col, v);
                    }
                }
                if (data.empty("type_name")) {
                    data.put("type_name", unknownName);
                }
                last = saveData(data);
                if (!last.ok()) {
                    return last;
                }
            }
            return last;
        });
    }

    /**
     * 删除（旧 Type::del → Type::delData）：逐个分类检查——视频分类（type_mid=1）查视频，其余查文章（旧版如此，演员、网址分类也查文章），
     * {@code type_id|type_id_1} 命中则"删除失败：<名称>还有<n>条数据，请先删除或转移"，否则全部删除。
     */
    public AdminResult delete(PhpForm param) {
        return AdminWrites.guard(() -> {
            List<Long> ids = AdminIds.ids(param);
            if (ids == null) {
                return AdminResult.error("param_err");
            }
            for (AdminRow t : rows(reader.selectTypesByIds(ids))) {
                long id = t.n("type_id");
                long cc = t.n("type_mid") == 1 ? reader.countVodInType(id) : reader.countArtInType(id);
                if (cc > 0) {
                    return AdminResult.error(AdminMessage.lang("del_err").thenText("：" + t.s("type_name") + "还有" + cc
                            + "条数据，请先删除或转移"));
                }
            }
            writer.delete(AdminTable.TYPE.table(), ids);
            return AdminResult.ok("del_ok");
        });
    }

    /** 状态（旧 Type::field）：{@code col} 只能是 {@code type_status}，{@code val} 宽松等于 {@code '0'} 或 {@code '1'}。 */
    public AdminResult field(PhpForm param) {
        return AdminWrites.guard(() -> {
            List<Long> ids = AdminIds.ids(param);
            byte[] col = param.scalar("col");
            byte[] val = param.scalar("val");
            if (ids == null || col == null || !"type_status".equals(new String(col, StandardCharsets.ISO_8859_1))
                    || val == null || !(PhpLoose.equalsNumber(val, 0) || PhpLoose.equalsNumber(val, 1))) {
                return AdminResult.error("param_err");
            }
            Map<String, String> c = new LinkedHashMap<>();
            c.put("type_status", AdminRowValues.value(AdminTable.TYPE.column("type_status"), val));
            writer.updateByIds(AdminTable.TYPE.table(), c, ids);
            return AdminResult.ok("set_ok");
        });
    }

    /**
     * 扩展字典（旧 Type::extend，供视频/文章表单按分类取扩展项）：分类的扩展项为空时依次取上级分类、旧配置
     * （文章 {@code app.art_extend_class}；其余 {@code app.vod_extend_class/state/version/area/lang/year}）；每项按逗号拆成数组。
     * 没有 {@code id} 时返回 null（旧版输出空页面）；分类不存在时"获取信息失败"。
     */
    public AdminResult extend(PhpForm param) {
        if (param.empty("id")) {
            return null;
        }
        Long key = param.isArray("id") ? null : PhpForm.Arr.intKey(new String(param.scalar("id"), StandardCharsets.UTF_8));
        Map<Long, AdminRow> list = cacheList();
        AdminRow info = key == null ? null : list.get(key);
        if (info == null) {
            return AdminResult.error("get_info_err");
        }
        AdminRow pinfo = list.get(info.n("type_pid"));
        Map<String, Object> ext = decodedExtend(info);
        Map<String, Object> pext = pinfo == null ? null : decodedExtend(pinfo);
        if (info.n("type_mid") == 2) {
            inherit(ext, pext, "class", "art_extend_class");
        } else {
            for (String k : List.of("class", "state", "version", "area", "lang", "year")) {
                inherit(ext, pext, k, "vod_extend_" + k);
            }
        }
        Object data = null;
        if (!ext.isEmpty()) {
            Map<String, List<String>> out = new LinkedHashMap<>();
            ext.forEach((k, v) -> {
                String s = v instanceof String str ? str : v == null ? "" : String.valueOf(v);
                out.put(k, List.of(s.split(",", -1)));
            });
            data = out;
        }
        return new AdminResult(true, AdminMessage.text("ok"), data);
    }

    private void inherit(Map<String, Object> ext, Map<String, Object> pext, String key, String configKey) {
        if (phpEmpty(ext.get(key)) && pext != null && !phpEmpty(pext.get(key))) {
            ext.put(key, pext.get(key));
        } else if (phpEmpty(ext.get(key)) && !config.empty("app", configKey)) {
            ext.put(key, config.text("app", configKey));
        }
    }

    /** {@code json_decode(type_extend, true)}（缓存里的分类）：不是对象时为空表。 */
    private static Map<String, Object> decodedExtend(AdminRow r) {
        Object d = PhpJson.decode(r.s("type_extend"));
        Map<String, Object> out = new LinkedHashMap<>();
        if (d instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
        }
        return out;
    }

    private static boolean phpEmpty(Object v) {
        return v == null || (v instanceof String s && (s.isEmpty() || s.equals("0"))) || (v instanceof List<?> l && l.isEmpty())
                || (v instanceof Map<?, ?> m && m.isEmpty());
    }

    /**
     * 转移（旧 Type::move → Type::moveData）：{@code ids}、{@code val} 都非空；目标分类按 {@code val} 取（数字按编号，否则按拼音），没有时
     * "获取目标分类信息失败"；每个原分类把视频（type_mid=1）或文章（其余）中 {@code type_id|type_id_1} 等于它的行改成
     * {@code type_id = val, type_id_1 = 目标的上级}。提示取语言包 {@code model/type/move_ok}（旧语言包里这一条的文字是"转移失败"，照旧）。
     */
    public AdminResult move(PhpForm param) {
        return AdminWrites.guard(() -> {
            List<Long> ids = AdminIds.ids(param);
            if (ids == null || param.empty("val") || param.isArray("val")) {
                return AdminResult.error("param_err");
            }
            byte[] val = param.scalar("val");
            AdminRow target = cacheInfo(new String(val, StandardCharsets.UTF_8));
            if (target == null) {
                return AdminResult.error("model/type/to_info_err");
            }
            String to = AdminRowValues.value(AdminTable.VOD.column("type_id"), val);
            String toPid = target.s("type_pid");
            for (AdminRow t : rows(reader.selectTypesByIds(ids))) {
                long from = t.n("type_id");
                ContentAdminWrites.run(() -> t.n("type_mid") == 1 ? writer.mapper().moveVodType(to, toPid, from)
                        : writer.mapper().moveArtType(to, toPid, from));
            }
            return AdminResult.ok("model/type/move_ok");
        });
    }

    /** 旧 {@code getCacheInfo($id)}：{@code is_numeric} 时按编号（数组键），否则按 {@code type_en} 全等查找第一条。 */
    private AdminRow cacheInfo(String id) {
        Map<Long, AdminRow> list = cacheList();
        if (PhpLoose.isNumeric(id)) {
            Long key = PhpForm.Arr.intKey(id);
            return key == null ? null : list.get(key);
        }
        for (AdminRow r : list.values()) {
            if (r.s("type_en").equals(id)) {
                return r;
            }
        }
        return null;
    }

    /**
     * 旧 {@code Type::saveData}（common/model/Type.php:233-285）：名称必填 → {@code type_extend} 非空时 {@code json_encode} →
     * 拼音为空时由名称生成 → 列出的字段 {@code mac_filter_xss} → {@code type_id} 非空时按主键整行覆盖，否则新增。
     */
    private AdminResult saveData(PhpForm data) {
        if (!data.require("type_name")) {
            return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_name"));
        }
        if (!data.empty("type_extend")) {
            data.put("type_extend", PhpJson.encode(phpValue(data, "type_extend")));
        }
        if (data.empty("type_en")) {
            data.put("type_en", LegacyPinyin.get(AdminText.utf8(data, "type_name")));
        }
        for (String f : XSS_FIELDS) {
            if (data.has(f) && !data.isNull(f)) {
                AdminText.requireScalar(data, f);
                data.put(f, PhpText.filterXss(data.scalar(f)));
            }
        }
        if (!data.empty("type_id")) {
            long id = AdminIds.primaryKey(data.scalar("type_id"));
            writer.update(AdminTable.TYPE.table(), AdminRowValues.of(AdminTable.TYPE, data, Set.of("type_id")), id);
        } else {
            writer.insert(AdminTable.TYPE.table(), AdminRowValues.of(AdminTable.TYPE, data, Set.of("type_id")));
        }
        return AdminResult.ok("save_ok");
    }

    /** 参数作为 PHP 值交给 json_encode：数组的键是 0..n-1 时为列表，否则为对象；标量为字符串。 */
    private static Object phpValue(PhpForm data, String name) {
        if (!data.isArray(name)) {
            return AdminText.utf8(data, name);
        }
        LinkedHashMap<String, byte[]> entries = data.array(name).entries();
        boolean list = true;
        int i = 0;
        for (String k : entries.keySet()) {
            if (!k.equals(Integer.toString(i++))) {
                list = false;
                break;
            }
        }
        if (list) {
            List<Object> out = new ArrayList<>();
            entries.values().forEach(v -> out.add(AdminText.utf8(v)));
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        entries.forEach((k, v) -> out.put(k, AdminText.utf8(v)));
        return out;
    }

    private static List<AdminRow> rows(List<Map<String, String>> raw) {
        List<AdminRow> out = new ArrayList<>();
        for (Map<String, String> r : raw) {
            out.add(new AdminRow(r));
        }
        return out;
    }

    /** {@code mac_list_to_tree}：顶级（type_pid = 0）按列表顺序，子分类挂到存在的上级下（上级不存在的丢弃）。 */
    static List<AdminTypeNode> tree(List<AdminRow> rows, Map<Long, Long> count) {
        Map<Long, List<AdminRow>> children = new LinkedHashMap<>();
        Map<Long, AdminRow> byId = new HashMap<>();
        for (AdminRow r : rows) {
            byId.put(r.n("type_id"), r);
        }
        List<AdminRow> top = new ArrayList<>();
        for (AdminRow r : rows) {
            long pid = r.n("type_pid");
            if (pid == 0) {
                top.add(r);
            } else if (byId.containsKey(pid)) {
                children.computeIfAbsent(pid, k -> new ArrayList<>()).add(r);
            }
        }
        List<AdminTypeNode> out = new ArrayList<>();
        for (AdminRow t : top) {
            List<AdminTypeNode> ch = new ArrayList<>();
            for (AdminRow c : children.getOrDefault(t.n("type_id"), List.of())) {
                ch.add(new AdminTypeNode(c, count.getOrDefault(c.n("type_id"), 0L), List.of()));
            }
            out.add(new AdminTypeNode(t, count.getOrDefault(t.n("type_id"), 0L), ch));
        }
        return out;
    }
}
