package com.maccms.backend.integration.application;

import java.util.Map;

/**
 * 插件市场下载（E13，B29；35 文档第 3 节）：旧 fastadmin-addons {@code Service::download}（Service.php:28-68）。实现在已登记子包
 * {@code integration.addonmarket}，经出站客户端发请求；解包、校验与安装在后台模块（{@code admin.application.AddonMarketService}），
 * 其他模块只经本接口使用。下载结果只在内存里，不落临时文件。
 */
public interface AddonMarket {

    /** 旧版下载失败时的提示（{@code Http::sendRequest} 的 {@code ret} 为假，Service.php:67）。 */
    String DOWNLOAD_FAILED = "无法下载远程文件";

    /**
     * 市场返回 JSON（正文以 {@code &#123;} 开头）且没有给出下载地址，或按 JSON 给出的地址再次下载失败：旧版抛
     * {@code AddonException($json['msg'], $json['code'], $json['data'])}（Service.php:45-58），后台按 {@code $this->result($data, $code, $msg)}
     * 输出。这里只带市场返回的原文，按 PHP {@code (array)json_decode(…, true)} 的取值由后台模块完成（同一套 PHP 语义只写一处）。
     */
    final class MarketRefused extends RuntimeException {
        private final transient String json;

        public MarketRefused(String json) {
            super("market refused", null, false, false);
            this.json = json;
        }

        /** 市场返回的 JSON 原文。 */
        public String json() {
            return json;
        }
    }

    /** 下载失败（连接失败、超时、超过大小上限、市场地址未配置、出站被拒绝）：旧版的 {@link #DOWNLOAD_FAILED}。 */
    final class DownloadFailed extends RuntimeException {
        public DownloadFailed() {
            super(DOWNLOAD_FAILED, null, false, false);
        }
    }

    /**
     * 下载插件包。
     *
     * @param name   插件名称（调用方已校验）
     * @param extend 旧版 {@code $extend}：后台 POST 的 {@code uid}、{@code token}、{@code version}、{@code faversion}，未提交的项不在其中
     *               （{@code http_build_query} 跳过 null）
     * @return 包的原始字节（不超过大小上限；是否为 zip 由调用方校验）
     * @throws MarketRefused  市场返回 JSON 且没有下载地址，或按 JSON 给出的地址再次下载失败
     * @throws DownloadFailed 其他失败
     */
    byte[] download(String name, Map<String, String> extend);

    /** 包的大小上限（字节）。 */
    int maxPackageBytes();
}
