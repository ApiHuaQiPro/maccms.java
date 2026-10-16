package com.maccms.backend.site.web;

import com.maccms.backend.site.config.SiteConfiguration;
import java.util.List;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;

/**
 * W2 默认关闭、远程存储模式（{@code upload.mode=remote}，{@code remoteurl} 是外部主机）：相对路径拼上外部前缀后不输出，
 * 只有站内路径照常输出。
 */
@WebMvcTest(controllers = SiteContentController.class, properties = {
        "maccms.site.enabled=true",
        "maccms.site.name=合成站",
        "maccms.site.base-url=https://www.maccms.test/",
        "maccms.site.image.mode=remote",
        "maccms.site.image.remote-url=https://cdn.ext.test/files/"})
@Import(SiteConfiguration.class)
class SiteExternalImagesRemoteMvcTest extends ExternalImagesPageCheck {

    @Override
    List<String> externalPics() {
        return List.of("upload/vod/1.jpg", "https://img.ext.test/a.jpg", "mac://img2.ext.test/b.jpg");
    }

    @Override
    List<String> externalHosts() {
        return List.of("cdn.ext.test", "img.ext.test", "img2.ext.test");
    }

    @Override
    List<String> localPicsAndSrc() {
        return List.of("/upload/vod/2.jpg", "/upload/vod/2.jpg");
    }
}
