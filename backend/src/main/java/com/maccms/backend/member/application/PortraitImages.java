package com.maccms.backend.member.application;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.spi.IIORegistry;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * 头像的检查与转换（B09，18 文档第 3.8 节），纯内存计算，不读写文件。检查顺序与提示照搬旧 {@code Upload::upload}
 * （{@code common/model/Upload.php:116-221}）；之后按 {@code user.portrait_size} 固定尺寸拉伸（旧 {@code think\Image::thumb(w, h, 6)}），
 * 重新编码为 JPEG（质量 80，与 think-image 保存 jpg 的默认质量相同），透明处按白底。
 *
 * <p>与旧版的差异（D-B09-9）：旧版先把上传的原文件落盘再用缩略图覆盖，扩展名在允许表内但不是图片的文件会留在磁盘上；新版只产出重新
 * 编码的 JPEG。旧版按 {@code finfo} 判断 {@code text/x-php}，新版按内容以 {@code <?php} 开头判断（同一提示）。
 *
 * <p>WebP（MACC-23 队长裁定方案 B，18 文档第 14 节）：由 TwelveMonkeys {@code imageio-webp} 解码（JDK 没有 WebP 解码器），同样
 * 重新编码为 JPEG；动图只取第一帧（旧版 GD 对动图 Fatal error，D-B09-13）。解码前由 {@link WebPHeaders} 检查容器里声明的全部尺寸，
 * 且 WebP 另有更低的像素上限 {@link #MAX_WEBP_PIXELS}（该解码器同样像素数的耗时约为 JDK PNG 的 8 倍）。读取器在本类加载时显式
 * 注册，不依赖首次使用 ImageIO 的线程能否看到可执行 jar 内的 SPI 声明；图片流显式用 {@link MemoryCacheImageInputStream}，不经
 * imageio-core 注册的 {@code ImageInputStreamSpi}，也不写临时文件，并给"读到流尾"的次数设上限 {@link #MAX_EOF_READS}。
 */
final class PortraitImages {

    static final String NO_INPUT = "未找到上传的文件(原因：表单名可能错误，默认表单名“file”或“imgdata”)！";
    static final String FORBIDDEN_EXT = "非系统允许的上传格式！";
    static final String FAILED = "文件上传失败！";
    static final String UNSAFE = "文件包含危险内容！";

    /** 旧 {@code Upload.php:53-55} 的三类扩展名：{@code $upload_image_ext}、{@code $upload_file_ext}、{@code $upload_media_ext}。 */
    private static final Set<String> IMAGE_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final Set<String> FILE_EXT = Set.of("doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf", "wps", "txt",
            "rar", "zip", "torrent");
    private static final Set<String> MEDIA_EXT = Set.of("rm", "rmvb", "avi", "mkv", "mp4", "mp3");
    /** 旧 {@code Upload.php:116}。 */
    private static final Pattern DATA_URL = Pattern.compile("^(data:\\s*image/(\\w+);base64,)");
    /** 旧 {@code Upload.php:180}：对首尾各 512 字节的十六进制文本匹配（不分大小写）。 */
    private static final Pattern UNSAFE_HEX = Pattern.compile("(3c25.*?28.*?29.*?253e)|(3c3f.*?28.*?29.*?3f3e)"
            + "|(3C534352495054)|(2F5343524950543E)|(3C736372697074)|(2F7363726970743E)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** 解码前按图片头部声明的尺寸拒绝过大的图片（防解压炸弹）。 */
    static final long MAX_PIXELS = 40_000_000L;
    /**
     * WebP 的像素上限（2048×2048）。实测（JDK 21，单线程，预热后）该解码器 4 MP 约 0.9 s、16.7 MP 约 4.7 s、36 MP 约 4.5～7 s，
     * 而 JDK 解码 36 MP 的 PNG/JPEG 约 0.4～0.6 s；按这个上限，WebP 最坏耗时与其他格式在 {@link #MAX_PIXELS} 下的同一量级。
     */
    static final long MAX_WEBP_PIXELS = 2048L * 2048L;
    /**
     * 解码器在流结束后还在要数据的次数上限。TwelveMonkeys 的无损位读取器在文件尾吞掉 {@code EOFException} 继续用残留的位解码，
     * 截断或损坏的文件因此会空转（去掉本上限时 PortraitImagesWebPTest 的 1600 个变异样本 9 分钟没有跑完，线程停在该读取器的
     * {@code refillBuffer}；加上后最慢的一个约 0.1 s）。合法样本（含 2000×2000 有损/无损）解码时最多 31 次。
     */
    static final int MAX_EOF_READS = 1024;

    static {
        IIORegistry.getDefaultInstance().registerServiceProvider(new WebPImageReaderSpi());
    }

    private final int width;
    private final int height;

    PortraitImages(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /** 成功时 {@code jpeg} 非空；失败时 {@code error} 是旧版提示。 */
    record Outcome(byte[] jpeg, String error) {
        static Outcome fail(String error) {
            return new Outcome(null, error);
        }
    }

    Outcome convert(byte[] file, String fileName, String imgdata) {
        byte[] content;
        if (imgdata != null && !imgdata.isEmpty()) {
            Matcher m = DATA_URL.matcher(imgdata);
            if (!m.find()) {
                return Outcome.fail(NO_INPUT);
            }
            if (!IMAGE_EXT.contains(m.group(2))) {
                return Outcome.fail(FORBIDDEN_EXT);
            }
            // PHP base64_decode（非严格）忽略不属于字母表的字符
            content = Base64.getMimeDecoder().decode(imgdata.substring(m.end()).getBytes(StandardCharsets.ISO_8859_1));
            if (content.length == 0) {
                return Outcome.fail(FAILED);
            }
        } else {
            if (file == null) {
                return Outcome.fail(NO_INPUT);
            }
            if (startsWithPhp(file)) {
                return Outcome.fail(FORBIDDEN_EXT);
            }
            String ext = extension(fileName);
            if (!IMAGE_EXT.contains(ext) && !FILE_EXT.contains(ext) && !MEDIA_EXT.contains(ext)) {
                return Outcome.fail(FORBIDDEN_EXT);
            }
            content = file;
        }
        if (unsafe(content)) {
            return Outcome.fail(UNSAFE);
        }
        BufferedImage source = decode(content);
        if (source == null) {
            // 旧版：扩展名允许但不是图片、或 GD 读不了时 make_thumb_faild，文案与 upload_faild 相同
            return Outcome.fail(FAILED);
        }
        try {
            return new Outcome(jpeg(resize(source)), null);
        } catch (IOException e) {
            return Outcome.fail(FAILED);
        }
    }

    /**
     * 头像登记附件的 {@code annex_type}（D-B09-9）：{@code imgdata} 时为 data URL 里的图片类型（{@code Upload.php:118-119}），否则按扩展名的
     * 分类 {@code image}/{@code file}/{@code media}（{@code Upload.php:144-152}；扩展名属于"文件"或"媒体"类而内容是图片时，旧版照样生成头像，
     * 登记的类型是 {@code file}/{@code media}）。只在 {@link #convert} 成功后调用。
     */
    static String annexType(String fileName, String imgdata) {
        if (imgdata != null && !imgdata.isEmpty()) {
            Matcher m = DATA_URL.matcher(imgdata);
            return m.find() ? m.group(2) : "image";
        }
        String ext = extension(fileName);
        return FILE_EXT.contains(ext) ? "file" : MEDIA_EXT.contains(ext) ? "media" : "image";
    }

    /** {@code strtolower(pathinfo($name, PATHINFO_EXTENSION))}。 */
    static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        String base = fileName.substring(Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1);
        int dot = base.lastIndexOf('.');
        return dot < 0 ? "" : base.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static boolean unsafe(byte[] content) {
        HexFormat hex = HexFormat.of();
        String text;
        if (content.length > 512) {
            text = hex.formatHex(content, 0, 512) + hex.formatHex(content, content.length - 512, content.length);
        } else {
            text = hex.formatHex(content);
        }
        return UNSAFE_HEX.matcher(text).find();
    }

    private static boolean startsWithPhp(byte[] b) {
        return b.length >= 5 && new String(b, 0, 5, StandardCharsets.ISO_8859_1).equalsIgnoreCase("<?php");
    }

    private static BufferedImage decode(byte[] content) {
        boolean webp = WebPHeaders.isWebP(content);
        if (webp && !WebPHeaders.withinLimits(content, MAX_WEBP_PIXELS)) {
            return null;
        }
        try (ImageInputStream in = new EofBudgetInputStream(content)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > (webp ? MAX_WEBP_PIXELS : MAX_PIXELS)) {
                    return null;
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 读到流尾超过 {@link #MAX_EOF_READS} 次后抛非受检异常，越过解码器内部对 {@code EOFException} 的捕获。 */
    private static final class EofBudgetInputStream extends MemoryCacheImageInputStream {
        private int eofReads;

        EofBudgetInputStream(byte[] content) {
            super(new ByteArrayInputStream(content));
        }

        @Override
        public int read() throws IOException {
            return counted(super.read());
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return counted(super.read(b, off, len));
        }

        private int counted(int n) {
            if (n < 0 && ++eofReads > MAX_EOF_READS) {
                throw new IllegalStateException("image data exhausted", null);
            }
            return n;
        }
    }

    private BufferedImage resize(BufferedImage source) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.8f);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
