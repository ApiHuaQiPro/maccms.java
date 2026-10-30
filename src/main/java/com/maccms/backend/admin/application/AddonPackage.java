package com.maccms.backend.admin.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 插件包（zip）的解析与校验（B29，35 文档第 4 节；对应旧 {@code Service::unzip} 的 {@code ZipArchive::extractTo}，Service.php:77-94）。
 * 只解析、不落盘：全部条目读进内存，通过校验后由 {@link AddonMarketService} 经 {@code LegacySiteFiles} 写进 {@code addons/<名称>/}。
 *
 * <p>校验（任何一条不过，整个包拒绝）：
 * <ul>
 * <li>是 zip（开头为 {@code PK\3\4} 或空包的 {@code PK\5\6}），能按中央目录打开，条目不加密；否则沿用旧版提示 {@link #UNABLE_TO_OPEN}；</li>
 * <li>条目名（路径穿越）：不以 {@code /} 开头，不含 {@code \}、{@code :}、控制字符，没有空段、{@code .}、{@code ..} 段，段不以点或空格结尾，
 * 不是 Windows 设备名（{@code con}、{@code nul}、{@code com1} 等），全长不超过 {@link #MAX_NAME}；不能有同名（不分大小写）条目，
 * 也不能同一路径既是文件又是目录；</li>
 * <li>zip 炸弹：条目数不超过 {@link Limits#maxEntries()}，单个条目与全部条目<b>实际解压出的</b>字节数不超过上限（不信任头部声明的大小），
 * 单个条目的压缩比不超过 {@link Limits#maxRatio()}（小于 1 MiB 的条目不看压缩比）。</li>
 * </ul>
 * zip 里的符号链接条目当作普通文件（内容为链接目标的文本）写出，不创建链接（旧 {@code ZipArchive::extractTo} 同样不创建）。
 */
public final class AddonPackage {

    /** 旧版打不开 zip 时的提示（Service.php:84）。 */
    public static final String UNABLE_TO_OPEN = "Unable to open the zip file";
    /** 条目名不安全（新增）。 */
    public static final String UNSAFE_PATH = "插件包含不安全的路径，已拒绝安装";
    /** 超过条目数或解压大小上限（新增）。 */
    public static final String TOO_LARGE = "插件包解压后超过文件数或大小上限，已拒绝安装";

    /** 条目名的最大长度（加上 {@code addons/<名称>/} 后仍在 {@code LegacySiteFiles} 的 1024 以内）。 */
    static final int MAX_NAME = 900;
    private static final int RATIO_FLOOR = 1024 * 1024;
    private static final Pattern DEVICE = Pattern.compile("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");

    /** 解压上限。 */
    public record Limits(int maxEntries, long maxEntryBytes, long maxTotalBytes, int maxRatio) {
        /** 默认：2000 个条目，单个 32 MiB，合计 64 MiB，压缩比 200。 */
        public static final Limits DEFAULT = new Limits(2000, 32L * 1024 * 1024, 64L * 1024 * 1024, 200);
    }

    /** 包不合格（信息是给管理员看的提示）。 */
    public static final class Rejected extends RuntimeException {
        Rejected(String message) {
            super(message, null, false, false);
        }
    }

    private final Map<String, byte[]> files;

    private AddonPackage(Map<String, byte[]> files) {
        this.files = files;
    }

    /** 文件（相对插件目录的路径 → 内容），按 zip 中央目录的顺序；目录条目不单列。 */
    public Map<String, byte[]> files() {
        return Collections.unmodifiableMap(files);
    }

    /** 某个文件的内容，没有时为 null。 */
    public byte[] file(String path) {
        return files.get(path);
    }

    /** 解析并校验。 */
    public static AddonPackage parse(byte[] zip, Limits limits) {
        if (zip == null || zip.length < 4 || zip[0] != 'P' || zip[1] != 'K'
                || !((zip[2] == 3 && zip[3] == 4) || (zip[2] == 5 && zip[3] == 6))) {
            throw new Rejected(UNABLE_TO_OPEN);
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile("maccms-addon-", ".zip");
            Files.write(tmp, zip);
            return read(tmp, limits);
        } catch (IOException e) {
            throw new Rejected(UNABLE_TO_OPEN);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    tmp.toFile().deleteOnExit();
                }
            }
        }
    }

    private static AddonPackage read(Path path, Limits limits) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        Set<String> dirs = new HashSet<>();
        Set<String> fileKeys = new HashSet<>();
        long total = 0;
        int count = 0;
        try (ZipFile zf = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (++count > limits.maxEntries()) {
                    throw new Rejected(TOO_LARGE);
                }
                String raw = e.getName();
                boolean dir = e.isDirectory();
                String name = dir ? raw.substring(0, raw.length() - 1) : raw;
                if (!safeName(name)) {
                    throw new Rejected(UNSAFE_PATH);
                }
                if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                    throw new Rejected(UNSAFE_PATH);
                }
                String[] segs = name.split("/");
                StringBuilder parent = new StringBuilder();
                for (int i = 0; i < segs.length - 1; i++) {
                    if (i > 0) {
                        parent.append('/');
                    }
                    parent.append(segs[i]);
                    String p = parent.toString().toLowerCase(Locale.ROOT);
                    if (fileKeys.contains(p)) {
                        throw new Rejected(UNSAFE_PATH);
                    }
                    dirs.add(p);
                }
                if (dir) {
                    dirs.add(name.toLowerCase(Locale.ROOT));
                    continue;
                }
                if (dirs.contains(name.toLowerCase(Locale.ROOT))) {
                    throw new Rejected(UNSAFE_PATH);
                }
                byte[] data;
                try (InputStream in = zf.getInputStream(e)) {
                    data = readLimited(in, Math.min(limits.maxEntryBytes(), limits.maxTotalBytes() - total));
                } catch (ZipException ze) {
                    throw new Rejected(UNABLE_TO_OPEN);
                }
                total += data.length;
                long compressed = e.getCompressedSize();
                if (data.length > RATIO_FLOOR && compressed > 0 && data.length / compressed > limits.maxRatio()) {
                    throw new Rejected(TOO_LARGE);
                }
                files.put(name, data);
                fileKeys.add(name.toLowerCase(Locale.ROOT));
            }
        } catch (ZipException | IllegalArgumentException e) {
            throw new Rejected(UNABLE_TO_OPEN);
        }
        return new AddonPackage(files);
    }

    /** 读完流，超过 {@code max} 字节即拒绝（按实际解压出的字节数）。 */
    private static byte[] readLimited(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long n = 0;
        int r;
        while ((r = in.read(buf)) > 0) {
            n += r;
            if (n > max) {
                throw new Rejected(TOO_LARGE);
            }
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    /** 条目名（目录去掉结尾的 /）是否安全。 */
    static boolean safeName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME || name.startsWith("/") || name.indexOf('\\') >= 0
                || name.indexOf(':') >= 0) {
            return false;
        }
        for (String seg : name.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..") || seg.endsWith(".") || seg.endsWith(" ")
                    || DEVICE.matcher(seg).matches()) {
                return false;
            }
            for (int i = 0; i < seg.length(); i++) {
                char c = seg.charAt(i);
                if (c < 0x20 || c == 0x7f) {
                    return false;
                }
            }
        }
        return true;
    }
}
