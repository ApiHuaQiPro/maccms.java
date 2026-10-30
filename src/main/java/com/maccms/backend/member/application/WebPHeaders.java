package com.maccms.backend.member.application;

import java.nio.charset.StandardCharsets;

/**
 * 解码前检查 WebP 容器里声明的全部尺寸（B09 头像，18 文档第 14 节），纯内存计算。
 *
 * <p>解码器只按画布尺寸报 {@code getWidth/getHeight}，而动图帧（{@code ANMF}）与位流（{@code VP8 }/{@code VP8L}）各自声明尺寸：
 * 实测 TwelveMonkeys 3.12.0 对"画布 100×100、首帧声明 16384×16384"的伪造文件按帧尺寸分配内存而 {@code OutOfMemoryError}。
 * 所以逐个检查块：画布、每一帧、每个位流的像素数都不超过 {@code maxPixels}；帧在画布内（WebP 容器规范）；位流不大于所属帧或
 * （静态图）画布；块长度不越界。不是 RIFF/WEBP 的内容不在本类判断范围内。
 */
final class WebPHeaders {

    private WebPHeaders() {
    }

    /** 内容以 {@code RIFF????WEBP} 开头。 */
    static boolean isWebP(byte[] b) {
        return b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    /** 声明的尺寸都在限度内且结构自洽时为 {@code true}；对非 WebP 内容不适用（调用方先用 {@link #isWebP}）。 */
    static boolean withinLimits(byte[] b, long maxPixels) {
        long riffEnd = Math.min(b.length, 8 + u32(b, 4));
        long canvasW = 0;
        long canvasH = 0;
        boolean animated = false;
        int p = 12;
        boolean first = true;
        while (p + 8 <= riffEnd) {
            String type = fourcc(b, p);
            long size = u32(b, p + 4);
            int data = p + 8;
            if (data + size > riffEnd) {
                return false;
            }
            if (first && type.equals("VP8X")) {
                if (size < 10) {
                    return false;
                }
                animated = (b[data] & 0x02) != 0;
                canvasW = u24(b, data + 4) + 1;
                canvasH = u24(b, data + 7) + 1;
                if (canvasW * canvasH > maxPixels) {
                    return false;
                }
            } else if (type.equals("ANMF")) {
                if (!animated || size < 16) {
                    return false;
                }
                long x = u24(b, data) * 2;
                long y = u24(b, data + 3) * 2;
                long w = u24(b, data + 6) + 1;
                long h = u24(b, data + 9) + 1;
                if (x + w > canvasW || y + h > canvasH || w * h > maxPixels) {
                    return false;
                }
                if (!framesWithin(b, data + 16, data + (int) size, w, h, maxPixels)) {
                    return false;
                }
            } else if (type.equals("VP8 ") || type.equals("VP8L")) {
                long[] dim = bitstreamSize(b, type, data, size);
                if (dim == null || dim[0] * dim[1] > maxPixels) {
                    return false;
                }
                if (canvasW > 0 && (dim[0] > canvasW || dim[1] > canvasH)) {
                    return false;
                }
            }
            first = false;
            p = data + (int) size + (int) (size & 1);
        }
        return true;
    }

    private static boolean framesWithin(byte[] b, int from, int to, long frameW, long frameH, long maxPixels) {
        int p = from;
        while (p + 8 <= to) {
            String type = fourcc(b, p);
            long size = u32(b, p + 4);
            int data = p + 8;
            if (data + size > to) {
                return false;
            }
            if (type.equals("VP8 ") || type.equals("VP8L")) {
                long[] dim = bitstreamSize(b, type, data, size);
                if (dim == null || dim[0] * dim[1] > maxPixels || dim[0] > frameW || dim[1] > frameH) {
                    return false;
                }
            }
            p = data + (int) size + (int) (size & 1);
        }
        return true;
    }

    /** 有损：3 字节帧标签 + 起始码 {@code 9d 01 2a} + 各 14 位宽高；无损：签名 {@code 0x2f} + 各 14 位（宽 - 1、高 - 1）。 */
    private static long[] bitstreamSize(byte[] b, String type, int data, long size) {
        if (type.equals("VP8 ")) {
            if (size < 10 || (b[data + 3] & 0xff) != 0x9d || (b[data + 4] & 0xff) != 0x01
                    || (b[data + 5] & 0xff) != 0x2a) {
                return null;
            }
            return new long[] {u16(b, data + 6) & 0x3fff, u16(b, data + 8) & 0x3fff};
        }
        if (size < 5 || (b[data] & 0xff) != 0x2f) {
            return null;
        }
        long bits = u32(b, data + 1);
        return new long[] {(bits & 0x3fff) + 1, ((bits >>> 14) & 0x3fff) + 1};
    }

    private static String fourcc(byte[] b, int p) {
        return new String(b, p, 4, StandardCharsets.ISO_8859_1);
    }

    private static long u16(byte[] b, int p) {
        return (b[p] & 0xff) | (b[p + 1] & 0xff) << 8;
    }

    private static long u24(byte[] b, int p) {
        return (b[p] & 0xff) | (b[p + 1] & 0xff) << 8 | (long) (b[p + 2] & 0xff) << 16;
    }

    private static long u32(byte[] b, int p) {
        return u24(b, p) | (long) (b[p + 3] & 0xff) << 24;
    }
}
