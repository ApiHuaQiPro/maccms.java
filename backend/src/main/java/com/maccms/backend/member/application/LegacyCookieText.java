package com.maccms.backend.member.application;

import com.maccms.backend.common.web.PhpCookies;
import com.maccms.backend.content.application.PhpIntval;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 旧会员登录态对 cookie 值做的文本处理（{@code common/controller/All.php:118-147}、{@code common/model/User.php:422-431}），
 * 按字节复刻 PHP 7.3/8.0 的函数，由 {@code member/php{80,73}-golden.json} 的 label_user 用例锁定。
 */
final class LegacyCookieText {

    private LegacyCookieText() {
    }

    /** PHP {@code empty()} 对字符串：空串与 {@code "0"}。 */
    static boolean empty(byte[] v) {
        return v == null || v.length == 0 || (v.length == 1 && v[0] == '0');
    }

    /** {@code label_user} 的前置判断 {@code intval(cookie('user_id'))} 是否为 0。 */
    static boolean intvalIsZero(byte[] v) {
        return PhpIntval.of(new String(v, StandardCharsets.ISO_8859_1)) == 0;
    }

    /**
     * {@code htmlspecialchars(urldecode(trim($v)))}：PHP {@code trim} 去掉首尾 {@code " \t\n\r\0\x0B"}；{@code urldecode} 解
     * {@code %XX} 与 {@code +}；{@code htmlspecialchars} 用 PHP 7.3/8.0 的默认 {@code ENT_COMPAT | ENT_HTML401}、UTF-8、
     * double_encode——只转义 {@code & " < >}（单引号不转），输入不是合法 UTF-8 时返回空串。
     *
     * @return 处理后的字符串；结果为空串时返回 ""
     */
    static String clean(byte[] raw) {
        byte[] decoded = PhpCookies.urlDecode(trim(raw));
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decoded)).toString();
        } catch (CharacterCodingException e) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static byte[] trim(byte[] v) {
        int start = 0;
        int end = v.length;
        while (start < end && trimmed(v[start])) {
            start++;
        }
        while (end > start && trimmed(v[end - 1])) {
            end--;
        }
        return start == 0 && end == v.length ? v : Arrays.copyOfRange(v, start, end);
    }

    private static boolean trimmed(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r' || b == 0 || b == 0x0B;
    }
}
