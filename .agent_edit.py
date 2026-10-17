import io

def edit(path, pairs):
    s = io.open(path, encoding='utf-8').read()
    for old, new in pairs:
        assert old in s, (path, old[:80])
        s = s.replace(old, new, 1)
    io.open(path, 'w', encoding='utf-8', newline='\n').write(s)

edit('backend/src/main/java/com/maccms/backend/site/web/SiteContentController.java', [
    ('''    private final SiteHtmlRenderer renderer;
    private final LegacyUrlParser parser;
    private final ContentPages pages;

    public SiteContentController(SiteHtmlRenderer renderer, SitePages sitePages, LegacyUrlBuilder urls,
                                 LegacyUrlParser parser, ImageUrlNormalizer images, RichTextCache richText,
                                 ContentReadService content) {
        this.renderer = renderer;
        this.parser = parser;
        this.pages = new ContentPages(content, sitePages, urls, images, richText);
    }
''', '''    private final SiteHtmlRenderer renderer;
    private final LegacyUrlParser parser;
    private final ContentPages pages;
    private final ObjectProvider<SiteSearchController> search;
    private final ObjectProvider<SitePlaybackController> playback;
    private final ObjectProvider<SiteLibraryController> library;

    public SiteContentController(SiteHtmlRenderer renderer, SitePages sitePages, LegacyUrlBuilder urls,
                                 LegacyUrlParser parser, ImageUrlNormalizer images, RichTextCache richText,
                                 ContentReadService content, ObjectProvider<SiteSearchController> search,
                                 ObjectProvider<SitePlaybackController> playback,
                                 ObjectProvider<SiteLibraryController> library) {
        this.renderer = renderer;
        this.parser = parser;
        this.pages = new ContentPages(content, sitePages, urls, images, richText);
        this.search = search;
        this.playback = playback;
        this.library = library;
    }
'''),
    ('''        String rawPath = request.getRequestURI().substring(request.getContextPath().length());
        SiteRoute route = parser.parse(rawPath, request.getQueryString())''',
     '''        String rawPath = request.getRequestURI().substring(request.getContextPath().length());
        // ThinkPHP 兼容模式 ?s=vod/search&…（B05）：入口路径上指向搜索页的 s 由搜索页处理（搜索页未开启时 404）
        if (request.getQueryString() != null && request.getQueryString().contains("s=")) {
            SiteSearchController s = search.getIfAvailable();
            if (s != null && parser.parseSearch(rawPath, request.getQueryString()).isPresent()) {
                return s.search(request);
            }
        }
        SiteRoute route = parser.parse(rawPath, request.getQueryString())'''),
    ('''            // B05 的其他页面由各自的 Controller 按路径承接；经 ?s= 兼容模式到达这里时不在本 Controller 渲染
            case SiteRoute.Legacy l -> throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");''',
     '''            // B05 的其他页面经 ?s= 兼容模式到达入口路径时，交给承接该页面的 Controller（它们按同一请求重新解析）
            case SiteRoute.Legacy l -> l.page().episode() ? playback.getObject().page(request)
                    : library.getObject().page(request);'''),
    ('''import org.springframework.http.ResponseEntity;''', '''import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;'''),
])

edit('backend/src/test/java/com/maccms/backend/site/application/LegacyUrlParserSearchB05Test.java', [
    ('''    /** ThinkPHP 兼容模式 s 仍交 PHP；isend、ajax 旧代码读不到或不使用，按未知参数 400（不静默丢弃）。 */
    @Test
    void onlyCompatModeIsHandedOffAndUnusedLegacyKeysAre400() {
        for (LegacyUrlStyle style : STYLES) {
            assertThat(parser(style).parseSearch(base(style), "s=vod/search&wd=x")).isEmpty();
            for (String q : List.of("isend=1", "ajax=1", "isend=0&wd=x", "mid=1", "sid=1", "nid=1", "starsign=x")) {''',
     '''    /**
     * ThinkPHP 兼容模式 s：值是搜索页路径时按搜索页处理（入口路径与搜索路径都可），指向其他页面时交还 PHP；isend 旧代码读不到，
     * 按未知参数 400（不静默丢弃）；ajax 按 D-B05-6 接受并忽略（见 {@link #ajaxIsAcceptedAndIgnoredLikeLegacyIntval}）。
     */
    @Test
    void compatModeAndUnusedLegacyKeys() {
        for (LegacyUrlStyle style : STYLES) {
            LegacyUrlParser p = parser(style);
            var viaSearchPath = p.parseSearch(base(style), "s=vod/search&wd=x").orElseThrow();
            assertThat(((SiteRoute.VodSearch) viaSearchPath.route()).filters().keyword()).isEqualTo("x");
            assertThat(viaSearchPath.canonical()).isFalse();
            for (String s : List.of("s=vod/search", "s=%2Fvod%2Fsearch.html", "s=vod/search/")) {
                var m = p.parseSearch("/index.php", s + "&wd=y&page=2").orElseThrow();
                assertThat(((SiteRoute.VodSearch) m.route()).filters().keyword()).as(s).isEqualTo("y");
                assertThat(((SiteRoute.VodSearch) m.route()).page()).isEqualTo(2);
            }
            assertThat(p.parseSearch("/", "s=vod/search&wd=y")).isPresent();
            // 入口路径没有 s、s 指向其他页面：不是搜索页
            assertThat(p.parseSearch("/index.php", "wd=y")).isEmpty();
            for (String q : List.of("s=vod/detail/id/1&wd=x", "s=/vod/searchx", "s=%FF", "wd=x&s=vod/type/id/1")) {
                assertThat(p.parseSearch(base(style), q)).as(q).isEmpty();
                assertThat(p.parseSearch("/index.php", q)).as(q).isEmpty();
            }
            assertThat(invalid(style, "s=vod/search&s=vod/search").field()).isEqualTo("s");
            for (String q : List.of("isend=1", "isend=0&wd=x", "mid=1", "sid=1", "nid=1", "starsign=x")) {'''),
    ('''    // ---------------------------------------------------------------- 逐键零值归一''',
     '''    /**
     * D-B05-6：旧 mac_param_url 对 ajax 只做 intval（application/common.php:1568），不进 listCacheData。新版接受任意取值（0、非数字、负数）
     * 并忽略：条件、链接、canonical、隐藏字段都与不带 ajax 时相同；重复、非法编码、总长度限制照旧；不因此放宽其他未知键。
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ajax=1", "ajax=0", "ajax=abc", "ajax=-1", "ajax=", "ajax"})
    void ajaxIsAcceptedAndIgnoredLikeLegacyIntval(String ajax) {
        for (LegacyUrlStyle style : STYLES) {
            LegacyUrlParser p = parser(style);
            var with = p.parseSearch(base(style), "wd=x&" + ajax + "&page=2").orElseThrow();
            var without = p.parseSearch(base(style), "wd=x&page=2").orElseThrow();
            assertThat(with.route()).isEqualTo(without.route());
            assertThat(with.canonical()).isFalse();
            assertThat(without.canonical()).isTrue();
            SearchFilters f = ((SiteRoute.VodSearch) with.route()).filters();
            assertThat(f.params()).doesNotContainKey("ajax");
            // 只有 ajax 时是无条件页
            assertThat(((SiteRoute.VodSearch) p.parseSearch(base(style), ajax).orElseThrow().route()).filters().isEmpty())
                    .isTrue();
        }
    }

    @Test
    void ajaxKeepsDuplicateEncodingAndUnknownKeyRules() {
        for (LegacyUrlStyle style : STYLES) {
            assertThat(invalid(style, "ajax=1&ajax=1").field()).isEqualTo("ajax");
            assertThat(invalid(style, "ajax=%FF").field()).isEqualTo("ajax");
            assertThat(invalid(style, "ajax=1&isend=1").field()).isEqualTo("query");
            assertThat(invalid(style, "ajax=1&foo=1").message()).isEqualTo("不支持的参数");
            assertThat(parser(style).parseSearch(base(style), "ajax=" + "1".repeat(2050))).isEmpty();
        }
    }

    // ---------------------------------------------------------------- 逐键零值归一'''),
])

edit('backend/src/test/java/com/maccms/backend/site/application/LegacyUrlParserSearchTest.java', [
    ('''            for (String q : List.of("s=/vod/detail/id/1", "wd=x&s=vod/search", "foo=1&s=x", "wd=%FF&s=x")) {
                assertThat(parser(style).parseSearch(base(style), q)).as(q).isEmpty();
            }
            for (String q : List.of("isend=1", "ajax=1", "foo=1&actor=x")) {''',
     '''            for (String q : List.of("s=/vod/detail/id/1", "foo=1&s=x", "wd=%FF&s=x")) {
                assertThat(parser(style).parseSearch(base(style), q)).as(q).isEmpty();
            }
            // B05：s 指向搜索页时按搜索页处理（LegacyUrlParserSearchB05Test）；ajax 接受并忽略（D-B05-6）
            assertThat(parser(style).parseSearch(base(style), "wd=x&s=vod/search")).isPresent();
            for (String q : List.of("isend=1", "foo=1&actor=x")) {'''),
])
print('ok')
