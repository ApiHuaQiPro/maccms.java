package com.maccms.backend.content.application;

import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 后台内容管理读取的旧配置 {@code extra/maccms.php}（B13，22 文档第 4.4 节）：每次调用时读取（旧配置按修改时间热加载，与旧版每个请求读
 * {@code $GLOBALS['config']} 一致，同 B11 的 {@code VodSearchOptimise}）。未配置旧配置目录、读取失败或没有这一项时为空串。
 */
public final class AdminConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminConfig.class);

    private final LegacyConfigService legacy;

    public AdminConfig(LegacyConfigService legacy) {
        this.legacy = legacy;
    }

    /** {@code $GLOBALS['config'][k1][k2]…} 的字符串形式（PHP 字符串转换）；没有时为空串。 */
    public String text(String... path) {
        PhpValue v = value(path);
        return v == null ? "" : PhpValues.text(v);
    }

    /** 原始值；没有时为 null。 */
    public PhpValue value(String... path) {
        if (legacy == null || !legacy.isConfigured()) {
            return null;
        }
        try {
            PhpArray maccms = legacy.read(LegacyConfigFile.MACCMS);
            return maccms.path(path);
        } catch (LegacyConfigException e) {
            log.warn("legacy maccms.php unreadable, value treated as empty: {}", SafeExceptionLog.typeChain(e));
            return null;
        }
    }

    /** PHP {@code empty()}。 */
    public boolean empty(String... path) {
        PhpValue v = value(path);
        return v == null || PhpValues.empty(v);
    }
}
