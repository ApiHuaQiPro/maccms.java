package com.maccms.backend.member.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 旧会员 cookie 的校验值 {@code user_check = md5(user_random-用户名-ID-)}（{@code common/model/User.php:389,446}）：
 * 对库里的三个原值按 UTF-8 字节拼接后取 MD5，32 位小写十六进制。
 */
public final class UserCheck {

    private UserCheck() {
    }

    public static String of(String userRandom, String userName, long userId) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(
                    (userRandom + "-" + userName + "-" + userId + "-").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 按字节比较（不提前退出）；只接受完全相同的值，没有旧版 {@code !=} 的数值宽松比较（D-B08-1）。 */
    static boolean matches(String expected, String presented) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
