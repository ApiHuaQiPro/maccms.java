package com.maccms.backend.comment.application;

import com.maccms.backend.comment.application.view.BoardSettings;
import com.maccms.backend.comment.application.view.InteractionSettings;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 旧配置 {@code extra/maccms.php} 的 {@code comment}、{@code gbook}、{@code app.filter_words}（B11）。每次调用读取：旧配置按修改时间热加载
 * （{@link LegacyConfigService}），与旧版每个请求读配置一致，后台改了开关即时生效。没有配置旧配置目录时用 {@link InteractionSettings#DEFAULTS}；
 * 配置文件读取失败时抛出 {@code LegacyConfigException}（调用方按服务不可用处理，不回退到默认值——旧版此时同样无法运行）。
 */
public final class InteractionSettingsSource {

    private final LegacyConfigService legacy;

    public InteractionSettingsSource(LegacyConfigService legacy) {
        this.legacy = legacy;
    }

    public InteractionSettings current() {
        if (legacy == null || !legacy.isConfigured()) {
            return InteractionSettings.DEFAULTS;
        }
        return from(legacy.read(LegacyConfigFile.MACCMS));
    }

    static InteractionSettings from(PhpArray maccms) {
        List<byte[]> words = new ArrayList<>();
        for (String w : PhpValues.text(maccms.path("app", "filter_words")).split(",", -1)) {
            words.add(w.getBytes(StandardCharsets.UTF_8));
        }
        return new InteractionSettings(board(maccms, "comment"), board(maccms, "gbook"), List.copyOf(words));
    }

    private static BoardSettings board(PhpArray maccms, String section) {
        long size = PhpLoose.intval(maccms.path(section, "pagesize"));
        return new BoardSettings(!PhpLoose.equalsInt(maccms.path(section, "status"), 0),
                PhpLoose.equalsInt(maccms.path(section, "audit"), 1),
                PhpLoose.equalsInt(maccms.path(section, "login"), 1),
                PhpLoose.equalsInt(maccms.path(section, "verify"), 1),
                (int) (size < 1 ? 20 : Math.min(size, 100)), timespan(maccms.path(section, "timespan")));
    }

    /**
     * ThinkPHP {@code cookie($n, $v, $option)}（thinkphp/library/think/Cookie.php）：{@code is_numeric($option)} 时 {@code expire = $option}，
     * {@code !empty(expire)} 时有效期为 {@code intval(expire)} 秒；否则写会话 cookie（{@code null}）。
     */
    private static Long timespan(PhpValue v) {
        if (!PhpLoose.isNumeric(PhpValues.text(v)) || PhpValues.empty(v)) {
            return null;
        }
        return PhpLoose.intval(v);
    }
}
