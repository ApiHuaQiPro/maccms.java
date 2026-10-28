package com.maccms.backend.admin.web.view.collect;

import com.maccms.backend.admin.application.LegacyAdminUrl;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.content.BrowserText;
import com.maccms.backend.admin.web.view.content.TypeListView;
import com.maccms.backend.collect.application.CollectBindings;
import com.maccms.backend.collect.application.ProvideKind;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集源列表（旧 admin/view/collect/index.html）与断点采集页（旧 union.html）。断点采集的按钮按断点是否存在显示（B19 增量 3，断点在入库时记下）。
 *
 * @param breakpoints 有断点的种类（旧 {@code $collect_break_<种类> neq ''}），按视频、文章、明星、角色、网址的顺序
 */
public record CollectListView(AdminHead head, LegacyLang lang, LegacyAdminUrl url, AdminRowPage list, List<ProvideKind> breakpoints)
        implements BrowserText {

    /** 断点采集按钮的文字（旧模板写死的中文）。 */
    private static final Map<ProvideKind, String> BREAK_LABELS = Map.of(ProvideKind.VOD, "【进入视频断点采集】", ProvideKind.ART,
            "【进入文章断点采集】", ProvideKind.ACTOR, "【进入明星断点采集】", ProvideKind.ROLE, "【进入角色断点采集】", ProvideKind.WEBSITE,
            "【进入网址断点采集】");

    /** 旧 {@code {:url('load')}?flag=<种类>}。 */
    public String breakHref(ProvideKind kind) {
        return url.build("load") + "?flag=" + kind.code();
    }

    public String breakLabel(ProvideKind kind) {
        return BREAK_LABELS.get(kind);
    }

    /** 旧 {@code {:url('api')}?{:http_build_query([...])}}：进入资源库（{@code ac=list}）与采集当天/本周/所有（{@code ac=cj}）的地址。 */
    public String api(AdminRow vo, String ac, String h) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("ac", ac);
        q.put("cjflag", CollectBindings.flag(vo.s("collect_url")));
        q.put("cjurl", vo.s("collect_url"));
        q.put("h", h);
        q.put("t", "");
        q.put("ids", "");
        q.put("wd", "");
        q.put("type", vo.s("collect_type"));
        q.put("mid", vo.s("collect_mid"));
        q.put("opt", vo.s("collect_opt"));
        q.put("sync_pic_opt", vo.s("collect_sync_pic_opt"));
        q.put("filter", vo.s("collect_filter"));
        q.put("filter_from", vo.s("collect_filter_from"));
        q.put("param", Base64.getEncoder().encodeToString(vo.s("collect_param").getBytes(StandardCharsets.UTF_8)));
        return url.build("api") + "?" + LegacyAdminUrl.httpBuildQuery(q);
    }

    /** 接口类型：{@code collect_type eq 1} 为 xml，否则 json。 */
    public String typeText(AdminRow vo) {
        return vo.n("collect_type") == 1 ? "xml" : "json";
    }

    /** 旧 {@code mac_get_mid_text}。 */
    public String midText(AdminRow vo) {
        return TypeListView.midText(lang, vo.n("collect_mid"));
    }
}
