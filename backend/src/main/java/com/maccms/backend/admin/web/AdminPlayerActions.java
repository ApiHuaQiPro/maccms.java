package com.maccms.backend.admin.web;

import com.maccms.backend.admin.application.AdminSettings;
import com.maccms.backend.admin.application.ListConfigService;
import com.maccms.backend.admin.application.PhpArrays;
import com.maccms.backend.admin.application.PlayerAdminService;
import com.maccms.backend.admin.application.PlayerAdminService.Kind;
import com.maccms.backend.admin.application.SystemConfigService;
import com.maccms.backend.admin.application.view.Reply;
import com.maccms.backend.admin.web.AdminController.Ctx;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.system.ConfigPage;
import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * B15 播放器、服务器组、下载器、站群与定时任务配置的动作（旧 admin/controller/Vodplayer.php、Vodserver.php、Voddowner.php、Domain.php、
 * Timming.php；24 文档第 6 节）。由 {@link AdminController} 在登录与 {@code check_auth} 之后分派。
 */
public class AdminPlayerActions {

    /** 导入文件的大小上限（旧 {@code validate(['size' => 10240000])} 不生效，实际受 PHP 上传上限约束；新版取同一个数）。 */
    static final int MAX_IMPORT_BYTES = 10_240_000;

    private final AdminEndpoint endpoint;
    private final AdminSettings settings;
    private final AdminResponses responses;
    private final PlayerAdminService players;
    private final ListConfigService lists;
    private final SystemConfigService system;
    private final RandomGenerator random;

    public AdminPlayerActions(AdminEndpoint endpoint, AdminSettings settings, AdminResponses responses, PlayerAdminService players,
                              ListConfigService lists, SystemConfigService system, AdminRandom random) {
        this.endpoint = endpoint;
        this.settings = settings;
        this.responses = responses;
        this.players = players;
        this.lists = lists;
        this.system = system;
        this.random = random.generator();
    }

    /** 处理本类的动作；不是本类的动作时返回 null。 */
    ResponseEntity<String> handle(Ctx ctx, SessionState session) {
        String c = ctx.route().controller();
        String a = ctx.route().action();
        try {
            Kind kind = Kind.of(c);
            if (kind != null) {
                return player(ctx, session, kind, a);
            }
            if (c.equals("domain")) {
                return domain(ctx, a);
            }
            if (c.equals("timming")) {
                return timming(ctx, session, a);
            }
        } catch (LegacyConfigException e) {
            return responses.notice(ctx.ajax(), HttpStatus.SERVICE_UNAVAILABLE, "跳转提示", "旧配置读取失败");
        } catch (AdminForms.BadInput e) {
            return responses.error(ctx.ajax(), settings.lang().get("param_err"));
        }
        return null;
    }

    private ResponseEntity<String> player(Ctx ctx, SessionState session, Kind kind, String action) {
        String title = "admin/" + kind.controller() + "/title";
        return switch (action) {
            case "index" -> page(ctx, title, Map.of("list", players.list(kind)), "", kind.controller() + "/index");
            case "info" -> {
                if (ctx.post_()) {
                    AdminForms.TokenCheck t = AdminForms.check(session, ctx.params());
                    if (t.error() != null) {
                        yield responses.error(ctx.ajax(), settings.lang().get(t.error()));
                    }
                    PhpArray param = PhpArrays.remove(AdminForms.input(ctx.params(), false), "__token__");
                    yield reply(ctx, players.save(kind, param, settings.lang()));
                }
                PhpValue info = players.info(kind, scalar(ctx, "id"));
                yield page(ctx, title, Map.of("info", info == null ? PhpValue.PhpNull.INSTANCE : info),
                        AdminForms.issue(session, random, null), kind.controller() + "/info");
            }
            case "del" -> reply(ctx, players.delete(kind, scalar(ctx, "ids"), settings.lang()));
            case "field" -> reply(ctx, players.field(kind, scalar(ctx, "ids"), scalar(ctx, "col"), value(ctx, "val"),
                    settings.lang()));
            case "export" -> {
                if (kind != Kind.PLAYER) {
                    yield null;
                }
                String ua = ctx.request().getHeader("User-Agent");
                String[] out = players.export(scalar(ctx, "id"), ua != null && ua.contains("MSIE"));
                yield responses.attachment(out[0], "attachment; filename=" + out[1]);
            }
            case "import" -> {
                if (kind != Kind.PLAYER) {
                    yield null;
                }
                if (!ctx.post_()) {
                    yield page(ctx, "", Map.of(), AdminForms.issue(session, random, null), "vodplayer/import");
                }
                AdminForms.TokenCheck t = AdminForms.check(session, ctx.params());
                if (t.error() != null) {
                    yield responses.error(ctx.ajax(), settings.lang().get(t.error()));
                }
                byte[] file = uploaded(ctx);
                if (file == null) {
                    yield responses.error(ctx.ajax(), settings.lang().get("param_err"));
                }
                yield reply(ctx, players.importPlayer(file, settings.lang()));
            }
            default -> null;
        };
    }

    private ResponseEntity<String> domain(Ctx ctx, String action) {
        return switch (action) {
            case "index" -> {
                if (ctx.post_()) {
                    // 旧 Domain::index 的保存不校验表单令牌
                    PhpArray input = AdminForms.input(ctx.params(), false);
                    yield reply(ctx, lists.saveDomains(input.get("domain"), settings.lang()));
                }
                Map<String, PhpValue> roots = new LinkedHashMap<>();
                PhpValue list = lists.domainsOrNull();
                roots.put("domain_list", list == null ? PhpValue.PhpNull.INSTANCE : list);
                roots.put("templates", PhpArrays.list(system.templates()));
                yield page(ctx, "admin/domain/title", roots, "", "domain/index");
            }
            case "del" -> reply(ctx, lists.deleteDomain(scalar(ctx, "ids"), settings.lang()));
            case "export" -> responses.attachment(lists.exportDomains(), "attachment; filename=mac_domains.txt");
            case "import" -> {
                byte[] file = uploaded(ctx);
                if (file == null) {
                    yield responses.error(ctx.ajax(), settings.lang().get("param_err"));
                }
                yield reply(ctx, lists.importDomains(file, settings.lang()));
            }
            default -> null;
        };
    }

    private ResponseEntity<String> timming(Ctx ctx, SessionState session, String action) {
        return switch (action) {
            case "index" -> {
                Map<String, PhpValue> roots = new LinkedHashMap<>();
                roots.put("list", lists.timmings());
                roots.put("config", system.maccms());
                yield page(ctx, "admin/timming/title", roots, "", "timming/index");
            }
            case "info" -> {
                if (ctx.post_()) {
                    AdminForms.TokenCheck t = AdminForms.check(session, ctx.params());
                    if (t.error() != null) {
                        yield responses.error(ctx.ajax(), settings.lang().get(t.error()));
                    }
                    // 旧版没有 unset($param['__token__'])，令牌照样写进 timming.php
                    yield reply(ctx, lists.saveTimming(AdminForms.input(ctx.params(), false), settings.lang()));
                }
                PhpValue info = scalar(ctx, "id") == null ? null : lists.timmings().get(scalar(ctx, "id"));
                yield page(ctx, "admin/timming/title", Map.of("info", info == null ? PhpValue.PhpNull.INSTANCE : info),
                        AdminForms.issue(session, random, null), "timming/info");
            }
            case "del" -> reply(ctx, lists.deleteTimming(scalar(ctx, "ids"), settings.lang()));
            case "field" -> reply(ctx, lists.fieldTimming(scalar(ctx, "ids"), scalar(ctx, "col"), value(ctx, "val"),
                    settings.lang()));
            default -> null;
        };
    }

    private ResponseEntity<String> page(Ctx ctx, String titleKey, Map<String, PhpValue> roots, String token, String template) {
        AdminHead head = new AdminHead(settings.lang().get(titleKey), endpoint.sitePath(), endpoint.root(), settings.versionCode());
        ConfigPage view = new ConfigPage(head, settings.lang(), roots, ctx.url(), token);
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> switch (template) {
            case "vodplayer/index" -> t.adminPagesVodplayerIndex(view);
            case "vodplayer/info" -> t.adminPagesVodplayerInfo(view);
            case "vodplayer/import" -> t.adminPagesVodplayerImport(view);
            case "vodserver/index" -> t.adminPagesVodserverIndex(view);
            case "vodserver/info" -> t.adminPagesVodserverInfo(view);
            case "voddowner/index" -> t.adminPagesVoddownerIndex(view);
            case "voddowner/info" -> t.adminPagesVoddownerInfo(view);
            case "domain/index" -> t.adminPagesDomainIndex(view);
            case "timming/index" -> t.adminPagesTimmingIndex(view);
            default -> t.adminPagesTimmingInfo(view);
        });
    }

    private ResponseEntity<String> reply(Ctx ctx, Reply r) {
        return r.ok() ? responses.success(ctx.ajax(), r.message(), AdminController.successUrl(ctx))
                : responses.error(ctx.ajax(), r.message());
    }

    /** {@code $param[$name]} 的标量（UTF-8；数组或缺失为 null）。 */
    private static String scalar(Ctx ctx, String name) {
        byte[] b = ctx.params().scalar(name);
        if (b == null) {
            return null;
        }
        String s = com.maccms.backend.admin.application.LegacyPhp.utf8(b);
        if (s == null) {
            throw new AdminForms.BadInput();
        }
        return s;
    }

    /** {@code $param[$name]} 作为要写进配置的值（缺失为 null，即写 NULL）。 */
    private static PhpValue value(Ctx ctx, String name) {
        String s = scalar(ctx, name);
        return s == null ? null : new PhpValue.PhpString(s);
    }

    /** 上传的 {@code file} 字段内容；不是 multipart、没有该字段或超过上限时为 null。 */
    private static byte[] uploaded(Ctx ctx) {
        String type = ctx.request().getContentType();
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            return null;
        }
        try {
            if (ctx.request() instanceof org.springframework.web.multipart.MultipartHttpServletRequest m) {
                org.springframework.web.multipart.MultipartFile f = m.getFile("file");
                if (f == null || f.getOriginalFilename() == null || f.getSize() > MAX_IMPORT_BYTES) {
                    return null;
                }
                return f.getBytes();
            }
            jakarta.servlet.http.Part part = ctx.request().getPart("file");
            if (part == null || part.getSubmittedFileName() == null) {
                return null;
            }
            try (InputStream in = part.getInputStream()) {
                byte[] b = in.readNBytes(MAX_IMPORT_BYTES + 1);
                return b.length > MAX_IMPORT_BYTES ? null : b;
            }
        } catch (java.io.IOException | jakarta.servlet.ServletException | RuntimeException e) {
            return null;
        }
    }

    static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
