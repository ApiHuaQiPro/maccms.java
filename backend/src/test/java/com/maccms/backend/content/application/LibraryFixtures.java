package com.maccms.backend.content.application;

import com.maccms.backend.content.Fixtures;
import com.maccms.backend.content.infrastructure.ActorDetailRow;
import com.maccms.backend.content.infrastructure.ActorSummaryRow;
import com.maccms.backend.content.infrastructure.ArtDetailRow;
import com.maccms.backend.content.infrastructure.ArtSummaryRow;
import com.maccms.backend.content.infrastructure.LinkRow;
import com.maccms.backend.content.infrastructure.RoleDetailRow;
import com.maccms.backend.content.infrastructure.RoleSummaryRow;
import com.maccms.backend.content.infrastructure.TopicDetailRow;
import com.maccms.backend.content.infrastructure.TopicSummaryRow;
import com.maccms.backend.content.infrastructure.TypeRow;
import com.maccms.backend.content.infrastructure.VodPlotRow;
import com.maccms.backend.content.infrastructure.VodRefRow;
import com.maccms.backend.content.infrastructure.WebsiteDetailRow;
import com.maccms.backend.content.infrastructure.WebsiteSummaryRow;
import java.math.BigDecimal;
import java.util.List;

/** 契约 1.2.0 测试用的合成行数据，形态与 install.sql 一致。分类与游客权限沿用 {@link Fixtures}。 */
final class LibraryFixtures {

    private LibraryFixtures() {
    }

    /**
     * 文章分类树（type_mid=2）：21(根) ← 22；23(根，游客无列表位 → 不可见)；24(根，游客只有列表位、没有详情位)。
     */
    static List<TypeRow> articleTypes() {
        return List.of(Fixtures.type(21, 0, 1), Fixtures.type(22, 21, 1), Fixtures.type(23, 0, 2), Fixtures.type(24, 0, 3));
    }

    /** 演员分类（type_mid=8）：31(根)。网址分类（type_mid=11）：41(根)。 */
    static List<TypeRow> actorTypes() {
        return List.of(Fixtures.type(31, 0, 1));
    }

    static List<TypeRow> websiteTypes() {
        return List.of(Fixtures.type(41, 0, 1));
    }

    /** 在 Fixtures.guestGroup() 的基础上加入 21、22、24（24 只有列表位）、31、41；23 不在 group_type 中。 */
    static com.maccms.backend.content.infrastructure.GroupPermissionRow guestGroup() {
        return new com.maccms.backend.content.infrastructure.GroupPermissionRow(1,
                ",1,6,7,2,13,3,21,22,24,31,41,",
                "{\"1\":{\"1\":\"1\",\"2\":\"2\"},\"6\":{\"1\":\"1\",\"2\":\"2\"},\"7\":{\"1\":\"1\"},"
                        + "\"2\":{\"1\":\"1\",\"2\":\"2\"},\"13\":{\"1\":\"1\",\"2\":\"2\"},\"3\":{\"2\":\"2\"},"
                        + "\"21\":{\"1\":\"1\",\"2\":\"2\"},\"22\":{\"1\":\"1\",\"2\":\"2\"},\"24\":{\"1\":\"1\"},"
                        + "\"31\":{\"1\":\"1\",\"2\":\"2\"},\"41\":{\"1\":\"1\",\"2\":\"2\"}}");
    }

    static ArtSummaryRow artSummary(long id, int categoryId) {
        return new ArtSummaryRow(id, categoryId, "文章" + id, "副标题", "wenzhang" + id, "W", "", "来源", "作者", "标签",
                "资讯", "upload/art/" + id + ".jpg", "", "", "简介", "备注", 1, new BigDecimal("7.5"), 3, 100, 1, 7, 30,
                2, 1, 1_700_000_000L + id, 1_690_000_000L + id, 0);
    }

    static ArtDetailRow artDetail(long id, int categoryId, int passwordRequired, int pointsRequired) {
        return new ArtDetailRow(id, categoryId, "文章" + id, "副标题", "wenzhang" + id, "W", "", "来源", "作者", "标签",
                "资讯", "upload/art/" + id + ".jpg", "", "", "简介", "备注", 1, new BigDecimal("7.5"), 22, 3, 100, 1,
                7, 30, 2, 1, 1_700_000_000L, 1_690_000_000L, "第一页$$$第二页", "注1", "<p>一</p>$$$<p>二</p>",
                "截图1$upload/s1.jpg#upload/s2.jpg", passwordRequired, pointsRequired);
    }

    static TopicSummaryRow topicSummary(int id) {
        return new TopicSummaryRow(id, "专题" + id, "副标题", "zhuanti" + id, "Z", "", 5, "合集", "标签A,标签B",
                "upload/topic/" + id + ".jpg", "", "", "SEO标题", "关键词", "描述", "简介", "备注", 2,
                new BigDecimal("8.0"), 4, 50, 1, 2, 3, 4, 5, 1_700_000_000L, 1_690_000_000L);
    }

    static TopicDetailRow topicDetail(int id, String tag, String relVod, String relArt) {
        return new TopicDetailRow(id, "专题" + id, "副标题", "zhuanti" + id, "Z", "", 5, "合集", tag,
                "upload/topic/" + id + ".jpg", "", "", "SEO标题", "关键词", "描述", "简介", "备注", 2,
                new BigDecimal("8.0"), 32, 4, 50, 1, 2, 3, 4, 5, 1_700_000_000L, 1_690_000_000L, "<p>专题</p>", relVod,
                relArt);
    }

    static ActorSummaryRow actorSummary(long id, int categoryId) {
        return new ActorSummaryRow(id, categoryId, "演员" + id, "yanyuan" + id, "别名", "Y", "", "女",
                "upload/actor/" + id + ".jpg", "简介", "备注", "大陆", "170cm", "50kg", "1990-01-01", "北京", "O", "白羊座",
                "学校", "作品", "标签", "演员", 1, new BigDecimal("9.0"), 5, 60, 1, 2, 3, 4, 5, 1_700_000_000L,
                1_690_000_000L);
    }

    static ActorDetailRow actorDetail(long id, int categoryId) {
        return new ActorDetailRow(id, categoryId, "演员" + id, "yanyuan" + id, "别名", "Y", "", "女",
                "upload/actor/" + id + ".jpg", "简介", "备注", "大陆", "170cm", "50kg", "1990-01-01", "北京", "O", "白羊座",
                "学校", "作品", "标签", "演员", 1, new BigDecimal("9.0"), 45, 5, 60, 1, 2, 3, 4, 5, 1_700_000_000L,
                1_690_000_000L, "<p>演员</p>");
    }

    static RoleSummaryRow roleSummary(long id, long videoId) {
        return new RoleSummaryRow(id, videoId, "角色" + id, "juese" + id, "J", "", "演员甲", "备注",
                "upload/role/" + id + ".jpg", 3, 1, new BigDecimal("6.0"), 2, 10, 1, 2, 3, 4, 5, 1_700_000_000L,
                1_690_000_000L);
    }

    static RoleDetailRow roleDetail(long id, long videoId) {
        return new RoleDetailRow(id, videoId, "角色" + id, "juese" + id, "J", "", "演员甲", "备注",
                "upload/role/" + id + ".jpg", 3, 1, new BigDecimal("6.0"), 12, 2, 10, 1, 2, 3, 4, 5, 1_700_000_000L,
                1_690_000_000L, "<p>角色</p>");
    }

    static VodRefRow vodRef(long id, int categoryId) {
        return new VodRefRow(id, categoryId, "视频" + id, "shipin" + id);
    }

    static WebsiteSummaryRow websiteSummary(long id, int categoryId) {
        return new WebsiteSummaryRow(id, categoryId, "网址" + id, "副标题", "wangzhi" + id, "W", "", 9,
                "https://example.test/" + id, "upload/web/" + id + ".jpg", "", "大陆", "国语", "标签", "导航", "简介", "备注",
                1, new BigDecimal("5.5"), 1, 10, 1, 2, 3, 4, 5, 6, 7, 8, 9, 1_700_000_000L, 1_690_000_000L);
    }

    static WebsiteDetailRow websiteDetail(long id, int categoryId) {
        return new WebsiteDetailRow(id, categoryId, "网址" + id, "副标题", "wangzhi" + id, "W", "", 9,
                "https://example.test/" + id, "upload/web/" + id + ".jpg", "", "大陆", "国语", "标签", "导航", "简介", "备注",
                1, new BigDecimal("5.5"), 11, 1, 10, 1, 2, 3, 4, 5, 6, 7, 8, 9, 1_700_000_000L, 1_690_000_000L,
                "<p>网址</p>", "首页$upload/w1.jpg");
    }

    static LinkRow link(int id, int type) {
        return new LinkRow(id, type, "友链" + id, id * 10, "https://link.test/" + id, type == 1 ? "upload/l.png" : "",
                1_690_000_000L, 1_700_000_000L);
    }

    static VodPlotRow plot(long id, int categoryId, int passwordRequired, int copyright) {
        return new VodPlotRow(id, categoryId, "第1集$$$第2集", "剧情一", passwordRequired, copyright);
    }
}
