package com.maccms.backend.legacyconfig.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.legacyconfig.infrastructure.LegacyConfigDirectory.AccessException;
import com.maccms.backend.legacyconfig.infrastructure.LegacyConfigDirectory.FileState;
import com.maccms.backend.legacyconfig.infrastructure.LegacyConfigDirectory.Problem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 路径穿越、目录外符号链接、非普通文件与超大文件的拒绝（只在临时目录中操作）。 */
class LegacyConfigDirectoryTest {

    @TempDir
    Path tmp;

    private Path root;
    private LegacyConfigDirectory dir;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(tmp.resolve("application"));
        Files.createDirectories(root.resolve("extra"));
        Files.writeString(root.resolve("extra").resolve("maccms.php"), "<?php return [];", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("route.php"), "<?php return [];", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("outside.php"), "<?php return ['test-only-not-a-secret'];", StandardCharsets.UTF_8);
        dir = new LegacyConfigDirectory(root);
    }

    private Problem problem(String relative) {
        try {
            dir.stat(relative);
        } catch (AccessException e) {
            assertThat(e.getMessage()).doesNotContain(tmp.toString());
            return e.problem();
        }
        return null;
    }

    @Test
    void readsFilesInsideTheDirectory() throws Exception {
        FileState s = dir.stat("extra/maccms.php");
        assertThat(new String(dir.read(s, 1024), StandardCharsets.UTF_8)).isEqualTo("<?php return [];");
        assertThat(dir.stat("route.php").size()).isEqualTo(16);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside.php", "extra/../../outside.php", "./route.php", "extra//maccms.php",
            "/etc/passwd.php", "C:/Windows/win.php", "C:outside.php", "extra\\maccms.php", "..\\outside.php",
            "Extra/maccms.php", "extra/maccms.php ", "extra/maccms.php\u0000", "extra/maccms", "route.php.bak",
            "extra/.php", "", "%2e%2e/outside.php"})
    void rejectsNamesThatCouldEscapeOrAreNotPlainRelativePhpNames(String relative) {
        assertThat(problem(relative)).as(relative).isEqualTo(Problem.INVALID_NAME);
    }

    @Test
    void rejectsNullAndOverlongNames() {
        assertThat(problem(null)).isEqualTo(Problem.INVALID_NAME);
        assertThat(problem("a/".repeat(64) + "x.php")).isEqualTo(Problem.INVALID_NAME);
    }

    @Test
    void missingFilesAndDirectories() throws IOException {
        assertThat(problem("extra/nothere.php")).isEqualTo(Problem.NOT_FOUND);
        Files.createDirectories(root.resolve("dir.php"));
        assertThat(problem("dir.php")).isEqualTo(Problem.NOT_REGULAR_FILE);
    }

    @Test
    void symlinkPointingOutsideIsRejected() throws IOException {
        Path link = root.resolve("extra").resolve("link.php");
        try {
            Files.createSymbolicLink(link, tmp.resolve("outside.php"));
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.abort("当前环境不能创建符号链接（Windows 需开发者模式或管理员权限）");
        }
        assertThat(problem("extra/link.php")).isEqualTo(Problem.OUTSIDE_ROOT);
    }

    @Test
    void symlinkedDirectoryPointingOutsideIsRejected() throws IOException {
        Path outsideDir = Files.createDirectories(tmp.resolve("elsewhere"));
        Files.writeString(outsideDir.resolve("x.php"), "<?php return [];", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(root.resolve("linked"), outsideDir);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.abort("当前环境不能创建符号链接（Windows 需开发者模式或管理员权限）");
        }
        assertThat(problem("linked/x.php")).isEqualTo(Problem.OUTSIDE_ROOT);
    }

    @Test
    void oversizedFilesAreRejectedBeforeAndDuringRead() throws Exception {
        Files.write(root.resolve("big.php"), new byte[2048]);
        FileState s = dir.stat("big.php");
        assertThatThrownBy(() -> dir.read(s, 1024)).isInstanceOf(AccessException.class)
                .extracting(e -> ((AccessException) e).problem()).isEqualTo(Problem.TOO_LARGE);
        // stat 之后文件变大：读取时按实际字节数再拦一次
        Files.write(root.resolve("grow.php"), new byte[10]);
        FileState small = dir.stat("grow.php");
        Files.write(root.resolve("grow.php"), new byte[2048]);
        assertThatThrownBy(() -> dir.read(small, 1024)).isInstanceOf(AccessException.class)
                .extracting(e -> ((AccessException) e).problem()).isEqualTo(Problem.TOO_LARGE);
    }

    @Test
    void rootMustBeAnExistingDirectory() {
        assertThatThrownBy(() -> new LegacyConfigDirectory(tmp.resolve("missing")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(tmp.toString());
        assertThatThrownBy(() -> new LegacyConfigDirectory(tmp.resolve("outside.php")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
