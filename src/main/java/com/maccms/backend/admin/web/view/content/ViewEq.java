package com.maccms.backend.admin.web.view.content;

/** 旧模板里两个变量的 {@code eq}/{@code ==}（都是数字串时按数值，否则按文字；null 按空串）。 */
public final class ViewEq {

    private ViewEq() {
    }

    public static boolean eq(String a, String b) {
        return TypeInfoView.looseEqStr(a == null ? "" : a, b == null ? "" : b);
    }
}
