package com.maccms.backend.member.application.view;

/**
 * 头像上传的结果（旧 {@code Upload::upload_return}，{@code common/model/Upload.php:293-310}）：{@code msg}、{@code code}（1 成功、0 失败）、
 * {@code file}（站点根路径 + 文件相对路径 + {@code ?} + 4 位随机数；失败时没有文件部分）。功能关闭时旧版只有 {@code code}、{@code msg}
 * 两个键（{@code index/controller/User.php:387-389}），此时 {@code file} 为 null、{@code disabled} 为 true。
 */
public record PortraitReply(int code, String msg, String file, boolean disabled) {
}
