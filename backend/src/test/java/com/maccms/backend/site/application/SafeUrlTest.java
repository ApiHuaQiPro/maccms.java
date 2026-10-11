package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** URL 白名单：只放行 http/https 绝对地址与以单个 / 开头的站内路径。 */
class SafeUrlTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/", "/static/logo.png", "/vod/type/id/1.html", "/a?b=1&c=2#x",
            "http://maccms.local.test/", "https://img.example.test/p/1.jpg?w=200", "HTTPS://img.example.test/a.png",
            "http://127.0.0.1:18280/_site/placeholder", "https://img.example.test/图片.png"})
    void allowed(String url) {
        assertThat(SafeUrl.parse(url)).hasValueSatisfying(u -> assertThat(u.href()).isEqualTo(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", "javascript:alert(1)", "JavaScript:alert(1)", "java\tscript:alert(1)", "java\nscript:alert(1)",
            " javascript:alert(1)", "data:text/html,<b>x</b>", "vbscript:msgbox(1)", "file:///etc/passwd",
            "ftp://host/x", "//evil.example/x", "/\\evil.example/x", "\\\\evil.example\\x", "relative/path.png",
            "img.png", "http://user:pass@host/", "http://user@host/", "https:///nohost", "http:host",
            "https://host/a b", "https://host/\"onerror=\"x", "https://host/'x", "https://host/<x>", "https://host/`x`",
            "/a\u0000b", "/a b", "/a b", "﻿/a"})
    void rejected(String url) {
        assertThat(SafeUrl.parse(url)).as(url).isEmpty();
    }

    @Test
    void rejectsNullAndOverlong() {
        assertThat(SafeUrl.parse(null)).isEmpty();
        assertThat(SafeUrl.parse("/" + "a".repeat(SafeUrl.MAX_LENGTH))).isEmpty();
    }

    @Test
    void constructorRejectsWithoutEchoingValue() {
        assertThatThrownBy(() -> new SafeUrl("javascript:SYNTHETIC-SECRET"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("SYNTHETIC-SECRET");
    }

    @Test
    void absoluteVersusSitePath() {
        assertThat(new SafeUrl("https://a.test/").isAbsolute()).isTrue();
        assertThat(new SafeUrl("/a").isAbsolute()).isFalse();
    }
}
