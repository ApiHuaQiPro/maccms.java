package com.maccms.backend.staticgen.application;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 播放/下载列表里每个来源的集数（旧 {@code mac_play_list}，application/common.php:1346-1406，与 {@code mac_play_list_one}，:1434-1452）：
 * 按 {@code $$$} 切分来源与地址；播放器（下载器）配置里 {@code status == '1'} 的来源才算，序号 sid 是来源在原串里的位置（从 1 开始）；
 * 集数是按 {@code #} 切分后非 {@code empty()} 的段数。静态生成（{@code $GLOBALS['ismake']}）时旧版按 sid 重新建键，所以只需要
 * "sid → 集数"，排序不影响生成哪些文件。
 */
final class EpisodeCounts {

    private EpisodeCounts() {
    }

    /**
     * @param from    {@code vod_play_from}（或 {@code vod_down_from}）
     * @param urls    {@code vod_play_url}（或 {@code vod_down_url}）
     * @param players {@code extra/vodplayer.php}（或 {@code voddowner.php}）；不存在时为 null（全部不可用）
     * @return sid → 集数，只含可用的来源；{@code from} 为空（{@code empty()}）时为空表
     */
    static Map<Integer, Integer> of(String from, String urls, PhpArray players) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        if (from == null || from.isEmpty() || from.equals("0")) {
            return out;
        }
        String[] froms = from.split("\\$\\$\\$", -1);
        String[] list = urls == null || urls.isEmpty() || urls.equals("0") ? new String[0] : urls.split("\\$\\$\\$", -1);
        for (int k = 0; k < froms.length; k++) {
            PhpValue p = players == null ? null : players.get(froms[k]);
            if (!(p instanceof PhpArray player) || !looseEqualsOne(player.get("status"))) {
                continue;
            }
            out.put(k + 1, count(k < list.length ? list[k] : ""));
        }
        return out;
    }

    /** {@code mac_play_list_one} 的条数：按 {@code #} 切分，跳过 {@code empty()} 的段（空串与 {@code "0"}）。 */
    static int count(String one) {
        int n = 0;
        for (String v : one.split("#", -1)) {
            if (!v.isEmpty() && !v.equals("0")) {
                n++;
            }
        }
        return n;
    }

    /** PHP 8 {@code $v == '1'}。 */
    static boolean looseEqualsOne(PhpValue v) {
        return switch (v) {
            case null -> false;
            case PhpValue.PhpString s -> TimmingSchedule.looseEqualsOne(s.value());
            case PhpValue.PhpInt i -> i.value() == 1;
            case PhpValue.PhpFloat f -> f.value() == 1.0;
            case PhpValue.PhpBool b -> b.value();
            case PhpValue.PhpNull n -> false;
            case PhpArray a -> false;
        };
    }

    /** {@code (string)} 取值的简写。 */
    static String text(PhpValue v) {
        return PhpValues.text(v);
    }
}
