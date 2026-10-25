package com.maccms.backend.writedrill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.common.write.CompensationFailedException;
import com.maccms.backend.common.write.WriteConflictException;
import com.maccms.backend.common.write.WriteDataSourceProperties;
import com.maccms.backend.common.write.WriteDataSourceSettings;
import com.maccms.backend.common.write.WriteDatabase;
import com.maccms.backend.common.write.WriteRegistry;
import com.maccms.backend.common.write.WriteRejectedException;
import com.maccms.backend.common.write.WriteSequence;
import com.maccms.backend.common.write.WriteSequence.Role;
import com.maccms.backend.drill.infrastructure.DrillWriteMapper;
import com.maccms.backend.drill.infrastructure.DrillWriteMapper.PlogInsert;
import com.maccms.backend.drillother.infrastructure.OtherWriteMapper;
import com.maccms.backend.schema.SchemaFingerprint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;

/**
 * 写入基础设施的真实 MySQL 演练（B07，./mvnw verify -Pmysql-it；docs/architecture/12-write-infrastructure.md 第 7 节）。
 *
 * <p>流程：外部管理账号新建一次性库 maccms_it_&lt;时间&gt;_&lt;随机&gt; → 原样导入 install.sql → 取表结构指纹 → 写入合成数据 →
 * 取数据快照 S0 → ① 应用默认不创建可写数据源、只读池仍拒绝写入 → ② 打开可写数据源时用正式登记表（为空），演练写入被拒 →
 * ③ 错误凭证不回显 → ④ 并发条件写入（扣减、计数、认领、条件插入、匹配行数）→ ⑤ 多表写入序列的认领幂等、失败补偿、
 * 补偿失败与超额匹配时保留认领并上报、对账后向前补齐 →
 * ⑥ 未登记的表、操作、模块在执行前被拒 → ⑦ MyISAM 不回滚 → ⑧ 快照 S1、逐行 diff、只用 DML 恢复、再次 diff 为空、表结构指纹不变 →
 * 删库并确认不存在。
 *
 * <p>写入只发生在这个一次性库；不连接、不读写 maccms_dev 或任何已有库；不改账号。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
class WriteInfrastructureMySqlIT {

    private static final Pattern DB_NAME = DmlRestore.DISPOSABLE;
    private static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    private static final List<String> TABLES = List.of("mac_user", "mac_card", "mac_vod", "mac_plog", "mac_ulog",
            "mac_gbook");
    private static final int USERS = 20;
    private static final int CARD_POINTS = 50;

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String dbName;
    private static boolean dbCreated;
    private static Map<String, String> atImport;
    private static DatabaseSnapshot s0;
    private static WriteDatabase drill;

    @BeforeAll
    static void setUp() throws Exception {
        adminUrl = requiredEnv("MACCMS_IT_ADMIN_URL");
        adminUser = requiredEnv("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = requiredEnv("MACCMS_IT_ADMIN_PASSWORD");
        byte[] rnd = new byte[4];
        new SecureRandom().nextBytes(rnd);
        dbName = "maccms_it_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "_" + String.format("%02x%02x%02x%02x", rnd[0], rnd[1], rnd[2], rnd[3]);
        if (!DB_NAME.matcher(dbName).matches()) {
            throw new IllegalStateException("一次性库名不合法: " + dbName);
        }
        try (Connection c = admin(null); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE `" + dbName + "` DEFAULT CHARACTER SET utf8");
            dbCreated = true;
        }
        System.out.println("[mysql-it] created disposable database " + dbName);
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            for (String sql : installStatements()) {
                st.execute(sql);
            }
            atImport = SchemaFingerprint.of(c, dbName);
            seed(c);
            s0 = DatabaseSnapshot.take(c, TABLES);
        }
        drill = WriteDatabase.open(settings(adminUser, adminPassword, 8), DrillRegistry.registry());
    }

    @AfterAll
    static void tearDown() throws Exception {
        System.clearProperty("spring.datasource.password");
        System.clearProperty("maccms.write.password");
        if (drill != null) {
            drill.close();
        }
        if (dbCreated && DB_NAME.matcher(dbName).matches()) {
            try (Connection c = admin(null); Statement st = c.createStatement()) {
                st.execute("DROP DATABASE `" + dbName + "`");
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.schemata "
                        + "WHERE schema_name = '" + dbName + "'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("一次性库已删除").isZero();
                }
            }
            System.out.println("[mysql-it] dropped disposable database " + dbName + " (verified absent)");
        }
    }

    /** ① 默认不创建可写数据源；只读池照旧是只读连接，写语句被拒绝（驱动在发送前拒绝，数据不变）。 */
    @Test
    @Order(1)
    void writeDataSourceIsOffByDefaultAndReadPoolStaysReadOnly() throws Exception {
        try (ConfigurableApplicationContext app = start(false)) {
            assertThat(app.getBeansOfType(WriteDatabase.class)).isEmpty();
            assertReadPoolRejectsWrites(app);
        }
        assertThat(SnapshotDiff.between(s0, snapshot()).isEmpty()).isTrue();
    }

    /**
     * ② 打开可写数据源：Bean 存在，使用正式登记表（B07/B08 为空；B09 起 member 对 mac_user 的插入与更新，B10 另加 member 对
     * mac_ulog、mac_plog、mac_card、mac_cash、mac_order、mac_visit 的写入，19 文档第 6.1 节；B11 另加 content 对六张内容表计数列与
     * mac_vod_search、comment 对 mac_comment 与 mac_gbook 的写入，20 文档第 10.1 节；B12 另加 admin 对 mac_admin 的写入与 content 对
     * mac_vod_search 的删除，21 文档第 5 节），演练写入（drill 模块、mac_vod）在执行前被拒——
     * B11 登记的是 content 模块对 mac_vod 的更新，drill 模块仍未登记；只读池不受影响。
     */
    @Test
    @Order(2)
    void enabledWriteDataSourceEnforcesTheProductionRegistry() throws Exception {
        try (ConfigurableApplicationContext app = start(true)) {
            WriteDatabase db = app.getBean(WriteDatabase.class);
            assertThat(db.registry().permissions()).isEqualTo(WriteRegistry.registered().permissions())
                    .allMatch(p -> p.module().equals("member") && (p.batch().equals("B09") && p.table().equals("mac_user")
                            || p.batch().equals("B10"))
                            || p.batch().equals("B11") && (p.module().equals("content") || p.module().equals("comment"))
                            || p.batch().equals("B12") && (p.module().equals("admin") && p.table().equals("mac_admin")
                            || p.module().equals("content") && p.table().equals("mac_vod_search")));
            assertThatThrownBy(() -> db.mapper(DrillWriteMapper.class).hit(1)).isInstanceOf(WriteRejectedException.class)
                    .hasMessageContaining("未登记的写入").hasMessageContaining("mac_vod");
            assertReadPoolRejectsWrites(app);
        }
        assertThat(SnapshotDiff.between(s0, snapshot()).isEmpty()).as("被拒的写入没有执行").isTrue();
    }

    /** ③ 凭证错误：只报异常类型，不回显账号、密码，日志里也没有。 */
    @Test
    @Order(3)
    void wrongCredentialsFailWithoutEcho(CapturedOutput out) {
        String user = "maccms_it_nouser_mk7";
        String password = "it-pass-MARKER-7f3a";
        assertThatThrownBy(() -> WriteDatabase.open(settings(user, password, 2), DrillRegistry.registry()))
                .isInstanceOfSatisfying(IllegalStateException.class, e -> {
                    assertThat(e.getCause()).isNull();
                    assertThat(e.getMessage()).contains("可写数据源初始化失败", "MACCMS_WRITE_DB_")
                            .doesNotContain(user).doesNotContain(password).doesNotContain(dbName);
                });
        assertThat(out.getAll()).doesNotContain(user).doesNotContain(password);
    }

    /** ④ 并发条件写入：useAffectedRows=false、余额不扣成负数、计数不丢、条件插入不重复。 */
    @Test
    @Order(4)
    void concurrentConditionalWrites() throws Exception {
        DrillWriteMapper m = drill.mapper(DrillWriteMapper.class);
        // 写入同值：匹配 1 行（变更 0 行）
        assertThat(m.setPoints(2, 0)).isEqualTo(1);
        assertThat(m.setPoints(999_999, 0)).isZero();

        // 40 个线程各扣 10 分，用户 1 只有 200 分：恰好 20 个成功，最终 0 分
        ConcurrentWrites.Outcome<Integer> deduct = ConcurrentWrites.run(40, i -> m.deductPoints(1, 10));
        assertThat(deduct.failures()).isEmpty();
        assertThat(deduct.count(1)).isEqualTo(20);
        assertThat(deduct.count(0)).isEqualTo(20);
        assertThat(intValue("SELECT user_points FROM mac_user WHERE user_id = 1")).isZero();

        // 100 个线程各加一次点击：一次不丢
        ConcurrentWrites.Outcome<Integer> hits = ConcurrentWrites.run(100, i -> m.hit(1));
        assertThat(hits.failures()).isEmpty();
        assertThat(hits.count(1)).isEqualTo(100);
        assertThat(intValue("SELECT vod_hits FROM mac_vod WHERE vod_id = 1")).isEqualTo(100);

        // 条件插入：每轮 16 个线程插同一组 (user, mid, type, rid)，只有 1 行
        for (int round = 1; round <= 5; round++) {
            long rid = 1000 + round;
            ConcurrentWrites.Outcome<Integer> once = ConcurrentWrites.run(16, i -> m.insertUlogOnce(1, 1, 1, rid, 1_700_000_000L));
            assertThat(once.failures()).as("round " + round).isEmpty();
            assertThat(once.count(1)).as("round " + round).isEqualTo(1);
            assertThat(intValue("SELECT COUNT(*) FROM mac_ulog WHERE user_id = 1 AND ulog_rid = " + rid)).isEqualTo(1);
        }
    }

    /** ⑤-1 用卡：认领 → 加积分 → 记流水。16 个用户同时用同一张卡，只有一个成功，积分只加一次，流水只有一条。 */
    @Test
    @Order(5)
    void cardUseSequenceIsIdempotentUnderConcurrency() throws Exception {
        DrillWriteMapper m = drill.mapper(DrillWriteMapper.class);
        long useTime = 1_700_000_100L;
        ConcurrentWrites.Outcome<Long> o = ConcurrentWrites.run(16, i -> {
            long user = 3 + i;
            useCard(m, 1, user, useTime, "drill card 1").run();
            return user;
        });
        assertThat(o.values()).hasSize(1);
        long winner = o.values().get(0);
        assertThat(o.failures()).hasSize(15).allSatisfy(t -> assertThat(t)
                .isInstanceOfSatisfying(WriteConflictException.class, e -> assertThat(e.step()).isEqualTo("card")));
        assertThat(intValue("SELECT user_id FROM mac_card WHERE card_id = 1")).isEqualTo(winner);
        assertThat(intValue("SELECT SUM(user_points) FROM mac_user WHERE user_id BETWEEN 3 AND 18")).isEqualTo(CARD_POINTS);
        assertThat(intValue("SELECT user_points FROM mac_user WHERE user_id = " + winner)).isEqualTo(CARD_POINTS);
        assertThat(intValue("SELECT COUNT(*) FROM mac_plog WHERE plog_remarks = 'drill card 1'")).isEqualTo(1);
    }

    /** ⑤-2 最后一步被服务端拒绝（备注超长，严格模式报错，语句没有执行）：前两步按相反顺序补偿；期间没有其他写入，所以与之前一致。 */
    @Test
    @Order(6)
    void serverRejectedStepIsCompensated() throws Exception {
        DrillWriteMapper m = drill.mapper(DrillWriteMapper.class);
        DatabaseSnapshot before = snapshot();
        assertThatThrownBy(() -> useCard(m, 2, 19, 1_700_000_200L, "x".repeat(101)).run())
                .isInstanceOf(DataAccessException.class);
        assertThat(SnapshotDiff.between(before, snapshot()).isEmpty()).as("补偿后与之前完全一致").isTrue();
    }

    /** ⑤-3 补偿失败（认领后卡被外部改动，补偿条件不再命中）：抛 COMPENSATION_FAILED，日志只有序列名与步骤名。 */
    @Test
    @Order(7)
    void failedCompensationIsReportedForReconcile(CapturedOutput out) throws Exception {
        DrillWriteMapper m = drill.mapper(DrillWriteMapper.class);
        long useTime = 1_700_000_300L;
        WriteSequence s = WriteSequence.named("drill-card")
                .step(Role.CLAIM, "card", () -> m.claimCard(3, 20, useTime), 1, () -> m.releaseCard(3, 20, useTime, 0))
                .step(Role.TRANSFER, "points", () -> {
                    adminUpdate("UPDATE mac_card SET card_use_time = card_use_time + 1 WHERE card_id = 3");
                    return m.addPoints(999_999, CARD_POINTS);
                }, 1, () -> m.deductPoints(999_999, CARD_POINTS))
                .build();
        assertThatThrownBy(s::run).isInstanceOfSatisfying(CompensationFailedException.class, e -> {
            assertThat(e.reason()).isEqualTo(CompensationFailedException.Reason.COMPENSATION_FAILED);
            assertThat(e.unresolvedSteps()).containsExactly("card");
        });
        assertThat(intValue("SELECT card_use_status FROM mac_card WHERE card_id = 3")).as("待人工对账").isEqualTo(1);
        assertThat(out.getAll()).contains("写入需人工对账", "sequence=drill-card", "unresolved=[card]")
                .doesNotContain("drill_u20");
    }

    /**
     * ⑤-4 匹配行数超过预期（步骤条件写错，预期 1 行实际 2 行）：这一步已经生效，不补偿任何步骤、认领保留，抛 UNEXPECTED_ROWS
     * 而不是可重试的普通冲突；重试在认领步骤被挡住。
     */
    @Test
    @Order(8)
    void overMatchedStepKeepsEverythingForReconcile(CapturedOutput out) throws Exception {
        DrillWriteMapper m = drill.mapper(DrillWriteMapper.class);
        long useTime = 1_700_000_400L;
        int p19 = points(19);
        int p20 = points(20);
        WriteSequence s = WriteSequence.named("drill-card")
                .step(Role.CLAIM, "card", () -> m.claimCard(4, 19, useTime), 1, () -> m.releaseCard(4, 19, useTime, 0))
                .step(Role.TRANSFER, "points", () -> m.addPointsInRange(19, 20, 2), 1, () -> m.deductPoints(19, 2))
                .step(Role.RECORD, "plog", () -> m.insertPlog(new PlogInsert(19, 1, 2, useTime, "drill card 4")), 1, null)
                .build();
        assertThatThrownBy(s::run).isNotInstanceOf(WriteConflictException.class)
                .isInstanceOfSatisfying(CompensationFailedException.class, e -> {
                    assertThat(e.reason()).isEqualTo(CompensationFailedException.Reason.UNEXPECTED_ROWS);
                    assertThat(e.failedStep()).isEqualTo("points");
                    assertThat(e.unresolvedSteps()).containsExactly("card", "points");
                });
        assertThat(points(19)).as("超额生效的变更原样留给对账").isEqualTo(p19 + 2);
        assertThat(points(20)).isEqualTo(p20 + 2);
        assertThat(intValue("SELECT card_use_status FROM mac_card WHERE card_id = 4")).as("认领保留").isEqualTo(1);
        assertThat(intValue("SELECT COUNT(*) FROM mac_plog WHERE plog_remarks = 'drill card 4'")).isZero();
        assertThat(out.getAll()).contains("写入需人工对账", "sequence=drill-card", "step=points", "reason=UNEXPECTED_ROWS",
                "unresolved=[card, points]").doesNotContain("drill_u19");

        assertThatThrownBy(s::run).isInstanceOfSatisfying(WriteConflictException.class,
                e -> assertThat(e.step()).isEqualTo("card"));
        assertThat(points(19)).isEqualTo(p19 + 2);
        assertThat(points(20)).isEqualTo(p20 + 2);
    }

    /**
     * ⑤-5 补偿失败后认领不释放（故障注入 + 并发）：16 个请求同时用卡 5；胜出者加 50 分后，同一用户被并发花掉 45 分，接着记流水被
     * 服务端拒绝。退 50 分的补偿不命中，补偿停止、卡保持已认领。其余请求与之后的重试全部在认领步骤失败，积分只加一次。
     */
    @Test
    @Order(9)
    void failedCompensationKeepsTheClaimUnderConcurrency(CapturedOutput out) throws Exception {
        DrillWriteMapper m = drill.mapper(DrillWriteMapper.class);
        long useTime = 1_700_000_500L;
        String tooLong = "x".repeat(101);
        int before = points(20);
        assertThat(before).as("前提：花掉 45 分后余额不够退回 50 分").isLessThan(CARD_POINTS - 5);
        WriteSequence s = WriteSequence.named("drill-card")
                .step(Role.CLAIM, "card", () -> m.claimCard(5, 20, useTime), 1, () -> m.releaseCard(5, 20, useTime, 0))
                .step(Role.TRANSFER, "points", () -> m.addPoints(20, CARD_POINTS), 1, () -> m.deductPoints(20, CARD_POINTS))
                .step(Role.RECORD, "plog", () -> {
                    assertThat(m.deductPoints(20, 45)).as("注入：并发消费").isEqualTo(1);
                    return m.insertPlog(new PlogInsert(20, 1, CARD_POINTS, useTime, tooLong));
                }, 1, null)
                .build();
        ConcurrentWrites.Outcome<Integer> o = ConcurrentWrites.run(16, i -> {
            s.run();
            return i;
        });
        assertThat(o.values()).isEmpty();
        assertThat(o.failures()).filteredOn(CompensationFailedException.class::isInstance).singleElement()
                .isInstanceOfSatisfying(CompensationFailedException.class, e -> {
                    assertThat(e.reason()).isEqualTo(CompensationFailedException.Reason.COMPENSATION_FAILED);
                    assertThat(e.failedStep()).isEqualTo("plog");
                    assertThat(e.unresolvedSteps()).containsExactly("card", "points");
                    assertThat(e.getCause()).isInstanceOf(DataAccessException.class);
                });
        assertThat(o.failures()).filteredOn(WriteConflictException.class::isInstance).hasSize(15)
                .allSatisfy(t -> assertThat(((WriteConflictException) t).step()).isEqualTo("card"));
        assertThat(points(20)).as("只加一次 50，被花掉 45").isEqualTo(before + 5);
        assertThat(intValue("SELECT card_use_status FROM mac_card WHERE card_id = 5")).as("认领保留").isEqualTo(1);
        assertThat(out.getAll()).contains("写入需人工对账", "sequence=drill-card", "step=plog", "reason=COMPENSATION_FAILED",
                "unresolved=[card, points]").doesNotContain("drill_u20").doesNotContain(tooLong);

        // 未对账前再并发重试 8 次：全部在认领步骤失败，积分不再增加
        ConcurrentWrites.Outcome<Integer> retry = ConcurrentWrites.run(8, i -> {
            s.run();
            return i;
        });
        assertThat(retry.failures()).hasSize(8).allSatisfy(t -> assertThat(t)
                .isInstanceOfSatisfying(WriteConflictException.class, e -> assertThat(e.step()).isEqualTo("card")));
        assertThat(points(20)).isEqualTo(before + 5);
    }

    /**
     * ⑤-6 对账后恢复（12 文档第 4.4 节"向前补齐"）：卡已用、积分已加且被合法消费，只缺流水，由对账补写一条流水。
     * 之后数据自洽，同一张卡仍不能再用。
     */
    @Test
    @Order(10)
    void reconcileCompletesForwardAndTheCardStaysUsed() throws Exception {
        DrillWriteMapper m = drill.mapper(DrillWriteMapper.class);
        int before = points(20);
        assertThat(intValue("SELECT COUNT(*) FROM mac_plog WHERE user_id = 20")).isZero();
        assertThat(m.insertPlog(new PlogInsert(20, 1, CARD_POINTS, 1_700_000_500L, "drill card 5 reconciled"))).isEqualTo(1);
        assertThatThrownBy(() -> useCard(m, 5, 20, 1_700_000_600L, "drill card 5 again").run())
                .isInstanceOfSatisfying(WriteConflictException.class, e -> assertThat(e.step()).isEqualTo("card"));
        assertThat(points(20)).isEqualTo(before);
        assertThat(intValue("SELECT COUNT(*) FROM mac_plog WHERE user_id = 20")).isEqualTo(1);
        assertThat(intValue("SELECT user_id FROM mac_card WHERE card_id = 5")).isEqualTo(20);
    }

    /** ⑥ 未登记的表、操作、模块：执行前被拒，数据不变。 */
    @Test
    @Order(11)
    void unregisteredWritesAreRejectedBeforeExecution() throws Exception {
        DrillWriteMapper m = drill.mapper(DrillWriteMapper.class);
        DatabaseSnapshot before = snapshot();
        assertThatThrownBy(() -> m.insertGbook("drill", "x", 1L)).isInstanceOf(WriteRejectedException.class)
                .hasMessageContaining("mac_gbook").hasMessageContaining("INSERT");
        assertThatThrownBy(() -> m.deleteUser(1)).isInstanceOf(WriteRejectedException.class)
                .hasMessageContaining("mac_user").hasMessageContaining("DELETE");
        assertThatThrownBy(() -> drill.mapper(OtherWriteMapper.class).addPoints(1, 1))
                .isInstanceOf(WriteRejectedException.class).hasMessageContaining("drillother");
        assertThat(SnapshotDiff.between(before, snapshot()).isEmpty()).isTrue();
        assertThat(WriteRegistry.registered().allows("drill", "mac_user", com.maccms.backend.common.write.WriteOperation.UPDATE))
                .isFalse();
    }

    /** ⑦ MyISAM 没有事务：关闭自动提交后回滚，修改仍然生效。所以一致性靠 WriteSequence，而不是事务。 */
    @Test
    @Order(12)
    void myIsamIgnoresRollback() throws Exception {
        int hits = intValue("SELECT vod_hits FROM mac_vod WHERE vod_id = 2");
        try (Connection c = admin(dbName)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE mac_vod SET vod_hits = vod_hits + 1000 WHERE vod_id = 2");
            }
            c.rollback();
            SQLWarning w = c.getWarnings();
            List<Integer> codes = new ArrayList<>();
            for (; w != null; w = w.getNextWarning()) {
                codes.add(w.getErrorCode());
            }
            System.out.println("[mysql-it] MyISAM rollback warnings: " + codes);
            c.setAutoCommit(true);
        }
        assertThat(intValue("SELECT vod_hits FROM mac_vod WHERE vod_id = 2")).isEqualTo(hits + 1000);
    }

    /** ⑧ 快照 → 写入（以上各步）→ 只用 DML 恢复 → diff 为空；表结构指纹与导入时一致。 */
    @Test
    @Order(13)
    void dmlRestoreReturnsToTheSnapshotWithoutStructureChanges() throws Exception {
        long autoIncrementBefore = autoIncrement("mac_plog");
        DatabaseSnapshot s1 = snapshot();
        SnapshotDiff written = SnapshotDiff.between(s0, s1);
        System.out.println("[mysql-it] writes since S0:\n" + written.summary());
        assertThat(written.isEmpty()).isFalse();

        List<DmlRestore.Statement> plan = DmlRestore.plan(s0, s1);
        assertThat(plan).isNotEmpty().allSatisfy(st -> assertThat(st.sql()).doesNotContainIgnoringCase("truncate")
                .doesNotContainIgnoringCase("alter").doesNotContainIgnoringCase("drop").doesNotContainIgnoringCase("create"));
        int applied;
        try (Connection c = admin(dbName)) {
            applied = DmlRestore.apply(c, plan);
        }
        System.out.println("[mysql-it] DML restore statements applied: " + applied);
        assertThat(applied).isEqualTo(plan.size());

        SnapshotDiff after = SnapshotDiff.between(s0, snapshot());
        assertThat(after.isEmpty()).as(after.summary()).isTrue();
        try (Connection c = admin(dbName)) {
            assertThat(SchemaFingerprint.diff(atImport, SchemaFingerprint.of(c, dbName)))
                    .as("演练前后表结构必须与导入 install.sql 时一致").isEmpty();
        }
        // 已知边界：AUTO_INCREMENT 不回退（回退需要结构语句）
        assertThat(autoIncrement("mac_plog")).isEqualTo(autoIncrementBefore);
        // 恢复工具拒绝非一次性库（未选库的连接 DATABASE() 为 NULL）
        try (Connection c = admin(null)) {
            assertThatThrownBy(() -> DmlRestore.apply(c, plan)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("maccms_it_");
        }
    }

    // ---- 辅助 ----

    private static WriteSequence useCard(DrillWriteMapper m, long cardId, long userId, long useTime, String remarks) {
        AtomicLong plogId = new AtomicLong();
        return WriteSequence.named("drill-card")
                .step(Role.CLAIM, "card", () -> m.claimCard(cardId, userId, useTime), 1,
                        () -> m.releaseCard(cardId, userId, useTime, 0))
                .step(Role.TRANSFER, "points", () -> m.addPoints(userId, CARD_POINTS), 1,
                        () -> m.deductPoints(userId, CARD_POINTS))
                .step(Role.RECORD, "plog", () -> {
                    PlogInsert row = new PlogInsert(userId, 1, CARD_POINTS, useTime, remarks);
                    int n = m.insertPlog(row);
                    plogId.set(row.plogId == null ? 0 : row.plogId);
                    return n;
                }, 1, () -> m.deletePlog(plogId.get()))
                .build();
    }

    private static void seed(Connection c) throws SQLException {
        try (PreparedStatement u = c.prepareStatement("INSERT INTO mac_user (user_id, group_id, user_name, user_status, "
                + "user_points, user_reg_time) VALUES (?, 2, ?, 1, ?, 1700000000)")) {
            for (int i = 1; i <= USERS; i++) {
                u.setInt(1, i);
                u.setString(2, "drill_u" + i);
                u.setInt(3, i == 1 ? 200 : 0);
                u.executeUpdate();
            }
        }
        try (PreparedStatement k = c.prepareStatement("INSERT INTO mac_card (card_id, card_no, card_pwd, card_points, "
                + "card_add_time) VALUES (?, ?, ?, ?, 1700000000)")) {
            for (int i = 1; i <= 5; i++) {
                k.setInt(1, i);
                k.setString(2, "DRILLCARD00000" + i);
                k.setString(3, "pw00000" + i);
                k.setInt(4, CARD_POINTS);
                k.executeUpdate();
            }
        }
        try (PreparedStatement v = c.prepareStatement("INSERT INTO mac_vod (vod_id, type_id, vod_name, vod_status, "
                + "vod_content, vod_play_url, vod_down_url, vod_plot_name, vod_plot_detail) VALUES (?, 1, ?, 1, '', '', '', '', '')")) {
            for (int i = 1; i <= 3; i++) {
                v.setInt(1, i);
                v.setString(2, "drill vod " + i);
                v.executeUpdate();
            }
        }
        try (PreparedStatement p = c.prepareStatement("INSERT INTO mac_plog (plog_id, user_id, plog_type, plog_points, "
                + "plog_time, plog_remarks) VALUES (1, 1, 1, 5, 1700000000, 'seed')")) {
            p.executeUpdate();
        }
    }

    private static void assertReadPoolRejectsWrites(ConfigurableApplicationContext app) throws SQLException {
        DataSource ds = app.getBean(DataSource.class);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            assertThat(c.isReadOnly()).isTrue();
            // Connector/J 对只读连接上的写语句直接拒绝（不发送到服务端）；会话本身也是 READ ONLY（服务端会报 1792）
            assertThatThrownBy(() -> st.executeUpdate("UPDATE mac_vod SET vod_hits = vod_hits + 1 WHERE vod_id = 3"))
                    .isInstanceOf(SQLException.class);
            try (ResultSet rs = st.executeQuery("SELECT @@session.tx_read_only")) {
                rs.next();
                assertThat(rs.getInt(1)).as("会话为只读事务").isEqualTo(1);
            }
        }
    }

    private static ConfigurableApplicationContext start(boolean writeEnabled) {
        String url = jdbcUrlFor(dbName);
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + adminUser,
                "--maccms.write.enabled=" + writeEnabled));
        if ("root".equalsIgnoreCase(adminUser)) {
            args.add("--maccms.datasource.allow-root=true");
        }
        if (writeEnabled) {
            args.add("--maccms.write.url=" + url);
            args.add("--maccms.write.username=" + adminUser);
            args.add("--maccms.write.allow-root=true");
            System.setProperty("maccms.write.password", adminPassword);
        }
        System.setProperty("spring.datasource.password", adminPassword);
        try {
            return new SpringApplicationBuilder(MaccmsBackendApplication.class).web(WebApplicationType.NONE)
                    .run(args.toArray(String[]::new));
        } finally {
            System.clearProperty("spring.datasource.password");
            System.clearProperty("maccms.write.password");
        }
    }

    private static WriteDataSourceSettings settings(String user, String password, int pool) {
        String url = jdbcUrlFor(dbName);
        return WriteDataSourceSettings.from(new WriteDataSourceProperties(true, url, user, password, true, pool), url);
    }

    private static DatabaseSnapshot snapshot() throws SQLException {
        try (Connection c = admin(dbName)) {
            return DatabaseSnapshot.take(c, TABLES);
        }
    }

    private static int intValue(String sql) throws SQLException {
        try (Connection c = admin(dbName); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int points(long userId) throws SQLException {
        return intValue("SELECT user_points FROM mac_user WHERE user_id = " + userId);
    }

    private static void adminUpdate(String sql) {
        try (Connection c = admin(dbName); Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long autoIncrement(String table) throws SQLException {
        try (Connection c = admin(dbName); PreparedStatement ps = c.prepareStatement(
                "SELECT AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
            ps.setString(1, dbName);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String requiredEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            fail("缺少环境变量 " + name + "：MySQL 集成测试需要外部提供管理账号，见 backend/README.md");
        }
        return v;
    }

    private static String jdbcUrlFor(String db) {
        int q = adminUrl.indexOf('?');
        String params = q >= 0 ? adminUrl.substring(q) : "";
        String path = q >= 0 ? adminUrl.substring(0, q) : adminUrl;
        if (!path.endsWith("/")) {
            path = path + "/";
        }
        return path + (db == null ? "" : db) + (params.isEmpty() ? "?characterEncoding=UTF-8" : params);
    }

    private static Connection admin(String db) throws SQLException {
        return DriverManager.getConnection(jdbcUrlFor(db), adminUser, adminPassword);
    }

    private static List<String> installStatements() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(INSTALL_SQL, StandardCharsets.UTF_8).split("\\r?\\n")) {
            if (!line.startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        List<String> out = new ArrayList<>();
        for (String stmt : sb.toString().split(";\\s*\\n")) {
            if (!stmt.isBlank()) {
                out.add(stmt.trim());
            }
        }
        return out;
    }
}
