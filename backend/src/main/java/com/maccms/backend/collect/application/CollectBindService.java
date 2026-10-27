package com.maccms.backend.collect.application;

import com.maccms.backend.content.application.AdminRejectedException;
import com.maccms.backend.content.application.PhpForm;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.content.application.view.AdminMessage;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.VarExportWriter;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * 采集分类绑定（B17，25 文档第 4 节）：旧 {@code admin/controller/Collect.php:184-228}（{@code clearbind}、{@code bind}）读写
 * {@code application/extra/bind.php}（{@code mac_arr2file}）。文件只经 {@link LegacyConfigService#update} 写回：在进程内的文件锁里
 * "读出当前内容 → 改一个键 → 原子写回"，两个管理员同时绑定不会互相覆盖；读写之间被进程外改过、或文件读不出来时报"保存失败"（D-B17-5）。
 */
public class CollectBindService {

    private final LegacyConfigService legacy;
    private final TypeNames types;

    /** @param legacy 旧配置；没有（未装配旧配置目录的测试上下文）时读到空表、写入一律失败 */
    public CollectBindService(LegacyConfigService legacy, TypeNames types) {
        this.legacy = legacy;
        this.types = types;
    }

    /** 当前的绑定表（文件不存在时为空表）；供 B18/B19 读取。 */
    public PhpArray bindings() {
        if (legacy == null) {
            return new PhpArray(Map.of());
        }
        LegacyConfigService.Snapshot s = legacy.snapshotOrNull(LegacyConfigFile.BIND);
        return s == null ? new PhpArray(Map.of()) : s.value();
    }

    /**
     * 绑定（旧 Collect::bind）：{@code col} 为空（PHP {@code empty()}）时"参数错误"；否则 {@code $config[$col] = intval($val)} 写回，
     * 成功时 {@code success(lang('save_ok'), null, data)}，{@code data} 为 {@code id}（{@code col} 原值）、{@code st}（{@code intval($val) > 0}
     * 时 1）、{@code local_type_id}（{@code $val} 原值）、{@code local_type_name}（{@code st} 为 1 时取 {@code $type_list[$val]['type_name']}：
     * {@code $val} 是规范的十进制整数且分类存在时为分类名，否则 null；{@code st} 为 0 时空串）。写不进去时"保存失败"。
     */
    public AdminResult bind(PhpForm param) {
        try {
            if (param.isArray("col") || param.isArray("val")) {
                // 数组的 col 当键：PHP 8 Illegal offset type（500）；数组的 val：intval(array) 为 0/1、按数组查分类名报错
                throw AdminRejectedException.paramError();
            }
            byte[] colRaw = param.scalar("col");
            if (PhpForm.phpEmpty(colRaw)) {
                return AdminResult.error("param_err");
            }
            String col = CollectAdminSupport.utf8(colRaw);
            byte[] valRaw = param.scalar("val");
            String val = valRaw == null ? null : CollectAdminSupport.utf8(valRaw);
            long local = val == null ? 0 : PhpIntval.of(val);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", col);
            data.put("st", local > 0 ? 1L : 0L);
            data.put("local_type_id", val);
            data.put("local_type_name", local > 0 ? typeName(val) : "");
            if (!write(current -> put(current, col, new PhpValue.PhpInt(local)))) {
                return AdminResult.error("save_err");
            }
            return new AdminResult(true, AdminMessage.lang("save_ok"), data);
        } catch (AdminRejectedException e) {
            return AdminResult.error(e.reason());
        }
    }

    /**
     * 清除绑定（旧 Collect::clearbind）：{@code cjflag} 非 {@code empty()} 时只保留键里不含它的项（{@code strpos === false}），否则清空。
     * 写回成功为 {@code true}（旧版 {@code json(['code'=>1,'msg'=>lang('clear_ok')])}），失败为 {@code false}。
     *
     * @throws AdminRejectedException {@code cjflag} 是数组或不是合法 UTF-8
     */
    public boolean clear(PhpForm param) {
        if (param.isArray("cjflag")) {
            throw AdminRejectedException.paramError();
        }
        byte[] raw = param.scalar("cjflag");
        String flag = PhpForm.phpEmpty(raw) ? null : CollectAdminSupport.utf8(raw);
        return write(current -> {
            Map<Object, PhpValue> kept = new LinkedHashMap<>();
            if (flag != null && current != null) {
                current.entries().forEach((k, v) -> {
                    if (!String.valueOf(k).contains(flag)) {
                        kept.put(k, v);
                    }
                });
            }
            return new PhpArray(kept);
        });
    }

    /** {@code $type_list[$val]['type_name']}：PHP 数组按规范化后的键查找，只有规范的十进制整数能找到分类。 */
    private Object typeName(String val) {
        Object k = PhpArray.normalizeKey(val);
        if (!(k instanceof Long id)) {
            return null;
        }
        return types.name(id).orElse(null);
    }

    private static PhpArray put(PhpArray current, String key, PhpValue v) {
        LinkedHashMap<Object, PhpValue> m = new LinkedHashMap<>(current == null ? Map.of() : current.entries());
        m.put(PhpArray.normalizeKey(key), v);
        return new PhpArray(m);
    }

    private boolean write(UnaryOperator<PhpArray> change) {
        if (legacy == null) {
            return false;
        }
        try {
            legacy.update(LegacyConfigFile.BIND, current -> VarExportWriter.macArr2File(change.apply(current)));
            return true;
        } catch (LegacyConfigException e) {
            return false;
        }
    }
}
