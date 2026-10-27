package com.maccms.backend.staticgen.application;

import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.VarExportWriter;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.time.Clock;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定时任务的执行（旧 api/controller/Timming.php:12-57，B16，25 文档第 7 节）。调用方（{@code TimmingController}）先做部署级鉴权（D2 ③），
 * 未通过时根本不调用本类。
 *
 * <p>按 {@code extra/timming.php} 的顺序逐个判断（{@link TimmingSchedule}）：不到点的输出"跳过"；第一个到点的输出"执行"，把它的
 * {@code runtime} 改成当前时间写回 {@code timming.php}（{@code mac_arr2file}，与 PHP 逐字节相同），然后执行该任务并结束（同旧版的
 * {@code die}，一次请求最多执行一个任务）。写回失败时输出"配置文件写入失败，请重试!"，不执行任务。
 *
 * <p>与旧版的差异：写回在文件锁里基于当前文件内容只改该任务的 {@code runtime}（旧版把请求开始时读到的整张表写回，期间别人的修改会被覆盖）；
 * 输出里的任务名等数据做 HTML 转义。
 */
public final class TimmingService {

    /** 执行一个任务，返回它的输出（HTML）。 */
    public interface TaskRunner {
        String run(String file, String param);
    }

    private final LegacyConfigService config;
    private final LegacyLang lang;
    private final Clock clock;
    private final ZoneId zone;

    public TimmingService(LegacyConfigService config, LegacyLang lang, Clock clock, ZoneId zone) {
        this.config = config;
        this.lang = lang;
        this.clock = clock;
        this.zone = zone;
    }

    /**
     * @param name    请求参数 {@code name}（只执行同名任务；空串或 {@code "0"} 表示不限）
     * @param enforce 请求参数 {@code enforce}（{@code == '1'} 时无视时间表）
     * @return 输出的 HTML
     */
    public String run(String name, String enforce, TaskRunner runner) {
        StringBuilder out = new StringBuilder();
        LegacyConfigService.Snapshot snapshot = config.snapshotOrNull(LegacyConfigFile.TIMMING);
        if (snapshot == null) {
            return "";
        }
        long now = clock.instant().getEpochSecond();
        boolean filter = !(name.isEmpty() || name.equals("0"));
        for (Map.Entry<Object, PhpValue> e : snapshot.value().entries().entrySet()) {
            if (!(e.getValue() instanceof PhpArray task)) {
                continue;
            }
            String taskName = PhpValues.text(task.get("name"));
            if (filter && !looseEquals(taskName, name)) {
                continue;
            }
            TimmingSchedule.Decision d = TimmingSchedule.decide(task, now, enforce, zone, lang.get("api/never"));
            String status = EpisodeCounts.looseEqualsOne(task.get("status")) ? lang.get("open") : lang.get("close");
            if (!d.due()) {
                echo(out, Html.escape(format(lang.get("api/task_tip_jump"), taskName, status, d.last())));
                continue;
            }
            echo(out, Html.escape(format(lang.get("api/task_tip_exec"), taskName, status, d.last())));
            if (!writeRuntime(e.getKey(), now)) {
                out.append(Html.escape(lang.get("write_err_config")));
                return out.toString();
            }
            out.append(runner.run(PhpValues.text(task.get("file")), PhpValues.text(task.get("param"))));
            return out.toString();
        }
        return out.toString();
    }

    /** 在文件锁里把当前文件中该任务的 runtime 改成 {@code now} 写回；任务已被删除时不写。 */
    boolean writeRuntime(Object key, long now) {
        AtomicBoolean written = new AtomicBoolean(false);
        try {
            config.update(LegacyConfigFile.TIMMING, current -> {
                if (current == null || !(current.entries().get(key) instanceof PhpArray task)) {
                    return null;
                }
                Map<Object, PhpValue> t = new LinkedHashMap<>(task.entries());
                t.put("runtime", new PhpValue.PhpInt(now));
                Map<Object, PhpValue> all = new LinkedHashMap<>(current.entries());
                all.put(key, new PhpArray(t));
                written.set(true);
                return VarExportWriter.macArr2File(new PhpArray(all));
            });
        } catch (LegacyConfigException ex) {
            return false;
        }
        return written.get();
    }

    /** PHP {@code lang($key, [...])}：依次替换 {@code %s}。 */
    static String format(String pattern, String... args) {
        StringBuilder sb = new StringBuilder();
        int a = 0;
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.startsWith("%s", i) && a < args.length) {
                sb.append(args[a++]);
                i++;
            } else {
                sb.append(pattern.charAt(i));
            }
        }
        return sb.toString();
    }

    /** {@code mac_echo}：内容后接 {@code <br>}。 */
    private static void echo(StringBuilder out, String html) {
        out.append(html).append("<br>");
    }

    /** PHP 8 字符串宽松比较 {@code $a != $b} 的反面（两边都是数字串时按数值）。 */
    static boolean looseEquals(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        try {
            java.util.regex.Pattern n = java.util.regex.Pattern.compile(
                    "[ \\t\\n\\r\\x0B\\f]*[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?[ \\t\\n\\r\\x0B\\f]*");
            if (n.matcher(a).matches() && n.matcher(b).matches()) {
                return new java.math.BigDecimal(a.strip()).compareTo(new java.math.BigDecimal(b.strip())) == 0;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        return false;
    }
}
