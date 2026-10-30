package com.maccms.backend.site.application;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.util.regex.Pattern;

/**
 * 旧搜索频率限制 {@code app.search_timespan}（B08，17 文档第 2.5 节）：{@code check_search}（{@code index/controller/Base.php:59-62}）
 * 在<b>第 1 页</b>时调用 {@code mac_get_time_span("last_searchtime")}，结果小于该值就报"请不要频繁操作"。视频与各模块的搜索页、
 * ajax_search 都经过这一步（旧版先判断搜索开关与 ajax 开关，再判断频率，最后才是验证码）。
 *
 * <p>取值：旧配置的数值或数字字符串（仓库值 {@code '3'}）；空串、非数字或不大于 0 时不限制（旧版 {@code 时间差 < 0/''} 恒不成立；
 * 非数字串在 PHP 8 下按字符串比较，属于配置错误，新版按不限制处理）。没有配置旧配置目录时不限制。
 *
 * @param seconds 两次第 1 页搜索之间至少间隔的秒数；0 表示不限制
 */
public record SearchTimespan(double seconds) {

    public static final SearchTimespan OFF = new SearchTimespan(0);

    private static final Pattern NUMERIC = Pattern.compile("\\s*[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?\\s*");

    public static SearchTimespan from(PhpArray maccms) {
        if (maccms == null) {
            return OFF;
        }
        PhpValue v = maccms.path("app", "search_timespan");
        double s = 0;
        if (v instanceof PhpValue.PhpInt i) {
            s = i.value();
        } else if (v instanceof PhpValue.PhpFloat f) {
            s = f.value();
        } else if (v instanceof PhpValue.PhpString str && NUMERIC.matcher(str.value()).matches()) {
            s = Double.parseDouble(str.value().strip());
        }
        return s > 0 && Double.isFinite(s) ? new SearchTimespan(s) : OFF;
    }

    public boolean enabled() {
        return seconds > 0;
    }

    /** 旧 {@code mac_get_time_span(...) < search_timespan}。 */
    public boolean tooSoon(long span) {
        return span < seconds;
    }

    /** 提示文案：旧 {@code lang('search_frequently') . search_timespan . lang('seconds')}（zh-cn 语言包原文，含"秒前"）。 */
    public String message() {
        long whole = (long) seconds;
        String n = whole == seconds ? Long.toString(whole) : Double.toString(seconds);
        return "请不要频繁操作，搜索时间间隔为" + n + "秒前";
    }

    /** 建议的重试间隔（秒，向上取整，至少 1）。 */
    public long retryAfterSeconds() {
        return Math.max(1, (long) Math.ceil(seconds));
    }
}
