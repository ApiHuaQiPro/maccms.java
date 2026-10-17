package com.maccms.backend.site.application;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 旧前台的两个页面开关（B05，docs/architecture/15-b05-readonly-pages.md 第 7 节）：
 * <ul>
 * <li>{@code ajaxPage}：旧 {@code app.ajax_page}（application/index/controller/Base.php:45-51），关闭时各模块的 ajax_detail、
 * ajax_show、ajax_search 不提供（404）；</li>
 * <li>{@code show}：旧 {@code app.show}（Base.php:29-43），关闭时各模块的 show 筛选页与 ajax_show 不提供。旧 {@code app.show_verify=1}
 * 要求先过验证码，新版没有验证码，按关闭处理（与旧版的差异）。</li>
 * </ul>
 * 取值：环境变量（{@code maccms.site.features.*}，true/false）非空时用它；否则取旧配置（{@code extra/maccms.php}）；都没有时按仓库值关闭。
 */
public record SiteFeatures(boolean ajaxPage, boolean show) {

    public static final SiteFeatures CLOSED = new SiteFeatures(false, false);

    /** {@code maccms.site.features.*}，按原始字符串绑定。 */
    @ConfigurationProperties(prefix = "maccms.site.features")
    public record Properties(String ajaxPage, String show) {
    }

    /**
     * 不合法时抛 {@link IllegalStateException}，信息只含配置项名称。
     *
     * @param maccms 旧配置 {@code extra/maccms.php}，没有配置旧配置目录时为 null
     */
    public static SiteFeatures from(Properties p, PhpArray maccms) {
        List<String> errors = new ArrayList<>();
        Boolean ajax = flag(p == null ? null : p.ajaxPage(), "maccms.site.features.ajax-page", errors);
        Boolean show = flag(p == null ? null : p.show(), "maccms.site.features.show", errors);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("site 页面开关不合法：" + String.join("；", errors));
        }
        boolean legacyAjax = maccms != null && one(maccms.path("app", "ajax_page"));
        boolean legacyShow = maccms != null && one(maccms.path("app", "show")) && !one(maccms.path("app", "show_verify"));
        return new SiteFeatures(ajax != null ? ajax : legacyAjax, show != null ? show : legacyShow);
    }

    private static Boolean flag(String raw, String key, List<String> errors) {
        String v = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "" -> null;
            case "true" -> true;
            case "false" -> false;
            default -> {
                errors.add(key + "（true 或 false）");
                yield null;
            }
        };
    }

    /** 旧代码的 {@code == 1}（数字或数字字符串 1）。 */
    private static boolean one(PhpValue v) {
        if (v instanceof PhpValue.PhpInt i) {
            return i.value() == 1;
        }
        return v instanceof PhpValue.PhpString s && s.value().strip().equals("1");
    }
}
