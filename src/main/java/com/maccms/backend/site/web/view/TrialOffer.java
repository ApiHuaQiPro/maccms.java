package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.Optional;

/**
 * 试看页给已登录会员的购买入口（B10，旧 5001/5002，19 文档第 5.3 节）。
 *
 * @param message  旧版提示原文（5001：可以支付；5002：积分不足）
 * @param form     购买表单（积分足够时）
 * @param recharge 充值页（积分不足时）
 */
public record TrialOffer(String message, Optional<ContentForm> form, Optional<SafeUrl> recharge) {
}
