package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B05：{@link PhpIntval}、{@link LegacyTagAttributes}、{@link LegacyRange} 与 PHP 黄金向量（content/vodtag/php-golden.json 为
 * PHP 8.0，php73-golden.json 为 PHP 7.3）逐项对照。只读文件，不连数据库；列表条件的整体效果由 VideoTagGoldenMySqlIT 在真实 MySQL 上验证。
 */
class LegacyTagValuesGoldenTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode golden(String file) throws Exception {
        try (InputStream in = LegacyTagValuesGoldenTest.class.getResourceAsStream("/content/vodtag/" + file)) {
            return JSON.readTree(in);
        }
    }

    @Test
    void intvalMatchesPhpForEveryVectorOnBothVersions() throws Exception {
        for (String file : List.of("php-golden.json", "php73-golden.json")) {
            JsonNode vectors = golden(file).get("intval");
            assertThat(vectors.size()).as(file).isGreaterThanOrEqualTo(50);
            for (JsonNode v : vectors) {
                assertThat(PhpIntval.of(v.get("input").asString())).as(file + " intval(" + v.get("input") + ")")
                        .isEqualTo(v.get("intval").asLong());
            }
        }
    }

    /**
     * in_array($v, ['0','1'])（isend/plot）：PHP 8 的数字字符串允许尾随空白，PHP 7.3 不允许，只有 "1 " 一项两版不同；
     * 新版按 PHP 8（与 {@link PhpValues#isNumeric} 一致），7.3 的差异逐项列出。
     */
    @Test
    void zeroOrOneMatchesPhp8AndListsThePhp73Difference() throws Exception {
        for (JsonNode v : golden("php-golden.json").get("inArray")) {
            String in = v.get("input").asString();
            assertThat(LegacyTagAttributes.zeroOrOne(in).isPresent()).as("PHP 8 in_array(" + in + ")")
                    .isEqualTo(v.get("inZeroOne").asBoolean());
        }
        int differences = 0;
        for (JsonNode v : golden("php73-golden.json").get("inArray")) {
            String in = v.get("input").asString();
            if (LegacyTagAttributes.zeroOrOne(in).isPresent() != v.get("inZeroOne").asBoolean()) {
                assertThat(in).as("PHP 7.3 与新版只在尾随空白上不同").isEqualTo("1 ");
                differences++;
            }
        }
        assertThat(differences).isEqualTo(1);
        assertThat(LegacyTagAttributes.zeroOrOne("01")).contains(1);
        assertThat(LegacyTagAttributes.zeroOrOne("0.0")).contains(0);
    }

    @Test
    void attributeListsFollowLegacyIntegerBinding() {
        assertThat(LegacyTagAttributes.intList("1,,2")).contains(List.of(1L, 0L, 2L));
        assertThat(LegacyTagAttributes.intList(" 5")).contains(List.of(5L));
        assertThat(LegacyTagAttributes.intList("5x")).contains(List.of(5L));
        assertThat(LegacyTagAttributes.intList("abc")).contains(List.of(0L));
        assertThat(LegacyTagAttributes.intList("0")).as("empty('0') 不筛选").isEmpty();
        assertThat(LegacyTagAttributes.intList("")).isEmpty();
        assertThat(LegacyTagAttributes.levels("all")).contains(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9));
        assertThat(LegacyTagAttributes.levels("abc")).contains(List.of(0));
        assertThat(LegacyTagAttributes.levels("0")).isEmpty();
        assertThat(LegacyTagAttributes.textList(",")).contains(List.of("", ""));
    }

    @Test
    void typesUseLooseNumericComparison() {
        assertThat(LegacyTagAttributes.types("1,2", 0)).contains(List.of(1, 2));
        assertThat(LegacyTagAttributes.types(" 6", 0)).contains(List.of(6));
        assertThat(LegacyTagAttributes.types("06", 0)).contains(List.of(6));
        assertThat(LegacyTagAttributes.types("6.0", 0)).contains(List.of(6));
        assertThat(LegacyTagAttributes.types("6abc", 0)).as("不是数字字符串：不命中任何分类").contains(List.of());
        assertThat(LegacyTagAttributes.types("abc", 0)).contains(List.of());
        assertThat(LegacyTagAttributes.types("all", 0)).isEmpty();
        assertThat(LegacyTagAttributes.types("0", 0)).as("empty('0') 不筛选").isEmpty();
        assertThat(LegacyTagAttributes.types("current", 6)).contains(List.of(6));
        assertThat(LegacyTagAttributes.types("current", 0)).as("PHP 8：全部顶级分类（PHP 7.3 为不筛选，见 15 文档）")
                .contains(List.of(0));
    }

    @Test
    void relIsIdsOnlyWhenEveryItemIsNumeric() {
        assertThat(LegacyTagAttributes.rel("1,2")).map(LegacyTagAttributes.Rel::ids).contains(List.of(1L, 2L));
        assertThat(LegacyTagAttributes.rel("1.0,2")).map(LegacyTagAttributes.Rel::ids).contains(List.of(1L, 2L));
        assertThat(LegacyTagAttributes.rel("1,a")).map(LegacyTagAttributes.Rel::text).contains("1,a");
        assertThat(LegacyTagAttributes.rel(",")).as("空项不是数字").map(LegacyTagAttributes.Rel::text).contains(",");
        assertThat(LegacyTagAttributes.rel("0")).isEmpty();
    }

    @Test
    void startIsAbsoluteValueAndOffsetSkipsOneOnlyAboveOne() {
        assertThat(LegacyTagAttributes.absInt("-3")).isEqualTo(3);
        assertThat(VideoLegacyConditions.builder().start(1).build().startOffset()).isEqualTo(1);
        assertThat(VideoLegacyConditions.builder().start(2).build().startOffset()).isEqualTo(1);
        assertThat(VideoLegacyConditions.builder().start(3).build().startOffset()).isEqualTo(2);
        assertThatThrownBy(() -> LegacyTagAttributes.absInt("abc")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rangesAcceptOnlyWhitelistedOperatorsAndIntegers() {
        assertThat(LegacyRange.parseLegacy("gt 300")).isEqualTo(LegacyRange.of(LegacyRange.Op.GT, 300));
        assertThat(LegacyRange.parseLegacy("GT 300")).isEqualTo(LegacyRange.of(LegacyRange.Op.GT, 300));
        assertThat(LegacyRange.parseLegacy("> 300")).isEqualTo(LegacyRange.of(LegacyRange.Op.GT, 300));
        assertThat(LegacyRange.parseLegacy("Between 100,500")).isEqualTo(LegacyRange.of(LegacyRange.Op.BETWEEN, 100, 500));
        assertThat(LegacyRange.parseLegacy("notin 13,23")).isEqualTo(LegacyRange.of(LegacyRange.Op.NOT_IN, 13, 23));
        assertThat(LegacyRange.parseLegacy("notbetween 1,2").op()).isEqualTo(LegacyRange.Op.NOT_BETWEEN);
        assertThat(LegacyRange.parseLegacy("neq 3").op()).isEqualTo(LegacyRange.Op.NEQ);
    }

    @ParameterizedTest
    @ValueSource(strings = {"300", "gt", "like 3%", "exp 1=1", "gt 1 2", "between 1", "between 1,2,3", "gt abc",
        "in 1,,2", "null x", "> time 1", "gt 1.5", ""})
    void unsupportedRangesAreRejected(String raw) {
        assertThatThrownBy(() -> LegacyRange.parseLegacy(raw)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(raw.isEmpty() ? "\u0000" : raw);
    }

    @Test
    void legacyConditionsRejectOversizedOrConflictingInput() {
        assertThatThrownBy(() -> VideoLegacyConditions.builder().tid(0L).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VideoLegacyConditions.builder().relIds(List.of(1L)).rel("a").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VideoLegacyConditions.builder().weekday("x".repeat(65)).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VideoLegacyConditions.builder().notIds(java.util.Collections.nCopies(101, 1L)).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VideoLegacyConditions.builder().isEnd(2).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VideoLegacyConditions.builder().start(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(VideoLegacyConditions.builder().types(List.of()).build().types()).isEmpty();
        assertThat(VideoLegacyConditions.NONE.isEmpty()).isTrue();
        assertThat(VideoLegacyConditions.builder().weekday("一").build().usesContainsMatch()).isTrue();
        assertThat(VideoLegacyConditions.builder().relIds(List.of(1L)).build().usesContainsMatch()).isFalse();
        assertThatThrownBy(() -> new VideoListQuery(1, VideoSort.TIME, true, 1, 20, VideoFilter.NONE, false,
                VideoLegacyConditions.builder().types(List.of(2)).build())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VideoListQuery(null, VideoSort.TIME, true, 1, 20,
                new VideoFilter(null, null, null, null, 3, null), false,
                VideoLegacyConditions.builder().levels(List.of(1)).build())).isInstanceOf(IllegalArgumentException.class);
        Optional<List<Integer>> none = LegacyTagAttributes.smallIntList("");
        assertThat(none).isEmpty();
    }
}
