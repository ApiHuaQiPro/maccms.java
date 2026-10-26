package com.maccms.backend.admin.web.view.content;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 旧 {@code mac_url_img}/{@code mac_url_content_img} 用到的上传配置（{@code upload.protocol/mode/remoteurl/img_key/img_api}）与站点路径
 * （旧 {@code MAC_PATH}）。
 */
public record ImgConfig(String protocol, String mode, String remoteUrl, String imgKey, String imgApi, String sitePath) {

    /**
     * 旧 {@code mac_url_img($url)}（application/common.php:1633-1655）在浏览器里得到的地址：{@code mac:} 换成协议；不以 http、//、/ 开头的
     * 相对地址前面加远程地址（mode=remote）或站点路径；匹配 img_key 时前面加 img_api；最后经 {@code mac_filter_xss} 并把 {@code &amp;}
     * 换回 {@code &}（旧版原样输出到 src，浏览器再解码一次；新版先算出解码后的地址，由模板转义输出）。
     */
    public String url(String url) {
        String u = url == null ? "" : url;
        if (u.startsWith("mac:")) {
            String p = protocol == null || protocol.isEmpty() ? "http" : protocol;
            u = u.replace("mac:", p + ":");
        } else if (!u.startsWith("http") && !u.startsWith("//") && !u.startsWith("/")) {
            u = "remote".equals(mode) ? remoteUrl + u : sitePath + "/" + u;
        } else if (imgKey != null && !imgKey.isEmpty() && matches(imgKey, u)) {
            u = imgApi + u;
        }
        String filtered = new String(com.maccms.backend.content.application.PhpText.filterXss(
                u.getBytes(java.nio.charset.StandardCharsets.UTF_8)), java.nio.charset.StandardCharsets.UTF_8);
        filtered = filtered.replace("&quot;&gt;", "").replace("&amp;", "&");
        return decode(filtered);
    }

    private static boolean matches(String key, String url) {
        try {
            return Pattern.compile(key).matcher(url).find();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /** 浏览器对属性值做的一次 HTML 解码（只涉及 htmlspecialchars 产生的几种写法）。 */
    static String decode(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#039;", "'")
                .replace("&amp;", "&");
    }
}
