package com.maccms.backend.admin.web;

import com.maccms.backend.admin.application.AdminSettings;
import com.maccms.backend.admin.application.view.AdminPrincipal;
import com.maccms.backend.admin.web.AdminController.Ctx;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.content.ContentFormView;
import com.maccms.backend.admin.web.view.content.ContentListView;
import com.maccms.backend.admin.web.view.content.FrontLinks;
import com.maccms.backend.admin.web.view.content.LinkInfoView;
import com.maccms.backend.admin.web.view.content.LinkListView;
import com.maccms.backend.admin.web.view.content.SelectView;
import com.maccms.backend.admin.web.view.content.TypeInfoView;
import com.maccms.backend.admin.web.view.content.TypeListView;
import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.content.application.AdminConfig;
import com.maccms.backend.content.application.AdminPaging;
import com.maccms.backend.admin.application.LegacyAdminUrl;
import com.maccms.backend.admin.web.view.JumpPage;
import com.maccms.backend.admin.web.view.content.ImgConfig;
import com.maccms.backend.content.application.AdminPlayers;
import com.maccms.backend.content.application.ContentAdminServices;
import com.maccms.backend.content.application.LegacyMultiValue;
import com.maccms.backend.content.application.view.AdminProgress;
import com.maccms.backend.content.application.PhpForm;
import com.maccms.backend.content.application.TypeAdminService;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.LegacyUrlStyle;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * B13 后台内容管理的动作（docs/architecture/22-b13-admin-content.md）：由 {@link AdminController} 在登录与 {@code check_auth} 之后分派。
 * 各动作取参数的方式与旧控制器相同（{@code input()} 为合并后的参数，{@code input('post.')} 只取 POST）。
 */
public class AdminContentActions {

    private static final Logger log = LoggerFactory.getLogger(AdminContentActions.class);
    private static final AdminRow EMPTY = new AdminRow(Map.of());
    /** 旧 Index::select 能取的模板（admin/view/public/select_*.html）。 */
    private static final List<String> SELECT_TEMPLATES = List.of("select_type", "select_level", "select_hits", "select_status",
            "select_lock", "select_state", "select_copyright");

    private final AdminEndpoint endpoint;
    private final AdminSettings settings;
    private final AdminResponses responses;
    private final ContentAdminServices s;
    private final RandomGenerator random;
    private final Clock clock;

    public AdminContentActions(AdminEndpoint endpoint, AdminSettings settings, AdminResponses responses, ContentAdminServices services,
                               AdminRandom random, Clock clock) {
        this.endpoint = endpoint;
        this.settings = settings;
        this.responses = responses;
        this.s = services;
        this.random = random.generator();
        this.clock = clock;
    }

    /** 处理 B13 的动作；不是本批的动作（或内容服务未注册）时返回 null。 */
    ResponseEntity<String> handle(Ctx ctx, AdminPrincipal admin, SessionState session) {
        if (s == null) {
            return null;
        }
        PhpForm param = form(ctx.params());
        return switch (ctx.route().code()) {
            case "index/select" -> select(ctx, param);
            // 友情链接（旧 admin/controller/Link.php）
            case "link/index" -> linkList(ctx, param);
            case "link/info" -> ctx.post_() ? result(ctx, s.links().save(param)) : linkInfo(ctx);
            case "link/del" -> result(ctx, s.links().delete(param));
            case "link/batch" -> result(ctx, s.links().batch(param, settings.lang().get("unknown")));
            // 分类（旧 admin/controller/Type.php）
            case "type/index" -> typeList(ctx);
            case "type/info" -> ctx.post_() ? typeSave(ctx, session) : typeInfo(ctx, session);
            case "type/del" -> result(ctx, s.types().delete(param));
            case "type/field" -> result(ctx, s.types().field(param));
            case "type/batch" -> result(ctx, s.types().batch(param, settings.lang().get("unknown")));
            case "type/extend" -> {
                AdminResult r = s.types().extend(param);
                yield r == null ? responses.text("") : result(ctx, r);
            }
            case "type/move" -> result(ctx, s.types().move(param));
            // 专题（旧 admin/controller/Topic.php）
            case "topic/data" -> topicList(ctx, param);
            case "topic/info" -> ctx.post_() ? result(ctx, s.topics().save(form(ctx.post()))) : topicInfo(ctx);
            case "topic/del" -> result(ctx, s.topics().delete(param));
            case "topic/field" -> result(ctx, s.topics().field(param));
            // 演员（旧 admin/controller/Actor.php）
            case "actor/data" -> actorList(ctx, param);
            case "actor/info" -> ctx.post_() ? result(ctx, s.actors().save(form(ctx.post()))) : actorInfo(ctx);
            case "actor/del" -> result(ctx, s.actors().delete(param));
            case "actor/field" -> result(ctx, s.actors().field(param));
            // 角色（旧 admin/controller/Role.php）
            case "role/data" -> roleList(ctx, param);
            case "role/info" -> ctx.post_() ? result(ctx, s.roles().save(form(ctx.post()))) : roleInfo(ctx);
            case "role/del" -> result(ctx, s.roles().delete(param));
            case "role/field" -> result(ctx, s.roles().field(param));
            // 网址（旧 admin/controller/Website.php）
            case "website/data" -> websiteList(ctx, param);
            case "website/info" -> ctx.post_() ? result(ctx, s.websites().save(form(ctx.post()))) : websiteInfo(ctx);
            case "website/del" -> result(ctx, s.websites().delete(param));
            case "website/field" -> result(ctx, s.websites().field(param));
            case "website/batch" -> ctx.params().values().isEmpty() ? batchPage(ctx, "admin/website/title", true)
                    : progress(ctx, s.websites().batch(param, settings.lang()::get), "website/batch");
            case "website/updatetoday" -> today(s.websites().today(param));
            // 文章（旧 admin/controller/Art.php）
            case "art/data" -> artList(ctx, param);
            case "art/info" -> ctx.post_() ? result(ctx, s.arts().save(form(ctx.post()))) : artInfo(ctx);
            case "art/del" -> result(ctx, s.arts().delete(param));
            case "art/field" -> result(ctx, s.arts().field(param));
            case "art/batch" -> ctx.params().values().isEmpty() ? batchPage(ctx, "admin/art/title", false)
                    : progress(ctx, s.arts().batch(param, settings.lang()::get), "art/batch");
            case "art/updatetoday" -> today(s.arts().today(param));
            // 视频（旧 admin/controller/Vod.php）
            case "vod/data" -> vodList(ctx, param);
            case "vod/info" -> ctx.post_() ? result(ctx, s.vods().save(form(ctx.post()))) : vodInfo(ctx, false);
            case "vod/iplot" -> ctx.post_() ? result(ctx, s.vods().savePlot(form(ctx.post()))) : vodInfo(ctx, true);
            case "vod/del" -> result(ctx, s.vods().delete(param));
            case "vod/field" -> result(ctx, s.vods().field(param));
            case "vod/batch" -> ctx.params().values().isEmpty() ? vodBatchPage(ctx)
                    : progress(ctx, s.vods().batch(param, settings.lang()::get), "vod/batch");
            case "vod/updatetoday" -> today(s.vods().today(param));
            default -> null;
        };
    }

    // ------------------------------------------------------------------ 公共选择框（旧 Index::select）

    /**
     * 旧 Index::select：{@code tpl tab col ids url} 任一为 {@code empty()} 时"参数错误"；{@code ids} 为数组时以逗号连接；{@code refresh} 为空时
     * {@code yes}；{@code url} 按 {@code url()} 生成提交地址。模板只接受旧版的 7 个 select_* 模板（旧版把参数直接拼进模板路径，不存在时报错，
     * 22 文档 D-B13-6）。
     */
    private ResponseEntity<String> select(Ctx ctx, PhpForm p) {
        for (String k : List.of("tpl", "tab", "col", "ids", "url")) {
            if (p.empty(k)) {
                return responses.error(ctx.ajax(), settings.lang().get("param_err"));
            }
        }
        String tpl = p.text("tpl");
        if (tpl == null || !SELECT_TEMPLATES.contains(tpl) || p.isArray("tab") || p.isArray("col") || p.isArray("url")) {
            return responses.error(ctx.ajax(), settings.lang().get("param_err"));
        }
        String ids;
        if (p.isArray("ids")) {
            ids = String.join(",", p.array("ids").entries().values().stream()
                    .map(b -> new String(b, StandardCharsets.UTF_8)).toList());
        } else {
            ids = p.text("ids");
        }
        String refresh = p.empty("refresh") || p.isArray("refresh") ? "yes" : p.text("refresh");
        String tab = p.text("tab");
        int mid = switch (tab) {
            case "art" -> 2;
            case "actor" -> 8;
            case "website" -> 11;
            default -> 1;
        };
        String val = p.isArray("val") || p.text("val") == null ? "" : p.text("val");
        SelectView page = new SelectView(settings.lang(), tpl, ctx.url().build(p.text("url")), p.text("col"), ids, val, refresh,
                mid, tpl.equals("select_type") ? s.types().cacheTree() : List.of());
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesSelect(page));
    }

    // ------------------------------------------------------------------ 友情链接

    private ResponseEntity<String> linkList(Ctx ctx, PhpForm p) {
        AdminRowPage list = s.links().list(p.scalar("wd"), paging(ctx));
        LinkListView page = new LinkListView(head("admin/link/title"), settings.lang(), ctx.url(), list,
                searchBox(list), ctx.url().build("link/index", pagingVars(ctx.params(), list)));
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesLinkIndex(page));
    }

    private ResponseEntity<String> linkInfo(Ctx ctx) {
        AdminRow info = s.links().info(ctx.params().scalar("id")).orElse(EMPTY);
        LinkInfoView page = new LinkInfoView(head("admin/link/title"), settings.lang(), info);
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesLinkInfo(page));
    }

    // ------------------------------------------------------------------ 分类

    private ResponseEntity<String> typeList(Ctx ctx) {
        TypeListView page = new TypeListView(head("admin/type/title"), settings.lang(), ctx.url(), s.types().index(), links());
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesTypeIndex(page));
    }

    private ResponseEntity<String> typeInfo(Ctx ctx, SessionState session) {
        AdminRow info = s.types().info(ctx.params().scalar("id")).orElse(EMPTY);
        byte[] pidRaw = ctx.params().scalar("pid");
        AdminRow infop = s.types().info(pidRaw).orElse(EMPTY);
        String pid = pidRaw == null ? "" : new String(pidRaw, StandardCharsets.UTF_8);
        TypeInfoView page = new TypeInfoView(head("admin/type/title"), settings.lang(), ctx.url(), info, infop, pid,
                s.types().parents(), TypeAdminService.extendValues(info.has("type_id") ? info : null), issueToken(session));
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesTypeInfo(page));
    }

    /** 旧 Type::info 的 POST：{@code input('post.')} 先过令牌验证（缺失"非法请求"、不一致"请不要重复提交表单"），再保存。 */
    private ResponseEntity<String> typeSave(Ctx ctx, SessionState session) {
        String bad = checkToken(ctx.post(), session);
        if (bad != null) {
            return responses.error(ctx.ajax(), settings.lang().get(bad));
        }
        return result(ctx, s.types().save(form(ctx.post())));
    }

    // ------------------------------------------------------------------ 专题

    private ResponseEntity<String> topicList(Ctx ctx, PhpForm p) {
        AdminRowPage list = s.topics().list(p, paging(ctx));
        ContentListView page = listView(ctx, "admin/topic/title", list, "topic/data", List.of(), "topic_detail", Map.of());
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesTopicIndex(page));
    }

    private ResponseEntity<String> topicInfo(Ctx ctx) {
        AdminRow info = s.topics().info(ctx.params().scalar("id")).orElse(EMPTY);
        ContentFormView page = formView(ctx, "admin/topic/title", info, "topic_editor", List.of(), Map.of());
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesTopicInfo(page));
    }

    // ------------------------------------------------------------------ 演员、角色

    private ResponseEntity<String> actorList(Ctx ctx, PhpForm p) {
        AdminRowPage list = s.actors().list(p, paging(ctx));
        ContentListView page = listView(ctx, "admin/actor/title", list, "actor/data", s.types().cacheTree(), "actor_detail",
                Map.of());
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesActorIndex(page));
    }

    private ResponseEntity<String> actorInfo(Ctx ctx) {
        AdminRow info = s.actors().info(ctx.params().scalar("id")).orElse(EMPTY);
        ContentFormView page = formView(ctx, "admin/actor/title", info, "actor_editor", s.types().cacheTree(), Map.of());
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesActorInfo(page));
    }

    private ResponseEntity<String> roleList(Ctx ctx, PhpForm p) {
        AdminRowPage list = s.roles().list(p, paging(ctx));
        ContentListView page = listView(ctx, "admin/role/title", list, "role/data", List.of(), "role_detail", Map.of());
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesRoleIndex(page));
    }

    /** 旧 Role::info：没有这一行时 {@code $info['role_rid'] = input('rid')}；所属视频按 {@code role_rid} 取（显示片名与分类名）。 */
    private ResponseEntity<String> roleInfo(Ctx ctx) {
        AdminRow info = s.roles().info(ctx.params().scalar("id")).orElse(null);
        if (info == null) {
            Map<String, String> row = new LinkedHashMap<>();
            AdminParams.Value rid = ctx.params().values().get("rid");
            if (rid != null && !rid.array()) {
                row.put("role_rid", new String(rid.scalar(), StandardCharsets.UTF_8));
            }
            info = new AdminRow(row);
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        AdminRow vod = s.roles().video(info.has("role_rid") ? info.s("role_rid") : null).orElse(null);
        if (vod != null) {
            extra.put("vodName", vod.s("vod_name"));
            s.types().info(vod.s("type_id").getBytes(StandardCharsets.UTF_8))
                    .ifPresent(t -> extra.put("vodTypeName", t.s("type_name")));
        }
        ContentFormView page = formView(ctx, "admin/role/title", info, "role_editor", List.of(), extra);
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesRoleInfo(page));
    }

    // ------------------------------------------------------------------ 网址、文章

    private ResponseEntity<String> websiteList(Ctx ctx, PhpForm p) {
        AdminRowPage list = s.websites().list(p, paging(ctx));
        ContentListView page = listView(ctx, "admin/website/title", list, "website/data", s.types().cacheTree(),
                "website_detail", Map.of());
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesWebsiteIndex(page));
    }

    private ResponseEntity<String> websiteInfo(Ctx ctx) {
        AdminRow info = s.websites().info(ctx.params().scalar("id")).orElse(EMPTY);
        ContentFormView page = formView(ctx, "admin/website/title", info, "website_editor", s.types().cacheTree(), Map.of());
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesWebsiteInfo(page));
    }

    private ResponseEntity<String> artList(Ctx ctx, PhpForm p) {
        AdminRowPage list = s.arts().list(p, paging(ctx));
        ContentListView page = listView(ctx, "admin/art/title", list, "art/data", s.types().cacheTree(), "art_detail",
                Map.of());
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesArtIndex(page));
    }

    private ResponseEntity<String> artInfo(Ctx ctx) {
        AdminRow info = s.arts().info(ctx.params().scalar("id")).orElse(EMPTY);
        ContentFormView page = formView(ctx, "admin/art/title", info, "art_editor", s.types().cacheTree(), Map.of());
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesArtInfo(page));
    }

    // ------------------------------------------------------------------ 视频

    private ResponseEntity<String> vodList(Ctx ctx, PhpForm p) {
        AdminRowPage list = s.vods().list(p, paging(ctx));
        ContentListView page = listView(ctx, "admin/vod/title", list, "vod/data", s.types().cacheTree(), "vod_detail",
                vodLists());
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesVodIndex(page));
    }

    private ResponseEntity<String> vodBatchPage(Ctx ctx) {
        AdminRowPage none = new AdminRowPage(List.of(), 0, 1, 0, null);
        ContentListView page = listView(ctx, "admin/vod/title", none, "vod/batch", s.types().cacheTree(), "vod_detail",
                vodLists());
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesVodBatch(page));
    }

    /**
     * 旧 Vod::info / Vod::iplot 的页面：播放组、下载组按 {@code mac_play_list}（只列出配置里启用的播放器/下载器的组，后台不按播放器排序），
     * 分集剧情按 {@code mac_plot_list}。
     */
    private ResponseEntity<String> vodInfo(Ctx ctx, boolean plot) {
        AdminRow info = s.vods().info(ctx.params().scalar("id")).orElse(EMPTY);
        if (plot && !info.has("vod_id")) {
            // 旧版没有这条视频时取 null 的下标报错（500）；新版"获取失败"（22 文档 D-B13-9）
            return responses.error(ctx.ajax(), settings.lang().get("obtain_err"));
        }
        Map<String, Object> extra = new LinkedHashMap<>(vodLists());
        Map<String, Map<String, String>> servers = s.players().servers();
        extra.put("playList", info.has("vod_id") ? LegacyMultiValue.playSources(info.s("vod_play_from"), info.s("vod_play_url"),
                info.s("vod_play_server"), info.s("vod_play_note"), s.players().players(), servers, false) : List.of());
        extra.put("downList", info.has("vod_id") ? LegacyMultiValue.playSources(info.s("vod_down_from"), info.s("vod_down_url"),
                info.s("vod_down_server"), info.s("vod_down_note"), s.players().downers(), servers, false) : List.of());
        extra.put("plotList", info.has("vod_id") ? LegacyMultiValue.plots(info.s("vod_plot_name"), info.s("vod_plot_detail"))
                : List.of());
        if (plot) {
            ContentFormView page = formView(ctx, "admin/vod/plot/title", info, "vod_editor", List.of(), extra);
            return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesVodIplot(page));
        }
        ContentFormView page = formView(ctx, "admin/vod/title", info, "vod_editor", s.types().cacheTree(), extra);
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesVodInfo(page));
    }

    /** 视频页共用的选项：播放器、下载器、服务器组（旧 {@code mac_multisort} 后的列表），周期、地区、语言（旧 {@code explode(',', 配置)}）。 */
    private Map<String, Object> vodLists() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("playerList", AdminPlayers.sorted(s.players().players()));
        m.put("downerList", AdminPlayers.sorted(s.players().downers()));
        m.put("serverList", AdminPlayers.sorted(s.players().servers()));
        m.put("weekdays", List.of(s.config().text("app", "vod_extend_weekday").split(",", -1)));
        m.put("areas", List.of(s.config().text("app", "vod_extend_area").split(",", -1)));
        m.put("langs", List.of(s.config().text("app", "vod_extend_lang").split(",", -1)));
        return m;
    }

    /** 批量处理页（没有任何参数时，旧 batch() 显示筛选表单）。 */
    private ResponseEntity<String> batchPage(Ctx ctx, String titleKey, boolean website) {
        AdminRowPage none = new AdminRowPage(List.of(), 0, 1, 0, null);
        ContentListView page = listView(ctx, titleKey, none, website ? "website/batch" : "art/batch", s.types().cacheTree(),
                website ? "website_detail" : "art_detail", Map.of());
        return website ? responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesWebsiteBatch(page))
                : responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesArtBatch(page));
    }

    /** 旧 updateToday：{@code json(['code'=>1,'msg'=>lang('obtain_ok'),'data'=>join(',',$ids)])}。 */
    private ResponseEntity<String> today(String ids) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", 1);
        m.put("msg", settings.lang().get("obtain_ok"));
        m.put("data", ids);
        return responses.rawJson(m);
    }

    /**
     * 逐页推进的输出（旧 {@code mac_echo}：每段后接 {@code <br>}；{@code mac_jump}：3 秒后跳转。22 文档第 8.4 节）：旧版原样输出的数据（名称等）
     * 转义后输出，跳转的内联脚本改为 progress.js 的 data-* 属性。中途 {@code error()} 时在已输出的内容后面接着输出错误页（Ajax 时为 JSON）。
     */
    private ResponseEntity<String> progress(Ctx ctx, AdminProgress p, String action) {
        StringBuilder sb = new StringBuilder();
        for (AdminProgress.Line line : p.lines()) {
            for (AdminProgress.Part part : line.parts()) {
                sb.append(part.markup() ? part.value() : escape(part.value()));
            }
            sb.append("<br>");
        }
        if (p.error() != null) {
            String msg = p.error().render(settings.lang()::get);
            sb.append(ctx.ajax() ? AdminResponses.legacyJson(0, msg, "") : responses.jumpHtml(false, msg, JumpPage.BACK));
            return responses.echoed(sb.toString());
        }
        if (p.jumpQuery() != null) {
            String url = ctx.url().build(action);
            if (!p.jumpQuery().isEmpty()) {
                url = url + "?" + LegacyAdminUrl.httpBuildQuery(p.jumpQuery());
            }
            String u = escape(url);
            sb.append("<script src=\"").append(escape(endpoint.sitePath())).append("/admin-assets/progress.js\" data-url=\"")
                    .append(u).append("\" data-seconds=\"3\"></script><span>").append(escape(settings.lang().get("pause")))
                    .append('3').append(escape(settings.lang().get("continue_in_second"))).append("  >>>  </span><a href=\"")
                    .append(u).append("\" >").append(escape(settings.lang().get("browser_jump"))).append("</a><br>");
        }
        return responses.echoed(sb.toString());
    }

    private static String escape(String s) {
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

    // ------------------------------------------------------------------ 公共

    private ContentListView listView(Ctx ctx, String titleKey, AdminRowPage list, String pagingAction,
                                     List<com.maccms.backend.content.application.view.AdminTypeNode> types, String detailKey,
                                     Map<String, Object> extra) {
        long detail = com.maccms.backend.content.application.PhpIntval.of(s.config().text("view", detailKey));
        return new ContentListView(head(titleKey), settings.lang(), ctx.url(), list, scalars(ctx.params()), searchBox(list),
                ctx.url().build(pagingAction, pagingVars(ctx.params(), list)), links(), types, detail,
                ContentListView.todayOf(clock), extra);
    }

    private ContentFormView formView(Ctx ctx, String titleKey, AdminRow info, String editorFlag,
                                     List<com.maccms.backend.content.application.view.AdminTypeNode> types,
                                     Map<String, Object> extra) {
        AdminConfig c = s.config();
        ImgConfig img = new ImgConfig(c.text("upload", "protocol"), c.text("upload", "mode"), c.text("upload", "remoteurl"),
                c.text("upload", "img_key"), c.text("upload", "img_api"), endpoint.sitePath());
        return new ContentFormView(head(titleKey), settings.lang(), ctx.url(), info, img, editorFlag, types, extra);
    }

    /** 前台链接：按旧配置 {@code rewrite.*}、{@code app.pathinfo_depr} 的地址形态（不支持的组合按仓库默认形态，并告警）。 */
    private FrontLinks links() {
        AdminConfig c = s.config();
        boolean rewrite = com.maccms.backend.content.application.PhpIntval.of(c.text("rewrite", "status")) == 1;
        boolean hide = com.maccms.backend.content.application.PhpIntval.of(c.text("rewrite", "suffix_hide")) == 1;
        boolean route = com.maccms.backend.content.application.PhpIntval.of(c.text("rewrite", "route_status")) == 1;
        String deprText = c.text("app", "pathinfo_depr");
        char depr = deprText.isEmpty() ? '/' : deprText.charAt(0);
        LegacyUrlStyle style;
        try {
            style = new LegacyUrlStyle(rewrite, hide, route, depr);
        } catch (IllegalArgumentException e) {
            log.warn("legacy rewrite settings not supported for admin front links; repository defaults used");
            style = LegacyUrlStyle.REPO_DEFAULT;
        }
        return new FrontLinks(new LegacyUrlBuilder(style), endpoint.sitePath());
    }

    private AdminHead head(String titleKey) {
        return new AdminHead(settings.lang().get(titleKey), endpoint.sitePath(), endpoint.root(), settings.versionCode());
    }

    private AdminPaging paging(Ctx ctx) {
        return AdminPaging.of(ctx.params().scalar("page"), ctx.params().scalar("limit"), settings.pageSize());
    }

    /** 旧 {@code success/error($res['msg'])}；带 data 时（{@code success('ok', null, $data)}）原样输出 data。 */
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

    /** 新表单令牌（旧 {@code {$Request.token}}）：每次打开表单都覆盖会话里的令牌（与管理员表单共用，同旧版的 __token__）。 */
    private String issueToken(SessionState session) {
        byte[] b = new byte[16];
        random.nextBytes(b);
        String token = HexFormat.of().formatHex(b);
        session.put(AdminSessions.TOKEN, token);
        return token;
    }

    /** 旧 Token 验证器：缺失或为空 → illegal_request；与会话里的不同或已用过 → token_err（令牌随之作废）；通过时也作废。 */
    private static String checkToken(AdminParams post, SessionState session) {
        byte[] posted = post.scalar("__token__");
        if (posted == null || posted.length == 0) {
            return "illegal_request";
        }
        String expected = session.take(AdminSessions.TOKEN, String.class).orElse(null);
        if (expected == null || !expected.equals(new String(posted, StandardCharsets.ISO_8859_1))) {
            return "token_err";
        }
        return null;
    }

    /** 列表搜索框的值：旧模板 {@code {$param['wd']|mac_filter_xss}}，浏览器看到的就是处理后的关键字（新版由模板转义输出）。 */
    static String searchBox(AdminRowPage list) {
        return list.wd() == null ? "" : list.wd();
    }

    /** {@code input()} 的标量参数（UTF-8 文字）。 */
    static Map<String, String> scalars(AdminParams params) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, AdminParams.Value> e : params.values().entrySet()) {
            if (!e.getValue().array()) {
                out.put(e.getKey(), new String(e.getValue().scalar(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    /**
     * 分页地址的参数（旧 {@code $param}：{@code input()} 的全部标量参数按出现顺序，{@code wd} 换成处理后的值，{@code page}、{@code limit}
     * 换成占位；没有时依次追加在末尾）。
     */
    static Map<String, String> pagingVars(AdminParams params, AdminRowPage list) {
        Map<String, String> vars = scalars(params);
        if (list.wd() != null) {
            vars.put("wd", list.wd());
        }
        vars.put("page", "{page}");
        vars.put("limit", "{limit}");
        return vars;
    }

    /** {@code input()} / {@code input('post.')} 的参数表（内容模块的 {@link PhpForm}）。 */
    static PhpForm form(AdminParams params) {
        PhpForm f = PhpForm.empty();
        for (Map.Entry<String, AdminParams.Value> e : params.values().entrySet()) {
            AdminParams.Value v = e.getValue();
            if (v.array()) {
                f.putArray(e.getKey(), new PhpForm.Arr(v.keys(), v.items()));
            } else {
                f.put(e.getKey(), v.scalar());
            }
        }
        return f;
    }
}
