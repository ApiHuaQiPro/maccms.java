package com.maccms.backend.collect.application;

import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 采集分类绑定的键与取值（B17，25 文档第 4 节；供 B18/B19 的预览与入库沿用）。旧算法不变：
 * <ul>
 * <li>资源站标识 {@code cjflag = md5(采集地址)}（{@code admin/view/collect/index.html:47} 的 {@code md5($vo.collect_url)}，按地址的原始字节，
 * 小写十六进制）；</li>
 * <li>绑定键 {@code cjflag . '_' . 远程分类编号}（{@code common/model/Collect.php:211、343、1102、1429、2020}，
 * {@code admin/controller/Collect.php:232}）；</li>
 * <li>本地分类 {@code intval($bind_list[$key]) > 0} 时为绑定的分类，否则未绑定（同上各处）。</li>
 * </ul>
 * 黄金向量见 {@code CollectBindingsGoldenTest}（PHP {@code md5} 与绑定键）。
 */
public final class CollectBindings {

    private CollectBindings() {
    }

    /** {@code md5($url)}：地址的原始字节的 MD5，小写十六进制。 */
    public static String flag(byte[] url) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(url));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String flag(String url) {
        return flag(url.getBytes(StandardCharsets.UTF_8));
    }

    /** 绑定键 {@code $cjflag . '_' . $type_id}。 */
    public static String key(String flag, String remoteTypeId) {
        return flag + "_" + remoteTypeId;
    }

    /**
     * 绑定的本地分类（旧 {@code intval($bind_list[$key])}，大于 0 时有效）：没有这个键或不大于 0 时为 0。键按 PHP 数组规则规范化
     * （{@code '15'} 与整数键 15 是同一个键）。
     */
    public static long localTypeId(PhpArray bind, String key) {
        if (bind == null) {
            return 0;
        }
        Object k = PhpArray.normalizeKey(key);
        PhpValue v = k instanceof Long l ? bind.get(l) : bind.get((String) k);
        long id = v == null ? 0 : intval(v);
        return Math.max(id, 0);
    }

    private static long intval(PhpValue v) {
        return switch (v) {
            case PhpValue.PhpInt i -> i.value();
            case PhpValue.PhpFloat f -> (long) f.value();
            case PhpValue.PhpBool b -> b.value() ? 1 : 0;
            case PhpValue.PhpString s -> PhpIntval.of(s.value());
            case PhpValue.PhpNull n -> 0;
            case PhpArray a -> a.size() == 0 ? 0 : 1;
        };
    }
}
