package com.maccms.backend.common.captcha;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.random.RandomGenerator;
import javax.imageio.ImageIO;

/**
 * 验证码 PNG（B08）。版式按 think-captcha {@code entry()}：底色 {@code bg}，整张图一种随机深色（RGB 各 1～150），第 i 个字符的
 * 左边距累加 {@code fontSize × 1.2～1.6}、旋转 −40°～40°、基线在 {@code fontSize × 1.6}；{@code useNoise} 时画 10 种浅色 × 5 个
 * 杂点字符，{@code useCurve} 时画两段正弦干扰线。
 *
 * <p>与旧版的差异（D-B08-5）：字形不用 TrueType 字体（旧版随机取 {@code assets/ttfs/*.ttf}，这些文件在不入 git 的 vendor 里，
 * 服务器上也不一定有可用字体），改为内置的笔画字形（0-9、A-Z，小写按大写画；校验本来就不分大小写），只用 Java2D 画线，
 * 不加载任何字体文件。图片尺寸与旧公式相同，像素内容不同（旧版本身每次随机）。
 *
 * <p>中文验证码（{@code useZh}）按旧版中文分支的版式：第 i 个字（从 0 起）的左边距 {@code fontSize × (i+1) × 1.5}、基线
 * {@code fontSize + 10～20}、旋转 −40°～40°；字形是 {@link ZhGlyphs} 的 16×16 点阵，按 GD 的磅→像素（96/72）放大到
 * {@code fontSize} 磅的字面大小，同样不加载字体文件。
 */
final class CaptchaImage {

    /** 4 × 6 网格上的折线，"|" 分隔笔画。 */
    private static final Map<Character, String> GLYPHS = Map.ofEntries(
            Map.entry('0', "0,0 4,0 4,6 0,6 0,0|0,6 4,0"),
            Map.entry('1', "1,1 2,0 2,6|1,6 3,6"),
            Map.entry('2', "0,1 1,0 3,0 4,1 4,2 0,6 4,6"),
            Map.entry('3', "0,0 4,0 2,2 3,2 4,3 4,5 3,6 1,6 0,5"),
            Map.entry('4', "3,6 3,0 0,4 4,4"),
            Map.entry('5', "4,0 0,0 0,3 3,3 4,4 4,5 3,6 0,6"),
            Map.entry('6', "4,0 1,0 0,1 0,5 1,6 3,6 4,5 4,4 3,3 0,3"),
            Map.entry('7', "0,0 4,0 1,6"),
            Map.entry('8', "1,0 3,0 4,1 4,2 3,3 1,3 0,2 0,1 1,0|1,3 0,4 0,5 1,6 3,6 4,5 4,4 3,3"),
            Map.entry('9', "4,3 1,3 0,2 0,1 1,0 3,0 4,1 4,5 3,6 0,6"),
            Map.entry('A', "0,6 2,0 4,6|1,3 3,3"),
            Map.entry('B', "0,0 0,6 3,6 4,5 4,4 3,3 0,3|0,0 3,0 4,1 4,2 3,3"),
            Map.entry('C', "4,0 1,0 0,1 0,5 1,6 4,6"),
            Map.entry('D', "0,0 0,6 2,6 4,4 4,2 2,0 0,0"),
            Map.entry('E', "4,0 0,0 0,6 4,6|0,3 3,3"),
            Map.entry('F', "4,0 0,0 0,6|0,3 3,3"),
            Map.entry('G', "4,1 3,0 1,0 0,1 0,5 1,6 3,6 4,5 4,3 2,3"),
            Map.entry('H', "0,0 0,6|4,0 4,6|0,3 4,3"),
            Map.entry('I', "1,0 3,0|2,0 2,6|1,6 3,6"),
            Map.entry('J', "1,0 4,0|3,0 3,5 2,6 1,6 0,5"),
            Map.entry('K', "0,0 0,6|4,0 0,4|1,3 4,6"),
            Map.entry('L', "0,0 0,6 4,6"),
            Map.entry('M', "0,6 0,0 2,3 4,0 4,6"),
            Map.entry('N', "0,6 0,0 4,6 4,0"),
            Map.entry('O', "1,0 3,0 4,1 4,5 3,6 1,6 0,5 0,1 1,0"),
            Map.entry('P', "0,6 0,0 3,0 4,1 4,2 3,3 0,3"),
            Map.entry('Q', "1,0 3,0 4,1 4,5 3,6 1,6 0,5 0,1 1,0|2,4 4,6"),
            Map.entry('R', "0,6 0,0 3,0 4,1 4,2 3,3 0,3|2,3 4,6"),
            Map.entry('S', "4,1 3,0 1,0 0,1 0,2 1,3 3,3 4,4 4,5 3,6 1,6 0,5"),
            Map.entry('T', "0,0 4,0|2,0 2,6"),
            Map.entry('U', "0,0 0,5 1,6 3,6 4,5 4,0"),
            Map.entry('V', "0,0 2,6 4,0"),
            Map.entry('W', "0,0 1,6 2,3 3,6 4,0"),
            Map.entry('X', "0,0 4,6|4,0 0,6"),
            Map.entry('Y', "0,0 2,3 4,0|2,3 2,6"),
            Map.entry('Z', "0,0 4,0 0,6 4,6"));

    /** 码表里没有字形的可见字符（标点等）画成方框加对角线。 */
    private static final String FALLBACK = "0,0 4,0 4,6 0,6 0,0|0,0 4,6";

    /** think-captcha {@code _writeNoise} 的杂点字符集。 */
    private static final String NOISE_SET = "2345678abcdefhijkmnpqrstuvwxyz";

    private CaptchaImage() {
    }

    static byte[] render(String code, CaptchaSettings s, RandomGenerator random) {
        int w = Math.max(1, s.width());
        int h = Math.max(1, s.height());
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int[] bg = s.bg();
            g.setColor(new Color(bg[0], bg[1], bg[2]));
            g.fillRect(0, 0, w, h);
            Color ink = new Color(between(random, 1, 150), between(random, 1, 150), between(random, 1, 150));
            double fs = s.fontSize();
            if (s.useNoise()) {
                for (int i = 0; i < 10; i++) {
                    g.setColor(new Color(between(random, 150, 225), between(random, 150, 225),
                            between(random, 150, 225)));
                    for (int j = 0; j < 5; j++) {
                        char c = NOISE_SET.charAt(random.nextInt(NOISE_SET.length()));
                        drawGlyph(g, c, between(random, -10, w), between(random, -10, h), 9, 0, 1f);
                    }
                }
            }
            if (s.useCurve()) {
                drawCurve(g, ink, w, h, fs, random);
            }
            g.setColor(ink);
            if (s.useZh()) {
                int[] cps = code.codePoints().toArray();
                for (int i = 0; i < cps.length; i++) {
                    double baseline = fs + between(random, 10, 20);
                    double angle = Math.toRadians(between(random, -40, 40));
                    drawZhGlyph(g, cps[i], fs * (i + 1) * 1.5, baseline, fs * 96 / 72, angle);
                }
                return png(img);
            }
            double x = 0;
            float stroke = (float) Math.max(1.5, fs / 8);
            for (int i = 0; i < code.length(); i++) {
                x += fs * 1.2 + random.nextDouble() * fs * 0.4;
                double angle = Math.toRadians(between(random, -40, 40));
                drawGlyph(g, code.charAt(i), x, fs * 1.6 - fs, fs, angle, stroke);
            }
        } finally {
            g.dispose();
        }
        return png(img);
    }

    private static byte[] png(BufferedImage img) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** 在 (x, top) 画一个高 {@code height} 的字形，绕字形中心旋转 {@code angle}。 */
    private static void drawGlyph(Graphics2D g, char c, double x, double top, double height, double angle,
                                  float stroke) {
        String def = GLYPHS.getOrDefault(Character.toUpperCase(c), FALLBACK);
        double unit = height / 6.0;
        Path2D.Double path = new Path2D.Double();
        for (String part : def.split("\\|")) {
            String[] pts = part.split(" ");
            for (int i = 0; i < pts.length; i++) {
                String[] xy = pts[i].split(",");
                double px = x + Integer.parseInt(xy[0]) * unit;
                double py = top + Integer.parseInt(xy[1]) * unit;
                if (i == 0) {
                    path.moveTo(px, py);
                } else {
                    path.lineTo(px, py);
                }
            }
        }
        AffineTransform saved = g.getTransform();
        g.rotate(angle, x + 2 * unit, top + 3 * unit);
        g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(path);
        g.setTransform(saved);
    }

    /**
     * 在左边距 {@code x}、基线 {@code baseline} 处画一个边长 {@code em} 的点阵字（Unifont 的基线在第 14 行下沿），绕字中心旋转
     * {@code angle}。
     */
    private static void drawZhGlyph(Graphics2D g, int codePoint, double x, double baseline, double em, double angle) {
        int[] rows = ZhGlyphs.rows(codePoint);
        if (rows == null) {
            return;
        }
        double cell = em / ZhGlyphs.SIZE;
        double top = baseline - cell * 14;
        Path2D.Double path = new Path2D.Double();
        for (int r = 0; r < ZhGlyphs.SIZE; r++) {
            for (int c = 0; c < ZhGlyphs.SIZE; c++) {
                if ((rows[r] & (0x8000 >>> c)) != 0) {
                    path.append(new Rectangle2D.Double(x + c * cell, top + r * cell, cell + 0.35, cell + 0.35), false);
                }
            }
        }
        AffineTransform saved = g.getTransform();
        g.rotate(angle, x + em / 2, top + em / 2);
        g.fill(path);
        g.setTransform(saved);
    }

    /** think-captcha {@code _writeCurve}：两段相接的正弦曲线，线宽约 {@code fontSize / 5}。 */
    private static void drawCurve(Graphics2D g, Color ink, int w, int h, double fs, RandomGenerator random) {
        g.setColor(ink);
        g.setStroke(new BasicStroke((float) Math.max(1, (int) (fs / 5)), BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        double a = between(random, 1, Math.max(1, h / 2));
        double b = between(random, -h / 4, h / 4);
        double f = between(random, -h / 4, h / 4);
        double t = between(random, h, Math.max(h, w * 2));
        double omega = 2 * Math.PI / t;
        int x2 = between(random, w / 2, Math.max(w / 2, (int) (w * 0.8)));
        Path2D.Double path = new Path2D.Double();
        double py = 0;
        for (int px = 0; px <= x2; px++) {
            py = a * Math.sin(omega * px + f) + b + h / 2.0;
            if (px == 0) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }
        a = between(random, 1, Math.max(1, h / 2));
        f = between(random, -h / 4, h / 4);
        t = between(random, h, Math.max(h, w * 2));
        omega = 2 * Math.PI / t;
        b = py - a * Math.sin(omega * x2 + f) - h / 2.0;
        for (int px = x2; px <= w; px++) {
            path.lineTo(px, a * Math.sin(omega * px + f) + b + h / 2.0);
        }
        g.draw(path);
    }

    /** 闭区间 [lo, hi] 的随机整数（同 {@code mt_rand(lo, hi)}；hi < lo 时取 lo）。 */
    private static int between(RandomGenerator random, int lo, int hi) {
        return hi <= lo ? lo : lo + random.nextInt(hi - lo + 1);
    }
}
