package com.maccms.backend.integration.backlink;

import com.maccms.backend.integration.application.BacklinkPages;
import com.maccms.backend.integration.http.OutboundException;
import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundRequest;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * 友链回链检测的页面抓取（E11，B28；30 文档第 4 节）：旧 {@code mac_curl_get($url)}（GET、跟随跳转、超时 15/30 秒）。
 * 经出站客户端：只允许 http/https、URL 不得带用户名密码、跳转最多 5 次、响应最大 4 MiB、校验证书（D-B23-1～4）；
 * 与旧版相同，不额外限制内网地址（路线图 B28 行）。地址原样使用；失败一律返回 null。
 */
public final class BacklinkFetcher implements BacklinkPages {

    private final OutboundHttpClient client;

    public BacklinkFetcher(OutboundHttpClient client) {
        this.client = client;
    }

    @Override
    public byte[] fetch(String url) {
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            return null;
        }
        try {
            return client.send(OutboundRequest.get("backlink.check", uri)).body();
        } catch (OutboundException | IllegalArgumentException e) {
            return null;
        }
    }
}
