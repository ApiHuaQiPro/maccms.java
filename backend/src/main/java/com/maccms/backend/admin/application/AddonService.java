package com.maccms.backend.admin.application;

import com.maccms.backend.admin.application.view.Reply;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import com.maccms.backend.legacyconfig.application.VarExportParser;
import com.maccms.backend.legacyconfig.application.VarExportWriter;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地插件（旧 admin/controller/Addon.php → fastadmin-addons {@code Service}；路线图第 5.7 节，24 文档第 7 节）：列表、配置、启用/禁用、卸载，
 * 以及插件钩子的新版实现。文件效果与旧版相同：{@code addons/<名称>/info.ini}（{@code set_addon_info}）、{@code config.php}
 * （{@code set_addon_fullconfig}）、资源目录 {@code static/addons/<名称>/}、全局文件、{@code static/js/addons.js} 与
 * {@code application/extra/addons.php}（{@code Service::refresh}）。
 *
 * <p>插件里的 PHP 代码在新系统里不能运行：只有 {@link #JAVA_ADDONS} 里登记了 Java 实现的插件可以启用（仓库里的 {@code adminloginbg}，钩子
 * {@code admin_login_init} 的等价实现见 {@link #loginBackground}）；其他插件启用时提示 {@link #NO_JAVA}、不改任何文件。禁用、配置与卸载对所有插件
 * 照旧执行文件操作（插件自己的 {@code enable/disable/uninstall} PHP 方法不执行）。{@code addons.php} 的钩子表按旧版
 * {@code get_addon_autoload_config} 由插件主类的公开方法得出——新版按源码文本找方法声明（不执行 PHP）。
 */
public final class AddonService {

    /** 有 Java 实现的插件。 */
    public static final Set<String> JAVA_ADDONS = Set.of("adminloginbg");

    /** 没有 Java 实现的插件不能启用（新增的提示）。 */
    public static final String NO_JAVA = "该插件没有新系统实现，无法启用";

    /** fastadmin-addons {@code think\Addons} 自身的公开方法与 install/uninstall/enable/disable（不算钩子）。 */
    static final Set<String> BASE_METHODS = Set.of("__construct", "getInfo", "getConfig", "setConfig", "setInfo", "getFullConfig",
            "getName", "checkInfo", "fetch", "display", "show", "assign", "getError", "install", "uninstall", "enable", "disable");

    private static final Pattern METHOD = Pattern.compile(
            "(?m)^[ \\t]*((?:(?:public|protected|private|static|final|abstract)\\s+)*)function\\s+&?\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]+");

    private final LegacySiteFiles files;
    private final LegacyConfigService config;
    private final String sitePath;
    private final Clock clock;
    private final RandomGenerator random;

    /**
     * @param sitePath 站点的路径前缀（根目录部署为空串），插件的 {@code url}（旧 {@code addon_url}）为 {@code 前缀/addons/<名称>.html}
     */
    public AddonService(LegacySiteFiles files, LegacyConfigService config, String sitePath, Clock clock, RandomGenerator random) {
        this.files = files;
        this.config = config;
        this.sitePath = sitePath;
        this.clock = clock;
        this.random = random;
    }

    /** 失败（旧版抛出的异常信息）。 */
    public static final class Failed extends RuntimeException {
        private final int code;
        private final PhpValue data;

        Failed(String message) {
            this(message, 0, null);
        }

        Failed(String message, int code, PhpValue data) {
            super(message, null, false, false);
            this.code = code;
            this.data = data;
        }

        /** {@code AddonException} 的代码（冲突为 -3），普通异常为 0。 */
        public int code() {
            return code;
        }

        /** {@code AddonException} 的数据（冲突文件列表），普通异常为 null。 */
        public PhpValue data() {
            return data;
        }
    }

    // ------------------------------------------------------------------ 读取

    /** {@code addon_url($name)}。 */
    String url(String name) {
        return sitePath + "/addons/" + name + ".html";
    }

    /** 名称能否当作目录名（不含路径分隔与 {@code ..}）。 */
    static boolean safeName(String name) {
        return name != null && !name.isEmpty() && !name.contains("/") && !name.contains("\\") && !name.contains("..")
                && !name.contains("\0") && !name.equals(".");
    }

    private static String ucfirst(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** {@code addons/<名称>/} 是否是目录（{@code is_dir(ADDON_PATH . $name)}）。 */
    boolean exists(String name) {
        if (!safeName(name)) {
            return false;
        }
        LegacySiteFiles.Entry e = files.stat("addons/" + name);
        return e != null && e.directory();
    }

    /** 插件主类文件存在（旧版 {@code class_exists}；新版不加载 PHP，以文件存在为准）。 */
    private boolean hasClass(String name) {
        LegacySiteFiles.Entry e = files.stat("addons/" + name + "/" + ucfirst(name) + ".php");
        return e != null && !e.directory();
    }

    /** {@code info.ini} 的顶层键值；没有该文件或读不出时为 null。 */
    private LinkedHashMap<String, String> ini(String name) {
        byte[] b = files.read("addons/" + name + "/info.ini", 64 * 1024);
        String text = b == null ? null : LegacyPhp.utf8(b);
        return text == null ? null : LegacyPhp.parseIni(text);
    }

    /** 旧 {@code get_addon_info($name)}：info.ini 的值，{@code url} 换成 {@code addon_url}（原有该键时原位替换）。没有主类时为 null。 */
    PhpArray info(String name) {
        if (!safeName(name) || !hasClass(name)) {
            return null;
        }
        LinkedHashMap<String, String> ini = ini(name);
        if (ini == null) {
            return PhpArrays.empty();
        }
        PhpArray a = PhpArrays.empty();
        for (Map.Entry<String, String> e : ini.entrySet()) {
            a = PhpArrays.put(a, e.getKey(), e.getValue());
        }
        return PhpArrays.put(a, "url", url(name));
    }

    /** 旧 {@code get_addon_list()}：{@code addons/} 下按名称排序，有主类文件与带 {@code name} 的 info.ini 的目录。 */
    List<PhpArray> list() {
        List<PhpArray> out = new ArrayList<>();
        for (LegacySiteFiles.Entry e : files.list("addons")) {
            if (!e.directory() || !safeName(e.name()) || !hasClass(e.name())) {
                continue;
            }
            LinkedHashMap<String, String> ini = ini(e.name());
            if (ini == null || !ini.containsKey("name")) {
                continue;
            }
            PhpArray a = PhpArrays.empty();
            for (Map.Entry<String, String> x : ini.entrySet()) {
                a = PhpArrays.put(a, x.getKey(), x.getValue());
            }
            out.add(PhpArrays.put(a, "url", url(e.name())));
        }
        return out;
    }

    /** 插件目录名（list() 的顺序）。 */
    private List<String> names() {
        List<String> out = new ArrayList<>();
        for (LegacySiteFiles.Entry e : files.list("addons")) {
            if (e.directory() && safeName(e.name()) && hasClass(e.name())) {
                LinkedHashMap<String, String> ini = ini(e.name());
                if (ini != null && ini.containsKey("name")) {
                    out.add(e.name());
                }
            }
        }
        return out;
    }

    /** 旧 {@code get_addon_fullconfig($name)}：{@code config.php} 的数组；没有或读不出时为空数组。 */
    public PhpArray fullConfig(String name) {
        byte[] b = files.read("addons/" + name + "/config.php", VarExportParser.MAX_BYTES);
        if (b == null) {
            return PhpArrays.empty();
        }
        try {
            return VarExportParser.parse(b);
        } catch (LegacyConfigException e) {
            return PhpArrays.empty();
        }
    }

    /** 旧 {@code get_addon_config($name)}：配置项的 {@code name => value}。 */
    PhpArray configValues(String name) {
        PhpArray out = PhpArrays.empty();
        for (PhpValue item : fullConfig(name).entries().values()) {
            out = PhpArrays.put(out, PhpValues.text(PhpArrays.get(item, "name")), valueOrNull(PhpArrays.get(item, "value")));
        }
        return out;
    }

    private static PhpValue valueOrNull(PhpValue v) {
        return v == null ? PhpValue.PhpNull.INSTANCE : v;
    }

    /**
     * 旧 {@code Addon::downloaded}（Addon.php:81-136）：本地插件列表，按 {@code search}（插件名或简介包含，不分大小写）与 {@code filter} 的
     * {@code category_id} 过滤，补上市场字段的默认值，{@code limit} 非 0 时取 {@code offset} 起的 {@code limit} 项。
     */
    public PhpArray downloaded(long offset, long limit, String filter, String search) {
        String s = search == null ? "" : search.replaceAll("<[^>]*>?", "").replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
        PhpValue f = filter == null ? null : LegacyPhp.jsonDecode(filter);
        List<PhpValue> rows = new ArrayList<>();
        for (PhpArray v : list()) {
            String name = PhpValues.text(v.get("name"));
            String intro = PhpValues.text(v.get("intro"));
            if (!s.isEmpty() && !containsIgnoreCase(name, s) && !containsIgnoreCase(intro, s)) {
                continue;
            }
            PhpArray row = v;
            row = putIfAbsent(row, "category_id", new PhpValue.PhpInt(0));
            row = putIfAbsent(row, "flag", new PhpValue.PhpString(""));
            row = putIfAbsent(row, "banner", new PhpValue.PhpString(""));
            row = putIfAbsent(row, "image", new PhpValue.PhpString(""));
            row = putIfAbsent(row, "donateimage", new PhpValue.PhpString(""));
            row = putIfAbsent(row, "demourl", new PhpValue.PhpString(""));
            row = putIfAbsent(row, "price", new PhpValue.PhpString("0.00"));
            row = PhpArrays.put(row, "url", url(PhpValues.text(v.get("name"))));
            LegacySiteFiles.Entry dir = files.stat("addons/" + PhpValues.text(v.get("name")));
            row = PhpArrays.put(row, "createtime", new PhpValue.PhpInt(dir == null ? 0 : dir.modifiedEpochSeconds()));
            row = PhpArrays.put(row, "install", "1");
            PhpValue cat = PhpArrays.get(f, "category_id");
            if (f instanceof PhpArray fa && !PhpValues.empty(fa) && cat != null && PhpLoose.isNumeric(PhpValues.text(cat))
                    && !PhpLoose.equals(cat, row.get("category_id"))) {
                continue;
            }
            rows.add(row);
        }
        int total = rows.size();
        if (limit != 0) {
            int from = (int) Math.max(0, Math.min(offset < 0 ? Math.max(0, total + offset) : offset, total));
            int to = (int) (limit < 0 ? Math.max(from, total + limit) : Math.min(total, (long) from + limit));
            rows = rows.subList(from, Math.max(from, to));
        }
        LinkedHashMap<Object, PhpValue> listMap = new LinkedHashMap<>();
        long i = 0;
        for (PhpValue r : rows) {
            listMap.put(i++, r);
        }
        return PhpArrays.put(PhpArrays.put(PhpArrays.empty(), "total", new PhpValue.PhpInt(total)), "rows", new PhpArray(listMap));
    }

    private static PhpArray putIfAbsent(PhpArray a, String k, PhpValue v) {
        return a.get(k) == null ? PhpArrays.put(a, k, v) : a;
    }

    private static boolean containsIgnoreCase(String hay, String needle) {
        return hay.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ 配置

    /** 配置页的数据（旧 {@code Addon::config} 的 GET）：{@code [info, config]}。名称不合法或目录不存在时抛 {@link Failed}（信息为语言包键）。 */
    public PhpArray[] configPage(String name) {
        checkConfigTarget(name);
        PhpArray info = info(name);
        if (info == null || PhpValues.empty(info)) {
            throw new Failed("get_addon_info_err");
        }
        return new PhpArray[] {info, fullConfig(name)};
    }

    private void checkConfigTarget(String name) {
        if (name == null || PhpValues.empty(new PhpValue.PhpString(name))) {
            throw new Failed("param_err");
        }
        if (!exists(name)) {
            throw new Failed("get_dir_err");
        }
    }

    /**
     * 保存配置（旧 {@code Addon::config} 的 POST，Addon.php:44-67）：{@code row} 里有的项更新 {@code value}（数组类型：不是数组时按 JSON 解码；
     * 其他类型：数组用逗号连接），写回 {@code config.php}，再 {@code Service::refresh()}。
     */
    public Reply saveConfig(String name, PhpValue row, LegacyLang lang) {
        try {
            checkConfigTarget(name);
            PhpArray info = info(name);
            if (info == null || PhpValues.empty(info)) {
                throw new Failed("get_addon_info_err");
            }
            if (!(row instanceof PhpArray params) || PhpValues.empty(params)) {
                return Reply.error(lang.get("param_err"));
            }
            LinkedHashMap<Object, PhpValue> out = new LinkedHashMap<>();
            for (Map.Entry<Object, PhpValue> e : fullConfig(name).entries().entrySet()) {
                PhpValue item = e.getValue();
                String itemName = PhpValues.text(PhpArrays.get(item, "name"));
                PhpValue p = params.get(itemName);
                if (p != null && !(p instanceof PhpValue.PhpNull) && item instanceof PhpArray ia) {
                    PhpValue value;
                    if (PhpLoose.equals(ia.get("type"), "array")) {
                        if (p instanceof PhpArray) {
                            value = p;
                        } else {
                            PhpValue d = LegacyPhp.jsonDecode(PhpValues.text(p));
                            value = d instanceof PhpArray ? d : d == null || d instanceof PhpValue.PhpNull ? PhpArrays.empty()
                                    : PhpArrays.put(PhpArrays.empty(), 0L, d);
                        }
                    } else {
                        value = p instanceof PhpArray pa ? new PhpValue.PhpString(LegacyPhp.join(",", pa)) : p;
                    }
                    item = PhpArrays.put(ia, "value", value);
                }
                out.put(e.getKey(), item);
            }
            byte[] content;
            try {
                content = VarExportWriter.addonConfigFile(new PhpArray(out));
            } catch (IllegalArgumentException e) {
                return Reply.error(lang.get("param_err"));
            }
            write("addons/" + name + "/config.php", content);
            refresh();
            return Reply.success(lang.get("save_ok"));
        } catch (Failed f) {
            return Reply.error(lang.get(f.getMessage()));
        }
    }

    // ------------------------------------------------------------------ 启用、禁用、卸载

    /** 旧 {@code Service::enable}（只有登记了 Java 实现的插件）。 */
    public void enable(String name) {
        requireExists(name);
        if (!JAVA_ADDONS.contains(name)) {
            throw new Failed(NO_JAVA);
        }
        noconflict(name);
        String dest = "static/addons/" + name;
        files.mkdirs(dest);
        for (String rel : files.walkFiles("addons/" + name + "/assets")) {
            files.copyFile("addons/" + name + "/assets/" + rel, dest + "/" + rel);
        }
        for (String dir : List.of("application", "static")) {
            for (String rel : files.walkFiles("addons/" + name + "/" + dir)) {
                files.copyToAddonGlobal("addons/" + name + "/" + dir + "/" + rel, dir + "/" + rel);
            }
        }
        setState(name, 1);
        refresh();
    }

    /** 旧 {@code Service::disable}。 */
    public void disable(String name) {
        requireExists(name);
        noconflict(name);
        files.deleteTree("static/addons/" + name);
        Set<String> dirs = new LinkedHashSet<>();
        for (String path : globalFiles(name, false)) {
            files.deleteAddonGlobal(path);
            int slash = path.lastIndexOf('/');
            if (slash > 0) {
                dirs.add(path.substring(0, slash));
            }
        }
        for (String d : dirs) {
            removeEmptyFolder(d);
        }
        setState(name, 0);
        refresh();
    }

    /** 旧 {@code Service::uninstall($name, false)}：删资源目录与插件目录（{@code force=0} 时不删全局文件），再 refresh。 */
    public void uninstall(String name) {
        requireExists(name);
        noconflict(name);
        files.deleteTree("static/addons/" + name);
        files.deleteTree("addons/" + name);
        refresh();
    }

    private void requireExists(String name) {
        if (!exists(name)) {
            throw new Failed("Addon not exists");
        }
    }

    /** 旧 {@code remove_empty_folder($dir)}：空目录删掉后继续看上一级。 */
    private void removeEmptyFolder(String dir) {
        String d = dir;
        while (d != null && d.contains("/")) {
            if (!files.deleteEmptyAddonGlobalDirectory(d)) {
                return;
            }
            d = d.substring(0, d.lastIndexOf('/'));
        }
    }

    /**
     * 旧 {@code Service::getGlobalFiles($name, $onlyconflict)}：插件目录里 {@code application/}、{@code static/} 下的文件对应的全局路径；
     * {@code onlyconflict} 时只列出全局已有、且大小或内容不同的。
     */
    private List<String> globalFiles(String name, boolean onlyconflict) {
        List<String> out = new ArrayList<>();
        for (String dir : List.of("application", "static")) {
            for (String rel : files.walkFiles("addons/" + name + "/" + dir)) {
                String path = dir + "/" + rel;
                if (onlyconflict) {
                    byte[] dest = readGlobal(path);
                    if (dest != null) {
                        byte[] src = files.read("addons/" + name + "/" + path, 64 * 1024 * 1024);
                        if (src == null || !java.util.Arrays.equals(src, dest)) {
                            out.add(path);
                        }
                    }
                } else {
                    out.add(path);
                }
            }
        }
        return out;
    }

    private byte[] readGlobal(String path) {
        try {
            return files.read(path, 64 * 1024 * 1024);
        } catch (LegacySiteFiles.FileException e) {
            return null;
        }
    }

    /** 旧 {@code Service::noconflict}：有冲突文件时 {@code AddonException("发现冲突文件", -3, ['conflictlist' => …])}。 */
    private void noconflict(String name) {
        List<String> list = globalFiles(name, true);
        if (!list.isEmpty()) {
            throw new Failed("发现冲突文件", -3,
                    PhpArrays.put(PhpArrays.empty(), "conflictlist", PhpArrays.list(list)));
        }
    }

    /**
     * {@code $info = get_addon_info($name); $info['state'] = …; unset($info['url']); set_addon_info($name, $info);}：{@code setInfo} 把缓存里带
     * {@code url} 的信息与新值合并，所以写出去的 info.ini 仍有 {@code url}（值为 {@code addon_url}）；每行 {@code 键 = 值}，以 \n 结尾。
     */
    private void setState(String name, int state) {
        PhpArray info = info(name);
        if (info == null || info.get("name") == null || info.get("title") == null || info.get("version") == null) {
            throw new Failed("插件配置写入失败");
        }
        info = PhpArrays.put(info, "state", new PhpValue.PhpInt(state));
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<Object, PhpValue> e : info.entries().entrySet()) {
            if (!first) {
                sb.append('\n');
            }
            first = false;
            if (e.getValue() instanceof PhpArray section) {
                sb.append('[').append(e.getKey()).append(']');
                for (Map.Entry<Object, PhpValue> s : section.entries().entrySet()) {
                    sb.append('\n').append(s.getKey()).append(" = ").append(PhpValues.text(s.getValue()));
                }
            } else {
                sb.append(e.getKey()).append(" = ").append(PhpValues.text(e.getValue()));
            }
        }
        sb.append('\n');
        write("addons/" + name + "/info.ini", sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void write(String rel, byte[] content) {
        try {
            files.replace(rel, content, null);
        } catch (LegacySiteFiles.FileException e) {
            throw new Failed("文件没有写入权限");
        }
    }

    /**
     * 旧 {@code Service::refresh()}：启用状态的插件的 {@code bootstrap.js} 拼成 {@code static/js/addons.js}；按 {@code get_addon_autoload_config(true)}
     * 重算 {@code extra/addons.php} 的钩子与路由（{@code autoload} 为真时不写）。
     */
    void refresh() {
        List<String> names = names();
        List<String> bootstraps = new ArrayList<>();
        for (String n : names) {
            LinkedHashMap<String, String> ini = ini(n);
            if (ini != null && PhpLoose.bool(ini.get("state"))) {
                byte[] b = files.read("addons/" + n + "/bootstrap.js", 4 * 1024 * 1024);
                if (b != null) {
                    bootstraps.add(new String(b, StandardCharsets.UTF_8));
                }
            }
        }
        String js = "define([], function () {\n    " + String.join("\n", bootstraps) + "\n});";
        write("static/js/addons.js", js.getBytes(StandardCharsets.UTF_8));

        try {
            config.update(LegacyConfigFile.ADDONS, current -> {
                PhpArray cfg = current == null ? PhpArrays.empty() : current;
                cfg = PhpArrays.put(cfg, "hooks", PhpArrays.empty());
                List<String> priority = new ArrayList<>();
                PhpValue pr = cfg.get("priority");
                if (pr != null && PhpLoose.bool(pr)) {
                    if (pr instanceof PhpArray pa) {
                        pa.entries().values().forEach(v -> priority.add(PhpValues.text(v)));
                    } else {
                        priority.addAll(LegacyPhp.explode(",", PhpValues.text(pr)));
                    }
                }
                priority.addAll(names);
                Set<String> done = new LinkedHashSet<>();
                PhpArray hooks = PhpArrays.empty();
                PhpArray route = PhpArrays.empty();
                for (String n : priority) {
                    if (!names.contains(n) || !done.add(n)) {
                        continue;
                    }
                    LinkedHashMap<String, String> ini = ini(n);
                    if (ini == null || !PhpLoose.bool(ini.get("state"))) {
                        continue;
                    }
                    for (String hook : hooks(n)) {
                        PhpArray list = PhpArrays.arr(hooks.get(hook));
                        boolean present = list.entries().values().stream().anyMatch(v -> PhpValues.text(v).equals(n));
                        if (!present) {
                            list = PhpArrays.put(list, (long) nextIndex(list), new PhpValue.PhpString(n));
                        }
                        hooks = PhpArrays.put(hooks, hook, list);
                    }
                    PhpArray conf = configValues(PhpValues.text(new PhpValue.PhpString(ini.getOrDefault("name", n))));
                    if (!PhpValues.empty(conf)) {
                        PhpValue rw = conf.get("rewrite");
                        if (rw instanceof PhpArray rewrite) {
                            for (Map.Entry<Object, PhpValue> e : rewrite.entries().entrySet()) {
                                route = PhpArrays.put(route, PhpValues.text(e.getValue()),
                                        ini.getOrDefault("name", n) + "/" + e.getKey());
                            }
                        }
                    }
                }
                cfg = PhpArrays.put(cfg, "hooks", hooks);
                cfg = PhpArrays.put(cfg, "route", route);
                if (PhpLoose.bool(cfg.get("autoload"))) {
                    return null;
                }
                return VarExportWriter.addonsFile(cfg);
            });
        } catch (LegacyConfigException e) {
            throw new Failed("addons.php文件没有写入权限");
        }
    }

    private static long nextIndex(PhpArray a) {
        long max = -1;
        for (Object k : a.entries().keySet()) {
            if (k instanceof Long l) {
                max = Math.max(max, l);
            }
        }
        return max + 1;
    }

    /**
     * 插件主类的钩子：{@code get_class_methods} 减去基类方法，转成小写下划线（{@code Loader::parseName}）。新版按源码文本找公开的方法声明。
     */
    List<String> hooks(String name) {
        byte[] b = files.read("addons/" + name + "/" + ucfirst(name) + ".php", 4 * 1024 * 1024);
        String src = b == null ? null : LegacyPhp.utf8(b);
        List<String> out = new ArrayList<>();
        if (src == null) {
            return out;
        }
        Matcher m = METHOD.matcher(src);
        while (m.find()) {
            String mods = m.group(1);
            if (mods.contains("private") || mods.contains("protected")) {
                continue;
            }
            String method = m.group(2);
            if (BASE_METHODS.contains(method)) {
                continue;
            }
            String hook = method.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT);
            hook = hook.replaceAll("^_+|_+$", "");
            if (!out.contains(hook)) {
                out.add(hook);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 钩子

    /**
     * {@code admin_login_init} 钩子（{@code adminloginbg} 的 {@code adminLoginInit}，addons/adminloginbg/Adminloginbg.php:28-40）：
     * {@code extra/addons.php} 的钩子表里登记了它、且插件 {@code state} 为 1 时，按配置给出登录页背景：{@code random} 为 1～4000 的随机数、
     * {@code daily} 为 {@code date("Ymd") % 4000}，地址 {@code http://img.infinitynewtab.com/wallpaper/<n>.jpg}；{@code fixed} 为配置的
     * {@code image}。其余情况为 null（不设背景）。
     */
    public String loginBackground() {
        try {
            if (!files.isConfigured() || !config.isConfigured()) {
                return null;
            }
            LegacyConfigService.Snapshot s = config.snapshotOrNull(LegacyConfigFile.ADDONS);
            PhpValue hook = s == null ? null : PhpArrays.get(s.value().get("hooks"), "admin_login_init");
            boolean registered = hook instanceof PhpArray h ? h.entries().values().stream().anyMatch(v -> PhpValues.text(v).equals("adminloginbg"))
                    : hook != null && LegacyPhp.explode(",", PhpValues.text(hook)).contains("adminloginbg");
            if (!registered || !exists("adminloginbg")) {
                return null;
            }
            LinkedHashMap<String, String> ini = ini("adminloginbg");
            if (ini == null || !PhpLoose.equals(ini.get("state"), 1L)) {
                return null;
            }
            PhpArray conf = configValues("adminloginbg");
            String mode = PhpValues.text(conf.get("mode"));
            if (mode.equals("random") || mode.equals("daily")) {
                long index = mode.equals("random") ? 1 + random.nextInt(4000)
                        : Long.parseLong(LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))).toString().replace("-", "")) % 4000;
                return "http://img.infinitynewtab.com/wallpaper/" + index + ".jpg";
            }
            return PhpValues.text(conf.get("image"));
        } catch (LegacySiteFiles.FileException | LegacyConfigException e) {
            return null;
        }
    }

    static boolean validName(String n) {
        return n != null && NAME.matcher(n).matches();
    }
}
