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
import com.maccms.backend.admin.web.view.content.UploadIndexView;
import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.content.application.AdminConfig;
import com.maccms.backend.content.application.AdminPaging;
import com.maccms.backend.admin.application.LegacyAdminUrl;
import com.maccms.backend.admin.web.view.JumpPage;
import com.maccms.backend.admin.web.view.content.ImgConfig;
import com.maccms.backend.comment.application.CommentAdminService;
import com.maccms.backend.content.application.AdminPlayers;
import com.maccms.backend.content.application.AdminUploadService;
import com.maccms.backend.content.application.view.AnnexDirectory;
import com.maccms.backend.content.application.view.UploadReply;
import com.maccms.backend.admin.web.view.content.AnnexFileView;
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
import java.util.Optional;
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
    private final CommentAdminService comments;
    private final RandomGenerator random;
    private final Clock clock;

    public AdminContentActions(AdminEndpoint endpoint, AdminSettings settings, AdminResponses responses, ContentAdminServices services,
                               CommentAdminService comments, AdminRandom random, Clock clock) {
        this.endpoint = endpoint;
        this.settings = settings;
        this.responses = responses;
        this.s = services;
        this.comments = comments;
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
            // 评论、留言（旧 admin/controller/Comment.php、Gbook.php；info 的 POST 用 input()）
            case "comment/data" -> commentList(ctx, param);
            case "comment/info" -> ctx.post_() ? result(ctx, comments.saveComment(param)) : commentInfo(ctx);
            case "comment/del" -> result(ctx, comments.deleteComments(param));
            case "comment/field" -> result(ctx, comments.commentField(param));
            case "gbook/data" -> gbookList(ctx, param);
            case "gbook/info" -> ctx.post_() ? result(ctx, comments.saveGbook(param)) : gbookInfo(ctx);
            case "gbook/del" -> result(ctx, comments.deleteGbooks(param));
            case "gbook/field" -> result(ctx, comments.gbookField(param));
            // 附件、图片、上传（旧 admin/controller/Annex.php、Images.php、Upload.php）
            case "annex/data" -> annexList(ctx, param);
            case "annex/file" -> annexFiles(ctx);
            case "annex/info" -> ctx.post_() ? result(ctx, s.annexes().save(form(ctx.post()))) : annexInfo(ctx);
            case "annex/del" -> result(ctx, s.annexes().delete(param));
            case "annex/check" -> progress(ctx, s.annexes().check(param, settings.lang()::get), "annex/check");
            case "annex/init" -> {
                AdminProgress pr = s.annexes().init(param, settings.lang()::get);
                yield pr == null ? simplePage(ctx, null, Map.of(), "init") : progress(ctx, pr, "annex/init");
            }
            case "images/data" -> responses.text("");
            case "images/opt" -> simplePage(ctx, null, imagesOpt(ctx), "opt");
            case "images/del" -> imagesDel(ctx, param);
            case "images/sync" -> imagesSync(ctx, param);
            case "upload/upload" -> upload(ctx);
            case "upload/index" -> uploadIndex(ctx);
            case "upload/test" -> uploadTest();
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

    // ------------------------------------------------------------------ 评论、留言

    /**
     * 旧 Comment::data：每条评论另取所属内容（旧 {@code Comment::listData} 按模块取视频、文章、专题、演员、角色、网址的整行，取不到时为空，
     * 页面显示"删除数据"）；旧版另取的回复与头像页面不用，不再读取。
     */
    private ResponseEntity<String> commentList(Ctx ctx, PhpForm p) {
        AdminRowPage list = comments.comments(p, paging(ctx));
        Map<String, AdminRow> related = new LinkedHashMap<>();
        for (AdminRow r : list.rows()) {
            byte[] rid = r.s("comment_rid").getBytes(StandardCharsets.UTF_8);
            Optional<AdminRow> data = switch (r.s("comment_mid")) {
                case "1" -> s.vods().info(rid);
                case "2" -> s.arts().info(rid);
                case "3" -> s.topics().info(rid);
                case "8" -> s.actors().info(rid);
                case "9" -> s.roles().info(rid);
                case "11" -> s.websites().info(rid);
                default -> Optional.empty();
            };
            data.ifPresent(d -> related.put(r.s("comment_id"), d));
        }
        ContentListView page = listView(ctx, "admin/comment/title", list, "comment/data", List.of(), "",
                Map.of("related", related));
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesCommentIndex(page));
    }

    private ResponseEntity<String> commentInfo(Ctx ctx) {
        AdminRow info = comments.comment(ctx.params().scalar("id")).orElse(EMPTY);
        ContentFormView page = formView(ctx, "admin/comment/title", info, "", List.of(), Map.of());
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesCommentInfo(page));
    }

    private ResponseEntity<String> gbookList(Ctx ctx, PhpForm p) {
        AdminRowPage list = comments.gbooks(p, paging(ctx));
        ContentListView page = listView(ctx, "admin/gbook/title", list, "gbook/data", List.of(), "", Map.of());
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesGbookIndex(page));
    }

    private ResponseEntity<String> gbookInfo(Ctx ctx) {
        AdminRow info = comments.gbook(ctx.params().scalar("id")).orElse(EMPTY);
        ContentFormView page = formView(ctx, "admin/gbook/title", info, "", List.of(), Map.of());
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesGbookInfo(page));
    }

    // ------------------------------------------------------------------ 附件、图片、上传

    private ResponseEntity<String> annexList(Ctx ctx, PhpForm p) {
        AdminRowPage list = s.annexes().list(p, paging(ctx));
        ContentListView page = listView(ctx, "admin/annex/title", list, "annex/data", List.of(), "",
                Map.of("macPath", s.config().text("site", "install_dir")));
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesAnnexIndex(page));
    }

    /** 旧 Annex::file：路径含 {@code ..@} 时"非法请求"。 */
    private ResponseEntity<String> annexFiles(Ctx ctx) {
        byte[] raw = ctx.params().scalar("path");
        AnnexDirectory dir = s.annexes().files(raw == null ? null : new String(raw, StandardCharsets.UTF_8));
        if (dir == null) {
            return responses.error(ctx.ajax(), settings.lang().get("illegal_request"));
        }
        AnnexFileView page = new AnnexFileView(head("admin/annex/title"), settings.lang(), ctx.url(), dir,
                ContentListView.todayOf(clock));
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesAnnexFile(page));
    }

    private ResponseEntity<String> annexInfo(Ctx ctx) {
        AdminRow info = s.annexes().info(ctx.params().scalar("id")).orElse(EMPTY);
        ContentFormView page = formView(ctx, "admin/annex/title", info, "actor_editor", List.of(), Map.of());
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesAnnexInfo(page));
    }

    /** 只有页面本身的表单页（附件初始化、图片同步选项）。 */
    private ResponseEntity<String> simplePage(Ctx ctx, String titleKey, Map<String, Object> extra, String which) {
        ContentFormView page = formView(ctx, titleKey, EMPTY, "", List.of(), extra);
        return which.equals("init") ? responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesAnnexInit(page))
                : responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesImagesOpt(page));
    }

    /** 旧 Images::opt：{@code tab} 原样写进隐藏字段，日期为今天。 */
    private Map<String, Object> imagesOpt(Ctx ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        byte[] tab = ctx.params().scalar("tab");
        m.put("tab", tab == null ? "" : new String(tab, StandardCharsets.UTF_8));
        m.put("today", ContentListView.todayOf(clock));
        return m;
    }

    /**
     * 旧 Images::del：{@code ids[]} 每项（反斜杠换成斜杠）以 {@code ./upload} 开头且只有一个 {@code ./} 时删除这个文件，总是"删除成功"。
     * {@code ids} 不是数组时旧版 foreach 报错（500），新版"参数错误"。
     */
    private ResponseEntity<String> imagesDel(Ctx ctx, PhpForm p) {
        if (!p.empty("ids")) {
            if (!p.isArray("ids")) {
                return responses.error(ctx.ajax(), settings.lang().get("param_err"));
            }
            for (byte[] v : p.array("ids").entries().values()) {
                String a = new String(v, StandardCharsets.UTF_8).replace('\\', '/');
                if (a.startsWith("./upload") && a.split("\\./", -1).length <= 2) {
                    s.uploads().deleteFile(a.substring(2));
                }
            }
        }
        return responses.success(ctx.ajax(), settings.lang().get("del_ok"), AdminController.successUrl(ctx));
    }

    /**
     * 旧 Images::sync：把远程图片下载到本地（E02）。按路线图只做本地版本、不发起外部下载，库内的图片地址保持不变（相当于关闭同步）；
     * {@code tab} 不在旧版的六类里时"参数错误"，否则输出固定提示（22 文档第 10 节）。
     */
    private ResponseEntity<String> imagesSync(Ctx ctx, PhpForm p) {
        String tab = p.isArray("tab") ? null : p.text("tab");
        if (tab == null || !List.of("vod", "art", "topic", "actor", "role", "website").contains(tab)) {
            return responses.error(ctx.ajax(), settings.lang().get("param_err"));
        }
        AdminProgress pr = new AdminProgress(List.of(AdminProgress.Line.of(AdminProgress.Part.markup(
                "<style type=\"text/css\">body{font-size:12px;color: #333333;line-height:21px;}span{font-weight:bold;color:#FF0000}</style>")),
                AdminProgress.Line.of(AdminProgress.Part.text(SYNC_DISABLED))), null, null);
        return progress(ctx, pr, "images/sync");
    }

    private static final String SYNC_DISABLED = "远程图片同步未开放：新系统只存本地、不下载外部图片，库内的图片地址保持不变。";

    /** 旧 Upload::upload：文件取表单里名为 {@code input}（默认 file）的文件；后台请求返回 JSON，编辑器请求按编辑器格式。 */
    /** 旧 Upload::index：上传弹窗页，{@code path}、{@code id} 写进隐藏字段（缺失时为空）。 */
    private ResponseEntity<String> uploadIndex(Ctx ctx) {
        byte[] path = ctx.params().scalar("path");
        byte[] id = ctx.params().scalar("id");
        UploadIndexView page = new UploadIndexView(settings.lang(), ctx.url().build("upload/upload"),
                path == null ? "" : new String(path, StandardCharsets.UTF_8),
                id == null ? "" : new String(id, StandardCharsets.UTF_8));
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesUploadIndex(page));
    }

    /**
     * 旧 Upload::test：在系统临时目录建一个 Tux 前缀的临时文件，输出语言包 {@code admin/upload/test_write_ok}（旧语言包里这个键写了两次，
     * 后一条"写入失败，请检查临时文件目录权限"生效）+ "：" + 文件路径；建不出来时输出 {@code admin/upload/test_write_err} + "：" + 临时目录。
     * 旧版把临时文件留在目录里，新版输出后删除（22 文档第 10 节）。
     */
    private ResponseEntity<String> uploadTest() {
        String tmp = System.getProperty("java.io.tmpdir");
        try {
            java.nio.file.Path f = java.nio.file.Files.createTempFile(java.nio.file.Path.of(tmp), "Tux", ".tmp");
            java.nio.file.Files.deleteIfExists(f);
            return responses.text(escape(settings.lang().get("admin/upload/test_write_ok") + "：" + f));
        } catch (java.io.IOException | RuntimeException e) {
            return responses.text(escape(settings.lang().get("admin/upload/test_write_err") + "：" + tmp));
        }
    }

    private ResponseEntity<String> upload(Ctx ctx) {
        UploadReply r = s.uploads().upload(uploadRequest(ctx), settings.lang()::get);
        return r.json() ? responses.jsonBody(r.body()) : responses.echoed(r.body());
    }

    /**
     * 上传请求：{@code input()} 的标量参数与表单里名为 {@code input}（默认 file）的文件。B13 的上传与 B14 的会员头像上传（23 文档第 7.7 节）
     * 共用。
     */
    static AdminUploadService.Upload uploadRequest(Ctx ctx) {
        Map<String, String> params = scalars(ctx.params());
        String input = AdminUploadService.inputName(params);
        byte[] file = null;
        String name = null;
        try {
            if (ctx.request().getContentType() != null
                    && ctx.request().getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("multipart/")) {
                jakarta.servlet.http.Part part = ctx.request().getPart(input);
                if (part != null && part.getSubmittedFileName() != null) {
                    try (java.io.InputStream in = part.getInputStream()) {
                        file = in.readAllBytes();
                    }
                    name = part.getSubmittedFileName();
                }
            }
        } catch (java.io.IOException | jakarta.servlet.ServletException | RuntimeException e) {
            // 超出大小等解析失败：同旧版上传的文件被丢弃（"未找到上传的文件"）
            file = null;
            name = null;
        }
        return new AdminUploadService.Upload(params, file, name);
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
        // 旧版没有给页面标题的动作（附件初始化页、图片同步选项页）标题为空
        return new AdminHead(titleKey == null ? "" : settings.lang().get(titleKey), endpoint.sitePath(), endpoint.root(),
                settings.versionCode());
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
