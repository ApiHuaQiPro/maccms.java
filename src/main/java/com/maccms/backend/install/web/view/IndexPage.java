package com.maccms.backend.install.web.view;

import com.maccms.backend.common.lang.LegacyLang;
import java.util.List;

/**
 * 首页：语言包选择与用户协议（旧 application/install/view/index/index.html）。
 *
 * @param langs    可选语言包（旧版 {@code glob('./application/lang/*.php')}）
 * @param selected 本次选中的语言包（旧 {@code $lang}）
 * @param indexUrl 选择语言后跳转的地址（旧 {@code {:url('index')}}）
 */
public record IndexPage(LegacyLang lang, String staticPath, String assetsPath, List<String> langs, String selected,
                        String indexUrl) {
}
