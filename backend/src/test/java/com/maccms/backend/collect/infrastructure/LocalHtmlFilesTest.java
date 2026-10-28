package com.maccms.backend.collect.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 本地 HTML 目录的网址映射与读取限制（B20，28 文档第 4 节）。 */
class LocalHtmlFilesTest {

    @TempDir
    Path tmp;

    private static Optional<String> rel(String url) {
        return LocalHtmlFiles.relativePath(url.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void urlsMapToMirrorLayout() {
        assertThat(rel("http://CJ.Example.test/list/1.html")).contains("cj.example.test/list/1.html");
        assertThat(rel("https://u:p@h.test:443/a/")).contains("h.test/a/index.html");
        assertThat(rel("http://h.test")).contains("h.test/index.html");
        assertThat(rel("http://h.test/")).contains("h.test/index.html");
        assertThat(rel("http://h.test:8080//a//b.html#x")).contains("h.test_8080/a/b.html");
        assertThat(rel("http://h.test/show.php?id=9&amp;p=1")).contains("h.test/show.php@id=9&amp;p=1");
        assertThat(rel("http://h.test/q?a=/b:c")).contains("h.test/q@a=%2Fb%3Ac");
        assertThat(rel("http://h.test/%E4%B8%AD%E6%96%87.html")).contains("h.test/中文.html");
        assertThat(rel("http://h.test/中文.html")).contains("h.test/中文.html");
        assertThat(rel("http://h.test/dir/./x.html")).isEmpty();
    }

    @Test
    void unsafeUrlsDoNotMap() {
        for (String u : new String[] {"ftp://h.test/a", "file:///etc/passwd", "http://../a", "http://h.test/../x", "http://h.test/a/%2e%2e/x",
                "http://h.test/a%2Fb", "http://h.test/a%5Cb", "http://h.test/a:b", "http://h.test/con", "http://h.test/NUL.txt",
                "http://h.test/a%00b", "http://h_x.test/", "http://h.test:99999999/", "http://h.test/x.", "http://h.test/%FF", "/rel/x",
                "http://h.test/a\\b", "http://h.test/x%20"}) {
            assertThat(rel(u)).as(u).isEmpty();
        }
        assertThat(LocalHtmlFiles.relativePath(new byte[] {'h', 't', 't', 'p', ':', '/', '/', 'h', '/', (byte) 0xff})).isEmpty();
    }

    @Test
    void readsOnlyRegularFilesInsideTheDirectory() throws IOException {
        Path root = Files.createDirectories(tmp.resolve("html"));
        Files.createDirectories(root.resolve("h.test/a"));
        Files.writeString(root.resolve("h.test/a/1.html"), "<p>一</p>", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("outside.html"), "secret", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("h.test/dir.html"));
        Files.write(root.resolve("h.test/big.html"), new byte[101]);
        LocalHtmlFiles f = new LocalHtmlFiles(root, 100);
        assertThat(f.configured()).isTrue();
        assertThat(f.read("http://h.test/a/1.html".getBytes(StandardCharsets.UTF_8))).hasValueSatisfying(
                b -> assertThat(new String(b, StandardCharsets.UTF_8)).isEqualTo("<p>一</p>"));
        assertThat(f.read("http://h.test/a/2.html".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(f.read("http://h.test/dir.html".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(f.read("http://h.test/big.html".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(new LocalHtmlFiles(null, 100).read("http://h.test/a/1.html".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(new LocalHtmlFiles(null, 100).configured()).isFalse();
    }

    @Test
    void symbolicLinksAreNotFollowed() throws IOException {
        Path root = Files.createDirectories(tmp.resolve("html"));
        Files.createDirectories(root.resolve("h.test"));
        Path target = Files.writeString(tmp.resolve("outside.html"), "secret", StandardCharsets.UTF_8);
        Path outDir = Files.createDirectories(tmp.resolve("outdir"));
        Files.writeString(outDir.resolve("x.html"), "secret", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(root.resolve("h.test/link.html"), target);
            Files.createSymbolicLink(root.resolve("h.test/d"), outDir);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("本机不能创建符号链接：" + e.getClass().getSimpleName());
        }
        LocalHtmlFiles f = new LocalHtmlFiles(root, 1000);
        assertThat(f.read("http://h.test/link.html".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(f.read("http://h.test/d/x.html".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }
}
