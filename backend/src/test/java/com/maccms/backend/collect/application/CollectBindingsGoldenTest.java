package com.maccms.backend.collect.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 采集分类绑定键的黄金向量（B17，25 文档第 4 节）：PHP 8.0 与 7.3 的后台黄金（{@code admin_collect_golden.php}）里，采集源列表页的
 * "进入资源库"链接带着旧模板算出的 {@code cjflag=md5($vo.collect_url)}，collect/bind 写出的 bind.php 键是 {@code cjflag_远程分类}。新版对同样的
 * 地址（含中文、空格、引号、尖括号）算出的标识与键逐个相同；绑定值的取法 {@code intval($bind_list[$key]) > 0} 也照旧。
 */
class CollectBindingsGoldenTest {

    private static final Pattern CJFLAG = Pattern.compile("[?&]cjflag=([0-9a-f]{32})&cjurl=([^&\"]*)");

    private static JsonNode golden(String v) {
        try (InputStream in = CollectBindingsGoldenTest.class.getClassLoader()
                .getResourceAsStream("admin/collect/php" + v + "-golden.json")) {
            return JsonMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode scenario(JsonNode g, String label) {
        for (JsonNode s : g.get("scenarios")) {
            if (s.get("label").asString().equals(label)) {
                return s;
            }
        }
        throw new IllegalArgumentException(label);
    }

    @Test
    void flagsInTheLegacyListPageMatch() {
        for (String v : List.of("80", "73")) {
            JsonNode s = scenario(golden(v), "collect-list");
            List<String> urls = new ArrayList<>();
            for (JsonNode r : s.get("seed").get("mac_collect")) {
                urls.add(new String(HexFormat.of().parseHex(r.get("collect_url").asString()), StandardCharsets.UTF_8));
            }
            String page = new String(Base64.getDecoder().decode(s.get("steps").get(1).get("body").asString()), StandardCharsets.UTF_8);
            Map<String, String> fromPhp = new LinkedHashMap<>();
            Matcher m = CJFLAG.matcher(page.replace("&amp;", "&"));
            while (m.find()) {
                fromPhp.put(java.net.URLDecoder.decode(m.group(2), StandardCharsets.UTF_8), m.group(1));
            }
            assertThat(fromPhp.keySet()).as("PHP " + v + " 列表页里每个采集源都有链接").containsExactlyInAnyOrderElementsOf(urls);
            fromPhp.forEach((url, flag) -> assertThat(CollectBindings.flag(url)).as(url).isEqualTo(flag));
        }
    }

    @Test
    void keysWrittenByLegacyBindMatch() {
        for (String v : List.of("80", "73")) {
            JsonNode s = scenario(golden(v), "bind");
            Set<String> keys = new LinkedHashSet<>();
            for (JsonNode st : s.get("steps")) {
                String body = new String(Base64.getDecoder().decode(st.get("body").asString()), StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("\"id\":\"([0-9a-f]{32})_(\\d+)\"").matcher(body);
                if (m.find()) {
                    keys.add(m.group(1) + "_" + m.group(2));
                }
            }
            assertThat(keys).isNotEmpty();
            String a = CollectBindings.flag("http://res.example.test/api.php/provide/vod/");
            String b = CollectBindings.flag("https://json.example.test/api?ac=list&x=中文 空格");
            for (String k : keys) {
                String remote = k.substring(33);
                assertThat(k).isIn(CollectBindings.key(a, remote), CollectBindings.key(b, remote));
            }
            // 旧 bind.php 的预置内容里也是这两个标识
            String bind0 = new String(Base64.getDecoder().decode(s.get("bind0").asString()), StandardCharsets.UTF_8);
            assertThat(bind0).contains("'" + CollectBindings.key(a, "1") + "' => 1", "'" + CollectBindings.key(b, "5") + "' => 6");
        }
    }

    @Test
    void localTypeIdFollowsIntvalGreaterThanZero() {
        Map<Object, PhpValue> m = new LinkedHashMap<>();
        m.put("f_1", new PhpValue.PhpInt(5));
        m.put("f_2", new PhpValue.PhpInt(0));
        m.put("f_3", new PhpValue.PhpString("7abc"));
        m.put("f_4", new PhpValue.PhpInt(-3));
        m.put(15L, new PhpValue.PhpInt(2));
        PhpArray bind = new PhpArray(m);
        assertThat(CollectBindings.localTypeId(bind, "f_1")).isEqualTo(5);
        assertThat(CollectBindings.localTypeId(bind, "f_2")).isZero();
        assertThat(CollectBindings.localTypeId(bind, "f_3")).isEqualTo(7);
        assertThat(CollectBindings.localTypeId(bind, "f_4")).isZero();
        assertThat(CollectBindings.localTypeId(bind, "f_9")).isZero();
        assertThat(CollectBindings.localTypeId(bind, "15")).as("数字串键与整数键是同一个键").isEqualTo(2);
        assertThat(CollectBindings.localTypeId(null, "f_1")).isZero();
    }
}
