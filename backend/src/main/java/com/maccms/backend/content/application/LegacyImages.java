package com.maccms.backend.content.application;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * 上传图片的缩略图与文字水印（旧 {@code common/model/Image.php} 的 {@code makethumb}、{@code watermark}，经 think-image 的
 * {@code thumb()}、{@code text()}，B13 22 文档第 7.10 节）。几何计算与 think-image 相同（六种缩略方式、九个水印位置）；像素由 Java2D 生成，
 * 与 GD 的输出不保证逐字节相同。读不了的图片（旧版异常被吞掉）返回 null，调用方跳过。
 */
final class LegacyImages {

    private LegacyImages() {
    }

    /**
     * think-image {@code thumb($width, $height, $type)} 后按原类型保存（JPEG 质量 80）。
     *
     * @param ext 原文件的扩展名（决定输出格式：jpg/jpeg、png、gif；其他读不了时返回 null）
     */
    static byte[] thumb(byte[] image, String ext, int width, int height, int type) {
        BufferedImage src = read(image);
        if (src == null || width <= 0 || height <= 0) {
            return null;
        }
        double w = src.getWidth();
        double h = src.getHeight();
        double x = 0;
        double y = 0;
        double tw = width;
        double th = height;
        switch (type) {
            case 1 -> {
                if (w < width && h < height) {
                    return write(src, ext);
                }
                double scale = Math.min(width / w, height / h);
                tw = w * scale;
                th = h * scale;
            }
            case 2 -> {
                double scale = w < width && h < height ? 1 : Math.min(width / w, height / h);
                BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = out.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, width, height);
                hints(g);
                g.drawImage(src, (int) ((width - w * scale) / 2), (int) ((height - h * scale) / 2), (int) (w * scale),
                        (int) (h * scale), null);
                g.dispose();
                return write(out, ext);
            }
            case 3, 4, 5 -> {
                double scale = Math.max(width / w, height / h);
                double cw = width / scale;
                double ch = height / scale;
                if (type == 3) {
                    x = (src.getWidth() - cw) / 2;
                    y = (src.getHeight() - ch) / 2;
                } else if (type == 5) {
                    x = src.getWidth() - cw;
                    y = src.getHeight() - ch;
                }
                w = cw;
                h = ch;
            }
            case 6 -> {
                // 固定尺寸：整张图缩放到 width × height
            }
            default -> {
                return null;
            }
        }
        int ow = (int) tw;
        int oh = (int) th;
        if (ow <= 0 || oh <= 0) {
            return null;
        }
        BufferedImage out = new BufferedImage(ow, oh, src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB
                : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        hints(g);
        g.drawImage(src, 0, 0, ow, oh, (int) x, (int) y, (int) (x + w), (int) (y + h), null);
        g.dispose();
        return write(out, ext);
    }

    /**
     * think-image {@code text($text, $font, $size, $color, $locate)} 后按原类型保存：位置 1–9 依次为左上、上中、右上、左中、中心、右中、左下、
     * 下中、右下；颜色 {@code #RRGGBB} 或 {@code #RRGGBBAA}（GD 的透明度 0–127）。
     */
    static byte[] text(byte[] image, String ext, String text, Font font, double size, String color, int locate) {
        BufferedImage src = read(image);
        if (src == null || font == null) {
            return null;
        }
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(),
                src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        hints(g);
        g.drawImage(src, 0, 0, null);
        // GD 的字号按 96 DPI 的磅值
        Font f = font.deriveFont((float) (size * 96 / 72));
        FontRenderContext frc = g.getFontRenderContext();
        Rectangle2D b = f.getStringBounds(text, frc);
        double tw = b.getWidth();
        double th = b.getHeight();
        double x = 0;
        double y = -b.getY();
        double iw = src.getWidth();
        double ih = src.getHeight();
        switch (locate) {
            case 9 -> {
                x += iw - tw;
                y += ih - th;
            }
            case 7 -> y += ih - th;
            case 1 -> {
            }
            case 3 -> x += iw - tw;
            case 5 -> {
                x += (iw - tw) / 2;
                y += (ih - th) / 2;
            }
            case 8 -> {
                x += (iw - tw) / 2;
                y += ih - th;
            }
            case 6 -> {
                x += iw - tw;
                y += (ih - th) / 2;
            }
            case 2 -> x += (iw - tw) / 2;
            case 4 -> y += (ih - th) / 2;
            default -> {
                g.dispose();
                return null;
            }
        }
        g.setColor(color(color));
        g.setComposite(AlphaComposite.SrcOver);
        g.setFont(f);
        g.drawString(text, (float) x, (float) y);
        g.dispose();
        return write(out, ext);
    }

    static Font font(byte[] ttf) {
        try {
            return ttf == null ? null : Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(ttf));
        } catch (java.awt.FontFormatException | IOException e) {
            return null;
        }
    }

    private static Color color(String c) {
        String s = c == null ? "" : c;
        if (!s.startsWith("#") || (s.length() != 7 && s.length() != 9)) {
            return Color.BLACK;
        }
        try {
            int r = Integer.parseInt(s.substring(1, 3), 16);
            int gr = Integer.parseInt(s.substring(3, 5), 16);
            int b = Integer.parseInt(s.substring(5, 7), 16);
            int a = s.length() == 9 ? Math.min(127, Integer.parseInt(s.substring(7, 9), 16)) : 0;
            return new Color(r, gr, b, 255 - (int) Math.round(a * 255 / 127.0));
        } catch (NumberFormatException e) {
            return Color.BLACK;
        }
    }

    private static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    private static BufferedImage read(byte[] image) {
        try {
            return image == null ? null : ImageIO.read(new ByteArrayInputStream(image));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] write(BufferedImage img, String ext) {
        String e = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        String format = switch (e) {
            case "jpg", "jpeg" -> "jpeg";
            case "png" -> "png";
            case "gif" -> "gif";
            default -> null;
        };
        if (format == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (format.equals("jpeg")) {
                BufferedImage rgb = img;
                if (img.getColorModel().hasAlpha()) {
                    rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = rgb.createGraphics();
                    g.drawImage(img, 0, 0, Color.WHITE, null);
                    g.dispose();
                }
                Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
                ImageWriter w = it.next();
                ImageWriteParam p = w.getDefaultWriteParam();
                p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                p.setCompressionQuality(0.8f);
                try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                    w.setOutput(ios);
                    w.write(null, new IIOImage(rgb, null, null), p);
                } finally {
                    w.dispose();
                }
            } else if (!ImageIO.write(img, format, out)) {
                return null;
            }
            return out.toByteArray();
        } catch (IOException | RuntimeException e2) {
            return null;
        }
    }
}
