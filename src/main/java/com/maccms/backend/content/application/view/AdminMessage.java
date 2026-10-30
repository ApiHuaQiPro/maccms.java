package com.maccms.backend.content.application.view;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 后台提示语（B13）：旧版的提示是若干段 {@code lang(键)} 与原样文字拼接（如 {@code lang('param_err').'：'.lang('validate/require_name')}），
 * 由后台按所选语言包渲染（{@link #render}）。
 *
 * @param parts 各段；{@code key} 非空时取语言包，否则用 {@code text}
 */
public record AdminMessage(List<Part> parts) {

    public AdminMessage {
        parts = List.copyOf(parts);
    }

    /** 一段。 */
    public record Part(String key, String text) {
    }

    public static AdminMessage lang(String key) {
        return new AdminMessage(List.of(new Part(key, null)));
    }

    public static AdminMessage text(String text) {
        return new AdminMessage(List.of(new Part(null, text)));
    }

    /** 追加一段语言包文字。 */
    public AdminMessage thenLang(String key) {
        List<Part> p = new ArrayList<>(parts);
        p.add(new Part(key, null));
        return new AdminMessage(p);
    }

    /** 追加一段原样文字。 */
    public AdminMessage thenText(String text) {
        List<Part> p = new ArrayList<>(parts);
        p.add(new Part(null, text));
        return new AdminMessage(p);
    }

    /** 按语言包（{@code lang.apply(键)} 同 ThinkPHP {@code lang()}）拼出提示文字。 */
    public String render(UnaryOperator<String> lang) {
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            sb.append(p.key() != null ? lang.apply(p.key()) : p.text());
        }
        return sb.toString();
    }
}
