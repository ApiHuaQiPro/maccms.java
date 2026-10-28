package com.maccms.backend.admin.web.view.collect;

import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.content.BrowserText;
import com.maccms.backend.collect.application.CjRunService;
import com.maccms.backend.common.lang.LegacyLang;

/**
 * 发布方案（旧 admin/view/cj/program.html）：每个数据库列一行，选择对应的节点字段与处理函数。
 *
 * @param id 参数 {@code id}（旧 {@code {$param.id|mac_filter_xss}}）
 */
public record CjProgramView(AdminHead head, LegacyLang lang, String id, CjRunService.Program program) implements BrowserText {

    /** 旧 {@code $program_config['map'][$vo.Field] eq $key}。 */
    public boolean mapped(String column, String key) {
        String v = program.map().get(column);
        return v != null && v.equals(key);
    }

    /** 旧 {@code $program_config['funcs'][$vo.Field] eq 'trim'}。 */
    public boolean trimmed(String column) {
        return "trim".equals(program.funcs().get(column));
    }
}
