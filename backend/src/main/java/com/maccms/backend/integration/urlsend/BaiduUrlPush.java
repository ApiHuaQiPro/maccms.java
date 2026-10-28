package com.maccms.backend.integration.urlsend;

import com.maccms.backend.integration.application.ThirdPartyConfig;
import com.maccms.backend.integration.application.UrlPush;
import com.maccms.backend.integration.http.OutboundException;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundRequest;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 百度普通推送与快速收录推送（E09，B28；30 文档第 3 节），逐行对应旧 {@code common/extend/urlsend/Baidu.php}、{@code Baidufast.php}：
 * <ul>
 * <li>地址：{@code <接口>?site=<站点>&token=<令牌>}，快速收录另加 {@code &type=daily}（拼接方式与旧版相同，不做编码）。接口默认
 * {@code http://data.zz.baidu.com/urls}，由 {@code maccms.integration.urlsend.baidu-endpoint} 注入（测试指向本机模拟服务）；</li>
 * <li>请求：POST，{@code Content-Type: text/plain}，请求体是换行分隔的地址；</li>
 * <li>结果：响应不是有效 JSON（含请求失败）→ 101；{@code error} 为真 → 102 + {@code message}；否则 1。快速收录在 {@code remain == 0}
 * 且 {@code success > 0} 时把前 {@code success} 条再推一次，失败 103、返回 error 104。</li>
 * </ul>
 * 令牌只在拼地址时取出，不进日志（出站客户端只记主机）。出站失败（含 {@code loopback-only} 模式下目标被拒绝）一律按"请求失败"处理，不抛出。
 */
public final class BaiduUrlPush implements UrlPush {

    private final OutboundHttpClient client;
    private final ThirdPartyConfig config;
    private final String endpoint;

    public BaiduUrlPush(OutboundHttpClient client, ThirdPartyConfig config, String endpoint) {
        this.client = client;
        this.config = config;
        this.endpoint = endpoint;
    }

    @Override
    public Result submit(String ac, String site, List<String> urls) {
        if (!UrlPush.supports(ac)) {
            throw new IllegalArgumentException("不支持的推送方式");
        }
        String token = config.secret("urlsend", ac, "token").reveal();
        String api = endpoint + "?site=" + site + "&token=" + token + (ac.equals("baidufast") ? "&type=daily" : "");
        PhpJsonResult json = post(ac, api, urls);
        if (json.falsy()) {
            return new Result(101, "请求失败，请重试");
        }
        if (PhpJsonResult.truthy(json.get("error"))) {
            return new Result(102, "发生错误：" + PhpJsonResult.text(json.get("message")));
        }
        if (ac.equals("baidu")) {
            return new Result(1, "推送成功" + PhpJsonResult.text(json.get("success")) + "条；当天剩余可推"
                    + PhpJsonResult.text(json.get("remain")) + "条。");
        }
        if (looseEqualsZero(json.get("remain")) && greaterThanZero(json.get("success"))) {
            long n = PhpJsonResult.intval(json.get("success"));
            json = post(ac, api, urls.subList(0, (int) Math.min(n, urls.size())));
            if (json.falsy()) {
                return new Result(103, "请求失败，请重试2");
            }
            if (PhpJsonResult.truthy(json.get("error"))) {
                return new Result(104, "发生错误2：" + PhpJsonResult.text(json.get("message")));
            }
        }
        // 旧版的提示把 remain 与 success 写反了，照旧
        return new Result(1, "推送" + PhpJsonResult.text(json.get("remain")) + "条；剩余可推送"
                + PhpJsonResult.text(json.get("success")) + "条.");
    }

    private PhpJsonResult post(String ac, String api, List<String> urls) {
        URI uri;
        try {
            uri = new URI(api);
        } catch (URISyntaxException e) {
            // 旧版 curl 会原样发出；新版地址不合法（令牌含空格等）时按请求失败处理
            return PhpJsonResult.decode(null);
        }
        byte[] body = String.join("\n", urls).getBytes(StandardCharsets.UTF_8);
        try {
            return PhpJsonResult.decode(client.send(OutboundRequest.post("urlsend." + ac, uri, "text/plain", body)).body());
        } catch (OutboundException | IllegalArgumentException e) {
            return PhpJsonResult.decode(null);
        }
    }

    /** PHP 7 {@code $v == 0}：数组不等于 0。 */
    private static boolean looseEqualsZero(JsonNode v) {
        BigDecimal d = PhpJsonResult.number(v);
        return d != null && d.signum() == 0;
    }

    /** PHP 7 {@code $v > 0}：数组总是大于整数。 */
    private static boolean greaterThanZero(JsonNode v) {
        BigDecimal d = PhpJsonResult.number(v);
        return d == null || d.signum() > 0;
    }
}
