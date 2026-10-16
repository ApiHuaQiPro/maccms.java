package com.maccms.backend.site.application;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 与旧 {@code mac_url_img}（application/common.php:1633-1657）等价的图片地址规范化，结果一律经 {@link SafeUrl} 校验。
 * 只处理"作为图片 URL 的字符串"，不过滤富文本（不处理 vod_content 等正文里的 img，旧版那是 mac_url_content_img）。
 * 逻辑映射、黄金向量与差异见 docs/architecture/06-image-url-normalizer.md。
 *
 * <p>步骤：
 * <ol>
 * <li>分支选择与拼接，与旧代码逐字等价（{@link Branch}）：前缀比较区分大小写，{@code mac:} 替换全部出现处，
 * {@code img_key} 按 PHP 的 {@code preg_match('/'.img_key.'/')} 语义匹配。</li>
 * <li>旧版的 {@code mac_filter_xss} 输出的是 HTML 转义后的字符串，由浏览器在属性中解码；新版改为求出浏览器最终请求的地址：
 * 按 PHP trim 去首尾空白，按属性规则解码字符引用（{@link HtmlAttributeCharRefs}），去掉首尾 C0 控制字符与空格，
 * 中间空格编码为 {@code %20}。含 {@code <} 时旧版 strip_tags 会改写地址，新版不模拟，直接拒绝。</li>
 * <li>协议相对地址（{@code //host/…}）按站点 base-url 的协议补全，等价于浏览器在 canonical 页面上的解析结果。</li>
 * <li>{@link SafeUrl} 白名单：只放行 http/https 绝对地址与以单个 / 开头的站内路径；其他一律拒绝，由页面显示占位图。</li>
 * <li>浏览器侧外部地址放行（B02，W2，docs/architecture/11-guards-and-legacy-config.md 第 5 节）：
 * {@link #withExternalImages(boolean) withExternalImages(false)} 时，主机不是本站（base-url 的主机）的绝对地址
 * 归为 {@link Outcome#EXTERNAL_BLOCKED}，页面按空值处理（显示占位）；{@code img_key} 命中时不拼 {@code img_api}
 * （它指向的代理脚本要由服务端去抓外部图片，属于 E02，新系统不提供），按原地址判断。
 * 放行时（以及 {@link #create} 直接得到的实例）前 4 步不变，MACC-11 黄金向量照常成立。</li>
 * </ol>
 * 空值（null、空串、只有空白）直接返回 {@link Outcome#EMPTY}，不再像旧版那样输出站点根路径。
 */
public final class ImageUrlNormalizer {

    /** 旧代码命中的分支。 */
    public enum Branch {
        /** {@code mac:} 前缀替换为 upload.protocol（:1635-1640）。 */
        MAC_PROTOCOL,
        /** 不以 http、//、/ 开头，本地模式拼 MAC_PATH（:1642,1646-1647）。 */
        RELATIVE_LOCAL,
        /** 不以 http、//、/ 开头，远程模式拼 upload.remoteurl（:1642-1644）。 */
        RELATIVE_REMOTE,
        /** img_key 命中，拼 upload.img_api（:1650-1651）。 */
        IMG_API,
        /** 以上都未命中，原样（只经过滤）。 */
        UNCHANGED
    }

    /** 规范化结果。 */
    public enum Outcome {
        ACCEPTED,
        /** null、空串或只有空白。 */
        EMPTY,
        /** 含 {@code <}：旧版 strip_tags 会改写，新版不模拟。 */
        TAG_LIKE,
        /** 含浏览器可能解码、但新版不支持的字符引用。 */
        UNSUPPORTED_CHAR_REF,
        /** 结果不在 {@link SafeUrl} 白名单内。 */
        NOT_ALLOWED,
        /** 合法，但主机不是本站，且部署参数未放行外部图片（W2，B02）。不属于旧 mac_url_img 的结果。 */
        EXTERNAL_BLOCKED
    }

    /** {@code url} 只在 {@link Outcome#ACCEPTED} 时有值。 */
    public record Resolution(Branch branch, Outcome outcome, Optional<SafeUrl> url) {
    }

    private static final int MAX_CONFIG_LENGTH = 256;

    private final String protocol;
    private final boolean remote;
    private final String remoteUrl;
    private final String localPrefix;
    private final Pattern imgKey;
    private final String imgApi;
    private final String pageScheme;
    private final String siteHost;
    private final boolean externalAllowed;

    private ImageUrlNormalizer(String protocol, boolean remote, String remoteUrl, String localPrefix, Pattern imgKey,
                               String imgApi, String pageScheme, String siteHost, boolean externalAllowed) {
        this.protocol = protocol;
        this.remote = remote;
        this.remoteUrl = remoteUrl;
        this.localPrefix = localPrefix;
        this.imgKey = imgKey;
        this.imgApi = imgApi;
        this.pageScheme = pageScheme;
        this.siteHost = siteHost;
        this.externalAllowed = externalAllowed;
    }

    /**
     * 校验配置并创建规范化器。旧版对任意配置值都照常拼接，新版在这里拒绝会产生非白名单地址或改变主机名的配置。
     * 创建出的规范化器放行外部主机（与旧版一致）；页面用的实例由 {@link #withExternalImages(boolean)} 按部署参数收紧。
     * 不合法时抛 {@link IllegalStateException}，信息只列出配置项名称与规则，不回显取值。
     *
     * @param site 站点设置：{@code homePath} 代替旧 MAC_PATH，{@code baseUrl} 的协议用于补全协议相对地址
     */
    public static ImageUrlNormalizer create(ImageUrlConfig config, SiteSettings site) {
        List<String> errors = new ArrayList<>();
        String pageScheme = URI.create(site.baseUrl().href()).getScheme();

        String protocol = nullToEmpty(config.protocol());
        if (phpEmpty(protocol)) {
            protocol = "http";
        } else if (!protocol.equals("http") && !protocol.equals("https")) {
            errors.add("upload.protocol（留空、http 或 https）");
        }

        boolean remote = "remote".equals(config.mode());
        String remoteUrl = nullToEmpty(config.remoteUrl());
        if (remote && !isValidRemoteUrl(remoteUrl, pageScheme)) {
            errors.add("upload.remoteurl（远程模式必填：http/https 绝对地址或协议相对地址，有主机名，"
                    + "不含用户信息、查询串、片段与 &，以 / 结尾）");
        }

        Pattern imgKey = null;
        String imgApi = nullToEmpty(config.imgApi());
        String key = nullToEmpty(config.imgKey());
        if (!phpEmpty(key)) {
            imgKey = compilePhpPattern(key);
            if (imgKey == null) {
                errors.add("upload.img_key（留空，或可作为 /…/ 正则主体的表达式：不含未转义的 /，不以未转义的 \\ 结尾，"
                        + "不含控制字符，能被 Java 正则编译，最多 " + MAX_CONFIG_LENGTH + " 个字符）");
            }
            if (!imgApi.isEmpty() && (imgApi.length() > MAX_CONFIG_LENGTH || imgApi.indexOf('<') >= 0
                    || SafeUrl.parse(completeProtocolRelative(imgApi, pageScheme)).isEmpty())) {
                errors.add("upload.img_api（留空，或以单个 / 开头的站内路径，或 http/https 绝对地址、协议相对地址）");
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException("图片地址配置不合法：" + String.join("；", errors));
        }
        return new ImageUrlNormalizer(protocol, remote, remoteUrl, site.homePath().href(), imgKey, imgApi, pageScheme,
                hostOf(site.baseUrl()), true);
    }

    /**
     * 返回只在外部主机放行与否上不同的规范化器。{@code allowed=false} 时，结果是绝对地址且主机（不区分大小写，忽略端口与协议）
     * 不等于 base-url 的主机，就归为 {@link Outcome#EXTERNAL_BLOCKED}。"本站"只按配置的 base-url 判定，不看请求的 Host 头。
     */
    public ImageUrlNormalizer withExternalImages(boolean allowed) {
        return new ImageUrlNormalizer(protocol, remote, remoteUrl, localPrefix, imgKey, imgApi, pageScheme, siteHost,
                allowed);
    }

    /** 规范化库内图片字段（如 vod_pic），不合法或为空时返回空。 */
    public Optional<SafeUrl> normalize(String raw) {
        return resolve(raw).url();
    }

    /** 同 {@link #normalize}，另给出旧代码命中的分支与拒绝原因（测试与诊断用；不要把原始值写进日志）。 */
    public Resolution resolve(String raw) {
        String s = raw == null ? "" : raw;
        Branch branch = branch(s);
        if (phpTrim(s).isEmpty()) {
            return reject(branch, Outcome.EMPTY);
        }
        String candidate = apply(!externalAllowed && branch == Branch.IMG_API ? Branch.UNCHANGED : branch, s);
        if (candidate.indexOf('<') >= 0) {
            return reject(branch, Outcome.TAG_LIKE);
        }
        Optional<String> decoded = HtmlAttributeCharRefs.decode(phpTrim(candidate));
        if (decoded.isEmpty()) {
            return reject(branch, Outcome.UNSUPPORTED_CHAR_REF);
        }
        String url = stripC0ControlOrSpace(decoded.get());
        if (hasUnpairedSurrogate(url)) {
            return reject(branch, Outcome.NOT_ALLOWED);
        }
        url = completeProtocolRelative(url.replace(" ", "%20"), pageScheme);
        Optional<SafeUrl> safe = SafeUrl.parse(url);
        if (safe.isEmpty()) {
            return reject(branch, Outcome.NOT_ALLOWED);
        }
        if (!externalAllowed && safe.get().isAbsolute() && !hostOf(safe.get()).equals(siteHost)) {
            return reject(branch, Outcome.EXTERNAL_BLOCKED);
        }
        return new Resolution(branch, Outcome.ACCEPTED, safe);
    }

    /** 绝对地址的主机名，小写、去掉末尾的点（{@code a.test.} 与 {@code a.test} 是同一主机）。 */
    private static String hostOf(SafeUrl url) {
        String host = URI.create(url.href()).getHost().toLowerCase(Locale.ROOT);
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }

    /** 旧代码在 mac_filter_xss 之前得到的字符串（application/common.php:1635-1652），测试用来对拍 PHP 输出。 */
    String legacyCandidate(String raw) {
        String s = raw == null ? "" : raw;
        return apply(branch(s), s);
    }

    private Branch branch(String s) {
        if (s.startsWith("mac:")) {
            return Branch.MAC_PROTOCOL;
        }
        if (!s.startsWith("http") && !s.startsWith("//") && !s.startsWith("/")) {
            return remote ? Branch.RELATIVE_REMOTE : Branch.RELATIVE_LOCAL;
        }
        if (imgKey != null && imgKey.matcher(s).find()) {
            return Branch.IMG_API;
        }
        return Branch.UNCHANGED;
    }

    private String apply(Branch branch, String s) {
        return switch (branch) {
            case MAC_PROTOCOL -> s.replace("mac:", protocol + ":");
            case RELATIVE_LOCAL -> localPrefix + s;
            case RELATIVE_REMOTE -> remoteUrl + s;
            case IMG_API -> imgApi + s;
            case UNCHANGED -> s;
        };
    }

    private static Resolution reject(Branch branch, Outcome outcome) {
        return new Resolution(branch, outcome, Optional.empty());
    }

    private static boolean isValidRemoteUrl(String remoteUrl, String pageScheme) {
        if (remoteUrl.isEmpty() || remoteUrl.length() > MAX_CONFIG_LENGTH || remoteUrl.indexOf('&') >= 0
                || remoteUrl.indexOf('?') >= 0 || remoteUrl.indexOf('#') >= 0 || !remoteUrl.endsWith("/")) {
            return false;
        }
        Optional<SafeUrl> url = SafeUrl.parse(completeProtocolRelative(remoteUrl, pageScheme));
        return url.isPresent() && url.get().isAbsolute();
    }

    /**
     * 按 PHP 的规则检查定界符后编译：旧代码是 {@code '/'.img_key.'/'}，PHP 从第一个字符后找第一个未被反斜线转义的 {@code /}
     * 作为结束定界符，它之后的字符当作修饰符。所以 img_key 中出现未转义的 {@code /} 或以奇数个反斜线结尾时，
     * PHP 报警告且不匹配（在 ThinkPHP 中警告会转为异常）。新版直接拒绝这样的配置。
     * PCRE 与 Java 正则在少数语法上不同，常见写法（字面域名、{@code .}、{@code |}、字符类）一致。
     */
    private static Pattern compilePhpPattern(String key) {
        if (key.length() > MAX_CONFIG_LENGTH || key.codePoints().anyMatch(Character::isISOControl)) {
            return null;
        }
        String pattern = key + "/";
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                i += 2;
                continue;
            }
            if (c == '/') {
                if (i != pattern.length() - 1) {
                    return null;
                }
                break;
            }
            i++;
        }
        if (i != pattern.length() - 1) {
            return null;
        }
        try {
            return Pattern.compile(key);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    private static String completeProtocolRelative(String url, String scheme) {
        return url.startsWith("//") ? scheme + ":" + url : url;
    }

    /** PHP {@code empty()} 对字符串的判断：空串与 "0" 为真。 */
    private static boolean phpEmpty(String s) {
        return s.isEmpty() || s.equals("0");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** PHP {@code trim()} 的默认字符集：空格、\t、\n、\r、\0、\x0B。 */
    static String phpTrim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isPhpTrimChar(s.charAt(start))) {
            start++;
        }
        while (end > start && isPhpTrimChar(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isPhpTrimChar(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\0' || c == 0x0B;
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

    private static boolean hasUnpairedSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }
}
