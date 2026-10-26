package com.maccms.backend.member.application;

import com.maccms.backend.content.application.AdminConfig;
import com.maccms.backend.content.application.AdminUploadService;
import com.maccms.backend.content.application.view.UploadReply;
import com.maccms.backend.member.infrastructure.MemberAdminMapper;
import com.maccms.backend.member.infrastructure.PortraitStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 后台会员表单的"上传图片"（B14，docs/architecture/23-b14-admin-member.md 第 7.7 节）：旧 {@code admin/view/user/info.html:42-46、159-182}
 * 把文件 POST 到 {@code upload/upload?flag=user&user_id=<会员号>}，经 {@code common/model/Upload.php} 的 {@code flag=user} 分支（第 79-88、
 * 188-213 行）写 {@code upload/user/<id%10>/<id>.jpg} 并登记附件；表单脚本把返回的地址填进头像一栏，管理员点"保存"时才写进
 * {@code mac_user.user_portrait}。
 *
 * <p>旧后台没有会员会话，{@code Upload.php:208} 的 {@code $GLOBALS['user']['user_id']} 不存在，{@code UPDATE mac_user} 不命中任何行
 * （PHP 8.0 与 7.3 实跑黄金：上传前后 {@code mac_user} 不变）；新版同样不改 {@code mac_user}。图片的检查与转换、文件的原子写入、附件登记
 * 与前台头像（B09/B13，D-B09-9）共用 {@link PortraitImages}、{@link PortraitStore}、{@link PortraitAnnex}：先检查与转换，通过后才写盘
 * （先写同目录临时文件再改名，失败时不留文件、原有头像不变），只写重新编码的 JPEG。
 *
 * <p>与旧版的差异（D-B14-10）：会员号必须是已存在会员的十进制编号（旧版不查会员，缺失或为 0 时写 {@code user/0/0.jpg}，不存在、负数、
 * 小数、带字母时照样写出对应的文件，PHP 7.3 上 {@code ../} 还能写到 {@code user/} 之外，PHP 8 上非数字报 500），否则"文件上传失败！"；
 * 头像不看前台的 {@code user.portrait_status}（旧版同样不看）。
 */
public final class AdminPortraitService {

    private static final Logger log = LoggerFactory.getLogger(AdminPortraitService.class);
    /** 会员号：mac_user.user_id 是 int unsigned，十进制、不带符号与前导零。 */
    private static final Pattern USER_ID = Pattern.compile("[1-9][0-9]{0,9}");
    private static final long MAX_USER_ID = 4_294_967_295L;
    private static final Map<String, String> LANG = Map.of(PortraitImages.NO_INPUT, "admin/upload/no_input_file",
            PortraitImages.FORBIDDEN_EXT, "admin/upload/forbidden_ext", PortraitImages.FAILED, "admin/upload/upload_faild",
            PortraitImages.UNSAFE, "admin/upload/upload_safe");

    private final MemberAdminMapper reader;
    private final PortraitStore store;
    private final AdminConfig config;
    private final PortraitAnnex annex;
    private final Clock clock;

    public AdminPortraitService(MemberAdminMapper reader, PortraitStore store, AdminConfig config, PortraitAnnex annex, Clock clock) {
        this.reader = reader;
        this.store = store;
        this.config = config;
        this.annex = annex;
        this.clock = clock;
    }

    /**
     * @param params   {@code input()} 的标量参数（{@code user_id}、{@code thumb_class}、{@code imgdata}）
     * @param file     表单里名为 {@code input}（默认 file）的文件内容；没有时为 null
     * @param fileName 该文件的原文件名（只取扩展名）
     * @param lang     语言包
     */
    public UploadReply upload(Map<String, String> params, byte[] file, String fileName, UnaryOperator<String> lang) {
        long userId = userId(params.get("user_id"));
        if (userId < 1 || reader.countUser(userId) != 1 || !store.configured()) {
            return fail(lang, "admin/upload/upload_faild");
        }
        int[] size = MemberSettings.portraitSize(config.text("user", "portrait_size"));
        if (size[0] < 1) {
            // 旧版交给 think\Image::thumb，尺寸不合法时出错（make_thumb_faild，文案同 upload_faild）
            return fail(lang, "admin/upload/make_thumb_faild");
        }
        String imgdata = params.get("imgdata");
        PortraitImages.Outcome out = new PortraitImages(size[0], size[1]).convert(file, fileName, imgdata);
        if (out.error() != null) {
            return fail(lang, LANG.getOrDefault(out.error(), "admin/upload/upload_faild"));
        }
        try {
            store.write(userId, out.jpeg());
        } catch (RuntimeException e) {
            log.warn("admin member portrait write failed: {}", e.getClass().getSimpleName());
            return fail(lang, "admin/upload/upload_faild");
        }
        String path = PortraitStore.relativePath(userId);
        String type = PortraitImages.annexType(fileName, imgdata);
        registerAnnex(path, type, out.jpeg().length);
        String thumbClass = params.get("thumb_class");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("file", path);
        data.put("type", type);
        data.put("size", AdminUploadService.sizeKb(out.sourceBytes()));
        data.put("flag", "user");
        data.put("ctime", clock.instant().getEpochSecond());
        data.put("thumb_class", thumbClass == null || thumbClass.isEmpty() || thumbClass.equals("0") ? "" : thumbClass);
        data.put("thumb", List.of());
        return AdminUploadService.adminReply(lang.apply("admin/upload/upload_success"), true, data);
    }

    /** 会员号；不是十进制正整数（或超出 int unsigned）时为 0。 */
    static long userId(String raw) {
        if (raw == null || !USER_ID.matcher(raw).matches()) {
            return 0;
        }
        long id = Long.parseLong(raw);
        return id <= MAX_USER_ID ? id : 0;
    }

    /**
     * 登记附件（同一文件已登记时不重复），类型与大小的取法同前台头像（D-B09-9：类型为 {@link PortraitImages#annexType}，大小按新版重新编码
     * 后的 JPEG，KB 保留两位小数、写库时按整数列取整）。登记失败只记日志，不影响上传结果（旧版不检查 saveData 的返回值）。
     */
    private void registerAnnex(String path, String type, int bytes) {
        String size = BigDecimal.valueOf(bytes / 1024.0).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        try {
            annex.register(path, type, size);
        } catch (RuntimeException e) {
            log.warn("admin member portrait annex registration failed: {}", e.getClass().getSimpleName());
        }
    }

    private static UploadReply fail(UnaryOperator<String> lang, String key) {
        return AdminUploadService.adminReply(lang.apply(key), false, null);
    }
}
