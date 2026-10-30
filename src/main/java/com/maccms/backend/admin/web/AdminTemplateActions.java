package com.maccms.backend.admin.web;

import com.maccms.backend.admin.application.AdminSettings;
import com.maccms.backend.admin.application.LegacyPhp;
import com.maccms.backend.admin.application.PhpArrays;
import com.maccms.backend.admin.web.AdminController.Ctx;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.system.ConfigPage;
import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import com.maccms.backend.sitetemplate.application.SiteTemplateService;
import com.maccms.backend.sitetemplate.application.TemplateWizard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * B15 模板管理与广告位（旧 admin/controller/Template.php；路线图第 5.6 节，24 文档第 7 节）。列目录、广告位、删除照旧版的路径约定
 * （{@code .@template@…}、{@code ./template/…}）；编辑页可编辑的扩展名为 {@code jte/html/js/xml}：jte 模板经 {@link SiteTemplateService}
 * 过滤、整体编译通过后才写入并生效，静态文件照旧版的安全过滤直接写入。
 */
public class AdminTemplateActions {

    private static final ZoneId PRC = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> EXTENSIONS = List.of("jte", "html", "js", "xml");
    /** 扩展名提示（旧 {@code admin/template/ext_safe_tip}、{@code name_tip} 说的是 htm/html/js/xml，D-B15-14 改为 jte/html/js/xml）。 */
    static final String EXT_TIP = "安全提示，后缀名只允许jte,html,js,xml";
    static final String NAME_TIP = "后缀名仅允许jte、html、js、xml；jte 模板放在当前模板目录下，保存时先整体编译，通过才生效。";

    private final AdminEndpoint endpoint;
    private final AdminSettings settings;
    private final AdminResponses responses;
    private final LegacySiteFiles files;
    private final LegacyConfigService config;
    private final SiteTemplateService templates;
    private final RandomGenerator random;
    private final Clock clock;

    public AdminTemplateActions(AdminEndpoint endpoint, AdminSettings settings, AdminResponses responses, LegacySiteFiles files,
                                LegacyConfigService config, SiteTemplateService templates, AdminRandom random, Clock clock) {
        this.endpoint = endpoint;
        this.settings = settings;
        this.responses = responses;
        this.files = files;
        this.config = config;
        this.templates = templates;
        this.random = random.generator();
        this.clock = clock;
    }

    ResponseEntity<String> handle(Ctx ctx, SessionState session) {
        if (!ctx.route().controller().equals("template")) {
            return null;
        }
        try {
            return switch (ctx.route().action()) {
                case "index" -> index(ctx);
                case "ads" -> ads(ctx);
                case "info" -> info(ctx, session);
                case "del" -> del(ctx);
                case "wizard" -> wizard(ctx);
                default -> null;
            };
        } catch (AdminForms.BadInput | LegacySiteFiles.FileException e) {
            return responses.error(ctx.ajax(), settings.lang().get("param_err"));
        }
    }

    // ------------------------------------------------------------------ 列表

    /** 旧 {@code Template::index}：{@code path} 形如 {@code .@template@default@html}；current/label/ads 三个快捷入口指向当前模板目录。 */
    private ResponseEntity<String> index(Ctx ctx) {
        String path = scalar(ctx, "path");
        path = path == null ? "" : path.replace("\\", "").replace("/", "");
        if (path.isEmpty()) {
            path = ".@template";
        }
        if (!path.startsWith(".@template")) {
            path = ".@template";
        }
        if (path.split("\\.@", -1).length > 2) {
            return responses.error(ctx.ajax(), settings.lang().get("illegal_request"));
        }
        String uppath = path.substring(0, path.lastIndexOf('@'));
        boolean ischild = !path.equals(".@template");
        PhpArray site = site();
        String current = ".@template@" + PhpValues.text(site.get("template_dir")) + "@" + PhpValues.text(site.get("html_dir"));
        String dir;
        if ("1".equals(scalar(ctx, "current"))) {
            path = current;
            ischild = false;
            dir = path.replace('@', '/');
        } else if ("1".equals(scalar(ctx, "label"))) {
            path = current;
            ischild = false;
            dir = path.replace('@', '/') + "/label";
        } else if ("1".equals(scalar(ctx, "ads"))) {
            path = current;
            ischild = false;
            dir = path.replace('@', '/') + "/ads";
        } else {
            dir = path.replace('@', '/');
        }
        PhpArray list = PhpArrays.empty();
        long numPath = 0;
        long numFile = 0;
        long sum = 0;
        long i = 0;
        String rel = relative(dir);
        LegacySiteFiles.Entry self = rel == null ? null : stat(rel);
        if (self != null && self.directory()) {
            for (LegacySiteFiles.Entry e : files.list(rel)) {
                if (e.name().startsWith(".")) {
                    continue;
                }
                String f = dir + "/" + e.name();
                PhpArray row = PhpArrays.empty();
                if (e.directory()) {
                    numPath++;
                    String tmp = f.replace("./template/", ".@template/").replace('/', '@');
                    row = PhpArrays.put(row, "isfile", "0");
                    row = PhpArrays.put(row, "name", tmp.replace(path + "@", ""));
                    row = PhpArrays.put(row, "path", tmp);
                    row = PhpArrays.put(row, "note", settings.lang().get("dir"));
                } else {
                    numFile++;
                    sum += e.size();
                    row = PhpArrays.put(row, "isfile", "1");
                    row = PhpArrays.put(row, "name", e.name());
                    row = PhpArrays.put(row, "path", dir);
                    row = PhpArrays.put(row, "fullname", f);
                    row = PhpArrays.put(row, "size", formatSize(e.size()));
                    row = PhpArrays.put(row, "note", settings.lang().get("file"));
                }
                row = time(row, e.modifiedEpochSeconds());
                list = PhpArrays.put(list, i++, row);
            }
        }
        Map<String, PhpValue> roots = new LinkedHashMap<>();
        roots.put("curpath", new PhpValue.PhpString(path));
        roots.put("uppath", new PhpValue.PhpString(uppath));
        roots.put("ischild", new PhpValue.PhpString(ischild ? "1" : "0"));
        roots.put("files", list);
        roots.put("num_path", new PhpValue.PhpString(Long.toString(numPath)));
        roots.put("num_file", new PhpValue.PhpString(Long.toString(numFile)));
        roots.put("sum_size", new PhpValue.PhpString(formatSize(sum)));
        roots.put("engine", new PhpValue.PhpString(engineText()));
        return page(ctx, "admin/template/title", roots, "", "index");
    }

    /** 旧 {@code Template::ads}：{@code template/<template_dir>/<ads_dir>/*.js}，目录不存在时先建。 */
    private ResponseEntity<String> ads(Ctx ctx) {
        PhpArray site = site();
        String adsdir = PhpValues.text(site.get("ads_dir"));
        if (adsdir.isEmpty() || adsdir.equals("0")) {
            adsdir = "ads";
        }
        String path = "./template/" + PhpValues.text(site.get("template_dir")) + "/" + adsdir;
        String rel = relative(path);
        if (rel != null && stat(rel) == null) {
            files.mkdirs(rel);
        }
        PhpArray list = PhpArrays.empty();
        long numFile = 0;
        long sum = 0;
        long i = 0;
        if (rel != null) {
            for (LegacySiteFiles.Entry e : files.list(rel)) {
                if (e.directory() || e.name().startsWith(".") || !e.name().endsWith(".js")) {
                    continue;
                }
                numFile++;
                sum += e.size();
                PhpArray row = PhpArrays.empty();
                row = PhpArrays.put(row, "isfile", "1");
                row = PhpArrays.put(row, "name", e.name());
                row = PhpArrays.put(row, "path", path);
                row = PhpArrays.put(row, "fullname", path + "/" + e.name());
                row = PhpArrays.put(row, "size", formatSize(e.size()));
                row = PhpArrays.put(row, "note", settings.lang().get("file"));
                row = time(row, e.modifiedEpochSeconds());
                list = PhpArrays.put(list, i++, row);
            }
        }
        Map<String, PhpValue> roots = new LinkedHashMap<>();
        roots.put("curpath", new PhpValue.PhpString(path));
        roots.put("files", list);
        roots.put("num_file", new PhpValue.PhpString(Long.toString(numFile)));
        roots.put("sum_size", new PhpValue.PhpString(formatSize(sum)));
        return page(ctx, "admin/template/ads/title", roots, "", "ads");
    }

    // ------------------------------------------------------------------ 编辑

    /** 旧 {@code Template::info}：GET 编辑页；POST 保存（{@code op=restore} 为新增的"恢复上一版"）。 */
    private ResponseEntity<String> info(Ctx ctx, SessionState session) {
        String fname = scalar(ctx, "fname");
        String fpath = scalar(ctx, "fpath");
        fname = fname == null ? "" : fname;
        if (fpath == null || fpath.isEmpty() || fpath.equals("0")) {
            return responses.error(ctx.ajax(), settings.lang().get("param_err") + "1");
        }
        fpath = fpath.replace('@', '/');
        String fullname = (fpath + "/" + fname).replace('\\', '/');
        if (!fullname.startsWith("./template") || fullname.split("\\./", -1).length > 2) {
            return responses.error(ctx.ajax(), settings.lang().get("param_err") + "2");
        }
        if (!fname.isEmpty() && !EXTENSIONS.contains(extension(fullname))) {
            return responses.error(ctx.ajax(), EXT_TIP);
        }
        String rel = relative(fullname);
        if (ctx.post_()) {
            AdminForms.TokenCheck t = AdminForms.check(session, ctx.params());
            if (t.error() != null) {
                return responses.error(ctx.ajax(), settings.lang().get(t.error()));
            }
            boolean jte = fullname.endsWith(".jte");
            if (fname.isEmpty()) {
                return retry(ctx, session, settings.lang().get("validate/require_name"), false);
            }
            if (rel == null) {
                return retry(ctx, session, settings.lang().get("param_err") + "2", jte);
            }
            String version = scalar(ctx, "fversion");
            version = version == null || version.isEmpty() ? null : version;
            SiteTemplateService.Result r;
            if ("restore".equals(scalar(ctx, "op"))) {
                r = templates.restore(rel, version);
            } else {
                byte[] content = ctx.params().scalar("fcontent");
                r = templates.save(rel, content == null ? new byte[0] : content, version);
            }
            if (r.ok()) {
                return responses.success(ctx.ajax(), settings.lang().get("save_ok"), AdminController.successUrl(ctx));
            }
            return retry(ctx, session, r.message() == null ? settings.lang().get("admin/template/php_safe_tip") : r.message(), jte);
        }
        String content = "";
        String version = "absent";
        boolean previous = false;
        if (rel != null && !fname.isEmpty()) {
            LegacySiteFiles.Entry e = stat(rel);
            if (e != null && !e.directory()) {
                content = new String(files.read(rel, 4 * 1024 * 1024), StandardCharsets.UTF_8);
                version = templates.version(rel);
                previous = templates.hasPrevious(rel);
            }
        }
        boolean jte = fullname.endsWith(".jte");
        Map<String, PhpValue> roots = new LinkedHashMap<>();
        roots.put("fname", new PhpValue.PhpString(fname));
        roots.put("fpath", new PhpValue.PhpString(fpath));
        roots.put("fcontent", new PhpValue.PhpString(content));
        roots.put("fversion", new PhpValue.PhpString(version));
        roots.put("previous", new PhpValue.PhpString(previous ? "1" : "0"));
        roots.put("jte", new PhpValue.PhpString(jte || fname.isEmpty() ? "1" : "0"));
        roots.put("filter", new PhpValue.PhpString(SiteTemplateService.LEGACY_FILTER));
        PhpArray rules = PhpArrays.empty();
        long i = 0;
        for (String r : templates.rules()) {
            rules = PhpArrays.put(rules, i++, r);
        }
        roots.put("rules", rules);
        String unavailable = templates.unavailable();
        roots.put("unavailable", new PhpValue.PhpString(unavailable == null ? "" : unavailable));
        roots.put("root", new PhpValue.PhpString("./" + templates.root()));
        roots.put("engine", new PhpValue.PhpString(engineText()));
        roots.put("name_tip", new PhpValue.PhpString(NAME_TIP));
        return page(ctx, "", roots, AdminForms.issue(session, random, null), "info");
    }

    /**
     * 保存没成功：提示原因。jte 模板同时给一个新的表单令牌（旧令牌已用掉），编辑页留在原处、改好后直接再保存；静态文件照旧版只给提示
     * （响应与旧版逐字节相同）。
     */
    private ResponseEntity<String> retry(Ctx ctx, SessionState session, String message, boolean renew) {
        if (!ctx.ajax() || !renew) {
            return responses.error(ctx.ajax(), message);
        }
        return responses.error(true, message, Map.of("token", AdminForms.issue(session, random, null)));
    }

    // ------------------------------------------------------------------ 删除、向导

    /** 旧 {@code Template::del}：路径不对的项跳过；jte 模板删除前先确认整套仍能编译，否则拒绝并说明原因。 */
    private ResponseEntity<String> del(Ctx ctx) {
        List<String> names = new ArrayList<>();
        List<byte[]> items = ctx.params().list("fname");
        if (items != null) {
            for (byte[] b : items) {
                names.add(utf8(b));
            }
        } else {
            String one = scalar(ctx, "fname");
            if (one != null && !one.isEmpty() && !one.equals("0")) {
                names.add(one);
            }
        }
        for (String a : names) {
            a = a.replace('\\', '/');
            if (!a.startsWith("./template") || a.split("\\./", -1).length > 2) {
                continue;
            }
            String rel = relative(a);
            if (rel == null) {
                continue;
            }
            LegacySiteFiles.Entry e = stat(rel);
            if (e == null || e.directory()) {
                continue;
            }
            SiteTemplateService.Result r = templates.delete(rel);
            if (!r.ok()) {
                return responses.error(ctx.ajax(), r.message());
            }
        }
        return responses.success(ctx.ajax(), settings.lang().get("del_ok"), AdminController.successUrl(ctx));
    }

    /** 标签向导：旧版生成 MacCMS 标签；新版列出各页面模板的参数与可直接粘贴的 jte 片段（D-B15-15）。 */
    private ResponseEntity<String> wizard(Ctx ctx) {
        PhpArray list = PhpArrays.empty();
        long i = 0;
        for (TemplateWizard.Entry e : TemplateWizard.entries(templates.builtin(), getClass().getClassLoader())) {
            PhpArray row = PhpArrays.empty();
            row = PhpArrays.put(row, "name", e.template());
            row = PhpArrays.put(row, "snippet", e.snippet());
            list = PhpArrays.put(list, i++, row);
        }
        return page(ctx, "admin/template/wizard/title", Map.of("list", list), "", "wizard");
    }

    // ------------------------------------------------------------------ 工具

    private String engineText() {
        long g = templates.generation();
        String notice = templates.notice();
        if (g > 0) {
            return "前台当前使用外部模板目录 ./" + templates.root() + "/ 的编译结果（本次启动后第 " + g + " 次编译）";
        }
        return "前台当前使用内置模板" + (notice == null ? (templates.enabled() ? "" : "（在线编辑未开启）") : "：" + notice);
    }

    private PhpArray site() {
        PhpValue v = config.read(LegacyConfigFile.MACCMS).get("site");
        return v instanceof PhpArray a ? a : PhpArrays.empty();
    }

    private PhpArray time(PhpArray row, long epoch) {
        String text = TIME.format(Instant.ofEpochSecond(epoch).atZone(PRC));
        String today = LocalDate.now(clock.withZone(PRC)).toString();
        row = PhpArrays.put(row, "time", Long.toString(epoch));
        row = PhpArrays.put(row, "time_text", text);
        return PhpArrays.put(row, "today", text.startsWith(today) ? "1" : "0");
    }

    private LegacySiteFiles.Entry stat(String rel) {
        try {
            return files.stat(rel);
        } catch (LegacySiteFiles.FileException e) {
            return null;
        }
    }

    /** {@code ./template/x/y} → {@code template/x/y}；不在 template/ 下或含 {@code ..} 等时为 null。 */
    static String relative(String dotPath) {
        if (!dotPath.startsWith("./template")) {
            return null;
        }
        String rel = dotPath.substring(2);
        while (rel.endsWith("/")) {
            rel = rel.substring(0, rel.length() - 1);
        }
        if (!rel.equals("template") && !rel.startsWith("template/")) {
            return null;
        }
        for (String seg : rel.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
                return null;
            }
        }
        return rel;
    }

    static String extension(String name) {
        String base = name.substring(name.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        return dot < 0 ? "" : base.substring(dot + 1);
    }

    /** 旧 {@code mac_format_size}：{@code round($s/pow(1024, floor(log($s,1024))), 2) . ' ' . 单位}。 */
    static String formatSize(long s) {
        if (s == 0) {
            return "0 kb";
        }
        String[] unit = {"b", "kb", "mb", "gb", "tb", "pb"};
        int i = (int) Math.floor(Math.log(s) / Math.log(1024));
        BigDecimal v = BigDecimal.valueOf(s / Math.pow(1024, i)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return v.toPlainString() + " " + unit[i];
    }

    private ResponseEntity<String> page(Ctx ctx, String titleKey, Map<String, PhpValue> roots, String token, String template) {
        AdminHead head = new AdminHead(titleKey.isEmpty() ? "" : settings.lang().get(titleKey), endpoint.sitePath(), endpoint.root(),
                settings.versionCode());
        ConfigPage view = new ConfigPage(head, settings.lang(), new LinkedHashMap<>(roots), ctx.url(), token);
        return responses.page(ctx.ajax(), HttpStatus.OK, tt -> switch (template) {
            case "index" -> tt.adminPagesTemplateIndex(view);
            case "ads" -> tt.adminPagesTemplateAds(view);
            case "info" -> tt.adminPagesTemplateInfo(view);
            default -> tt.adminPagesTemplateWizard(view);
        });
    }

    private static String scalar(Ctx ctx, String name) {
        byte[] b = ctx.params().scalar(name);
        return b == null ? null : utf8(b);
    }

    private static String utf8(byte[] b) {
        String s = LegacyPhp.utf8(b);
        if (s == null) {
            throw new AdminForms.BadInput();
        }
        return s;
    }
}
