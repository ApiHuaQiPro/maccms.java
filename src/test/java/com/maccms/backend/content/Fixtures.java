package com.maccms.backend.content;

import com.maccms.backend.content.infrastructure.GroupPermissionRow;
import com.maccms.backend.content.infrastructure.TypeRow;
import com.maccms.backend.content.infrastructure.VodDetailRow;
import com.maccms.backend.content.infrastructure.VodSummaryRow;
import java.math.BigDecimal;
import java.util.List;

/** 单元/契约测试用的合成数据，形态与 install.sql、initdata.sql 一致。 */
public final class Fixtures {

    private Fixtures() {
    }

    public static final String FULL_EXTEND =
            "{\"class\":\"喜剧,爱情\",\"area\":\"大陆,香港\",\"lang\":\"国语\",\"year\":\"2024,2023\","
                    + "\"star\":\"\",\"director\":\"\",\"state\":\"正片\",\"version\":\"高清\"}";

    public static TypeRow type(int id, int parentId, int sort) {
        return new TypeRow(id, "分类" + id, "fenlei" + id, sort, parentId, "", "", "", "", "", FULL_EXTEND);
    }

    /**
     * 分类树：1(根,sort1) ← 6,7；2(根,sort2) ← 13；3(根,sort3)。
     * 行顺序模拟 TypeMapper 的 ORDER BY type_sort, type_id。
     */
    public static List<TypeRow> standardTypes() {
        return List.of(type(1, 0, 1), type(6, 1, 1), type(13, 2, 1), type(2, 0, 2), type(7, 1, 2), type(3, 0, 3));
    }

    /** 游客组：分类 1/6/7/2/13/3 都在 group_type 中；3 只有详情位没有列表位；7 只有列表位没有详情位。 */
    public static GroupPermissionRow guestGroup() {
        return new GroupPermissionRow(1, ",1,6,7,2,13,3,",
                "{\"1\":{\"1\":\"1\",\"2\":\"2\"},\"6\":{\"1\":\"1\",\"2\":\"2\"},\"7\":{\"1\":\"1\"},"
                        + "\"2\":{\"1\":\"1\",\"2\":\"2\"},\"13\":{\"1\":\"1\",\"2\":\"2\"},\"3\":{\"2\":\"2\"}}");
    }

    public static VodSummaryRow summary(long id, int categoryId) {
        return new VodSummaryRow(id, categoryId, "视频" + id, "别名", "shipin" + id, "S", "", "喜剧", "标签",
                "upload/vod/" + id + ".jpg", "", "", "演员甲", "导演乙", "简介", "更新至10集", "大陆", "国语", "2024",
                "高清", "正片", "10", 24, 0, 1, 0, new BigDecimal("8.5"), 10, 100, 1, 7, 30, 3, 1,
                1_700_000_000L + id, 1_690_000_000L + id, 0);
    }

    public static VodDetailRow detail(long id, int categoryId, int passwordRequired, int copyright) {
        return new VodDetailRow(id, categoryId, "视频" + id, "别名", "shipin" + id, "S", "", "喜剧", "标签",
                "upload/vod/" + id + ".jpg", "", "", "演员甲", "导演乙", "编剧丙", "幕后", "简介", "更新至10集",
                "2024-01-01", "大陆", "国语", "2024", "高清", "正片", "CCTV", "周一", "45分钟", "10", 24, 1, 1,
                copyright, new BigDecimal("8.5"), 85, 10, 1234567L, new BigDecimal("7.9"), 100, 1, 7, 30, 3, 1,
                1_700_000_000L, 1_690_000_000L, "<p>正文</p>", passwordRequired);
    }
}
