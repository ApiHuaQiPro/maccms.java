package com.maccms.backend.integration.application;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 第三方接口配置（B23，路线图第 4.3 节；26 文档第 4 节）：旧配置 {@code extra/maccms.php} 中 {@link #GROUPS} 各组的只读快照。
 *
 * <p>取值方式与旧版一致：旧代码读 {@code $GLOBALS['config']['pay']['alipay']['appkey']} 并按字符串使用，这里
 * {@link #text}/{@link #secret} 按 PHP {@code (string)$v} 转换（{@link PhpValues#text}），{@link #empty} 即 PHP {@code empty($v)}；
 * 键不存在时与旧版一样得到空串。
 *
 * <p>密钥字段（键名属于 {@link #SECRET_KEYS}）只能经 {@link #secret} 取得 {@link Secret}，用 {@link #text} 读会被拒绝，
 * 避免明文混进普通字符串流向日志；{@link #toString()} 不输出任何值。启动时读取一次，后台修改配置后重启生效（D-B15-18）。
 */
public final class ThirdPartyConfig {

    /** 第三方接口相关的配置组（maccms.php 顶层键）。 */
    public static final List<String> GROUPS = List.of("pay", "sms", "email", "connect", "weixin", "urlsend");

    /**
     * 视为密钥的键名（精确匹配，小写）。覆盖 {@code application/extra/*.php} 中全部凭证类字段：{@code pass}（db、interface）、
     * {@code pwd}、{@code password}、{@code cache_password}、{@code secret}、{@code secretkey}、{@code accesskey}、{@code appkey}、
     * {@code key}（connect、uomg 的应用凭证；seo 段同名的关键词也按密钥处理，宁可多遮）、{@code token}、{@code cookie}、
     * 商户号 {@code mchid}、{@code encode_key}、{@code img_key}。
     */
    public static final Set<String> SECRET_KEYS = Set.of("pass", "pwd", "password", "cache_password", "secret", "secretkey",
            "accesskey", "appkey", "key", "token", "cookie", "mchid", "encode_key", "img_key");

    private static final ThirdPartyConfig EMPTY = new ThirdPartyConfig(Map.of());

    private final Map<String, PhpArray> groups;

    private ThirdPartyConfig(Map<String, PhpArray> groups) {
        this.groups = groups;
    }

    /** 从 maccms.php 的顶层数组取出 {@link #GROUPS}；null（未配置旧配置目录）得到全空的配置。 */
    public static ThirdPartyConfig from(PhpArray maccms) {
        if (maccms == null) {
            return EMPTY;
        }
        Map<String, PhpArray> out = new LinkedHashMap<>();
        for (String g : GROUPS) {
            if (maccms.get(g) instanceof PhpArray a) {
                out.put(g, a);
            }
        }
        return new ThirdPartyConfig(Collections.unmodifiableMap(out));
    }

    /** 键名是否为密钥字段。 */
    public static boolean isSecretKey(String key) {
        return key != null && SECRET_KEYS.contains(key.toLowerCase(Locale.ROOT));
    }

    /**
     * 非密钥字段的字符串值（PHP {@code (string)}），如 {@code text("sms", "sign")}、{@code text("connect", "qq", "status")}。
     *
     * @throws IllegalArgumentException 组名不在 {@link #GROUPS}，或最后一级键是密钥字段（应改用 {@link #secret}）
     */
    public String text(String group, String... path) {
        if (path.length > 0 && isSecretKey(path[path.length - 1])) {
            throw new IllegalArgumentException("密钥字段只能用 secret() 读取：" + group + "." + String.join(".", path));
        }
        return PhpValues.text(value(group, path));
    }

    /** PHP {@code empty($config[group][...])}，密钥字段同样适用。 */
    public boolean empty(String group, String... path) {
        return PhpValues.empty(value(group, path));
    }

    /**
     * 密钥字段的值，如 {@code secret("pay", "alipay", "appkey")}。
     *
     * @throws IllegalArgumentException 组名不在 {@link #GROUPS}，或最后一级键不是密钥字段
     */
    public Secret secret(String group, String... path) {
        if (path.length == 0 || !isSecretKey(path[path.length - 1])) {
            throw new IllegalArgumentException("不是密钥字段：" + group + "." + String.join(".", path));
        }
        return Secret.of(PhpValues.text(value(group, path)));
    }

    /** 全部非空的密钥值（供 {@link LogRedactor#redact(String, java.util.Collection)} 按原文遮盖），按字典序。 */
    public List<String> secretValues() {
        Set<String> out = new TreeSet<>();
        for (PhpArray g : groups.values()) {
            collectSecrets(g, out);
        }
        return new ArrayList<>(out);
    }

    private static void collectSecrets(PhpArray a, Set<String> out) {
        for (Map.Entry<Object, PhpValue> e : a.entries().entrySet()) {
            if (e.getValue() instanceof PhpArray child) {
                collectSecrets(child, out);
            } else if (isSecretKey(String.valueOf(e.getKey()))) {
                String v = PhpValues.text(e.getValue());
                if (!v.isEmpty()) {
                    out.add(v);
                }
            }
        }
    }

    private PhpValue value(String group, String... path) {
        if (!GROUPS.contains(group)) {
            throw new IllegalArgumentException("不是第三方接口配置组：" + group);
        }
        PhpArray g = groups.get(group);
        return g == null ? null : path.length == 0 ? g : g.path(path);
    }

    @Override
    public String toString() {
        // 不输出任何值：非密钥字段里也可能有账号、邮箱等
        return "ThirdPartyConfig" + groups.keySet();
    }
}
