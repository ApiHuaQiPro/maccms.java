package com.maccms.backend.member.application;

import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.common.write.CompensationFailedException;
import com.maccms.backend.common.write.WriteConflictException;
import com.maccms.backend.common.write.WriteSequence;
import com.maccms.backend.content.application.ContentLookupService;
import com.maccms.backend.content.application.view.ArticlePoints;
import com.maccms.backend.content.application.view.VideoPoints;
import com.maccms.backend.member.application.view.JsonReply;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.infrastructure.MemberFinanceMapper;
import com.maccms.backend.member.infrastructure.MemberFinanceWriteMapper;
import com.maccms.backend.member.infrastructure.WalletRow;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * 积分购买观看/下载/文章（{@code ajax_buy_popedom}，index/controller/User.php:101-173；19 文档第 4.2 节）。
 *
 * <p>写入顺序：<b>认领</b>（条件插入 {@code mac_ulog}：七项相同的记录不存在才插入）→ <b>扣积分</b>（{@code WHERE user_points >= ?}）→
 * <b>记流水</b>（{@code mac_plog} type 8）→ 分销奖励（{@link MemberRewards}）。认领匹配 0 行即"已经购买过"；扣积分匹配 0 行时删除刚认领的
 * 记录后按积分不足返回。同一会员对同一数据的并发购买只扣一次（旧版先查后扣，可以扣两次，D-B10-4）。
 */
public class MemberPurchaseService {

    private static final Logger log = LoggerFactory.getLogger(MemberPurchaseService.class);
    private static final Set<String> MIDS = Set.of("1", "2");
    private static final Set<String> TYPES = Set.of("1", "4", "5");

    private final MemberFinanceMapper reader;
    private final MemberFinanceWriteMapper writer;
    private final ContentLookupService content;
    private final MemberRewards rewards;
    private final Clock clock;

    public MemberPurchaseService(MemberFinanceMapper reader, MemberFinanceWriteMapper writer,
                                 ContentLookupService content, MemberFinanceSettings settings, Clock clock) {
        this.reader = reader;
        this.writer = writer;
        this.content = content;
        this.rewards = new MemberRewards(writer, settings);
        this.clock = clock;
    }

    public boolean available() {
        return writer != null;
    }

    /** 购买的目标（已按旧版规则换算好的七项中的六项）。 */
    private record Target(int mid, int type, long rid, int sid, int nid, int points) {
    }

    public JsonReply buy(MemberState member, Map<String, PhpInput.Value> params) {
        if (writer == null) {
            throw new IllegalStateException("购买需要可写数据源");
        }
        String midText = FinanceSupport.text(params, "mid");
        String typeText = FinanceSupport.text(params, "type");
        long rid = FinanceSupport.intval(params, "id");
        // 旧版 in_array 是宽松比较（'01'、'1.0' 都算 '1'），新版按字节（D-B10-1）
        if (!MIDS.contains(midText) || !TYPES.contains(typeText) || rid == 0) {
            return JsonReply.of(2001, "参数错误");
        }
        int mid = Integer.parseInt(midText);
        int type = Integer.parseInt(typeText);
        int sid = FinanceSupport.intvalInt(params, "sid");
        int nid = FinanceSupport.intvalInt(params, "nid");
        Target target;
        if (type == 1) {
            Optional<ArticlePoints> art = content.articlePoints(rid);
            if (art.isEmpty()) {
                return JsonReply.of(1002, "获取失败").wrapped();
            }
            boolean whole = content.articlePointsWhole();
            target = new Target(mid, type, rid, whole ? 0 : sid, whole ? 0 : nid,
                    whole ? art.get().whole() : art.get().detail());
        } else {
            Optional<VideoPoints> vod = content.videoPoints(rid);
            if (vod.isEmpty()) {
                return JsonReply.of(1002, "获取失败").wrapped();
            }
            boolean whole = content.videoPointsWhole();
            int points = whole ? vod.get().whole() : type == 4 ? vod.get().play() : vod.get().down();
            target = new Target(mid, type, rid, whole ? 0 : sid, whole ? 0 : nid, points);
        }
        long userId = member.userId();
        if (reader.selectUlogMatch(userId, target.mid(), target.type(), target.rid(), target.sid(), target.nid(),
                target.points()) != null) {
            return purchased();
        }
        WalletRow wallet = reader.selectWallet(userId);
        if (wallet == null) {
            return JsonReply.of(2003, "对不起,更新用户积分信息失败，请刷新重试！");
        }
        if (target.points() > FinanceSupport.nz(wallet.points())) {
            return notEnough(target.points(), FinanceSupport.nz(wallet.points()));
        }
        long now = FinanceSupport.now(clock);
        try {
            WriteSequence.named("member.buy_popedom")
                    .step(WriteSequence.Role.CLAIM, "ulog", () -> writer.claimUlog(userId, target.mid(), target.type(),
                                    target.rid(), target.sid(), target.nid(), target.points(), now), 1,
                            () -> writer.releaseUlog(userId, target.mid(), target.type(), target.rid(), target.sid(),
                                    target.nid(), target.points(), now))
                    .step(WriteSequence.Role.TRANSFER, "points", () -> writer.deductPoints(userId, target.points()), 1,
                            () -> writer.addPoints(userId, target.points()))
                    .step(WriteSequence.Role.RECORD, "plog", () -> writer.insertPlog(userId, 8, target.points(), now, ""),
                            1, null)
                    .build().run();
        } catch (WriteConflictException e) {
            if (e.step().equals("ulog")) {
                return purchased();
            }
            // 积分在检查之后被并发花掉：认领已删除，按积分不足返回（积分重读）
            WalletRow again = reader.selectWallet(userId);
            return notEnough(target.points(), again == null ? 0 : FinanceSupport.nz(again.points()));
        } catch (CompensationFailedException e) {
            return JsonReply.of(2003, "对不起,更新用户积分信息失败，请刷新重试！");
        } catch (DataAccessException e) {
            // 服务端拒绝（例如 sid 超出 tinyint）：前序步骤已补偿（旧版此时已扣积分、记流水，再报 500，D-B10-3）
            log.warn("member buy rejected by database: {}", SafeExceptionLog.typeChain(e));
            return JsonReply.of(1004, "保存失败!：");
        }
        rewards.pay(wallet, target.points(), now);
        return JsonReply.of(1, "保存成功!");
    }

    private static JsonReply purchased() {
        return JsonReply.of(1, "您已经购买过此条数据，无需再次支付，请刷新页面重试！");
    }

    private static JsonReply notEnough(int need, long have) {
        return JsonReply.of(2002, "对不起,查看此页面数据需要[" + need + "]积分，您还剩下[" + have + "]积分，请先充值！");
    }
}
