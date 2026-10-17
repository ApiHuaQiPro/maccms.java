p = 'backend/src/main/java/com/maccms/backend/site/application/LegacyUrlParser.java'
s = open(p, encoding='utf-8').read()


def rep(old, new):
    global s
    assert old in s, old[:100]
    s = s.replace(old, new)


rep('''    /** 搜索页接受的查询串键（旧参数名）。 */
    private static final Set<String> SEARCH_REQUEST_KEYS = Set.of("wd", "area", "lang", "year", "level", "class",
            "by", "order", "page");
    /** 出现即交 PHP 的键：兼容模式 s，以及旧站支持、首批不承接的参数（09 方案第 7.2.3 节步骤 4）。 */
    private static final Set<String> SEARCH_HANDOFF_KEYS = Set.of("s", "actor", "director", "tag", "letter", "state",
            "version", "tid", "name", "ids", "isend", "ajax");''',
'''    /**
     * 搜索页接受的查询串键（旧参数名）。B05 起承接 B04 的 actor/director/tag/letter/state/version/name/ids 与 tid
     * （docs/architecture/15-b05-readonly-pages.md 第 3.1 节）。
     */
    private static final Set<String> SEARCH_REQUEST_KEYS = Set.of("wd", "area", "lang", "year", "level", "class",
            "by", "order", "page", "actor", "director", "tag", "letter", "state", "version", "name", "ids", "tid");
    /**
     * 出现即交 PHP 的键：只剩 ThinkPHP 兼容模式 {@code s}（它改变的是路由而不是条件，Request.php:403-406）。B05 之前交 PHP 的
     * 其余键已由 Java 承接；{@code isend}、{@code ajax} 旧代码读不到或不使用（15 文档第 3.1 节），按未知参数 400。
     */
    private static final Set<String> SEARCH_HANDOFF_KEYS = Set.of("s");
    /** 按旧站 trim + {@code !empty()} 归一的文本键（application/common.php:1580-1604；Vod.php:167-247 只在非空时覆盖）。 */
    private static final List<String> SEARCH_TEXT_KEYS = List.of("wd", "area", "lang", "year", "class", "letter",
            "state", "version", "actor", "director", "tag", "name", "ids", "by", "order");''')

rep('''     * <p>只认路径 {@code /index.php/vod/search.html}、{@code /index.php/vod/search/}、{@code /index.php/vod/search}
     * （{@code rewrite} 时另加去掉 {@code /index.php} 的三种）；路径参数形式、别名、{@code -} 分隔符、大写、其他后缀，
     * 查询串超过 {@link #MAX_SEARCH_QUERY}，含 {@code s} 或旧站支持而首批不支持的键，以及 {@code by=rnd} → empty（交 PHP）。
     * 未知键、重复键、值不能按表单规则解码一次为合法 UTF-8、页码不合法、条件不满足 content 的规则（{@link VideoFilter}、
     * {@link VideoListQuery}）→ INVALID_PARAMETER（搜索页显示 400，不回显输入）。值只解码一次，不做二次解码。
     */''', '''     * <p>只认路径 {@code /index.php/vod/search.html}、{@code /index.php/vod/search/}、{@code /index.php/vod/search}
     * （{@code rewrite} 时另加去掉 {@code /index.php} 的三种）；路径参数形式、别名、{@code -} 分隔符、大写、其他后缀，
     * 查询串超过 {@link #MAX_SEARCH_QUERY}，含 {@code s} → empty（交 PHP）。
     * 未知键、重复键、值不能按表单规则解码一次为合法 UTF-8、页码不合法、条件不满足 content 的规则（{@link VideoFilter}、
     * {@link VideoListQuery}）或编码后超出链接预算 → INVALID_PARAMETER（搜索页显示 400，不回显输入）。值只解码一次，不做二次解码。
     *
     * <p>B05 逐键零值归一（队长裁定 Q2，15 文档第 3.2 节）：文本键去首尾 W 后 {@code ''}/{@code '0'} 为未传；{@code level}、
     * {@code tid} 按旧 {@code mac_param_url} 的 {@code intval}（{@link PhpIntval}），结果为 0 时未传，{@code tid} 为负数照常筛选；
     * {@code year} 为 {@code 0000}、{@code 0000-0000}（旧版 intval 后为空）时未传。{@code by=rnd} 为随机排序。
     */''')

rep('''        // 步骤 6：rnd（随机页/随机 id 段）留在 PHP
        if (decoded.containsKey("by") && SearchFilters.stripW(decoded.get("by")).equals("rnd")) {
            return Optional.empty();
        }
        // 步骤 7：旧站式归一（trim + !empty()），页码另按现有解析器的规则
        String wd = normalized(decoded.get("wd"));
        String area = normalized(decoded.get("area"));
        String lang = normalized(decoded.get("lang"));
        String year = normalized(decoded.get("year"));
        String level = normalized(decoded.get("level"));
        String cls = normalized(decoded.get("class"));
        String by = normalized(decoded.get("by"));
        String order = normalized(decoded.get("order"));
        String rawPage = decoded.get("page");''', '''        // 步骤 7：旧站式归一（文本键 trim + !empty()；level、tid 为 intval；year 的 intval 为 0 时不筛选），页码另按现有规则
        Map<String, String> text = new HashMap<>();
        for (String key : SEARCH_TEXT_KEYS) {
            text.put(key, normalized(decoded.get(key)));
        }
        if (text.get("year") != null && SearchFilters.yearIsZero(text.get("year"))) {
            text.put("year", null);
        }
        boolean random = "rnd".equals(text.get("by"));
        String by = random ? null : text.get("by");
        String order = text.get("order");
        String level = intvalNonZero(decoded.get("level"));
        String tidValue = intvalNonZero(decoded.get("tid"));
        String rawPage = decoded.get("page");''')

rep('''        // 步骤 8：交给 content 的同一套校验（既有参数在前，与 API 的顺序一致）；开关由 content 在查询时判断
        VideoListQuery sorting = VideoListQuery.parse(null, by, order, null, null);
        VideoFilter filter = VideoFilter.parse(wd, area, lang, year, level, cls, true);
        SearchFilters filters = new SearchFilters(filter.keyword(), filter.area(), filter.lang(), filter.year(),
                filter.level(), filter.classes(), by == null ? null : sorting.sort(),
                order == null ? null : sorting.descending() ? SearchFilters.SortOrder.DESC : SearchFilters.SortOrder.ASC);''',
'''        // 步骤 8：交给 content 的同一套校验（既有参数在前，与 API 的顺序一致）；开关由 content 在查询时判断
        VideoListQuery sorting = VideoListQuery.parse(null, by, order, null, null);
        Map<String, String> apiNames = Map.of("keyword", "wd", "classes", "class");
        VideoFilter filter = VideoFilter.parse(p -> {
            String v = p.equals("level") ? level : text.get(apiNames.getOrDefault(p, p));
            return VideoFilter.RawParam.of(v);
        }, true);
        SearchFilters filters;
        try {
            filters = new SearchFilters(filter.keyword(), filter.area(), filter.lang(), filter.year(), filter.level(),
                    filter.classes(), filter.letter(), filter.state(), filter.version(), filter.actor(),
                    filter.director(), filter.tag(), filter.name(), filter.ids(),
                    tidValue == null ? null : Long.parseLong(tidValue), by == null ? null : sorting.sort(), random,
                    order == null ? null : sorting.descending() ? SearchFilters.SortOrder.DESC
                            : SearchFilters.SortOrder.ASC);
        } catch (IllegalArgumentException e) {
            // 各值都已满足 content 规则，只剩编码后超出链接预算（SearchFilters 第 4 条不变式）
            throw ApiException.invalidParameter("query", "搜索条件过长");
        }''')

rep('''    /** 去掉首尾 W 字符；结果为 {@code ''} 或 {@code '0'} 时按未传（{@code null}）。 */''',
'''    /** 旧 {@code intval}（{@link PhpIntval}）；未出现或结果为 0 时为 {@code null}（未传），否则为十进制文本。 */
    private static String intvalNonZero(String v) {
        if (v == null) {
            return null;
        }
        long n = PhpIntval.of(v);
        return n == 0 ? null : Long.toString(n);
    }

    /** 去掉首尾 W 字符；结果为 {@code ''} 或 {@code '0'} 时按未传（{@code null}）。 */''')

rep('''import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.content.application.VideoFilter;''', '''import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.content.application.VideoFilter;''')
open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
