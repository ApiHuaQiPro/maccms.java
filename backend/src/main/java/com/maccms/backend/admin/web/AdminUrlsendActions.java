package com.maccms.backend.admin.web;

import com.maccms.backend.admin.application.AdminSettings;
import com.maccms.backend.admin.application.LegacyAdminUrl;
import com.maccms.backend.admin.web.AdminController.Ctx;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.integration.application.BacklinkPages;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import com.maccms.backend.staticgen.application.UrlsendService;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

/**
 * B28 的后台动作（30 文档第 5 节）：
 * <ul>
 * <li>{@code urlsend/push}：旧 {@code Urlsend::push}，推送一页（{@link UrlsendService}），不是最后一页时跳到下一页（旧 {@code mac_jump}，
 * 改为 progress.js，同 B16）；推送方式不存在时"参数错误"；</li>
 * <li>{@code urlsend/data}：旧 {@code Urlsend::data}，只列出本页地址；</li>
 * <li>{@code index/check_back_link}：旧 {@code Index::check_back_link} → {@code mac_check_back_link}（application/common.php:432-473），
 * 抓取友链页面（{@link BacklinkPages}），依次查找 {@code site_url}、{@code site_wapurl}，返回 {@code {"code":…,"msg":…}}。</li>
 * </ul>
 * 地址前缀的协议（旧 {@code $GLOBALS['http_type']}）按后台对外地址 {@code maccms.admin.base-url} 是否为 https。
 */
public class AdminUrlsendActions {

    private final AdminEndpoint endpoint;
    private final AdminSettings settings;
    private final AdminResponses responses;
    private final ObjectProvider<UrlsendService> urlsend;
    private final ObjectProvider<BacklinkPages> pages;
    private final LegacyConfigService legacy;

    public AdminUrlsendActions(AdminEndpoint endpoint, AdminSettings settings, AdminResponses responses,
                               ObjectProvider<UrlsendService> urlsend, ObjectProvider<BacklinkPages> pages,
                               LegacyConfigService legacy) {
        this.endpoint = endpoint;
        this.settings = settings;
        this.responses = responses;
        this.urlsend = urlsend;
        this.pages = pages;
        this.legacy = legacy;
    }

    /** 处理本类的动作；不是本类的动作（或所需服务没有装配）时返回 null。 */
    ResponseEntity<String> handle(Ctx ctx) {
        String code = ctx.route().code();
        if (code.equals("index/check_back_link")) {
            BacklinkPages p = pages.getIfAvailable();
            return p == null ? null : checkBackLink(ctx, p);
        }
        UrlsendService service = urlsend.getIfAvailable();
        if (service == null) {
            return null;
        }
        if (code.equals("urlsend/data")) {
            return responses.echoed(service.data(AdminMakeActions.params(ctx), httpType()));
        }
        if (code.equals("urlsend/push")) {
            UrlsendService.Output o = service.push(AdminMakeActions.params(ctx), httpType(), UrlsendService.Entrance.ADMIN);
            if (o.error() != null) {
                return responses.error(ctx.ajax(), o.error());
            }
            String html = o.html();
            if (o.next() != null) {
                String url = ctx.url().build("urlsend/push") + "?" + LegacyAdminUrl.httpBuildQuery(o.next());
                html = html + AdminMakeActions.progressJump(endpoint, settings, url, 3);
            }
            return responses.echoed(html);
        }
        return null;
    }

    private String httpType() {
        return endpoint.secure() ? "https://" : "http://";
    }

    /** 旧 {@code Index::check_back_link}：{@code input()} 的 url；空（含 {@code "0"}、数组）时旧版返回 {@code json(json($res))}，即 {@code {}}。 */
    private ResponseEntity<String> checkBackLink(Ctx ctx, BacklinkPages p) {
        byte[] raw = ctx.params().scalar("url");
        String url = raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
        if (url.isEmpty() || url.equals("0")) {
            return responses.jsonBody("{}");
        }
        String siteUrl = siteConfig("site_url");
        String wapUrl = siteConfig("site_wapurl");
        Result r = check(p.fetch(url), siteUrl, wapUrl, settings.lang());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", r.code());
        m.put("msg", r.msg());
        return responses.rawJson(m);
    }

    private String siteConfig(String key) {
        if (!legacy.isConfigured()) {
            return "";
        }
        LegacyConfigService.Snapshot s = legacy.snapshotOrNull(LegacyConfigFile.MACCMS);
        return s == null ? "" : PhpValues.text(s.value().path("site", key));
    }

    record Result(int code, String msg) {
    }

    /**
     * 旧 {@code mac_check_back_link} 的判定：页面里依次找主域名与手机域名，提示两项都列出，最终的 code 只看第二项（找到 1，找不到 101）。
     * 抓取失败按空页面。域名为空时按 PHP 7.3（{@code strpos} 的空字符串参数返回 false，即"异常"；PHP 8.0 返回 0 即"正常"，D-B28-2）。
     */
    static Result check(byte[] html, String siteUrl, String wapUrl, LegacyLang lang) {
        String ok = lang.get("back_link") + lang.get("normal");
        String err = lang.get("back_link") + lang.get("abnormal");
        byte[] page = html == null ? new byte[0] : html;
        boolean main = contains(page, siteUrl);
        boolean wap = contains(page, wapUrl);
        String msg = "[" + siteUrl + "]" + (main ? ok : err) + "，[" + wapUrl + "]" + (wap ? ok : err);
        return new Result(wap ? 1 : 101, msg);
    }

    /** {@code strpos($haystack, $needle) !== false}（按字节）；空的 needle 为 false（PHP 7.3）。 */
    static boolean contains(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.UTF_8);
        if (n.length == 0 || n.length > haystack.length) {
            return false;
        }
        outer:
        for (int i = 0; i <= haystack.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
