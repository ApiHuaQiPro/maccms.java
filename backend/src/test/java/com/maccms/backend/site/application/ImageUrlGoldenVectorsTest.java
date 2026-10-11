package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * mac_url_img 黄金向量（src/test/resources/site/image-url/mac-url-img-vectors.json）。每条向量同时断言：
 * <ol>
 * <li>旧语义：新版选出的分支与向量记录的一致；对不含 {@code <} 的输入，按旧 mac_filter_xss 与两次 str_replace
 * 处理新版的分支结果，得到的字符串等于 PHP 实际输出（php 字段由同目录 PHP 脚本从旧函数生成、复核）。</li>
 * <li>新输出：结果与拒绝原因等于 expect。</li>
 * <li>兼容关系：SAME / PROTOCOL_RELATIVE_COMPLETED 用 jsoup 按 HTML 属性规则解码 PHP 输出（独立于被测解码器），
 * 求出浏览器最终请求的地址，与新版 href 比较。</li>
 * </ol>
 */
class ImageUrlGoldenVectorsTest {

    private static final String RESOURCE = "/site/image-url/mac-url-img-vectors.json";

    private static JsonNode data;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = ImageUrlGoldenVectorsTest.class.getResourceAsStream(RESOURCE)) {
            assertThat(in).as(RESOURCE).isNotNull();
            data = JsonMapper.builder().build().readTree(in);
        }
    }

    @TestFactory
    Stream<DynamicTest> vectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode v : data.get("vectors")) {
            tests.add(DynamicTest.dynamicTest(v.get("id").stringValue(), () -> check(v)));
        }
        return tests.stream();
    }

    private static void check(JsonNode v) {
        JsonNode config = data.get("configs").get(v.get("config").stringValue());
        assertThat(config).as("config").isNotNull();
        JsonNode expect = v.get("expect");
        String outcome = expect.get("outcome").stringValue();
        String compat = v.get("compat").stringValue();
        String php = v.get("php").stringValue();
        String input = v.get("input").isNull() ? null : v.get("input").stringValue();

        if (outcome.equals("CONFIG_ERROR")) {
            assertThat(compat).isEqualTo("CONFIG_REJECTED");
            String key = expect.get("config_key").stringValue();
            String badValue = config.get("php").get(key.substring("upload.".length())).stringValue();
            assertThatThrownBy(() -> normalizer(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(key)
                    .satisfies(e -> {
                        if (!badValue.isEmpty()) {
                            assertThat(e.getMessage()).as("不回显取值").doesNotContain(badValue);
                        }
                    });
            return;
        }

        ImageUrlNormalizer normalizer = normalizer(config);
        ImageUrlNormalizer.Resolution r = normalizer.resolve(input);

        // 旧语义：分支与 PHP 输出
        assertThat(r.branch().name()).as("branch").isEqualTo(v.get("branch").stringValue());
        String candidate = normalizer.legacyCandidate(input);
        if (candidate.indexOf('<') >= 0) {
            assertThat(r.outcome()).isEqualTo(ImageUrlNormalizer.Outcome.TAG_LIKE);
        } else {
            assertThat(legacyFilter(candidate)).as("按旧过滤处理分支结果应等于 PHP 输出").isEqualTo(php);
        }

        // 新输出
        assertThat(r.outcome().name()).as("outcome").isEqualTo(outcome);
        if (outcome.equals("ACCEPTED")) {
            assertThat(r.url()).hasValueSatisfying(u -> assertThat(u.href()).isEqualTo(expect.get("href").stringValue()));
            assertThat(normalizer.normalize(input)).isEqualTo(r.url());
        } else {
            assertThat(r.url()).isEmpty();
            assertThat(normalizer.normalize(input)).isEmpty();
        }

        // 兼容关系
        String browser = browserView(php);
        switch (compat) {
            case "SAME" -> assertThat(r.url()).hasValueSatisfying(u -> assertThat(u.href()).isEqualTo(browser));
            case "PROTOCOL_RELATIVE_COMPLETED" -> {
                assertThat(browser).startsWith("//");
                String scheme = URI.create(config.get("java").get("base_url").stringValue()).getScheme();
                assertThat(r.url()).hasValueSatisfying(u -> assertThat(u.href()).isEqualTo(scheme + ":" + browser));
            }
            case "REJECTED" -> assertThat(r.outcome()).isNotEqualTo(ImageUrlNormalizer.Outcome.ACCEPTED);
            default -> throw new AssertionError("未知 compat：" + compat);
        }
    }

    @Test
    void vectorFileIsWellFormed() {
        Set<String> ids = new HashSet<>();
        Set<String> branches = new HashSet<>();
        Set<String> outcomes = new HashSet<>();
        Set<String> compats = new HashSet<>();
        for (JsonNode v : data.get("vectors")) {
            assertThat(ids.add(v.get("id").stringValue())).as("id 唯一").isTrue();
            assertThat(data.get("configs").has(v.get("config").stringValue())).isTrue();
            assertThat(v.get("evidence").stringValue()).contains("application/common.php:");
            assertThat(v.get("note").stringValue()).isNotBlank();
            assertThat(v.get("php").isString()).isTrue();
            branches.add(v.get("branch").stringValue());
            outcomes.add(v.get("expect").get("outcome").stringValue());
            compats.add(v.get("compat").stringValue());
        }
        Set<String> allBranches = new HashSet<>();
        EnumSet.allOf(ImageUrlNormalizer.Branch.class).forEach(b -> allBranches.add(b.name()));
        assertThat(branches).as("覆盖旧代码全部分支").containsExactlyInAnyOrderElementsOf(allBranches);
        Set<String> allOutcomes = new HashSet<>(Set.of("CONFIG_ERROR"));
        EnumSet.allOf(ImageUrlNormalizer.Outcome.class).forEach(o -> allOutcomes.add(o.name()));
        assertThat(outcomes).as("覆盖全部结果").containsExactlyInAnyOrderElementsOf(allOutcomes);
        assertThat(compats).containsExactlyInAnyOrder("SAME", "PROTOCOL_RELATIVE_COMPLETED", "REJECTED", "CONFIG_REJECTED");
    }

    /**
     * 固定向量只用合成值：地址类字段（输入、PHP 输出、期望、配置）中每个带点的名称，最后一段只能是保留顶级域 test
     * 或文件扩展名 jpg/php（旧代码直接拼接，可能紧跟其他字符，如 {@code example.testupload}），不会出现真实域名。
     */
    @Test
    void vectorFileUsesOnlySyntheticHosts() {
        List<String> fields = new ArrayList<>();
        for (JsonNode v : data.get("vectors")) {
            if (!v.get("input").isNull()) {
                fields.add(v.get("input").stringValue());
            }
            fields.add(v.get("php").stringValue());
            if (v.get("expect").has("href")) {
                fields.add(v.get("expect").get("href").stringValue());
            }
        }
        for (JsonNode c : data.get("configs")) {
            c.get("php").forEach(n -> fields.add(n.stringValue().replace("\\", "")));
            fields.add(c.get("java").get("base_url").stringValue());
        }
        Pattern dotted = Pattern.compile("(?i)[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.([a-z0-9-]+)");
        Map<String, String> labels = new LinkedHashMap<>();
        for (String f : fields) {
            Matcher m = dotted.matcher(f);
            while (m.find()) {
                labels.put(m.group(), m.group(1).toLowerCase(java.util.Locale.ROOT));
            }
        }
        assertThat(labels).isNotEmpty();
        assertThat(labels).allSatisfy((name, last) -> assertThat(last).as(name).matches("(test|jpg|php).*"));
    }

    private static ImageUrlNormalizer normalizer(JsonNode config) {
        JsonNode php = config.get("php");
        ImageUrlConfig c = new ImageUrlConfig(php.get("protocol").stringValue(), php.get("mode").stringValue(),
                php.get("remoteurl").stringValue(), php.get("img_key").stringValue(), php.get("img_api").stringValue());
        String baseUrl = config.get("java").get("base_url").stringValue();
        assertThat(URI.create(baseUrl).getRawPath()).as("base-url 路径应等于 install_dir")
                .isEqualTo(php.get("install_dir").stringValue());
        return ImageUrlNormalizer.create(c, site(baseUrl));
    }

    static SiteSettings site(String baseUrl) {
        return SiteSettings.from(new SiteProperties("true", "合成站", baseUrl, null, null, null, null, null, null, null,
                null));
    }

    /**
     * 旧 mac_url_img 第 1653-1655 行对不含 {@code <} 的字符串的效果：strip_tags 只删除 NUL，
     * htmlspecialchars(ENT_QUOTES)，PHP trim，再删除 {@code &quot;&gt;}、把 {@code &amp;} 换回 {@code &}。
     */
    private static String legacyFilter(String s) {
        String noNul = s.replace("\0", "");
        StringBuilder b = new StringBuilder();
        for (char c : noNul.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#039;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                default -> b.append(c);
            }
        }
        return ImageUrlNormalizer.phpTrim(b.toString()).replace("&quot;&gt;", "").replace("&amp;", "&");
    }

    /** 浏览器从 {@code src="<php 输出>"} 得到的地址：属性解码、去首尾 C0 控制字符与空格、空格编码。 */
    private static String browserView(String php) {
        String decoded = Parser.unescapeEntities(php, true);
        int start = 0;
        int end = decoded.length();
        while (start < end && decoded.charAt(start) <= 0x20) {
            start++;
        }
        while (end > start && decoded.charAt(end - 1) <= 0x20) {
            end--;
        }
        return decoded.substring(start, end).replace(" ", "%20");
    }
}
