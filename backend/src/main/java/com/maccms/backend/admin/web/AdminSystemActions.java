package com.maccms.backend.admin.web;

import com.maccms.backend.admin.application.AdminSettings;
import com.maccms.backend.admin.application.PhpArrays;
import com.maccms.backend.admin.application.SystemConfigService;
import com.maccms.backend.admin.application.view.Reply;
import com.maccms.backend.admin.web.AdminController.Ctx;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.system.ConfigPage;
import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * B15 后台系统配置的动作（旧 admin/controller/System.php 的 15 个配置页、{@code Urlsend::index} 与快捷菜单的保存；24 文档第 5 节）。
 * 由 {@link AdminController} 在登录与 {@code check_auth} 之后分派。发信测试 {@code system/test_email} 属 E07（B25），由 B12 的分派返回
 * "由 B25 批次提供"；缓存连接测试 {@code system/test_cache} 新版不连接任何缓存服务（新系统没有外部缓存），返回固定提示（D-B15-4）。
 */
public class AdminSystemActions {

    /** 旧版用 {@code input('', '', 'htmlentities')} 取参数的页面（configurl 与 urlsend 用不过滤的 {@code input()}）。 */
    private static final java.util.Set<String> HTMLENTITIES = java.util.Set.of("config", "configuser", "configupload",
            "configcomment", "configweixin", "configpay", "configconnect", "configemail", "configsms", "configapi",
            "configinterface", "configcollect", "configplay", "configseo");

    /** test_cache 的固定提示（新增的文字）。 */
    static final String TEST_CACHE_DISABLED = "新系统不使用外部缓存服务，未进行连接测试";

    private final AdminEndpoint endpoint;
    private final AdminSettings settings;
    private final AdminResponses responses;
    private final SystemConfigService system;
    private final RandomGenerator random;

    public AdminSystemActions(AdminEndpoint endpoint, AdminSettings settings, AdminResponses responses, SystemConfigService system,
                              AdminRandom random) {
        this.endpoint = endpoint;
        this.settings = settings;
        this.responses = responses;
        this.system = system;
        this.random = random.generator();
    }

    /** 处理本类的动作；不是本类的动作时返回 null。 */
    ResponseEntity<String> handle(Ctx ctx, SessionState session) {
        String code = ctx.route().code();
        if (code.equals("index/quickmenu") && ctx.post_()) {
            return quickmenu(ctx, session);
        }
        if (code.equals("urlsend/index")) {
            return ctx.post_() ? save(ctx, session, "urlsend") : page(ctx, session, "urlsend", "admin/urlsend/title");
        }
        if (code.equals("system/test_cache")) {
            return responses.error(ctx.ajax(), TEST_CACHE_DISABLED);
        }
        if (!code.startsWith("system/")) {
            return null;
        }
        String page = ctx.route().action();
        if (!SystemConfigService.GROUPS.containsKey(page)) {
            return null;
        }
        return ctx.post_() ? save(ctx, session, page) : page(ctx, session, page, "admin/system/" + page + "/title");
    }

    private ResponseEntity<String> save(Ctx ctx, SessionState session, String page) {
        boolean urlsend = page.equals("urlsend");
        PhpArray input;
        try {
            input = AdminForms.input(ctx.params(), HTMLENTITIES.contains(page));
        } catch (AdminForms.BadInput e) {
            return responses.error(ctx.ajax(), settings.lang().get("param_err"));
        }
        String expected = null;
        if (!urlsend) {
            // 旧 Urlsend::index 的保存不校验表单令牌（Urlsend.php:16-29）
            AdminForms.TokenCheck t = AdminForms.check(session, ctx.params());
            if (t.error() != null) {
                return responses.error(ctx.ajax(), settings.lang().get(t.error()));
            }
            expected = versionFor(page, t.version());
            input = PhpArrays.remove(input, "__token__");
        }
        Reply r = system.save(page, input, expected, settings.lang());
        return r.ok() ? responses.success(ctx.ajax(), r.message(), AdminController.successUrl(ctx))
                : responses.error(ctx.ajax(), r.message());
    }

    /** 签发时记下的是"页面名:版本"，只有同一页的版本才用来检查。 */
    private static String versionFor(String page, String stored) {
        if (stored == null || !stored.startsWith(page + ":")) {
            return null;
        }
        return stored.substring(page.length() + 1);
    }

    private ResponseEntity<String> page(Ctx ctx, SessionState session, String page, String titleKey) {
        PhpArray maccms;
        try {
            maccms = system.maccms();
        } catch (LegacyConfigException e) {
            return responses.notice(ctx.ajax(), HttpStatus.SERVICE_UNAVAILABLE, "跳转提示", "旧配置读取失败");
        }
        Map<String, PhpValue> roots = new LinkedHashMap<>();
        if (page.equals("urlsend")) {
            // 旧 Urlsend::index 把 $GLOBALS['config']['urlsend'] 赋给 $config
            roots.put("config", PhpArrays.arr(maccms.get("urlsend")));
            roots.put("extends", extendsOf("urlsend"));
            // 旧模板 {$GLOBALS['http_type'].$GLOBALS['config']['site']['site_url']}
            roots.put("site_root", new PhpValue.PhpString((endpoint.secure() ? "https://" : "http://")
                    + PhpValues.text(PhpArrays.get(maccms.get("site"), "site_url"))));
        } else {
            roots.put("config", page.equals("config") ? configDefaults(maccms) : maccms);
        }
        switch (page) {
            case "config" -> {
                roots.put("templates", PhpArrays.list(system.templates()));
                roots.put("langs", PhpArrays.list(system.langs()));
                roots.put("editors", extendsOf("editor"));
            }
            case "configemail", "configsms", "configupload", "configpay" ->
                    roots.put("extends", extendsOf(switch (page) {
                        case "configemail" -> "email";
                        case "configsms" -> "sms";
                        case "configupload" -> "upload";
                        default -> "pay";
                    }));
            case "configplay" -> {
                PhpValue play = system.playParams();
                roots.put("play", play == null ? PhpValue.PhpNull.INSTANCE : play);
            }
            default -> {
            }
        }
        if (page.equals("configpay")) {
            roots.put("http_type", new PhpValue.PhpString(endpoint.secure() ? "https://" : "http://"));
        }
        if (page.equals("configupload")) {
            String tmp = System.getProperty("java.io.tmpdir");
            roots.put("sys_temp_dir", new PhpValue.PhpString(tmp == null ? "" : tmp));
            roots.put("temp_writable", new PhpValue.PhpBool(tempWritable(tmp)));
        }
        String token = page.equals("urlsend") ? "" : AdminForms.issue(session, random,
                page + ":" + system.groupVersion(page));
        ConfigPage view = new ConfigPage(head(titleKey), settings.lang(), roots, ctx.url(), token);
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> switch (page) {
            case "config" -> t.adminPagesSystemConfig(view);
            case "configapi" -> t.adminPagesSystemConfigapi(view);
            case "configcollect" -> t.adminPagesSystemConfigcollect(view);
            case "configcomment" -> t.adminPagesSystemConfigcomment(view);
            case "configconnect" -> t.adminPagesSystemConfigconnect(view);
            case "configemail" -> t.adminPagesSystemConfigemail(view);
            case "configinterface" -> t.adminPagesSystemConfiginterface(view);
            case "configpay" -> t.adminPagesSystemConfigpay(view);
            case "configplay" -> t.adminPagesSystemConfigplay(view);
            case "configseo" -> t.adminPagesSystemConfigseo(view);
            case "configsms" -> t.adminPagesSystemConfigsms(view);
            case "configupload" -> t.adminPagesSystemConfigupload(view);
            case "configurl" -> t.adminPagesSystemConfigurl(view);
            case "configuser" -> t.adminPagesSystemConfiguser(view);
            case "configweixin" -> t.adminPagesSystemConfigweixin(view);
            default -> t.adminPagesSystemUrlsend(view);
        });
    }

    /**
     * 旧 {@code System::config} 的 GET（System.php:152-158）：{@code app.input_type} 没有时为 1；{@code vod_search_optimise_cache_minutes}
     * 换成 {@code VodSearch::getResultCacheMinutes}（{@code (int)} 后大于 0 时取该值，否则 20160）。
     */
    private static PhpArray configDefaults(PhpArray maccms) {
        PhpArray app = PhpArrays.arr(maccms.get("app"));
        if (app.get("input_type") == null || app.get("input_type") instanceof PhpValue.PhpNull) {
            app = PhpArrays.put(app, "input_type", new PhpValue.PhpInt(1));
        }
        long minutes = com.maccms.backend.content.application.PhpIntval.of(PhpValues.text(app.get("vod_search_optimise_cache_minutes")));
        app = PhpArrays.put(app, "vod_search_optimise_cache_minutes", new PhpValue.PhpInt(minutes > 0 ? minutes : 20160));
        return PhpArrays.put(maccms, "app", app);
    }

    /** {@code mac_extends_list($flag)}：{@code ext_list}（{@code ext_html} 由模板按扩展列表逐个输出）。 */
    private PhpValue extendsOf(String flag) {
        PhpArray list;
        try {
            list = system.extendsList(flag);
        } catch (LegacySiteFiles.FileException e) {
            list = PhpArrays.empty();
        }
        return PhpArrays.put(PhpArrays.empty(), "ext_list", list);
    }

    /** 旧模板里的 {@code tempnam(sys_get_temp_dir(), 'Tux')} 是否成功。 */
    private static boolean tempWritable(String dir) {
        if (dir == null) {
            return false;
        }
        try {
            Path p = Files.createTempFile(Path.of(dir), "Tux", null);
            Files.deleteIfExists(p);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 旧 {@code Index::quickmenu} 的 POST：先校验令牌，再写 {@code extra/quickmenu.php}。 */
    private ResponseEntity<String> quickmenu(Ctx ctx, SessionState session) {
        AdminForms.TokenCheck t = AdminForms.check(session, ctx.params());
        if (t.error() != null) {
            return responses.error(ctx.ajax(), settings.lang().get(t.error()));
        }
        byte[] raw = ctx.post().scalar("quickmenu");
        String text = raw == null ? null : com.maccms.backend.admin.application.LegacyPhp.utf8(raw);
        if (raw != null && text == null) {
            return responses.error(ctx.ajax(), settings.lang().get("param_err"));
        }
        Reply r = system.saveQuickmenu(text, settings.lang());
        return r.ok() ? responses.success(ctx.ajax(), r.message(), AdminController.successUrl(ctx))
                : responses.error(ctx.ajax(), r.message());
    }

    private AdminHead head(String titleKey) {
        return new AdminHead(settings.lang().get(titleKey), endpoint.sitePath(), endpoint.root(), settings.versionCode());
    }

    static String utf8(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }
}
