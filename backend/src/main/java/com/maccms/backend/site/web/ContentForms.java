package com.maccms.backend.site.web;

import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.web.view.ContentForm;
import com.maccms.backend.site.web.view.HiddenField;
import java.util.List;

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

    /** "确认支付"：{@code user/ajax_buy_popedom}。 */
    static ContentForm buy(SiteSettings site, LegacyUrlBuilder urls, int mid, long id, int sid, int nid, int type) {
        return new ContentForm(site.publicPath(urls.user("ajax_buy_popedom")), List.of(
                new HiddenField("mid", Integer.toString(mid)), new HiddenField("id", Long.toString(id)),
                new HiddenField("sid", Integer.toString(sid)), new HiddenField("nid", Integer.toString(nid)),
                new HiddenField("type", Integer.toString(type))), "确认支付");
    }
}
