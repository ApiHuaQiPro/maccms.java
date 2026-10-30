package com.maccms.backend.member.application;

import com.maccms.backend.common.web.PhpCookies;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.member.application.view.AccountReply;
import com.maccms.backend.member.application.view.MemberGroup;
import com.maccms.backend.member.application.view.MemberPageSettings;
import com.maccms.backend.member.application.view.MemberProfile;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.application.view.PortraitReply;
import com.maccms.backend.member.infrastructure.MemberAccountRow;
import com.maccms.backend.member.infrastructure.MemberInviterRow;
import com.maccms.backend.member.infrastructure.MemberLoginCandidate;
import com.maccms.backend.member.infrastructure.MemberLoginRow;
import com.maccms.backend.member.infrastructure.MemberMapper;
import com.maccms.backend.member.infrastructure.MemberWriteMapper;
import com.maccms.backend.member.infrastructure.PortraitStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * 会员账号（B09，docs/architecture/18-b09-member-account.md 第 3 节）：注册、登录、注销、资料与改密、密保找回、头像，以及 B25 前按
 * "未配置短信/邮件"返回的入口。逐条按旧 {@code common/model/User.php} 与 {@code index/controller/User.php} 的判定顺序、返回码与提示原文；
 * 密码字节语义见 03 草稿第 4.3 (1) 节，由 {@code member/account-php{80,73}-golden.json} 锁定。
 *
 * <p>写库只经 {@link MemberWriteMapper}（B07 可写数据源；关闭时 {@link #available()} 为 false，调用方返回 503）。每条写语句检查匹配行数。
 * 验证码由调用方按会话校验，以 {@link BooleanSupplier} 传入，在旧代码调用 {@code captcha_check} 的同一位置调用（有的入口不论开关都会
 * 调用一次，通过即作废）。日志不记录任何账号、密码、cookie。
 */
public class MemberAccountService {

    private static final Logger log = LoggerFactory.getLogger(MemberAccountService.class);

    /** 旧版时区 {@code PRC}（application/config.php:42）：注册 IP 限额的"今天零点"。 */
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 旧 {@code User.php:339}：不锚定，{@code \w} 只有 ASCII（PCRE 未开 u 修饰）。 */
    static final Pattern EMAIL = Pattern.compile("\\w+([-+.]\\w+)*@\\w+([-.]\\w+)*\\.\\w+([-.]\\w+)*");
    /** 旧 {@code User.php:149}：{@code $} 只认最后一个 {@code \n} 之前（PCRE 默认），用 UNIX_LINES 对齐。 */
    private static final Pattern NAME = Pattern.compile("^[a-zA-Z\\d]*$", Pattern.UNIX_LINES);
    /** 旧 {@code findpass_reset} 的手机号正则（{@code User.php:826}），{@code $} 同上。 */
    private static final Pattern FINDPASS_PHONE = Pattern.compile("^1{1}\\d{10}$", Pattern.UNIX_LINES);
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZONE);
    private static final String DEFAULT_PORTRAIT = "static/images/touxiang.png";

    private final MemberMapper mapper;
    private final MemberWriteMapper writer;
    private final MemberSessionService sessions;
    private final PortraitStore portraits;
    private final MemberSettings settings;
    private final PortraitImages images;
    private final Clock clock;
    private final RandomGenerator random;
    private final String sitePath;
    private final LongConsumer invited;
    private final PortraitAnnex annex;
    private final MemberVerification verification;

    /**
     * @param writer   可写数据源关闭时为 null
     * @param random   生成 {@code user_random} 与头像地址后缀的随机源（生产为 {@code SecureRandom}）
     * @param sitePath 站点根路径（旧 {@code MAC_PATH}，以 {@code /} 开头和结尾），用于头像地址与 {@code user_portrait} cookie
     */
    public MemberAccountService(MemberMapper mapper, MemberWriteMapper writer, MemberSessionService sessions,
                                PortraitStore portraits, MemberSettings settings, Clock clock, RandomGenerator random,
                                String sitePath) {
        this(mapper, writer, sessions, portraits, settings, clock, random, sitePath, inviterId -> { });
    }

    /**
     * @param invited 推广关系写入成功后调用（参数为邀请人编号）：B10 给邀请人加 {@code invite_reg_points} 并记 {@code mac_plog}
     *                （D-B09-7，{@link MemberReferralService#invitedRegistration}）
     */
    public MemberAccountService(MemberMapper mapper, MemberWriteMapper writer, MemberSessionService sessions,
                                PortraitStore portraits, MemberSettings settings, Clock clock, RandomGenerator random,
                                String sitePath, LongConsumer invited) {
        this(mapper, writer, sessions, portraits, settings, clock, random, sitePath, invited, (f, t, z) -> { });
    }

    /** @param annex 头像写入成功后登记附件（D-B09-9） */
    public MemberAccountService(MemberMapper mapper, MemberWriteMapper writer, MemberSessionService sessions,
                                PortraitStore portraits, MemberSettings settings, Clock clock, RandomGenerator random,
                                String sitePath, LongConsumer invited, PortraitAnnex annex) {
        this(mapper, writer, sessions, portraits, settings, clock, random, sitePath, invited, annex, null);
    }

    /**
     * @param verification 短信/邮件验证码（B25）；为 null 时按 B09 的"未配置"处理（发送固定失败、没有可用的验证码）
     */
    public MemberAccountService(MemberMapper mapper, MemberWriteMapper writer, MemberSessionService sessions,
                                PortraitStore portraits, MemberSettings settings, Clock clock, RandomGenerator random,
                                String sitePath, LongConsumer invited, PortraitAnnex annex, MemberVerification verification) {
        if (sitePath == null || !sitePath.startsWith("/") || !sitePath.endsWith("/")) {
            throw new IllegalArgumentException("站点根路径须以 / 开头和结尾");
        }
        this.mapper = mapper;
        this.writer = writer;
        this.sessions = sessions;
        this.portraits = portraits;
        this.settings = settings;
        this.images = new PortraitImages(settings.portraitWidth(), settings.portraitHeight());
        this.clock = clock;
        this.random = random;
        this.sitePath = sitePath;
        this.invited = invited;
        this.annex = annex;
        this.verification = verification;
    }

    /** 可写数据源已打开。 */
    public boolean available() {
        return writer != null;
    }

    public MemberPageSettings pageSettings() {
        return new MemberPageSettings(available(), settings.loginVerify(), settings.regVerify(),
                !settings.registrationClosed(), settings.regPhoneSms(), settings.regEmailSms(), portraitAvailable());
    }

    private boolean portraitAvailable() {
        return !settings.portraitZero() && settings.portraitWidth() > 0 && portraits.configured();
    }

    // ------------------------------------------------------------------ 登录、注销

    /**
     * 旧 {@code login()}（{@code common/model/User.php:321-393}）。{@code openid}、{@code col} 忽略（D2 ②）；密码只比较
     * {@code md5(trim(原值))}（D2 ①）。
     *
     * @param captcha  {@code login_verify=1} 时调用一次
     * @param clientIp {@code ClientIpResolver} 的结果
     */
    public AccountReply login(Map<String, PhpInput.Value> params, BooleanSupplier captcha, String clientIp) {
        requireWriter();
        return doLogin(params, settings.loginVerify() ? captcha : null, clientIp);
    }

    private AccountReply doLogin(Map<String, PhpInput.Value> params, BooleanSupplier captcha, String clientIp) {
        byte[] rawPwd = raw(params, "user_pwd");
        String name = LegacyText.clean(raw(params, "user_name"));
        String pwdClean = LegacyText.clean(rawPwd);
        if (LegacyText.empty(name) || LegacyText.empty(pwdClean)) {
            return AccountReply.of(1001, "请填写必填项");
        }
        if (captcha != null && !captcha.getAsBoolean()) {
            return AccountReply.of(1002, "验证码错误");
        }
        String pwd = LegacyText.md5(LegacyText.trim(rawPwd));
        MemberLoginCandidate row = MemberSessionService.hasSupplementary(name) ? null
                : mapper.selectLoginCandidate(EMAIL.matcher(name).find(), name, pwd);
        if (row == null) {
            return AccountReply.of(1003, "获取用户信息失败");
        }
        long now = now();
        String userRandom = newRandom();
        if (writer.recordLogin(row.userId(), pwd, userRandom, now, legacyIp(clientIp)) != 1) {
            return AccountReply.of(1003, "获取用户信息失败");
        }
        int groupId = row.groupId() > MemberState.EXPIRED_GROUP_ID && row.userEndTime() < now
                ? MemberState.EXPIRED_GROUP_ID : row.groupId();
        List<String> cookies = MemberCookies.login(row.userId(), row.userName(), sessions.group(groupId), userRandom,
                portraitUrl(row.userId()), now);
        return new AccountReply(1, "登录成功", cookies);
    }

    /**
     * 注销（{@code User.php:411-420}）：当前 cookie 校验通过时先轮换 {@code user_random}（D2 ⑥），再写 6 个删除 cookie。
     * 可写数据源关闭时只删 cookie。
     */
    public AccountReply logout(Map<String, PhpCookies.Value> cookies) {
        if (writer != null) {
            MemberLoginRow row = sessions.authenticated(cookies);
            if (row != null) {
                // 匹配 0 行：并发的注销/登录已经换过，旧 cookie 同样已失效
                writer.rotateRandom(row.userId(), row.userRandom(), newRandom());
            }
        }
        return new AccountReply(1, "退出成功", MemberCookies.logout());
    }

    // ------------------------------------------------------------------ 注册

    /**
     * 旧 {@code register()} + 控制器的注册后登录（{@code index/controller/User.php:349-365}、{@code common/model/User.php:119-267}）。
     * {@code user_openid_qq}、{@code user_openid_weixin} 参数忽略（D2 ② 的延伸）。
     *
     * @param cookies 请求 cookie（取推广人 {@code uid}）
     * @param captcha 旧版不论 {@code reg_verify} 都会调用一次（通过即作废），这里相同
     */
    public AccountReply register(Map<String, PhpInput.Value> params, Map<String, PhpCookies.Value> cookies,
                                 BooleanSupplier captcha, String clientIp) {
        return register(null, params, cookies, captcha, clientIp);
    }

    /** @param user 本次请求的登录态（旧 {@code $GLOBALS['user']}：校验手机/邮箱验证码时按它的编号查找，游客或 null 为 0） */
    public AccountReply register(MemberState user, Map<String, PhpInput.Value> params, Map<String, PhpCookies.Value> cookies,
                                 BooleanSupplier captcha, String clientIp) {
        requireWriter();
        AccountReply reg = doRegister(user, params, cookies, captcha, clientIp);
        if (!reg.ok()) {
            return reg;
        }
        AccountReply login = doLogin(params, null, clientIp);
        return new AccountReply(login.code(), "注册成功，" + login.msg(), login.setCookies());
    }

    private AccountReply doRegister(MemberState user, Map<String, PhpInput.Value> params,
                                    Map<String, PhpCookies.Value> cookies, BooleanSupplier captcha, String clientIp) {
        byte[] rawPwd = raw(params, "user_pwd");
        String name = LegacyText.clean(raw(params, "user_name"));
        String pwd = LegacyText.clean(rawPwd);
        String pwd2 = LegacyText.clean(raw(params, "user_pwd2"));
        if (settings.registrationClosed()) {
            return AccountReply.of(1001, "未开放注册");
        }
        if (LegacyText.empty(name) || LegacyText.empty(pwd) || LegacyText.empty(pwd2)) {
            return AccountReply.of(1002, "请填写必填项");
        }
        boolean verified = captcha.getAsBoolean();
        if (!verified && settings.regVerify()) {
            return AccountReply.of(1003, "验证码错误");
        }
        if (!pwd.equals(pwd2)) {
            return AccountReply.of(1004, "密码与确认密码不一致");
        }
        boolean storable = !MemberSessionService.hasSupplementary(name);
        if (storable && mapper.selectIdByName(name) != null) {
            return AccountReply.of(1005, "用户名已被注册，请更换");
        }
        if (!NAME.matcher(name).find()) {
            return AccountReply.of(1006, "用户名只能包含字母和数字，请更换");
        }
        if (name.codePointCount(0, name.length()) < 6) {
            return AccountReply.of(1007, "参数错误：名称最少不能低于6个字符");
        }
        if (containsFilterWord(name)) {
            return AccountReply.of(1008, "用户名禁止包含：" + settings.filterWords() + "等字符，请重试");
        }
        long ip = legacyIp(clientIp);
        long now = now();
        long todayStart = LocalDate.ofInstant(Instant.ofEpochSecond(now), ZONE).atStartOfDay(ZONE).toEpochSecond();
        if (settings.regNum() > 0 && mapper.countRegisteredSince(ip, todayStart) >= settings.regNum()) {
            return ipLimit();
        }
        String phone = null;
        String email = null;
        if (settings.regPhoneSms() || settings.regEmailSms()) {
            // 旧 check_msg（User.php:191-228 → :602-632），type=3；手机优先（旧版 elseif）
            String to = LegacyText.clean(raw(params, "to"));
            AccountReply check = checkMessage(user, params, 3, to);
            if (check != null) {
                return check;
            }
            if (settings.regPhoneSms()) {
                if (mapper.selectIdByPhone(to) != null) {
                    return AccountReply.of(1011, "手机号已被使用，请更换");
                }
                phone = to;
            } else {
                if (mapper.selectIdByEmail(to) != null) {
                    return AccountReply.of(1012, "邮箱已被使用，请更换");
                }
                email = to;
            }
        }
        int inserted;
        try {
            String pwdHash = LegacyText.md5(LegacyText.trim(rawPwd));
            inserted = phone == null && email == null
                    ? writer.register(name, pwdHash, settings.regPoints(), settings.regStatus(), now, ip,
                            settings.regNum(), todayStart)
                    : writer.registerWithContact(name, pwdHash, settings.regPoints(), settings.regStatus(), now, ip,
                            settings.regNum(), todayStart, phone, email);
        } catch (DataAccessException e) {
            log.warn("member register rejected by database: {}", e.getClass().getSimpleName());
            return AccountReply.of(1010, "注册失败，请重试");
        }
        if (inserted != 1) {
            if (mapper.selectIdByName(name) != null) {
                return AccountReply.of(1005, "用户名已被注册，请更换");
            }
            if (phone != null && mapper.selectIdByPhone(phone) != null) {
                return AccountReply.of(1011, "手机号已被使用，请更换");
            }
            if (email != null && mapper.selectIdByEmail(email) != null) {
                return AccountReply.of(1012, "邮箱已被使用，请更换");
            }
            return settings.regNum() > 0 ? ipLimit() : AccountReply.of(1010, "注册失败，请重试");
        }
        Long newId = mapper.selectIdByName(name);
        long uid = inviter(params, cookies);
        if (newId != null && uid > 0) {
            MemberInviterRow inv = mapper.selectInviter(uid);
            if (inv != null && writer.setInviter(newId, inv.userId(), inv.pid(), inv.pid2()) == 1) {
                // 邀请人加积分与 mac_plog 流水（B10，D-B09-7；19 文档第 4.13 节）：推广关系只生效一次，加分随之只有一次
                invited.accept(inv.userId());
            }
        }
        return AccountReply.of(1, "注册成功,请登录去会员中心完善个人信息");
    }

    private AccountReply ipLimit() {
        return AccountReply.of(1009, "每IP每日限制注册" + settings.regNumText() + "次");
    }

    /** {@code str_replace(explode(',', filter), '', name) != name}：任一非空词出现在用户名中。 */
    private boolean containsFilterWord(String name) {
        if (settings.filterWords().isEmpty()) {
            return false;
        }
        for (String w : settings.filterWords().split(",", -1)) {
            if (!w.isEmpty() && name.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 推广人：{@code uid} cookie 非空时取它，否则取参数 {@code uid}，再 intval（index/controller/User.php:353-355）。 */
    private static long inviter(Map<String, PhpInput.Value> params, Map<String, PhpCookies.Value> cookies) {
        PhpCookies.Value c = cookies.get("uid");
        if (c != null && !c.array() && !LegacyText.empty(c.bytes())) {
            return PhpIntval.of(new String(c.bytes(), StandardCharsets.ISO_8859_1));
        }
        byte[] p = raw(params, "uid");
        return PhpIntval.of(new String(p, StandardCharsets.ISO_8859_1));
    }

    /**
     * 旧 {@code regcheck}（{@code index/controller/User.php:337-347}、{@code common/model/User.php:269-290}）。
     *
     * @param captcha 校验 {@code t=verify} 时的验证码（参数是处理后的 {@code str}）
     * @return null 表示 JSON {@code {"code":1,"msg":"ok"}}；否则是要原样输出的正文（处理后的 {@code str}）
     */
    public String regcheck(Map<String, PhpInput.Value> params, Predicate<String> captcha) {
        String t = LegacyText.clean(raw(params, "t"));
        String str = LegacyText.clean(raw(params, "str"));
        boolean taken = switch (t) {
            case "user_name" -> !MemberSessionService.hasSupplementary(str) && mapper.selectIdByName(str) != null;
            case "user_email" -> !MemberSessionService.hasSupplementary(str) && mapper.selectIdByEmail(str) != null;
            case "verify" -> !captcha.test(str);
            default -> false;
        };
        return taken ? str : null;
    }

    // ------------------------------------------------------------------ 短信/邮件验证码（B25，32 文档第 5 节）

    /** 游客身份调用 {@link #sendMessage(MemberState, Map, int)}（B09 的测试沿用）。 */
    public AccountReply sendMessage(Map<String, PhpInput.Value> params, int type) {
        return sendMessage(null, params, type);
    }

    /**
     * {@code reg_msg}、{@code findpass_msg}、{@code bindmsg}（旧 {@code send_msg}，{@code User.php:634-712}）：参数检查与邮箱黑白名单同旧版，
     * 之后交给 {@link MemberVerification}；没有接入（{@code verification} 为 null）时按 B09 固定为"未配置"。
     *
     * @param user 本次请求的登录态（游客或 null 按会员编号 0）
     * @param type 1 绑定、2 找回、3 注册
     */
    public AccountReply sendMessage(MemberState user, Map<String, PhpInput.Value> params, int type) {
        String to = LegacyText.clean(raw(params, "to"));
        String ac = new String(raw(params, "ac"), StandardCharsets.UTF_8);
        if (!(ac.equals("email") || ac.equals("phone")) || LegacyText.empty(to)) {
            return AccountReply.of(9001, "参数错误");
        }
        if (ac.equals("email") && (type == 1 || type == 3)) {
            AccountReply host = emailHost(to);
            if (host != null) {
                return host;
            }
        }
        if (verification == null) {
            return AccountReply.of(9009, "验证码发送失败：" + (ac.equals("email") ? "未配置邮箱发送服务" : "未配置短信发送服务"));
        }
        requireWriter();
        return verification.send(user, ac, to, type);
    }

    /** 游客身份调用 {@link #findpassReset(MemberState, Map)}。 */
    public AccountReply findpassReset(Map<String, PhpInput.Value> params) {
        return findpassReset(null, params);
    }

    /**
     * {@code findpass_reset}（旧 {@code User.php:785-846}）：验证码通过后按手机/邮箱找到会员，改密并轮换 {@code user_random}（D2 ⑥）。
     * 地址取 {@code user_email}，为空时取 {@code to}；验证码必须是发给这个地址的（D-B25-4）。
     */
    public AccountReply findpassReset(MemberState user, Map<String, PhpInput.Value> params) {
        byte[] rawPwd = raw(params, "user_pwd");
        String to = LegacyText.clean(raw(params, "user_email"));
        if (LegacyText.empty(to)) {
            to = LegacyText.clean(raw(params, "to"));
        }
        String pwd = LegacyText.clean(rawPwd);
        String pwd2 = LegacyText.clean(raw(params, "user_pwd2"));
        if (pwd.getBytes(StandardCharsets.UTF_8).length < 6) {
            return AccountReply.of(2002, "密码最少6个字符");
        }
        if (!pwd.equals(pwd2)) {
            return AccountReply.of(2003, "两次输入的新密码不一致");
        }
        AccountReply check = checkMessage(user, params, 2, to);
        if (check != null) {
            return check;
        }
        requireWriter();
        boolean email = new String(raw(params, "ac"), StandardCharsets.UTF_8).equals("email");
        if (email) {
            if (!EMAIL.matcher(to).find()) {
                return AccountReply.of(2005, "邮箱地址格式不正确");
            }
            if (mapper.selectIdByEmail(to) == null) {
                return AccountReply.of(2006, "邮箱地址不正确");
            }
        } else {
            if (!FINDPASS_PHONE.matcher(to).find()) {
                return AccountReply.of(2007, "手机号码格式不正确");
            }
            if (mapper.selectIdByPhone(to) == null) {
                return AccountReply.of(2008, "手机号码不正确");
            }
        }
        String hash = LegacyText.md5(LegacyText.trim(rawPwd));
        try {
            if (email) {
                writer.resetPasswordByEmail(to, hash, newRandom());
            } else {
                writer.resetPasswordByPhone(to, hash, newRandom());
            }
        } catch (DataAccessException e) {
            log.warn("member findpass reset failed: {}", e.getClass().getSimpleName());
            return AccountReply.of(2009, "密码重置失败，请重试");
        }
        return AccountReply.of(1, "密码重置成功");
    }

    /**
     * {@code bind} POST（旧 {@code User.php:714-742}）：验证码（类型 1）通过后，清掉所有会员上的同一号码/地址，再写到当前会员。
     * 写入的是处理后的地址（旧版写未处理的原值，D-B25-6）。
     */
    public AccountReply bind(MemberState user, Map<String, PhpInput.Value> params) {
        requireWriter();
        String to = LegacyText.clean(raw(params, "to"));
        AccountReply check = checkMessage(user, params, 1, to);
        if (check != null) {
            return check;
        }
        boolean email = new String(raw(params, "ac"), StandardCharsets.UTF_8).equals("email");
        try {
            if (email) {
                writer.clearEmail(to);
                writer.setEmail(user.userId(), to);
            } else {
                writer.clearPhone(to);
                writer.setPhone(user.userId(), to);
            }
        } catch (DataAccessException e) {
            log.warn("member bind failed: {}", e.getClass().getSimpleName());
            return AccountReply.of(2003, "更新用户绑定信息失败");
        }
        return AccountReply.of(1, "绑定成功");
    }

    /** {@code unbind} POST（旧 {@code User.php:744-764}）。 */
    public AccountReply unbind(MemberState user, Map<String, PhpInput.Value> params) {
        requireWriter();
        String ac = new String(raw(params, "ac"), StandardCharsets.UTF_8);
        if (!(ac.equals("email") || ac.equals("phone"))) {
            return AccountReply.of(2001, "参数错误");
        }
        try {
            if (ac.equals("email")) {
                writer.setEmail(user.userId(), "");
            } else {
                writer.setPhone(user.userId(), "");
            }
        } catch (DataAccessException e) {
            log.warn("member unbind failed: {}", e.getClass().getSimpleName());
            return AccountReply.of(2002, "更新用户绑定信息失败");
        }
        return AccountReply.of(1, "解除绑定成功");
    }

    /**
     * 旧 {@code check_msg}（{@code User.php:602-632}）。{@code findpass_reset} 先把 {@code code} 处理过一次，这里再处理一次（旧代码就是两次）。
     *
     * @param target 验证码必须是发给它的（D-B25-4）
     * @return null 表示通过
     */
    private AccountReply checkMessage(MemberState user, Map<String, PhpInput.Value> params, int type, String target) {
        String to = LegacyText.clean(raw(params, "to"));
        byte[] code = raw(params, "code");
        if (type == 2) {
            code = LegacyText.clean(code).getBytes(StandardCharsets.UTF_8);
        }
        String codeClean = LegacyText.clean(code);
        String ac = new String(raw(params, "ac"), StandardCharsets.UTF_8);
        if (!(ac.equals("email") || ac.equals("phone")) || LegacyText.empty(to) || LegacyText.empty(codeClean)) {
            return AccountReply.of(9001, "参数错误");
        }
        if (ac.equals("email") && (type == 1 || type == 3)) {
            AccountReply host = emailHost(to);
            if (host != null) {
                return host;
            }
        }
        // 库是 utf8（3 字节）：含 4 字节字符的值不可能与库里的记录相等，不送进查询
        if (verification == null || MemberSessionService.hasSupplementary(target)
                || MemberSessionService.hasSupplementary(codeClean)
                || !verification.valid(user, ac, codeClean, type, target)) {
            return AccountReply.of(9002, "验证信息错误，请重试");
        }
        return null;
    }

    /** 旧 {@code UserValidate::validateEmail}（{@code common/validate/User.php:27-42}）。 */
    private AccountReply emailHost(String email) {
        int at = email.indexOf('@');
        String host = at < 0 ? null : email.substring(at + 1);
        Set<String> white = hostSet(settings.emailWhiteHosts());
        if (!white.isEmpty() && (host == null || !white.contains(host))) {
            return AccountReply.of(1001, "邮箱域名不允许");
        }
        if (host != null && hostSet(settings.emailBlackHosts()).contains(host)) {
            return AccountReply.of(1002, "邮箱域名不允许");
        }
        return null;
    }

    private static Set<String> hostSet(String config) {
        Set<String> out = new HashSet<>();
        for (String h : config.replace("\n", ",").split(",", -1)) {
            String t = LegacyText.phpTrim(h);
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 第三方登录（B26，34 文档第 4 节）

    /**
     * 已登录会员收到第三方回调（旧 {@code index/controller/User.php:232-252}）：该方式已绑定的就是这个 openid → 1001"已经绑定该账号"；
     * 否则先清掉所有会员上的同一 openid，再绑定到当前会员 → 1"绑定成功"。
     *
     * @param openid 服务端换取的 openid（{@code OAuthLogin#fetch}），不是浏览器提交的参数
     */
    public AccountReply openidBind(MemberState member, boolean qq, String openid) {
        requireWriter();
        if (!member.member()) {
            throw new IllegalArgumentException("绑定须在会员已登录时进行");
        }
        String current = mapper.selectOpenid(member.userId(), qq);
        if (openid.equals(current)) {
            return AccountReply.of(1001, "已经绑定该账号");
        }
        try {
            writer.clearOpenid(qq, openid);
            if (writer.setOpenid(member.userId(), qq, openid) != 1) {
                return AccountReply.of(1002, "绑定失败");
            }
        } catch (DataAccessException e) {
            log.warn("member openid bind failed: {}", e.getClass().getSimpleName());
            return AccountReply.of(1002, "绑定失败");
        }
        log.info("member openid bound type={}", qq ? "qq" : "weixin");
        return AccountReply.of(1, "绑定成功");
    }

    /**
     * 游客收到第三方回调（旧 {@code index/controller/User.php:254-279}）：openid 未绑定任何会员时先自动注册（用户名取 openid 前 10 位，
     * 密码是不可猜测的随机值，D-B26-1），再凭 openid 登录并签发与普通登录相同的 cookie。
     *
     * @param openid 服务端换取的 openid（{@code OAuthLogin#fetch}），只含 {@code [A-Za-z0-9_-]}
     * @return 1 登录成功（带 cookie）；1010"同步信息注册失败，请联系管理员"；1003"获取用户信息失败"
     */
    public AccountReply openidLogin(boolean qq, String openid, String clientIp) {
        requireWriter();
        long ip = legacyIp(clientIp);
        if (mapper.selectIdByOpenid(qq, openid) == null && !openidRegister(qq, openid, ip)) {
            return AccountReply.of(1010, "同步信息注册失败，请联系管理员");
        }
        MemberLoginCandidate row = mapper.selectOpenidLoginCandidate(qq, openid);
        if (row == null) {
            return AccountReply.of(1003, "获取用户信息失败");
        }
        long now = now();
        String userRandom = newRandom();
        if (writer.recordOpenidLogin(row.userId(), qq, openid, userRandom, now, ip) != 1) {
            return AccountReply.of(1003, "获取用户信息失败");
        }
        int groupId = row.groupId() > MemberState.EXPIRED_GROUP_ID && row.userEndTime() < now
                ? MemberState.EXPIRED_GROUP_ID : row.groupId();
        log.info("member openid login type={}", qq ? "qq" : "weixin");
        return new AccountReply(1, "登录成功", MemberCookies.login(row.userId(), row.userName(), sessions.group(groupId),
                userRandom, portraitUrl(row.userId()), now));
    }

    /**
     * 旧 {@code register()} 在第三方注册时走到的检查（跳过验证码与手机/邮箱验证，{@code common/model/User.php:130-231}）：未开放注册、用户名已存在、
     * 用户名含字母数字以外的字符、不足 6 个字符、含过滤词、IP 限额，任一不满足即失败。
     *
     * @return 已注册（本次插入，或并发的另一次回调已经插入了同一 openid：此时用户名判重等检查也会失败，所以失败后再查一次 openid）
     */
    private boolean openidRegister(boolean qq, String openid, long ip) {
        return tryOpenidRegister(qq, openid, ip) || mapper.selectIdByOpenid(qq, openid) != null;
    }

    private boolean tryOpenidRegister(boolean qq, String openid, long ip) {
        String name = LegacyText.clean(openid.substring(0, Math.min(10, openid.length()))
                .getBytes(StandardCharsets.UTF_8));
        if (settings.registrationClosed() || LegacyText.empty(name) || mapper.selectIdByName(name) != null
                || !NAME.matcher(name).find() || name.codePointCount(0, name.length()) < 6 || containsFilterWord(name)) {
            return false;
        }
        long now = now();
        long todayStart = LocalDate.ofInstant(Instant.ofEpochSecond(now), ZONE).atStartOfDay(ZONE).toEpochSecond();
        if (settings.regNum() > 0 && mapper.countRegisteredSince(ip, todayStart) >= settings.regNum()) {
            return false;
        }
        // 旧版密码是 time()（可猜测）；新版是 256 位随机数的十六进制再 md5，没有人知道原文（D-B26-1）
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String pwd = LegacyText.md5(java.util.HexFormat.of().formatHex(secret));
        int inserted;
        try {
            inserted = writer.registerOpenid(name, pwd, settings.regPoints(), settings.regStatus(), now, ip,
                    settings.regNum(), todayStart, qq, openid);
        } catch (DataAccessException e) {
            log.warn("member openid register rejected by database: {}", e.getClass().getSimpleName());
            return false;
        }
        if (inserted == 1) {
            log.info("member openid registered type={}", qq ? "qq" : "weixin");
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 资料与改密、找回

    /**
     * 旧 {@code info()}（{@code common/model/User.php:292-319} → {@code saveData :58-94}）。改了密码时同一条语句轮换
     * {@code user_random}（D2 ⑥），并在本响应重新签发 6 个登录 cookie。
     *
     * @return {@code code} 1 成功、0 失败（旧控制器只用 {@code success/error}，JSON 里的 code 只有 1/0）
     */
    public AccountReply updateProfile(MemberState member, Map<String, PhpInput.Value> params) {
        requireWriter();
        byte[] rawPwd = raw(params, "user_pwd");
        if (LegacyText.empty(rawPwd)) {
            return AccountReply.of(0, "请输入原密码");
        }
        MemberAccountRow account = mapper.selectAccount(member.userId());
        if (account == null) {
            return AccountReply.of(0, "原密码错误");
        }
        String stored = account.userPwd() == null ? "" : account.userPwd();
        if (!stored.equals(LegacyText.md5(LegacyText.trim(rawPwd)))
                && !stored.equals(LegacyText.md5(LegacyText.clean(rawPwd)))) {
            return AccountReply.of(0, "原密码错误");
        }
        byte[] pwd1 = raw(params, "user_pwd1");
        byte[] pwd2 = raw(params, "user_pwd2");
        if (!Arrays.equals(pwd1, pwd2)) {
            return AccountReply.of(0, "两次输入的新密码不一致");
        }
        // saveData 的 edit 场景校验的是库里的用户名（ThinkPHP min:6 按 mb_strlen）
        String userName = account.userName();
        if (userName.codePointCount(0, userName.length()) < 6) {
            return AccountReply.of(0, "参数错误：名称最少不能低于6个字符");
        }
        byte[] rawNick = raw(params, "user_nick_name");
        String nick = LegacyText.empty(rawNick) ? null : LegacyText.clean(rawNick);
        String qq = LegacyText.clean(raw(params, "user_qq"));
        String question = LegacyText.clean(raw(params, "user_question"));
        String answer = LegacyText.clean(raw(params, "user_answer"));
        byte[] newPwdRaw = LegacyText.trim(pwd2);
        // !empty($param['user_pwd2']) 后存 trim 的结果，saveData 再按 empty() 判断是否改密
        boolean changePwd = !LegacyText.empty(pwd2) && !LegacyText.empty(newPwdRaw);
        String newPwd = changePwd ? LegacyText.md5(newPwdRaw) : null;
        String newRandom = changePwd ? newRandom() : null;
        int matched;
        try {
            matched = writer.updateProfile(member.userId(), stored, qq, question, answer, nick, newPwd, newRandom);
        } catch (DataAccessException e) {
            log.warn("member profile rejected by database: {}", e.getClass().getSimpleName());
            return AccountReply.of(0, "保存失败!");
        }
        if (matched != 1) {
            return AccountReply.of(0, "原密码错误");
        }
        if (!changePwd) {
            return AccountReply.of(1, "保存成功!");
        }
        long now = now();
        List<String> cookies = MemberCookies.login(account.userId(), account.userName(), member.group(), newRandom,
                portraitUrl(account.userId()), now);
        return new AccountReply(1, "保存成功!", cookies);
    }

    /**
     * 旧 {@code findpass()}（{@code common/model/User.php:480-526}）：成功时同一条语句写新密码并轮换 {@code user_random}（D2 ⑥）。
     */
    public AccountReply findpass(Map<String, PhpInput.Value> params, BooleanSupplier captcha) {
        requireWriter();
        byte[] rawPwd = raw(params, "user_pwd");
        String name = LegacyText.clean(raw(params, "user_name"));
        String question = LegacyText.clean(raw(params, "user_question"));
        String answer = LegacyText.clean(raw(params, "user_answer"));
        String pwd = LegacyText.clean(rawPwd);
        String pwd2 = LegacyText.clean(raw(params, "user_pwd2"));
        byte[] verify = raw(params, "verify");
        if (LegacyText.empty(name) || LegacyText.empty(question) || LegacyText.empty(answer) || LegacyText.empty(pwd)
                || LegacyText.empty(pwd2) || LegacyText.empty(verify)) {
            return AccountReply.of(1001, "参数错误");
        }
        if (!captcha.getAsBoolean()) {
            return AccountReply.of(1002, "验证码错误");
        }
        if (!pwd.equals(pwd2)) {
            return AccountReply.of(1003, "两次输入的新密码不一致");
        }
        Long id = MemberSessionService.hasSupplementary(name + question + answer) ? null
                : mapper.selectIdByQuestion(name, question, answer);
        if (id == null) {
            return AccountReply.of(1004, "获取用户失败，账号、问题、答案可能不正确");
        }
        if (writer.resetPassword(id, name, question, answer, LegacyText.md5(LegacyText.trim(rawPwd)),
                newRandom()) != 1) {
            return AccountReply.of(1004, "获取用户失败，账号、问题、答案可能不正确");
        }
        return AccountReply.of(1, "密码找回成功成功");
    }

    // ------------------------------------------------------------------ 头像

    /**
     * 旧 {@code portrait} 的上传（{@code index/controller/User.php:384-398}、{@code common/model/Upload.php:37-291}）。
     *
     * @param file     {@code file} 字段的内容；没有时为 null
     * @param fileName {@code file} 字段的原文件名（只取扩展名）
     * @param imgdata  {@code imgdata} 参数（{@code data:image/<类型>;base64,…}）；没有时为 null 或空
     */
    public PortraitReply portrait(MemberState member, byte[] file, String fileName, String imgdata) {
        requireWriter();
        if (!portraitAvailable()) {
            return new PortraitReply(0, "未开启自定义头像功能", null, true);
        }
        PortraitImages.Outcome out = images.convert(file, fileName, imgdata);
        if (out.error() != null) {
            return failed(out.error());
        }
        try {
            portraits.write(member.userId(), out.jpeg());
        } catch (RuntimeException e) {
            log.warn("member portrait write failed: {}", e.getClass().getSimpleName());
            return failed("文件上传失败！");
        }
        String path = PortraitStore.relativePath(member.userId());
        if (writer.updatePortrait(member.userId(), path) != 1) {
            return failed("更新会员头像信息失败！");
        }
        registerAnnex(path, PortraitImages.annexType(fileName, imgdata), out.jpeg().length);
        return new PortraitReply(1, "文件上传成功！", sitePath + path + "?" + random.nextInt(1000, 10_000), false);
    }

    /**
     * D-B09-9：登记附件（同一文件已登记时不重复）。类型同旧版（{@link PortraitImages#annexType}）：文件上传按扩展名分类为 {@code image}、
     * {@code file} 或 {@code media}，{@code imgdata} 为 data URL 里的图片类型；大小按新版重新编码后的 JPEG（KB，保留两位小数，写库时按
     * 整数列取整）。登记失败不影响上传结果（旧版不检查 saveData 的返回值）。
     */
    private void registerAnnex(String path, String type, int bytes) {
        String size = java.math.BigDecimal.valueOf(bytes / 1024.0).setScale(2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        try {
            annex.register(path, type, size);
        } catch (RuntimeException e) {
            log.warn("member portrait annex registration failed: {}", e.getClass().getSimpleName());
        }
    }

    private PortraitReply failed(String msg) {
        return new PortraitReply(0, msg, sitePath + "?" + random.nextInt(1000, 10_000), false);
    }

    // ------------------------------------------------------------------ 资料展示

    /** 会员中心、资料页与 {@code ajax_info} 的资料；会员不存在时为 null。 */
    public MemberProfile profile(MemberState member) {
        MemberAccountRow a = mapper.selectAccount(member.userId());
        if (a == null) {
            return null;
        }
        MemberGroup g = member.group();
        return new MemberProfile(a.userId(), str(a.userName()), str(a.nickName()), member.groupId(),
                g == null ? "" : str(g.name()), a.userEndTime(), a.points(), str(a.email()), str(a.phone()),
                str(a.qq()), time(a.regTime()), time(a.loginTime()), time(a.userEndTime()),
                portraitUrl(a.userId()), LegacyText.unclean(a.nickName()), LegacyText.unclean(a.qq()),
                LegacyText.unclean(a.question()), LegacyText.unclean(a.answer()));
    }

    // ------------------------------------------------------------------ 工具

    /** 旧 {@code mac_get_user_portrait}（{@code application/common.php:1205-1215}）。 */
    String portraitUrl(long userId) {
        return sitePath + (portraits.exists(userId) ? PortraitStore.relativePath(userId) : DEFAULT_PORTRAIT);
    }

    /** 旧 {@code md5(rand(10000000, 99999999))}（{@code User.php:368}），随机数改用调用方给的安全随机源。 */
    private String newRandom() {
        return LegacyText.md5(Integer.toString(random.nextInt(10_000_000, 100_000_000)));
    }

    private long now() {
        return clock.instant().getEpochSecond();
    }

    private void requireWriter() {
        if (writer == null) {
            throw new IllegalStateException("会员账号功能需要可写数据源");
        }
    }

    /**
     * 旧 {@code mac_get_ip_long}（{@code application/common.php:990-999}）：{@code sprintf('%u', ip2long($ip))}，不是点分十进制 IPv4
     * 时为 0（{@code ip2long} 对 IPv6 返回 false）。
     */
    static long legacyIp(String ip) {
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

    private static byte[] raw(Map<String, PhpInput.Value> params, String name) {
        byte[] v = PhpInput.scalar(params, name);
        return v == null ? new byte[0] : v;
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    private static String time(long epoch) {
        return epoch <= 0 ? "" : DATE_TIME.format(Instant.ofEpochSecond(epoch));
    }
}
