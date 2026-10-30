package com.maccms.backend.site.application;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 播放器相关的旧配置（B05，docs/architecture/15-b05-readonly-pages.md 第 6 节）：经 B02 的 LegacyConfigService 在启动时读一次
 * {@code extra/vodplayer.php}、{@code voddowner.php}、{@code vodserver.php} 与 {@code maccms.php} 的 {@code app.encrypt}、
 * {@code app.player_sort}、{@code play.*}。没有配置旧配置目录时为 {@link #unavailable()}：播放/下载页不可用（404），
 * {@code playerconfig.js} 不提供。
 *
 * <p>W1（播放器解析与外部脚本）：打开时 {@link #playerConfigScript()} 与旧后台生成的 {@code static/js/playerconfig.js} 逐字节相同
 * （admin/controller/Base.php:59-103、System.php:675-690；PHP 黄金样本 site/play/php-golden.json）；关闭时其中的解析地址
 * （{@code play.parse}、各播放器/下载器的 {@code parse}）与 {@code play.prestrain}、{@code play.buffer} 只要不是站内地址就输出为空串。
 */
public final class PlayerSettings {

    private static final PlayerSettings UNAVAILABLE = new PlayerSettings(false, 0, false, Map.of(), Map.of(), Map.of(),
            "", "", "", "", "");

    private final boolean available;
    private final int encrypt;
    private final boolean playerSort;
    private final Map<String, Map<String, String>> players;
    private final Map<String, Map<String, String>> downers;
    private final Map<String, Map<String, String>> servers;
    private final String globalParse;
    private final String prestrain;
    private final String buffer;
    private final String script;
    private final String siteHost;

    private PlayerSettings(boolean available, int encrypt, boolean playerSort, Map<String, Map<String, String>> players,
                           Map<String, Map<String, String>> downers, Map<String, Map<String, String>> servers,
                           String globalParse, String prestrain, String buffer, String script, String siteHost) {
        this.available = available;
        this.encrypt = encrypt;
        this.playerSort = playerSort;
        this.players = players;
        this.downers = downers;
        this.servers = servers;
        this.globalParse = globalParse;
        this.prestrain = prestrain;
        this.buffer = buffer;
        this.script = script;
        this.siteHost = siteHost;
    }

    /** 没有旧配置：播放/下载页不可用。 */
    public static PlayerSettings unavailable() {
        return UNAVAILABLE;
    }

    /**
     * 校验并建立。旧配置的值类型或取值不受支持时抛 {@link IllegalStateException}，信息只含旧配置键名。
     *
     * @param w1       浏览器侧外部地址放行 W1
     * @param siteHost base-url 的主机名（{@link BrowserAddress#hostOf}）
     */
    public static PlayerSettings from(PhpArray maccms, PhpArray vodplayer, PhpArray voddowner, PhpArray vodserver,
                                      boolean w1, String siteHost) {
        List<String> errors = new ArrayList<>();
        int encrypt = switch (scalar(maccms.path("app", "encrypt"))) {
            case "", "0" -> 0;
            case "1" -> 1;
            case "2" -> 2;
            default -> {
                errors.add("app.encrypt（只支持 0、1、2）");
                yield 0;
            }
        };
        boolean sort = switch (scalar(maccms.path("app", "player_sort"))) {
            case "", "0" -> false;
            case "1" -> true;
            default -> {
                errors.add("app.player_sort（只支持 0 或 1）");
                yield false;
            }
        };
        PhpValue playValue = maccms.path("play");
        PhpArray play;
        if (playValue instanceof PhpArray a) {
            play = a;
        } else {
            errors.add("play（应为数组）");
            play = new PhpArray(Map.of());
        }
        Map<String, Map<String, String>> players = entries(vodplayer, "extra/vodplayer.php", errors);
        Map<String, Map<String, String>> downers = entries(voddowner, "extra/voddowner.php", errors);
        Map<String, Map<String, String>> servers = entries(vodserver, "extra/vodserver.php", errors);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("旧播放器配置不受支持：" + String.join("；", errors));
        }
        String parse = scalar(play.get("parse"));
        String prestrain = scalar(play.get("prestrain"));
        String buffer = scalar(play.get("buffer"));
        String script = script(play, players, downers, servers, w1, siteHost);
        return new PlayerSettings(true, encrypt, sort, players, downers, servers, parse, prestrain, buffer, script,
                siteHost);
    }

    public boolean available() {
        return available;
    }

    /** app.encrypt：0 原样、1 escape、2 base64(escape)（All.php:533-540）。 */
    public int encrypt() {
        return encrypt;
    }

    /** app.player_sort=1：前台按播放器 sort 降序排列播放组（MAC_PLAYER_SORT，common.php:1398）。 */
    public boolean playerSort() {
        return playerSort;
    }

    /** 播放器配置：编码 → 配置项（值为 PHP 字符串转换后的结果），顺序与文件相同。 */
    public Map<String, Map<String, String>> players() {
        return players;
    }

    public Map<String, Map<String, String>> downers() {
        return downers;
    }

    public Map<String, Map<String, String>> servers() {
        return servers;
    }

    /** 由旧配置生成的 {@code playerconfig.js}（已按 W1 处理）。 */
    public String playerConfigScript() {
        return script;
    }

    /**
     * 该播放来源在浏览器中实际使用的解析地址：旧 player.js 在 {@code player_list[from].ps=="1"} 时用播放器自己的 parse，
     * 为空时用全局 {@code MacPlayerConfig.parse}（static/js/player.js:2）。不是解析来源时为空。按 W1 处理之前的原值。
     */
    public Optional<String> parseFor(String from) {
        Map<String, String> p = players.get(from);
        if (p == null || !"1".equals(p.getOrDefault("ps", ""))) {
            return Optional.empty();
        }
        String own = p.getOrDefault("parse", "");
        return Optional.of(own.isEmpty() ? globalParse : own);
    }

    /** {@code play.prestrain}、{@code play.buffer}（按 W1 处理之前的原值）。 */
    public List<String> prestrainAndBuffer() {
        return List.of(prestrain, buffer);
    }

    public String siteHost() {
        return siteHost;
    }

    // ------------------------------------------------------------------ playerconfig.js

    /**
     * 旧文件结构：仓库 static/js/playerconfig.js 的固定框架，"参数"段为 {@code MacPlayerConfig=json_encode(play);}，"缓存"段为三个列表，
     * 段前后各一个 \r\n（后台 str_replace 的写法）。列表项只取 show、des、ps、parse（播放器、下载器）与 show、des（服务器组），
     * 均按 PHP {@code (string)} 转换。
     */
    private static String script(PhpArray play, Map<String, Map<String, String>> players,
                                 Map<String, Map<String, String>> downers, Map<String, Map<String, String>> servers,
                                 boolean w1, String siteHost) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (Map.Entry<Object, PhpValue> e : play.entries().entrySet()) {
            String key = String.valueOf(e.getKey());
            PhpValue v = e.getValue();
            if (!w1 && (key.equals("parse") || key.equals("prestrain") || key.equals("buffer"))
                    && !local(scalar(v), siteHost)) {
                params.put(key, "");
            } else {
                params.put(key, v);
            }
        }
        String json = isList(play) ? PhpJson.encode(play) : PhpJson.encode(params);
        return "var MacPlayerConfig={};\r\n//参数开始\r\nMacPlayerConfig=" + json + ";\r\n//参数结束\r\n//缓存开始\r\n"
                + "MacPlayerConfig.player_list=" + PhpJson.encode(list(players, true, w1, siteHost))
                + ",MacPlayerConfig.downer_list=" + PhpJson.encode(list(downers, true, w1, siteHost))
                + ",MacPlayerConfig.server_list=" + PhpJson.encode(list(servers, false, w1, siteHost))
                + ";\r\n//缓存结束";
    }

    private static Object list(Map<String, Map<String, String>> items, boolean withParse, boolean w1, String siteHost) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : items.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            Map<String, String> v = e.getValue();
            item.put("show", v.getOrDefault("show", ""));
            item.put("des", v.getOrDefault("des", ""));
            if (withParse) {
                item.put("ps", v.getOrDefault("ps", ""));
                String parse = v.getOrDefault("parse", "");
                item.put("parse", !w1 && !local(parse, siteHost) ? "" : parse);
            }
            out.put(e.getKey(), item);
        }
        // PHP 数组的键全是 0..n-1 时 json_encode 输出数组；旧配置的键是播放器编码，不会出现这种情况，这里如实处理
        if (!out.isEmpty() && sequentialKeys(out)) {
            return new ArrayList<>(out.values());
        }
        return out.isEmpty() ? List.of() : out;
    }

    /** 站内地址或空串。 */
    static boolean local(String v, String siteHost) {
        BrowserAddress.Kind k = BrowserAddress.classify(v, siteHost).kind();
        return k == BrowserAddress.Kind.EMPTY || k == BrowserAddress.Kind.LOCAL;
    }

    private static boolean sequentialKeys(Map<String, Object> m) {
        long i = 0;
        for (String k : m.keySet()) {
            if (!k.equals(Long.toString(i))) {
                return false;
            }
            i++;
        }
        return true;
    }

    private static boolean isList(PhpArray a) {
        long expected = 0;
        for (Object k : a.entries().keySet()) {
            if (!(k instanceof Long l) || l != expected) {
                return false;
            }
            expected++;
        }
        return true;
    }

    /** 每个顶层条目须是数组；值按 PHP (string) 转换。 */
    private static Map<String, Map<String, String>> entries(PhpArray file, String name, List<String> errors) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map.Entry<Object, PhpValue> e : file.entries().entrySet()) {
            if (!(e.getValue() instanceof PhpArray item)) {
                errors.add(name + "（每一项应为数组）");
                continue;
            }
            Map<String, String> m = new LinkedHashMap<>();
            for (Map.Entry<Object, PhpValue> f : item.entries().entrySet()) {
                if (f.getValue() instanceof PhpArray) {
                    errors.add(name + "（配置项的值应为标量）");
                    continue;
                }
                m.put(String.valueOf(f.getKey()), scalar(f.getValue()));
            }
            out.put(String.valueOf(e.getKey()), Collections.unmodifiableMap(m));
        }
        return Collections.unmodifiableMap(out);
    }

    /** PHP {@code (string)} 转换（标量）；null 或数组为空串。 */
    static String scalar(PhpValue v) {
        if (v == null) {
            return "";
        }
        return switch (v) {
            case PhpValue.PhpString s -> s.value();
            case PhpValue.PhpInt i -> Long.toString(i.value());
            case PhpValue.PhpBool b -> b.value() ? "1" : "";
            case PhpValue.PhpNull n -> "";
            case PhpValue.PhpFloat f -> phpFloatString(f.value());
            case PhpArray a -> "";
        };
    }

    /** PHP 8 的 (string) 浮点：整数值不带小数点（1.0 → "1"），其余为最短往返表示。 */
    private static String phpFloatString(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return BigDecimal.valueOf(d).toBigInteger().toString();
        }
        return PhpJson.phpFloat(d).replace("e+", "E+").replace("e-", "E-");
    }
}
