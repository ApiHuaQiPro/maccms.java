package com.maccms.backend.site.application;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 旧版每个前台请求的"手机模板与站群"选择（B12 承接 B05 D-B05-8，docs/architecture/21-b12-admin-framework.md 第 12 节；旧
 * {@code application/common/behavior/Init.php:12-63}）。纯计算，由 PHP 黄金 {@code site/template-select/php*-golden.json} 逐项锁定：
 * <ol>
 * <li>手机判定：{@code User-Agent} 在任意位置含 {@code nokia|sony|ericsson|mot|samsung|sgh|lg|philips|panasonic|alcatel|lenovo|meizu|cldc|midp|iphone|wap|mobile|android}
 * （不分大小写；{@code mot}、{@code lg} 这类短词会误判，旧版如此）。</li>
 * <li>站群：{@code extra/domain.php} 是数组且有 {@code Host} 头<b>原样</b>（区分大小写）的键时，把该项并入 site 配置；
 * {@code mob_template_dir} 为空或 {@code no} 时取并入后的 {@code template_dir}；{@code site_wapurl}、{@code mob_html_dir}、
 * {@code mob_ads_dir} 分别取并入后的 {@code site_url}、{@code html_dir}、{@code ads_dir}。命中站群时<b>不再切换手机模板</b>。</li>
 * <li>手机模板：手机访问、没有命中站群，且 {@code mob_status == 2}，或 {@code mob_status == 1} 且 {@code Host} 原样等于
 * {@code site_wapurl}（PHP 宽松比较：{@code '01'}、{@code ' 2'}、整数都按数值）时，模板、html、广告目录取 {@code mob_*}（非站群时
 * {@code mob_template_dir} 为空或 {@code no} 也照用，旧版不回退）。</li>
 * </ol>
 * 结果中的 {@code pathTpl}、{@code pathAds} 就是旧模板里的 {@code {$maccms.path_tpl}}、{@code {$maccms.path_ads}}。
 *
 * <p>与旧版的差异：请求没有 {@code User-Agent} 时旧版整页报错（ThinkPHP 把 Notice 转成异常），新版按桌面处理（D-B12-L2）。
 * 新版前台是一套响应式 jte 模板（B05），这里的选择结果供页面中依赖手机标志与目录的输出使用；接线在 B12 之后与前端协调（21 文档第 12 节）。
 */
public final class SiteTemplateSelection {

    private static final Pattern MOBILE = Pattern.compile(
            "(nokia|sony|ericsson|mot|samsung|sgh|lg|philips|panasonic|alcatel|lenovo|meizu|cldc|midp|iphone|wap|mobile|android)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC = Pattern.compile(
            "[ \\t\\n\\r\\x0B\\f]*[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?[ \\t\\n\\r\\x0B\\f]*");

    /**
     * 选择结果。
     *
     * @param wap          旧 {@code MAC_MOB}
     * @param domain       命中了站群
     * @param pathTemplate 旧 {@code MAC_PATH_TEMPLATE}（安装目录 + {@code template/模板目录/}）
     * @param pathTpl      旧 {@code MAC_PATH_TPL}
     * @param pathAds      旧 {@code MAC_PATH_ADS}
     * @param site         并入站群之后的 site 配置（文本值）
     */
    public record Result(boolean wap, boolean domain, String templateDir, String htmlDir, String adsDir,
                         String pathTemplate, String pathTpl, String pathAds, Map<String, String> site) {
    }

    private SiteTemplateSelection() {
    }

    /**
     * @param site      旧配置 {@code extra/maccms.php} 的 {@code site} 数组
     * @param domains   旧配置 {@code extra/domain.php}（不是数组或没有该文件时传 null）
     * @param userAgent 请求的 {@code User-Agent}，没有时为 null
     * @param host      请求的 {@code Host} 头原文（没有时为空串）
     */
    public static Result resolve(PhpArray site, PhpValue domains, String userAgent, String host) {
        Map<String, String> s = new LinkedHashMap<>();
        Map<String, PhpValue> raw = new LinkedHashMap<>();
        for (Map.Entry<Object, PhpValue> e : site.entries().entrySet()) {
            s.put(String.valueOf(e.getKey()), PhpValues.text(e.getValue()));
            raw.put(String.valueOf(e.getKey()), e.getValue());
        }
        boolean mobile = userAgent != null && MOBILE.matcher(userAgent).find();
        boolean domain = false;
        if (domains instanceof PhpArray d && host != null) {
            PhpValue entry = d.get(host);
            if (entry instanceof PhpArray over && !PhpValues.empty(entry)) {
                for (Map.Entry<Object, PhpValue> e : over.entries().entrySet()) {
                    s.put(String.valueOf(e.getKey()), PhpValues.text(e.getValue()));
                    raw.put(String.valueOf(e.getKey()), e.getValue());
                }
                domain = true;
                String mob = s.getOrDefault("mob_template_dir", "");
                if (mob.isEmpty() || mob.equals("0") || mob.equals("no")) {
                    s.put("mob_template_dir", s.getOrDefault("template_dir", ""));
                }
                s.put("site_wapurl", s.getOrDefault("site_url", ""));
                s.put("mob_html_dir", s.getOrDefault("html_dir", ""));
                s.put("mob_ads_dir", s.getOrDefault("ads_dir", ""));
            }
        }
        String templateDir = s.getOrDefault("template_dir", "");
        String htmlDir = s.getOrDefault("html_dir", "");
        String adsDir = s.getOrDefault("ads_dir", "");
        boolean wap = false;
        if (mobile && !domain) {
            PhpValue status = raw.get("mob_status");
            String h = host == null ? "" : host;
            if (looseEquals(status, 2) || (looseEquals(status, 1) && h.equals(s.getOrDefault("site_wapurl", "")))) {
                wap = true;
                templateDir = s.getOrDefault("mob_template_dir", "");
                htmlDir = s.getOrDefault("mob_html_dir", "");
                adsDir = s.getOrDefault("mob_ads_dir", "");
            }
        }
        String pathTemplate = s.getOrDefault("install_dir", "") + "template/" + templateDir + "/";
        return new Result(wap, domain, templateDir, htmlDir, adsDir, pathTemplate, pathTemplate + htmlDir + "/",
                pathTemplate + adsDir + "/", Map.copyOf(s));
    }

    /**
     * 同一个地址的输出会不会随 {@code User-Agent} 不同：主站配置的 {@code mob_status} 宽松等于 1 或 2 时会（站群项里的 {@code mob_status}
     * 不起作用——命中站群就不切手机模板）。页面响应据此加 {@code Vary: User-Agent}（旧版的页面缓存键带 {@code MAC_MOB}，All.php:27,52）。
     */
    public static boolean dependsOnUserAgent(PhpArray site) {
        PhpValue status = site.get("mob_status");
        return looseEquals(status, 1) || looseEquals(status, 2);
    }

    /** PHP 8 的 {@code $v == n}（n 为整数）：数字字符串按数值，非数字字符串不等；整数、浮点按数值；布尔按真假；null 等于 0。 */
    static boolean looseEquals(PhpValue v, int n) {
        return switch (v) {
            case null -> n == 0;
            case PhpValue.PhpString s -> NUMERIC.matcher(s.value()).matches() && Double.parseDouble(s.value().trim()) == n;
            case PhpValue.PhpInt i -> i.value() == n;
            case PhpValue.PhpFloat f -> f.value() == n;
            case PhpValue.PhpBool b -> b.value() == (n != 0);
            case PhpValue.PhpNull x -> n == 0;
            case PhpArray a -> false;
        };
    }
}
