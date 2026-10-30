package com.maccms.backend.admin.application;

import com.maccms.backend.admin.application.view.Reply;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.PhpIntval;
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
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台系统配置各组的保存（旧 admin/controller/System.php 的 {@code config*} 与 {@code Urlsend::index}、{@code Index::quickmenu} 的 POST；
 * 24 文档第 5 节）。输入是旧 {@code input()} 合并后的参数（各页是否经 {@code htmlentities} 由调用方按旧版处理好），输出写回
 * {@code extra/maccms.php}（{@code array_merge($config_old, $config_new)}，只替换本页的几组）与本页附带的文件（{@code static/js/tj.js}、
 * {@code route.php}、{@code static/js/playerconfig.js}、{@code extra/quickmenu.php}）。
 *
 * <p>与旧版相同的写法逐条见各方法的注释；与旧版不同的只有（24 文档第 13 节）：写入失败时报"保存失败"（旧版 {@code mac_arr2file} 不返回结果，
 * 总是提示成功）；打开页面之后本页的几组被他人改过时拒绝保存（{@link #STALE}）；值里有 NUL 字节或不是合法 UTF-8 时"参数错误"（新版写不出也读不回）。
 */
public final class SystemConfigService {

    /** 表单打开之后本页对应的配置被他人改过（另一位管理员，或仍在运行的旧 PHP）。新增的提示，没有语言包键。 */
    public static final String STALE = "配置已被他人修改，请刷新页面后重新保存";

    /** 各页写回的组（{@code $config_new} 的键），也是表单打开时记下的版本所覆盖的范围。 */
    public static final Map<String, List<String>> GROUPS = Map.ofEntries(
            Map.entry("config", List.of("site", "app", "extra")),
            Map.entry("configurl", List.of("view", "path", "rewrite")),
            Map.entry("configuser", List.of("user")), Map.entry("configupload", List.of("upload")),
            Map.entry("configcomment", List.of("gbook", "comment")), Map.entry("configweixin", List.of("weixin")),
            Map.entry("configpay", List.of("pay")), Map.entry("configconnect", List.of("connect")),
            Map.entry("configemail", List.of("email")), Map.entry("configsms", List.of("sms")),
            Map.entry("configapi", List.of("api")), Map.entry("configinterface", List.of("interface")),
            Map.entry("configcollect", List.of("collect")), Map.entry("configplay", List.of("play")),
            Map.entry("configseo", List.of("seo")), Map.entry("urlsend", List.of("urlsend")));

    private static final String[] RULE_TYPES = {"vod", "art", "actor", "role", "website", "comment"};

    private final LegacyConfigService config;
    private final LegacySiteFiles files;
    private final Clock clock;

    public SystemConfigService(LegacyConfigService config, LegacySiteFiles files, Clock clock) {
        this.config = config;
        this.files = files;
        this.clock = clock;
    }

    /** 保存被拒绝：{@code message} 是给管理员看的提示，或（{@link #PARAM_ERR}）语言包的"参数错误"。 */
    public static final class Refused extends RuntimeException {
        public Refused(String message) {
            super(message, null, false, false);
        }
    }

    /** {@link Refused} 的信息为此值时按语言包的 {@code param_err} 输出。 */
    static final String PARAM_ERR = "\u0000param_err";

    // ------------------------------------------------------------------ 读取

    /** 当前的 {@code extra/maccms.php}。 */
    public PhpArray maccms() {
        return config.read(LegacyConfigFile.MACCMS);
    }

    /** 本页各组的版本（打开表单时记下，保存时比较）。 */
    public String groupVersion(String page) {
        return groupVersion(maccms(), GROUPS.get(page));
    }

    static String groupVersion(PhpArray maccms, List<String> groups) {
        StringBuilder sb = new StringBuilder();
        for (String g : groups) {
            PhpValue v = maccms.get(g);
            sb.append(g).append('=').append(v == null ? "<absent>" : VarExportWriter.export(v)).append('\n');
        }
        return LegacyPhp.md5(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** {@code glob('./template/*', GLOB_ONLYDIR)} 的目录名（按字节序）。 */
    public List<String> templates() {
        List<String> out = new ArrayList<>();
        for (LegacySiteFiles.Entry e : files.list("template")) {
            if (e.directory()) {
                out.add(e.name());
            }
        }
        return out;
    }

    /** {@code glob('./application/lang/*.php')} 去掉扩展名。 */
    public List<String> langs() {
        List<String> out = new ArrayList<>();
        for (LegacySiteFiles.Entry e : files.list("application/lang")) {
            if (!e.directory() && e.name().endsWith(".php")) {
                out.add(e.name().substring(0, e.name().length() - 4));
            }
        }
        return out;
    }

    /**
     * 旧 {@code mac_extends_list($flag)}（application/common.php:358-377）的 {@code ext_list}：{@code application/common/extend/<flag>/*.php}
     * 的类名 → 类的 {@code $name}。新版不执行 PHP，按源码里的 {@code public $name = '…';} 取名字（取不到的文件跳过，旧版此时类无法加载同样
     * 跳过）。旧版用 {@code GLOB_NOSORT}（顺序随文件系统），新版按字节序。
     */
    public PhpArray extendsList(String flag) {
        LinkedHashMap<Object, PhpValue> m = new LinkedHashMap<>();
        String dir = "application/common/extend/" + flag;
        for (LegacySiteFiles.Entry e : files.list(dir)) {
            if (e.directory() || !e.name().endsWith(".php")) {
                continue;
            }
            String cl = e.name().substring(0, e.name().length() - 4);
            byte[] src = files.readListedSource(dir + "/" + e.name(), 256 * 1024);
            String text = src == null ? null : LegacyPhp.utf8(src);
            if (text == null) {
                continue;
            }
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("public\\s+\\$name\\s*=\\s*'((?:[^'\\\\]|\\\\.)*)'\\s*;")
                    .matcher(text);
            if (mm.find()) {
                m.put(PhpArray.normalizeKey(cl), new PhpValue.PhpString(mm.group(1).replace("\\'", "'").replace("\\\\", "\\")));
            }
        }
        return new PhpArray(m);
    }

    /**
     * 播放器参数页（旧 {@code System::configplay} 的 GET，System.php:690-701）显示的是 {@code static/js/playerconfig.js}（没有时
     * {@code playerconfig.js.bak}）"参数"段里的值，不是 {@code maccms.php}：{@code trim(mac_get_body(...))} 去掉开头 16 个字符
     * （{@code MacPlayerConfig=}）与结尾的 {@code ;} 后 {@code json_decode}。文件不存在或解析失败时为 null。
     */
    public PhpValue playParams() {
        String fc = playerConfigText();
        String jsb = LegacyPhp.trim(LegacyPhp.getBody(fc, "//参数开始", "//参数结束"));
        if (jsb.length() < 17) {
            return null;
        }
        return LegacyPhp.jsonDecode(jsb.substring(16, jsb.length() - 1));
    }

    /** {@code static/js/playerconfig.js}（不存在时 {@code .bak}）的内容；都没有时为 null。 */
    String playerConfigText() {
        byte[] b = files.read("static/js/playerconfig.js", 4 * 1024 * 1024);
        if (b == null) {
            b = files.read("static/js/playerconfig.js.bak", 4 * 1024 * 1024);
        }
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 保存

    /**
     * 保存一页。
     *
     * @param input          旧 {@code input()} 的结果（已去掉 {@code __token__}）
     * @param expectedGroups 打开表单时本页各组的版本（{@link #groupVersion}）；为 null 时不检查
     */
    public Reply save(String page, PhpArray input, String expectedGroups, LegacyLang lang) {
        try {
            return switch (page) {
                case "config" -> saveConfig(input, expectedGroups, lang);
                case "configurl" -> saveUrl(input, expectedGroups, lang);
                case "configapi" -> saveGroups(page, input, expectedGroups, lang, n -> {
                    PhpArray api = PhpArrays.arr(n.get("api"));
                    for (String t : List.of("vod", "art", "actor")) {
                        api = PhpArrays.put(api, t, "auth", new PhpValue.PhpString(
                                LegacyPhp.replaceText2(PhpValues.text(PhpArrays.get(api.get(t), "auth")))));
                    }
                    return PhpArrays.put(n, "api", api);
                });
                case "configinterface" -> {
                    PhpValue itf = input.get("interface");
                    if (PhpLoose.equals(PhpArrays.get(itf, "status"), 1L)
                            && PhpValues.text(PhpArrays.get(itf, "pass")).getBytes(StandardCharsets.UTF_8).length < 16) {
                        yield Reply.error(lang.get("admin/system/configinterface/pass_check"));
                    }
                    yield saveGroups(page, input, expectedGroups, lang, n -> {
                        PhpArray i = PhpArrays.arr(n.get("interface"));
                        i = PhpArrays.put(i, "vodtype", LegacyPhp.replaceText2(PhpValues.text(i.get("vodtype"))));
                        i = PhpArrays.put(i, "arttype", LegacyPhp.replaceText2(PhpValues.text(i.get("arttype"))));
                        return PhpArrays.put(n, "interface", i);
                    });
                }
                case "configcollect" -> saveGroups(page, input, expectedGroups, lang, SystemConfigService::collectRules);
                case "configplay" -> savePlay(input, expectedGroups, lang);
                default -> GROUPS.containsKey(page) ? saveGroups(page, input, expectedGroups, lang, n -> n)
                        : Reply.error(lang.get("param_err"));
            };
        } catch (Refused e) {
            return Reply.error(PARAM_ERR.equals(e.getMessage()) ? lang.get("param_err") : e.getMessage());
        }
    }

    /**
     * 旧 {@code System::config} 的 POST（System.php:64-131）。
     */
    private Reply saveConfig(PhpArray input, String expected, LegacyLang lang) {
        PhpValue siteIn = input.get("site");
        if (siteIn != null && !(siteIn instanceof PhpArray) && !PhpValues.empty(siteIn)) {
            // 旧版对字符串做 $s['ads_dir'] = …（PHP 7/8 都是致命错误）
            return Reply.error(lang.get("param_err"));
        }
        PhpArray site = PhpArrays.arr(siteIn);
        // ads_dir / mob_ads_dir：ThinkPHP Config::load 把模板 info.ini 并入全局配置后返回全局配置，所以手机模板没有 adsdir 时沿用主模板的
        Map<String, String> global = new LinkedHashMap<>();
        String adsDir = "ads";
        String mobAdsDir = "ads";
        mergeIni(global, PhpValues.text(site.get("template_dir")));
        if (!PhpValues.empty(str(global.get("adsdir")))) {
            adsDir = global.get("adsdir");
        }
        mergeIni(global, PhpValues.text(site.get("mob_template_dir")));
        if (!PhpValues.empty(str(global.get("adsdir")))) {
            mobAdsDir = global.get("adsdir");
        }
        site = PhpArrays.put(site, "ads_dir", adsDir);
        site = PhpArrays.put(site, "mob_ads_dir", mobAdsDir);

        PhpValue appIn = input.get("app");
        if (appIn != null && !(appIn instanceof PhpArray) && !PhpValues.empty(appIn)) {
            return Reply.error(lang.get("param_err"));
        }
        PhpArray app = PhpArrays.arr(appIn);
        if (PhpValues.empty(app.get("cache_flag"))) {
            app = PhpArrays.put(app, "cache_flag",
                    LegacyPhp.md5(Long.toString(clock.instant().getEpochSecond()).getBytes(StandardCharsets.US_ASCII)).substring(0, 10));
        }
        app = PhpArrays.put(app, "search_vod_rule", joinRule(app.get("search_vod_rule")));
        app = PhpArrays.put(app, "search_art_rule", joinRule(app.get("search_art_rule")));
        PhpValue optimise = app.get("vod_search_optimise");
        app = PhpArrays.put(app, "vod_search_optimise",
                PhpValues.empty(optimise) ? "" : optimise instanceof PhpArray oa ? LegacyPhp.join("|", oa) : PhpValues.text(optimise));
        app = PhpArrays.put(app, "vod_search_optimise_cache_minutes",
                new PhpValue.PhpInt(PhpIntval.of(PhpValues.text(app.get("vod_search_optimise_cache_minutes")))));

        PhpArray extra = PhpArrays.empty();
        if (!PhpValues.empty(app.get("extra_var"))) {
            String ev = PhpValues.text(app.get("extra_var")).replace("\n", "").replace("\r", "#");
            for (String a : LegacyPhp.explode("#", ev)) {
                if (a.contains("$$$")) {
                    List<String> kv = LegacyPhp.explode("$$$", a);
                    extra = PhpArrays.put(extra, kv.get(0), kv.get(1));
                }
            }
        }
        // html_entity_decode：值是 htmlentities 的输出，这一步恰好还原（PhpHtmlEntities）
        site = PhpArrays.put(site, "site_tj", PhpHtmlEntities.decodeEncoded(PhpValues.text(site.get("site_tj"))));

        PhpArray newGroups = PhpArrays.put(PhpArrays.put(PhpArrays.put(PhpArrays.empty(), "site", site), "app", app), "extra", extra);
        PhpArray[] written = new PhpArray[1];
        writeMaccms("config", newGroups, expected, lang, written);

        String tj = PhpValues.text(PhpArrays.get(written[0].get("site"), "site_tj"));
        if (!tj.contains("document.w")) {
            tj = "document.write('" + tj.replace("'", "\\'") + "')";
        }
        // 旧版 @fwrite(fopen('./static/js/tj.js','wb'), $tj)，结果不检查；新版在 maccms.php 写好之后原子写入，失败只记日志级别的忽略
        try {
            files.replace("static/js/tj.js", tj.getBytes(StandardCharsets.UTF_8), null);
        } catch (LegacySiteFiles.FileException ignored) {
            // 同旧版：统计代码文件写不进去不影响保存结果
        }
        return Reply.success(lang.get("save_ok"));
    }

    private static PhpValue str(String s) {
        return s == null ? null : new PhpValue.PhpString(s);
    }

    /**
     * {@code join('|', $rule)}：数组按 {@code (string)} 连接；缺失（一个复选框都没勾）时同 PHP 7.3 的 {@code join('|', null)}（告警后为 NULL，
     * PHP 8 是 TypeError、整页 500，24 文档 D-B15-6）。
     */
    private static PhpValue joinRule(PhpValue v) {
        if (v instanceof PhpArray a) {
            return new PhpValue.PhpString(LegacyPhp.join("|", a));
        }
        if (v == null) {
            return PhpValue.PhpNull.INSTANCE;
        }
        throw new Refused(PARAM_ERR);
    }

    /** 把 {@code template/<dir>/info.ini} 的顶层键（转小写）并入 {@code global}（ThinkPHP {@code Config::load} 的 {@code array_change_key_case}）。 */
    private void mergeIni(Map<String, String> global, String templateDir) {
        String rel = "template/" + templateDir + "/info.ini";
        byte[] b;
        try {
            b = files.read(rel, 64 * 1024);
        } catch (LegacySiteFiles.FileException e) {
            return;
        }
        if (b == null) {
            return;
        }
        String text = LegacyPhp.utf8(b);
        if (text == null) {
            return;
        }
        for (Map.Entry<String, String> e : LegacyPhp.parseIni(text).entrySet()) {
            global.put(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getValue());
        }
    }

    /** 旧 {@code System::configurl} 的 POST（System.php:164-244）：先写 {@code route.php}，再写 {@code maccms.php}。 */
    private Reply saveUrl(PhpArray input, String expected, LegacyLang lang) {
        PhpArray current = maccms();
        if (expected != null && !expected.equals(groupVersion(current, GROUPS.get("configurl")))) {
            return Reply.error(STALE);
        }
        PhpValue rewrite = input.get("rewrite");
        PhpArray route = PhpArrays.put(PhpArrays.empty(), "__pattern__", routePattern());
        String text = PhpValues.text(PhpArrays.get(rewrite, "route")).replace("\n", "");
        for (String r : LegacyPhp.explode("\r", text)) {
            if (r.contains("=>")) {
                List<String> a = LegacyPhp.explode("=>", r);
                PhpArray rule = PhpArrays.put(PhpArrays.put(PhpArrays.put(PhpArrays.empty(), 0L,
                        new PhpValue.PhpString(LegacyPhp.trim(a.get(1)))), 1L, PhpArrays.empty()), 2L, PhpArrays.empty());
                route = PhpArrays.put(route, LegacyPhp.trim(a.get(0)), rule);
            }
        }
        try {
            byte[] content = VarExportWriter.macArr2File(route);
            config.update(LegacyConfigFile.ROUTE, old -> content);
        } catch (IllegalArgumentException e) {
            return Reply.error(lang.get("param_err"));
        } catch (LegacyConfigException e) {
            return Reply.error(e.reason() == LegacyConfigException.Reason.CONFLICT ? STALE : lang.get("write_err_route"));
        }
        PhpArray groups = PhpArrays.empty();
        groups = PhpArrays.put(groups, "view", nullToNull(input.get("view")));
        groups = PhpArrays.put(groups, "path", nullToNull(input.get("path")));
        groups = PhpArrays.put(groups, "rewrite", nullToNull(rewrite));
        try {
            writeMaccms("configurl", groups, expected, lang, new PhpArray[1]);
        } catch (Refused e) {
            return Reply.error(e.getMessage().equals(lang.get("save_err")) ? lang.get("write_err_config") : e.getMessage());
        }
        return Reply.success(lang.get("save_ok"));
    }

    /** {@code $config['x']}：不存在为 NULL（{@code $config_new['x'] = null} 照样写进文件）。 */
    private static PhpValue nullToNull(PhpValue v) {
        return v == null ? PhpValue.PhpNull.INSTANCE : v;
    }

    /** 旧 configurl 写死的 {@code __pattern__}（System.php:181-218）。 */
    static PhpArray routePattern() {
        String any = "[\\s\\S]*?";
        String greedy = "[\\s\\S]*";
        String digits = "\\d+";
        String[][] p = {{"id", any}, {"ids", any}, {"wd", greedy}, {"en", any}, {"state", any}, {"area", greedy}, {"year", any},
                {"lang", any}, {"letter", any}, {"actor", any}, {"director", any}, {"tag", any}, {"class", any}, {"order", any},
                {"by", any}, {"file", any}, {"name", any}, {"url", any}, {"type", any}, {"sex", any}, {"version", any},
                {"blood", any}, {"starsign", any}, {"page", digits}, {"ajax", digits}, {"tid", digits}, {"mid", digits},
                {"rid", digits}, {"pid", digits}, {"sid", digits}, {"nid", digits}, {"uid", digits}, {"level", digits},
                {"score", digits}, {"limit", digits}};
        PhpArray a = PhpArrays.empty();
        for (String[] kv : p) {
            a = PhpArrays.put(a, kv[0], kv[1]);
        }
        return a;
    }

    /** 旧 {@code System::configplay} 的 POST（System.php:657-688）：先写 {@code maccms.php}，再替换 {@code playerconfig.js} 的"参数"段。 */
    private Reply savePlay(PhpArray input, String expected, LegacyLang lang) {
        Reply r = saveGroups("configplay", input, expected, lang, n -> n);
        if (!r.ok()) {
            return r;
        }
        String fc = playerConfigText();
        String jsb = LegacyPhp.getBody(fc, "//参数开始", "//参数结束");
        String content = "MacPlayerConfig=" + PhpJson.encode(nullToNull(input.get("play"))) + ";";
        String out = fc == null ? "" : jsb.isEmpty() ? fc : fc.replace(jsb, "\r\n" + content + "\r\n");
        try {
            files.replace("static/js/playerconfig.js", out.getBytes(StandardCharsets.UTF_8), null);
        } catch (LegacySiteFiles.FileException e) {
            return Reply.error(lang.get("save_err"));
        }
        return Reply.success(lang.get("save_ok"));
    }

    /** 旧 {@code System::configcollect} 的规则字段（System.php:571-637）。 */
    private static PhpArray collectRules(PhpArray n) {
        PhpArray collect = PhpArrays.arr(n.get("collect"));
        for (String t : RULE_TYPES) {
            PhpValue in = PhpArrays.get(collect.get(t), "inrule");
            if (PhpValues.empty(in)) {
                in = PhpArrays.list(List.of("a"));
            }
            PhpValue up = PhpArrays.get(collect.get(t), "uprule");
            if (PhpValues.empty(up)) {
                up = PhpArrays.empty();
            }
            collect = PhpArrays.put(collect, t, "inrule", new PhpValue.PhpString("," + joinAny(in)));
            collect = PhpArrays.put(collect, t, "uprule", new PhpValue.PhpString("," + joinAny(up)));
        }
        String[][] words = {{"vod", "namewords"}, {"vod", "thesaurus"}, {"vod", "playerwords"}, {"vod", "areawords"},
                {"vod", "langwords"}, {"vod", "words"}, {"art", "thesaurus"}, {"art", "words"}, {"actor", "thesaurus"},
                {"actor", "words"}, {"role", "thesaurus"}, {"role", "words"}, {"website", "thesaurus"}, {"website", "words"},
                {"comment", "thesaurus"}, {"comment", "words"}};
        for (String[] w : words) {
            collect = PhpArrays.put(collect, w[0], w[1], new PhpValue.PhpString(
                    LegacyPhp.replaceText2(PhpValues.text(PhpArrays.get(collect.get(w[0]), w[1])))));
        }
        return PhpArrays.put(n, "collect", collect);
    }

    private static String joinAny(PhpValue v) {
        if (v instanceof PhpArray a) {
            return LegacyPhp.join(",", a);
        }
        throw new Refused(PARAM_ERR);
    }

    /** 只替换本页各组的页面：{@code $config_new[组] = $config[组]}（不存在为 NULL），再按页面的规则处理。 */
    private Reply saveGroups(String page, PhpArray input, String expected, LegacyLang lang,
                             java.util.function.UnaryOperator<PhpArray> transform) {
        PhpArray groups = PhpArrays.empty();
        for (String g : GROUPS.get(page)) {
            groups = PhpArrays.put(groups, g, nullToNull(input.get(g)));
        }
        groups = transform.apply(groups);
        writeMaccms(page, groups, expected, lang, new PhpArray[1]);
        return Reply.success(lang.get("save_ok"));
    }

    /** {@code mac_arr2file(maccms.php, array_merge(config('maccms'), $config_new))}，在文件锁里读改写。 */
    private void writeMaccms(String page, PhpArray newGroups, String expected, LegacyLang lang, PhpArray[] written) {
        try {
            config.update(LegacyConfigFile.MACCMS, current -> {
                PhpArray old = current == null ? PhpArrays.empty() : current;
                if (expected != null && !expected.equals(groupVersion(old, GROUPS.get(page)))) {
                    throw new Refused(STALE);
                }
                PhpArray merged = PhpArrays.merge(old, newGroups);
                written[0] = merged;
                try {
                    return VarExportWriter.macArr2File(merged);
                } catch (IllegalArgumentException e) {
                    throw new Refused(lang.get("param_err"));
                }
            });
        } catch (LegacyConfigException e) {
            throw new Refused(switch (e.reason()) {
                case CONFLICT -> STALE;
                case INVALID_VALUE -> lang.get("param_err");
                default -> lang.get("save_err");
            });
        }
    }

    // ------------------------------------------------------------------ 快捷菜单

    /**
     * 旧 {@code Index::quickmenu} 的 POST（admin/controller/Index.php:113-126）：{@code input('post.quickmenu')} 去掉 \n、按 \r 拆成列表写进
     * {@code extra/quickmenu.php}。
     */
    public Reply saveQuickmenu(String quickmenu, LegacyLang lang) {
        List<String> lines = LegacyPhp.explode("\r", (quickmenu == null ? "" : quickmenu).replace("\n", ""));
        try {
            byte[] content = VarExportWriter.macArr2File(PhpArrays.list(lines));
            config.update(LegacyConfigFile.QUICKMENU, old -> content);
        } catch (IllegalArgumentException e) {
            return Reply.error(lang.get("param_err"));
        } catch (LegacyConfigException e) {
            return Reply.error(e.reason() == LegacyConfigException.Reason.CONFLICT ? STALE : lang.get("save_err"));
        }
        return Reply.success(lang.get("save_ok"));
    }
}
