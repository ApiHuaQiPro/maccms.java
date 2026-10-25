package com.maccms.backend.member.application;

import static com.maccms.backend.member.application.FinanceSupport.nz;

import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.content.application.ContentLookupService;
import com.maccms.backend.content.application.ContentPurchases;
import com.maccms.backend.content.application.view.VideoPoints;
import com.maccms.backend.member.application.view.FinanceRecords;
import com.maccms.backend.member.application.view.JsonReply;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.application.view.RecordPage;
import com.maccms.backend.member.infrastructure.MemberFinanceMapper;
import com.maccms.backend.member.infrastructure.MemberFinanceWriteMapper;
import com.maccms.backend.member.infrastructure.PlogRow;
import com.maccms.backend.member.infrastructure.UlogRow;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * 会员记录与积分日志（B10，docs/architecture/19-b10-member-finance.md 第 4.1、4.3、4.4、5.1、5.4 节）：{@code ajax_ulog}
 * （记录与列表）、{@code plays}/{@code downs}/{@code favs}/{@code ulog} 页、{@code ulog_del}、{@code plog} 页、{@code plog_del}，以及
 * 内容页用的已购买查询（{@link #purchases}）与播放/下载记录（{@link #recordView}）。返回码与提示逐条按旧
 * {@code index/controller/User.php} 与 {@code common/model/Ulog.php}、{@code Plog.php}，由 {@code member/finance-php{80,73}-golden.json} 锁定。
 *
 * <p>写库只经 {@link MemberFinanceWriteMapper}，每条语句检查匹配行数；记录的"先查后插"合成一条条件插入（同一会员连点两次只有一行）。
 * 日志不记录会员、参数与内容编号。
 */
public class MemberRecordService {

    private static final Logger log = LoggerFactory.getLogger(MemberRecordService.class);
    private static final Set<String> MIDS = Set.of("1", "2", "3", "8");
    private static final Set<String> TYPES = Set.of("1", "2", "3", "4", "5");

    private final MemberFinanceMapper reader;
    private final MemberFinanceWriteMapper writer;
    private final ContentLookupService content;
    private final Clock clock;

    /** @param writer 可写数据源关闭时为 null（只读的列表与已购买查询照常可用） */
    public MemberRecordService(MemberFinanceMapper reader, MemberFinanceWriteMapper writer,
                               ContentLookupService content, Clock clock) {
        this.reader = reader;
        this.writer = writer;
        this.content = content;
        this.clock = clock;
    }

    public boolean available() {
        return writer != null;
    }

    // ------------------------------------------------------------------ 已购买查询（D-B09-11）

    /** 本会员的已购买查询（只读 mac_ulog）；会员编号不是正数时一律未购买。 */
    public ContentPurchases purchases(long userId) {
        if (userId <= 0) {
            return ContentPurchases.NONE;
        }
        return key -> reader.selectUlogMatch(userId, key.mid(), key.type(), key.rid(), key.sid(), key.nid(),
                key.points()) != null;
    }

    // ------------------------------------------------------------------ ajax_ulog

    /**
     * {@code ajax_ulog} 的 {@code ac=set}（User.php:50-80）：记一笔。其他 {@code ac} 是 JSON 列表，见 {@link #ulogJsonList}（列表项的
     * {@code data} 与 {@code user_name} 由 site 组装）。
     */
    public JsonReply ulogSet(MemberState member, Map<String, PhpInput.Value> params) {
        requireWriter();
        return record(member.userId(), FinanceSupport.intvalInt(params, "mid"), FinanceSupport.intval(params, "id"),
                FinanceSupport.intvalInt(params, "type"), FinanceSupport.intvalInt(params, "sid"),
                FinanceSupport.intvalInt(params, "nid"));
    }

    /** {@code $param['ac'] == 'set'}（按字节相等）。 */
    public static boolean isSet(Map<String, PhpInput.Value> params) {
        return FinanceSupport.text(params, "ac").equals("set");
    }

    /**
     * 已登录会员打开播放页/下载页时的记录（旧模板 {@code .mac_ulog_set} 在页面加载后请求 {@code ajax_ulog?ac=set}，home.js:519-523；
     * 19 文档第 5.4 节）。可写数据源关闭或写库失败时什么都不做（只记一行 WARN），不影响页面。
     *
     * @param type 4 播放、5 下载
     */
    public void recordView(MemberState member, int type, long vodId, int sid, int nid) {
        if (writer == null || !member.member()) {
            return;
        }
        try {
            record(member.userId(), 1, vodId, type, sid, nid);
        } catch (RuntimeException e) {
            log.warn("member view record failed: {}", SafeExceptionLog.typeChain(e));
        }
    }

    private JsonReply record(long userId, int mid, long rid, int type, int sid, int nid) {
        int points = 0;
        if (mid == 1 && type > 3) {
            // 旧版按 vod_id 查视频（不看审核状态），积分取 vod_points_play/down，不看 vod_points_type（User.php:59-68）
            Optional<VideoPoints> vod = content.videoPoints(rid);
            if (vod.isEmpty()) {
                return JsonReply.of(1002, "获取失败");
            }
            points = type == 4 ? vod.get().play() : vod.get().down();
        }
        UlogRow found = reader.selectUlogMatch(userId, mid, type, rid, sid, nid, points);
        if (found != null) {
            return touched(found, userId, mid, type, rid, sid, nid, points);
        }
        if (points != 0) {
            return JsonReply.of(2001, "收费收据需单独记录");
        }
        // 旧 Ulog::saveData 的检查（common/model/Ulog.php:142-145）
        if (userId == 0 || !List.of(1, 2, 3, 8).contains(mid) || type < 1 || type > 5) {
            return JsonReply.of(1002, "参数错误");
        }
        int inserted;
        try {
            inserted = writer.claimUlog(userId, mid, type, rid, sid, nid, 0, FinanceSupport.now(clock));
        } catch (DataAccessException e) {
            // 例如 sid 超出 tinyint：旧版 ThinkPHP 抛异常（500），新版返回保存失败（D-B10-3）
            log.warn("member ulog rejected by database: {}", e.getClass().getSimpleName());
            return JsonReply.of(1004, "保存失败!：");
        }
        if (inserted == 1) {
            return JsonReply.of(1, "保存成功!");
        }
        // 并发的同一请求刚插入：按"已有记录"返回
        UlogRow again = reader.selectUlogMatch(userId, mid, type, rid, sid, nid, 0);
        return again == null ? JsonReply.of(1004, "保存失败!：") : touched(again, userId, mid, type, rid, sid, nid, 0);
    }

    /** 已有记录：刷新时间（全部七项相同的记录），返回更新前的那一行（旧 {@code infoData} 的结果）。 */
    private JsonReply touched(UlogRow found, long userId, int mid, int type, long rid, int sid, int nid, int points) {
        writer.touchUlog(userId, mid, type, rid, sid, nid, points, FinanceSupport.now(clock));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 1);
        body.put("msg", "获取成功");
        body.put("info", ulogJson(found));
        return JsonReply.of(body);
    }

    private static Map<String, Object> ulogJson(UlogRow r) {
        return ulogJson(toRecord(r));
    }

    /** mac_ulog 一行按表的列顺序（旧 JSON 的 info 与列表项的前 9 个键）。 */
    public static Map<String, Object> ulogJson(FinanceRecords.Ulog r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ulog_id", r.id());
        m.put("user_id", r.userId());
        m.put("ulog_mid", r.mid());
        m.put("ulog_type", r.type());
        m.put("ulog_rid", r.rid());
        m.put("ulog_sid", r.sid());
        m.put("ulog_nid", r.nid());
        m.put("ulog_points", r.points());
        m.put("ulog_time", r.time());
        return m;
    }

    /** {@code ajax_ulog} 的列表（User.php:81-97）：{@code limit < 1} 时 10；{@code mid}、{@code id}、{@code type} 的 intval &gt; 0 时加条件。 */
    public RecordPage<FinanceRecords.Ulog> ulogJsonList(MemberState member, Map<String, PhpInput.Value> params) {
        int page = FinanceSupport.page(params);
        int limit = FinanceSupport.limit(params, 1, 10);
        int mid = (int) Math.max(0, Math.min(Integer.MAX_VALUE, FinanceSupport.intval(params, "mid")));
        long rid = Math.max(0, FinanceSupport.intval(params, "id"));
        int type = (int) Math.max(0, Math.min(Integer.MAX_VALUE, FinanceSupport.intval(params, "type")));
        return ulogs(member.userId(), mid, rid, type, page, limit);
    }

    // ------------------------------------------------------------------ 记录列表页

    /** {@code plays}（User.php:596-614）。 */
    public RecordPage<FinanceRecords.Ulog> plays(MemberState member, Map<String, PhpInput.Value> params) {
        return ulogs(member.userId(), 1, 0, 4, FinanceSupport.page(params), FinanceSupport.limit(params, 20, 20));
    }

    /** {@code downs}（User.php:616-634）。 */
    public RecordPage<FinanceRecords.Ulog> downs(MemberState member, Map<String, PhpInput.Value> params) {
        return ulogs(member.userId(), 1, 0, 5, FinanceSupport.page(params), FinanceSupport.limit(params, 20, 20));
    }

    /** {@code favs}（User.php:636-656）：{@code mid} 为 1/2/3/8（按字节）时加条件。 */
    public RecordPage<FinanceRecords.Ulog> favs(MemberState member, Map<String, PhpInput.Value> params) {
        return ulogs(member.userId(), filterMid(params), 0, 2, FinanceSupport.page(params),
                FinanceSupport.limit(params, 20, 20));
    }

    /** {@code ulog}（User.php:658-681）：{@code mid}、{@code type} 在白名单内（按字节）时加条件。 */
    public RecordPage<FinanceRecords.Ulog> all(MemberState member, Map<String, PhpInput.Value> params) {
        return ulogs(member.userId(), filterMid(params), 0, filterType(params), FinanceSupport.page(params),
                FinanceSupport.limit(params, 20, 20));
    }

    /** 列表页的模型筛选（1/2/3/8，按字节）；不在白名单时为 0。 */
    public static int filterMid(Map<String, PhpInput.Value> params) {
        String mid = FinanceSupport.text(params, "mid");
        return MIDS.contains(mid) ? Integer.parseInt(mid) : 0;
    }

    /** 列表页的类型筛选（1～5，按字节）；不在白名单时为 0。 */
    public static int filterType(Map<String, PhpInput.Value> params) {
        String type = FinanceSupport.text(params, "type");
        return TYPES.contains(type) ? Integer.parseInt(type) : 0;
    }

    private RecordPage<FinanceRecords.Ulog> ulogs(long userId, int mid, long rid, int type, int page, int limit) {
        long total = reader.countUlogs(userId, mid, rid, type);
        long offset = (long) (page - 1) * limit;
        List<FinanceRecords.Ulog> rows = offset >= total ? List.of()
                : reader.selectUlogs(userId, mid, rid, type, offset, limit).stream()
                        .map(MemberRecordService::toRecord).toList();
        return new RecordPage<>(rows, total, page, limit);
    }

    private static FinanceRecords.Ulog toRecord(UlogRow r) {
        return new FinanceRecords.Ulog(nz(r.ulogId()), nz(r.userId()), nz(r.mid()), nz(r.type()), nz(r.rid()),
                nz(r.sid()), nz(r.nid()), nz(r.points()), nz(r.time()));
    }

    // ------------------------------------------------------------------ ulog_del

    /** {@code ulog_del}（User.php:683-713）。 */
    public JsonReply ulogDelete(MemberState member, Map<String, PhpInput.Value> params) {
        requireWriter();
        String ids = LegacyText.clean(FinanceSupport.raw(params, "ids"));
        String type = FinanceSupport.text(params, "type");
        byte[] all = FinanceSupport.raw(params, "all");
        if (!TYPES.contains(type)) {
            return JsonReply.of(1001, "参数错误");
        }
        if (LegacyText.empty(ids) && LegacyText.empty(all)) {
            return JsonReply.of(1001, "参数错误");
        }
        Set<Long> list = FinanceSupport.ids(ids);
        if (list == null) {
            return JsonReply.of(1001, "参数错误");
        }
        writer.deleteUlogs(member.userId(), Integer.parseInt(type), isAll(all) ? null : list);
        return JsonReply.of(1, "删除成功");
    }

    // ------------------------------------------------------------------ plog

    /** {@code plog}（User.php:715-731）：plog_id desc。 */
    public RecordPage<FinanceRecords.Plog> plogs(MemberState member, Map<String, PhpInput.Value> params) {
        int page = FinanceSupport.page(params);
        int limit = FinanceSupport.limit(params, 20, 20);
        long total = reader.countPlogs(member.userId());
        long offset = (long) (page - 1) * limit;
        List<FinanceRecords.Plog> rows = offset >= total ? List.of()
                : reader.selectPlogs(member.userId(), offset, limit).stream().map(MemberRecordService::toRecord)
                        .toList();
        return new RecordPage<>(rows, total, page, limit);
    }

    private static FinanceRecords.Plog toRecord(PlogRow r) {
        return new FinanceRecords.Plog(nz(r.plogId()), nz(r.type()), nz(r.points()), nz(r.time()),
                FinanceSupport.str(r.remarks()));
    }

    /** {@code plog_del}（User.php:733-758）。 */
    public JsonReply plogDelete(MemberState member, Map<String, PhpInput.Value> params) {
        requireWriter();
        String ids = LegacyText.clean(FinanceSupport.raw(params, "ids"));
        byte[] all = FinanceSupport.raw(params, "all");
        if (LegacyText.empty(ids) && LegacyText.empty(all)) {
            return JsonReply.of(1001, "参数错误");
        }
        Set<Long> list = FinanceSupport.ids(ids);
        if (list == null) {
            return JsonReply.of(1001, "参数错误");
        }
        writer.deletePlogs(member.userId(), isAll(all) ? null : list);
        return JsonReply.of(1, "删除成功");
    }

    /** {@code $all != '1'} 的反面（按字节，D-B10-1）。 */
    static boolean isAll(byte[] all) {
        return all.length == 1 && all[0] == '1';
    }

    private void requireWriter() {
        if (writer == null) {
            throw new IllegalStateException("会员记录功能需要可写数据源");
        }
    }
}
