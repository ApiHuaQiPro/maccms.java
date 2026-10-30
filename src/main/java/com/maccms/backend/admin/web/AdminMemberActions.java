package com.maccms.backend.admin.web;

import com.maccms.backend.admin.application.AdminSettings;
import com.maccms.backend.admin.web.AdminController.Ctx;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.member.CardInfoView;
import com.maccms.backend.admin.web.view.member.GroupInfoView;
import com.maccms.backend.admin.web.view.member.MemberListView;
import com.maccms.backend.admin.web.view.member.RewardView;
import com.maccms.backend.admin.web.view.member.UserInfoView;
import com.maccms.backend.content.application.AdminPaging;
import com.maccms.backend.content.application.AdminUploadService;
import com.maccms.backend.content.application.ContentAdminServices;
import com.maccms.backend.content.application.PhpForm;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import com.maccms.backend.content.application.view.AdminTypeNode;
import com.maccms.backend.content.application.view.UploadReply;
import com.maccms.backend.member.application.CardAdminService;
import com.maccms.backend.member.application.MemberAdminServices;
import com.maccms.backend.member.application.view.MemberRewardStats;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * B14 后台会员与财务管理的动作（docs/architecture/23-b14-admin-member.md）：由 {@link AdminController} 在登录与 {@code check_auth} 之后、
 * B13 的动作之后分派。auth.php 里归 B14 的 20 个权限码，加旧控制器里有、auth.php 没列的 {@code user/reward}、{@code visit/index}、
 * {@code visit/del}。各动作取参数的方式与旧控制器相同（{@code input()} 为合并后的参数，{@code input('post.')} 只取 POST）。
 */
public class AdminMemberActions {

    private static final ZoneId PRC = ZoneId.of("Asia/Shanghai");
    private static final AdminRow EMPTY = new AdminRow(Map.of());

    private final AdminEndpoint endpoint;
    private final AdminSettings settings;
    private final AdminResponses responses;
    private final MemberAdminServices s;
    private final ContentAdminServices content;
    private final Clock clock;

    public AdminMemberActions(AdminEndpoint endpoint, AdminSettings settings, AdminResponses responses, MemberAdminServices services,
                              ContentAdminServices content, Clock clock) {
        this.endpoint = endpoint;
        this.settings = settings;
        this.responses = responses;
        this.s = services;
        this.content = content;
        this.clock = clock;
    }

    /** 处理 B14 的动作；不是本批的动作（或会员服务未注册）时返回 null。 */
    ResponseEntity<String> handle(Ctx ctx) {
        if (s == null) {
            return null;
        }
        PhpForm param = AdminContentActions.form(ctx.params());
        return switch (ctx.route().code()) {
            // 会员（旧 admin/controller/User.php）
            case "user/data" -> userList(ctx, param);
            case "user/info" -> ctx.post_() ? result(ctx, s.users().save(AdminContentActions.form(ctx.post()))) : userInfo(ctx);
            case "user/del" -> result(ctx, s.users().delete(param));
            case "user/field" -> result(ctx, s.users().field(param));
            case "user/reward" -> reward(ctx, param);
            // 会员组（旧 admin/controller/Group.php）
            case "group/index" -> groupList(ctx, param);
            case "group/info" -> ctx.post_()
                    ? result(ctx, s.groups().save(AdminContentActions.form(ctx.post()), ctx.post().nested("group_popedom")))
                    : groupInfo(ctx);
            case "group/del" -> result(ctx, s.groups().delete(param));
            case "group/field" -> result(ctx, s.groups().field(param));
            // 充值卡（旧 admin/controller/Card.php）
            case "card/index" -> cardList(ctx, param);
            case "card/info" -> ctx.post_() ? result(ctx, s.cards().generate(AdminContentActions.form(ctx.post())))
                    : responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesCardInfo(new CardInfoView(head(null), settings.lang())));
            case "card/del" -> result(ctx, s.cards().delete(param));
            // 订单、提现（旧 admin/controller/Order.php、Cash.php）
            case "order/index" -> list(ctx, param, "admin/order/title", "order/index", s.finance().orders(param, paging(ctx)), "order");
            case "order/del" -> result(ctx, s.finance().deleteOrders(param));
            case "cash/index" -> list(ctx, param, "admin/cash/title", "cash/index", s.finance().cashes(param, paging(ctx)), "cash");
            case "cash/del" -> result(ctx, s.finance().deleteCashes(param));
            case "cash/audit" -> result(ctx, s.finance().audit(param));
            // 积分日志、访问日志、推广访问（旧 admin/controller/Plog.php、Ulog.php、Visit.php）
            case "plog/index" -> list(ctx, param, "admin/plog/title", "plog/index", s.logs().plogs(param, paging(ctx)), "plog");
            case "plog/del" -> result(ctx, s.logs().deletePlogs(param));
            case "ulog/index" -> list(ctx, param, "admin/ulog/title", "ulog/index", s.logs().ulogs(param, paging(ctx)), "ulog");
            case "ulog/del" -> result(ctx, s.logs().deleteUlogs(param));
            case "visit/index" -> list(ctx, param, "admin/visit/title", "visit/index", s.logs().visits(param, paging(ctx)), "visit");
            case "visit/del" -> result(ctx, s.logs().deleteVisits(param));
            default -> null;
        };
    }

    /**
     * 会员表单的"上传图片"（23 文档第 7.7 节）：{@code upload/upload} 带 {@code flag=user}、不带编辑器 {@code from} 时由会员服务写头像；
     * 其他上传返回 null，仍由 B13 的上传处理。在登录、同源与 {@code upload/upload} 权限检查之后调用（{@link AdminController}）。
     */
    ResponseEntity<String> portraitUpload(Ctx ctx) {
        if (s == null || !ctx.route().code().equals("upload/upload")) {
            return null;
        }
        AdminUploadService.Upload req = AdminContentActions.uploadRequest(ctx);
        Map<String, String> p = req.params();
        String from = p.get("from");
        if (!"user".equals(p.get("flag")) || !(from == null || from.isEmpty() || from.equals("0"))) {
            return null;
        }
        UploadReply r = s.portraits().upload(p, req.file(), req.fileName(), settings.lang()::get);
        return responses.jsonBody(r.body());
    }

    // ------------------------------------------------------------------ 会员

    private ResponseEntity<String> userList(Ctx ctx, PhpForm p) {
        AdminRowPage list = s.users().list(p, paging(ctx));
        MemberListView page = view(ctx, "admin/user/title", list, ctx.url().build("user/data", pagingVars(ctx, list)));
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesUserIndex(page));
    }

    private ResponseEntity<String> userInfo(Ctx ctx) {
        AdminRow info = s.users().info(ctx.params().scalar("id")).orElse(EMPTY);
        String upload = ctx.url().build("upload/upload") + "?flag=user&user_id=" + info.s("user_id");
        UserInfoView page = new UserInfoView(head("admin/user/title"), settings.lang(), info, s.users().groups(), upload);
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesUserInfo(page));
    }

    private ResponseEntity<String> reward(Ctx ctx, PhpForm p) {
        MemberRewardStats stats = s.users().reward(p, paging(ctx));
        Map<String, String> vars = pagingVars(ctx, stats.list());
        vars.put("uid", Long.toString(stats.uid()));
        MemberListView page = view(ctx, "admin/user/title", stats.list(), ctx.url().build("user/reward", vars));
        RewardView reward = new RewardView(stats);
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesUserReward(page, reward));
    }

    // ------------------------------------------------------------------ 会员组

    private ResponseEntity<String> groupList(Ctx ctx, PhpForm p) {
        List<AdminRow> rows = s.groups().list(p);
        AdminRowPage list = new AdminRowPage(rows, rows.size(), 1, Math.max(1, rows.size()), null);
        MemberListView page = view(ctx, "admin/group/title", list, "");
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesGroupIndex(page));
    }

    private ResponseEntity<String> groupInfo(Ctx ctx) {
        AdminRow info = s.groups().info(ctx.params().scalar("id")).orElse(EMPTY);
        List<AdminTypeNode> types = content == null ? List.of() : content.types().cacheTree();
        GroupInfoView page = new GroupInfoView(head("admin/group/title"), settings.lang(), info, types);
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesGroupInfo(page));
    }

    // ------------------------------------------------------------------ 充值卡

    /** 列表；{@code export == 1} 时输出 CSV（旧 Card.php:40-63：表头一行语言包，每张卡 {@code ="卡号",="卡密",添加时间}）。 */
    private ResponseEntity<String> cardList(Ctx ctx, PhpForm p) {
        AdminRowPage list = s.cards().list(p, paging(ctx));
        if (CardAdminService.export(p)) {
            StringBuilder sb = new StringBuilder(settings.lang().get("admin/card/import_tip")).append('\n');
            for (AdminRow r : list.rows()) {
                sb.append("=\"").append(r.s("card_no")).append("\",=\"").append(r.s("card_pwd")).append("\",")
                        .append(MemberListView.dateTime(r.n("card_add_time"))).append('\n');
            }
            String date = LocalDate.now(clock.withZone(PRC)).toString();
            return responses.download(sb.toString(), "card_" + date + ".csv");
        }
        return list(ctx, p, "admin/card/title", "card/index", list, "card");
    }

    // ------------------------------------------------------------------ 公共

    /** 订单、提现、积分日志、访问日志、推广访问与卡密的列表页。 */
    private ResponseEntity<String> list(Ctx ctx, PhpForm p, String titleKey, String action, AdminRowPage list, String page) {
        MemberListView view = view(ctx, titleKey, list, ctx.url().build(action, pagingVars(ctx, list)));
        return switch (page) {
            case "card" -> responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesCardIndex(view));
            case "order" -> responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesOrderIndex(view));
            case "cash" -> responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesCashIndex(view));
            case "plog" -> responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesPlogIndex(view));
            case "ulog" -> responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesUlogIndex(view));
            default -> responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesVisitIndex(view));
        };
    }

    private MemberListView view(Ctx ctx, String titleKey, AdminRowPage list, String curUrl) {
        Map<String, String> param = AdminContentActions.scalars(ctx.params());
        // 搜索框：旧 {$param['wd']|mac_filter_xss}；会员、会员组、卡密、订单、提现、推广会员的控制器先把 wd 换成
        // htmlspecialchars(urldecode(wd))，积分日志、访问日志、推广访问不换
        String wd = list.wd() != null ? list.wd() : param.get("wd");
        List<AdminRow> groups = s.users().groups();
        Map<Long, String> names = new LinkedHashMap<>();
        for (AdminRow g : groups) {
            names.put(g.n("group_id"), g.s("group_name"));
        }
        return new MemberListView(head(titleKey), settings.lang(), ctx.url(), list, param, MemberListView.searchBox(wd), curUrl,
                LocalDate.now(clock.withZone(PRC)).toString(), names, groups);
    }

    /** 分页地址的参数（同 B13：{@code input()} 的标量参数，{@code wd} 换成处理后的值，页码与条数换成占位）。 */
    private static Map<String, String> pagingVars(Ctx ctx, AdminRowPage list) {
        return AdminContentActions.pagingVars(ctx.params(), list);
    }

    private AdminPaging paging(Ctx ctx) {
        return AdminPaging.of(ctx.params().scalar("page"), ctx.params().scalar("limit"), settings.pageSize());
    }

    private AdminHead head(String titleKey) {
        // 旧版生成充值卡的表单页没有给标题
        return new AdminHead(titleKey == null ? "" : settings.lang().get(titleKey), endpoint.sitePath(), endpoint.root(),
                settings.versionCode());
    }

    /** 旧 {@code success/error($res['msg'])}。 */
    private ResponseEntity<String> result(Ctx ctx, AdminResult r) {
        String msg = r.message().render(settings.lang()::get);
        return r.ok() ? responses.success(ctx.ajax(), msg, AdminController.successUrl(ctx)) : responses.error(ctx.ajax(), msg);
    }
}
