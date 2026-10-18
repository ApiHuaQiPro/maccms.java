package com.maccms.backend.site.application;

import com.maccms.backend.content.application.PasswordProof;
import com.maccms.backend.content.application.PasswordTarget;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 访问密码验证后的签名票据（B05，队长裁定 D-B05-3），代替旧版的 session 键 {@code mid-type-id}（Ajax.php:411-456）。
 *
 * <p>票据 = {@code v1.<到期 Unix 秒>.<凭据>.<签名>}：
 * <ul>
 * <li>凭据 = SHA-256(salt + 用户输入的正确密码) 的小写十六进制；salt 由部署密钥派生。content 在读取内容的同一条 SQL 中比较
 * {@code SHA2(CONCAT(salt, 当前密码), 256)}，密码被修改后旧票据立即失效，服务端始终不读出密码；</li>
 * <li>签名 = HMAC-SHA256(签名密钥, {@code v1|用途|内容 id|到期|凭据})，绑定用途（播放 v4、下载 v5、文章 a1）与内容 id；</li>
 * <li>到期由服务端检查（{@link #TTL}），不依赖 cookie 的过期属性。</li>
 * </ul>
 * 签名密钥与 salt 都由部署密钥（{@code maccms.site.password-tickets.secret}，只来自环境变量，没有内置值）经 HMAC 派生；未配置时
 * {@link #enabled()} 为 false，密码页保持 403 并说明暂不支持在线验证。票据与密钥都不写日志，{@code toString} 不输出内容。
 */
public final class PasswordTickets {

    /** 票据的服务端有效期。 */
    public static final Duration TTL = Duration.ofHours(2);
    /** 部署密钥的长度范围（字符）。 */
    public static final int MIN_SECRET = 32;
    public static final int MAX_SECRET = 256;

    private static final Pattern TICKET = Pattern.compile("v1\\.([0-9]{1,12})\\.([0-9a-f]{64})\\.([A-Za-z0-9_-]{43})");
    private static final String PREFIX = "maccms_pwd_";

    private final byte[] signKey;
    private final String salt;
    private final Clock clock;

    private PasswordTickets(byte[] signKey, String salt, Clock clock) {
        this.signKey = signKey;
        this.salt = salt;
        this.clock = clock;
    }

    /** 未配置部署密钥：不提供在线验证。 */
    public static PasswordTickets disabled() {
        return new PasswordTickets(null, null, Clock.systemUTC());
    }

    /**
     * @param secret 部署密钥：留空为未配置；否则须是 {@value #MIN_SECRET}～{@value #MAX_SECRET} 个可打印 ASCII 字符，
     *               不合法时抛 {@link IllegalStateException}（信息只含配置项名称与规则）
     */
    public static PasswordTickets from(String secret, Clock clock) {
        String s = secret == null ? "" : secret.strip();
        if (s.isEmpty()) {
            return new PasswordTickets(null, null, clock);
        }
        if (s.length() < MIN_SECRET || s.length() > MAX_SECRET || !s.chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
            throw new IllegalStateException("maccms.site.password-tickets.secret（留空，或 " + MIN_SECRET + " 到 " + MAX_SECRET
                    + " 个可打印 ASCII 字符，不含空格）");
        }
        byte[] master = s.getBytes(StandardCharsets.US_ASCII);
        byte[] sign = hmac(master, "maccms-password-ticket-sign-v1");
        String salt = HexFormat.of().formatHex(hmac(master, "maccms-password-fingerprint-salt-v1"));
        return new PasswordTickets(sign, salt, clock);
    }

    public boolean enabled() {
        return signKey != null;
    }

    /** cookie 名称：每个用途 + 内容一个。 */
    public static String cookieName(PasswordTarget target, long id) {
        return PREFIX + target.code() + "_" + id;
    }

    /** 密码已由 content 校验通过后签发票据。未启用时抛 {@link IllegalStateException}。 */
    public String issue(PasswordTarget target, long id, String password) {
        requireEnabled();
        long expires = clock.instant().plus(TTL).getEpochSecond();
        String fp = fingerprint(password);
        return "v1." + expires + "." + fp + "." + sign(target, id, expires, fp);
    }

    /**
     * 校验票据的格式、签名（常量时间比较）与到期时间；通过时返回交给 content 的凭据（是否与当前密码一致由 content 判断）。
     * 未启用、没有票据或任一检查失败时为空。
     */
    public Optional<PasswordProof> verify(PasswordTarget target, long id, String ticket) {
        if (!enabled() || ticket == null || ticket.length() > 160) {
            return Optional.empty();
        }
        var m = TICKET.matcher(ticket);
        if (!m.matches()) {
            return Optional.empty();
        }
        long expires = Long.parseLong(m.group(1));
        long now = clock.instant().getEpochSecond();
        if (expires <= now || expires > now + TTL.getSeconds()) {
            return Optional.empty();
        }
        String fp = m.group(2);
        byte[] expected = sign(target, id, expires, fp).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, m.group(3).getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        return Optional.of(new PasswordProof(salt, fp));
    }

    /** 票据的剩余有效秒数（用作 cookie Max-Age）。 */
    public long maxAgeSeconds() {
        return TTL.getSeconds();
    }

    private String fingerprint(String password) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest((salt + password).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 不可用", null);
        }
    }

    private String sign(PasswordTarget target, long id, long expires, String fp) {
        byte[] mac = hmac(signKey, "v1|" + target.code() + "|" + id + "|" + expires + "|" + fp);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 不可用", null);
        }
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new IllegalStateException("未配置访问密码票据密钥");
        }
    }

    @Override
    public String toString() {
        return "PasswordTickets[enabled=" + enabled() + "]";
    }
}
