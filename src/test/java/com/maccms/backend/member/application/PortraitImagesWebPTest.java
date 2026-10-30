package com.maccms.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.IIOServiceProvider;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * B09 WebP 头像（MACC-23 队长裁定方案 B，18 文档第 14 节）：有损、无损带透明、有损带透明、动图首帧都重新编码为 100×100 的真实
 * JPEG，透明处按白底；截断、伪造、超过像素上限与声明尺寸自相矛盾的文件都按"文件上传失败！"，不抛异常、不按声明尺寸分配内存；
 * 损坏的数据不会让解码器空转（{@link PortraitImages#MAX_EOF_READS}）；加入读取器后图片格式只多 WebP 读取一项，扩展名表不变。
 * 样本见 {@code src/test/resources/member/webp/generate.py}。
 */
class PortraitImagesWebPTest {

    private static final PortraitImages IMAGES = new PortraitImages(100, 100);

    @BeforeAll
    static void loaded() {
        assertThat(IMAGES).isNotNull();
    }

    @Test
    void onlyAWebPReaderIsAddedToImageIo() {
        assertThat(nonJdk(ImageReaderSpi.class))
                .containsExactly("com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi");
        assertThat(nonJdk(ImageWriterSpi.class)).as("没有新的写入器").isEmpty();
        assertThat(ImageIO.getImageWritersByFormatName("webp").hasNext()).isFalse();
        assertThat(ImageIO.getImageReadersByMIMEType("image/webp").hasNext()).isTrue();
    }

    @Test
    void lossyIsReencodedAsJpeg() throws IOException {
        BufferedImage out = jpeg(IMAGES.convert(fixture("lossy.webp"), "me.webp", null));
        // 渐变：左上 (0,0,128) 附近，右下 (255,255,128) 附近
        assertNear(out.getRGB(2, 2), 0, 0, 128, 40);
        assertNear(out.getRGB(97, 97), 255, 255, 128, 40);
    }

    @Test
    void transparencyBecomesWhiteLikeOtherPortraits() throws IOException {
        for (String name : List.of("lossless-alpha.webp", "lossy-alpha.webp")) {
            BufferedImage out = jpeg(IMAGES.convert(fixture(name), "me.WEBP", null));
            assertThat(out.getColorModel().hasAlpha()).isFalse();
            assertNear(out.getRGB(20, 50), 255, 255, 255, 12);
            assertNear(out.getRGB(80, 50), 255, 0, 0, 40);
        }
        // 与 PNG 透明图的处理相同（同一 resize 路径）
        BufferedImage png = new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 200; y++) {
            for (int x = 150; x < 300; x++) {
                png.setRGB(x, y, 0xFFFF0000);
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(png, "png", bytes);
        BufferedImage fromPng = jpeg(IMAGES.convert(bytes.toByteArray(), "me.png", null));
        BufferedImage fromWebP = jpeg(IMAGES.convert(fixture("lossless-alpha.webp"), "me.webp", null));
        assertNear(fromWebP.getRGB(20, 50), red(fromPng.getRGB(20, 50)), green(fromPng.getRGB(20, 50)),
                blue(fromPng.getRGB(20, 50)), 4);
        assertNear(fromWebP.getRGB(80, 50), red(fromPng.getRGB(80, 50)), green(fromPng.getRGB(80, 50)),
                blue(fromPng.getRGB(80, 50)), 4);
    }

    @Test
    void animationUsesTheFirstFrame() throws IOException {
        byte[] anim = fixture("animated.webp");
        assertThat(frames(anim)).as("样本确有两帧").isEqualTo(2);
        BufferedImage out = jpeg(IMAGES.convert(anim, "me.webp", null));
        assertNear(out.getRGB(50, 50), 0, 0, 255, 30);
        assertNear(out.getRGB(5, 95), 0, 0, 255, 30);
    }

    @Test
    void imgdataAndExtensionRulesAreUnchanged() throws IOException {
        byte[] lossy = fixture("lossy.webp");
        String dataUrl = "data:image/webp;base64," + Base64.getEncoder().encodeToString(lossy);
        jpeg(IMAGES.convert(null, null, dataUrl));
        assertThat(IMAGES.convert(lossy, "me.bmp", null).error()).as("扩展名表不变").isEqualTo(PortraitImages.FORBIDDEN_EXT);
        assertThat(IMAGES.convert(lossy, "me", null).error()).isEqualTo(PortraitImages.FORBIDDEN_EXT);
        String bmpUrl = "data:image/bmp;base64," + Base64.getEncoder().encodeToString(lossy);
        assertThat(IMAGES.convert(null, null, bmpUrl).error()).isEqualTo(PortraitImages.FORBIDDEN_EXT);
        // 与其他格式一样按内容解码：扩展名在允许表内即可
        jpeg(IMAGES.convert(lossy, "me.png", null));
        // 内容检查顺序不变：危险内容先于解码
        byte[] script = Arrays.copyOf(lossy, lossy.length + 8);
        System.arraycopy("<script>".getBytes(StandardCharsets.US_ASCII), 0, script, lossy.length, 8);
        assertThat(IMAGES.convert(script, "me.webp", null).error()).isEqualTo(PortraitImages.UNSAFE);
    }

    @Test
    void webpPixelCapIsInclusive() throws IOException {
        jpeg(IMAGES.convert(fixture("at-cap-2048x2048.webp"), "me.webp", null));
        assertThat(IMAGES.convert(fixture("over-cap-2049x2048.webp"), "me.webp", null).error())
                .isEqualTo(PortraitImages.FAILED);
        assertThat(PortraitImages.MAX_WEBP_PIXELS).isEqualTo(2048L * 2048L).isLessThan(PortraitImages.MAX_PIXELS);
    }

    @Test
    void forgedHeadersAreRejectedBeforeDecoding() {
        byte[] small = vp8l(16, 16);
        List<byte[]> forged = List.of(
                riff(chunk("VP8L", vp8l(16384, 16384))),
                riff(chunk("VP8 ", vp8(16383, 16383))),
                riff(vp8x(0, 16384, 16384), chunk("VP8L", small)),
                riff(vp8x(0, 100, 100), chunk("VP8L", vp8l(16384, 16384))),
                // 画布 100×100 的动图，首帧声明 16384×16384（实测不检查时解码器 OutOfMemoryError）
                riff(vp8x(0x02, 100, 100), chunk("ANIM", new byte[6]),
                        chunk("ANMF", anmf(0, 0, 16384, 16384, chunk("VP8L", vp8l(16384, 16384))))),
                // 帧在画布内，帧里的位流比帧大
                riff(vp8x(0x02, 100, 100), chunk("ANIM", new byte[6]),
                        chunk("ANMF", anmf(0, 0, 50, 50, chunk("VP8L", vp8l(4000, 4000))))),
                // 帧越出画布
                riff(vp8x(0x02, 100, 100), chunk("ANIM", new byte[6]),
                        chunk("ANMF", anmf(60, 0, 50, 50, chunk("VP8L", vp8l(50, 50))))),
                // 没有动图标志却有帧
                riff(vp8x(0, 100, 100), chunk("ANMF", anmf(0, 0, 50, 50, chunk("VP8L", vp8l(50, 50))))),
                // 块长度越界
                truncatedChunk(),
                // 有损位流缺起始码
                riff(chunk("VP8 ", new byte[10])));
        for (byte[] f : forged) {
            assertThat(WebPHeaders.isWebP(f)).isTrue();
            assertThat(WebPHeaders.withinLimits(f, PortraitImages.MAX_WEBP_PIXELS)).isFalse();
            assertThat(IMAGES.convert(f, "me.webp", null).error()).isEqualTo(PortraitImages.FAILED);
        }
        assertThat(WebPHeaders.isWebP(new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'})).isFalse();
    }

    @Test
    void validFixturesPassTheHeaderCheck() throws IOException {
        for (String name : List.of("lossy.webp", "lossless-alpha.webp", "lossy-alpha.webp", "animated.webp",
                "at-cap-2048x2048.webp")) {
            assertThat(WebPHeaders.withinLimits(fixture(name), PortraitImages.MAX_WEBP_PIXELS)).as(name).isTrue();
        }
    }

    @Test
    void garbageAndTruncationFailWithTheLegacyMessage() throws IOException {
        assertThat(IMAGES.convert(riff(chunk("VP8 ", vp8garbage())), "me.webp", null).error())
                .isEqualTo(PortraitImages.FAILED);
        assertThat(IMAGES.convert(riff(chunk("VP8L", vp8lGarbage())), "me.webp", null).error())
                .isEqualTo(PortraitImages.FAILED);
        for (String name : List.of("lossy.webp", "lossless-alpha.webp", "lossy-alpha.webp", "animated.webp")) {
            byte[] f = fixture(name);
            for (int len = 1; len < f.length; len++) {
                PortraitImages.Outcome o = IMAGES.convert(Arrays.copyOf(f, len), "me.webp", null);
                assertOkOrFailed(o, name + " 截断到 " + len);
            }
        }
    }

    @Test
    void randomCorruptionNeverEscapesTheJsonContract() throws IOException {
        Random random = new Random(20261018L);
        for (String name : List.of("lossy.webp", "lossless-alpha.webp", "lossy-alpha.webp", "animated.webp")) {
            byte[] f = fixture(name);
            long worst = 0;
            for (int i = 0; i < 400; i++) {
                byte[] m = f.clone();
                int flips = 1 + random.nextInt(4);
                for (int k = 0; k < flips; k++) {
                    m[12 + random.nextInt(m.length - 12)] = (byte) random.nextInt(256);
                }
                long t = System.nanoTime();
                PortraitImages.Outcome o = IMAGES.convert(m, "me.webp", null);
                worst = Math.max(worst, System.nanoTime() - t);
                assertOkOrFailed(o, name + " 变异 " + i);
            }
            System.out.printf("WEBP-MUTATION %s worst %d ms%n", name, worst / 1_000_000);
            // 实测最慢约 0.1 s；没有 MAX_EOF_READS 时这组样本 9 分钟没有跑完。放宽到 10 s 以免慢机器误报
            assertThat(worst / 1_000_000).as(name + " 最慢的变异样本（ms）").isLessThan(10_000);
        }
    }

    private static void assertOkOrFailed(PortraitImages.Outcome o, String what) throws IOException {
        if (o.error() == null) {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(o.jpeg()));
            assertThat(img.getWidth()).as(what).isEqualTo(100);
            assertThat(img.getHeight()).as(what).isEqualTo(100);
        } else {
            assertThat(o.error()).as(what).isEqualTo(PortraitImages.FAILED);
        }
    }

    // ---- 断言与样本工具 ----

    private static Set<String> nonJdk(Class<? extends IIOServiceProvider> type) {
        Set<String> names = new TreeSet<>();
        Iterator<? extends IIOServiceProvider> it = IIORegistry.getDefaultInstance().getServiceProviders(type, false);
        while (it.hasNext()) {
            String n = it.next().getClass().getName();
            if (!n.startsWith("com.sun.") && !n.startsWith("sun.") && !n.startsWith("javax.") && !n.startsWith("java.")) {
                names.add(n);
            }
        }
        return names;
    }

    private static BufferedImage jpeg(PortraitImages.Outcome o) throws IOException {
        assertThat(o.error()).isNull();
        byte[] b = o.jpeg();
        assertThat(new byte[] {b[0], b[1], b[2]}).as("JPEG SOI").containsExactly(0xFF, 0xD8, 0xFF);
        try (MemoryCacheImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(b))) {
            ImageReader r = ImageIO.getImageReaders(in).next();
            assertThat(r.getFormatName()).isEqualToIgnoringCase("jpeg");
            r.setInput(in);
            BufferedImage img = r.read(0);
            r.dispose();
            assertThat(img.getWidth()).isEqualTo(100);
            assertThat(img.getHeight()).isEqualTo(100);
            return img;
        }
    }

    private static int frames(byte[] webp) throws IOException {
        try (MemoryCacheImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(webp))) {
            ImageReader r = ImageIO.getImageReaders(in).next();
            r.setInput(in);
            int n = r.getNumImages(true);
            r.dispose();
            return n;
        }
    }

    private static void assertNear(int rgb, int r, int g, int b, int tolerance) {
        assertThat(Math.abs(red(rgb) - r)).as("R of %08x", rgb).isLessThanOrEqualTo(tolerance);
        assertThat(Math.abs(green(rgb) - g)).as("G of %08x", rgb).isLessThanOrEqualTo(tolerance);
        assertThat(Math.abs(blue(rgb) - b)).as("B of %08x", rgb).isLessThanOrEqualTo(tolerance);
    }

    private static int red(int rgb) {
        return rgb >> 16 & 0xFF;
    }

    private static int green(int rgb) {
        return rgb >> 8 & 0xFF;
    }

    private static int blue(int rgb) {
        return rgb & 0xFF;
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = PortraitImagesWebPTest.class.getResourceAsStream("/member/webp/" + name)) {
            assertThat(in).as(name).isNotNull();
            return in.readAllBytes();
        }
    }

    private static byte[] riff(byte[]... chunks) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(new byte[] {'W', 'E', 'B', 'P'});
        for (byte[] c : chunks) {
            body.writeBytes(c);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'R', 'I', 'F', 'F'});
        out.writeBytes(le(body.size(), 4));
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    private static byte[] chunk(String type, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(le(data.length, 4));
        out.writeBytes(data);
        if (data.length % 2 == 1) {
            out.write(0);
        }
        return out.toByteArray();
    }

    private static byte[] truncatedChunk() {
        byte[] ok = riff(chunk("VP8L", vp8l(16, 16)));
        // 块长度改成远大于文件
        ok[16] = (byte) 0xFF;
        ok[17] = (byte) 0xFF;
        return ok;
    }

    private static byte[] vp8x(int flags, int w, int h) {
        byte[] d = new byte[10];
        d[0] = (byte) flags;
        System.arraycopy(le(w - 1, 3), 0, d, 4, 3);
        System.arraycopy(le(h - 1, 3), 0, d, 7, 3);
        return chunk("VP8X", d);
    }

    private static byte[] anmf(int x, int y, int w, int h, byte[] frame) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(le(x / 2, 3));
        out.writeBytes(le(y / 2, 3));
        out.writeBytes(le(w - 1, 3));
        out.writeBytes(le(h - 1, 3));
        out.writeBytes(le(100, 3));
        out.write(0);
        out.writeBytes(frame);
        return out.toByteArray();
    }

    private static byte[] vp8l(int w, int h) {
        byte[] d = new byte[69];
        d[0] = 0x2f;
        System.arraycopy(le((w - 1) | (long) (h - 1) << 14, 4), 0, d, 1, 4);
        return d;
    }

    private static byte[] vp8(int w, int h) {
        byte[] d = new byte[64];
        d[3] = (byte) 0x9d;
        d[4] = 0x01;
        d[5] = 0x2a;
        System.arraycopy(le(w, 2), 0, d, 6, 2);
        System.arraycopy(le(h, 2), 0, d, 8, 2);
        return d;
    }

    private static byte[] vp8garbage() {
        byte[] d = new byte[1024];
        for (int i = 0; i < d.length; i++) {
            d[i] = (byte) i;
        }
        return d;
    }

    private static byte[] vp8lGarbage() {
        byte[] d = vp8l(64, 64);
        Arrays.fill(d, 5, d.length, (byte) 0xFF);
        return d;
    }

    private static byte[] le(long v, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (v >>> (8 * i));
        }
        return b;
    }
}
