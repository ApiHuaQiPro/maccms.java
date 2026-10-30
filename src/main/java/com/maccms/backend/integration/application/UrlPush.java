package com.maccms.backend.integration.application;

import java.util.List;

/**
 * 搜索引擎推送（E09，B28；30 文档第 3 节）：旧 {@code common/extend/urlsend/Baidu.php}、{@code Baidufast} 的 {@code submit($data)}。
 * 实现在已登记子包 {@code integration.urlsend}，经出站客户端发请求；其他模块只经本接口使用。
 */
public interface UrlPush {

    /** 旧 {@code urlsend} 扩展目录里的推送方式（{@code ac} 参数，小写）。 */
    List<String> KINDS = List.of("baidu", "baidufast");

    /**
     * 推送结果，取值与旧版相同：1 成功；101 请求失败；102 百度返回 error；快速收录额度用尽后重推时 103 请求失败、104 返回 error。
     *
     * @param msg 旧版的提示文字（含百度返回的 {@code message} 原文，未转义）
     */
    record Result(int code, String msg) {
        public boolean ok() {
            return code == 1;
        }
    }

    /** {@code ac} 是否为可用的推送方式（旧 {@code class_exists(urlsend 扩展类)}）。 */
    static boolean supports(String ac) {
        return ac != null && KINDS.contains(ac);
    }

    /**
     * 推送一页地址。
     *
     * @param ac   {@link #KINDS} 之一
     * @param site 旧 {@code $GLOBALS['http_type'] . site_url}（如 {@code http://www.example.com}）
     * @param urls 本页地址（按旧 {@code implode("\n", $data['urls'])} 的顺序）；null 表示旧版 {@code data()} 没有取到数据
     */
    Result submit(String ac, String site, List<String> urls);
}
