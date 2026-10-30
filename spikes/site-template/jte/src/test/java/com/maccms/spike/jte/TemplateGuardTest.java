package com.maccms.spike.jte;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 正式模板目录的静态护栏：禁止不转义输出、禁止把数据放进事件属性或脚本块、禁止引用原始富文本字段。
 * 默认转义不等于富文本过滤；vod_content 必须经白名单过滤后由专门组件输出（不在本 spike 范围）。
 */
class TemplateGuardTest {

    private static final Path TEMPLATES = Path.of("src/main/jte");

    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("\\$unsafe\\{"),                     // 不转义输出
            Pattern.compile("@raw"),                             // 原样输出块
            Pattern.compile("\\son[a-z]+=\"[^\"]*\\$\\{"),       // 事件属性中放数据
            Pattern.compile("<script[^>]*>[^<]*\\$\\{"),         // 脚本块中放数据
            Pattern.compile("\\.content\\(\\)"),                 // 富文本 content 字段
            Pattern.compile("vod_content"));

    @Test
    void templatesUseOnlyEscapedOutput() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(TEMPLATES)) {
            files = s.filter(p -> p.toString().endsWith(".jte")).toList();
        }
        assertThat(files).isNotEmpty();
        for (Path f : files) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            for (Pattern p : FORBIDDEN) {
                assertThat(p.matcher(text).find()).as(f + " 含禁止的写法 " + p).isFalse();
            }
        }
    }
}
