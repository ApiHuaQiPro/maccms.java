package com.maccms.backend.site.application;

import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;
import com.maccms.backend.common.clientip.IpAddress;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * 二维码（B06，旧 index/controller/Qrcode.php:14-24 的 {@code QR::png($url, false, QR_ECLEVEL_M, 10, 2)}，16 文档第 4.7 节）：
 * 本地生成，不调用任何外部服务。纠错等级 M、每模块 10 像素、静区 2 模块、黑白 PNG。
 *
 * <p>输入校验：{@link #isLegacyValidUrl}（PHP {@code filter_var($url, FILTER_VALIDATE_URL)}，由 PHP 黄金向量锁定）且不超过
 * {@value #MAX_BYTES} 字节。PNG 的字节与 phpqrcode 不同（掩码选择、压缩不同），验收按"解码出同一文本、版本相同"。
 */
public final class QrcodeImages {

    /** 输入上限（字节）；纠错 M 时版本 40 的字节容量为 2331，旧版超出容量时 phpqrcode 报错。 */
    public static final int MAX_BYTES = 1024;
    static final int SCALE = 10;
    static final int MARGIN = 2;
    /** FILTER_SANITIZE_URL 保留的字符（ext/filter/sanitizing_filters.c）：字母、数字与 {@code $-_.+!*'(),{}|\^~[]`<>#%";/?:@&=}。 */
    private static final String URL_SAFE_PUNCT = "$-_.+!*'(),{}|\\^~[]`<>#%\";/?:@&=";
    private static final String USERINFO_PUNCT = "-._~!$&'()*+,;=:";

    private QrcodeImages() {
    }

    /** 合法时返回 PNG 字节，否则为空。 */
    public static Optional<byte[]> png(String url) {
        if (url == null || url.isEmpty() || url.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES
                || !isLegacyValidUrl(url)) {
            return Optional.empty();
        }
        QRCode code;
        try {
            code = Encoder.encode(url, ErrorCorrectionLevel.M, Map.of(EncodeHintType.CHARACTER_SET, "ISO-8859-1"));
        } catch (WriterException e) {
            return Optional.empty();
        }
        return Optional.of(encodePng(code.getMatrix()));
    }

    /**
     * 1 位灰度 PNG（IHDR + IDAT + IEND，扫描行过滤类型 0，zlib 压缩）：深色模块为 0（黑），其余为 1（白）。不经 javax.imageio
     * （它在写出流时可能使用磁盘缓存）。
     */
    static byte[] encodePng(ByteMatrix m) {
        return encodePng(m, MARGIN);
    }

    /**
     * 会员中心微信支付二维码（B10，旧 {@code user/qrcode}：{@code QRcode::png($data, false, QR_ECLEVEL_L, 10)}，静区为 phpqrcode 默认的
     * 4 模块，index/controller/User.php:535-547）：{@code data} 须以 {@code weixin} 开头、不超过 {@value #MAX_BYTES} 字节，否则为空。
     * B24 前没有入口会生成这种地址；本地生成，不调用外部服务。
     */
    public static Optional<byte[]> weixinPng(String data) {
        if (data == null || !data.startsWith("weixin") || data.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return Optional.empty();
        }
        QRCode code;
        try {
            code = Encoder.encode(data, ErrorCorrectionLevel.L, Map.of(EncodeHintType.CHARACTER_SET, "ISO-8859-1"));
        } catch (WriterException e) {
            return Optional.empty();
        }
        return Optional.of(encodePng(code.getMatrix(), 4));
    }

    static byte[] encodePng(ByteMatrix m, int margin) {
        int size = (m.getWidth() + 2 * margin) * SCALE;
        int rowBytes = (size + 7) / 8;
        byte[] raw = new byte[(rowBytes + 1) * size];
        for (int y = 0; y < size; y++) {
            int base = y * (rowBytes + 1);
            raw[base] = 0;
            int my = y / SCALE - margin;
            for (int x = 0; x < size; x++) {
                int mx = x / SCALE - margin;
                boolean dark = mx >= 0 && my >= 0 && mx < m.getWidth() && my < m.getHeight() && m.get(mx, my) == 1;
                if (!dark) {
                    raw[base + 1 + x / 8] |= (byte) (0x80 >>> (x % 8));
                }
            }
        }
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream idat = new ByteArrayOutputStream(raw.length / 4 + 64);
        byte[] buf = new byte[4096];
        while (!deflater.finished()) {
            idat.write(buf, 0, deflater.deflate(buf));
        }
        deflater.end();
        ByteArrayOutputStream out = new ByteArrayOutputStream(idat.size() + 64);
        out.writeBytes(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, size);
        putInt(ihdr, 4, size);
        ihdr[8] = 1; // 位深 1
        ihdr[9] = 0; // 灰度
        chunk(out, "IHDR", ihdr);
        chunk(out, "IDAT", idat.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] len = new byte[4];
        putInt(len, 0, data.length);
        out.writeBytes(len);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        byte[] c = new byte[4];
        putInt(c, 0, (int) crc.getValue());
        out.writeBytes(c);
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }

    /**
     * PHP {@code filter_var($s, FILTER_VALIDATE_URL)}（ext/filter/logical_filters.c 的 php_filter_validate_url，无附加标志）：
     * <ol>
     * <li>按 FILTER_SANITIZE_URL 会被删除的字符（空白、控制字符、非 ASCII 等）出现即不合法；</li>
     * <li>按 parse_url 拆出 scheme、authority（userinfo@host:port）；没有 scheme 不合法；端口须是 0..65535 的数字；</li>
     * <li>scheme 为 http/https（不区分大小写）时必须有 host：方括号内为合法 IPv6 则合法；否则按主机名规则（字母数字与连字符、
     * 标签首尾为字母数字、标签 ≤ 63、总长 ≤ 253，末尾的点忽略）；</li>
     * <li>没有 host 时只有 scheme 为 mailto、news、file 才合法；</li>
     * <li>userinfo 只能含字母数字、{@code -._~!$&'()*+,;=:} 与 {@code %HH}。</li>
     * </ol>
     */
    public static boolean isLegacyValidUrl(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(isAsciiAlnum(c) || URL_SAFE_PUNCT.indexOf(c) >= 0)) {
                return false;
            }
        }
        int colon = s.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        String scheme = s.substring(0, colon);
        for (int i = 0; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            if (!(isAsciiAlnum(c) || c == '+' || c == '-' || c == '.')) {
                return false;
            }
        }
        String rest = s.substring(colon + 1);
        String host = null;
        String userinfo = null;
        if (rest.startsWith("//")) {
            String afterSlashes = rest.substring(2);
            int end = afterSlashes.length();
            for (int i = 0; i < afterSlashes.length(); i++) {
                char c = afterSlashes.charAt(i);
                if (c == '/' || c == '?' || c == '#') {
                    end = i;
                    break;
                }
            }
            String authority = afterSlashes.substring(0, end);
            // file:///etc/passwd 这类空 authority：没有 host（http/https 随后因缺 host 不合法）
            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                userinfo = authority.substring(0, at);
                authority = authority.substring(at + 1);
            }
            String hostPort = authority;
            String port = null;
            if (hostPort.startsWith("[")) {
                int close = hostPort.indexOf(']');
                if (close < 0) {
                    return false;
                }
                host = hostPort.substring(0, close + 1);
                String tail = hostPort.substring(close + 1);
                if (!tail.isEmpty()) {
                    if (!tail.startsWith(":")) {
                        return false;
                    }
                    port = tail.substring(1);
                }
            } else {
                int pc = hostPort.lastIndexOf(':');
                if (pc >= 0) {
                    host = hostPort.substring(0, pc);
                    port = hostPort.substring(pc + 1);
                } else {
                    host = hostPort;
                }
            }
            if (port != null && !port.isEmpty()) {
                if (port.length() > 5 || !port.chars().allMatch(ch -> ch >= '0' && ch <= '9')
                        || Integer.parseInt(port) > 65535) {
                    return false;
                }
            }
            if (host.isEmpty()) {
                host = null;
            }
        }
        if (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) {
            if (host == null) {
                return false;
            }
            if (host.startsWith("[") && host.endsWith("]")) {
                return isIpv6(host.substring(1, host.length() - 1));
            }
            if (!isHostname(host)) {
                return false;
            }
        }
        if (host == null && !scheme.equals("mailto") && !scheme.equals("news") && !scheme.equals("file")) {
            return false;
        }
        if (userinfo != null) {
            int at = userinfo.indexOf(':');
            String user = at < 0 ? userinfo : userinfo.substring(0, at);
            String pass = at < 0 ? null : userinfo.substring(at + 1);
            return isUserinfo(user) && (pass == null || isUserinfo(pass));
        }
        return true;
    }

    private static boolean isIpv6(String s) {
        return s.indexOf(':') >= 0 && IpAddress.parse(s).isPresent();
    }

    private static boolean isHostname(String host) {
        String h = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        if (h.isEmpty() || h.length() > 253) {
            return false;
        }
        for (String label : h.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || !isAsciiAlnum(label.charAt(0))
                    || !isAsciiAlnum(label.charAt(label.length() - 1))) {
                return false;
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (!isAsciiAlnum(c) && c != '-') {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isUserinfo(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length() || Character.digit(s.charAt(i + 1), 16) < 0
                        || Character.digit(s.charAt(i + 2), 16) < 0) {
                    return false;
                }
                i += 2;
            } else if (!isAsciiAlnum(c) && USERINFO_PUNCT.indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
