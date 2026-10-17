package com.maccms.backend.legacyconfig.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValue.PhpBool;
import com.maccms.backend.legacyconfig.application.view.PhpValue.PhpFloat;
import com.maccms.backend.legacyconfig.application.view.PhpValue.PhpInt;
import com.maccms.backend.legacyconfig.application.view.PhpValue.PhpNull;
import com.maccms.backend.legacyconfig.application.view.PhpValue.PhpString;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 旧配置解析的 PHP 黄金向量（src/test/resources/legacyconfig/legacy-config-vectors.json）：
 * 向量是 PHP {@code include} 每个文件后的带类型结果，由同目录 var_export_golden.php 生成，并在 PHP 7.2.34、7.3.33、
 * 8.0.30、8.2.32 上核对一致。覆盖仓库内全部 {@code application/extra/*.php} 与 {@code application/route.php}，
 * 以及 cases/ 下的合成文件（var_export 写出的边界值、手写的短数组与注释写法）。
 *
 * <p>逐项比较类型、键（整数/字符串）、顺序与值；浮点按数值比较（含 -0.0）。仓库文件在 git 中按 LF 存储、Windows 检出时为 CRLF，
 * 而 maccms.php 的 {@code rewrite.route} 值内含换行，所以仓库文件两侧都把 CRLF 换成 LF 后比较；cases/ 按原字节比较（-text）。
 */
class LegacyConfigGoldenVectorsTest {

    private static final String RESOURCE = "/legacyconfig/legacy-config-vectors.json";
    private static final Path REPO_ROOT = Path.of("..");
    private static final Path CASES = Path.of("src", "test", "resources", "legacyconfig");

    private static JsonNode data;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = LegacyConfigGoldenVectorsTest.class.getResourceAsStream(RESOURCE)) {
            assertThat(in).as(RESOURCE).isNotNull();
            data = JsonMapper.builder().build().readTree(in);
        }
    }

    @Test
    void vectorsCoverEveryLegacyConfigFileInTheRepository() throws IOException {
        List<String> paths = new ArrayList<>();
        data.get("files").forEach(f -> paths.add(f.get("path").stringValue()));
        List<String> expected = new ArrayList<>();
        try (Stream<Path> s = Files.list(REPO_ROOT.resolve("application").resolve("extra"))) {
            s.filter(p -> p.toString().endsWith(".php"))
                    .forEach(p -> expected.add("application/extra/" + p.getFileName()));
        }
        expected.add("application/route.php");
        assertThat(paths).containsAll(expected).contains("cases/generated_var_export.php", "cases/handwritten.php");
        // LegacyConfigFile 枚举与仓库文件一一对应
        List<String> enumPaths = Stream.of(LegacyConfigFile.values()).map(f -> "application/" + f.relativePath()).toList();
        assertThat(enumPaths).containsExactlyInAnyOrderElementsOf(expected);
    }

    @TestFactory
    Stream<DynamicTest> javaParserMatchesPhpInclude() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode f : data.get("files")) {
            String rel = f.get("path").stringValue();
            tests.add(DynamicTest.dynamicTest(rel, () -> check(rel, f)));
        }
        return tests.stream();
    }

    private static void check(String rel, JsonNode f) throws Exception {
        boolean repoFile = rel.startsWith("application/");
        Path path = repoFile ? REPO_ROOT.resolve(rel) : CASES.resolve(rel);
        byte[] bytes = Files.readAllBytes(path);
        assertThat(sha256(lf(bytes))).as(rel + " 内容与生成向量时不同，需重新运行 var_export_golden.php --write")
                .isEqualTo(f.get("sha256_lf").stringValue());
        PhpArray parsed = VarExportParser.parse(repoFile ? lf(bytes) : bytes);
        compare(rel, parsed, f.get("value"), repoFile);
    }

    private static void compare(String at, PhpValue actual, JsonNode expected, boolean lfStrings) {
        String type = expected.get(0).stringValue();
        switch (type) {
            case "array" -> {
                assertThat(actual).as(at).isInstanceOf(PhpArray.class);
                Map<Object, PhpValue> entries = ((PhpArray) actual).entries();
                JsonNode items = expected.get(1);
                assertThat(entries).as(at + " 元素个数").hasSize(items.size());
                int i = 0;
                for (Map.Entry<Object, PhpValue> e : entries.entrySet()) {
                    JsonNode item = items.get(i++);
                    JsonNode key = item.get(0);
                    Object expectedKey = key.get(0).stringValue().equals("int")
                            ? (Object) Long.parseLong(key.get(1).stringValue())
                            : key.get(1).stringValue();
                    assertThat(e.getKey()).as(at + " 第 " + i + " 个键").isEqualTo(expectedKey);
                    compare(at + "." + e.getKey(), e.getValue(), item.get(1), lfStrings);
                }
            }
            case "string" -> {
                String want = expected.get(1).stringValue();
                assertThat(actual).as(at).isEqualTo(new PhpString(lfStrings ? want.replace("\r\n", "\n") : want));
            }
            case "int" -> assertThat(actual).as(at).isEqualTo(new PhpInt(Long.parseLong(expected.get(1).stringValue())));
            case "float" -> {
                assertThat(actual).as(at).isInstanceOf(PhpFloat.class);
                double want = Double.parseDouble(expected.get(1).stringValue());
                assertThat(Double.compare(((PhpFloat) actual).value(), want)).as(at).isZero();
            }
            case "bool" -> assertThat(actual).as(at).isEqualTo(new PhpBool(expected.get(1).booleanValue()));
            case "null" -> assertThat(actual).as(at).isSameAs(PhpNull.INSTANCE);
            default -> throw new AssertionError("未知类型 " + type);
        }
    }

    private static byte[] lf(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1).replace("\r\n", "\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
