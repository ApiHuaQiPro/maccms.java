package com.maccms.backend.admin.web.view.collect;

import com.maccms.backend.admin.application.LegacyAdminUrl;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.collect.application.CollectPreviewService;
import com.maccms.backend.collect.application.ProvideKind;
import com.maccms.backend.collect.application.CollectPreviewService.LocalFile;
import com.maccms.backend.common.lang.LegacyLang;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源库预览（B18，26 文档第 6 节；旧 {@code admin/view/collect/{vod,art,actor,role,website}.html}）。五个旧模板只有绑定弹窗的
 * {@code tab}、表头与各列取的字段不同，新版合成一个模板，差别由本类按种类给出。新版另在页首加了"本地文件"一栏（载入、重新载入、清除预览缓存），
 * 旧版没有（旧版直接请求资源站）。
 *
 * @param param   旧 {@code $param}（{@code input()}，{@code pg} 已换成 {@code page}）
 * @param preview 已载入时的预览；没有缓存时为 null（只显示"本地文件"一栏）
 * @param now     当前时间（{@code mac_day} 判断"今天"）
 */
public record CollectApiView(AdminHead head, LegacyLang lang, LegacyAdminUrl url, ProvideKind kind, Map<String, String> param,
                             CollectPreviewService.Preview preview, boolean localConfigured, List<LocalFile> localFiles,
                             long maxBytes, String maxUpload, Instant now) {

    private static final ZoneId PRC = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public String p(String key) {
        String v = param.get(key);
        return v == null ? "" : v;
    }

    /** 旧 {@code curUrl="{:url('api')}?{$param_str}"}：{@code page}、{@code limit} 为占位符。 */
    public String curUrl() {
        Map<String, String> q = new LinkedHashMap<>(param);
        q.put("page", "{page}");
        q.put("limit", "{limit}");
        return url.build("api") + "?" + LegacyAdminUrl.httpBuildQuery(q);
    }

    /** 采集按钮的地址（旧 {@code {:url('api')}?{$p1_str}&...}，{@code p1} 是去掉 {@code ac} 的参数）。 */
    public String collectUrl(String extra) {
        Map<String, String> q = new LinkedHashMap<>(param);
        q.remove("ac");
        q.put("page", "{page}");
        q.put("limit", "{limit}");
        return url.build("api") + "?" + LegacyAdminUrl.httpBuildQuery(q) + extra;
    }

    /** 本页的"本地文件"动作（{@code ac=localload}、{@code localclear}）与预览（{@code ac=list}）的地址，带着同样的资源站参数。 */
    public String actionUrl(String ac) {
        Map<String, String> q = new LinkedHashMap<>(param);
        q.put("ac", ac);
        q.remove("page");
        q.remove("limit");
        return url.build("api") + "?" + LegacyAdminUrl.httpBuildQuery(q);
    }

    /** 分类绑定弹窗的地址（旧 {@code {:url('index/select')}?tab=…&col=cjflag_type_id&ids=1&tpl=select_type&refresh=no&url=collect/bind}）。 */
    public String selectHref(String typeId) {
        // 旧模板原样拼接；新版对取自请求的 cjflag 与文件里的分类编号做 URL 编码（规范的 md5 与数字编号不变）
        return url.build("index/select") + "?tab=" + kind.code() + "&col="
                + java.net.URLEncoder.encode(p("cjflag") + "_" + typeId, java.nio.charset.StandardCharsets.UTF_8)
                + "&ids=1&tpl=select_type&refresh=no&url=collect/bind";
    }

    public String bindId(String typeId) {
        return p("cjflag") + "_" + typeId;
    }

    /** 旧模板 {@code {$vo.<kind>_id}}。 */
    public String idField() {
        return kind.code() + "_id";
    }

    /** 表头（第 3、4 列）的语言包键与宽度：角色是"影片""演员"，其他是"分类"与"来源/性别/地区"。 */
    public String head3() {
        return kind == ProvideKind.ROLE ? "vod" : "type";
    }

    public String head3Width() {
        return kind == ProvideKind.ROLE ? "200" : "60";
    }

    public String head4() {
        return switch (kind) {
            case ACTOR -> "sex";
            case ROLE -> "actor";
            case WEBSITE -> "area";
            default -> "from";
        };
    }

    public String head4Width() {
        return kind == ProvideKind.ROLE ? "100" : "60";
    }

    /** 第 3、4 列取的字段。 */
    public String col3() {
        return kind == ProvideKind.ROLE ? "vod_name" : "type_name";
    }

    public String col4() {
        return switch (kind) {
            case VOD -> "vod_play_from";
            case ART -> "art_from";
            case ACTOR -> "actor_sex";
            case ROLE -> "role_actor";
            case WEBSITE -> "website_area";
            case COMMENT -> "comment_name";
        };
    }

    public String timeField() {
        return kind.code() + "_time";
    }

    /** 旧 {@code mac_day($t, 'color')} 的文字：空时空串；数字按北京时间格式化。 */
    public String day(String t) {
        if (t == null || t.isEmpty() || t.equals("0")) {
            return "";
        }
        if (t.matches("\\s*[+-]?\\d+(\\.\\d+)?\\s*")) {
            long sec = (long) Double.parseDouble(t.strip());
            return DATE_TIME.format(LocalDateTime.ofInstant(Instant.ofEpochSecond(sec), PRC));
        }
        return t;
    }

    /** {@code mac_day} 是否标红（文字含今天的日期）。 */
    public boolean today(String t) {
        String d = day(t);
        return !d.isEmpty() && d.contains(now.atZone(PRC).toLocalDate().toString());
    }

    /** 绑定弹窗回调（{@code onSubmitResult}）的写法：视频与文章显示绑定的分类名，其他显示"取消绑定"（旧模板各自的写法）。 */
    public String bindStyle() {
        return kind == ProvideKind.VOD || kind == ProvideKind.ART ? "name" : "unbind";
    }

    public String loadedAt() {
        return preview == null ? "" : DATE_TIME.format(LocalDateTime.ofInstant(preview.loadedAt(), PRC));
    }

    public String size(long bytes) {
        return bytes < 1024 ? bytes + " B" : bytes < 1024 * 1024 ? (bytes / 1024) + " KB" : String.format("%.1f MB", bytes / 1048576.0);
    }
}
