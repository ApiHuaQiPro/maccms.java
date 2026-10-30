package com.maccms.backend.content.application;

import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 后台视频页用到的播放器、下载器、服务器组配置（旧 {@code config('vodplayer')}、{@code config('voddowner')}、{@code config('vodserver')}，
 * 22 文档第 7.2 节）：每次调用时读取（同 {@link AdminConfig}）。未配置旧配置目录、文件不存在或读取失败时为空。
 */
public final class AdminPlayers {

    private static final Logger log = LoggerFactory.getLogger(AdminPlayers.class);

    private final LegacyConfigService legacy;

    public AdminPlayers(LegacyConfigService legacy) {
        this.legacy = legacy;
    }

    /** 播放器配置：编码 → 配置项（值按 PHP 字符串转换；不是数组的项跳过）。 */
    public Map<String, Map<String, String>> players() {
        return read(LegacyConfigFile.VODPLAYER);
    }

    public Map<String, Map<String, String>> downers() {
        return read(LegacyConfigFile.VODDOWNER);
    }

    public Map<String, Map<String, String>> servers() {
        return read(LegacyConfigFile.VODSERVER);
    }

    private Map<String, Map<String, String>> read(LegacyConfigFile file) {
        if (legacy == null || !legacy.isConfigured()) {
            return Map.of();
        }
        PhpArray a;
        try {
            a = legacy.read(file);
        } catch (LegacyConfigException e) {
            log.warn("legacy player config unreadable, treated as empty: {}", SafeExceptionLog.typeChain(e));
            return Map.of();
        }
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map.Entry<Object, PhpValue> e : a.entries().entrySet()) {
            if (!(e.getValue() instanceof PhpArray item)) {
                continue;
            }
            Map<String, String> m = new LinkedHashMap<>();
            for (Map.Entry<Object, PhpValue> f : item.entries().entrySet()) {
                m.put(String.valueOf(f.getKey()), f.getValue() instanceof PhpArray ? "Array"
                        : com.maccms.backend.legacyconfig.application.view.PhpValues.text(f.getValue()));
            }
            out.put(String.valueOf(e.getKey()), Collections.unmodifiableMap(m));
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * 旧 {@code mac_multisort($list, 'sort', SORT_DESC, 'status', '1')}（application/common.php:1492-1504）：去掉 {@code status != '1'}
     * （宽松比较）的项，按 {@code sort}（缺失为 0）降序；{@code sort} 相同时按 {@code array_multisort} 的第二个数组，即配置项数组本身升序
     * （PHP 数组比较：先比项数，再按左边的键逐个比值）。
     */
    public static List<Map<String, String>> sorted(Map<String, Map<String, String>> list) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> v : list.values()) {
            if (PhpValues.looseEqualsString(v.get("status"), "1")) {
                out.add(v);
            }
        }
        out.sort((a, b) -> {
            int c = PhpValues.compareRegular(sortOf(b), sortOf(a));
            return c != 0 ? c : compareArrays(a, b);
        });
        return out;
    }

    private static String sortOf(Map<String, String> v) {
        String s = v.get("sort");
        return s == null ? "0" : s;
    }

    /** PHP 数组的 {@code <=>}：项数少的小；项数相同时按左边的键逐个比较，右边没有这个键时不可比（PHP 返回 1）。 */
    static int compareArrays(Map<String, String> a, Map<String, String> b) {
        if (a.size() != b.size()) {
            return Integer.compare(a.size(), b.size());
        }
        for (Map.Entry<String, String> e : a.entrySet()) {
            if (!b.containsKey(e.getKey())) {
                return 1;
            }
            int c = PhpValues.compareRegular(e.getValue(), b.get(e.getKey()));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
