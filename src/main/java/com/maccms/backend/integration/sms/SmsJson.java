package com.maccms.backend.integration.sms;

import java.util.Locale;

/** PHP {@code json_encode} 的字符串写法：默认转义 {@code /} 与非 ASCII（{@code \\uXXXX}）；{@code JSON_UNESCAPED_UNICODE} 时非 ASCII 原样。 */
final class SmsJson {

    private SmsJson() {
    }

    static String string(String s, boolean unescapedUnicode) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '/' -> sb.append("\\/");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || (c > 0x7e && !unescapedUnicode)) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
