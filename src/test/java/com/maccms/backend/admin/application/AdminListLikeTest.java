package com.maccms.backend.admin.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 管理员列表的搜索词（旧 {@code admin_name LIKE '%词%'}，默认转义符是反斜线）转写成 {@code ESCAPE '!'} 下的同义模式。
 * 对照用本机 MySQL 5.7 核对过：{@code 'xabc%' LIKE '%abc\%'} 为 1、{@code 'xabc' LIKE '%abc\%'} 为 0，与 {@code '%abc!%' ESCAPE '!'} 相同。
 */
class AdminListLikeTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "abc|%abc%",
            "a%b_c|%a%b_c%",
            "a!b|%a!!b%",
            "a\\%b|%a!%b%",
            "a\\\\b|%a!\\b%",
            "abc\\|%abc!%",
            "\\|%!%"})
    void legacyPatternsMatchTheSameStrings(String word, String like) {
        assertThat(AdminAccountService.legacyLike(word)).isEqualTo(like);
    }
}
