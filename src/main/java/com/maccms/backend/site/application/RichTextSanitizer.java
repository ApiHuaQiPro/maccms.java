package com.maccms.backend.site.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.jsoup.select.NodeFilter;
import org.jsoup.select.NodeTraversor;

/**
 * 公开详情富文本（{@code vod_content} 等后台录入、采集写入的原始 HTML）的白名单过滤，结果是只能放在元素内容位置的
 * {@link SafeHtml}。规则、黄金向量与旧版差异见 docs/architecture/07-rich-text-filter.md。
 *
 * <p>纯函数：不记录日志、不访问文件/网络/数据库，同一输入总是得到同一输出，对任何输入都不抛异常（null 视为空）；
 * 过滤在渲染之前完成，不会产生半页输出。只做展示过滤，不是授权、密码、版权或播放处理。
 *
 * <p>步骤：
 * <ol>
 * <li>jsoup 按 HTML5 规范解析（与浏览器解析旧版原样输出的结果一致，字符引用在这一步按浏览器规则解码一次）。</li>
 * <li>在解析得到的树上逐个元素判断（{@link #mark}）：{@link #DROPPED_WITH_CONTENT} 中的元素连同内容整段删除
 * （脚本、样式、嵌入、表单、svg/math 等）；链接经 {@link SafeUrl}，不合格改为普通文字；图片经 {@link ImageUrlNormalizer}，
 * 不合格整张删除；宽高、合并单元格只接受短十进制整数，文本属性限制长度。</li>
 * <li>jsoup {@link Cleaner} 按 {@link #SAFELIST} 复制出新文档：白名单外的元素去掉标签、保留子节点；白名单外的属性
 * （事件属性、style、class、id 等）、注释一律丢弃。</li>
 * <li>按 HTML 语法序列化：文本转义 {@code & < >} 与不换行空格，属性值加双引号并转义 {@code & " < >}，
 * 中文等非 ASCII 字符原样输出；控制字符与不成对的代理项替换为 U+FFFD。</li>
 * <li>按浏览器规则重新解析输出直到不再变化，再确认只含白名单内容（{@link #stabilize}）。</li>
 * </ol>
 */
public final class RichTextSanitizer {

    /** 输入超过该长度（UTF-16 单元）直接拒绝，不截断（截断会切开标签或实体）。旧库 vod_content 为 MEDIUMTEXT，长度上限按耗时确定（07 文档第 3.1 节）。 */
    static final int MAX_INPUT_LENGTH = 100_000;
    /** 输出超过该长度时不输出。HTML5 的格式元素重建会让输出比输入长（实测最多约 5 倍）。 */
    static final int MAX_OUTPUT_LENGTH = 500_000;
    /** alt/title 的最大长度，超过则删除该属性。 */
    static final int MAX_TEXT_ATTRIBUTE_LENGTH = 500;
    /** 重新解析的最多次数；正常输入一次即收敛。 */
    static final int MAX_REPARSE = 3;

    /** 过滤结果。 */
    public enum Outcome {
        /** 已过滤（结果可能为空，比如输入只有脚本）。 */
        SANITIZED,
        /** null、空串或只有空白。 */
        EMPTY,
        /** 输入超过 {@link #MAX_INPUT_LENGTH} 或输出超过 {@link #MAX_OUTPUT_LENGTH}，不输出。 */
        TOO_LONG,
        /**
         * 按浏览器规则重新解析后结构不收敛或超出白名单，不输出（防御性分支）。随机输入中未出现；
         * 表格嵌套超过 jsoup 的 512 层深度上限时会出现。
         */
        UNSTABLE
    }

    /** 过滤时做过的改动（只用于统计与诊断，不含原文）。 */
    public enum Finding {
        /** 删除了 {@link #DROPPED_WITH_CONTENT} 中的元素及其内容。 */
        DANGEROUS_ELEMENT_REMOVED,
        /** 白名单外的元素去掉了标签，保留文字与子元素。 */
        ELEMENT_UNWRAPPED,
        /** 保留的元素上删除了白名单外或取值不合格的属性（事件属性、style、class、超长 title 等）。 */
        ATTRIBUTE_REMOVED,
        /** 链接地址不在 {@link SafeUrl} 白名单内，链接改为普通文字。 */
        LINK_REJECTED,
        /** 图片地址不能规范化为 {@link SafeUrl}，整张图片被删除。 */
        IMAGE_REJECTED,
        /** 删除了注释等非内容节点。 */
        NON_CONTENT_REMOVED,
        /** 去掉标签后元素落在 HTML 不允许的位置，已按浏览器解析结果调整结构（如把 table 下的 i 移到表格前）。 */
        STRUCTURE_NORMALIZED
    }

    /** {@code html} 在 {@link Outcome#SANITIZED} 以外时为 {@link SafeHtml#EMPTY}。 */
    public record Result(SafeHtml html, Outcome outcome, Set<Finding> findings) {
    }

    /** 连同内容整段删除的元素：脚本与样式、原样文本类、嵌入与框架、表单控件、外来内容（svg/math）、媒体与文档级元素。 */
    static final Set<String> DROPPED_WITH_CONTENT = Set.of(
            "script", "style", "template", "noscript", "noembed", "noframes", "xmp", "plaintext", "textarea", "title",
            "iframe", "frame", "frameset", "object", "embed", "applet", "param", "portal", "fencedframe",
            "form", "input", "button", "select", "option", "optgroup", "datalist", "output", "fieldset", "legend",
            "label", "keygen", "isindex", "svg", "math", "audio", "video", "source", "track", "canvas", "map", "area",
            "link", "meta", "base", "head", "dialog", "slot");

    /** 允许的元素与属性。属性取值另经 {@link #checkElement} 校验；{@code rel} 只由过滤器自己写入。 */
    static final Safelist SAFELIST = Safelist.none()
            .addTags("p", "br", "hr", "div", "span", "b", "strong", "i", "em", "u", "s", "strike", "del", "ins",
                    "sub", "sup", "small", "big", "blockquote", "q", "cite", "pre", "code",
                    "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "dl", "dt", "dd",
                    "table", "caption", "thead", "tbody", "tfoot", "tr", "th", "td", "a", "img")
            .addAttributes("a", "href", "title", "rel")
            .addAttributes("img", "src", "alt", "title", "width", "height")
            .addAttributes("td", "colspan", "rowspan")
            .addAttributes("th", "colspan", "rowspan");

    /** 标记：整段丢弃、去掉标签保留子节点。不在白名单内，由 Cleaner 处理（见 {@link #mark}）。 */
    private static final String DROP = "maccms-drop";
    private static final String UNWRAP = "maccms-unwrap";

    /** 外链统一加的 rel：不传递权重、不给目标页 window.opener、不发 Referer。 */
    static final String EXTERNAL_REL = "nofollow noopener noreferrer";

    private static final Pattern DIMENSION = Pattern.compile("[1-9][0-9]{0,3}");
    private static final Pattern SPAN = Pattern.compile("[1-9][0-9]{0,2}");
    /** URL 开头的 scheme（WHATWG URL：字母开头，后接字母、数字、+ - .，以冒号结束）。 */
    private static final Pattern SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.\\-]*:");

    private final ImageUrlNormalizer images;

    private RichTextSanitizer(ImageUrlNormalizer images) {
        this.images = images;
    }

    /** @param images 正文图片地址的规范化器（与 vod_pic 等字段共用同一份 upload 配置） */
    public static RichTextSanitizer create(ImageUrlNormalizer images) {
        if (images == null) {
            throw new IllegalArgumentException("images 不能为空");
        }
        return new RichTextSanitizer(images);
    }

    /** 过滤一段库内富文本。 */
    public Result sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Result(SafeHtml.EMPTY, Outcome.EMPTY, Set.of());
        }
        if (raw.length() > MAX_INPUT_LENGTH) {
            return new Result(SafeHtml.EMPTY, Outcome.TOO_LONG, Set.of());
        }
        EnumSet<Finding> findings = EnumSet.noneOf(Finding.class);
        Document dirty = Parser.parseBodyFragment(raw, "");
        mark(dirty.body(), findings);

        Document clean = new Cleaner(SAFELIST).clean(dirty);
        configureOutput(clean);
        Element body = clean.body();
        for (Element el : body.getAllElements()) {
            for (TextNode t : el.textNodes()) {
                String text = t.getWholeText();
                String fixed = normalizeChars(text);
                if (!fixed.equals(text)) {
                    t.text(fixed);
                }
            }
            for (Attribute attr : el.attributes()) {
                attr.setValue(normalizeChars(attr.getValue()));
            }
        }
        Optional<String> html = stabilize(serialize(body), findings);
        if (html.isEmpty()) {
            return new Result(SafeHtml.EMPTY, Outcome.UNSTABLE, Collections.unmodifiableSet(findings));
        }
        if (html.get().length() > MAX_OUTPUT_LENGTH) {
            return new Result(SafeHtml.EMPTY, Outcome.TOO_LONG, Collections.unmodifiableSet(findings));
        }
        return new Result(html.get().isBlank() ? SafeHtml.EMPTY : new SafeHtml(html.get()), Outcome.SANITIZED,
                Collections.unmodifiableSet(findings));
    }

    /**
     * 去掉标签后，白名单内的元素可能落在 HTML 不允许的位置（如 {@code <table>} 下直接是 {@code <i>}），
     * 浏览器解析时会把它们挪走（foster parenting 等树构建规则）。这里按浏览器规则重新解析、序列化，直到结果不再变化，
     * 再检查只含白名单元素、属性与合格地址，保证浏览器得到的 DOM 就是校验过的结构。
     * 重新解析只会挪动、补全（tbody 等）或复制已有元素，不会引入新内容；不收敛或检查不通过时返回空（防御性分支）。
     */
    static Optional<String> stabilize(String html, Set<Finding> findings) {
        String current = html;
        for (int i = 0; i < MAX_REPARSE; i++) {
            Document again = Parser.parseBodyFragment(current, "");
            configureOutput(again);
            String next = serialize(again.body());
            if (next.equals(current)) {
                return isWithinSafelist(again.body()) ? Optional.of(current) : Optional.empty();
            }
            findings.add(Finding.STRUCTURE_NORMALIZED);
            current = next;
        }
        return Optional.empty();
    }

    private static boolean isWithinSafelist(Element body) {
        for (Element el : body.getAllElements()) {
            if (el == body) {
                continue;
            }
            if (!SAFELIST.isSafeTag(el.normalName())) {
                return false;
            }
            for (Attribute attr : el.attributes()) {
                if (!SAFELIST.isSafeAttribute(el.normalName(), el, attr)) {
                    return false;
                }
                boolean url = attr.getKey().equals("href") || attr.getKey().equals("src");
                if (url && SafeUrl.parse(attr.getValue()).isEmpty()) {
                    return false;
                }
            }
        }
        // 只允许元素与文字节点（没有注释、脚本数据）
        return body.getAllElements().stream().flatMap(e -> e.childNodes().stream())
                .allMatch(n -> n instanceof Element || n instanceof TextNode);
    }

    /**
     * 序列化 body 的内容（会修改传入的树，调用后不再使用）。HTML 解析会忽略紧跟在 {@code <pre>} 开始标签后的一个换行，
     * HTML 序列化规范因此要求：pre 的第一个文字以换行开头时，再多写一个换行。jsoup 不做这一步，这里补上，
     * 否则每重新解析一次就少一个换行，结果不收敛。
     */
    private static String serialize(Element body) {
        for (Element pre : body.getElementsByTag("pre")) {
            if (pre.childNodeSize() > 0 && pre.childNode(0) instanceof TextNode t && t.getWholeText().startsWith("\n")) {
                t.text("\n" + t.getWholeText());
            }
        }
        return body.html();
    }

    private static void configureOutput(Document doc) {
        doc.outputSettings()
                .prettyPrint(false)
                .charset(StandardCharsets.UTF_8)
                .escapeMode(Entities.EscapeMode.base);
    }

    /**
     * 在解析得到的树上逐个元素做决定，并记录改动。只改标签名、属性或清空子节点，不在兄弟列表中删除/插入节点
     * （jsoup 每次删除/去标签都要重排其后的兄弟节点，几万个兄弟元素时是平方级耗时）；真正的删除与去标签由
     * {@link Cleaner} 一次复制完成：{@link #DROP} 已清空子节点，复制时整段消失；{@link #UNWRAP} 只保留子节点。
     */
    private void mark(Element body, Set<Finding> findings) {
        List<Element> dropped = new ArrayList<>();
        NodeTraversor.filter((node, depth) -> {
            if (node == body) {
                return NodeFilter.FilterResult.CONTINUE;
            }
            if (node instanceof Element el) {
                if (DROPPED_WITH_CONTENT.contains(el.normalName())) {
                    dropped.add(el);
                    findings.add(Finding.DANGEROUS_ELEMENT_REMOVED);
                    return NodeFilter.FilterResult.SKIP_ENTIRELY;
                }
                checkElement(el, findings);
            } else if (!(node instanceof TextNode)) {
                findings.add(Finding.NON_CONTENT_REMOVED);
            }
            return NodeFilter.FilterResult.CONTINUE;
        }, body);
        for (Element el : dropped) {
            el.empty();
            el.clearAttributes();
            el.tagName(DROP);
        }
    }

    private void checkElement(Element el, Set<Finding> findings) {
        String name = el.normalName();
        if (!SAFELIST.isSafeTag(name)) {
            // 去掉标签的元素，属性随标签一起丢弃，不另计 ATTRIBUTE_REMOVED
            findings.add(Finding.ELEMENT_UNWRAPPED);
            return;
        }
        boolean kept = switch (name) {
            case "a" -> checkLink(el, findings);
            case "img" -> checkImage(el, findings);
            default -> true;
        };
        if (!kept) {
            return;
        }
        for (Attribute attr : el.attributes().asList()) {
            if (!SAFELIST.isSafeAttribute(name, el, attr) || !isValidValue(attr.getKey(), attr.getValue())) {
                el.removeAttr(attr.getKey());
                findings.add(Finding.ATTRIBUTE_REMOVED);
            }
        }
    }

    private static boolean isValidValue(String name, String value) {
        return switch (name) {
            case "width", "height" -> DIMENSION.matcher(value).matches();
            case "colspan", "rowspan" -> SPAN.matcher(value).matches();
            case "alt", "title" -> value.length() <= MAX_TEXT_ATTRIBUTE_LENGTH;
            default -> true;
        };
    }

    /** 链接地址合格时写回去掉首尾空白的地址并设置 rel；否则改为 {@link #UNWRAP}（保留文字）。 */
    private static boolean checkLink(Element a, Set<Finding> findings) {
        String href = stripC0ControlOrSpace(a.attr("href"));
        Optional<SafeUrl> url = href.isEmpty() ? Optional.empty() : SafeUrl.parse(href);
        if (url.isEmpty()) {
            if (a.hasAttr("href")) {
                findings.add(Finding.LINK_REJECTED);
            }
            a.clearAttributes();
            a.tagName(UNWRAP);
            return false;
        }
        a.attr("href", url.get().href());
        if (url.get().isAbsolute()) {
            a.attr("rel", EXTERNAL_REL);
        } else {
            a.removeAttr("rel");
        }
        return true;
    }

    /**
     * 图片地址：浏览器先去掉首尾空白；带 scheme 的只接受旧代码认识的 {@code http://}、{@code https://}、{@code mac:} 前缀
     * （区分大小写，与 mac_url_img 的分支一致），{@code javascript:}、{@code data:} 等直接拒绝；协议相对地址
     * （{@link #isProtocolRelative}）也直接拒绝。这些都不交给规范化器（它会把不以 http、/ 开头的值当作相对路径拼上站点路径，
     * 并把协议相对地址按 base-url 协议补全）。不合格时改为 {@link #DROP}（整张删除）。
     */
    private boolean checkImage(Element img, Set<Finding> findings) {
        String raw = stripC0ControlOrSpace(img.attr("src"));
        boolean schemeOk = !SCHEME.matcher(raw).lookingAt()
                || raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("mac:");
        Optional<SafeUrl> src = !raw.isEmpty() && schemeOk && !isProtocolRelative(raw)
                ? images.normalize(asLegacyAttributeText(raw))
                : Optional.empty();
        if (src.isEmpty()) {
            findings.add(Finding.IMAGE_REJECTED);
            img.clearAttributes();
            img.tagName(DROP);
            return false;
        }
        img.attr("src", src.get().href());
        return true;
    }

    /**
     * jsoup 给出的属性值已按浏览器规则解码过一次。{@link ImageUrlNormalizer} 的输入约定是"放进属性前的库内字符串"，
     * 会自己再解码一次，所以先把 {@code &} 还原为 {@code &amp;}，保证总共只解码一次（{@code &amp;amp;} 不会变成 {@code &}）。
     */
    static String asLegacyAttributeText(String decoded) {
        return decoded.replace("&", "&amp;");
    }

    /**
     * 协议相对地址：浏览器解析时先删去 URL 中所有的 tab/CR/LF，并把 {@code \} 当作 {@code /}（http/https 页面），
     * 所以 {@code //host}、{@code /\host}、{@code \\host}、{@code \/host}、{@code /<tab>/host} 都指向外部主机。
     */
    static boolean isProtocolRelative(String url) {
        int slashes = 0;
        for (int i = 0; i < url.length() && slashes < 2; i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '\\') {
                slashes++;
            } else if (c != '\t' && c != '\n' && c != '\r') {
                return false;
            }
        }
        return slashes == 2;
    }

    /** 浏览器解析 URL 前去掉首尾的 C0 控制字符与空格（WHATWG URL 标准）。 */
    private static String stripC0ControlOrSpace(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) <= 0x20) {
            start++;
        }
        while (end > start && s.charAt(end - 1) <= 0x20) {
            end--;
        }
        return s.substring(start, end);
    }

    /**
     * 不成对的代理项与控制字符（制表、换行、换页、回车除外）替换为 U+FFFD：原文中的控制字符和 {@code &#1;}、{@code &#xD800;}、
     * {@code &#x81;} 这类引用解码出的字符都会被替换，输出总能按严格 UTF-8 编码，也不含 HTML 不允许的控制字符。
     */
    static String normalizeChars(String s) {
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                if (out != null) {
                    out.append(c).append(s.charAt(i + 1));
                }
                i++;
                continue;
            }
            boolean bad = Character.isSurrogate(c)
                    || (Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\f' && c != '\r');
            if (bad && out == null) {
                out = new StringBuilder(s.length()).append(s, 0, i);
            }
            if (out != null) {
                out.append(bad ? '\uFFFD' : c);
            }
        }
        return out == null ? s : out.toString();
    }
}
