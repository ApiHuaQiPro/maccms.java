package com.maccms.backend.collect.infrastructure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 预览缓存（B18，26 文档第 4.2 节）：只写本机缓存目录，不写库、不写旧站目录。每项一个 {@code <id>.json}，放在 {@code maccms.collect.local.cache-dir}（默认系统临时目录下的
 * {@code maccms-collect-preview}）。写入先写同目录的临时文件再原子改名；只处理名字符合 {@code [0-9a-f-]+.json} 的普通文件，不跟随符号链接，
 * 目录里的其他文件不碰。
 */
public class FileProvidePreviewStore {

    /** 缓存项。 */
    public record Entry(byte[] body, Instant writtenAt) {
    }

    private static final Pattern ID = Pattern.compile("[0-9a-f-]{1,128}");
    private static final String SUFFIX = ".json";

    private final Path dir;

    public FileProvidePreviewStore(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    public Path dir() {
        return dir;
    }

    /** 写入（原子替换同名项）。{@code id} 只含 {@code [0-9a-f-]}。 */
    public synchronized void put(String id, byte[] body) {
        Path target = file(id);
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, ".tmp-", ".part");
            try {
                Files.write(tmp, body);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 读取；没有时为空。 */
    public synchronized Optional<Entry> get(String id) {
        Path f = file(id);
        try {
            BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!a.isRegularFile()) {
                return Optional.empty();
            }
            return Optional.of(new Entry(Files.readAllBytes(f), a.lastModifiedTime().toInstant()));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 删除一项；不存在时为 false。 */
    public synchronized boolean delete(String id) {
        try {
            return Files.deleteIfExists(file(id));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 删除编号以 {@code prefix} 开头的全部项，返回删除的个数。 */
    public synchronized int deletePrefix(String prefix) {
        int n = 0;
        for (Item it : items()) {
            if (it.id().startsWith(prefix) && delete(it.id())) {
                n++;
            }
        }
        return n;
    }

    /** 删除写入时间早于 {@code before} 的项；项数超过 {@code maxEntries} 时再按写入时间从旧到新删除。返回删除的个数。 */
    public synchronized int purge(Instant before, int maxEntries) {
        List<Item> all = new ArrayList<>(items());
        all.sort(Comparator.comparing(Item::written));
        int n = 0;
        int remaining = all.size();
        for (Item it : all) {
            if (it.written().isBefore(before) || remaining > maxEntries) {
                if (delete(it.id())) {
                    n++;
                }
                remaining--;
            }
        }
        return n;
    }

    private record Item(String id, Instant written) {
    }

    private List<Item> items() {
        List<Item> out = new ArrayList<>();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                String id = name.substring(0, name.length() - SUFFIX.length());
                if (!ID.matcher(id).matches()) {
                    continue;
                }
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (a.isRegularFile()) {
                    out.add(new Item(id, a.lastModifiedTime().toInstant()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private Path file(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("缓存编号不合法");
        }
        return dir.resolve(id + SUFFIX);
    }
}
