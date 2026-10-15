package com.maccms.backend.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.content.application.VideoFilter;
import com.maccms.backend.content.application.VideoSort;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 用真实的 VodMapper.xml 与 VodMapper 接口（@Param 名称）生成 MyBatis BoundSql，锁定 SQL 文本（09 方案第 2.3、6.1 节）：
 * <ul>
 * <li>不带搜索/筛选条件的两条语句与契约 1.0.0 时逐字相同：黄金文件 {@code content/vod-mapper-legacy-sql.txt}
 * 由本任务修改 VodMapper.xml <b>之前</b>的代码生成（count 1 条 + 11 种排序 × 2 个方向的分页 22 条），按空白规范化后比较；</li>
 * <li>带条件的语句只多出固定的执行时限提示与固定列名的条件，用户输入只以 {@code ?} 绑定，不出现在 SQL 文本中。</li>
 * </ul>
 * 只解析 XML、不连数据库。
 */
class VodMapperBoundSqlTest {

    private static final String XML = "mapper/content/VodMapper.xml";
    private static final String LEGACY_GOLDEN = "content/vod-mapper-legacy-sql.txt";
    private static final List<Integer> TYPE_IDS = List.of(1, 6, 7);
    private static final String HINT = "/*+ MAX_EXECUTION_TIME(3000) */";

    private static Configuration configuration;

    @BeforeAll
    static void parse() throws Exception {
        configuration = new Configuration();
        try (InputStream in = resource(XML)) {
            new XMLMapperBuilder(in, configuration, XML, configuration.getSqlFragments()).parse();
        }
    }

    private static InputStream resource(String name) {
        InputStream in = VodMapperBoundSqlTest.class.getClassLoader().getResourceAsStream(name);
        assertThat(in).as(name).isNotNull();
        return in;
    }

    private static BoundSql bound(String method, Object... args) {
        Method m = Arrays.stream(VodMapper.class.getMethods()).filter(x -> x.getName().equals(method)).findFirst()
                .orElseThrow();
        Object param = new ParamNameResolver(configuration, m).getNamedParams(args);
        return configuration.getMappedStatement(VodMapper.class.getName() + "." + method).getBoundSql(param);
    }

    private static String sql(BoundSql b) {
        return String.join(" ", b.getSql().trim().split("\\s+"));
    }

    /** 按 DefaultParameterHandler 的取值方式列出绑定值（顺序即 ? 的顺序）。 */
    private static List<Object> values(BoundSql b) {
        MetaObject meta = configuration.newMetaObject(b.getParameterObject());
        List<Object> out = new ArrayList<>();
        for (ParameterMapping pm : b.getParameterMappings()) {
            String p = pm.getProperty();
            out.add(b.hasAdditionalParameter(p) ? b.getAdditionalParameter(p) : meta.getValue(p));
        }
        return out;
    }

    private static Map<String, String> legacyGolden() throws Exception {
        String text;
        try (InputStream in = resource(LEGACY_GOLDEN)) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, String> out = new LinkedHashMap<>();
        // lines() 同时识别 \n 与 \r\n：Windows 默认 core.autocrlf=true 检出时黄金文件为 CRLF
        List<String> lines = text.lines().toList();
        for (int i = 0; i + 1 < lines.size(); i += 2) {
            assertThat(lines.get(i)).startsWith("### ");
            out.put(lines.get(i).substring(4), lines.get(i + 1));
        }
        return out;
    }

    // ------------------------------------------------------------------ 不带条件：与 1.0.0 相同

    @Test
    void unfilteredStatementsAreUnchangedFromContract100() throws Exception {
        Map<String, String> golden = legacyGolden();
        assertThat(golden).hasSize(1 + VideoSort.values().length * 2);
        BoundSql count = bound("countPublished", List.of(1, 6));
        assertThat(sql(count)).isEqualTo(golden.get("countPublished"));
        assertThat(values(count)).containsExactly(1, 6);
        for (VideoSort s : VideoSort.values()) {
            for (boolean d : new boolean[]{true, false}) {
                BoundSql page = bound("selectPublishedPage", List.of(1, 6), s, d, 0L, 20);
                assertThat(sql(page)).as(s + (d ? " DESC" : " ASC"))
                        .isEqualTo(golden.get("selectPublishedPage " + s.name() + (d ? " DESC" : " ASC")))
                        .doesNotContain("MAX_EXECUTION_TIME");
                assertThat(values(page)).containsExactly(1, 6, 0L, 20);
            }
        }
    }

    // ------------------------------------------------------------------ 带条件

    private static VodFilterCriteria criteria(VideoFilter f) {
        return new VodFilterCriteria(f.level(), f.year(), f.area(), f.lang(), f.classesPattern(), f.keywordPatterns());
    }

    private static final String VISIBLE = "vod_status = 1 AND type_id IN ( ? , ? , ? )";

    @Test
    void allConditionsUseFixedColumnsAndBindVariablesOnly() {
        VideoFilter f = new VideoFilter("Tom & Jerry", "大陆", "国语", "2020", 9, "动作");
        VodFilterCriteria c = criteria(f);
        String conditions = VISIBLE + " AND vod_level = ? AND vod_year = ? AND vod_area = ? AND vod_lang = ?"
                + " AND vod_class LIKE ? ESCAPE '!'"
                + " AND ( vod_name LIKE ? ESCAPE '!' OR vod_sub LIKE ? ESCAPE '!' OR vod_en LIKE ? ESCAPE '!'"
                + " OR vod_name LIKE ? ESCAPE '!' OR vod_sub LIKE ? ESCAPE '!' OR vod_en LIKE ? ESCAPE '!' )";

        BoundSql count = bound("countPublishedMatching", TYPE_IDS, c);
        assertThat(sql(count)).isEqualTo("SELECT " + HINT + " COUNT(*) FROM mac_vod WHERE " + conditions);
        List<Object> expected = List.of(1, 6, 7, 9, "2020", "大陆", "国语", "%动作%",
                "%Tom & Jerry%", "%Tom & Jerry%", "%Tom & Jerry%",
                "%Tom &amp; Jerry%", "%Tom &amp; Jerry%", "%Tom &amp; Jerry%");
        assertThat(values(count)).isEqualTo(expected);

        BoundSql page = bound("selectPublishedMatchingPage", TYPE_IDS, c, VideoSort.HITS, false, 40L, 20);
        String text = sql(page);
        assertThat(text).startsWith("SELECT " + HINT + " vod_id AS id,")
                .contains(" FROM mac_vod WHERE " + conditions + " ORDER BY vod_hits ASC, vod_id ASC LIMIT ?, ?");
        List<Object> pageValues = new ArrayList<>(expected);
        pageValues.addAll(List.of(40L, 20));
        assertThat(values(page)).isEqualTo(pageValues);
    }

    @Test
    void matchingPageSharesColumnsAndOrderWithUnfilteredPage() {
        VodFilterCriteria c = criteria(new VideoFilter(null, null, null, "2020", null, null));
        for (VideoSort s : VideoSort.values()) {
            for (boolean d : new boolean[]{true, false}) {
                String legacy = sql(bound("selectPublishedPage", TYPE_IDS, s, d, 0L, 20));
                String matching = sql(bound("selectPublishedMatchingPage", TYPE_IDS, c, s, d, 0L, 20));
                // 去掉提示与新增条件后，两条语句逐字相同：列清单、可见性、排序白名单、LIMIT 共用同一片段
                assertThat(matching.replace(HINT + " ", "").replace(" AND vod_year = ?", "")).isEqualTo(legacy);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Single.class)
    void eachConditionAloneAddsExactlyItsPredicate(Single single) {
        BoundSql count = bound("countPublishedMatching", TYPE_IDS, criteria(single.filter));
        assertThat(sql(count)).isEqualTo("SELECT " + HINT + " COUNT(*) FROM mac_vod WHERE " + VISIBLE + " "
                + single.predicate);
        assertThat(values(count).subList(3, values(count).size())).isEqualTo(single.values);
    }

    enum Single {
        KEYWORD(new VideoFilter("a", null, null, null, null, null),
                "AND ( vod_name LIKE ? ESCAPE '!' OR vod_sub LIKE ? ESCAPE '!' OR vod_en LIKE ? ESCAPE '!' )",
                List.of("%a%", "%a%", "%a%")),
        AREA(new VideoFilter(null, "a", null, null, null, null), "AND vod_area = ?", List.of("a")),
        LANG(new VideoFilter(null, null, "a", null, null, null), "AND vod_lang = ?", List.of("a")),
        YEAR(new VideoFilter(null, null, null, "1999", null, null), "AND vod_year = ?", List.of("1999")),
        LEVEL(new VideoFilter(null, null, null, null, 1, null), "AND vod_level = ?", List.of(1)),
        CLASSES(new VideoFilter(null, null, null, null, null, "a"), "AND vod_class LIKE ? ESCAPE '!'",
                List.of("%a%"));

        final VideoFilter filter;
        final String predicate;
        final List<Object> values;

        Single(VideoFilter filter, String predicate, List<Object> values) {
            this.filter = filter;
            this.predicate = predicate;
            this.values = values;
        }
    }

    @Test
    void hostileInputNeverReachesSqlText() {
        String[] hostile = {"x' OR '1'='1", "a%_!\\b", "vod_play_url", "1;DROP TABLE mac_vod", "${vod_id}", "#{offset}"};
        for (String h : hostile) {
            VideoFilter f = new VideoFilter(h, h.length() <= 20 && h.indexOf(',') < 0 ? h : null, null, null, null,
                    h.length() <= 32 ? h : null);
            BoundSql b = bound("selectPublishedMatchingPage", TYPE_IDS, criteria(f), VideoSort.TIME, true, 0L, 20);
            assertThat(b.getSql()).as(h).doesNotContain(h).doesNotContain("vod_play_url");
            assertThat(values(b)).as(h).contains("%" + escaped(h) + "%");
        }
    }

    private static String escaped(String v) {
        return v.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
