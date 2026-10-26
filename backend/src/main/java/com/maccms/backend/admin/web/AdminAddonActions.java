package com.maccms.backend.admin.web;

import com.maccms.backend.admin.application.AddonService;
import com.maccms.backend.admin.application.AdminSettings;
import com.maccms.backend.admin.application.LegacyPhp;
import com.maccms.backend.admin.application.PhpArrays;
import com.maccms.backend.admin.application.view.Reply;
import com.maccms.backend.admin.web.AdminController.Ctx;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.system.ConfigPage;
import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.common.web.LegacyJson;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * B15 本地插件的动作（旧 admin/controller/Addon.php；路线图第 5.7 节，24 文档第 7 节）。插件市场的 {@code install}、{@code upgrade}（E13）在 B29，
 * 由 B12 的分派返回"由 B29 批次提供"；{@code downloaded} 在这份生产代码里只列本地插件（旧版已去掉市场列表），本批实现。
 */
public class AdminAddonActions {

    /** JSONP 回调名：只接受 JavaScript 标识符与点（旧版原样拼进响应，新版不合法时用 ThinkPHP 的默认名 jsonpReturn）。 */
    private static final Pattern CALLBACK = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$.]{0,127}");

    private final AdminEndpoint endpoint;
    private final AdminSettings settings;
    private final AdminResponses responses;
    private final AddonService addons;
    private final RandomGenerator random;
    private final Clock clock;

    public AdminAddonActions(AdminEndpoint endpoint, AdminSettings settings, AdminResponses responses, AddonService addons,
                             AdminRandom random, Clock clock) {
        this.endpoint = endpoint;
        this.settings = settings;
        this.responses = responses;
        this.addons = addons;
        this.random = random.generator();
        this.clock = clock;
    }

    ResponseEntity<String> handle(Ctx ctx, SessionState session) {
        if (!ctx.route().controller().equals("addon")) {
            return null;
        }
        try {
            return switch (ctx.route().action()) {
                case "index" -> page(ctx, "admin/addon/title", Map.of(), "", "index");
                case "downloaded" -> downloaded(ctx);
                case "add" -> page(ctx, "", Map.of(), AdminForms.issue(session, random, null), "add");
                case "config" -> config(ctx);
                case "state" -> state(ctx);
                case "uninstall" -> uninstall(ctx);
                case "local" -> {
                    AdminForms.TokenCheck t = AdminForms.check(session, ctx.params());
                    // 旧 Addon::local：令牌通过后 echo 'closed'; exit;（本地上传安装已关闭）
                    yield t.error() != null ? responses.error(ctx.ajax(), settings.lang().get(t.error())) : responses.text("closed");
                }
                // 旧 Addon::info 是空方法：200、空正文
                case "info" -> responses.text("");
                default -> null;
            };
        } catch (AdminForms.BadInput e) {
            return responses.error(ctx.ajax(), settings.lang().get("param_err"));
        } catch (LegacySiteFiles.FileException | LegacyConfigException e) {
            return responses.notice(ctx.ajax(), HttpStatus.SERVICE_UNAVAILABLE, "跳转提示", "插件目录读取失败");
        }
    }

    /**
     * 旧 {@code Addon::downloaded}：GET 的 {@code offset}、{@code limit}（{@code (int)}）、{@code filter}、{@code search}；有 {@code callback}
     * 时 JSONP，否则 JSON（{@code JSON_UNESCAPED_UNICODE}）。
     */
    private ResponseEntity<String> downloaded(Ctx ctx) {
        AdminParams get = ctx.get();
        PhpArray result = addons.downloaded(PhpIntval.of(text(get.scalar("offset"))), PhpIntval.of(text(get.scalar("limit"))),
                text(get.scalar("filter")), text(get.scalar("search")));
        Object value = PhpArrays.jsonable(result);
        String json = LegacyJson.encode(value);
        byte[] cb = get.scalar("callback");
        if (cb == null || cb.length == 0) {
            return responses.rawJson(value);
        }
        String name = text(cb);
        if (!CALLBACK.matcher(name).matches()) {
            name = "jsonpReturn";
        }
        String body = name + "(" + json + ");";
        return ResponseEntity.ok().contentType(new MediaType("application", "javascript", java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff").header("X-Robots-Tag", "noindex")
                .body(body);
    }

    private ResponseEntity<String> config(Ctx ctx) {
        String name = scalar(ctx, "name");
        if (ctx.post_()) {
            // 旧 $this->request->post("row/a")：只取 POST，按数组转换（标量 x 成为 [x]）
            PhpValue row = AdminForms.input(ctx.post(), false).get("row");
            if (row != null && !(row instanceof PhpArray)) {
                row = PhpArrays.put(PhpArrays.empty(), 0L, row);
            }
            return reply(ctx, addons.saveConfig(name, row, settings.lang()));
        }
        PhpArray[] data;
        try {
            data = addons.configPage(name);
        } catch (AddonService.Failed f) {
            return responses.error(ctx.ajax(), settings.lang().get(f.getMessage()));
        }
        Map<String, PhpValue> roots = new LinkedHashMap<>();
        roots.put("info", data[0]);
        roots.put("config", data[1]);
        return page(ctx, "", roots, "", "config");
    }

    /** 旧 {@code Addon::state}：{@code action} 为 enable 时启用，其他一律禁用。 */
    private ResponseEntity<String> state(Ctx ctx) {
        String name = scalar(ctx, "name");
        if (name == null || name.isEmpty() || name.equals("0")) {
            return responses.error(ctx.ajax(), settings.lang().get("param_err"));
        }
        try {
            if ("enable".equals(scalar(ctx, "action"))) {
                addons.enable(name);
            } else {
                addons.disable(name);
            }
        } catch (AddonService.Failed f) {
            return failed(ctx, f);
        }
        return responses.success(ctx.ajax(), settings.lang().get("opt_ok"), AdminController.successUrl(ctx));
    }

    private ResponseEntity<String> uninstall(Ctx ctx) {
        String name = scalar(ctx, "name");
        if (name == null || name.isEmpty() || name.equals("0")) {
            return responses.error(ctx.ajax(), settings.lang().get("param_err"));
        }
        if (name.contains(".") || name.contains("/") || name.contains("\\")) {
            return responses.error(ctx.ajax(), settings.lang().get("admin/addon/path_err"));
        }
        try {
            addons.uninstall(name);
        } catch (AddonService.Failed f) {
            return failed(ctx, f);
        }
        return responses.success(ctx.ajax(), settings.lang().get("uninstall_ok"), AdminController.successUrl(ctx));
    }

    /**
     * 旧版捕获 {@code AddonException} 时 {@code $this->result($e->getData(), $e->getCode(), $e->getMessage())}（{@code {code,msg,time,data}}），
     * 其他异常 {@code $this->error($e->getMessage())}。
     */
    private ResponseEntity<String> failed(Ctx ctx, AddonService.Failed f) {
        if (f.code() != 0) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", f.code());
            m.put("msg", f.getMessage());
            m.put("time", clock.instant().getEpochSecond());
            m.put("data", PhpArrays.jsonable(f.data()));
            return responses.rawJson(m);
        }
        return responses.error(ctx.ajax(), f.getMessage());
    }

    private ResponseEntity<String> page(Ctx ctx, String titleKey, Map<String, PhpValue> roots, String token, String template) {
        AdminHead head = new AdminHead(settings.lang().get(titleKey), endpoint.sitePath(), endpoint.root(), settings.versionCode());
        ConfigPage view = new ConfigPage(head, settings.lang(), roots, ctx.url(), token);
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> switch (template) {
            case "index" -> t.adminPagesAddonIndex(view);
            case "add" -> t.adminPagesAddonAdd(view);
            default -> t.adminPagesAddonConfig(view);
        });
    }

    private ResponseEntity<String> reply(Ctx ctx, Reply r) {
        return r.ok() ? responses.success(ctx.ajax(), r.message(), AdminController.successUrl(ctx))
                : responses.error(ctx.ajax(), r.message());
    }

    private static String scalar(Ctx ctx, String name) {
        byte[] b = ctx.params().scalar(name);
        if (b == null) {
            return null;
        }
        String s = LegacyPhp.utf8(b);
        if (s == null) {
            throw new AdminForms.BadInput();
        }
        return s;
    }

    private static String text(byte[] b) {
        return b == null ? null : new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }
}
