package com.maccms.backend.legacyconfig.application;

import com.maccms.backend.legacyconfig.application.LegacyConfigException.Reason;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValue.PhpBool;
import com.maccms.backend.legacyconfig.application.view.PhpValue.PhpFloat;
import com.maccms.backend.legacyconfig.application.view.PhpValue.PhpInt;
import com.maccms.backend.legacyconfig.application.view.PhpValue.PhpNull;
import com.maccms.backend.legacyconfig.application.view.PhpValue.PhpString;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * 只读解析旧配置文件：{@code <?php return <值>;}，值是 {@code var_export} 能写出的数据结构（与旧 {@code mac_arr2file}
 * 相同，application/common.php:126-139）。<b>不执行任何 PHP</b>，只接受下面这个 PHP 子集，其余一律拒绝：
 * <ul>
 * <li>文件以 {@code <?php}（不区分大小写）加空白开头，之后是唯一一条 {@code return 值;}，末尾可有 {@code ?>} 与空白；
 * 文件为 UTF-8，不带 BOM；最多 {@link #MAX_BYTES} 字节。</li>
 * <li>值：{@code array(...)} 或 {@code [...]}（可嵌套，最多 {@link #MAX_DEPTH} 层、共 {@link #MAX_ELEMENTS} 个元素，
 * 允许尾逗号与省略键）；单引号字符串（只有 {@code \\}、{@code \'} 两个转义，与 PHP 相同）；十进制整数（可带 {@code -}，
 * 不接受前导零、八进制、十六进制、二进制、数字分隔符，超出 64 位拒绝）；十进制浮点数（{@code 1.5}、{@code -0.0}、
 * {@code 1.0E+25}）；{@code true}、{@code false}、{@code null}（不区分大小写）。</li>
 * <li>键：整数或单引号字符串，字符串键按 PHP 规则规范化（{@link PhpArray#normalizeKey}）；重复键后者覆盖前者、保留原位置；
 * 省略键时取"已用最大整数键 + 1"，没有整数键时为 0。已用整数键全是负数时省略键会被拒绝：PHP 7 从 0 开始，
 * PHP 8 从"最大负数键 + 1"开始（本机 7.2/7.3 与 8.0/8.2 实测），线上 PHP 版本未核实，不猜。</li>
 * <li>空白与注释：空格、制表、换行，{@code //} 行注释与 {@code /* *\/} 块注释（手写的 captcha.php、queue.php 带版权注释）。
 * {@code #} 注释（PHP 8 中 {@code #[} 是属性）与含 {@code ?>} 的行注释（PHP 在那里结束代码段）拒绝。</li>
 * </ul>
 * 拒绝的写法（合法 PHP，但不是 var_export 数据，或会执行代码）：常量与函数调用（含 {@code NAN}、{@code INF}、
 * {@code PHP_INT_MAX}）、变量、双引号字符串与 heredoc（可插值）、{@code .} 拼接与其他运算、类型转换、
 * {@code \Foo::__set_state}、{@code (object)}、{@code include}/{@code eval}/反引号等。
 * PHP 7.3+ 的 var_export 对含 NUL 字节的字符串写成 {@code '' . "\0" . ''}，对 {@code PHP_INT_MIN} 写成
 * {@code -9223372036854775807-1}，二者都属拼接/运算，同样拒绝（旧配置中没有这两种值）。
 */
public final class VarExportParser {

    /** 文件大小上限：1 MiB（仓库内最大的 maccms.php 约 18 KB）。 */
    public static final int MAX_BYTES = 1 << 20;
    /** 数组嵌套层数上限（顶层数组为第 1 层；仓库内最深 3 层）。 */
    public static final int MAX_DEPTH = 32;
    /** 全文件数组元素总数上限。 */
    public static final int MAX_ELEMENTS = 100_000;

    private final String src;
    private final int len;
    private int pos;
    private int elements;

    private VarExportParser(String src) {
        this.src = src;
        this.len = src.length();
    }

    /** 解析整个文件内容，顶层必须是数组。失败抛 {@link LegacyConfigException}（不含文件内容）。 */
    public static PhpArray parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) {
            throw new LegacyConfigException(Reason.TOO_LARGE, "文件超过 " + MAX_BYTES + " 字节");
        }
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xEF && (bytes[1] & 0xff) == 0xBB && (bytes[2] & 0xff) == 0xBF) {
            throw new LegacyConfigException(Reason.INVALID_ENCODING, "文件以 UTF-8 BOM 开头（PHP 会把它当作输出）");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new LegacyConfigException(Reason.INVALID_ENCODING, "不是合法的 UTF-8");
        }
        return new VarExportParser(text).file();
    }

    private PhpArray file() {
        if (!src.regionMatches(true, 0, "<?php", 0, 5)) {
            throw fail(Reason.SYNTAX, "文件必须以 <?php 开头");
        }
        pos = 5;
        if (pos >= len || !isWhitespace(src.charAt(pos))) {
            throw fail(Reason.SYNTAX, "<?php 之后必须是空白");
        }
        skipTrivia();
        if (!src.regionMatches(true, pos, "return", 0, 6) || (pos + 6 < len && isIdentifierChar(src.charAt(pos + 6)))) {
            throw fail(Reason.UNSUPPORTED_CONSTRUCT, "只接受唯一一条 return 语句");
        }
        pos += 6;
        skipTrivia();
        PhpValue value = value(1);
        skipTrivia();
        if (pos >= len || src.charAt(pos) != ';') {
            throw fail(Reason.SYNTAX, "return 的值之后应为 ;");
        }
        pos++;
        skipTrivia();
        if (src.startsWith("?>", pos)) {
            pos += 2;
            while (pos < len && isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
        if (pos < len) {
            throw fail(Reason.UNSUPPORTED_CONSTRUCT, "return 语句之后只允许空白、注释与结尾的 ?>");
        }
        if (!(value instanceof PhpArray array)) {
            throw new LegacyConfigException(Reason.UNSUPPORTED_CONSTRUCT, "顶层值必须是数组");
        }
        return array;
    }

    private PhpValue value(int depth) {
        if (pos >= len) {
            throw fail(Reason.SYNTAX, "文件提前结束");
        }
        char c = src.charAt(pos);
        if (c == '\'') {
            return new PhpString(singleQuoted());
        }
        if (c == '[') {
            pos++;
            return array(depth, ']');
        }
        if (c == '-' || isDigit(c)) {
            return number();
        }
        if (isIdentifierStart(c)) {
            int start = pos;
            while (pos < len && isIdentifierChar(src.charAt(pos))) {
                pos++;
            }
            String word = src.substring(start, pos).toLowerCase(Locale.ROOT);
            switch (word) {
                case "array" -> {
                    skipTrivia();
                    if (pos >= len || src.charAt(pos) != '(') {
                        throw fail(Reason.SYNTAX, "array 之后应为 (");
                    }
                    pos++;
                    return array(depth, ')');
                }
                case "true" -> {
                    return new PhpBool(true);
                }
                case "false" -> {
                    return new PhpBool(false);
                }
                case "null" -> {
                    return PhpNull.INSTANCE;
                }
                default -> {
                    pos = start;
                    throw fail(Reason.UNSUPPORTED_CONSTRUCT, "不支持常量、函数调用、include/eval 等写法");
                }
            }
        }
        if (c == '"' || c == '<' || c == '`') {
            throw fail(Reason.UNSUPPORTED_CONSTRUCT, "不支持双引号字符串、heredoc、反引号（可插值或执行命令）");
        }
        if (c == '$' || c == '(' || c == '\\' || c == '+' || c == '!' || c == '@' || c == '&') {
            throw fail(Reason.UNSUPPORTED_CONSTRUCT, "不支持变量、类型转换、命名空间引用与运算");
        }
        throw fail(Reason.SYNTAX, "此处应为值");
    }

    private PhpArray array(int depth, char close) {
        if (depth > MAX_DEPTH) {
            throw fail(Reason.LIMIT_EXCEEDED, "数组嵌套超过 " + MAX_DEPTH + " 层");
        }
        LinkedHashMap<Object, PhpValue> map = new LinkedHashMap<>();
        Long maxIntKey = null;
        while (true) {
            skipTrivia();
            if (pos < len && src.charAt(pos) == close) {
                pos++;
                return new PhpArray(map);
            }
            int itemStart = pos;
            PhpValue first = value(depth + 1);
            skipTrivia();
            Object key;
            PhpValue item;
            if (src.startsWith("=>", pos)) {
                pos += 2;
                skipTrivia();
                key = key(first, itemStart);
                item = value(depth + 1);
            } else {
                if (maxIntKey != null && maxIntKey < 0) {
                    pos = itemStart;
                    throw fail(Reason.UNSUPPORTED_CONSTRUCT, "整数键全为负数时省略键（PHP 7 与 PHP 8 结果不同）");
                }
                if (maxIntKey != null && maxIntKey == Long.MAX_VALUE) {
                    pos = itemStart;
                    throw fail(Reason.LIMIT_EXCEEDED, "自动整数键超出 64 位范围");
                }
                key = maxIntKey == null ? 0L : maxIntKey + 1;
                item = first;
            }
            if (++elements > MAX_ELEMENTS) {
                throw fail(Reason.LIMIT_EXCEEDED, "数组元素总数超过 " + MAX_ELEMENTS);
            }
            map.put(key, item);
            if (key instanceof Long k && (maxIntKey == null || k > maxIntKey)) {
                maxIntKey = k;
            }
            skipTrivia();
            if (pos < len && src.charAt(pos) == ',') {
                pos++;
            } else if (pos < len && src.charAt(pos) == close) {
                pos++;
                return new PhpArray(map);
            } else if (pos < len && src.charAt(pos) == '.') {
                throw fail(Reason.UNSUPPORTED_CONSTRUCT, "不支持字符串拼接");
            } else if (pos < len && "+-*/%|&^?:<>=!".indexOf(src.charAt(pos)) >= 0) {
                throw fail(Reason.UNSUPPORTED_CONSTRUCT, "不支持运算");
            } else {
                throw fail(Reason.SYNTAX, "数组元素之后应为 , 或 " + close);
            }
        }
    }

    private Object key(PhpValue v, int at) {
        if (v instanceof PhpString s) {
            return PhpArray.normalizeKey(s.value());
        }
        if (v instanceof PhpInt i) {
            return i.value();
        }
        pos = at;
        throw fail(Reason.UNSUPPORTED_CONSTRUCT, "数组键只能是整数或单引号字符串");
    }

    private PhpValue number() {
        int start = pos;
        if (src.charAt(pos) == '-') {
            pos++;
            if (pos >= len || !isDigit(src.charAt(pos))) {
                pos = start;
                throw fail(Reason.UNSUPPORTED_CONSTRUCT, "- 之后必须紧跟数字（不支持运算）");
            }
        }
        int digitsStart = pos;
        while (pos < len && isDigit(src.charAt(pos))) {
            pos++;
        }
        boolean isFloat = false;
        if (pos < len && src.charAt(pos) == '.') {
            pos++;
            if (pos >= len || !isDigit(src.charAt(pos))) {
                throw fail(Reason.SYNTAX, "小数点之后必须有数字");
            }
            while (pos < len && isDigit(src.charAt(pos))) {
                pos++;
            }
            isFloat = true;
        }
        if (pos < len && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            pos++;
            if (pos < len && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                pos++;
            }
            if (pos >= len || !isDigit(src.charAt(pos))) {
                throw fail(Reason.SYNTAX, "指数部分必须有数字");
            }
            while (pos < len && isDigit(src.charAt(pos))) {
                pos++;
            }
            isFloat = true;
        }
        if (pos < len && (isIdentifierChar(src.charAt(pos)) || src.charAt(pos) == '.')) {
            pos = start;
            throw fail(Reason.UNSUPPORTED_CONSTRUCT, "不支持的数字写法（十六进制、八进制、二进制、数字分隔符等）");
        }
        String text = src.substring(start, pos);
        if (isFloat) {
            double d = Double.parseDouble(text);
            if (Double.isInfinite(d)) {
                pos = start;
                throw fail(Reason.LIMIT_EXCEEDED, "浮点数超出范围");
            }
            return new PhpFloat(d);
        }
        if (pos - digitsStart > 1 && src.charAt(digitsStart) == '0') {
            pos = start;
            throw fail(Reason.UNSUPPORTED_CONSTRUCT, "不支持前导零整数（PHP 按八进制解释）");
        }
        try {
            return new PhpInt(Long.parseLong(text));
        } catch (NumberFormatException e) {
            pos = start;
            throw fail(Reason.LIMIT_EXCEEDED, "整数超出 64 位范围");
        }
    }

    private String singleQuoted() {
        int start = pos;
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < len) {
            char c = src.charAt(pos);
            if (c == '\\' && pos + 1 < len && (src.charAt(pos + 1) == '\\' || src.charAt(pos + 1) == '\'')) {
                sb.append(src.charAt(pos + 1));
                pos += 2;
            } else if (c == '\'') {
                pos++;
                return sb.toString();
            } else {
                sb.append(c);
                pos++;
            }
        }
        pos = start;
        throw fail(Reason.SYNTAX, "字符串未闭合");
    }

    /** 跳过空白与注释。 */
    private void skipTrivia() {
        while (pos < len) {
            char c = src.charAt(pos);
            if (isWhitespace(c)) {
                pos++;
            } else if (src.startsWith("//", pos)) {
                int start = pos;
                while (pos < len && src.charAt(pos) != '\n' && src.charAt(pos) != '\r') {
                    if (src.startsWith("?>", pos)) {
                        pos = start;
                        throw fail(Reason.UNSUPPORTED_CONSTRUCT, "行注释中出现 ?>（PHP 在此结束代码段）");
                    }
                    pos++;
                }
            } else if (src.startsWith("/*", pos)) {
                int end = src.indexOf("*/", pos + 2);
                if (end < 0) {
                    throw fail(Reason.SYNTAX, "块注释未闭合");
                }
                pos = end + 2;
            } else if (c == '#') {
                throw fail(Reason.UNSUPPORTED_CONSTRUCT, "不支持 # 注释（PHP 8 中 #[ 是属性）");
            } else {
                return;
            }
        }
    }

    private LegacyConfigException fail(Reason reason, String detail) {
        int line = 1;
        int col = 1;
        for (int i = 0; i < pos && i < len; i++) {
            if (src.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new LegacyConfigException(reason, detail + "（第 " + line + " 行第 " + col + " 列）");
    }

    /** PHP 词法中的空白：空格、\t、\n、\r。 */
    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** PHP 标识符：[a-zA-Z_\x80-\xff]，UTF-8 多字节字符的每个字节都在 \x80-\xff 内。 */
    private static boolean isIdentifierStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c >= 0x80;
    }

    private static boolean isIdentifierChar(char c) {
        return isIdentifierStart(c) || isDigit(c);
    }
}
