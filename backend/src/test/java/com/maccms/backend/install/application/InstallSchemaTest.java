package com.maccms.backend.install.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 指纹文件的读写：Windows 下 {@code core.autocrlf=true} 检出（或打包）时资源会变成 CRLF，行尾的 {@code \r} 不能算进定义里；
 * 定义自身的回车写成 {@code \r} 转义，不受影响。
 */
class InstallSchemaTest {

    private static final Map<String, String> SAMPLE = Map.of(
            "table mac_a", "CREATE TABLE `mac_a` (\n  `id` int\r\n) ENGINE=MyISAM",
            "table mac_b", "a\tb\\c");

    @Test
    void parseReadsWhatFormatWrites() {
        assertThat(InstallSchema.parse(InstallSchema.format(SAMPLE))).isEqualTo(SAMPLE);
    }

    @Test
    void parseIgnoresCrlfLineEndings() {
        String crlf = InstallSchema.format(SAMPLE).replace("\n", "\r\n");
        assertThat(InstallSchema.parse(crlf)).isEqualTo(SAMPLE);
    }

    @Test
    void bundledResourceHas26Tables() {
        assertThat(InstallSchema.expected().keySet().stream().filter(k -> k.startsWith("table ")))
                .hasSize(InstallSchema.TABLES)
                .allSatisfy(k -> assertThat(InstallSchema.expected().get(k)).doesNotEndWith("\r"));
    }
}
