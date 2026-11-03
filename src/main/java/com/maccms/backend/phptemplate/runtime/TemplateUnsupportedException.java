package com.maccms.backend.phptemplate.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 编译产物超出 PHP 子集（RT-1）时抛出（技术设计 v15 C-23 的 runtime 部分）。
 *
 * <p>只带三样东西：种类 {@link Kind}、编译产物里的原始字节偏移、该偏移起的原文片段（到行尾为止，
 * 最多 {@value #SNIPPET_MAX_BYTES} 字节，不截断 UTF-8 字符）。不带变量取值、不读配置或宿主信息；
 * 模板文件与行号由 {@code PhpTemplateRenderer} 按 TPL-9 用 {@code OriginMap} 补（C-24，不在 runtime）。</p>
 */
public final class TemplateUnsupportedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 原文片段的最大字节数。 */
    public static final int SNIPPET_MAX_BYTES = 80;

    /** 不支持的种类；{@link #code()} 是写进日志的稳定代码。 */
    public enum Kind {
        /** 字面文本里出现 {@code <?php}+空白 以外的 {@code <?} 形态（短标签、{@code <?=} 等）。 */
        OPEN_TAG("open_tag"),
        /** RT-1 以外的词法单位：块注释、{@code #} 注释、反引号、heredoc、非十进制整数、非法字节等。 */
        TOKEN("token"),
        /** 字符串到编译产物末尾仍未结束。 */
        UNTERMINATED("unterminated"),
        /** 双引号串里 {@code $var} 以外的插值写法。 */
        INTERPOLATION("interpolation"),
        /** BARE-5：登记名单以外的裸标识符（解析阶段判定，S3-07b）。 */
        BARE_IDENTIFIER("bare_identifier"),
        /** RT-1 以外的语法（类定义、include、eval、可变函数名、{@code $$}、类型转换、未列运算符等，S3-07b）。 */
        SYNTAX("syntax"),
        /** RT-7：函数名不在解析时注入的白名单内，或带命名空间（S3-07b）。 */
        FUNCTION("function"),
        /** MC-1：{@code model(...)} 不是“字符串字面量 + 一次 {@code ->方法(实参)}”的形态（S3-07b）。 */
        MODEL_CALL("model_call"),
        /** OP-T7：对象写法——登记类名以外的 {@code instanceof}、{@code ->} 属性/方法、非固定形态（S3-07b）。 */
        OBJECT("object"),
        /** RT-8：循环总次数、输出字节数或语句嵌套深度超过 {@link ExecutionLimits}（执行期，S3-07c）。 */
        LIMIT("limit"),
        /**
         * 执行期遇到 PHP 7.3 本身会以致命错误终止的操作（数组参与算术、{@code %} 除数为 0、字符串下标写入等），
         * 解释器不猜结果，按不支持拒绝（S3-07c）。
         */
        RUNTIME("runtime");

        private final String code;

        Kind(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final Kind kind;
    private final int offset;
    private final String snippet;

    /**
     * @param source 编译产物原始字节（只用来截取片段，不保留引用）
     * @param offset 被拒内容首字节的偏移，{@code 0 <= offset <= source.length}
     */
    public TemplateUnsupportedException(Kind kind, byte[] source, int offset) {
        this(kind, offset, snippetOf(source, offset));
    }

    private TemplateUnsupportedException(Kind kind, int offset, String snippet) {
        super(Objects.requireNonNull(kind, "kind").code() + " at offset " + offset + ": " + snippet);
        this.kind = kind;
        this.offset = offset;
        this.snippet = snippet;
    }

    public Kind kind() {
        return kind;
    }

    /** 编译产物里的原始字节偏移。 */
    public int offset() {
        return offset;
    }

    /** 从偏移起的原文片段（UTF-8 解码，到 CR/LF 前为止）。 */
    public String snippet() {
        return snippet;
    }

    private static String snippetOf(byte[] source, int offset) {
        Objects.requireNonNull(source, "source");
        if (offset < 0 || offset > source.length) {
            throw new IllegalArgumentException("offset out of range: " + offset);
        }
        int limit = Math.min(source.length, offset + SNIPPET_MAX_BYTES);
        int end = offset;
        while (end < limit && source[end] != '\n' && source[end] != '\r') {
            end++;
        }
        if (end < source.length && end == limit) {
            // 截断处若落在多字节字符中间，退回到该字符起点
            while (end > offset && (source[end] & 0xC0) == 0x80) {
                end--;
            }
        }
        return new String(source, offset, end - offset, StandardCharsets.UTF_8);
    }
}
