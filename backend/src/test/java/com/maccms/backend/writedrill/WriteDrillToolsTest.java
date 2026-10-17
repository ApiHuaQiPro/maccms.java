package com.maccms.backend.writedrill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.common.write.WriteOperation;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 回滚演练工具与并发测试支撑的纯逻辑部分（不连数据库）。真实 MySQL 上的完整演练见 WriteInfrastructureMySqlIT。 */
class WriteDrillToolsTest {

    private static final List<String> COLS = List.of("user_id", "user_name", "user_points", "user_portrait");

    private static List<Object> row(Object... v) {
        return Arrays.asList(v);
    }

    @SafeVarargs
    private static DatabaseSnapshot snapshot(List<Object>... rows) {
        Map<List<Object>, List<Object>> m = new LinkedHashMap<>();
        for (List<Object> r : rows) {
            m.put(List.of(r.get(0)), r);
        }
        Map<String, TableSnapshot> t = new LinkedHashMap<>();
        t.put("mac_user", new TableSnapshot("mac_user", COLS, List.of("user_id"), m));
        return new DatabaseSnapshot(t);
    }

    @Test
    void diffFindsAddedRemovedAndChangedRowsByPrimaryKey() {
        DatabaseSnapshot before = snapshot(row(1L, "a", 10L, null), row(2L, "b", 20L, "p"), row(3L, "c", 0L, "x"));
        DatabaseSnapshot after = snapshot(row(1L, "a", 10L, null), row(2L, "b", 5L, null), row(4L, "d", 0L, ""));
        SnapshotDiff diff = SnapshotDiff.between(before, after);
        SnapshotDiff.TableDiff d = diff.tables().get(0);
        assertThat(d.added()).containsExactly(List.of(4L));
        assertThat(d.removed()).containsExactly(List.of(3L));
        assertThat(d.changed()).containsOnlyKeys(List.of(2L));
        assertThat(d.changed().get(List.of(2L))).containsExactly("user_points", "user_portrait");
        assertThat(diff.isEmpty()).isFalse();
        // 摘要不含列值
        assertThat(diff.summary()).contains("mac_user +1 -1 ~1").doesNotContain("20").doesNotContain("\"b\"");
        assertThat(SnapshotDiff.between(before, before).isEmpty()).isTrue();
        // byte[] 按内容比较
        DatabaseSnapshot bytesA = snapshot(row(1L, new byte[] {1, 2}, 0L, null));
        DatabaseSnapshot bytesB = snapshot(row(1L, new byte[] {1, 2}, 0L, null));
        assertThat(SnapshotDiff.between(bytesA, bytesB).isEmpty()).isTrue();
    }

    @Test
    void restorePlanUsesOnlyKeyedDmlInSafeOrder() {
        DatabaseSnapshot target = snapshot(row(1L, "a", 10L, null), row(2L, "b", 20L, "p"), row(3L, "c", 0L, "x"));
        DatabaseSnapshot current = snapshot(row(1L, "a", 10L, null), row(2L, "b", 5L, null), row(4L, "d", 0L, ""));
        List<DmlRestore.Statement> plan = DmlRestore.plan(target, current);
        assertThat(plan).extracting(DmlRestore.Statement::operation)
                .containsExactly(WriteOperation.DELETE, WriteOperation.UPDATE, WriteOperation.INSERT);
        assertThat(plan.get(0).sql()).isEqualTo("DELETE FROM `mac_user` WHERE `user_id` = ?");
        assertThat(plan.get(0).params()).containsExactly(4L);
        assertThat(plan.get(1).sql()).isEqualTo("UPDATE `mac_user` SET `user_points` = ?, `user_portrait` = ? WHERE `user_id` = ?");
        assertThat(plan.get(1).params()).containsExactly(20L, "p", 2L);
        assertThat(plan.get(2).sql()).isEqualTo(
                "INSERT INTO `mac_user` (`user_id`, `user_name`, `user_points`, `user_portrait`) VALUES (?, ?, ?, ?)");
        assertThat(plan.get(2).params()).containsExactly(3L, "c", 0L, "x");
        assertThat(plan.get(1).describe()).isEqualTo("UPDATE mac_user [2]").doesNotContain("20");
        for (DmlRestore.Statement s : plan) {
            assertThat(s.sql()).doesNotContainIgnoringCase("truncate").doesNotContainIgnoringCase("alter")
                    .doesNotContainIgnoringCase("drop").doesNotContainIgnoringCase("create");
        }
        assertThat(DmlRestore.plan(target, target)).isEmpty();
    }

    @Test
    void restoreRefusesStructureChangesBetweenSnapshots() {
        DatabaseSnapshot a = snapshot(row(1L, "a", 10L, null));
        Map<String, TableSnapshot> t = new LinkedHashMap<>();
        t.put("mac_user", new TableSnapshot("mac_user", List.of("user_id", "user_name"), List.of("user_id"),
                Map.of(List.of(1L), List.of(1L, "a"))));
        assertThatThrownBy(() -> DmlRestore.plan(a, new DatabaseSnapshot(t))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DmlRestore.plan(a, new DatabaseSnapshot(Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disposableDatabaseNamePattern() {
        assertThat(DmlRestore.DISPOSABLE.matcher("maccms_it_20261016120000_0a1b2c3d").matches()).isTrue();
        for (String db : List.of("maccms_dev", "maccms", "maccms_it_", "maccms_it_2026_x", "MACCMS_IT_20261016120000_0a1b2c3d",
                "maccms_it_20261016120000_0a1b2c3d_x")) {
            assertThat(DmlRestore.DISPOSABLE.matcher(db).matches()).as(db).isFalse();
        }
    }

    @Test
    void concurrentRunnerStartsAllThreadsTogetherAndCollectsFailures() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        ConcurrentWrites.Outcome<Integer> o = ConcurrentWrites.run(8, i -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            if (i % 2 == 0) {
                throw new IllegalStateException("偶数线程失败");
            }
            return i;
        });
        assertThat(o.results()).hasSize(8);
        assertThat(o.values()).containsExactly(1, 3, 5, 7);
        assertThat(o.failures()).hasSize(4).allSatisfy(t -> assertThat(t).isInstanceOf(IllegalStateException.class));
        assertThat(maxInFlight.get()).as("起跑信号后同时在执行").isGreaterThan(1);
        assertThat(o.count(3)).isEqualTo(1);
        assertThatThrownBy(() -> ConcurrentWrites.run(1, i -> i)).isInstanceOf(IllegalArgumentException.class);
    }
}
