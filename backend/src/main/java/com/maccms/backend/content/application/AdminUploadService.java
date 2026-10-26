package com.maccms.backend.content.application;

import com.maccms.backend.content.application.view.UploadReply;
import com.maccms.backend.content.infrastructure.UploadStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 后台上传（B13，22 文档第 7.10 节）：旧 {@code admin/controller/Upload::upload} → {@code common/model/Upload::upload}。只存本地
 * （E08：其他存储方式的配置照常读写，上传一律落在 upload/）。目录 {@code upload/<flag>/<Ymd>-<n>/}（每个目录最多 999 个文件），文件名为随机的
 * 32 位十六进制加原扩展名；按扩展名分为图片、文件、媒体三类，开头与结尾各 512 字节里有脚本标记时拒绝；图片按配置加文字水印、生成缩略图；
 * 成功后把 upload/ 下的文件登记为附件。编辑器（{@code from}）的请求按各编辑器的格式返回。
 */
public class AdminUploadService {

    static final Set<String> IMAGE_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp");
    static final Set<String> FILE_EXT = Set.of("doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf", "wps", "txt", "rar", "zip",
            "torrent");
    static final Set<String> MEDIA_EXT = Set.of("rm", "rmvb", "avi", "mkv", "mp4", "mp3");
    private static final List<String> EDITORS = List.of("Ueditor", "Umeditor", "Ckeditor", "Kindeditor", "Tinymce");
    private static final Pattern DATA_URL = Pattern.compile("^(data:\\s*image/(\\w+);base64,)");
    private static final Pattern UNSAFE_HEX = Pattern.compile("(3c25.*?28.*?29.*?253e)|(3c3f.*?28.*?29.*?3f3e)"
            + "|(3C534352495054)|(2F5343524950543E)|(3C736372697074)|(2F7363726970743E)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** upload/ 下的目录名：旧版直接拼进路径（{@code ../} 可以写到目录之外），新版只接受字母、数字、下划线与减号（22 文档 D-B13-8）。 */
    private static final Pattern FLAG = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final ZoneId PRC = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 一次上传请求：{@code params} 为 {@code input()} 的标量参数；{@code file} 为表单里名为 {@code input} 的文件（没有时为 null）。 */
    public record Upload(Map<String, String> params, byte[] file, String fileName) {
    }

    private final UploadStore uploads;
    private final AdminConfig config;
    private final AnnexAdminService annexes;
    private final Clock clock;
    private final RandomGenerator random;

    public AdminUploadService(UploadStore uploads, AdminConfig config, AnnexAdminService annexes, Clock clock,
                              RandomGenerator random) {
        this.uploads = uploads;
        this.config = config;
        this.annexes = annexes;
        this.clock = clock;
        this.random = random;
    }

    /** 文件表单字段名（旧 {@code $param['input']}，为空时 {@code file}）。 */
    public static String inputName(Map<String, String> params) {
        return empty(params.get("input")) ? "file" : params.get("input");
    }

    public UploadReply upload(Upload req, UnaryOperator<String> lang) {
        Map<String, String> p = req.params();
        String from = empty(p.get("from")) ? "" : p.get("from");
        String flag = empty(p.get("flag")) ? "vod" : p.get("flag");
        boolean thumbWanted = !empty(p.get("thumb")) && PhpLoose.equalsOne(p.get("thumb").getBytes(StandardCharsets.UTF_8));
        String thumbClass = empty(p.get("thumb_class")) ? "" : p.get("thumb_class");
        String pre = config.text("site", "install_dir");
        String editor = null;
        if (!from.isEmpty()) {
            String name = Character.toUpperCase(from.charAt(0)) + from.substring(1);
            if (!EDITORS.contains(name)) {
                return reply(lang.apply("admin/upload/not_find_extend"), null, false, null);
            }
            editor = name;
            if ((editor.equals("Ueditor") || editor.equals("Umeditor")) && "config".equals(p.get("action"))) {
                return new UploadReply(ueditorConfig(), false);
            }
        } else {
            pre = "";
        }
        if (!uploads.configured() || !FLAG.matcher(flag).matches() || flag.equals("user")) {
            // 会员头像（flag=user）：后台会员表单的上传（不带编辑器 from）由 B14 的会员服务处理，不进这里（23 文档第 7.7 节）；
            // 编辑器带 flag=user 的请求照旧拒绝（22 文档 D-B13-17）
            return reply(lang.apply("admin/upload/upload_faild"), editor, false, null);
        }
        String dir = directory(flag);
        String saveName = dir + "/" + md5(clock.millis() + "." + random.nextInt(10_000));
        byte[] bytes;
        String type;
        String base64 = p.get("imgdata");
        if (!empty(base64)) {
            Matcher m = DATA_URL.matcher(base64);
            if (!m.find()) {
                return reply(lang.apply("admin/upload/no_input_file"), editor, false, null);
            }
            type = m.group(2);
            if (!IMAGE_EXT.contains(type)) {
                return reply(lang.apply("admin/upload/forbidden_ext"), editor, false, null);
            }
            try {
                // 旧 base64_decode(str_replace($result[1], '', $s))：非法字符跳过
                bytes = Base64.getMimeDecoder().decode(base64.replace(m.group(1), ""));
            } catch (IllegalArgumentException e) {
                bytes = new byte[0];
            }
            if (bytes.length == 0) {
                return reply(lang.apply("admin/upload/upload_faild"), editor, false, null);
            }
            // 旧版 file_put_contents 不建目录：当天的目录还不存在时（这一天还没有文件上传）写入失败
            if (!uploads.directoryExists("upload/" + flag + "/" + dir)) {
                return reply(lang.apply("admin/upload/upload_faild"), editor, false, null);
            }
        } else {
            if (req.file() == null) {
                return reply(lang.apply("admin/upload/no_input_file"), editor, false, null);
            }
            bytes = req.file();
            String ext = extension(req.fileName());
            String lower = ext.toLowerCase(Locale.ROOT);
            if (startsWithPhp(bytes)) {
                return reply(lang.apply("admin/upload/forbidden_ext"), editor, false, null);
            }
            if (IMAGE_EXT.contains(lower)) {
                type = "image";
            } else if (FILE_EXT.contains(lower)) {
                type = "file";
            } else if (MEDIA_EXT.contains(lower)) {
                type = "media";
            } else {
                return reply(lang.apply("admin/upload/forbidden_ext"), editor, false, null);
            }
            saveName = saveName + "." + ext;
        }
        // 旧版先落盘再检查，危险文件留在磁盘上；新版检查通过才写入
        if (unsafe(bytes)) {
            return reply(lang.apply("admin/upload/upload_safe"), editor, false, null);
        }
        String file = "upload/" + flag + "/" + saveName;
        if (!uploads.write(file, bytes)) {
            return reply(lang.apply("admin/upload/upload_faild"), editor, false, null);
        }
        String sizeText = PhpNumbers.round2Text(bytes.length / 1024.0);
        long now = clock.instant().getEpochSecond();
        List<Map<String, Object>> thumbs = new ArrayList<>();
        if (type.equals("image")) {
            String ext = extension(file).toLowerCase(Locale.ROOT);
            if (PhpLoose.equalsOne(config.text("upload", "watermark").getBytes(StandardCharsets.UTF_8))) {
                byte[] w = watermark(uploads.read(file), ext, location());
                if (w != null) {
                    uploads.write(file, w);
                }
            }
            if (thumbWanted && PhpLoose.equalsOne(config.text("upload", "thumb").getBytes(StandardCharsets.UTF_8))) {
                thumbs = thumbs(file, ext, flag, now);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("file", file);
        data.put("type", type);
        data.put("size", number(sizeText));
        data.put("flag", flag);
        data.put("ctime", now);
        data.put("thumb_class", thumbClass);
        data.put("thumb", thumbs);
        if (editor != null) {
            data.put("file", pre + file);
        }
        String tmp = (String) data.get("file");
        if (tmp.startsWith("/upload")) {
            tmp = tmp.substring(1);
        }
        if (tmp.startsWith("upload")) {
            if (annexes.register(tmp, type, sizeText) && !thumbs.isEmpty()) {
                String t = (String) thumbs.getFirst().get("file");
                annexes.register(t, type, Long.toString(uploads.size(t)));
            }
        }
        return reply(lang.apply("admin/upload/upload_success"), editor, true, data);
    }

    /** 删除 upload/ 下的一个文件（旧 Images::del 的 {@code unlink}；不在 upload/ 之内时什么也不做）。 */
    public void deleteFile(String siteRelative) {
        uploads.delete(siteRelative);
    }

    /** 旧目录规则：{@code <Ymd>-1} 起，目录已存在且名称含点的文件超过 999 个时换下一个，最多到 100。 */
    private String directory(String flag) {
        String ymd = LocalDate.now(clock.withZone(PRC)).format(YMD);
        String n = ymd;
        for (int i = 1; i <= 100; i++) {
            n = ymd + "-" + i;
            int count = uploads.countDotted("upload/" + flag + "/" + n);
            if (count < 0 || count <= 999) {
                break;
            }
        }
        return n;
    }

    private List<Map<String, Object>> thumbs(String file, String ext, String flag, long now) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sizes = config.text("upload", "thumb_size");
        if (sizes.isEmpty()) {
            return out;
        }
        int type = (int) PhpIntval.of(config.text("upload", "thumb_type"));
        // 旧 makethumb 只打开一次原图，同一个 think\Image 对象依次 thumb()->save()；开水印时再改成打开刚存的缩略图加水印。所以后面的尺寸
        // 是从前一张缩略图（开水印时是加过水印的那张）再缩出来的（如 120x80,300x300 按等比例缩放时两张都是 120×67），这里照旧
        byte[] src = uploads.read(file);
        for (String v : sizes.split(",", -1)) {
            String[] t = v.toLowerCase(Locale.ROOT).split("x", -1);
            String tw = t[0];
            String th = t.length > 1 ? t[1] : t[0];
            String name = file + "_" + tw + "x" + th + "." + ext;
            byte[] img = LegacyImages.thumb(src, ext, (int) PhpIntval.of(tw), (int) PhpIntval.of(th), type);
            if (img == null) {
                // 旧版异常被吞掉，余下的尺寸也不再生成
                break;
            }
            if (PhpLoose.equalsOne(config.text("upload", "watermark").getBytes(StandardCharsets.UTF_8))) {
                byte[] w = watermark(img, ext, 9);
                if (w != null) {
                    img = w;
                }
            }
            uploads.write(name, img);
            src = img;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "image");
            m.put("flag", flag);
            m.put("file", name);
            m.put("size", number(PhpNumbers.round2Text(img.length / 1024.0)));
            m.put("ctime", now);
            out.add(m);
        }
        return out;
    }

    private int location() {
        long l = PhpIntval.of(config.text("upload", "watermark_location"));
        return l >= 1 && l <= 9 ? (int) l : 9;
    }

    /** 文字水印（旧 Image::watermark）：字体为配置的 {@code watermark_font}（站点根之下），为空时用旧版自带的 static/font/test.ttf。 */
    private byte[] watermark(byte[] image, String ext, int locate) {
        byte[] ttf = font(config.text("upload", "watermark_font"));
        return LegacyImages.text(image, ext, config.text("upload", "watermark_content"), LegacyImages.font(ttf),
                PhpIntval.of(config.text("upload", "watermark_size")), config.text("upload", "watermark_color"), locate);
    }

    private byte[] font(String configured) {
        if (configured.isEmpty()) {
            try (InputStream in = AdminUploadService.class.getResourceAsStream("/legacy/font/test.ttf")) {
                return in == null ? null : in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        String rel = configured.startsWith("./") ? configured.substring(2) : configured;
        return uploads.readSiteFile(rel);
    }

    /** ueditor 的 {@code action=config}：旧版读 static/ueditor/config.json，去掉 {@code /* … *}{@code /} 注释后解码再编码。 */
    private static String ueditorConfig() {
        try (InputStream in = AdminUploadService.class.getResourceAsStream("/legacy/ueditor/config.json")) {
            if (in == null) {
                return "null";
            }
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("/\\*[\\s\\S]+?\\*/", "");
            return PhpJson.reencode(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 旧 {@code upload_return}：编辑器请求按编辑器格式（{@code json_encode($arr, JSON_HEX_TAG)} 直接输出）；后台请求为
     * {@code {msg, code, data}}（失败时 data 为空数组）。
     */
    private static UploadReply reply(String info, String editor, boolean ok, Map<String, Object> data) {
        if (editor != null) {
            Map<String, Object> arr = new LinkedHashMap<>();
            String file = data == null ? null : (String) data.get("file");
            switch (editor) {
                case "Ueditor", "Umeditor" -> {
                    arr.put("message", info);
                    if (ok) {
                        arr.put("url", file);
                    }
                    arr.put("state", ok ? "SUCCESS" : "ERROR");
                }
                case "Ckeditor" -> {
                    if (ok) {
                        arr.put("uploaded", 1);
                        arr.put("fileName", "");
                        arr.put("url", file);
                    } else {
                        arr.put("uploaded", 0);
                        arr.put("error", Map.of("msg", info));
                    }
                }
                case "Kindeditor" -> {
                    arr.put("error", ok ? 0 : 1);
                    if (ok) {
                        arr.put("url", file);
                    } else {
                        arr.put("message", info);
                    }
                }
                default -> arr.put("location", file);
            }
            return new UploadReply(PhpJson.encode(arr, true), false);
        }
        Map<String, Object> arr = new LinkedHashMap<>();
        arr.put("msg", info);
        arr.put("code", ok ? 1 : 0);
        arr.put("data", data == null ? List.of() : data);
        return new UploadReply(com.maccms.backend.common.web.LegacyJson.encode(arr), true);
    }

    /**
     * 后台请求（不带编辑器 {@code from}）的上传结果 {@code {msg, code, data}}（旧 {@code upload_return}，失败时 data 为空数组）。B14 的会员头像
     * 上传（23 文档第 7.7 节）用同一格式。
     */
    public static UploadReply adminReply(String info, boolean ok, Map<String, Object> data) {
        return reply(info, null, ok, data);
    }

    /** 上传结果里的 {@code size}：{@code round(字节数 / 1024, 2)}（JSON 里整数值不带小数点）。 */
    public static Object sizeKb(long bytes) {
        return number(PhpNumbers.round2Text(bytes / 1024.0));
    }

    private static Object number(String text) {
        return text.contains(".") ? (Object) Double.parseDouble(text) : (Object) Long.parseLong(text);
    }

    static boolean unsafe(byte[] bytes) {
        String hex;
        if (bytes.length > 512) {
            HexFormat h = HexFormat.of();
            hex = h.formatHex(bytes, 0, 512) + h.formatHex(bytes, bytes.length - 512, bytes.length);
        } else {
            hex = HexFormat.of().formatHex(bytes);
        }
        return UNSAFE_HEX.matcher(hex).find();
    }

    /** 旧版 finfo 识别为 {@code text/x-php} 的文件（以 {@code <?php} 开头）。 */
    private static boolean startsWithPhp(byte[] b) {
        String head = new String(b, 0, Math.min(b.length, 16), StandardCharsets.ISO_8859_1).stripLeading()
                .toLowerCase(Locale.ROOT);
        return head.startsWith("<?php");
    }

    private static String extension(String name) {
        if (name == null) {
            return "";
        }
        String base = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        int dot = base.lastIndexOf('.');
        return dot < 0 ? "" : base.substring(dot + 1);
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty() || s.equals("0");
    }

    private static String md5(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
