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
 * <li>{@code cj}：规则采集的一轮（旧 {@code Cj::col_all}，B20 的 {@code CjColAll}，由装配处给出；页面来自本地 HTML 目录）。</li>
 * <li>{@code collect}：定时资源站采集不提供（2026-10-28 用户决定，27 文档 D-B19-15），固定输出"该功能未在新系统中提供"，不执行任何采集
 * （任务已按时间表记为执行过，同旧版"先写 runtime 再执行"）。</li>
 * <li>{@code urlsend}：URL 推送（B28，{@link UrlsendService} 的定时任务入口，同旧 {@code Timming::urlsend} 调后台 {@code Urlsend::push}，
 * 一次推完所有页）；推送不可用（没有数据源）时输出"参数错误"（同旧版推送方式不存在时）。</li>
 * <li>其他：旧版调用不存在的方法而报错；新版输出"参数错误"。</li>
 * </ul>
 */
public final class TimmingTasks implements TimmingService.TaskRunner {

    /** 与后台 {@code collect/timing} 相同的固定提示（D-B19-15）。 */
    static final String NOT_PROVIDED = "该功能未在新系统中提供";

    /** 定时任务 {@code cj}：按 {@code parse_str} 后的参数执行一轮规则采集，返回输出；采集模块未启用时返回 null。 */
    @FunctionalInterface
    public interface CjTask {
        String run(Map<String, String> param);
    }

    private final MakeService make;
    private final Runnable clearCache;
    private final CjTask cj;
    private final UrlsendService urlsend;
    private final LegacyLang lang;
    private final boolean secure;

    public TimmingTasks(MakeService make, Runnable clearCache, CjTask cj, UrlsendService urlsend, LegacyLang lang) {
        this(make, clearCache, cj, urlsend, lang, false);
    }

    private TimmingTasks(MakeService make, Runnable clearCache, CjTask cj, UrlsendService urlsend, LegacyLang lang,
                         boolean secure) {
        this.make = make;
        this.clearCache = clearCache;
        this.cj = cj;
        this.urlsend = urlsend;
        this.lang = lang;
        this.secure = secure;
    }

    @Override
    public TimmingService.TaskRunner forRequest(boolean secure) {
        return new TimmingTasks(make, clearCache, cj, urlsend, lang, secure);
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
            case "cj" -> {
                String out = cj.run(parseStr(param));
                yield out == null ? line("规则采集不可用") : out;
            }
            case "collect" -> line(NOT_PROVIDED);
            case "urlsend" -> {
                if (urlsend == null) {
                    yield line(lang.get("param_err"));
                }
                UrlsendService.Output o = urlsend.push(parseStr(param), secure ? "https://" : "http://",
                        UrlsendService.Entrance.API);
                yield o.error() == null ? o.html() : line(o.error());
            }
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
