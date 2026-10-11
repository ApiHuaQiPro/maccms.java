package com.maccms.backend.content.application.view;

/** 视频所属分类的摘要。 */
public record CategoryRefDto(int id, String name, String enName, int parentId) {
}
