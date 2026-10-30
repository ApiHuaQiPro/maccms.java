package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.maccms.backend.content.infrastructure.UploadStore;
import com.maccms.backend.legacyconfig.application.LegacyConfigProperties;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 后台上传生成多个尺寸的缩略图（{@link AdminUploadService}）与旧 {@code common/model/Image::makethumb} 实跑的对照（22 文档第 7.10 节）。
 * 旧版只打开一次原图，同一个 think\Image 对象依次 {@code thumb()->save()}，开水印时再改成打开刚存的缩略图加水印——所以后面的尺寸是从
 * 前一张缩略图（开水印时是加过水印的那张）再缩出来的：如 {@code 120x80,300x300} 按等比例缩放时两张都是 120×67。黄金由
 * {@code src/test/php/legacy_images_golden.php} 的 makethumb 部分生成；这里经完整的 {@link AdminUploadService#upload} 上传同样的
 * 纯色图，比较每张缩略图的宽高与 3×3 网格。
 */
class AdminUploadThumbGoldenTest {

    @TempDir
    Path tmp;

    @ParameterizedTest
    @ValueSource(strings = {"80", "73"})
    void thumbnailChainsMatchLegacyMakethumb(String version) throws IOException {
        JsonNode golden;
        try (InputStream in = getClass().getResourceAsStream("/content/admin/legacy-images-php" + version + "-golden.json")) {
            golden = JsonMapper.builder().build().readTree(in);
        }
        List<String> problems = new ArrayList<>();
        int n = 0;
        for (JsonNode c : golden.get("makethumb")) {
            String ext = c.get("ext").asString();
            String sizes = c.get("sizes").asString();
            Path root = Files.createDirectories(tmp.resolve("case" + n++));
            AdminUploadService service = service(root, sizes, c.get("type").asInt(), c.get("watermark").asInt());
            Map<String, String> params = new LinkedHashMap<>();
            params.put("flag", "vod");
            params.put("thumb", "1");
            service.upload(new AdminUploadService.Upload(params, solid(ext), "src." + ext), k -> k);
            List<String> expected = new ArrayList<>();
            for (JsonNode t : c.get("thumbs")) {
                String size = t.get("file").asString().replaceFirst("^.*_", "");
                expected.add(size + " " + t.get("size").get(0).asInt() + "x" + t.get("size").get(1).asInt() + " "
                        + t.get("grid").asString());
            }
            List<String> actual = new ArrayList<>();
            for (String size : sizes.split(",")) {
                Path f = thumb(root.resolve("upload"), "_" + size + "." + ext);
                BufferedImage im = f == null ? null : ImageIO.read(f.toFile());
                actual.add(size + "." + ext + " " + (im == null ? "missing" : im.getWidth() + "x" + im.getHeight() + " " + grid(im)));
            }
            if (!actual.equals(expected)) {
                problems.add(ext + " thumb_size=" + sizes + " type=" + c.get("type").asInt() + " watermark="
                        + c.get("watermark").asInt() + "\n  php:  " + expected + "\n  java: " + actual);
            }
        }
        assertThat(n).isEqualTo(12);
        assertThat(problems).as("与旧 makethumb（PHP %s）的差异", golden.get("php").asString()).isEmpty();
    }

    private AdminUploadService service(Path root, String sizes, int type, int watermark) throws IOException {
        Path extra = Files.createDirectories(root.resolve("legacy").resolve("extra"));
        try (Stream<Path> s = Files.list(Path.of("php", "application", "extra"))) {
            for (Path f : s.toList()) {
                Files.copy(f, extra.resolve(f.getFileName().toString()));
            }
        }
        Path maccms = extra.resolve("maccms.php");
        String php = Files.readString(maccms, StandardCharsets.UTF_8)
                .replace("'thumb' => '0',", "'thumb' => '1',")
                .replace("'thumb_size' => '300x300',", "'thumb_size' => '" + sizes + "',")
                .replace("'thumb_type' => '1',", "'thumb_type' => '" + type + "',")
                .replace("'watermark' => '0',", "'watermark' => '" + watermark + "',")
                .replace("'watermark_content' => 'test',", "'watermark_content' => 'B13',")
                .replace("'watermark_size' => '40',", "'watermark_size' => '20',");
        Files.writeString(maccms, php, StandardCharsets.UTF_8);
        AdminConfig config = new AdminConfig(new LegacyConfigService(new LegacyConfigProperties(root.resolve("legacy").toString())));
        assertThat(config.text("upload", "thumb_size")).isEqualTo(sizes);
        AnnexAdminService annexes = mock(AnnexAdminService.class);
        when(annexes.register(any(), any(), any())).thenReturn(true);
        return new AdminUploadService(new UploadStore(Files.createDirectories(root.resolve("upload")).toString()), config,
                annexes, Clock.systemUTC(), new SecureRandom());
    }

    private static Path thumb(Path upload, String suffix) throws IOException {
        try (Stream<Path> s = Files.walk(upload)) {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix)).findFirst().orElse(null);
        }
    }

    /** 与黄金脚本 source() 相同：640×360 纯红 (220, 30, 30)。 */
    private static byte[] solid(String ext) throws IOException {
        BufferedImage img = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(220, 30, 30));
        g.fillRect(0, 0, 640, 360);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, ext.equals("jpg") ? "jpeg" : ext, out);
        return out.toByteArray();
    }

    /** 与黄金脚本 grid() 相同的 3×3 网格分类（S 源图红色、W 白、T 透明、O 其他）。 */
    private static String grid(BufferedImage im) {
        StringBuilder sb = new StringBuilder();
        int w = im.getWidth();
        int h = im.getHeight();
        for (int j : new int[] {1, 3, 5}) {
            for (int i : new int[] {1, 3, 5}) {
                int argb = im.getRGB(Math.min(w - 1, w * i / 6), Math.min(h - 1, h * j / 6));
                int a = argb >>> 24;
                int r = (argb >> 16) & 0xff;
                int gr = (argb >> 8) & 0xff;
                int b = argb & 0xff;
                sb.append(im.getColorModel().hasAlpha() && a <= 128 ? 'T'
                        : r > 180 && gr < 90 && b < 90 ? 'S' : r > 230 && gr > 230 && b > 230 ? 'W' : 'O');
            }
        }
        return sb.toString();
    }
}
