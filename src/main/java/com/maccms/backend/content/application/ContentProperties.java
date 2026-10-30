package com.maccms.backend.content.application;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * content 模块的行为开关，对齐旧 PHP 配置（application/extra/maccms.php）。线上取值待核实，默认取更严格的一侧。
 *
 * @param enforceGuestPopedom  对应 user.status=1：按游客组 group_id=1 的 group_type/group_popedom 判定可见性
 *                             （index/controller/Base.php:92-175）
 * @param copyrightHidesDetail 对应 app.copyright_status=2：vod_copyright=1 的详情不展示（index/controller/Vod.php:58-60）
 * @param search               视频搜索开关与并发上限（契约 1.1.0，09 方案第 6.3–6.4 节），由 {@link SearchSettings} 校验
 * @param artPointsType        对应 user.art_points_type（B05）：{@code 1} 按 art_points（整篇收费），{@code 0} 按 art_points_detail
 *                             （按页收费），index/controller/Base.php:108-112；留空按仓库值 {@code 1}（application/extra/maccms.php:122），
 *                             只接受 0 或 1（旧后台只写这两个值，application/admin/view/system/configuser.html:178-179）
 */
@ConfigurationProperties(prefix = "maccms.content")
public record ContentProperties(boolean enforceGuestPopedom, boolean copyrightHidesDetail, Search search,
                                String artPointsType) {

    /** 旧系统游客组固定为 1（common/controller/All.php:127,139,143）。 */
    public static final int GUEST_GROUP_ID = 1;

    @ConstructorBinding
    public ContentProperties {
        if (search == null) {
            search = new Search(null, null);
        }
        String t = artPointsType == null ? "" : artPointsType.strip().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            t = "1";
        }
        if (!t.equals("0") && !t.equals("1")) {
            // 只报配置项名称与规则，不回显取值
            throw new IllegalStateException("maccms.content.art-points-type（0 或 1）");
        }
        artPointsType = t;
    }

    /** 不带文章收费方式配置（按仓库值 1）。 */
    public ContentProperties(boolean enforceGuestPopedom, boolean copyrightHidesDetail, Search search) {
        this(enforceGuestPopedom, copyrightHidesDetail, search, null);
    }

    /** 不带搜索配置（搜索关闭、并发上限取默认值）。 */
    public ContentProperties(boolean enforceGuestPopedom, boolean copyrightHidesDetail) {
        this(enforceGuestPopedom, copyrightHidesDetail, null, null);
    }

    /** 文章按整篇收费（art_points_type == '1'，Base.php:110）；否则按页（art_points_detail）。 */
    public boolean artPointsWholeArticle() {
        return artPointsType.equals("1");
    }

    /**
     * {@code maccms.content.search.*}，按原始字符串绑定：绑定本身不会失败，Spring 的绑定错误分析不会把取值打印到启动日志；
     * 校验与默认值在 {@link SearchSettings#from} 中进行，错误只报配置项名称（与 site 配置相同的做法）。
     *
     * @param enabled       是否接受包含匹配参数 keyword、classes（对应旧 app.search，但默认关闭、不读取 PHP 配置）
     * @param maxConcurrent 带包含匹配的请求同时执行的上限
     */
    public record Search(String enabled, String maxConcurrent) {
    }
}
