package com.maccms.backend.collect.application;

import static com.maccms.backend.collect.application.CollectAdminSupport.guard;

import com.maccms.backend.collect.application.view.CjNodeExport;
import com.maccms.backend.collect.application.view.CjNodeForm;
import com.maccms.backend.collect.infrastructure.CollectAdminMapper;
import com.maccms.backend.collect.infrastructure.CollectAdminWrites;
import com.maccms.backend.content.application.AdminIds;
import com.maccms.backend.content.application.AdminPaging;
import com.maccms.backend.content.application.AdminRejectedException;
import com.maccms.backend.content.application.AdminRowValues;
import com.maccms.backend.content.application.AdminTable;
import com.maccms.backend.content.application.PhpForm;
import com.maccms.backend.content.application.PhpJson;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 自定义规则节点（B17，25 文档第 5 节）：旧 {@code admin/controller/Cj.php} 的 {@code index}、{@code info}、{@code del}、{@code export}、
 * {@code import} → {@code common/model/Cj.php}（{@code listData}、{@code infoData}、{@code saveData}、{@code delData}）。规则的执行（采集网址、
 * 内容、发布）在 B20。
 */
public class CjNodeAdminService {

    /** 一批取出的内容网址数（删除节点时按网址删采集历史）。 */
    static final int URL_BATCH = 500;

    private static final List<String> DIY_FIELDS = List.of("name", "en_name", "rule", "html_rule");

    private final CollectAdminMapper reader;
    private final CollectAdminWrites writer;
    private final Clock clock;

    public CjNodeAdminService(CollectAdminMapper reader, CollectAdminWrites writer, Clock clock) {
        this.reader = reader;
        this.writer = writer;
        this.clock = clock;
    }

    /** 列表（旧 Cj::index）：无条件，{@code nodeid desc}；每页默认为后台每页条数（{@code app.pagesize}）。 */
    public AdminRowPage list(byte[] page, byte[] limit, int pageSize) {
        AdminPaging paging = AdminPaging.of(page, limit, pageSize);
        long total = reader.countCjNodes();
        List<AdminRow> rows = new ArrayList<>();
        if (!paging.beyond()) {
            for (Map<String, String> r : reader.listCjNodes(paging.offset(), paging.limit())) {
                rows.add(new AdminRow(r));
            }
        }
        return new AdminRowPage(rows, total, paging.page(), paging.limit(), null);
    }

    /**
     * 表单（旧 Cj::info 的 GET）：{@code where nodeid = input('id')}；{@code customize_config} 非 {@code empty()} 时
     * {@code json_decode(…, true)} 后逐项列出（不是数组时没有项）。没有这一行时为空表单。
     */
    public CjNodeForm form(byte[] id) {
        Optional<AdminRow> row = AdminIds.lookupId(id).map(reader::selectCjNode).map(AdminRow::new);
        AdminRow info = row.orElse(new AdminRow(Map.of()));
        List<Map<String, String>> diy = new ArrayList<>();
        String raw = info.s("customize_config");
        Object decoded = PhpForm.phpEmpty(raw.getBytes(StandardCharsets.UTF_8)) ? null : PhpJson.decode(raw);
        Iterable<?> items = decoded instanceof Map<?, ?> m ? m.values() : decoded instanceof List<?> l ? l : List.of();
        for (Object item : items) {
            Map<String, String> one = new LinkedHashMap<>();
            for (String f : DIY_FIELDS) {
                one.put(f, item instanceof Map<?, ?> im && im.get(f) instanceof String s ? s : "");
            }
            diy.add(one);
        }
        return new CjNodeForm(info, diy);
    }

    /**
     * 保存（旧 Cj::info 的 POST，旧版不校验表单令牌）：{@code $data = $param['data']}；{@code $data['urlpage'] = (string)$param['urlpage'.
     * $data['sourcetype']]}；{@code data[customize_config]} 非 {@code empty()} 时按 {@code name[]} 逐项取 {@code name/en_name/rule/html_rule}
     * （名称为 {@code empty()} 的项跳过）后 {@code json_encode(…, JSON_FORCE_OBJECT)}（一项都没有时是 {@code null}）。再交给 {@code saveData}：
     * {@code lastdate = time()}；{@code nodeid} 非空时按主键整行覆盖，否则新增（{@code urlpage}、{@code page_base} 缺省为空串）。
     *
     * @param data  参数 {@code data}（{@code AdminParams.nested} 的结果：标量为字节串、数组为按出现顺序的 {@link Map}）；缺失为 null
     * @param param 全部参数（取 {@code urlpage1/2/3} 等）
     */
    public AdminResult save(Object data, PhpForm param) {
        return guard(() -> {
            if (data != null && !(data instanceof Map<?, ?>)) {
                // 标量的 data：旧版对字符串按下标赋值（PHP 8 报错 500）
                throw AdminRejectedException.paramError();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> d = data == null ? Map.of() : (Map<String, Object>) data;
            PhpForm row = PhpForm.empty();
            for (Map.Entry<String, Object> e : d.entrySet()) {
                if (e.getValue() instanceof byte[] b) {
                    row.put(e.getKey(), b);
                } else {
                    row.putArray(e.getKey(), PhpForm.Arr.of(List.of()));
                }
            }
            Object st = d.get("sourcetype");
            if (st instanceof Map<?, ?>) {
                throw AdminRejectedException.paramError();
            }
            String urlpageName = "urlpage" + (st == null ? "" : CollectAdminSupport.latin1((byte[]) st));
            if (param.isArray(urlpageName)) {
                // (string)array 是 "Array"（PHP 8 告警）
                throw AdminRejectedException.paramError();
            }
            byte[] urlpage = param.scalar(urlpageName);
            row.put("urlpage", urlpage == null ? new byte[0] : urlpage);
            Object diy = d.get("customize_config");
            if (!(diy == null || (diy instanceof byte[] b && PhpForm.phpEmpty(b)))) {
                row.put("customize_config", customizeConfig(diy));
            }
            return saveData(row);
        });
    }

    /** {@code customize_config} 的 JSON（{@code JSON_FORCE_OBJECT}）；取值不合规时"参数错误"。 */
    private static String customizeConfig(Object diy) {
        if (!(diy instanceof Map<?, ?> c)) {
            // 标量：旧版按字符串下标取 name（PHP 8 报错 500，7.3 告警后写成 null），新版"参数错误"
            throw AdminRejectedException.paramError();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (c.get("name") instanceof Map<?, ?> names) {
            for (Map.Entry<?, ?> e : names.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Map<?, ?>) {
                    throw AdminRejectedException.paramError();
                }
                if (PhpForm.phpEmpty((byte[]) v)) {
                    continue;
                }
                Map<String, Object> one = new LinkedHashMap<>();
                for (String f : DIY_FIELDS) {
                    one.put(f, item(c.get(f), e.getKey()));
                }
                out.put(Integer.toString(out.size()), one);
            }
        } else if (c.get("name") != null && !(c.get("name") instanceof byte[])) {
            throw AdminRejectedException.paramError();
        }
        // 一项都没有时 $data['customize_config'] 未定义，json_encode(null) 为 "null"
        return out.isEmpty() ? "null" : PhpJson.encode(out);
    }

    /** {@code $customize_config[f][$k]}：没有为 null；{@code f} 不是数组（按字符串下标取字符）或项是数组时"参数错误"。 */
    private static String item(Object field, Object k) {
        if (field == null) {
            return null;
        }
        if (!(field instanceof Map<?, ?> m)) {
            throw AdminRejectedException.paramError();
        }
        Object v = m.get(k);
        if (v == null) {
            return null;
        }
        if (!(v instanceof byte[] b)) {
            throw AdminRejectedException.paramError();
        }
        return CollectAdminSupport.utf8(b);
    }

    /** 旧 {@code Cj::saveData}（common/model/Cj.php:25-44）。 */
    private AdminResult saveData(PhpForm data) {
        if (data.isArray("nodeid")) {
            throw AdminRejectedException.paramError();
        }
        data.put("lastdate", Long.toString(clock.instant().getEpochSecond()));
        if (!data.empty("nodeid")) {
            long id = AdminIds.primaryKey(data.scalar("nodeid"));
            writer.updateCjNode(AdminRowValues.of(AdminTable.CJ_NODE, data, Set.of()), id);
        } else {
            // isset()：缺失或为 null 时写空串
            if (!data.has("urlpage") || data.isNull("urlpage")) {
                data.put("urlpage", "");
            }
            if (!data.has("page_base") || data.isNull("page_base")) {
                data.put("page_base", "");
            }
            writer.insertCjNode(AdminRowValues.of(AdminTable.CJ_NODE, data, Set.of("nodeid")));
        }
        return AdminResult.ok("save_ok");
    }

    /**
     * 删除（旧 Cj::del → {@code Cj::delData}）：{@code ids} 为空时"参数错误"；否则依次删节点、按节点下各内容网址的 {@code md5} 删采集历史、
     * 删节点下的内容（旧版的顺序；表是 MyISAM 没有事务，中途失败时已完成的步骤不回退，与旧版相同）。
     */
    public AdminResult delete(PhpForm param) {
        return guard(() -> {
            List<Long> ids = AdminIds.ids(param);
            if (ids == null) {
                return AdminResult.error("param_err");
            }
            writer.deleteCjNodes(ids);
            long after = 0;
            while (true) {
                List<Map<String, String>> batch = reader.listCjContentUrls(ids, after, URL_BATCH);
                if (batch.isEmpty()) {
                    break;
                }
                List<String> md5s = new ArrayList<>();
                for (Map<String, String> r : batch) {
                    md5s.add(CollectBindings.flag(r.get("url").getBytes(StandardCharsets.UTF_8)));
                    after = Long.parseLong(r.get("id"));
                }
                writer.deleteCjHistories(md5s);
                if (batch.size() < URL_BATCH) {
                    break;
                }
            }
            writer.deleteCjContentsOfNodes(ids);
            return AdminResult.ok("del_ok");
        });
    }

    /**
     * 导出（旧 Cj::export）：{@code where nodeid = $param['id']}，没有时"获取失败"；否则 {@code base64_encode(json_encode($node))}（整数列是
     * JSON 数字，与旧版经 PDO 取回的类型相同），文件名 {@code mac_cj_名称.txt}（IE 时名称先 {@code urlencode}）。
     */
    public Optional<CjNodeExport> export(byte[] id, boolean msie) {
        Optional<Map<String, String>> row = AdminIds.lookupId(id).map(reader::selectCjNode);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        // 列按 install.sql 的顺序（旧版 SELECT * 的顺序），整数列是 JSON 数字
        Map<String, Object> typed = new LinkedHashMap<>();
        for (AdminTable.Column c : AdminTable.CJ_NODE.columns().values()) {
            String v = row.get().get(c.name());
            typed.put(c.name(), c.numeric() && v != null ? (Object) Long.parseLong(v) : v);
        }
        String body = java.util.Base64.getEncoder().encodeToString(PhpJson.encode(typed).getBytes(StandardCharsets.UTF_8));
        String name = row.get().get("name");
        return Optional.of(new CjNodeExport(body, "mac_cj_" + (msie ? urlencode(name) : name) + ".txt"));
    }

    /**
     * 导入（旧 Cj::import）：{@code json_decode(base64_decode(文件内容), true)}（非严格 base64）；解出的不是非空数组时
     * {@code success(lang('import_err'))}；否则去掉 {@code nodeid} 后交给 {@code saveData} 新增，{@code success(保存结果的提示)}（旧版保存失败时
     * 也是 {@code success}）。值是数组或 null 的列旧版写库时报错（500），新版"参数错误"（{@code error}）。
     */
    public AdminResult importNode(byte[] content) {
        return guard(() -> {
            byte[] decoded = base64Decode(content);
            String json;
            try {
                json = CollectAdminSupport.utf8(decoded);
            } catch (AdminRejectedException e) {
                return AdminResult.ok("import_err");
            }
            Object v = PhpJson.decode(json);
            Map<Object, Object> m = new LinkedHashMap<>();
            if (v instanceof Map<?, ?> map) {
                m.putAll(map);
            } else if (v instanceof List<?> list) {
                // JSON 数组在 PHP 里是整数键的数组（键 0、1…都不是表的列）
                for (int i = 0; i < list.size(); i++) {
                    m.put(Integer.toString(i), list.get(i));
                }
            }
            if (m.isEmpty()) {
                return AdminResult.ok("import_err");
            }
            PhpForm data = PhpForm.empty();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (k.equals("nodeid")) {
                    continue;
                }
                Object x = e.getValue();
                if (x == null) {
                    data.putNull(k);
                } else if (x instanceof String s) {
                    data.put(k, s);
                } else {
                    data.putArray(k, PhpForm.Arr.of(List.of()));
                }
            }
            return saveData(data);
        });
    }

    /** PHP {@code base64_decode($s)}（非严格）：跳过字母表以外的字符，按 6 位一组拼接，凑满 8 位就输出一个字节。 */
    static byte[] base64Decode(byte[] in) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int bits = 0;
        int n = 0;
        for (byte b : in) {
            int c = b & 0xff;
            int v;
            if (c >= 'A' && c <= 'Z') {
                v = c - 'A';
            } else if (c >= 'a' && c <= 'z') {
                v = c - 'a' + 26;
            } else if (c >= '0' && c <= '9') {
                v = c - '0' + 52;
            } else if (c == '+') {
                v = 62;
            } else if (c == '/') {
                v = 63;
            } else {
                continue;
            }
            bits = (bits << 6) | v;
            n += 6;
            if (n >= 8) {
                n -= 8;
                out.write((bits >> n) & 0xff);
            }
        }
        return out.toByteArray();
    }

    /** PHP {@code urlencode}（空格为 {@code +}，{@code -_.} 与字母数字不编码）。 */
    static String urlencode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '-' || c == '_' || c == '.') {
                sb.append((char) c);
            } else if (c == ' ') {
                sb.append('+');
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return sb.toString();
    }
}
