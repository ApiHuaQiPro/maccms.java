package com.maccms.backend.content.infrastructure;

/** mac_art 的两个积分列（ContentLookupMapper.xml）：整篇 art_points、按页 art_points_detail。 */
public record ArtPointsRow(Long id, Integer whole, Integer detail) {
}
