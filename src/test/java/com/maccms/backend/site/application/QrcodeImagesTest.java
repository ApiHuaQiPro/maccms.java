package com.maccms.backend.site.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 二维码（B06，16 文档第 4.7 节）：URL 校验与 PHP {@code filter_var(FILTER_VALIDATE_URL)} 的黄金向量（PHP 8.0.30 与 7.3.33）逐条相同；
 * 生成的 PNG 与旧版 phpqrcode 的输出解码出同一文本、尺寸相同；超长与非法输入不生成。
 */
class QrcodeImagesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"/site/qrcode/php80-url-vectors.json", "/site/qrcode/php73-url-vectors.json"})
    void urlValidationMatchesPhpFilterValidateUrl(String resource) throws Exception {
        JsonNode golden;
        try (InputStream in = QrcodeImagesTest.class.getResourceAsStream(resource)) {
            golden = JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        int n = 0;
        for (JsonNode v : golden.get("vectors")) {
            String input = v.get("input").asString();
            assertThat(QrcodeImages.isLegacyValidUrl(input)).as("%s：%s", golden.get("php").asString(), input)
                    .isEqualTo(v.get("valid").asBoolean());
            n++;
        }
        assertThat(n).isGreaterThanOrEqualTo(60);
    }

    @Test
    void pngMatchesTheLegacyImage() throws Exception {
        JsonNode golden;
        try (InputStream in = QrcodeImagesTest.class.getResourceAsStream("/openapi/provide/php80-golden.json")) {
            golden = JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        String url = "http://www.example.test/index.php/vod/detail/id/1.html";
        byte[] php = null;
        for (JsonNode c : golden.get("cases")) {
            if (c.get("id").asString().equals("qrcode.valid")) {
                php = Base64.getDecoder().decode(c.get("body").asString());
            }
        }
        assertThat(php).isNotNull();
        byte[] png = QrcodeImages.png(url).orElseThrow();
        assertThat(decode(png)).isEqualTo(url);
        assertThat(decode(php)).isEqualTo(url);
        var a = ImageIO.read(new ByteArrayInputStream(png));
        var b = ImageIO.read(new ByteArrayInputStream(php));
        assertThat(a.getWidth()).isEqualTo(b.getWidth()).isEqualTo(370);
        assertThat(a.getHeight()).isEqualTo(b.getHeight());
    }

    @Test
    void invalidOrOversizedInputIsNotEncoded() {
        assertThat(QrcodeImages.png(null)).isEmpty();
        assertThat(QrcodeImages.png("")).isEmpty();
        assertThat(QrcodeImages.png("not a url")).isEmpty();
        assertThat(QrcodeImages.png("http://x.test/" + "a".repeat(QrcodeImages.MAX_BYTES))).isEmpty();
        assertThat(QrcodeImages.png("http://x.test/" + "a".repeat(900))).isPresent();
    }

    private static String decode(byte[] png) throws Exception {
        var img = ImageIO.read(new ByteArrayInputStream(png));
        int w = img.getWidth();
        int h = img.getHeight();
        int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, h, pixels))))
                .getText();
    }
}
