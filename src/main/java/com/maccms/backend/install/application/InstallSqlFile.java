package com.maccms.backend.install.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 安装向导第 5 步执行的 {@code application/install/sql/install.sql}（APPK-2 S3-20，技术设计 §6.14 INS-1、INS-2 ③④）。
 *
 * <p>只执行与内置 SHA-256 完全相同的文件：哈希按读到的原始字节计算，不做换行归一化，所以 CRLF 检出的同一文件也判为不一致（INS-1、RISK-T17）。
 * 切分照搬 PHP {@code mac_parse_sql($sql, 0, ['mac_' => 'mac_'])}（application/common.php）：新版前缀固定 {@code mac_}，替换前后相同，
 * 所以不做前缀替换，语句一字不改。
 */
public final class InstallSqlFile {

    /**
     * 基线提交 {@code a57e9cb} 中 blob {@code php/application/install/sql/install.sql} 的 SHA-256（45049 字节，全部 LF）；
     * 核对：{@code git show a57e9cb:php/application/install/sql/install.sql | sha256sum}。
     */
    public static final String INSTALL_SQL_SHA256 = "48dbe0fbd138f670919208d8bcc0ffe28d09b507c527d2a6a1a8f7b77ffa3aa4";
    /** 读文件的上限（文件本身 45049 字节）。 */
    public static final int MAX_BYTES = 1 << 20;

    private static final Pattern LINE_COMMENT = Pattern.compile("^(#|--)");
    private static final Pattern ONE_LINE_BLOCK_COMMENT = Pattern.compile("^/\\*(.*?)\\*/");

    private InstallSqlFile() {
    }

    /** 原始字节与内置常量一致（null 视为不一致）。 */
    public static boolean matches(byte[] bytes) {
        return bytes != null && MessageDigest.isEqual(sha256(bytes).getBytes(StandardCharsets.US_ASCII),
                INSTALL_SQL_SHA256.getBytes(StandardCharsets.US_ASCII));
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** PHP {@code mac_parse_sql($sql, 0, …)} 之后再 {@code array_filter}（Index.php:225-227）：去掉空串。 */
    public static List<String> statements(byte[] bytes) {
        String sql = new String(bytes, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        if (sql.isEmpty()) {
            return out;
        }
        sql = sql.replace("\r\n", "\n").replace("\r", "\n");
        List<String> pure = new ArrayList<>();
        boolean comment = false;
        for (String line : phpTrim(sql).split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            if (LINE_COMMENT.matcher(line).find() || ONE_LINE_BLOCK_COMMENT.matcher(line).find()) {
                continue;
            }
            if (line.startsWith("/*")) {
                comment = true;
                continue;
            }
            if (line.endsWith("*/")) {
                comment = false;
                continue;
            }
            if (comment) {
                continue;
            }
            if (line.equals("BEGIN;") || line.equals("COMMIT;")) {
                continue;
            }
            pure.add(line);
        }
        for (String s : String.join("\n", pure).split(";\n", -1)) {
            if (!s.isEmpty() && !s.equals("0")) {
                out.add(s);
            }
        }
        return out;
    }

    /** PHP {@code trim}：去掉首尾的空格、\t、\n、\r、\0、\x0B。 */
    private static String phpTrim(String s) {
        int b = 0;
        int e = s.length();
        while (b < e && " \t\n\r\0\u000B".indexOf(s.charAt(b)) >= 0) {
            b++;
        }
        while (e > b && " \t\n\r\0\u000B".indexOf(s.charAt(e - 1)) >= 0) {
            e--;
        }
        return s.substring(b, e);
    }
}
