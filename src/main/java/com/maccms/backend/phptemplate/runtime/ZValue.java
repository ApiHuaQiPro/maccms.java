package com.maccms.backend.phptemplate.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 模板解释器里的 PHP 7.3 值（技术设计 §6.5 RT-2）：{@code null}、{@code bool}、{@code int}（64 位）、
 * {@code float}（双精度）、{@code string}（字节串）与有序数组 {@link ZArray}。
 *
 * <p>标量不可变；字符串按字节存，所有字符串语义（长度、下标、比较）都按字节，与 PHP 一致。
 * 只依赖 JDK（§6.3）。字符串化、比较等语义在 {@link PhpSemantics}。</p>
 */
public sealed interface ZValue permits ZValue.Null, ZValue.Bool, ZValue.Int, ZValue.Float, ZValue.Str, ZArray {

    Null NULL = Null.INSTANCE;
    Bool TRUE = new Bool(true);
    Bool FALSE = new Bool(false);
    Str EMPTY_STRING = new Str(new byte[0]);

    /** PHP {@code gettype()} 的结果（{@code NULL}、{@code boolean}、{@code integer}、{@code double}、{@code string}、{@code array}）。 */
    String typeName();

    static Bool of(boolean b) {
        return b ? TRUE : FALSE;
    }

    static Int of(long v) {
        return new Int(v);
    }

    static Float of(double v) {
        return new Float(v);
    }

    /** Java 字符串按 UTF-8 编码成字节串。 */
    static Str of(String s) {
        return s.isEmpty() ? EMPTY_STRING : new Str(s.getBytes(StandardCharsets.UTF_8));
    }

    /** 原样字节串（复制入参）。 */
    static Str bytes(byte[] b) {
        return b.length == 0 ? EMPTY_STRING : new Str(b.clone());
    }

    /** PHP {@code null}。 */
    final class Null implements ZValue {
        static final Null INSTANCE = new Null();

        private Null() {
        }

        @Override
        public String typeName() {
            return "NULL";
        }

        @Override
        public String toString() {
            return "ZValue.Null";
        }
    }

    /** PHP {@code bool}。 */
    record Bool(boolean value) implements ZValue {
        @Override
        public String typeName() {
            return "boolean";
        }
    }

    /** PHP {@code int}（64 位，同 {@code PHP_INT_SIZE=8}）。 */
    record Int(long value) implements ZValue {
        @Override
        public String typeName() {
            return "integer";
        }
    }

    /** PHP {@code float}（IEEE 754 双精度）。相等按 {@link Double#compare}，只供 Java 集合使用；PHP 语义见 {@link PhpSemantics}。 */
    record Float(double value) implements ZValue {
        @Override
        public String typeName() {
            return "double";
        }
    }

    /** PHP {@code string}：不可变字节串。 */
    final class Str implements ZValue {
        private final byte[] bytes;

        private Str(byte[] bytes) {
            this.bytes = bytes;
        }

        /** 字节副本。 */
        public byte[] bytes() {
            return bytes.clone();
        }

        /** 字节数（PHP {@code strlen}）。 */
        public int length() {
            return bytes.length;
        }

        /** 第 {@code i} 个字节（0 ≤ i &lt; length），无符号 0～255。 */
        public int byteAt(int i) {
            return bytes[i] & 0xff;
        }

        /** 按 UTF-8 解码（非法序列替换为 U+FFFD），只用于输出到 Java 侧，不参与 PHP 语义。 */
        public String utf8() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        byte[] raw() {
            return bytes;
        }

        @Override
        public String typeName() {
            return "string";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Str other && Arrays.equals(bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            // 不输出内容：模板变量里可能有用户数据
            return "ZValue.Str[length=" + bytes.length + "]";
        }
    }
}
