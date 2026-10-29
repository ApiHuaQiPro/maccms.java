package com.maccms.backend.member.application;

import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.integration.application.OAuthLogin;
import com.maccms.backend.integration.application.OAuthOutcome;
import com.maccms.backend.member.application.view.AccountReply;
import com.maccms.backend.member.application.view.MemberState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QQ/微信第三方登录与绑定（E10，B26；docs/architecture/34-b26-oauth.md）：旧 {@code index/controller/User.php} 的 {@code oauth}、
 * {@code logincallback}，按路线图第 4.3 节 ③ 的修复后方案：
 * <ul>
 * <li>发起授权时生成一次性、有时效（{@link #STATE_TTL}）、与会话及当时的登录身份绑定的 {@code state}，存在进程内会话里；</li>
 * <li>回调一到达就取出并作废本会话待用的 {@code state}，校验方式、值、时效与登录身份，不符即拒绝，不发任何出站请求；</li>
 * <li>openid 只来自服务端用 {@code code} 换取的结果（{@link OAuthLogin#fetch}）；</li>
 * <li>回调地址是部署配置中的固定值（{@code maccms.site.base-url} + {@code index.php/user/logincallback/type/<方式>}），不接受请求中的跳转地址。</li>
 * </ul>
 * 之后的流程同旧版：已登录 → 改绑（{@link MemberAccountService#openidBind}）；游客 → 未绑定时自动注册，再凭 openid 登录
 * （{@link MemberAccountService#openidLogin}）。日志不记授权码、{@code state}、openid。
 */
public class MemberOAuthService {

    /** {@code state} 的有效期。 */
    public static final Duration STATE_TTL = Duration.ofMinutes(10);
    /** 会话里存放待用 {@code state} 的键（每个会话只保留最近一次发起的授权）。 */
    static final String SESSION_KEY = "member.oauth.pending";
    /** 回调地址的站内路径前缀（旧 {@code THIRD_LOGIN_CALLBACK}，{@code index/controller/User.php:15}）。 */
    public static final String CALLBACK_PATH = "index.php/user/logincallback/type/";

    private static final Logger log = LoggerFactory.getLogger(MemberOAuthService.class);

    private final OAuthLogin oauth;
    private final MemberAccountService accounts;
    private final String callbackBase;
    private final Clock clock;
    private final RandomGenerator random;

    /**
     * @param callbackBase 站点对外绝对地址（{@code maccms.site.base-url}，以 {@code /} 结尾）
     * @param random       生成 {@code state} 的随机源（生产为 {@code SecureRandom}）
     */
    public MemberOAuthService(OAuthLogin oauth, MemberAccountService accounts, String callbackBase, Clock clock,
                              RandomGenerator random) {
        if (callbackBase == null || !callbackBase.endsWith("/")
                || !(callbackBase.startsWith("http://") || callbackBase.startsWith("https://"))) {
            throw new IllegalArgumentException("回调地址须是以 / 结尾的 http/https 绝对地址");
        }
        this.oauth = oauth;
        this.accounts = accounts;
        this.callbackBase = callbackBase;
        this.clock = clock;
        this.random = random;
    }

    /** 该方式已打开（登录页据此显示入口）。 */
    public boolean enabled(String type) {
        return OAuthLogin.TYPES.contains(type) && oauth.enabled(type);
    }

    /** 已打开的方式（按 {@link OAuthLogin#TYPES} 的顺序）。 */
    public List<String> enabledTypes() {
        return OAuthLogin.TYPES.stream().filter(this::enabled).toList();
    }

    /** 回调地址（发起授权与换令牌时都用这一个值）。 */
    public String callbackUrl(String type) {
        return callbackBase + CALLBACK_PATH + type;
    }

    /**
     * 发起授权（旧 {@code oauth($type)}）：生成新的 {@code state} 存进会话（覆盖之前未用的），返回授权页地址。
     *
     * @return 授权页地址；方式为空、不支持或未打开时为 null（调用方提示"参数错误"）
     */
    public String start(String type, MemberState member, SessionState session) {
        if (type == null || !enabled(type)) {
            return null;
        }
        byte[] b = new byte[32];
        random.nextBytes(b);
        String state = HexFormat.of().formatHex(b);
        session.put(SESSION_KEY, new Pending(state, type, member.member() ? member.userId() : 0,
                clock.instant().getEpochSecond()));
        return oauth.authorizeUrl(type, state, callbackUrl(type));
    }

    /**
     * 授权回调（旧 {@code logincallback($type, $code)}，{@code index/controller/User.php:212-285}）。
     *
     * @param session 请求带来的会话；没有时为 null（必然拒绝）
     * @param state   请求中的 {@code state} 参数（原始字节）
     */
    public Callback callback(String type, byte[] code, byte[] state, MemberState member, SessionState session,
                             String clientIp) {
        if (type == null || type.isEmpty()) {
            return Callback.error("参数错误");
        }
        // 回调一到达就作废本会话待用的 state：同一个 state 只能用一次，失败也不能再用
        Pending pending = session == null ? null : session.take(SESSION_KEY, Pending.class).orElse(null);
        if (code == null || LegacyText.empty(code)) {
            return Callback.error("参数错误");
        }
        if (!OAuthLogin.TYPES.contains(type) || !enabled(type)) {
            return Callback.error("参数错误");
        }
        String rejected = checkState(pending, type, state, member);
        if (rejected != null) {
            log.warn("member oauth callback rejected type={} reason={}", type, rejected);
            return Callback.error("登录请求已失效，请重新登录");
        }
        OAuthOutcome out = oauth.fetch(type, new String(code, StandardCharsets.UTF_8), callbackUrl(type));
        switch (out.status()) {
            case TOKEN_FAILED -> {
                return Callback.error("获取第三方用户信息失败，请重试");
            }
            case USER_INFO_FAILED -> {
                return Callback.error((type.equals("qq") ? "获取腾讯QQ用户信息失败：" : "获取微信用户信息失败：") + out.message());
            }
            default -> {
            }
        }
        boolean qq = type.equals("qq");
        if (member.member()) {
            AccountReply bind = accounts.openidBind(member, qq, out.openid());
            return Callback.json(bind.code(), bind.msg());
        }
        AccountReply login = accounts.openidLogin(qq, out.openid(), clientIp);
        if (!login.ok()) {
            return Callback.error(login.msg());
        }
        return Callback.loggedIn(login.setCookies());
    }

    /** @return null 表示通过；否则是拒绝原因（只用于日志） */
    private String checkState(Pending pending, String type, byte[] state, MemberState member) {
        if (pending == null) {
            return "no-pending-state";
        }
        if (state == null || !MessageDigest.isEqual(pending.state().getBytes(StandardCharsets.US_ASCII), state)) {
            return "state-mismatch";
        }
        if (!pending.type().equals(type)) {
            return "type-mismatch";
        }
        long age = clock.instant().getEpochSecond() - pending.issuedAt();
        if (age < 0 || age > STATE_TTL.toSeconds()) {
            return "expired";
        }
        if (pending.userId() != (member.member() ? member.userId() : 0)) {
            return "member-changed";
        }
        return null;
    }

    /** 会话里待用的授权：{@code state}、方式、发起时的会员编号（游客为 0）、发起时刻（秒）。 */
    record Pending(String state, String type, long userId, long issuedAt) {

        @Override
        public String toString() {
            return "Pending[" + type + "]";
        }
    }

    /**
     * 回调的结果：{@link Kind#JSON}（改绑，旧版即输出 JSON）、{@link Kind#LOGGED_IN}（302 到会员中心并写登录 cookie）、
     * {@link Kind#ERROR}（旧 {@code $this->error($msg)}）。
     */
    public record Callback(Kind kind, int code, String msg, List<String> setCookies) {

        public enum Kind { JSON, LOGGED_IN, ERROR }

        static Callback json(int code, String msg) {
            return new Callback(Kind.JSON, code, msg, List.of());
        }

        static Callback loggedIn(List<String> cookies) {
            return new Callback(Kind.LOGGED_IN, 1, "登录成功", List.copyOf(cookies));
        }

        static Callback error(String msg) {
            return new Callback(Kind.ERROR, 0, msg, List.of());
        }
    }
}
