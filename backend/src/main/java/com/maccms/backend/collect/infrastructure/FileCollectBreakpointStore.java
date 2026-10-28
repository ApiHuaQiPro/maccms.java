package com.maccms.backend.collect.infrastructure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 采集入库的断点（B19 增量 3，27 文档第 12 节）：旧版 {@code Cache::set(cache_flag . '_collect_break_' . 种类, 地址)}，存在站点缓存里、没有有效期，
 * 入库每一页开始时写下这一页的地址，采集完成时删除。新版一个种类一个文件 {@code <缓存目录>/breakpoints/collect_break_<种类>.url}，
 * 先写临时文件再原子改名；进程重启后仍在。只处理这几个文件名，目录里的其他文件不碰。不写库、不写旧站目录。
 */
public class FileCollectBreakpointStore {

    private static final Pattern FLAG = Pattern.compile("[a-z]{1,16}");
    /** 断点地址的长度上限（字节；地址由后台按白名单参数拼出，正常不到 2KB）。 */
    static final int MAX_BYTES = 16 * 1024;

    private final Path dir;

    /** @param cacheDir 预览缓存目录（{@code maccms.collect.local.cache-dir}）；断点放在它下面的 {@code breakpoints} 子目录 */
    public FileCollectBreakpointStore(Path cacheDir) {
        this.dir = cacheDir.toAbsolutePath().normalize().resolve("breakpoints");
    }

    public Path dir() {
        return dir;
    }

    /** 读取断点；没有、不是普通文件或超过上限时为空。 */
    public synchronized Optional<String> get(String flag) {
        Path f = file(flag);
        try {
            BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!a.isRegularFile() || a.size() > MAX_BYTES) {
                return Optional.empty();
            }
            String v = Files.readString(f, StandardCharsets.UTF_8);
            return v.isEmpty() ? Optional.empty() : Optional.of(v);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 写下断点（覆盖）。 */
    public synchronized void put(String flag, String url) {
        Path target = file(flag);
        byte[] body = url.getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_BYTES) {
            throw new IllegalArgumentException("断点地址过长");
        }
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

    /** 删除断点；没有时为 false。 */
    public synchronized boolean remove(String flag) {
        try {
            return Files.deleteIfExists(file(flag));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path file(String flag) {
        if (flag == null || !FLAG.matcher(flag).matches()) {
            throw new IllegalArgumentException("断点标识不合法");
        }
        return dir.resolve("collect_break_" + flag + ".url");
    }
}
