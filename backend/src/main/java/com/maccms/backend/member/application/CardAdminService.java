package com.maccms.backend.member.application;

import static com.maccms.backend.member.application.MemberAdminSupport.guard;
import static com.maccms.backend.member.application.MemberAdminSupport.like;
import static com.maccms.backend.member.application.MemberAdminSupport.looseEquals;
import static com.maccms.backend.member.application.MemberAdminSupport.strictZeroOne;
import static com.maccms.backend.member.application.MemberAdminSupport.text;
import static com.maccms.backend.member.application.MemberAdminSupport.wd;

import com.maccms.backend.content.application.AdminIds;
import com.maccms.backend.content.application.AdminPaging;
import com.maccms.backend.content.application.AdminRejectedException;
import com.maccms.backend.content.application.PhpForm;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.content.application.view.AdminMessage;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRowPage;
import com.maccms.backend.member.infrastructure.CardInsert;
import com.maccms.backend.member.infrastructure.MemberAdminCriteria;
import com.maccms.backend.member.infrastructure.MemberAdminMapper;
import com.maccms.backend.member.infrastructure.MemberAdminWrites;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 后台充值卡管理（B14，23 文档第 7.3 节）：旧 {@code admin/controller/Card.php} → {@code common/model/Card.php}。列表（筛选、导出）、
 * 批量生成、删除与清空。
 */
public final class CardAdminService {

    /** 一次生成的上限（旧版不限，数量很大时整页内存耗尽，D-B14-4）。 */
    public static final int MAX_CARDS = 10_000;
    /** 导出的条数（旧 {@code $param['limit'] = 9999}）。 */
    public static final int EXPORT_LIMIT = 9999;
    /** 一条多行 INSERT 写入的卡数。 */
    static final int INSERT_BATCH = 500;

    private static final String MIXED = "234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String NUMBERS = "1234567890";
    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final MemberAdminMapper reader;
    private final MemberAdminWrites writer;
    private final Clock clock;
    private final RandomGenerator random;

    public CardAdminService(MemberAdminMapper reader, MemberAdminWrites writer, Clock clock, RandomGenerator random) {
        this.reader = reader;
        this.writer = writer;
        this.clock = clock;
        this.random = random;
    }

    /** 列表是否为导出（旧 {@code $param['export'] == '1'}，宽松比较）。 */
    public static boolean export(PhpForm p) {
        return looseEquals(p, "export", 1);
    }

    /**
     * 列表（旧 Card::index，Card.php:12-76）：售出、使用状态严格 {@code '0'/'1'}；卡号关键字；{@code time} 存在时（{@code isset}，含空串）
     * 宽松等于 1 为"最近一批"（{@code card_add_time >=} 最大添加时间，没有卡时为 0），否则为 N 天前的零点（{@link LegacyTime#daysAgoMidnight}）；
     * 导出时取第 1 页 9999 条。按编号倒序，另带使用者名称。
     */
    public AdminRowPage list(PhpForm p, AdminPaging paging) {
        MemberAdminCriteria c = new MemberAdminCriteria();
        c.saleStatus = strictZeroOne(p, "sale_status");
        c.useStatus = strictZeroOne(p, "use_status");
        String wd = wd(p);
        c.like = like(wd);
        if (p.has("time") && !p.isNull("time")) {
            if (looseEquals(p, "time", 1)) {
                Long max = reader.maxCardAddTime();
                c.timeFrom = max == null ? 0 : max;
            } else {
                c.timeFrom = LegacyTime.daysAgoMidnight(p.isArray("time") ? null : text(p.scalar("time")),
                        clock.instant().getEpochSecond());
            }
        }
        AdminPaging page = export(p) ? new AdminPaging(1, EXPORT_LIMIT) : paging;
        return MemberAdminSupport.page(page, wd, () -> reader.countCards(c), (o, l) -> reader.listCards(c, o, l));
    }

    /**
     * 批量生成（旧 Card::info 的 POST → {@code Card::saveAllData}，common/model/Card.php:80-96）：{@code num}、{@code money}、{@code point}
     * 任一 {@code empty()} → "参数错误"；按 {@code intval} 取值；数量小于 1 → "保存失败!："（旧版 {@code insertAll([])} 返回 false）；大于
     * {@link #MAX_CARDS} → "参数错误"（D-B14-4）。卡号 16 位、卡密 8 位，字符集按 {@code role_no}/{@code role_pwd}（{@code num} 数字、
     * {@code letter} 大写字母、其他为数字与大写字母混合，混合字符集里没有 1，照旧），以 0 开头时换成 1～9 的随机数字（旧 {@code mac_get_rndstr}，
     * application/common.php:318-337）；同一批里卡号相同的只留一条（旧版以卡号作数组键）。面值、积分超出列的范围时"参数错误"（旧版 500）。
     */
    public AdminResult generate(PhpForm post) {
        if (post.empty("num") || post.empty("money") || post.empty("point")) {
            return AdminResult.error("param_err");
        }
        return guard(() -> {
            if (post.isArray("num") || post.isArray("money") || post.isArray("point")) {
                throw AdminRejectedException.paramError();
            }
            long num = intval(post.scalar("num"));
            long money = intval(post.scalar("money"));
            long points = intval(post.scalar("point"));
            if (num < 1) {
                return AdminResult.error(AdminMessage.lang("save_err").thenText("："));
            }
            if (num > MAX_CARDS) {
                throw AdminRejectedException.paramError();
            }
            String noSet = charset(post, "role_no");
            String pwdSet = charset(post, "role_pwd");
            Map<String, String> cards = new LinkedHashMap<>();
            for (long i = 0; i < num; i++) {
                cards.put(randomString(16, noSet), randomString(8, pwdSet));
            }
            List<CardInsert> rows = new ArrayList<>();
            cards.forEach((no, pwd) -> rows.add(new CardInsert(no, pwd)));
            long now = clock.instant().getEpochSecond();
            for (int from = 0; from < rows.size(); from += INSERT_BATCH) {
                writer.insertCards(rows.subList(from, Math.min(rows.size(), from + INSERT_BATCH)), money, points, now);
            }
            return AdminResult.ok("save_ok");
        });
    }

    /** 删除（旧 Card::del）：{@code ids} 为空 → "参数错误"；{@code all == 1}（宽松）时清空，否则按 {@code ids}。 */
    public AdminResult delete(PhpForm p) {
        return deleteLike(p, writer::deleteAllCards, writer::deleteCards);
    }

    /** 订单、积分日志、访问日志、推广访问与卡密相同的删除规则（旧 {@code del()}：先要 {@code !empty($ids)}，再看 {@code all}）。 */
    static AdminResult deleteLike(PhpForm p, Runnable all, java.util.function.Consumer<List<Long>> byIds) {
        if (p.empty("ids")) {
            return AdminResult.error("param_err");
        }
        return guard(() -> {
            if (looseEquals(p, "all", 1)) {
                all.run();
            } else {
                byIds.accept(AdminIds.ids(p));
            }
            return AdminResult.ok("del_ok");
        });
    }

    private static String charset(PhpForm p, String k) {
        String v = p.isArray(k) ? null : p.text(k);
        if ("num".equals(v)) {
            return NUMBERS;
        }
        if ("letter".equals(v)) {
            return LETTERS;
        }
        return MIXED;
    }

    /** 旧 {@code mac_get_rndstr($length, $f)}（随机源为安全随机数，旧版 {@code mt_rand}）。 */
    String randomString(int length, String pattern) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(pattern.charAt(random.nextInt(pattern.length())));
        }
        if (sb.charAt(0) == '0') {
            sb.setCharAt(0, (char) ('1' + random.nextInt(9)));
        }
        return sb.toString();
    }

    private static long intval(byte[] raw) {
        return PhpIntval.of(new String(raw, StandardCharsets.ISO_8859_1));
    }
}
