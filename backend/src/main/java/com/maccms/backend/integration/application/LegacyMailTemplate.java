package com.maccms.backend.integration.application;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 邮件标题与正文模板（B25，32 文档第 4.3 节）：旧版把配置 {@code email.tpl.*} 交给 ThinkPHP {@code View::display()} 编译执行，
 * 变量有 {@code $code}、{@code $time} 与 {@code label_maccms} 放进视图的 {@code $maccms}（{@code site} 配置段等）。
 *
 * <p>新版只替换变量输出标签 {@code {$名}} 与 {@code {$名.键}}：{@code code}、{@code time} 与 {@code maccms.<键>} 按给定的值原样输出，
 * 其他变量（旧版未赋值时输出空）输出空串；其余模板语法（函数调用 {@code {:…}}、标签、过滤器 {@code |}）原样保留、不执行（D-B25-7）。
 */
public final class LegacyMailTemplate {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\$(\\w+)(?:\\.(\\w+))?}");

    private LegacyMailTemplate() {
    }

    /**
     * @param code   验证码（{@code {$code}}）
     * @param time   {@code email.time} 原文（{@code {$time}}）
     * @param maccms {@code {$maccms.<键>}} 的值（{@code site} 配置段的标量）
     */
    public static String render(String template, String code, String time, Map<String, String> maccms) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher m = VARIABLE.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String key = m.group(2);
            String value;
            if (key == null) {
                value = name.equals("code") ? code : name.equals("time") ? time : "";
            } else {
                value = name.equals("maccms") ? maccms.getOrDefault(key, "") : "";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** PHP {@code htmlspecialchars_decode($s)}（默认 {@code ENT_COMPAT}）：还原 {@code &amp; &quot; &lt; &gt;}，单引号实体保留。 */
    public static String htmlspecialcharsDecode(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s == null ? "" : s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&') {
                if (s.startsWith("&amp;", i)) {
                    sb.append('&');
                    i += 5;
                    continue;
                }
                if (s.startsWith("&quot;", i)) {
                    sb.append('"');
                    i += 6;
                    continue;
                }
                if (s.startsWith("&lt;", i)) {
                    sb.append('<');
                    i += 4;
                    continue;
                }
                if (s.startsWith("&gt;", i)) {
                    sb.append('>');
                    i += 4;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
