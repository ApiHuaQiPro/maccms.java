package com.maccms.backend.member.application;

import com.maccms.backend.content.application.ContentLookupService;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.member.infrastructure.MemberFinanceMapper;
import com.maccms.backend.member.infrastructure.MemberFinanceWriteMapper;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 会员权益与财务服务的装配（B10，docs/architecture/19-b10-member-finance.md）：与会员账号相同，只在站点页面启用时创建。旧配置的
 * {@code user}、{@code pay} 段启动时读一次（{@link MemberFinanceSettings}）。可写数据源关闭时写 Mapper 不存在，各服务的
 * {@code available()} 为 false，会员入口按 503 处理（同 B09）；只读的已购买查询照常可用。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "maccms.site", name = "enabled", havingValue = "true")
public class MemberFinanceConfiguration {

    @Bean
    MemberFinanceSettings memberFinanceSettings(LegacyConfigService legacy) {
        if (!legacy.isConfigured()) {
            return MemberFinanceSettings.from(null);
        }
        try {
            return MemberFinanceSettings.from(legacy.read(LegacyConfigFile.MACCMS));
        } catch (LegacyConfigException e) {
            // 信息只含原因与行列，不含文件内容
            throw new IllegalStateException("会员财务读取旧配置 extra/maccms.php 失败：" + e.getMessage(), null);
        }
    }

    @Bean
    MemberRecordService memberRecordService(MemberFinanceMapper reader, ObjectProvider<MemberFinanceWriteMapper> writer,
                                            ContentLookupService content) {
        return new MemberRecordService(reader, writer.getIfAvailable(), content, Clock.systemUTC());
    }

    @Bean
    MemberPurchaseService memberPurchaseService(MemberFinanceMapper reader,
                                                ObjectProvider<MemberFinanceWriteMapper> writer,
                                                ContentLookupService content, MemberFinanceSettings settings) {
        return new MemberPurchaseService(reader, writer.getIfAvailable(), content, settings, Clock.systemUTC());
    }

    @Bean
    MemberWalletService memberWalletService(MemberFinanceMapper reader, ObjectProvider<MemberFinanceWriteMapper> writer,
                                            MemberFinanceSettings settings) {
        return new MemberWalletService(reader, writer.getIfAvailable(), settings, Clock.systemUTC(), new SecureRandom());
    }

    @Bean
    MemberReferralService memberReferralService(ObjectProvider<MemberFinanceWriteMapper> writer,
                                                MemberFinanceSettings settings) {
        return new MemberReferralService(writer.getIfAvailable(), settings, Clock.systemUTC());
    }

    /** {@code maccms.member.expiry-sweep-minutes}（部署参数，默认 0 = 不定时执行，第 4.14 节）。 */
    @Bean
    MemberExpiryService memberExpiryService(ObjectProvider<MemberFinanceWriteMapper> writer,
                                            @Value("${maccms.member.expiry-sweep-minutes:0}") long minutes) {
        if (minutes < 0 || minutes > 7 * 24 * 60) {
            throw new IllegalStateException("maccms.member.expiry-sweep-minutes 须为 0～10080");
        }
        return new MemberExpiryService(writer.getIfAvailable(), Clock.systemUTC(),
                minutes == 0 ? null : Duration.ofMinutes(minutes));
    }
}
