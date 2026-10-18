package com.maccms.backend.common.captcha;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文验证码（{@code useZh=true}）的内置字形与默认码表（B08，17 文档第 2.4 节）。
 *
 * <p>旧版随机取 {@code vendor/topthink/think-captcha/assets/zhttfs/*.ttf}（官方 v1.0.7 只带 {@code 1.ttf}；备份的 vendor 里没有
 * 这个目录，旧站开启 useZh 时 {@code verify/index} 直接出错），服务器上也不一定有中文字体。新版与英文验证码一样不加载字体文件，
 * 改用打包在 jar 里的点阵：{@code captcha/zh-glyphs.hex} 是 GNU Unifont 17.0.05 的子集，范围与 {@code 1.ttf} 能画的字相同
 * （GB2312 一、二级汉字与符号、ASCII 0x20–0x7E、1.ttf 按 GBK 映射的 35 个符号；不含私用区），共 7573 个码位，全宽 16×16、
 * 半宽 8×16 两种（SIL OFL 1.1，许可、来源文件与校验值见同目录 {@code zh-glyphs-LICENSE.txt}）。默认码表
 * {@code captcha/think-zhset.txt} 是 think-captcha {@code $config['zhSet']} 原样复制（1500 个字，含重复，重复的字被抽中的概率
 * 与旧版一样更高），其中的字都有字形。
 *
 * <p>没有内置字形的字（繁体、扩展区汉字、私用区等，旧 {@code 1.ttf} 也没有）按单字画 {@link #MISSING} 缺字框，不影响其余字和
 * 校验；旧版 {@code imagettftext} 对这类字画 {@code 1.ttf} 的 {@code .notdef}，该字形没有轮廓，即一块空白。
 */
final class ZhGlyphs {

    static final int SIZE = 16;

    /** 一个点阵字：{@code width} 为 16（全宽）或 8（半宽）；{@code rows} 16 行，每行 16 位，最高位在左，半宽字只用左 8 位。 */
    record Glyph(int width, int[] rows) {
        Glyph {
            rows = rows.clone();
        }

        @Override
        public int[] rows() {
            return rows.clone();
        }

        boolean ink(int row, int col) {
            return (rows[row] & (0x8000 >>> col)) != 0;
        }
    }

    /** 没有内置字形时画的缺字框（全宽，第 1～14 行、第 1～14 列的空心方框）。 */
    static final Glyph MISSING = missingBox();

    private static final String GLYPHS = "/captcha/zh-glyphs.hex";
    private static final String THINK_ZH_SET = "/captcha/think-zhset.txt";

    private ZhGlyphs() {
    }

    /** 码位是否有内置字形。 */
    static boolean has(int codePoint) {
        return Holder.GLYPHS_BY_CODE_POINT.containsKey(codePoint);
    }

    /** 码位的内置字形；没有时为 null。 */
    static Glyph glyph(int codePoint) {
        return Holder.GLYPHS_BY_CODE_POINT.get(codePoint);
    }

    /** 码位的 16 行点阵（见 {@link Glyph#rows()}）；没有字形时为 null。 */
    static int[] rows(int codePoint) {
        Glyph g = glyph(codePoint);
        return g == null ? null : g.rows();
    }

    /** {@code text} 中没有内置字形的码位，去重并保持首次出现的顺序。 */
    static List<Integer> missing(String text) {
        return text.codePoints().filter(cp -> !has(cp)).distinct().boxed().toList();
    }

    /** think-captcha 默认的中文码表。 */
    static String thinkZhSet() {
        return Holder.THINK_ZH_SET_TEXT;
    }

    private static Glyph missingBox() {
        int[] rows = new int[SIZE];
        rows[1] = 0x7FFE;
        rows[14] = 0x7FFE;
        for (int i = 2; i < 14; i++) {
            rows[i] = 0x4002;
        }
        return new Glyph(SIZE, rows);
    }

    private static final class Holder {
        static final Map<Integer, Glyph> GLYPHS_BY_CODE_POINT = load();
        static final String THINK_ZH_SET_TEXT = read(THINK_ZH_SET).strip();

        private static Map<Integer, Glyph> load() {
            Map<Integer, Glyph> out = new HashMap<>(8192);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(open(GLYPHS), StandardCharsets.US_ASCII))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int colon = line.indexOf(':');
                    int digits = colon < 0 ? -1 : line.length() - colon - 1;
                    if (line.isEmpty() || (digits != SIZE * 4 && digits != SIZE * 2)) {
                        throw new IllegalStateException("字形资源格式不对：" + GLYPHS);
                    }
                    int perRow = digits / SIZE;
                    int[] rows = new int[SIZE];
                    for (int i = 0; i < SIZE; i++) {
                        int start = colon + 1 + i * perRow;
                        rows[i] = Integer.parseInt(line, start, start + perRow, 16) << (16 - perRow * 4);
                    }
                    out.put(Integer.parseInt(line, 0, colon, 16), new Glyph(perRow * 4, rows));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return Map.copyOf(out);
        }

        private static String read(String name) {
            try (InputStream in = open(name)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static InputStream open(String name) {
            InputStream in = ZhGlyphs.class.getResourceAsStream(name);
            if (in == null) {
                throw new IllegalStateException("缺少验证码资源 " + name);
            }
            return in;
        }
    }
}
