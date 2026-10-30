package com.maccms.backend.admin.application;

import com.maccms.backend.admin.application.view.Reply;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.VarExportWriter;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站群（旧 admin/controller/Domain.php，{@code extra/domain.php}）与定时任务配置（旧 admin/controller/Timming.php，{@code extra/timming.php}）
 * 的读写（24 文档第 6 节）。定时任务的执行与 {@code runtime} 回写在 B16。
 */
public final class ListConfigService {

    static final String[] DOMAIN_FIELDS = {"site_url", "site_name", "site_keywords", "site_description", "template_dir", "html_dir",
        "ads_dir"};

    private final LegacyConfigService config;

    public ListConfigService(LegacyConfigService config) {
        this.config = config;
    }

    public PhpArray domains() {
        LegacyConfigService.Snapshot s = config.snapshotOrNull(LegacyConfigFile.DOMAIN);
        return s == null ? PhpArrays.empty() : s.value();
    }

    /** {@code config('domain')}：文件不存在时为 null（页面上的站点数为 0）。 */
    public PhpValue domainsOrNull() {
        LegacyConfigService.Snapshot s = config.snapshotOrNull(LegacyConfigFile.DOMAIN);
        return s == null ? null : s.value();
    }

    public PhpArray timmings() {
        LegacyConfigService.Snapshot s = config.snapshotOrNull(LegacyConfigFile.TIMMING);
        return s == null ? PhpArrays.empty() : s.value();
    }

    /**
     * 站群保存（旧 {@code Domain::index} 的 POST，Domain.php:12-37，不校验表单令牌）：按 {@code domain[site_url][]} 的各项，以网址为键整份重写
     * （同一网址出现两次时后者覆盖前者、保留前者的位置）；其他字段按同一下标取（缺失为 NULL）。
     */
    public Reply saveDomains(PhpValue domainParam, LegacyLang lang) {
        PhpValue urls = PhpArrays.get(domainParam, "site_url");
        LinkedHashMap<Object, PhpValue> out = new LinkedHashMap<>();
        if (urls instanceof PhpArray ua) {
            for (Map.Entry<Object, PhpValue> e : ua.entries().entrySet()) {
                PhpArray item = PhpArrays.empty();
                item = PhpArrays.put(item, "site_url", e.getValue());
                for (int i = 1; i < DOMAIN_FIELDS.length; i++) {
                    PhpValue col = PhpArrays.get(domainParam, DOMAIN_FIELDS[i]);
                    PhpValue v = col instanceof PhpArray ca ? ca.entries().get(e.getKey()) : null;
                    item = PhpArrays.put(item, DOMAIN_FIELDS[i], v == null ? PhpValue.PhpNull.INSTANCE : v);
                }
                out.put(PhpArray.normalizeKey(PhpValues.text(e.getValue())), item);
            }
        }
        return write(LegacyConfigFile.DOMAIN, old -> new PhpArray(out), lang, "save_err", "save_ok");
    }

    /** 站群删除（旧 {@code Domain::del}）：{@code ids} 非空时 {@code unset($list[$ids])} 写回；总是"删除成功"。 */
    public Reply deleteDomain(String ids, LegacyLang lang) {
        if (ids == null || PhpValues.empty(new PhpValue.PhpString(ids))) {
            return Reply.success(lang.get("del_ok"));
        }
        return write(LegacyConfigFile.DOMAIN, old -> PhpArrays.remove(old, ids), lang, "del_err", "del_ok");
    }

    /** 站群导出（旧 {@code Domain::export}）：每行 {@code 网址$站名$关键词$描述$模板$html目录$广告目录\n}。 */
    public String exportDomains() {
        StringBuilder sb = new StringBuilder();
        for (PhpValue v : domains().entries().values()) {
            for (int i = 0; i < DOMAIN_FIELDS.length; i++) {
                if (i > 0) {
                    sb.append('$');
                }
                sb.append(PhpValues.text(PhpArrays.get(v, DOMAIN_FIELDS[i])));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 站群导入（旧 {@code Domain::import}，Domain.php:84-118）：文件内容按 \n 拆行，非空行按 {@code $} 拆成 7 个字段（缺的为 NULL），以网址为键
     * 整份重写；文件为空时什么都不写。旧版不检查扩展名与大小（{@code validate()} 只是登记规则，没有 {@code move()} 就不生效），成功时提示的是
     * 语言包的 {@code import_err}（"导入失败，请检查文件格式"，旧版的笔误，照旧）。
     */
    public Reply importDomains(byte[] content, LegacyLang lang) {
        String text = LegacyPhp.utf8(content);
        if (text == null) {
            return Reply.error(lang.get("param_err"));
        }
        if (PhpValues.empty(new PhpValue.PhpString(text))) {
            return Reply.success(lang.get("import_err"));
        }
        LinkedHashMap<Object, PhpValue> out = new LinkedHashMap<>();
        for (String line : LegacyPhp.explode("\n", text)) {
            if (PhpValues.empty(new PhpValue.PhpString(line))) {
                continue;
            }
            List<String> one = LegacyPhp.explode("$", line);
            PhpArray item = PhpArrays.empty();
            for (int i = 0; i < DOMAIN_FIELDS.length; i++) {
                item = PhpArrays.put(item, DOMAIN_FIELDS[i],
                        i < one.size() ? new PhpValue.PhpString(one.get(i)) : PhpValue.PhpNull.INSTANCE);
            }
            out.put(PhpArray.normalizeKey(one.get(0)), item);
        }
        return write(LegacyConfigFile.DOMAIN, old -> new PhpArray(out), lang, "write_err_config", "import_err");
    }

    /**
     * 定时任务保存（旧 {@code Timming::info} 的 POST，Timming.php:22-43）：{@code $param = input()}（含表单令牌，旧版没有去掉，照旧写进文件），
     * {@code weeks}、{@code hours} 用逗号连接（没有勾选时同 PHP 7.3 为 NULL），以 {@code name} 为键写进列表（新键追加在末尾）。
     * 执行文件为 {@code collect}（定时连资源站采集）时不保存，固定返回 {@link #TIMMING_COLLECT_REFUSED}（27 文档 D-B19-15）。
     */
    public Reply saveTimming(PhpArray param, LegacyLang lang) {
        if (TIMMING_COLLECT.equals(PhpValues.text(param.get("file")))) {
            return Reply.error(TIMMING_COLLECT_REFUSED);
        }
        PhpArray p = PhpArrays.put(param, "weeks", joinOrNull(param.get("weeks")));
        p = PhpArrays.put(p, "hours", joinOrNull(p.get("hours")));
        String name = PhpValues.text(p.get("name"));
        PhpArray entry = p;
        return write(LegacyConfigFile.TIMMING, old -> PhpArrays.put(old, name, entry), lang, "write_err_config", "save_ok");
    }

    /** 新系统不提供的定时任务执行文件：定时采集（旧 {@code api/controller/Timming.php} 按 {@code file=collect} 连资源站采集，E01）。 */
    public static final String TIMMING_COLLECT = "collect";
    /** 保存执行文件为 {@link #TIMMING_COLLECT} 的定时任务时的固定提示（2026-10-28 用户决定删除，D-B19-15）。 */
    public static final String TIMMING_COLLECT_REFUSED = "定时采集（collect）未在新系统中提供，请改选其他执行文件";

    private static PhpValue joinOrNull(PhpValue v) {
        if (v instanceof PhpArray a) {
            return new PhpValue.PhpString(LegacyPhp.join(",", a));
        }
        if (v == null) {
            return PhpValue.PhpNull.INSTANCE;
        }
        throw new SystemConfigService.Refused(SystemConfigService.PARAM_ERR);
    }

    /** 定时任务删除（旧 {@code Timming::del}）。 */
    public Reply deleteTimming(String ids, LegacyLang lang) {
        return write(LegacyConfigFile.TIMMING, old -> ids == null ? old : PhpArrays.remove(old, ids), lang, "del_err", "del_ok");
    }

    /** 定时任务改状态（旧 {@code Timming::field}）：只接受 {@code col=status}。 */
    public Reply fieldTimming(String ids, String col, PhpValue val, LegacyLang lang) {
        if (ids == null || PhpValues.empty(new PhpValue.PhpString(ids)) || !"status".equals(col)) {
            return Reply.error(lang.get("param_err"));
        }
        List<String> idList = LegacyPhp.explode(",", ids);
        PhpValue v = val == null ? PhpValue.PhpNull.INSTANCE : val;
        return write(LegacyConfigFile.TIMMING, old -> {
            LinkedHashMap<Object, PhpValue> m = new LinkedHashMap<>();
            for (Map.Entry<Object, PhpValue> e : old.entries().entrySet()) {
                boolean hit = idList.stream().anyMatch(id -> PhpLoose.equals(e.getKey(), id));
                m.put(e.getKey(), hit ? PhpArrays.put(PhpArrays.arr(e.getValue()), col, v) : e.getValue());
            }
            return new PhpArray(m);
        }, lang, "save_err", "save_ok");
    }

    private Reply write(LegacyConfigFile file, java.util.function.UnaryOperator<PhpArray> change, LegacyLang lang, String errKey,
                        String okKey) {
        try {
            config.update(file, current -> {
                try {
                    return VarExportWriter.macArr2File(change.apply(current == null ? PhpArrays.empty() : current));
                } catch (IllegalArgumentException e) {
                    throw new SystemConfigService.Refused(SystemConfigService.PARAM_ERR);
                }
            });
            return Reply.success(lang.get(okKey));
        } catch (SystemConfigService.Refused e) {
            return Reply.error(SystemConfigService.PARAM_ERR.equals(e.getMessage()) ? lang.get("param_err") : e.getMessage());
        } catch (LegacyConfigException e) {
            return Reply.error(e.reason() == LegacyConfigException.Reason.CONFLICT ? SystemConfigService.STALE : lang.get(errKey));
        }
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
