package com.maccms.backend.phptemplate.runtime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 编译产物的语法解析（技术设计 v15 C-18 语法部分；RT-1、BARE-1～BARE-5、MC-1/MC-2、OP-T7）。
 *
 * <p>输入是 {@link PhpLexer} 的切分结果，输出是 {@link PhpAst.Program}。只构造与校验语法树，不执行任何代码。
 * 字面段是 {@link PhpAst.InlineHtml} 语句；{@code ?>} 与 PHP 一样充当一个 {@code ;}。RT-1 以外的写法在解析阶段
 * 抛 {@link TemplateUnsupportedException}（带被拒记号首字节的原始偏移与原文片段），未执行的分支里出现也一样。</p>
 *
 * <ul>
 *   <li>语句：{@code echo}、表达式语句、{@code if/elseif/else}、{@code foreach ($a as $k => $v)}、{@code for}、
 *       {@code switch/case/default}、{@code break}（花括号与 {@code :}/{@code endXxx;} 两种写法）。</li>
 *   <li>表达式按 PHP 7.3 的优先级与结合性：{@code or} &lt; {@code xor} &lt; {@code and} &lt; 赋值（{@code =}、{@code .=}）
 *       &lt; 三元（左结合，含 {@code ?:}）&lt; {@code ||} &lt; {@code &&} &lt; 相等（不结合）&lt; 大小比较（不结合）
 *       &lt; {@code + - .}（同级左结合）&lt; {@code * / %} &lt; {@code !}/一元 {@code -}{@code +} &lt; {@code instanceof}
 *       &lt; {@code ++}、下标。</li>
 *   <li>BARE-1/BARE-2：名字后接 {@code (} 是函数调用，只查解析时注入的 RT-7 白名单，白名单外报 {@link TemplateUnsupportedException.Kind#FUNCTION}，不回退为裸名；
 *       不接 {@code (} 的名字：{@code true/false/null}（不分大小写）是字面量，原样在 {@link BareIdentifiers#REGISTERED} 内是
 *       {@link PhpAst.BareConst}，其余（含限定名、{@code X::Y}、魔术常量）报 {@link TemplateUnsupportedException.Kind#BARE_IDENTIFIER}（BARE-5）。</li>
 *   <li>MC-1：只有 {@code model(<无插值字符串字面量>)->名字(实参)} 是 {@link PhpAst.ModelCall}；其他 {@code model} 用法报
 *       {@link TemplateUnsupportedException.Kind#MODEL_CALL}。</li>
 *   <li>OP-T7：{@code instanceof} 右侧只接受 {@link PhpAst#REGISTERED_CLASSES} 的完整类名，且整个 {@code if}/{@code elseif}
 *       条件必须是下面两种固定形态之一（S 为同一个变量/字面量下标链）；{@code ->isEmpty()} 只在形态 B 里接受。
 *       其他类名、动态类名、{@code ->} 属性/方法、链式调用、固定形态以外出现的 {@code instanceof}/{@code isEmpty} 都报
 *       {@link TemplateUnsupportedException.Kind#OBJECT}：
 *       <pre>
 *       A: is_array(S) || S instanceof \think\Collection || S instanceof \think\Paginator
 *       B: empty(S) || ((S instanceof \think\Collection || S instanceof \think\Paginator ) &amp;&amp; S-&gt;isEmpty())
 *       </pre></li>
 * </ul>
 * <p>只依赖 JDK（§6.3）。</p>
 */
public final class PhpParser {

    private static final Set<String> EQUALITY_OPS = Set.of("==", "!=", "<>", "===", "!==");
    private static final Set<String> COMPARISON_OPS = Set.of("<", "<=", ">", ">=");
    private static final Set<String> COMPOUND_ASSIGN_OPS = Set.of(
            "+=", "-=", "*=", "/=", "%=", "**=", "&=", "|=", "^=", "<<=", ">>=");
    private static final String COLLECTION = "\\think\\Collection";
    private static final String PAGINATOR = "\\think\\Paginator";

    private final byte[] src;
    private final List<Tok> toks;
    private final Predicate<String> functions;
    private int pos;
    private int breakDepth;
    /** OP-T7：已构造、尚未被固定形态认领的 instanceof / isEmpty 节点。 */
    private final Set<PhpAst.Expr> pendingObjects = Collections.newSetFromMap(new IdentityHashMap<>());

    private PhpParser(byte[] src, List<Tok> toks, Predicate<String> functions) {
        this.src = src;
        this.toks = toks;
        this.functions = functions;
    }

    /**
     * 切分并解析编译产物。
     *
     * @param functions RT-7 白名单判定：传入函数名原文，返回是否允许调用（{@code model}、{@code isset}、{@code empty}、
     *                  {@code array} 由语法层处理，不经此判定）
     * @throws TemplateUnsupportedException 超出 RT-1 子集
     */
    public static PhpAst.Program parse(byte[] source, Predicate<String> functions) {
        return parse(PhpLexer.lex(source), functions);
    }

    /** 解析已切分的编译产物。 */
    public static PhpAst.Program parse(PhpLexer.Result lexed, Predicate<String> functions) {
        Objects.requireNonNull(functions, "functions");
        byte[] source = lexed.source();
        return new PhpParser(source, flatten(lexed, source), functions).program();
    }

    // ---- 记号流 ----

    private enum TK { OP, VAR, NAME, KW, CAST, INT, FLOAT, STR, INTERP, HTML, EOF }

    /** 统一记号：lexer 记号、字面段（HTML）、{@code ?>} 充当的 {@code ;}（virtual）、结尾。 */
    private record Tok(TK k, int start, int end, String text, PhpLexer.Token token, boolean virtual) {
    }

    private static List<Tok> flatten(PhpLexer.Result lexed, byte[] source) {
        List<Tok> out = new ArrayList<>();
        for (PhpLexer.Segment seg : lexed.segments()) {
            if (seg instanceof PhpLexer.Segment.Literal lit) {
                out.add(new Tok(TK.HTML, lit.start(), lit.end(), null, null, false));
                continue;
            }
            PhpLexer.Segment.Code code = (PhpLexer.Segment.Code) seg;
            for (PhpLexer.Token t : code.tokens()) {
                out.add(switch (t) {
                    case PhpLexer.Token.Op o -> new Tok(TK.OP, o.start(), o.end(), o.symbol(), t, false);
                    case PhpLexer.Token.Variable v -> new Tok(TK.VAR, v.start(), v.end(), v.name(), t, false);
                    case PhpLexer.Token.Name n -> new Tok(TK.NAME, n.start(), n.end(), n.name(), t, false);
                    case PhpLexer.Token.Keyword w -> new Tok(TK.KW, w.start(), w.end(), w.word(), t, false);
                    case PhpLexer.Token.Cast c -> new Tok(TK.CAST, c.start(), c.end(), c.type(), t, false);
                    case PhpLexer.Token.IntLit i -> new Tok(TK.INT, i.start(), i.end(), null, t, false);
                    case PhpLexer.Token.FloatLit f -> new Tok(TK.FLOAT, f.start(), f.end(), null, t, false);
                    case PhpLexer.Token.Str s -> new Tok(TK.STR, s.start(), s.end(), null, t, false);
                    case PhpLexer.Token.Interpolated s -> new Tok(TK.INTERP, s.start(), s.end(), null, t, false);
                });
            }
            if (code.close() != PhpLexer.Close.EOF) {
                out.add(new Tok(TK.OP, code.codeEnd(), code.codeEnd(), ";", null, true));
            }
        }
        out.add(new Tok(TK.EOF, source.length, source.length, null, null, false));
        return out;
    }

    private Tok peek() {
        return toks.get(pos);
    }

    private Tok peek(int ahead) {
        return toks.get(Math.min(pos + ahead, toks.size() - 1));
    }

    private Tok next() {
        Tok t = toks.get(pos);
        if (t.k != TK.EOF) {
            pos++;
        }
        return t;
    }

    private static boolean isOp(Tok t, String symbol) {
        return t.k == TK.OP && t.text.equals(symbol);
    }

    private boolean atOp(String symbol) {
        return isOp(peek(), symbol);
    }

    private boolean atKw(String word) {
        Tok t = peek();
        return t.k == TK.KW && t.text.equals(word);
    }

    private Tok expectOp(String symbol) {
        if (!atOp(symbol)) {
            throw fail(TemplateUnsupportedException.Kind.SYNTAX, peek().start);
        }
        return next();
    }

    private Tok expectKw(String word) {
        if (!atKw(word)) {
            throw fail(TemplateUnsupportedException.Kind.SYNTAX, peek().start);
        }
        return next();
    }

    private TemplateUnsupportedException fail(TemplateUnsupportedException.Kind kind, int offset) {
        return new TemplateUnsupportedException(kind, src, offset);
    }

    // ---- 语句 ----

    private PhpAst.Program program() {
        List<PhpAst.Stmt> out = statementsUntil();
        if (peek().k != TK.EOF) {
            throw fail(TemplateUnsupportedException.Kind.SYNTAX, peek().start);
        }
        if (!pendingObjects.isEmpty()) {
            int first = Integer.MAX_VALUE;
            for (PhpAst.Expr e : pendingObjects) {
                first = Math.min(first, e.offset());
            }
            throw fail(TemplateUnsupportedException.Kind.OBJECT, first);
        }
        return new PhpAst.Program(out);
    }

    /** 解析语句直到 EOF 或遇到 stops 里的关键字 / {@code }}（不消费）。 */
    private List<PhpAst.Stmt> statementsUntil(String... stops) {
        Set<String> stopSet = Set.of(stops);
        List<PhpAst.Stmt> out = new ArrayList<>();
        while (true) {
            Tok t = peek();
            if (t.k == TK.EOF) {
                return out;
            }
            if (t.k == TK.KW && stopSet.contains(t.text) || isOp(t, "}") && stopSet.contains("}")) {
                return out;
            }
            statement(out);
        }
    }

    private void statement(List<PhpAst.Stmt> out) {
        Tok t = peek();
        if (t.k == TK.HTML) {
            next();
            out.add(new PhpAst.InlineHtml(t.start, Arrays.copyOfRange(src, t.start, t.end)));
            return;
        }
        if (isOp(t, ";")) {
            next();
            return;
        }
        if (isOp(t, "{")) {
            next();
            out.addAll(statementsUntil("}"));
            expectOp("}");
            return;
        }
        if (t.k == TK.KW) {
            switch (t.text) {
                case "echo" -> {
                    out.add(echo());
                    return;
                }
                case "if" -> {
                    out.add(ifStmt());
                    return;
                }
                case "foreach" -> {
                    out.add(foreachStmt());
                    return;
                }
                case "for" -> {
                    out.add(forStmt());
                    return;
                }
                case "switch" -> {
                    out.add(switchStmt());
                    return;
                }
                case "break" -> {
                    out.add(breakStmt());
                    return;
                }
                default -> {
                    // 其余关键字只在表达式里可能合法（isset/empty/array 等），不合法的由表达式解析拒绝
                }
            }
        }
        PhpAst.Expr e = expr();
        endStatement();
        out.add(new PhpAst.ExprStmt(e.offset(), e));
    }

    private void endStatement() {
        expectOp(";");
    }

    private PhpAst.Echo echo() {
        Tok kw = next();
        List<PhpAst.Expr> values = new ArrayList<>();
        values.add(expr());
        while (atOp(",")) {
            next();
            values.add(expr());
        }
        endStatement();
        return new PhpAst.Echo(kw.start, values);
    }

    private PhpAst.Expr parenExpr() {
        expectOp("(");
        PhpAst.Expr e = expr();
        expectOp(")");
        return e;
    }

    private List<PhpAst.Stmt> singleStatement() {
        List<PhpAst.Stmt> body = new ArrayList<>();
        if (peek().k == TK.EOF) {
            throw fail(TemplateUnsupportedException.Kind.SYNTAX, peek().start);
        }
        statement(body);
        return body;
    }

    private PhpAst.If ifStmt() {
        Tok kw = next();
        PhpAst.Expr cond = condition();
        List<PhpAst.Branch> branches = new ArrayList<>();
        List<PhpAst.Stmt> elseBody = null;
        if (atOp(":")) {
            next();
            branches.add(new PhpAst.Branch(kw.start, cond, statementsUntil("elseif", "else", "endif")));
            while (true) {
                if (atKw("elseif")) {
                    Tok ek = next();
                    PhpAst.Expr c = condition();
                    expectOp(":");
                    branches.add(new PhpAst.Branch(ek.start, c, statementsUntil("elseif", "else", "endif")));
                } else if (atKw("else")) {
                    next();
                    expectOp(":");
                    elseBody = statementsUntil("endif");
                    break;
                } else {
                    break;
                }
            }
            expectKw("endif");
            endStatement();
        } else {
            branches.add(new PhpAst.Branch(kw.start, cond, singleStatement()));
            while (true) {
                if (atKw("elseif")) {
                    Tok ek = next();
                    PhpAst.Expr c = condition();
                    if (atOp(":")) {
                        throw fail(TemplateUnsupportedException.Kind.SYNTAX, peek().start);
                    }
                    branches.add(new PhpAst.Branch(ek.start, c, singleStatement()));
                } else if (atKw("else")) {
                    next();
                    if (atOp(":")) {
                        throw fail(TemplateUnsupportedException.Kind.SYNTAX, peek().start);
                    }
                    elseBody = singleStatement();
                    break;
                } else {
                    break;
                }
            }
        }
        return new PhpAst.If(kw.start, branches, elseBody);
    }

    /** if/elseif 的括号条件；条件整体若是 OP-T7 固定形态，认领其中的 instanceof/isEmpty 节点。 */
    private PhpAst.Expr condition() {
        PhpAst.Expr c = parenExpr();
        claimFixedForm(c);
        return c;
    }

    private PhpAst.Foreach foreachStmt() {
        Tok kw = next();
        expectOp("(");
        PhpAst.Expr subject = expr();
        expectKw("as");
        PhpAst.Variable first = foreachVariable();
        PhpAst.Variable key = null;
        PhpAst.Variable value = first;
        if (atOp("=>")) {
            next();
            key = first;
            value = foreachVariable();
        }
        expectOp(")");
        List<PhpAst.Stmt> body = loopBody("endforeach");
        return new PhpAst.Foreach(kw.start, subject, key, value, body);
    }

    private PhpAst.Variable foreachVariable() {
        Tok t = peek();
        if (t.k != TK.VAR) {
            throw fail(TemplateUnsupportedException.Kind.SYNTAX, t.start);
        }
        next();
        return new PhpAst.Variable(t.start, t.text);
    }

    private List<PhpAst.Stmt> loopBody(String endWord) {
        breakDepth++;
        try {
            if (atOp(":")) {
                next();
                List<PhpAst.Stmt> body = statementsUntil(endWord);
                expectKw(endWord);
                endStatement();
                return body;
            }
            return singleStatement();
        } finally {
            breakDepth--;
        }
    }

    private PhpAst.For forStmt() {
        Tok kw = next();
        expectOp("(");
        List<PhpAst.Expr> init = exprList(";");
        expectOp(";");
        List<PhpAst.Expr> cond = exprList(";");
        expectOp(";");
        List<PhpAst.Expr> step = exprList(")");
        expectOp(")");
        List<PhpAst.Stmt> body = loopBody("endfor");
        return new PhpAst.For(kw.start, init, cond, step, body);
    }

    private List<PhpAst.Expr> exprList(String close) {
        List<PhpAst.Expr> out = new ArrayList<>();
        if (atOp(close)) {
            return out;
        }
        out.add(expr());
        while (atOp(",")) {
            next();
            out.add(expr());
        }
        return out;
    }

    private PhpAst.Switch switchStmt() {
        Tok kw = next();
        PhpAst.Expr subject = parenExpr();
        boolean alt;
        if (atOp(":")) {
            alt = true;
        } else if (atOp("{")) {
            alt = false;
        } else {
            throw fail(TemplateUnsupportedException.Kind.SYNTAX, peek().start);
        }
        next();
        if (atOp(";")) {
            next(); // PHP 文法允许 switch 后紧跟一个 ;（含 ?> 充当的 ;）
        }
        String end = alt ? "endswitch" : "}";
        List<PhpAst.Case> cases = new ArrayList<>();
        boolean seenDefault = false;
        breakDepth++;
        try {
            while (true) {
                Tok t = peek();
                if (alt ? t.k == TK.KW && t.text.equals("endswitch") : isOp(t, "}")) {
                    break;
                }
                if (t.k == TK.KW && t.text.equals("case")) {
                    next();
                    PhpAst.Expr test = expr();
                    expectOp(":");
                    cases.add(new PhpAst.Case(t.start, test, statementsUntil("case", "default", end)));
                } else if (t.k == TK.KW && t.text.equals("default")) {
                    if (seenDefault) {
                        throw fail(TemplateUnsupportedException.Kind.SYNTAX, t.start);
                    }
                    seenDefault = true;
                    next();
                    expectOp(":");
                    cases.add(new PhpAst.Case(t.start, null, statementsUntil("case", "default", end)));
                } else {
                    // switch 与第一个 case 之间的字面文本、其他语句：PHP 7.3 解析错误
                    throw fail(TemplateUnsupportedException.Kind.SYNTAX, t.start);
                }
            }
        } finally {
            breakDepth--;
        }
        if (alt) {
            expectKw("endswitch");
            endStatement();
        } else {
            expectOp("}");
        }
        return new PhpAst.Switch(kw.start, subject, cases);
    }

    private PhpAst.Break breakStmt() {
        Tok kw = next();
        if (breakDepth == 0) {
            throw fail(TemplateUnsupportedException.Kind.SYNTAX, kw.start);
        }
        if (!atOp(";")) {
            throw fail(TemplateUnsupportedException.Kind.SYNTAX, peek().start); // break N 不在 RT-1
        }
        endStatement();
        return new PhpAst.Break(kw.start);
    }

    // ---- 表达式 ----

    private PhpAst.Expr expr() {
        PhpAst.Expr left = lowXor();
        while (atKw("or")) {
            next();
            left = new PhpAst.Binary(left.offset(), "or", left, lowXor());
        }
        return left;
    }

    private PhpAst.Expr lowXor() {
        PhpAst.Expr left = lowAnd();
        while (atKw("xor")) {
            next();
            left = new PhpAst.Binary(left.offset(), "xor", left, lowAnd());
        }
        return left;
    }

    private PhpAst.Expr lowAnd() {
        PhpAst.Expr left = assignment();
        while (atKw("and")) {
            next();
            left = new PhpAst.Binary(left.offset(), "and", left, assignment());
        }
        return left;
    }

    /** 赋值级 = 三元级（赋值本身在 {@link #postfix()} 里按左值识别，右侧回到本级，右结合）。 */
    private PhpAst.Expr assignment() {
        PhpAst.Expr cond = orExpr();
        while (atOp("?")) {
            next();
            PhpAst.Expr then = null;
            if (!atOp(":")) {
                then = assignment();
            }
            expectOp(":");
            PhpAst.Expr otherwise = orExpr();
            cond = new PhpAst.Ternary(cond.offset(), cond, then, otherwise);
        }
        return cond;
    }

    private PhpAst.Expr orExpr() {
        PhpAst.Expr left = andExpr();
        while (atOp("||")) {
            next();
            left = new PhpAst.Binary(left.offset(), "||", left, andExpr());
        }
        return left;
    }

    private PhpAst.Expr andExpr() {
        PhpAst.Expr left = equality();
        while (atOp("&&")) {
            next();
            left = new PhpAst.Binary(left.offset(), "&&", left, equality());
        }
        return left;
    }

    private PhpAst.Expr equality() {
        PhpAst.Expr left = comparison();
        if (peek().k == TK.OP && EQUALITY_OPS.contains(peek().text)) {
            String op = next().text;
            left = new PhpAst.Binary(left.offset(), op, left, comparison());
            if (peek().k == TK.OP && EQUALITY_OPS.contains(peek().text)) {
                throw fail(TemplateUnsupportedException.Kind.SYNTAX, peek().start); // PHP 7.3：相等运算不结合
            }
        }
        return left;
    }

    private PhpAst.Expr comparison() {
        PhpAst.Expr left = additive();
        if (peek().k == TK.OP && COMPARISON_OPS.contains(peek().text)) {
            String op = next().text;
            left = new PhpAst.Binary(left.offset(), op, left, additive());
            if (peek().k == TK.OP && COMPARISON_OPS.contains(peek().text)) {
                throw fail(TemplateUnsupportedException.Kind.SYNTAX, peek().start); // PHP 7.3：大小比较不结合
            }
        }
        return left;
    }

    private PhpAst.Expr additive() {
        PhpAst.Expr left = multiplicative();
        while (atOp("+") || atOp("-") || atOp(".")) {
            String op = next().text;
            left = new PhpAst.Binary(left.offset(), op, left, multiplicative());
        }
        return left;
    }

    private PhpAst.Expr multiplicative() {
        PhpAst.Expr left = unary();
        while (atOp("*") || atOp("/") || atOp("%")) {
            String op = next().text;
            left = new PhpAst.Binary(left.offset(), op, left, unary());
        }
        return left;
    }

    private PhpAst.Expr unary() {
        Tok t = peek();
        if (isOp(t, "!") || isOp(t, "-") || isOp(t, "+")) {
            next();
            return new PhpAst.Unary(t.start, t.text, unary());
        }
        if (isOp(t, "++")) {
            next();
            PhpAst.Expr target = postfixNoAssign();
            if (!PhpAst.isLvalue(target)) {
                throw fail(TemplateUnsupportedException.Kind.SYNTAX, target.offset());
            }
            return new PhpAst.Increment(t.start, target, true);
        }
        if (t.k == TK.CAST || isOp(t, "--") || isOp(t, "~") || isOp(t, "@") || isOp(t, "&")) {
            throw fail(TemplateUnsupportedException.Kind.SYNTAX, t.start);
        }
        PhpAst.Expr e = postfix();
        if (atKw("instanceof")) {
            e = instanceOf(e);
        }
        return e;
    }

    private PhpAst.Expr instanceOf(PhpAst.Expr subject) {
        Tok kw = next();
        Tok cls = peek();
        if (!PhpAst.isLvalue(subject)) {
            throw fail(TemplateUnsupportedException.Kind.OBJECT, kw.start);
        }
        if (cls.k != TK.NAME || !(cls.token instanceof PhpLexer.Token.Name n)
                || n.kind() != PhpLexer.NameKind.FULLY_QUALIFIED || !PhpAst.REGISTERED_CLASSES.contains(n.name())) {
            // 其他类名、未限定/部分限定类名、动态类名（$x）、归一化黄金里的 /think/… 都不是登记类名
            throw fail(TemplateUnsupportedException.Kind.OBJECT, cls.start);
        }
        next();
        PhpAst.InstanceOf io = new PhpAst.InstanceOf(subject.offset(), subject, n.name());
        pendingObjects.add(io);
        return io;
    }

    /** 后缀表达式，并在左值后识别赋值与后置 {@code ++}。 */
    private PhpAst.Expr postfix() {
        PhpAst.Expr p = postfixNoAssign();
        if (!PhpAst.isLvalue(p)) {
            return p;
        }
        Tok t = peek();
        if (isOp(t, "=") || isOp(t, ".=")) {
            next();
            return new PhpAst.Assign(p.offset(), p, t.text, assignment());
        }
        if (isOp(t, "++")) {
            next();
            return new PhpAst.Increment(p.offset(), p, false);
        }
        if (t.k == TK.OP && (COMPOUND_ASSIGN_OPS.contains(t.text) || t.text.equals("--"))) {
            throw fail(TemplateUnsupportedException.Kind.SYNTAX, t.start);
        }
        return p;
    }

    private PhpAst.Expr postfixNoAssign() {
        if (atOp("(")) {
            // 括号表达式：不是左值，也不接下标 / -> / 赋值
            next();
            PhpAst.Expr e = expr();
            expectOp(")");
            return e;
        }
        PhpAst.Expr p = primary();
        while (true) {
            Tok t = peek();
            if (isOp(t, "[")) {
                if (!PhpAst.isLvalue(p)) {
                    throw fail(TemplateUnsupportedException.Kind.SYNTAX, t.start); // 只对变量/下标取下标
                }
                next();
                if (atOp("]")) {
                    throw fail(TemplateUnsupportedException.Kind.SYNTAX, peek().start); // $a[] 不在 RT-1
                }
                PhpAst.Expr idx = expr();
                expectOp("]");
                p = new PhpAst.Index(p.offset(), p, idx);
            } else if (isOp(t, "->")) {
                p = objectOperator(p, t);
            } else if (isOp(t, "::")) {
                throw fail(TemplateUnsupportedException.Kind.OBJECT, t.start);
            } else {
                return p;
            }
        }
    }

    /** {@code ->}：只认 {@code S->isEmpty()}（OP-T7 形态 B，最后由固定形态认领），其余一律拒绝。 */
    private PhpAst.Expr objectOperator(PhpAst.Expr subject, Tok arrow) {
        Tok name = peek(1);
        if (PhpAst.isLvalue(subject) && name.k == TK.NAME && "isEmpty".equals(name.text)
                && isOp(peek(2), "(") && isOp(peek(3), ")")) {
            next();
            next();
            next();
            next();
            Tok after = peek();
            if (isOp(after, "->") || isOp(after, "[") || isOp(after, "::")) {
                throw fail(TemplateUnsupportedException.Kind.OBJECT, after.start); // 链式
            }
            PhpAst.IsEmptyCall call = new PhpAst.IsEmptyCall(subject.offset(), subject);
            pendingObjects.add(call);
            return call;
        }
        throw fail(TemplateUnsupportedException.Kind.OBJECT, arrow.start);
    }

    private PhpAst.Expr primary() {
        Tok t = peek();
        switch (t.k) {
            case VAR -> {
                next();
                return new PhpAst.Variable(t.start, t.text);
            }
            case INT -> {
                next();
                return new PhpAst.IntLiteral(t.start, ((PhpLexer.Token.IntLit) t.token).value());
            }
            case FLOAT -> {
                next();
                return new PhpAst.FloatLiteral(t.start, ((PhpLexer.Token.FloatLit) t.token).value());
            }
            case STR -> {
                next();
                return new PhpAst.StringLiteral(t.start, ((PhpLexer.Token.Str) t.token).bytes());
            }
            case INTERP -> {
                next();
                List<PhpAst.Expr> parts = new ArrayList<>();
                for (PhpLexer.Part part : ((PhpLexer.Token.Interpolated) t.token).parts()) {
                    if (part instanceof PhpLexer.Part.Text text) {
                        parts.add(new PhpAst.StringLiteral(t.start, text.bytes()));
                    } else {
                        PhpLexer.Part.Var v = (PhpLexer.Part.Var) part;
                        parts.add(new PhpAst.Variable(v.offset(), v.name()));
                    }
                }
                return new PhpAst.Interpolated(t.start, parts);
            }
            case NAME -> {
                return name();
            }
            case KW -> {
                return keywordExpr();
            }
            case OP -> {
                if (t.text.equals("[")) {
                    next();
                    return arrayLiteral(t.start, "]");
                }
                // 含单独的 $（$$a、${...}，RT-1 拒绝）
                throw fail(TemplateUnsupportedException.Kind.SYNTAX, t.start);
            }
            default -> throw fail(TemplateUnsupportedException.Kind.SYNTAX, t.start);
        }
    }

    private PhpAst.Expr keywordExpr() {
        Tok t = peek();
        switch (t.text) {
            case "array" -> {
                next();
                expectOp("(");
                return arrayLiteral(t.start, ")");
            }
            case "isset" -> {
                next();
                expectOp("(");
                List<PhpAst.Expr> targets = new ArrayList<>();
                while (true) {
                    PhpAst.Expr e = expr();
                    if (!PhpAst.isLvalue(e)) {
                        throw fail(TemplateUnsupportedException.Kind.SYNTAX, e.offset());
                    }
                    targets.add(e);
                    if (atOp(",")) {
                        next();
                        continue;
                    }
                    break;
                }
                expectOp(")");
                return new PhpAst.Isset(t.start, targets);
            }
            case "empty" -> {
                next();
                expectOp("(");
                PhpAst.Expr e = expr();
                expectOp(")");
                return new PhpAst.Empty(t.start, e);
            }
            case "static" -> {
                if (isOp(peek(1), "::")) {
                    throw fail(TemplateUnsupportedException.Kind.BARE_IDENTIFIER, t.start); // BARE-5 ②：static::Y
                }
                throw fail(TemplateUnsupportedException.Kind.SYNTAX, t.start);
            }
            default -> throw fail(TemplateUnsupportedException.Kind.SYNTAX, t.start); // new、function、include、eval、print、exit、list、clone……
        }
    }

    private PhpAst.Expr arrayLiteral(int offset, String close) {
        List<PhpAst.ArrayItem> items = new ArrayList<>();
        while (!atOp(close)) {
            Tok t = peek();
            if (isOp(t, "&") || isOp(t, "...") || isOp(t, ",")) {
                throw fail(TemplateUnsupportedException.Kind.SYNTAX, t.start);
            }
            PhpAst.Expr first = expr();
            if (atOp("=>")) {
                next();
                Tok v = peek();
                if (isOp(v, "&")) {
                    throw fail(TemplateUnsupportedException.Kind.SYNTAX, v.start);
                }
                items.add(new PhpAst.ArrayItem(first.offset(), first, expr()));
            } else {
                items.add(new PhpAst.ArrayItem(first.offset(), null, first));
            }
            if (atOp(",")) {
                next();
            } else if (!atOp(close)) {
                throw fail(TemplateUnsupportedException.Kind.SYNTAX, peek().start);
            }
        }
        next();
        if (atOp("=")) {
            throw fail(TemplateUnsupportedException.Kind.SYNTAX, peek().start); // [$a, $b] = ... 解构不在 RT-1
        }
        return new PhpAst.ArrayLiteral(offset, items);
    }

    private PhpAst.Expr name() {
        Tok t = next();
        PhpLexer.Token.Name n = (PhpLexer.Token.Name) t.token;
        Tok after = peek();
        if (n.kind() != PhpLexer.NameKind.UNQUALIFIED) {
            // 带命名空间的函数调用不在 RT-7；带命名空间的常量是 BARE-5 ②
            throw fail(isOp(after, "(") ? TemplateUnsupportedException.Kind.FUNCTION : TemplateUnsupportedException.Kind.BARE_IDENTIFIER, t.start);
        }
        if (isOp(after, "::")) {
            throw fail(TemplateUnsupportedException.Kind.BARE_IDENTIFIER, t.start); // 类常量 X::Y、self::Y（BARE-5 ②）
        }
        if (isOp(after, "(")) {
            if (t.text.equals("model")) {
                return modelCall(t);
            }
            if (!functions.test(t.text)) {
                throw fail(TemplateUnsupportedException.Kind.FUNCTION, t.start); // BARE-2：不回退为裸名
            }
            return new PhpAst.FunctionCall(t.start, t.text, arguments());
        }
        String lower = t.text.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "true" -> {
                return new PhpAst.BoolLiteral(t.start, true);
            }
            case "false" -> {
                return new PhpAst.BoolLiteral(t.start, false);
            }
            case "null" -> {
                return new PhpAst.NullLiteral(t.start);
            }
            default -> {
                if (BareIdentifiers.REGISTERED.contains(t.text)) {
                    return new PhpAst.BareConst(t.start, t.text);
                }
                throw fail(TemplateUnsupportedException.Kind.BARE_IDENTIFIER, t.start); // BARE-5 ①③
            }
        }
    }

    private List<PhpAst.Expr> arguments() {
        expectOp("(");
        List<PhpAst.Expr> args = new ArrayList<>();
        if (atOp(")")) {
            next();
            return args;
        }
        while (true) {
            Tok t = peek();
            if (isOp(t, "...") || isOp(t, "&")) {
                throw fail(TemplateUnsupportedException.Kind.SYNTAX, t.start);
            }
            args.add(expr());
            if (atOp(",")) {
                next();
                if (atOp(")")) {
                    throw fail(TemplateUnsupportedException.Kind.SYNTAX, peek().start); // 尾逗号不在登记写法内
                }
                continue;
            }
            expectOp(")");
            return args;
        }
    }

    /** MC-1：{@code model('<字面量>')->方法(实参)}，后面不能再接 {@code ->}、下标或 {@code ::}。 */
    private PhpAst.Expr modelCall(Tok modelTok) {
        if (!isOp(peek(), "(") || peek(1).k != TK.STR || !isOp(peek(2), ")") || !isOp(peek(3), "->")
                || peek(4).k != TK.NAME || ((PhpLexer.Token.Name) peek(4).token).kind() != PhpLexer.NameKind.UNQUALIFIED
                || !isOp(peek(5), "(")) {
            throw fail(TemplateUnsupportedException.Kind.MODEL_CALL, modelTok.start);
        }
        next();
        Tok lit = next();
        next();
        next();
        Tok method = next();
        List<PhpAst.Expr> args = arguments();
        Tok after = peek();
        if (isOp(after, "->") || isOp(after, "[") || isOp(after, "::")) {
            throw fail(TemplateUnsupportedException.Kind.MODEL_CALL, after.start); // 链式第二次 ->、对结果取下标
        }
        String model = new String(((PhpLexer.Token.Str) lit.token).bytes(), StandardCharsets.UTF_8);
        return new PhpAst.ModelCall(modelTok.start, model, method.text, args);
    }

    // ---- OP-T7 固定形态 ----

    private void claimFixedForm(PhpAst.Expr c) {
        // A: (is_array(S) || S instanceof C) || S instanceof P
        if (c instanceof PhpAst.Binary or && or.op().equals("||")
                && or.right() instanceof PhpAst.InstanceOf p && p.className().equals(PAGINATOR)
                && or.left() instanceof PhpAst.Binary inner && inner.op().equals("||")
                && inner.right() instanceof PhpAst.InstanceOf col && col.className().equals(COLLECTION)
                && inner.left() instanceof PhpAst.FunctionCall f && f.name().equals("is_array") && f.args().size() == 1
                && sameSubject(f.args().get(0), col.subject()) && sameSubject(col.subject(), p.subject())) {
            pendingObjects.remove(p);
            pendingObjects.remove(col);
            return;
        }
        // B: empty(S) || ((S instanceof C || S instanceof P) && S->isEmpty())
        if (c instanceof PhpAst.Binary or && or.op().equals("||")
                && or.left() instanceof PhpAst.Empty empty
                && or.right() instanceof PhpAst.Binary and && and.op().equals("&&")
                && and.right() instanceof PhpAst.IsEmptyCall call
                && and.left() instanceof PhpAst.Binary pair && pair.op().equals("||")
                && pair.left() instanceof PhpAst.InstanceOf col && col.className().equals(COLLECTION)
                && pair.right() instanceof PhpAst.InstanceOf p && p.className().equals(PAGINATOR)
                && sameSubject(empty.operand(), col.subject()) && sameSubject(col.subject(), p.subject())
                && sameSubject(p.subject(), call.subject())) {
            pendingObjects.remove(col);
            pendingObjects.remove(p);
            pendingObjects.remove(call);
        }
    }

    /** 同一个变量 / 字面量下标链（不比偏移）。 */
    private static boolean sameSubject(PhpAst.Expr a, PhpAst.Expr b) {
        if (a instanceof PhpAst.Variable va && b instanceof PhpAst.Variable vb) {
            return va.name().equals(vb.name());
        }
        if (a instanceof PhpAst.Index ia && b instanceof PhpAst.Index ib) {
            return sameSubject(ia.base(), ib.base()) && sameKey(ia.index(), ib.index());
        }
        return false;
    }

    private static boolean sameKey(PhpAst.Expr a, PhpAst.Expr b) {
        if (a instanceof PhpAst.StringLiteral sa && b instanceof PhpAst.StringLiteral sb) {
            return Arrays.equals(sa.bytes(), sb.bytes());
        }
        if (a instanceof PhpAst.IntLiteral ia && b instanceof PhpAst.IntLiteral ib) {
            return ia.value() == ib.value();
        }
        return false;
    }
}
