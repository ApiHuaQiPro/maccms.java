package com.maccms.backend.content.application;

import com.maccms.backend.content.application.view.AdminRow;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 视频、文章、专题、演员、角色、网址的 {@code saveData} 共有的步骤（B13，22 文档第 4.2 节），每一步与旧模型的写法逐句对应，调用方按旧版的
 * 顺序调用。
 */
final class ContentSaveSteps {

    private ContentSaveSteps() {
    }

    /** {@code $data['type_id_1'] = $type_list[$data['type_id']]['type_pid']}：分类不存在时是 null（写库时 NOT NULL 报错，新版"参数错误"）。 */
    static void typeParent(PhpForm data, Map<Long, AdminRow> typeList) {
        AdminRow t = null;
        if (!data.isArray("type_id") && data.scalar("type_id") != null) {
            Long key = PhpForm.Arr.intKey(new String(data.scalar("type_id"), StandardCharsets.UTF_8));
            t = key == null ? null : typeList.get(key);
        }
        if (t == null) {
            data.putNull("type_id_1");
        } else {
            data.put("type_id_1", t.s("type_pid"));
        }
    }

    /** {@code if(empty($data[en])) $data[en] = Pinyin::get($data[name])}。 */
    static void pinyin(PhpForm data, String en, String name) {
        if (data.empty(en)) {
            data.put(en, LegacyPinyin.get(AdminText.utf8(data, name)));
        }
    }

    /** {@code if(empty($data[letter])) $data[letter] = strtoupper(substr($data[en], 0, 1))}。 */
    static void letter(PhpForm data, String letter, String en) {
        if (data.empty(letter)) {
            AdminText.requireScalar(data, en);
            byte[] e = data.scalar(en);
            data.put(letter, LegacyPinyin.letter(e == null ? new byte[0] : e));
        }
    }

    /** 正文非空时把图片地址里的 {@code 协议:} 换成 {@code mac:}（{@code upload.protocol}）。 */
    static void contentImages(PhpForm data, String content, String protocol) {
        if (!data.empty(content)) {
            AdminText.requireScalar(data, content);
            data.put(content, PhpText.contentImageProtocol(data.scalar(content), protocol));
        }
    }

    /** {@code if(empty($data[blurb])) $data[blurb] = mac_substring(strip_tags($data[content]), 100)}（PHP 7.3 的截断，D-B13-5）。 */
    static void blurb(PhpForm data, String blurb, String content) {
        if (data.empty(blurb)) {
            AdminText.requireScalar(data, content);
            byte[] c = data.scalar(content);
            data.put(blurb, PhpText.macSubstring(PhpText.stripTags(c == null ? new byte[0] : c), 100));
        }
    }

    /** {@code if($data['uptime']==1) $data[time] = time(); unset($data['uptime'])}（不是数字的 uptime 不等于 1）。 */
    static void uptime(PhpForm data, String time, long now) {
        if (!data.isArray("uptime") && PhpLoose.equalsOne(data.scalar("uptime"))) {
            data.put(time, Long.toString(now));
        }
        data.remove("uptime");
    }

    /**
     * {@code if($data['uptag']==1) $data[tag] = mac_get_tag(…)}：仓库里的 {@code mac_get_tag} 已移除外部调用、恒返回 false，写库为空串
     * （application/common.php:945-948）；{@code unset($data['uptag'])}。
     */
    static void uptag(PhpForm data, String tag) {
        if (!data.isArray("uptag") && PhpLoose.equalsOne(data.scalar("uptag"))) {
            data.put(tag, "");
        }
        data.remove("uptag");
    }

    /** 列出的字段（存在且不是 null 时）{@code mac_filter_xss}。 */
    static void filterXss(PhpForm data, List<String> fields) {
        for (String f : fields) {
            if (data.has(f) && !data.isNull(f)) {
                AdminText.requireScalar(data, f);
                data.put(f, PhpText.filterXss(data.scalar(f)));
            }
        }
    }
}
