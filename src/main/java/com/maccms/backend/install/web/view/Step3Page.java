package com.maccms.backend.install.web.view;

import com.maccms.backend.common.lang.LegacyLang;

/**
 * 第三步：数据库配置与管理员（旧 application/install/view/index/step3.html）。
 *
 * @param installDir 安装目录（旧 Index.php:99-101 由 {@code SCRIPT_NAME} 取目录部分）
 * @param token      表单令牌（新版，两个表单都带，31 文档第 7 节）
 */
public record Step3Page(LegacyLang lang, String staticPath, String assetsPath, String installDir, String token) {
}
