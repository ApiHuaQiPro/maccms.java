package com.maccms.backend.member.application;

import com.maccms.backend.common.write.ConditionalOnWriteEnabled;
import com.maccms.backend.content.application.AdminConfig;
import com.maccms.backend.content.application.AnnexAdminService;
import com.maccms.backend.content.application.ContentLookupService;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.member.infrastructure.MemberAdminMapper;
import com.maccms.backend.member.infrastructure.MemberAdminWrites;
import com.maccms.backend.member.infrastructure.MemberFinanceWriteMapper;
import com.maccms.backend.member.infrastructure.PortraitStore;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * B14 后台会员与财务管理的服务（23 文档第 2 节）。后台要求可写数据源打开（21 文档第 7 节），所以只在可写数据源打开时注册；与站点是否启用无关。
 *
 * <p>会员列表第 1 页的到期降级用 B10 的 {@link MemberExpiryService#expireAll()}：站点启用时取容器里的那一个（定时任务也在它上面），否则用同一条
 * 降级语句另建一个不定时执行的实例。旧配置（{@code user.reg_group}、{@code user.portrait_size}）每次调用都读（{@link AdminConfig}，同 B13）。
 *
 * <p>会员表单的头像上传（{@link AdminPortraitService}）写 {@link PortraitStore}（部署参数 {@code maccms.member.upload-dir}，与前台头像同一处），
 * 附件经 content 模块的 {@link AnnexAdminService} 登记（同 D-B09-9）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWriteEnabled
public class MemberAdminConfiguration {

    @Bean
    MemberAdminServices memberAdminServices(MemberAdminMapper reader, MemberAdminWrites writer,
                                            ObjectProvider<MemberFinanceWriteMapper> finance,
                                            ObjectProvider<MemberExpiryService> expiry, ContentLookupService content,
                                            ObjectProvider<LegacyConfigService> legacy, ObjectProvider<Clock> clockProvider,
                                            PortraitStore portraits, ObjectProvider<AnnexAdminService> annexes) {
        Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
        MemberExpiryService sweep = expiry.getIfAvailable(() -> new MemberExpiryService(finance.getIfAvailable(), clock, null));
        AdminConfig config = new AdminConfig(legacy.getIfAvailable());
        AnnexAdminService a = annexes.getIfAvailable();
        PortraitAnnex annex = a == null ? (f, t, z) -> { } : (f, t, z) -> a.register(f, t, z);
        return new MemberAdminServices(
                new UserAdminService(reader, writer, sweep, clock),
                new GroupAdminService(reader, writer, config),
                new CardAdminService(reader, writer, clock, new SecureRandom()),
                new FinanceAdminService(reader, writer, clock),
                new MemberLogAdminService(reader, writer, content, clock),
                new AdminPortraitService(reader, portraits, config, annex, clock));
    }
}
