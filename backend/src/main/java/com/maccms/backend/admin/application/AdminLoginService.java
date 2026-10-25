package com.maccms.backend.admin.application;

import com.maccms.backend.admin.application.view.AdminPrincipal;
import com.maccms.backend.admin.infrastructure.AdminMapper;
import com.maccms.backend.admin.infrastructure.AdminRow;
import com.maccms.backend.admin.infrastructure.AdminWriteMapper;
import java.time.Clock;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * 后台登录、会话核对与锁屏解锁（B12，21 文档第 4.1～4.3 节）。会话本身（进程内、标识轮换、cookie）在 web 层，这里只做与库有关的判定。
 */
public class AdminLoginService {

    /** 登录失败的原因，对应旧 {@code Admin::login} 的返回码与提示（common/model/Admin.php:114-152）。 */
    public enum Failure {
        /** 1001 {@code param_err}：账号或密码 {@code empty()}。 */
        PARAM("param_err"),
        /** 1002 {@code verify_err}：要验证码而校验不通过。 */
        VERIFY("verify_err"),
        /** 1003 {@code access_or_pass_err}：没有匹配的启用账号。 */
        ACCESS("access_or_pass_err"),
        /** 1004 {@code model/admin/update_login_err}：登录记录没有写入（新版：读和写之间账号被停用或改了密码）。 */
        UPDATE("model/admin/update_login_err");

        private final String langKey;

        Failure(String langKey) {
            this.langKey = langKey;
        }

        public String langKey() {
            return langKey;
        }
    }

    /**
     * 登录结果。
     *
     * @param failure 成功时为 null
     * @param pwd     成功时为查到的那一行的 {@code admin_pwd} 原值（存进会话，D2 ⑤ 据此判断"登录后被改密"）
     */
    public record LoginResult(Failure failure, long adminId, String pwd) {

        public boolean ok() {
            return failure == null;
        }
    }

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    private final AdminMapper reader;
    private final AdminWriteMapper writer;
    private final Clock clock;
    private final RandomGenerator random;

    public AdminLoginService(AdminMapper reader, AdminWriteMapper writer, Clock clock, RandomGenerator random) {
        this.reader = reader;
        this.writer = writer;
        this.clock = clock;
        this.random = random;
    }

    /**
     * 旧 {@code Admin::login}：
     * <ol>
     * <li>账号或密码 {@code empty()}（缺失、空串、{@code "0"}；数组形式按缺失）→ {@code param_err}，不碰验证码；</li>
     * <li>要验证码时 {@code captcha} 不通过 → {@code verify_err}；</li>
     * <li>按 {@code admin_name}（MySQL 比较规则）、{@code admin_pwd = md5(原值)}、{@code admin_status = 1} 查一行，没有 → {@code access_or_pass_err}；</li>
     * <li>写登录记录（条件为查到的 id、本次的密码哈希与状态 1），没写进 → {@code model/admin/update_login_err}。</li>
     * </ol>
     *
     * @param name    已经 {@link LegacyInput#filterExp} 的原始字节，缺失为 null
     * @param pwd     同上
     * @param captcha 只在需要时调用一次（校验并作废本会话的验证码）
     * @param ip      旧 {@code mac_get_ip_long()} 的值（{@link #ipLong}）
     */
    public LoginResult login(byte[] name, byte[] pwd, boolean verify, BooleanSupplier captcha, long ip) {
        if (LegacyInput.empty(name) || LegacyInput.empty(pwd)) {
            return new LoginResult(Failure.PARAM, 0, null);
        }
        if (verify && !captcha.getAsBoolean()) {
            return new LoginResult(Failure.VERIFY, 0, null);
        }
        String account = LegacyInput.utf8Bmp(name);
        String hash = LegacyInput.md5Hex(pwd);
        AdminRow row = account == null ? null : reader.selectForLogin(account, hash);
        if (row == null) {
            return new LoginResult(Failure.ACCESS, 0, null);
        }
        byte[] seed = new byte[16];
        random.nextBytes(seed);
        long now = clock.instant().getEpochSecond();
        if (writer.recordLogin(row.adminId(), hash, now, ip, LegacyInput.md5Hex(seed)) != 1) {
            return new LoginResult(Failure.UPDATE, 0, null);
        }
        // 会话里存库里的原值（同旧版 session 存整行）：库里是大写哈希时 MySQL 比较不分大小写能登录，D2 ⑤ 与解锁按原值比较
        return new LoginResult(null, row.adminId(), row.adminPwd());
    }

    /**
     * D2 ⑤：按会话里的 {@code admin_id} 重读当前行。行不存在、{@code admin_status != 1} 或 {@code admin_pwd} 与登录时不同 → 空
     * （调用方作废会话、要求重新登录）；否则返回当前行（权限按当前的 {@code admin_auth} 判定）。
     */
    public Optional<AdminPrincipal> current(long adminId, String pwdAtLogin) {
        AdminRow row = reader.selectById(adminId);
        if (row == null || row.adminStatus() != 1 || pwdAtLogin == null || !pwdAtLogin.equals(row.adminPwd())) {
            return Optional.empty();
        }
        return Optional.of(new AdminPrincipal(row.adminId(), row.adminName(), row.adminAuth(), row.adminLoginNum(),
                row.adminLastLoginTime(), row.adminLastLoginIp()));
    }

    /**
     * 旧 {@code Index::unlocked}（admin/controller/Index.php:180-190）：{@code md5(password) == 会话里的 admin_pwd}。新版会话里的密码哈希
     * 在每个请求都已与库里的当前值核对过（{@link #current}），两者相同。
     *
     * @param password 已经 filterExp 的原始字节；缺失为 null（旧版 {@code md5(null)} 即 {@code md5('')}）
     */
    public boolean unlock(String pwdAtLogin, byte[] password) {
        return LegacyInput.md5Hex(password == null ? new byte[0] : password).equals(pwdAtLogin);
    }

    /**
     * 旧 {@code mac_get_ip_long}（application/common.php:990-999）：{@code sprintf('%u', ip2long($ip))}，不是点分十进制 IPv4（含 IPv6）时
     * 为 0。
     */
    public static long ipLong(String ip) {
        if (ip == null) {
            return 0;
        }
        var m = IPV4.matcher(ip);
        if (!m.matches()) {
            return 0;
        }
        long v = 0;
        for (int i = 1; i <= 4; i++) {
            String part = m.group(i);
            if (part.length() > 1 && part.charAt(0) == '0') {
                return 0;
            }
            int octet = Integer.parseInt(part);
            if (octet > 255) {
                return 0;
            }
            v = (v << 8) | octet;
        }
        return v >= 0xFFFF_FFFFL ? 0 : v;
    }
}
