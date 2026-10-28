package com.maccms.backend.common.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.List;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;

/** 登记表与运行期拦截器（12 文档第 3 节）：未登记的模块、表、操作都在执行前被拒绝。 */
class WriteRegistryTest {

    private static final WriteRegistry REGISTRY = WriteRegistry.of(List.of(
            new WritePermission("member", "mac_user", WriteOperation.UPDATE, "B09"),
            new WritePermission("member", "mac_plog", WriteOperation.INSERT, "B10")));
    private static final String MEMBER = "com.maccms.backend.member.infrastructure.UserWriteMapper";

    @Test
    void productionRegistryHasOnlyTheMemberWrites() {
        // B07、B08 为空；B09（MACC-33）登记会员模块对 mac_user 的插入与更新；B10（MACC-34）登记会员权益与财务的写入；
        // B11（MACC-35）登记内容计数、视频搜索缓存、评论与留言
        assertThat(WriteRegistry.registered().allows("member", "mac_user", WriteOperation.UPDATE)).isTrue();
        assertThat(WriteRegistry.registered().allows("member", "mac_user", WriteOperation.INSERT)).isTrue();
        // B14（MACC-38）：后台会员删除
        assertThat(WriteRegistry.registered().allows("member", "mac_user", WriteOperation.DELETE)).isTrue();
        assertThat(WriteRegistry.registered().allows("member", "mac_plog", WriteOperation.INSERT)).isTrue();
        assertThat(WriteRegistry.registered().allows("member", "mac_plog", WriteOperation.UPDATE)).isFalse();
        // B14：生成与删除卡密、删除订单（没有修改订单的后台动作）、会员组增删改、提现审核、删除推广访问
        assertThat(WriteRegistry.registered().allows("member", "mac_card", WriteOperation.INSERT)).isTrue();
        assertThat(WriteRegistry.registered().allows("member", "mac_card", WriteOperation.DELETE)).isTrue();
        assertThat(WriteRegistry.registered().allows("member", "mac_order", WriteOperation.UPDATE)).isFalse();
        assertThat(WriteRegistry.registered().allows("member", "mac_order", WriteOperation.DELETE)).isTrue();
        assertThat(WriteRegistry.registered().allows("member", "mac_group", WriteOperation.UPDATE)).isTrue();
        assertThat(WriteRegistry.registered().allows("member", "mac_group", WriteOperation.INSERT)).isTrue();
        assertThat(WriteRegistry.registered().allows("member", "mac_group", WriteOperation.DELETE)).isTrue();
        assertThat(WriteRegistry.registered().allows("member", "mac_cash", WriteOperation.UPDATE)).isTrue();
        assertThat(WriteRegistry.registered().allows("member", "mac_visit", WriteOperation.DELETE)).isTrue();
        assertThat(WriteRegistry.registered().allows("member", "mac_visit", WriteOperation.UPDATE)).isFalse();
        // B15（MACC-39）：数据库工具"批量替换"可以 UPDATE 任一 mac_* 表，但后台模块不能借此新增或删除会员组等
        assertThat(WriteRegistry.registered().allows("admin", "mac_group", WriteOperation.UPDATE)).isTrue();
        assertThat(WriteRegistry.registered().allows("admin", "mac_group", WriteOperation.INSERT)).isFalse();
        assertThat(WriteRegistry.registered().allows("admin", "mac_vod", WriteOperation.DELETE)).isFalse();
        assertThat(WriteRegistry.registered().allows("content", "mac_user", WriteOperation.UPDATE)).isFalse();
        assertThat(WriteRegistry.registered().allows("content", "mac_vod", WriteOperation.UPDATE)).isTrue();
        // B13（MACC-37）：后台内容管理的新增与删除
        assertThat(WriteRegistry.registered().allows("content", "mac_vod", WriteOperation.INSERT)).isTrue();
        assertThat(WriteRegistry.registered().allows("content", "mac_vod", WriteOperation.DELETE)).isTrue();
        assertThat(WriteRegistry.registered().allows("content", "mac_link", WriteOperation.UPDATE)).isTrue();
        assertThat(WriteRegistry.registered().allows("content", "mac_annex", WriteOperation.INSERT)).isTrue();
        assertThat(WriteRegistry.registered().allows("content", "mac_comment", WriteOperation.DELETE)).isFalse();
        assertThat(WriteRegistry.registered().allows("member", "mac_annex", WriteOperation.INSERT)).isFalse();
        // B12（MACC-36）：后台"清理缓存"删除过期的视频搜索缓存；管理员的增删改
        assertThat(WriteRegistry.registered().allows("content", "mac_vod_search", WriteOperation.DELETE)).isTrue();
        assertThat(WriteRegistry.registered().allows("admin", "mac_admin", WriteOperation.INSERT)).isTrue();
        assertThat(WriteRegistry.registered().allows("admin", "mac_admin", WriteOperation.UPDATE)).isTrue();
        assertThat(WriteRegistry.registered().allows("admin", "mac_admin", WriteOperation.DELETE)).isTrue();
        // B15：数据库工具的批量替换让后台模块可以 UPDATE mac_user（不能新增或删除会员）
        assertThat(WriteRegistry.registered().allows("admin", "mac_user", WriteOperation.UPDATE)).isTrue();
        assertThat(WriteRegistry.registered().allows("admin", "mac_user", WriteOperation.DELETE)).isFalse();
        assertThat(WriteRegistry.registered().allows("member", "mac_admin", WriteOperation.UPDATE)).isFalse();
        assertThat(WriteRegistry.registered().allows("comment", "mac_comment", WriteOperation.DELETE)).isTrue();
        assertThat(WriteRegistry.registered().allows("comment", "mac_gbook", WriteOperation.UPDATE)).isTrue();
        assertThat(WriteRegistry.registered().allows("comment", "mac_gbook", WriteOperation.DELETE)).isTrue();
        assertThat(WriteRegistry.registered().allows("comment", "mac_vod", WriteOperation.UPDATE)).isFalse();
        // B17（MACC-41）：采集源与自定义规则的增删改；删除规则时连同内容与采集历史（只删，不新增、不修改，B20 才写它们）
        assertThat(WriteRegistry.registered().allows("collect", "mac_collect", WriteOperation.INSERT)).isTrue();
        assertThat(WriteRegistry.registered().allows("collect", "mac_cj_node", WriteOperation.DELETE)).isTrue();
        assertThat(WriteRegistry.registered().allows("collect", "mac_cj_content", WriteOperation.DELETE)).isTrue();
        // B20（MACC-44）：规则采集记下采集历史与内容、写入采集结果与发布状态；采集历史不修改
        assertThat(WriteRegistry.registered().allows("collect", "mac_cj_content", WriteOperation.INSERT)).isTrue();
        assertThat(WriteRegistry.registered().allows("collect", "mac_cj_content", WriteOperation.UPDATE)).isTrue();
        assertThat(WriteRegistry.registered().allows("collect", "mac_cj_history", WriteOperation.INSERT)).isTrue();
        assertThat(WriteRegistry.registered().allows("collect", "mac_cj_history", WriteOperation.UPDATE)).isFalse();
        // B19（MACC-43）：采集入库与 Receive 新增、更新视频（不删除）
        assertThat(WriteRegistry.registered().allows("collect", "mac_vod", WriteOperation.INSERT)).isTrue();
        assertThat(WriteRegistry.registered().allows("collect", "mac_vod", WriteOperation.UPDATE)).isTrue();
        assertThat(WriteRegistry.registered().allows("collect", "mac_vod", WriteOperation.DELETE)).isFalse();
        // B19 增量 2：文章、演员、角色、网址、评论的新增与更新（不删除）
        for (String t : java.util.List.of("mac_art", "mac_actor", "mac_role", "mac_website", "mac_comment")) {
            assertThat(WriteRegistry.registered().allows("collect", t, WriteOperation.INSERT)).isTrue();
            assertThat(WriteRegistry.registered().allows("collect", t, WriteOperation.UPDATE)).isTrue();
            assertThat(WriteRegistry.registered().allows("collect", t, WriteOperation.DELETE)).isFalse();
        }
        assertThat(WriteRegistry.registered().permissions()).hasSize(108);
    }

    @Test
    void moduleComesFromTheInfrastructurePackage() {
        assertThat(WriteRegistry.moduleOf(MEMBER)).isEqualTo("member");
        assertThat(WriteRegistry.moduleOf("com.maccms.backend.member.infrastructure.write.UserWriteMapper"))
                .isEqualTo("member");
        for (String bad : List.of("com.maccms.backend.member.application.UserWriteMapper",
                "com.maccms.backend.member.UserWriteMapper", "com.example.member.infrastructure.UserWriteMapper",
                "com.maccms.backend.member.infrastructurex.UserWriteMapper", "UserWriteMapper", "")) {
            assertThatThrownBy(() -> WriteRegistry.moduleOf(bad)).as(bad).isInstanceOf(WriteRejectedException.class);
        }
        assertThatThrownBy(() -> WriteRegistry.moduleOf(null)).isInstanceOf(WriteRejectedException.class);
    }

    @Test
    void unregisteredModuleTableOrOperationIsRejected() {
        WriteGuardInterceptor guard = new WriteGuardInterceptor(REGISTRY);
        assertThatCode(() -> guard.check(MEMBER + ".deduct", "UPDATE",
                "UPDATE mac_user SET user_points = user_points - ? WHERE user_id = ?")).doesNotThrowAnyException();
        assertThatCode(() -> guard.check(MEMBER + ".log", "INSERT",
                "INSERT INTO mac_plog (user_id) VALUES (?)")).doesNotThrowAnyException();

        // 表未登记
        assertThatThrownBy(() -> guard.check(MEMBER + ".g", "INSERT", "INSERT INTO mac_gbook (gbook_name) VALUES (?)"))
                .isInstanceOf(WriteRejectedException.class).hasMessageContaining("mac_gbook");
        // 操作未登记
        assertThatThrownBy(() -> guard.check(MEMBER + ".d", "DELETE", "DELETE FROM mac_user WHERE user_id = ?"))
                .isInstanceOf(WriteRejectedException.class).hasMessageContaining("DELETE");
        // 模块未登记
        assertThatThrownBy(() -> guard.check("com.maccms.backend.admin.infrastructure.UserWriteMapper.u", "UPDATE",
                "UPDATE mac_user SET user_points = 0 WHERE user_id = ?"))
                .isInstanceOf(WriteRejectedException.class).hasMessageContaining("admin");
        // 不在 infrastructure、元素类型与 SQL 不符、动态 SQL 运行时变成整表写入
        assertThatThrownBy(() -> guard.check("com.maccms.backend.member.application.X.u", "UPDATE",
                "UPDATE mac_user SET user_points = 0 WHERE user_id = ?")).isInstanceOf(WriteRejectedException.class);
        assertThatThrownBy(() -> guard.check(MEMBER + ".u", "INSERT",
                "UPDATE mac_user SET user_points = 0 WHERE user_id = ?")).isInstanceOf(WriteRejectedException.class);
        assertThatThrownBy(() -> guard.check(MEMBER + ".u", "UPDATE", "UPDATE mac_user SET user_points = 0"))
                .isInstanceOf(WriteRejectedException.class);
        assertThatThrownBy(() -> guard.check("nodot", "UPDATE", "UPDATE mac_user SET user_points = 0 WHERE user_id = 1"))
                .isInstanceOf(WriteRejectedException.class);
    }

    @Test
    void queriesThroughTheWritePoolAreRejected() throws Exception {
        WriteGuardInterceptor guard = new WriteGuardInterceptor(REGISTRY);
        Method query = Executor.class.getMethod("query", org.apache.ibatis.mapping.MappedStatement.class, Object.class,
                RowBounds.class, ResultHandler.class);
        Invocation invocation = new Invocation(new Object(), query, new Object[] {null, null, null, null});
        assertThatThrownBy(() -> guard.intercept(invocation)).isInstanceOf(WriteRejectedException.class)
                .hasMessageContaining("不执行查询");
    }

    @Test
    void duplicateAndMalformedPermissionsAreRejected() {
        WritePermission p = new WritePermission("member", "mac_user", WriteOperation.UPDATE, "B09");
        assertThatThrownBy(() -> WriteRegistry.of(List.of(p, p))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WritePermission("Member", "mac_user", WriteOperation.UPDATE, "B09"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WritePermission("member", "user", WriteOperation.UPDATE, "B09"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WritePermission("member", "mac_user", WriteOperation.UPDATE, "9"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WritePermission("member", "mac_user", null, "B09"))
                .isInstanceOf(NullPointerException.class);
    }
}
