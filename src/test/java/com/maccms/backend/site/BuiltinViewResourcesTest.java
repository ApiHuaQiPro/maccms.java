package com.maccms.backend.site;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** APPK-2 C-39：随 jar 的 3 个 PHP 内置视图必须与 {@code php/application/index/view/} 的原文件逐字节相同。 */
class BuiltinViewResourcesTest {

    private static final Path PHP_VIEWS = Path.of("php", "application", "index", "view");
    private static final Path COPIES = Path.of("src", "main", "resources", "phptemplate", "builtin", "index", "view");
    private static final List<String> VIEWS = List.of("index/index.html", "public/jump.html", "public/msg.html");

    @Test
    void builtinViewsAreByteIdenticalToPhpOriginals() throws IOException {
        for (String view : VIEWS) {
            byte[] original = Files.readAllBytes(PHP_VIEWS.resolve(view));
            byte[] copy = Files.readAllBytes(COPIES.resolve(view));
            assertThat(original).as("php 原文件 %s 不应为空", view).isNotEmpty();
            assertThat(copy).as("内置视图 %s 与 php/ 原文件逐字节相同", view).isEqualTo(original);
        }
    }

    @Test
    void builtinViewsHaveNoCarriageReturnSoAutocrlfCannotChangeBytes() throws IOException {
        for (String view : VIEWS) {
            assertThat(Files.readAllBytes(COPIES.resolve(view)))
                    .as("内置视图 %s 不含 CR（.gitattributes -text 固定为 blob 字节）", view)
                    .doesNotContain((byte) '\r');
        }
    }

    @Test
    void exactlyThreeBuiltinViewsAreShipped() throws IOException {
        try (var files = Files.walk(COPIES)) {
            assertThat(files.filter(Files::isRegularFile).map(p -> COPIES.relativize(p).toString().replace('\\', '/')))
                    .containsExactlyInAnyOrderElementsOf(VIEWS);
        }
    }
}
