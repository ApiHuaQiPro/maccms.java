package com.maccms.backend.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B06 黄金样本的版本一致性（16 文档第 9 节）：同一份合成数据与用例在 PHP 8.0.30 与 7.3.33 上生成的两份
 * {@code openapi/provide/php*-golden.json} 中，状态码与正文除下列登记项外逐字节相同，所以 ProvideGoldenMySqlIT 只对照 8.0 一份即可。
 * <ul>
 * <li>h 参数用例：行时间按执行时刻改写，两次运行的时刻不同（新版用记录的时刻重放）；</li>
 * <li>ajax/data 的视频与文章：旧版先把时间格式化成字符串再拼详情地址，PHP 8 的 {@code date()} 抛类型错误（500），PHP 7.3 只告警并输出；
 * site 的 AjaxGoldenMySqlIT 对照 7.3 的输出；</li>
 * <li>二维码 PNG：两版的 zlib 压缩结果不同（图像相同）。</li>
 * </ul>
 */
class ProvideGoldenVersionsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> CLOCK_DEPENDENT = Set.of("vod.h24", "vod.h48", "vod.h0");
    private static final Set<String> PHP8_DATE_TYPE_ERROR = Set.of("data.mid1", "data.mid2", "data.limit20",
            "data.limit15", "data.page21", "data.page-abc", "data.tid1", "data.tid2", "data.tid99", "data.art.tid21",
            "data.path-params");
    private static final Set<String> PNG = Set.of("qrcode.valid");

    @Test
    void php80AndPhp73GoldenAgreeExceptRegisteredCases() throws Exception {
        JsonNode php80 = load("/openapi/provide/php80-golden.json");
        JsonNode php73 = load("/openapi/provide/php73-golden.json");
        assertThat(php80.get("php").asString()).startsWith("8.0");
        assertThat(php73.get("php").asString()).startsWith("7.3");
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode c : php73.get("cases")) {
            byId.put(c.get("id").asString(), c);
        }
        int same = 0;
        for (JsonNode c : php80.get("cases")) {
            String id = c.get("id").asString();
            JsonNode o = byId.get(id);
            assertThat(o).as(id).isNotNull();
            boolean equal = c.get("status").asInt() == o.get("status").asInt()
                    && c.get("body").asString().equals(o.get("body").asString());
            if (CLOCK_DEPENDENT.contains(id) || PNG.contains(id)) {
                continue;
            }
            if (PHP8_DATE_TYPE_ERROR.contains(id)) {
                assertThat(c.get("status").asInt()).as(id + " PHP 8").isEqualTo(500);
                assertThat(o.get("status").asInt()).as(id + " PHP 7.3").isEqualTo(200);
                continue;
            }
            assertThat(equal).as(id + " 两个 PHP 版本输出相同").isTrue();
            same++;
        }
        assertThat(same).isGreaterThan(150);
        assertThat(php80.get("cases").size()).isEqualTo(php73.get("cases").size());
    }

    private static JsonNode load(String resource) throws Exception {
        try (InputStream in = ProvideGoldenVersionsTest.class.getResourceAsStream(resource)) {
            return JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
