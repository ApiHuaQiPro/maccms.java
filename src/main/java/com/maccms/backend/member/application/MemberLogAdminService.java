package com.maccms.backend.member.application;

import static com.maccms.backend.member.application.MemberAdminSupport.nonEmpty;
import static com.maccms.backend.member.application.MemberAdminSupport.prefixLike;

import com.maccms.backend.content.application.AdminPaging;
import com.maccms.backend.content.application.ContentLookupService;
import com.maccms.backend.content.application.PhpForm;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import com.maccms.backend.content.application.view.ContentTitle;
import com.maccms.backend.member.infrastructure.MemberAdminCriteria;
import com.maccms.backend.member.infrastructure.MemberAdminMapper;
import com.maccms.backend.member.infrastructure.MemberAdminWrites;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台积分日志、访问日志与推广访问管理（B14，23 文档第 7.6 节）：旧 {@code admin/controller/Plog.php}、{@code Ulog.php}、{@code Visit.php}。
 * 列表与删除、清空。
 */
public final class MemberLogAdminService {

    /** 按编号查内容名称时每批的编号数（不超过 {@code ContentLookupMapper.MAX_IDS}）。 */
    static final int TITLE_BATCH = 100;
    private static final byte[] HTTP = "http:".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HTTPS = "https:".getBytes(StandardCharsets.US_ASCII);

    private final MemberAdminMapper reader;
    private final MemberAdminWrites writer;
    private final ContentLookupService content;
    private final Clock clock;

    public MemberLogAdminService(MemberAdminMapper reader, MemberAdminWrites writer, ContentLookupService content, Clock clock) {
        this.reader = reader;
        this.writer = writer;
        this.content = content;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 积分日志（旧 Plog::index / del）

    /** 列表：类型、会员（均 {@code !empty}，原值），按编号倒序，另带会员名。 */
    public AdminRowPage plogs(PhpForm p, AdminPaging paging) {
        MemberAdminCriteria c = new MemberAdminCriteria();
        c.type = nonEmpty(p, "type");
        c.uid = nonEmpty(p, "uid");
        return MemberAdminSupport.page(paging, null, () -> reader.countPlogs(c), (o, l) -> reader.listPlogs(c, o, l));
    }

    public AdminResult deletePlogs(PhpForm p) {
        return CardAdminService.deleteLike(p, writer::deleteAllPlogs, writer::deletePlogs);
    }

    // ------------------------------------------------------------------ 访问日志（旧 Ulog::index / del）

    /**
     * 列表：模块、类型、会员（均 {@code !empty}，原值），按编号倒序，另带会员名与所属内容的名称（旧 {@code Ulog::listData} 对模块
     * 1/2/3/8 取视频/文章/专题/演员；取不到时为空，页面显示 {@code []}）。名称放在每行的 {@code data_name} 列。
     */
    public AdminRowPage ulogs(PhpForm p, AdminPaging paging) {
        MemberAdminCriteria c = new MemberAdminCriteria();
        c.mid = nonEmpty(p, "mid");
        c.type = nonEmpty(p, "type");
        c.uid = nonEmpty(p, "uid");
        AdminRowPage page = MemberAdminSupport.page(paging, null, () -> reader.countUlogs(c), (o, l) -> reader.listUlogs(c, o, l));
        Map<Integer, Set<Long>> wanted = new HashMap<>();
        for (AdminRow r : page.rows()) {
            int mid = (int) r.n("ulog_mid");
            if (ContentLookupService.TITLE_MIDS.contains(mid)) {
                wanted.computeIfAbsent(mid, k -> new LinkedHashSet<>()).add(r.n("ulog_rid"));
            }
        }
        // 一次最多查 TITLE_BATCH 个编号（ContentLookupService 的上限），一页最多 1000 行，按批查
        Map<Integer, Map<Long, ContentTitle>> titles = new HashMap<>();
        wanted.forEach((mid, ids) -> {
            List<Long> all = new ArrayList<>(ids);
            Map<Long, ContentTitle> found = new HashMap<>();
            for (int from = 0; from < all.size(); from += TITLE_BATCH) {
                found.putAll(content.titles(mid, all.subList(from, Math.min(all.size(), from + TITLE_BATCH))));
            }
            titles.put(mid, found);
        });
        List<AdminRow> rows = new ArrayList<>();
        for (AdminRow r : page.rows()) {
            Map<String, String> v = new LinkedHashMap<>(r.values());
            ContentTitle t = titles.getOrDefault((int) r.n("ulog_mid"), Map.of()).get(r.n("ulog_rid"));
            v.put("data_name", t == null ? "" : t.name());
            rows.add(new AdminRow(v));
        }
        return new AdminRowPage(rows, page.total(), page.page(), page.limit(), null);
    }

    public AdminResult deleteUlogs(PhpForm p) {
        return CardAdminService.deleteLike(p, writer::deleteAllUlogs, writer::deleteUlogs);
    }

    // ------------------------------------------------------------------ 推广访问（旧 Visit::index / del）

    /**
     * 列表（旧 Visit::index，Visit.php:12-58）：会员（{@code !empty}）；{@code time} 存在时为 N 天前的零点起（{@link LegacyTime#daysAgoMidnight}）；
     * 关键字 {@code wd}（原值，不转义）按来源前缀匹配：旧代码本意是"已带协议时另试另一种协议"，但比较写成了 {@code substr($a,5)}（第 5 个
     * 字节之后的部分），实际几乎总是走 {@code 'http://'.wd} 与 {@code 'https://'.wd} 两个前缀——照旧。按编号倒序；旧版不取会员名。
     */
    public AdminRowPage visits(PhpForm p, AdminPaging paging) {
        MemberAdminCriteria c = new MemberAdminCriteria();
        c.uid = nonEmpty(p, "uid");
        if (p.has("time") && !p.isNull("time")) {
            c.timeFrom = LegacyTime.daysAgoMidnight(p.isArray("time") ? null : p.text("time"), clock.instant().getEpochSecond());
        }
        if (!p.empty("wd") && !p.isArray("wd")) {
            byte[][] ab = visitPrefixes(p.scalar("wd"));
            c.lyLikeA = prefixLike(new String(ab[0], StandardCharsets.UTF_8));
            c.lyLikeB = prefixLike(new String(ab[1], StandardCharsets.UTF_8));
        }
        return MemberAdminSupport.page(paging, null, () -> reader.countVisits(c), (o, l) -> reader.listVisits(c, o, l));
    }

    /** 旧 Visit::index 的两个来源前缀（规则见 {@link #visits}）。 */
    static byte[][] visitPrefixes(byte[] wd) {
        byte[] tail = wd.length > 5 ? Arrays.copyOfRange(wd, 5, wd.length) : new byte[0];
        if (Arrays.equals(tail, HTTP)) {
            return new byte[][] {wd, replace(wd, HTTP, HTTPS)};
        }
        if (Arrays.equals(tail, "https".getBytes(StandardCharsets.US_ASCII))) {
            return new byte[][] {wd, replace(wd, HTTPS, HTTP)};
        }
        return new byte[][] {concat("http://", wd), concat("https://", wd)};
    }

    public AdminResult deleteVisits(PhpForm p) {
        return CardAdminService.deleteLike(p, writer::deleteAllVisits, writer::deleteVisits);
    }

    private static byte[] concat(String prefix, byte[] wd) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(prefix.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(wd);
        return out.toByteArray();
    }

    /** PHP {@code str_replace}（全部出现处，按字节）。 */
    private static byte[] replace(byte[] s, byte[] from, byte[] to) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < s.length) {
            if (i + from.length <= s.length && Arrays.equals(Arrays.copyOfRange(s, i, i + from.length), from)) {
                out.writeBytes(to);
                i += from.length;
            } else {
                out.write(s[i++]);
            }
        }
        return out.toByteArray();
    }
}
