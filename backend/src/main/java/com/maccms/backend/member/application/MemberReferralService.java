package com.maccms.backend.member.application;

import com.maccms.backend.common.web.PhpCookies;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.common.write.WriteConflictException;
import com.maccms.backend.common.write.WriteSequence;
import com.maccms.backend.member.infrastructure.MemberFinanceWriteMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * 推广（B10，docs/architecture/19-b10-member-finance.md 第 4.11、4.13 节）：推广访问 {@code user/visit} 给推广人加积分
 * （{@code common/model/User.php:848-892}），以及推广注册给邀请人加 {@code invite_reg_points}（D-B09-7，{@code :236-264}）。
 *
 * <p>推广访问的"今日次数未满才记"合成一条条件插入（同一 IP 并发访问不会超额，旧版先数后插可超额，D-B10-4）。加积分与流水是
 * "加积分 → 记流水"序列；加积分匹配 0 行（推广人不存在）时与旧版 {@code if($res)} 一样不写流水。积分为 0 时旧版的 {@code setInc}
 * 影响 0 行、同样不写流水，这里直接跳过。日志不记录会员、IP 与来路。
 */
public class MemberReferralService {

    private static final Logger log = LoggerFactory.getLogger(MemberReferralService.class);

    private final MemberFinanceWriteMapper writer;
    private final MemberFinanceSettings settings;
    private final Clock clock;

    public MemberReferralService(MemberFinanceWriteMapper writer, MemberFinanceSettings settings, Clock clock) {
        this.writer = writer;
        this.settings = settings;
        this.clock = clock;
    }

    public boolean available() {
        return writer != null;
    }

    /**
     * 推广访问（旧 {@code User::visit}）。结果只影响库，页面一律跳转（旧控制器忽略返回值）。
     *
     * @param clientIp {@code ClientIpResolver} 的结果
     * @param referer  请求头 {@code Referer} 的原始字节，没有时为 null
     * @return 旧版的返回码（1 成功、101 编号错误、102 次数已满、103 记录失败），只用于测试与日志
     */
    public int visit(Map<String, PhpInput.Value> params, String clientIp, byte[] referer) {
        if (writer == null) {
            throw new IllegalStateException("推广访问需要可写数据源");
        }
        long uid = Math.abs(FinanceSupport.intval(params, "uid"));
        if (uid == 0 || uid == Long.MIN_VALUE) {
            return 101;
        }
        long ip = MemberAccountService.legacyIp(clientIp);
        long now = FinanceSupport.now(clock);
        int inserted;
        try {
            inserted = writer.insertVisit(uid, ip, referer(referer), now, FinanceSupport.todayStart(now),
                    settings.inviteVisitMax());
        } catch (DataAccessException e) {
            // 例如来路超过 visit_ly 的 100 字符：旧版 500，新版不记、照常跳转（19 文档 D-B10-3）
            log.warn("member visit rejected by database: {}", e.getClass().getSimpleName());
            return 103;
        }
        if (inserted != 1) {
            return 102;
        }
        credit(uid, settings.inviteVisitPoints(), 3, now);
        return 1;
    }

    /**
     * 推广注册给邀请人的积分（D-B09-7）：在新会员的推广关系写入成功之后调用（推广关系以 {@code user_pid = 0} 为条件，每个新会员只生效一次）。
     */
    public void invitedRegistration(long inviterId) {
        if (writer == null) {
            return;
        }
        credit(inviterId, settings.inviteRegPoints(), 2, FinanceSupport.now(clock));
    }

    /** 加积分 → 记流水；积分 ≤ 0 或会员不存在时什么都不写。 */
    private void credit(long userId, long points, int plogType, long now) {
        if (points <= 0) {
            return;
        }
        try {
            WriteSequence.named("member.referral")
                    .step(WriteSequence.Role.TRANSFER, "points", () -> writer.addPoints(userId, points), 1,
                            () -> writer.deductPoints(userId, points))
                    .step(WriteSequence.Role.RECORD, "plog", () -> writer.insertPlog(userId, plogType, points, now, ""), 1,
                            null)
                    .build().run();
        } catch (WriteConflictException e) {
            // 会员不存在：旧版 setInc 影响 0 行，不写流水
        } catch (RuntimeException e) {
            log.warn("member referral credit failed: {}", SafeExceptionLog.typeChain(e));
        }
    }

    /**
     * {@code htmlspecialchars(mac_get_refer())}，{@code mac_get_refer() = trim(urldecode($_SERVER['HTTP_REFERER']))}
     * （application/common.php:353-356）：先 urldecode 再 trim（与 {@code clean()} 的顺序相反）；不是合法 UTF-8 时为空串。
     */
    static String referer(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        byte[] decoded = LegacyText.trim(PhpCookies.urlDecode(raw));
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decoded)).toString();
        } catch (CharacterCodingException e) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
