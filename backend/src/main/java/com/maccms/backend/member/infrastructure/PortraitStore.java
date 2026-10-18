package com.maccms.backend.member.infrastructure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 会员头像文件（B09，18 文档第 3.8 节）：旧站 {@code upload/} 目录下的 {@code user/<id%10>/<id>.jpg}（{@code Upload.php:76-87}、
 * {@code common.php:1205-1215}）。根目录来自部署参数 {@code maccms.member.upload-dir}，为空时不可用。
 *
 * <p>只写调用方给出的、已经重新编码过的 JPEG 字节；先写同目录的临时文件再改名，读者不会看到写了一半的文件。路径只由会员号拼出，
 * 不含任何用户输入。
 */
public final class PortraitStore {

    private final Path root;

    /** @param uploadDir 旧站 {@code upload/} 的本地路径；null 或空表示未配置 */
    public PortraitStore(String uploadDir) {
        this.root = uploadDir == null || uploadDir.isBlank() ? null : Path.of(uploadDir).toAbsolutePath().normalize();
    }

    public boolean configured() {
        return root != null;
    }

    /** 站点相对路径 {@code upload/user/<id%10>/<id>.jpg}（写进 {@code user_portrait} 与 cookie）。 */
    public static String relativePath(long userId) {
        return "upload/user/" + (userId % 10) + "/" + userId + ".jpg";
    }

    /** 头像文件存在（旧 {@code mac_get_user_portrait} 的 {@code file_exists}）；未配置时为 false。 */
    public boolean exists(long userId) {
        return root != null && Files.isRegularFile(file(userId));
    }

    public void write(long userId, byte[] jpeg) {
        if (root == null) {
            throw new IllegalStateException("头像目录未配置");
        }
        Path target = file(userId);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".portrait-", ".tmp");
            try {
                Files.write(tmp, jpeg);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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

    private Path file(long userId) {
        if (userId < 1) {
            throw new IllegalArgumentException("会员号不合法");
        }
        return root.resolve("user").resolve(Long.toString(userId % 10)).resolve(userId + ".jpg");
    }
}
