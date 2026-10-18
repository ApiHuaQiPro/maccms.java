package com.maccms.backend.member.application;

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
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/**
 * 头像的检查与转换（B09，18 文档第 3.8 节），纯内存计算，不读写文件。检查顺序与提示照搬旧 {@code Upload::upload}
 * （{@code common/model/Upload.php:116-221}）；之后按 {@code user.portrait_size} 固定尺寸拉伸（旧 {@code think\Image::thumb(w, h, 6)}），
 * 重新编码为 JPEG（质量 80，与 think-image 保存 jpg 的默认质量相同），透明处按白底。
 *
 * <p>与旧版的差异（D-B09-9）：旧版先把上传的原文件落盘再用缩略图覆盖，扩展名在允许表内但不是图片的文件会留在磁盘上；新版只产出重新
 * 编码的 JPEG。{@code webp} 没有 JDK 解码器，按"文件上传失败！"处理。旧版按 {@code finfo} 判断 {@code text/x-php}，新版按内容以
 * {@code <?php} 开头判断（同一提示）。
 */
final class PortraitImages {

    static final String NO_INPUT = "未找到上传的文件(原因：表单名可能错误，默认表单名“file”或“imgdata”)！";
    static final String FORBIDDEN_EXT = "非系统允许的上传格式！";
    static final String FAILED = "文件上传失败！";
    static final String UNSAFE = "文件包含危险内容！";

    private static final Set<String> IMAGE_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final Set<String> OTHER_EXT = Set.of("doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf", "wps", "txt",
            "rar", "zip", "torrent", "rm", "rmvb", "avi", "mkv", "mp4", "mp3");
    /** 旧 {@code Upload.php:116}。 */
    private static final Pattern DATA_URL = Pattern.compile("^(data:\\s*image/(\\w+);base64,)");
    /** 旧 {@code Upload.php:180}：对首尾各 512 字节的十六进制文本匹配（不分大小写）。 */
    private static final Pattern UNSAFE_HEX = Pattern.compile("(3c25.*?28.*?29.*?253e)|(3c3f.*?28.*?29.*?3f3e)"
            + "|(3C534352495054)|(2F5343524950543E)|(3C736372697074)|(2F7363726970743E)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** 解码前按图片头部声明的尺寸拒绝过大的图片（防解压炸弹）。 */
    private static final long MAX_PIXELS = 40_000_000L;

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
            if (!IMAGE_EXT.contains(ext) && !OTHER_EXT.contains(ext)) {
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
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (in == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > MAX_PIXELS) {
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
