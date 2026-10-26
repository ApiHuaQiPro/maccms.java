package com.maccms.backend.member.application;

import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.member.infrastructure.MemberMapper;
import com.maccms.backend.member.infrastructure.MemberWriteMapper;
import com.maccms.backend.member.infrastructure.PortraitStore;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import com.maccms.backend.content.application.AnnexAdminService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 会员账号服务的装配（B09）：只在站点页面启用时创建（会员入口都是站点页面）。旧配置 {@code user} 段启动时读一次
 * （{@link MemberSettings}），站点根路径取 {@code maccms.site.base-url} 的路径部分（旧 {@code MAC_PATH}；site 启动时已校验该地址）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "maccms.site", name = "enabled", havingValue = "true")
public class MemberAccountConfiguration {

    @Bean
    MemberSettings memberSettings(LegacyConfigService legacy) {
        if (!legacy.isConfigured()) {
            return MemberSettings.from(null);
        }
        try {
            return MemberSettings.from(legacy.read(LegacyConfigFile.MACCMS));
        } catch (LegacyConfigException e) {
            // 信息只含原因与行列，不含文件内容
            throw new IllegalStateException("会员功能读取旧配置 extra/maccms.php 失败：" + e.getMessage(), null);
        }
    }

    @Bean
    MemberAccountService memberAccountService(MemberMapper mapper, ObjectProvider<MemberWriteMapper> writer,
                                              MemberSessionService sessions, PortraitStore portraits,
                                              MemberSettings settings, ObjectProvider<MemberReferralService> referral,
                                              ObjectProvider<AnnexAdminService> annexes,
                                              @Value("${maccms.site.base-url:}") String baseUrl) {
        MemberReferralService r = referral.getIfAvailable();
        AnnexAdminService a = annexes.getIfAvailable();
        return new MemberAccountService(mapper, writer.getIfAvailable(), sessions, portraits, settings,
                Clock.systemUTC(), new SecureRandom(), sitePath(baseUrl),
                r == null ? inviterId -> { } : r::invitedRegistration,
                a == null ? (f, t, z) -> { } : (f, t, z) -> a.register(f, t, z));
    }

    /** base-url 的路径部分，以 {@code /} 结尾；解析不了时为 {@code /}（site 自己的校验会让启动失败）。 */
    static String sitePath(String baseUrl) {
        String path;
        try {
            path = URI.create(baseUrl == null ? "" : baseUrl.strip()).getRawPath();
        } catch (IllegalArgumentException e) {
            return "/";
        }
        if (path == null || path.isEmpty()) {
            return "/";
        }
        return path.endsWith("/") ? path : path + "/";
    }
}
