package com.maccms.backend.site.application;

/**
 * 经 {@link RichTextSanitizer} 白名单过滤、可以原样放进 HTML 元素内容位置的片段。只有过滤器能创建（构造器包内可见），
 * 所以持有 SafeHtml 就说明内容已过滤；其他来源的字符串不能冒充。
 *
 * <p>只能输出在元素内容上下文（如 {@code <div>…</div>}），不能放进属性、{@code <script>}、{@code <style>}、
 * {@code <title>}、{@code <textarea>} 等位置。模板中如何输出（{@code gg.jte.Content} 适配）由页面任务实现，
 * 规则见 docs/architecture/07-rich-text-filter.md 第 5 节。
 */
public final class SafeHtml {

    /** 空片段。 */
    public static final SafeHtml EMPTY = new SafeHtml("");

    private final String html;

    SafeHtml(String html) {
        this.html = html;
    }

    /** 过滤后的 HTML 文本。 */
    public String html() {
        return html;
    }

    public boolean isEmpty() {
        return html.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SafeHtml other && html.equals(other.html);
    }

    @Override
    public int hashCode() {
        return html.hashCode();
    }

    /** 不输出内容，避免过滤结果被意外写进日志。 */
    @Override
    public String toString() {
        return "SafeHtml[length=" + html.length() + "]";
    }
}
