package com.maccms.backend.content.infrastructure;

/** mac_vod 的三个积分列（ContentLookupMapper.xml）。 */
public record VodPointsRow(Long id, Integer whole, Integer play, Integer down) {
}
