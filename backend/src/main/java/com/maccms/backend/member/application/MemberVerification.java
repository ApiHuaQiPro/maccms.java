package com.maccms.backend.member.application;

import com.maccms.backend.integration.application.LegacyMailTemplate;
import com.maccms.backend.integration.application.MailSender;
import com.maccms.backend.integration.application.SendResult;
import com.maccms.backend.integration.application.SmsSender;
import com.maccms.backend.member.application.view.AccountReply;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.infrastructure.MemberMapper;
import com.maccms.backend.member.infrastructure.MemberWriteMapper;
import java.time.Clock;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * 短信/邮件验证码的发送与校验（B25，32 文档第 5 节）：旧 {@code send_msg}（{@code common/model/User.php:634-712}）与
 * {@code check_msg}（{@code :602-632}）。验证码记在 {@code mac_msg}（发送成功才记），按"会员编号（游客为 0）+ 类型 + 时间窗"查找：
 * 1 绑定、2 找回、3 注册；时间窗 5 分钟，邮箱可由 {@code email.time} 改为别的分钟数。
 *
 * <p>与旧版的差异（32 文档第 9 节）：校验时另要求验证码的发送地址与本次使用的地址相同（D-B25-4，旧版只比验证码，拿发到自己手机的验证码
 * 可以重置任何人的密码）；地址超过 {@code mac_msg.msg_to} 的 30 个字符时不发送（D-B25-5）。验证码在时间窗内可以重复使用、
 * 不限制猜测次数，与旧版相同。
 *
 * <p>日志只记返回码，不记号码、地址、验证码与通道返回的内容。
 */
public class MemberVerification {

    private static final Logger log = LoggerFactory.getLogger(MemberVerification.class);

    /** {@code mac_msg.msg_to varchar(30)}。 */
    static final int MAX_TO = 30;
    /** {@code mac_msg.msg_content varchar(255)}。 */
    static final int MAX_CONTENT = 255;

    private final MemberMapper mapper;
    private final MemberWriteMapper writer;
    private final SmsSender sms;
    private final MailSender mail;
    private final VerificationSettings settings;
    private final Clock clock;
    private final RandomGenerator random;

    public MemberVerification(MemberMapper mapper, MemberWriteMapper writer, SmsSender sms, MailSender mail,
                              VerificationSettings settings, Clock clock, RandomGenerator random) {
        this.mapper = mapper;
        this.writer = writer;
        this.sms = sms;
        this.mail = mail;
        this.settings = settings;
        this.clock = clock;
        this.random = random;
    }

    /** 验证码类型：旧 {@code $type_arr}。 */
    enum Kind {
        BIND(1, "绑定", "bind"), FINDPASS(2, "找回密码", "findpass"), REG(3, "注册", "reg");

        final int type;
        final String des;
        final String flag;

        Kind(int type, String des, String flag) {
            this.type = type;
            this.des = des;
            this.flag = flag;
        }

        static Kind of(int type) {
            for (Kind k : values()) {
                if (k.type == type) {
                    return k;
                }
            }
            throw new IllegalArgumentException("未知的验证码类型");
        }
    }

    /**
     * 发送（参数检查与邮箱黑白名单已由调用方按旧顺序做完）。
     *
     * @param ac  {@code email} 或 {@code phone}
     * @param to  处理后的地址（{@code htmlspecialchars(urldecode(trim()))}）
     */
    AccountReply send(MemberState user, String ac, String to, int type) {
        Kind kind = Kind.of(type);
        boolean email = ac.equals("email");
        if (to.codePointCount(0, to.length()) > MAX_TO) {
            // D-B25-5：存不进 msg_to 的地址收不到可用的验证码，不发送
            return AccountReply.of(9009, "验证码发送失败：" + (email ? "邮箱格式不正确" : "手机号格式不正确"));
        }
        long now = clock.instant().getEpochSecond();
        long userId = userId(user);
        long since = now - settings.windowSeconds(email);
        if (mapper.selectRecentMessage(userId, since, kind.type, to) != null) {
            return AccountReply.of(9002, "请不要频繁发送");
        }
        String code = code();
        SendResult res;
        String content;
        if (email) {
            String title = LegacyMailTemplate.render(settings.emailTemplate("user_" + kind.flag + "_title"), code,
                    settings.emailTime(), settings.site());
            content = LegacyMailTemplate.htmlspecialcharsDecode(LegacyMailTemplate.render(
                    settings.emailTemplate("user_" + kind.flag + "_body"), code, settings.emailTime(), settings.site()));
            res = mail.send(to, title, content);
        } else {
            content = settings.smsContent().replace("[用户]", user.member() ? user.userName() : "游客")
                    .replace("[类型]", kind.des).replace("[时长]", "5").replace("[验证码]", code);
            res = sms.send(to, code, kind.flag);
        }
        log.info("member verification send type={} channel={} result={}", kind.type, email ? "email" : "sms", res.code());
        if (!res.ok()) {
            return AccountReply.of(9009, "验证码发送失败：" + res.msg());
        }
        try {
            writer.insertMessage(userId, kind.type, to, code, storable(content), now);
        } catch (DataAccessException e) {
            // 旧版不检查 saveData 的结果，照样提示发送成功
            log.warn("member verification record failed: {}", e.getClass().getSimpleName());
        }
        return AccountReply.of(1, "验证码发送成功，请前往查看");
    }

    /**
     * 校验：时间窗内该会员收到过同类、同验证码、同地址的验证码（参数检查已由调用方做完）。
     *
     * @param target 本次要使用的地址（绑定、注册写进会员资料的值，找回时查找会员的值）
     */
    boolean valid(MemberState user, String ac, String code, int type, String target) {
        long since = clock.instant().getEpochSecond() - settings.windowSeconds(ac.equals("email"));
        return mapper.selectValidMessage(userId(user), since, Kind.of(type).type, code, target) != null;
    }

    private static long userId(MemberState user) {
        return user == null || !user.member() ? 0 : user.userId();
    }

    /** 旧 {@code mac_get_rndstr(6, 'num')}：6 位数字，可以 0 开头。 */
    private String code() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append((char) ('0' + random.nextInt(10)));
        }
        return sb.toString();
    }

    /** {@code msg_content varchar(255)}（utf8）：超出的截掉，4 字节字符换成 {@code ?}（非严格模式下 MySQL 的结果）。 */
    static String storable(String content) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 0; i < content.length() && n < MAX_CONTENT; n++) {
            int cp = content.codePointAt(i);
            sb.appendCodePoint(Character.isSupplementaryCodePoint(cp) ? '?' : cp);
            i += Character.charCount(cp);
        }
        return sb.toString();
    }
}
