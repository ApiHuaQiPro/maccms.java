package com.maccms.backend.site.web;

import com.maccms.backend.common.web.PhpCookies;
import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.content.application.ContentProperties;
import com.maccms.backend.content.application.ContentPurchases;
import com.maccms.backend.content.application.ContentViewer;
import com.maccms.backend.member.application.MemberRecordService;
import com.maccms.backend.member.application.MemberSessionService;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.application.view.PageMember;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 前台页面的 {@code label_user} 接线（B09，18 文档第 5 节）：旧版每个继承 {@code Base} 的前台控制器在构造时判定会员
 * （{@code index/controller/Base.php:11-18} → {@code common/controller/All.php:118-147}）。这里对 site 的页面处理器做同样的事：
 * <ol>
 * <li>按 {@link MemberSessionService#resolvePage} 判定（只有带齐三个会员 cookie 的请求才查库）；</li>
 * <li>写出游客回写或到期重发的 {@code Set-Cookie}；</li>
 * <li>本请求内的内容权限按有效会员组判定，收费数据按本会员的购买记录判定（{@link ContentViewer}，B10；请求结束时在
 * {@link #afterCompletion} 关闭）；</li>
 * <li>带会员 cookie 的响应缓存改为 {@code private, no-cache}，写了 cookie 的改为 {@code no-store}（{@link SiteMemberCacheFilter}）。</li>
 * </ol>
 * 不适用：{@code verify/*}（旧 {@code Verify} 不继承 {@code Base}）与播放器配置脚本。在站点状态拦截器之后执行（旧版先
 * {@code check_site_status} 再 {@code label_user}）。没有 {@link MemberSessionService}（只装配了部分 Bean 的测试）时一律按游客。
 */
public class SiteMemberInterceptor implements HandlerInterceptor {

    /** 本请求的 {@link PageMember}（游客时也有值）。 */
    static final String MEMBER = SiteMemberInterceptor.class.getName() + ".member";
    private static final String SCOPE = SiteMemberInterceptor.class.getName() + ".scope";
    private static final Logger log = LoggerFactory.getLogger(SiteMemberInterceptor.class);
    private static final PageMember GUEST = new PageMember(MemberState.guest(null), List.of(), false);

    private final ObjectProvider<MemberSessionService> sessions;
    private final ObjectProvider<MemberRecordService> records;

    public SiteMemberInterceptor(ObjectProvider<MemberSessionService> sessions) {
        this(sessions, null);
    }

    /**
     * @param records 已购买查询（B10，19 文档第 5.1 节）；为 null 或没有该服务时一律未购买
     */
    public SiteMemberInterceptor(ObjectProvider<MemberSessionService> sessions,
                                 ObjectProvider<MemberRecordService> records) {
        this.sessions = sessions;
        this.records = records;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod hm) || !applies(hm.getBeanType())) {
            return true;
        }
        PageMember member = resolve(request);
        request.setAttribute(MEMBER, member);
        for (String cookie : member.setCookies()) {
            response.addHeader("Set-Cookie", cookie);
        }
        if (!member.setCookies().isEmpty()) {
            SiteMemberCacheFilter.override(request, response, "no-store");
        } else if (member.memberCookies()) {
            SiteMemberCacheFilter.override(request, response, "private, no-cache");
        }
        MemberState s = member.state();
        request.setAttribute(SCOPE, ContentViewer.enter(s.member() ? s.groupId() : ContentProperties.GUEST_GROUP_ID,
                purchases(s)));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        Object scope = request.getAttribute(SCOPE);
        if (scope instanceof ContentViewer.Scope s) {
            request.removeAttribute(SCOPE);
            s.close();
        }
    }

    /** 本请求会员的已购买查询（B10，D-B09-11）：游客、或没有记录服务时一律未购买。 */
    private ContentPurchases purchases(MemberState s) {
        MemberRecordService r = records == null ? null : records.getIfAvailable();
        return s.member() && r != null ? r.purchases(s.userId()) : ContentPurchases.NONE;
    }

    /** 本请求的会员判定；拦截器没有处理过（verify 等）时按游客。 */
    static PageMember member(HttpServletRequest request) {
        Object m = request.getAttribute(MEMBER);
        return m instanceof PageMember pm ? pm : GUEST;
    }

    static boolean applies(Class<?> type) {
        return type.getPackageName().startsWith("com.maccms.backend.site")
                && type != SiteVerifyController.class && type != PlayerConfigController.class;
    }

    private PageMember resolve(HttpServletRequest request) {
        MemberSessionService service = sessions.getIfAvailable();
        if (service == null) {
            return GUEST;
        }
        List<String> headers = Collections.list(request.getHeaders("Cookie"));
        try {
            return service.resolvePage(PhpCookies.parse(headers));
        } catch (RuntimeException e) {
            // 数据库不可用时按游客渲染（页面自己的查询会给出 503）；不记录 cookie 与会员身份
            log.warn("member resolve failed: {}", SafeExceptionLog.typeChain(e));
            return GUEST;
        }
    }
}
