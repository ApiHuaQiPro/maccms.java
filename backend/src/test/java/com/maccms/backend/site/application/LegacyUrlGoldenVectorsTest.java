package com.maccms.backend.site.application;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 用 PHP 黄金向量（src/test/resources/site/url-golden/php-vectors.json，由 src/test/php/mac_url_golden.php
 * 执行旧代码生成）锁定 {@link LegacyUrlBuilder} 与 {@link LegacyUrlParser}。每条向量都必须有明确的 Java 预期：
 * 逐字一致、有意拒绝（列出理由）或本批不实现（列出理由），新增向量而不补预期会失败。
 * 对照说明见 docs/frontend/02-url-golden-vectors.md。
 */
class LegacyUrlGoldenVectorsTest {

    private static JsonNode vectors;

    /** 生成向量 id → Java 调用。 */
    private static final Map<String, Function<LegacyUrlBuilder, String>> JAVA_BUILD = Map.ofEntries(
            entry("home.mac_path", LegacyUrlBuilder::home),
            entry("type.p1", b -> b.vodType(1, 1)),
            entry("type.p1.explicit", b -> b.vodType(1, 1)),
            entry("type.p2", b -> b.vodType(1, 2)),
            entry("type.p1000", b -> b.vodType(1, 1000)),
            entry("type.p0", b -> b.vodType(1, 0)),
            entry("type.pneg", b -> b.vodType(1, -1)),
            entry("type.max", b -> b.vodType(65535, 1)),
            entry("detail", b -> b.vodDetail(123)),
            entry("detail.min", b -> b.vodDetail(1)),
            entry("detail.max", b -> b.vodDetail(4294967295L)),
            entry("play.1-1", b -> b.vodPlay(123, 1, 1)),
            entry("play.2-10", b -> b.vodPlay(123, 2, 10)),
            entry("play.0-0", b -> b.vodPlay(123, 0, 0)),
            entry("search.empty", b -> b.vodSearch(Map.of(), 1)),
            entry("search.wd", b -> b.vodSearch(Map.of("wd", "合成关键词"), 1)),
            entry("search.wd.p2", b -> b.vodSearch(Map.of("wd", "合成关键词"), 2)),
            entry("search.wd.zero", b -> b.vodSearch(Map.of("wd", "0"), 1)),
            entry("search.wd.space", b -> b.vodSearch(Map.of("wd", " a b "), 1)),
            entry("search.wd.reserved", b -> b.vodSearch(Map.of("wd", "a+b/c-d.e&f=g?h#i%j~k*l"), 1)),
            entry("search.wd.html", b -> b.vodSearch(Map.of("wd", "<b>\"x'</b>"), 1)),
            entry("search.wd.js", b -> b.vodSearch(Map.of("wd", "javascript:alert(1)"), 1)),
            entry("search.wd.dots", b -> b.vodSearch(Map.of("wd", ".."), 1)),
            entry("search.wd.dothtml", b -> b.vodSearch(Map.of("wd", "x.html"), 1)),
            entry("search.wd.dash", b -> b.vodSearch(Map.of("wd", "a-"), 1)),
            entry("search.wd.indexphp", b -> b.vodSearch(Map.of("wd", "index.php", "actor", "x"), 1)),
            entry("search.wd.ctrl", b -> b.vodSearch(Map.of("wd", "a\tb\nc"), 1)),
            entry("search.multi", b -> b.vodSearch(Map.of("wd", "合成", "year", "2020", "area", "大陆", "actor", "演员甲"), 1)),
            entry("show.none", b -> b.vodShow(1, Map.of(), 1)),
            entry("show.filters", b -> b.vodShow(1, Map.of("area", "大陆", "year", "2020"), 2)),
            entry("show.order", b -> b.vodShow(1, Map.of("by", "hits", "order", "asc", "class", "动作"), 1)),
            entry("user.login", b -> b.user("login")),
            entry("user.index", b -> b.user("index")));

    /** 本批不实现的生成向量（保留作旧行为记录）。 */
    private static final Map<String, String> NOT_IMPLEMENTED = Map.of(
            "index.p1", "首页链接用 MAC_PATH（home.mac_path）；mac_url('index/index') 只用于首页分页，首批首页不分页",
            "index.p2", "同上；route_status=1 时旧输出 /index.php/2.html 不能回到首页（往返向量）",
            "index.pagelink", "同上",
            "type.pagelink", "分页模板占位符，Java 分页直接按页生成 vodType(id, n)",
            "type.pagelink.n1", "旧分页第 1 页链接带 /page/1；Java 第 1 页用规范地址（与旧版的差异）",
            "type.pagelink.n3", "Java 用 vodType(1, 3)，与 type.p2 等同形");

    /** 有意拒绝（Java 抛 IllegalArgumentException），"*" 表示所有分支。 */
    private static final Map<String, String> JAVA_REJECTS = Map.ofEntries(
            entry("*:type.p0", "页码须 ≥1（旧代码把 0 当第 1 页）"),
            entry("*:type.pneg", "页码须 ≥1（旧代码输出 /page/-1，route 分支解析出 id=1-）"),
            entry("*:play.0-0", "sid/nid 须 ≥1（旧代码改成 1）"),
            entry("*:search.wd.reserved", "值含 / 编码为 %2F，Web 服务器解码后路径结构改变（往返向量 wd 被截断）"),
            entry("*:search.wd.html", "同上（</b> 中的 /）"),
            entry("*:search.wd.ctrl", "编码后的控制字符 %09 %0A"),
            entry("suffix_hide:search.wd.dots", "suffix_hide 把 ...html 变成 /../ 点段"),
            entry("suffix_hide:search.wd.dothtml", "suffix_hide 把 x.html.html 变成 x// 空段"));

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = LegacyUrlGoldenVectorsTest.class.getResourceAsStream("/site/url-golden/php-vectors.json")) {
            assertThat(in).as("黄金向量文件").isNotNull();
            vectors = JsonMapper.builder().build().readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static LegacyUrlStyle style(JsonNode config) {
        // 本批固定前提：安装目录 /、数字 id、动态页
        assertThat(config.get("site.install_dir").asString()).isEqualTo("/");
        assertThat(config.get("rewrite.type_id").asString()).isEqualTo("0");
        assertThat(config.get("rewrite.vod_id").asString()).isEqualTo("0");
        assertThat(config.get("view.vod_type").asString()).isEqualTo("0");
        assertThat(config.get("view.vod_detail").asString()).isEqualTo("0");
        assertThat(config.get("url_html_suffix").asString()).startsWith("html|");
        return new LegacyUrlStyle(config.get("rewrite.status").asString().equals("1"),
                config.get("rewrite.suffix_hide").asString().equals("1"),
                config.get("rewrite.route_status").asString().equals("1"),
                config.get("app.pathinfo_depr").asString().charAt(0));
    }

    private static List<JsonNode> branches() {
        List<JsonNode> list = new ArrayList<>();
        vectors.get("branches").forEach(list::add);
        return list;
    }

    @Test
    void vectorFileCoversRequiredBranches() {
        Set<String> names = new HashSet<>();
        for (JsonNode b : branches()) {
            names.add(b.get("branch").asString());
            for (JsonNode v : b.get("build")) {
                assertThat(v.get("check").asString()).isEqualTo("php-executed");
                assertThat(v.get("source").asString()).isNotBlank();
            }
            for (JsonNode v : b.get("parse")) {
                assertThat(v.get("check").asString()).isEqualTo("php-executed");
            }
        }
        assertThat(names).containsExactlyInAnyOrder("default", "rewrite", "suffix_hide", "route", "rewrite_route",
                "depr_dash");
        JsonNode def = branches().get(0);
        assertThat(def.get("branch").asString()).isEqualTo("default");
        assertThat(style(def.get("config"))).isEqualTo(LegacyUrlStyle.REPO_DEFAULT);
    }

    @Test
    void builderMatchesPhpOutputOrRejectsOnPurpose() {
        int compared = 0;
        for (JsonNode b : branches()) {
            String branch = b.get("branch").asString();
            LegacyUrlBuilder builder = new LegacyUrlBuilder(style(b.get("config")));
            for (JsonNode v : b.get("build")) {
                String id = v.get("id").asString();
                String php = v.get("output").asString();
                String where = branch + ":" + id;
                // 旧输出不受 Host、X-Forwarded-Host、HTTPS 影响
                assertThat(v.get("hostIndependent").asBoolean()).as(where).isTrue();
                if (NOT_IMPLEMENTED.containsKey(id)) {
                    continue;
                }
                assertThat(JAVA_BUILD).as("缺少 Java 预期：" + where).containsKey(id);
                Function<LegacyUrlBuilder, String> call = JAVA_BUILD.get(id);
                if (JAVA_REJECTS.containsKey("*:" + id) || JAVA_REJECTS.containsKey(where)) {
                    assertThatThrownBy(() -> call.apply(builder)).as(where).isInstanceOf(IllegalArgumentException.class);
                    continue;
                }
                String java = call.apply(builder);
                assertThat("/" + java).as(where).isEqualTo(php);
                assertThat(SafeUrl.parse("/" + java)).as(where).isPresent();
                compared++;
            }
        }
        assertThat(compared).isEqualTo(160);
    }

    @Test
    void onlyKnownVectorDependsOnRequestEntry() {
        for (JsonNode b : branches()) {
            for (JsonNode v : b.get("build")) {
                if (v.has("outputViaSiteRoot")) {
                    // search.wd.indexphp：值含 index.php 时，经站点根访问的旧输出缺少 /index.php（mac_url 尾部 strpos 判断），
                    // Java 取经 /index.php 的结果。index.pagelink：common.php:1765-1768 的 /PAGELINK.html 特殊分支只在
                    // 经站点根访问时生效，Java 不实现首页分页。
                    assertThat(v.get("id").asString()).isIn("search.wd.indexphp", "index.pagelink");
                }
            }
        }
    }

    @Test
    void generatedPageUrlsRoundTripInPhpAndJava() {
        for (JsonNode b : branches()) {
            String branch = b.get("branch").asString();
            LegacyUrlBuilder builder = new LegacyUrlBuilder(style(b.get("config")));
            LegacyUrlParser parser = new LegacyUrlParser(builder);
            for (JsonNode v : b.get("build")) {
                String id = v.get("id").asString();
                Optional<SiteRoute> route = pageRoute(id);
                if (route.isEmpty()) {
                    continue;
                }
                String where = branch + ":" + id;
                // PHP：生成的地址在同一配置下调度回同一页面与参数
                JsonNode rt = v.get("roundTrip");
                assertThat(rt).as(where).isNotNull();
                assertPhpDispatch(where, rt, route.get());
                // Java：解析生成器输出得到同一路由，且判为规范地址
                String path = "/" + builder.path(route.get());
                assertThat(path).as(where).isEqualTo(v.get("output").asString());
                assertThat(parser.parse(path, null)).as(where)
                        .hasValue(new LegacyUrlParser.Match(route.get(), path.substring(1), true));
            }
        }
    }

    private static Optional<SiteRoute> pageRoute(String id) {
        return Optional.ofNullable(switch (id) {
            case "home.mac_path" -> new SiteRoute.Home();
            case "type.p1", "type.p1.explicit" -> new SiteRoute.VodType(1, 1);
            case "type.p2" -> new SiteRoute.VodType(1, 2);
            case "type.p1000" -> new SiteRoute.VodType(1, 1000);
            case "type.max" -> new SiteRoute.VodType(65535, 1);
            case "detail" -> new SiteRoute.VodDetail(123);
            case "detail.min" -> new SiteRoute.VodDetail(1);
            case "detail.max" -> new SiteRoute.VodDetail(4294967295L);
            default -> null;
        });
    }

    /** Java 认得的路由必须与旧代码的调度一致（控制器/操作存在，id、page 相同）。 */
    private static void assertPhpDispatch(String where, JsonNode php, SiteRoute route) {
        assertThat(php.has("error")).as(where + " PHP 报错").isFalse();
        assertThat(php.get("handlerExists").asBoolean()).as(where).isTrue();
        JsonNode param = php.get("param");
        switch (route) {
            case SiteRoute.Home h -> assertThat(php.get("dispatch").asString()).as(where).isEqualTo("index/index");
            case SiteRoute.VodType t -> {
                assertThat(php.get("dispatch").asString()).as(where).isEqualTo("vod/type");
                assertThat(param.get("id").asString()).as(where).isEqualTo(Integer.toString(t.typeId()));
                assertThat(param.get("page").asInt()).as(where).isEqualTo(t.page());
            }
            case SiteRoute.VodDetail d -> {
                assertThat(php.get("dispatch").asString()).as(where).isEqualTo("vod/detail");
                assertThat(param.get("id").asString()).as(where).isEqualTo(Long.toString(d.vodId()));
            }
            // parse() 不产生搜索页路由（搜索页走 parseSearch，见 SearchLinkRoundTripTest）
            case SiteRoute.VodSearch s -> throw new AssertionError(where + " parse() 不应识别搜索页");
        }
    }

    // ---- 解析向量 ----

    /** Java 解析预期，按解析模式分组：slash（pathinfo_depr=/、无路由）、dash（pathinfo_depr=-）、route（route_status=1）。 */
    private static final Map<String, String> PARSE_SLASH = Map.ofEntries(
            entry("root", "home"), entry("root.slash", "home"), entry("index.module", "home"),
            entry("index.route", "unsupported"),
            entry("type", "type 1 1"), entry("type.p2", "type 1 2"), entry("type.p1", "type 1 1"),
            entry("type.p0", "invalid"), entry("type.pabc", "unsupported"), entry("type.p2x", "unsupported"),
            entry("type.order", "type 1 2"), entry("type.nosuffix", "type 1 1"), entry("type.slash", "type 1 1"),
            entry("type.htm", "unsupported"), entry("type.upper", "unsupported"), entry("type.lead0", "invalid"),
            entry("type.en", "unsupported"), entry("type.extra", "invalid"), entry("type.dup", "invalid"),
            entry("type.tags", "unsupported"), entry("type.query.page", "invalid"), entry("type.query.id", "invalid"),
            entry("type.query.other", "invalid"), entry("type.dash", "unsupported"), entry("type.route", "unsupported"),
            entry("type.route.p2", "unsupported"), entry("type.route.p1", "unsupported"),
            entry("detail", "detail 123"), entry("detail.nosuffix", "detail 123"), entry("detail.slash", "detail 123"),
            entry("detail.big", "invalid"), entry("detail.zero", "invalid"), entry("detail.en", "unsupported"),
            entry("detail.dash", "unsupported"), entry("detail.route", "unsupported"),
            entry("detail.s.slash", "detail 123"), entry("detail.s.slash.html", "detail 123"),
            entry("detail.s.dash", "unsupported"), entry("detail.s.route", "unsupported"),
            entry("play", "unsupported"), entry("play.route", "unsupported"), entry("search", "unsupported"),
            entry("search.route", "unsupported"), entry("show.route", "unsupported"), entry("user.login", "unsupported"),
            // MACC-20：搜索页的固定路径 + 查询串由 parseSearch 承接（SearchGoldenVectorsTest），parse() 仍不认识
            entry("search.q.base", "unsupported"), entry("search.q.wd", "unsupported"),
            entry("search.q.multi", "unsupported"), entry("search.q.slash", "unsupported"),
            entry("search.q.nosuffix", "unsupported"), entry("search.q.dothtml", "unsupported"),
            entry("search.q.backslash", "unsupported"), entry("search.q.trim", "unsupported"),
            entry("search.q.zero", "unsupported"), entry("search.q.levelpad", "unsupported"),
            entry("search.q.p1000", "unsupported"), entry("search.q.pct", "unsupported"),
            entry("search.q.plus", "unsupported"), entry("search.q.amp", "unsupported"));

    private static final Map<String, String> PARSE_DASH = override(PARSE_SLASH, Map.of(
            "type.dash", "type 1 2", "detail.dash", "detail 123", "detail.s.dash", "detail 123"));

    private static final Map<String, String> PARSE_ROUTE = route();

    private static Map<String, String> route() {
        Map<String, String> m = new LinkedHashMap<>();
        // 旧站开启路由后，模块路径 vod/type、vod/detail、index/index 直接 404（Route.php:1264-1273），Java 不认
        PARSE_SLASH.keySet().forEach(k -> m.put(k, "unsupported"));
        m.putAll(Map.of("root", "home", "root.slash", "home", "type.route", "type 1 1", "type.route.p2", "type 1 2",
                "type.route.p1", "type 1 1", "detail.route", "detail 123", "detail.s.route", "detail 123"));
        return m;
    }

    private static Map<String, String> override(Map<String, String> base, Map<String, String> changes) {
        Map<String, String> m = new LinkedHashMap<>(base);
        m.putAll(changes);
        return m;
    }

    @Test
    void parserFollowsExpectationsAndNeverContradictsPhp() {
        int matched = 0;
        for (JsonNode b : branches()) {
            String branch = b.get("branch").asString();
            LegacyUrlStyle style = style(b.get("config"));
            LegacyUrlParser parser = new LegacyUrlParser(new LegacyUrlBuilder(style));
            Map<String, String> expected = style.routeAliases() ? PARSE_ROUTE : style.depr() == '-' ? PARSE_DASH
                    : PARSE_SLASH;
            for (JsonNode v : b.get("parse")) {
                String id = v.get("id").asString();
                String where = branch + ":" + id;
                assertThat(expected).as("缺少 Java 解析预期：" + where).containsKey(id);
                String[] request = rawRequest(v.get("input"));
                String actual = outcome(parser, request[0], request[1]);
                assertThat(actual).as(where + " " + request[0] + "?" + request[1]).isEqualTo(expected.get(id));
                Optional<SiteRoute> route = toRoute(actual);
                if (route.isPresent()) {
                    // 健全性：Java 认得的请求，旧代码必然调度到同一页面与参数
                    assertPhpDispatch(where, v, route.get());
                    matched++;
                }
            }
        }
        assertThat(matched).isGreaterThan(40);
    }

    /** 按浏览器的方式把向量输入还原为原始请求：PATH_INFO 拼在 /index.php 后并做百分号编码；?s= 与其他参数按表单编码。 */
    private static String[] rawRequest(JsonNode in) {
        String path;
        List<String> query = new ArrayList<>();
        if (in.has("s")) {
            path = "/";
            query.add("s=" + LegacyUrlBuilder.urlencode(in.get("s").asString()));
        } else {
            path = "/index.php" + encodePath(in.get("pathInfo").asString());
        }
        if (in.has("query")) {
            in.get("query").properties().forEach(e -> query.add(e.getKey() + "=" + e.getValue().asString()));
        }
        return new String[] {path, String.join("&", query)};
    }

    private static String encodePath(String p) {
        StringBuilder sb = new StringBuilder();
        for (byte x : p.getBytes(StandardCharsets.UTF_8)) {
            int c = x & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "-._~/".indexOf(c) >= 0) {
                sb.append((char) c);
            } else {
                sb.append(String.format("%%%02X", c));
            }
        }
        return sb.toString();
    }

    static String outcome(LegacyUrlParser parser, String path, String query) {
        try {
            Optional<LegacyUrlParser.Match> m = parser.parse(path, query);
            if (m.isEmpty()) {
                return "unsupported";
            }
            return switch (m.get().route()) {
                case SiteRoute.Home h -> "home";
                case SiteRoute.VodType t -> "type " + t.typeId() + " " + t.page();
                case SiteRoute.VodDetail d -> "detail " + d.vodId();
                case SiteRoute.VodSearch s -> "search";
            };
        } catch (ApiException e) {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMETER);
            return "invalid";
        }
    }

    private static Optional<SiteRoute> toRoute(String outcome) {
        String[] p = outcome.split(" ");
        return switch (p[0]) {
            case "home" -> Optional.of(new SiteRoute.Home());
            case "type" -> Optional.of(new SiteRoute.VodType(Integer.parseInt(p[1]), Integer.parseInt(p[2])));
            case "detail" -> Optional.of(new SiteRoute.VodDetail(Long.parseLong(p[1])));
            default -> Optional.empty();
        };
    }
}
