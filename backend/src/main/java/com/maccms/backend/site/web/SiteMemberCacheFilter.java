package com.maccms.backend.site.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 会员相关页面的缓存头（B09，18 文档第 5.3 节）：{@link SiteMemberInterceptor} 判定请求带了会员 cookie（页面按会员组渲染）或本响应写了
 * {@code Set-Cookie} 时记下要用的 {@code Cache-Control}；本过滤器包装响应，之后任何地方写 {@code Cache-Control}（页面渲染器、JSON
 * 接口）都换成该值，避免共享缓存把会员组的页面给别人。没有记下时原样透传。
 */
public class SiteMemberCacheFilter extends OncePerRequestFilter {

    private static final String CACHE = SiteMemberCacheFilter.class.getName() + ".cache";

    /** 记下本请求的 {@code Cache-Control} 并立即写出（处理器之后再写时同样被替换）。 */
    static void override(HttpServletRequest request, HttpServletResponse response, String value) {
        request.setAttribute(CACHE, value);
        response.setHeader("Cache-Control", value);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, new HttpServletResponseWrapper(response) {
            @Override
            public void setHeader(String name, String value) {
                super.setHeader(name, replace(request, name, value));
            }

            @Override
            public void addHeader(String name, String value) {
                if ("Cache-Control".equalsIgnoreCase(name) && request.getAttribute(CACHE) != null) {
                    super.setHeader(name, replace(request, name, value));
                } else {
                    super.addHeader(name, value);
                }
            }
        });
    }

    /** 处理器自己写的是 {@code no-store}（更严）时保留它，其余换成记下的值。 */
    private static String replace(HttpServletRequest request, String name, String value) {
        Object v = request.getAttribute(CACHE);
        if (v == null || !"Cache-Control".equalsIgnoreCase(name) || (value != null && value.contains("no-store"))) {
            return value;
        }
        return (String) v;
    }
}
