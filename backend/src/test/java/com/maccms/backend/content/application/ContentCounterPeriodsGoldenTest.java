package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.content.infrastructure.HitPeriods;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 点击/来路计数的日、周、月区间与旧 {@code Ajax::hits}（application/index/controller/Ajax.php:178-199）的判定相同（B11）：黄金向量
 * （{@code interaction_text_golden.php} 的 {@code periods}）给出一组 PRC 时刻（含周日、周六、月末、年末、闰日与 1990/1991 年的夏令时日期）下，
 * 不同"上次计数时间"的同月、同周、同日判定；新版的 SQL 用 {@link ContentCounterService#periods} 的区间做同样的判定。
 */
class ContentCounterPeriodsGoldenTest {

    @ParameterizedTest
    @ValueSource(strings = {"interaction/text-php80-golden.json", "interaction/text-php73-golden.json"})
    void periodsMatchLegacyGetdateChecks(String resource) {
        JsonNode golden = load(resource);
        int n = 0;
        for (JsonNode m : golden.get("periods")) {
            long now = m.get("now").asLong();
            HitPeriods p = ContentCounterService.periods(now);
            assertThat(p.weekStart()).as(m.get("moment").asString() + " weekStart").isEqualTo(m.get("weekStart").asLong());
            assertThat(p.weekEnd()).as(m.get("moment").asString() + " weekEnd").isEqualTo(m.get("weekEnd").asLong());
            assertThat(p.now()).isEqualTo(now);
            for (JsonNode c : m.get("checks")) {
                long old = now + c.get("offset").asLong();
                String what = m.get("moment").asString() + " offset " + c.get("offset").asLong();
                // 与 ContentCounterWriteMapper.xml 中的 IF(...) 条件逐字对应
                assertThat(old >= p.monthStart() && old < p.monthEnd()).as(what + " month")
                        .isEqualTo(c.get("month").asBoolean());
                assertThat(old >= p.weekStart() && old <= p.weekEnd()).as(what + " week")
                        .isEqualTo(c.get("week").asBoolean());
                assertThat(old >= p.dayStart() && old < p.dayEnd()).as(what + " day")
                        .isEqualTo(c.get("day").asBoolean());
                n++;
            }
        }
        assertThat(n).isEqualTo(130);
    }

    private static JsonNode load(String resource) {
        try (InputStream in = ContentCounterPeriodsGoldenTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing " + resource);
            }
            return JsonMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
