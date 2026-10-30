package com.maccms.backend.content.infrastructure;

/** mac_group 中与内容可见性有关的两列：group_type（",1,6,"）与 group_popedom（JSON）。 */
public record GroupPermissionRow(Integer id, String typeIds, String popedomJson) {
}
