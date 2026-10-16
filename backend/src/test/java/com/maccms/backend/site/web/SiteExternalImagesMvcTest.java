package com.maccms.backend.site.web;

import com.maccms.backend.site.config.SiteConfiguration;
import java.util.List;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;

/**
 * W2 默认关闭、本地存储模式（{@code upload.mode=local}）且启用 {@code img_key/img_api}：{@code mac:}、协议相对、
 * {@code img_api} 命中、仿冒本站的外部主机都不输出；相对路径、站内路径与本站绝对地址照常输出。
 */
@WebMvcTest(controllers = SiteContentController.class, properties = {
        "maccms.site.enabled=true",
        "maccms.site.name=合成站",
        "maccms.site.base-url=https://www.maccms.test/",
        "maccms.site.image.protocol=https",
        "maccms.site.image.img-key=img\\.api\\.ext\\.test|www\\.maccms\\.test",
        "maccms.site.image.img-api=/img.php?url="})
@Import(SiteConfiguration.class)
class SiteExternalImagesMvcTest extends ExternalImagesPageCheck {

    @Override
    List<String> externalPics() {
        return List.of("https://img.ext.test/a.jpg", "mac://img2.ext.test/b.jpg", "//img3.ext.test/c.jpg",
                "https://img.api.ext.test/d.jpg", "http://www.maccms.test.img4.ext.test/e.jpg");
    }

    @Override
    List<String> externalHosts() {
        return List.of("img.ext.test", "img2.ext.test", "img3.ext.test", "img.api.ext.test", "img4.ext.test",
                "/img.php?url=");
    }

    @Override
    List<String> localPicsAndSrc() {
        return List.of(
                "upload/vod/1.jpg", "/upload/vod/1.jpg",
                "/upload/vod/2.jpg", "/upload/vod/2.jpg",
                // 本站地址命中 img_key：关闭时不拼 img_api，原地址输出
                "https://www.maccms.test/upload/3.jpg", "https://www.maccms.test/upload/3.jpg",
                "mac://www.maccms.test/upload/4.jpg", "https://www.maccms.test/upload/4.jpg");
    }
}
