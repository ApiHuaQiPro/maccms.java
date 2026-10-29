package com.maccms.backend.integration.addonmarket;

import com.maccms.backend.integration.application.AddonMarket;
import com.maccms.backend.integration.http.OutboundException;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * fastadmin 插件市场的下载（E13，B29；35 文档第 3 节），逐行对应旧 {@code Service::download}（Service.php:28-68）：
 * <ul>
 * <li>{@code GET <市场地址>/addon/download?name=…&uid=…&token=…&version=…&faversion=…}（{@code http_build_query}：值为 null 的项不出现，
 * 空格编码为 {@code +}），请求头 {@code X-REQUESTED-WITH: XMLHttpRequest}；跟随跳转；不看状态码（旧 {@code Http::sendRequest} 只看 curl 是否出错）；</li>
 * <li>正文以 {@code &#123;} 开头时按 JSON 处理：{@code data} 是带 {@code url} 的对象 → 再 GET 这个地址（不带上面的请求头），失败时
 * {@link AddonMarket.MarketRefused}（带第一次的 JSON）；没有下载地址 → {@link AddonMarket.MarketRefused}；</li>
 * <li>其余正文就是插件包。连接失败、超时、超过包大小上限、出站被拒绝 → {@link AddonMarket.DownloadFailed}。</li>
 * </ul>
 * 与旧版的差异：不写临时文件 {@code runtime/addons/<名称>.zip}（包只在内存里）；校验 TLS 证书（旧版 {@code CURLOPT_SSL_VERIFYPEER=false}）；
 * 超时按出站客户端的配置（旧版连接与总时限各 30 秒）；包有大小上限。{@code uid}、{@code token} 只放进发往市场的查询串，出站客户端不记路径与查询串。
 */
public final class FastadminAddonMarket implements AddonMarket {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OutboundHttpClient client;
    private final String apiUrl;
    private final int maxPackageBytes;

    /**
     * @param apiUrl 市场地址（旧 {@code config('fastadmin.api_url')}），空串表示未配置：不发请求，按下载失败处理
     */
    public FastadminAddonMarket(OutboundHttpClient client, String apiUrl, int maxPackageBytes) {
        this.client = client;
        this.apiUrl = apiUrl == null ? "" : apiUrl;
        this.maxPackageBytes = maxPackageBytes;
    }

    @Override
    public int maxPackageBytes() {
        return maxPackageBytes;
    }

    @Override
    public byte[] download(String name, Map<String, String> extend) {
        if (apiUrl.isEmpty()) {
            throw new DownloadFailed();
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("name", name);
        extend.forEach((k, v) -> {
            if (v != null) {
                params.put(k, v);
            }
        });
        byte[] first = get("addonmarket.download", apiUrl + "/addon/download?" + buildQuery(params), true);
        if (first.length == 0 || first[0] != '{') {
            return first;
        }
        String json = new String(first, StandardCharsets.UTF_8);
        String url = downloadUrl(json);
        if (url == null) {
            throw new MarketRefused(json);
        }
        try {
            return get("addonmarket.package", url, false);
        } catch (DownloadFailed e) {
            throw new MarketRefused(json);
        }
    }

    /** {@code $json['data'] && isset($json['data']['url'])}：{@code data} 是对象且 {@code url} 不为 null；地址按 PHP 字符串取值。 */
    static String downloadUrl(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            return null;
        }
        JsonNode data = root == null || !root.isObject() ? null : root.get("data");
        if (data == null || !data.isObject() || data.isEmpty()) {
            return null;
        }
        JsonNode url = data.get("url");
        if (url == null || url.isNull()) {
            return null;
        }
        if (url.isBoolean()) {
            return url.asBoolean() ? "1" : "";
        }
        return url.isValueNode() ? url.asString() : null;
    }

    private byte[] get(String purpose, String url, boolean ajaxHeader) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            // 旧版 curl 会原样发出；新版地址不合法时按下载失败处理
            throw new DownloadFailed();
        }
        OutboundRequest req = OutboundRequest.get(purpose, uri);
        if (ajaxHeader) {
            req = req.withHeader("X-REQUESTED-WITH", "XMLHttpRequest");
        }
        try {
            return client.send(req, maxPackageBytes).body();
        } catch (OutboundException | IllegalArgumentException e) {
            throw new DownloadFailed();
        }
    }

    /** PHP {@code http_build_query}（RFC 1738：空格为 {@code +}，{@code ~} 编码为 {@code %7E}）。 */
    static String buildQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        return sb.toString();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("*", "%2A").replace("~", "%7E");
    }
}
