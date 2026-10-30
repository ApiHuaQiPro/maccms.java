package com.maccms.backend.site.web;

import com.maccms.backend.comment.application.CommentService;
import com.maccms.backend.comment.application.view.BoardPage;
import com.maccms.backend.comment.application.view.CommentItem;
import com.maccms.backend.comment.application.view.GbookItem;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.PlayerFramePolicy;
import com.maccms.backend.site.application.SafeUrl;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.web.view.MemberShell;
import com.maccms.backend.site.web.view.NavLink;
import com.maccms.backend.site.web.view.PaginationView;
import com.maccms.backend.site.web.view.UserListPage;
import com.maccms.backend.site.web.view.UserTable;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 会员中心的"我的评论""我的留言"（B11，旧 {@code user/comment}、{@code user/gbook}，index/controller/User.php:892-904）：旧控制器只渲染模板，
 * 列表由模板标签按当前会员取（{@code {maccms:comment uid=…}}、{@code {maccms:gbook uid=…}}，标签只列已审核的，评论只列顶层）。仓库缺模板，
 * 新版按标签的条件实现【推断，20 文档第 3.4 节】。
 */
final class SiteMemberBoards {

    private static final String SCRIPT_PATH = "site/member.js";
    private static final ZoneId PRC = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int PAGE_SIZE = 20;

    private final SiteSettings site;
    private final SitePages pages;
    private final LegacyUrlBuilder urls;
    private final SiteHtmlRenderer renderer;
    private final CommentService comments;

    SiteMemberBoards(SitePages pages, LegacyUrlBuilder urls, SiteHtmlRenderer renderer, CommentService comments) {
        this.site = pages.site();
        this.pages = pages;
        this.urls = urls;
        this.renderer = renderer;
        this.comments = comments;
    }

    ResponseEntity<?> handle(String action, MemberState member, Map<String, PhpInput.Value> params) {
        int page = page(params);
        return action.equals("comment") ? commentList(member, page) : gbookList(member, page);
    }

    private ResponseEntity<?> commentList(MemberState member, int page) {
        BoardPage<CommentItem> p = comments.comments(0, 0, 0, member.userId(), "time", "desc", page, PAGE_SIZE);
        List<UserTable.Row> rows = new ArrayList<>();
        for (CommentItem c : p.items()) {
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("mid", Integer.toString(c.mid()));
            vars.put("rid", Long.toString(c.rid()));
            SafeUrl link = site.publicPath(urls.action("comment/index", vars));
            rows.add(new UserTable.Row(List.of(UserTable.Cell.of(time(c.time())), UserTable.Cell.link(c.content(), link),
                    UserTable.Cell.of(c.up() + " / " + c.down())), Optional.empty()));
        }
        UserTable table = new UserTable("我的评论", List.of("时间", "内容", "顶 / 踩"), rows, "暂无评论。",
                pagination("comment", p));
        return list("user-comment", "我的评论", table);
    }

    private ResponseEntity<?> gbookList(MemberState member, int page) {
        BoardPage<GbookItem> p = comments.gbooks(0, member.userId(), "time", "desc", page, PAGE_SIZE);
        List<UserTable.Row> rows = new ArrayList<>();
        for (GbookItem g : p.items()) {
            rows.add(new UserTable.Row(List.of(UserTable.Cell.of(time(g.time())), UserTable.Cell.of(g.content()),
                    UserTable.Cell.of(g.reply().isEmpty() ? "-" : g.reply())), Optional.empty()));
        }
        UserTable table = new UserTable("我的留言", List.of("时间", "内容", "回复"), rows, "暂无留言。",
                pagination("gbook", p));
        return list("user-gbook", "我的留言", table);
    }

    private ResponseEntity<?> list(String key, String heading, UserTable table) {
        MemberShell shell = new MemberShell(pages.member(heading), PlayerFramePolicy.create().nonce(),
                site.publicPath(SCRIPT_PATH));
        UserListPage page = new UserListPage(shell, key, heading, List.of(), List.of(), table, Optional.empty(),
                new NavLink("返回会员中心", site.publicPath(urls.user("index")), false));
        return renderer.member(HttpStatus.OK, t -> t.sitePagesUserList(page), shell.nonce());
    }

    private <T> Optional<PaginationView> pagination(String action, BoardPage<T> p) {
        if (p.pageCount() <= 1) {
            return Optional.empty();
        }
        int count = (int) Math.min(100_000, p.pageCount());
        int current = Math.min(p.page(), count);
        Optional<SafeUrl> prev = current > 1 ? Optional.of(site.publicPath(urls.userPage(action, Map.of(), current - 1)))
                : Optional.empty();
        Optional<SafeUrl> next = current < count
                ? Optional.of(site.publicPath(urls.userPage(action, Map.of(), current + 1))) : Optional.empty();
        return Optional.of(new PaginationView(current, count, prev, next));
    }

    private static int page(Map<String, PhpInput.Value> params) {
        byte[] v = PhpInput.scalar(params, "page");
        long p = v == null ? 1 : PhpIntval.of(new String(v, StandardCharsets.ISO_8859_1));
        return (int) Math.max(1, Math.min(100_000, p));
    }

    private static String time(long epoch) {
        return TIME.format(Instant.ofEpochSecond(epoch).atZone(PRC));
    }
}
