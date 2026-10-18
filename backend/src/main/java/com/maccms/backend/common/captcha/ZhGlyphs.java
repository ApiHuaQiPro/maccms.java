package com.maccms.backend.common.captcha;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 中文验证码（{@code useZh=true}）的内置字形与默认码表（B08，17 文档第 2.4 节）。
 *
 * <p>旧版随机取 {@code vendor/topthink/think-captcha/assets/zhttfs/*.ttf}；备份的 vendor 里没有这个目录（旧站开启 useZh 时
 * {@code verify/index} 直接出错），服务器上也不一定有中文字体。新版与英文验证码一样不加载字体文件，改用打包在 jar 里的
 * 16×16 点阵：{@code captcha/zh-glyphs.hex} 是 GNU Unifont 17.0.05 的 GB2312 一、二级汉字子集（SIL OFL 1.1，许可与来源见同目录
 * {@code zh-glyphs-LICENSE.txt}）。默认码表 {@code captcha/think-zhset.txt} 是 think-captcha {@code $config['zhSet']} 原样
 * 复制（1500 个字，含重复，重复的字被抽中的概率与旧版一样更高），其中的字都有字形。
 */
final class ZhGlyphs {

    static final int SIZE = 16;

    private static final String GLYPHS = "/captcha/zh-glyphs.hex";
    private static final String THINK_ZH_SET = "/captcha/think-zhset.txt";

    private ZhGlyphs() {
    }

    /** 码位是否有内置字形。 */
    static boolean has(int codePoint) {
        return Holder.ROWS.containsKey(codePoint);
    }

    /** 码位的 16 行点阵（每行 16 位，最高位在左）；没有字形时为 null。 */
    static int[] rows(int codePoint) {
        int[] r = Holder.ROWS.get(codePoint);
        return r == null ? null : r.clone();
    }

    /** think-captcha 默认的中文码表。 */
    static String thinkZhSet() {
        return Holder.THINK_ZH_SET_TEXT;
    }

    private static final class Holder {
        static final Map<Integer, int[]> ROWS = load();
        static final String THINK_ZH_SET_TEXT = read(THINK_ZH_SET).strip();

        private static Map<Integer, int[]> load() {
            Map<Integer, int[]> out = new HashMap<>(8192);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(open(GLYPHS), StandardCharsets.US_ASCII))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int colon = line.indexOf(':');
                    if (line.isEmpty() || colon < 0 || line.length() - colon - 1 != SIZE * 4) {
                        throw new IllegalStateException("字形资源格式不对：" + GLYPHS);
                    }
                    int[] rows = new int[SIZE];
                    for (int i = 0; i < SIZE; i++) {
                        rows[i] = Integer.parseInt(line, colon + 1 + i * 4, colon + 5 + i * 4, 16);
                    }
                    out.put(Integer.parseInt(line, 0, colon, 16), rows);
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
