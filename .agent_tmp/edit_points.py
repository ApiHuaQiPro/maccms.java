import re

def edit(p, pairs):
    s = open(p, encoding='utf-8').read()
    for old, new in pairs:
        assert old in s, (p, old[:90])
        s = s.replace(old, new)
    open(p, 'w', encoding='utf-8', newline='\n').write(s)

B = 'backend/src/main/java/com/maccms/backend/content/'

edit(B + 'application/ContentProperties.java', [
("""import org.springframework.boot.context.properties.ConfigurationProperties;""",
 """import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;"""),
(""" * @param search               视频搜索开关与并发上限（契约 1.1.0，09 方案第 6.3–6.4 节），由 {@link SearchSettings} 校验
 */
@ConfigurationProperties(prefix = "maccms.content")
public record ContentProperties(boolean enforceGuestPopedom, boolean copyrightHidesDetail, Search search) {""",
 """ * @param search               视频搜索开关与并发上限（契约 1.1.0，09 方案第 6.3–6.4 节），由 {@link SearchSettings} 校验
 * @param artPointsType        对应 user.art_points_type（B05）：{@code 1} 按 art_points（整篇收费），{@code 0} 按 art_points_detail
 *                             （按页收费），index/controller/Base.php:108-112；留空按仓库值 {@code 1}（application/extra/maccms.php:122），
 *                             只接受 0 或 1（旧后台只写这两个值，application/admin/view/system/configuser.html:178-179）
 */
@ConfigurationProperties(prefix = "maccms.content")
public record ContentProperties(boolean enforceGuestPopedom, boolean copyrightHidesDetail, Search search,
                                String artPointsType) {"""),
("""        if (search == null) {
            search = new Search(null, null);
        }
    }

    /** 不带搜索配置（搜索关闭、并发上限取默认值）。 */
    public ContentProperties(boolean enforceGuestPopedom, boolean copyrightHidesDetail) {
        this(enforceGuestPopedom, copyrightHidesDetail, null);
    }
""",
 """        if (search == null) {
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
"""),
])

edit(B + 'application/ContentCatalogs.java', [
("""    boolean enforceGuestPopedom() {
        return properties.enforceGuestPopedom();
    }
""",
 """    boolean enforceGuestPopedom() {
        return properties.enforceGuestPopedom();
    }

    /** user.art_points_type（{@link ContentProperties#artPointsWholeArticle()}）。 */
    boolean artPointsWholeArticle() {
        return properties.artPointsWholeArticle();
    }
"""),
])

edit(B + 'application/ArticleReadService.java', [
("""            // 旧版游客（group_id<3）访问 art_points/art_points_detail>0 的文章时改为显示扣积分确认页（index/controller/Base.php:105-112、:118-142）；
            // user.status=0 时不检查（:115-117）
            if (catalogs.enforceGuestPopedom() && nz(row.pointsRequired()) != 0) {""",
 """            // 旧版游客（group_id<3）访问需积分的文章时改为显示扣积分确认页（index/controller/Base.php:105-112、:118-142）：
            // 积分列由 user.art_points_type 决定，'1' 时为 art_points（整篇），否则为 art_points_detail（按页，:108-111）；
            // user.status=0 时不检查（:115-117）。B05 起按配置取列（此前两列任一大于 0 即拒绝）
            int points = catalogs.artPointsWholeArticle() ? nz(row.pointsRequired()) : nz(row.pointsDetailRequired());
            if (catalogs.enforceGuestPopedom() && points != 0) {"""),
])

edit(B + 'infrastructure/ArtDetailRow.java', [
(""" * 由 content.application 解码；detailPasswordRequired、pointsRequired 只以 SQL 计算的布尔值出现，密码与积分值不读入内存。""",
 """ * 由 content.application 解码；detailPasswordRequired、pointsRequired（art_points &gt; 0）、pointsDetailRequired
 * （art_points_detail &gt; 0）只以 SQL 计算的布尔值出现，密码与积分值不读入内存。"""),
("""        Integer pointsRequired) {""", """        Integer pointsRequired,
        Integer pointsDetailRequired) {"""),
])

edit('backend/src/main/resources/mapper/content/ArtMapper.xml', [
("""               (art_points &gt; 0 OR art_points_detail &gt; 0) AS pointsRequired""",
 """               (art_points &gt; 0)        AS pointsRequired,
               (art_points_detail &gt; 0) AS pointsDetailRequired"""),
("""    <!-- 对应旧 mac_label_art_detail 的 art_id + art_status=1 条件（application/common.php:2693-2713）。 -->""",
 """    <!--
      对应旧 mac_label_art_detail 的 art_id + art_status=1 条件（application/common.php:2693-2713）。积分两列分别给出布尔值，
      由 content.application 按 user.art_points_type 取其一（index/controller/Base.php:108-111，B05）。
    -->"""),
])

edit('backend/src/main/resources/application.yml', [
("""    copyright-hides-detail: ${MACCMS_CONTENT_COPYRIGHT_HIDES_DETAIL:true}
""",
 """    copyright-hides-detail: ${MACCMS_CONTENT_COPYRIGHT_HIDES_DETAIL:true}
    # 对齐 PHP 配置 user.art_points_type（B05）：1 按 art_points（整篇收费），0 按 art_points_detail（按页收费），留空按仓库值 1
    art-points-type: ${MACCMS_CONTENT_ART_POINTS_TYPE:}
"""),
])
print('ok')
