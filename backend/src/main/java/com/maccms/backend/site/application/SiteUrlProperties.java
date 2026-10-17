package com.maccms.backend.site.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 旧站 URL 形态配置 {@code maccms.site.url.*}（MACC-14），与 {@link SiteProperties} 同属 {@code maccms.site.*}，
 * 来自本应用的环境变量，留空时可取旧配置（{@link SiteLegacyConfig}）。按原始字符串绑定，校验在 {@link #toStyle()} 中进行，错误只报配置项名称。
 *
 * <p>首批页面只承接 {@code pathinfo_depr='/'}、不启用路由别名（{@code route_status=0}）的形态，所以只开放两项；
 * 其余前提见 {@link LegacyUrlStyle}。旧配置的线上取值未核实（03 方案第 5.1 节）。
 *
 * @param rewrite    旧 rewrite.status（application/extra/maccms.php:490）：true 时地址不含 index.php，默认 false
 * @param suffixHide 旧 rewrite.suffix_hide（:488）：true 时 .html 换成 /，默认 false
 */
@ConfigurationProperties(prefix = "maccms.site.url")
public record SiteUrlProperties(String rewrite, String suffixHide) {

    /** 校验并转换为 {@link LegacyUrlStyle}；不合法时抛 {@link IllegalStateException}，不回显取值。 */
    public LegacyUrlStyle toStyle() {
        List<String> errors = new ArrayList<>();
        boolean rw = flag(rewrite, "maccms.site.url.rewrite", errors);
        boolean hide = flag(suffixHide, "maccms.site.url.suffix-hide", errors);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("site 已启用但 URL 配置不合法：" + String.join("；", errors));
        }
        return new LegacyUrlStyle(rw, hide, false, '/');
    }

    private static boolean flag(String raw, String key, List<String> errors) {
        String v = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "", "false" -> false;
            case "true" -> true;
            default -> {
                errors.add(key + "（true 或 false）");
                yield false;
            }
        };
    }
}
