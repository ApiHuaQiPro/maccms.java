package com.maccms.backend.install.infrastructure;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 新系统的部署配置文件（B22，docs/architecture/31-b22-install-wizard.md 第 5.3 节）：旧版第四步写 {@code application/database.php}
 * （Index.php:375-442），新版写这个 Java properties 文件，键为环境变量名（{@code MACCMS_DB_URL} 等）。正常启动时由
 * {@code application.yml} 的 {@code spring.config.import} 读入，环境变量仍然优先。
 *
 * <p>文件位置由部署参数 {@code MACCMS_DEPLOY_CONFIG} 指定（绝对路径，不在旧站目录里，不进 git）。写法：同目录临时文件 → 原子改名；
 * 支持 POSIX 权限的系统上权限为 600（只有运行账号可读，内含数据库密码）。值中的反斜杠、行结束符、非 ASCII 字符按 properties 规则转义。
 */
public class DeployConfigFile {

    /** 文件只写 ASCII（properties 按 ISO-8859-1 读），注释用英文。 */
    static final String HEADER = "# MacCMS backend deployment config, written by the B22 install wizard"
            + " (docs/architecture/31-b22-install-wizard.md section 5.3).\n"
            + "# Contains database passwords: keep it out of git and out of the web root. Environment variables win.\n";

    private final Path path;

    /** @param path 绝对路径（{@code InstallSettings} 已校验） */
    public DeployConfigFile(Path path) {
        this.path = path;
    }

    public Path path() {
        return path;
    }

    /** 可以写：已存在时文件可写，不存在时所在目录存在且可写（旧版 {@code is_writable(database.php)}，Index.php:112）。 */
    public boolean writable() {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.isWritable(path);
        }
        Path dir = path.getParent();
        return dir != null && Files.isDirectory(dir) && Files.isWritable(dir);
    }

    /** 整个文件替换为这些项（按给出的顺序）。 */
    public void write(Map<String, String> entries) throws IOException {
        StringBuilder sb = new StringBuilder(HEADER);
        entries.forEach((k, v) -> sb.append(k).append('=').append(escape(v)).append('\n'));
        byte[] bytes = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        Path dir = path.getParent();
        Path tmp = dir.resolve("." + path.getFileName() + ".tmp-" + Long.toHexString(System.nanoTime()));
        try {
            try {
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException e) {
                Files.createFile(tmp);
            }
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (FileAlreadyExistsException e) {
            throw new IOException("临时文件已存在");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** 读回全部项；文件不存在时为 null。 */
    public Map<String, String> read() throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Properties p = new Properties();
        p.load(new StringReader(new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1)));
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : p.stringPropertyNames()) {
            out.put(k, p.getProperty(k));
        }
        return out;
    }

    /** properties 值的转义（{@code Properties.store} 对值的规则）：只输出 ASCII。 */
    static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\f' -> sb.append("\\f");
                case ' ' -> sb.append(i == 0 ? "\\ " : " ");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
