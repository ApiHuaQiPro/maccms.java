package com.maccms.backend.site.application;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 旧 {@code player_aaaa}（common/controller/All.php:496-543）的字段与顺序，以及 {@code app.encrypt} 的两种编码。
 * 与 PHP 黄金样本 site/play/php-golden.json 逐字段一致（PlaybackGoldenMySqlIT）。
 */
public final class LegacyPlayerData {

    private LegacyPlayerData() {
    }

    /**
     * @param flag    {@code play} 或 {@code down}
     * @param id      旧 {@code $param['id']}：字符串（mac_param_url 的 htmlspecialchars 结果，数字 id 原样）
     * @param url     本集地址（已处理 upload 前缀，未编码）
     * @param urlNext 下一个位置的地址（同上）
     */
    public static Map<String, Object> build(String flag, int encrypt, int trysee, int points, String link,
                                            String linkNext, String linkPre, String vodName, String vodActor,
                                            String vodDirector, String vodClass, String url, String urlNext,
                                            String from, String server, String note, String id, int sid, int nid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("flag", flag);
        m.put("encrypt", encrypt);
        m.put("trysee", trysee);
        m.put("points", points);
        m.put("link", link);
        m.put("link_next", linkNext);
        m.put("link_pre", linkPre);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vod_name", vodName);
        data.put("vod_actor", vodActor);
        data.put("vod_director", vodDirector);
        data.put("vod_class", vodClass);
        m.put("vod_data", data);
        m.put("url", encode(url, encrypt));
        m.put("url_next", encode(urlNext, encrypt));
        m.put("from", from);
        m.put("server", server);
        m.put("note", note);
        m.put("id", id);
        m.put("sid", sid);
        m.put("nid", nid);
        return m;
    }

    /** encrypt=1：mac_escape；=2：base64_encode(mac_escape)；其余原样（All.php:533-540）。 */
    static String encode(String s, int encrypt) {
        return switch (encrypt) {
            case 1 -> macEscape(s);
            case 2 -> Base64.getEncoder().encodeToString(macEscape(s).getBytes(StandardCharsets.US_ASCII));
            default -> s;
        };
    }

    /**
     * 旧 {@code mac_escape}（application/common.php:1009-1022）：逐个字符，单字节字符写成 {@code %HH}，多字节字符写成
     * {@code %uHHHH}（UCS-2 大端，大写十六进制）。UCS-2 表示不了的字符（U+10000 以上）PHP 的 mb_convert_encoding 输出为 {@code ?}，
     * 这里同样写成 {@code %u003F}。
     */
    static String macEscape(String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (cp < 0x80) {
                sb.append('%').append(hex(cp, 2));
            } else if (cp <= 0xFFFF) {
                sb.append("%u").append(hex(cp, 4));
            } else {
                sb.append("%u003F");
            }
        });
        return sb.toString();
    }

    private static String hex(int v, int width) {
        String h = Integer.toHexString(v).toUpperCase(java.util.Locale.ROOT);
        return "0".repeat(Math.max(0, width - h.length())) + h;
    }
}
