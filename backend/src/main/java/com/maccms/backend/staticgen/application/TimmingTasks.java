package com.maccms.backend.staticgen.application;

import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.common.web.PhpInput;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 定时任务的各类任务（旧 Timming.php:66-108 的 {@code collect}、{@code make}、{@code cj}、{@code cache}、{@code urlsend}，B16，25 文档第 7.3 节）。
 * 任务参数按 PHP {@code parse_str} 解析。
 *
 * <ul>
 * <li>{@code make}：静态生成（{@link MakeService}，定时任务入口）。</li>
 * <li>{@code cache}：同后台"清理缓存"（{@code Runnable}，由装配处给出：重写 playerconfig.js 的缓存段、清理过期的视频搜索缓存）。</li>
 * <li>{@code collect}、{@code cj}：采集与规则采集在 B17～B20 实现；本批输出"未执行"的提示（任务已按时间表记为执行过，同旧版"先写 runtime 再执行"）。</li>
 * <li>{@code urlsend}：推送在 B28 接入；同上。</li>
 * <li>其他：旧版调用不存在的方法而报错；新版输出"参数错误"。</li>
 * </ul>
 */
public final class TimmingTasks implements TimmingService.TaskRunner {

    private final MakeService make;
    private final Runnable clearCache;
    private final LegacyLang lang;

    public TimmingTasks(MakeService make, Runnable clearCache, LegacyLang lang) {
        this.make = make;
        this.clearCache = clearCache;
        this.lang = lang;
    }

    @Override
    public String run(String file, String param) {
        return switch (file) {
            case "make" -> {
                if (make == null) {
                    yield line("静态生成不可用");
                }
                MakeOutput o = make.run("make", parseStr(param), MakeService.Entrance.API, null);
                yield o.html() + (o.error() == null ? "" : line(o.error()));
            }
            case "cache" -> {
                clearCache.run();
                yield line(lang.get("admin/index/clear_ok"));
            }
            case "collect", "cj" -> line("采集任务由后续批次（B17～B20）提供，本次未执行");
            case "urlsend" -> line("推送任务由后续批次（B28）提供，本次未执行");
            default -> line(lang.get("param_err"));
        };
    }

    private static String line(String text) {
        return Html.escape(text) + "<br>";
    }

    /** PHP {@code parse_str}：数组参数（{@code a[]=1}）在本批的任务参数里不使用，按空串处理。 */
    static Map<String, String> parseStr(String param) {
        Map<String, String> out = new LinkedHashMap<>();
        PhpInput.parse(param.getBytes(StandardCharsets.UTF_8)).forEach((k, v) -> out.put(k, v.array() ? "" : v.text()));
        return out;
    }
}
