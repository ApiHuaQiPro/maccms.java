package com.maccms.backend.common.lang;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 旧语言包（B12，docs/architecture/21-b12-admin-framework.md 第 6 节）：仓库 {@code application/lang/zh-cn.php}、{@code zh-tw.php}
 * 由 {@code backend/src/test/php/lang_export.php} 原样导出为 jar 内的 {@code lang/<语言>.json}，这里只读这两份资源，不读部署目录、
 * 不执行 PHP。
 *
 * <p>取值语义同 ThinkPHP 5.0 {@code Lang::get}（thinkphp/library/think/Lang.php）：键按 ASCII 转小写后查找（导出时已按
 * {@code array_change_key_case} 转小写），找不到时返回调用方给的键名原文。
 *
 * <p>语言选择（旧 {@code common/behavior/Init.php:96-98}：{@code app.lang} 非空时作为 {@code default_lang}，否则用
 * {@code application/config.php} 的 {@code zh-cn}；{@code lang_switch_on=false}，不按请求切换）：见 {@link #select}。
 */
public final class LegacyLang {

    /** 旧 {@code application/config.php} 的 {@code default_lang}。 */
    public static final String DEFAULT_CODE = "zh-cn";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, LegacyLang> PACKS = new LinkedHashMap<>();

    /** zh-cn 的值 → 键（{@link #localize}）；同一个值对应的几个键在其他包里取值不同时不收录。 */
    private static final Map<String, String> REVERSE;

    static {
        for (String code : new String[] {"zh-cn", "zh-tw"}) {
            PACKS.put(code, load(code));
        }
        Map<String, String> reverse = new LinkedHashMap<>();
        Map<String, Boolean> ambiguous = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : PACKS.get(DEFAULT_CODE).messages.entrySet()) {
            String first = reverse.putIfAbsent(e.getValue(), e.getKey());
            if (first != null) {
                for (LegacyLang pack : PACKS.values()) {
                    if (!Objects.equals(pack.messages.get(first), pack.messages.get(e.getKey()))) {
                        ambiguous.put(e.getValue(), true);
                    }
                }
            }
        }
        ambiguous.keySet().forEach(reverse::remove);
        REVERSE = Map.copyOf(reverse);
    }

    private final String code;
    private final String sourceSha256;
    private final Map<String, String> messages;

    private LegacyLang(String code, String sourceSha256, Map<String, String> messages) {
        this.code = code;
        this.sourceSha256 = sourceSha256;
        this.messages = messages;
    }

    /**
     * 按旧配置 {@code app.lang} 选择语言包。
     *
     * @param configured 旧配置的原值；{@code null} 或空串表示未设置
     */
    public static Selection select(String configured) {
        if (configured == null || configured.isEmpty()) {
            return new Selection(PACKS.get(DEFAULT_CODE), false);
        }
        LegacyLang pack = PACKS.get(configured);
        if (pack != null) {
            return new Selection(pack, false);
        }
        // 旧版按原值拼文件名，文件不存在时 Lang::load 静默跳过，页面上显示的是键名本身；新版回退到简体并由调用方告警（差异 D-B12-L1）
        return new Selection(PACKS.get(DEFAULT_CODE), true);
    }

    /** 已打包的语言包（只有 zh-cn、zh-tw）。 */
    public static LegacyLang of(String code) {
        LegacyLang pack = PACKS.get(code);
        if (pack == null) {
            throw new IllegalArgumentException("没有这个语言包");
        }
        return pack;
    }

    /** 语言代码（{@code zh-cn} / {@code zh-tw}）。 */
    public String code() {
        return code;
    }

    /** 导出时源文件的 SHA-256（十六进制小写），测试用它确认导出与仓库源文件一致。 */
    public String sourceSha256() {
        return sourceSha256;
    }

    /** 条目数。 */
    public int size() {
        return messages.size();
    }

    /** {@code lang($key)}：找不到时返回 {@code key} 原文。 */
    public String get(String key) {
        String v = messages.get(asciiLower(key));
        return v == null ? key : v;
    }

    /** 是否有这个键。 */
    public boolean has(String key) {
        return messages.containsKey(asciiLower(key));
    }

    /**
     * 把新版代码里照抄的简体原文换成本语言包的对应文字（B12 承接 D-B05-8，21 文档第 12.2 节）：前台各批次的提示（B05～B11 的跳转页、
     * 错误页、JSON 的 {@code msg}）照抄了旧 {@code lang(键)} 在 zh-cn 包里的值；这里按"zh-cn 包里<b>整段</b>等于该值的键"反查，取本包同一键
     * 的值。规则：
     * <ul>
     * <li>本包就是 zh-cn，或文本不是 zh-cn 包里任何一项的完整值（新版新增的文字、拼接出来的文字）→ 原样返回；</li>
     * <li>zh-cn 值相同的几个键在本包里的值不同（只有"采集"一处）→ 无法确定是哪个键，原样返回；</li>
     * <li>不做简繁自动转换。</li>
     * </ul>
     */
    public String localize(String zhCnText) {
        if (zhCnText == null || code.equals(DEFAULT_CODE)) {
            return zhCnText;
        }
        String key = REVERSE.get(zhCnText);
        return key == null ? zhCnText : messages.getOrDefault(key, zhCnText);
    }

    /** PHP {@code strtolower}（C 区域设置）：只改 ASCII 字母。 */
    static String asciiLower(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                if (sb == null) {
                    sb = new StringBuilder(s);
                }
                sb.setCharAt(i, (char) (c + 32));
            }
        }
        return sb == null ? s : sb.toString();
    }

    private static LegacyLang load(String code) {
        String name = "/lang/" + code + ".json";
        try (InputStream in = LegacyLang.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("缺少语言包资源 " + name);
            }
            JsonNode doc = JSON.readTree(in);
            Map<String, String> messages = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : doc.get("messages").properties()) {
                messages.put(e.getKey(), e.getValue().asString());
            }
            return new LegacyLang(code, doc.get("sha256").asString().toLowerCase(Locale.ROOT), Map.copyOf(messages));
        } catch (IOException e) {
            throw new UncheckedIOException("读取语言包资源失败", e);
        }
    }

    /**
     * 语言选择的结果。
     *
     * @param fallback 配置了不受支持的语言、已回退到简体时为 true
     */
    public record Selection(LegacyLang pack, boolean fallback) {
    }
}
