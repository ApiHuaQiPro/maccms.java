package com.maccms.backend.content.application.view;

/**
 * 角色所属视频（role_rid）的摘要。视频不存在、未审核或所属分类不可见时为 {@link #NONE}（id=0、其余为空值），不暴露隐藏视频的信息。
 */
public record RoleVideoDto(long id, String name, String enName, int categoryId) {

    public static final RoleVideoDto NONE = new RoleVideoDto(0, "", "", 0);
}
