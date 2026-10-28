package com.maccms.backend.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.legacyconfig.application.LegacyConfigProperties;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.VarExportParser;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 第三方接口配置的读取（B23 ③）：经 B02 的 legacyconfig 读取 maccms.php，取值方式与旧版一致，密钥字段只能显式取出。 */
class ThirdPartyConfigTest {

    static final Path REPO_MACCMS = Path.of("..", "application", "extra", "maccms.php");

    /** 仓库 maccms.php 的第三方组，密钥字段换成假值（只在内存与临时目录里）。 */
    static String withFakeSecrets() throws IOException {
        String php = Files.readString(REPO_MACCMS, StandardCharsets.UTF_8);
        php = replaceOnce(php, "      'mchid' => '',", "      'mchid' => 'test-only-mchid-0001',");
        php = php.replaceFirst("('alipay' => \\s*array \\(\\s*'account' => '111',\\s*'appid' => '',\\s*'appkey' => )''",
                "$1'test-only-not-a-secret-alipay'");
        php = replaceOnce(php, "    'token' => '',\n    'guanzhu'", "    'token' => 'test-only-weixin-token',\n    'guanzhu'");
        php = replaceOnce(php, "      'password' => '',", "      'password' => 'test-only-smtp-password'");
        return php;
    }

    private static String replaceOnce(String s, String from, String to) {
        String crlfFrom = from.replace("\n", "\r\n");
        String src = s.contains(from) ? from : crlfFrom;
        assertThat(s).as(from).contains(src);
        return s.replace(src, s.contains(from) ? to : to.replace("\n", "\r\n"));
    }

    private static ThirdPartyConfig parse(String php) {
        return ThirdPartyConfig.from(VarExportParser.parse(php.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsLikeLegacyFromTheRepositoryConfig() throws IOException {
        ThirdPartyConfig c = parse(Files.readString(REPO_MACCMS, StandardCharsets.UTF_8));
        assertThat(c.text("sms", "sign")).isEqualTo("我的网站");
        assertThat(c.text("sms", "tpl_code_reg")).isEqualTo("SMS_144850895");
        assertThat(c.text("email", "phpmailer", "host")).isEqualTo("smtp.qq.com");
        assertThat(c.text("email", "phpmailer", "port")).isEqualTo("587");
        assertThat(c.text("pay", "min")).isEqualTo("10");
        assertThat(c.text("connect", "qq", "status")).isEqualTo("0");
        assertThat(c.empty("connect", "qq", "status")).isTrue();
        assertThat(c.text("weixin", "duijie")).isEqualTo("wx.test.com");
        // 不存在的键与旧版一样是空串；数组按 PHP (string) 是 "Array"
        assertThat(c.text("pay", "nope", "x")).isEmpty();
        assertThat(c.text("urlsend", "baidu")).isEqualTo("Array");
        // 仓库里密钥字段都是空的
        assertThat(c.secret("pay", "alipay", "appkey").isEmpty()).isTrue();
        assertThat(c.secret("urlsend", "baidu", "token").isEmpty()).isTrue();
        assertThat(c.secretValues()).isEmpty();
    }

    @Test
    void secretsOnlyThroughSecretAndNeverInToString() throws IOException {
        ThirdPartyConfig c = parse(withFakeSecrets());
        Secret appkey = c.secret("pay", "alipay", "appkey");
        assertThat(appkey.reveal()).isEqualTo("test-only-not-a-secret-alipay");
        assertThat(appkey.toString()).isEqualTo("Secret[***]").doesNotContain("test-only");
        assertThat(c.secret("pay", "weixin", "appkey").toString()).isEqualTo("Secret[empty]");
        assertThat(c.secretValues()).containsExactly("test-only-mchid-0001", "test-only-not-a-secret-alipay",
                "test-only-smtp-password", "test-only-weixin-token");
        assertThat(c.toString()).isEqualTo("ThirdPartyConfig[pay, sms, email, connect, weixin, urlsend]");

        assertThatThrownBy(() -> c.text("pay", "alipay", "appkey")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("test-only");
        assertThatThrownBy(() -> c.text("weixin", "token")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.secret("sms", "sign")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.text("db", "pass")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.text("site", "site_name")).isInstanceOf(IllegalArgumentException.class);
        // 已知密钥值交给脱敏后按原文遮盖
        assertThat(LogRedactor.redact("resp: test-only-weixin-token / test-only-mchid-0001", c.secretValues()))
                .isEqualTo("resp: *** / ***");
    }

    @Test
    void emptyWhenLegacyConfigIsNotConfigured() {
        ThirdPartyConfig c = new IntegrationConfiguration().thirdPartyConfig(new LegacyConfigService(new LegacyConfigProperties("")));
        assertThat(c.text("sms", "sign")).isEmpty();
        assertThat(c.secret("pay", "alipay", "appkey").isEmpty()).isTrue();
        assertThat(c.secretValues()).isEmpty();
    }

    @Test
    void readsThroughLegacyConfigServiceAndFailsWithoutContent(@TempDir Path dir) throws IOException {
        Path extra = Files.createDirectories(dir.resolve("extra"));
        Files.writeString(extra.resolve("maccms.php"), withFakeSecrets(), StandardCharsets.UTF_8);
        LegacyConfigService legacy = new LegacyConfigService(new LegacyConfigProperties(dir.toString()));
        ThirdPartyConfig c = new IntegrationConfiguration().thirdPartyConfig(legacy);
        assertThat(c.secret("email", "phpmailer", "password").reveal()).isEqualTo("test-only-smtp-password");

        Files.writeString(extra.resolve("maccms.php"), "<?php\nreturn array ('pay' => 'test-only-x' . phpinfo());",
                StandardCharsets.UTF_8);
        LegacyConfigService broken = new LegacyConfigService(new LegacyConfigProperties(dir.toString()));
        assertThatThrownBy(() -> new IntegrationConfiguration().thirdPartyConfig(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("第三方接口配置读取旧配置 extra/maccms.php 失败")
                .hasMessageNotContaining("test-only");
    }

    @Test
    void fromNullAndMissingGroups() {
        assertThat(ThirdPartyConfig.from(null).text("pay", "min")).isEmpty();
        PhpArray onlySite = VarExportParser.parse("<?php\nreturn array ('site' => array ('site_name' => 'x'));"
                .getBytes(StandardCharsets.UTF_8));
        assertThat(ThirdPartyConfig.from(onlySite).empty("pay")).isTrue();
        assertThat(ThirdPartyConfig.from(onlySite).toString()).isEqualTo("ThirdPartyConfig[]");
    }
}
