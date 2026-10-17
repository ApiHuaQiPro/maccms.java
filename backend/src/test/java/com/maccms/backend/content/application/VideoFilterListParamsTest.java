package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.VideoFilter.RawParam;
import com.maccms.backend.content.infrastructure.VodFilterCriteria;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 契约 1.3.0（B04）新增参数的纯规则：letter/state/version/actor/director/tag/name/ids 的校验顺序、逗号多值（保留空项）、
 * letter 的 0?9 展开、包含匹配的 LIKE 模式与实体变体、ids 的上限与格式、开关与参数顺序。旧版行为依据见
 * docs/architecture/14-video-list-filter-completion.md 第 3 节；旧版对同一输入的结果由 VideoListGoldenMySqlIT 与 PHP 黄金样本对照。
 */
class VideoFilterListParamsTest {

    private static final List<String> LIST_TEXT = List.of("letter", "state", "version", "actor", "director", "tag",
            "name");
    private static final List<String> CONTAINS = List.of("actor", "director", "tag", "name");

    private static VideoFilter parse(Map<String, String> values, boolean enabled) {
        return VideoFilter.parse(p -> RawParam.of(values.get(p)), enabled);
    }

    private static VideoFilter one(String param, String value) {
        return parse(Map.of(param, value), true);
    }

    private static ApiException.FieldError error(Runnable r) {
        try {
            r.run();
        } catch (ApiException e) {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMETER);
            assertThat(e.fieldErrors()).hasSize(1);
            return e.fieldErrors().get(0);
        }
        throw new AssertionError("应抛出 ApiException");
    }

    private static void rejects(String param, String value, String message) {
        ApiException.FieldError e = error(() -> one(param, value));
        assertThat(e.field()).isEqualTo(param);
        assertThat(e.message()).isEqualTo(message);
    }

    // ------------------------------------------------------------------ 通用文本规则

    @Test
    void emptyIsAbsentAndCommonTextRulesApply() {
        for (String p : LIST_TEXT) {
            assertThat(one(p, "")).isEqualTo(VideoFilter.NONE);
            rejects(p, " a", "首尾不能有空白字符");
            rejects(p, "a　", "首尾不能有空白字符");
            rejects(p, "a\u0000b", "含不允许的字符");
            rejects(p, "�", "含不允许的字符");
            rejects(p, "x".repeat(65), "长度不能超过 64 个字符");
            assertThat(error(() -> VideoFilter.parse(q -> q.equals(p) ? new RawParam(2, null, false) : RawParam.ABSENT,
                    true)).message()).isEqualTo("参数不能重复");
            assertThat(error(() -> VideoFilter.parse(q -> q.equals(p) ? new RawParam(1, null, true) : RawParam.ABSENT,
                    true)).message()).as(p + " 非法 UTF-8").isEqualTo("含不允许的字符");
        }
        assertThat(one("state", "x".repeat(64)).state()).hasSize(64);
    }

    @Test
    void commasAreMultiValueAndEmptyItemsAreKeptLikePhpExplode() {
        assertThat(one("state", "正片,预告片").states()).containsExactly("正片", "预告片");
        assertThat(one("state", "正片,").states()).containsExactly("正片", "");
        assertThat(one("state", ",").states()).containsExactly("", "");
        assertThat(one("version", "a,,b").versions()).containsExactly("a", "", "b");
        assertThat(one("version", "a, b").versions()).as("各项不裁剪").containsExactly("a", " b");
        assertThat(one("state", "0").states()).as("API 中 '0' 是字面值").containsExactly("0");
        assertThat(one("version", "a，b").versions()).as("全角逗号是普通字符").containsExactly("a，b");
    }

    // ------------------------------------------------------------------ letter

    @ParameterizedTest
    @ValueSource(strings = {"0-9", "0x9", "0,9", "0~9", "0-9,A", "0a9b", "0 9", "009"})
    void letterZeroQuestionNineExpandsToDigits(String raw) {
        // 旧 Vod.php:331：substr($letter,0,1)=='0' && substr($letter,2,1)=='9'
        assertThat(one("letter", raw).letters()).containsExactly("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "A       | A",
            "a       | a",
            "0       | 0",
            "9       | 9",
            "09      | 09",
            "A,B     | A,B",
            "1-9     | 1-9",
            "0-8     | 0-8",
            "'0&9'   | 0&9",
            "'0中9'  | 0中9",
    })
    void otherLettersAreSplitOnComma(String raw, String expected) {
        // "0&9"：旧版在 htmlspecialchars 之后判断（"0&amp;9" 的第 3 个字节是 'a'），不展开；
        // "0中9"：PHP substr 按字节，'中' 占 3 个字节，第 3 个字节不是 '9'，不展开
        assertThat(one("letter", raw).letters()).containsExactly(expected.split(",", -1));
    }

    @Test
    void letterEmptyItems() {
        assertThat(one("letter", "A,").letters()).containsExactly("A", "");
        assertThat(one("letter", ",").letters()).containsExactly("", "");
    }

    // ------------------------------------------------------------------ 包含匹配

    @Test
    void containsParametersBuildOnePatternPerItemWithEntityForm() {
        assertThat(one("actor", "甲,乙").actorPatterns()).containsExactly("%甲%", "%乙%");
        assertThat(one("director", "100%_!").directorPatterns()).containsExactly("%100!%!_!!%");
        assertThat(one("tag", "Tom & Jerry,丙").tagPatterns())
                .containsExactly("%Tom & Jerry%", "%Tom &amp; Jerry%", "%丙%");
        assertThat(one("name", "a\\b").namePatterns()).as("反斜线不是转义符").containsExactly("%a\\b%");
        assertThat(one("actor", "甲,").actorPatterns()).as("空项与旧 mac_like_arr 相同为 %%，命中全部")
                .containsExactly("%甲%", "%%");
        assertThat(one("actor", ",").actorPatterns()).containsExactly("%%", "%%");
        assertThat(VideoFilter.NONE.actorPatterns()).isNull();
        assertThat(VideoFilter.NONE.namePatterns()).isNull();
    }

    @Test
    void containsParametersNeedTheSearchSwitchBeforeCharacterChecks() {
        for (String p : CONTAINS) {
            ApiException.FieldError e = error(() -> parse(Map.of(p, "x"), false));
            assertThat(e.field()).isEqualTo(p);
            assertThat(e.message()).isEqualTo("当前未开放关键字搜索");
            assertThat(error(() -> VideoFilter.parse(q -> q.equals(p) ? new RawParam(1, null, true) : RawParam.ABSENT,
                    false)).message()).isEqualTo("当前未开放关键字搜索");
            assertThat(parse(Map.of(p, ""), false)).as("空串在开关之前按未传").isEqualTo(VideoFilter.NONE);
        }
        // 精确匹配参数不受开关影响
        VideoFilter exact = parse(Map.of("letter", "A", "state", "正片", "version", "高清", "ids", "1,2",
                "year", "2018-2020"), false);
        assertThat(exact.usesContainsMatch()).isFalse();
        assertThat(exact.isEmpty()).isFalse();
    }

    @Test
    void containsMatchFlagsAndFirstParameter() {
        for (String p : CONTAINS) {
            VideoFilter f = one(p, "x");
            assertThat(f.usesContainsMatch()).as(p).isTrue();
            assertThat(f.firstContainsMatchParameter()).isEqualTo(p);
        }
        assertThat(parse(Map.of("name", "x", "actor", "y"), true).firstContainsMatchParameter()).isEqualTo("actor");
        assertThat(parse(Map.of("name", "x", "classes", "y"), true).firstContainsMatchParameter()).isEqualTo("classes");
        assertThat(one("letter", "A").firstContainsMatchParameter()).isNull();
    }

    // ------------------------------------------------------------------ ids

    @Test
    void idsAllMeansNoCondition() {
        VideoFilter all = one("ids", "all");
        assertThat(all.ids()).isEqualTo("all");
        assertThat(all.idList()).isNull();
        assertThat(all.isEmpty()).as("ids=all 不产生条件，使用与 1.0.0 相同的语句").isTrue();
        assertThat(ContentReadService.criteria(all)).isNull();
        rejects("ids", "ALL", "必须是 all 或以逗号分隔的视频 ID");
        rejects("ids", "all,1", "必须是 all 或以逗号分隔的视频 ID");
    }

    @Test
    void idsItemsAndEmptyItems() {
        assertThat(one("ids", "3,1,2").idList()).containsExactly(3L, 1L, 2L);
        assertThat(one("ids", "007").idList()).containsExactly(7L);
        assertThat(one("ids", "1,,2").idList()).as("空项按 0（旧版 IN ('') 按数值 0 比较）").containsExactly(1L, 0L, 2L);
        assertThat(one("ids", ",").idList()).containsExactly(0L, 0L);
        assertThat(one("ids", "0").idList()).as("API 中 '0' 是字面值（旧版 0 视为未传）").containsExactly(0L);
        assertThat(one("ids", "4294967295").idList()).containsExactly(4294967295L);
        rejects("ids", "4294967296", "必须是 all 或以逗号分隔的视频 ID");
        rejects("ids", "00000000001", "必须是 all 或以逗号分隔的视频 ID");
        for (String bad : List.of("1, 2", "a", "1;2", "-1", "+1", "1.0", "１", "1 ")) {
            ApiException.FieldError e = error(() -> one("ids", bad));
            assertThat(e.field()).as(bad).isEqualTo("ids");
        }
        rejects("ids", " 1", "首尾不能有空白字符");
    }

    @Test
    void idsAreLimitedToOneHundredItemsCountingEmptyOnes() {
        String hundred = String.join(",", java.util.Collections.nCopies(100, "1"));
        assertThat(one("ids", hundred).idList()).hasSize(100);
        rejects("ids", hundred + ",2", "最多 100 个 ID");
        rejects("ids", ",".repeat(100), "最多 100 个 ID");
        assertThat(one("ids", ",".repeat(99)).idList()).hasSize(100);
        // 数量在格式之前检查
        rejects("ids", hundred + ",x", "最多 100 个 ID");
    }

    // ------------------------------------------------------------------ 顺序与构造器

    @Test
    void parameterOrderExtendsTheContract110Order() {
        assertThat(VideoFilter.PARAMETERS).containsExactly("keyword", "area", "lang", "year", "level", "classes",
                "letter", "state", "version", "actor", "director", "tag", "name", "ids");
        Map<String, String> bad = new HashMap<>();
        for (String p : VideoFilter.PARAMETERS) {
            bad.put(p, p.equals("ids") ? "x" : " x");
        }
        for (String expected : VideoFilter.PARAMETERS) {
            assertThat(error(() -> parse(bad, true)).field()).isEqualTo(expected);
            bad.put(expected, "");
        }
    }

    @Test
    void constructorEnforcesTheSameRulesAsParse() {
        assertThat(new VideoFilter(null, null, null, null, null, null, "", "", "", "", "", "", "", ""))
                .isEqualTo(VideoFilter.NONE);
        assertThat(error(() -> new VideoFilter(null, null, null, null, null, null, " A", null, null, null, null, null,
                null, null)).field()).isEqualTo("letter");
        assertThat(error(() -> new VideoFilter(null, null, null, null, null, null, null, null, null, null, null, null,
                null, "x")).field()).isEqualTo("ids");
        assertThat(error(() -> new VideoFilter(null, null, null, "2020-202", null, null)).field()).isEqualTo("year");
        VideoFilter f = new VideoFilter("k", "a", "l", "2018-2020", 9, "c", "A,B", "s", "v", "act", "dir", "t", "n",
                "1,2");
        Map<String, String> all = new HashMap<>(Map.of("keyword", "k", "area", "a", "lang", "l", "year", "2018-2020",
                "level", "9", "classes", "c", "letter", "A,B", "state", "s", "version", "v", "actor", "act"));
        all.putAll(Map.of("director", "dir", "tag", "t", "name", "n", "ids", "1,2"));
        assertThat(parse(all, true)).isEqualTo(f);
    }

    // ------------------------------------------------------------------ 到 Mapper 条件

    @Test
    void criteriaKeepsTheContract110SqlForSingleValuesAndAddsListsForNewParameters() {
        assertThat(ContentReadService.criteria(one("year", "2020")))
                .isEqualTo(new VodFilterCriteria(null, "2020", null, null, null, null));
        assertThat(ContentReadService.criteria(one("year", "2020-2020")))
                .as("区间只有一年时仍用单值 year").isEqualTo(new VodFilterCriteria(null, "2020", null, null, null, null));
        VodFilterCriteria range = ContentReadService.criteria(one("year", "2020-2018"));
        assertThat(range.year()).isNull();
        assertThat(range.years()).containsExactly("2018", "2019", "2020");

        VideoFilter f = parse(Map.of("letter", "0-9", "state", "a,", "version", "v", "actor", "甲", "director", "乙",
                "tag", "丙", "name", "丁", "ids", "5,,6"), true);
        VodFilterCriteria c = ContentReadService.criteria(f);
        assertThat(c.letters()).hasSize(10);
        assertThat(c.states()).containsExactly("a", "");
        assertThat(c.versions()).containsExactly("v");
        assertThat(c.actorPatterns()).containsExactly("%甲%");
        assertThat(c.directorPatterns()).containsExactly("%乙%");
        assertThat(c.tagPatterns()).containsExactly("%丙%");
        assertThat(c.namePatterns()).containsExactly("%丁%");
        assertThat(c.ids()).containsExactly(5L, 0L, 6L);
        assertThat(c.candidateIds()).isNull();
        assertThat(c.withCandidates(List.of(1L)).candidateIds()).containsExactly(1L);
    }

    /** 契约 1.3.0（MACC-28 Q1）：area/lang/classes 只有一项时仍是 1.1.0 的单值条件，逗号多值时为列表条件。 */
    @Test
    void criteriaUsesSingleValueFormsUnlessAreaLangOrClassesHaveSeveralItems() {
        assertThat(ContentReadService.criteria(parse(Map.of("area", "大陆", "lang", "国语", "classes", "动作"), true)))
                .isEqualTo(new VodFilterCriteria(null, null, "大陆", "国语", "%动作%", null));
        VodFilterCriteria c = ContentReadService.criteria(parse(Map.of("area", "大陆,香港", "lang", "国语,",
                "classes", ",动作"), true));
        assertThat(c.area()).isNull();
        assertThat(c.lang()).isNull();
        assertThat(c.classesPattern()).isNull();
        assertThat(c.areas()).containsExactly("大陆", "香港");
        assertThat(c.langs()).containsExactly("国语", "");
        assertThat(c.classesPatterns()).containsExactly("%%", "%动作%");
        VodFilterCriteria withCandidates = c.withCandidates(List.of(3L));
        assertThat(withCandidates.areas()).isEqualTo(c.areas());
        assertThat(withCandidates.langs()).isEqualTo(c.langs());
        assertThat(withCandidates.classesPatterns()).isEqualTo(c.classesPatterns());
        assertThat(ContentReadService.criteria(parse(Map.of("area", "大陆，香港"), true)).area())
                .as("全角逗号不拆分").isEqualTo("大陆，香港");
    }
}
