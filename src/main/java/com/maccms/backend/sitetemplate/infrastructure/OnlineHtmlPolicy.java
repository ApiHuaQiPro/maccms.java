package com.maccms.backend.sitetemplate.infrastructure;

import gg.jte.html.HtmlAttribute;
import gg.jte.html.HtmlPolicy;
import gg.jte.html.HtmlPolicyException;
import gg.jte.html.HtmlTag;
import gg.jte.html.OwaspHtmlPolicy;
import gg.jte.html.policy.PreventInlineEventHandlers;
import gg.jte.html.policy.PreventSingleQuotedAttributes;
import java.util.Locale;
import java.util.Set;

/**
 * 在线编辑的外部模板编译时用的 HTML 策略（24 文档第 7.3 节）：构建用的 {@link OwaspHtmlPolicy}，加上与 SiteTemplatePolicyTest 相同的
 * 站点规则——禁止内联事件属性、单引号属性、样式/嵌入类标签、style 等属性。{@code <script>}（只能带 nonce 与 src）与 {@code <iframe>}
 * 在这里放行，是否允许出现在某个模板里由安全过滤按"内置版本的同名模板本身就有"判断（B05/B09/B10 已裁定的例外只限那几个模板）。
 */
public final class OnlineHtmlPolicy extends OwaspHtmlPolicy {

    public OnlineHtmlPolicy() {
        addPolicy(new PreventInlineEventHandlers());
        addPolicy(new PreventSingleQuotedAttributes());
        addPolicy(new ForbiddenMarkup());
    }

    static final class ForbiddenMarkup implements HtmlPolicy {
        static final Set<String> TAGS = Set.of("style", "frame", "frameset", "object", "embed", "applet", "base", "svg", "math",
                "template");
        static final Set<String> ATTRIBUTES = Set.of("style", "srcdoc", "http-equiv", "formaction");
        static final Set<String> SCRIPT_ATTRIBUTES = Set.of("nonce", "src");

        @Override
        public void validateHtmlTag(HtmlTag tag) {
            if (TAGS.contains(tag.getName().toLowerCase(Locale.ROOT))) {
                throw new HtmlPolicyException("site 模板不允许 <" + tag.getName() + ">");
            }
        }

        @Override
        public void validateHtmlAttribute(HtmlTag tag, HtmlAttribute attribute) {
            String name = attribute.getName().toLowerCase(Locale.ROOT);
            if (ATTRIBUTES.contains(name)) {
                throw new HtmlPolicyException("site 模板不允许属性 " + attribute.getName());
            }
            if (tag.getName().equalsIgnoreCase("script") && !SCRIPT_ATTRIBUTES.contains(name)) {
                throw new HtmlPolicyException("<script> 只能有 nonce 与 src 属性：" + attribute.getName());
            }
        }
    }
}
