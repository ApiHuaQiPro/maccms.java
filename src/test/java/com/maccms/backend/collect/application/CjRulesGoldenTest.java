package com.maccms.backend.collect.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 自定义规则执行的 PHP/Java 一致（B20，28 文档第 7 节）：{@code src/test/php/cj_rules_golden.php} 在 PHP 7.3.33 与 8.0.30 上直接调用旧
 * {@code Collection.php}（不改旧代码、不联网），本测试用同样的节点与页面重放每个操作，逐值比较（带类型、保持键的顺序；字符串按字节），
 * 另比较 {@code json_encode} 的结果（旧 col_content 写进 {@code mac_cj_content.data} 的文字）与按顺序读取的页面地址。
 *
 * <p>PHP 8.0 的内容采集一律 TypeError（旧代码的图片回调类名不存在，D-B20-3），{@code explode('')} 为 ValueError（D-B20-4）；这些操作新版按
 * 7.3 的结果，本测试核对它们在 7.3 的黄金里都有结果、且 8.0 只在这两类错误上与 7.3 不同。
 */
class CjRulesGoldenTest {

    private static final Path DIR = Path.of("src", "test", "resources", "collect", "cj");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void php73GoldenMatches() throws IOException {
        JsonNode golden = load("php73-cj-golden.json");
        assertThat(golden.get("php").asString()).startsWith("7.3.");
        int ops = 0;
        for (JsonNode c : golden.get("cases")) {
            ops += replay(c, false);
        }
        assertThat(golden.get("cases")).hasSize(55);
        assertThat(ops).isEqualTo(59);
    }

    @Test
    void php80GoldenMatchesWhereLegacyDoesNotFail() throws IOException {
        JsonNode php80 = load("php80-cj-golden.json");
        JsonNode php73 = load("php73-cj-golden.json");
        assertThat(php80.get("php").asString()).startsWith("8.0.");
        Map<String, JsonNode> by73 = new HashMap<>();
        php73.get("cases").forEach(c -> by73.put(c.get("name").asString(), c));
        int failures = 0;
        for (JsonNode c : php80.get("cases")) {
            replay(c, true);
            JsonNode c73 = by73.get(c.get("name").asString());
            for (int i = 0; i < c.get("ops").size(); i++) {
                JsonNode op = c.get("ops").get(i);
                if (op.has("error")) {
                    failures++;
                    String err = op.get("error").asString();
                    assertThat(err).as(c.get("name").asString()).isIn("TypeError", "ValueError");
                    if (err.equals("TypeError")) {
                        assertThat(op.get("op").asString()).isEqualTo("content");
                    }
                    assertThat(c73.get("ops").get(i).has("result")).isTrue();
                } else {
                    assertThat(op).as(c.get("name").asString()).isEqualTo(c73.get("ops").get(i));
                }
            }
        }
        assertThat(failures).isEqualTo(33);
    }

    /** 重放一个用例；{@code skipErrors} 时跳过 PHP 报错的操作（但仍按顺序执行，保持分页状态）。返回比较过的操作数。 */
    private static int replay(JsonNode c, boolean skipErrors) {
        String name = c.get("name").asString();
        Map<String, String> node = new LinkedHashMap<>();
        for (JsonNode kv : c.get("node")) {
            node.put(kv.get(0).asString(), utf8(kv.get(1).asString()));
        }
        Map<String, byte[]> pages = new HashMap<>();
        for (JsonNode p : c.get("pages")) {
            pages.put(latin1(p.get(0).asString()), b64(p.get(1).asString()));
        }
        List<String> fetched = new ArrayList<>();
        CjRules rules = new CjRules(node, url -> {
            fetched.add(url);
            return pages.get(url);
        }, () -> 1_700_000_000L);
        int compared = 0;
        for (JsonNode op : c.get("ops")) {
            fetched.clear();
            String kind = op.get("op").asString();
            String url = op.get("url").isNull() ? null : latin1(op.get("url").asString());
            Object actual = switch (kind) {
                case "url_list" -> phpList(rules.urlList());
                case "url_lists" -> links(rules.urlLists(url));
                default -> rules.content(url);
            };
            if (skipErrors && op.has("error")) {
                continue;
            }
            assertThat(actual).as(name + " " + kind).isEqualTo(typed(op.get("result")));
            if (kind.equals("content")) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> data = (Map<Object, Object>) actual;
                String json = CjRules.json(data);
                JsonNode expected = op.get("json");
                assertThat(json).as(name + " json").isEqualTo(expected.isNull() ? "" : utf8(expected.asString()));
            }
            List<String> exp = new ArrayList<>();
            op.get("fetched").forEach(f -> exp.add(latin1(f.asString())));
            assertThat(fetched).as(name + " fetched").isEqualTo(exp);
            compared++;
        }
        return compared;
    }

    private static Map<Object, Object> phpList(List<String> l) {
        Map<Object, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < l.size(); i++) {
            m.put((long) i, l.get(i));
        }
        return m;
    }

    private static Object links(LinkedHashMap<Long, CjRules.Link> l) {
        if (l == null) {
            return false;
        }
        Map<Object, Object> m = new LinkedHashMap<>();
        l.forEach((k, v) -> {
            Map<Object, Object> one = new LinkedHashMap<>();
            one.put("url", v.url());
            one.put("title", v.title());
            m.put(k, one);
        });
        return m;
    }

    /** 带类型的值：字符串值按字节（ISO-8859-1），数组键按 UTF-8。 */
    static Object typed(JsonNode t) {
        String type = t.get(0).asString();
        return switch (type) {
            case "s" -> latin1(t.get(1).asString());
            case "i" -> Long.parseLong(t.get(1).asString());
            case "b" -> t.get(1).asBoolean();
            case "n" -> null;
            case "a" -> {
                Map<Object, Object> m = new LinkedHashMap<>();
                for (JsonNode e : t.get(1)) {
                    Object k = e.get(0).asString().equals("i") ? (Object) Long.parseLong(e.get(1).asString()) : utf8(e.get(1).asString());
                    m.put(k, typed(e.get(2)));
                }
                yield m;
            }
            default -> throw new IllegalArgumentException(type);
        };
    }

    private static JsonNode load(String f) throws IOException {
        return JSON.readTree(Files.readString(DIR.resolve(f), StandardCharsets.UTF_8));
    }

    private static byte[] b64(String s) {
        return Base64.getDecoder().decode(s);
    }

    private static String latin1(String b64) {
        return new String(b64(b64), StandardCharsets.ISO_8859_1);
    }

    private static String utf8(String b64) {
        return new String(b64(b64), StandardCharsets.UTF_8);
    }
}
