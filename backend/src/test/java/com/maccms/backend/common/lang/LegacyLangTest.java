package com.maccms.backend.common.lang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.common.LegacyCheckout;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 旧语言包（B12，21 文档第 6 节）：jar 内的 {@code lang/zh-cn.json}、{@code zh-tw.json} 由 lang_export.php 从仓库语言包导出；源文件变了
 * 就必须重新导出（这里按 SHA-256 锁定）。语言选择与取值语义同 ThinkPHP 5.0 {@code Lang}。
 */
class LegacyLangTest {

    @ParameterizedTest
    @CsvSource({"zh-cn", "zh-tw"})
    void bundledPacksMatchTheRepositorySources(String code) throws Exception {
        String want = LegacyLang.of(code).sourceSha256();
        byte[] source = LegacyCheckout.read(Path.of("..", "application", "lang", code + ".php"), want);
        assertThat(LegacyCheckout.sha256(source))
                .as("application/lang/%s.php 变了：用 backend/src/test/php/lang_export.php 重新导出" + LegacyCheckout.HINT, code)
                .isEqualTo(want);
        assertThat(LegacyLang.of(code).size()).isEqualTo(1773);
    }

    @Test
    void valuesAreTheLegacyTextsInEachLanguage() {
        LegacyLang cn = LegacyLang.of("zh-cn");
        LegacyLang tw = LegacyLang.of("zh-tw");
        assertThat(cn.get("permission_denied")).isEqualTo("您没有权限访问此页面");
        assertThat(tw.get("permission_denied")).isEqualTo("您沒有權限訪問此頁面");
        assertThat(cn.get("model/admin/login_ok")).isEqualTo("登录成功");
        assertThat(tw.get("model/admin/login_ok")).isEqualTo("登錄成功");
        assertThat(cn.get("menu/admin")).isEqualTo("管理员");
        // 双引号字符串里的 \n 是换行（旧语言包 1203 行）
        assertThat(cn.get("admin/system/configuser/email_white_hosts_tip")).contains("360.com\n注：");
    }

    @Test
    void lookupIsAsciiCaseInsensitiveAndMissingKeysEchoTheName() {
        LegacyLang cn = LegacyLang.of("zh-cn");
        assertThat(cn.get("PERMISSION_DENIED")).isEqualTo("您没有权限访问此页面");
        assertThat(cn.get("Menu/Admin")).isEqualTo("管理员");
        // ThinkPHP Lang::get：没有这个键时原样返回调用方给的名字（不转小写）
        assertThat(cn.get("No/Such_Key")).isEqualTo("No/Such_Key");
        assertThat(cn.has("menu/admin")).isTrue();
        assertThat(cn.has("menu/nope")).isFalse();
    }

    @Test
    void selectionFollowsAppLangWithSimplifiedAsDefaultAndFallback() {
        assertThat(LegacyLang.select(null)).isEqualTo(new LegacyLang.Selection(LegacyLang.of("zh-cn"), false));
        assertThat(LegacyLang.select("")).isEqualTo(new LegacyLang.Selection(LegacyLang.of("zh-cn"), false));
        assertThat(LegacyLang.select("zh-cn").pack().code()).isEqualTo("zh-cn");
        assertThat(LegacyLang.select("zh-tw")).isEqualTo(new LegacyLang.Selection(LegacyLang.of("zh-tw"), false));
        // 旧版按原值拼文件名，文件不存在时页面显示键名本身；新版回退到简体并告警（D-B12-L1）
        for (String other : new String[] {"en-us", "ZH-TW", "zh-tw ", "../zh-cn", "zh-CN"}) {
            assertThat(LegacyLang.select(other)).as(other).isEqualTo(new LegacyLang.Selection(LegacyLang.of("zh-cn"), true));
        }
        assertThatThrownBy(() -> LegacyLang.of("en-us")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void localizeMapsWholeZhCnValuesToTheSelectedPack() {
        LegacyLang cn = LegacyLang.of("zh-cn");
        LegacyLang tw = LegacyLang.of("zh-tw");
        // 新版照抄的旧 lang(键) 原文：整段等于 zh-cn 包里某个键的值时，取本包同一键的值
        assertThat(tw.localize("页面不存在")).isEqualTo("頁面不存在");
        assertThat(tw.localize("登录成功")).isEqualTo("登錄成功");
        assertThat(tw.localize("您没有权限访问此页面")).isEqualTo(tw.get("permission_denied"));
        // 简体包原样；不是任何一项完整值的（新版新增、拼接、只含一部分）原样；不做简繁自动转换
        assertThat(cn.localize("页面不存在")).isEqualTo("页面不存在");
        assertThat(tw.localize("您访问的页面不存在或已被移除。")).isEqualTo("您访问的页面不存在或已被移除。");
        assertThat(tw.localize("页面不存在!")).isEqualTo("页面不存在!");
        assertThat(tw.localize("")).isEqualTo("");
        assertThat(tw.localize(null)).isNull();
        // zh-cn 值相同、zh-tw 值不同的键（"采集"）无法确定是哪一个，原样
        assertThat(tw.localize("采集")).isEqualTo("采集");
    }
}
