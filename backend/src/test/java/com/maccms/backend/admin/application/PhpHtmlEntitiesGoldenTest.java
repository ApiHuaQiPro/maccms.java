package com.maccms.backend.admin.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link PhpHtmlEntities} 与 PHP 7.3/8.0 的 {@code htmlentities}、{@code html_entity_decode(htmlentities(…))} 逐字节相同（B15，24 文档第 3.2 节），
 * 黄金同 {@code VarExportWriterGoldenTest}：翻译表 252 项、231 个字符串（含非法 UTF-8、已有实体、数字实体、表情、控制字符与随机拼接）。
 */
class PhpHtmlEntitiesGoldenTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode golden(String name) throws IOException {
        try (InputStream in = PhpHtmlEntitiesGoldenTest.class.getResourceAsStream("/legacyconfig/" + name)) {
            return JSON.readTree(in);
        }
    }

    private static byte[] b64(JsonNode n) {
        return Base64.getDecoder().decode(n.asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"php80-semantics-golden.json", "php73-semantics-golden.json"})
    void tableMatchesPhp(String file) throws IOException {
        JsonNode g = golden(file);
        assertThat(PhpHtmlEntities.size()).isEqualTo(g.get("htmlTable").size()).isEqualTo(252);
        for (JsonNode e : g.get("htmlTable")) {
            byte[] ch = b64(e.get(0));
            assertThat(new String(PhpHtmlEntities.encode(ch), StandardCharsets.UTF_8)).isEqualTo(e.get(1).asString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"php80-semantics-golden.json", "php73-semantics-golden.json"})
    void encodeAndDecodeMatchPhp(String file) throws IOException {
        JsonNode g = golden(file);
        for (JsonNode e : g.get("entities")) {
            byte[] in = b64(e.get("in"));
            byte[] enc = PhpHtmlEntities.encode(in);
            assertThat(enc).as(e.get("in").asString()).isEqualTo(b64(e.get("htmlentities")));
            assertThat(PhpHtmlEntities.decodeEncoded(new String(enc, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8))
                    .as("round trip " + e.get("in").asString()).isEqualTo(b64(e.get("roundTrip")));
        }
    }
}
