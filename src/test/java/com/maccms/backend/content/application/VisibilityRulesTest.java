package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.content.Fixtures;
import com.maccms.backend.content.infrastructure.GroupPermissionRow;
import com.maccms.backend.content.infrastructure.TypeRow;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 游客权限与分类可见性规则（对应旧 Base.php:97-100、Type.php:189、mac_list_to_tree）。 */
class VisibilityRulesTest {

    @Test
    void guestPermissionFollowsPhpStrposAndEmptySemantics() {
        GroupPermission p = GroupPermission.from(new GroupPermissionRow(1, ",1,2,10,",
                "{\"1\":{\"1\":\"1\",\"2\":\"0\"},\"2\":{\"1\":\"\",\"2\":2},\"10\":{\"1\":[\"x\"]},\"5\":{\"1\":\"1\"}}"));
        assertThat(p.allows(1, GroupPermission.LIST)).isTrue();
        assertThat(p.allows(1, GroupPermission.DETAIL)).as("\"0\" 在 PHP 中是 empty").isFalse();
        assertThat(p.allows(2, GroupPermission.LIST)).as("空字符串是 empty").isFalse();
        assertThat(p.allows(2, GroupPermission.DETAIL)).as("数字 2 非 empty").isTrue();
        assertThat(p.allows(10, GroupPermission.LIST)).isTrue();
        assertThat(p.allows(5, GroupPermission.LIST)).as("不在 group_type 中").isFalse();
        // ",1," 不能匹配 ",10,"——与 strpos(',' . group_type, ',' . id . ',') 一致
        GroupPermission onlyTen = GroupPermission.from(new GroupPermissionRow(1, ",10,", "{\"1\":{\"1\":\"1\"}}"));
        assertThat(onlyTen.allows(1, GroupPermission.LIST)).isFalse();
    }

    @Test
    void guestPermissionToleratesMissingOrBrokenData() {
        assertThat(GroupPermission.from(null).allows(1, GroupPermission.LIST)).isFalse();
        assertThat(GroupPermission.from(new GroupPermissionRow(1, ",1,", "not json")).allows(1, "1")).isFalse();
        assertThat(GroupPermission.from(new GroupPermissionRow(1, null, null)).allows(1, "1")).isFalse();
        // PHP json_decode 对 JSON 数组同样按下标取值
        assertThat(GroupPermission.from(new GroupPermissionRow(1, ",0,", "[{\"1\":\"1\"}]")).allows(0, "1")).isTrue();
    }

    @Test
    void catalogHidesCategoriesWithoutListPermissionAndOrphans() {
        CategoryCatalog c = CategoryCatalog.build(Fixtures.standardTypes(), GroupPermission.from(Fixtures.guestGroup()), true);
        assertThat(c.allIds()).containsExactly(1, 6, 13, 2, 7);
        assertThat(c.isVisible(3)).isFalse();
        assertThat(c.roots()).extracting(TypeRow::id).containsExactly(1, 2);
        assertThat(c.childrenOf(1)).extracting(TypeRow::id).containsExactly(6, 7);
        assertThat(c.idsForFilter(1)).containsExactlyInAnyOrder(1, 6, 7);
        assertThat(c.idsForFilter(6)).containsExactly(6);
        assertThat(c.idsForFilter(3)).isEmpty();

        // 父分类 1 无列表权限 → 其子分类 6、7 也不可见
        GroupPermission noRoot = GroupPermission.from(new GroupPermissionRow(1, ",6,7,2,",
                "{\"6\":{\"1\":\"1\"},\"7\":{\"1\":\"1\"},\"2\":{\"1\":\"1\"}}"));
        CategoryCatalog c2 = CategoryCatalog.build(Fixtures.standardTypes(), noRoot, true);
        assertThat(c2.allIds()).containsExactly(2);
    }

    @Test
    void catalogWithoutGuestEnforcementShowsAllEnabledReachable() {
        List<TypeRow> rows = List.of(Fixtures.type(1, 0, 1), Fixtures.type(6, 1, 1), Fixtures.type(8, 99, 1));
        CategoryCatalog c = CategoryCatalog.build(rows, GroupPermission.from(null), false);
        // 8 的上级 99 不存在/已停用 → 不可见（与 mac_list_to_tree 丢弃孤儿节点一致）
        assertThat(c.allIds()).containsExactly(1, 6);
    }

    @Test
    void catalogSurvivesParentCycles() {
        List<TypeRow> rows = List.of(Fixtures.type(1, 2, 1), Fixtures.type(2, 1, 1), Fixtures.type(3, 0, 1));
        CategoryCatalog c = CategoryCatalog.build(rows, GroupPermission.from(null), false);
        assertThat(c.allIds()).containsExactly(3);
    }

    @Test
    void extendParsingDefaultsToEmptyStrings() {
        assertThat(ContentReadService.parseExtend(Fixtures.FULL_EXTEND).classes()).isEqualTo("喜剧,爱情");
        assertThat(ContentReadService.parseExtend("").area()).isEmpty();
        assertThat(ContentReadService.parseExtend("{broken").year()).isEmpty();
        assertThat(ContentReadService.parseExtend("[1,2]").lang()).isEmpty();
        assertThat(ContentReadService.parseExtend("{\"year\":2024,\"area\":null}").year()).isEqualTo("2024");
        assertThat(ContentReadService.parseExtend("{\"year\":2024,\"area\":null}").area()).isEmpty();
    }

    @Test
    void listQueryBoundaries() {
        VideoListQuery q = VideoListQuery.parse(null, null, null, null, null);
        assertThat(q).isEqualTo(new VideoListQuery(null, VideoSort.TIME, true, 1, 20));
        assertThat(VideoListQuery.parse("65535", "id", "asc", "1000", "100").offset()).isEqualTo(99_900L);
        assertThat(VideoSort.fromApiValue("hits_month")).contains(VideoSort.HITS_MONTH);
        assertThat(VideoSort.fromApiValue("rnd")).isEmpty();
        assertThat(VideoId.parse("4294967295")).isEqualTo(4_294_967_295L);
    }
}
