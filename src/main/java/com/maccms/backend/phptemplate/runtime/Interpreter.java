package com.maccms.backend.phptemplate.runtime;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PHP 子集解释器（技术设计 v15 C-19）：执行 {@link PhpParser} 产出的语法树，输出写到字节缓冲。
 *
 * <ul>
 *   <li>值语义只用 C-17（{@link ZValue}、{@link ZArray}、{@link PhpSemantics}）：字符串化、7.3 弱比较、未定义取 null（RT-2～RT-5）；
 *       数组赋值、传参、{@code foreach} 都取快照，等同 PHP 写时复制。</li>
 *   <li>函数只经注入的 {@link FunctionDispatcher}（RT-7）；模型调用只经注入的 {@link ModelCallHandler}（MC-1～MC-6），
 *       实参按值快照（MC-4），处理器抛出的异常原样上抛（MC-6、QG-5）。没有反射、文件、网络、数据库访问。</li>
 *   <li>裸名 {@link PhpAst.BareConst} 求值为 {@code ZValue.of(name)}，不查任何表、不写日志（BARE-4）。</li>
 *   <li>OP-T7：{@code instanceof} 先求主语，按 {@link ZValue} 封闭类型逐一判定为数组或标量，结果为 false；
 *       {@code ->isEmpty()} 一旦被执行到即报 {@code object}（固定形态里因短路永不执行）。</li>
 *   <li>RT-8：循环总次数、输出字节、语句嵌套深度超过 {@link ExecutionLimits} 报 {@code limit}。</li>
 * </ul>
 *
 * <p>PHP 的 Notice/Warning 只进日志、不进响应体（RT-5）；本类不写任何日志（runtime 只依赖 JDK，§6.3）。
 * 实例无状态，可复用；每次 {@link #render}/{@link #execute} 使用独立的执行上下文。</p>
 */
public final class Interpreter {

    private final FunctionDispatcher functions;
    private final ModelCallHandler models;
    private final ExecutionLimits limits;

    public Interpreter(FunctionDispatcher functions, ModelCallHandler models, ExecutionLimits limits) {
        this.functions = Objects.requireNonNull(functions, "functions");
        this.models = Objects.requireNonNull(models, "models");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * 解析并执行编译产物（原始字节，不做任何归一化）。
     *
     * @param source 编译产物原始字节
     * @param vars   模板变量作用域（键为变量名，不含 {@code $}）；执行中的赋值写回这里
     * @return 输出字节
     * @throws TemplateUnsupportedException 超出 RT-1 子集、超出 RT-8 上限或执行到不支持的写法
     */
    public byte[] render(byte[] source, ZArray vars) {
        PhpAst.Program program = PhpParser.parse(source, functions::isRegistered);
        return execute(source, program, vars);
    }

    /**
     * 执行已解析的语法树。
     *
     * @param source  与 program 对应的编译产物原始字节（只用于异常的偏移与片段）
     * @param program 语法树
     * @param vars    模板变量作用域
     */
    public byte[] execute(byte[] source, PhpAst.Program program, ZArray vars) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(vars, "vars");
        Run run = new Run(source, vars);
        run.block(program.statements(), 0);
        return run.out.toByteArray();
    }

    private enum Flow { NORMAL, BREAK }

    /** 一次执行的上下文。 */
    private final class Run {
        private final byte[] src;
        private final ZArray vars;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private long iterations;

        Run(byte[] src, ZArray vars) {
            this.src = src;
            this.vars = vars;
        }

        private TemplateUnsupportedException fail(TemplateUnsupportedException.Kind kind, int offset) {
            return new TemplateUnsupportedException(kind, src, Math.min(offset, src.length));
        }

        // ------------------------------------------------------------ 语句

        private Flow block(List<PhpAst.Stmt> stmts, int depth) {
            for (PhpAst.Stmt s : stmts) {
                if (stmt(s, depth) == Flow.BREAK) {
                    return Flow.BREAK;
                }
            }
            return Flow.NORMAL;
        }

        /** 进入语句体（if/foreach/for/switch）：嵌套深度 +1，超出 RT-8 上限即拒绝。 */
        private Flow body(List<PhpAst.Stmt> stmts, int depth, int offset) {
            if (depth + 1 > limits.maxDepth()) {
                throw fail(TemplateUnsupportedException.Kind.LIMIT, offset);
            }
            return block(stmts, depth + 1);
        }

        private void tick(int offset) {
            iterations++;
            if (iterations > limits.maxLoopIterations()) {
                throw fail(TemplateUnsupportedException.Kind.LIMIT, offset);
            }
        }

        private void write(byte[] bytes, int offset) {
            if (out.size() + (long) bytes.length > limits.maxOutputBytes()) {
                throw fail(TemplateUnsupportedException.Kind.LIMIT, offset);
            }
            out.write(bytes, 0, bytes.length);
        }

        private Flow stmt(PhpAst.Stmt s, int depth) {
            switch (s) {
                case PhpAst.InlineHtml h -> write(h.bytes(), h.offset());
                case PhpAst.Echo e -> {
                    for (PhpAst.Expr v : e.values()) {
                        write(PhpSemantics.toBytes(eval(v)), v.offset());
                    }
                }
                case PhpAst.ExprStmt e -> eval(e.expr());
                case PhpAst.If i -> {
                    for (PhpAst.Branch b : i.branches()) {
                        if (PhpSemantics.isTrue(eval(b.condition()))) {
                            return body(b.body(), depth, b.offset());
                        }
                    }
                    if (i.elseBody() != null) {
                        return body(i.elseBody(), depth, i.offset());
                    }
                }
                case PhpAst.Foreach f -> foreachStmt(f, depth);
                case PhpAst.For f -> forStmt(f, depth);
                case PhpAst.Switch sw -> switchStmt(sw, depth);
                case PhpAst.Break b -> {
                    return Flow.BREAK;
                }
                default -> throw fail(TemplateUnsupportedException.Kind.SYNTAX, s.offset());
            }
            return Flow.NORMAL;
        }

        private void foreachStmt(PhpAst.Foreach f, int depth) {
            ZValue subject = eval(f.subject());
            if (!(subject instanceof ZArray arr)) {
                return; // PHP：Invalid argument supplied for foreach()（Warning，只进日志），不执行循环体
            }
            // 按值遍历：PHP 遍历的是数组快照，循环体改原变量不影响本次遍历
            for (Map.Entry<Object, ZValue> e : arr.copy().entries().entrySet()) {
                tick(f.offset());
                if (f.key() != null) {
                    vars.put(f.key().name(), ZArray.keyValue(e.getKey()));
                }
                assignTo(f.value(), e.getValue());
                if (body(f.body(), depth, f.offset()) == Flow.BREAK) {
                    return;
                }
            }
        }

        private void forStmt(PhpAst.For f, int depth) {
            for (PhpAst.Expr e : f.init()) {
                eval(e);
            }
            while (true) {
                ZValue cond = ZValue.TRUE;
                for (PhpAst.Expr e : f.condition()) {
                    cond = eval(e); // 逗号分隔时全部求值，以最后一个为准
                }
                if (!PhpSemantics.isTrue(cond)) {
                    return;
                }
                tick(f.offset());
                if (body(f.body(), depth, f.offset()) == Flow.BREAK) {
                    return;
                }
                for (PhpAst.Expr e : f.step()) {
                    eval(e);
                }
            }
        }

        private void switchStmt(PhpAst.Switch sw, int depth) {
            ZValue subject = eval(sw.subject());
            List<PhpAst.Case> cases = sw.cases();
            int start = -1;
            for (int i = 0; i < cases.size() && start < 0; i++) {
                PhpAst.Expr test = cases.get(i).test();
                if (test != null && PhpSemantics.looseEquals(subject, eval(test))) {
                    start = i;
                }
            }
            if (start < 0) {
                for (int i = 0; i < cases.size(); i++) {
                    if (cases.get(i).test() == null) {
                        start = i;
                        break;
                    }
                }
            }
            if (start < 0) {
                return;
            }
            for (int i = start; i < cases.size(); i++) { // 贯穿到 break
                if (body(cases.get(i).body(), depth, cases.get(i).offset()) == Flow.BREAK) {
                    return;
                }
            }
        }

        // ------------------------------------------------------------ 表达式

        private ZValue eval(PhpAst.Expr e) {
            return switch (e) {
                case PhpAst.NullLiteral n -> ZValue.NULL;
                case PhpAst.BoolLiteral b -> ZValue.of(b.value());
                case PhpAst.IntLiteral i -> ZValue.of(i.value());
                case PhpAst.FloatLiteral f -> ZValue.of(f.value());
                case PhpAst.StringLiteral s -> ZValue.bytes(s.bytes());
                case PhpAst.BareConst c -> ZValue.of(c.name()); // BARE-4
                case PhpAst.Variable v -> readVar(v.name());
                case PhpAst.Index ix -> PhpSemantics.fetchDim(eval(ix.base()), eval(ix.index()));
                case PhpAst.Interpolated in -> interpolate(in);
                case PhpAst.ArrayLiteral a -> arrayLiteral(a);
                case PhpAst.Assign a -> assign(a);
                case PhpAst.Increment inc -> increment(inc);
                case PhpAst.Unary u -> unary(u);
                case PhpAst.Binary b -> binary(b);
                case PhpAst.Ternary t -> ternary(t);
                case PhpAst.Isset is -> isset(is);
                case PhpAst.Empty em -> ZValue.of(PhpSemantics.empty(quiet(em.operand())));
                case PhpAst.FunctionCall fc -> call(fc);
                case PhpAst.ModelCall mc -> modelCall(mc);
                case PhpAst.InstanceOf io -> instanceOf(io);
                case PhpAst.IsEmptyCall ie -> throw fail(TemplateUnsupportedException.Kind.OBJECT, ie.offset());
                default -> throw fail(TemplateUnsupportedException.Kind.SYNTAX, e.offset());
            };
        }

        private ZValue readVar(String name) {
            ZValue v = vars.get(name);
            return v == null ? ZValue.NULL : v; // RT-5
        }

        /** isset/empty 上下文的取值（PHP {@code BP_VAR_IS}）：取不到返回 Java null。 */
        private ZValue quiet(PhpAst.Expr e) {
            if (e instanceof PhpAst.Variable v) {
                return vars.get(v.name());
            }
            if (e instanceof PhpAst.Index ix) {
                ZValue base = quiet(ix.base());
                ZValue idx = eval(ix.index());
                if (base instanceof ZArray a) {
                    return a.get(idx);
                }
                if (base instanceof ZValue.Str) {
                    ZValue r = PhpSemantics.fetchDimQuiet(base, idx);
                    return r instanceof ZValue.Null ? null : r;
                }
                return null;
            }
            return eval(e);
        }

        private ZValue isset(PhpAst.Isset is) {
            for (PhpAst.Expr t : is.targets()) {
                if (!PhpSemantics.isset(quiet(t))) {
                    return ZValue.FALSE;
                }
            }
            return ZValue.TRUE;
        }

        private ZValue interpolate(PhpAst.Interpolated in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            for (PhpAst.Expr p : in.parts()) {
                b.writeBytes(PhpSemantics.toBytes(eval(p)));
            }
            return ZValue.bytes(b.toByteArray());
        }

        private ZValue arrayLiteral(PhpAst.ArrayLiteral a) {
            ZArray arr = new ZArray();
            for (PhpAst.ArrayItem item : a.items()) {
                if (item.key() == null) {
                    arr.append(snapshot(eval(item.value())));
                } else {
                    ZValue k = eval(item.key());
                    ZValue v = snapshot(eval(item.value()));
                    if (!arr.put(k, v)) {
                        throw fail(TemplateUnsupportedException.Kind.RUNTIME, item.offset()); // 数组作键：Illegal offset type
                    }
                }
            }
            return arr;
        }

        private ZValue ternary(PhpAst.Ternary t) {
            ZValue c = eval(t.condition());
            if (PhpSemantics.isTrue(c)) {
                return t.then() == null ? c : eval(t.then());
            }
            return eval(t.otherwise());
        }

        private ZValue call(PhpAst.FunctionCall fc) {
            if (!functions.isRegistered(fc.name())) {
                throw fail(TemplateUnsupportedException.Kind.FUNCTION, fc.offset()); // RT-7，不回退为裸名
            }
            ZValue r = functions.call(fc.name(), snapshots(fc.args()));
            return Objects.requireNonNull(r, "function result");
        }

        private ZValue modelCall(PhpAst.ModelCall mc) {
            // MC-4：实参按值快照；MC-6/QG-5：处理器抛出的异常不捕获、不包装
            ZValue r = models.call(mc.normalizedModel(), mc.method(), snapshots(mc.args()));
            return Objects.requireNonNull(r, "model call result");
        }

        private List<ZValue> snapshots(List<PhpAst.Expr> args) {
            List<ZValue> vs = new ArrayList<>(args.size());
            for (PhpAst.Expr a : args) {
                vs.add(snapshot(eval(a)));
            }
            return List.copyOf(vs);
        }

        /** OP-T7：主语只可能是数组或标量（RT-2 封闭类型），两者都不是登记类的实例。 */
        private ZValue instanceOf(PhpAst.InstanceOf io) {
            ZValue subject = eval(io.subject());
            boolean isInstance = switch (subject) {
                case ZArray a -> false;
                case ZValue.Null n -> false;
                case ZValue.Bool b -> false;
                case ZValue.Int i -> false;
                case ZValue.Float f -> false;
                case ZValue.Str s -> false;
            };
            return ZValue.of(isInstance);
        }

        // ------------------------------------------------------------ 运算

        private ZValue unary(PhpAst.Unary u) {
            ZValue v = eval(u.operand());
            return switch (u.op()) {
                case "!" -> ZValue.of(!PhpSemantics.isTrue(v));
                case "-" -> arith("*", v, ZValue.of(-1L), u.offset());
                case "+" -> arith("*", v, ZValue.of(1L), u.offset());
                default -> throw fail(TemplateUnsupportedException.Kind.SYNTAX, u.offset());
            };
        }

        private ZValue binary(PhpAst.Binary b) {
            String op = b.op();
            switch (op) {
                case "&&", "and" -> {
                    return ZValue.of(PhpSemantics.isTrue(eval(b.left())) && PhpSemantics.isTrue(eval(b.right())));
                }
                case "||", "or" -> {
                    return ZValue.of(PhpSemantics.isTrue(eval(b.left())) || PhpSemantics.isTrue(eval(b.right())));
                }
                default -> {
                    // 其余运算两侧都求值（左先右后）
                }
            }
            ZValue l = eval(b.left());
            ZValue r = eval(b.right());
            return switch (op) {
                case "xor" -> ZValue.of(PhpSemantics.isTrue(l) ^ PhpSemantics.isTrue(r));
                case "." -> concat(l, r);
                case "==" -> ZValue.of(PhpSemantics.looseEquals(l, r));
                case "!=", "<>" -> ZValue.of(!PhpSemantics.looseEquals(l, r));
                case "===" -> ZValue.of(PhpSemantics.identical(l, r));
                case "!==" -> ZValue.of(!PhpSemantics.identical(l, r));
                case "<" -> ZValue.of(PhpSemantics.isSmaller(l, r));
                case "<=" -> ZValue.of(PhpSemantics.isSmallerOrEqual(l, r));
                case ">" -> ZValue.of(PhpSemantics.isSmaller(r, l));
                case ">=" -> ZValue.of(PhpSemantics.isSmallerOrEqual(r, l));
                default -> arith(op, l, r, b.offset());
            };
        }

        private ZValue concat(ZValue l, ZValue r) {
            byte[] a = PhpSemantics.toBytes(l);
            byte[] c = PhpSemantics.toBytes(r);
            byte[] out = new byte[a.length + c.length];
            System.arraycopy(a, 0, out, 0, a.length);
            System.arraycopy(c, 0, out, a.length, c.length);
            return ZValue.bytes(out);
        }

        /** PHP 7.3 算术（{@code add/sub/mul/div/mod_function}）：整数溢出转浮点；数组参与算术是致命错误，按不支持拒绝。 */
        private ZValue arith(String op, ZValue l, ZValue r, int offset) {
            if (op.equals("+") && l instanceof ZArray la && r instanceof ZArray ra) {
                ZArray u = la.copy(); // 数组并集：左侧已有的键保留
                ra.entries().forEach((k, v) -> {
                    if (!u.entries().containsKey(k)) {
                        u.put(ZArray.keyValue(k), snapshot(v));
                    }
                });
                return u;
            }
            if (l instanceof ZArray || r instanceof ZArray) {
                throw fail(TemplateUnsupportedException.Kind.RUNTIME, offset); // Unsupported operand types
            }
            if (op.equals("%")) {
                long a = PhpSemantics.toLong(l);
                long d = PhpSemantics.toLong(r);
                if (d == 0) {
                    throw fail(TemplateUnsupportedException.Kind.RUNTIME, offset); // DivisionByZeroError: Modulo by zero
                }
                return ZValue.of(d == -1 ? 0L : a % d);
            }
            ZValue a = toNumber(l);
            ZValue c = toNumber(r);
            if (a instanceof ZValue.Int ai && c instanceof ZValue.Int ci) {
                long x = ai.value();
                long y = ci.value();
                switch (op) {
                    case "+" -> {
                        long s = x + y;
                        return ((x ^ s) & (y ^ s)) < 0 ? ZValue.of((double) x + (double) y) : ZValue.of(s);
                    }
                    case "-" -> {
                        long s = x - y;
                        return ((x ^ y) & (x ^ s)) < 0 ? ZValue.of((double) x - (double) y) : ZValue.of(s);
                    }
                    case "*" -> {
                        long hi = Math.multiplyHigh(x, y);
                        long lo = x * y;
                        return (hi == 0 && lo >= 0) || (hi == -1 && lo < 0) ? ZValue.of(lo) : ZValue.of((double) x * (double) y);
                    }
                    case "/" -> {
                        if (y == 0) {
                            return ZValue.of(divByZero(x));
                        }
                        if (x % y == 0 && !(x == Long.MIN_VALUE && y == -1)) {
                            return ZValue.of(x / y);
                        }
                        return ZValue.of((double) x / (double) y);
                    }
                    default -> throw fail(TemplateUnsupportedException.Kind.SYNTAX, offset);
                }
            }
            double x = toDouble(a);
            double y = toDouble(c);
            return switch (op) {
                case "+" -> ZValue.of(x + y);
                case "-" -> ZValue.of(x - y);
                case "*" -> ZValue.of(x * y);
                case "/" -> ZValue.of(y == 0 ? divByZero(x) : x / y);
                default -> throw fail(TemplateUnsupportedException.Kind.SYNTAX, offset);
            };
        }

        /** PHP 7：{@code /} 除以 0 写 Warning（只进日志）并得到 INF/-INF/NAN。 */
        private double divByZero(double x) {
            return x == 0 || Double.isNaN(x) ? Double.NaN : (x > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
        }

        private double toDouble(ZValue n) {
            return n instanceof ZValue.Int i ? (double) i.value() : ((ZValue.Float) n).value();
        }

        /** 算术操作数转数值（{@code zendi_smart_str…}/{@code _zval_get_number}）：字符串取数字前缀，无前缀为 0。 */
        private ZValue toNumber(ZValue v) {
            return switch (v) {
                case ZValue.Null n -> ZValue.of(0L);
                case ZValue.Bool b -> ZValue.of(b.value() ? 1L : 0L);
                case ZValue.Int i -> i;
                case ZValue.Float f -> f;
                case ZValue.Str s -> {
                    PhpSemantics.Numeric num = PhpSemantics.parseNumeric(s);
                    yield num == null ? ZValue.of(0L) : num.value();
                }
                case ZArray a -> throw new IllegalStateException("array operand");
            };
        }

        // ------------------------------------------------------------ 赋值

        /** 数组按值语义取快照（写时复制的等价做法）；标量不可变，原样返回。 */
        private ZValue snapshot(ZValue v) {
            return v instanceof ZArray a ? a.copy() : v;
        }

        private ZValue assign(PhpAst.Assign a) {
            if (a.op().equals("=")) {
                if (a.target() instanceof PhpAst.Variable v) {
                    ZValue value = snapshot(eval(a.value()));
                    vars.put(v.name(), value);
                    return value;
                }
                // $a[k1][k2] = v：PHP 先求下标，再求右值，最后写入
                List<ZValue> keys = indexKeys(a.target());
                ZValue value = snapshot(eval(a.value()));
                writeDim(rootVar(a.target()), keys, value, a.offset());
                return value;
            }
            // .=
            List<ZValue> keys = indexKeys(a.target());
            ZValue current = readPath(rootVar(a.target()), keys);
            ZValue value = concat(current, eval(a.value()));
            store(a.target(), keys, value, a.offset());
            return value;
        }

        private ZValue increment(PhpAst.Increment inc) {
            List<ZValue> keys = indexKeys(inc.target());
            ZValue old = readPath(rootVar(inc.target()), keys);
            ZValue next = incremented(old);
            store(inc.target(), keys, next, inc.offset());
            return inc.prefix() ? next : old;
        }

        private void store(PhpAst.Expr target, List<ZValue> keys, ZValue value, int offset) {
            if (keys.isEmpty()) {
                vars.put(rootVar(target), value);
            } else {
                writeDim(rootVar(target), keys, value, offset);
            }
        }

        /** PHP 7.3 {@code increment_function}：null→1，int 溢出转浮点，数字串按数值，其他字符串按 Perl 规则，bool/数组不变。 */
        private ZValue incremented(ZValue v) {
            return switch (v) {
                case ZValue.Null n -> ZValue.of(1L);
                case ZValue.Int i -> i.value() == Long.MAX_VALUE ? ZValue.of((double) Long.MAX_VALUE + 1) : ZValue.of(i.value() + 1);
                case ZValue.Float f -> ZValue.of(f.value() + 1);
                case ZValue.Bool b -> b;
                case ZArray a -> a;
                case ZValue.Str s -> {
                    if (s.length() == 0) {
                        yield ZValue.of("1");
                    }
                    PhpSemantics.Numeric num = PhpSemantics.parseNumeric(s);
                    if (num != null && num.wellFormed()) {
                        yield num.isDouble() ? ZValue.of(num.doubleValue() + 1) : incremented(ZValue.of(num.longValue()));
                    }
                    yield ZValue.bytes(perlIncrement(s.bytes()));
                }
            };
        }

        /** {@code increment_string}：从末尾按 a-z/A-Z/0-9 进位，最高位进位时在前面补一位。 */
        private byte[] perlIncrement(byte[] b) {
            int i = b.length - 1;
            int last = 0; // 0 无进位类别，1 小写，2 大写，3 数字
            while (i >= 0) {
                byte c = b[i];
                if (c >= 'a' && c <= 'z') {
                    last = 1;
                    if (c == 'z') {
                        b[i] = 'a';
                    } else {
                        b[i]++;
                        return b;
                    }
                } else if (c >= 'A' && c <= 'Z') {
                    last = 2;
                    if (c == 'Z') {
                        b[i] = 'A';
                    } else {
                        b[i]++;
                        return b;
                    }
                } else if (c >= '0' && c <= '9') {
                    last = 3;
                    if (c == '9') {
                        b[i] = '0';
                    } else {
                        b[i]++;
                        return b;
                    }
                } else {
                    return b; // 遇到非字母数字即停止，不再进位
                }
                i--;
            }
            byte lead = switch (last) {
                case 1 -> 'a';
                case 2 -> 'A';
                default -> '1';
            };
            byte[] r = new byte[b.length + 1];
            r[0] = lead;
            System.arraycopy(b, 0, r, 1, b.length);
            return r;
        }

        private void assignTo(PhpAst.Variable v, ZValue value) {
            vars.put(v.name(), snapshot(value));
        }

        private String rootVar(PhpAst.Expr lvalue) {
            PhpAst.Expr e = lvalue;
            while (e instanceof PhpAst.Index ix) {
                e = ix.base();
            }
            return ((PhpAst.Variable) e).name();
        }

        /** 下标链从外到内的键值（按源码从左到右求值）。 */
        private List<ZValue> indexKeys(PhpAst.Expr lvalue) {
            List<PhpAst.Expr> chain = new ArrayList<>();
            PhpAst.Expr e = lvalue;
            while (e instanceof PhpAst.Index ix) {
                chain.add(0, ix.index());
                e = ix.base();
            }
            List<ZValue> keys = new ArrayList<>(chain.size());
            for (PhpAst.Expr k : chain) {
                keys.add(eval(k));
            }
            return keys;
        }

        private ZValue readPath(String root, List<ZValue> keys) {
            ZValue v = readVar(root);
            for (ZValue k : keys) {
                v = PhpSemantics.fetchDim(v, k);
            }
            return v;
        }

        /**
         * {@code $root[k1]…[kn] = value}：途经的 null/未定义/false 自动建数组（PHP 7.3 同）；
         * 途经非空字符串属字符串下标写入，按不支持拒绝；途经其他标量 PHP 只写 Warning 不写入。
         */
        private void writeDim(String root, List<ZValue> keys, ZValue value, int offset) {
            ZValue rootValue = vars.get(root);
            ZArray container = containerFor(rootValue, offset);
            if (container == null) {
                return;
            }
            if (container != rootValue) {
                vars.put(root, container);
            }
            for (int i = 0; i < keys.size() - 1; i++) {
                ZValue child = container.get(keys.get(i));
                ZArray next = containerFor(child, offset);
                if (next == null) {
                    return;
                }
                if (next != child && !container.put(keys.get(i), next)) {
                    throw fail(TemplateUnsupportedException.Kind.RUNTIME, offset); // Illegal offset type
                }
                container = next;
            }
            if (!container.put(keys.get(keys.size() - 1), value)) {
                throw fail(TemplateUnsupportedException.Kind.RUNTIME, offset);
            }
        }

        /** 写入时取容器：数组原样（作用域独占，可就地改），null/未定义/false 新建，字符串拒绝，其他标量返回 null（不写入）。 */
        private ZArray containerFor(ZValue v, int offset) {
            if (v instanceof ZArray a) {
                return a;
            }
            if (v == null || v instanceof ZValue.Null || v instanceof ZValue.Bool b && !b.value()) {
                return new ZArray();
            }
            if (v instanceof ZValue.Str) {
                throw fail(TemplateUnsupportedException.Kind.RUNTIME, offset); // 字符串下标写入不在 RT-1
            }
            return null; // Cannot use a scalar value as an array（Warning）
        }
    }
}
