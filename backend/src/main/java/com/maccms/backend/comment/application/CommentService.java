package com.maccms.backend.comment.application;

import com.maccms.backend.comment.application.view.BoardPage;
import com.maccms.backend.comment.application.view.BoardSettings;
import com.maccms.backend.comment.application.view.CommentCounts;
import com.maccms.backend.comment.application.view.CommentItem;
import com.maccms.backend.comment.application.view.GbookItem;
import com.maccms.backend.comment.application.view.InteractionSettings;
import com.maccms.backend.comment.application.view.PostReply;
import com.maccms.backend.comment.application.view.Poster;
import com.maccms.backend.comment.infrastructure.CommentListCriteria;
import com.maccms.backend.comment.infrastructure.CommentMapper;
import com.maccms.backend.comment.infrastructure.CommentRow;
import com.maccms.backend.comment.infrastructure.CommentWriteMapper;
import com.maccms.backend.comment.infrastructure.GbookRow;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.content.application.PhpIntval;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * 评论与留言（B11，docs/architecture/20-b11-comment-gbook-counters.md 第 4 节）：发表（旧 {@code Comment::saveData}、{@code Gbook::saveData}，
 * application/index/controller/Comment.php:37-117、Gbook.php:43-117 与两个模型的 {@code saveData}）、评论举报与顶踩、列表。
 * 地址解析、cookie、验证码会话、页面与 JSON 由 site 负责；这里按旧控制器的判断顺序给出旧版的返回码与提示，并按旧版写入。
 *
 * <p>与旧版有意不同（第 9 节）：发表者身份按会员登录态（D-B11-2）；{@code comment_reply}、{@code comment_report}、{@code gbook_reply_time}
 * 不取客户端提交的值（D-B11-3）；{@code comment_mid} 按字节精确比较（D-B11-4，同 D-B10-1）；写入被数据库拒绝时返回"保存失败"（D-B11-5，
 * 同 D-B10-3）。
 */
public final class CommentService {

    static final String VISITOR = "游客";
    private static final Set<String> COMMENT_MIDS = Set.of("1", "2", "3", "8", "9", "11");
    private static final Pattern UNSIGNED = Pattern.compile("[0-9]{1,10}");
    private static final long UINT_MAX = 4_294_967_295L;
    /** 列表页码上限（旧版不限；超过时按空页）。 */
    static final int MAX_PAGE = 100_000;
    /** 一页评论附带的回复总数上限（旧版对每条评论不限条数地查回复）。 */
    static final int MAX_REPLIES = 1000;
    private static final Logger log = LoggerFactory.getLogger(CommentService.class);

    private final CommentMapper reader;
    private final CommentWriteMapper writer;
    private final InteractionSettingsSource settings;
    private final Clock clock;

    public CommentService(CommentMapper reader, CommentWriteMapper writer, InteractionSettingsSource settings,
                          Clock clock) {
        this.reader = reader;
        this.writer = writer;
        this.settings = settings;
        this.clock = clock;
    }

    /** 可写数据源打开时为 true；否则只能浏览。 */
    public boolean writable() {
        return writer != null;
    }

    /** 本次请求的旧配置（每次调用重新读取）。 */
    public InteractionSettings settings() {
        return settings.current();
    }

    public long now() {
        return clock.instant().getEpochSecond();
    }

    // ------------------------------------------------------------------ 发表

    /**
     * 发表评论（Comment.php:37-117）。调用方已确认评论功能打开（旧构造函数）。
     *
     * @param params         ThinkPHP {@code input()} 合并后的参数
     * @param timespanCookie 请求带了非空的 {@code comment_timespan} cookie（旧 {@code !empty(cookie(...))}）
     * @param captcha        旧 {@code captcha_check($param['verify'])}（只在 {@code verify == 1} 时调用，调用即作废）
     * @param ip             旧 {@code mac_get_ip_long()}
     */
    public PostReply postComment(InteractionSettings s, Map<String, PhpInput.Value> params, Poster poster,
                                 boolean timespanCookie, BooleanSupplier captcha, long ip) {
        BoardSettings b = s.comment();
        PostReply pre = precheck(b, params, "comment_content", poster, timespanCookie, captcha);
        if (pre != null) {
            return pre;
        }
        String content = entities(s, params, "comment_content");
        byte[] mid = PhpInput.scalar(params, "comment_mid");
        String midText = mid == null ? null : new String(mid, StandardCharsets.ISO_8859_1);
        if (midText == null || !COMMENT_MIDS.contains(midText)) {
            return PostReply.of(1006, "模型mid错误");
        }
        String name = displayName(poster);
        long rid = intval(params, "comment_rid");
        long pid = intval(params, "comment_pid");
        int status = b.audit() ? 0 : 1;
        PostReply invalid = validate(name, content);
        if (invalid != null) {
            return invalid;
        }
        int inserted;
        try {
            inserted = writer().insertComment(Integer.parseInt(midText), rid, pid, poster.userId(), status,
                    LegacyHtml.filterXss(name), ip, now(), LegacyHtml.filterXss(content));
        } catch (DataAccessException e) {
            // 超长、4 字节字符、rid/pid 越界（负数）等被严格模式拒绝：旧版 500，新版"保存失败"，都没有写入（D-B11-5）
            log.warn("comment rejected by database: {}", SafeExceptionLog.typeChain(e));
            return PostReply.of(1002, "保存失败!：");
        }
        if (inserted != 1) {
            return PostReply.of(1002, "保存失败!：");
        }
        return new PostReply(1, b.audit() ? "谢谢，我们会尽快审核你的留言！" : "感谢你的留言！", true);
    }

    /** 发表留言（Gbook.php:43-117）。调用方已确认留言功能打开。参数含义同 {@link #postComment}。 */
    public PostReply postGbook(InteractionSettings s, Map<String, PhpInput.Value> params, Poster poster,
                               boolean timespanCookie, BooleanSupplier captcha, long ip) {
        BoardSettings b = s.gbook();
        PostReply pre = precheck(b, params, "gbook_content", poster, timespanCookie, captcha);
        if (pre != null) {
            return pre;
        }
        String content = entities(s, params, "gbook_content");
        String name = displayName(poster);
        int status = b.audit() ? 0 : 1;
        PostReply invalid = validateGbook(name, content);
        if (invalid != null) {
            return invalid;
        }
        // 旧版 gbook_rid 不做 intval，原样交给数据库：非数字、负数在严格模式下报错（500）
        Long rid = gbookRid(params);
        if (rid == null) {
            return PostReply.of(1002, "保存失败!：");
        }
        int inserted;
        try {
            inserted = writer().insertGbook(rid, poster.userId(), status, LegacyHtml.filterXss(name), ip, now(),
                    LegacyHtml.filterXss(content));
        } catch (DataAccessException e) {
            log.warn("gbook rejected by database: {}", SafeExceptionLog.typeChain(e));
            return PostReply.of(1002, "保存失败!：");
        }
        if (inserted != 1) {
            return PostReply.of(1002, "保存失败!：");
        }
        return new PostReply(1, b.audit() ? "谢谢，我们会尽快审核你的留言！" : "感谢你的留言！", true);
    }

    /** 两个入口共同的前置判断，顺序同旧版：验证码 → 登录 → 内容为空 → 频率 cookie。 */
    private PostReply precheck(BoardSettings b, Map<String, PhpInput.Value> params, String contentKey, Poster poster,
                               boolean timespanCookie, BooleanSupplier captcha) {
        if (b.verify() && !captcha.getAsBoolean()) {
            return PostReply.of(1002, "验证码错误");
        }
        if (b.login() && !poster.member()) {
            return PostReply.of(1003, "登录后才可以发表留言");
        }
        PhpInput.Value content = params.get(contentKey);
        if (content == null || (!content.array() && phpEmpty(content.bytes()))) {
            return PostReply.of(1004, "内容不能为空");
        }
        if (timespanCookie) {
            return PostReply.of(1005, "请不要频繁操作");
        }
        return null;
    }

    /**
     * {@code htmlentities(mac_filter_words($content))}。数组形式的参数：PHP 7.3 的 htmlentities 返回 null（之后校验"内容必须"），PHP 8
     * 抛类型错误（500）；新版按 7.3（同 D-B10-8 的取向）。
     */
    private static String entities(InteractionSettings s, Map<String, PhpInput.Value> params, String key) {
        PhpInput.Value v = params.get(key);
        if (v == null || v.array()) {
            return "";
        }
        return LegacyHtml.htmlentities(LegacyHtml.filterWords(v.bytes(), s.filterWords()));
    }

    /** 旧版：游客为"游客"；会员为 cookie 的用户名，库里昵称不为空时用昵称；再 {@code htmlentities(trim())}。 */
    private static String displayName(Poster poster) {
        String raw = poster.member() ? poster.name() : VISITOR;
        return LegacyHtml.htmlentities(LegacyHtml.phpTrim(raw.getBytes(StandardCharsets.UTF_8)));
    }

    /** 旧 {@code validate('Comment')}（application/common/validate/Comment.php）：按规则顺序报第一个 require 错误。 */
    private static PostReply validate(String name, String content) {
        if (!require(name)) {
            return PostReply.of(1001, "参数错误：昵称必须");
        }
        if (!require(content)) {
            return PostReply.of(1001, "参数错误：内容必须");
        }
        return null;
    }

    private static PostReply validateGbook(String name, String content) {
        return validate(name, content);
    }

    /** ThinkPHP {@code require}：{@code !empty($v) || '0' == $v}，即只有空串不通过。 */
    private static boolean require(String v) {
        return !v.isEmpty();
    }

    private static Long gbookRid(Map<String, PhpInput.Value> params) {
        PhpInput.Value v = params.get("gbook_rid");
        if (v == null) {
            return 0L;
        }
        if (v.array()) {
            return null;
        }
        String s = new String(v.bytes(), StandardCharsets.ISO_8859_1);
        if (!UNSIGNED.matcher(s).matches()) {
            return null;
        }
        long n = Long.parseLong(s);
        return n > UINT_MAX ? null : n;
    }

    // ------------------------------------------------------------------ 举报与顶踩

    /**
     * 举报一次（Comment.php:132-137 的 setInc；不存在的评论匹配 0 行，旧版同样返回"操作成功"）。
     *
     * @return 写入被数据库拒绝（举报数已到上限，旧版 500）时为 false
     */
    public boolean report(long id) {
        try {
            writer().reportComment(id);
            return true;
        } catch (DataAccessException e) {
            log.warn("comment report rejected by database: {}", SafeExceptionLog.typeChain(e));
            return false;
        }
    }

    /** 只读顶踩数。 */
    public CommentCounts counts(long id) {
        CommentRow row = reader.selectComment(id);
        return row == null ? CommentCounts.MISSING : new CommentCounts(true, false, nz(row.up()), nz(row.down()));
    }

    /** 顶（up）或踩一次再读（Comment.php:161-181、Ajax.php:322-344）。 */
    public CommentCounts digg(long id, boolean up) {
        try {
            writer().diggComment(id, up);
        } catch (DataAccessException e) {
            log.warn("comment digg rejected by database: {}", SafeExceptionLog.typeChain(e));
            return CommentCounts.REJECTED;
        }
        return counts(id);
    }

    // ------------------------------------------------------------------ 列表

    /**
     * 评论列表（旧 {@code {maccms:comment}} 标签的 {@code listCacheData}，Comment.php:97-195）：已审核的顶层评论（pid 为 0）或某条评论的回复，
     * 每条附带已审核的回复。
     *
     * @param by    旧 by：id/time/up/down，其他按 time
     * @param order 旧 order：asc/desc，其他按 desc
     */
    public BoardPage<CommentItem> comments(long rid, int mid, long pid, long userId, String by, String order, int page,
                                           int limit) {
        String sort = switch (by == null ? "" : by) {
            case "id" -> "ID";
            case "up" -> "UP";
            case "down" -> "DOWN";
            default -> "TIME";
        };
        boolean desc = !"asc".equals(order);
        CommentListCriteria c = new CommentListCriteria(rid, pid, mid, userId, sort, desc);
        int p = Math.max(1, Math.min(page, MAX_PAGE));
        long total = reader.countComments(c);
        List<CommentRow> rows = total == 0 ? List.of() : reader.selectComments(c, (long) (p - 1) * limit, limit);
        Map<Long, List<CommentItem>> replies = new LinkedHashMap<>();
        if (!rows.isEmpty()) {
            for (CommentRow r : reader.selectReplies(rows.stream().map(CommentRow::id).toList(), sort, desc,
                    MAX_REPLIES)) {
                replies.computeIfAbsent(r.pid(), k -> new ArrayList<>()).add(item(r, List.of()));
            }
        }
        List<CommentItem> items = rows.stream().map(r -> item(r, replies.getOrDefault(r.id(), List.of()))).toList();
        return new BoardPage<>(items, p, ceil(total, limit), limit, total);
    }

    /** 留言列表（Gbook.php:309-387）：已审核的留言。{@code by}：id/time/reply_time，其他按 time。 */
    public BoardPage<GbookItem> gbooks(long rid, long userId, String by, String order, int page, int limit) {
        String sort = switch (by == null ? "" : by) {
            case "id" -> "ID";
            case "reply_time" -> "REPLY_TIME";
            default -> "TIME";
        };
        boolean desc = !"asc".equals(order);
        int p = Math.max(1, Math.min(page, MAX_PAGE));
        long total = reader.countGbooks(rid, userId);
        List<GbookRow> rows = total == 0 ? List.of()
                : reader.selectGbooks(rid, userId, sort, desc, (long) (p - 1) * limit, limit);
        List<GbookItem> items = rows.stream().map(r -> new GbookItem(nz(r.id()), nz(r.rid()), nz(r.userId()),
                LegacyHtml.displayText(str(r.name())), nz(r.time()), LegacyHtml.displayText(str(r.content())),
                LegacyHtml.displayText(str(r.reply())), nz(r.replyTime()))).toList();
        return new BoardPage<>(items, p, ceil(total, limit), limit, total);
    }

    private static CommentItem item(CommentRow r, List<CommentItem> replies) {
        return new CommentItem(nz(r.id()), r.mid() == null ? 0 : r.mid(), nz(r.rid()), nz(r.userId()),
                LegacyHtml.displayText(str(r.name())), nz(r.time()), LegacyHtml.displayText(str(r.content())),
                nz(r.up()), nz(r.down()), replies);
    }

    // ------------------------------------------------------------------ 工具

    private CommentWriteMapper writer() {
        if (writer == null) {
            throw new IllegalStateException("可写数据源未打开");
        }
        return writer;
    }

    private static long intval(Map<String, PhpInput.Value> params, String key) {
        byte[] v = PhpInput.scalar(params, key);
        return v == null ? 0 : PhpIntval.of(new String(v, StandardCharsets.ISO_8859_1));
    }

    static boolean phpEmpty(byte[] v) {
        return v.length == 0 || (v.length == 1 && v[0] == '0');
    }

    private static long ceil(long total, int limit) {
        return (total + limit - 1) / limit;
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }
}
