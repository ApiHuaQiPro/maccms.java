package com.maccms.backend.content.infrastructure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 旧站 {@code upload/} 目录（B13，22 文档第 7.10 节）：根目录来自部署参数 {@code maccms.upload.dir}（旧站根目录下 upload/ 的本地路径，与
 * B09 的会员头像目录是同一个目录），为空时不可用（删除内容时不删文件，上传返回失败）。
 *
 * <p>库里的地址是相对站点根的 {@code upload/…}；只处理落在根目录之内的路径（规范化后仍在根目录下），旧版按 {@code "./upload"} 前缀判断，
 * {@code upload/../} 一类地址会删到目录之外（22 文档 D-B13-8）。
 */
public final class UploadStore {

    private final Path root;

    /** @param uploadDir 旧站 {@code upload/} 的本地路径；null 或空表示未配置 */
    public UploadStore(String uploadDir) {
        this.root = uploadDir == null || uploadDir.isBlank() ? null : Path.of(uploadDir).toAbsolutePath().normalize();
    }

    public boolean configured() {
        return root != null;
    }

    public Path root() {
        return root;
    }

    /**
     * 库里的地址（相对站点根，如 {@code upload/vod/20260101-1/a.jpg}）对应的文件；不以 {@code upload/} 开头、规范化后不在根目录内或未配置时为 null。
     */
    public Path resolve(String siteRelative) {
        if (root == null || siteRelative == null || !siteRelative.startsWith("upload/")) {
            return null;
        }
        String rest = siteRelative.substring("upload/".length());
        if (rest.isEmpty() || rest.indexOf('\0') >= 0 || rest.contains("\\") || rest.contains(":")) {
            return null;
        }
        Path p;
        try {
            p = root.resolve(rest).normalize();
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
        return p.startsWith(root) && !p.equals(root) ? p : null;
    }

    /** 文件存在（旧 {@code file_exists('./' . $x)}，只限根目录内的普通文件）。 */
    public boolean exists(String siteRelative) {
        Path p = resolve(siteRelative);
        return p != null && Files.isRegularFile(p);
    }

    /** 文件大小（字节）；不存在时为 -1。 */
    public long size(String siteRelative) {
        Path p = resolve(siteRelative);
        try {
            return p != null && Files.isRegularFile(p) ? Files.size(p) : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    /** 删除一个文件（旧 {@code unlink}）；不在根目录内、不存在或不是普通文件时什么也不做。 */
    public boolean delete(String siteRelative) {
        Path p = resolve(siteRelative);
        if (p == null || !Files.isRegularFile(p)) {
            return false;
        }
        try {
            return Files.deleteIfExists(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
