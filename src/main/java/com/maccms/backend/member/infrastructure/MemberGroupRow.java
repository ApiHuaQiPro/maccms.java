package com.maccms.backend.member.infrastructure;

/** {@code mac_group} 一行中登录态用到的列。 */
public record MemberGroupRow(int id, String name, int status, String typeIds, String popedomJson) {
}
