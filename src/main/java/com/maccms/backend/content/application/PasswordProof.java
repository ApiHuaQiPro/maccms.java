package com.maccms.backend.content.application;

import java.util.regex.Pattern;

/**
 * 已验证访问密码的凭据（B05，D-B05-3）：{@code fingerprint = SHA-256(salt + 密码)} 的小写十六进制。content 在读取内容的同一条
 * SQL 中计算 {@code SHA2(CONCAT(salt, 当前密码列), 256) = fingerprint}，只得到一个布尔值，密码本身不读入内存；密码被修改后
 * 旧凭据自然失效。salt 由调用方（site 的签名 cookie）从部署密钥派生，content 不保存它。
 *
 * <p>只校验形态（各 64 位小写十六进制），不保证凭据有效。
 */
public record PasswordProof(String salt, String fingerprint) {

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    public PasswordProof {
        if (salt == null || fingerprint == null || !HEX64.matcher(salt).matches()
                || !HEX64.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("密码凭据格式不正确");
        }
    }

    @Override
    public String toString() {
        // 不输出取值
        return "PasswordProof[]";
    }
}
