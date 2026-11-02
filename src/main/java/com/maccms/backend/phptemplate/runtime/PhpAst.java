package com.maccms.backend.phptemplate.runtime;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * PHP 子集语法树（技术设计 v15 C-18）——S3-07a 只放基础：节点接口、字面文本段、字面量、变量与登记裸名。
 * 语句与其余表达式节点（echo、赋值、if/foreach/for/switch、函数调用、{@code ModelCall} 等）由 S3-07b
 * 的解析器按 RT-1 追加。每个节点的 {@link Node#offset()} 都是编译产物里的原始字节偏移（C-23 定位用）。
 * 只依赖 JDK（§6.3）。
 */
public final class PhpAst {

    private PhpAst() {
    }

    /** 语法树节点。 */
    public interface Node {
        /** 节点首字节在编译产物里的偏移。 */
        int offset();
    }

    /** 语句。 */
    public interface Stmt extends Node {
    }

    /** 表达式。 */
    public interface Expr extends Node {
    }

    /** 整个编译产物。 */
    public record Program(List<Stmt> statements) {
        public Program {
            statements = List.copyOf(statements);
        }
    }

    /** 字面文本段：原样输出的字节（TPL-5 吃掉的换行不在内），不做任何换行归一化。 */
    public record InlineHtml(int offset, byte[] bytes) implements Stmt {
        public InlineHtml {
            requireOffset(offset);
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof InlineHtml h && h.offset == offset && Arrays.equals(h.bytes, bytes);
        }

        @Override
        public int hashCode() {
            return 31 * offset + Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "InlineHtml[offset=" + offset + ", length=" + bytes.length + "]";
        }
    }

    /** {@code null}（含不分大小写的 {@code NULL}，BARE-1 ①）。 */
    public record NullLiteral(int offset) implements Expr {
        public NullLiteral {
            requireOffset(offset);
        }
    }

    /** {@code true}/{@code false}（不分大小写，BARE-1 ①）。 */
    public record BoolLiteral(int offset, boolean value) implements Expr {
        public BoolLiteral {
            requireOffset(offset);
        }
    }

    /** 十进制整数字面量（64 位）。 */
    public record IntLiteral(int offset, long value) implements Expr {
        public IntLiteral {
            requireOffset(offset);
        }
    }

    /** 浮点字面量（含超出 64 位的十进制整数，PHP 同样按 float）。 */
    public record FloatLiteral(int offset, double value) implements Expr {
        public FloatLiteral {
            requireOffset(offset);
        }
    }

    /** 已解码转义的字符串字面量（字节串，RT-2）。 */
    public record StringLiteral(int offset, byte[] bytes) implements Expr {
        public StringLiteral {
            requireOffset(offset);
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof StringLiteral s && s.offset == offset && Arrays.equals(s.bytes, bytes);
        }

        @Override
        public int hashCode() {
            return 31 * offset + Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "StringLiteral[offset=" + offset + ", length=" + bytes.length + "]";
        }
    }

    /** 变量 {@code $name}（不含 {@code $}）。 */
    public record Variable(int offset, String name) implements Expr {
        public Variable {
            requireOffset(offset);
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("variable name must not be empty");
            }
        }
    }

    /** 登记裸名（BARE-4）：名字必须原样在 {@link BareIdentifiers#REGISTERED} 内。 */
    public record BareConst(int offset, String name) implements Expr {
        public BareConst {
            requireOffset(offset);
            if (!BareIdentifiers.REGISTERED.contains(Objects.requireNonNull(name, "name"))) {
                throw new IllegalArgumentException("not a registered bare identifier");
            }
        }
    }

    private static void requireOffset(int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
    }
}
