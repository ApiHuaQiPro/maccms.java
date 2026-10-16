package com.maccms.backend.legacyconfig.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.regex.Pattern;

/**
 * 旧配置目录（旧站的 {@code application/} 目录）中文件的只读访问：只按相对名读取目录内的普通文件，不写、不删、不列目录。
 *
 * <p>路径规则（任何一条不满足都拒绝，见 {@link Problem}）：
 * <ol>
 * <li>相对名只能由小写字母、数字、下划线组成的段与 {@code /} 构成，以 {@code .php} 结尾（如 {@code extra/maccms.php}）：
 * 拒绝 {@code ..}、{@code .}、绝对路径、盘符、反斜线、空段。</li>
 * <li>解析符号链接后的真实路径必须仍在配置目录的真实路径之内（防止链接指向目录外）。</li>
 * <li>必须是普通文件；读取前检查大小，读取时最多读上限 + 1 字节（读取期间文件变大同样拒绝）。</li>
 * </ol>
 */
public final class LegacyConfigDirectory {

    /** 拒绝原因。 */
    public enum Problem {
        INVALID_NAME, OUTSIDE_ROOT, NOT_FOUND, NOT_REGULAR_FILE, TOO_LARGE, IO
    }

    /** 读取失败，信息不含路径与内容。 */
    public static final class AccessException extends Exception {
        private final Problem problem;

        AccessException(Problem problem) {
            super(problem.name(), null, false, false);
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    /** 文件状态：真实路径、修改时间、大小。 */
    public record FileState(Path realPath, FileTime modified, long size) {
    }

    private static final Pattern RELATIVE_NAME = Pattern.compile("[a-z0-9_]+(?:/[a-z0-9_]+)*\\.php");

    private final Path root;

    /**
     * @param root 已存在的目录；保存其真实路径（解析符号链接）
     * @throws IllegalArgumentException 不存在或不是目录（信息不含路径）
     */
    public LegacyConfigDirectory(Path root) {
        try {
            Path real = root.toRealPath();
            if (!Files.isDirectory(real)) {
                throw new IllegalArgumentException("不是目录");
            }
            this.root = real;
        } catch (IOException e) {
            throw new IllegalArgumentException("目录不存在或无法访问");
        }
    }

    /** 解析并检查 {@code relative}，返回文件当前的状态。 */
    public FileState stat(String relative) throws AccessException {
        if (relative == null || relative.length() > 128 || !RELATIVE_NAME.matcher(relative).matches()) {
            throw new AccessException(Problem.INVALID_NAME);
        }
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw new AccessException(Problem.OUTSIDE_ROOT);
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (NoSuchFileException e) {
            throw new AccessException(Problem.NOT_FOUND);
        } catch (IOException e) {
            throw new AccessException(Problem.IO);
        }
        if (!real.startsWith(root)) {
            throw new AccessException(Problem.OUTSIDE_ROOT);
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile()) {
                throw new AccessException(Problem.NOT_REGULAR_FILE);
            }
            return new FileState(real, attrs.lastModifiedTime(), attrs.size());
        } catch (IOException e) {
            throw new AccessException(Problem.IO);
        }
    }

    /** 读取 {@link #stat} 得到的文件，超过 {@code maxBytes} 拒绝。 */
    public byte[] read(FileState state, int maxBytes) throws AccessException {
        if (state.size() > maxBytes) {
            throw new AccessException(Problem.TOO_LARGE);
        }
        try (InputStream in = Files.newInputStream(state.realPath(), LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new AccessException(Problem.TOO_LARGE);
            }
            return bytes;
        } catch (IOException e) {
            throw new AccessException(Problem.IO);
        }
    }
}
