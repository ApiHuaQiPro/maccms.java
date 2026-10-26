package com.maccms.backend.content.application;

import java.nio.charset.StandardCharsets;

/** 后台内容保存时取参数文字的公共检查（B13）。 */
final class AdminText {

    private AdminText() {
    }

    /** 参数按 UTF-8 解码；缺失或 null 为空串；数组、非法 UTF-8、4 字节字符"参数错误"（旧版报错或写库时 MySQL 拒绝）。 */
    static String utf8(PhpForm data, String name) {
        if (data.isArray(name)) {
            throw AdminRejectedException.paramError();
        }
        byte[] v = data.scalar(name);
        return v == null ? "" : utf8(v);
    }

    static String utf8(byte[] v) {
        String s = AdminRowValues.utf8(v);
        if (s == null) {
            throw AdminRejectedException.paramError();
        }
        return s;
    }

    /** 标量参数（数组"参数错误"：旧版对数组调用字符串函数报错）。 */
    static void requireScalar(PhpForm data, String name) {
        if (data.isArray(name)) {
            throw AdminRejectedException.paramError();
        }
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
