package com.maccms.backend.admin.web.view.collect;

import com.maccms.backend.admin.application.LegacyAdminUrl;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.content.BrowserText;
import com.maccms.backend.admin.web.view.content.TypeListView;
import com.maccms.backend.collect.application.CollectBindings;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 采集源列表（旧 admin/view/collect/index.html）。断点采集的按钮（旧版按缓存里的断点地址显示）在 B19 接上（断点只在入库时记下），
 * 现在没有断点，不显示。
 */
public record CollectListView(AdminHead head, LegacyLang lang, LegacyAdminUrl url, AdminRowPage list) implements BrowserText {

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
