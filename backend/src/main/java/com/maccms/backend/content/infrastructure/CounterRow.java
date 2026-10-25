package com.maccms.backend.content.infrastructure;

import java.math.BigDecimal;

/**
 * 内容表一行的计数列（ContentCounterMapper.xml；B11）。{@code score} 是 {@code decimal(3,1)}，按库里的值原样（例如 {@code 7.0}）；
 * 非网址表的来路计数为 0。
 */
public record CounterRow(Long hits, Long hitsDay, Long hitsWeek, Long hitsMonth, Long up, Long down, BigDecimal score,
                         Long scoreAll, Long scoreNum, Long referer, Long refererDay, Long refererWeek,
                         Long refererMonth) {
}
