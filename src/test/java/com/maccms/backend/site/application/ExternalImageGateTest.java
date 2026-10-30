package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.site.application.ImageUrlNormalizer.Branch;
import com.maccms.backend.site.application.ImageUrlNormalizer.Outcome;
import com.maccms.backend.site.application.ImageUrlNormalizer.Resolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * W2 外链图片放行（B02，docs/architecture/11-guards-and-legacy-config.md 第 5 节）。旧版各分支在关闭时的结果；
 * 放行时与 MACC-11 一致的逐条对拍见 {@link ImageUrlGoldenVectorsTest}。
 */
class ExternalImageGateTest {

    private static final SiteSettings SITE = ImageUrlGoldenVectorsTest.site("https://www.maccms.test/");

    private static ImageUrlNormalizer closed(ImageUrlConfig config) {
        return ImageUrlNormalizer.create(config, SITE).withExternalImages(false);
    }

    private static final ImageUrlNormalizer LOCAL = closed(new ImageUrlConfig("https", "local", "", "", ""));

    @ParameterizedTest
    @ValueSource(strings = {"https://img.ext.test/a.jpg", "http://img.ext.test/a.jpg", "https://IMG.EXT.TEST/a.jpg",
            "//img.ext.test/a.jpg", "mac://img.ext.test/a.jpg", "https://www.maccms.test.ext.test/a.jpg",
            "https://maccms.test/a.jpg", "https://cdn.www.maccms.test/a.jpg", "https://www.maccms.tes/a.jpg",
            "https://img.ext.test/a.jpg	", "https://img.ext.test:443/a.jpg", "http://127.0.0.1/a.jpg"})
    void externalHostsAreBlocked(String raw) {
        Resolution r = LOCAL.resolve(raw);
        assertThat(r.outcome()).as(raw).isEqualTo(Outcome.EXTERNAL_BLOCKED);
        assertThat(r.url()).isEmpty();
        assertThat(LOCAL.normalize(raw)).isEmpty();
        // 放行时同一地址照常输出
        assertThat(ImageUrlNormalizer.create(new ImageUrlConfig("https", "local", "", "", ""), SITE).normalize(raw))
                .as(raw).isPresent();
    }

    @Test
    void sameSiteAndRelativeAddressesStillWork() {
        assertThat(LOCAL.normalize("/upload/vod/a.jpg")).map(SafeUrl::href).hasValue("/upload/vod/a.jpg");
        assertThat(LOCAL.normalize("upload/vod/a.jpg")).map(SafeUrl::href).hasValue("/upload/vod/a.jpg");
        assertThat(LOCAL.normalize("https://www.maccms.test/upload/a.jpg")).map(SafeUrl::href)
                .hasValue("https://www.maccms.test/upload/a.jpg");
        // 主机不区分大小写、忽略末尾的点、端口与协议（同一主机即本站）
        assertThat(LOCAL.normalize("http://WWW.MACCMS.TEST.:8080/a.jpg")).isPresent();
        assertThat(LOCAL.normalize("mac://www.maccms.test/a.jpg")).map(SafeUrl::href)
                .hasValue("https://www.maccms.test/a.jpg");
        assertThat(LOCAL.normalize("//www.maccms.test/a.jpg")).map(SafeUrl::href)
                .hasValue("https://www.maccms.test/a.jpg");
        assertThat(LOCAL.resolve("").outcome()).isEqualTo(Outcome.EMPTY);
        // 不在 SafeUrl 白名单内的仍是 NOT_ALLOWED（先于主机判断）；旧版把不以 http、/ 开头的值当相对路径拼站点路径，仍是本站
        assertThat(LOCAL.resolve("https://u@img.ext.test/a.jpg").outcome()).isEqualTo(Outcome.NOT_ALLOWED);
        assertThat(LOCAL.normalize("HTTPS://IMG.EXT.TEST/a.jpg")).map(SafeUrl::href).hasValue("/HTTPS://IMG.EXT.TEST/a.jpg");
    }

    @Test
    void remoteModeIsBlockedOnlyWhenRemoteUrlIsExternal() {
        ImageUrlNormalizer externalRemote = closed(new ImageUrlConfig("", "remote", "https://cdn.ext.test/", "", ""));
        Resolution r = externalRemote.resolve("upload/a.jpg");
        assertThat(r.branch()).isEqualTo(Branch.RELATIVE_REMOTE);
        assertThat(r.outcome()).isEqualTo(Outcome.EXTERNAL_BLOCKED);
        assertThat(externalRemote.normalize("/upload/a.jpg")).map(SafeUrl::href).hasValue("/upload/a.jpg");

        ImageUrlNormalizer ownRemote = closed(new ImageUrlConfig("", "remote", "https://www.maccms.test/files/", "", ""));
        assertThat(ownRemote.normalize("upload/a.jpg")).map(SafeUrl::href)
                .hasValue("https://www.maccms.test/files/upload/a.jpg");
        ImageUrlNormalizer protocolRelative = closed(new ImageUrlConfig("", "remote", "//cdn.ext.test/", "", ""));
        assertThat(protocolRelative.resolve("upload/a.jpg").outcome()).isEqualTo(Outcome.EXTERNAL_BLOCKED);
    }

    @Test
    void imgApiPrefixIsNotAddedWhenClosed() {
        ImageUrlConfig config = new ImageUrlConfig("", "local", "", "img\\.ext\\.test|www\\.maccms\\.test", "/img.php?url=");
        ImageUrlNormalizer open = ImageUrlNormalizer.create(config, SITE);
        ImageUrlNormalizer closed = open.withExternalImages(false);

        // 外部图片：放行时拼代理前缀（站内路径，代理再去抓外部图片），关闭时不拼前缀、按原地址判断 → 拦下
        assertThat(open.normalize("https://img.ext.test/a.jpg")).map(SafeUrl::href)
                .hasValue("/img.php?url=https://img.ext.test/a.jpg");
        Resolution r = closed.resolve("https://img.ext.test/a.jpg");
        assertThat(r.branch()).isEqualTo(Branch.IMG_API);
        assertThat(r.outcome()).isEqualTo(Outcome.EXTERNAL_BLOCKED);

        // 本站图片命中 img_key：关闭时不拼前缀，原地址照常输出
        assertThat(open.normalize("https://www.maccms.test/a.jpg")).map(SafeUrl::href)
                .hasValue("/img.php?url=https://www.maccms.test/a.jpg");
        assertThat(closed.normalize("https://www.maccms.test/a.jpg")).map(SafeUrl::href)
                .hasValue("https://www.maccms.test/a.jpg");

        // img_api 本身是外部地址时同样不会输出
        ImageUrlNormalizer externalApi = ImageUrlNormalizer.create(
                new ImageUrlConfig("", "local", "", "www\\.maccms\\.test", "https://proxy.ext.test/?u="), SITE);
        assertThat(externalApi.normalize("https://www.maccms.test/a.jpg")).map(SafeUrl::href)
                .hasValue("https://proxy.ext.test/?u=https://www.maccms.test/a.jpg");
        assertThat(externalApi.withExternalImages(false).normalize("https://www.maccms.test/a.jpg")).map(SafeUrl::href)
                .hasValue("https://www.maccms.test/a.jpg");
    }

    @Test
    void withExternalImagesTrueBehavesLikeCreate() {
        ImageUrlConfig config = new ImageUrlConfig("https", "remote", "https://cdn.ext.test/", "img\\.ext\\.test", "/i?u=");
        ImageUrlNormalizer created = ImageUrlNormalizer.create(config, SITE);
        ImageUrlNormalizer reopened = created.withExternalImages(false).withExternalImages(true);
        for (String raw : new String[] {"https://img.ext.test/a.jpg", "mac://x.ext.test/b.png", "upload/c.jpg",
                "/upload/d.jpg", "//e.ext.test/f.jpg", "", "javascript:x", "<b>"}) {
            assertThat(reopened.resolve(raw)).as(raw).isEqualTo(created.resolve(raw));
        }
    }
}
