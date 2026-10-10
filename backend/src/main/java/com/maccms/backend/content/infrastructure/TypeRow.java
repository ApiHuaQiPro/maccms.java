package com.maccms.backend.content.infrastructure;

/** mac_type 中视频分类(type_mid=1)的公开列，见 TypeMapper.xml。不含模板、跳转、状态等内部列。 */
public record TypeRow(
        Integer id,
        String name,
        String enName,
        Integer sort,
        Integer parentId,
        String title,
        String keywords,
        String description,
        String logo,
        String pic,
        String extendJson) {
}
