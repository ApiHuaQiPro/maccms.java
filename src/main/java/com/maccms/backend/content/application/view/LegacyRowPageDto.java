package com.maccms.backend.content.application.view;

import java.util.List;

/**
 * 整行读取的一页（B06）。
 *
 * @param total 满足条件的总行数
 * @param rows  本页各行
 */
public record LegacyRowPageDto(long total, List<LegacyRowDto> rows) {

    public LegacyRowPageDto {
        rows = List.copyOf(rows);
    }
}
