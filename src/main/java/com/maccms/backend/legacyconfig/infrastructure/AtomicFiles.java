package com.maccms.backend.legacyconfig.infrastructure;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 旧站文件的原子写回（B15，24 文档第 3.3 节）：同目录临时文件 → 刷盘 → 原子改名覆盖。改名之前在进程内的文件锁里再读一次目标，
 * 内容的 SHA-256 与调用方读到时的版本不同（另一位管理员刚写过，或仍在运行的旧 PHP 改过）就放弃、不写，避免后写的一方无声覆盖。
 * 旧 PHP 的 {@code fopen('wb') + fwrite} 是就地截断再写，读的一方可能读到半个文件；新版的读者只会看到旧文件或新文件。
 *
 * <p>临时文件名以 {@code .} 开头、以 {@code .tmp} 结尾，失败时删除。POSIX 文件系统上新文件沿用原文件的权限位（没有原文件时为
 * {@code rw-r--r--}），属主是本进程的用户——若旧 PHP 以另一个用户运行，部署方要保证它仍能读写（部署说明）。
 */
public final class AtomicFiles {

    /** 文件不存在时的版本。 */
    public static final String ABSENT = "absent";

    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private static final Set<PosixFilePermission> DEFAULT_PERMS = PosixFilePermissions.fromString("rw-r--r--");

    private AtomicFiles() {
    }

    /** 写回失败的原因。 */
    public enum Failure {
        CONFLICT, IO
    }

    /** 写回失败（信息不含路径与内容）。 */
    public static final class WriteException extends Exception {
        private final Failure failure;

        WriteException(Failure failure) {
            super(failure.name(), null, false, false);
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }

    /** 内容的版本：SHA-256 十六进制。 */
    public static String version(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 目标文件当前的版本；不存在时为 {@link #ABSENT}。 */
    public static String currentVersion(Path target) throws IOException {
        try {
            return version(Files.readAllBytes(target));
        } catch (NoSuchFileException e) {
            return ABSENT;
        }
    }

    /**
     * 在 {@code target} 的锁里执行 {@code action}（同一进程内对同一文件的读改写串行化）。
     */
    public static <T> T locked(Path target, Supplier<T> action) {
        ReentrantLock lock = LOCKS.computeIfAbsent(target.toAbsolutePath().normalize(), p -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 原子写回 {@code target}（父目录必须已存在）。
     *
     * @param expectedVersion 调用方读到的版本（{@link #version}，或 {@link #ABSENT}）；为 null 时不检查
     */
    public static void replace(Path target, byte[] content, String expectedVersion) throws WriteException {
        Path key = target.toAbsolutePath().normalize();
        ReentrantLock lock = LOCKS.computeIfAbsent(key, p -> new ReentrantLock());
        lock.lock();
        Path tmp = null;
        try {
            Set<PosixFilePermission> perms = DEFAULT_PERMS;
            if (expectedVersion != null && !expectedVersion.equals(currentVersion(target))) {
                throw new WriteException(Failure.CONFLICT);
            }
            PosixFileAttributeView posix = Files.getFileAttributeView(target, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (posix != null && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                perms = posix.readAttributes().permissions();
            }
            Path dir = target.getParent();
            tmp = Files.createTempFile(dir, "." + target.getFileName() + ".", ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                out.write(content);
            }
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            PosixFileAttributeView tmpPosix = Files.getFileAttributeView(tmp, PosixFileAttributeView.class);
            if (tmpPosix != null) {
                tmpPosix.setPermissions(perms);
            }
            move(tmp, target);
            tmp = null;
        } catch (IOException e) {
            throw new WriteException(Failure.IO);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 临时文件以 . 开头、.tmp 结尾，残留时可手工删除
                }
            }
            lock.unlock();
        }
    }

    /**
     * 原子改名。Windows 上目标文件正被别的线程或进程打开读取时，替换会短暂地因共享冲突失败（{@link AccessDeniedException}）：
     * 稍等后重试，最多约 2 秒；仍失败则按写入失败处理（原文件不变）。
     */
    private static void move(Path tmp, Path target) throws IOException, WriteException {
        for (int attempt = 0; ; attempt++) {
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                throw new WriteException(Failure.IO);
            } catch (AccessDeniedException e) {
                if (attempt >= 40 || Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw e;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
