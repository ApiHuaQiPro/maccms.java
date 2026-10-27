package com.maccms.backend.collect.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 本地目录里的 Provide 文件（B18，26 文档第 4.1 节；{@code maccms.collect.local.source-dir}，留空表示不使用）。只认目录下直接存放的普通文件：
 * 名字只能是一段（不含 {@code / \ :}、不是 {@code .}/{@code ..}、不以点开头、没有控制字符），扩展名为 {@code .json .xml .txt}；
 * 符号链接与子目录一律不认（{@code NOFOLLOW_LINKS}）；读取时先看大小，超过上限不读，读的过程中再按上限截止。
 */
public class DirectoryProvideSourceFiles {

    /** 目录里的文件。 */
    public record SourceFile(String name, long size, Instant modified) {
    }

    /** 文件不存在或不可用。 */
    public static final class NotFoundException extends Exception {
        public NotFoundException() {
            super("not found", null, false, false);
        }
    }

    /** 文件超过上限。 */
    public static final class TooLargeException extends Exception {
        public TooLargeException() {
            super("too large", null, false, false);
        }
    }

    private static final Pattern NAME = Pattern.compile("[^/\\\\:\\p{Cntrl}]{1,255}");

    private final Path dir;

    /** {@code dir} 为 null 表示未配置。 */
    public DirectoryProvideSourceFiles(Path dir) {
        this.dir = dir == null ? null : dir.toAbsolutePath().normalize();
    }

    /** 目录是否已配置。 */
    public boolean configured() {
        return dir != null;
    }

    /** 目录下扩展名为 {@code .json}、{@code .xml}、{@code .txt} 的普通文件，按名称排序，最多 {@code limit} 个。 */
    public List<SourceFile> list(int limit) {
        List<SourceFile> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (!acceptable(name)) {
                    continue;
                }
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (a.isRegularFile()) {
                    out.add(new SourceFile(name, a.size(), a.lastModifiedTime().toInstant()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.comparing(SourceFile::name));
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : out;
    }

    /** 读出一个文件：名字不合法、不存在、不是普通文件或是符号链接时 {@link NotFoundException}，超过 {@code maxBytes} 时 {@link TooLargeException}。 */
    public byte[] read(String name, long maxBytes) throws NotFoundException, TooLargeException {
        if (dir == null || name == null || !acceptable(name)) {
            throw new NotFoundException();
        }
        Path p = dir.resolve(name).normalize();
        if (!dir.equals(p.getParent())) {
            throw new NotFoundException();
        }
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!a.isRegularFile()) {
                throw new NotFoundException();
            }
            if (a.size() > maxBytes) {
                throw new TooLargeException();
            }
            try (InputStream in = Files.newInputStream(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                byte[] b = in.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE - 8));
                if (b.length > maxBytes) {
                    throw new TooLargeException();
                }
                return b;
            }
        } catch (NoSuchFileException e) {
            throw new NotFoundException();
        } catch (IOException e) {
            throw new NotFoundException();
        }
    }

    static boolean acceptable(String name) {
        if (!NAME.matcher(name).matches() || name.startsWith(".")) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".txt");
    }
}
