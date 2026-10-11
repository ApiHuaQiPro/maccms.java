package com.maccms.backend.site.application;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 图片地址配置 {@code maccms.site.image.*}（MACC-14），对应旧 {@code upload.*}（见 {@link ImageUrlConfig}），
 * 与 {@link SiteProperties} 同属 {@code maccms.site.*}，只来自本应用的环境变量。按原始字符串绑定，
 * 由 {@link #toNormalizer(SiteSettings)} 校验，错误只报配置项名称、不回显取值。
 *
 * <p>{@code img-key} 另有一条比 {@link ImageUrlNormalizer#create} 更严的限制（07 文档第 8 节"img_key 回溯"的处理）：
 * 只接受字母、数字与 {@code . - _ | \ /}，即字面域名的多选一（如 {@code img\.a\.test|img\.b\.test}）。
 * 不允许量词、分组、字符类，正则匹配耗时与地址长度成线性关系；正文里的图片数量可能远多于海报字段。
 * 旧版接受任意 PCRE，这是差异。
 *
 * @param protocol  旧 upload.protocol：{@code mac:} 前缀替换成的协议，留空按 http
 * @param mode      旧 upload.mode：{@code remote} 时相对路径拼 {@code remote-url}，其他值拼站点路径
 * @param remoteUrl 旧 upload.remoteurl
 * @param imgKey    旧 upload.img_key，留空不启用
 * @param imgApi    旧 upload.img_api
 */
@ConfigurationProperties(prefix = "maccms.site.image")
public record SiteImageProperties(String protocol, String mode, String remoteUrl, String imgKey, String imgApi) {

    static final Pattern LITERAL_IMG_KEY = Pattern.compile("[A-Za-z0-9._|\\\\/-]*");

    /** 校验配置并创建规范化器；不合法时抛 {@link IllegalStateException}，信息只含配置项名称与规则。 */
    public ImageUrlNormalizer toNormalizer(SiteSettings site) {
        String key = imgKey == null ? "" : imgKey.strip();
        if (!LITERAL_IMG_KEY.matcher(key).matches()) {
            throw new IllegalStateException("site 已启用但图片配置不合法：maccms.site.image.img-key"
                    + "（留空，或只含字母、数字与 . - _ | \\ / 的字面域名多选一，不含量词、分组、字符类）");
        }
        try {
            return ImageUrlNormalizer.create(new ImageUrlConfig(strip(protocol), strip(mode), strip(remoteUrl), key,
                    strip(imgApi)), site);
        } catch (IllegalStateException e) {
            // ImageUrlNormalizer 的错误信息用旧配置名（upload.*），这里换成本应用的配置项名；原信息同样不含取值
            throw new IllegalStateException(e.getMessage().replace("upload.remoteurl", "maccms.site.image.remote-url")
                    .replace("upload.img_key", "maccms.site.image.img-key")
                    .replace("upload.img_api", "maccms.site.image.img-api")
                    .replace("upload.protocol", "maccms.site.image.protocol"), null);
        }
    }

    private static String strip(String s) {
        return s == null ? "" : s.strip();
    }
}
