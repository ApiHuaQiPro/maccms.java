package com.maccms.backend.staticgen.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.staticgen.application.StaticPaths.Item;
import com.maccms.backend.staticgen.application.StaticPaths.Topic;
import com.maccms.backend.staticgen.application.StaticPaths.TypeRef;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * 静态页地址与文件名对 PHP 黄金向量逐条比较（{@code src/test/php/b16_static_timming_golden.php} 在 PHP 8.0.30 上生成，7.3.33 结果相同）。
 * 合成数据与生成脚本里的 {@code $typeTop}、{@code $vodSub} 等一一对应。
 */
class StaticPathsGoldenTest {

    static final ZoneId PRC = ZoneId.of("Asia/Shanghai");

    private static final TypeRef TYPE_TOP = new TypeRef(1, "dianying", 0, 1);
    private static final TypeRef TYPE_SUB = new TypeRef(6, "dongzuopian", 1, 1);
    private static final TypeRef ART_TYPE = new TypeRef(21, "zixun", 0, 2);
    private static final Item VOD_SUB = new Item(123, "hecheng-shipin", 1700000000L, 6, TYPE_SUB, TYPE_TOP);
    private static final Item VOD_TOP = new Item(9, "dingji", 1704038400L, 1, TYPE_TOP, null);
    private static final Item ART = new Item(55, "wenzhang55", 1700000000L, 21, ART_TYPE, null);
    private static final Topic TOPIC = new Topic(7, "zhuanti7");

    private static final Map<String, Function<StaticPaths, String>> CASES = new LinkedHashMap<>();

    static {
        CASES.put("index", p -> StaticPaths.index(false));
        CASES.put("index.wap", p -> StaticPaths.index(true));
        CASES.put("map", p -> StaticPaths.map());
        CASES.put("rss.baidu", p -> p.rss("baidu", 1));
        CASES.put("rss.index.p2", p -> p.rss("index", 2));
        CASES.put("label.dotted", p -> StaticPaths.label("about.html"));
        CASES.put("label.bare", p -> StaticPaths.label("about"));
        CASES.put("vod.type.top", p -> p.type("vod", TYPE_TOP, 1));
        CASES.put("vod.type.top.p2", p -> p.type("vod", TYPE_TOP, 2));
        CASES.put("vod.type.sub.p3", p -> p.type("vod", TYPE_SUB, 3));
        CASES.put("art.type", p -> p.type("art", ART_TYPE, 1));
        CASES.put("art.type.p2", p -> p.type("art", ART_TYPE, 2));
        CASES.put("vod.detail.sub", p -> p.vodDetail(VOD_SUB));
        CASES.put("vod.detail.top", p -> p.vodDetail(VOD_TOP));
        CASES.put("vod.play.1-1", p -> p.vodEpisode("play", VOD_SUB, 1, 1));
        CASES.put("vod.play.2-3", p -> p.vodEpisode("play", VOD_SUB, 2, 3));
        CASES.put("vod.play.top.1-12", p -> p.vodEpisode("play", VOD_TOP, 1, 12));
        CASES.put("vod.down.1-1", p -> p.vodEpisode("down", VOD_SUB, 1, 1));
        CASES.put("vod.down.3-2", p -> p.vodEpisode("down", VOD_TOP, 3, 2));
        CASES.put("art.detail.p1", p -> p.artDetail(ART, 1));
        CASES.put("art.detail.p2", p -> p.artDetail(ART, 2));
        CASES.put("topic.index.p1", p -> p.topicIndex(1));
        CASES.put("topic.index.p2", p -> p.topicIndex(2));
        CASES.put("topic.detail", p -> p.topicDetail(TOPIC));
    }

    static JsonNode golden() throws IOException {
        try (InputStream in = StaticPathsGoldenTest.class.getResourceAsStream("/staticgen/b16-php80-golden.json")) {
            return JsonMapper.builder().build().readTree(in);
        }
    }

    @Test
    void everyCaseMatchesPhp() throws IOException {
        int checked = 0;
        for (JsonNode branch : golden().get("paths")) {
            Map<Object, PhpValue> cfg = new LinkedHashMap<>();
            cfg.put("view", strings(branch.get("view")));
            cfg.put("path", strings(branch.get("path")));
            StaticPaths paths = new StaticPaths(new PhpArray(cfg), PRC);
            assertThat(branch.get("cases")).hasSize(CASES.size());
            for (JsonNode c : branch.get("cases")) {
                String id = c.get("id").asText();
                String label = branch.get("branch").asText() + " " + id;
                String url = CASES.get(id).apply(paths);
                assertThat(url).as(label + " url").isEqualTo(c.get("url").asText());
                // PHP 的文件名带 ./ 前缀
                assertThat("./" + paths.htmlFile(url)).as(label + " file").isEqualTo(c.get("file").asText());
                checked++;
            }
        }
        assertThat(checked).isEqualTo(3 * CASES.size());
    }

    @Test
    void nonStaticModesAndEmptyRulesAreRefused() {
        Map<Object, PhpValue> view = new LinkedHashMap<>();
        view.put("vod_type", new PhpValue.PhpString("3"));
        view.put("vod_detail", new PhpValue.PhpString("0"));
        view.put("vod_play", new PhpValue.PhpString("5"));
        view.put("topic_index", new PhpValue.PhpString("2"));
        Map<Object, PhpValue> path = new LinkedHashMap<>();
        path.put("topic_index", new PhpValue.PhpString(""));
        Map<Object, PhpValue> cfg = new LinkedHashMap<>();
        cfg.put("view", new PhpArray(view));
        cfg.put("path", new PhpArray(path));
        StaticPaths p = new StaticPaths(new PhpArray(cfg), PRC);
        assertThat(p.isStatic("vod_type")).isTrue();
        assertThatThrownBy(() -> p.type("vod", TYPE_TOP, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> p.vodDetail(VOD_TOP)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> p.vodEpisode("play", VOD_TOP, 1, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> p.topicIndex(1)).isInstanceOf(IllegalStateException.class);
    }

    private static PhpArray strings(JsonNode obj) {
        Map<Object, PhpValue> m = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = obj.properties().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            m.put(PhpArray.normalizeKey(e.getKey()), new PhpValue.PhpString(e.getValue().asText()));
        }
        return new PhpArray(m);
    }
}
