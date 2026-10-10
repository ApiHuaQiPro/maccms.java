package com.maccms.backend.content.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * content 模块的行为开关，对齐旧 PHP 配置（application/extra/maccms.php）。线上取值待核实，默认取更严格的一侧。
 *
 * @param enforceGuestPopedom  对应 user.status=1：按游客组 group_id=1 的 group_type/group_popedom 判定可见性
 *                             （index/controller/Base.php:92-175）
 * @param copyrightHidesDetail 对应 app.copyright_status=2：vod_copyright=1 的详情不展示（index/controller/Vod.php:58-60）
 */
@ConfigurationProperties(prefix = "maccms.content")
public record ContentProperties(boolean enforceGuestPopedom, boolean copyrightHidesDetail) {

    /** 旧系统游客组固定为 1（common/controller/All.php:127,139,143）。 */
    public static final int GUEST_GROUP_ID = 1;
}
