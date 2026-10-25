package com.maccms.backend.site.web;

import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.SafeUrl;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.web.view.ContentForm;
import com.maccms.backend.site.web.view.HiddenField;
import com.maccms.backend.site.web.view.InlineForm;
import com.maccms.backend.site.web.view.InteractionPanel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 内容页上的会员表单（B10，19 文档第 5.2、5.4 节）：普通 POST，提交后是提示页。 */
final class ContentForms {

    private ContentForms() {
    }

    /** "收藏"：{@code user/ajax_ulog}，{@code ac=set}、{@code type=2}。 */
    static ContentForm favorite(SiteSettings site, LegacyUrlBuilder urls, int mid, long id) {
        return new ContentForm(site.publicPath(urls.user("ajax_ulog")), List.of(new HiddenField("ac", "set"),
                new HiddenField("mid", Integer.toString(mid)), new HiddenField("id", Long.toString(id)),
                new HiddenField("type", "2")), "收藏");
    }

    /**
     * 内容详情页的互动区（B11，20 文档第 8 节）：点击计数（{@code ajax/hits?type=update}，页内不显示的图片请求）、顶与踩（{@code ajax/digg}）、
     * 评分（{@code ajax/score}，1～10 分）与评论页链接（{@code comment/index}）。
     */
    static InteractionPanel interaction(SiteSettings site, LegacyUrlBuilder urls, int mid, long id) {
        String m = Integer.toString(mid);
        String i = Long.toString(id);
        SafeUrl hits = site.publicPath(urls.action("ajax/hits", Map.of("mid", m, "id", i, "type", "update")));
        SafeUrl digg = site.publicPath(urls.action("ajax/digg", Map.of()));
        InlineForm up = new InlineForm(digg, List.of(new HiddenField("mid", m), new HiddenField("id", i),
                new HiddenField("type", "up")), "顶");
        InlineForm down = new InlineForm(digg, List.of(new HiddenField("mid", m), new HiddenField("id", i),
                new HiddenField("type", "down")), "踩");
        List<Integer> options = new ArrayList<>();
        for (int s = 1; s <= 10; s++) {
            options.add(s);
        }
        return new InteractionPanel(hits, up, down, site.publicPath(urls.action("ajax/score", Map.of())),
                List.of(new HiddenField("mid", m), new HiddenField("id", i)), options,
                Optional.of(site.publicPath(urls.action("comment/index", Map.of("mid", m, "rid", i)))));
    }

    /** "确认支付"：{@code user/ajax_buy_popedom}。 */
    static ContentForm buy(SiteSettings site, LegacyUrlBuilder urls, int mid, long id, int sid, int nid, int type) {
        return new ContentForm(site.publicPath(urls.user("ajax_buy_popedom")), List.of(
                new HiddenField("mid", Integer.toString(mid)), new HiddenField("id", Long.toString(id)),
                new HiddenField("sid", Integer.toString(sid)), new HiddenField("nid", Integer.toString(nid)),
                new HiddenField("type", Integer.toString(type))), "确认支付");
    }
}
