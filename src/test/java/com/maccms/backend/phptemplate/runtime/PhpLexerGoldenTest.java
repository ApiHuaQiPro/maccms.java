package com.maccms.backend.phptemplate.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.phptemplate.runtime.PhpLexer.Close;
import com.maccms.backend.phptemplate.runtime.PhpLexer.NameKind;
import com.maccms.backend.phptemplate.runtime.PhpLexer.Result;
import com.maccms.backend.phptemplate.runtime.PhpLexer.Segment;
import com.maccms.backend.phptemplate.runtime.PhpLexer.Token;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * C-18 对 S3-05 真实黄金（{@code b71b1aa}）的切分与定位（技术设计 v15 §6.5.1 BARE-7、S15-1～S15-3）。
 * 只验证切分、偏移与记号，不执行代码：BV-6 的执行层（S15-4）属 S3-07c，本类不冒充其通过。
 */
class PhpLexerGoldenTest {

    private static final Path COMPILE_GOLDEN = Path.of("src", "test", "resources", "phptemplate", "compile-golden");
    private static final byte[] P15 = concat(repeat((byte) 0x20, 12),
            HexFormat.of().parseHex("e4b88ae6aca1e799bbe5bd95e697b6e997b4efbc9a"));
    private static final byte[] B15 = "<?php echo mac_day($obj['user_last_login_time'],color); ?>"
            .getBytes(StandardCharsets.US_ASCII);

    private static byte[] repeat(byte v, int n) {
        byte[] out = new byte[n];
        Arrays.fill(out, v);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String sha256(byte[] b) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    /** S15-1：按字节以 LF 切分，取第 n 段（从 1 计），不含行尾 LF；返回 [起点, 终点)。 */
    private static int[] lfLine(byte[] file, int n) {
        int start = 0;
        for (int line = 1; line < n; line++) {
            int lf = indexOf(file, (byte) 0x0a, start);
            assertThat(lf).as("第 %d 行之前的 LF", line).isNotNegative();
            start = lf + 1;
        }
        int end = indexOf(file, (byte) 0x0a, start);
        return new int[] {start, end < 0 ? file.length : end};
    }

    private static int indexOf(byte[] a, byte v, int from) {
        for (int i = from; i < a.length; i++) {
            if (a[i] == v) {
                return i;
            }
        }
        return -1;
    }

    private static List<Integer> occurrences(byte[] hay, byte[] needle) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i + needle.length <= hay.length; i++) {
            if (Arrays.equals(hay, i, i + needle.length, needle, 0, needle.length)) {
                out.add(i);
            }
        }
        return out;
    }

    @ParameterizedTest
    @ValueSource(strings = {"default_pc", "default_wap"})
    void s15SampleBoundaryAndLexerSegmentation(String pkg) throws Exception {
        byte[] file = Files.readAllBytes(COMPILE_GOLDEN.resolve(pkg).resolve("html/user/ajax_info.html"));

        // S15-1：原行 R15
        assertThat(indexOf(file, (byte) 0x0d, 0)).as("整份文件不含 0x0d").isNegative();
        assertThat(occurrences(file, new byte[] {0x0a})).as("26 个 LF").hasSize(26);
        int[] line = lfLine(file, 15);
        byte[] r15 = Arrays.copyOfRange(file, line[0], line[1]);
        assertThat(r15).hasSize(91);
        assertThat(sha256(r15)).isEqualTo("2fca28e786dbfb27b6468b87fd6863991678aa4c63bacff7ae8de26931d59d9b");
        assertThat(file[line[1]]).as("R15 后紧跟 LF").isEqualTo((byte) 0x0a);

        // S15-3 EX-15（取样规则，与解释器切分分开）
        List<Integer> opens = occurrences(r15, "<?php".getBytes(StandardCharsets.US_ASCII));
        List<Integer> closes = occurrences(r15, "?>".getBytes(StandardCharsets.US_ASCII));
        assertThat(opens).hasSize(1);
        assertThat(closes).hasSize(1);
        int s = opens.get(0);
        int e = closes.get(0) + 2;
        assertThat(s).isEqualTo(33);
        assertThat(e).isEqualTo(91);
        assertThat(Arrays.copyOfRange(r15, 0, s)).as("P15 33 字节").isEqualTo(P15);
        assertThat(Arrays.copyOfRange(r15, s, e)).as("B15 58 字节").isEqualTo(B15).hasSize(58);
        assertThat(sha256(B15)).isEqualTo("6d67a0652c98f13083570dc5694887bb8f14a2ca565fdf48a532ca7adeb3de7e");

        // C-18 对 R15 + LF（该行完整 92 字节）的切分：字面 P15 + 代码块，紧跟 ?> 的 LF 按 TPL-5 被吃掉，之后无字面段
        byte[] r15lf = Arrays.copyOfRange(file, line[0], line[1] + 1);
        Result r = PhpLexer.lex(r15lf);
        assertThat(r.segments()).hasSize(2);
        Segment.Literal p = (Segment.Literal) r.segments().get(0);
        Segment.Code c = (Segment.Code) r.segments().get(1);
        assertThat(r.bytes(p)).isEqualTo(P15);
        assertThat(c.start()).isEqualTo(s);
        assertThat(c.codeStart()).isEqualTo(s + 6);
        assertThat(c.codeEnd()).isEqualTo(e - 2);
        assertThat(c.close()).isEqualTo(Close.TAG_LF);
        assertThat(c.end()).isEqualTo(92);
        assertB15Tokens(c.tokens(), s);

        // 不带 LF 的 R15：同样的两段，只是没有被吃的换行
        Result noLf = PhpLexer.lex(r15);
        assertThat(((Segment.Code) noLf.segments().get(1)).close()).isEqualTo(Close.TAG);

        // 单独的 B15（不加 LF）：一个代码段，偏移从 0 起
        Result block = PhpLexer.lex(B15);
        assertThat(block.segments()).hasSize(1);
        Segment.Code bc = (Segment.Code) block.segments().get(0);
        assertThat(bc.start()).isZero();
        assertThat(bc.end()).isEqualTo(58);
        assertB15Tokens(bc.tokens(), 0);

        // 整份文件：第 15 行的代码段落在原始文件偏移 line[0] + 33 处
        Result whole = PhpLexer.lex(file);
        Segment.Code inFile = whole.segments().stream()
                .filter(Segment.Code.class::isInstance).map(Segment.Code.class::cast)
                .filter(x -> x.start() == line[0] + s).findFirst().orElseThrow();
        assertThat(inFile.end()).isEqualTo(line[1] + 1);
        assertThat(inFile.close()).isEqualTo(Close.TAG_LF);
    }

    private static void assertB15Tokens(List<Token> ts, int base) {
        // <?php echo mac_day($obj['user_last_login_time'],color); ?>
        // 0     6    11     18 19  23 24                    46 47 48   53 54
        assertThat(ts).containsExactly(
                new Token.Keyword(base + 6, base + 10, "echo"),
                new Token.Name(base + 11, base + 18, "mac_day", NameKind.UNQUALIFIED),
                new Token.Op(base + 18, base + 19, "("),
                new Token.Variable(base + 19, base + 23, "obj"),
                new Token.Op(base + 23, base + 24, "["),
                ts.get(5),
                new Token.Op(base + 46, base + 47, "]"),
                new Token.Op(base + 47, base + 48, ","),
                new Token.Name(base + 48, base + 53, "color", NameKind.UNQUALIFIED),
                new Token.Op(base + 53, base + 54, ")"),
                new Token.Op(base + 54, base + 55, ";"));
        Token.Str key = (Token.Str) ts.get(5);
        assertThat(key.start()).isEqualTo(base + 24);
        assertThat(key.end()).isEqualTo(base + 46);
        assertThat(key.doubleQuoted()).isFalse();
        assertThat(new String(key.bytes(), StandardCharsets.UTF_8))
                .as("键是带引号的字符串，不是裸名").isEqualTo("user_last_login_time");
    }

    @Test
    void wholeCompileGoldenSplitsIntoContiguousSegmentsWithoutNormalisation() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(COMPILE_GOLDEN)) {
            files = s.filter(p -> p.toString().endsWith(".html")).sorted().toList();
        }
        assertThat(files).hasSize(168);
        int codeBlocks = 0;
        int slashThink = 0;
        for (Path f : files) {
            byte[] src = Files.readAllBytes(f);
            Result r = PhpLexer.lex(src);
            int pos = 0;
            for (Segment seg : r.segments()) {
                assertThat(seg.start()).as("%s 段连续", f).isEqualTo(pos);
                pos = seg.end();
                if (seg instanceof Segment.Code c) {
                    codeBlocks++;
                    for (Token t : c.tokens()) {
                        if (t instanceof Token.Name n) {
                            assertThat(n.kind()).as("%s：归一化黄金没有完整类名记号", f).isEqualTo(NameKind.UNQUALIFIED);
                            if (n.name().equals("think")) {
                                slashThink++;
                            }
                        }
                    }
                }
            }
            assertThat(pos).as("%s 全部字节都在某一段内", f).isEqualTo(src.length);
        }
        assertThat(codeBlocks).as("与 BARE-3 复跑 php_open_tags 一致").isEqualTo(6032);
        assertThat(slashThink).as("/think/Collection 与 /think/Paginator 各 49 处，均按除号 + 名字切分").isEqualTo(98);
    }
}
