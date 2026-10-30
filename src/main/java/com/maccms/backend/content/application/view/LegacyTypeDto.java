package com.maccms.backend.content.application.view;

/**
 * 一个分类的整行（B06）：旧 {@code Type::getCache('type_list')}（全部分类，按 type_id 升序，common/model/Type.php:350-360）的一项。
 *
 * @param row      mac_type 全部列（{@code type_extend} 为原始文本）
 * @param id       type_id
 * @param parentId type_pid
 * @param mid      type_mid
 * @param visible  是否为游客可见分类（与 content API 相同的 {@code CategoryCatalog} 规则；模型 1/2/8/11 以外的分类一律不可见）
 */
public record LegacyTypeDto(LegacyRowDto row, int id, int parentId, int mid, boolean visible) {
}
