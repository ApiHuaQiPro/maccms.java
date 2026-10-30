package com.maccms.backend.admin.web.view.collect;

import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.collect.application.CjRunService;
import com.maccms.backend.common.lang.LegacyLang;
import java.util.List;

/**
 * 规则表单"测试"打开的列表页地址（旧 admin/view/cj/show_url.html）。新版在每个地址后标出对应的本地文件与是否存在，未配置本地 HTML 目录时
 * 在页首提示（28 文档第 8 节）。
 */
public record CjShowUrlView(AdminHead head, LegacyLang lang, List<CjRunService.UrlEntry> urls, boolean htmlConfigured) {
}
