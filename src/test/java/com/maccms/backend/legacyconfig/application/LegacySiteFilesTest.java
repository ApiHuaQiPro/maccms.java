package com.maccms.backend.legacyconfig.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * APPK-2 S3-20 C-69：{@link LegacySiteFiles#readInstallSql} 只读固定路径 {@code application/install/sql/install.sql}，返回原始字节
 * （不做换行转换）；文件不存在为 null；超过上限、链接到站点根之外、未配置站点根时拒绝。
 */
class LegacySiteFilesTest {

    @TempDir
    Path dir;

    private Path site;
    private Path sql;

    private LegacySiteFiles files() throws IOException {
        site = Files.createDirectories(dir.resolve("site"));
        Path app = Files.createDirectories(site.resolve("application"));
        sql = site.resolve(LegacySiteFiles.INSTALL_SQL);
        return new LegacySiteFiles(new LegacyConfigProperties(app.toString()));
    }

    @Test
    void readsRawBytesWithoutNewlineConversion() throws Exception {
        LegacySiteFiles files = files();
        Files.createDirectories(sql.getParent());
        byte[] lf = "DROP TABLE IF EXISTS `mac_a`;\nCREATE TABLE `mac_a` (id int);\n".getBytes(StandardCharsets.UTF_8);
        Files.write(sql, lf);
        assertThat(files.readInstallSql(1024)).isEqualTo(lf);
        byte[] crlf = "a;\r\nb;\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(sql, crlf);
        assertThat(files.readInstallSql(1024)).isEqualTo(crlf);
        // 恰好等于上限仍可读
        assertThat(files.readInstallSql(crlf.length)).isEqualTo(crlf);
    }

    @Test
    void missingFileIsNull() throws Exception {
        LegacySiteFiles files = files();
        assertThat(files.readInstallSql(1024)).isNull();
        Files.createDirectories(sql.getParent());
        assertThat(files.readInstallSql(1024)).isNull();
    }

    @Test
    void largerThanLimitIsRejected() throws Exception {
        LegacySiteFiles files = files();
        Files.createDirectories(sql.getParent());
        Files.write(sql, new byte[11]);
        assertThatThrownBy(() -> files.readInstallSql(10)).isInstanceOf(LegacySiteFiles.FileException.class)
                .satisfies(e -> assertThat(((LegacySiteFiles.FileException) e).reason())
                        .isEqualTo(LegacySiteFiles.Reason.TOO_LARGE));
    }

    @Test
    void directoryInPlaceOfTheFileIsRejected() throws Exception {
        LegacySiteFiles files = files();
        Files.createDirectories(sql);
        assertThatThrownBy(() -> files.readInstallSql(1024)).isInstanceOf(LegacySiteFiles.FileException.class)
                .satisfies(e -> assertThat(((LegacySiteFiles.FileException) e).reason())
                        .isEqualTo(LegacySiteFiles.Reason.NOT_REGULAR_FILE));
    }

    /** 指向站点根之外的符号链接：解析出的真实路径不在站点根内，拒绝（不读链接目标）。 */
    @Test
    void symlinkOutsideTheSiteIsRejected() throws Exception {
        LegacySiteFiles files = files();
        Files.createDirectories(sql.getParent());
        Path outside = Files.writeString(dir.resolve("outside.sql"), "DROP TABLE x;\n");
        try {
            Files.createSymbolicLink(sql, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.abort("本机不允许创建符号链接：" + e.getClass().getSimpleName());
        }
        assertThatThrownBy(() -> files.readInstallSql(1024)).isInstanceOf(LegacySiteFiles.FileException.class)
                .satisfies(e -> assertThat(((LegacySiteFiles.FileException) e).reason())
                        .isEqualTo(LegacySiteFiles.Reason.OUTSIDE_ROOT));
        // 整个 install 目录链接到站点根之外同样拒绝
        Files.delete(sql);
        Path sqlDir = sql.getParent();
        Files.delete(sqlDir);
        Path outsideDir = Files.createDirectories(dir.resolve("outside-dir"));
        Files.writeString(outsideDir.resolve("install.sql"), "DROP TABLE x;\n");
        Files.createSymbolicLink(sqlDir, outsideDir);
        assertThatThrownBy(() -> files.readInstallSql(1024)).isInstanceOf(LegacySiteFiles.FileException.class)
                .satisfies(e -> assertThat(((LegacySiteFiles.FileException) e).reason())
                        .isEqualTo(LegacySiteFiles.Reason.OUTSIDE_ROOT));
    }

    @Test
    void notConfiguredIsRejected() {
        LegacySiteFiles files = new LegacySiteFiles(new LegacyConfigProperties(""));
        assertThatThrownBy(() -> files.readInstallSql(1024)).isInstanceOf(LegacySiteFiles.FileException.class)
                .satisfies(e -> assertThat(((LegacySiteFiles.FileException) e).reason())
                        .isEqualTo(LegacySiteFiles.Reason.NOT_CONFIGURED));
        assertThatThrownBy(() -> new LegacySiteFiles(null).readInstallSql(1024))
                .isInstanceOf(LegacySiteFiles.FileException.class);
    }
}
