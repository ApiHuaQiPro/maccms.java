package com.maccms.backend.content.infrastructure;

/** mac_link 的全部列（LinkMapper.xml），友链没有非公开列。 */
public record LinkRow(Integer id, Integer type, String name, Integer sort, String url, String logo, Long addedAt,
                      Long updatedAt) {
}
