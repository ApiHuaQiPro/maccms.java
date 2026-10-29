package com.maccms.backend.integration.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 邮件模板（B25，32 文档第 4.3 节）：只替换变量输出标签，其余模板语法原样保留、不执行。 */
class LegacyMailTemplateTest {

    @Test
    void replacesTheLegacyVariables() {
        Map<String, String> site = Map.of("site_name", "黄金样本站", "site_url", "www.example.test");
        assertThat(LegacyMailTemplate.render("【{$maccms.site_name}】的会员您好，注册验证码为：{$code}，请在{$time}分钟内完成验证。",
                "012345", "5", site)).isEqualTo("【黄金样本站】的会员您好，注册验证码为：012345，请在5分钟内完成验证。");
        assertThat(LegacyMailTemplate.render("{$maccms.nope}{$user.user_name}{$other}|{$code}", "1", "5", site))
                .as("未赋值的变量输出空").isEqualTo("|1");
        assertThat(LegacyMailTemplate.render("{:phpinfo()}{$code|md5}{ $code}{$maccms['site_name']}$1\\", "1", "5", site))
                .as("函数、过滤器等语法不执行").isEqualTo("{:phpinfo()}{$code|md5}{ $code}{$maccms['site_name']}$1\\");
        assertThat(LegacyMailTemplate.render("", "1", "5", site)).isEmpty();
        assertThat(LegacyMailTemplate.render(null, "1", "5", site)).isEmpty();
    }

    @Test
    void decodesLikeHtmlspecialcharsDecode() {
        assertThat(LegacyMailTemplate.htmlspecialcharsDecode("&lt;b&gt;&quot;a&quot; &amp;amp; &#039; &yen;&lt"))
                .isEqualTo("<b>\"a\" &amp; &#039; &yen;&lt");
    }
}
