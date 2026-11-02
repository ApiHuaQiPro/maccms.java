package com.maccms.backend.phptemplate.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.phptemplate.runtime.PhpLexer.Close;
import com.maccms.backend.phptemplate.runtime.PhpLexer.NameKind;
import com.maccms.backend.phptemplate.runtime.PhpLexer.Part;
import com.maccms.backend.phptemplate.runtime.PhpLexer.Result;
import com.maccms.backend.phptemplate.runtime.PhpLexer.Segment;
import com.maccms.backend.phptemplate.runtime.PhpLexer.Token;
import com.maccms.backend.phptemplate.runtime.TemplateUnsupportedException.Kind;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * C-18 词法切分（技术设计 v15 §6.4 TPL-5、§6.5 RT-1）。期望值逐条对照 PHP 7.3.33 {@code token_get_all}
 * 实测（见实现说明 S3-07a 节的探针命令），偏移一律是编译产物的原始字节偏移。
 */
class PhpLexerTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Result lex(String s) {
        return PhpLexer.lex(b(s));
    }

    private static List<Token> tokens(String code) {
        Result r = lex(code);
        assertThat(r.segments()).hasSize(1);
        return ((Segment.Code) r.segments().get(0)).tokens();
    }

    private static String ops(List<Token> ts) {
        StringBuilder sb = new StringBuilder();
        for (Token t : ts) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(switch (t) {
                case Token.Op o -> o.symbol();
                case Token.Variable v -> "$" + v.name();
                case Token.Name n -> "N:" + n.name();
                case Token.Keyword k -> "K:" + k.word();
                case Token.Cast c -> "C:" + c.type();
                case Token.IntLit i -> "I:" + i.value();
                case Token.FloatLit f -> "F:" + f.value();
                case Token.Str s -> "S:" + new String(s.bytes(), StandardCharsets.UTF_8);
                case Token.Interpolated ip -> "T" + ip.parts().size();
            });
        }
        return sb.toString();
    }

    private static TemplateUnsupportedException rejected(String src) {
        try {
            lex(src);
        } catch (TemplateUnsupportedException e) {
            return e;
        }
        throw new AssertionError("应抛 TemplateUnsupportedException：" + src);
    }

    @Nested
    class Segmentation {

        @Test
        void literalOnlyIsOneSegmentWithRawBytes() {
            byte[] src = b("a\r\nb\n\tc ?> 上 <a> \r");
            Result r = PhpLexer.lex(src);
            assertThat(r.segments()).hasSize(1);
            Segment.Literal lit = (Segment.Literal) r.segments().get(0);
            assertThat(lit.start()).isZero();
            assertThat(lit.end()).isEqualTo(src.length);
            assertThat(r.bytes(lit)).as("CRLF、TAB、多字节原样保留，不做换行归一化").isEqualTo(src);
        }

        @Test
        void emptySourceHasNoSegments() {
            assertThat(PhpLexer.lex(new byte[0]).segments()).isEmpty();
        }

        @Test
        void mixedLiteralAndCodeKeepOffsets() {
            Result r = lex("上a<?php echo 1; ?>b");
            assertThat(r.segments()).hasSize(3);
            Segment.Literal l0 = (Segment.Literal) r.segments().get(0);
            Segment.Code c = (Segment.Code) r.segments().get(1);
            Segment.Literal l1 = (Segment.Literal) r.segments().get(2);
            assertThat(l0.start()).isZero();
            assertThat(l0.end()).as("'上' 占 3 字节").isEqualTo(4);
            assertThat(c.start()).isEqualTo(4);
            assertThat(c.codeStart()).as("开标签吃掉 <?php 后的 1 个空格").isEqualTo(10);
            assertThat(c.codeEnd()).isEqualTo(18);
            assertThat(c.end()).isEqualTo(20);
            assertThat(c.close()).isEqualTo(Close.TAG);
            assertThat(l1.start()).isEqualTo(20);
            assertThat(new String(r.bytes(l1), StandardCharsets.UTF_8)).isEqualTo("b");
            assertThat(c.tokens()).extracting(Token::start).containsExactly(10, 15, 16);
            assertThat(c.tokens()).extracting(Token::end).containsExactly(14, 16, 17);
        }

        @Test
        void segmentsPartitionTheSource() {
            byte[] src = b("x<?php echo 1 ?>\n\ny<?php echo 2;?>\r\nz<?PHP\techo 3;");
            Result r = PhpLexer.lex(src);
            int pos = 0;
            for (Segment s : r.segments()) {
                assertThat(s.start()).isEqualTo(pos);
                pos = s.end();
            }
            assertThat(pos).isEqualTo(src.length);
        }

        @Test
        void openTagIsCaseInsensitiveAndConsumesOneWhitespace() {
            Segment.Code tab = (Segment.Code) lex("<?PhP\techo 1;").segments().get(0);
            assertThat(tab.codeStart()).isEqualTo(6);
            Segment.Code crlf = (Segment.Code) lex("<?php\r\necho 1;").segments().get(0);
            assertThat(crlf.codeStart()).as("开标签后的 CRLF 整体属于开标签").isEqualTo(7);
            Segment.Code lf = (Segment.Code) lex("<?php\n\necho 1;").segments().get(0);
            assertThat(lf.codeStart()).as("只吃 1 个换行").isEqualTo(6);
        }

        @Test
        void codeWithoutCloseTagRunsToEndOfSource() {
            Result r = lex("a<?php echo 1;");
            Segment.Code c = (Segment.Code) r.segments().get(1);
            assertThat(c.close()).isEqualTo(Close.EOF);
            assertThat(c.codeEnd()).isEqualTo(14);
            assertThat(c.end()).isEqualTo(14);
        }

        @Test
        void literalCloseMarkerOutsideCodeIsText() {
            Result r = lex("a ?> b\n");
            assertThat(r.segments()).hasSize(1);
        }
    }

    @Nested
    class Tpl5CloseTagNewline {

        @Test
        void exactlyOneLfAfterCloseTagIsEaten() {
            Result r = lex("a<?php echo 1; ?>\n\nb");
            Segment.Code c = (Segment.Code) r.segments().get(1);
            assertThat(c.close()).isEqualTo(Close.TAG_LF);
            assertThat(c.close().eatenNewlineLength()).isEqualTo(1);
            assertThat(c.end()).isEqualTo(18);
            Segment.Literal tail = (Segment.Literal) r.segments().get(2);
            assertThat(r.bytes(tail)).as("第 2 个 LF 原样输出").isEqualTo(b("\nb"));
        }

        @Test
        void wholeCrlfAfterCloseTagIsEaten() {
            Result r = lex("a<?php echo 1; ?>\r\n\r\nb");
            Segment.Code c = (Segment.Code) r.segments().get(1);
            assertThat(c.close()).isEqualTo(Close.TAG_CRLF);
            assertThat(c.close().eatenNewlineLength()).isEqualTo(2);
            assertThat(r.bytes((Segment.Literal) r.segments().get(2))).isEqualTo(b("\r\nb"));
        }

        @Test
        void noNewlineMeansNothingEaten() {
            Result r = lex("<?php echo 1; ?> \nb");
            Segment.Code c = (Segment.Code) r.segments().get(0);
            assertThat(c.close()).isEqualTo(Close.TAG);
            assertThat(c.close().eatenNewlineLength()).isZero();
            assertThat(r.bytes((Segment.Literal) r.segments().get(1))).isEqualTo(b(" \nb"));
        }

        @Test
        void closeTagAtEndOfSource() {
            Result r = lex("<?php echo 1; ?>");
            assertThat(r.segments()).hasSize(1);
            assertThat(((Segment.Code) r.segments().get(0)).close()).isEqualTo(Close.TAG);
        }

        @Test
        void loneCrIsNotEatenPerDesignTpl5() {
            // 设计 TPL-5 只列 LF 与整个 CRLF；PHP 7.3 实测也会吃单独的 CR（见实现说明“已知问题”），本批按设计实现
            Result r = lex("<?php echo 1; ?>\rb");
            assertThat(((Segment.Code) r.segments().get(0)).close()).isEqualTo(Close.TAG);
            assertThat(r.bytes((Segment.Literal) r.segments().get(1))).isEqualTo(b("\rb"));
        }
    }

    @Nested
    class Strings {

        @Test
        void singleQuotedOnlyUnescapesQuoteAndBackslash() {
            List<Token> ts = tokens("<?php 'a\\'b\\\\c\\n$x' ?>");
            Token.Str s = (Token.Str) ts.get(0);
            assertThat(s.bytes()).isEqualTo(b("a'b\\c\\n$x"));
            assertThat(s.doubleQuoted()).isFalse();
            assertThat(s.start()).isEqualTo(6);
        }

        @Test
        void closeTagInsideStringDoesNotCloseCode() {
            Result r = lex("<?php echo 'a ?> b'; echo \"?>\"; ?>c");
            assertThat(r.segments()).hasSize(2);
            Segment.Code c = (Segment.Code) r.segments().get(0);
            assertThat(ops(c.tokens())).isEqualTo("K:echo S:a ?> b ; K:echo S:?> ;");
        }

        @Test
        void openTagInsideStringIsPlainBytes() {
            assertThat(ops(tokens("<?php echo '<?'; ?>"))).isEqualTo("K:echo S:<? ;");
        }

        @Test
        void doubleQuotedEscapes() {
            Token.Str s = (Token.Str) tokens("<?php \"\\n\\t\\r\\v\\e\\f\\\\\\$\\\"\\x41\\101\\u{4e0a}\\q\\x\\8\"").get(0);
            byte[] expected = new byte[] {'\n', '\t', '\r', 0x0b, 0x1b, '\f', '\\', '$', '"', 'A', 'A',
                (byte) 0xe4, (byte) 0xb8, (byte) 0x8a, '\\', 'q', '\\', 'x', '\\', '8'};
            assertThat(s.bytes()).isEqualTo(expected);
            assertThat(s.doubleQuoted()).isTrue();
        }

        @Test
        void octalEscapeWrapsToOneByte() {
            Token.Str s = (Token.Str) tokens("<?php \"\\777\"").get(0);
            assertThat(s.bytes()).as("PHP 取低 8 位").isEqualTo(new byte[] {(byte) 0xff});
        }

        @Test
        void doubleQuotedSimpleVariableInterpolation() {
            Token.Interpolated ip = (Token.Interpolated) tokens("<?php \"a$name-b $ c\"").get(0);
            assertThat(ip.parts()).hasSize(3);
            assertThat(((Part.Text) ip.parts().get(0)).bytes()).isEqualTo(b("a"));
            Part.Var v = (Part.Var) ip.parts().get(1);
            assertThat(v.name()).isEqualTo("name");
            assertThat(v.offset()).isEqualTo(8);
            assertThat(((Part.Text) ip.parts().get(2)).bytes()).isEqualTo(b("-b $ c"));
        }

        @Test
        void complexInterpolationIsUnsupported() {
            for (String src : List.of("<?php \"{$a}\"", "<?php \"${a}\"", "<?php \"$a[1]\"", "<?php \"$a->b\"")) {
                TemplateUnsupportedException e = rejected(src);
                assertThat(e.kind()).as(src).isEqualTo(Kind.INTERPOLATION);
                assertThat(e.offset()).as(src).isEqualTo(7);
            }
        }

        @Test
        void unterminatedStringsReportStartOffset() {
            for (String src : List.of("<?php echo 'abc", "<?php echo \"abc", "<?php echo 'a\\", "<?php echo 'a ?> b")) {
                TemplateUnsupportedException e = rejected(src);
                assertThat(e.kind()).as(src).isEqualTo(Kind.UNTERMINATED);
                assertThat(e.offset()).as(src).isEqualTo(11);
            }
        }
    }

    @Nested
    class Comments {

        @Test
        void lineCommentRunsToLineEnd() {
            assertThat(ops(tokens("<?php // echo 'x';\necho 1; // tail\r$a;"))).isEqualTo("K:echo I:1 ; $a ;");
        }

        @Test
        void lineCommentStopsBeforeCloseTagAndDoesNotSwallowIt() {
            Result r = lex("a<?php // x '?>' y\nb");
            assertThat(r.segments()).hasSize(3);
            Segment.Code c = (Segment.Code) r.segments().get(1);
            assertThat(c.tokens()).isEmpty();
            assertThat(c.codeEnd()).isEqualTo(13);
            assertThat(c.close()).isEqualTo(Close.TAG);
            assertThat(r.bytes((Segment.Literal) r.segments().get(2))).as("注释里的引号不开始字符串").isEqualTo(b("' y\nb"));
        }

        @Test
        void lineCommentCloseTagStillEatsNewline() {
            Result r = lex("<?php // c ?>\nb");
            Segment.Code c = (Segment.Code) r.segments().get(0);
            assertThat(c.close()).isEqualTo(Close.TAG_LF);
            assertThat(r.bytes((Segment.Literal) r.segments().get(1))).isEqualTo(b("b"));
        }

        @Test
        void lineCommentAtEndOfSource() {
            Segment.Code c = (Segment.Code) lex("<?php echo 1; // end").segments().get(0);
            assertThat(c.close()).isEqualTo(Close.EOF);
            assertThat(ops(c.tokens())).isEqualTo("K:echo I:1 ;");
        }

        @Test
        void otherCommentFormsAreOutsideRt1() {
            assertThat(rejected("<?php /* x ?> */ ?>").kind()).isEqualTo(Kind.TOKEN);
            assertThat(rejected("<?php /* x ?> */ ?>").offset()).isEqualTo(6);
            assertThat(rejected("<?php # x\n?>").kind()).isEqualTo(Kind.TOKEN);
        }
    }

    @Nested
    class NamesAndVariables {

        @Test
        void bareNameFunctionNameVariableAndKeywordAreDistinctTokens() {
            List<Token> ts = tokens("<?php echo color; color(1); $color; ECHO $x; true; NULL;");
            assertThat(ops(ts)).isEqualTo(
                    "K:echo N:color ; N:color ( I:1 ) ; $color ; K:echo $x ; N:true ; N:NULL ;");
            Token.Name bare = (Token.Name) ts.get(1);
            assertThat(bare.kind()).isEqualTo(NameKind.UNQUALIFIED);
            assertThat(bare.start()).isEqualTo(11);
            assertThat(bare.end()).isEqualTo(16);
        }

        @Test
        void fullyQualifiedAndQualifiedNamesAreSingleTokens() {
            List<Token> ts = tokens("<?php $a instanceof \\think\\Collection; a\\color; \\color;");
            Token.Name fq = (Token.Name) ts.get(2);
            assertThat(fq.name()).isEqualTo("\\think\\Collection");
            assertThat(fq.kind()).isEqualTo(NameKind.FULLY_QUALIFIED);
            assertThat(fq.start()).as("记号从前导 \\ 起算").isEqualTo(20);
            assertThat(fq.end()).isEqualTo(37);
            Token.Name q = (Token.Name) ts.get(4);
            assertThat(q.name()).isEqualTo("a\\color");
            assertThat(q.kind()).isEqualTo(NameKind.QUALIFIED);
            assertThat(((Token.Name) ts.get(6)).kind()).isEqualTo(NameKind.FULLY_QUALIFIED);
            assertThat(ts.get(1)).isEqualTo(new Token.Keyword(9, 19, "instanceof"));
        }

        @Test
        void normalizedGoldenSlashFormIsNotAClassName() {
            // OP-T8：S3-05 黄金把 \ 归一成 /，`/think/Collection` 在 PHP 里是除号与名字，不是完整类名
            assertThat(ops(tokens("<?php $a instanceof /think/Collection; ?>")))
                    .isEqualTo("$a K:instanceof / N:think / N:Collection ;");
        }

        @Test
        void danglingNamespaceSeparatorIsUnsupported() {
            TemplateUnsupportedException e = rejected("<?php echo \\ ;");
            assertThat(e.kind()).isEqualTo(Kind.TOKEN);
            assertThat(e.offset()).isEqualTo(11);
            assertThat(rejected("<?php a\\;").offset()).isEqualTo(7);
        }

        @Test
        void labelAfterObjectOperatorIsAlwaysName() {
            assertThat(ops(tokens("<?php $a->list; $a->isEmpty(); list;")))
                    .isEqualTo("$a -> N:list ; $a -> N:isEmpty ( ) ; K:list ;");
        }

        @Test
        void highBytesAreLabelCharacters() {
            Token.Variable v = (Token.Variable) tokens("<?php $变量;").get(0);
            assertThat(v.name()).isEqualTo("变量");
            assertThat(v.end()).isEqualTo(13);
        }

        @Test
        void dollarWithoutLabelIsOperatorForParserToReject() {
            assertThat(ops(tokens("<?php $$a; $"))).isEqualTo("$ $a ; $");
        }

        @Test
        void castsAreRecognisedAsOneToken() {
            List<Token> ts = tokens("<?php (int)$a; ( String )$b; (foo)");
            assertThat(ops(ts)).isEqualTo("C:int $a ; C:string $b ; ( N:foo )");
            assertThat(ts.get(3).start()).isEqualTo(15);
            assertThat(ts.get(3).end()).isEqualTo(25);
        }
    }

    @Nested
    class NumbersAndOperators {

        @Test
        void decimalNumbers() {
            assertThat(ops(tokens("<?php 0; 30; 1.5; .5; 2.; 1e3; 1.5E-2; 9223372036854775807; 9223372036854775808;")))
                    .isEqualTo("I:0 ; I:30 ; F:1.5 ; F:0.5 ; F:2.0 ; F:1000.0 ; F:0.015 ; I:9223372036854775807 ;"
                            + " F:9.223372036854776E18 ;");
        }

        @Test
        void leadingZeroDecimalFloatsAndExponentsAreDecimal() {
            // REV-1：PHP 7.3.33 token_get_all 把以下各项都切为单个 T_DNUMBER（十进制，前导零不表示八进制）
            assertThat(ops(tokens("<?php 00.5; 01.5; 00e1; 00.; 09.5; 08e1; 0012.25; 00E+2; 00.5e-1;")))
                    .isEqualTo("F:0.5 ; F:1.5 ; F:0.0 ; F:0.0 ; F:9.5 ; F:80.0 ; F:12.25 ; F:0.0 ; F:0.05 ;");
            Token t = tokens("<?php 0012.25;").get(0);
            assertThat(t.start()).isEqualTo(6);
            assertThat(t.end()).isEqualTo(13);
        }

        @Test
        void leadingZeroIntegerWithoutFractionOrExponentStaysUnsupported() {
            // PHP 7.3.33：00 / 07 为 T_LNUMBER（八进制），00e 为 T_LNUMBER"00" + T_STRING"e"——都仍按非十进制整数拒绝
            for (String src : List.of("<?php 00;", "<?php 07;", "<?php 00e;", "<?php 00...$a;")) {
                TemplateUnsupportedException e = rejected(src);
                assertThat(e.kind()).as(src).isEqualTo(Kind.TOKEN);
                assertThat(e.offset()).as(src).isEqualTo(6);
            }
        }

        @Test
        void nonDecimalIntegerLiteralsAreUnsupported() {
            for (String src : List.of("<?php 012;", "<?php 0x1A;", "<?php 0b11;")) {
                TemplateUnsupportedException e = rejected(src);
                assertThat(e.kind()).as(src).isEqualTo(Kind.TOKEN);
                assertThat(e.offset()).as(src).isEqualTo(6);
            }
        }

        @Test
        void longestMatchOperators() {
            assertThat(ops(tokens("<?php === !== == != <> <= >= <=> && || ?? ++ -- .= += -= *= /= %= -> => :: ** ... << >>")))
                    .isEqualTo("=== !== == != <> <= >= <=> && || ?? ++ -- .= += -= *= /= %= -> => :: ** ... << >>");
            assertThat(ops(tokens("<?php $a?$b:$c; [1,2]{}%!@&|^~/ *1")))
                    .isEqualTo("$a ? $b : $c ; [ I:1 , I:2 ] { } % ! @ & | ^ ~ / * I:1");
        }

        @Test
        void questionMarkBeforeGreaterThanClosesCode() {
            Result r = lex("<?php $a ?>1");
            assertThat(r.segments()).hasSize(2);
            assertThat(ops(((Segment.Code) r.segments().get(0)).tokens())).isEqualTo("$a");
        }

        @Test
        void unsupportedLexemes() {
            assertThat(rejected("<?php `ls`;").kind()).isEqualTo(Kind.TOKEN);
            assertThat(rejected("<?php <<<EOT\nx\nEOT;").kind()).isEqualTo(Kind.TOKEN);
            assertThat(rejected("<?php echo\f1;").offset()).isEqualTo(10);
            assertThat(rejected("<?php echo\u0000;").kind()).isEqualTo(Kind.TOKEN);
        }
    }

    @Nested
    class OpenTags {

        @Test
        void shortEchoAndShortOpenTagsAreUnsupported() {
            // 编译器（TPL-2 的 php 步）已把短标签改写为 echo；编译产物里残留的其他 <? 形态不在 RT-1 内，
            // 其 PHP 行为又取决于 short_open_tag，不能静默当作字面文本输出
            for (String src : List.of("a<?= 1 ?>", "a<? echo 1; ?>", "a<?\n", "a<?phpx 1", "a<?", "a<?php", "a<?xml v?>")) {
                TemplateUnsupportedException e = rejected(src);
                assertThat(e.kind()).as(src).isEqualTo(Kind.OPEN_TAG);
                assertThat(e.offset()).as(src).isEqualTo(1);
            }
        }

        @Test
        void openTagTextInsideCodeIsJustOperators() {
            assertThat(ops(tokens("<?php <?php"))).isEqualTo("< ? N:php");
        }
    }

    @Test
    void sourceIsCopiedDefensively() {
        byte[] src = b("ab");
        Result r = PhpLexer.lex(src);
        src[0] = 'X';
        assertThat(r.bytes((Segment.Literal) r.segments().get(0))).isEqualTo(b("ab"));
        byte[] out = r.source();
        out[1] = 'Y';
        assertThat(r.source()).isEqualTo(b("ab"));
    }

    @Test
    void exceptionCarriesSnippetOfRawSourceOnly() {
        TemplateUnsupportedException e = rejected("<?php echo `whoami`; ?>\nnext line");
        assertThat(e.offset()).isEqualTo(11);
        assertThat(e.snippet()).isEqualTo("`whoami`; ?>");
        assertThat(e.getMessage()).contains("token").contains("11").contains("`whoami`");
    }
}
