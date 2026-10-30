package com.maccms.backend.admin.web.view.content;

import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.site.application.LegacyPage;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.SiteRoute;

/**
 * 后台列表里指向前台的链接（旧 {@code mac_url_type}、{@code mac_url_*_detail}，application/common.php，22 文档第 8.3 节）：由站点模块的
 * 纯函数 {@link LegacyUrlBuilder} 按旧配置的地址形态生成（只支持数字编号的地址；静态页地址在 B16），前面加部署前缀与 {@code /}（旧
 * {@code MAC_PATH}）。
 */
public final class FrontLinks {

    private final LegacyUrlBuilder builder;
    private final String prefix;

    public FrontLinks(LegacyUrlBuilder builder, String sitePath) {
        this.builder = builder;
        this.prefix = sitePath + "/";
    }

    /** {@code mac_url_type($info)}：按 type_mid 取 vod/art/actor/website 的分类页（其他取值按视频，旧版如此）。 */
    public String type(AdminRow t) {
        long id = t.n("type_id");
        if (id < 1 || id > 65535) {
            return prefix + builder.bare("vod/type");
        }
        return prefix + switch ((int) t.n("type_mid")) {
            case 2 -> builder.legacy(SiteRoute.Legacy.of(LegacyPage.ART_TYPE, id));
            case 8 -> builder.legacy(SiteRoute.Legacy.of(LegacyPage.ACTOR_TYPE, id));
            case 11 -> builder.legacy(SiteRoute.Legacy.of(LegacyPage.WEBSITE_TYPE, id));
            default -> builder.vodType((int) id, 1);
        };
    }

    public String vod(AdminRow r) {
        long id = r.n("vod_id");
        return prefix + (id < 1 ? builder.bare("vod/detail") : builder.vodDetail(id));
    }

    /** {@code mac_url_vod_detail($vo.data)}：所属视频不存在时是没有编号的地址。 */
    public String vodId(String id) {
        long v;
        try {
            v = id == null || id.isEmpty() ? 0 : Long.parseLong(id);
        } catch (NumberFormatException e) {
            v = 0;
        }
        return prefix + (v < 1 ? builder.bare("vod/detail") : builder.vodDetail(v));
    }

    public String art(AdminRow r) {
        return detail(LegacyPage.ART_DETAIL, r.n("art_id"), "art/detail");
    }

    public String topic(AdminRow r) {
        return detail(LegacyPage.TOPIC_DETAIL, r.n("topic_id"), "topic/detail");
    }

    public String actor(AdminRow r) {
        return detail(LegacyPage.ACTOR_DETAIL, r.n("actor_id"), "actor/detail");
    }

    public String role(AdminRow r) {
        return detail(LegacyPage.ROLE_DETAIL, r.n("role_id"), null);
    }

    public String website(AdminRow r) {
        return detail(LegacyPage.WEBSITE_DETAIL, r.n("website_id"), null);
    }

    private String detail(LegacyPage page, long id, String bare) {
        if (id < 1) {
            return prefix + (bare == null ? "" : builder.bare(bare));
        }
        return prefix + builder.legacy(SiteRoute.Legacy.of(page, id));
    }
}
