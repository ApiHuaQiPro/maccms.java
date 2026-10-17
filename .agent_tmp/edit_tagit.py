p = 'backend/src/test/java/com/maccms/backend/content/VideoTagGoldenMySqlIT.java'
s = open(p, encoding='utf-8').read()


def rep(old, new):
    global s
    assert old in s, old[:100]
    s = s.replace(old, new)


rep(''' * → 启动应用（不开 Web，打开搜索开关）→ 对 php-golden.json''', ''' * → 启动完整应用（打开搜索开关、site 与 SSR 搜索页）→ 对 php-golden.json''')
rep(''' * 与 total（count 与列表条件一致）；旧代码报错的用例断言新版在构造条件时拒绝；文档登记的差异逐条断言 → 请求前后全部表不变 → 删库前比对表结构指纹 →''',
    ''' * 与 total（count 与列表条件一致）；旧代码报错的用例断言新版在构造条件时拒绝；文档登记的差异逐条断言 → SSR 搜索页：每个 tid 用例以真实
 * HTTP 请求 {@code vod/search.html?tid=…}，沿页面实际生成的"下一页"链接取完所有页，ID 序列与 total 同上（链接往返）→ 请求前后全部表不变
 * → 删库前比对表结构指纹 →''')
rep('''import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;''', '''import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;''')
rep('''import java.io.InputStream;''', '''import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;''')
rep('''import tools.jackson.databind.JsonNode;''', '''import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import tools.jackson.databind.JsonNode;''')
rep('''    private static ContentReadService content;''', '''    private static ContentReadService content;
    private static String baseUrl;
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String SEARCH = "/index.php/vod/search.html";''')
rep('''                "--maccms.content.search.enabled=true",
                "--maccms.content.search.max-concurrent=2"));''', '''                "--server.port=0",
                "--server.address=127.0.0.1",
                "--maccms.content.search.enabled=true",
                "--maccms.content.search.max-concurrent=2",
                "--maccms.site.enabled=true",
                "--maccms.site.search.enabled=true",
                "--maccms.site.name=合成站",
                "--maccms.site.base-url=http://maccms.local.test/"));''')
rep('''        app = new SpringApplicationBuilder(MaccmsBackendApplication.class).web(WebApplicationType.NONE)
                .run(args.toArray(String[]::new));
        content = app.getBean(ContentReadService.class);''', '''        app = new SpringApplicationBuilder(MaccmsBackendApplication.class).run(args.toArray(String[]::new));
        content = app.getBean(ContentReadService.class);
        baseUrl = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();''')

rep('''    // ------------------------------------------------------------------ 条件换算（与旧标签调用链相同的顺序）''',
    '''    /**
     * SSR 搜索页的 tid（队长裁定 Q2）：真实 HTTP 下每个 tid 用例（旧 vod/search 的分页标签，URL 参数 tid）沿页面上实际生成的
     * "下一页"链接取完所有页，ID 序列 = 旧结果按新版可见范围过滤，"共 N 条" = total；canonical 为 intval 后的规范形态，
     * intval 为 0 时没有 tid 条件（与无条件的筛选结果相同）。
     */
    @Test
    @Order(3)
    void ssrSearchTidMatchesLegacyAcrossFollowedPages() throws Exception {
        Map<String, String> before = snapshot();
        JsonNode g = GOLDEN.get("php-golden.json");
        Map<Long, Integer> rowTypes = new HashMap<>();
        g.get("rowTypes").properties().forEach(e -> rowTypes.put(Long.parseLong(e.getKey()), e.getValue().asInt()));
        int n = 0;
        for (JsonNode c : g.get("cases")) {
            if (!c.get("kind").asString().equals("url") || !"vod/search".equals(lp(c, "pageurl")) || c.get("lp").has("isend")) {
                continue;
            }
            String raw = c.get("query").get("tid").asString();
            String id = c.get("id").asString();
            List<Long> expected = visible(c.get("ids"), rowTypes);
            long tid = PhpIntval.of(raw);
            String url = SEARCH + "?tid=" + URLEncoder.encode(raw, StandardCharsets.UTF_8);
            if (tid == 0 && raw.isEmpty()) {
                // tid= 空值：没有任何条件，搜索页显示表单与提示、不查库（MACC-20）
                Document empty = page(url, 200);
                assertThat(empty.select(".search-summary")).as(id).isEmpty();
                continue;
            }
            if (tid == 0) {
                // intval 为 0：不筛选（旧版同样不筛选）——条件为空，与无条件搜索页相同
                assertThat(page(url, 200).select(".search-summary")).as(id).isEmpty();
                continue;
            }
            List<Long> got = new ArrayList<>();
            Document doc = page(url, 200);
            assertThat(doc.selectFirst("link[rel=canonical]").attr("href")).as(id + " canonical")
                    .isEqualTo("http://maccms.local.test/index.php/vod/search.html?tid=" + tid);
            assertThat(doc.selectFirst(".search-summary").text()).as(id + " total").isEqualTo("共 " + expected.size() + " 条");
            assertThat(doc.selectFirst("form.search-form input[name=tid]").attr("value")).as(id + " 隐藏字段")
                    .isEqualTo(Long.toString(tid));
            for (int guard = 0; guard < 10; guard++) {
                got.addAll(cardIds(doc));
                Element next = doc.selectFirst("a.pagination__link--next");
                if (next == null) {
                    break;
                }
                doc = page(next.attr("href"), 200);
            }
            assertThat(got).as(id + " SSR 沿分页链接取完的 ID 序列").isEqualTo(expected);
            n++;
        }
        assertThat(n).as("SSR tid 用例数").isGreaterThanOrEqualTo(15);
        // tid 与新键并存（同一组条件经 content 一次查询）、不可见分类的 tid 只命中 type_id_1 等于它的可见行
        Document combo = page(SEARCH + "?tid=1&letter=" + URLEncoder.encode("0-9", StandardCharsets.UTF_8), 200);
        assertThat(cardIds(combo)).isEmpty();
        Document hidden = page(SEARCH + "?tid=9", 200);
        assertThat(hidden.selectFirst(".search-summary").text()).isEqualTo("共 0 条");
        assertThat(hidden.select(".filter-group a.filter-chip--current")).extracting(Element::text).contains("分类 9");
        assertThat(snapshot()).as("请求前后一次性库所有表未变").isEqualTo(before);
    }

    private static Document page(String pathAndQuery, int status) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(r.statusCode()).as(pathAndQuery).isEqualTo(status);
        return Jsoup.parse(r.body());
    }

    private static List<Long> cardIds(Document doc) {
        List<Long> out = new ArrayList<>();
        doc.select(".video-grid a.video-card__link").forEach(a ->
                out.add(Long.parseLong(a.attr("href").replaceAll(".*/id/([0-9]+)\\\\.html$", "$1"))));
        return out;
    }

    // ------------------------------------------------------------------ 条件换算（与旧标签调用链相同的顺序）''')
open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
