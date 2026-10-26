package com.maccms.backend.admin.web.view.content;

import com.maccms.backend.admin.application.LegacyAdminUrl;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminTypeNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 内容表单页（旧 admin/view/{topic,actor,role,website,art,vod,comment,gbook,annex}/info.html）共用的视图模型。
 *
 * @param info   编辑的行（新增时为空行）
 * @param img    图片地址相关的旧配置（{@code mac_url_img}、{@code mac_url_content_img}）
 * @param editor 富文本编辑器的地址参数（旧 public/editor.html 的 {@code flag}，如 {@code topic_editor}）
 * @param types  分类树（旧 {@code type_tree}）
 * @param extra  各页面另用的数据
 */
public record ContentFormView(AdminHead head, LegacyLang lang, LegacyAdminUrl url, AdminRow info, ImgConfig img,
                              String editor, List<AdminTypeNode> types, Map<String, Object> extra) {

    /** 截图的一项（旧 {@code mac_screenshot_list}）。 */
    public record Shot(String name, String url) {
    }

    /** 旧 {@code mac_default($s, $def)}：{@code empty($s)} 时取默认值。 */
    public String def(String s, String d) {
        return s == null || s.isEmpty() || s.equals("0") ? d : s;
    }

    /** 旧 {@code mac_url_content_img($content)}：{@code mac:} 换成 {@code 协议:}（img_key 代理不改写正文，路线图第 4.2 节 W2）。 */
    public String contentImg(String content) {
        String p = img.protocol() == null || img.protocol().isEmpty() ? "http" : img.protocol();
        return content.replace("mac:", p + ":");
    }

    /** 旧 {@code mac_url_img($url)} 在浏览器里得到的地址。 */
    public String imgUrl(String url) {
        return img.url(url);
    }

    /** 旧 {@code mac_str_correct($s, '#', chr(13))}：截图文本框里把 {@code #} 换成回车。 */
    public String strCorrect(String s) {
        return s.replace("#", "\r");
    }

    /** 旧 {@code mac_screenshot_list($info.x_pic_screenshot)}：按 {@code #} 拆分、空项跳过，每项 {@code 标题$地址} 或只有地址。 */
    public List<Shot> shots(String raw) {
        List<Shot> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        String[] items = raw.split("#", -1);
        for (int i = 0; i < items.length; i++) {
            String v = items[i];
            if (v.isEmpty() || v.equals("0")) {
                continue;
            }
            String[] parts = v.split("\\$", -1);
            String title = parts[0];
            String u = parts.length > 1 ? parts[1] : "";
            if (u.isEmpty() || u.equals("0")) {
                out.add(new Shot(Integer.toString(i + 1), title));
            } else {
                out.add(new Shot(title, u));
            }
        }
        return out;
    }

    /** 文章的一页（旧 {@code mac_art_list}）。 */
    public record ArtPage(int page, String title, String note, String content) {
    }

    /**
     * 旧 {@code mac_art_list($art_title, $art_note, $art_content)}（application/common.php）：三列各按 {@code $$$} 拆分（为 PHP
     * {@code empty()} 时为空），页数按正文，标题、注释缺的一页为空串。
     */
    public List<ArtPage> artPages() {
        List<String> titles = explode(info.s("art_title"));
        List<String> notes = explode(info.s("art_note"));
        List<String> contents = explode(info.s("art_content"));
        List<ArtPage> out = new ArrayList<>();
        for (int k = 0; k < contents.size(); k++) {
            out.add(new ArtPage(k + 1, k < titles.size() ? titles.get(k) : "", k < notes.size() ? notes.get(k) : "",
                    contents.get(k)));
        }
        return out;
    }

    private static List<String> explode(String s) {
        if (s == null || s.isEmpty() || s.equals("0")) {
            return List.of();
        }
        return List.of(s.split(java.util.regex.Pattern.quote("$$$"), -1));
    }

    /** 旧 {@code $info.col eq n}（行里没有这一列时按 null：只等于 0）。 */
    public boolean is(String col, long n) {
        return TypeInfoView.looseEq(info.has(col) ? info.s(col) : null, n);
    }

    /** 旧 {@code $info.col eq 'v'}（行里没有这一列时为 null）。 */
    public boolean eq(String col, String v) {
        if (!info.has(col)) {
            // PHP null == 'v'：null 按空串比较
            return v.isEmpty();
        }
        return TypeInfoView.looseEqStr(info.s(col), v);
    }

    /** 编辑已有的行（旧模板里 {@code $info.x_id gt 0} 的分支）。 */
    public boolean editing(String pk) {
        return info.n(pk) > 0;
    }

    /** 页面脚本用到的选项（播放器、下载器、服务器组的 {@code from}、{@code show}），JSON 数组，放在 script 标签的 data-* 属性里。 */
    public static String options(List<Map<String, String>> list) {
        List<Object> out = new ArrayList<>();
        for (Map<String, String> m : list) {
            Map<String, Object> o = new java.util.LinkedHashMap<>();
            o.put("from", m.get("from") == null ? "" : m.get("from"));
            o.put("show", m.get("show") == null ? "" : m.get("show"));
            out.add(o);
        }
        return com.maccms.backend.common.web.LegacyJson.encode(out);
    }

    /** 页面脚本用到的语言包文字（键 → 文字），JSON 对象，放在 script 标签的 data-* 属性里。 */
    public String langJson(String... keys) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (String k : keys) {
            m.put(k, lang.get(k));
        }
        return com.maccms.backend.common.web.LegacyJson.encode(m);
    }

    /** 另用的列表数据的条数（没有时为 0）。 */
    public int count(String key) {
        return extra.get(key) instanceof java.util.Collection<?> c ? c.size() : 0;
    }

    /** 另用的文字数据（没有时为空串）。 */
    public String xs(String key) {
        Object v = extra.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    public <T> T x(String key) {
        return (T) extra.get(key);
    }
}
