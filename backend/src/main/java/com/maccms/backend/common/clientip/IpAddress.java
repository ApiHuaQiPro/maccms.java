package com.maccms.backend.common.clientip;

import java.util.Arrays;
import java.util.Optional;

/**
 * IP 字面量（B06，D2 ④，docs/architecture/16-b06-misc-pages-provide.md 第 2.2 节第 4 条）。只做字面量解析，从不做 DNS 查询
 * （不调用 {@code InetAddress.getByName}）。
 *
 * <ul>
 * <li>IPv4：恰好 4 段十进制，每段 0–255，不允许前导 0（{@code 01.2.3.4} 不合法）、空段、正负号与空白。</li>
 * <li>IPv6：最多 8 组 1–4 位十六进制（大小写均可），至多一个 {@code ::}，末尾可为点分 IPv4；区域标识（{@code %eth0}）、方括号、
 * 端口一律不合法。IPv4 映射地址（{@code ::ffff:a.b.c.d}）归一为 IPv4。</li>
 * <li>{@link #toString()} 为规范形式：IPv4 点分十进制；IPv6 按 RFC 5952（小写、去前导 0、最长的连续零组压缩为 {@code ::}，
 * 长度相同取最左，单个零组不压缩）。</li>
 * </ul>
 */
public final class IpAddress {

    private static final byte[] V4_MAPPED_PREFIX = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff};

    private final byte[] bytes;

    private IpAddress(byte[] bytes) {
        this.bytes = bytes;
    }

    /** 解析；不合法时为空。 */
    public static Optional<IpAddress> parse(String s) {
        if (s == null || s.isEmpty() || s.length() > 45) {
            return Optional.empty();
        }
        byte[] b = s.indexOf(':') >= 0 ? parseV6(s) : parseV4(s);
        if (b == null) {
            return Optional.empty();
        }
        if (b.length == 16 && Arrays.equals(Arrays.copyOf(b, 12), V4_MAPPED_PREFIX)) {
            b = Arrays.copyOfRange(b, 12, 16);
        }
        return Optional.of(new IpAddress(b));
    }

    public boolean isV4() {
        return bytes.length == 4;
    }

    /** {@code 0.0.0.0} 或 {@code ::}（{@link ClientIpResolver#UNKNOWN} 的取值），不代表任何客户端。 */
    public boolean isUnspecified() {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /** 地址的字节（IPv4 为 4 字节，IPv6 为 16 字节）的副本。 */
    byte[] bytes() {
        return bytes.clone();
    }

    int bitLength() {
        return bytes.length * 8;
    }

    /** 第 i 位（从最高位起，0 起算）。 */
    int bit(int i) {
        return (bytes[i / 8] >> (7 - i % 8)) & 1;
    }

    private static byte[] parseV4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            Integer v = octet(parts[i]);
            if (v == null) {
                return null;
            }
            out[i] = (byte) (int) v;
        }
        return out;
    }

    private static Integer octet(String p) {
        if (p.isEmpty() || p.length() > 3 || (p.length() > 1 && p.charAt(0) == '0')) {
            return null;
        }
        int v = 0;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
            v = v * 10 + (c - '0');
        }
        return v > 255 ? null : v;
    }

    private static byte[] parseV6(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == ':'
                    || c == '.';
            if (!ok) {
                return null;
            }
        }
        int dbl = s.indexOf("::");
        if (dbl >= 0 && s.indexOf("::", dbl + 1) >= 0) {
            return null;
        }
        String head = dbl >= 0 ? s.substring(0, dbl) : s;
        String tail = dbl >= 0 ? s.substring(dbl + 2) : "";
        int[] h = groups(head, dbl < 0);
        if (h == null) {
            return null;
        }
        int[] t = dbl >= 0 ? groups(tail, true) : new int[0];
        if (t == null) {
            return null;
        }
        int total = h.length + t.length;
        if (dbl < 0 ? total != 8 : total > 7) {
            return null;
        }
        int[] all = new int[8];
        System.arraycopy(h, 0, all, 0, h.length);
        System.arraycopy(t, 0, all, 8 - t.length, t.length);
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[2 * i] = (byte) (all[i] >> 8);
            out[2 * i + 1] = (byte) all[i];
        }
        return out;
    }

    /**
     * 以 : 分隔的组；{@code allowV4Tail} 时最后一段可以是点分 IPv4（占两组）。空串为 0 组（只出现在 :: 的一侧）。
     */
    private static int[] groups(String part, boolean allowV4Tail) {
        if (part.isEmpty()) {
            return new int[0];
        }
        String[] g = part.split(":", -1);
        int n = g.length;
        boolean v4 = allowV4Tail && g[n - 1].indexOf('.') >= 0;
        int[] out = new int[v4 ? n + 1 : n];
        for (int i = 0; i < n; i++) {
            if (v4 && i == n - 1) {
                byte[] b = parseV4(g[i]);
                if (b == null) {
                    return null;
                }
                out[i] = ((b[0] & 0xff) << 8) | (b[1] & 0xff);
                out[i + 1] = ((b[2] & 0xff) << 8) | (b[3] & 0xff);
                continue;
            }
            String x = g[i];
            if (x.isEmpty() || x.length() > 4 || x.indexOf('.') >= 0) {
                return null;
            }
            out[i] = Integer.parseInt(x, 16);
        }
        return out;
    }

    @Override
    public String toString() {
        if (isV4()) {
            return (bytes[0] & 0xff) + "." + (bytes[1] & 0xff) + "." + (bytes[2] & 0xff) + "." + (bytes[3] & 0xff);
        }
        int[] g = new int[8];
        for (int i = 0; i < 8; i++) {
            g[i] = ((bytes[2 * i] & 0xff) << 8) | (bytes[2 * i + 1] & 0xff);
        }
        int bestStart = -1;
        int bestLen = 0;
        for (int i = 0; i < 8; ) {
            if (g[i] != 0) {
                i++;
                continue;
            }
            int j = i;
            while (j < 8 && g[j] == 0) {
                j++;
            }
            if (j - i > bestLen) {
                bestStart = i;
                bestLen = j - i;
            }
            i = j;
        }
        if (bestLen < 2) {
            bestStart = -1;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) {
                sb.append("::");
                i += bestLen - 1;
                continue;
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ':') {
                sb.append(':');
            }
            sb.append(Integer.toHexString(g[i]));
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof IpAddress other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
