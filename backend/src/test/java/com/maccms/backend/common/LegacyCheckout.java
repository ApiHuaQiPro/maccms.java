package com.maccms.backend.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 读黄金锁定的旧站文件（MACC-56）：git 里存的是 LF，而黄金哈希是在 {@code core.autocrlf=true} 的检出上生成的，锁的是 CRLF 字节。
 * 先比原始字节，对不上再比按 CRLF 规范化后的字节，返回对上的那份；都对不上返回原始字节，由调用方断言失败。二进制文件原始字节就对得上，不会被转换。
 */
public final class LegacyCheckout {

    private LegacyCheckout() {}

    /** 断言失败时附在说明后面。 */
    public static final String HINT = "（已按 CRLF 规范化比对；检出的换行设置？）";

    public static byte[] read(Path file, String sha256) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        if (sha256(raw).equals(sha256)) {
            return raw;
        }
        byte[] crlf = crlf(raw);
        return sha256(crlf).equals(sha256) ? crlf : raw;
    }

    /** 只把前面没有 {@code \r} 的 {@code \n} 换成 {@code \r\n}。 */
    public static byte[] crlf(byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length + b.length / 32);
        for (int i = 0; i < b.length; i++) {
            if (b[i] == '\n' && (i == 0 || b[i - 1] != '\r')) {
                out.write('\r');
            }
            out.write(b[i]);
        }
        return out.toByteArray();
    }

    public static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
