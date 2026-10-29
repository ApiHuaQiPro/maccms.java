package com.maccms.backend.install.web.view;

import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.install.application.InstallEnvironment;

/** 第二步：运行环境检测（旧 application/install/view/index/step2.html）。 */
public record Step2Page(LegacyLang lang, String staticPath, InstallEnvironment.Result env) {
}
