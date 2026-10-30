package com.maccms.backend.legacyconfig.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 旧站根目录（旧 {@code ROOT_PATH}，即 {@code application/} 的上一级）中文件的受限访问（B15，24 文档第 3.4 节）：后台的播放器代码文件、
 * 统计代码、模板、插件、数据库备份都在这里。只接受以 {@code /} 分隔的相对路径（不能为空、不能有空段、{@code .}、{@code ..}、
 * 反斜线、NUL、盘符），解析符号链接后的真实路径必须仍在根目录内；不跟随符号链接删除或复制目录。哪些区域可以访问由
 * {@code LegacySiteFiles} 按用途限定，本类只管路径安全与原子写回。
 */
public final class LegacySiteRoot {

    /** 访问失败（信息不含路径）。 */
    public static final class AccessException extends Exception {
        private final String problem;

        public AccessException(String problem) {
            super(problem, null, false, false);
            this.problem = problem;
        }

        public String problem() {
            return problem;
        }
    }

    /** 目录项。 */
    public record Entry(String name, boolean directory, long size, long modifiedEpochSeconds) {
    }

    private final Path root;

    /** @param root 已存在的目录（取其真实路径） */
    public LegacySiteRoot(Path root) {
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

    /** 相对路径是否合法（只看写法）。 */
    public static boolean validRelative(String relative) {
        if (relative == null || relative.isEmpty() || relative.length() > 1024 || relative.startsWith("/")
                || relative.endsWith("/") || relative.indexOf('\\') >= 0 || relative.indexOf('\0') >= 0
                || relative.indexOf(':') >= 0) {
            return false;
        }
        for (String seg : relative.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
                return false;
            }
            for (int i = 0; i < seg.length(); i++) {
                if (seg.charAt(i) < 0x20) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 解析出绝对路径：已存在时为真实路径（必须在根目录内），不存在时为"最近的已存在祖先的真实路径 + 其余段"（祖先必须在根目录内）。
     */
    public Path resolve(String relative) throws AccessException {
        if (!validRelative(relative)) {
            throw new AccessException("INVALID_NAME");
        }
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw new AccessException("OUTSIDE_ROOT");
        }
        Path existing = candidate;
        List<String> rest = new ArrayList<>();
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(existing)) {
            rest.addFirst(existing.getFileName().toString());
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new AccessException("OUTSIDE_ROOT");
        }
        Path real;
        try {
            real = existing.toRealPath();
        } catch (IOException e) {
            throw new AccessException("IO");
        }
        if (!real.startsWith(root)) {
            throw new AccessException("OUTSIDE_ROOT");
        }
        for (String r : rest) {
            real = real.resolve(r);
        }
        return real;
    }

    public boolean isFile(String relative) throws AccessException {
        return Files.isRegularFile(resolve(relative), LinkOption.NOFOLLOW_LINKS);
    }

    public boolean isDirectory(String relative) throws AccessException {
        return Files.isDirectory(resolve(relative), LinkOption.NOFOLLOW_LINKS);
    }

    /** 读取普通文件，超过 {@code maxBytes} 拒绝；不存在时为 null。 */
    public byte[] read(String relative, int maxBytes) throws AccessException {
        Path p = resolve(relative);
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
            throw new AccessException("NOT_REGULAR_FILE");
        }
        try (InputStream in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new AccessException("TOO_LARGE");
            }
            return bytes;
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new AccessException("IO");
        }
    }

    /** 文件或目录的状态；不存在时为 null。 */
    public Entry stat(String relative) throws AccessException {
        Path p = resolve(relative);
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return new Entry(p.getFileName().toString(), a.isDirectory(), a.size(), a.lastModifiedTime().toMillis() / 1000);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new AccessException("IO");
        }
    }

    /** 列出目录（不递归，按名称的 UTF-16 顺序，与 PHP {@code glob} 的字节序在 ASCII 名称上相同）；不存在或不是目录时为空。 */
    public List<Entry> list(String relative) throws AccessException {
        Path dir = resolve(relative);
        List<Entry> out = new ArrayList<>();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                out.add(new Entry(p.getFileName().toString(), a.isDirectory(), a.size(),
                        a.lastModifiedTime().toMillis() / 1000));
            }
        } catch (IOException e) {
            throw new AccessException("IO");
        }
        out.sort(Comparator.comparing(Entry::name, LegacySiteRoot::compareBytes));
        return out;
    }

    /** 按 UTF-8 字节比较（PHP {@code glob}/{@code scandir} 的排序）。 */
    static int compareBytes(String a, String b) {
        byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.util.Arrays.compareUnsigned(x, y);
    }

    /** 当前内容的版本（{@link AtomicFiles#version}；不存在时 {@link AtomicFiles#ABSENT}）。 */
    public String version(String relative) throws AccessException {
        try {
            return AtomicFiles.currentVersion(resolve(relative));
        } catch (IOException e) {
            throw new AccessException("IO");
        }
    }

    /** 原子写回（父目录不存在时先创建）。 */
    public void replace(String relative, byte[] content, String expectedVersion) throws AccessException, AtomicFiles.WriteException {
        Path p = resolve(relative);
        try {
            Files.createDirectories(p.getParent());
        } catch (IOException e) {
            throw new AccessException("IO");
        }
        AtomicFiles.replace(p, content, expectedVersion);
    }

    /** 创建目录（含上级）。 */
    public void mkdirs(String relative) throws AccessException {
        try {
            Files.createDirectories(resolve(relative));
        } catch (IOException e) {
            throw new AccessException("IO");
        }
    }

    /** 删除普通文件；不存在时什么都不做。 */
    public boolean delete(String relative) throws AccessException {
        Path p = resolve(relative);
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
            throw new AccessException("IS_DIRECTORY");
        }
        try {
            return Files.deleteIfExists(p);
        } catch (IOException e) {
            throw new AccessException("IO");
        }
    }

    /** 删除空目录；不是空目录或不存在时返回 false。 */
    public boolean deleteEmptyDirectory(String relative) throws AccessException {
        Path p = resolve(relative);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
            if (ds.iterator().hasNext()) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        try {
            return Files.deleteIfExists(p);
        } catch (IOException e) {
            return false;
        }
    }

    /** 递归删除目录（不跟随符号链接：链接本身被删除，链接指向的内容不动）。 */
    public void deleteTree(String relative) throws AccessException {
        Path dir = resolve(relative);
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new AccessException("IO");
        }
    }

    /** 递归列出目录下的普通文件（相对 {@code relative} 的路径，{@code /} 分隔，按字节序）。 */
    public List<String> walkFiles(String relative) throws AccessException {
        Path dir = resolve(relative);
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return out;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        out.add(dir.relativize(file).toString().replace('\\', '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new AccessException("IO");
        }
        out.sort(LegacySiteRoot::compareBytes);
        return out;
    }

    /** 复制普通文件（覆盖；目标父目录自动创建）。 */
    public void copyFile(String from, String to) throws AccessException {
        Path src = resolve(from);
        Path dst = resolve(to);
        if (!Files.isRegularFile(src, LinkOption.NOFOLLOW_LINKS)) {
            throw new AccessException("NOT_REGULAR_FILE");
        }
        try {
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new AccessException("IO");
        }
    }

    /** 根目录的真实路径（只给本模块内的写回与测试用）。 */
    public Path root() {
        return root;
    }
}
