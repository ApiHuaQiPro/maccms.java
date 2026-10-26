package com.maccms.backend.member.infrastructure;

/** 生成的一张充值卡（卡号、卡密）；面值、积分与添加时间对同一批卡相同。 */
public record CardInsert(String no, String pwd) {
}
