package com.maccms.backend.integration.application;

/**
 * 友链回链检测（E11，B28；30 文档第 4 节）要抓取的对方页面：旧 {@code mac_check_back_link} 里的 {@code mac_curl_get($url)}。
 * 实现在已登记子包 {@code integration.backlink}，经出站客户端发请求（超时、响应大小、跳转次数、协议都由客户端限制）；其他模块只经本接口使用。
 */
public interface BacklinkPages {

    /**
     * 抓取页面并返回响应体（任何状态码都返回正文，同 curl 不设 {@code CURLOPT_FAILONERROR}）；地址不合法、目标被拒绝、连接失败、超时、
     * 响应过大等一律返回 null（同旧版 {@code curl_exec} 返回 false）。
     */
    byte[] fetch(String url);
}
