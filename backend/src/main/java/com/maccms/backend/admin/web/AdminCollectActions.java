package com.maccms.backend.admin.web;

import com.maccms.backend.admin.application.AdminSettings;
import com.maccms.backend.admin.web.AdminController.Ctx;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.collect.CjInfoView;
import com.maccms.backend.admin.web.view.collect.CjListView;
import com.maccms.backend.admin.web.view.collect.CollectInfoView;
import com.maccms.backend.admin.web.view.collect.CollectListView;
import com.maccms.backend.collect.application.CollectAdminServices;
import com.maccms.backend.collect.application.view.CjNodeExport;
import com.maccms.backend.collect.application.view.CjNodeForm;
import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.content.application.AdminRejectedException;
import com.maccms.backend.content.application.PhpForm;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRow;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * B17 采集配置管理的动作（docs/architecture/25-b17-collect-config.md）：旧 {@code admin/controller/Collect.php} 的 {@code index}、{@code info}、
 * {@code del}、{@code union}、{@code bind}、{@code clearbind}、{@code test}，{@code admin/controller/Cj.php} 的 {@code index}、{@code info}、
 * {@code del}、{@code export}、{@code import}。资源站的解析与预览（{@code collect/api}、{@code load}）在 B18，入库在 B19，规则的执行在 B20
 * （{@code AdminPermissions} 的批次表）。本批不发起任何网络请求：{@code collect/test} 返回固定提示（E01 只做本地版本）。
 */
public class AdminCollectActions {

    /** {@code collect/test} 的固定提示（旧版按采集地址发起 HTTP 请求，路线图 E01 只做本地版本）。 */
    static final String TEST_REFUSED = "新系统不连接资源站（在线采集按 E01 只做本地文件源），未进行连接测试";
    /** 导入文件的大小上限（旧版 validate 的 size 写了 10240000，但没有调用 move，实际不生效；新版按此上限）。 */
    static final int MAX_IMPORT_BYTES = 10_240_000;
    private static final AdminRow EMPTY = new AdminRow(Map.of());

    private final AdminEndpoint endpoint;
    private final AdminSettings settings;
    private final AdminResponses responses;
    private final CollectAdminServices s;
    private final RandomGenerator random;

    public AdminCollectActions(AdminEndpoint endpoint, AdminSettings settings, AdminResponses responses, CollectAdminServices services,
                               AdminRandom random) {
        this.endpoint = endpoint;
        this.settings = settings;
        this.responses = responses;
        this.s = services;
        this.random = random.generator();
    }

    /** 处理 B17 的动作；不是本批的动作（或采集服务未注册）时返回 null。 */
    ResponseEntity<String> handle(Ctx ctx, SessionState session) {
        if (s == null) {
            return null;
        }
        PhpForm param = AdminContentActions.form(ctx.params());
        return switch (ctx.route().code()) {
            case "collect/index" -> collectList(ctx);
            case "collect/info" -> ctx.post_() ? saveCollect(ctx, session) : collectInfo(ctx, session);
            case "collect/del" -> result(ctx, s.sources().delete(param));
            case "collect/union" -> union(ctx);
            case "collect/bind" -> result(ctx, s.binds().bind(param));
            case "collect/clearbind" -> clearBind(param);
            case "collect/test" -> testRefused();
            case "cj/index" -> cjList(ctx);
            case "cj/info" -> ctx.post_() ? result(ctx, s.nodes().save(ctx.params().nested("data"), param)) : cjInfo(ctx);
            case "cj/del" -> result(ctx, s.nodes().delete(param));
            case "cj/export" -> export(ctx);
            case "cj/import" -> importNode(ctx);
            default -> null;
        };
    }

    // ------------------------------------------------------------------ 采集源

    private ResponseEntity<String> collectList(Ctx ctx) {
        CollectListView page = new CollectListView(head("admin/collect/title"), settings.lang(), ctx.url(),
                s.sources().list(ctx.params().scalar("page"), ctx.params().scalar("limit")));
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesCollectIndex(page));
    }

    /** 旧 Collect::union：本生产代码里只剩断点采集的按钮（B18）与一个空的 {@code <script src="">}（新版不输出）。 */
    private ResponseEntity<String> union(Ctx ctx) {
        CollectListView page = new CollectListView(head("admin/collect/title"), settings.lang(), ctx.url(),
                new com.maccms.backend.content.application.view.AdminRowPage(java.util.List.of(), 0, 1, 1, null));
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesCollectUnion(page));
    }

    private ResponseEntity<String> collectInfo(Ctx ctx, SessionState session) {
        AdminRow info = s.sources().info(ctx.params().scalar("id")).orElse(EMPTY);
        CollectInfoView page = new CollectInfoView(head("admin/collect/title"), settings.lang(), info,
                AdminForms.issue(session, random, null), ctx.url().build("test"));
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesCollectInfo(page));
    }

    /** 旧 Collect::info 的 POST：{@code input('post.')}，先校验表单令牌（缺失 → 非法请求；不一致 → 请不要重复提交表单），再保存。 */
    private ResponseEntity<String> saveCollect(Ctx ctx, SessionState session) {
        AdminForms.TokenCheck t = AdminForms.check(session, ctx.post());
        if (t.error() != null) {
            return responses.error(ctx.ajax(), settings.lang().get(t.error()));
        }
        return result(ctx, s.sources().save(AdminContentActions.form(ctx.post())));
    }

    /** 旧 Collect::clearbind：{@code json(['code'=>1,'msg'=>lang('clear_ok')])}，写不进去时 {@code code=0}、{@code clear_err}。 */
    private ResponseEntity<String> clearBind(PhpForm param) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            boolean ok = s.binds().clear(param);
            m.put("code", ok ? 1 : 0);
            m.put("msg", settings.lang().get(ok ? "clear_ok" : "clear_err"));
        } catch (AdminRejectedException e) {
            m.put("code", 0);
            m.put("msg", e.reason().render(settings.lang()::get));
        }
        return responses.rawJson(m);
    }

    /** 旧 Collect::test 的 {@code json($res)}：新版不连接资源站，固定为失败的结果（表单脚本在 code 不为 1 时显示 msg）。 */
    private ResponseEntity<String> testRefused() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", 1001);
        m.put("msg", TEST_REFUSED);
        return responses.rawJson(m);
    }

    // ------------------------------------------------------------------ 自定义规则节点

    private ResponseEntity<String> cjList(Ctx ctx) {
        CjListView page = new CjListView(head("admin/cj/title"), settings.lang(), ctx.url(),
                s.nodes().list(ctx.params().scalar("page"), ctx.params().scalar("limit"), settings.pageSize()));
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesCjIndex(page));
    }

    private ResponseEntity<String> cjInfo(Ctx ctx) {
        CjNodeForm form = s.nodes().form(ctx.params().scalar("id"));
        CjInfoView page = new CjInfoView(head("admin/cj/title"), settings.lang(), form.info(), form.customize(),
                ctx.url().build("show_url"));
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesCjInfo(page));
    }

    /**
     * 旧 Cj::export：没有这一行时 {@code error(获取失败)}；否则附件 {@code mac_cj_名称.txt}（旧版文件名原样写进头部，IE 时先 urlencode）。
     * 名称里的控制字符（含换行）不进头部（旧版 PHP 的 header() 拒绝含换行的头，整个头不发出）。
     */
    private ResponseEntity<String> export(Ctx ctx) {
        String ua = ctx.request().getHeader("User-Agent");
        Optional<CjNodeExport> out = s.nodes().export(ctx.params().scalar("id"), ua != null && ua.contains("MSIE"));
        if (out.isEmpty()) {
            return responses.error(ctx.ajax(), settings.lang().get("obtain_err"));
        }
        String name = out.get().filename().replaceAll("[\\x00-\\x1f\\x7f]", "");
        // 头部按字节写出 UTF-8 的文件名（与旧版相同；Servlet 按 ISO-8859-1 输出每个字符）
        String header = "attachment; filename=" + new String(name.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        return responses.attachment(out.get().body(), header);
    }

    /** 旧 Cj::import：上传的 {@code file}；没有文件时旧版报错（500），新版"参数错误"。 */
    private ResponseEntity<String> importNode(Ctx ctx) {
        byte[] file = uploaded(ctx);
        if (file == null) {
            return responses.error(ctx.ajax(), settings.lang().get("param_err"));
        }
        AdminResult r = s.nodes().importNode(file);
        return result(ctx, r);
    }

    // ------------------------------------------------------------------ 公共

    private AdminHead head(String titleKey) {
        return new AdminHead(settings.lang().get(titleKey), endpoint.sitePath(), endpoint.root(), settings.versionCode());
    }

    private ResponseEntity<String> result(Ctx ctx, AdminResult r) {
        String msg = r.message().render(settings.lang()::get);
        if (!r.ok()) {
            return responses.error(ctx.ajax(), msg);
        }
        if (r.data() != null) {
            return responses.success(ctx.ajax(), msg, AdminController.successUrl(ctx), r.data());
        }
        return responses.success(ctx.ajax(), msg, AdminController.successUrl(ctx));
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
}
