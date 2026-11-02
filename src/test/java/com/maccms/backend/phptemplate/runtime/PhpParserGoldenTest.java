package com.maccms.backend.phptemplate.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.phptemplate.runtime.TemplateUnsupportedException.Kind;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * C-18 语法解析对 S3-05 真实黄金（{@code b71b1aa}，168 个编译产物）的覆盖（AC-307B-1、AC-307B-3）。
 *
 * <p>黄金是归一化文本（OP-T8：全部 {@code \} 写成 {@code /}），{@code instanceof /think/…} 不是 PHP 类名，原样解析必须
 * 被拒（{@link Kind#OBJECT}）。覆盖用例只把 {@code instanceof /think/Collection|Paginator} 还原成编译器真实输出的
 * {@code \think\…}（{@code Cx.php:101,161,434,451}），其余字节不动；黄金文件本身不改。只验证语法树，不执行。</p>
 */
class PhpParserGoldenTest {

    private static final Path COMPILE_GOLDEN = Path.of("src", "test", "resources", "phptemplate", "compile-golden");
    private static final byte[] NORMALIZED_COLLECTION = "instanceof /think/Collection".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NORMALIZED_PAGINATOR = "instanceof /think/Paginator".getBytes(StandardCharsets.US_ASCII);

    static List<Path> goldenFiles() throws IOException {
        try (Stream<Path> s = Files.walk(COMPILE_GOLDEN)) {
            return s.filter(p -> p.toString().endsWith(".html")).sorted().toList();
        }
    }

    static byte[] restoreClassNames(byte[] raw) {
        byte[] out = replaceAll(raw, NORMALIZED_COLLECTION,
                "instanceof \\think\\Collection".getBytes(StandardCharsets.US_ASCII));
        return replaceAll(out, NORMALIZED_PAGINATOR, "instanceof \\think\\Paginator".getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] replaceAll(byte[] hay, byte[] from, byte[] to) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(hay.length);
        int i = 0;
        while (i < hay.length) {
            if (i + from.length <= hay.length && Arrays.equals(hay, i, i + from.length, from, 0, from.length)) {
                out.write(to, 0, to.length);
                i += from.length;
            } else {
                out.write(hay[i++]);
            }
        }
        return out.toByteArray();
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        for (int i = 0; i + needle.length <= hay.length; i++) {
            if (Arrays.equals(hay, i, i + needle.length, needle, 0, needle.length)) {
                return i;
            }
        }
        return -1;
    }

    /** 反射遍历语法树，按节点类型计数（测试辅助；runtime 不用反射）。 */
    static void count(Object node, Map<String, Integer> counts, List<PhpAst.Node> sink) {
        if (node instanceof List<?> list) {
            list.forEach(o -> count(o, counts, sink));
            return;
        }
        if (!(node instanceof Record r)) {
            return;
        }
        if (node instanceof PhpAst.Node n) {
            counts.merge(node.getClass().getSimpleName(), 1, Integer::sum);
            sink.add(n);
        }
        for (RecordComponent c : r.getClass().getRecordComponents()) {
            try {
                count(c.getAccessor().invoke(r), counts, sink);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void allGoldenProductsParseAfterRestoringCompilerClassNamesAndCoverageIsLocked() throws IOException {
        List<Path> files = goldenFiles();
        assertThat(files).hasSize(168);
        Map<String, Integer> counts = new TreeMap<>();
        TreeSet<String> functions = new TreeSet<>();
        List<String> modelCalls = new ArrayList<>();
        List<String> bareConsts = new ArrayList<>();
        int normalizedFiles = 0;
        int parsed = 0;
        List<String> rejected = new ArrayList<>();
        for (Path f : files) {
            byte[] raw = Files.readAllBytes(f);
            if (indexOf(raw, NORMALIZED_COLLECTION) >= 0 || indexOf(raw, NORMALIZED_PAGINATOR) >= 0) {
                normalizedFiles++;
            }
            String rel = COMPILE_GOLDEN.relativize(f).toString().replace('\\', '/');
            PhpAst.Program program;
            try {
                program = PhpParser.parse(restoreClassNames(raw), name -> functions.add(name) || true);
            } catch (TemplateUnsupportedException e) {
                rejected.add(rel + " " + e.kind().code() + "@" + e.offset() + " " + e.snippet());
                continue;
            }
            parsed++;
            List<PhpAst.Node> nodes = new ArrayList<>();
            count(program.statements(), counts, nodes);
            for (PhpAst.Node n : nodes) {
                if (n instanceof PhpAst.ModelCall mc) {
                    modelCalls.add(rel + " " + mc.normalizedModel() + "->" + mc.method() + "/" + mc.args().size());
                } else if (n instanceof PhpAst.BareConst bc) {
                    bareConsts.add(rel + " " + bc.name());
                }
            }
        }
        System.out.println("PhpParserGoldenTest coverage: " + counts);
        System.out.println("PhpParserGoldenTest functions: " + functions);
        System.out.println("PhpParserGoldenTest modelCalls: " + modelCalls + " bare: " + bareConsts);
        System.out.println("PhpParserGoldenTest rejected: " + rejected);

        // GAP-2（本批查出，RT-1 / PRE-5 均未登记）：两份 rss/index.html 第 11 行 {$maccms:site_url}{$maccms:path_tpl}
        // 编译为 $maccms->site_url / $maccms->path_tpl（对象属性取值）。RT-1 写明“单独的 ->属性 取值不支持”，
        // 解析器按现设计拒绝，不自行定义语义；是否支持由设计修订决定。
        String rssSnippet = "->site_url; ?><?php echo $maccms->path_tpl; ?>images/logo.png</url>";
        assertThat(rejected).containsExactly(
                "default_pc/html/rss/index.html object@437 " + rssSnippet,
                "default_wap/html/rss/index.html object@437 " + rssSnippet);
        assertThat(parsed).isEqualTo(166);

        // 覆盖表（166 个可解析产物的节点计数）：任何语法变化都会改动这些数字，须重新核对
        assertThat(counts).containsExactlyEntriesOf(new TreeMap<>(Map.ofEntries(
                Map.entry("ArrayItem", 667), Map.entry("ArrayLiteral", 168), Map.entry("Assign", 121),
                Map.entry("BareConst", 2), Map.entry("Binary", 638), Map.entry("Branch", 447), Map.entry("Break", 5),
                Map.entry("Case", 5), Map.entry("Echo", 5258), Map.entry("Empty", 10), Map.entry("ExprStmt", 156),
                Map.entry("Foreach", 39), Map.entry("FunctionCall", 1588), Map.entry("If", 423),
                Map.entry("Increment", 35), Map.entry("Index", 5295), Map.entry("InlineHtml", 5995),
                Map.entry("InstanceOf", 98), Map.entry("IntLiteral", 393), Map.entry("IsEmptyCall", 10),
                Map.entry("ModelCall", 2), Map.entry("StringLiteral", 7008), Map.entry("Switch", 1),
                Map.entry("Variable", 6281))));
        // 真实产物调用到的函数名（RT-7 白名单的语法侧输入；白名单本身归 C-19/C-27）
        assertThat(functions).containsExactly("array_slice", "ceil", "count", "date", "explode", "htmlspecialchars",
                "in_array", "intval", "is_array", "long2ip", "mac_day", "mac_default", "mac_em_replace",
                "mac_filter_html", "mac_get_plog_type_text", "mac_get_user_portrait", "mac_long2ip", "mac_page_param",
                "mac_substring", "mac_url", "mac_url_actor_detail", "mac_url_art_detail", "mac_url_art_index",
                "mac_url_content_img", "mac_url_create", "mac_url_img", "mac_url_page", "mac_url_plot_detail",
                "mac_url_role_detail", "mac_url_search", "mac_url_topic_detail", "mac_url_topic_index",
                "mac_url_type", "mac_url_vod_detail", "mac_url_vod_play", "mac_url_vod_plot",
                "mac_url_website_detail", "mac_url_website_index", "str_replace", "url", "urlencode");
        assertThat(normalizedFiles).isEqualTo(28);
        assertThat(counts.get("InstanceOf")).isEqualTo(98);
        assertThat(counts.get("IsEmptyCall")).isEqualTo(10);
        assertThat(modelCalls).containsExactly(
                "default_pc/html/art/detail.html Art->infoData/3",
                "default_pc/html/art/detail.html Art->infoData/3");
        assertThat(bareConsts).containsExactly(
                "default_pc/html/user/ajax_info.html color",
                "default_wap/html/user/ajax_info.html color");
    }

    @Test
    void normalizedGoldenClassNamesAreRejectedAsObjectSyntax() throws IOException {
        int rejected = 0;
        for (Path f : goldenFiles()) {
            byte[] raw = Files.readAllBytes(f);
            int at = indexOf(raw, NORMALIZED_COLLECTION);
            if (at < 0) {
                continue;
            }
            rejected++;
            int slash = at + "instanceof ".length();
            assertThatThrownBy(() -> PhpParser.parse(raw, name -> true))
                    .as(f.toString())
                    .isInstanceOfSatisfying(TemplateUnsupportedException.class, e -> {
                        assertThat(e.kind()).isEqualTo(Kind.OBJECT);
                        assertThat(e.offset()).isEqualTo(slash);
                        assertThat(e.snippet()).startsWith("/think/Collection");
                    });
        }
        assertThat(rejected).isEqualTo(28);
    }
}
