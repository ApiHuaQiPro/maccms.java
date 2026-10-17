package com.maccms.backend.content.application;

import com.maccms.backend.content.application.view.ArticleContentPageDto;
import com.maccms.backend.content.application.view.PlotDto;
import com.maccms.backend.content.application.view.ScreenshotDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 旧库多值字段的解码，逐条复刻 application/common.php 中的旧函数（行号见各方法），由 PHP 黄金向量
 * （src/test/resources/content/multivalue/php-vectors.json）锁定。说明见 docs/architecture/11-content-read-completion.md 第 3 节。
 *
 * <p>存储格式：{@code $$$} 分隔组（播放组、文章分页、分集剧情），{@code #} 分隔集/截图，{@code $} 分隔标题、地址、来源。
 * PHP 的 {@code empty()} 把 {@code ""} 与 {@code "0"} 都当作空，这里同样处理；PHP 中取不到的数组元素（null）一律输出为 {@code ""}。
 * 纯函数：不读配置、不访问数据库，播放器配置由调用方传入。
 */
public final class LegacyMultiValue {

    /** 集名缺省为"第N集"（lang('the').N.lang('episode')，application/lang/zh-cn.php:84-85，zh-tw 相同）。 */
    static final String EPISODE_PREFIX = "第";
    static final String EPISODE_SUFFIX = "集";

    private LegacyMultiValue() {
    }

    /** 一集播放/下载地址：nid 是该集在 {@code #} 分隔序列中的位置（从 1 起，空段占位，所以可能不连续）。 */
    public record PlayEpisode(int nid, String name, String url, String from) {
    }

    /**
     * 一个播放组（mac_play_list 的一项）。
     *
     * @param sid        组在 {@code $$$} 序列中的位置（从 1 起；被过滤的组占位，所以可能不连续）
     * @param url        该组的原始地址串
     * @param player     播放器/下载器配置项（启用的才会出现）
     * @param serverInfo 服务器组配置项，找不到时为空 Map
     */
    public record PlaySource(int sid, String from, String url, String server, String note,
                             Map<String, String> player, Map<String, String> serverInfo, List<PlayEpisode> episodes) {

        public PlaySource {
            player = Map.copyOf(player);
            serverInfo = Map.copyOf(serverInfo);
            episodes = List.copyOf(episodes);
        }

        public int urlCount() {
            return episodes.size();
        }
    }

    /**
     * mac_play_list_one（application/common.php:1434-1455）：解析一组的地址串。
     * mac_play_list 内部以两个参数调用（serverOne 为 ""），所以播放组的地址不带服务器前缀。
     */
    public static List<PlayEpisode> playEpisodes(String urlOne, String fromOne, String serverOne) {
        String prefix = serverOne == null ? "" : serverOne;
        String defaultFrom = fromOne == null ? "" : fromOne;
        List<String> segments = explode("#", urlOne == null ? "" : urlOne);
        List<PlayEpisode> out = new ArrayList<>();
        for (int key = 0; key < segments.size(); key++) {
            String val = segments.get(key);
            if (empty(val)) {
                continue;
            }
            List<String> parts = explode("$", val);
            String title = parts.get(0);
            String url = part(parts, 1);
            String from = part(parts, 2);
            int nid = key + 1;
            String name;
            String address;
            if (empty(url)) {
                name = EPISODE_PREFIX + nid + EPISODE_SUFFIX;
                address = prefix + title;
            } else {
                name = title;
                address = prefix + url;
            }
            out.add(new PlayEpisode(nid, name, address, empty(from) ? defaultFrom : from));
        }
        return List.copyOf(out);
    }

    /**
     * mac_play_list（application/common.php:1346-1407）：播放（flag=play，配置 vodplayer）或下载（flag=down，配置 voddowner）分组。
     * 只保留配置中 {@code status == '1'}（PHP 宽松比较）的组；{@code sortByPlayer} 为真时（前台且 app.player_sort=1，
     * MAC_PLAYER_SORT，application/common/behavior/Init.php:55）按配置 sort 降序（PHP SORT_REGULAR 比较），
     * 相同时按 sid 升序（array_multisort 以第二个数组为次序键）；否则按 sid 升序。
     *
     * @param players 播放器或下载器配置：编码 → 配置项（值为 PHP 标量转字符串后的结果）
     * @param servers 服务器组配置：编码 → 配置项
     */
    public static List<PlaySource> playSources(String from, String url, String server, String note,
                                               Map<String, Map<String, String>> players,
                                               Map<String, Map<String, String>> servers, boolean sortByPlayer) {
        List<String> froms = groups(from);
        List<String> urls = groups(url);
        List<String> serverCodes = groups(server);
        List<String> notes = groups(note);
        List<PlaySource> out = new ArrayList<>();
        for (int k = 0; k < froms.size(); k++) {
            String code = froms.get(k);
            Map<String, String> player = players.get(code);
            if (player == null || !PhpValues.looseEqualsString(player.get("status"), "1")) {
                continue;
            }
            String serverCode = part(serverCodes, k);
            String groupUrl = part(urls, k);
            Map<String, String> serverInfo = servers.get(serverCode);
            out.add(new PlaySource(k + 1, code, groupUrl, serverCode, part(notes, k), player,
                    serverInfo == null ? Map.of() : serverInfo, playEpisodes(groupUrl, code, "")));
        }
        if (sortByPlayer) {
            stableSort(out, Comparator.<PlaySource, String>comparing(s -> s.player().get("sort"), PhpValues::compareRegular)
                    .reversed()
                    .thenComparingInt(PlaySource::sid));
        }
        return List.copyOf(out);
    }

    /**
     * mac_art_list（application/common.php:1294-1318）：文章分页。页数取 art_content 的组数，标题与备注按位置对齐，缺少时为 ""。
     */
    public static List<ArticleContentPageDto> articlePages(String title, String note, String content) {
        List<String> titles = groups(title);
        List<String> notes = groups(note);
        List<String> contents = groups(content);
        List<ArticleContentPageDto> out = new ArrayList<>(contents.size());
        for (int k = 0; k < contents.size(); k++) {
            out.add(new ArticleContentPageDto(k + 1, part(titles, k), part(notes, k), contents.get(k)));
        }
        return List.copyOf(out);
    }

    /** mac_plot_list（application/common.php:1320-1341）：分集剧情。条数取 vod_plot_name 的组数，详情按位置对齐。 */
    public static List<PlotDto> plots(String name, String detail) {
        List<String> names = groups(name);
        List<String> details = groups(detail);
        List<PlotDto> out = new ArrayList<>(names.size());
        for (int k = 0; k < names.size(); k++) {
            out.add(new PlotDto(k + 1, names.get(k), part(details, k)));
        }
        return List.copyOf(out);
    }

    /**
     * mac_screenshot_list（application/common.php:1415-1432）：截图。旧调用方只在字段非空（PHP !empty）时调用
     * （application/common/model/Art.php:422-424、Website.php:453-455），这里对空值同样返回空列表。
     * 没有 {@code $} 或地址为空时，名称是序号、地址是整段。
     */
    public static List<ScreenshotDto> screenshots(String raw) {
        if (empty(raw)) {
            return List.of();
        }
        List<String> segments = explode("#", raw);
        List<ScreenshotDto> out = new ArrayList<>();
        for (int key = 0; key < segments.size(); key++) {
            String val = segments.get(key);
            if (empty(val)) {
                continue;
            }
            List<String> parts = explode("$", val);
            String title = parts.get(0);
            String url = part(parts, 1);
            int index = key + 1;
            out.add(empty(url) ? new ScreenshotDto(index, Integer.toString(index), title)
                    : new ScreenshotDto(index, title, url));
        }
        return List.copyOf(out);
    }

    /**
     * 稳定插入排序。数字与非数字的 sort 值混在一起时 PHP 的比较不满足传递性（如 "9" &lt; "10" &lt; "1a" &lt; "9"），
     * {@code List.sort} 可能因此抛出 IllegalArgumentException；这里只做相邻比较，不会抛异常（播放组通常只有几个）。
     * 这种配置下 PHP 的结果本身也依赖其排序算法，新旧顺序可能不同。
     */
    private static <T> void stableSort(List<T> list, Comparator<T> cmp) {
        for (int i = 1; i < list.size(); i++) {
            T cur = list.get(i);
            int j = i - 1;
            while (j >= 0 && cmp.compare(list.get(j), cur) > 0) {
                list.set(j + 1, list.get(j));
                j--;
            }
            list.set(j + 1, cur);
        }
    }

    // ------------------------------------------------------------------ PHP 语义

    /** {@code !empty($s) ? explode('$$$', $s) : []}。 */
    private static List<String> groups(String s) {
        return empty(s) ? List.of() : explode("$$$", s);
    }

    /** PHP explode：按字面分隔符从左到右切分，保留空段（含首尾）。 */
    static List<String> explode(String delimiter, String s) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int i;
        while ((i = s.indexOf(delimiter, start)) >= 0) {
            out.add(s.substring(start, i));
            start = i + delimiter.length();
        }
        out.add(s.substring(start));
        return out;
    }

    /** 取第 i 项，不存在时为 ""（PHP 中为 null）。 */
    private static String part(List<String> list, int i) {
        return i < list.size() ? list.get(i) : "";
    }

    /** PHP empty() 对字符串：{@code null}、{@code ""}、{@code "0"}。 */
    static boolean empty(String s) {
        return s == null || s.isEmpty() || "0".equals(s);
    }
}
