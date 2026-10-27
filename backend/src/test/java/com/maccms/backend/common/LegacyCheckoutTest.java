package com.maccms.backend.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyCheckoutTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void crlfOnlyTouchesBareLineFeeds() {
        assertThat(LegacyCheckout.crlf(b("\na\nb\r\nc\r\r\n\n"))).isEqualTo(b("\r\na\r\nb\r\nc\r\r\n\r\n"));
        assertThat(LegacyCheckout.crlf(b("no newline"))).isEqualTo(b("no newline"));
    }

    @Test
    void readReturnsWhicheverBytesMatchTheLockedHash(@TempDir Path dir) throws Exception {
        Path lf = Files.write(dir.resolve("lf.php"), b("<?php\nreturn 1;\n"));
        String crlfSha = LegacyCheckout.sha256(b("<?php\r\nreturn 1;\r\n"));
        // LF 检出：转换后对上，返回 CRLF 字节
        assertThat(LegacyCheckout.read(lf, crlfSha)).isEqualTo(b("<?php\r\nreturn 1;\r\n"));
        // 原始字节对上（CRLF 检出或二进制文件）：原样返回
        assertThat(LegacyCheckout.read(lf, LegacyCheckout.sha256(b("<?php\nreturn 1;\n")))).isEqualTo(b("<?php\nreturn 1;\n"));
        // 内容变了：两份都对不上，返回原始字节，调用方的哈希断言照样失败
        Path changed = Files.write(dir.resolve("changed.php"), b("<?php\nreturn 2;\n"));
        assertThat(LegacyCheckout.sha256(LegacyCheckout.read(changed, crlfSha))).isNotEqualTo(crlfSha);
    }
}
