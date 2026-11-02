package com.maccms.backend.phptemplate.runtime;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * PHP 子集语法树（技术设计 v15 C-18）。S3-07a 放基础：节点接口、字面文本段、字面量、变量与登记裸名；
 * S3-07b 按 RT-1 追加语句（echo、表达式语句、if/foreach/for/switch/break）与其余表达式（运算、赋值、下标、
 * 数组、插值、isset/empty、函数调用、{@code ModelCall}，以及 OP-T7 的 {@code InstanceOf}/{@code IsEmptyCall}），
 * 由 {@link PhpParser} 构造。每个节点的 {@link Node#offset()} 都是编译产物里的原始字节偏移（C-23 定位用）。
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

    // ---- S3-07b：语句 ----

    /** {@code echo e1, e2, ...;}。 */
    public record Echo(int offset, List<Expr> values) implements Stmt {
        public Echo {
            requireOffset(offset);
            values = List.copyOf(values);
            if (values.isEmpty()) {
                throw new IllegalArgumentException("echo needs arguments");
            }
        }
    }

    /** 表达式语句（赋值、{@code ++}、函数调用、模型调用等）。 */
    public record ExprStmt(int offset, Expr expr) implements Stmt {
        public ExprStmt {
            requireOffset(offset);
            Objects.requireNonNull(expr, "expr");
        }
    }

    /** {@code if}/{@code elseif} 的一支。 */
    public record Branch(int offset, Expr condition, List<Stmt> body) implements Node {
        public Branch {
            requireOffset(offset);
            Objects.requireNonNull(condition, "condition");
            body = List.copyOf(body);
        }
    }

    /**
     * {@code if (...) ... elseif (...) ... else ...}（花括号或 {@code :}/{@code endif} 写法）；
     * 无 else 时 {@code elseBody} 为 null。
     */
    public record If(int offset, List<Branch> branches, List<Stmt> elseBody) implements Stmt {
        public If {
            requireOffset(offset);
            branches = List.copyOf(branches);
            elseBody = elseBody == null ? null : List.copyOf(elseBody);
            if (branches.isEmpty()) {
                throw new IllegalArgumentException("if needs at least one branch");
            }
        }
    }

    /** {@code foreach ($subject as $key => $value)}；无键时 key 为 null。 */
    public record Foreach(int offset, Expr subject, Variable key, Variable value, List<Stmt> body) implements Stmt {
        public Foreach {
            requireOffset(offset);
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(value, "value");
            body = List.copyOf(body);
        }
    }

    /** {@code for (init; condition; step)}，三部分各是逗号分隔的表达式（可空）。 */
    public record For(int offset, List<Expr> init, List<Expr> condition, List<Expr> step, List<Stmt> body)
            implements Stmt {
        public For {
            requireOffset(offset);
            init = List.copyOf(init);
            condition = List.copyOf(condition);
            step = List.copyOf(step);
            body = List.copyOf(body);
        }
    }

    /** {@code case 值:} 或 {@code default:}（test 为 null）。 */
    public record Case(int offset, Expr test, List<Stmt> body) implements Node {
        public Case {
            requireOffset(offset);
            body = List.copyOf(body);
        }
    }

    /** {@code switch (subject) ...}。 */
    public record Switch(int offset, Expr subject, List<Case> cases) implements Stmt {
        public Switch {
            requireOffset(offset);
            Objects.requireNonNull(subject, "subject");
            cases = List.copyOf(cases);
        }
    }

    /** {@code break;}（只出现在 foreach/for/switch 内，不带层数）。 */
    public record Break(int offset) implements Stmt {
        public Break {
            requireOffset(offset);
        }
    }

    // ---- S3-07b：表达式 ----

    /** 双引号串插值：parts 只含 {@link StringLiteral} 与 {@link Variable}（RT-1 只支持 {@code $var}）。 */
    public record Interpolated(int offset, List<Expr> parts) implements Expr {
        public Interpolated {
            requireOffset(offset);
            parts = List.copyOf(parts);
            for (Expr p : parts) {
                if (!(p instanceof StringLiteral) && !(p instanceof Variable)) {
                    throw new IllegalArgumentException("interpolation part must be string or variable");
                }
            }
        }
    }

    /** 数组字面量的一项；无键时 key 为 null。 */
    public record ArrayItem(int offset, Expr key, Expr value) implements Node {
        public ArrayItem {
            requireOffset(offset);
            Objects.requireNonNull(value, "value");
        }
    }

    /** {@code [...]} 或 {@code array(...)}。 */
    public record ArrayLiteral(int offset, List<ArrayItem> items) implements Expr {
        public ArrayLiteral {
            requireOffset(offset);
            items = List.copyOf(items);
        }
    }

    /** 下标取值 {@code base[index]}；base 只能是变量或下标（左值链）。 */
    public record Index(int offset, Expr base, Expr index) implements Expr {
        public Index {
            requireOffset(offset);
            requireLvalue(base);
            Objects.requireNonNull(index, "index");
        }
    }

    /** 赋值：op 为 {@code =} 或 {@code .=}；target 为变量或下标（含 {@code $a['k'] = 值}，v11）。 */
    public record Assign(int offset, Expr target, String op, Expr value) implements Expr {
        public Assign {
            requireOffset(offset);
            requireLvalue(target);
            if (!"=".equals(op) && !".=".equals(op)) {
                throw new IllegalArgumentException("unsupported assignment operator");
            }
            Objects.requireNonNull(value, "value");
        }
    }

    /** {@code ++$a} / {@code $a++}（RT-1 只列 {@code ++}）。 */
    public record Increment(int offset, Expr target, boolean prefix) implements Expr {
        public Increment {
            requireOffset(offset);
            requireLvalue(target);
        }
    }

    /** 一元运算：{@code !}、{@code -}、{@code +}。 */
    public record Unary(int offset, String op, Expr operand) implements Expr {
        public Unary {
            requireOffset(offset);
            if (!UNARY_OPS.contains(op)) {
                throw new IllegalArgumentException("unsupported unary operator");
            }
            Objects.requireNonNull(operand, "operand");
        }
    }

    /**
     * 二元运算：连接 {@code .}、算术 {@code + - * / %}、比较 {@code == != <> === !== < <= > >=}、
     * 逻辑 {@code && || and or xor}（op 为小写）。
     */
    public record Binary(int offset, String op, Expr left, Expr right) implements Expr {
        public Binary {
            requireOffset(offset);
            if (!BINARY_OPS.contains(op)) {
                throw new IllegalArgumentException("unsupported binary operator");
            }
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    /** 三元 {@code c ? a : b}；{@code c ?: b} 时 then 为 null。 */
    public record Ternary(int offset, Expr condition, Expr then, Expr otherwise) implements Expr {
        public Ternary {
            requireOffset(offset);
            Objects.requireNonNull(condition, "condition");
            Objects.requireNonNull(otherwise, "otherwise");
        }
    }

    /** {@code isset(v1, ...)}：参数只能是变量或下标。 */
    public record Isset(int offset, List<Expr> targets) implements Expr {
        public Isset {
            requireOffset(offset);
            targets = List.copyOf(targets);
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("isset needs arguments");
            }
            targets.forEach(PhpAst::requireLvalue);
        }
    }

    /** {@code empty(expr)}。 */
    public record Empty(int offset, Expr operand) implements Expr {
        public Empty {
            requireOffset(offset);
            Objects.requireNonNull(operand, "operand");
        }
    }

    /** 函数调用 {@code name(args)}：name 是原文（不限定的名字），已通过解析时注入的 RT-7 白名单判定。 */
    public record FunctionCall(int offset, String name, List<Expr> args) implements Expr {
        public FunctionCall {
            requireOffset(offset);
            Objects.requireNonNull(name, "name");
            args = List.copyOf(args);
        }
    }

    /**
     * 模型调用（MC-1）：{@code model('<字面量>')->方法(实参)}。model 是字符串字面量的原文（未规范化），
     * {@link #normalizedModel()} 按 MC-2（{@code Loader::parseName($name, 1)}）规范化；方法名保留原文大小写。
     * 方法白名单（MC-3）由 {@code TagModels} 的唯一分派表判定，不在语法层。
     */
    public record ModelCall(int offset, String model, String method, List<Expr> args) implements Expr {
        public ModelCall {
            requireOffset(offset);
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(method, "method");
            args = List.copyOf(args);
        }

        /** MC-2：{@code _x} → {@code X}（x 为 ASCII 字母），再首字母大写；与 PHP {@code parseName($name, 1)} 一致。 */
        public String normalizedModel() {
            StringBuilder sb = new StringBuilder(model.length());
            for (int i = 0; i < model.length(); i++) {
                char c = model.charAt(i);
                if (c == '_' && i + 1 < model.length() && isAsciiLetter(model.charAt(i + 1))) {
                    sb.append(Character.toUpperCase(model.charAt(i + 1)));
                    i++;
                } else {
                    sb.append(c);
                }
            }
            if (!sb.isEmpty() && sb.charAt(0) >= 'a' && sb.charAt(0) <= 'z') {
                sb.setCharAt(0, (char) (sb.charAt(0) - 'a' + 'A'));
            }
            return sb.toString();
        }

        private static boolean isAsciiLetter(char c) {
            return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
        }
    }

    /**
     * OP-T7：{@code subject instanceof <完整类名>}，className 只能是 {@link #REGISTERED_CLASSES} 之一；只出现在
     * {@link PhpParser} 登记的两种固定形态里。值域只有数组与标量（RT-2），执行语义归 S3-07c。
     */
    public record InstanceOf(int offset, Expr subject, String className) implements Expr {
        public InstanceOf {
            requireOffset(offset);
            requireLvalue(subject);
            if (!REGISTERED_CLASSES.contains(className)) {
                throw new IllegalArgumentException("unregistered class name");
            }
        }
    }

    /** OP-T7：固定形态里紧跟两类名判定的 {@code subject->isEmpty()}；按短路永不执行，执行到达即报不支持（S3-07c）。 */
    public record IsEmptyCall(int offset, Expr subject) implements Expr {
        public IsEmptyCall {
            requireOffset(offset);
            requireLvalue(subject);
        }
    }

    /** OP-T7 登记的两个完整类名（区分大小写，原文带前导反斜杠）。 */
    public static final Set<String> REGISTERED_CLASSES = Set.of("\\think\\Collection", "\\think\\Paginator");

    private static final Set<String> UNARY_OPS = Set.of("!", "-", "+");

    private static final Set<String> BINARY_OPS = Set.of(
            ".", "+", "-", "*", "/", "%", "==", "!=", "<>", "===", "!==", "<", "<=", ">", ">=",
            "&&", "||", "and", "or", "xor");

    /** 左值：变量或下标链。 */
    public static boolean isLvalue(Expr e) {
        return e instanceof Variable || e instanceof Index;
    }

    private static void requireLvalue(Expr e) {
        Objects.requireNonNull(e, "target");
        if (!isLvalue(e)) {
            throw new IllegalArgumentException("target must be a variable or index");
        }
    }

    private static void requireOffset(int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
    }
}
