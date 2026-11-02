package com.maccms.backend.phptemplate.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.phptemplate.runtime.PhpAst.ArrayLiteral;
import com.maccms.backend.phptemplate.runtime.PhpAst.Assign;
import com.maccms.backend.phptemplate.runtime.PhpAst.BareConst;
import com.maccms.backend.phptemplate.runtime.PhpAst.Binary;
import com.maccms.backend.phptemplate.runtime.PhpAst.BoolLiteral;
import com.maccms.backend.phptemplate.runtime.PhpAst.Echo;
import com.maccms.backend.phptemplate.runtime.PhpAst.Expr;
import com.maccms.backend.phptemplate.runtime.PhpAst.ExprStmt;
import com.maccms.backend.phptemplate.runtime.PhpAst.FunctionCall;
import com.maccms.backend.phptemplate.runtime.PhpAst.If;
import com.maccms.backend.phptemplate.runtime.PhpAst.Index;
import com.maccms.backend.phptemplate.runtime.PhpAst.InlineHtml;
import com.maccms.backend.phptemplate.runtime.PhpAst.ModelCall;
import com.maccms.backend.phptemplate.runtime.PhpAst.NullLiteral;
import com.maccms.backend.phptemplate.runtime.PhpAst.Stmt;
import com.maccms.backend.phptemplate.runtime.PhpAst.Ternary;
import com.maccms.backend.phptemplate.runtime.PhpAst.Variable;
import com.maccms.backend.phptemplate.runtime.TemplateUnsupportedException.Kind;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** C-18 语法解析（技术设计 v15 RT-1、BARE-1～BARE-5、MC-1/MC-2、OP-T7；AC-307B-1～3）。只验证语法树，不执行。 */
class PhpParserTest {

    /** 测试用 RT-7 白名单桩（真实白名单归 C-19/C-27）；不含 constant/defined/color。 */
    private static final Predicate<String> FUNCTIONS =
            Set.of("is_array", "count", "mac_day", "f", "in_array", "date", "call_user_func")::contains;
    private static final Predicate<String> NO_DYNAMIC =
            Set.of("is_array", "count", "mac_day", "f", "in_array", "date")::contains;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static List<Stmt> parse(String src) {
        return PhpParser.parse(b(src), FUNCTIONS).statements();
    }

    /** 单条 {@code <?php echo X; ?>} 的表达式。 */
    private static Expr echoExpr(String expr) {
        List<Stmt> s = parse("<?php echo " + expr + "; ?>");
        assertThat(s).hasSize(1);
        return ((Echo) s.get(0)).values().get(0);
    }

    private static Expr stmtExpr(String code) {
        List<Stmt> s = parse("<?php " + code + " ?>");
        assertThat(s).hasSize(1);
        return ((ExprStmt) s.get(0)).expr();
    }

    private static TemplateUnsupportedException rejects(String src, Predicate<String> functions) {
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> PhpParser.parse(b(src), functions));
        assertThat(t).as(src).isInstanceOf(TemplateUnsupportedException.class);
        return (TemplateUnsupportedException) t;
    }

    private static void rejects(String src, Kind kind, int offset) {
        TemplateUnsupportedException e = rejects(src, NO_DYNAMIC);
        assertThat(e.kind()).as(src).isEqualTo(kind);
        assertThat(e.offset()).as(src).isEqualTo(offset);
    }

    @Nested
    class Statements {

        @Test
        void literalSegmentsAndEchoKeepOffsetsAndCloseTagActsAsSemicolon() {
            List<Stmt> s = parse("a<?php echo $x, 'y' ?>b");
            assertThat(s).hasSize(3);
            assertThat(s.get(0)).isEqualTo(new InlineHtml(0, b("a")));
            Echo echo = (Echo) s.get(1);
            assertThat(echo.offset()).isEqualTo(7);
            assertThat(echo.values()).hasSize(2);
            assertThat(echo.values().get(0)).isEqualTo(new Variable(12, "x"));
            assertThat(s.get(2)).isEqualTo(new InlineHtml(22, b("b")));
        }

        @Test
        void alternativeIfElseifElseSpansSegments() {
            List<Stmt> s = parse("<?php if($a == 1): ?>A<?php elseif($a == 2): ?>B<?php else: ?>C<?php endif; ?>");
            If i = (If) s.get(0);
            assertThat(i.branches()).hasSize(2);
            assertThat(i.branches().get(0).body()).containsExactly(new InlineHtml(21, b("A")));
            assertThat(i.branches().get(1).body()).containsExactly(new InlineHtml(47, b("B")));
            assertThat(i.elseBody()).containsExactly(new InlineHtml(62, b("C")));
        }

        @Test
        void braceIfWithElseIfAndElse() {
            If i = (If) parse("<?php if($a){ echo 1; } else if($b) echo 2; else { echo 3; } ?>").get(0);
            assertThat(i.branches()).hasSize(1);
            If nested = (If) i.elseBody().get(0);
            assertThat(nested.branches().get(0).body()).hasSize(1);
            assertThat(nested.elseBody()).hasSize(1);
            assertThat(((If) parse("<?php if($a){ } ?>").get(0)).elseBody()).isNull();
        }

        @Test
        void foreachForAndSwitchFromCompilerOutput() {
            List<Stmt> s = parse("<?php if(is_array($list) || $list instanceof \\think\\Collection || $list instanceof "
                    + "\\think\\Paginator): $i = 0; $__LIST__ = $list;if( count($__LIST__)==0 ) : echo \"\" ;else: "
                    + "foreach($__LIST__ as $key=>$vo): $mod = ($i % 2 );++$i;?>x<?php endforeach; endif; else: echo \"\" ;endif; ?>");
            assertThat(s).hasSize(1);
            PhpAst.Foreach f = (PhpAst.Foreach) ((If) ((If) s.get(0)).branches().get(0).body().get(2)).elseBody().get(0);
            assertThat(f.key().name()).isEqualTo("key");
            assertThat(f.value().name()).isEqualTo("vo");
            assertThat(f.body()).hasSize(3);

            PhpAst.For loop = (PhpAst.For) parse("<?php for($i=0;$i<3;$i++){ echo $i; break; } ?>").get(0);
            assertThat(loop.init()).hasSize(1);
            assertThat(loop.condition()).hasSize(1);
            assertThat(loop.step()).hasSize(1);
            assertThat(((PhpAst.For) parse("<?php for(;;): endfor; ?>").get(0)).condition()).isEmpty();

            PhpAst.Switch sw = (PhpAst.Switch) parse("<?php switch($vo['type_id']): case \"1\": ?>dy<?php break; "
                    + "case \"2\": ?>ds<?php break; default: echo 0; endswitch; ?>").get(0);
            assertThat(sw.cases()).hasSize(3);
            assertThat(sw.cases().get(2).test()).isNull();
            assertThat(sw.cases().get(0).body()).hasSize(2);
            assertThat(((PhpAst.Switch) parse("<?php switch($a){ ?><?php case 1: break; } ?>").get(0)).cases())
                    .hasSize(1);
        }

        @Test
        void phpBlockFromArtDetailParsesWithCommentsIndexAssignmentAndModelCalls() {
            String src = "<?php\n            $where=[];\n            $where['art_status'] = ['eq',1];\n"
                    + "            // 上一篇\n            $where['art_id'] = ['lt',$obj['art_id']];\n"
                    + "            $pre_info = model('art')->infoData($where,'*',1);\n            ?>";
            List<Stmt> s = parse(src);
            assertThat(s).hasSize(4);
            Assign idx = (Assign) ((ExprStmt) s.get(1)).expr();
            assertThat(idx.target()).isInstanceOf(Index.class);
            assertThat(idx.value()).isInstanceOf(ArrayLiteral.class);
            ModelCall mc = (ModelCall) ((Assign) ((ExprStmt) s.get(3)).expr()).value();
            assertThat(mc.model()).isEqualTo("art");
            assertThat(mc.normalizedModel()).isEqualTo("Art");
            assertThat(mc.method()).isEqualTo("infoData");
            assertThat(mc.args()).hasSize(3);
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "<?php break; ?>|6",
            "<?php foreach($a as $v){ break 2; } ?>|30",
            "<?php switch($a): default: default: endswitch; ?>|28",
            "<?php switch($a): ?> x <?php case 1: endswitch; ?>|20",
            "<?php switch($a): echo 1; case 1: endswitch; ?>|19",
            "<?php if($a): else if($b): endif; endif; ?>|23",
            "<?php if($a){ } elseif($b): endif; ?>|26",
            "<?php foreach($a as &$v){} ?>|20",
            "<?php foreach($a as $k => $v['x']){} ?>|31",
            "<?php echo 1|10",
            "<?php continue; ?>|6",
            "<?php while(1){} ?>|6",
            "<?php print 1; ?>|6",
            "<?php exit; ?>|6",
            "<?php return; ?>|6",
            "<?php function f(){} ?>|6",
            "<?php class A{} ?>|6",
            "<?php include 'a.php'; ?>|6",
            "<?php eval('1'); ?>|6",
            "<?php $x = new A; ?>|11",
            "<?php list($a) = $b; ?>|6",
            "<?php unset($a); ?>|6",
            "<?php global $a; ?>|6",
        })
        void unsupportedStatementsAreSyntaxErrors(String src, int offset) {
            rejects(src, Kind.SYNTAX, offset);
        }
    }

    @Nested
    class Expressions {

        @Test
        void precedenceAndAssociativityFollowPhp73() {
            Binary sum = (Binary) echoExpr("1 + 2 * 3");
            assertThat(sum.op()).isEqualTo("+");
            assertThat(((Binary) sum.right()).op()).isEqualTo("*");
            // PHP 7.3：. 与 + - 同级左结合
            Binary concat = (Binary) echoExpr("'a' . 1 + 2");
            assertThat(concat.op()).isEqualTo("+");
            assertThat(((Binary) concat.left()).op()).isEqualTo(".");
            Binary and = (Binary) echoExpr("!$a && $b || $c");
            assertThat(and.op()).isEqualTo("||");
            assertThat(((Binary) and.left()).left()).isInstanceOf(PhpAst.Unary.class);
            Binary low = (Binary) stmtExpr("$a = $b and $c;");
            assertThat(low.op()).isEqualTo("and");
            assertThat(low.left()).isInstanceOf(Assign.class);
            Binary cmp = (Binary) echoExpr("$a == 1 + 2");
            assertThat(cmp.right()).isInstanceOf(Binary.class);
            // PHP 7.3 三元左结合
            Ternary t = (Ternary) echoExpr("$a ? 1 : 2 ? 3 : 4");
            assertThat(t.condition()).isInstanceOf(Ternary.class);
            Ternary elvis = (Ternary) echoExpr("$a ?: 'x'");
            assertThat(elvis.then()).isNull();
            assertThat(((Binary) echoExpr("$a or $b xor $c")).op()).isEqualTo("or");
            assertThat(((Binary) echoExpr("(1 + 2) * 3")).op()).isEqualTo("*");
        }

        @Test
        void assignmentsIndexesIncrementsArraysAndInterpolation() {
            Assign concatAssign = (Assign) stmtExpr("$s .= 'x';");
            assertThat(concatAssign.op()).isEqualTo(".=");
            Assign chain = (Assign) stmtExpr("$a = $b = 1;");
            assertThat(chain.value()).isInstanceOf(Assign.class);
            Assign deep = (Assign) stmtExpr("$a['x'][0] = 1;");
            assertThat(((Index) deep.target()).base()).isInstanceOf(Index.class);
            assertThat(((PhpAst.Increment) stmtExpr("++$i;")).prefix()).isTrue();
            assertThat(((PhpAst.Increment) stmtExpr("$i++;")).prefix()).isFalse();

            ArrayLiteral arr = (ArrayLiteral) echoExpr("[1, 'k' => 2, ]");
            assertThat(arr.items()).hasSize(2);
            assertThat(arr.items().get(1).key()).isInstanceOf(PhpAst.StringLiteral.class);
            assertThat(((ArrayLiteral) echoExpr("array(1, array())")).items()).hasSize(2);
            assertThat(((ArrayLiteral) echoExpr("[]")).items()).isEmpty();

            PhpAst.Interpolated in = (PhpAst.Interpolated) echoExpr("\"a$b c\"");
            assertThat(in.parts()).hasSize(3);
            assertThat(in.parts().get(1)).isEqualTo(new Variable(13, "b"));

            assertThat(((PhpAst.Isset) echoExpr("isset($a, $b['x'])")).targets()).hasSize(2);
            assertThat(((PhpAst.Empty) echoExpr("empty($a['x'])")).operand()).isInstanceOf(Index.class);
            assertThat(((PhpAst.Unary) echoExpr("-1")).op()).isEqualTo("-");
            assertThat(echoExpr("1.5")).isEqualTo(new PhpAst.FloatLiteral(11, 1.5));
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "$a == $b == $c|20",
            "$a < $b < $c|19",
            "$a ?? 1|14",
            "$a & 1|14",
            "$a | 1|14",
            "$a ^ 1|14",
            "$a << 1|14",
            "$a <=> $b|14",
            "~$a|11",
            "(int)$a|11",
            "@f()|11",
            "--$a|11",
            "$a--|13",
            "$a[]|14",
            "$$a|11",
            "${'a'}|11",
            "$f()|13",
            "'abc'[0]|16",
            "f()[0]|14",
            "($a)['x']|15",
            "[&$a]|12",
            "[1,,2]|14",
            "[...$a]|12",
            "f(...$a)|13",
            "f(1,)|15",
            "isset(1)|17",
            "`ls`|11",
            "$a{0}|13",
            "array($a => &$b)|20",
        })
        void unsupportedExpressionFormsAreRejected(String expr, int offset) {
            TemplateUnsupportedException e = rejects("<?php echo " + expr + "; ?>", NO_DYNAMIC);
            assertThat(e.offset()).as(expr).isEqualTo(offset);
            assertThat(e.kind()).as(expr).isIn(Kind.SYNTAX, Kind.TOKEN);
        }

        @ParameterizedTest
        @ValueSource(strings = {"$a += 1;", "$a -= 1;", "$a *= 2;", "$a = &$b;", "[$a, $b] = $c;"})
        void unsupportedAssignmentsAreRejected(String code) {
            assertThat(rejects("<?php " + code + " ?>", NO_DYNAMIC).kind()).isEqualTo(Kind.SYNTAX);
        }
    }

    @Nested
    class BareIdentifiersAndFunctions {

        /** BV-1/BV-2：大小写不限的 true/false/null 是字面量，color 是登记裸名。 */
        @Test
        void literalsAndRegisteredBareName() {
            for (String t : List.of("true", "TRUE", "True")) {
                assertThat(echoExpr(t)).isEqualTo(new BoolLiteral(11, true));
            }
            for (String f : List.of("false", "FALSE")) {
                assertThat(echoExpr(f)).isEqualTo(new BoolLiteral(11, false));
            }
            for (String n : List.of("null", "NULL", "Null")) {
                assertThat(echoExpr(n)).isEqualTo(new NullLiteral(11));
            }
            assertThat(echoExpr("color")).isEqualTo(new BareConst(11, "color"));
            // BV-3：位置无关
            assertThat(((Binary) echoExpr("color.'x'")).left()).isEqualTo(new BareConst(11, "color"));
            assertThat(((FunctionCall) echoExpr("f(color)")).args()).containsExactly(new BareConst(13, "color"));
            FunctionCall md = (FunctionCall) echoExpr("mac_day($obj['user_last_login_time'],color)");
            assertThat(md.args().get(1)).isEqualTo(new BareConst(48, "color"));
        }

        /** BV-4：color(1) 是函数调用（白名单外 → function，不回退为裸名）；$color 是变量。 */
        @Test
        void functionCallVariableAndBareNameAreDistinct() {
            rejects("<?php echo color(1); ?>", Kind.FUNCTION, 11);
            assertThat(echoExpr("$color")).isEqualTo(new Variable(11, "color"));
            assertThat(echoExpr("f(1)")).isInstanceOf(FunctionCall.class);
        }

        /** BV-5：9 个裸名样例 bare_identifier @11，constant/defined 走 RT-7（function @11），片段含名字。 */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "COLOR|bare_identifier", "Color|bare_identifier", "PHP_EOL|bare_identifier", "E_ALL|bare_identifier",
            "MAC_PATH|bare_identifier", "\\color|bare_identifier", "a\\color|bare_identifier", "X::Y|bare_identifier",
            "__LINE__|bare_identifier", "constant('color')|function", "defined('color')|function",
        })
        void bv5RejectionsCarryKindOffsetAndSnippet(String name, String kind) {
            TemplateUnsupportedException e = rejects("<?php echo " + name + "; ?>", NO_DYNAMIC);
            assertThat(e.kind().code()).isEqualTo(kind);
            assertThat(e.offset()).isEqualTo(11);
            assertThat(e.snippet()).startsWith(name);
        }

        @Test
        void unexecutedBranchesAreRejectedAtParseTime() {
            rejects("<?php if(false){ echo PHP_EOL; } ?>", Kind.BARE_IDENTIFIER, 22);
            rejects("<?php if(true): else: echo user_last_login_time; endif; ?>", Kind.BARE_IDENTIFIER, 27);
            rejects("<?php if(false): echo constant('x'); endif; ?>", Kind.FUNCTION, 22);
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "PHP_VERSION|bare_identifier|11", "DIRECTORY_SEPARATOR|bare_identifier|11", "THINK_PATH|bare_identifier|11",
            "APP_PATH|bare_identifier|11", "ROOT_PATH|bare_identifier|11", "IS_AJAX|bare_identifier|11",
            "__FILE__|bare_identifier|11", "__DIR__|bare_identifier|11", "__CLASS__|bare_identifier|11",
            "self::Y|bare_identifier|11", "static::Y|bare_identifier|11", "X::y()|bare_identifier|11",
            "\\f(1)|function|11", "a\\f(1)|function|11", "getenv('A')|function|11", "ini_get('a')|function|11",
            "phpversion()|function|11", "get_defined_constants()|function|11", "define('A',1)|function|11",
            "call_user_func('f')|function|11", "Model('art')->infoData()|function|11",
        })
        void otherConstantsNamespacesClassesMagicAndHostFunctionsAreRejected(String expr, String kind, int offset) {
            TemplateUnsupportedException e = rejects("<?php echo " + expr + "; ?>", NO_DYNAMIC);
            assertThat(e.kind().code()).as(expr).isEqualTo(kind);
            assertThat(e.offset()).as(expr).isEqualTo(offset);
        }
    }

    @Nested
    class ModelCalls {

        @Test
        void mc2NormalizesLikeLoaderParseName() {
            assertThat(new ModelCall(0, "art", "x", List.of()).normalizedModel()).isEqualTo("Art");
            assertThat(new ModelCall(0, "Vod", "x", List.of()).normalizedModel()).isEqualTo("Vod");
            assertThat(new ModelCall(0, "user_group", "x", List.of()).normalizedModel()).isEqualTo("UserGroup");
            assertThat(new ModelCall(0, "a_1", "x", List.of()).normalizedModel()).isEqualTo("A_1");
            ModelCall dq = (ModelCall) echoExpr("model(\"vod\")->listCacheData($p)");
            assertThat(dq.normalizedModel()).isEqualTo("Vod");
            assertThat(dq.method()).isEqualTo("listCacheData");
            assertThat(dq.offset()).isEqualTo(11);
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "model($m)->infoData()|11",
            "model('art')|11",
            "model()->infoData()|11",
            "model('a','b')->infoData()|11",
            "model(\"a$b\")->infoData()|11",
            "model('art')->info|11",
            "model('art')::infoData()|11",
            "model('art')->infoData()->x()|35",
            "model('art')->infoData()['code']|35",
            "model('art')->$m()|11",
        })
        void mc1RejectsOtherModelForms(String expr, int offset) {
            rejects("<?php echo " + expr + "; ?>", Kind.MODEL_CALL, offset);
        }
    }

    @Nested
    class ObjectForms {

        private static final String C = "\\think\\Collection";
        private static final String P = "\\think\\Paginator";

        @Test
        void bothRegisteredFixedFormsParse() {
            If a = (If) parse("<?php if(is_array($vo['child']) || $vo['child'] instanceof " + C
                    + " || $vo['child'] instanceof " + P + "): endif; ?>").get(0);
            Binary or = (Binary) a.branches().get(0).condition();
            PhpAst.InstanceOf pag = (PhpAst.InstanceOf) or.right();
            assertThat(pag.className()).isEqualTo(P);
            assertThat(pag.subject()).isInstanceOf(Index.class);

            If bForm = (If) parse("<?php if(empty($obj['type_extend']['area']) || (($obj['type_extend']['area'] instanceof "
                    + C + " || $obj['type_extend']['area'] instanceof " + P
                    + " ) && $obj['type_extend']['area']->isEmpty())): ?>x<?php endif; ?>").get(0);
            Binary and = (Binary) ((Binary) bForm.branches().get(0).condition()).right();
            assertThat(and.right()).isInstanceOf(PhpAst.IsEmptyCall.class);
            // elseif 条件同样认领
            parse("<?php if($x): elseif(is_array($a) || $a instanceof " + C + " || $a instanceof " + P + "): endif; ?>");
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            // 其他 / 非完整 / 动态类名、归一化黄金写法：拒绝点是类名记号
            "if($a instanceof \\think\\Model): endif;|22",
            "if($a instanceof Collection): endif;|22",
            "if($a instanceof think\\Collection): endif;|22",
            "if($a instanceof \\Think\\Collection): endif;|22",
            "if($a instanceof $b): endif;|22",
            "if($a instanceof /think/Collection): endif;|22",
            "if(1 instanceof \\think\\Collection): endif;|11",
            // 登记类名但不在固定形态里：拒绝点是该判定的首字节
            "if($a instanceof \\think\\Collection): endif;|9",
            "echo $a instanceof \\think\\Collection;|11",
            "echo is_array($a) || $a instanceof \\think\\Collection || $a instanceof \\think\\Paginator;|27",
            "if(is_array($a) || $b instanceof \\think\\Collection || $b instanceof \\think\\Paginator): endif;|25",
            "if(is_array($a) || $a instanceof \\think\\Paginator || $a instanceof \\think\\Collection): endif;|25",
            "if(count($a) || $a instanceof \\think\\Collection || $a instanceof \\think\\Paginator): endif;|22",
            "if(is_array($a) || $a instanceof \\think\\Collection): endif;|25",
            "if(empty($a) || (($a instanceof \\think\\Collection || $a instanceof \\think\\Paginator) && $b->isEmpty())): endif;|23",
            "if(empty($a) || ($a instanceof \\think\\Collection && $a->isEmpty())): endif;|23",
            "if(false): if(is_array($a) || $a instanceof \\think\\Collection): endif; endif;|35",
            // 任意成员访问、独立 / 变形 / 链式方法
            "echo $a->b;|13",
            "echo $a->b();|13",
            "echo $a['x']->b;|18",
            "echo $a::B;|13",
            "if($a->isEmpty()): endif;|9",
            "echo $a->IsEmpty();|13",
            "echo $a->isEmpty(1);|13",
            "echo $a->isEmpty()->x();|24",
            "echo f()->x;|14",
        })
        void otherObjectFormsAreRejected(String code, int offset) {
            rejects("<?php " + code + " ?>", Kind.OBJECT, offset);
        }
    }
}
