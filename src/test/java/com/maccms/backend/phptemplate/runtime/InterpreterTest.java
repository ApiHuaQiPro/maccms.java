package com.maccms.backend.phptemplate.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.maccms.backend.phptemplate.runtime.TemplateUnsupportedException.Kind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * C-19 解释器（技术设计 v15 §6.5、§6.5.1、§6.21；V-11 {@code InterpreterTest}）。全部函数、模型都是注入的桩，
 * 不实现业务函数、不连库。
 */
class InterpreterTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final String C = "\\think\\Collection";
    private static final String P = "\\think\\Paginator";

    /** 测试用函数桩：按名登记，记录每次调用的实参。 */
    static final class StubFunctions implements FunctionDispatcher {
        final Map<String, Function<List<ZValue>, ZValue>> impls = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        final List<List<ZValue>> args = new ArrayList<>();

        StubFunctions with(String name, Function<List<ZValue>, ZValue> f) {
            impls.put(name, f);
            return this;
        }

        @Override
        public boolean isRegistered(String name) {
            return impls.containsKey(name);
        }

        @Override
        public ZValue call(String name, List<ZValue> a) {
            calls.add(name);
            args.add(a);
            return impls.get(name).apply(a);
        }
    }

    static StubFunctions stdStubs() {
        return new StubFunctions()
                .with("is_array", a -> ZValue.of(a.get(0) instanceof ZArray))
                .with("f", a -> ZValue.of("F"))
                .with("strtoupper", a -> ZValue.of(((ZValue.Str) PhpSemantics.toStr(a.get(0))).utf8().toUpperCase()));
    }

    static final ModelCallHandler NO_MODELS = (m, method, a) -> {
        throw new AssertionError("unexpected model call");
    };

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String run(String src) {
        return run(src, new ZArray());
    }

    private static String run(String src, ZArray vars) {
        return new String(new Interpreter(stdStubs(), NO_MODELS, ExecutionLimits.DEFAULTS).render(b(src), vars),
                StandardCharsets.UTF_8);
    }

    private static TemplateUnsupportedException fails(String src, Interpreter in) {
        return catchThrowableOfType(TemplateUnsupportedException.class, () -> in.render(b(src), new ZArray()));
    }

    // ================================================================== AC-307C-1 值语义与执行

    @Nested
    class Execution {

        @Test
        void literalSegmentsAndEchoKeepRawBytesAndTpl5EatsOneNewline() {
            assertThat(run("a<?php echo 1; ?>\nb")).isEqualTo("a1b");
            assertThat(run("a<?php echo 1; ?>\r\nb")).isEqualTo("a1b");
            assertThat(run("a<?php echo 1; ?>\n\nb")).isEqualTo("a1\nb"); // 只吃一个
            assertThat(run("a<?php echo 1; ?>\r\r\nb")).isEqualTo("a1\r\r\nb"); // 单独 CR 不吃
            assertThat(run("x\r\ny")).isEqualTo("x\r\ny"); // 字面段不归一化
            assertThat(run("<?php echo 'a', 2, true, null, false, 1.5; ?>")).isEqualTo("a211.5");
        }

        @Test
        void variablesAndArrayAssignmentUseValueSemantics() {
            ZArray vars = new ZArray();
            assertThat(run("<?php $a = ['x' => 1]; $b = $a; $b['x'] = 2; echo $a['x'], $b['x']; ?>", vars))
                    .isEqualTo("12");
            assertThat(run("<?php $w = []; $w['k']['j'] = 'v'; $w['k']['n'] = 3; echo $w['k']['j'], $w['k']['n']; ?>"))
                    .isEqualTo("v3");
            // 未定义变量 / null / false 上的下标写入自动建数组（PHP 7.3）
            assertThat(run("<?php $u['a'] = 1; $n = null; $n['b'] = 2; $f = false; $f['c'] = 3; echo $u['a'], $n['b'], $f['c']; ?>"))
                    .isEqualTo("123");
            // 标量（非字符串）上的下标写入：PHP 只写 Warning、不写入
            assertThat(run("<?php $i = 5; $i['a'] = 1; echo $i; ?>")).isEqualTo("5");
            // 字符串下标写入不在子集，显式拒绝
            assertThat(fails("<?php $s = 'abc'; $s['a'] = 1; ?>", interp()).kind()).isEqualTo(Kind.RUNTIME);
            // 宿主传入的数组被模板改写时，作用域里的值变化，宿主原对象之外的副本不受影响
            ZArray host = new ZArray();
            ZArray inner = new ZArray();
            inner.put("k", ZValue.of(1L));
            host.put("obj", inner);
            run("<?php $copy = $obj; $copy['k'] = 9; ?>", host);
            assertThat(inner.get("k")).isEqualTo(ZValue.of(1L));
        }

        @Test
        void concatAssignAndIncrement() {
            assertThat(run("<?php $s = 'a'; $s .= 'b'; $s .= 1; echo $s; ?>")).isEqualTo("ab1");
            assertThat(run("<?php $x['k'] = 'a'; $x['k'] .= 'z'; $x['n'] .= 'q'; echo $x['k'], $x['n']; ?>")).isEqualTo("azq");
            assertThat(run("<?php $i = 1; echo $i++, $i, ++$i, $i; ?>")).isEqualTo("1233");
            assertThat(run("<?php $n++; echo $n; $s = 'a'; $s++; echo $s; $z = 'Az'; $z++; echo $z; $y = 'zz'; $y++; echo $y;"
                    + " $d = 'a9'; $d++; echo $d; $e = ''; $e++; echo $e; $num = '5'; $num++; echo $num; ?>"))
                    .isEqualTo("1bBaaaab0" + "16");
            assertThat(run("<?php $m = 9223372036854775807; $m++; echo $m; ?>")).isEqualTo("9.2233720368548E+18");
            assertThat(run("<?php $c['i'] = 1; $c['i']++; ++$c['i']; echo $c['i']; ?>")).isEqualTo("3");
        }

        @Test
        void branchesLoopsAndSwitch() {
            String src = "<?php if($a == 1): ?>one<?php elseif($a == 2): ?>two<?php else: ?>other<?php endif; ?>";
            for (long i = 1; i <= 3; i++) {
                ZArray v = new ZArray();
                v.put("a", ZValue.of(i));
                assertThat(run(src, v)).isEqualTo(i == 1 ? "one" : i == 2 ? "two" : "other");
            }
            assertThat(run("<?php $l = ['a' => 1, 'b' => 2, 5 => 3]; foreach($l as $k => $v){ echo $k, '=', $v, ';'; } ?>"))
                    .isEqualTo("a=1;b=2;5=3;");
            assertThat(run("<?php foreach([1,2,3,4] as $v){ if($v == 3){ break; } echo $v; } echo $v; ?>")).isEqualTo("123");
            // 遍历的是快照：循环体改原数组不影响本次遍历
            assertThat(run("<?php $l = [1,2]; foreach($l as $v){ $l['x'] = 9; echo $v; } echo $l['x']; ?>"))
                    .isEqualTo("129");
            assertThat(run("<?php foreach($undefined as $v){ echo 'x'; } foreach('str' as $v){ echo 'y'; } echo 'ok'; ?>"))
                    .isEqualTo("ok");
            assertThat(run("<?php for($i=0;$i<3;$i++){ echo $i; } ?>")).isEqualTo("012");
            assertThat(run("<?php for($i=0;;$i++){ if($i>2){ break; } echo $i; } ?>")).isEqualTo("012");
            String sw = "<?php switch($s){ case 1: echo 'a'; case '2': echo 'b'; break; default: echo 'd'; case 3: echo 'c'; } ?>";
            Map<ZValue, String> expect = Map.of(ZValue.of(1L), "ab", ZValue.of("2"), "b", ZValue.of(2.0), "b",
                    ZValue.of(3L), "c", ZValue.of(9L), "dc", ZValue.of("1"), "ab");
            expect.forEach((in, out) -> {
                ZArray v = new ZArray();
                v.put("s", in);
                assertThat(run(sw, v)).as("switch %s", in).isEqualTo(out);
            });
        }

        @Test
        void stringsInterpolationAndUndefinedValues() {
            assertThat(run("<?php $n = 'W'; echo \"a$n\"; ?>")).isEqualTo("aW");
            assertThat(run("<?php echo 'a\\'b', \"c\\td\\\\\"; ?>")).isEqualTo("a'bc\td\\");
            assertThat(run("<?php echo $nope, $nope['a']['b'], '|'; $s = 'abc'; echo $s[0], $s[-1], $s[5], $s['1']; ?>"))
                    .isEqualTo("|acb");
            assertThat(run("<?php echo isset($a), isset($a['x']), empty($a), empty($a['x']) ? 'E' : 'N'; ?>")).isEqualTo("1E");
            assertThat(run("<?php $a = ['x' => null, 'y' => 0]; echo isset($a['x']) ? 1 : 0, isset($a['y']) ? 1 : 0,"
                    + " empty($a['y']) ? 1 : 0; ?>")).isEqualTo("011");
        }

        @Test
        void operatorsFollowPhp73() {
            assertThat(run("<?php echo 1 + 2, '|', '5' + '3', '|', '1.5' + 1, '|', 7 / 2, '|', 6 / 3, '|', 7 % 3, '|', -7 % 3; ?>"))
                    .isEqualTo("3|8|2.5|3.5|2|1|-1");
            assertThat(run("<?php echo 0.1 + 0.2, '|', 9223372036854775807 + 1, '|', -'5', '|', 'abc' * 2, '|', 1 / 0, '|', -1 / 0; ?>"))
                    .isEqualTo("0.3|9.2233720368548E+18|-5|0|INF|-INF");
            assertThat(run("<?php echo 'abc' == 0 ? 'T' : 'F', '1e1' == '10' ? 'T' : 'F', null == false ? 'T' : 'F',"
                    + " '1' === 1 ? 'T' : 'F', 2 > 1, 1 <> 1 ? 'T' : 'F', 'a' < 'b', 3 >= 3, 2 <= 1 ? 'T' : 'F'; ?>"))
                    .isEqualTo("TTTF1F11F");
            assertThat(run("<?php echo (true xor true) ? 'T' : 'F', (true xor false) ? 'T' : 'F', !0, 0 ?: 'd', 'v' ?: 'd'; ?>"))
                    .isEqualTo("FT1dv");
            assertThat(run("<?php $u = ['a' => 1] + ['a' => 2, 'b' => 3]; echo $u['a'], $u['b']; ?>")).isEqualTo("13");
            assertThat(fails("<?php echo [1] + 1; ?>", interp()).kind()).isEqualTo(Kind.RUNTIME);
            assertThat(fails("<?php echo 1 % 0; ?>", interp())).satisfies(e -> {
                assertThat(e.kind()).isEqualTo(Kind.RUNTIME);
                assertThat(e.offset()).isEqualTo(11);
            });
        }

        @Test
        void logicalOperatorsShortCircuitBeforeCallingFunctions() {
            StubFunctions fns = stdStubs();
            Interpreter in = new Interpreter(fns, NO_MODELS, ExecutionLimits.DEFAULTS);
            in.render(b("<?php $r = false && f(); $r = true || f(); $r = false and f(); $r = true or f(); $r = 1 ? 2 : f(); ?>"),
                    new ZArray());
            assertThat(fns.calls).isEmpty();
            in.render(b("<?php $r = true && f(); $r = false || f(); $r = false xor f(); ?>"), new ZArray());
            assertThat(fns.calls).containsExactly("f", "f", "f");
        }

        @Test
        void functionsDispatchOnlyThroughInjectedTableWithSnapshotArguments() {
            StubFunctions fns = new StubFunctions().with("touch", a -> {
                ((ZArray) a.get(0)).put("k", ZValue.of("changed"));
                return ZValue.of("r");
            });
            ZArray vars = new ZArray();
            byte[] out = new Interpreter(fns, NO_MODELS, ExecutionLimits.DEFAULTS)
                    .render(b("<?php $a = ['k' => 'orig']; echo touch($a), $a['k']; ?>"), vars);
            assertThat(new String(out, StandardCharsets.UTF_8)).isEqualTo("rorig");
            // 未登记函数：解析期拒绝（RT-7）；绕过解析直接给语法树时执行期同样拒绝
            assertThat(fails("<?php echo nope(1); ?>", interp()).kind()).isEqualTo(Kind.FUNCTION);
            byte[] src = b("<?php echo nope(); ?>");
            PhpAst.Program p = new PhpAst.Program(List.of(new PhpAst.Echo(6,
                    List.of(new PhpAst.FunctionCall(11, "nope", List.of())))));
            TemplateUnsupportedException e = catchThrowableOfType(TemplateUnsupportedException.class,
                    () -> interp().execute(src, p, new ZArray()));
            assertThat(e.kind()).isEqualTo(Kind.FUNCTION);
            assertThat(e.offset()).isEqualTo(11);
        }
    }

    private static Interpreter interp() {
        return new Interpreter(stdStubs(), NO_MODELS, ExecutionLimits.DEFAULTS);
    }

    private static Interpreter limited(long loops, long bytes, int depth) {
        return new Interpreter(stdStubs(), NO_MODELS, new ExecutionLimits(loops, bytes, depth));
    }

    // ================================================================== AC-307C-2 RT-8

    @Nested
    class Limits {

        @Test
        void defaultsAreTheDesignValues() {
            assertThat(ExecutionLimits.DEFAULTS).isEqualTo(new ExecutionLimits(1_000_000L, 33_554_432L, 64));
            assertThatThrownBy(() -> new ExecutionLimits(-1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ExecutionLimits(1, -1, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ExecutionLimits(1, 1, -1)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void loopIterationsAreCountedAcrossAllLoopsOfOneRender() {
            assertThat(new String(limited(3, 100, 8).render(b("<?php for($i=0;$i<3;$i++){ echo $i; } ?>"), new ZArray()),
                    StandardCharsets.UTF_8)).isEqualTo("012");
            TemplateUnsupportedException e = fails("<?php for($i=0;$i<4;$i++){ echo $i; } ?>", limited(3, 100, 8));
            assertThat(e.kind()).isEqualTo(Kind.LIMIT);
            assertThat(e.offset()).isEqualTo(6);
            assertThat(fails("<?php foreach([1,2] as $v){} foreach([1,2] as $v){} ?>", limited(3, 100, 8)).kind())
                    .isEqualTo(Kind.LIMIT);
            // 每次渲染重新计数
            Interpreter in = limited(2, 100, 8);
            in.render(b("<?php foreach([1,2] as $v){} ?>"), new ZArray());
            in.render(b("<?php foreach([1,2] as $v){} ?>"), new ZArray());
        }

        @Test
        void defaultLoopLimitIsExactlyOneMillion() {
            interp().render(b("<?php for($i=0;$i<1000000;$i++){} ?>"), new ZArray());
            assertThat(fails("<?php for($i=0;$i<1000001;$i++){} ?>", interp()).kind()).isEqualTo(Kind.LIMIT);
            assertThat(fails("<?php for(;;){} ?>", interp()).kind()).isEqualTo(Kind.LIMIT);
        }

        @Test
        void outputBytesIncludeLiteralSegments() {
            assertThat(limited(10, 5, 8).render(b("ab<?php echo 'cde'; ?>"), new ZArray())).hasSize(5);
            TemplateUnsupportedException e = fails("ab<?php echo 'cdef'; ?>", limited(10, 5, 8));
            assertThat(e.kind()).isEqualTo(Kind.LIMIT);
            assertThat(e.offset()).isEqualTo(13);
            assertThat(fails("abcdef", limited(10, 5, 8)).kind()).isEqualTo(Kind.LIMIT);
        }

        @Test
        void defaultOutputLimitIsExactly32MiB() {
            String kib = "x".repeat(1024);
            byte[] out = interp().render(b("<?php for($i=0;$i<32768;$i++){ echo '" + kib + "'; } ?>"), new ZArray());
            assertThat(out).hasSize(32 * 1024 * 1024);
            assertThat(fails("<?php for($i=0;$i<32768;$i++){ echo '" + kib + "'; } echo 'y'; ?>", interp()).kind())
                    .isEqualTo(Kind.LIMIT);
        }

        @Test
        void nestingDepthIsBounded() {
            assertThat(limited(10, 100, 2).render(b("<?php if(1){ if(1){ echo 'ok'; } } ?>"), new ZArray())).hasSize(2);
            assertThat(fails("<?php if(1){ if(1){ if(1){ echo 'no'; } } } ?>", limited(10, 100, 2)).kind()).isEqualTo(Kind.LIMIT);
            assertThat(fails("<?php foreach([1] as $a){ foreach([1] as $b){ switch(1){ case 1: echo 1; } } } ?>",
                    limited(10, 100, 2)).kind()).isEqualTo(Kind.LIMIT);
            // 默认 64 层：64 层通过，65 层拒绝；未进入的分支不计深度
            assertThat(interp().render(b(nestedIfs(64)), new ZArray())).hasSize(1);
            TemplateUnsupportedException e = fails(nestedIfs(65), interp());
            assertThat(e.kind()).isEqualTo(Kind.LIMIT);
            interp().render(b("<?php if(0){" + "if(1){".repeat(70) + "}".repeat(70) + "} ?>"), new ZArray());
        }

        private String nestedIfs(int n) {
            return "<?php " + "if(1){".repeat(n) + "echo 1;" + "}".repeat(n) + " ?>";
        }
    }

    // ================================================================== AC-307C-3 MC-1/2/4/6

    record ModelCallRecord(String model, String method, List<ZValue> args) {
    }

    @Nested
    class ModelCalls {

        @Test
        void dispatchesOnlyThroughInjectedHandlerWithNormalizedModelName() {
            List<ModelCallRecord> seen = new ArrayList<>();
            ModelCallHandler h = (m, method, a) -> {
                seen.add(new ModelCallRecord(m, method, a));
                ZArray r = new ZArray();
                r.put("code", ZValue.of(1L));
                return r;
            };
            byte[] out = new Interpreter(stdStubs(), h, ExecutionLimits.DEFAULTS).render(b(
                    "<?php $w['art_id'] = 3; $r = model('art')->infoData($w, '*', 0); echo $r['code'];"
                            + " $l = model(\"Vod\")->listCacheData($__TAG__); $x = model('user_group')->listCacheData('a'); ?>"),
                    new ZArray());
            assertThat(new String(out, StandardCharsets.UTF_8)).isEqualTo("1");
            assertThat(seen).extracting(ModelCallRecord::model).containsExactly("Art", "Vod", "UserGroup");
            assertThat(seen).extracting(ModelCallRecord::method).containsExactly("infoData", "listCacheData", "listCacheData");
            assertThat(seen.get(0).args()).hasSize(3);
            assertThat(seen.get(0).args().get(1)).isEqualTo(ZValue.of("*"));
            assertThat(seen.get(0).args().get(2)).isEqualTo(ZValue.of(0L));
            assertThat(seen.get(1).args()).containsExactly(ZValue.NULL); // 未定义变量按 null 传
        }

        @Test
        void argumentsAreValueSnapshotsBothWays() {
            List<ZValue> received = new ArrayList<>();
            ZArray kept = new ZArray();
            kept.put("v", ZValue.of("handler"));
            ModelCallHandler h = (m, method, a) -> {
                received.add(a.get(0));
                ((ZArray) a.get(0)).put("art_id", ZValue.of(99L)); // 处理器改实参，不影响模板变量
                return kept;
            };
            ZArray vars = new ZArray();
            byte[] out = new Interpreter(stdStubs(), h, ExecutionLimits.DEFAULTS).render(b(
                    "<?php $where['art_id'] = 1; $pre = model('art')->infoData($where); $where['art_id'] = 2;"
                            + " $pre['v'] = 'tpl'; echo $where['art_id'], $pre['v']; ?>"), vars);
            assertThat(new String(out, StandardCharsets.UTF_8)).isEqualTo("2tpl");
            // 第一次调用收到的实参是调用当时的快照（之后模板改 $where 不影响；这里看到的是处理器自己写的 99）
            assertThat(((ZArray) received.get(0)).get("art_id")).isEqualTo(ZValue.of(99L));
            assertThat(kept.get("v")).as("模板改返回值不影响处理器持有的对象").isEqualTo(ZValue.of("handler"));
        }

        @Test
        void laterTemplateWritesDoNotReachEarlierSnapshot() {
            List<ZValue> received = new ArrayList<>();
            ModelCallHandler h = (m, method, a) -> {
                received.addAll(a);
                return ZValue.NULL;
            };
            new Interpreter(stdStubs(), h, ExecutionLimits.DEFAULTS).render(b(
                    "<?php $where['art_id'] = 1; $a = model('art')->infoData($where); $where['art_id'] = 2;"
                            + " $b = model('art')->infoData($where); ?>"), new ZArray());
            assertThat(((ZArray) received.get(0)).get("art_id")).isEqualTo(ZValue.of(1L));
            assertThat(((ZArray) received.get(1)).get("art_id")).isEqualTo(ZValue.of(2L));
        }

        @Test
        void handlerExceptionsPropagateUnchanged() {
            RuntimeException apiLike = new IllegalStateException("service unavailable");
            Interpreter in = new Interpreter(stdStubs(), (m, method, a) -> {
                throw apiLike;
            }, ExecutionLimits.DEFAULTS);
            assertThatThrownBy(() -> in.render(b("<?php $r = model('art')->infoData($w); ?>"), new ZArray()))
                    .isSameAs(apiLike);
            TemplateUnsupportedException mc6 = new TemplateUnsupportedException(Kind.MODEL_CALL, b("x"), 0);
            Interpreter rejecting = new Interpreter(stdStubs(), (m, method, a) -> {
                throw mc6;
            }, ExecutionLimits.DEFAULTS);
            assertThatThrownBy(() -> rejecting.render(b("<?php $r = model('art')->delData($w); ?>"), new ZArray()))
                    .isSameAs(mc6);
        }

        @Test
        void nonRegisteredModelFormsAreRejectedBeforeAnyHandlerCall() {
            for (String src : List.of("<?php $r = model($m)->infoData($w); ?>", "<?php $r = model('art')->x->y(); ?>",
                    "<?php $r = model('art'); ?>", "<?php $r = model('art')->a()->b(); ?>")) {
                assertThat(fails(src, interp()).kind()).as(src).isIn(Kind.MODEL_CALL, Kind.OBJECT);
            }
        }
    }

    // ================================================================== AC-307C-4 BARE-4、BV-1～6

    /** 在根日志器上挂 ListAppender，执行 body 后断言没有 WARN 及以上事件（BARE-4，V-11 SUG-1）。 */
    static void assertNoWarnLogs(Runnable body) {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            body.run();
        } finally {
            root.detachAppender(logs);
        }
        assertThat(logs.list).filteredOn(ev -> ev.getLevel().isGreaterOrEqual(Level.WARN)).isEmpty();
    }

    @Nested
    class BareIdentifiers {

        @Test
        void bv1AndBv3RegisteredColorIsTheStringColorEverywhere() {
            assertNoWarnLogs(() -> {
                ZArray vars = new ZArray();
                StubFunctions fns = stdStubs();
                byte[] out = new Interpreter(fns, NO_MODELS, ExecutionLimits.DEFAULTS).render(b(
                        "<?php $c = color; $t = true; echo color.'x', '|', color == 'color' ? 'T' : 'F', '|', f(color); ?>"),
                        vars);
                assertThat(new String(out, StandardCharsets.UTF_8)).isEqualTo("colorx|T|F");
                assertThat(vars.get("c")).isInstanceOf(ZValue.Str.class).isEqualTo(ZValue.of("color"));
                assertThat(vars.get("t")).isEqualTo(ZValue.TRUE);
                assertThat(fns.args.get(0)).containsExactly(ZValue.of("color"));
            });
        }

        @Test
        void bv1GoldenGroupGap1BareIdentifierFromRealVectors() throws IOException {
            tools.jackson.databind.JsonNode g;
            try (java.io.InputStream in = InterpreterTest.class.getResourceAsStream("/phptemplate/semantics/php73-vectors.json")) {
                g = tools.jackson.databind.json.JsonMapper.builder().build().readTree(in).get("gap1_bare_identifier");
            }
            assertThat(g.size()).isEqualTo(2);
            ZArray vars = new ZArray();
            assertNoWarnLogs(() -> run("<?php $color = color; $defined = true; ?>", vars));
            tools.jackson.databind.JsonNode color = g.get(0);
            assertThat(color.get("label").asString()).isEqualTo("color");
            assertThat(vars.get("color") instanceof ZValue.Str).isEqualTo(color.get("is_string").asBoolean());
            assertThat(((ZValue.Str) vars.get("color")).utf8()).isEqualTo(color.get("string").asString());
            tools.jackson.databind.JsonNode def = g.get(1);
            assertThat(def.get("label").asString()).isEqualTo("defined-true");
            assertThat(vars.get("defined")).isEqualTo(ZValue.of(def.get("bool").asBoolean()));
        }

        @ParameterizedTest
        @ValueSource(strings = {"true", "TRUE", "True", "false", "FALSE", "null", "NULL", "Null"})
        void bv2LiteralNamesAreLiterals(String name) {
            ZArray vars = new ZArray();
            run("<?php $v = " + name + "; ?>", vars);
            ZValue expected = switch (name.toLowerCase()) {
                case "true" -> ZValue.TRUE;
                case "false" -> ZValue.FALSE;
                default -> ZValue.NULL;
            };
            assertThat(vars.get("v")).isEqualTo(expected);
        }

        @Test
        void bv4FunctionCallPathIsSeparateFromBareName() {
            TemplateUnsupportedException e = fails("<?php echo color(1); ?>", interp());
            assertThat(e.kind()).isEqualTo(Kind.FUNCTION);
            assertThat(e.offset()).isEqualTo(11);
            assertThat(run("<?php echo $color === null ? 'null' : 'set'; ?>")).isEqualTo("null");
        }

        @ParameterizedTest
        @ValueSource(strings = {"COLOR", "Color", "PHP_EOL", "E_ALL", "MAC_PATH", "\\color", "a\\color", "X::Y", "__LINE__"})
        void bv5UnregisteredNamesAreRejectedBeforeExecution(String name) {
            StubFunctions fns = stdStubs();
            TemplateUnsupportedException e = catchThrowableOfType(TemplateUnsupportedException.class,
                    () -> new Interpreter(fns, NO_MODELS, ExecutionLimits.DEFAULTS)
                            .render(b("<?php echo " + name + "; ?>"), new ZArray()));
            assertThat(e.kind()).isEqualTo(Kind.BARE_IDENTIFIER);
            assertThat(e.offset()).isEqualTo(11);
            assertThat(e.snippet()).contains(name);
            assertThat(fns.calls).isEmpty();
        }

        @Test
        void bv5HostFunctionsAndUnexecutedBranches() {
            for (String fn : List.of("constant", "defined")) {
                TemplateUnsupportedException e = fails("<?php echo " + fn + "('color'); ?>", interp());
                assertThat(e.kind()).isEqualTo(Kind.FUNCTION);
                assertThat(e.offset()).isEqualTo(11);
                assertThat(e.snippet()).contains(fn);
            }
            StubFunctions fns = stdStubs();
            TemplateUnsupportedException e = catchThrowableOfType(TemplateUnsupportedException.class,
                    () -> new Interpreter(fns, NO_MODELS, ExecutionLimits.DEFAULTS)
                            .render(b("<?php if(false){ echo PHP_EOL; } ?>"), new ZArray()));
            assertThat(e.kind()).isEqualTo(Kind.BARE_IDENTIFIER);
            assertThat(e.offset()).isEqualTo(22);
        }

        // ---- BV-6：真实黄金第 15 行（S15-1～S15-4）----

        private static final Path COMPILE_GOLDEN = Path.of("src", "test", "resources", "phptemplate", "compile-golden");
        private static final String P15_HEX = "202020202020202020202020e4b88ae6aca1e799bbe5bd95e697b6e997b4efbc9a";
        private static final String B15 = "<?php echo mac_day($obj['user_last_login_time'],color); ?>";

        @ParameterizedTest
        @ValueSource(strings = {"default_pc", "default_wap"})
        void bv6RealGoldenLine15(String pkg) throws IOException, NoSuchAlgorithmException {
            byte[] file = Files.readAllBytes(COMPILE_GOLDEN.resolve(pkg).resolve("html/user/ajax_info.html"));
            // BV-6a 原行
            assertThat(indexOf(file, (byte) '\r')).isEqualTo(-1);
            int start = 0;
            for (int line = 1; line < 15; line++) {
                start = indexOf(file, start, (byte) '\n') + 1;
            }
            int end = indexOf(file, start, (byte) '\n');
            byte[] r15 = Arrays.copyOfRange(file, start, end);
            assertThat(r15).hasSize(91);
            assertThat(HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(r15)))
                    .isEqualTo("2fca28e786dbfb27b6468b87fd6863991678aa4c63bacff7ae8de26931d59d9b");
            assertThat(file[end]).isEqualTo((byte) '\n');
            // BV-6b 代码块（EX-15）
            String r15s = new String(r15, StandardCharsets.ISO_8859_1);
            int s = r15s.indexOf("<?php");
            int e = r15s.indexOf("?>", s) + 2;
            assertThat(r15s.indexOf("<?php", s + 1)).isEqualTo(-1);
            assertThat(r15s.indexOf("?>", e)).isEqualTo(-1);
            assertThat(s).isEqualTo(33);
            assertThat(e).isEqualTo(91);
            byte[] p15 = Arrays.copyOfRange(r15, 0, s);
            byte[] b15 = Arrays.copyOfRange(r15, s, e);
            assertThat(HEX.formatHex(p15)).isEqualTo(P15_HEX);
            assertThat(b15).isEqualTo(b(B15));

            // BV-6c 代码块执行；BV-6d 整行 + LF 执行
            assertThat(HEX.formatHex(execute(b15))).isEqualTo("53545542");
            byte[] lineWithLf = Arrays.copyOfRange(file, start, end + 1);
            assertThat(lineWithLf).hasSize(92);
            assertThat(HEX.formatHex(execute(lineWithLf))).isEqualTo(P15_HEX + "53545542");
        }

        /** 宿主构造 obj（字符串键 "user_last_login_time" ⇒ int 1793496600），桩 mac_day 返回 STUB 并记录实参。 */
        private byte[] execute(byte[] src) {
            List<List<ZValue>> calls = new ArrayList<>();
            StubFunctions fns = new StubFunctions().with("mac_day", a -> {
                calls.add(a);
                return ZValue.of("STUB");
            });
            ZArray obj = new ZArray();
            obj.put("user_last_login_time", ZValue.of(1793496600L));
            ZArray vars = new ZArray();
            vars.put("obj", obj);
            byte[][] out = new byte[1][];
            assertNoWarnLogs(() -> out[0] = new Interpreter(fns, NO_MODELS, ExecutionLimits.DEFAULTS).render(src, vars));
            assertThat(calls).hasSize(1);
            assertThat(calls.get(0)).hasSize(2);
            assertThat(calls.get(0).get(0)).isInstanceOf(ZValue.Int.class).isEqualTo(ZValue.of(1793496600L));
            assertThat(calls.get(0).get(1)).isInstanceOf(ZValue.Str.class);
            assertThat(HEX.formatHex(((ZValue.Str) calls.get(0).get(1)).bytes())).isEqualTo("636f6c6f72");
            return out[0];
        }

        @Test
        void unquotedHostKeyInTemplateSourceIsABareNameAndRejected() {
            assertThat(fails("<?php $obj = [user_last_login_time => 1]; ?>", interp()).kind()).isEqualTo(Kind.BARE_IDENTIFIER);
        }
    }

    private static int indexOf(byte[] hay, byte needle) {
        return indexOf(hay, 0, needle);
    }

    private static int indexOf(byte[] hay, int from, byte needle) {
        for (int i = from; i < hay.length; i++) {
            if (hay[i] == needle) {
                return i;
            }
        }
        return -1;
    }

    // ================================================================== AC-307C-5 OP-T7

    /** PHP 7.3.33 探针所用的 12 类代表输入（架构评审 OP-T7 约束）：null、两个布尔、整数、浮点、空/非空字符串、空/非空数组。 */
    static List<ZValue> twelveInputs() {
        ZArray assoc = new ZArray();
        assoc.put("k", ZValue.of("v"));
        return List.of(ZValue.NULL, ZValue.TRUE, ZValue.FALSE, ZValue.of(0L), ZValue.of(7L), ZValue.of(0.0), ZValue.of(1.5),
                ZValue.EMPTY_STRING, ZValue.of("0"), ZValue.of("abc"), new ZArray(), assoc);
    }

    @Nested
    class ObjectForms {

        private static final String FORM_A = "<?php if(is_array($s) || $s instanceof " + C + " || $s instanceof " + P
                + "): ?>A<?php else: ?>N<?php endif; ?>";
        private static final String FORM_B = "<?php if(empty($s) || (($s instanceof " + C + " || $s instanceof " + P
                + " ) && $s->isEmpty())): ?>E<?php else: ?>F<?php endif; ?>";

        @Test
        void fixedFormsOverEveryRepresentativeInputMatchPhp() {
            List<ZValue> inputs = twelveInputs();
            assertThat(inputs).hasSize(12);
            assertThat(inputs.stream().map(ZValue::typeName).distinct())
                    .containsExactlyInAnyOrder("NULL", "boolean", "integer", "double", "string", "array");
            for (ZValue in : inputs) {
                ZArray vars = new ZArray();
                vars.put("s", in);
                boolean isArray = in instanceof ZArray;
                assertThat(run(FORM_A, vars)).as("A %s", in).isEqualTo(isArray ? "A" : "N");
                // 形态 B：empty 为真走 E；否则 instanceof 两项都为 false，&& 短路，isEmpty 不执行
                assertThat(run(FORM_B, vars)).as("B %s", in).isEqualTo(PhpSemantics.empty(in) ? "E" : "F");
            }
            // 未定义主语同样走 RT-5 null
            assertThat(run(FORM_A)).isEqualTo("N");
            assertThat(run(FORM_B)).isEqualTo("E");
        }

        @Test
        void instanceOfEvaluatesItsSubjectAndIsFalseForEveryValueType() {
            StubFunctions fns = stdStubs();
            Interpreter in = new Interpreter(fns, NO_MODELS, ExecutionLimits.DEFAULTS);
            for (ZValue v : twelveInputs()) {
                ZArray vars = new ZArray();
                vars.put("s", v);
                byte[] src = b("<?php echo $s instanceof \\think\\Collection ? 'T' : 'F'; ?>");
                PhpAst.Program p = new PhpAst.Program(List.of(new PhpAst.Echo(6, List.of(
                        new PhpAst.InstanceOf(11, new PhpAst.Variable(11, "s"), C)))));
                assertThat(in.execute(src, p, vars)).as("%s", v).isEqualTo(b(""));
            }
            // 主语确实被求值：下标里的函数调用发生了（instanceof 不是不看输入就返回 false）
            ZArray vars = new ZArray();
            vars.put("s", ZValue.of("abc"));
            byte[] src = b("<?php echo $s[f()] instanceof X; ?>");
            PhpAst.Expr index = new PhpAst.Index(11, new PhpAst.Variable(11, "s"), new PhpAst.FunctionCall(14, "f", List.of()));
            PhpAst.Program p = new PhpAst.Program(List.of(new PhpAst.Echo(6, List.of(new PhpAst.InstanceOf(11, index, P)))));
            assertThat(in.execute(src, p, vars)).isEqualTo(b(""));
            assertThat(fns.calls).containsExactly("f");
        }

        @Test
        void reachingIsEmptyIsAnExplicitRejection() {
            byte[] src = b("<?php echo $s->isEmpty(); ?>");
            PhpAst.Program p = new PhpAst.Program(List.of(new PhpAst.Echo(6, List.of(
                    new PhpAst.IsEmptyCall(11, new PhpAst.Variable(11, "s"))))));
            TemplateUnsupportedException e = catchThrowableOfType(TemplateUnsupportedException.class,
                    () -> interp().execute(src, p, new ZArray()));
            assertThat(e.kind()).isEqualTo(Kind.OBJECT);
            assertThat(e.offset()).isEqualTo(11);
            assertThat(e.snippet()).startsWith("$s->isEmpty()");
            // 形态 B 若 && 左侧为真（只能由人为语法树造出）就会到达 isEmpty，同样显式拒绝
            PhpAst.Variable s = new PhpAst.Variable(11, "s");
            PhpAst.Program forced = new PhpAst.Program(List.of(new PhpAst.Echo(6, List.of(
                    new PhpAst.Binary(11, "&&", new PhpAst.BoolLiteral(11, true), new PhpAst.IsEmptyCall(11, s))))));
            assertThat(catchThrowableOfType(TemplateUnsupportedException.class,
                    () -> interp().execute(src, forced, new ZArray())).kind()).isEqualTo(Kind.OBJECT);
        }

        @Test
        void normalizedGoldenClassNamesAndOtherObjectSyntaxAreRejected() {
            for (String src : List.of(
                    "<?php if(is_array($s) || $s instanceof /think/Collection || $s instanceof /think/Paginator): endif; ?>",
                    "<?php if($s instanceof \\think\\Model): endif; ?>",
                    "<?php echo $s->isEmpty(); ?>",
                    "<?php echo $s->name; ?>",
                    "<?php if($s instanceof " + C + "): endif; ?>")) {
                assertThat(fails(src, interp())).as(src).isNotNull();
            }
            assertThat(fails("<?php echo $s->isEmpty(); ?>", interp()).kind()).isEqualTo(Kind.OBJECT);
        }
    }
}
