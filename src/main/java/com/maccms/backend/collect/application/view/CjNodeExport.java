package com.maccms.backend.collect.application.view;

/** 自定义规则节点的导出文件（旧 Cj::export）：正文（{@code base64_encode(json_encode($node))}）与文件名 {@code mac_cj_名称.txt}。 */
public record CjNodeExport(String body, String filename) {
}
