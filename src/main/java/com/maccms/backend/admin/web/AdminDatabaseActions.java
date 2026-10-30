package com.maccms.backend.admin.web;

import com.maccms.backend.admin.application.AdminSettings;
import com.maccms.backend.admin.application.DatabaseToolService;
import com.maccms.backend.admin.application.LegacyPhp;
import com.maccms.backend.admin.application.PhpArrays;
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
import java.time.Instant;
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
 * B15 数据库工具的动作（旧 admin/controller/Database.php；24 文档第 9 节）。还原备份 {@code import}、执行 SQL {@code sql}、优化表 {@code optimize}、
 * 修复表 {@code repair} 按零结构变更在入口拒绝（路线图第 1.2 节）：菜单与权限码照旧，页面与接口只给出固定提示，不接受也不执行任何 SQL 文本。
 */
public class AdminDatabaseActions {

    private static final ZoneId PRC = ZoneId.of("Asia/Shanghai");

    private final AdminEndpoint endpoint;
    private final AdminSettings settings;
    private final AdminResponses responses;
    private final DatabaseToolService db;
    private final RandomGenerator random;

    public AdminDatabaseActions(AdminEndpoint endpoint, AdminSettings settings, AdminResponses responses, DatabaseToolService db,
                                AdminRandom random) {
        this.endpoint = endpoint;
        this.settings = settings;
        this.responses = responses;
        this.db = db;
        this.random = random.generator();
    }

    ResponseEntity<String> handle(Ctx ctx, SessionState session) {
        if (!ctx.route().controller().equals("database")) {
            return null;
        }
        try {
            return switch (ctx.route().action()) {
                case "index" -> index(ctx);
                case "export" -> export(ctx);
                case "del" -> reply(ctx, db.delete(scalar(ctx, "id"), settings.lang()));
                case "columns" -> columns(ctx);
                case "rep" -> rep(ctx, session);
                // 零结构变更：不还原、不执行 SQL、不做表维护（D-B15-1）
                case "import", "optimize", "repair" -> responses.error(ctx.ajax(), DatabaseToolService.REFUSED);
                case "sql" -> responses.notice(ctx.ajax(), HttpStatus.OK, "跳转提示", DatabaseToolService.REFUSED);
                default -> null;
            };
        } catch (AdminForms.BadInput e) {
            return responses.error(ctx.ajax(), settings.lang().get("param_err"));
        } catch (LegacySiteFiles.FileException | LegacyConfigException e) {
            return responses.error(ctx.ajax(), DatabaseToolService.BAD_BACKUP_PATH);
        }
    }

    /** 旧 {@code Database::index}：{@code group=import} 时备份列表，否则表列表。 */
    private ResponseEntity<String> index(Ctx ctx) {
        boolean imports = "import".equals(scalar(ctx, "group"));
        PhpArray list = PhpArrays.empty();
        long i = 0;
        if (imports) {
            DateTimeFormatter stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
            DateTimeFormatter dt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            for (PhpArray b : db.backups()) {
                long t = com.maccms.backend.content.application.PhpIntval.of(PhpValues.text(b.get("time")));
                PhpArray row = b;
                row = PhpArrays.put(row, "name", stamp.format(Instant.ofEpochSecond(t).atZone(PRC)));
                row = PhpArrays.put(row, "size_k", DatabaseToolService.kb(b.get("size")));
                row = PhpArrays.put(row, "time_text", dt.format(Instant.ofEpochSecond(t).atZone(PRC)));
                row = PhpArrays.put(row, "id", Long.toString(t));
                list = PhpArrays.put(list, i++, row);
            }
        } else {
            for (PhpArray t : db.tableStatus()) {
                PhpArray row = PhpArrays.put(t, "size_kb", DatabaseToolService.kb(t.get("Data_length")));
                row = PhpArrays.put(row, "free_kb", DatabaseToolService.kb(t.get("Data_free")));
                list = PhpArrays.put(list, i++, row);
            }
        }
        return page(ctx, "admin/database/title", Map.of("list", list), "", imports ? "import" : "export");
    }

    /** 旧 {@code export($ids = '', $start = 0)}：参数按名字从 {@code input()} 绑定；只接受 POST，否则"备份出错"。 */
    private ResponseEntity<String> export(Ctx ctx) {
        if (!ctx.post_()) {
            return responses.error(ctx.ajax(), settings.lang().get("admin/database/backup_err"));
        }
        List<String> ids = new ArrayList<>();
        List<byte[]> items = ctx.params().list("ids");
        if (items != null) {
            for (byte[] b : items) {
                ids.add(utf8(b));
            }
        } else if (ctx.params().scalar("ids") != null) {
            ids.add(utf8(ctx.params().scalar("ids")));
        }
        return reply(ctx, db.export(ids, scalar(ctx, "start"), settings.lang()));
    }

    /** 旧 {@code Database::columns}：表不在库里 → "Table is invalid."；有表 → 列清单（{@code success} 带 data）；否则"参数错误"。 */
    private ResponseEntity<String> columns(Ctx ctx) {
        String table = scalar(ctx, "table");
        boolean has = table != null && !table.isEmpty() && !table.equals("0");
        if (has && !validTable(table)) {
            return responses.error(ctx.ajax(), "Table is invalid.");
        }
        if (has) {
            return responses.success(ctx.ajax(), settings.lang().get("obtain_ok"), AdminController.successUrl(ctx), db.columns(table));
        }
        return responses.error(ctx.ajax(), settings.lang().get("param_err"));
    }

    private boolean validTable(String table) {
        for (PhpArray t : db.tableStatus()) {
            if (PhpValues.text(t.get("Name")).equals(table)) {
                return table.matches("[A-Za-z0-9_]{1,64}");
            }
        }
        return false;
    }

    /** 旧 {@code Database::rep}：GET 显示表单（表列表与令牌），POST 先校验令牌再替换。 */
    private ResponseEntity<String> rep(Ctx ctx, SessionState session) {
        if (!ctx.post_()) {
            PhpArray list = PhpArrays.empty();
            long i = 0;
            for (PhpArray t : db.tableStatus()) {
                list = PhpArrays.put(list, i++, t);
            }
            return page(ctx, "", Map.of("list", list), AdminForms.issue(session, random, null), "rep");
        }
        AdminForms.TokenCheck t = AdminForms.check(session, ctx.params());
        if (t.error() != null) {
            return responses.error(ctx.ajax(), settings.lang().get(t.error()));
        }
        return reply(ctx, db.replace(scalar(ctx, "table"), scalar(ctx, "field"), scalar(ctx, "findstr"), scalar(ctx, "tostr"),
                settings.lang()));
    }

    private ResponseEntity<String> page(Ctx ctx, String titleKey, Map<String, PhpValue> roots, String token, String template) {
        AdminHead head = new AdminHead(settings.lang().get(titleKey), endpoint.sitePath(), endpoint.root(), settings.versionCode());
        ConfigPage view = new ConfigPage(head, settings.lang(), new LinkedHashMap<>(roots), ctx.url(), token);
        return responses.page(ctx.ajax(), HttpStatus.OK, tt -> switch (template) {
            case "export" -> tt.adminPagesDatabaseExport(view);
            case "import" -> tt.adminPagesDatabaseImport(view);
            default -> tt.adminPagesDatabaseRep(view);
        });
    }

    private ResponseEntity<String> reply(Ctx ctx, Reply r) {
        return r.ok() ? responses.success(ctx.ajax(), r.message(), AdminController.successUrl(ctx))
                : responses.error(ctx.ajax(), r.message());
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

    static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
