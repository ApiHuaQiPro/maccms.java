package com.maccms.backend.collect.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自定义规则采集的本地 HTML 源（B20，28 文档第 4 节；{@code maccms.collect.local.html-dir}，留空表示未配置）。规则里的网址不去请求，而是按
 * "镜像目录"的约定映射到目录里的文件（与 {@code wget --mirror} 的布局相同）：
 * <pre>
 *   http(s)://主机[:端口]/路径/文件?查询   →   &lt;html-dir&gt;/主机[_端口]/路径/文件@查询
 * </pre>
 * <ul>
 * <li>只认 {@code http}、{@code https}（不区分大小写）；主机转小写，只能是字母、数字、{@code . -}；端口是默认端口（80、443）时不写；
 * 用户名、密码与 {@code #} 之后的部分忽略。</li>
 * <li>路径按 {@code /} 分段，每段 {@code %XX} 解码后必须是合法 UTF-8，不能是 {@code .}、{@code ..}，不能含 {@code \ : * ? " < > |} 与
 * 控制字符，不能以空格或点结尾，不能是 Windows 设备名（{@code con}、{@code nul}、{@code com1} 等）；空段（{@code //}）跳过。路径为空或以 {@code /} 结尾时文件名是 {@code index.html}。</li>
 * <li>有查询串时文件名后接 {@code @} 与查询串（{@code / \ : * ? " < > |} 与控制字符写成 {@code %XX}）。</li>
 * <li>解析后的文件必须在目录之内，路径上的每一级都不是符号链接，且是普通文件；超过大小上限的不读。</li>
 * </ul>
 * 不满足以上任何一条时当作"没有这个页面"（旧版请求失败），不抛出。
 */
public class LocalHtmlFiles {

    private static final Pattern URL = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)([^?#]*)(?:\\?([^#]*))?(?:#.*)?$",
            Pattern.DOTALL);
    private static final Pattern HOST = Pattern.compile("[a-z0-9.-]{1,253}");
    private static final String BAD = "\\:*?\"<>|";

    private final Path dir;
    private final long maxBytes;

    /** {@code dir} 为 null 表示未配置。 */
    public LocalHtmlFiles(Path dir, long maxBytes) {
        this.dir = dir == null ? null : dir.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    public boolean configured() {
        return dir != null;
    }

    /** 网址（原始字节）对应的相对路径（用 {@code /} 分隔）；网址不能映射时为空。 */
    public static Optional<String> relativePath(byte[] url) {
        String u;
        try {
            u = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(url)).toString();
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
        Matcher m = URL.matcher(u);
        if (!m.matches()) {
            return Optional.empty();
        }
        String scheme = m.group(1).toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Optional.empty();
        }
        String auth = m.group(2);
        int at = auth.lastIndexOf('@');
        if (at >= 0) {
            auth = auth.substring(at + 1);
        }
        String host = auth.toLowerCase(Locale.ROOT);
        String port = null;
        int colon = host.indexOf(':');
        if (colon >= 0) {
            port = host.substring(colon + 1);
            host = host.substring(0, colon);
            if (!port.matches("[0-9]{1,5}")) {
                return Optional.empty();
            }
            port = Integer.toString(Integer.parseInt(port));
            if (scheme.equals("http") && port.equals("80") || scheme.equals("https") && port.equals("443")) {
                port = null;
            }
        }
        if (!HOST.matcher(host).matches() || host.startsWith(".") || host.endsWith(".") || host.contains("..")) {
            return Optional.empty();
        }
        List<String> segs = new ArrayList<>();
        segs.add(port == null ? host : host + "_" + port);
        String path = m.group(3);
        String[] parts = path.split("/", -1);
        for (String raw : parts) {
            if (raw.isEmpty()) {
                continue;
            }
            String seg = percentDecode(raw);
            if (seg == null || !safeSegment(seg)) {
                return Optional.empty();
            }
            segs.add(seg);
        }
        if (path.isEmpty() || path.endsWith("/") || segs.size() == 1) {
            segs.add("index.html");
        }
        String query = m.group(4);
        if (query != null && !query.isEmpty()) {
            String last = segs.remove(segs.size() - 1) + "@" + encodeQuery(query);
            if (last.length() > 255) {
                return Optional.empty();
            }
            segs.add(last);
        }
        return Optional.of(String.join("/", segs));
    }

    /**
     * 读出网址对应的文件（旧 {@code mac_curl_get} 的位置）：未配置、不能映射、不存在、不是普通文件、经过符号链接或超过上限时为空。
     */
    public Optional<byte[]> read(byte[] url) {
        if (dir == null) {
            return Optional.empty();
        }
        Optional<String> rel = relativePath(url);
        if (rel.isEmpty()) {
            return Optional.empty();
        }
        Path p = dir;
        try {
            for (String seg : rel.get().split("/")) {
                p = p.resolve(seg);
                if (!p.normalize().startsWith(dir) || Files.isSymbolicLink(p)) {
                    return Optional.empty();
                }
            }
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!a.isRegularFile() || a.size() > maxBytes) {
                return Optional.empty();
            }
            try (InputStream in = Files.newInputStream(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                byte[] b = in.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE - 8));
                return b.length > maxBytes ? Optional.empty() : Optional.of(b);
            }
        } catch (IOException | java.nio.file.InvalidPathException e) {
            return Optional.empty();
        }
    }

    /** Windows 的设备名（含扩展名时同样是设备）。 */
    private static final Pattern DEVICE = Pattern.compile("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");

    private static boolean safeSegment(String s) {
        if (s.equals(".") || s.equals("..") || s.endsWith(".") || s.endsWith(" ") || s.length() > 255 || DEVICE.matcher(s).matches()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '/' || BAD.indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    /** {@code %XX} 解码（不把 {@code +} 当空格），结果必须是合法 UTF-8；不完整的 {@code %} 原样。 */
    private static String percentDecode(String s) {
        byte[] in = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        for (int i = 0; i < in.length; i++) {
            if (in[i] == '%' && i + 2 < in.length && hex(in[i + 1]) >= 0 && hex(in[i + 2]) >= 0) {
                out.write(hex(in[i + 1]) * 16 + hex(in[i + 2]));
                i += 2;
            } else {
                out.write(in[i]);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String encodeQuery(String q) {
        StringBuilder sb = new StringBuilder(q.length());
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '/' || BAD.indexOf(c) >= 0) {
                sb.append(String.format(Locale.ROOT, "%%%02X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int hex(byte b) {
        return Character.digit((char) (b & 0xff), 16);
    }
}
