package com.maccms.backend.admin.web;

import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.common.session.SessionStore;
import com.maccms.backend.common.web.PhpCookies;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import org.springframework.http.HttpHeaders;

/**
 * 后台会话（B12，21 文档第 4.2 节）：进程内（{@link SessionStore} 的独立实例，与站点会话互不相通），重启即全部失效。
 *
 * <ul>
 * <li>cookie {@value #COOKIE}：随机 128 位标识，{@code Path} 为后台入口、{@code HttpOnly}、{@code SameSite=Strict}，对外地址为 https 时
 * {@code Secure}；不设有效期。旧版用前台共用的 {@code PHPSESSID}（{@code Path=/}、无 HttpOnly），新版不与站点共用，跨站请求不带这个
 * cookie；同站点的其他子域名或端口仍会带上它，由 {@code AdminController} 的来源校验挡住（D-B12-3）。</li>
 * <li>空闲 {@value #IDLE_MINUTES} 分钟失效（旧 PHP 会话默认 {@code gc_maxlifetime=1440} 秒；框架页每 30 秒轮询 checkcache，打开时会话不会
 * 过期）。</li>
 * <li>未登录的会话（登录页验证码）与已登录的会话分放在两个 {@link SessionStore} 里，各最多 {@value #MAX_SESSIONS} 个：已登录的会话只在登录
 * 成功时建立，所以不带 cookie 反复请求验证码只会挤掉别的未登录会话，不会把已登录的管理员挤下线。</li>
 * <li>登录成功时换新标识（旧版沿用登录前的会话，可被会话固定攻击），退出与 D2 ⑤ 踢出时作废。</li>
 * </ul>
 * 会话里只存：登录的 {@code admin_id}、登录时比较过的密码哈希（D2 ⑤）、表单令牌、登录页的验证码。
 */
public final class AdminSessions {

    public static final String COOKIE = "mac_admin_sid";
    static final int IDLE_MINUTES = 24;
    static final int MAX_SESSIONS = 10_000;
    static final String ADMIN_ID = "admin.id";
    static final String ADMIN_PWD = "admin.pwd";
    static final String TOKEN = "admin.token";

    /** 未登录的会话：只放登录页的验证码。 */
    private final SessionStore anonymous;
    /** 已登录的会话：只在登录成功时建立。 */
    private final SessionStore loggedIn;
    private final String path;
    private final boolean secure;

    public AdminSessions(Clock clock, AdminEndpoint endpoint) {
        this.anonymous = new SessionStore(clock, Duration.ofMinutes(IDLE_MINUTES), MAX_SESSIONS);
        this.loggedIn = new SessionStore(clock, Duration.ofMinutes(IDLE_MINUTES), MAX_SESSIONS);
        this.path = endpoint.root();
        this.secure = endpoint.secure();
    }

    /** 按标识找有效会话（先找已登录的）。 */
    Optional<SessionState> find(String id) {
        Optional<SessionState> s = loggedIn.find(id);
        return s.isPresent() ? s : anonymous.find(id);
    }

    /** 请求带来的有效会话，没有时为 null。 */
    public SessionState current(HttpServletRequest request) {
        PhpCookies.Value id = PhpCookies.parse(Collections.list(request.getHeaders(HttpHeaders.COOKIE))).get(COOKIE);
        if (id == null || id.array()) {
            return null;
        }
        return find(id.text()).orElse(null);
    }

    /** 请求带来的有效会话；没有时新建一个未登录的会话并写出 cookie。 */
    public SessionState currentOrCreate(HttpServletRequest request, HttpServletResponse response) {
        SessionState s = current(request);
        return s != null ? s : create(anonymous, response);
    }

    /** 登录成功：作废请求带来的会话（含登录页的验证码），换一个新标识的已登录会话并写出 cookie。 */
    public SessionState rotate(HttpServletRequest request, HttpServletResponse response) {
        SessionState old = current(request);
        if (old != null) {
            drop(old.id());
        }
        return create(loggedIn, response);
    }

    /** 退出登录、被停用或改密（D2 ⑤）：作废会话并删除 cookie。 */
    public void invalidate(HttpServletRequest request, HttpServletResponse response) {
        SessionState s = current(request);
        if (s != null) {
            drop(s.id());
            response.addHeader(HttpHeaders.SET_COOKIE, COOKIE + "=; Path=" + path + "; Max-Age=0; HttpOnly; SameSite=Strict"
                    + (secure ? "; Secure" : ""));
        }
    }

    /** 当前时刻（秒）。 */
    public long nowEpochSeconds() {
        return loggedIn.clock().instant().getEpochSecond();
    }

    private void drop(String id) {
        loggedIn.invalidate(id);
        anonymous.invalidate(id);
    }

    private SessionState create(SessionStore store, HttpServletResponse response) {
        SessionState s = store.create();
        response.addHeader(HttpHeaders.SET_COOKIE, COOKIE + "=" + s.id() + "; Path=" + path + "; HttpOnly; SameSite=Strict"
                + (secure ? "; Secure" : ""));
        return s;
    }
}
