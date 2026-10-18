package com.maccms.backend.site.web;

import com.maccms.backend.common.session.SessionState;
import com.maccms.backend.common.session.SessionStore;
import com.maccms.backend.common.web.PhpCookies;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.Map;
import org.springframework.http.HttpHeaders;

/**
 * 站点页面使用的进程内会话（B08，17 文档第 2.3 节）。会话 cookie 沿用旧名 {@value #COOKIE}（便于已有的反向代理/CDN 规则继续按它
 * 区分有会话的请求），值是 {@link SessionStore} 签发的随机标识，与旧 PHP 的会话不通用。属性：{@code Path} 为站点根、{@code HttpOnly}、
 * {@code SameSite=Lax}、站点地址是 https 时加 {@code Secure}；不设有效期（浏览器关闭即失效，与旧版相同）。
 *
 * <p>只有需要写入会话时（签发验证码、第 1 页搜索计时）才新建会话并写出 cookie；客户端带来的未知或已过期标识不会被沿用。
 */
public final class SiteSessions {

    public static final String COOKIE = "PHPSESSID";

    private final SessionStore store;
    private final String path;
    private final boolean secure;

    public SiteSessions(SessionStore store, String path, boolean secure) {
        this.store = store;
        this.path = path;
        this.secure = secure;
    }

    public SessionStore store() {
        return store;
    }

    /** 请求带来的有效会话，没有时为 null。 */
    public SessionState current(HttpServletRequest request) {
        PhpCookies.Value id = cookies(request).get(COOKIE);
        if (id == null || id.array()) {
            return null;
        }
        return store.find(id.text()).orElse(null);
    }

    /** 请求带来的有效会话；没有时新建并在响应上写出会话 cookie。 */
    public SessionState currentOrCreate(HttpServletRequest request, HttpServletResponse response) {
        SessionState s = current(request);
        if (s != null) {
            return s;
        }
        s = store.create();
        response.addHeader(HttpHeaders.SET_COOKIE, COOKIE + "=" + s.id() + "; Path=" + path + "; HttpOnly; SameSite=Lax"
                + (secure ? "; Secure" : ""));
        return s;
    }

    /** 当前时刻（秒）。 */
    public long nowEpochSeconds() {
        return store.clock().instant().getEpochSecond();
    }

    static Map<String, PhpCookies.Value> cookies(HttpServletRequest request) {
        return PhpCookies.parse(Collections.list(request.getHeaders(HttpHeaders.COOKIE)));
    }
}
