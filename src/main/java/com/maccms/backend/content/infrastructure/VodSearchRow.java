package com.maccms.backend.content.infrastructure;

/** mac_vod_search 一行中搜索要用的两列（VodSearchMapper.xml；B11）。 */
public record VodSearchRow(String resultIds, Long hitCount) {
}
