package com.maccms.backend.admin.application;

import com.maccms.backend.integration.application.AddonMarket;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import com.maccms.backend.legacyconfig.application.VarExportWriter;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 插件市场的安装与升级（E13，B29；35 文档）：旧 admin/controller/Addon.php 的 {@code install}（:143-172）、{@code upgrade}（:314-340）→
 * fastadmin-addons {@code Service::install}（Service.php:261-327）、{@code Service::upgrade}（:502-551）。
 *
 * <p>步骤与旧版相同（下载 → 解包 → 校验 → 导入 SQL → 启用/刷新），区别在于"先全部校验、再落盘、失败回滚"：
 * <ol>
 * <li>下载（{@link AddonMarket}，包只在内存里）；</li>
 * <li>解析与校验插件包（{@link AddonPackage}：路径穿越、zip 炸弹、坏包），按"解到已有目录之上"的结果做旧版的 {@code check}（主类文件、
 * {@code info.ini} 必需的键）与冲突检查（{@code noconflict}），并分析 {@code install.sql}（{@link AddonSql}：只要有一条不是数据语句就整包拒绝）——
 * 以上任何一步失败都不写任何文件；</li>
 * <li>升级时写旧版的备份 {@code runtime/addons/<名称>-backup-<YmdHis>.zip}；</li>
 * <li>记下将被改动的文件的原内容，再写插件目录、启用（只有登记了 Java 实现的插件；其他插件保持禁用，路线图第 5.7 节）、恢复升级前的配置值、
 * {@code Service::refresh}；中途失败时按记下的原内容恢复，再刷新一次钩子表。</li>
 * </ol>
 * 插件包里的数据语句（{@code INSERT/UPDATE/DELETE/REPLACE}）经过校验但<b>不执行</b>：写入方矩阵（路线图第 5.3 节）没有给插件写库登记任何表，
 * 可写数据源也只执行写 Mapper 里登记的静态语句（12 文档第 3 节），见 35 文档 D-B29-3。插件自己的 PHP {@code install/upgrade} 方法不执行。
 */
public final class AddonMarketService {

    /** 旧 {@code config('database.prefix')}。 */
    static final String TABLE_PREFIX = "mac_";
    /** 名称不合法（新增）。 */
    public static final String BAD_NAME = "插件名称不合法";
    /** 插件包的 SQL 有不能执行的语句（新增，%s 为关键字）。 */
    public static final String SQL_REFUSED = "插件包的 SQL 含有不能执行的语句（%s），新系统只接受数据语句，已拒绝安装";
    /** 写文件失败（旧版 {@code set_addon_info} 等的提示）。 */
    static final String WRITE_FAILED = "文件没有写入权限";

    private static final Logger log = LoggerFactory.getLogger(AddonMarketService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId PRC = ZoneId.of("Asia/Shanghai");
    private static final int MAX_FILE = 64 * 1024 * 1024;
    private static final List<String> INFO_KEYS = List.of("name", "title", "intro", "author", "version", "state");

    private final AddonService addons;
    private final LegacySiteFiles files;
    private final AddonMarket market;
    private final AddonPackage.Limits limits;
    private final Clock clock;

    public AddonMarketService(AddonService addons, LegacySiteFiles files, AddonMarket market, AddonPackage.Limits limits, Clock clock) {
        this.addons = addons;
        this.files = files;
        this.market = market;
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * 市场拒绝（旧 {@code AddonException}）：后台按 {@code $this->result($data, $code, $msg)} 输出。三项是旧版
     * {@code (array)json_decode($ret['msg'], true)} 里的原值，缺失时为 null。
     */
    public static final class Refused extends RuntimeException {
        private final transient PhpValue msg;
        private final transient PhpValue code;
        private final transient PhpValue data;

        Refused(PhpValue msg, PhpValue code, PhpValue data) {
            super("refused", null, false, false);
            this.msg = msg;
            this.code = code;
            this.data = data;
        }

        public PhpValue msg() {
            return msg;
        }

        public PhpValue code() {
            return code;
        }

        public PhpValue data() {
            return data;
        }
    }

    // ------------------------------------------------------------------ 安装

    /**
     * 旧 {@code Service::install($name, $force, $extend)}。成功返回；失败抛 {@link AddonService.Failed}（{@code code} 非 0 时为旧版的
     * {@code AddonException}，如冲突 -3）或 {@link Refused}。
     */
    public synchronized void install(String name, boolean force, Map<String, String> extend) {
        checkName(name);
        boolean existed = addons.exists(name);
        if (existed && !force) {
            throw new AddonService.Failed("Addon already exists");
        }
        AddonPackage pkg = fetch(name, extend);
        String dir = "addons/" + name;
        Map<String, byte[]> merged = existed ? readTree(dir) : new LinkedHashMap<>();
        merged.putAll(pkg.files());
        // Service::check
        if (!merged.containsKey(AddonService.ucfirst(name) + ".php")) {
            throw new AddonService.Failed("插件主启动程序不存在");
        }
        Map<String, String> ini = ini(merged.get("info.ini"));
        if (ini == null || !INFO_KEYS.stream().allMatch(ini::containsKey)) {
            throw new AddonService.Failed("配置文件不完整");
        }
        boolean java = AddonService.JAVA_ADDONS.contains(name);
        List<String> globals = globalPaths(merged);
        if (java) {
            checkGlobalTargets(globals);
        }
        if (!force) {
            List<String> conflicts = conflicts(merged, globals);
            if (!conflicts.isEmpty()) {
                throw new AddonService.Failed("发现冲突文件", -3,
                        PhpArrays.put(PhpArrays.empty(), "conflictlist", PhpArrays.list(conflicts)));
            }
        }
        AddonSql.Analysis sql = analyzeSql(merged);

        // 先记下原内容（读失败时什么都没改，直接失败，不回滚）
        Journal j = new Journal();
        try {
            j.snapshotTree(dir);
            j.snapshotTree("static/addons/" + name);
            j.remember("static/js/addons.js");
            for (String g : globals) {
                j.rememberGlobal(g);
            }
        } catch (RuntimeException e) {
            throw failure(e);
        }
        try {
            writePackage(dir, pkg);
            if (java) {
                // Service::install：复制资源与全局文件，state 为假时置 1
                copyAssets(name, merged, globals);
                if (!PhpLoose.bool(ini.get("state"))) {
                    addons.setState(name, 1);
                }
            } else if (PhpLoose.bool(ini.get("state"))) {
                // 没有 Java 实现的插件保持禁用（路线图第 5.7 节）
                addons.setState(name, 0);
            }
            logSql(name, sql);
            addons.refresh();
        } catch (RuntimeException e) {
            j.rollback();
            throw failure(e);
        }
    }

    // ------------------------------------------------------------------ 升级

    /** 旧 {@code Service::upgrade($name, $extend)}。 */
    public synchronized void upgrade(String name, Map<String, String> extend) {
        checkName(name);
        if (!addons.exists(name)) {
            throw new AddonService.Failed("Addon not exists");
        }
        PhpArray info = addons.info(name);
        if (info != null && PhpLoose.bool(PhpValues.text(info.get("state")))) {
            throw new AddonService.Failed("Please disable addon first");
        }
        // get_addon_config：插件主类存在时取 config.php 的 name => value
        PhpArray oldConfig = info == null ? PhpArrays.empty() : addons.configValues(name);
        AddonPackage pkg = fetch(name, extend);
        String dir = "addons/" + name;
        Map<String, byte[]> before = readTree(dir);
        Map<String, byte[]> merged = new LinkedHashMap<>(before);
        merged.putAll(pkg.files());
        AddonSql.Analysis sql = analyzeSql(merged);
        Map<String, String> ini = ini(merged.get("info.ini"));

        // Service::backup：旧版在下载之前备份，新版在包通过校验之后（失败时不留下备份文件）
        backup(name, before);
        Journal j = new Journal();
        try {
            j.snapshotTree(dir);
            j.remember("static/js/addons.js");
        } catch (RuntimeException e) {
            throw failure(e);
        }
        try {
            writePackage(dir, pkg);
            if (!PhpValues.empty(oldConfig)) {
                restoreConfig(name, oldConfig);
            }
            if (!AddonService.JAVA_ADDONS.contains(name) && ini != null && PhpLoose.bool(ini.get("state"))) {
                addons.setState(name, 0);
            }
            logSql(name, sql);
            addons.refresh();
        } catch (RuntimeException e) {
            j.rollback();
            throw failure(e);
        }
    }

    // ------------------------------------------------------------------ 步骤

    private static void checkName(String name) {
        if (!AddonService.validName(name)) {
            throw new AddonService.Failed(BAD_NAME);
        }
    }

    /** 下载并解析插件包（不写任何文件）。 */
    private AddonPackage fetch(String name, Map<String, String> extend) {
        byte[] zip;
        try {
            zip = market.download(name, extend);
        } catch (AddonMarket.MarketRefused r) {
            PhpValue json = LegacyPhp.jsonDecode(r.json());
            PhpArray a = json instanceof PhpArray arr ? arr : PhpArrays.empty();
            throw new Refused(a.get("msg"), a.get("code"), a.get("data"));
        } catch (AddonMarket.DownloadFailed f) {
            throw new AddonService.Failed(AddonMarket.DOWNLOAD_FAILED);
        }
        try {
            return AddonPackage.parse(zip, limits);
        } catch (AddonPackage.Rejected r) {
            throw new AddonService.Failed(r.getMessage());
        }
    }

    private static Map<String, String> ini(byte[] b) {
        String text = b == null ? null : LegacyPhp.utf8(b);
        return text == null ? null : LegacyPhp.parseIni(text);
    }

    private static AddonSql.Analysis analyzeSql(Map<String, byte[]> merged) {
        byte[] sql = merged.get("install.sql");
        if (sql == null) {
            return new AddonSql.Analysis(List.of(), null);
        }
        AddonSql.Analysis a = AddonSql.analyze(sql, TABLE_PREFIX);
        if (!a.ok()) {
            throw new AddonService.Failed(String.format(SQL_REFUSED, a.rejected()));
        }
        return a;
    }

    private static void logSql(String name, AddonSql.Analysis sql) {
        if (!sql.statements().isEmpty()) {
            log.warn("addon market: {} data statements in install.sql of addon {} were validated but not executed (D-B29-3)",
                    sql.statements().size(), name);
        }
    }

    /** 插件目录里 {@code application/}、{@code static/} 下的文件对应的全局路径（旧 {@code getGlobalFiles}）。 */
    private static List<String> globalPaths(Map<String, byte[]> merged) {
        List<String> out = new ArrayList<>();
        for (String p : merged.keySet()) {
            if (p.startsWith("application/") || p.startsWith("static/")) {
                out.add(p);
            }
        }
        return out;
    }

    /** 启用时要写的全局文件必须在 {@code LegacySiteFiles} 允许的位置（{@code application/extra/} 等拒绝）——先查，免得写到一半失败。 */
    private void checkGlobalTargets(List<String> globals) {
        for (String g : globals) {
            try {
                files.readAddonGlobal(g, 1);
            } catch (LegacySiteFiles.FileException e) {
                if (e.reason() != LegacySiteFiles.Reason.TOO_LARGE) {
                    throw new AddonService.Failed(AddonPackage.UNSAFE_PATH);
                }
            }
        }
    }

    /** 旧 {@code getGlobalFiles($name, true)}：全局已有、且内容不同的文件。 */
    private List<String> conflicts(Map<String, byte[]> merged, List<String> globals) {
        List<String> out = new ArrayList<>();
        for (String g : globals) {
            byte[] dest;
            try {
                dest = files.readAddonGlobal(g, MAX_FILE);
            } catch (LegacySiteFiles.FileException e) {
                dest = null;
            }
            if (dest != null && !java.util.Arrays.equals(dest, merged.get(g))) {
                out.add(g);
            }
        }
        return out;
    }

    private void writePackage(String dir, AddonPackage pkg) {
        for (Map.Entry<String, byte[]> e : pkg.files().entrySet()) {
            files.replace(dir + "/" + e.getKey(), e.getValue(), null);
        }
    }

    /** 旧 {@code copydirs(assets, static/addons/<名称>)} 与 {@code copydirs($addonDir . $dir, ROOT_PATH . $dir)}。 */
    private void copyAssets(String name, Map<String, byte[]> merged, List<String> globals) {
        files.mkdirs("static/addons/" + name);
        for (Map.Entry<String, byte[]> e : merged.entrySet()) {
            if (e.getKey().startsWith("assets/")) {
                files.replace("static/addons/" + name + "/" + e.getKey().substring("assets/".length()), e.getValue(), null);
            }
        }
        for (String g : globals) {
            files.replaceAddonGlobal(g, merged.get(g));
        }
    }

    /** 旧 {@code set_addon_config($name, $config)}：新 config.php 中同名的项换成升级前的值（非数组类型遇到数组时用逗号连接）。 */
    private void restoreConfig(String name, PhpArray oldConfig) {
        PhpArray full = addons.fullConfig(name);
        LinkedHashMap<Object, PhpValue> out = new LinkedHashMap<>();
        for (Map.Entry<Object, PhpValue> e : full.entries().entrySet()) {
            PhpValue item = e.getValue();
            if (item instanceof PhpArray ia) {
                PhpValue v = oldConfig.get(PhpValues.text(ia.get("name")));
                if (v != null && !(v instanceof PhpValue.PhpNull)) {
                    PhpValue value = !PhpLoose.equals(ia.get("type"), "array") && v instanceof PhpArray va
                            ? new PhpValue.PhpString(LegacyPhp.join(",", va)) : v;
                    item = PhpArrays.put(ia, "value", value);
                }
            }
            out.put(e.getKey(), item);
        }
        files.replace("addons/" + name + "/config.php", VarExportWriter.addonConfigFile(new PhpArray(out)), null);
    }

    /** 旧 {@code Service::backup}：插件目录打成 zip，写到 {@code runtime/addons/<名称>-backup-<YmdHis>.zip}（只新建）。 */
    private void backup(String name, Map<String, byte[]> tree) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buf)) {
            for (Map.Entry<String, byte[]> e : tree.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new AddonService.Failed(WRITE_FAILED);
        }
        String file = name + "-backup-" + STAMP.format(clock.instant().atZone(PRC)) + ".zip";
        try {
            files.writeAddonBackup(file, buf.toByteArray());
        } catch (LegacySiteFiles.FileException e) {
            throw new AddonService.Failed(WRITE_FAILED);
        }
    }

    private Map<String, byte[]> readTree(String dir) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (String rel : files.walkFiles(dir)) {
            byte[] b = files.read(dir + "/" + rel, MAX_FILE);
            if (b != null) {
                out.put(rel, b);
            }
        }
        return out;
    }

    private static RuntimeException failure(RuntimeException e) {
        if (e instanceof AddonService.Failed || e instanceof Refused) {
            return e;
        }
        if (e instanceof LegacySiteFiles.FileException || e instanceof LegacyConfigException) {
            return new AddonService.Failed(WRITE_FAILED);
        }
        return e;
    }

    // ------------------------------------------------------------------ 回滚

    /** 改动前的文件内容；{@link #rollback} 按原样恢复（原来没有的删除），再按恢复后的插件状态刷新钩子表与 addons.js。 */
    private final class Journal {
        private final Map<String, byte[]> trees = new LinkedHashMap<>();
        private final Map<String, Map<String, byte[]>> treeFiles = new LinkedHashMap<>();
        private final Map<String, byte[]> plain = new LinkedHashMap<>();
        private final Map<String, byte[]> globals = new LinkedHashMap<>();

        void snapshotTree(String dir) {
            LegacySiteFiles.Entry e = files.stat(dir);
            boolean existed = e != null && e.directory();
            // 先读完再登记：读到一半失败时不留下"要整体恢复"的记录
            Map<String, byte[]> content = existed ? readTree(dir) : Map.of();
            treeFiles.put(dir, content);
            trees.put(dir, existed ? new byte[0] : null);
        }

        void remember(String path) {
            plain.put(path, files.read(path, MAX_FILE));
        }

        void rememberGlobal(String path) {
            globals.put(path, files.readAddonGlobal(path, MAX_FILE));
        }

        void rollback() {
            try {
                for (Map.Entry<String, byte[]> g : globals.entrySet()) {
                    if (g.getValue() == null) {
                        files.deleteAddonGlobal(g.getKey());
                    } else {
                        files.replaceAddonGlobal(g.getKey(), g.getValue());
                    }
                }
                for (Map.Entry<String, byte[]> t : trees.entrySet()) {
                    files.deleteTree(t.getKey());
                    if (t.getValue() != null) {
                        files.mkdirs(t.getKey());
                        for (Map.Entry<String, byte[]> f : treeFiles.get(t.getKey()).entrySet()) {
                            files.replace(t.getKey() + "/" + f.getKey(), f.getValue(), null);
                        }
                    }
                }
                for (Map.Entry<String, byte[]> p : plain.entrySet()) {
                    if (p.getValue() == null) {
                        files.delete(p.getKey());
                    } else {
                        files.replace(p.getKey(), p.getValue(), null);
                    }
                }
            } catch (RuntimeException e) {
                // 回滚本身失败：只记类型，由管理员按 35 文档第 8 节用快照恢复
                log.error("addon market rollback failed: {}", e.getClass().getName());
                return;
            }
            try {
                addons.refresh();
            } catch (RuntimeException e) {
                // 文件已恢复；钩子表与 addons.js 没能按恢复后的状态重写（多半是同一个写入故障），保存插件配置或启用/禁用时会再刷新
                log.error("addon market rollback: files restored, refresh failed: {}", e.getClass().getName());
            }
        }
    }
}
