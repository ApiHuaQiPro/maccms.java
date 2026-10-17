package com.maccms.backend.common.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 写 SQL 规范的语法部分（12 文档第 4.1 节）。 */
class WriteStatementTest {

    @Test
    void acceptsSingleTableWrites() {
        assertThat(WriteStatement.parse("INSERT INTO mac_plog (user_id, plog_points) VALUES (?, ?)"))
                .isEqualTo(new WriteStatement(WriteOperation.INSERT, "mac_plog"));
        assertThat(WriteStatement.parse("insert into `MAC_PLOG`(user_id) values (?)").table()).isEqualTo("mac_plog");
        assertThat(WriteStatement.parse("""
                INSERT INTO mac_ulog (user_id, ulog_rid) SELECT ?, ? FROM DUAL
                WHERE NOT EXISTS (SELECT 1 FROM mac_ulog WHERE user_id = ? AND ulog_rid = ?)""").operation())
                .isEqualTo(WriteOperation.INSERT);
        assertThat(WriteStatement.parse("UPDATE mac_user SET user_points = user_points - ? WHERE user_id = ? "
                + "AND user_points >= ?")).isEqualTo(new WriteStatement(WriteOperation.UPDATE, "mac_user"));
        assertThat(WriteStatement.parse("UPDATE `mac_user` u SET u.user_points = 0 WHERE u.user_id = ?").table())
                .isEqualTo("mac_user");
        assertThat(WriteStatement.parse("UPDATE LOW_PRIORITY mac_vod AS v SET v.vod_hits = v.vod_hits + 1 WHERE v.vod_id = ?")
                .table()).isEqualTo("mac_vod");
        assertThat(WriteStatement.parse("DELETE FROM mac_plog WHERE plog_id = ?"))
                .isEqualTo(new WriteStatement(WriteOperation.DELETE, "mac_plog"));
        assertThat(WriteStatement.parse("DELETE FROM mac_plog\n  WHERE plog_id IN (?, ?) LIMIT 2").table())
                .isEqualTo("mac_plog");
        // 字符串字面量里的分号、注释符、WHERE 不参与判断
        assertThat(WriteStatement.parse("UPDATE mac_plog SET plog_remarks = 'a;b -- c # d /* e' WHERE plog_id = ?")
                .table()).isEqualTo("mac_plog");
        assertThat(WriteStatement.parse("UPDATE mac_plog SET plog_remarks = 'it''s \\' ok' WHERE plog_id = 1").table())
                .isEqualTo("mac_plog");
    }

    @Test
    void rejectsEverythingElse() {
        for (String bad : List.of(
                "", "   ",
                "SELECT user_id FROM mac_user",
                "REPLACE INTO mac_user (user_id) VALUES (1)",
                "INSERT IGNORE INTO mac_plog (user_id) VALUES (1)",
                "INSERT INTO mac_plog (plog_id) VALUES (1) ON DUPLICATE KEY UPDATE plog_points = 1",
                "INSERT INTO maccms.mac_plog (user_id) VALUES (1)",
                "UPDATE mac_user SET user_points = 0",
                "UPDATE mac_user SET user_points = (SELECT 1 FROM mac_card WHERE card_id = 1)",
                "UPDATE mac_user SET user_points = 0 WHERE user_id = 1; DELETE FROM mac_user WHERE user_id = 2",
                "UPDATE mac_user SET user_points = 0 WHERE user_id = 1;",
                "UPDATE mac_user, mac_card SET user_points = 0 WHERE mac_user.user_id = mac_card.user_id",
                "UPDATE mac_user u JOIN mac_card c ON c.user_id = u.user_id SET u.user_points = 0 WHERE c.card_id = 1",
                "UPDATE IGNORE mac_user SET user_points = 0 WHERE user_id = 1",
                "UPDATE maccms.mac_user SET user_points = 0 WHERE user_id = 1",
                "UPDATE mac_user SET user_points = 0 WHERE user_id = 1 /* x */",
                "UPDATE mac_user SET user_points = 0 WHERE user_id = 1 -- x",
                "UPDATE mac_user SET user_points = 0 WHERE user_id = 1 # x",
                "UPDATE /*+ BKA(t) */ mac_user SET user_points = 0 WHERE user_id = 1",
                "UPDATE mac_user SET user_name = 'x WHERE y'",
                "UPDATE mac_user SET user_name = 'unterminated WHERE user_id = 1",
                "DELETE FROM mac_user",
                "DELETE FROM mac_user LIMIT 1",
                "DELETE mac_user FROM mac_user JOIN mac_card ON 1 = 1 WHERE card_id = 1",
                "DELETE FROM mac_user USING mac_user JOIN mac_card WHERE card_id = 1",
                "DELETE FROM mac_user, mac_card WHERE 1 = 1",
                "DELETE IGNORE FROM mac_user WHERE user_id = 1",
                "TRUNCATE mac_user",
                "LOCK TABLES mac_user WRITE",
                "CALL p()")) {
            assertThatThrownBy(() -> WriteStatement.parse(bad)).as(bad).isInstanceOf(WriteRejectedException.class)
                    .hasMessageStartingWith("写语句不符合规范：");
        }
        assertThatThrownBy(() -> WriteStatement.parse(null)).isInstanceOf(WriteRejectedException.class);
    }

    @Test
    void rejectionMessagesDoNotEchoTheStatement() {
        assertThatThrownBy(() -> WriteStatement.parse("UPDATE mac_user SET user_pwd = 'secret-MARKER'"))
                .hasMessageNotContaining("secret-MARKER").hasMessageNotContaining("user_pwd");
    }
}
