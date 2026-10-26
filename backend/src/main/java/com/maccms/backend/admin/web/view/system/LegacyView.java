package com.maccms.backend.admin.web.view.system;

import com.maccms.backend.admin.application.PhpLoose;
import com.maccms.backend.admin.application.LegacyAdminUrl;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.content.BrowserText;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * B15 后台页面（系统配置、播放器、站群、定时任务、推送、数据库工具、插件等，24 文档第 8 节）的模板取值：旧模板的变量（{@code $config}、
 * {@code $info}、{@code $list}…）按名字放在 {@link #roots()} 里，模板用点分路径取（{@code page.at("config.site.site_name")}）。
 * <ul>
 * <li>{@link #s}：PHP {@code (string)}；不存在的键为空串（旧模板对未定义下标只出 Notice、输出空串）。</li>
 * <li>{@link #v}/{@link #t}：旧模板原样输出到属性值/文本时浏览器看到的文字（{@link BrowserText}），由 jte 转义后输出。</li>
 * <li>{@link #eq}：ThinkPHP 模板的 {@code eq}（PHP 8 {@code ==}，{@link PhpLoose}）；{@link #has}：{@code strpos(…) !== false}。</li>
 * <li>{@link #each}：{@code {volist}}，{@code i()} 是从 1 开始的序号（旧 {@code $i}）。</li>
 * </ul>
 */
public interface LegacyView extends BrowserText {

    AdminHead head();

    LegacyLang lang();

    /** 模板变量：名字 → 值。 */
    Map<String, PhpValue> roots();

    /** 当前请求的地址生成（{@code {:url('…')}}）。 */
    LegacyAdminUrl urls();

    /** {@code {volist}} 的一项。 */
    record Item(Object key, PhpValue value, int i) {
    }

    /** 点分路径取值：第一段是模板变量名，之后逐层取数组下标；不存在为 null。 */
    default PhpValue at(String path) {
        int dot = path.indexOf('.');
        PhpValue root = roots().get(dot < 0 ? path : path.substring(0, dot));
        return dot < 0 ? root : at(root, path.substring(dot + 1));
    }

    /** 从 {@code base} 起逐层取下标。 */
    default PhpValue at(PhpValue base, String path) {
        PhpValue cur = base;
        for (String k : path.split("\\.")) {
            if (!(cur instanceof PhpArray a)) {
                return null;
            }
            cur = a.get(k);
        }
        return cur;
    }

    /** PHP {@code (string)}。 */
    default String s(Object v) {
        return switch (v) {
            case null -> "";
            case PhpValue p -> PhpValues.text(p);
            case String str -> str;
            default -> String.valueOf(v);
        };
    }

    default String v(Object raw) {
        return attr(s(raw));
    }

    default String t(Object raw) {
        return text(s(raw));
    }

    default boolean eq(Object a, Object b) {
        return PhpLoose.equals(a, b);
    }

    default boolean has(Object haystack, String needle) {
        return s(haystack).contains(needle);
    }

    /** 旧 {@code mac_replace_text($txt)}（type=1）：{@code #} 换成回车。 */
    default String replaceText(Object v) {
        return s(v).replace("#", "\r");
    }

    default List<Item> each(Object v) {
        List<Item> out = new ArrayList<>();
        if (v instanceof PhpArray a) {
            int i = 1;
            for (Map.Entry<Object, PhpValue> e : a.entries().entrySet()) {
                out.add(new Item(e.getKey(), e.getValue(), i++));
            }
        }
        return out;
    }

    default String url(String route) {
        return urls().build(route);
    }
}
