package com.maccms.backend.member.application.view;

/**
 * 会员组（{@code mac_group} 一行，install.sql:361）。旧版 {@code Group::getCache} 取全部组，不看 {@code group_status}，这里相同。
 *
 * @param typeIds     {@code group_type} 原文（如 {@code ,1,6,7,}）
 * @param popedomJson {@code group_popedom} 原文（JSON），由使用方按需解析
 */
public record MemberGroup(int id, String name, int status, String typeIds, String popedomJson) {
}
