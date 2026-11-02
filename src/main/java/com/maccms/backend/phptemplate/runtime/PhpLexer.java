package com.maccms.backend.phptemplate.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 编译产物的词法切分（技术设计 v15 C-18；§6.4 TPL-5；§6.5 RT-1）。
 *
 * <p>输入是编译产物的原始字节，输出是首尾相接、覆盖全部字节的段：字面段（原样输出，不做换行归一化）与
 * 代码段（{@code <?php} 起、{@code ?>} 及按 TPL-5 吃掉的一个换行止，或到产物末尾）。代码段内切成记号，
 * 所有偏移都是原始字节偏移。规则逐条对照 PHP 7.3.33 {@code token_get_all}：</p>
 * <ul>
 *   <li>开标签：{@code <?php}（不分大小写）后接 1 个空格、TAB、LF、CR 或整个 CRLF，空白属于开标签。字面段里其他
 *       {@code <?} 形态（{@code <?=}、短标签、末尾无空白的 {@code <?php}）不在 RT-1 内，报 {@link TemplateUnsupportedException.Kind#OPEN_TAG}——
 *       编译器已把短标签改写为 {@code echo}，残留的写法其 PHP 行为又取决于 {@code short_open_tag}，不静默当字面输出。</li>
 *   <li>TPL-5：{@code ?>} 后紧跟的一个 LF 或整个 CRLF 属于结束标记，不输出（{@link Close}）。</li>
 *   <li>{@code //} 注释到 LF、CR 或 {@code ?>} 为止，不吞 {@code ?>}；块注释与 {@code #} 注释不在 RT-1 内。</li>
 *   <li>字符串整体切分：其中的 {@code ?>}、{@code //}、{@code <?} 都只是字节。</li>
 *   <li>名字 {@code [A-Za-z_\x80-\xff][A-Za-z0-9_\x80-\xff]*}；带 {@code \} 的限定名 / 完整类名作为一个记号（从前导
 *       {@code \} 起算）；保留字（不分大小写）是 {@link Token.Keyword}，但 {@code ->} 之后一律是名字。
 *       {@code true}/{@code false}/{@code null} 与其他名字一样是 {@link Token.Name}：裸名、函数名的判定在解析器（S3-07b）。</li>
 * </ul>
 * <p>只依赖 JDK（§6.3），不执行任何代码。</p>
 */
public final class PhpLexer {

    /** PHP 7.3 保留字（不含 {@code true}/{@code false}/{@code null}，它们是常量名）。 */
    private static final Set<String> KEYWORDS = Set.of(
            "__halt_compiler", "abstract", "and", "array", "as", "break", "callable", "case", "catch", "class",
            "clone", "const", "continue", "declare", "default", "die", "do", "echo", "else", "elseif", "empty",
            "enddeclare", "endfor", "endforeach", "endif", "endswitch", "endwhile", "eval", "exit", "extends",
            "final", "finally", "for", "foreach", "function", "global", "goto", "if", "implements", "include",
            "include_once", "instanceof", "insteadof", "interface", "isset", "list", "namespace", "new", "or",
            "print", "private", "protected", "public", "require", "require_once", "return", "static", "switch",
            "throw", "trait", "try", "unset", "use", "var", "while", "xor", "yield");

    /** 类型转换 {@code (type)}（PHP 7.3 的 T_*_CAST，括号内可有空格/TAB）。 */
    private static final Set<String> CAST_TYPES = Set.of(
            "int", "integer", "bool", "boolean", "float", "double", "real", "string", "binary", "array", "object", "unset");

    /** 运算符与标点，按长度从长到短尝试（最长匹配）。 */
    private static final String[] OPERATORS = {
        "<=>", "===", "!==", "**=", "...", "<<=", ">>=",
        "==", "!=", "<>", "<=", ">=", "&&", "||", "??", "++", "--", "+=", "-=", "*=", "/=", ".=", "%=",
        "&=", "|=", "^=", "<<", ">>", "->", "=>", "::", "**",
        "+", "-", "*", "/", "%", ".", "=", "<", ">", "!", "?", ":", ";", ",", "(", ")", "[", "]", "{", "}",
        "&", "|", "^", "~", "@", "$",
    };

    private final byte[] src;
    private final int len;
    private int pos;

    private PhpLexer(byte[] src) {
        this.src = src;
        this.len = src.length;
    }

    /**
     * 切分编译产物。
     *
     * @throws TemplateUnsupportedException 遇到 RT-1 以外的词法写法或截断的字符串
     */
    public static Result lex(byte[] source) {
        byte[] copy = source.clone();
        return new Result(copy, new PhpLexer(copy).run());
    }

    private List<Segment> run() {
        List<Segment> out = new ArrayList<>();
        while (pos < len) {
            int litStart = pos;
            int open = findOpenTag(pos);
            if (open < 0) {
                out.add(new Segment.Literal(litStart, len));
                pos = len;
                break;
            }
            if (open > litStart) {
                out.add(new Segment.Literal(litStart, open));
            }
            out.add(code(open));
        }
        return List.copyOf(out);
    }

    /** 从 from 起找下一个合法开标签的起点；遇到其他 {@code <?} 形态即拒绝。 */
    private int findOpenTag(int from) {
        for (int i = from; i + 1 < len; i++) {
            if (src[i] == '<' && src[i + 1] == '?') {
                if (openTagLength(i) > 0) {
                    return i;
                }
                throw new TemplateUnsupportedException(TemplateUnsupportedException.Kind.OPEN_TAG, src, i);
            }
        }
        return -1;
    }

    /** {@code <?php} + 1 个空白（CRLF 整体）的长度；不是合法开标签返回 0。 */
    private int openTagLength(int i) {
        if (i + 5 >= len) {
            return 0;
        }
        String word = new String(src, i + 2, 3, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        if (!word.equals("php")) {
            return 0;
        }
        byte ws = src[i + 5];
        if (ws == ' ' || ws == '\t' || ws == '\n') {
            return 6;
        }
        if (ws == '\r') {
            return i + 6 < len && src[i + 6] == '\n' ? 7 : 6;
        }
        return 0;
    }

    private Segment.Code code(int start) {
        int codeStart = start + openTagLength(start);
        pos = codeStart;
        List<Token> tokens = new ArrayList<>();
        Token prev = null;
        while (true) {
            skipWhitespaceAndComments();
            if (pos >= len) {
                return new Segment.Code(start, codeStart, len, len, Close.EOF, List.copyOf(tokens));
            }
            if (src[pos] == '?' && pos + 1 < len && src[pos + 1] == '>') {
                int codeEnd = pos;
                int after = pos + 2;
                Close close = Close.TAG;
                if (after < len && src[after] == '\n') {
                    close = Close.TAG_LF;
                } else if (after + 1 < len && src[after] == '\r' && src[after + 1] == '\n') {
                    close = Close.TAG_CRLF;
                }
                pos = after + close.eatenNewlineLength();
                return new Segment.Code(start, codeStart, codeEnd, pos, close, List.copyOf(tokens));
            }
            Token t = token(prev);
            tokens.add(t);
            prev = t;
        }
    }

    private void skipWhitespaceAndComments() {
        while (pos < len) {
            byte c = src[pos];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else if (c == '/' && pos + 1 < len && src[pos + 1] == '/') {
                pos += 2;
                while (pos < len && src[pos] != '\n' && src[pos] != '\r'
                        && !(src[pos] == '?' && pos + 1 < len && src[pos + 1] == '>')) {
                    pos++;
                }
            } else if (c == '/' && pos + 1 < len && src[pos + 1] == '*' || c == '#') {
                throw new TemplateUnsupportedException(TemplateUnsupportedException.Kind.TOKEN, src, pos);
            } else {
                return;
            }
        }
    }

    private Token token(Token prev) {
        int start = pos;
        byte c = src[pos];
        if (c == '$' && pos + 1 < len && isLabelStart(src[pos + 1])) {
            pos++;
            String name = label();
            return new Token.Variable(start, pos, name);
        }
        if (isLabelStart(c) || c == '\\') {
            return name(start, prev instanceof Token.Op op && op.symbol().equals("->"));
        }
        if (isDigit(c) || c == '.' && pos + 1 < len && isDigit(src[pos + 1])) {
            return number(start);
        }
        if (c == '\'') {
            return singleQuoted(start);
        }
        if (c == '"') {
            return doubleQuoted(start);
        }
        if (c == '(') {
            Token cast = cast(start);
            if (cast != null) {
                return cast;
            }
        }
        if (c == '<' && startsWith(pos, "<<<")) {
            throw new TemplateUnsupportedException(TemplateUnsupportedException.Kind.TOKEN, src, start);
        }
        for (String op : OPERATORS) {
            if (startsWith(pos, op)) {
                pos += op.length();
                return new Token.Op(start, pos, op);
            }
        }
        throw new TemplateUnsupportedException(TemplateUnsupportedException.Kind.TOKEN, src, start);
    }

    private Token name(int start, boolean afterObjectOperator) {
        boolean leading = src[pos] == '\\';
        int segments = 0;
        if (leading) {
            pos++;
        }
        StringBuilder sb = new StringBuilder();
        if (leading) {
            sb.append('\\');
        }
        while (true) {
            if (pos >= len || !isLabelStart(src[pos])) {
                // 前导或中间的 \ 后面没有名字
                throw new TemplateUnsupportedException(TemplateUnsupportedException.Kind.TOKEN, src, pos - 1);
            }
            sb.append(label());
            segments++;
            if (pos < len && src[pos] == '\\') {
                sb.append('\\');
                pos++;
                continue;
            }
            break;
        }
        String text = sb.toString();
        if (leading) {
            return new Token.Name(start, pos, text, NameKind.FULLY_QUALIFIED);
        }
        if (segments > 1) {
            return new Token.Name(start, pos, text, NameKind.QUALIFIED);
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (!afterObjectOperator && KEYWORDS.contains(lower)) {
            return new Token.Keyword(start, pos, lower);
        }
        return new Token.Name(start, pos, text, NameKind.UNQUALIFIED);
    }

    /** 读一个名字（调用前已确认首字节合法）；非 ASCII 字节按 UTF-8 解码进名字文本。 */
    private String label() {
        int s = pos;
        while (pos < len && isLabelPart(src[pos])) {
            pos++;
        }
        return new String(src, s, pos - s, StandardCharsets.UTF_8);
    }

    private Token number(int start) {
        int p = pos;
        while (p < len && isDigit(src[p])) {
            p++;
        }
        int intDigits = p - pos;
        boolean isFloat = false;
        if (intDigits == 1 && src[pos] == '0' && p < len
                && (src[p] == 'x' || src[p] == 'X' || src[p] == 'b' || src[p] == 'B')) {
            // 十六进制 / 二进制整数不在 RT-1 内
            throw new TemplateUnsupportedException(TemplateUnsupportedException.Kind.TOKEN, src, start);
        }
        if (p < len && src[p] == '.' && !(p + 1 < len && src[p + 1] == '.' && p + 2 < len && src[p + 2] == '.')) {
            isFloat = true;
            p++;
            while (p < len && isDigit(src[p])) {
                p++;
            }
        }
        if (p < len && (src[p] == 'e' || src[p] == 'E')) {
            int q = p + 1;
            if (q < len && (src[q] == '+' || src[q] == '-')) {
                q++;
            }
            if (q < len && isDigit(src[q])) {
                isFloat = true;
                p = q;
                while (p < len && isDigit(src[p])) {
                    p++;
                }
            }
        }
        if (!isFloat && intDigits > 1 && src[pos] == '0') {
            // 八进制整数（含 00、07、012）不在 RT-1 内；带小数点或指数的前导零串（00.5、01.5、00e1）
            // 在 PHP 7.3 是十进制 T_DNUMBER，上面已识别为 float，不走这里
            throw new TemplateUnsupportedException(TemplateUnsupportedException.Kind.TOKEN, src, start);
        }
        String text = new String(src, pos, p - pos, StandardCharsets.ISO_8859_1);
        pos = p;
        if (!isFloat) {
            try {
                return new Token.IntLit(start, pos, Long.parseLong(text));
            } catch (NumberFormatException overflow) {
                // PHP：超出 64 位的十进制整数字面量按 float
                return new Token.FloatLit(start, pos, Double.parseDouble(text));
            }
        }
        return new Token.FloatLit(start, pos, Double.parseDouble(text));
    }

    private Token singleQuoted(int start) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int p = pos + 1;
        while (p < len) {
            byte c = src[p];
            if (c == '\'') {
                pos = p + 1;
                return new Token.Str(start, pos, out.toByteArray(), false);
            }
            if (c == '\\' && p + 1 < len && (src[p + 1] == '\'' || src[p + 1] == '\\')) {
                out.write(src[p + 1]);
                p += 2;
                continue;
            }
            out.write(c);
            p++;
        }
        throw new TemplateUnsupportedException(TemplateUnsupportedException.Kind.UNTERMINATED, src, start);
    }

    private Token doubleQuoted(int start) {
        List<Part> parts = new ArrayList<>();
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        int p = pos + 1;
        while (p < len) {
            byte c = src[p];
            if (c == '"') {
                pos = p + 1;
                if (parts.isEmpty()) {
                    return new Token.Str(start, pos, text.toByteArray(), true);
                }
                if (text.size() > 0) {
                    parts.add(new Part.Text(text.toByteArray()));
                }
                return new Token.Interpolated(start, pos, List.copyOf(parts));
            }
            if (c == '\\' && p + 1 < len) {
                p = escape(p, text);
                continue;
            }
            if (c == '$' && p + 1 < len && (isLabelStart(src[p + 1]) || src[p + 1] == '{')) {
                if (src[p + 1] == '{') {
                    throw new TemplateUnsupportedException(TemplateUnsupportedException.Kind.INTERPOLATION, src, p);
                }
                int varStart = p;
                int q = p + 1;
                while (q < len && isLabelPart(src[q])) {
                    q++;
                }
                if (q < len && src[q] == '['
                        || q + 2 < len && src[q] == '-' && src[q + 1] == '>' && isLabelStart(src[q + 2])) {
                    // $a[...] / $a->b 的简单插值语法不在 RT-1 内
                    throw new TemplateUnsupportedException(TemplateUnsupportedException.Kind.INTERPOLATION, src, varStart);
                }
                if (text.size() > 0) {
                    parts.add(new Part.Text(text.toByteArray()));
                    text.reset();
                }
                parts.add(new Part.Var(varStart, new String(src, p + 1, q - p - 1, StandardCharsets.UTF_8)));
                p = q;
                continue;
            }
            if (c == '{' && p + 1 < len && src[p + 1] == '$') {
                throw new TemplateUnsupportedException(TemplateUnsupportedException.Kind.INTERPOLATION, src, p);
            }
            text.write(c);
            p++;
        }
        throw new TemplateUnsupportedException(TemplateUnsupportedException.Kind.UNTERMINATED, src, start);
    }

    /** 双引号串的转义（PHP 7.3）；p 指向反斜杠，返回处理后的下一个位置。未知转义原样保留反斜杠。 */
    private int escape(int p, ByteArrayOutputStream out) {
        byte n = src[p + 1];
        switch (n) {
            case 'n' -> out.write('\n');
            case 't' -> out.write('\t');
            case 'r' -> out.write('\r');
            case 'v' -> out.write(0x0b);
            case 'e' -> out.write(0x1b);
            case 'f' -> out.write('\f');
            case '\\' -> out.write('\\');
            case '$' -> out.write('$');
            case '"' -> out.write('"');
            default -> {
                if (n >= '0' && n <= '7') {
                    int q = p + 1;
                    int v = 0;
                    while (q < len && q < p + 4 && src[q] >= '0' && src[q] <= '7') {
                        v = v * 8 + (src[q] - '0');
                        q++;
                    }
                    out.write(v & 0xff);
                    return q;
                }
                if (n == 'x' && p + 2 < len && isHex(src[p + 2])) {
                    int q = p + 2;
                    int v = 0;
                    while (q < len && q < p + 4 && isHex(src[q])) {
                        v = v * 16 + Character.digit(src[q], 16);
                        q++;
                    }
                    out.write(v);
                    return q;
                }
                if (n == 'u' && p + 2 < len && src[p + 2] == '{') {
                    int q = p + 3;
                    int cp = 0;
                    while (q < len && isHex(src[q])) {
                        cp = cp * 16 + Character.digit(src[q], 16);
                        if (cp > 0x10FFFF) {
                            throw new TemplateUnsupportedException(TemplateUnsupportedException.Kind.TOKEN, src, p);
                        }
                        q++;
                    }
                    if (q == p + 3 || q >= len || src[q] != '}') {
                        // PHP 7.3：反斜杠 u{ 后缺十六进制数字或右括号是编译错误
                        throw new TemplateUnsupportedException(TemplateUnsupportedException.Kind.TOKEN, src, p);
                    }
                    out.writeBytes(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8));
                    return q + 1;
                }
                out.write('\\');
                out.write(n);
            }
        }
        return p + 2;
    }

    /** {@code (} 空白* 类型 空白* {@code )}；不是转换返回 null（位置不变）。 */
    private Token cast(int start) {
        int p = pos + 1;
        while (p < len && (src[p] == ' ' || src[p] == '\t')) {
            p++;
        }
        int ws = p;
        while (p < len && isAsciiLetter(src[p])) {
            p++;
        }
        String type = new String(src, ws, p - ws, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        while (p < len && (src[p] == ' ' || src[p] == '\t')) {
            p++;
        }
        if (p < len && src[p] == ')' && CAST_TYPES.contains(type)) {
            pos = p + 1;
            return new Token.Cast(start, pos, type);
        }
        return null;
    }

    private boolean startsWith(int at, String s) {
        if (at + s.length() > len) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (src[at + i] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLabelStart(byte c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_' || (c & 0x80) != 0;
    }

    private static boolean isLabelPart(byte c) {
        return isLabelStart(c) || isDigit(c);
    }

    private static boolean isDigit(byte c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHex(byte c) {
        return isDigit(c) || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
    }

    private static boolean isAsciiLetter(byte c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    // ---- 结果类型 ----

    /** 代码段的结束方式（TPL-5）。 */
    public enum Close {
        /** 到编译产物末尾都没有 {@code ?>}。 */
        EOF(0),
        /** {@code ?>} 后没有紧跟换行，什么也不吃。 */
        TAG(0),
        /** {@code ?>} 后紧跟的一个 LF 被吃掉、不输出。 */
        TAG_LF(1),
        /** {@code ?>} 后紧跟的整个 CRLF 被吃掉、不输出。 */
        TAG_CRLF(2);

        private final int eaten;

        Close(int eaten) {
            this.eaten = eaten;
        }

        /** 结束标记之后被吃掉的换行字节数。 */
        public int eatenNewlineLength() {
            return eaten;
        }
    }

    /** 名字记号的限定方式。 */
    public enum NameKind {
        /** {@code color}、{@code mac_day}、{@code true}。 */
        UNQUALIFIED,
        /** {@code a\color}。 */
        QUALIFIED,
        /** {@code \think\Collection}、{@code \color}。 */
        FULLY_QUALIFIED
    }

    /** 切分结果：原始字节（副本）与首尾相接的段。 */
    public record Result(byte[] source, List<Segment> segments) {
        public Result {
            source = source.clone();
            segments = List.copyOf(segments);
        }

        @Override
        public byte[] source() {
            return source.clone();
        }

        /** 字面段的原始字节。 */
        public byte[] bytes(Segment.Literal literal) {
            return Arrays.copyOfRange(source, literal.start(), literal.end());
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Result r && Arrays.equals(r.source, source) && r.segments.equals(segments);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(source) + segments.hashCode();
        }

        @Override
        public String toString() {
            return "Result[length=" + source.length + ", segments=" + segments.size() + "]";
        }
    }

    /** 编译产物的一段，[start, end) 为原始字节范围。 */
    public sealed interface Segment {
        int start();

        int end();

        /** 原样输出的字面文本。 */
        record Literal(int start, int end) implements Segment {
        }

        /**
         * 代码段：[start, codeStart) 是开标签（含其后 1 个空白），[codeStart, codeEnd) 是代码，
         * [codeEnd, end) 是 {@code ?>} 与按 TPL-5 吃掉的换行（{@link Close#EOF} 时为空）。
         */
        record Code(int start, int codeStart, int codeEnd, int end, Close close, List<Token> tokens) implements Segment {
            public Code {
                tokens = List.copyOf(tokens);
            }
        }
    }

    /** 双引号串插值的组成部分。 */
    public sealed interface Part {
        /** 已解码转义的文本。 */
        record Text(byte[] bytes) implements Part {
            public Text {
                bytes = bytes.clone();
            }

            @Override
            public byte[] bytes() {
                return bytes.clone();
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof Text t && Arrays.equals(t.bytes, bytes);
            }

            @Override
            public int hashCode() {
                return Arrays.hashCode(bytes);
            }

            @Override
            public String toString() {
                return "Text[length=" + bytes.length + "]";
            }
        }

        /** {@code $name}；offset 是 {@code $} 的原始字节偏移。 */
        record Var(int offset, String name) implements Part {
        }
    }

    /** 代码段内的记号；[start, end) 为原始字节范围。 */
    public sealed interface Token {
        int start();

        int end();

        /** 运算符或标点（含单独的 {@code $}，由解析器拒绝 {@code $$}）。 */
        record Op(int start, int end, String symbol) implements Token {
        }

        /** {@code $name}，name 不含 {@code $}。 */
        record Variable(int start, int end, String name) implements Token {
        }

        /** 名字：裸名、函数名、常量名、完整类名都是它，由解析器按上下文区分。 */
        record Name(int start, int end, String name, NameKind kind) implements Token {
        }

        /** 保留字，word 为小写。 */
        record Keyword(int start, int end, String word) implements Token {
        }

        /** 类型转换，type 为小写。 */
        record Cast(int start, int end, String type) implements Token {
        }

        /** 十进制整数。 */
        record IntLit(int start, int end, long value) implements Token {
        }

        /** 浮点数（含超出 64 位的十进制整数）。 */
        record FloatLit(int start, int end, double value) implements Token {
        }

        /** 无插值的字符串，bytes 已解码转义。 */
        record Str(int start, int end, byte[] bytes, boolean doubleQuoted) implements Token {
            public Str {
                bytes = bytes.clone();
            }

            @Override
            public byte[] bytes() {
                return bytes.clone();
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof Str s && s.start == start && s.end == end && s.doubleQuoted == doubleQuoted
                        && Arrays.equals(s.bytes, bytes);
            }

            @Override
            public int hashCode() {
                return 31 * (31 * start + end) + Arrays.hashCode(bytes);
            }

            @Override
            public String toString() {
                return "Str[" + start + ", " + end + ", length=" + bytes.length + "]";
            }
        }

        /** 含 {@code $var} 插值的双引号串。 */
        record Interpolated(int start, int end, List<Part> parts) implements Token {
            public Interpolated {
                parts = List.copyOf(parts);
            }
        }
    }
}
