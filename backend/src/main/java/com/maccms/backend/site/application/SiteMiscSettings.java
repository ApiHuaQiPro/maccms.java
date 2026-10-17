package com.maccms.backend.site.application;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;

/**
 * B06 杂项页用到的旧配置（16 文档第 4.5、4.9 节），启动时读一次：
 *
 * @param copyrightNotice 版权提示文案 {@code app.copyright_notice}（application/extra/maccms.php:74）；没有旧配置或为空时用仓库值
 * @param legacySearch    旧 {@code app.search == 1}（联想词的开关，Ajax.php:90）；没有旧配置时按仓库值 1
 */
public record SiteMiscSettings(String copyrightNotice, boolean legacySearch) {

    /** 仓库值（application/extra/maccms.php:74）。 */
    public static final String DEFAULT_COPYRIGHT_NOTICE = "该视频由于版权限制，暂不提供播放。";

    public SiteMiscSettings {
        copyrightNotice = copyrightNotice == null || copyrightNotice.isBlank() ? DEFAULT_COPYRIGHT_NOTICE : copyrightNotice;
    }

    /** @param maccms 旧配置 {@code extra/maccms.php}，没有配置旧配置目录时为 null */
    public static SiteMiscSettings from(PhpArray maccms) {
        if (maccms == null) {
            return new SiteMiscSettings(null, true);
        }
        String notice = maccms.path("app", "copyright_notice") instanceof PhpValue.PhpString s ? s.value() : null;
        PhpValue search = maccms.path("app", "search");
        boolean legacySearch = search instanceof PhpValue.PhpInt i ? i.value() == 1
                : search instanceof PhpValue.PhpString s && s.value().strip().equals("1");
        return new SiteMiscSettings(notice, legacySearch);
    }
}
