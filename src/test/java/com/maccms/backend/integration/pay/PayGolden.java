package com.maccms.backend.integration.pay;

import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.integration.application.ThirdPartyConfig;
import com.maccms.backend.legacyconfig.application.VarExportParser;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B24 的 PHP 黄金样本（{@code src/test/php/pay_golden.php} 生成的 {@code integration/b24/php{73,80}-golden.json}）与复放用的
 * 工具：配置（仓库 {@code maccms.php} → 黄金的基础配置 → 用例覆盖项）、回调请求的参数（与 ThinkPHP {@code input()}/{@code $_POST} 相同的解析）。
 */
public final class PayGolden {

    public static final String PHP73 = "integration/b24/php73-golden.json";
    public static final String PHP80 = "integration/b24/php80-golden.json";
    /** 黄金运行时的 Host（旧版 notify_url 用 HTTP_HOST；新版用 base-url）。 */
    public static final String HOST = "http://www.example.test/";

    private static final Path MACCMS_PHP = Path.of("php", "application", "extra", "maccms.php");

    private PayGolden() {
    }

    public static JsonNode load(String resource) {
        try (InputStream in = PayGolden.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing " + resource);
            }
            return JsonMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 回调地址：与黄金相同的 {@code http://www.example.test/index.php/payment/notify/pay_type/<方式>}。 */
    public static String notifyUrl(String payment) {
        return HOST + "index.php/payment/notify/pay_type/" + payment;
    }

    public static ThirdPartyConfig config(JsonNode golden, JsonNode overrides) {
        PhpArray repo;
        try {
            repo = VarExportParser.parse(Files.readAllBytes(MACCMS_PHP));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Map<Object, Object> root = tree(repo);
        golden.get("baseConfig").properties().forEach(e -> put(root, e.getKey(), e.getValue().asString()));
        if (overrides != null) {
            overrides.properties().forEach(e -> put(root, e.getKey(), e.getValue().asString()));
        }
        return ThirdPartyConfig.from(array(root));
    }

    /** 用例的 GET 参数。 */
    public static Map<String, PhpInput.Value> get(JsonNode c) {
        JsonNode q = c.get("query");
        return PhpInput.parseQuery(q == null || q.asString().isEmpty() ? null : q.asString());
    }

    /** 用例的 {@code $_POST}（只有 urlencoded 正文）。 */
    public static Map<String, PhpInput.Value> post(JsonNode c) {
        return c.get("contentType").asString().startsWith("application/x-www-form-urlencoded")
                ? PhpInput.parse(raw(c)) : Map.of();
    }

    /** 用例的 {@code input()}：GET → POST → 路由 {@code pay_type}。 */
    public static Map<String, PhpInput.Value> input(JsonNode c) {
        return PhpInput.merge(get(c), post(c), Map.of("pay_type", c.get("payType").asString()));
    }

    public static byte[] raw(JsonNode c) {
        return c.get("body").asString().getBytes(StandardCharsets.UTF_8);
    }

    /** 种子订单（按 id）。 */
    public static JsonNode order(JsonNode golden, long id) {
        for (JsonNode o : golden.get("seedOrders")) {
            if (o.get("order_id").asLong() == id) {
                return o;
            }
        }
        return null;
    }

    private static Map<Object, Object> tree(PhpArray a) {
        Map<Object, Object> m = new LinkedHashMap<>();
        a.entries().forEach((k, v) -> m.put(k, v instanceof PhpArray child ? tree(child) : v));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<Object, Object> root, String dotted, String value) {
        String[] path = dotted.split("[.]");
        Map<Object, Object> cur = root;
        for (int i = 0; i < path.length - 1; i++) {
            Object k = PhpArray.normalizeKey(path[i]);
            if (!(cur.get(k) instanceof Map)) {
                cur.put(k, new LinkedHashMap<Object, Object>());
            }
            cur = (Map<Object, Object>) cur.get(k);
        }
        cur.put(PhpArray.normalizeKey(path[path.length - 1]), new PhpValue.PhpString(value));
    }

    @SuppressWarnings("unchecked")
    private static PhpArray array(Map<Object, Object> m) {
        Map<Object, PhpValue> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, v instanceof Map<?, ?> child ? array((Map<Object, Object>) child) : (PhpValue) v));
        return new PhpArray(out);
    }
}
