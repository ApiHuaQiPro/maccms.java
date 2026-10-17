package com.maccms.backend.common.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.common.write.WriteSequence.Role;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.RecoverableDataAccessException;

/** 多表写入的固定顺序与补偿（12 文档第 4.4 节）。真实 MySQL 上的同类场景见 WriteInfrastructureMySqlIT。 */
@ExtendWith(OutputCaptureExtension.class)
class WriteSequenceTest {

    private final List<String> calls = new ArrayList<>();

    private IntSupplier op(String name, int result) {
        return () -> {
            calls.add(name);
            return result;
        };
    }

    private IntSupplier fail(String name, RuntimeException e) {
        return () -> {
            calls.add(name);
            throw e;
        };
    }

    private static RuntimeException serverError(int code, String state) {
        return new DataIntegrityViolationException("参数 user-MARKER", new SQLException("x user-MARKER", state, code));
    }

    @Test
    void runsAllStepsInOrder() {
        WriteSequence.named("card-use")
                .step(Role.CLAIM, "card", op("claim", 1), 1, op("release", 1))
                .step(Role.TRANSFER, "points", op("add", 1), 1, op("sub", 1))
                .step(Role.RECORD, "plog", op("log", 1), 1, null)
                .build().run();
        assertThat(calls).containsExactly("claim", "add", "log");
    }

    @Test
    void lostClaimChangesNothing() {
        WriteSequence s = WriteSequence.named("card-use")
                .step(Role.CLAIM, "card", op("claim", 0), 1, op("release", 1))
                .step(Role.TRANSFER, "points", op("add", 1), 1, op("sub", 1))
                .build();
        assertThatThrownBy(s::run).isInstanceOfSatisfying(WriteConflictException.class, e -> {
            assertThat(e.step()).isEqualTo("card");
            assertThat(e.matched()).isZero();
        });
        assertThat(calls).containsExactly("claim");
    }

    @Test
    void laterConflictCompensatesInReverseOrder() {
        WriteSequence s = WriteSequence.named("buy")
                .step(Role.CLAIM, "order", op("claim", 1), 1, op("unclaim", 1))
                .step(Role.TRANSFER, "points", op("deduct", 1), 1, op("refund", 1))
                .step(Role.TRANSFER, "vip", op("extend", 0), 1, op("shorten", 1))
                .step(Role.RECORD, "plog", op("log", 1), 1, null)
                .build();
        assertThatThrownBy(s::run).isInstanceOfSatisfying(WriteConflictException.class,
                e -> assertThat(e.step()).isEqualTo("vip"));
        assertThat(calls).containsExactly("claim", "deduct", "extend", "refund", "unclaim");
    }

    @Test
    void serverRejectedStepIsCompensatedAndRethrown() {
        RuntimeException tooLong = serverError(1406, "22001");
        WriteSequence s = WriteSequence.named("card-use")
                .step(Role.CLAIM, "card", op("claim", 1), 1, op("release", 1))
                .step(Role.TRANSFER, "points", op("add", 1), 1, op("sub", 1))
                .step(Role.RECORD, "plog", fail("log", tooLong), 1, null)
                .build();
        assertThatThrownBy(s::run).isSameAs(tooLong);
        assertThat(calls).containsExactly("claim", "add", "log", "sub", "release");
        // 执行前被拒绝的写入同样肯定没有执行
        calls.clear();
        WriteSequence r = WriteSequence.named("x")
                .step(Role.TRANSFER, "a", op("a", 1), 1, op("undo-a", 1))
                .step(Role.RECORD, "b", fail("b", new WriteRejectedException("未登记")), 1, null)
                .build();
        assertThatThrownBy(r::run).isInstanceOf(WriteRejectedException.class);
        assertThat(calls).containsExactly("a", "b", "undo-a");
    }

    @Test
    void uncertainOutcomeIsNotCompensated(CapturedOutput out) {
        for (RuntimeException e : List.of(
                new RecoverableDataAccessException("lost", new SQLException("x", "08S01", 0)),
                new RecoverableDataAccessException("lost", new SQLException("x", "HY000", 2013)),
                new RecoverableDataAccessException("timeout", new SQLTimeoutException("x", "HY008", 3024)),
                serverError(1317, "70100"),
                new IllegalStateException("不是 SQL 异常"))) {
            calls.clear();
            WriteSequence s = WriteSequence.named("card-use")
                    .step(Role.CLAIM, "card", op("claim", 1), 1, op("release", 1))
                    .step(Role.TRANSFER, "points", fail("add", e), 1, op("sub", 1))
                    .build();
            assertThatThrownBy(s::run).isInstanceOfSatisfying(CompensationFailedException.class, c -> {
                assertThat(c.reason()).isEqualTo(CompensationFailedException.Reason.OUTCOME_UNKNOWN);
                assertThat(c.unresolvedSteps()).containsExactly("card", "points");
                assertThat(c.getCause()).isSameAs(e);
            });
            assertThat(calls).as(e.toString()).containsExactly("claim", "add");
        }
        // 预期多行的步骤出错时 MyISAM 可能已部分执行：一律不确定
        calls.clear();
        WriteSequence multi = WriteSequence.named("batch")
                .step(Role.TRANSFER, "many", fail("many", serverError(1406, "22001")), 3, op("undo", 3))
                .build();
        assertThatThrownBy(multi::run).isInstanceOfSatisfying(CompensationFailedException.class,
                c -> assertThat(c.reason()).isEqualTo(CompensationFailedException.Reason.OUTCOME_UNKNOWN));
        assertThat(out.getAll()).contains("sequence=card-use", "reason=OUTCOME_UNKNOWN").doesNotContain("user-MARKER");
    }

    @Test
    void failedCompensationStopsAndKeepsTheClaim(CapturedOutput out) {
        WriteSequence s = WriteSequence.named("card-use")
                .step(Role.CLAIM, "card", op("claim", 1), 1, op("release", 1))
                .step(Role.TRANSFER, "points", op("add", 1), 1, fail("sub", serverError(1205, "40001")))
                .step(Role.RECORD, "plog", op("log", 0), 1, null)
                .build();
        assertThatThrownBy(s::run).isInstanceOfSatisfying(CompensationFailedException.class, c -> {
            assertThat(c.reason()).isEqualTo(CompensationFailedException.Reason.COMPENSATION_FAILED);
            assertThat(c.failedStep()).isEqualTo("plog");
            assertThat(c.unresolvedSteps()).containsExactly("card", "points");
            assertThat(c.getMessage()).doesNotContain("user-MARKER");
        });
        // 补偿失败后立即停止：认领不释放，未对账前同一张卡不能再次生效
        assertThat(calls).containsExactly("claim", "add", "log", "sub");
        assertThat(out.getAll()).contains("写入需人工对账", "sequence=card-use", "step=plog",
                "reason=COMPENSATION_FAILED", "unresolved=[card, points]").doesNotContain("user-MARKER");
    }

    @Test
    void compensationMissStopsBeforeEarlierSteps() {
        WriteSequence s = WriteSequence.named("buy")
                .step(Role.CLAIM, "order", op("claim", 1), 1, op("unclaim", 1))
                .step(Role.TRANSFER, "points", op("deduct", 1), 1, op("refund", 0))
                .step(Role.TRANSFER, "vip", op("extend", 1), 1, op("shorten", 1))
                .step(Role.RECORD, "plog", op("log", 0), 1, null)
                .build();
        assertThatThrownBy(s::run).isInstanceOfSatisfying(CompensationFailedException.class, c -> {
            assertThat(c.reason()).isEqualTo(CompensationFailedException.Reason.COMPENSATION_FAILED);
            assertThat(c.unresolvedSteps()).as("vip 已补偿；points 补偿未命中；order 保留").containsExactly("order", "points");
            assertThat(c.getCause()).isNull();
        });
        assertThat(calls).containsExactly("claim", "deduct", "extend", "log", "shorten", "refund");
    }

    @Test
    void unexpectedRowCountIsNotAnOrdinaryConflict(CapturedOutput out) {
        // 超额匹配（预期 1 行、实际 2 行）：这一步已经改了数据，不补偿任何步骤，认领保留
        WriteSequence over = WriteSequence.named("card-use")
                .step(Role.CLAIM, "card", op("claim", 1), 1, op("release", 1))
                .step(Role.TRANSFER, "points", op("add", 2), 1, op("sub", 1))
                .step(Role.RECORD, "plog", op("log", 1), 1, null)
                .build();
        assertThatThrownBy(over::run).isNotInstanceOf(WriteConflictException.class)
                .isInstanceOfSatisfying(CompensationFailedException.class, c -> {
                    assertThat(c.reason()).isEqualTo(CompensationFailedException.Reason.UNEXPECTED_ROWS);
                    assertThat(c.failedStep()).isEqualTo("points");
                    assertThat(c.unresolvedSteps()).containsExactly("card", "points");
                });
        assertThat(calls).containsExactly("claim", "add");
        assertThat(out.getAll()).contains("写入需人工对账", "sequence=card-use", "step=points", "reason=UNEXPECTED_ROWS",
                "unresolved=[card, points]");
        // 部分生效（预期 3 行、实际 2 行）与负数同样处理
        for (int matched : new int[] {2, -2}) {
            calls.clear();
            WriteSequence partial = WriteSequence.named("batch")
                    .step(Role.TRANSFER, "a", op("a", 1), 1, op("undo-a", 1))
                    .step(Role.TRANSFER, "many", op("many", matched), 3, op("undo-many", 3))
                    .build();
            assertThatThrownBy(partial::run).isInstanceOfSatisfying(CompensationFailedException.class, c -> {
                assertThat(c.reason()).isEqualTo(CompensationFailedException.Reason.UNEXPECTED_ROWS);
                assertThat(c.unresolvedSteps()).containsExactly("a", "many");
            });
            assertThat(calls).containsExactly("a", "many");
        }
        // 预期多行、匹配 0 行：这一步什么都没改，仍是普通冲突
        calls.clear();
        WriteSequence none = WriteSequence.named("batch")
                .step(Role.TRANSFER, "a", op("a", 1), 1, op("undo-a", 1))
                .step(Role.TRANSFER, "many", op("many", 0), 3, op("undo-many", 3))
                .build();
        assertThatThrownBy(none::run).isInstanceOf(WriteConflictException.class);
        assertThat(calls).containsExactly("a", "many", "undo-a");
    }

    @Test
    void builderEnforcesTheFixedOrder() {
        IntSupplier one = () -> 1;
        assertThatThrownBy(() -> WriteSequence.named("x").step(Role.TRANSFER, "a", one, 1, one)
                .step(Role.CLAIM, "b", one, 1, one)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WriteSequence.named("x").step(Role.RECORD, "a", one, 1, one)
                .step(Role.TRANSFER, "b", one, 1, one)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WriteSequence.named("x").step(Role.CLAIM, "a", one, 1, one)
                .step(Role.CLAIM, "b", one, 1, one)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WriteSequence.named("x").step(Role.CLAIM, "a", one, 1, null)
                .step(Role.TRANSFER, "b", one, 1, one)).as("只有最后一步可以没有补偿")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WriteSequence.named("x").step(Role.CLAIM, "a", one, 1, one)
                .step(Role.TRANSFER, "a", one, 1, one)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WriteSequence.named("x").step(Role.CLAIM, "a", one, 0, one))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WriteSequence.named("x").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WriteSequence.named(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireMatchedChecksSingleConditionalWrites(CapturedOutput out) {
        WriteSequence.requireMatched("hit", 1, 1);
        assertThatThrownBy(() -> WriteSequence.requireMatched("hit", 0, 1)).isInstanceOf(WriteConflictException.class);
        assertThatThrownBy(() -> WriteSequence.requireMatched("hit", 2, 1))
                .isInstanceOfSatisfying(CompensationFailedException.class, c -> {
                    assertThat(c.reason()).isEqualTo(CompensationFailedException.Reason.UNEXPECTED_ROWS);
                    assertThat(c.unresolvedSteps()).containsExactly("hit");
                });
        assertThat(out.getAll()).contains("sequence=hit", "reason=UNEXPECTED_ROWS");
    }
}
