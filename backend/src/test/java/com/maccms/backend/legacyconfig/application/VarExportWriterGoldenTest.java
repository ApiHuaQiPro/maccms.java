package com.maccms.backend.legacyconfig.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link VarExportWriter} 与 PHP {@code var_export} 逐字节相同（B15，24 文档第 3.1 节）：黄金
 * {@code legacyconfig/php80-semantics-golden.json}、{@code php73-semantics-golden.json} 由 {@code backend/src/test/php/b15_php_semantics_golden.php}
 * 在本机 PHP 8.0.30 / 7.3.33 生成（两版结果相同），覆盖转义、Unicode、换行、空数组与嵌套、整数键与字符串键、负数、布尔与 NULL、浮点，
 * 另有 300 个确定性随机的嵌套数组；三种写文件外壳（mac_arr2file、addons.php、插件 config.php）逐字节比对；写出的内容能被
 * {@link VarExportParser} 读回同一个值。
 */
class VarExportWriterGoldenTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static JsonNode golden(String name) throws IOException {
        try (InputStream in = VarExportWriterGoldenTest.class.getResourceAsStream("/legacyconfig/" + name)) {
            return JSON.readTree(in);
        }
    }

    /** 黄金里带类型的值（{@code ["s", base64]}、{@code ["i", "1"]}、{@code ["f", hexbits]}、{@code ["a", [[kt, k, v], …]]}…）。 */
    static PhpValue typed(JsonNode n) {
        String t = n.get(0).asString();
        return switch (t) {
            case "s" -> new PhpValue.PhpString(new String(Base64.getDecoder().decode(n.get(1).asString()), StandardCharsets.UTF_8));
            case "i" -> new PhpValue.PhpInt(Long.parseLong(n.get(1).asString()));
            case "f" -> new PhpValue.PhpFloat(Double.longBitsToDouble(Long.parseUnsignedLong(n.get(1).asString(), 16)));
            case "b" -> new PhpValue.PhpBool(n.get(1).asBoolean());
            case "n" -> PhpValue.PhpNull.INSTANCE;
            case "a" -> {
                Map<Object, PhpValue> m = new LinkedHashMap<>();
                for (JsonNode item : n.get(1)) {
                    Object key = item.get(0).asString().equals("i") ? (Object) Long.parseLong(item.get(1).asString())
                            : new String(Base64.getDecoder().decode(item.get(1).asString()), StandardCharsets.UTF_8);
                    m.put(key, typed(item.get(2)));
                }
                yield new PhpArray(m);
            }
            default -> throw new IllegalArgumentException(t);
        };
    }

    private static byte[] b64(JsonNode n) {
        return Base64.getDecoder().decode(n.asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"php80-semantics-golden.json", "php73-semantics-golden.json"})
    void exportMatchesPhpByteForByte(String file) throws IOException {
        JsonNode g = golden(file);
        int checked = 0;
        for (JsonNode v : g.get("values")) {
            String label = v.get("label").asString();
            PhpValue value = typed(v.get("value"));
            byte[] expected = b64(v.get("export"));
            assertThat(VarExportWriter.export(value).getBytes(StandardCharsets.UTF_8)).as(label).isEqualTo(expected);
            if (v.has("arr2file")) {
                PhpArray a = (PhpArray) value;
                assertThat(VarExportWriter.macArr2File(a)).as(label + " mac_arr2file").isEqualTo(b64(v.get("arr2file")));
                assertThat(VarExportWriter.addonsFile(a)).as(label + " addons.php").isEqualTo(b64(v.get("addons")));
                assertThat(VarExportWriter.addonConfigFile(a)).as(label + " config.php").isEqualTo(b64(v.get("addonConfig")));
                // 写出去的文件新版自己能读回同一个值
                assertThat(VarExportParser.parse(VarExportWriter.macArr2File(a))).as(label + " 读回").isEqualTo(a);
            }
            checked++;
        }
        assertThat(checked).isEqualTo(310);
    }

    @ParameterizedTest
    @ValueSource(strings = {"php80-semantics-golden.json", "php73-semantics-golden.json"})
    void floatsMatchPhpSerializePrecision(String file) throws IOException {
        JsonNode g = golden(file);
        int n = 0;
        for (JsonNode f : g.get("floats")) {
            double d = Double.longBitsToDouble(Long.parseUnsignedLong(f.get("bits").asString(), 16));
            assertThat(VarExportWriter.floatText(d)).as(f.get("bits").asString()).isEqualTo(f.get("export").asString());
            n++;
        }
        assertThat(n).isGreaterThan(200);
    }

    @Test
    void repositoryConfigFilesWrittenByMacArr2FileRoundTripByteForByte() throws IOException {
        // 仓库里由后台（mac_arr2file）写出的配置文件：读出来再写回去逐字节相同。手工编辑过的（vodserver.php 结尾带 ?>）不在此列。
        // git 在 Windows 上可能以 CRLF 检出（库里是 LF，这些文件的取值里没有 \r），比较前换回 LF
        for (String f : List.of("extra/maccms.php", "route.php", "extra/vodplayer.php", "extra/voddowner.php", "extra/timming.php",
                "extra/domain.php", "extra/quickmenu.php", "extra/bind.php")) {
            String text = Files.readString(Path.of("..", "application").resolve(f), StandardCharsets.UTF_8).replace("\r\n", "\n");
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            assertThat(new String(VarExportWriter.macArr2File(VarExportParser.parse(bytes)), StandardCharsets.UTF_8)).as(f)
                    .isEqualTo(text);
        }
    }

    @Test
    void valuesPhpCannotRoundTripAreRefused() {
        assertThatThrownBy(() -> VarExportWriter.export(new PhpValue.PhpString("a\0b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VarExportWriter.export(new PhpValue.PhpInt(Long.MIN_VALUE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VarExportWriter.export(new PhpValue.PhpFloat(Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VarExportWriter.export(new PhpValue.PhpFloat(Double.POSITIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
