package com.maccms.backend.site.application;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValue.PhpInt;
import com.maccms.backend.legacyconfig.application.view.PhpValue.PhpString;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 把旧配置 {@code application/extra/maccms.php} 中 site 已有的设置项按字段并入 {@code maccms.site.*}
 * （B02，docs/architecture/11-guards-and-legacy-config.md 第 4.4 节）。
 *
 * <p>每个字段：本应用配置（环境变量）非空就用它；否则用旧配置的值；都没有时保持原值（空，由现有校验取默认值）。
 * 合并结果仍交给 {@link SiteSettings#from}、{@link SiteUrlProperties#toStyle}、{@link SiteImageProperties#toNormalizer}
 * 校验，校验规则不变。{@code base-url} 不从旧配置取（旧 {@code site_url} 不带协议），仍须由环境变量提供。
 *
 * <p>旧配置里新版不支持的 URL 形态（路由别名、非 {@code /} 分隔符、子目录安装、id 加密/拼音、静态页）直接拒绝，
 * 不静默生成与旧站不同的地址。错误信息只含旧配置的键名，不含取值。
 */
public final class SiteLegacyConfig {

    /** 旧配置文件名，只用于错误信息。 */
    static final String FILE = "extra/maccms.php";

    /**
     * PHP 8 的数字字符串（前后可有空白，十进制，可带小数与指数）。PHP 7 与 PHP 8 对这类字符串与整数的 {@code ==}
     * 结果相同；其余非空字符串两版结果不同，一律拒绝。
     */
    private static final Pattern NUMERIC = Pattern.compile(
            "[ \\t\\n\\r\\x0B\\f]*[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?[ \\t\\n\\r\\x0B\\f]*");

    private SiteLegacyConfig() {
    }

    /**
     * 合并结果。
     *
     * @param fromLegacy 取自旧配置的项：本应用配置项名 → 旧配置键名（按字段顺序），用于校验失败时说明来源
     */
    public record Merged(SiteProperties site, SiteUrlProperties url, SiteImageProperties image,
                         Map<String, String> fromLegacy) {

        public Merged {
            fromLegacy = Collections.unmodifiableMap(new LinkedHashMap<>(fromLegacy));
        }

        /**
         * 执行校验；失败时在错误信息后补充"哪些项取自旧配置"，仍不含取值。
         */
        public <T> T validate(Supplier<T> check) {
            try {
                return check.get();
            } catch (IllegalStateException e) {
                if (fromLegacy.isEmpty()) {
                    throw e;
                }
                List<String> notes = new ArrayList<>();
                fromLegacy.forEach((k, v) -> notes.add(k + "←" + v));
                throw new IllegalStateException(e.getMessage() + "（以下项取自旧配置 " + FILE + "，可用对应环境变量覆盖："
                        + String.join("，", notes) + "）", null);
            }
        }
    }

    /** 没有旧配置时原样返回（行为与 B02 之前相同）。 */
    public static Merged none(SiteProperties site, SiteUrlProperties url, SiteImageProperties image) {
        return new Merged(site, url, image, Map.of());
    }

    /**
     * 按字段合并。旧配置的值类型或取值不受支持时抛 {@link IllegalStateException}，信息只含旧配置键名。
     *
     * @param maccms {@code extra/maccms.php} 解析结果
     */
    public static Merged merge(SiteProperties site, SiteUrlProperties url, SiteImageProperties image, PhpArray maccms) {
        Merger m = new Merger(maccms);

        m.requireUrlShapeSupported();

        SiteProperties mergedSite = new SiteProperties(site.enabled(),
                m.text(site.name(), "maccms.site.name", "site", "site_name"),
                site.baseUrl(),
                m.text(site.keywords(), "maccms.site.keywords", "site", "site_keywords"),
                m.text(site.description(), "maccms.site.description", "site", "site_description"),
                m.text(site.icp(), "maccms.site.icp", "site", "site_icp"),
                m.text(site.logoUrl(), "maccms.site.logo-url", "site", "site_logo"),
                m.status(site.status()),
                m.text(site.closedMessage(), "maccms.site.closed-message", "site", "site_close_tip"),
                site.canonicalRedirect(), site.hitCounterEnabled());
        SiteUrlProperties mergedUrl = new SiteUrlProperties(m.rewrite(url.rewrite()), m.suffixHide(url.suffixHide()));
        SiteImageProperties mergedImage = new SiteImageProperties(
                m.text(image.protocol(), "maccms.site.image.protocol", "upload", "protocol"),
                m.text(image.mode(), "maccms.site.image.mode", "upload", "mode"),
                m.text(image.remoteUrl(), "maccms.site.image.remote-url", "upload", "remoteurl"),
                m.text(image.imgKey(), "maccms.site.image.img-key", "upload", "img_key"),
                m.text(image.imgApi(), "maccms.site.image.img-api", "upload", "img_api"));

        if (!m.errors.isEmpty()) {
            throw new IllegalStateException("site 已启用但旧配置 " + FILE + " 不受支持：" + String.join("；", m.errors));
        }
        return new Merged(mergedSite, mergedUrl, mergedImage, m.fromLegacy);
    }

    private static final class Merger {

        private final PhpArray root;
        private final List<String> errors = new ArrayList<>();
        private final Map<String, String> fromLegacy = new LinkedHashMap<>();

        Merger(PhpArray root) {
            this.root = root;
        }

        /** 文本项：PHP 字符串，或整数（按 PHP 转成十进制字符串）。 */
        String text(String current, String key, String... legacyPath) {
            if (isSet(current)) {
                return current;
            }
            PhpValue v = root.path(legacyPath);
            if (v == null) {
                return current;
            }
            String s = switch (v) {
                case PhpString str -> str.value();
                case PhpInt i -> Long.toString(i.value());
                default -> null;
            };
            if (s == null) {
                errors.add(name(legacyPath) + "（应为字符串）");
                return current;
            }
            fromLegacy.put(key, name(legacyPath));
            return s;
        }

        /**
         * site_status：旧版 {@code index/controller/Base.php:75} 用 {@code site_status == 0} 判断关站。
         * 数字（含数字字符串）按是否等于 0；空串按开站（PHP 8 的结果；PHP 7 下 {@code '' == 0} 为真即关站，见 11 文档第 6 节）；
         * 其余字符串 PHP 7/8 结果不同，拒绝。
         */
        String status(String current) {
            if (isSet(current)) {
                return current;
            }
            String[] path = {"site", "site_status"};
            PhpValue v = root.path(path);
            if (v == null) {
                return current;
            }
            if (v instanceof PhpString s && s.value().isEmpty()) {
                fromLegacy.put("maccms.site.status", name(path));
                return "open";
            }
            BigDecimal n = number(v);
            if (n == null) {
                errors.add(name(path) + "（应为数字，如 0 或 1）");
                return current;
            }
            fromLegacy.put("maccms.site.status", name(path));
            return n.signum() == 0 ? "closed" : "open";
        }

        /** rewrite.status：旧版 {@code common.php:2396-2411} 只处理 {@code ==0} 与 {@code ==1} 两种，其余取值拒绝。 */
        String rewrite(String current) {
            if (isSet(current)) {
                return current;
            }
            String[] path = {"rewrite", "status"};
            PhpValue v = root.path(path);
            if (v == null) {
                return current;
            }
            BigDecimal n = number(v);
            if (n == null || (n.compareTo(BigDecimal.ZERO) != 0 && n.compareTo(BigDecimal.ONE) != 0)) {
                errors.add(name(path) + "（只支持 0 或 1）");
                return current;
            }
            fromLegacy.put("maccms.site.url.rewrite", name(path));
            return Boolean.toString(n.signum() != 0);
        }

        /** rewrite.suffix_hide：旧版 {@code common.php:2414} 用 {@code ==1} 判断；空串两版都不等于 1，按 false。 */
        String suffixHide(String current) {
            if (isSet(current)) {
                return current;
            }
            String[] path = {"rewrite", "suffix_hide"};
            PhpValue v = root.path(path);
            if (v == null) {
                return current;
            }
            boolean hide;
            if (v instanceof PhpString s && s.value().isEmpty()) {
                hide = false;
            } else {
                BigDecimal n = number(v);
                if (n == null) {
                    errors.add(name(path) + "（应为数字，如 0 或 1）");
                    return current;
                }
                hide = n.compareTo(BigDecimal.ONE) == 0;
            }
            fromLegacy.put("maccms.site.url.suffix-hide", name(path));
            return Boolean.toString(hide);
        }

        /**
         * 新版只承接 {@link LegacyUrlStyle} 的固定前提，旧配置不满足时拒绝：
         * <ul>
         * <li>{@code rewrite.route_status} 为假（{@code Init.php:81} 直接作为 {@code url_route_on}）；</li>
         * <li>{@code app.pathinfo_depr} 为 {@code /}，PHP {@code empty()} 时按 {@code /}（{@code Init.php:82}）；</li>
         * <li>{@code site.install_dir} 为 {@code /}（{@code MAC_PATH}，{@code Init.php:48}）；</li>
         * <li>{@code rewrite.type_id}、{@code rewrite.vod_id} 不是 1、2（{@code common.php:1807,1866} 的 switch，数字 id）；</li>
         * <li>{@code view.index}、{@code view.vod_type}、{@code view.vod_detail} 不是 2（静态页，{@code common.php:1758,1828,1858}）。</li>
         * </ul>
         * 键不存在时按旧版的取值（未定义即 null）处理：前两项视为满足，{@code install_dir} 视为不满足。
         */
        void requireUrlShapeSupported() {
            PhpValue route = root.path("rewrite", "route_status");
            if (route != null && truthy(route)) {
                errors.add("rewrite.route_status（新版不支持路由别名，只能为 0）");
            }
            PhpValue depr = root.path("app", "pathinfo_depr");
            if (depr != null && truthy(depr) && !(depr instanceof PhpString s && s.value().equals("/"))) {
                errors.add("app.pathinfo_depr（新版只支持 /）");
            }
            PhpValue dir = root.path("site", "install_dir");
            if (!(dir instanceof PhpString s && s.value().equals("/"))) {
                errors.add("site.install_dir（新版只支持安装在根目录 /）");
            }
            for (String k : List.of("type_id", "vod_id")) {
                PhpValue v = root.path("rewrite", k);
                if (v != null && !(v instanceof PhpString s && s.value().isEmpty())) {
                    BigDecimal n = number(v);
                    if (n == null || n.compareTo(BigDecimal.ONE) == 0 || n.compareTo(BigDecimal.TWO) == 0) {
                        errors.add("rewrite." + k + "（新版只支持数字 id，只能为 0）");
                    }
                }
            }
            for (String k : List.of("index", "vod_type", "vod_detail")) {
                PhpValue v = root.path("view", k);
                if (v != null && !(v instanceof PhpString s && s.value().isEmpty())) {
                    BigDecimal n = number(v);
                    if (n == null || n.compareTo(BigDecimal.TWO) == 0) {
                        errors.add("view." + k + "（新版不支持静态页，不能为 2）");
                    }
                }
            }
        }

        private static boolean isSet(String s) {
            return s != null && !s.isBlank();
        }

        private static String name(String... path) {
            return String.join(".", path);
        }

        /** PHP 整数或数字字符串的数值；其他类型与非数字字符串返回 null。 */
        private static BigDecimal number(PhpValue v) {
            if (v instanceof PhpInt i) {
                return BigDecimal.valueOf(i.value());
            }
            if (v instanceof PhpString s && NUMERIC.matcher(s.value()).matches()) {
                try {
                    return new BigDecimal(s.value().strip());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }

        /** PHP 的布尔转换（只覆盖配置里会出现的类型；其余类型按真处理，使相关检查拒绝）。 */
        private static boolean truthy(PhpValue v) {
            return switch (v) {
                case PhpString s -> !s.value().isEmpty() && !s.value().equals("0");
                case PhpInt i -> i.value() != 0;
                case PhpValue.PhpBool b -> b.value();
                case PhpValue.PhpNull n -> false;
                default -> true;
            };
        }
    }
}
