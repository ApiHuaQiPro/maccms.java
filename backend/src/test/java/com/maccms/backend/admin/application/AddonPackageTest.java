package com.maccms.backend.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** 插件包的解析与校验（B29，35 文档第 4 节）：路径穿越、zip 炸弹、坏包一律整包拒绝；不留临时文件。 */
class AddonPackageTest {

    static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(buf)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                if (e.getValue() != null) {
                    z.write(e.getValue());
                }
                z.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buf.toByteArray();
    }

    static byte[] zip(String... nameContent) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int i = 0; i < nameContent.length; i += 2) {
            m.put(nameContent[i], nameContent[i + 1] == null ? null : nameContent[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return zip(m);
    }

    private static AddonPackage parse(byte[] b) {
        return AddonPackage.parse(b, AddonPackage.Limits.DEFAULT);
    }

    @Test
    void readsFilesAndSkipsDirectoryEntries() {
        AddonPackage p = parse(zip("Demo.php", "<?php", "assets/", null, "assets/a.css", "a{}", "info.ini", "name = demo"));
        assertThat(p.files()).containsOnlyKeys("Demo.php", "assets/a.css", "info.ini");
        assertThat(new String(p.file("assets/a.css"), StandardCharsets.UTF_8)).isEqualTo("a{}");
    }

    @Test
    void notAZipKeepsTheLegacyMessage() {
        for (byte[] b : new byte[][] {"not found".getBytes(StandardCharsets.UTF_8), "PK\3\4garbage".getBytes(StandardCharsets.ISO_8859_1),
                new byte[0], null}) {
            assertThatThrownBy(() -> parse(b)).isInstanceOf(AddonPackage.Rejected.class).hasMessage(AddonPackage.UNABLE_TO_OPEN);
        }
    }

    @Test
    void emptyZipIsAnEmptyPackage() {
        assertThat(parse(zip()).files()).isEmpty();
    }

    @Test
    void traversalAndUnsafeNamesAreRejected() {
        String[] bad = {"../evil.php", "../../evil.txt", "a/../../evil", "/abs.php", "a\\..\\b", "C:/x", "c:x", "a//b", "./a", "a/./b",
            "a/..", "con", "aux.txt", "a/NUL", "com1.php", "lpt9", "dots.", "space ", "ctl\u0001", "del\u007f"};
        for (String name : bad) {
            assertThatThrownBy(() -> parse(zip("Demo.php", "<?php", name, "x"))).as(name)
                    .isInstanceOf(AddonPackage.Rejected.class).hasMessage(AddonPackage.UNSAFE_PATH);
        }
    }

    @Test
    void caseInsensitiveDuplicatesAndFileDirectoryClashesAreRejected() {
        assertThatThrownBy(() -> parse(zip("A.php", "1", "a.php", "2"))).hasMessage(AddonPackage.UNSAFE_PATH);
        assertThatThrownBy(() -> parse(zip("x", "file", "x/y", "nested"))).hasMessage(AddonPackage.UNSAFE_PATH);
        assertThatThrownBy(() -> parse(zip("x/y", "nested", "x", "file"))).hasMessage(AddonPackage.UNSAFE_PATH);
    }

    @Test
    void entryCountLimit() {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int i = 0; i < 11; i++) {
            m.put("f" + i, new byte[] {1});
        }
        assertThatThrownBy(() -> AddonPackage.parse(zip(m), new AddonPackage.Limits(10, 1 << 20, 1 << 20, 200)))
                .hasMessage(AddonPackage.TOO_LARGE);
        assertThat(AddonPackage.parse(zip(m), new AddonPackage.Limits(11, 1 << 20, 1 << 20, 200)).files()).hasSize(11);
    }

    @Test
    void zipBombByEntrySizeTotalAndRatio() {
        byte[] zeros = new byte[3 * 1024 * 1024];
        assertThatThrownBy(() -> AddonPackage.parse(zip(Map.of("big.bin", zeros)), new AddonPackage.Limits(10, 2L << 20, 64L << 20, 100000)))
                .hasMessage(AddonPackage.TOO_LARGE);
        Map<String, byte[]> two = new LinkedHashMap<>();
        two.put("a.bin", new byte[2 << 20]);
        two.put("b.bin", new byte[2 << 20]);
        assertThatThrownBy(() -> AddonPackage.parse(zip(two), new AddonPackage.Limits(10, 3L << 20, 3L << 20, 100000)))
                .hasMessage(AddonPackage.TOO_LARGE);
        // 默认压缩比上限 200：3 MiB 的 0 压缩后只有几 KiB
        assertThatThrownBy(() -> parse(zip(Map.of("zero.bin", zeros)))).hasMessage(AddonPackage.TOO_LARGE);
    }

    @Test
    void declaredSizesAreNotTrusted() throws IOException {
        // STORED 条目在本地头与中央目录里声明 1 字节，实际数据更长：按实际读出的字节判断，读不出就是坏包
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(buf)) {
            ZipEntry e = new ZipEntry("x.bin");
            e.setMethod(ZipEntry.STORED);
            byte[] data = new byte[] {7};
            CRC32 crc = new CRC32();
            crc.update(data);
            e.setSize(1);
            e.setCompressedSize(1);
            e.setCrc(crc.getValue());
            z.putNextEntry(e);
            z.write(data);
            z.closeEntry();
        }
        assertThat(parse(buf.toByteArray()).file("x.bin")).containsExactly(7);
    }

    @Test
    void noTemporaryFilesAreLeftBehind() throws IOException {
        long before = leftovers();
        parse(zip("Demo.php", "<?php"));
        assertThatThrownBy(() -> parse(zip("../x", "y"))).isInstanceOf(AddonPackage.Rejected.class);
        assertThatThrownBy(() -> parse("PK\3\4garbage".getBytes(StandardCharsets.ISO_8859_1))).isInstanceOf(AddonPackage.Rejected.class);
        assertThat(leftovers()).isEqualTo(before);
    }

    private static long leftovers() throws IOException {
        try (Stream<Path> s = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return s.filter(p -> p.getFileName().toString().startsWith("maccms-addon-")).count();
        }
    }
}
