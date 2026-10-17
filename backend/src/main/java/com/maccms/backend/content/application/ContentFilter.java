package com.maccms.backend.content.application;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.content.infrastructure.ContentCriteria;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 契约 1.2.0 列表的精确筛选条件，字段为 {@code null} 表示未设置。紧凑构造器与 {@link #parse} 使用同一套规则，其他模块直接构造也不能越过。
 * 每个类型只接受 {@link ContentKind#filters()} 中列出的参数，其余参数名被忽略（与未声明参数相同）。
 *
 * <p>取值规则（docs/architecture/11-content-read-completion.md 第 4.2 节）：
 * <ul>
 *   <li>{@code level}：1..9 的整数（旧版逗号多值、{@code all}，新版单值）；</li>
 *   <li>{@code letter}：单个 ASCII 字母或数字，或 {@code 0-9}（展开为 10 个数字，旧 letter 的同名写法）；大小写是否敏感由列排序规则
 *       （utf8_general_ci，不敏感）决定，与旧版相同；</li>
 *   <li>{@code sex}、{@code area}、{@code lang}、{@code starSign}、{@code blood}：与契约 1.1.0 的 area/lang 相同的文本规则
 *       （禁用控制字符/代理项/U+FFFD、不含逗号、首尾不得为空白、不超过列宽），精确匹配；</li>
 *   <li>{@code videoId}：1..4294967295（角色所属视频 role_rid）；</li>
 *   <li>{@code type}：{@code font}（文字链接，link_type=0）或 {@code pic}（图片链接，link_type=1）；其他值 → 400
 *       （旧版忽略未知值，见差异表）。</li>
 * </ul>
 */
public record ContentFilter(Integer level, String letter, String sex, String area, String lang, String starSign,
                            String blood, Long videoId, String linkType) {

    public static final ContentFilter NONE = new ContentFilter(null, null, null, null, null, null, null, null, null);

    /** actor_sex char(1)（install.sql）。 */
    public static final int MAX_SEX_LENGTH = 1;
    /** actor_area / website_area varchar(20)。 */
    public static final int MAX_AREA_LENGTH = 20;
    /** website_lang varchar(10)。 */
    public static final int MAX_LANG_LENGTH = 10;
    /** actor_starsign varchar(10)。 */
    public static final int MAX_STAR_SIGN_LENGTH = 10;
    /** actor_blood varchar(10)。 */
    public static final int MAX_BLOOD_LENGTH = 10;

    static final String DIGITS = "0-9";
    static final String BAD_LETTER = "必须是单个字母或数字，或 0-9";
    static final String BAD_LINK_TYPE = "只能是 font 或 pic";
    private static final List<String> DIGIT_LETTERS = List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");

    public ContentFilter {
        if (level != null && (level < VideoFilter.MIN_LEVEL || level > VideoFilter.MAX_LEVEL)) {
            throw levelError();
        }
        letter = letter(letter);
        sex = VideoFilter.text("sex", sex, MAX_SEX_LENGTH, false);
        area = VideoFilter.text("area", area, MAX_AREA_LENGTH, false);
        lang = VideoFilter.text("lang", lang, MAX_LANG_LENGTH, false);
        starSign = VideoFilter.text("starSign", starSign, MAX_STAR_SIGN_LENGTH, false);
        blood = VideoFilter.text("blood", blood, MAX_BLOOD_LENGTH, false);
        if (videoId != null && (videoId < 1 || videoId > VideoId.MAX)) {
            throw ApiException.invalidParameter("videoId", "必须是 1 到 " + VideoId.MAX + " 之间的整数");
        }
        if (linkType != null && linkType.isEmpty()) {
            linkType = null;
        }
        if (linkType != null && !linkType.equals("font") && !linkType.equals("pic")) {
            throw ApiException.invalidParameter("type", BAD_LINK_TYPE);
        }
    }

    /**
     * 按 {@link ContentKind#filters()} 的固定顺序读取并校验；{@code param} 返回参数的原始解码值（未传为 {@code null}）。
     * 空字符串按未传处理。只报第一个错误。
     */
    public static ContentFilter parse(ContentKind kind, Function<String, String> param) {
        Integer level = null;
        String letter = null;
        String sex = null;
        String area = null;
        String lang = null;
        String starSign = null;
        String blood = null;
        Long videoId = null;
        String linkType = null;
        for (String name : kind.filters()) {
            String raw = param.apply(name);
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            switch (name) {
                case "level" -> level = VideoListQuery.parseInt("level", raw, VideoFilter.MIN_LEVEL, VideoFilter.MAX_LEVEL);
                case "letter" -> letter = letter(raw);
                case "sex" -> sex = VideoFilter.text("sex", raw, MAX_SEX_LENGTH, false);
                case "area" -> area = VideoFilter.text("area", raw, MAX_AREA_LENGTH, false);
                case "lang" -> lang = VideoFilter.text("lang", raw, MAX_LANG_LENGTH, false);
                case "starSign" -> starSign = VideoFilter.text("starSign", raw, MAX_STAR_SIGN_LENGTH, false);
                case "blood" -> blood = VideoFilter.text("blood", raw, MAX_BLOOD_LENGTH, false);
                case "videoId" -> videoId = videoId(raw);
                case "type" -> linkType = raw;
                default -> throw new IllegalStateException("未知筛选参数 " + name);
            }
        }
        return new ContentFilter(level, letter, sex, area, lang, starSign, blood, videoId, linkType);
    }

    /** 已设置的条件对应的参数名，供 {@link ContentListQuery} 检查该类型是否支持。 */
    List<String> setParameters() {
        List<String> out = new ArrayList<>();
        if (level != null) {
            out.add("level");
        }
        if (letter != null) {
            out.add("letter");
        }
        if (sex != null) {
            out.add("sex");
        }
        if (area != null) {
            out.add("area");
        }
        if (lang != null) {
            out.add("lang");
        }
        if (starSign != null) {
            out.add("starSign");
        }
        if (blood != null) {
            out.add("blood");
        }
        if (videoId != null) {
            out.add("videoId");
        }
        if (linkType != null) {
            out.add("type");
        }
        return out;
    }

    /** 转为 Mapper 条件：letter "0-9" 展开为 10 个数字，type 转为 link_type 0/1。 */
    public ContentCriteria toCriteria() {
        List<String> letters = letter == null ? null : DIGITS.equals(letter) ? DIGIT_LETTERS : List.of(letter);
        Integer type = linkType == null ? null : linkType.equals("font") ? 0 : 1;
        return new ContentCriteria(level, letters, sex, area, lang, starSign, blood, videoId, type);
    }

    private static String letter(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        if (DIGITS.equals(v)) {
            return v;
        }
        if (v.length() != 1) {
            throw ApiException.invalidParameter("letter", BAD_LETTER);
        }
        char c = v.charAt(0);
        if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
            throw ApiException.invalidParameter("letter", BAD_LETTER);
        }
        return v;
    }

    private static long videoId(String raw) {
        try {
            return VideoId.parse(raw);
        } catch (ApiException e) {
            throw ApiException.invalidParameter("videoId", "必须是 1 到 " + VideoId.MAX + " 之间的整数");
        }
    }

    private static ApiException levelError() {
        return ApiException.invalidParameter("level",
                "必须是 " + VideoFilter.MIN_LEVEL + " 到 " + VideoFilter.MAX_LEVEL + " 之间的整数");
    }
}
