package com.maccms.backend.admin.application;

import com.maccms.backend.admin.application.view.Reply;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import com.maccms.backend.legacyconfig.application.VarExportWriter;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import com.maccms.backend.site.application.PhpJson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 播放器、服务器组、下载器（旧 admin/controller/Vodplayer.php、Vodserver.php、Voddowner.php；24 文档第 6 节）与 {@code playerconfig.js}
 * 的"缓存"段（旧 {@code Base::_cache_clear}，Base.php:59-103）。
 *
 * <p>旧版保存、删除后执行 {@code cache('cache_data','1')}，框架页每 30 秒请求 {@code index/checkcache}，看到 {@code haved} 就提示"清理缓存"；
 * 清理缓存（{@code index/clear}）才按当前的三个配置重写 {@code static/js/playerconfig.js} 的"缓存"段。新版照旧：{@link #cacheDirty()} 是这个标记
 * （进程内，重启即清），{@link #clearCache()} 重写文件。前台的 {@code /static/js/playerconfig.js} 由站点按当前配置生成（B05），与这个文件无关。
 */
public final class PlayerAdminService {

    /** 三种配置。 */
    public enum Kind {
        PLAYER("vodplayer", LegacyConfigFile.VODPLAYER, List.of("ps", "status")),
        SERVER("vodserver", LegacyConfigFile.VODSERVER, List.of("parse_status", "status")),
        DOWNER("voddowner", LegacyConfigFile.VODDOWNER, List.of("ps", "status"));

        private final String controller;
        private final LegacyConfigFile file;
        private final List<String> fieldCols;

        Kind(String controller, LegacyConfigFile file, List<String> fieldCols) {
            this.controller = controller;
            this.file = file;
            this.fieldCols = fieldCols;
        }

        public String controller() {
            return controller;
        }

        public static Kind of(String controller) {
            for (Kind k : values()) {
                if (k.controller.equals(controller)) {
                    return k;
                }
            }
            return null;
        }
    }

    private final LegacyConfigService config;
    private final LegacySiteFiles files;
    private final AtomicBoolean cacheDirty = new AtomicBoolean();

    public PlayerAdminService(LegacyConfigService config, LegacySiteFiles files) {
        this.config = config;
        this.files = files;
    }

    /** 当前的配置（文件不存在时为空数组，同旧版 {@code config('vodplayer')} 为 null 时的效果）。 */
    public PhpArray list(Kind kind) {
        LegacyConfigService.Snapshot s = config.snapshotOrNull(kind.file);
        return s == null ? PhpArrays.empty() : s.value();
    }

    /** 表单（旧 {@code info} 的 GET）：{@code $list[$id]}；播放器另读 {@code static/player/<id>.js} 作为 {@code code}。没有时为 null。 */
    public PhpValue info(Kind kind, String id) {
        PhpValue info = id == null ? null : list(kind).get(id);
        if (kind == Kind.PLAYER && info instanceof PhpArray a && !PhpValues.empty(a)) {
            info = PhpArrays.put(a, "code", new PhpValue.PhpString(code(id)));
        }
        return info;
    }

    /** {@code file_get_contents('./static/player/' . $id . '.js')}；读不到为空串（旧版为 false，页面上同样为空）。 */
    private String code(String id) {
        try {
            byte[] b = files.read("static/player/" + id + ".js", 4 * 1024 * 1024);
            return b == null ? "" : new String(b, StandardCharsets.UTF_8);
        } catch (LegacySiteFiles.FileException e) {
            return "";
        }
    }

    /**
     * 保存（旧 {@code info} 的 POST）：{@code $param}（{@code input()}，已去掉 {@code __token__}）去掉 {@code flag}；播放器另取出 {@code code}；
     * {@code from} 是数字时加 {@code _}，含 {@code . / \} 时"参数错误"；{@code $list[$from] = $param} 后按各项的 {@code sort} 降序重排
     * （{@code array_multisort($sort, SORT_DESC, SORT_FLAG_CASE, $list)}），写回配置；播放器再把 {@code code} 写成 {@code static/player/<from>.js}。
     */
    public Reply save(Kind kind, PhpArray param, LegacyLang lang) {
        PhpArray p = PhpArrays.remove(param, "flag");
        PhpValue code = null;
        if (kind == Kind.PLAYER) {
            code = p.get("code");
            p = PhpArrays.remove(p, "code");
        }
        String from = PhpValues.text(p.get("from"));
        if (PhpLoose.isNumeric(from)) {
            from = from + "_";
            p = PhpArrays.put(p, "from", from);
        }
        if (from.contains(".") || from.contains("/") || from.contains("\\")) {
            return Reply.error(lang.get("param_err"));
        }
        String key = from;
        PhpArray entry = p;
        Reply r = write(kind, list -> sortDesc(PhpArrays.put(list, key, entry)), lang,
                kind == Kind.PLAYER ? "write_err_config" : "save_err");
        if (!r.ok()) {
            return r;
        }
        if (kind == Kind.PLAYER) {
            try {
                files.replace("static/player/" + from + ".js", PhpValues.text(code).getBytes(StandardCharsets.UTF_8), null);
            } catch (LegacySiteFiles.FileException e) {
                return Reply.error(lang.get("wirte_err_codefile"));
            }
        }
        cacheDirty.set(true);
        return Reply.success(lang.get("save_ok"));
    }

    /** 删除（旧 {@code del}）：{@code unset($list[$param['ids']])} 后整份写回（没有这一项也照样写）。 */
    public Reply delete(Kind kind, String ids, LegacyLang lang) {
        Reply r = write(kind, list -> ids == null ? list : PhpArrays.remove(list, ids), lang, "del_err");
        if (!r.ok()) {
            return r;
        }
        cacheDirty.set(true);
        return Reply.success(lang.get("del_ok"));
    }

    /**
     * 改状态（旧 {@code field}）：{@code ids} 非空且 {@code col} 是该类允许的列时，逐项 {@code in_array($k, explode(',', $ids))}（宽松比较）的那些
     * 设成 {@code $val}（缺失为 NULL）；服务器组旧版不看 {@code ids}，全部设置（照旧）。否则"参数错误"。
     */
    public Reply field(Kind kind, String ids, String col, PhpValue val, LegacyLang lang) {
        if (PhpValues.empty(ids == null ? null : new PhpValue.PhpString(ids)) || col == null || !kind.fieldCols.contains(col)) {
            return Reply.error(lang.get("param_err"));
        }
        List<String> idList = LegacyPhp.explode(",", ids);
        PhpValue v = val == null ? PhpValue.PhpNull.INSTANCE : val;
        Reply r = write(kind, list -> {
            LinkedHashMap<Object, PhpValue> m = new LinkedHashMap<>();
            for (Map.Entry<Object, PhpValue> e : list.entries().entrySet()) {
                boolean hit = kind == Kind.SERVER || idList.stream().anyMatch(id -> PhpLoose.equals(keyValue(e.getKey()), id));
                PhpValue item = e.getValue();
                if (hit) {
                    item = PhpArrays.put(item instanceof PhpArray a ? a : PhpArrays.empty(), col, v);
                }
                m.put(e.getKey(), item);
            }
            return new PhpArray(m);
        }, lang, "save_err");
        return r.ok() ? Reply.success(lang.get("save_ok")) : r;
    }

    private static Object keyValue(Object key) {
        return key instanceof Long l ? l : key;
    }

    /** 导出（旧 {@code Vodplayer::export}）：{@code base64_encode(json_encode($info))}，{@code $info} 含播放器代码；文件名 {@code mac_<from>.txt}。 */
    public String[] export(String id, boolean msie) {
        PhpValue info = info(Kind.PLAYER, id);
        String from = PhpValues.text(PhpArrays.get(info, "from"));
        String body = java.util.Base64.getEncoder().encodeToString(
                PhpJson.encode(info == null ? PhpValue.PhpNull.INSTANCE : info).getBytes(StandardCharsets.UTF_8));
        return new String[] {body, "mac_" + (msie ? LegacyAdminUrl.urlencode(from) : from) + ".txt"};
    }

    /**
     * 导入（旧 {@code Vodplayer::import} 的 POST）：{@code json_decode(base64_decode(文件内容), true)}；解不出数据时什么都不做（照样"导入完成"）；
     * {@code status}、{@code from}、{@code sort} 有一项为 {@code empty} 时"格式错误"；{@code from} 含 {@code . / \} 时"参数错误"；否则取出
     * {@code code}，{@code $list[$from] = $data}（不重排）写回，再写播放器代码文件。
     */
    public Reply importPlayer(byte[] content, LegacyLang lang) {
        String decoded = LegacyPhp.utf8(phpBase64Decode(content));
        PhpValue data = decoded == null ? null : LegacyPhp.jsonDecode(decoded);
        if (!(data instanceof PhpArray d) || PhpValues.empty(d)) {
            return Reply.success(lang.get("import_ok"));
        }
        if (PhpValues.empty(d.get("status")) || PhpValues.empty(d.get("from")) || PhpValues.empty(d.get("sort"))) {
            return Reply.error(lang.get("format_err"));
        }
        String from = PhpValues.text(d.get("from"));
        if (from.contains(".") || from.contains("/") || from.contains("\\")) {
            return Reply.error(lang.get("param_err"));
        }
        PhpValue code = d.get("code");
        PhpArray entry = PhpArrays.remove(d, "code");
        Reply r = write(Kind.PLAYER, list -> PhpArrays.put(list, from, entry), lang, "write_err_config");
        if (!r.ok()) {
            return r;
        }
        try {
            files.replace("static/player/" + from + ".js", PhpValues.text(code).getBytes(StandardCharsets.UTF_8), null);
        } catch (LegacySiteFiles.FileException e) {
            return Reply.error(lang.get("wirte_err_codefile"));
        }
        return Reply.success(lang.get("import_ok"));
    }

    /** 旧 {@code cache('cache_data')} 是否为 1（{@code index/checkcache} 输出 {@code haved}）。 */
    public boolean cacheDirty() {
        return cacheDirty.get();
    }

    /**
     * 清理缓存（旧 {@code Base::_cache_clear} 的播放器部分）：按当前的三个配置重写 {@code static/js/playerconfig.js}（没有时读 {@code .bak}）的
     * "缓存"段；文件为空或读不到时不写。之后标记清除。
     */
    public void clearCache() {
        cacheDirty.set(false);
        String fc;
        try {
            byte[] b = files.read("static/js/playerconfig.js", 4 * 1024 * 1024);
            if (b == null) {
                b = files.read("static/js/playerconfig.js.bak", 4 * 1024 * 1024);
            }
            fc = b == null ? "" : new String(b, StandardCharsets.UTF_8);
        } catch (LegacySiteFiles.FileException e) {
            return;
        }
        if (fc.isEmpty() || fc.equals("0")) {
            return;
        }
        String content = "MacPlayerConfig.player_list=" + PhpJson.encode(cacheList(list(Kind.PLAYER), true))
                + ",MacPlayerConfig.downer_list=" + PhpJson.encode(cacheList(list(Kind.DOWNER), true))
                + ",MacPlayerConfig.server_list=" + PhpJson.encode(cacheList(list(Kind.SERVER), false)) + ";";
        String jsb = LegacyPhp.getBody(fc, "//缓存开始", "//缓存结束");
        String out = jsb.isEmpty() ? fc : fc.replace(jsb, "\r\n" + content + "\r\n");
        try {
            files.replace("static/js/playerconfig.js", out.getBytes(StandardCharsets.UTF_8), null);
        } catch (LegacySiteFiles.FileException ignored) {
            // 旧版 @fwrite：写不进去不报错
        }
    }

    private static PhpArray cacheList(PhpArray items, boolean withParse) {
        LinkedHashMap<Object, PhpValue> m = new LinkedHashMap<>();
        for (Map.Entry<Object, PhpValue> e : items.entries().entrySet()) {
            PhpArray x = PhpArrays.empty();
            x = PhpArrays.put(x, "show", PhpValues.text(PhpArrays.get(e.getValue(), "show")));
            x = PhpArrays.put(x, "des", PhpValues.text(PhpArrays.get(e.getValue(), "des")));
            if (withParse) {
                x = PhpArrays.put(x, "ps", PhpValues.text(PhpArrays.get(e.getValue(), "ps")));
                x = PhpArrays.put(x, "parse", PhpValues.text(PhpArrays.get(e.getValue(), "parse")));
            }
            m.put(e.getKey(), x);
        }
        return new PhpArray(m);
    }

    private Reply write(Kind kind, java.util.function.UnaryOperator<PhpArray> change, LegacyLang lang, String errKey) {
        try {
            config.update(kind.file, current -> {
                PhpArray next = change.apply(current == null ? PhpArrays.empty() : current);
                try {
                    return VarExportWriter.macArr2File(next);
                } catch (IllegalArgumentException e) {
                    throw new SystemConfigService.Refused(SystemConfigService.PARAM_ERR);
                }
            });
            return Reply.success("");
        } catch (SystemConfigService.Refused e) {
            return Reply.error(SystemConfigService.PARAM_ERR.equals(e.getMessage()) ? lang.get("param_err") : e.getMessage());
        } catch (LegacyConfigException e) {
            return Reply.error(e.reason() == LegacyConfigException.Reason.CONFLICT ? SystemConfigService.STALE : lang.get(errKey));
        }
    }

    /**
     * {@code array_multisort($sort, SORT_DESC, SORT_FLAG_CASE, $list)}：按各项的 {@code sort}（PHP 标准比较）降序；{@code sort} 相同时由第二个
     * 数组 {@code $list} 本身（默认 {@code SORT_ASC}）决定，即两项整体按数组比较升序（元素少的在前，{@link PhpCompare#compareArrays}）；
     * 再相同时保持原顺序。字符串键保留，整数键重新编号。
     */
    static PhpArray sortDesc(PhpArray list) {
        List<Map.Entry<Object, PhpValue>> entries = new ArrayList<>(list.entries().entrySet());
        entries.sort((a, b) -> {
            int c = PhpCompare.compare(PhpArrays.get(b.getValue(), "sort"), PhpArrays.get(a.getValue(), "sort"));
            return c != 0 ? c : PhpCompare.compare(a.getValue(), b.getValue());
        });
        LinkedHashMap<Object, PhpValue> m = new LinkedHashMap<>();
        long next = 0;
        for (Map.Entry<Object, PhpValue> e : entries) {
            m.put(e.getKey() instanceof Long ? (Object) next++ : e.getKey(), e.getValue());
        }
        return new PhpArray(m);
    }

    /** PHP {@code base64_decode($s)}（非严格）：跳过字母表以外的字符，按 4 个一组解码，末尾不足一组的按已有的位解码。 */
    static byte[] phpBase64Decode(byte[] in) {
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
}
