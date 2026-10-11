package com.maccms.backend.site.application;

/**
 * 图片地址规范化用到的 upload 配置，原始字符串，未校验（校验见 {@link ImageUrlNormalizer#create}）。
 * 各项与旧 {@code application/extra/maccms.php} 的 {@code upload.*} 一一对应（:145-159，仓库内为开发值，线上取值未知）；
 * 旧版的 {@code MAC_PATH}（{@code site.install_dir}）由 {@link SiteSettings#homePath()} 代替。
 * 本类只定义取值，尚未绑定外部化配置，接入方式随页面任务确定（docs/architecture/06-image-url-normalizer.md）。
 *
 * @param protocol  {@code upload.protocol}：替换 {@code mac:} 前缀的协议，留空按 http
 * @param mode      {@code upload.mode}：等于 {@code remote} 时相对路径拼 {@code remoteUrl}，其他值拼站点路径
 * @param remoteUrl {@code upload.remoteurl}：远程模式的图片地址前缀
 * @param imgKey    {@code upload.img_key}：正则主体（旧代码套上 {@code /…/} 定界符），留空不启用
 * @param imgApi    {@code upload.img_api}：{@code imgKey} 命中时拼在地址前面的前缀
 */
public record ImageUrlConfig(String protocol, String mode, String remoteUrl, String imgKey, String imgApi) {
}
