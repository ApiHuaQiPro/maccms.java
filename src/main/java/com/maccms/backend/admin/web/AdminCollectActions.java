package com.maccms.backend.admin.web;

import com.maccms.backend.admin.application.AdminSettings;
import com.maccms.backend.admin.web.AdminController.Ctx;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.collect.CjInfoView;
import com.maccms.backend.admin.web.view.collect.CjListView;
import com.maccms.backend.admin.web.view.collect.CollectApiView;
import com.maccms.backend.admin.web.view.collect.CollectInfoView;
import com.maccms.backend.admin.web.view.collect.CollectListView;
import com.maccms.backend.collect.application.CollectAdminServices;
import com.maccms.backend.collect.application.CollectPreviewService;
import com.maccms.backend.collect.application.CollectRunService;
import com.maccms.backend.collect.application.ProvideKind;
import com.maccms.backend.collect.application.view.CjNodeExport;
import com.maccms.backend.collect.application.view.CjNodeForm;
import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.content.application.AdminRejectedException;
import com.maccms.backend.content.application.PhpForm;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRow;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.random.RandomGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * B17 采集配置管理的动作（docs/architecture/25-b17-collect-config.md）：旧 {@code admin/controller/Collect.php} 的 {@code index}、{@code info}、
 * {@code del}、{@code union}、{@code bind}、{@code clearbind}、{@code test}，{@code admin/controller/Cj.php} 的 {@code index}、{@code info}、
 * {@code del}、{@code export}、{@code import}；B18 资源库预览的本地文件版本（docs/architecture/26-b18-collect-preview.md 第 5 节）：
 * {@code collect/api} 的 {@code ac=list}（预览）与新增的 {@code ac=localload}、{@code ac=localclear}（载入本地文件、清除预览缓存）。
 * {@code collect/api} 的其他 {@code ac}（采集入库）与断点续采 {@code collect/load} 是 B19 增量 3（docs/architecture/27-b19-collect-ingest.md 第 12 节），
 * 自定义规则的采集与发布是 B20（{@link AdminCjActions}，docs/architecture/28-b20-cj-local-html.md）。
 * 不发起任何网络请求：{@code collect/test} 返回固定提示（E01 只做本地版本）。
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
    private final Clock clock;
    private final String maxUpload;
    private final AdminCjActions cj;

    public AdminCollectActions(AdminEndpoint endpoint, AdminSettings settings, AdminResponses responses, CollectAdminServices services,
                               AdminRandom random, Clock clock, String maxUpload) {
        this.endpoint = endpoint;
        this.settings = settings;
        this.responses = responses;
        this.s = services;
        this.random = random.generator();
        this.clock = clock;
        this.maxUpload = maxUpload;
        this.cj = services == null ? null : new AdminCjActions(this, endpoint, settings, responses, services.cj());
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
            case "collect/api" -> api(ctx);
            case "collect/load" -> load(ctx);
            case "cj/index" -> cjList(ctx);
            case "cj/info" -> ctx.post_() ? result(ctx, s.nodes().save(ctx.params().nested("data"), param)) : cjInfo(ctx);
            case "cj/del" -> result(ctx, s.nodes().delete(param));
            case "cj/export" -> export(ctx);
            case "cj/import" -> importNode(ctx);
            case "cj/show_url", "cj/col_url", "cj/col_content", "cj/col_all", "cj/publish", "cj/show", "cj/content_del",
                 "cj/content_into", "cj/program" -> cj.handle(ctx);
            default -> null;
        };
    }

    // ------------------------------------------------------------------ 采集源

    private ResponseEntity<String> collectList(Ctx ctx) {
        CollectListView page = new CollectListView(head("admin/collect/title"), settings.lang(), ctx.url(),
                s.sources().list(ctx.params().scalar("page"), ctx.params().scalar("limit")), breakpoints());
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesCollectIndex(page));
    }

    /** 旧 Collect::union：本生产代码里只剩断点采集的按钮（B19 增量 3）与一个空的 {@code <script src="">}（新版不输出）。 */
    private ResponseEntity<String> union(Ctx ctx) {
        CollectListView page = new CollectListView(head("admin/collect/title"), settings.lang(), ctx.url(),
                new com.maccms.backend.content.application.view.AdminRowPage(java.util.List.of(), 0, 1, 1, null), breakpoints());
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesCollectUnion(page));
    }

    /** 有断点的种类（旧 {@code Cache::get(cache_flag . '_collect_break_' . 种类)} 非空）。 */
    private List<ProvideKind> breakpoints() {
        List<ProvideKind> out = new java.util.ArrayList<>();
        s.runs().breakpoints().forEach((k, has) -> {
            if (has) {
                out.add(k);
            }
        });
        return out;
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

    // ------------------------------------------------------------------ 资源库预览（B18）

    /** 旧 {@code collect/api} 带到预览页的参数（采集源列表"进入资源库"链接的键，加上分页）。 */
    private static final List<String> API_KEYS = List.of("ac", "cjflag", "cjurl", "h", "t", "ids", "wd", "type", "mid", "opt",
            "sync_pic_opt", "filter", "filter_from", "param", "page", "limit", "rday", "retry");

    /**
     * 旧 {@code Collect::api}（{@code admin/controller/Collect.php:140-171}）：{@code pg} 非空时当作 {@code page}；按 {@code mid} 分派（其他值
     * 旧版没有输出，新版"参数错误"）。{@code ac=list} 显示预览；{@code ac=localload}、{@code ac=localclear}（POST）载入本地文件、清除预览缓存；
     * 其他 {@code ac} 是采集入库（B19 增量 3，{@link #ingest}）。
     */
    private ResponseEntity<String> api(Ctx ctx) {
        Map<String, String> param = new LinkedHashMap<>();
        for (String k : API_KEYS) {
            byte[] v = ctx.params().scalar(k);
            if (v != null) {
                param.put(k, new String(v, StandardCharsets.UTF_8));
            }
        }
        String pg = str(ctx.params().scalar("pg"));
        if (!pg.isEmpty() && !pg.equals("0")) {
            param.put("page", pg);
        }
        CollectPreviewService.Source source = new CollectPreviewService.Source(param.get("cjflag"), param.get("mid"), param.get("type"),
                param.get("param"));
        Optional<ProvideKind> kind = source.kind();
        if (kind.isEmpty()) {
            return responses.error(ctx.ajax(), settings.lang().get("param_err"));
        }
        String ac = param.getOrDefault("ac", "");
        UnaryOperator<String> lang = settings.lang()::get;
        Map<String, String> back = new LinkedHashMap<>(param);
        back.put("ac", "list");
        back.remove("page");
        String listUrl = ctx.url().build("api") + "?" + com.maccms.backend.admin.application.LegacyAdminUrl.httpBuildQuery(back);
        switch (ac) {
            case "list" -> {
                CollectPreviewService.Preview preview = s.preview().preview(source, param.get("t"), param.get("wd"), param.get("page"),
                        param.get("limit"), lang).orElse(null);
                CollectApiView page = new CollectApiView(head("admin/collect/title"), settings.lang(), ctx.url(), kind.get(), param, preview,
                        s.preview().localDirectoryConfigured(), s.preview().localFiles(), s.preview().maxBytes(), maxUpload, clock.instant());
                return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesCollectApi(page));
            }
            case "localload" -> {
                if (!ctx.post_()) {
                    return responses.error(ctx.ajax(), settings.lang().get("param_err"));
                }
                String local = str(ctx.post().scalar("local"));
                Upload up = upload(ctx, s.preview().maxBytes());
                CollectPreviewService.LoadOutcome r;
                if (up.tooLarge()) {
                    r = new CollectPreviewService.LoadOutcome(false, "文件超过上传上限（" + maxUpload + "），更大的文件请放进本地目录", 0);
                } else if (up.content() != null) {
                    r = s.preview().load(source, up.name(), up.content(), lang);
                } else if (!local.isEmpty()) {
                    r = s.preview().loadLocal(source, local, lang);
                } else {
                    r = s.preview().load(source, null, null, lang);
                }
                return r.ok() ? responses.success(ctx.ajax(), r.message(), listUrl) : responses.error(ctx.ajax(), r.message());
            }
            case "localclear" -> {
                if (!ctx.post_()) {
                    return responses.error(ctx.ajax(), settings.lang().get("param_err"));
                }
                s.preview().clear(source);
                return responses.success(ctx.ajax(), settings.lang().get("clear_ok"), listUrl);
            }
            default -> {
                return ingest(ctx, source, param, lang);
            }
        }
    }

    // ------------------------------------------------------------------ 采集入库与断点续采（B19 增量 3）

    /** 没有载入的本地文件时的提示（旧版这时向资源站请求这一页）。 */
    static final String NOT_LOADED = "没有载入的本地文件（或已过期），请先在资源库页面载入文件";
    /** 旧 {@code <style>}（{@code admin/controller/Collect.php:278} 等）。 */
    private static final String STYLE = "<style type=\"text/css\">body{font-size:12px;color: #333333;line-height:21px;}"
            + "span{font-weight:bold;color:#FF0000}</style>";

    /**
     * 旧 {@code Collect::vod/art/actor/role/website($param)} 在 {@code ac != 'list'} 时：记下断点、取这一页、逐行入库并输出（旧 {@code mac_echo}），
     * 最后跳到下一页、同一页（重试）或回到预览（27 文档第 12 节）。没有载入的本地文件时"没有载入的本地文件"，不记断点、不写库。
     */
    private ResponseEntity<String> ingest(Ctx ctx, CollectPreviewService.Source source, Map<String, String> param,
                                          UnaryOperator<String> lang) {
        String api = ctx.url().build("api");
        String here = api + "?" + com.maccms.backend.admin.application.LegacyAdminUrl.httpBuildQuery(param);
        Optional<CollectRunService.RunPage> run = s.runs().run(source, param, here, lang);
        if (run.isEmpty()) {
            return responses.error(ctx.ajax(), NOT_LOADED);
        }
        CollectRunService.RunPage r = run.get();
        StringBuilder sb = new StringBuilder();
        if (r.kind() == ProvideKind.VOD) {
            sb.append("<title>").append(r.pageNow()).append('/').append(r.pageCount()).append(" collecting..</title><br>");
        }
        sb.append(STYLE).append("<br>");
        sb.append(escape("[" + r.kind().code() + "_data] ")).append(tip(r)).append("<br>");
        for (CollectRunService.Line line : r.lines()) {
            String color = line.green() ? "green" : "red";
            if (r.kind() == ProvideKind.VOD) {
                sb.append(line.no()).append("、").append(escape(line.name())).append(" <font color='").append(color).append("'>");
            } else {
                sb.append(line.no()).append(escape(line.name())).append("<font color=").append(color).append('>');
            }
            sb.append(escape(line.des())).append("</font><br>");
        }
        if (r.end() == CollectRunService.End.RETRY) {
            sb.append(escape("本页有 " + r.busy() + " 条入库排队超时，重新入库本页（第 " + r.jump().get("retry") + " 次重试，最多 "
                    + CollectRunService.MAX_RETRY + " 次）")).append("<br>");
        }
        if (r.end() == CollectRunService.End.DONE || r.end() == CollectRunService.End.SELECTED_DONE) {
            sb.append(escape(settings.lang().get("model/collect/is_over"))).append("<br>");
        }
        String target = api + "?" + com.maccms.backend.admin.application.LegacyAdminUrl.httpBuildQuery(r.jump());
        if (r.end() == CollectRunService.End.SELECTED_DONE) {
            // 旧版 $_SERVER["HTTP_REFERER"] 非空时回到来源页
            String ref = sameSite(ctx, ctx.request().getHeader("Referer"));
            if (ref != null && !ref.isEmpty()) {
                target = ref;
            }
        }
        sb.append(jump(target, r.seconds()));
        return responses.echoed(sb.toString());
    }

    /** {@code lang('model/collect/data_tip1', [页, 总页数, 地址])}：语言包里的 HTML 原样，三个值转义（地址是载入的文件名）。 */
    private String tip(CollectRunService.RunPage r) {
        String t = settings.lang().get("model/collect/data_tip1");
        String[] args = {Long.toString(r.page()), Long.toString(r.pageCount()), escape(r.sourceName() == null ? "" : r.sourceName())};
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

    /**
     * 旧 {@code Collect::load}（{@code admin/controller/Collect.php:126-137}）：有断点时输出 {@code lang('admin/collect/load_break')} 并跳到断点地址，
     * 否则跳回来源页（旧 {@code mac_get_refer()}）；没有来源页或来源页不是本站时跳到采集源列表（旧版跳到空地址，即不停地重新载入本页，D-B19-12）。
     */
    private ResponseEntity<String> load(Ctx ctx) {
        Optional<String> bp = s.runs().breakpoint(str(ctx.params().scalar("flag")));
        StringBuilder sb = new StringBuilder();
        String target;
        if (bp.isPresent() && sameSite(ctx, bp.get()) != null) {
            sb.append(escape(settings.lang().get("admin/collect/load_break")));
            target = bp.get();
        } else {
            String raw = ctx.request().getHeader("Referer");
            String ref = raw == null ? null : sameSite(ctx, phpUrldecode(raw).trim());
            target = ref == null || ref.isEmpty() ? ctx.url().build("index") : ref;
        }
        sb.append(jump(target, 0));
        return responses.echoed(sb.toString());
    }

    /** 旧 {@code mac_jump($url, $sec)}：内联脚本改为 progress.js 的 data-*（同 B13，22 文档第 8.4 节），地址与文字转义。 */
    String jump(String url, long seconds) {
        String u = escape(url);
        return "<script src=\"" + escape(endpoint.sitePath()) + "/admin-assets/progress.js\" data-url=\"" + u + "\" data-seconds=\"" + seconds
                + "\"></script><span>" + escape(settings.lang().get("pause")) + seconds + escape(settings.lang().get("continue_in_second"))
                + "  >>>  </span><a href=\"" + u + "\" >" + escape(settings.lang().get("browser_jump")) + "</a><br>";
    }

    /**
     * 只跳本站：以 {@code /} 开头的路径（不能是 {@code //}），或来源（{@code scheme://host[:port]}）与后台对外地址相同的 http(s) 地址；含反斜杠、
     * 控制字符或其他协议（如 {@code javascript:}）时为 null（27 文档 D-B19-12）。空串与 null 原样返回。来源不同的 Referer 在入口处已被拒绝
     * （D-B12-3），这里再挡一次断点文件与来源页里的地址。
     */
    private String sameSite(Ctx ctx, String url) {
        return sameSite(endpoint.origin(), url);
    }

    static String sameSite(String origin, String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\\') {
                return null;
            }
        }
        if (url.startsWith("/")) {
            return url.startsWith("//") ? null : url;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null;
        }
        int slash = url.indexOf('/', url.indexOf("//") + 2);
        String o = (slash < 0 ? url : url.substring(0, slash)).toLowerCase(Locale.ROOT);
        return origin != null && o.equals(origin) && o.indexOf('@') < 0 && o.indexOf('?') < 0 && o.indexOf('#') < 0 ? url : null;
    }

    /** PHP {@code urldecode}：{@code +} 为空格，{@code %XX} 按字节解码，不完整的 {@code %} 原样；结果按 UTF-8 解释。 */
    static String phpUrldecode(String s) {
        byte[] in = s.getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(in.length);
        for (int i = 0; i < in.length; i++) {
            byte b = in[i];
            if (b == '+') {
                out.write(' ');
            } else if (b == '%' && i + 2 < in.length && hex(in[i + 1]) >= 0 && hex(in[i + 2]) >= 0) {
                out.write(hex(in[i + 1]) * 16 + hex(in[i + 2]));
                i += 2;
            } else {
                out.write(b);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int hex(byte b) {
        return Character.digit((char) (b & 0xff), 16);
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private record Upload(String name, byte[] content, boolean tooLarge) {
    }

    /** 上传的 {@code file}：没有选文件时内容为 null；超过 multipart 上限或 {@code max} 时 {@code tooLarge}。 */
    private static Upload upload(Ctx ctx, long max) {
        String type = ctx.request().getContentType();
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            return new Upload(null, null, false);
        }
        try {
            if (ctx.request() instanceof org.springframework.web.multipart.MultipartHttpServletRequest m) {
                org.springframework.web.multipart.MultipartFile f = m.getFile("file");
                if (f == null || f.isEmpty() && (f.getOriginalFilename() == null || f.getOriginalFilename().isEmpty())) {
                    return new Upload(null, null, false);
                }
                if (f.getSize() > max) {
                    return new Upload(null, null, true);
                }
                return new Upload(f.getOriginalFilename(), f.getBytes(), false);
            }
            jakarta.servlet.http.Part part = ctx.request().getPart("file");
            if (part == null || part.getSubmittedFileName() == null || part.getSubmittedFileName().isEmpty() && part.getSize() == 0) {
                return new Upload(null, null, false);
            }
            if (part.getSize() > max) {
                return new Upload(null, null, true);
            }
            try (InputStream in = part.getInputStream()) {
                byte[] b = in.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE - 8));
                return b.length > max ? new Upload(null, null, true) : new Upload(part.getSubmittedFileName(), b, false);
            }
        } catch (org.springframework.web.multipart.MaxUploadSizeExceededException | IllegalStateException e) {
            return new Upload(null, null, true);
        } catch (java.io.IOException | jakarta.servlet.ServletException | RuntimeException e) {
            return new Upload(null, null, false);
        }
    }

    private static String str(byte[] b) {
        return b == null ? "" : new String(b, StandardCharsets.UTF_8);
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
