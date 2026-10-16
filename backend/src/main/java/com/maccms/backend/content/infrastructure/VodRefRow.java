package com.maccms.backend.content.infrastructure;

/** 已审核视频的最小摘要（VodMapper.selectPublishedRefs），用于角色所属视频。 */
public record VodRefRow(Long id, Integer categoryId, String name, String enName) {
}
