package com.maccms.backend.collect.application;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 自定义规则的"过滤规则"所用的 PHP {@code preg_replace}（B20，28 文档第 3.3 节）。旧 {@code Collection::replace_item} 把每行
 * {@code 模式[|]替换} 拼成 {@code '/' . str_replace('/', '\/', 模式) . '/i'}，模式是管理员填写的 PCRE。
 *
 * <p><b>按字节</b>：旧版不带 {@code u} 修饰符，PCRE 按字节匹配（{@code .} 是一个字节，{@code /i} 只对 ASCII 字母不区分大小写）。这里的字符串
 * 一律是"一个字符一个字节"的 ISO-8859-1 文字（{@link CjRules} 的约定），Java 正则在其上的匹配与 PCRE 的字节模式相同；
 * 另加 {@link Pattern#UNIX_LINES}，使 {@code .}、{@code ^}、{@code $} 只把 {@code \n} 当换行（PCRE 默认）。
 *
 * <p><b>模式</b>：按 PHP 的方式找结束分隔符（反斜杠转义下一个字符）与修饰符，再把 PCRE 语法换成等价的 Java 语法（字符类里的 {@code [}、
 * {@code &}、POSIX 类 {@code [:alpha:]}，不构成量词的 {@code {}，{@code \x}、八进制、{@code (?P<名称>)}、{@code (?#注释)} 等）。
 * Java 不支持的 PCRE 构造（递归、条件、回溯控制动词、{@code \K}、修饰符 {@code A D U u J n} 等）按"模式无效"处理——与 PHP 编译失败
 * 相同，{@code preg_replace} 返回 null（28 文档 D-B20-6）。
 *
 * <p><b>替换</b>：与 {@code php_pcre_replace_impl} 相同——{@code \n}、{@code $n}、<code>${n}</code>（n 为一到两位数字）引用分组，没有该分组
 * 或分组没有参与匹配时为空串；{@code \\}、{@code \$} 写出反斜杠后的那个字符；其余字符原样。空匹配之后从下一个字符继续（PCRE 会先在同一
 * 位置再试一次非空匹配，只有"优先匹配空串"的模式才会因此不同，D-B20-6）。
 *
 * <p><b>时限</b>：每次替换有截止时间（{@link Deadline}），超时按匹配出错处理（PHP 超过 {@code pcre.backtrack_limit} 时同样返回 null），
 * 防止灾难性回溯占住线程。
 */
public final class CjPcre {

    private CjPcre() {
    }

    /** 匹配超过截止时间。 */
    static final class Timeout extends RuntimeException {
        Timeout() {
            super("正则匹配超时", null, false, false);
        }
    }

    /** 截止时间（{@link System#nanoTime()} 的值）。 */
    record Deadline(long nanos) {
        static Deadline in(java.time.Duration d) {
            return new Deadline(System.nanoTime() + d.toNanos());
        }

        void check() {
            if (System.nanoTime() - nanos > 0) {
                throw new Timeout();
            }
        }
    }

    private static final Map<String, String> POSIX = Map.ofEntries(
            Map.entry("alnum", "\\p{Alnum}"), Map.entry("alpha", "\\p{Alpha}"), Map.entry("ascii", "\\p{ASCII}"),
            Map.entry("blank", "\\p{Blank}"), Map.entry("cntrl", "\\p{Cntrl}"), Map.entry("digit", "\\p{Digit}"),
            Map.entry("graph", "\\p{Graph}"), Map.entry("lower", "\\p{Lower}"), Map.entry("print", "\\p{Print}"),
            Map.entry("punct", "\\p{Punct}"), Map.entry("space", "\\p{Space}"), Map.entry("upper", "\\p{Upper}"),
            Map.entry("word", "\\w"), Map.entry("xdigit", "\\p{XDigit}"));

    private static final class Unsupported extends RuntimeException {
        Unsupported() {
            super(null, null, false, false);
        }
    }

    /**
     * 编译 PHP 的完整模式（含分隔符与修饰符，ISO-8859-1 文字）。PHP 编译失败（没有结束分隔符、未知修饰符、语法错误、含 NUL）或用了
     * Java 不支持的构造时为 null。
     */
    public static Pattern compile(String php) {
        int p = 0;
        int n = php.length();
        while (p < n && isSpace(php.charAt(p))) {
            p++;
        }
        if (p >= n) {
            return null;
        }
        char delim = php.charAt(p);
        if (Character.isLetterOrDigit(delim) && delim < 0x80 || delim == '\\' || delim == 0) {
            return null;
        }
        int i = p + 1;
        while (i < n) {
            char c = php.charAt(i);
            if (c == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (c == delim) {
                break;
            }
            i++;
        }
        if (i >= n) {
            return null;
        }
        String body = php.substring(p + 1, i);
        int flags = Pattern.UNIX_LINES;
        for (int m = i + 1; m < n; m++) {
            switch (php.charAt(m)) {
                case 'i' -> flags |= Pattern.CASE_INSENSITIVE;
                case 'm' -> flags |= Pattern.MULTILINE;
                case 's' -> flags |= Pattern.DOTALL;
                case 'x' -> flags |= Pattern.COMMENTS;
                case 'S', 'X', ' ', '\n', '\r' -> {
                }
                default -> {
                    // 未知修饰符（PHP 编译失败）；A D U u J n 是 PHP 认得、但这里不支持的修饰符
                    return null;
                }
            }
        }
        if (body.indexOf('\0') >= 0) {
            return null;
        }
        try {
            return Pattern.compile(translate(body, (flags & Pattern.COMMENTS) != 0), flags);
        } catch (PatternSyntaxException | Unsupported e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ PCRE → Java

    static String translate(String s, boolean extended) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> i = escape(s, i, out, false);
                case '[' -> i = charClass(s, i, out, extended);
                case '(' -> i = group(s, i, out);
                case '{' -> {
                    int q = quantifierEnd(s, i);
                    if (q < 0) {
                        out.append("\\{");
                        i++;
                    } else {
                        out.append(s, i, q);
                        i = q;
                    }
                }
                default -> {
                    out.append(c);
                    i++;
                }
            }
        }
        return out.toString();
    }

    /** {@code {n}}、{@code {n,}}、{@code {n,m}} 的结束位置（不含）；不是量词时 -1（PCRE 把它当普通字符）。 */
    private static int quantifierEnd(String s, int i) {
        int j = i + 1;
        int d = j;
        while (j < s.length() && isDigit(s.charAt(j))) {
            j++;
        }
        if (j == d) {
            return -1;
        }
        if (j < s.length() && s.charAt(j) == '}') {
            return j + 1;
        }
        if (j >= s.length() || s.charAt(j) != ',') {
            return -1;
        }
        j++;
        while (j < s.length() && isDigit(s.charAt(j))) {
            j++;
        }
        return j < s.length() && s.charAt(j) == '}' ? j + 1 : -1;
    }

    private static int group(String s, int i, StringBuilder out) {
        if (!s.startsWith("(?", i) && !s.startsWith("(*", i)) {
            out.append('(');
            return i + 1;
        }
        if (s.startsWith("(*", i)) {
            // 回溯控制动词 (*FAIL) 等
            throw new Unsupported();
        }
        if (s.startsWith("(?#", i)) {
            int e = s.indexOf(')', i);
            if (e < 0) {
                throw new PatternSyntaxException("comment", s, i);
            }
            return e + 1;
        }
        if (s.startsWith("(?P<", i)) {
            int e = name(s, i + 4, '>');
            out.append("(?<").append(s, i + 4, e).append('>');
            return e + 1;
        }
        if (s.startsWith("(?P=", i)) {
            int e = name(s, i + 4, ')');
            out.append("\\k<").append(s, i + 4, e).append('>');
            return e + 1;
        }
        if (s.startsWith("(?'", i)) {
            int e = name(s, i + 3, '\'');
            out.append("(?<").append(s, i + 3, e).append('>');
            return e + 1;
        }
        if (s.startsWith("(?<", i) && i + 3 < s.length() && s.charAt(i + 3) != '=' && s.charAt(i + 3) != '!') {
            int e = name(s, i + 3, '>');
            out.append("(?<").append(s, i + 3, e).append('>');
            return e + 1;
        }
        if (i + 2 >= s.length()) {
            throw new PatternSyntaxException("group", s, i);
        }
        char k = s.charAt(i + 2);
        if (k == ':' || k == '=' || k == '!' || k == '>' || k == '<') {
            out.append(s, i, i + 3);
            return i + 3;
        }
        // 内联选项 (?imsx-imsx) 或 (?imsx-imsx:…)；其他 (?R)、(?1)、(?&名称)、(?(条件)…)、(?|…) 等不支持
        int j = i + 2;
        while (j < s.length() && "imsx-".indexOf(s.charAt(j)) >= 0) {
            j++;
        }
        if (j > i + 2 && j < s.length() && (s.charAt(j) == ')' || s.charAt(j) == ':')) {
            out.append(s, i, j + 1);
            return j + 1;
        }
        throw new Unsupported();
    }

    /** 分组名（Java 只认字母开头、字母与数字），返回结束符的位置。 */
    private static int name(String s, int from, char end) {
        int e = s.indexOf(end, from);
        if (e <= from) {
            throw new PatternSyntaxException("name", s, from);
        }
        if (!s.substring(from, e).matches("[A-Za-z][A-Za-z0-9]*")) {
            throw new Unsupported();
        }
        return e;
    }

    private static int charClass(String s, int i, StringBuilder out, boolean extended) {
        int n = s.length();
        out.append('[');
        int j = i + 1;
        if (j < n && s.charAt(j) == '^') {
            out.append('^');
            j++;
        }
        if (j < n && s.charAt(j) == ']') {
            out.append("\\]");
            j++;
        }
        while (j < n) {
            char c = s.charAt(j);
            if (c == ']') {
                out.append(']');
                return j + 1;
            }
            if (c == '\\') {
                j = escape(s, j, out, true);
                continue;
            }
            if (c == '[') {
                if (s.startsWith("[:", j)) {
                    int e = s.indexOf(":]", j + 2);
                    if (e > 0) {
                        String nm = s.substring(j + 2, e);
                        boolean neg = nm.startsWith("^");
                        String java = POSIX.get(neg ? nm.substring(1) : nm);
                        if (java == null) {
                            throw new PatternSyntaxException("posix", s, j);
                        }
                        if (neg) {
                            java = java.equals("\\w") ? "\\W" : "\\P" + java.substring(2);
                        }
                        out.append(java);
                        j = e + 2;
                        continue;
                    }
                }
                if (s.startsWith("[.", j) || s.startsWith("[=", j)) {
                    throw new PatternSyntaxException("collating", s, j);
                }
                out.append("\\[");
                j++;
                continue;
            }
            if (c == '&') {
                out.append("\\&");
            } else if (extended && (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0b || c == '#')) {
                // Java 的 COMMENTS 模式在字符类里也忽略空白与 #，PCRE 不忽略
                out.append(String.format(Locale.ROOT, "\\x%02x", (int) c));
            } else {
                out.append(c);
            }
            j++;
        }
        throw new PatternSyntaxException("missing terminating ] for character class", s, i);
    }

    /** 转义序列（{@code s.charAt(i) == '\\'}），写出等价的 Java 写法，返回下一个位置。 */
    private static int escape(String s, int i, StringBuilder out, boolean inClass) {
        int n = s.length();
        if (i + 1 >= n) {
            throw new PatternSyntaxException("\\ at end of pattern", s, i);
        }
        char e = s.charAt(i + 1);
        switch (e) {
            case 'Q' -> {
                int end = s.indexOf("\\E", i + 2);
                String lit = end < 0 ? s.substring(i + 2) : s.substring(i + 2, end);
                for (int k = 0; k < lit.length(); k++) {
                    hex(out, lit.charAt(k));
                }
                return end < 0 ? n : end + 2;
            }
            case 'E' -> {
                return i + 2;
            }
            case 'x' -> {
                int j = i + 2;
                if (j < n && s.charAt(j) == '{') {
                    int c = s.indexOf('}', j);
                    if (c < 0) {
                        throw new PatternSyntaxException("\\x{", s, i);
                    }
                    out.append(s, i, c + 1);
                    return c + 1;
                }
                int v = 0;
                int k = 0;
                while (k < 2 && j < n && Character.digit(s.charAt(j), 16) >= 0 && s.charAt(j) < 0x80) {
                    v = v * 16 + Character.digit(s.charAt(j), 16);
                    j++;
                    k++;
                }
                hex(out, (char) v);
                return j;
            }
            case 'o' -> {
                int c = s.indexOf('}', i);
                if (i + 2 >= n || s.charAt(i + 2) != '{' || c < 0) {
                    throw new PatternSyntaxException("\\o", s, i);
                }
                hex(out, (char) Integer.parseInt(s.substring(i + 3, c), 8));
                return c + 1;
            }
            case '0' -> {
                int j = i + 2;
                int v = 0;
                while (j < n && j < i + 4 && s.charAt(j) >= '0' && s.charAt(j) <= '7') {
                    v = v * 8 + s.charAt(j) - '0';
                    j++;
                }
                hex(out, (char) v);
                return j;
            }
            case 'c' -> {
                if (i + 2 >= n) {
                    throw new PatternSyntaxException("\\c", s, i);
                }
                out.append(s, i, i + 3);
                return i + 3;
            }
            case 'b' -> {
                out.append(inClass ? "\\x08" : "\\b");
                return i + 2;
            }
            case 'N' -> {
                if (inClass) {
                    throw new PatternSyntaxException("\\N in class", s, i);
                }
                out.append("[^\\n]");
                return i + 2;
            }
            case 'C' -> {
                if (inClass) {
                    throw new PatternSyntaxException("\\C in class", s, i);
                }
                out.append("[\\x00-\\x{ff}]");
                return i + 2;
            }
            case 'g' -> {
                // \g{n}、\gn、\g{名称}；相对引用 \g{-n} 不支持
                int j = i + 2;
                if (j < n && s.charAt(j) == '{') {
                    int c = s.indexOf('}', j);
                    if (c < 0) {
                        throw new PatternSyntaxException("\\g", s, i);
                    }
                    String ref = s.substring(j + 1, c);
                    if (ref.matches("[0-9]+")) {
                        out.append('\\').append(ref);
                    } else if (ref.matches("[A-Za-z][A-Za-z0-9]*")) {
                        out.append("\\k<").append(ref).append('>');
                    } else {
                        throw new Unsupported();
                    }
                    return c + 1;
                }
                int k = j;
                while (k < n && isDigit(s.charAt(k))) {
                    k++;
                }
                if (k == j) {
                    throw new Unsupported();
                }
                out.append('\\').append(s, j, k);
                return k;
            }
            case 'k' -> {
                int j = i + 2;
                if (j >= n) {
                    throw new PatternSyntaxException("\\k", s, i);
                }
                char open = s.charAt(j);
                char close = open == '<' ? '>' : open == '\'' ? '\'' : open == '{' ? '}' : 0;
                if (close == 0) {
                    throw new PatternSyntaxException("\\k", s, i);
                }
                int c = name(s, j + 1, close);
                out.append("\\k<").append(s, j + 1, c).append('>');
                return c + 1;
            }
            case 'K', 'u', 'U', 'L', 'l', 'i', 'j', 'm', 'q', 'y', 'F', 'I', 'J', 'M', 'O', 'T', 'Y' -> throw new Unsupported();
            default -> {
                if (isDigit(e)) {
                    if (inClass) {
                        // 字符类里是八进制
                        int j = i + 1;
                        int v = 0;
                        while (j < n && j < i + 4 && s.charAt(j) >= '0' && s.charAt(j) <= '7') {
                            v = v * 8 + s.charAt(j) - '0';
                            j++;
                        }
                        if (j == i + 1) {
                            throw new Unsupported();
                        }
                        hex(out, (char) v);
                        return j;
                    }
                    // PCRE：十进制数小于 10、以 8 或 9 开头、或不超过此前已打开的分组数时是反向引用，否则最多三位八进制
                    int j = i + 1;
                    while (j < n && isDigit(s.charAt(j))) {
                        j++;
                    }
                    String digits = s.substring(i + 1, j);
                    int dec = digits.length() > 6 ? Integer.MAX_VALUE : Integer.parseInt(digits);
                    if (dec < 10 || e == '8' || e == '9' || dec <= groupsBefore(s, i)) {
                        if (dec > groupsBefore(s, n)) {
                            // 引用不存在的分组：PCRE2 编译失败
                            throw new PatternSyntaxException("reference to non-existent subpattern", s, i);
                        }
                        // (?:) 隔开，免得 Java 把后面的数字也读进组号
                        out.append('\\').append(dec).append("(?:)");
                        return j;
                    }
                    int k = i + 1;
                    int v = 0;
                    while (k < n && k < i + 4 && s.charAt(k) >= '0' && s.charAt(k) <= '7') {
                        v = v * 8 + s.charAt(k) - '0';
                        k++;
                    }
                    hex(out, (char) (v & 0xff));
                    return k;
                }
                if (e >= 0x80 || !Character.isLetterOrDigit(e)) {
                    // 非字母数字的转义就是字符本身
                    hex(out, e);
                    return i + 2;
                }
                // d D s S w W h H v V R A z Z G a e f n r t p P X 等，两边写法相同
                out.append('\\').append(e);
                return i + 2;
            }
        }
    }

    /** {@code s} 的前 {@code end} 个字符里打开的捕获组数（不含 {@code (?:} 等非捕获组；转义与字符类里的括号不算）。 */
    static int groupsBefore(String s, int end) {
        int count = 0;
        boolean inClass = false;
        for (int i = 0; i < end; i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                if (s.startsWith("\\Q", i)) {
                    int e = s.indexOf("\\E", i + 2);
                    i = e < 0 ? end : e + 1;
                } else {
                    i++;
                }
            } else if (inClass) {
                if (c == ']') {
                    inClass = false;
                }
            } else if (c == '[') {
                inClass = true;
                if (s.startsWith("[^]", i)) {
                    i += 2;
                } else if (s.startsWith("[]", i)) {
                    i += 1;
                }
            } else if (c == '(') {
                if (i + 1 >= s.length() || s.charAt(i + 1) != '?' && s.charAt(i + 1) != '*') {
                    count++;
                } else if (s.startsWith("(?P<", i) || s.startsWith("(?'", i)
                        || s.startsWith("(?<", i) && i + 3 < s.length() && s.charAt(i + 3) != '=' && s.charAt(i + 3) != '!') {
                    count++;
                }
            }
        }
        return count;
    }

    private static void hex(StringBuilder out, char c) {
        out.append(String.format(Locale.ROOT, "\\x{%x}", (int) c));
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0b || c == '\f';
    }

    // ------------------------------------------------------------------ preg_replace

    /**
     * PHP {@code preg_replace($patterns, $replacements, $subject)}（数组形式，逐个模式依次作用在上一步的结果上）：任一模式为 null（编译失败）、
     * 匹配超时时整体为 null。{@code replacements} 的 null 项是空串。
     */
    static String replaceAll(List<Pattern> patterns, List<String> replacements, String subject, Deadline deadline) {
        String cur = subject;
        for (Pattern p : patterns) {
            if (p == null) {
                return null;
            }
        }
        try {
            for (int k = 0; k < patterns.size(); k++) {
                String r = replacements.get(k);
                cur = replace(patterns.get(k), r == null ? "" : r, cur, deadline);
            }
        } catch (Timeout | StackOverflowError e) {
            return null;
        }
        return cur;
    }

    private static String replace(Pattern p, String replacement, String subject, Deadline deadline) {
        Matcher m = p.matcher(new Bounded(subject, deadline));
        m.useTransparentBounds(true).useAnchoringBounds(false);
        StringBuilder out = new StringBuilder(subject.length());
        int n = subject.length();
        int from = 0;
        int copied = 0;
        while (from <= n) {
            m.region(from, n);
            if (!m.find()) {
                break;
            }
            out.append(subject, copied, m.start());
            expand(m, replacement, out);
            copied = m.end();
            if (m.end() == m.start()) {
                if (m.end() >= n) {
                    break;
                }
                out.append(subject.charAt(m.end()));
                copied = m.end() + 1;
                from = m.end() + 1;
            } else {
                from = m.end();
            }
        }
        out.append(subject, Math.min(copied, n), n);
        return out.toString();
    }

    /** {@code php_pcre_replace_impl} 的替换串展开（{@code preg_get_backref} 与 {@code walk_last} 的规则）。 */
    static void expand(Matcher m, String r, StringBuilder out) {
        int n = r.length();
        int i = 0;
        char last = 0;
        while (i < n) {
            char c = r.charAt(i);
            if (c == '\\' || c == '$') {
                if (last == '\\') {
                    out.setCharAt(out.length() - 1, c);
                    i++;
                    last = 0;
                    continue;
                }
                int[] ref = backref(r, i);
                if (ref != null) {
                    int g = ref[0];
                    if (g <= m.groupCount() && m.group(g) != null) {
                        out.append(m.group(g));
                    }
                    i = ref[1];
                    continue;
                }
            }
            out.append(c);
            last = c;
            i++;
        }
    }

    /** {@code preg_get_backref}：返回 {分组号, 下一个位置}；不是分组引用时 null。 */
    private static int[] backref(String r, int i) {
        int n = r.length();
        if (i + 1 >= n) {
            return null;
        }
        int w = i;
        boolean brace = false;
        if (r.charAt(w) == '$' && r.charAt(w + 1) == '{') {
            brace = true;
            w++;
        }
        w++;
        if (w >= n || !isDigit(r.charAt(w))) {
            return null;
        }
        int g = r.charAt(w) - '0';
        w++;
        if (w < n && isDigit(r.charAt(w))) {
            g = g * 10 + r.charAt(w) - '0';
            w++;
        }
        if (brace) {
            if (w >= n || r.charAt(w) != '}') {
                return null;
            }
            w++;
        }
        return new int[] {g, w};
    }

    /** 读字符时检查截止时间的字符序列（正则引擎回溯时会反复读字符）。 */
    private static final class Bounded implements CharSequence {
        private final String s;
        private final Deadline deadline;
        private int reads;

        Bounded(String s, Deadline deadline) {
            this.s = s;
            this.deadline = deadline;
        }

        @Override
        public int length() {
            return s.length();
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0xfff) == 0) {
                deadline.check();
            }
            return s.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return s.substring(start, end);
        }

        @Override
        public String toString() {
            return s;
        }
    }
}
