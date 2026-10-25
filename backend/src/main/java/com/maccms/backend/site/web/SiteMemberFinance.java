package com.maccms.backend.site.web;

import com.maccms.backend.common.clientip.ClientIpResolver;
import com.maccms.backend.common.web.LegacyJson;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.content.application.ContentLookupService;
import com.maccms.backend.content.application.GroupPermission;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.content.application.view.CategoryNode;
import com.maccms.backend.content.application.view.ContentTitle;
import com.maccms.backend.member.application.MemberPurchaseService;
import com.maccms.backend.member.application.MemberRecordService;
import com.maccms.backend.member.application.MemberReferralService;
import com.maccms.backend.member.application.MemberWalletService;
import com.maccms.backend.member.application.view.FinanceRecords;
import com.maccms.backend.member.application.view.JsonReply;
import com.maccms.backend.member.application.view.MemberGroup;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.application.view.RecordPage;
import com.maccms.backend.site.application.ImageUrlNormalizer;
import com.maccms.backend.site.application.LegacyPage;
import com.maccms.backend.site.application.LegacyTextEntities;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.PlayerFramePolicy;
import com.maccms.backend.site.application.QrcodeImages;
import com.maccms.backend.site.application.SafeUrl;
import com.maccms.backend.site.application.SiteRoute;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.web.view.HiddenField;
import com.maccms.backend.site.web.view.MemberAction;
import com.maccms.backend.site.web.view.MemberShell;
import com.maccms.backend.site.web.view.MetadataItem;
import com.maccms.backend.site.web.view.NavLink;
import com.maccms.backend.site.web.view.PaginationView;
import com.maccms.backend.site.web.view.UserBuyPage;
import com.maccms.backend.site.web.view.UserCashPage;
import com.maccms.backend.site.web.view.UserJumpPage;
import com.maccms.backend.site.web.view.UserListPage;
import com.maccms.backend.site.web.view.UserOrderPage;
import com.maccms.backend.site.web.view.UserPayPage;
import com.maccms.backend.site.web.view.UserPopedomPage;
import com.maccms.backend.site.web.view.UserTable;
import com.maccms.backend.site.web.view.UserUpgradePage;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 会员权益与财务的 {@code user/*} 入口（B10，docs/architecture/19-b10-member-finance.md 第 2～4、8 节），由 {@link SiteUserController}
 * 在登录态检查之后分派。业务规则在 member 模块的服务里；这里负责 JSON 与页面输出、内容链接与图片地址（{@code ajax_ulog} 列表的
 * {@code data}）、非 ajax 请求的提示页（第 3.3 节）。
 */
final class SiteMemberFinance {

    /** B10 的操作名。 */
    static final Set<String> ACTIONS = Set.of("ajax_ulog", "ajax_buy_popedom", "plays", "downs", "favs", "ulog",
            "ulog_del", "plog", "plog_del", "buy", "pay", "gopay", "qrcode", "upgrade", "popedom", "cash", "cash_del",
            "reward", "orders", "order_info", "cards", "visit");

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZONE);
    private static final MediaType PNG = MediaType.IMAGE_PNG;
    private static final String SCRIPT_PATH = "site/member.js";
    /** 旧 {@code mac_extends_list('pay')}（application/common/extend/pay/*.php 的 {@code $name}，按文件名）。 */
    private static final List<UserPayPage.Method> PAY_METHODS = List.of(new UserPayPage.Method("alipay", "支付宝"),
            new UserPayPage.Method("codepay", "码支付"), new UserPayPage.Method("weixin", "微信支付"),
            new UserPayPage.Method("zhapay", "幻兮支付"));
    private static final Map<Integer, String> PLOG_TYPES = Map.of(1, "积分充值", 2, "注册推广", 3, "访问推广",
            4, "一级分销", 5, "二级分销", 6, "三级分销", 7, "积分升级", 8, "积分消费", 9, "积分提现");
    private static final Map<Integer, String> ULOG_TYPES = Map.of(1, "浏览", 2, "收藏", 3, "想看", 4, "播放", 5, "下载");
    private static final Map<Integer, String> MIDS = Map.of(1, "视频", 2, "文章", 3, "专题", 8, "演员");

    private final SiteSettings site;
    private final LegacyUrlBuilder urls;
    private final SiteHtmlRenderer renderer;
    private final SitePages pages;
    private final ClientIpResolver clientIp;
    private final ImageUrlNormalizer images;
    private final ContentLookupService content;
    private final MemberRecordService records;
    private final MemberPurchaseService purchases;
    private final MemberWalletService wallet;
    private final MemberReferralService referral;

    SiteMemberFinance(SitePages pages, LegacyUrlBuilder urls, SiteHtmlRenderer renderer, ClientIpResolver clientIp,
                      ImageUrlNormalizer images, ContentLookupService content, MemberRecordService records,
                      MemberPurchaseService purchases, MemberWalletService wallet, MemberReferralService referral) {
        this.site = pages.site();
        this.pages = pages;
        this.urls = urls;
        this.renderer = renderer;
        this.clientIp = clientIp;
        this.images = images;
        this.content = content;
        this.records = records;
        this.purchases = purchases;
        this.wallet = wallet;
        this.referral = referral;
    }

    /** 可写数据源已打开、各服务都在。 */
    boolean available() {
        return records.available() && purchases.available() && wallet.available() && referral.available();
    }

    ResponseEntity<?> handle(String action, HttpServletRequest request, Map<String, PhpInput.Value> params,
                             MemberState member, boolean ajax, boolean post) {
        return switch (action) {
            case "ajax_ulog" -> MemberRecordService.isSet(params) ? contentReply(records.ulogSet(member, params), ajax,
                    favoriteBack(params)) : ulogList(member, params);
            case "ajax_buy_popedom" -> contentReply(purchases.buy(member, params), ajax, purchaseBack(params));
            case "plays" -> ulogPage(member, params, "plays", "播放记录", records.plays(member, params), 4,
                    Map.of(), false);
            case "downs" -> ulogPage(member, params, "downs", "下载记录", records.downs(member, params), 5,
                    Map.of(), false);
            case "favs" -> ulogPage(member, params, "favs", "我的收藏", records.favs(member, params), 2,
                    filter("mid", MemberRecordService.filterMid(params)), true);
            case "ulog" -> ulogPage(member, params, "ulog", "全部记录", records.all(member, params),
                    MemberRecordService.filterType(params), filters(MemberRecordService.filterMid(params),
                            MemberRecordService.filterType(params)), true);
            case "ulog_del" -> json(records.ulogDelete(member, params));
            case "plog" -> plogPage(member, params);
            case "plog_del" -> json(records.plogDelete(member, params));
            case "buy" -> post ? json(MemberWalletService.isCard(params) ? wallet.useCard(member, params)
                    : wallet.createOrder(member, params)) : buyPage(member);
            case "pay" -> payPage(member, params, ajax);
            case "gopay" -> error(ajax, MemberWalletService.gopayMessage(params), link("orders"));
            case "qrcode" -> qrcode(params, ajax);
            case "upgrade" -> post ? json(wallet.upgrade(member, params)) : upgradePage(member);
            case "popedom" -> popedomPage(member);
            case "cash" -> post ? json(wallet.applyCash(member, params)) : cashPage(member, params);
            case "cash_del" -> json(wallet.deleteCash(member, params));
            case "reward" -> rewardPage(member, params);
            case "orders" -> ordersPage(member, params);
            case "order_info" -> orderInfo(member, params, ajax);
            case "cards" -> cardsPage(member, params);
            case "visit" -> visit(request, params);
            default -> throw new IllegalArgumentException("未知操作");
        };
    }

    // ------------------------------------------------------------------ 内容页的表单（非 ajax 时为提示页）

    /** ajax → 旧版 JSON；否则提示页，"继续"回到对应的内容页（19 文档第 3.3 节）。 */
    private ResponseEntity<?> contentReply(JsonReply reply, boolean ajax, Optional<SafeUrl> back) {
        if (ajax) {
            return json(reply);
        }
        UserJumpPage page = new UserJumpPage(pages.member(reply.ok() ? "操作成功" : "操作失败"), reply.ok(), SiteVariants.text(reply.msg()),
                back);
        ResponseEntity<String> r = renderer.memberPlain(HttpStatus.OK, t -> t.sitePagesUserJump(page));
        return withCookies(ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders()), reply.setCookies())
                .body(r.getBody());
    }

    /** 收藏的去处：按 {@code mid}/{@code id} 回到详情页。 */
    private Optional<SafeUrl> favoriteBack(Map<String, PhpInput.Value> params) {
        long id = intval(params, "id");
        return switch ((int) intval(params, "mid")) {
            case 1 -> safe(() -> urls.vodDetail(id));
            case 2 -> legacy(LegacyPage.ART_DETAIL, id);
            case 3 -> legacy(LegacyPage.TOPIC_DETAIL, id);
            case 8 -> legacy(LegacyPage.ACTOR_DETAIL, id);
            default -> Optional.empty();
        };
    }

    /** 购买的去处：播放 → vod/play、下载 → vod/down、文章 → art/detail 第 sid 页。 */
    private Optional<SafeUrl> purchaseBack(Map<String, PhpInput.Value> params) {
        long id = intval(params, "id");
        int sid = (int) Math.max(0, Math.min(Integer.MAX_VALUE, intval(params, "sid")));
        int nid = (int) Math.max(0, Math.min(Integer.MAX_VALUE, intval(params, "nid")));
        String type = new String(raw(params, "type"), StandardCharsets.ISO_8859_1);
        return switch (type) {
            case "4" -> safe(() -> urls.legacy(SiteRoute.Legacy.episode(LegacyPage.VOD_PLAY, id, sid, nid)));
            case "5" -> safe(() -> urls.legacy(SiteRoute.Legacy.episode(LegacyPage.VOD_DOWN, id, sid, nid)));
            case "1" -> safe(() -> urls.legacy(new SiteRoute.Legacy(LegacyPage.ART_DETAIL, id, 0, 0,
                    Math.max(1, sid))));
            default -> Optional.empty();
        };
    }

    // ------------------------------------------------------------------ ajax_ulog 列表

    /** {@code ajax_ulog} 的 JSON 列表（User.php:81-97、common/model/Ulog.php:19-122），键顺序与形态同旧版。 */
    private ResponseEntity<?> ulogList(MemberState member, Map<String, PhpInput.Value> params) {
        RecordPage<FinanceRecords.Ulog> page = records.ulogJsonList(member, params);
        Map<Integer, Map<Long, ContentTitle>> titles = titles(page.items());
        List<Object> list = new ArrayList<>();
        for (FinanceRecords.Ulog u : page.items()) {
            Map<String, Object> item = new LinkedHashMap<>(MemberRecordService.ulogJson(u));
            if (ContentLookupService.TITLE_MIDS.contains(u.mid())) {
                item.put("data", data(u, titles.getOrDefault(u.mid(), Map.of()).get(u.rid())));
            }
            item.put("user_name", member.userName());
            list.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 1);
        body.put("msg", "数据列表");
        body.put("page", page.page());
        body.put("pagecount", page.pageCount());
        body.put("limit", page.limit());
        body.put("total", page.total());
        body.put("list", list);
        return SiteUserController.jsonBody(body, List.of());
    }

    private Map<Integer, Map<Long, ContentTitle>> titles(List<FinanceRecords.Ulog> rows) {
        Map<Integer, List<Long>> byMid = rows.stream().filter(u -> ContentLookupService.TITLE_MIDS.contains(u.mid()))
                .collect(Collectors.groupingBy(FinanceRecords.Ulog::mid, LinkedHashMap::new,
                        Collectors.mapping(FinanceRecords.Ulog::rid, Collectors.toList())));
        Map<Integer, Map<Long, ContentTitle>> out = new LinkedHashMap<>();
        byMid.forEach((mid, ids) -> out.put(mid, content.titles(mid, ids.stream().distinct().toList())));
        return out;
    }

    /** 列表项的 {@code data}（common/model/Ulog.php:36-100）；内容不存在时按旧版的空值形态。 */
    private Map<String, Object> data(FinanceRecords.Ulog u, ContentTitle t) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", t == null ? null : t.id());
        d.put("name", t == null ? null : t.name());
        d.put("pic", t == null ? site.homePath().href() : pic(t.pic()));
        d.put("link", t == null ? site.publicPath(urls.bare(bareModel(u.mid()))).href() : contentLink(u, t).href());
        if (u.mid() == 1 || u.mid() == 2) {
            Map<String, Object> type = new LinkedHashMap<>();
            boolean typed = t != null && t.typeFound();
            type.put("type_id", typed ? t.typeId() : null);
            type.put("type_name", typed ? t.typeName() : null);
            type.put("link", typed ? typeLink(u.mid(), t.typeId()) : site.publicPath(urls.bare("vod/type")).href());
            d.put("type", type);
        } else {
            d.put("type", List.of());
        }
        return d;
    }

    private static String bareModel(int mid) {
        return switch (mid) {
            case 1 -> "vod/detail";
            case 2 -> "art/detail";
            case 3 -> "topic/detail";
            default -> "actor/detail";
        };
    }

    /** 旧 {@code mac_url_img}：为空时是站点根路径。 */
    private String pic(String raw) {
        return images.normalize(raw).map(SafeUrl::href).orElse(site.homePath().href());
    }

    /** 记录指向的内容页：视频按 sid/nid 为播放或下载页，否则详情页。 */
    private SafeUrl contentLink(FinanceRecords.Ulog u, ContentTitle t) {
        Optional<SafeUrl> url = switch (u.mid()) {
            case 1 -> u.sid() > 0 && u.nid() > 0
                    ? safe(() -> urls.legacy(SiteRoute.Legacy.episode(u.type() == 5 ? LegacyPage.VOD_DOWN
                    : LegacyPage.VOD_PLAY, t.id(), u.sid(), u.nid()))).or(() -> safe(() -> urls.vodDetail(t.id())))
                    : safe(() -> urls.vodDetail(t.id()));
            case 2 -> legacy(LegacyPage.ART_DETAIL, t.id());
            case 3 -> legacy(LegacyPage.TOPIC_DETAIL, t.id());
            default -> legacy(LegacyPage.ACTOR_DETAIL, t.id());
        };
        return url.orElse(site.homePath());
    }

    private String typeLink(int mid, int typeId) {
        Optional<SafeUrl> u = mid == 1 ? safe(() -> urls.vodType(typeId, 1)) : legacy(LegacyPage.ART_TYPE, typeId);
        return u.orElse(site.homePath()).href();
    }

    // ------------------------------------------------------------------ 列表页

    private ResponseEntity<?> ulogPage(MemberState member, Map<String, PhpInput.Value> params, String action,
                                       String heading, RecordPage<FinanceRecords.Ulog> page, int clearType,
                                       Map<String, String> filters, boolean showKind) {
        Map<Integer, Map<Long, ContentTitle>> titles = titles(page.items());
        SafeUrl self = site.publicPath(urls.userPage(action, filters, page.page()));
        List<String> headers = new ArrayList<>(List.of("内容"));
        if (showKind) {
            headers.add("模型");
            headers.add("类型");
        }
        headers.add("积分");
        headers.add("时间");
        List<UserTable.Row> rows = new ArrayList<>();
        for (FinanceRecords.Ulog u : page.items()) {
            ContentTitle t = titles.getOrDefault(u.mid(), Map.of()).get(u.rid());
            List<UserTable.Cell> cells = new ArrayList<>();
            cells.add(t == null ? UserTable.Cell.of("内容已删除") : UserTable.Cell.link(text(t.name()), contentLink(u, t)));
            if (showKind) {
                cells.add(UserTable.Cell.of(MIDS.getOrDefault(u.mid(), "-")));
                cells.add(UserTable.Cell.of(ULOG_TYPES.getOrDefault(u.type(), "-")));
            }
            cells.add(UserTable.Cell.of(u.points() > 0 ? Integer.toString(u.points()) : "-"));
            cells.add(UserTable.Cell.of(time(u.time())));
            Optional<MemberAction> delete = ULOG_TYPES.containsKey(u.type())
                    ? Optional.of(new MemberAction("delete", link("ulog_del"), List.of(
                    new HiddenField("ids", Long.toString(u.id())), new HiddenField("type", Integer.toString(u.type()))),
                    "删除", self)) : Optional.empty();
            rows.add(new UserTable.Row(cells, delete));
        }
        Optional<MemberAction> clear = clearType > 0 && !page.items().isEmpty()
                ? Optional.of(new MemberAction("clear", link("ulog_del"), List.of(new HiddenField("all", "1"),
                new HiddenField("type", Integer.toString(clearType))), "清空" + ULOG_TYPES.get(clearType) + "记录", self))
                : Optional.empty();
        List<NavLink> tabs = new ArrayList<>();
        if (action.equals("favs") || action.equals("ulog")) {
            int mid = filters.containsKey("mid") ? Integer.parseInt(filters.get("mid")) : 0;
            Map<String, String> base = new LinkedHashMap<>(filters);
            base.remove("mid");
            tabs.add(new NavLink("全部", site.publicPath(urls.userPage(action, base, 1)), mid == 0));
            for (int m : List.of(1, 2, 3, 8)) {
                Map<String, String> f = new LinkedHashMap<>(base);
                f.put("mid", Integer.toString(m));
                tabs.add(new NavLink(MIDS.get(m), site.publicPath(urls.userPage(action, f, 1)), mid == m));
            }
            if (action.equals("ulog")) {
                int type = filters.containsKey("type") ? Integer.parseInt(filters.get("type")) : 0;
                Map<String, String> noType = new LinkedHashMap<>(filters);
                noType.remove("type");
                tabs.add(new NavLink("全部类型", site.publicPath(urls.userPage(action, noType, 1)), type == 0));
                for (int ty = 1; ty <= 5; ty++) {
                    Map<String, String> f = new LinkedHashMap<>(noType);
                    f.put("type", Integer.toString(ty));
                    tabs.add(new NavLink(ULOG_TYPES.get(ty), site.publicPath(urls.userPage(action, f, 1)), type == ty));
                }
            }
        }
        UserTable table = new UserTable(heading, headers, rows, "暂无记录。", pagination(action, filters, page));
        return listPage(new UserListPage(shell(heading), "user-" + action, heading, List.of(), tabs, table, clear,
                backHome()));
    }

    private ResponseEntity<?> plogPage(MemberState member, Map<String, PhpInput.Value> params) {
        RecordPage<FinanceRecords.Plog> page = records.plogs(member, params);
        SafeUrl self = site.publicPath(urls.userPage("plog", Map.of(), page.page()));
        List<UserTable.Row> rows = new ArrayList<>();
        for (FinanceRecords.Plog p : page.items()) {
            String sign = p.type() >= 1 && p.type() <= 6 ? "+" : "-";
            rows.add(new UserTable.Row(List.of(UserTable.Cell.of(PLOG_TYPES.getOrDefault(p.type(), "-")),
                    UserTable.Cell.of(sign + p.points()), UserTable.Cell.of(time(p.time())),
                    UserTable.Cell.of(p.remarks().isEmpty() ? "-" : p.remarks())),
                    Optional.of(new MemberAction("delete", link("plog_del"),
                            List.of(new HiddenField("ids", Long.toString(p.id()))), "删除", self))));
        }
        Optional<MemberAction> clear = page.items().isEmpty() ? Optional.empty()
                : Optional.of(new MemberAction("clear", link("plog_del"), List.of(new HiddenField("all", "1")),
                "清空积分日志", self));
        UserTable table = new UserTable("积分日志", List.of("类型", "积分", "时间", "备注"), rows, "暂无积分日志。",
                pagination("plog", Map.of(), page));
        return listPage(new UserListPage(shell("积分日志"), "user-plog", "积分日志", List.of(), List.of(), table, clear,
                backHome()));
    }

    private ResponseEntity<?> rewardPage(MemberState member, Map<String, PhpInput.Value> params) {
        int level = MemberWalletService.invitationLevel(params);
        RecordPage<FinanceRecords.Invitee> page = wallet.invitees(member, params);
        Map<String, String> filters = level == 1 ? Map.of() : Map.of("level", Integer.toString(level));
        List<UserTable.Row> rows = new ArrayList<>();
        for (FinanceRecords.Invitee i : page.items()) {
            rows.add(new UserTable.Row(List.of(UserTable.Cell.of(i.userName()),
                    UserTable.Cell.of(i.nickName().isEmpty() ? "-" : text(i.nickName())),
                    UserTable.Cell.of(time(i.regTime())), UserTable.Cell.of(time(i.loginTime()))), Optional.empty()));
        }
        List<NavLink> tabs = List.of(
                new NavLink("一级推广", site.publicPath(urls.userPage("reward", Map.of(), 1)), level == 1),
                new NavLink("二级推广", site.publicPath(urls.userPage("reward", Map.of("level", "2"), 1)), level == 2),
                new NavLink("三级推广", site.publicPath(urls.userPage("reward", Map.of("level", "3"), 1)), level == 3));
        UserTable table = new UserTable("推广会员", List.of("用户名", "昵称", "注册时间", "最近登录"), rows, "暂无推广会员。",
                pagination("reward", filters, page));
        List<String> intro = List.of("推广注册链接：" + promotionUrl("reg", member.userId()),
                "推广访问链接：" + promotionUrl("visit", member.userId()));
        return listPage(new UserListPage(shell("推广会员"), "user-reward", "推广会员", intro, tabs, table,
                Optional.empty(), backHome()));
    }

    private ResponseEntity<?> ordersPage(MemberState member, Map<String, PhpInput.Value> params) {
        RecordPage<FinanceRecords.Order> page = wallet.orders(member, params);
        List<UserTable.Row> rows = new ArrayList<>();
        for (FinanceRecords.Order o : page.items()) {
            List<UserTable.Cell> cells = new ArrayList<>();
            cells.add(UserTable.Cell.link(o.code(), orderInfoLink(o.id())));
            cells.add(UserTable.Cell.of(o.price()));
            cells.add(UserTable.Cell.of(Integer.toString(o.points())));
            cells.add(UserTable.Cell.of(o.status() == 1 ? "已支付" : "未支付"));
            cells.add(UserTable.Cell.of(time(o.time())));
            cells.add(o.status() == 1 ? UserTable.Cell.of("-") : UserTable.Cell.link("去支付", payLink(o.code())));
            rows.add(new UserTable.Row(cells, Optional.empty()));
        }
        UserTable table = new UserTable("我的订单", List.of("单号", "金额", "积分", "状态", "时间", "支付"), rows,
                "暂无订单。", pagination("orders", Map.of(), page));
        return listPage(new UserListPage(shell("我的订单"), "user-orders", "我的订单", List.of(), List.of(), table,
                Optional.empty(), backHome()));
    }

    private ResponseEntity<?> cardsPage(MemberState member, Map<String, PhpInput.Value> params) {
        RecordPage<FinanceRecords.Card> page = wallet.cards(member, params);
        List<UserTable.Row> rows = new ArrayList<>();
        for (FinanceRecords.Card c : page.items()) {
            rows.add(new UserTable.Row(List.of(UserTable.Cell.of(c.cardNo()), UserTable.Cell.of(Integer.toString(c.money())),
                    UserTable.Cell.of(Integer.toString(c.points())), UserTable.Cell.of(time(c.useTime()))),
                    Optional.empty()));
        }
        UserTable table = new UserTable("充值卡记录", List.of("卡号", "面值", "积分", "使用时间"), rows, "暂无充值卡记录。",
                pagination("cards", Map.of(), page));
        return listPage(new UserListPage(shell("充值卡记录"), "user-cards", "充值卡记录", List.of(), List.of(), table,
                Optional.empty(), backHome()));
    }

    private ResponseEntity<?> cashPage(MemberState member, Map<String, PhpInput.Value> params) {
        RecordPage<FinanceRecords.Cash> page = wallet.cashes(member, params);
        SafeUrl self = site.publicPath(urls.userPage("cash", Map.of(), page.page()));
        List<UserTable.Row> rows = new ArrayList<>();
        for (FinanceRecords.Cash c : page.items()) {
            rows.add(new UserTable.Row(List.of(UserTable.Cell.of(c.money()), UserTable.Cell.of(Integer.toString(c.points())),
                    UserTable.Cell.of(text(c.bankName())), UserTable.Cell.of(text(c.bankNo())),
                    UserTable.Cell.of(text(c.payeeName())), UserTable.Cell.of(c.status() == 1 ? "已审核" : "未审核"),
                    UserTable.Cell.of(time(c.time()))),
                    Optional.of(new MemberAction("delete", link("cash_del"),
                            List.of(new HiddenField("ids", Long.toString(c.id()))),
                            c.status() == 0 ? "撤销" : "删除", self))));
        }
        FinanceRecords.Wallet w = wallet.wallet(member.userId()).orElse(null);
        List<MetadataItem> summary = new ArrayList<>();
        summary.add(new MetadataItem("可用积分", w == null ? "0" : Long.toString(w.points())));
        summary.add(new MetadataItem("冻结积分", w == null ? "0" : Long.toString(w.froze())));
        summary.add(new MetadataItem("每元积分", number(wallet.settings().cashRatio())));
        summary.add(new MetadataItem("最低提现金额（元）", wallet.settings().cashMinText()));
        UserTable table = new UserTable("提现记录", List.of("金额", "积分", "银行", "账号", "收款人", "状态", "申请时间"),
                rows, "暂无提现记录。", pagination("cash", Map.of(), page));
        UserCashPage view = new UserCashPage(shell("积分提现"), summary,
                wallet.settings().cashOn() ? Optional.of(link("cash")) : Optional.empty(), self, table, backHome());
        MemberShell shell = view.shell();
        return renderer.member(HttpStatus.OK, t -> t.sitePagesUserCash(view), shell.nonce());
    }

    // ------------------------------------------------------------------ 充值、支付、升级、权限

    private ResponseEntity<?> buyPage(MemberState member) {
        long points = wallet.wallet(member.userId()).map(FinanceRecords.Wallet::points).orElse(0L);
        UserBuyPage view = new UserBuyPage(shell("充值"), link("buy"), points, wallet.settings().payMinText(),
                number(wallet.settings().payScale()), cardShop(wallet.settings().cardShopUrl()), link("buy"),
                link("orders"), backHome());
        return renderer.member(HttpStatus.OK, t -> t.sitePagesUserBuy(view), view.shell().nonce());
    }

    /** {@code pay.card.url}：只在是本站地址（站内路径，或与 base-url 同主机）时显示（19 文档第 7、11 节）。 */
    private Optional<SafeUrl> cardShop(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Optional<SafeUrl> url = SafeUrl.parse(raw.strip());
        if (url.isEmpty()) {
            return Optional.empty();
        }
        String href = url.get().href();
        if (href.startsWith("/") && !href.startsWith("//")) {
            return url;
        }
        try {
            String host = new URI(href).getHost();
            String base = new URI(site.baseUrl().href()).getHost();
            return host != null && host.equalsIgnoreCase(base) ? url : Optional.empty();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    private ResponseEntity<?> payPage(MemberState member, Map<String, PhpInput.Value> params, boolean ajax) {
        Optional<FinanceRecords.Order> order = wallet.orderByCode(member, params);
        if (order.isEmpty()) {
            return error(ajax, "获取失败", link("orders"));
        }
        FinanceRecords.Order o = order.get();
        UserPayPage view = new UserPayPage(shell("订单支付"), orderDetails(o), o.status() == 1, link("gopay"),
                List.of(new HiddenField("order_code", o.code()), new HiddenField("order_id", Long.toString(o.id()))),
                PAY_METHODS, new NavLink("返回我的订单", link("orders"), false));
        return renderer.member(HttpStatus.OK, t -> t.sitePagesUserPay(view), view.shell().nonce());
    }

    private ResponseEntity<?> orderInfo(MemberState member, Map<String, PhpInput.Value> params, boolean ajax) {
        Optional<FinanceRecords.Order> order = wallet.orderById(member, params);
        if (ajax) {
            if (order.isEmpty()) {
                return json(JsonReply.of(1002, "获取失败"));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", 1);
            body.put("msg", "获取成功");
            body.put("info", MemberWalletService.orderJson(order.get()));
            return SiteUserController.jsonBody(body, List.of());
        }
        UserOrderPage view = new UserOrderPage(shell("订单详情"), order.map(this::orderDetails).orElse(List.of()),
                order.filter(o -> o.status() == 0).map(o -> payLink(o.code())),
                new NavLink("返回我的订单", link("orders"), false));
        return renderer.member(HttpStatus.OK, t -> t.sitePagesUserOrder(view), view.shell().nonce());
    }

    private List<MetadataItem> orderDetails(FinanceRecords.Order o) {
        List<MetadataItem> d = new ArrayList<>();
        d.add(new MetadataItem("单号", o.code()));
        d.add(new MetadataItem("金额（元）", o.price()));
        d.add(new MetadataItem("积分", Integer.toString(o.points())));
        d.add(new MetadataItem("状态", o.status() == 1 ? "已支付" : "未支付"));
        d.add(new MetadataItem("下单时间", time(o.time())));
        if (o.status() == 1) {
            d.add(new MetadataItem("支付方式", o.payType().isEmpty() ? "-" : o.payType()));
            d.add(new MetadataItem("支付时间", time(o.payTime())));
        }
        return d;
    }

    private ResponseEntity<?> qrcode(Map<String, PhpInput.Value> params, boolean ajax) {
        byte[] data = raw(params, "data");
        Optional<byte[]> png = QrcodeImages.weixinPng(new String(data, StandardCharsets.UTF_8));
        if (png.isEmpty()) {
            return error(ajax, "参数错误", link("orders"));
        }
        return ResponseEntity.ok().contentType(PNG).contentLength(png.get().length).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(png.get());
    }

    private ResponseEntity<?> upgradePage(MemberState member) {
        FinanceRecords.Wallet w = wallet.wallet(member.userId()).orElse(null);
        List<MetadataItem> summary = new ArrayList<>();
        MemberGroup g = member.group();
        summary.add(new MetadataItem("当前会员组", g == null ? "-" : text(g.name())));
        if (w != null && member.groupId() > 2 && w.endTime() > 0) {
            summary.add(new MetadataItem("到期时间", time(w.endTime())));
        }
        summary.add(new MetadataItem("当前积分", w == null ? "0" : Long.toString(w.points())));
        List<UserUpgradePage.Option> options = wallet.groups().stream().filter(o -> o.id() >= 3 && o.status() != 0)
                .map(o -> new UserUpgradePage.Option(o.id(), text(o.name()), o.day(), o.week(), o.month(), o.year()))
                .toList();
        UserUpgradePage view = new UserUpgradePage(shell("升级会员"), summary, options, link("upgrade"),
                link("upgrade"), backHome());
        return renderer.member(HttpStatus.OK, t -> t.sitePagesUserUpgrade(view), view.shell().nonce());
    }

    /** 我的权限（User.php:563-594）：全部分类，非视频分类只有前两列。 */
    private ResponseEntity<?> popedomPage(MemberState member) {
        MemberGroup g = member.group();
        GroupPermission perm = g == null ? GroupPermission.none()
                : GroupPermission.of(g.id(), g.typeIds(), g.popedomJson());
        List<UserPopedomPage.Row> rows = new ArrayList<>();
        for (CategoryNode root : content.typeTree()) {
            rows.add(popedomRow(root, false, perm));
            for (CategoryNode child : root.children()) {
                rows.add(popedomRow(child, true, perm));
            }
        }
        UserPopedomPage view = new UserPopedomPage(shell("我的权限"), g == null ? "-" : text(g.name()),
                List.of("分类", "列表页", "内容页", "播放页", "下载页", "试看"), rows, backHome());
        return renderer.member(HttpStatus.OK, t -> t.sitePagesUserPopedom(view), view.shell().nonce());
    }

    private static UserPopedomPage.Row popedomRow(CategoryNode n, boolean child, GroupPermission perm) {
        List<String> cells = new ArrayList<>();
        for (int bit = 1; bit <= 5; bit++) {
            if (n.mid() != 1 && bit > 2) {
                cells.add("");
            } else {
                cells.add(perm.allows(n.id(), Integer.toString(bit)) ? "是" : "否");
            }
        }
        return new UserPopedomPage.Row(text(n.name()), child, cells);
    }

    // ------------------------------------------------------------------ 推广访问

    /**
     * {@code visit}（User.php:906-918）：记录后跳转。{@code url} 是 {@code http(s)} 绝对地址且主机（含端口）与请求的 Host 相同才跳过去，
     * 否则跳到 {@code /}（与旧版相同，不加站点根路径，D-B10-12）。
     */
    private ResponseEntity<?> visit(HttpServletRequest request, Map<String, PhpInput.Value> params) {
        List<String> refs = Collections.list(request.getHeaders(HttpHeaders.REFERER));
        byte[] referer = refs.isEmpty() ? null : refs.get(0).getBytes(StandardCharsets.ISO_8859_1);
        referral.visit(params, clientIp.resolve(request), referer);
        String target = "/";
        String url = new String(raw(params, "url"), StandardCharsets.ISO_8859_1);
        String host = request.getHeader(HttpHeaders.HOST);
        if (host != null && url.matches("(?i)https?://[\\x21-\\x7e]+")) {
            try {
                URI u = new URI(url);
                if (host.equals(u.getRawAuthority())) {
                    target = url;
                }
            } catch (URISyntaxException e) {
                target = "/";
            }
        }
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, target)
                .cacheControl(CacheControl.noStore()).build();
    }

    // ------------------------------------------------------------------ 共用

    private ResponseEntity<?> listPage(UserListPage page) {
        return renderer.member(HttpStatus.OK, t -> t.sitePagesUserList(page), page.shell().nonce());
    }

    /** ThinkPHP {@code error($msg)}：ajax → {@code {"code":0,"msg":…,"data":"","url":"","wait":3}}；否则提示页。 */
    private ResponseEntity<?> error(boolean ajax, String msg, SafeUrl back) {
        if (ajax) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", 0);
            body.put("msg", msg);
            body.put("data", "");
            body.put("url", "");
            body.put("wait", 3);
            return SiteUserController.jsonBody(body, List.of());
        }
        UserJumpPage page = new UserJumpPage(pages.member("操作失败"), false, SiteVariants.text(msg), Optional.of(back));
        return renderer.memberPlain(HttpStatus.OK, t -> t.sitePagesUserJump(page));
    }

    private static ResponseEntity<byte[]> json(JsonReply reply) {
        return SiteUserController.jsonBody(reply.body(), reply.setCookies());
    }

    private static ResponseEntity.BodyBuilder withCookies(ResponseEntity.BodyBuilder b, List<String> cookies) {
        for (String c : cookies) {
            b.header(HttpHeaders.SET_COOKIE, c);
        }
        return b;
    }

    private Optional<PaginationView> pagination(String action, Map<String, String> filters, RecordPage<?> page) {
        long count = page.pageCount();
        if (count <= 1) {
            return Optional.empty();
        }
        int current = page.page();
        int pageCount = (int) Math.min(Integer.MAX_VALUE, count);
        Optional<SafeUrl> prev = current > 1
                ? Optional.of(site.publicPath(urls.userPage(action, filters, Math.min(current - 1, pageCount))))
                : Optional.empty();
        Optional<SafeUrl> next = current < pageCount
                ? Optional.of(site.publicPath(urls.userPage(action, filters, current + 1))) : Optional.empty();
        return Optional.of(new PaginationView(current, pageCount, prev, next));
    }

    private static Map<String, String> filter(String key, int value) {
        return value > 0 ? Map.of(key, Integer.toString(value)) : Map.of();
    }

    private static Map<String, String> filters(int mid, int type) {
        Map<String, String> f = new LinkedHashMap<>();
        if (mid > 0) {
            f.put("mid", Integer.toString(mid));
        }
        if (type > 0) {
            f.put("type", Integer.toString(type));
        }
        return f;
    }

    private MemberShell shell(String heading) {
        return new MemberShell(pages.member(heading), PlayerFramePolicy.create().nonce(), site.publicPath(SCRIPT_PATH));
    }

    private NavLink backHome() {
        return new NavLink("返回会员中心", link("index"), false);
    }

    private SafeUrl link(String action) {
        return site.publicPath(urls.user(action));
    }

    private SafeUrl orderInfoLink(long id) {
        return site.publicPath(urls.userPage("order_info", Map.of("order_id", Long.toString(id)), 1));
    }

    /** 旧模板的去支付地址 {@code user/pay?order_code=…}（单号按 UTF-8 百分号编码）。 */
    private SafeUrl payLink(String code) {
        return SafeUrl.parse(link("pay").href() + "?order_code=" + URLEncoder.encode(code, StandardCharsets.UTF_8))
                .orElse(link("orders"));
    }

    String promotionUrl(String action, long userId) {
        return site.canonical(urls.userPage(action, Map.of("uid", Long.toString(userId)), 1)).href();
    }

    private Optional<SafeUrl> legacy(LegacyPage page, long id) {
        return safe(() -> urls.legacy(SiteRoute.Legacy.of(page, id)));
    }

    private Optional<SafeUrl> safe(Supplier<String> path) {
        try {
            return Optional.of(site.publicPath(path.get()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String time(long epoch) {
        return epoch <= 0 ? "-" : TIME.format(Instant.ofEpochSecond(epoch));
    }

    /** 配置中的数值（每元积分等）按最短形式显示。 */
    private static String number(double d) {
        return LegacyJson.encode(d);
    }

    /** 库里的文本字段按 B05 的规则解码一次字符引用。 */
    private static String text(String raw) {
        return LegacyTextEntities.decodeOnce(raw);
    }

    private static long intval(Map<String, PhpInput.Value> params, String name) {
        return PhpIntval.of(new String(raw(params, name), StandardCharsets.ISO_8859_1));
    }

    private static byte[] raw(Map<String, PhpInput.Value> params, String name) {
        byte[] v = PhpInput.scalar(params, name);
        return v == null ? new byte[0] : v;
    }
}
