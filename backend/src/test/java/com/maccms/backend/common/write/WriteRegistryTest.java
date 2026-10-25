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
        assertThat(WriteRegistry.registered().allows("member", "mac_user", WriteOperation.DELETE)).isFalse();
        assertThat(WriteRegistry.registered().allows("member", "mac_plog", WriteOperation.INSERT)).isTrue();
        assertThat(WriteRegistry.registered().allows("member", "mac_plog", WriteOperation.UPDATE)).isFalse();
        assertThat(WriteRegistry.registered().allows("member", "mac_card", WriteOperation.INSERT)).isFalse();
        assertThat(WriteRegistry.registered().allows("member", "mac_card", WriteOperation.DELETE)).isFalse();
        assertThat(WriteRegistry.registered().allows("member", "mac_order", WriteOperation.UPDATE)).isFalse();
        assertThat(WriteRegistry.registered().allows("member", "mac_group", WriteOperation.UPDATE)).isFalse();
        assertThat(WriteRegistry.registered().allows("content", "mac_user", WriteOperation.UPDATE)).isFalse();
        assertThat(WriteRegistry.registered().allows("content", "mac_vod", WriteOperation.UPDATE)).isTrue();
        assertThat(WriteRegistry.registered().allows("content", "mac_vod", WriteOperation.INSERT)).isFalse();
        assertThat(WriteRegistry.registered().allows("content", "mac_vod", WriteOperation.DELETE)).isFalse();
        // B12（MACC-36）：后台"清理缓存"删除过期的视频搜索缓存；管理员的增删改
        assertThat(WriteRegistry.registered().allows("content", "mac_vod_search", WriteOperation.DELETE)).isTrue();
        assertThat(WriteRegistry.registered().allows("admin", "mac_admin", WriteOperation.INSERT)).isTrue();
        assertThat(WriteRegistry.registered().allows("admin", "mac_admin", WriteOperation.UPDATE)).isTrue();
        assertThat(WriteRegistry.registered().allows("admin", "mac_admin", WriteOperation.DELETE)).isTrue();
        assertThat(WriteRegistry.registered().allows("admin", "mac_user", WriteOperation.UPDATE)).isFalse();
        assertThat(WriteRegistry.registered().allows("member", "mac_admin", WriteOperation.UPDATE)).isFalse();
        assertThat(WriteRegistry.registered().allows("comment", "mac_comment", WriteOperation.DELETE)).isFalse();
        assertThat(WriteRegistry.registered().allows("comment", "mac_gbook", WriteOperation.UPDATE)).isFalse();
        assertThat(WriteRegistry.registered().allows("comment", "mac_vod", WriteOperation.UPDATE)).isFalse();
        assertThat(WriteRegistry.registered().permissions()).hasSize(27);
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
