package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.VideoFilter.RawParam;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 09 方案第 4.3 节每一步、第 4.4 节对照表每一格的正反例，以及 LIKE 转义与实体变体（第 4.5 节）。
 * 只测纯规则，不连数据库；HTTP 层（重复、非法 UTF-8）见 ContentApiContractTest / ContentSearchRealHttpTest。
 */
class VideoFilterTest {

    private enum P { KEYWORD, AREA, LANG, YEAR, LEVEL, CLASSES }

    /** 只设置一个参数（开关打开），返回解析结果。 */
    private static VideoFilter one(P p, String v) {
        return one(p, RawParam.of(v), true);
    }

    private static VideoFilter one(P p, RawParam v, boolean enabled) {
        RawParam a = RawParam.ABSENT;
        return VideoFilter.parse(p == P.KEYWORD ? v : a, p == P.AREA ? v : a, p == P.LANG ? v : a,
                p == P.YEAR ? v : a, p == P.LEVEL ? v : a, p == P.CLASSES ? v : a, enabled);
    }

    private static ApiException.FieldError error(Runnable r) {
        try {
            r.run();
        } catch (ApiException e) {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMETER);
            assertThat(e.getMessage()).isEqualTo("请求参数不合法");
            assertThat(e.fieldErrors()).hasSize(1);
            return e.fieldErrors().get(0);
        }
        throw new AssertionError("应抛出 ApiException");
    }

    private static void rejects(P p, String v, String message) {
        ApiException.FieldError e = error(() -> one(p, v));
        assertThat(e.field()).isEqualTo(p.name().toLowerCase());
        assertThat(e.message()).isEqualTo(message);
        // 固定文案，不回显输入（太短的值可能恰好是文案中的数字，不比较）
        if (v.strip().length() >= 3) {
            assertThat(e.message()).doesNotContain(v.strip());
        }
    }

    private static Object value(P p, VideoFilter f) {
        return switch (p) {
            case KEYWORD -> f.keyword();
            case AREA -> f.area();
            case LANG -> f.lang();
            case YEAR -> f.year();
            case LEVEL -> f.level();
            case CLASSES -> f.classes();
        };
    }

    private static final String WS = "首尾不能有空白字符";
    private static final String YEAR = "必须是 4 位数字年份";
    private static final String LEVEL = "必须是 1 到 9 之间的整数";
    private static final String CHAR = "含不允许的字符";

    // ------------------------------------------------------------------ 第 4.4 节对照表（逐格）

    @Test
    void emptyStringIsAbsentForEveryParameter() {
        for (P p : P.values()) {
            VideoFilter f = one(p, "");
            assertThat(f).isEqualTo(VideoFilter.NONE);
            assertThat(f.isEmpty()).isTrue();
        }
        assertThat(VideoFilter.parse((String) null, null, null, null, null, null, false)).isEqualTo(VideoFilter.NONE);
        assertThat(VideoFilter.parse("", "", "", "", "", "", false)).as("空串在开关检查之前按未传")
                .isEqualTo(VideoFilter.NONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"   ", " 2020", "2020 ", "\u3000x", "x\u00A0"})
    void surroundingWhitespaceRow(String v) {
        rejects(P.KEYWORD, v, WS);
        rejects(P.AREA, v, WS);
        rejects(P.LANG, v, WS);
        rejects(P.CLASSES, v, WS);
        rejects(P.YEAR, v, YEAR);
        rejects(P.LEVEL, v, LEVEL);
    }

    @Test
    void innerWhitespaceRow() {
        for (P p : List.of(P.KEYWORD, P.AREA, P.LANG, P.CLASSES)) {
            assertThat(value(p, one(p, "a b"))).isEqualTo("a b");
            assertThat(value(p, one(p, "a\u3000b"))).isEqualTo("a\u3000b");
        }
        rejects(P.YEAR, "a b", YEAR);
        rejects(P.LEVEL, "a b", LEVEL);
    }

    @Test
    void commaRow() {
        assertThat(one(P.KEYWORD, "a,b").keyword()).as("keyword 的逗号是字面字符").isEqualTo("a,b");
        // 契约 1.3.0（MACC-28 Q1）：area/lang/classes 与旧版一样按半角逗号分多值，原始值原样保留
        for (P p : List.of(P.AREA, P.LANG, P.CLASSES)) {
            assertThat(value(p, one(p, "a,b"))).as(p.name()).isEqualTo("a,b");
        }
        rejects(P.YEAR, "a,b", YEAR);
        rejects(P.LEVEL, "a,b", LEVEL);
        assertThat(one(P.AREA, "a，b").area()).as("全角逗号是普通字符").isEqualTo("a，b");
        assertThat(one(P.AREA, "a，b").areas()).as("全角逗号不拆分").containsExactly("a，b");
    }

    @Test
    void areaLangClassesSplitOnHalfWidthCommaKeepingEmptyItems() {
        VideoFilter f = new VideoFilter(null, "大陆,香港", "国语,粤语,", "", null, "动作,喜剧");
        assertThat(f.areas()).containsExactly("大陆", "香港");
        assertThat(f.langs()).as("尾逗号产生空项").containsExactly("国语", "粤语", "");
        assertThat(f.classesPatterns()).containsExactly("%动作%", "%喜剧%");
        assertThat(f.classesPattern()).as("多值时没有单值模式").isNull();
        assertThat(new VideoFilter(null, ",", ",", null, null, ",").areas()).containsExactly("", "");
        assertThat(new VideoFilter(null, null, null, null, null, ",").classesPatterns())
                .as("空项为 %%，与旧 mac_like_arr 相同").containsExactly("%%", "%%");
        assertThat(new VideoFilter(null, "大陆, 香港", null, null, null, null).areas()).as("各项不裁剪")
                .containsExactly("大陆", " 香港");
        assertThat(new VideoFilter(null, null, null, null, null, "a_b,%").classesPatterns()).as("各项照常转义")
                .containsExactly("%a!_b%", "%!%%");
        assertThat(new VideoFilter(null, null, null, null, null, "A&B,C").classesPatterns()).as("各项不做实体变体")
                .containsExactly("%A&B%", "%C%");
        VideoFilter single = new VideoFilter(null, "大陆", "国语", null, null, "动作");
        assertThat(single.areas()).containsExactly("大陆");
        assertThat(single.langs()).containsExactly("国语");
        assertThat(single.classesPatterns()).containsExactly("%动作%");
        assertThat(single.classesPattern()).isEqualTo("%动作%");
        assertThat(VideoFilter.NONE.areas()).isNull();
        assertThat(VideoFilter.NONE.langs()).isNull();
        assertThat(VideoFilter.NONE.classesPatterns()).isNull();
        // 多值与空项都是包含匹配，受开关约束（不能借逗号或空项绕过）
        assertThat(error(() -> VideoFilter.parse("", "", "", "", "", ",", false)).message())
                .isEqualTo("当前未开放关键字搜索");
        assertThat(new VideoFilter(null, null, null, null, null, ",").usesContainsMatch()).isTrue();
        assertThat(new VideoFilter(null, "a,b", "c,", null, null, null).usesContainsMatch()).isFalse();
        // 长度上限作用于整个原始值（与单值相同）
        rejects(P.AREA, "a,".repeat(10) + "b", "长度不能超过 20 个字符");
        assertThat(one(P.AREA, ",".repeat(20)).areas()).hasSize(21);
        rejects(P.LANG, "a,b,c,d,e,f", "长度不能超过 10 个字符");
        rejects(P.CLASSES, ",".repeat(33), "长度不能超过 32 个字符");
        rejects(P.AREA, "a, ", WS);
        rejects(P.CLASSES, ",a\u0000", CHAR);
    }

    @Test
    void lengthRow() {
        assertThat(one(P.KEYWORD, "字".repeat(64)).keyword()).hasSize(64);
        rejects(P.KEYWORD, "字".repeat(65), "长度不能超过 64 个字符");
        assertThat(one(P.AREA, "a".repeat(20)).area()).hasSize(20);
        rejects(P.AREA, "a".repeat(21), "长度不能超过 20 个字符");
        assertThat(one(P.LANG, "a".repeat(10)).lang()).hasSize(10);
        rejects(P.LANG, "a".repeat(11), "长度不能超过 10 个字符");
        assertThat(one(P.CLASSES, "a".repeat(32)).classes()).hasSize(32);
        rejects(P.CLASSES, "a".repeat(33), "长度不能超过 32 个字符");
        rejects(P.YEAR, "20200", YEAR);
        rejects(P.LEVEL, "00000000009", LEVEL);
    }

    @Test
    void rangeLikeValueRow() {
        for (P p : List.of(P.KEYWORD, P.AREA, P.LANG, P.CLASSES)) {
            assertThat(value(p, one(p, "2020-2022"))).isEqualTo("2020-2022");
        }
        assertThat(one(P.YEAR, "2020-2022").year()).as("契约 1.3.0：9 字符年份区间").isEqualTo("2020-2022");
        rejects(P.LEVEL, "2020-2022", LEVEL);
    }

    @Test
    void leadingZeroRows() {
        for (P p : List.of(P.KEYWORD, P.AREA, P.LANG, P.CLASSES)) {
            assertThat(value(p, one(p, "09"))).isEqualTo("09");
            assertThat(value(p, one(p, "0000000009"))).isEqualTo("0000000009");
        }
        assertThat(one(P.LEVEL, "09").level()).isEqualTo(9);
        assertThat(one(P.LEVEL, "0000000009").level()).isEqualTo(9);
        rejects(P.YEAR, "09", YEAR);
        rejects(P.YEAR, "0000000009", YEAR);
        // 11 位：lang 超长，area/classes/keyword 原样，level 词法长度超限（数值虽为 9）
        assertThat(one(P.KEYWORD, "00000000009").keyword()).isEqualTo("00000000009");
        assertThat(one(P.AREA, "00000000009").area()).isEqualTo("00000000009");
        assertThat(one(P.CLASSES, "00000000009").classes()).isEqualTo("00000000009");
        rejects(P.LANG, "00000000009", "长度不能超过 10 个字符");
        rejects(P.LEVEL, "00000000009", LEVEL);
        rejects(P.YEAR, "00000000009", YEAR);
    }

    @Test
    void zeroRow() {
        for (P p : List.of(P.KEYWORD, P.AREA, P.LANG, P.CLASSES)) {
            assertThat(value(p, one(p, "0"))).as("API 中 '0' 是字面值").isEqualTo("0");
        }
        rejects(P.YEAR, "0", YEAR);
        rejects(P.LEVEL, "0", LEVEL);
    }

    @Test
    void malformedUtf8Row() {
        RawParam bad = new RawParam(1, null, true);
        for (P p : List.of(P.KEYWORD, P.AREA, P.LANG, P.CLASSES)) {
            ApiException.FieldError e = error(() -> one(p, bad, true));
            assertThat(e.field()).isEqualTo(p.name().toLowerCase());
            assertThat(e.message()).isEqualTo(CHAR);
        }
        assertThat(error(() -> one(P.YEAR, bad, true)).message()).isEqualTo(YEAR);
        assertThat(error(() -> one(P.LEVEL, bad, true)).message()).isEqualTo(LEVEL);
        // 容器替换出的 U+FFFD 同样拒绝
        rejects(P.KEYWORD, "a\uFFFDb", CHAR);
    }

    @Test
    void searchSwitchRow() {
        for (P p : List.of(P.KEYWORD, P.CLASSES)) {
            ApiException.FieldError e = error(() -> one(p, RawParam.of("x"), false));
            assertThat(e.field()).isEqualTo(p.name().toLowerCase());
            assertThat(e.message()).isEqualTo("当前未开放关键字搜索");
            // 开关检查在字符检查之前：关闭时非法 UTF-8 也报"未开放"
            assertThat(error(() -> one(p, new RawParam(1, null, true), false)).message())
                    .isEqualTo("当前未开放关键字搜索");
        }
        assertThat(one(P.AREA, RawParam.of("大陆"), false).area()).isEqualTo("大陆");
        assertThat(one(P.LANG, RawParam.of("国语"), false).lang()).isEqualTo("国语");
        assertThat(one(P.YEAR, RawParam.of("2020"), false).year()).isEqualTo("2020");
        assertThat(one(P.LEVEL, RawParam.of("9"), false).level()).isEqualTo(9);
    }

    // ------------------------------------------------------------------ 第 4.3 节各步骤

    @Test
    void duplicatesAreRejectedBeforeAnythingElseIncludingEmptyValues() {
        for (P p : P.values()) {
            for (RawParam dup : List.of(new RawParam(2, null, false), new RawParam(3, null, true))) {
                ApiException.FieldError e = error(() -> one(p, dup, false));
                assertThat(e.field()).isEqualTo(p.name().toLowerCase());
                assertThat(e.message()).isEqualTo("参数不能重复");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0000", "\u0009", "\n", "\u001F", "\u007F", "\u0080", "\u009F", "\uD83D\uDE00",
            "\uD800", "\uDC00", "\uFFFD"})
    void forbiddenCharactersAnywhere(String c) {
        for (P p : List.of(P.KEYWORD, P.AREA, P.LANG, P.CLASSES)) {
            rejects(p, "a" + c + "b", CHAR);
            rejects(p, c, CHAR);
        }
        rejects(P.YEAR, "20" + c, YEAR);
    }

    @Test
    void forbiddenCharacterIsCheckedBeforeWhitespaceAndLength() {
        rejects(P.AREA, " a,\u0001" + "x".repeat(30), CHAR);
        // 1.3.0 起 area 不再检查逗号：首尾空白先于长度
        rejects(P.AREA, " a," + "x".repeat(30), WS);
        rejects(P.AREA, " a" + "x".repeat(30), WS);
        rejects(P.AREA, "a," + "x".repeat(30), "长度不能超过 20 个字符");
    }

    @Test
    void whitespaceSetIsEcmaScriptSlashS() {
        // 首尾为下列任一字符 → 400（控制字符 U+0009–U+000D 在第 4 步已作为禁用字符拒绝）
        int[] ws = {0x20, 0xA0, 0x1680, 0x2000, 0x2005, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF};
        for (int c : ws) {
            String s = String.valueOf((char) c);
            rejects(P.KEYWORD, s + "a", WS);
            rejects(P.KEYWORD, "a" + s, WS);
            assertThat(one(P.KEYWORD, "a" + s + "b").keyword()).isEqualTo("a" + s + "b");
        }
        // 不在集合中的字符：U+180E、U+200B、U+2060 不属于 ECMAScript \s，首尾出现也照常接受
        for (char c : new char[]{'\u180E', '\u200B', '\u2060'}) {
            assertThat(one(P.KEYWORD, c + "a" + c).keyword()).isEqualTo(c + "a" + c);
        }
    }

    @ParameterizedTest
    @CsvSource({"1,1", "9,9", "01,1", "0000000001,1"})
    void levelAccepted(String raw, int expected) {
        assertThat(one(P.LEVEL, raw).level()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "10", "-1", "+9", " 9", "9 ", "9.0", "all", "１", "99999999999", "00000000001"})
    void levelRejected(String raw) {
        rejects(P.LEVEL, raw, LEVEL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2020", "0000", "1999", "9999"})
    void yearAccepted(String raw) {
        assertThat(one(P.YEAR, raw).year()).isEqualTo(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"202", "20201", "２０２０", "2020年", "-202", "2020-202", "20 0", "2020-20222", "202a-2022",
            "2020-202a", "2020年2022", "2020 2022", "2020　2022", "２０２０-2022", "-2020-2022"})
    void yearRejected(String raw) {
        rejects(P.YEAR, raw, YEAR);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "2018-2020 | 2018,2019,2020",
            "2020-2018 | 2018,2019,2020",
            "2018~2020 | 2018,2019,2020",
            "2018,2020 | 2018,2019,2020",
            "'2018 2020' | 2018,2019,2020",
            "201812020 | 2018,2019,2020",
            "2020-2020 | 2020",
            "0005-0003 | 0003,0004,0005",
            "2020 | 2020",
            "0000 | 0000",
    })
    void yearRangeExpandsLikeLegacy(String raw, String expected) {
        // 旧 Vod.php:187-200：strlen==9 时取 substr(0,4)、substr(5,4)，前大后小交换后逐年展开；新版展开为 4 位字符串
        assertThat(one(P.YEAR, raw).years()).containsExactly(expected.split(","));
    }

    @Test
    void yearRangeUpperBoundIsTenThousandValues() {
        assertThat(one(P.YEAR, "0000-9999").years()).hasSize(10000).startsWith("0000", "0001").endsWith("9999");
        assertThat(one(P.YEAR, "9999-0000").years()).hasSize(10000);
        assertThat(VideoFilter.NONE.years()).isNull();
    }

    @Test
    void parameterOrderIsKeywordAreaLangYearLevelClasses() {
        Function<String[], String> firstField = v -> error(() -> VideoFilter.parse(v[0], v[1], v[2], v[3], v[4], v[5],
                true)).field();
        String[] allBad = {" k", " a", " a", "x", "0", " a"};
        String[] order = {"keyword", "area", "lang", "year", "level", "classes"};
        for (int i = 0; i < order.length; i++) {
            String[] v = allBad.clone();
            for (int j = 0; j < i; j++) {
                v[j] = "";
            }
            assertThat(firstField.apply(v)).isEqualTo(order[i]);
        }
        // 开关关闭时 classes 的"未开放"排在 area 等之后
        assertThat(error(() -> VideoFilter.parse("", " a", "", "", "", "x", false)).field()).isEqualTo("area");
    }

    // ------------------------------------------------------------------ 构造器与 parse 同一套规则

    @Test
    void constructorEnforcesTheSameRules() {
        assertThat(new VideoFilter("", "", "", "", null, "")).isEqualTo(VideoFilter.NONE);
        assertThat(error(() -> new VideoFilter(" k", null, null, null, null, null)).message()).isEqualTo(WS);
        assertThat(new VideoFilter(null, "a,b", null, null, null, null).area()).isEqualTo("a,b");
        assertThat(error(() -> new VideoFilter(null, "a, ", null, null, null, null)).message()).isEqualTo(WS);
        assertThat(error(() -> new VideoFilter(null, null, "a".repeat(11), null, null, null)).field()).isEqualTo("lang");
        assertThat(error(() -> new VideoFilter(null, null, null, "20", null, null)).message()).isEqualTo(YEAR);
        assertThat(error(() -> new VideoFilter(null, null, null, null, 0, null)).message()).isEqualTo(LEVEL);
        assertThat(error(() -> new VideoFilter(null, null, null, null, 10, null)).field()).isEqualTo("level");
        assertThat(error(() -> new VideoFilter(null, null, null, null, null, "\u0000")).message()).isEqualTo(CHAR);
        VideoFilter f = new VideoFilter("k", "a", "l", "2020", 9, "c");
        assertThat(VideoFilter.parse("k", "a", "l", "2020", "9", "c", true)).isEqualTo(f);
        assertThat(f.isEmpty()).isFalse();
        assertThat(f.usesContainsMatch()).isTrue();
        assertThat(new VideoFilter(null, "a", "l", "2020", 9, null).usesContainsMatch()).isFalse();
    }

    // ------------------------------------------------------------------ LIKE 与实体

    @Test
    void likePatternsEscapePercentUnderscoreAndBang() {
        assertThat(VideoFilter.contains("a%b_c!d\\e")).isEqualTo("%a!%b!_c!!d\\e%");
        assertThat(new VideoFilter("100%", null, null, null, null, null).keywordPatterns()).containsExactly("%100!%%");
        assertThat(new VideoFilter(null, null, null, null, null, "a_b").classesPattern()).isEqualTo("%a!_b%");
        assertThat(new VideoFilter("C:\\x", null, null, null, null, null).keywordPatterns())
                .as("反斜线不是转义符").containsExactly("%C:\\x%");
        assertThat(VideoFilter.NONE.keywordPatterns()).isNull();
        assertThat(VideoFilter.NONE.classesPattern()).isNull();
    }

    @Test
    void keywordWithHtmlSpecialCharsAlsoMatchesEntityForm() {
        assertThat(new VideoFilter("Tom & Jerry", null, null, null, null, null).keywordPatterns())
                .containsExactly("%Tom & Jerry%", "%Tom &amp; Jerry%");
        assertThat(new VideoFilter("<a href=\"x\">'", null, null, null, null, null).keywordPatterns())
                .containsExactly("%<a href=\"x\">'%", "%&lt;a href=&quot;x&quot;&gt;&#039;%");
        assertThat(new VideoFilter("&amp;", null, null, null, null, null).keywordPatterns())
                .as("输入里的实体不解码，按字面匹配；变体与 PHP 一样再编码一次").containsExactly("%&amp;%", "%&amp;amp;%");
        assertThat(new VideoFilter("普通", null, null, null, null, null).keywordPatterns()).containsExactly("%普通%");
        assertThat(new VideoFilter(null, null, null, null, null, "A&B").classesPattern()).as("classes 不做实体变体")
                .isEqualTo("%A&B%");
    }
}
