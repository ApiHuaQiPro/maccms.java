package com.maccms.backend.admin.config;

import com.maccms.backend.admin.application.AdminAccountService;
import com.maccms.backend.admin.application.AdminLoginService;
import com.maccms.backend.admin.application.AdminSettings;
import com.maccms.backend.admin.application.LegacyInput;
import com.maccms.backend.admin.application.SystemConfigService;
import com.maccms.backend.admin.infrastructure.AdminMapper;
import com.maccms.backend.admin.infrastructure.AdminWriteMapper;
import com.maccms.backend.admin.web.AdminContentActions;
import com.maccms.backend.admin.web.AdminEndpoint;
import com.maccms.backend.admin.web.AdminMemberActions;
import com.maccms.backend.admin.web.AdminHtmlRenderer;
import com.maccms.backend.admin.web.AdminPages;
import com.maccms.backend.admin.web.AdminRandom;
import com.maccms.backend.admin.web.AdminResponses;
import com.maccms.backend.admin.web.AdminSessions;
import com.maccms.backend.admin.web.AdminSystemActions;
import com.maccms.backend.admin.web.jte.StaticTemplates;
import com.maccms.backend.common.captcha.CaptchaSettings;
import com.maccms.backend.content.application.ContentAdminServices;
import com.maccms.backend.member.application.MemberAdminServices;
import com.maccms.backend.comment.application.CommentAdminService;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.legacyconfig.application.LegacyConfigConfiguration;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import com.maccms.backend.content.application.PhpIntval;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 后台装配（B12，docs/architecture/21-b12-admin-framework.md 第 7 节），仅在 {@code maccms.admin.enabled=true} 时生效。
 *
 * <ul>
 * <li>部署参数 {@code maccms.admin.entry}、{@code maccms.admin.base-url} 在这里校验，缺失或不合法时启动失败（{@link AdminEndpoint#from}）。</li>
 * <li>后台要写 {@code mac_admin}（登录记录、管理员管理），可写数据源（B07）必须打开，否则启动失败。</li>
 * <li>旧配置（{@code extra/maccms.php} 的 {@code app.admin_login_verify}、{@code app.pagesize}、{@code app.lang}，{@code extra/version.php}、
 * {@code extra/captcha.php}）启动时读一次，改了要重启（与 site 相同；配置热加载在 B15）；没有旧配置目录时用仓库值。快捷菜单每次请求读
 * （按修改时间热加载）。</li>
 * <li>会话、验证码参数与随机源都不注册成 Bean（站点也有同类型的 Bean，按类型注入会冲突），只在本模块内使用。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAdminEnabled
@EnableConfigurationProperties(AdminProperties.class)
@Import(LegacyConfigConfiguration.class)
public class AdminConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AdminConfiguration.class);
    private static final RandomGenerator RANDOM = new SecureRandom();

    @Bean
    AdminEndpoint adminEndpoint(AdminProperties properties) {
        return AdminEndpoint.from(properties.entry(), properties.baseUrl());
    }

    @Bean
    AdminSettings adminSettings(LegacyConfigService legacy) {
        if (!legacy.isConfigured()) {
            // 仓库 application/extra/maccms.php:92-96 与 captcha.php 的值
            return new AdminSettings(true, 20, LegacyLang.of(LegacyLang.DEFAULT_CODE), "",
                    CaptchaSettings.fromLegacy(Map.of("codeSet", "1234567890", "length", 4L, "fontSize", 16L,
                            "useNoise", false, "useCurve", false)));
        }
        PhpArray maccms;
        try {
            maccms = legacy.read(LegacyConfigFile.MACCMS);
        } catch (LegacyConfigException e) {
            throw new IllegalStateException("admin 已启用但旧配置 extra/maccms.php 读取失败：" + e.getMessage(), null);
        }
        boolean verify = loginVerify(maccms.path("app", "admin_login_verify"));
        long size = PhpIntval.of(PhpValues.text(maccms.path("app", "pagesize")));
        LegacyLang.Selection lang = LegacyLang.select(PhpValues.text(maccms.path("app", "lang")));
        if (lang.fallback()) {
            log.warn("legacy app.lang is not zh-cn or zh-tw; admin pages use zh-cn");
        }
        String version = "";
        try {
            version = PhpValues.text(legacy.read(LegacyConfigFile.VERSION).get("code"));
        } catch (LegacyConfigException e) {
            log.warn("legacy version.php unreadable; version parameter left empty");
        }
        return new AdminSettings(verify, size < 1 ? 20 : (int) Math.min(size, AdminAccountService.MAX_LIMIT),
                lang.pack(), version, captcha(legacy));
    }

    /**
     * 旧 {@code $config['app']['admin_login_verify'] != '0'}（PHP 8 宽松比较）：整数/浮点为 0、{@code false}、数值为 0 的数字字符串
     * 等于 {@code '0'}（不要验证码）；空串、非数字字符串、{@code null}、缺失都不等于（要验证码）。
     */
    static boolean loginVerify(PhpValue v) {
        return switch (v) {
            case null -> true;
            case PhpValue.PhpString s -> !LegacyInput.looseEqualsNumber(s.value(), 0);
            case PhpValue.PhpInt i -> i.value() != 0;
            case PhpValue.PhpFloat f -> f.value() != 0;
            case PhpValue.PhpBool b -> b.value();
            case PhpValue.PhpNull n -> true;
            case PhpArray a -> true;
        };
    }

    private static CaptchaSettings captcha(LegacyConfigService legacy) {
        PhpArray captcha;
        try {
            captcha = legacy.read(LegacyConfigFile.CAPTCHA);
        } catch (LegacyConfigException e) {
            if (e.reason() == LegacyConfigException.Reason.NOT_FOUND) {
                return CaptchaSettings.THINK_DEFAULTS;
            }
            throw new IllegalStateException("admin 已启用但旧验证码配置 extra/captcha.php 读取失败：" + e.getMessage(), null);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        captcha.entries().forEach((k, v) -> values.put(String.valueOf(k), scalar(v)));
        return CaptchaSettings.fromLegacy(values);
    }

    private static Object scalar(PhpValue v) {
        return switch (v) {
            case PhpValue.PhpString s -> s.value();
            case PhpValue.PhpInt i -> i.value();
            case PhpValue.PhpFloat f -> f.value();
            case PhpValue.PhpBool b -> b.value();
            case PhpValue.PhpNull n -> null;
            case PhpArray a -> null;
        };
    }

    @Bean
    AdminLoginService adminLoginService(AdminMapper reader, ObjectProvider<AdminWriteMapper> writer,
                                        ObjectProvider<Clock> clock) {
        return new AdminLoginService(reader, requireWriter(writer), clock.getIfAvailable(Clock::systemUTC), RANDOM);
    }

    @Bean
    AdminAccountService adminAccountService(AdminMapper reader, ObjectProvider<AdminWriteMapper> writer,
                                            AdminSettings settings) {
        return new AdminAccountService(reader, requireWriter(writer), settings.lang());
    }

    @Bean
    AdminSessions adminSessions(AdminEndpoint endpoint, ObjectProvider<Clock> clock) {
        return new AdminSessions(clock.getIfAvailable(Clock::systemUTC), endpoint);
    }

    @Bean
    AdminResponses adminResponses(AdminEndpoint endpoint) {
        return new AdminResponses(new AdminHtmlRenderer(new StaticTemplates()), endpoint.sitePath() + "/admin-assets");
    }

    @Bean
    AdminPages adminPages(AdminEndpoint endpoint, AdminSettings settings, AdminResponses responses,
                          LegacyConfigService legacy, ObjectProvider<Clock> clock,
                          @Value("${spring.servlet.multipart.max-file-size:1MB}") String maxUpload) {
        return new AdminPages(endpoint, settings, responses, legacy, clock.getIfAvailable(Clock::systemUTC), maxUpload);
    }

    /**
     * B13 后台内容管理的动作（22 文档）。内容服务只在可写数据源打开时注册；单元测试的替身上下文里没有时，B13 的动作按"由后续批次实现"处理。
     */
    @Bean
    AdminContentActions adminContentActions(AdminEndpoint endpoint, AdminSettings settings, AdminResponses responses,
                                            AdminRandom random, ObjectProvider<Clock> clock,
                                            ObjectProvider<ContentAdminServices> content,
                                            ObjectProvider<CommentAdminService> comments) {
        return new AdminContentActions(endpoint, settings, responses, content.getIfAvailable(), comments.getIfAvailable(), random,
                clock.getIfAvailable(Clock::systemUTC));
    }

    /**
     * B14 后台会员与财务管理的动作（23 文档）。会员服务只在可写数据源打开时注册；单元测试的替身上下文里没有时，B14 的动作按"由后续批次实现"
     * 处理（同 B13）。
     */
    @Bean
    AdminMemberActions adminMemberActions(AdminEndpoint endpoint, AdminSettings settings, AdminResponses responses,
                                          ObjectProvider<Clock> clock, ObjectProvider<MemberAdminServices> members,
                                          ObjectProvider<ContentAdminServices> content) {
        return new AdminMemberActions(endpoint, settings, responses, members.getIfAvailable(), content.getIfAvailable(),
                clock.getIfAvailable(Clock::systemUTC));
    }

    /** B15 后台系统配置（24 文档第 5 节）：读写旧配置文件与旧站目录里的文件，不访问数据库。 */
    @Bean
    SystemConfigService adminSystemConfigService(LegacyConfigService legacy, LegacySiteFiles files, ObjectProvider<Clock> clock) {
        return new SystemConfigService(legacy, files, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    com.maccms.backend.admin.application.PlayerAdminService adminPlayerAdminService(LegacyConfigService legacy, LegacySiteFiles files) {
        return new com.maccms.backend.admin.application.PlayerAdminService(legacy, files);
    }

    @Bean
    com.maccms.backend.admin.application.ListConfigService adminListConfigService(LegacyConfigService legacy) {
        return new com.maccms.backend.admin.application.ListConfigService(legacy);
    }

    @Bean
    com.maccms.backend.admin.web.AdminPlayerActions adminPlayerActions(AdminEndpoint endpoint, AdminSettings settings,
            AdminResponses responses, com.maccms.backend.admin.application.PlayerAdminService players,
            com.maccms.backend.admin.application.ListConfigService lists, SystemConfigService system, AdminRandom random) {
        return new com.maccms.backend.admin.web.AdminPlayerActions(endpoint, settings, responses, players, lists, system, random);
    }

    @Bean
    AdminSystemActions adminSystemActions(AdminEndpoint endpoint, AdminSettings settings, AdminResponses responses,
                                          SystemConfigService system, AdminRandom random) {
        return new AdminSystemActions(endpoint, settings, responses, system, random);
    }

    /** 后台控制器的随机源（验证码、表单令牌）：不注册为 RandomGenerator 类型的 Bean。 */
    @Bean
    AdminRandom adminRandom() {
        return new AdminRandom(RANDOM);
    }

    private static AdminWriteMapper requireWriter(ObjectProvider<AdminWriteMapper> writer) {
        AdminWriteMapper w = writer.getIfAvailable();
        if (w == null) {
            throw new IllegalStateException("admin 已启用但可写数据源未打开：后台登录与管理员管理要写 mac_admin");
        }
        return w;
    }
}
