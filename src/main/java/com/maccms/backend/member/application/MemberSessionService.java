package com.maccms.backend.member.application;

import com.maccms.backend.common.web.PhpCookies;
import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.member.application.view.MemberGroup;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.application.view.PageMember;
import com.maccms.backend.member.infrastructure.MemberGroupRow;
import com.maccms.backend.member.infrastructure.MemberLoginRow;
import com.maccms.backend.member.infrastructure.MemberMapper;
import com.maccms.backend.member.infrastructure.MemberWriteMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 会员登录态校验（B08，docs/architecture/17-b08-member-session-captcha.md 第 2.2 节）：按旧 {@code label_user}
 * （{@code common/controller/All.php:118-147}）+ {@code checkLogin}（{@code common/model/User.php:422-473}）从请求 cookie
 * 判定当前会员。
 *
 * <ol>
 * <li>前置判断（{@code label_user}）：{@code intval(user_id)}、{@code user_name}、{@code user_check} 三者在 PHP {@code empty()}
 * 意义下都非空，否则直接是游客；</li>
 * <li>三个值各做 {@code htmlspecialchars(urldecode(trim()))}，任一为空 → 游客；</li>
 * <li>{@code SELECT … WHERE user_id = ? AND user_name = ? AND user_status = 1}（两个参数都按字符串，比较规则交给 MySQL）；
 * 查不到 → 游客；</li>
 * <li>用库里的 {@code user_random}、{@code user_name}、{@code user_id} 算 {@link UserCheck}，与 cookie 逐字节相同才算登录；</li>
 * <li>有效会员组：库里 {@code group_id > 2} 且 {@code user_end_time < 现在}（秒，等于现在不算到期）时按 2 组。</li>
 * </ol>
 *
 * <p>{@link #resolve} 只读、只在内存中判定（B08）。{@link #resolvePage} 是 B09 的页面接线（18 文档第 5 节）：校验失败时给出游客回写
 * cookie，到期时执行单行降级写库并重发组 cookie（可写数据源关闭时退回只读判定）。
 *
 * <p>与旧版的有意差异（17 文档第 5 节）：数组形式的 cookie 按游客（PHP 8 抛类型错误、7.3 告警后按游客）；{@code user_check} 只接受完全
 * 相同的值（D-B08-1，旧版 {@code !=} 会把 {@code 0e…} 形式的数值串判为相等）；库里 {@code user_random} 为空（注册后从未登录、后台建号）时
 * 不认任何 cookie（D-B08-2，旧版可凭 {@code md5("-用户名-ID-")} 伪造）；含 4 字节字符（库里 utf8 列存不下）的值直接按游客，不查库。
 */
@Service
public class MemberSessionService {

    private static final Logger log = LoggerFactory.getLogger(MemberSessionService.class);

    static final String USER_ID = "user_id";
    static final String USER_NAME = "user_name";
    static final String USER_CHECK = "user_check";

    private final MemberMapper mapper;
    private final MemberWriteMapper writer;
    private final Clock clock;

    @Autowired
    public MemberSessionService(MemberMapper mapper, ObjectProvider<MemberWriteMapper> writer) {
        this(mapper, writer.getIfAvailable(), Clock.systemUTC());
    }

    public MemberSessionService(MemberMapper mapper, Clock clock) {
        this(mapper, null, clock);
    }

    /** @param writer 可写数据源关闭时为 null（只读判定，同 B08） */
    public MemberSessionService(MemberMapper mapper, MemberWriteMapper writer, Clock clock) {
        this.mapper = mapper;
        this.writer = writer;
        this.clock = clock;
    }

    /** 按 {@link PhpCookies#parse} 的结果判定，只读（B08）。 */
    public MemberState resolve(Map<String, PhpCookies.Value> cookies) {
        long now = clock.instant().getEpochSecond();
        Cleaned c = precheck(cookies);
        MemberLoginRow row = c == null ? null : authenticate(c);
        return row == null ? guest() : stateOf(row, now);
    }

    /**
     * 一次前台页面请求的 {@code label_user}（B09，18 文档第 5 节）：
     * <ul>
     * <li>没有带齐三个会员 cookie 或前置判断不通过 → 游客，不查库、不写 cookie；</li>
     * <li>查库校验失败 → 游客，写 {@link MemberCookies#guestReset()} 两个游客 cookie（旧 {@code All.php:136-138}）；</li>
     * <li>已登录且到期 → {@code UPDATE … SET group_id = 2 WHERE user_id = ? AND user_status = 1 AND group_id > 2 AND
     * user_end_time < 现在}：匹配 1 行时按 2 组并重发组 cookie；匹配 0 行时重读一次——已被并发请求降为 2 组则同样按 2 组并重发，
     * 已续费则按续费后的组、不重发；可写数据源关闭时只在内存中按 2 组（B08）。</li>
     * </ul>
     */
    public PageMember resolvePage(Map<String, PhpCookies.Value> cookies) {
        long now = clock.instant().getEpochSecond();
        Cleaned c = precheck(cookies);
        if (c == null) {
            return new PageMember(guest(), List.of(), false);
        }
        MemberLoginRow row = authenticate(c);
        if (row == null) {
            return new PageMember(guest(), MemberCookies.guestReset(), true);
        }
        if (!expired(row, now) || writer == null) {
            return new PageMember(stateOf(row, now), List.of(), true);
        }
        int matched;
        try {
            matched = writer.expireMember(row.userId(), now);
        } catch (RuntimeException e) {
            // 写库失败不影响页面：本次按内存判定（2 组），不重发 cookie，下次请求再试
            log.warn("member expiry downgrade failed: {}", SafeExceptionLog.typeChain(e));
            return new PageMember(stateOf(row, now), List.of(), true);
        }
        if (matched == 1) {
            return downgraded(row, now);
        }
        MemberLoginRow again = mapper.selectActiveUser(Long.toString(row.userId()), row.userName());
        if (again != null && again.groupId() == MemberState.EXPIRED_GROUP_ID) {
            return downgraded(again, now);
        }
        return new PageMember(stateOf(again != null ? again : row, now), List.of(), true);
    }

    /**
     * 当前 cookie 对应的已登录会员行（含库里的 {@code user_random}），否则 null。供注销轮换与资料修改使用，只读。
     */
    MemberLoginRow authenticated(Map<String, PhpCookies.Value> cookies) {
        Cleaned c = precheck(cookies);
        return c == null ? null : authenticate(c);
    }

    MemberGroup group(int id) {
        MemberGroupRow g = mapper.selectGroup(id);
        return g == null ? null : new MemberGroup(g.id(), g.name(), g.status(), g.typeIds(), g.popedomJson());
    }

    MemberState stateOf(MemberLoginRow row, long now) {
        boolean expired = expired(row, now);
        int effective = expired ? MemberState.EXPIRED_GROUP_ID : row.groupId();
        return new MemberState(true, row.userId(), row.userName(), effective, row.groupId(), group(effective),
                row.userEndTime(), expired);
    }

    private PageMember downgraded(MemberLoginRow row, long now) {
        MemberGroup g = group(MemberState.EXPIRED_GROUP_ID);
        MemberState state = new MemberState(true, row.userId(), row.userName(), MemberState.EXPIRED_GROUP_ID,
                MemberState.EXPIRED_GROUP_ID, g, row.userEndTime(), false);
        return new PageMember(state, MemberCookies.groupRefresh(g, now), true);
    }

    private MemberState guest() {
        return MemberState.guest(group(MemberState.GUEST_GROUP_ID));
    }

    private static boolean expired(MemberLoginRow row, long now) {
        return row.groupId() > MemberState.EXPIRED_GROUP_ID && row.userEndTime() < now;
    }

    /** 前置判断与清洗；不通过时为 null。 */
    private static Cleaned precheck(Map<String, PhpCookies.Value> cookies) {
        PhpCookies.Value id = cookies.get(USER_ID);
        PhpCookies.Value name = cookies.get(USER_NAME);
        PhpCookies.Value check = cookies.get(USER_CHECK);
        if (id == null || name == null || check == null || id.array() || name.array() || check.array()) {
            return null;
        }
        if (LegacyText.intvalIsZero(id.bytes()) || LegacyText.empty(name.bytes())
                || LegacyText.empty(check.bytes())) {
            return null;
        }
        return new Cleaned(LegacyText.clean(id.bytes()), LegacyText.clean(name.bytes()),
                LegacyText.clean(check.bytes()));
    }

    /** 查库与 user_check 校验；不通过时为 null。 */
    private MemberLoginRow authenticate(Cleaned c) {
        if (LegacyText.empty(c.userId()) || LegacyText.empty(c.userName()) || LegacyText.empty(c.userCheck())
                || hasSupplementary(c.userId()) || hasSupplementary(c.userName())) {
            return null;
        }
        MemberLoginRow row = mapper.selectActiveUser(c.userId(), c.userName());
        if (row == null || row.userRandom() == null || row.userRandom().isEmpty()) {
            return null;
        }
        if (!UserCheck.matches(UserCheck.of(row.userRandom(), row.userName(), row.userId()), c.userCheck())) {
            return null;
        }
        return row;
    }

    static boolean hasSupplementary(String v) {
        return v.codePoints().anyMatch(Character::isSupplementaryCodePoint);
    }

    /**
     * 旧 label_user 的前置判断在原始字节上（{@code empty()}），清洗后的判断是第二步；前置判断通过、清洗后为空的请求仍算"带了会员
     * cookie"（旧版走 checkLogin 失败分支，写游客回写）。
     */
    private record Cleaned(String userId, String userName, String userCheck) {
    }
}
