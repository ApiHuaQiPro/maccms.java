package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 上传图片的缩略图与文字水印（{@link LegacyImages}）与 think-image 的几何对照（22 文档第 7.10、12 节）。黄金由
 * {@code src/test/php/legacy_images_golden.php} 直接调用 vendor/ 里的 think-image 在 PHP 8.0 与 7.3 上生成：
 * <ul>
 *   <li>缩略图：纯色源图（PNG、JPEG）× 5 种源尺寸 × 3 种目标尺寸 × 六种方式——输出宽高逐个相同，3×3 网格中心点的类别（源图颜色 / 白 /
 *       透明 / 其他）逐个相同，即缩放、裁剪、留白的位置相同；</li>
 *   <li>水印：白底图上 {@code text('B13', static/font/test.ttf, 20, '#FF0000', 位置)}，九个位置 × 两种图片尺寸——红色像素外接矩形的
 *       中心与 PHP 相差不超过 {@link #MARK_CENTER} 像素、宽高相差不超过 {@link #MARK_SIZE} 像素（字形由 FreeType 与 Java2D 各自栅格化，
 *       像素不逐个相同；实测横向与宽高完全相同，上下两排纵向差 1 像素）。</li>
 * </ul>
 */
class LegacyImagesGoldenTest {

    static final int MARK_CENTER = 1;
    static final int MARK_SIZE = 1;

    private static JsonNode load(String version) throws IOException {
        try (InputStream in = LegacyImagesGoldenTest.class.getResourceAsStream(
                "/content/admin/legacy-images-php" + version + "-golden.json")) {
            return JsonMapper.builder().build().readTree(in);
        }
    }

    private static byte[] solid(String ext, int w, int h, Color c) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, ext.equals("jpg") ? "jpeg" : ext, out);
        return out.toByteArray();
    }

    /** 与黄金脚本 kind() 相同的分类。 */
    private static char kind(BufferedImage im, int x, int y) {
        int argb = im.getRGB(x, y);
        int a = argb >>> 24;
        int r = (argb >> 16) & 0xff;
        int g = (argb >> 8) & 0xff;
        int b = argb & 0xff;
        // GD 的 alpha 0（不透明）–127（全透明）：alpha ≥ 64 约等于 ARGB 的 a ≤ 128
        if (im.getColorModel().hasAlpha() && a <= 128) {
            return 'T';
        }
        if (r > 180 && g < 90 && b < 90) {
            return 'S';
        }
        if (r > 230 && g > 230 && b > 230) {
            return 'W';
        }
        return 'O';
    }

    private static String grid(BufferedImage im) {
        StringBuilder sb = new StringBuilder();
        int w = im.getWidth();
        int h = im.getHeight();
        for (int j : new int[] {1, 3, 5}) {
            for (int i : new int[] {1, 3, 5}) {
                sb.append(kind(im, Math.min(w - 1, w * i / 6), Math.min(h - 1, h * j / 6)));
            }
        }
        return sb.toString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"80", "73"})
    void thumbnailsMatchThinkImageGeometry(String version) throws IOException {
        JsonNode golden = load(version);
        List<String> problems = new ArrayList<>();
        int n = 0;
        for (JsonNode c : golden.get("thumbs")) {
            String ext = c.get("ext").asString();
            int sw = c.get("src").get(0).asInt();
            int sh = c.get("src").get(1).asInt();
            int tw = c.get("target").get(0).asInt();
            int th = c.get("target").get(1).asInt();
            int type = c.get("type").asInt();
            byte[] out = LegacyImages.thumb(solid(ext, sw, sh, new Color(220, 30, 30)), ext, tw, th, type);
            BufferedImage im = ImageIO.read(new ByteArrayInputStream(out));
            String actual = im.getWidth() + "x" + im.getHeight() + " " + grid(im);
            String expected = c.get("size").get(0).asInt() + "x" + c.get("size").get(1).asInt() + " " + c.get("grid").asString();
            if (!actual.equals(expected)) {
                problems.add(ext + " " + sw + "x" + sh + " → " + tw + "x" + th + " type " + type + ": php " + expected
                        + " java " + actual);
            }
            n++;
        }
        assertThat(n).isEqualTo(180);
        assertThat(problems).as("与 think-image（PHP %s）的缩略图几何差异", golden.get("php").asString()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"80", "73"})
    void watermarksLandWhereThinkImagePutsThem(String version) throws IOException {
        JsonNode golden = load(version);
        Font font = LegacyImages.font(Files.readAllBytes(Path.of("php", "static", "font", "test.ttf")));
        assertThat(font).isNotNull();
        List<String> problems = new ArrayList<>();
        List<String> measured = new ArrayList<>();
        for (JsonNode c : golden.get("marks")) {
            int w = c.get("src").get(0).asInt();
            int h = c.get("src").get(1).asInt();
            int locate = c.get("locate").asInt();
            byte[] out = LegacyImages.text(solid("png", w, h, Color.WHITE), "png", "B13", font, 20, "#FF0000", locate);
            BufferedImage im = ImageIO.read(new ByteArrayInputStream(out));
            int[] box = redBox(im);
            JsonNode p = c.get("box");
            int dcx = (box[0] + box[2]) - (p.get(0).asInt() + p.get(2).asInt());
            int dcy = (box[1] + box[3]) - (p.get(1).asInt() + p.get(3).asInt());
            int dw = (box[2] - box[0]) - (p.get(2).asInt() - p.get(0).asInt());
            int dh = (box[3] - box[1]) - (p.get(3).asInt() - p.get(1).asInt());
            String line = w + "x" + h + " 位置 " + locate + ": php " + p + " java [" + box[0] + "," + box[1] + "," + box[2] + ","
                    + box[3] + "] 中心差 (" + dcx / 2.0 + ", " + dcy / 2.0 + ") 宽高差 (" + dw + ", " + dh + ")";
            measured.add(line);
            if (Math.abs(dcx) > 2 * MARK_CENTER || Math.abs(dcy) > 2 * MARK_CENTER || Math.abs(dw) > MARK_SIZE
                    || Math.abs(dh) > MARK_SIZE) {
                problems.add(line);
            }
        }
        System.out.println("[legacy-images] PHP " + golden.get("php").asString() + " 水印外接矩形对照：\n  "
                + String.join("\n  ", measured));
        assertThat(measured).hasSize(18);
        assertThat(problems).as("水印位置与 think-image 的差异超过容差").isEmpty();
    }

    private static int[] redBox(BufferedImage im) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < im.getHeight(); y++) {
            for (int x = 0; x < im.getWidth(); x++) {
                int rgb = im.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                if (r > 200 && g < 120 && b < 120) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return new int[] {minX, minY, maxX, maxY};
    }
}
