package com.maccms.backend.collect.application.view;

import com.maccms.backend.content.application.view.AdminRow;
import java.util.List;
import java.util.Map;

/**
 * 自定义规则节点的表单数据（旧 Cj::info 的 GET）：节点行（新增时为空行）与解码后的自定义字段（每项 {@code name}、{@code en_name}、
 * {@code rule}、{@code html_rule}，缺失为空串）。
 */
public record CjNodeForm(AdminRow info, List<Map<String, String>> customize) {

    public CjNodeForm {
        customize = List.copyOf(customize);
    }
}
