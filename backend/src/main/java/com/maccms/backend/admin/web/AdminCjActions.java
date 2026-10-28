package com.maccms.backend.admin.web;

import com.maccms.backend.admin.application.AdminSettings;
import com.maccms.backend.admin.application.LegacyAdminUrl;
import com.maccms.backend.admin.web.AdminController.Ctx;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.collect.CjColUrlView;
import com.maccms.backend.admin.web.view.collect.CjProgramView;
import com.maccms.backend.admin.web.view.collect.CjPublishView;
import com.maccms.backend.admin.web.view.collect.CjShowUrlView;
import com.maccms.backend.admin.web.view.collect.CjShowView;
import com.maccms.backend.collect.application.CjRules;
import com.maccms.backend.collect.application.CjRunService;
import com.maccms.backend.content.application.AdminIds;
import com.maccms.backend.content.application.AdminRejectedException;
import com.maccms.backend.content.application.PhpForm;
import com.maccms.backend.content.application.PhpIntval;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * B20 自定义规则的采集与发布（docs/architecture/28-b20-cj-local-html.md 第 6 节）：旧 {@code admin/controller/Cj.php} 的 {@code show_url}、
 * {@code col_url}、{@code col_content}、{@code publish}、{@code show}、{@code content_del}、{@code content_into}、{@code program}、
 * {@code col_all}。页面来自本地 HTML 目录，不发请求。逐页推进的动作照旧输出逐行结果并跳到下一页（旧 {@code mac_echo}、{@code mac_jump}）；
 * 跳转改由 progress.js 的 {@code data-*}（内容安全策略），地址只带本动作需要的参数（旧版把全部参数原样 {@code http_build_query}）。
 */
final class AdminCjActions {

    /** 旧 {@code mac_jump(..., 3)}。 */
    static final long JUMP_SECONDS = 3;
    static final String NOT_CONFIGURED = "未配置本地 HTML 目录（maccms.collect.local.html-dir），无法采集";
    private static final String STYLE = "<style type=\"text/css\">body{font-size:12px;color: #333333;line-height:21px;}"
            + "span{font-weight:bold;color:#FF0000}</style>";

    private final AdminCollectActions owner;
    private final AdminEndpoint endpoint;
    private final AdminSettings settings;
    private final AdminResponses responses;
    private final CjRunService cj;

    AdminCjActions(AdminCollectActions owner, AdminEndpoint endpoint, AdminSettings settings, AdminResponses responses, CjRunService cj) {
        this.owner = owner;
        this.endpoint = endpoint;
        this.settings = settings;
        this.responses = responses;
        this.cj = cj;
    }

    /** 不是本批的动作时 null。 */
    ResponseEntity<String> handle(Ctx ctx) {
        if (cj == null) {
            return null;
        }
        try {
            return switch (ctx.route().code()) {
                case "cj/show_url" -> showUrl(ctx);
                case "cj/col_url" -> withNode(ctx, true, node -> colUrl(ctx, node));
                case "cj/col_content" -> withNode(ctx, true, node -> responses.echoed(colContent(ctx, node, false).html()));
                case "cj/col_all" -> withNode(ctx, true, node -> colAll(ctx, node));
                case "cj/publish" -> publish(ctx);
                case "cj/show" -> show(ctx);
                case "cj/content_del" -> contentDel(ctx);
                case "cj/content_into" -> withNode(ctx, false, node -> contentInto(ctx, node));
                case "cj/program" -> withNode(ctx, false, node -> program(ctx, node));
                default -> null;
            };
        } catch (CjRules.RuleException e) {
            return responses.error(ctx.ajax(), e.reason());
        } catch (AdminRejectedException e) {
            return responses.error(ctx.ajax(), e.reason().render(settings.lang()::get));
        }
    }

    private interface NodeAction {
        ResponseEntity<String> run(Map<String, String> node);
    }

    /** 旧 {@code infoData('cj_node', nodeid = id)}：没有这一行时"获取失败"；需要读页面的动作在未配置本地 HTML 目录时提示，不写库。 */
    private ResponseEntity<String> withNode(Ctx ctx, boolean needsHtml, NodeAction action) {
        Optional<Map<String, String>> node = cj.node(ctx.params().scalar("id"));
        if (node.isEmpty()) {
            return responses.error(ctx.ajax(), settings.lang().get("obtain_err"));
        }
        if (needsHtml && !cj.htmlConfigured()) {
            return responses.error(ctx.ajax(), NOT_CONFIGURED);
        }
        return action.run(node.get());
    }

    private UnaryOperator<String> lang() {
        return settings.lang()::get;
    }

    private AdminHead head(String titleKey) {
        return new AdminHead(settings.lang().get(titleKey), endpoint.sitePath(), endpoint.root(), settings.versionCode());
    }

    private String self(Ctx ctx, String action, Map<String, String> q) {
        return ctx.url().build(action) + "?" + LegacyAdminUrl.httpBuildQuery(q);
    }

    // ------------------------------------------------------------------ show_url

    private ResponseEntity<String> showUrl(Ctx ctx) {
        List<CjRunService.UrlEntry> urls = cj.showUrl(ctx.params().nested("data"), AdminContentActions.form(ctx.params()));
        CjShowUrlView page = new CjShowUrlView(head("admin/cj/title"), settings.lang(), urls, cj.htmlConfigured());
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesCjShow_url(page));
    }

    // ------------------------------------------------------------------ col_url

    private ResponseEntity<String> colUrl(Ctx ctx, Map<String, String> node) {
        CjRunService.ColUrl r = cj.colUrl(node, ctx.params().scalar("page"), lang());
        String notice;
        String jump = null;
        if (r.more()) {
            notice = settings.lang().get("server_rest");
            jump = self(ctx, "col_url", Map.of("id", node.get("nodeid"), "page", Long.toString(r.page() + 1)));
        } else {
            notice = settings.lang().get("admin/cj/url_cj_complete");
        }
        CjColUrlView page = new CjColUrlView(head("admin/cj/url/title"), settings.lang(), r, notice, jump, JUMP_SECONDS);
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesCjCol_url(page));
    }

    // ------------------------------------------------------------------ col_content

    private record Echo(String html, List<Long> ids, boolean empty) {
    }

    /**
     * 旧 {@code col_content} 的输出：样式、提示（共几条、几批）、逐条网址与结果；还有"未采集"的内容时"让服务器休息一会"并跳到下一批，采完时
     * "采集完成"；开始时就没有时"内容采集完成"。{@code all} 时（col_all）只做这一批，最后输出"内容采集完成"，不跳转。
     */
    private Echo colContent(Ctx ctx, Map<String, String> node, boolean all) {
        CjRunService.ColContent r = cj.colContent(node, ctx.params().scalar("page"));
        StringBuilder sb = new StringBuilder();
        sb.append(STYLE).append("<br>");
        sb.append(esc(format(settings.lang().get("admin/cj/content/tip"), r.total(), r.totalPage(), CjRunService.CONTENT_BATCH, r.page())))
                .append("<br>");
        if (r.empty()) {
            sb.append(esc(settings.lang().get("admin/cj/content_cj_complete"))).append("<br>");
            return new Echo(sb.toString(), List.of(), true);
        }
        for (CjRunService.ContentLine line : r.lines()) {
            sb.append(esc(line.url())).append("&nbsp;&nbsp;");
            sb.append(line.note() == null ? "ok" : "<span>" + esc(line.note()) + "</span>").append("<br>");
        }
        if (all) {
            sb.append(esc(settings.lang().get("admin/cj/content_cj_complete"))).append("<br>");
        } else if (r.remaining() > 0) {
            sb.append(esc(settings.lang().get("server_rest"))).append("<br>");
            sb.append(owner.jump(self(ctx, "col_content", Map.of("id", node.get("nodeid"), "page", Long.toString(r.page() + 1))),
                    JUMP_SECONDS));
        } else {
            sb.append(esc(settings.lang().get("admin/cj/cj_complete"))).append("<br>");
        }
        return new Echo(sb.toString(), r.ids(), false);
    }

    // ------------------------------------------------------------------ col_all

    /**
     * 旧 {@code Cj::col_all}（{@code Cj.php:128-132}，定时任务 cj 也调它）：采集一页网址（{@code page}，缺省第 1 页）→"URL采集完成"→采集一批内容
     * → 导入这一批（每页 999 条）→"内容入库完成"。只做一轮，不跳转。
     */
    private ResponseEntity<String> colAll(Ctx ctx, Map<String, String> node) {
        cj.colUrl(node, ctx.params().scalar("page"), lang());
        StringBuilder sb = new StringBuilder();
        sb.append(esc(settings.lang().get("admin/cj/url_cj_complete"))).append("<br>");
        Echo content = colContent(ctx, node, true);
        sb.append(content.html());
        if (content.empty()) {
            return responses.echoed(sb.toString());
        }
        long page = pageOf(ctx.params().scalar("page"));
        CjRunService.Into r = cj.contentInto(node, content.ids(), false, page, 999, null, 0, lang());
        sb.append(intoHtml(r));
        sb.append(esc(settings.lang().get("admin/cj/content_into/complete"))).append("<br>");
        return responses.echoed(sb.toString());
    }

    // ------------------------------------------------------------------ publish、show、content_del

    private ResponseEntity<String> publish(Ctx ctx) {
        String id = str(ctx.params().scalar("id"));
        String status = str(ctx.params().scalar("status"));
        Map<String, String> q = new LinkedHashMap<>();
        q.put("id", id);
        if (!status.isEmpty()) {
            q.put("status", status);
        }
        q.put("page", "{page}");
        q.put("limit", "{limit}");
        CjPublishView page = new CjPublishView(head("admin/cj/publish/title"), settings.lang(), ctx.url(),
                cj.publish(ctx.params().scalar("id"), ctx.params().scalar("status"), ctx.params().scalar("page"),
                        ctx.params().scalar("limit"), settings.pageSize()),
                LegacyAdminUrl.urlencode(id), status, ctx.url().build("cj/publish", q));
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesCjPublish(page));
    }

    private ResponseEntity<String> show(Ctx ctx) {
        CjShowView page = new CjShowView(head("admin/cj/title"), settings.lang(), cj.show(ctx.params().scalar("id")));
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesCjShow(page));
    }

    private ResponseEntity<String> contentDel(Ctx ctx) {
        cj.contentDel(AdminContentActions.form(ctx.params()));
        return responses.success(ctx.ajax(), settings.lang().get("del_ok"), AdminController.successUrl(ctx));
    }

    // ------------------------------------------------------------------ content_into

    /**
     * 旧 {@code content_into} 的输出：样式、提示（共几条、几页、每页几条、当前页）、每条入库结果的说明（旧 {@code mac_echo($res['msg'])}）；
     * 还有下一页时"让服务器休息一会"并跳到下一页（带上总数与本页最后一行的编号），否则"导入完成"。
     */
    private ResponseEntity<String> contentInto(Ctx ctx, Map<String, String> node) {
        PhpForm param = AdminContentActions.form(ctx.params());
        List<Long> ids = AdminIds.ids(param);
        boolean all = "1".equals(param.text("all"));
        long page = pageOf(ctx.params().scalar("page"));
        long l = PhpIntval.of(str(ctx.params().scalar("limit")));
        int limit = (int) (l < 20 ? settings.pageSize() : Math.min(l, com.maccms.backend.content.application.AdminPaging.MAX_LIMIT));
        byte[] t = ctx.params().scalar("total");
        Long total = PhpForm.phpEmpty(t) ? null : Math.max(0, PhpIntval.of(str(t)));
        long after = Math.max(0, PhpIntval.of(str(ctx.params().scalar("after"))));
        CjRunService.Into r = cj.contentInto(node, ids, all, page, limit, total, after, lang());
        StringBuilder sb = new StringBuilder(intoHtml(r));
        if (r.more()) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("id", node.get("nodeid"));
            if (all) {
                q.put("all", "1");
            } else if (ids != null) {
                q.put("ids", ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
            }
            q.put("page", Long.toString(page + 1));
            q.put("limit", Integer.toString(limit));
            q.put("total", Long.toString(r.total()));
            q.put("after", Long.toString(r.after()));
            sb.append(esc(settings.lang().get("server_rest"))).append("<br>");
            sb.append(owner.jump(self(ctx, "content_into", q), JUMP_SECONDS));
        } else {
            sb.append(esc(settings.lang().get("import_ok"))).append("<br>");
        }
        return responses.echoed(sb.toString());
    }

    private String intoHtml(CjRunService.Into r) {
        StringBuilder sb = new StringBuilder();
        sb.append(STYLE).append("<br>");
        sb.append(esc(format(settings.lang().get("admin/cj/content_into/tip"), r.total(), r.totalPage(), r.limit(), r.page()))).append("<br>");
        for (CjRunService.IntoLine line : r.lines()) {
            sb.append(esc(line.msg())).append("<br>");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ program

    private ResponseEntity<String> program(Ctx ctx, Map<String, String> node) {
        if (ctx.post_()) {
            cj.saveProgram(node, AdminContentActions.form(ctx.params()));
            return responses.success(ctx.ajax(), settings.lang().get("save_ok"), AdminController.successUrl(ctx));
        }
        CjProgramView page = new CjProgramView(head("admin/cj/title"), settings.lang(), str(ctx.params().scalar("id")),
                cj.program(node, lang()));
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesCjProgram(page));
    }

    // ------------------------------------------------------------------ 公共

    /** 旧 {@code intval($param['page']) < 1 ? 1 : $param['page']}。 */
    private static long pageOf(byte[] page) {
        long p = page == null ? 0 : PhpIntval.of(str(page));
        return p < 1 ? 1 : p;
    }

    /** {@code lang(key, [a, b, c, d])}：按顺序替换 {@code %s}（ThinkPHP 的 {@code sprintf}）。 */
    static String format(String t, Object... args) {
        StringBuilder sb = new StringBuilder();
        int a = 0;
        int i = 0;
        while (i < t.length()) {
            if (t.startsWith("%s", i) && a < args.length) {
                sb.append(args[a++]);
                i += 2;
            } else {
                sb.append(t.charAt(i++));
            }
        }
        return sb.toString();
    }

    private static String esc(String s) {
        return AdminCollectActions.escape(s);
    }

    private static String str(byte[] b) {
        return b == null ? "" : new String(b, StandardCharsets.UTF_8);
    }
}
