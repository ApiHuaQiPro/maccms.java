package com.maccms.backend.admin.application;

import com.maccms.backend.integration.application.LegacyMailTemplate;
import com.maccms.backend.integration.application.MailSender;
import com.maccms.backend.integration.application.SendResult;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * 后台"测试发信"（E07，B25；32 文档第 6 节）：旧 {@code admin/controller/System.php:12-40} 的 {@code test_email}。
 *
 * <p>标题与正文取当前旧配置的 {@code email.tpl.test_title}、{@code test_body}，变量 {@code {$code}}（6 位随机数字）、
 * {@code {$time}}（{@code email.time}）、{@code {$maccms.<键>}}（{@code site} 段），正文再 {@code htmlspecialchars_decode}；
 * SMTP 参数取 {@code email.<提交的 type 小写>.*}（出站侧在启动时读入，修改后重启生效，D-B23-5），发件人昵称用提交的 {@code nick}。
 * 结果：成功 {@code {"code":1,"msg":"测试成功"}}，否则 {@code {"code":1001,"msg":"测试失败：<原因>"}}。
 */
public class MailTestService {

    /** 结果（旧 {@code json([...])} 的两个字段）。 */
    public record Result(int code, String msg) {
    }

    private final MailSender mail;
    private final Supplier<PhpArray> config;
    private final RandomGenerator random;

    /** 读当前旧配置 {@code extra/maccms.php}（没有配置旧配置目录时为空）。 */
    public MailTestService(MailSender mail, LegacyConfigService legacy, RandomGenerator random) {
        this(mail, () -> current(legacy), random);
    }

    /** @param config 每次测试时取一次的旧配置数组（可以为 null） */
    public MailTestService(MailSender mail, Supplier<PhpArray> config, RandomGenerator random) {
        this.mail = mail;
        this.config = config;
        this.random = random;
    }

    public Result test(String type, String nick, String to) {
        PhpArray maccms = config.get();
        String time = text(maccms, "email", "time");
        String code = code();
        Map<String, String> site = site(maccms);
        String title = LegacyMailTemplate.render(text(maccms, "email", "tpl", "test_title"), code, time, site);
        String body = LegacyMailTemplate.htmlspecialcharsDecode(
                LegacyMailTemplate.render(text(maccms, "email", "tpl", "test_body"), code, time, site));
        SendResult r = mail.sendTest(type == null ? "" : type.toLowerCase(Locale.ROOT), nick, to, title, body);
        return r.ok() ? new Result(1, "测试成功") : new Result(1001, "测试失败：" + r.msg());
    }

    private static PhpArray current(LegacyConfigService legacy) {
        if (!legacy.isConfigured()) {
            return null;
        }
        LegacyConfigService.Snapshot s = legacy.snapshotOrNull(LegacyConfigFile.MACCMS);
        return s == null ? null : s.value();
    }

    private static String text(PhpArray maccms, String... path) {
        return maccms == null ? "" : PhpValues.text(maccms.path(path));
    }

    private static Map<String, String> site(PhpArray maccms) {
        Map<String, String> out = new LinkedHashMap<>();
        if (maccms != null && maccms.get("site") instanceof PhpArray s) {
            for (Map.Entry<Object, PhpValue> e : s.entries().entrySet()) {
                if (!(e.getValue() instanceof PhpArray)) {
                    out.put(String.valueOf(e.getKey()), PhpValues.text(e.getValue()));
                }
            }
        }
        return out;
    }

    /** 旧 {@code mac_get_rndstr(6, 'num')}。 */
    private String code() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append((char) ('0' + random.nextInt(10)));
        }
        return sb.toString();
    }
}
