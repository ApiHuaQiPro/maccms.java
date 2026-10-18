package com.maccms.backend.member.application;

import com.maccms.backend.common.web.PhpCookies;
import com.maccms.backend.member.application.view.MemberGroup;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.infrastructure.MemberGroupRow;
import com.maccms.backend.member.infrastructure.MemberLoginRow;
import com.maccms.backend.member.infrastructure.MemberMapper;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 会员登录态校验（B08，docs/architecture/17-b08-member-session-captcha.md 第 2.2 节）：按旧 {@code label_user}
 * （{@code common/controller/All.php:118-147}）+ {@code checkLogin}（{@code common/model/User.php:422-473}）从请求 cookie
 * 判定当前会员，只读，不写库、不写 cookie。
 *
 * <ol>
 * <li>前置判断（{@code label_user}）：{@code intval(user_id)}、{@code user_name}、{@code user_check} 三者在 PHP {@code empty()}
 * 意义下都非空，否则直接是游客；</li>
 * <li>三个值各做 {@code htmlspecialchars(urldecode(trim()))}，任一为空 → 游客；</li>
 * <li>{@code SELECT … WHERE user_id = ? AND user_name = ? AND user_status = 1 LIMIT 1}（两个参数都按字符串，比较规则交给 MySQL）；
 * 查不到 → 游客；</li>
 * <li>用库里的 {@code user_random}、{@code user_name}、{@code user_id} 算 {@link UserCheck}，与 cookie 逐字节相同才算登录；</li>
 * <li>有效会员组：库里 {@code group_id > 2} 且 {@code user_end_time < 现在}（秒，等于现在不算到期）时按 2 组，只在内存中判定。</li>
 * </ol>
 *
 * <p>与旧版的有意差异（17 文档第 5 节）：数组形式的 cookie 按游客（PHP 8 抛类型错误、7.3 告警后按游客）；{@code user_check} 只接受完全
 * 相同的值（D-B08-1，旧版 {@code !=} 会把 {@code 0e…} 形式的数值串判为相等）；库里 {@code user_random} 为空（注册后从未登录、后台建号）时
 * 不认任何 cookie（D-B08-2，旧版可凭 {@code md5("-用户名-ID-")} 伪造）；含 4 字节字符（库里 utf8 列存不下）的值直接按游客，不查库；
 * 到期时不写库、不重发 cookie（D-B08-3）。
 */
@Service
public class MemberSessionService {

    static final String USER_ID = "user_id";
    static final String USER_NAME = "user_name";
    static final String USER_CHECK = "user_check";

    private final MemberMapper mapper;
    private final Clock clock;

    @Autowired
    public MemberSessionService(MemberMapper mapper) {
        this(mapper, Clock.systemUTC());
    }

    public MemberSessionService(MemberMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 按 {@link PhpCookies#parse} 的结果判定。 */
    public MemberState resolve(Map<String, PhpCookies.Value> cookies) {
        MemberState member = member(cookies, clock.instant().getEpochSecond());
        return member != null ? member : MemberState.guest(group(MemberState.GUEST_GROUP_ID));
    }

    /** 已登录时返回会员，否则 null。 */
    private MemberState member(Map<String, PhpCookies.Value> cookies, long now) {
        PhpCookies.Value id = cookies.get(USER_ID);
        PhpCookies.Value name = cookies.get(USER_NAME);
        PhpCookies.Value check = cookies.get(USER_CHECK);
        if (id == null || name == null || check == null || id.array() || name.array() || check.array()) {
            return null;
        }
        if (LegacyCookieText.intvalIsZero(id.bytes()) || LegacyCookieText.empty(name.bytes())
                || LegacyCookieText.empty(check.bytes())) {
            return null;
        }
        String userId = LegacyCookieText.clean(id.bytes());
        String userName = LegacyCookieText.clean(name.bytes());
        String userCheck = LegacyCookieText.clean(check.bytes());
        if (isEmpty(userId) || isEmpty(userName) || isEmpty(userCheck)
                || hasSupplementary(userId) || hasSupplementary(userName)) {
            return null;
        }
        MemberLoginRow row = mapper.selectActiveUser(userId, userName);
        if (row == null || row.userRandom() == null || row.userRandom().isEmpty()) {
            return null;
        }
        if (!UserCheck.matches(UserCheck.of(row.userRandom(), row.userName(), row.userId()), userCheck)) {
            return null;
        }
        boolean expired = row.groupId() > MemberState.EXPIRED_GROUP_ID && row.userEndTime() < now;
        int effective = expired ? MemberState.EXPIRED_GROUP_ID : row.groupId();
        return new MemberState(true, row.userId(), row.userName(), effective, row.groupId(), group(effective),
                row.userEndTime(), expired);
    }

    private MemberGroup group(int id) {
        MemberGroupRow g = mapper.selectGroup(id);
        return g == null ? null : new MemberGroup(g.id(), g.name(), g.status(), g.typeIds(), g.popedomJson());
    }

    private static boolean isEmpty(String v) {
        return v.isEmpty() || v.equals("0");
    }

    private static boolean hasSupplementary(String v) {
        return v.codePoints().anyMatch(Character::isSupplementaryCodePoint);
    }
}
