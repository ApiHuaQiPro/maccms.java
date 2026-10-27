package com.maccms.backend.site.web;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.site.application.LegacyPage;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.SiteRoute;
import com.maccms.backend.site.application.StaticPageSource;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import jakarta.servlet.http.HttpSession;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * {@link StaticPageSource} 的实现（B16，25 文档第 4 节）：对页面的动态地址做一次进程内 GET——经 Spring MVC 的处理器映射找到承接该地址的
 * 前台控制器，依次执行其拦截器（关站检查、会员接线）与控制器，取得响应正文。所以静态文件与浏览器访问动态地址得到的页面逐字节相同
 * （{@code InternalPageRendererTest} 对照）。
 *
 * <ul>
 * <li>请求是全新的游客请求：没有 cookie、会话、转发头，{@code User-Agent} 固定（手机首页用手机 UA），属性表独立于外层请求；
 * 页面的手机模板与语言选择按 {@link SiteVariants} 对该 UA 的结果（同前台过滤器）。</li>
 * <li>只在一个真实请求（后台或定时任务入口）之内调用：借用外层请求的 ServletContext 等容器对象，结束后恢复外层的
 * {@link RequestContextHolder}。</li>
 * <li>控制器抛出的异常不交给异常处理器：{@link ApiException} 按其状态（如 404），其他按 500，正文为空，调用方据此报告失败、不写文件。</li>
 * </ul>
 */
public final class InternalPageRenderer implements StaticPageSource {

    private static final Logger log = LoggerFactory.getLogger(InternalPageRenderer.class);

    /** 桌面 UA（旧 make 在后台入口里生成，模板目录为桌面模板）。 */
    static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) MaccmsStaticGenerator";
    /** 手机 UA（旧 make/index?ac2=wap 换成手机模板目录）。 */
    static final String MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Mobile MaccmsStaticGenerator";

    private final LegacyUrlBuilder urls;
    private final SiteVariants variants;
    private final Supplier<RequestMappingHandlerMapping> mapping;
    private final Supplier<RequestMappingHandlerAdapter> adapter;

    /** 处理器映射与适配器在 MVC 装配完成后才有，按需取（避免与站点配置互相依赖）。 */
    public InternalPageRenderer(LegacyUrlBuilder urls, SiteVariants variants, Supplier<RequestMappingHandlerMapping> mapping,
                                Supplier<RequestMappingHandlerAdapter> adapter) {
        this.urls = urls;
        this.variants = variants;
        this.mapping = mapping;
        this.adapter = adapter;
    }

    /** 目标页面的动态地址（相对站点根）。 */
    String path(Target target) {
        return switch (target) {
            case Home h -> urls.home();
            case Sitemap m -> urls.legacy(SiteRoute.Legacy.of(LegacyPage.MAP_INDEX, 0));
            case Rss r -> urls.legacy(SiteRoute.Legacy.of(rssPage(r.name()), 0));
            case Label l -> urls.label(l.name());
            case VodType t -> urls.vodType(t.typeId(), t.page());
            case ArtType t -> urls.legacy(SiteRoute.Legacy.paged(LegacyPage.ART_TYPE, t.typeId(), t.page()));
            case VodDetail d -> urls.vodDetail(d.vodId());
            case VodEpisode e -> urls.legacy(SiteRoute.Legacy.episode(e.down() ? LegacyPage.VOD_DOWN : LegacyPage.VOD_PLAY,
                    e.vodId(), e.sid(), e.nid()));
            case ArtDetail a -> urls.legacy(SiteRoute.Legacy.paged(LegacyPage.ART_DETAIL, a.artId(), a.page()));
            case TopicIndex t -> urls.legacy(SiteRoute.Legacy.paged(LegacyPage.TOPIC_INDEX, 0, t.page()));
            case TopicDetail t -> urls.legacy(SiteRoute.Legacy.of(LegacyPage.TOPIC_DETAIL, t.topicId()));
        };
    }

    private static LegacyPage rssPage(String name) {
        return switch (name) {
            case "index" -> LegacyPage.RSS_INDEX;
            case "baidu" -> LegacyPage.RSS_BAIDU;
            case "google" -> LegacyPage.RSS_GOOGLE;
            case "so" -> LegacyPage.RSS_SO;
            case "sogou" -> LegacyPage.RSS_SOGOU;
            case "bing" -> LegacyPage.RSS_BING;
            case "sm" -> LegacyPage.RSS_SM;
            default -> throw new IllegalArgumentException("不支持的 RSS");
        };
    }

    @Override
    public Rendered render(Target target) {
        RequestAttributes outer = RequestContextHolder.getRequestAttributes();
        if (!(outer instanceof ServletRequestAttributes sra)) {
            throw new IllegalStateException("只能在请求之内生成静态页");
        }
        String path;
        try {
            path = path(target);
        } catch (IllegalArgumentException e) {
            return new Rendered(404, new byte[0]);
        }
        String ua = target instanceof Home h && h.wap() ? MOBILE_UA : DESKTOP_UA;
        GuestRequest request = new GuestRequest(sra.getRequest(), "/" + path, ua);
        request.setAttribute(SiteVariants.ATTRIBUTE, variants.resolve(ua, null));
        CapturingResponse response = new CapturingResponse(sra.getResponse());
        ServletRequestAttributes inner = new ServletRequestAttributes(request, response);
        RequestContextHolder.setRequestAttributes(inner);
        List<HandlerInterceptor> applied = new ArrayList<>();
        Exception failure = null;
        try {
            ServletRequestPathUtils.parseAndCache(request);
            HandlerExecutionChain chain = mapping.get().getHandler(request);
            if (chain == null) {
                return new Rendered(404, new byte[0]);
            }
            for (HandlerInterceptor i : chain.getInterceptorList()) {
                if (!i.preHandle(request, response, chain.getHandler())) {
                    return new Rendered(response.status, new byte[0]);
                }
                applied.add(i);
            }
            adapter.get().handle(request, response, chain.getHandler());
            return new Rendered(response.status, response.body.toByteArray());
        } catch (ApiException e) {
            failure = e;
            return new Rendered(e.code().status().value(), new byte[0]);
        } catch (SiteClosedException e) {
            failure = e;
            return new Rendered(503, new byte[0]);
        } catch (Exception e) {
            failure = e;
            log.warn("static page render failed: {}", SafeExceptionLog.typeChain(e));
            return new Rendered(500, new byte[0]);
        } finally {
            for (int i = applied.size() - 1; i >= 0; i--) {
                try {
                    applied.get(i).afterCompletion(request, response, null, failure);
                } catch (Exception e) {
                    log.warn("static page interceptor cleanup failed: {}", SafeExceptionLog.typeChain(e));
                }
            }
            inner.requestCompleted();
            RequestContextHolder.setRequestAttributes(outer);
        }
    }

    /** 全新的游客 GET 请求：路径、UA 由生成器给定，没有 cookie、会话、查询串与其他请求头，属性独立。 */
    static final class GuestRequest extends HttpServletRequestWrapper {

        private final String uri;
        private final Map<String, String> headers;
        private final Map<String, Object> attributes = new HashMap<>();

        GuestRequest(HttpServletRequest outer, String uri, String userAgent) {
            super(outer);
            this.uri = uri;
            this.headers = Map.of("user-agent", userAgent, "accept", "text/html,application/xhtml+xml,application/xml");
        }

        @Override
        public String getMethod() {
            return "GET";
        }

        @Override
        public String getRequestURI() {
            return uri;
        }

        @Override
        public StringBuffer getRequestURL() {
            return new StringBuffer(uri);
        }

        @Override
        public String getContextPath() {
            return "";
        }

        @Override
        public String getServletPath() {
            return "";
        }

        @Override
        public String getPathInfo() {
            return uri;
        }

        @Override
        public String getQueryString() {
            return null;
        }

        @Override
        public String getParameter(String name) {
            return null;
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return Map.of();
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.emptyEnumeration();
        }

        @Override
        public String[] getParameterValues(String name) {
            return null;
        }

        @Override
        public String getHeader(String name) {
            return name == null ? null : headers.get(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            String v = getHeader(name);
            return v == null ? Collections.emptyEnumeration() : Collections.enumeration(List.of(v));
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(headers.keySet());
        }

        @Override
        public long getDateHeader(String name) {
            return -1;
        }

        @Override
        public int getIntHeader(String name) {
            return -1;
        }

        @Override
        public Cookie[] getCookies() {
            return null;
        }

        @Override
        public HttpSession getSession(boolean create) {
            if (create) {
                throw new IllegalStateException("静态生成的请求不建会话");
            }
            return null;
        }

        @Override
        public HttpSession getSession() {
            return getSession(true);
        }

        @Override
        public String getRequestedSessionId() {
            return null;
        }

        @Override
        public String getContentType() {
            return null;
        }

        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }

        @Override
        public String getRemoteAddr() {
            return "127.0.0.1";
        }

        @Override
        public String getRemoteHost() {
            return "127.0.0.1";
        }

        @Override
        public DispatcherType getDispatcherType() {
            return DispatcherType.REQUEST;
        }

        @Override
        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        public Enumeration<String> getAttributeNames() {
            return Collections.enumeration(new ArrayList<>(attributes.keySet()));
        }

        @Override
        public void setAttribute(String name, Object o) {
            if (o == null) {
                attributes.remove(name);
            } else {
                attributes.put(name, o);
            }
        }

        @Override
        public void removeAttribute(String name) {
            attributes.remove(name);
        }
    }

    /** 只在内存里记录状态、头与正文的响应，不碰外层响应。 */
    static final class CapturingResponse extends HttpServletResponseWrapper {

        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final Map<String, List<String>> headers = new HashMap<>();
        int status = 200;
        private String contentType;
        private String encoding = StandardCharsets.UTF_8.name();
        private PrintWriter writer;
        private ServletOutputStream stream;

        CapturingResponse(HttpServletResponse outer) {
            super(outer);
        }

        @Override
        public void setStatus(int sc) {
            status = sc;
        }

        @Override
        public int getStatus() {
            return status;
        }

        @Override
        public void sendError(int sc) {
            status = sc;
        }

        @Override
        public void sendError(int sc, String msg) {
            status = sc;
        }

        @Override
        public void sendRedirect(String location) {
            status = 302;
        }

        @Override
        public void addCookie(Cookie cookie) {
            // 游客页面不应设置 cookie；忽略，不影响外层响应
        }

        @Override
        public void setHeader(String name, String value) {
            headers.put(name.toLowerCase(Locale.ROOT), new ArrayList<>(List.of(value)));
        }

        @Override
        public void addHeader(String name, String value) {
            headers.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(value);
        }

        @Override
        public void setIntHeader(String name, int value) {
            setHeader(name, Integer.toString(value));
        }

        @Override
        public void addIntHeader(String name, int value) {
            addHeader(name, Integer.toString(value));
        }

        @Override
        public void setDateHeader(String name, long date) {
            setHeader(name, Long.toString(date));
        }

        @Override
        public void addDateHeader(String name, long date) {
            addHeader(name, Long.toString(date));
        }

        @Override
        public boolean containsHeader(String name) {
            return headers.containsKey(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public String getHeader(String name) {
            List<String> v = headers.get(name.toLowerCase(Locale.ROOT));
            return v == null || v.isEmpty() ? null : v.getFirst();
        }

        @Override
        public Collection<String> getHeaders(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
        }

        @Override
        public Collection<String> getHeaderNames() {
            return headers.keySet();
        }

        @Override
        public void setContentType(String type) {
            contentType = type;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public void setCharacterEncoding(String charset) {
            encoding = charset;
        }

        @Override
        public String getCharacterEncoding() {
            return encoding;
        }

        @Override
        public void setContentLength(int len) {
        }

        @Override
        public void setContentLengthLong(long len) {
        }

        @Override
        public void setLocale(Locale loc) {
        }

        @Override
        public void setBufferSize(int size) {
        }

        @Override
        public boolean isCommitted() {
            return false;
        }

        @Override
        public void reset() {
            body.reset();
            headers.clear();
            status = 200;
        }

        @Override
        public void resetBuffer() {
            body.reset();
        }

        @Override
        public void flushBuffer() {
            if (writer != null) {
                writer.flush();
            }
        }

        @Override
        public ServletOutputStream getOutputStream() {
            if (stream == null) {
                stream = new ServletOutputStream() {
                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                    }

                    @Override
                    public void write(int b) {
                        body.write(b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) {
                        body.write(b, off, len);
                    }
                };
            }
            return stream;
        }

        @Override
        public PrintWriter getWriter() {
            if (writer == null) {
                writer = new PrintWriter(new java.io.OutputStreamWriter(getOutputStream(),
                        java.nio.charset.Charset.forName(encoding)), true);
            }
            return writer;
        }
    }
}
