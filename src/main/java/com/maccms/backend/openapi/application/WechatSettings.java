package com.maccms.backend.openapi.application;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import com.maccms.backend.site.application.LegacyUrlStyle;
import java.util.List;

/**
 * 微信公众号接口（B27，docs/architecture/29-b27-wechat.md 第 3 节）用到的旧配置 {@code extra/maccms.php}：{@code weixin} 组
 * （api/controller/Wechat.php、common/util/WechatPublic.php），以及生成链接用的 {@code rewrite.*}、{@code app.pathinfo_depr}
 * 与图片地址用的 {@code upload.protocol/img_key/img_api}。启动时读一次，改了要重启（D-B15-18）。
 *
 * <p>取值一律按 PHP {@code (string)} 保存原文，比较规则在使用处按旧代码实现。{@code token} 不出现在 {@link #toString()}。
 *
 * @param open         {@code weixin.status} 不按 PHP 7 宽松比较等于 0（{@code $config['status'] ==0} 时输出 closed）
 * @param token        接入验证的令牌
 * @param sousuo       链接前缀，已按旧版补 {@code http://}
 * @param keywords     关键词回复 1～4
 * @param urlStyle     链接形态；配置不受支持时为仓库默认形态（{@link #urlStyleSupported} 为假）
 * @param imgKey       {@code upload.img_key}（正则，匹配时图片地址前加 {@code img_api}）
 */
public record WechatSettings(boolean open, String token, String guanzhu, String wuziyuan, String wuziyuanlink,
                             String sousuo, String bofang, String msgtype, List<Keyword> keywords,
                             LegacyUrlStyle urlStyle, boolean urlStyleSupported, String uploadProtocol, String imgKey,
                             String imgApi) {

    /** 关键词回复：命中 {@code gjc} 时回复标题 {@code gjcm}、图片 {@code gjci}、链接 {@code gjcl}。 */
    public record Keyword(String word, String title, String pic, String link) {
    }

    public WechatSettings {
        keywords = List.copyOf(keywords);
    }

    /** 未配置旧配置目录时：接口关闭（与 {@code weixin.status=0} 相同，输出 closed）。 */
    public static WechatSettings closed() {
        return new WechatSettings(false, "", "", "", "", "http://", "0", "0", List.of(), LegacyUrlStyle.REPO_DEFAULT, true,
                "http", "", "");
    }

    public static WechatSettings from(PhpArray maccms) {
        PhpValue w = maccms.get("weixin");
        PhpArray g = w instanceof PhpArray a ? a : new PhpArray(java.util.Map.of());
        String sousuo = text(g, "sousuo");
        if (!sousuo.startsWith("http")) {
            // WechatPublic.php:100-102：substr($sousuo, 0, 4) != 'http' 时前面加 http://
            sousuo = "http://" + sousuo;
        }
        List<Keyword> kw = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            kw.add(new Keyword(text(g, "gjc" + i), text(g, "gjcm" + i), text(g, "gjci" + i), text(g, "gjcl" + i)));
        }
        LegacyUrlStyle style;
        boolean supported = true;
        try {
            String depr = text(maccms, "app", "pathinfo_depr");
            style = new LegacyUrlStyle(WechatPhp.number(text(maccms, "rewrite", "status")) == 1,
                    WechatPhp.number(text(maccms, "rewrite", "suffix_hide")) == 1,
                    WechatPhp.number(text(maccms, "rewrite", "route_status")) == 1, depr.isEmpty() ? '/' : depr.charAt(0));
        } catch (IllegalArgumentException e) {
            style = LegacyUrlStyle.REPO_DEFAULT;
            supported = false;
        }
        return new WechatSettings(WechatPhp.number(text(g, "status")) != 0, text(g, "token"), text(g, "guanzhu"),
                text(g, "wuziyuan"), text(g, "wuziyuanlink"), sousuo, text(g, "bofang"), text(g, "msgtype"), kw, style,
                supported, text(maccms, "upload", "protocol"), text(maccms, "upload", "img_key"),
                text(maccms, "upload", "img_api"));
    }

    private static String text(PhpArray root, String... path) {
        PhpValue v = root.path(path);
        return v instanceof PhpArray ? "Array" : PhpValues.text(v);
    }

    @Override
    public String toString() {
        return "WechatSettings[open=" + open + ", bofang=" + bofang + ", msgtype=" + msgtype + ", token=***]";
    }
}
