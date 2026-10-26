package com.maccms.backend.content.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 旧 {@code Pinyin::get($str)}（application/common/util/Pinyin.php，默认参数 {@code 'all'}、占位符空串、
 * 允许字符 {@code /[a-zA-Z\d ]/}）：名称留空的拼音字段（{@code vod_en} 等）由它生成。
 *
 * <p>字典是仓库 {@code static/data/pinyin.dat}（构建时原样打进 jar 的 {@code legacy/data/pinyin.dat}，不修改）：{@code 拼音:字,字|…}，
 * 同一个字出现在几行时取最后一行（旧版逐行覆盖数组）。处理：{@code trim} 后逐个字符——ASCII 字符只保留字母、数字与空格；多字节字符查字典，
 * 查不到的丢掉；最后删除 {@code 空格 + / \ | ' ? % # & = ! ( ) ; : < >}。输入须是合法 UTF-8（调用方先检查）。
 */
public final class LegacyPinyin {

    static final String RESOURCE = "legacy/data/pinyin.dat";
    private static volatile Map<String, String> table;

    private LegacyPinyin() {
    }

    /** {@code Pinyin::get($s)}；{@code s} 是合法 UTF-8 的字符串。 */
    public static String get(String s) {
        Map<String, String> t = table();
        String str = phpTrim(s);
        StringBuilder rs = new StringBuilder();
        for (int i = 0; i < str.length(); ) {
            int cp = str.codePointAt(i);
            String chr = new String(Character.toChars(cp));
            if (cp < 0x80) {
                if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || (cp >= '0' && cp <= '9') || cp == ' ') {
                    rs.append((char) cp);
                }
            } else {
                String py = t.get(chr);
                if (py != null) {
                    rs.append(py);
                }
            }
            i += Character.charCount(cp);
        }
        StringBuilder out = new StringBuilder(rs.length());
        for (int i = 0; i < rs.length(); i++) {
            char c = rs.charAt(i);
            if (" +/\\|'?%#&=!();:<>".indexOf(c) < 0) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** 旧版的首字母：{@code strtoupper(substr($en, 0, 1))}（按字节，只转换 ASCII 小写字母）。 */
    public static byte[] letter(byte[] en) {
        if (en.length == 0) {
            return new byte[0];
        }
        byte b = en[0];
        return new byte[] {b >= 'a' && b <= 'z' ? (byte) (b - 32) : b};
    }

    private static String phpTrim(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && " \t\n\r\0\u000B".indexOf(s.charAt(a)) >= 0) {
            a++;
        }
        while (b > a && " \t\n\r\0\u000B".indexOf(s.charAt(b - 1)) >= 0) {
            b--;
        }
        return s.substring(a, b);
    }

    static Map<String, String> table() {
        Map<String, String> t = table;
        if (t == null) {
            synchronized (LegacyPinyin.class) {
                t = table;
                if (t == null) {
                    t = load();
                    table = t;
                }
            }
        }
        return t;
    }

    private static Map<String, String> load() {
        try (InputStream in = LegacyPinyin.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("缺少拼音字典 " + RESOURCE);
            }
            String data = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> m = new HashMap<>(32768);
            for (String row : data.split("\\|", -1)) {
                int colon = row.indexOf(':');
                String py = colon < 0 ? row : row.substring(0, colon);
                String vals = colon < 0 ? "" : row.substring(colon + 1);
                int second = vals.indexOf(':');
                if (second >= 0) {
                    // explode(':') 的第二段只到下一个冒号为止
                    vals = vals.substring(0, second);
                }
                for (String ch : vals.split(",", -1)) {
                    m.put(ch, py);
                }
            }
            return Map.copyOf(m);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
