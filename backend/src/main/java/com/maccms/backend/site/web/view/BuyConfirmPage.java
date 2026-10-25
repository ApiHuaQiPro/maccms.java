package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.Optional;

/**
 * 收费数据的购买确认页（B10，旧 {@code vod/confirm} 与 3003/4003/4001 的提示，19 文档第 5.2 节）：403、no-store、严格策略。
 * 已登录会员看到"确认支付"表单，游客只看到去登录页的链接。
 *
 * @param chrome  页面外壳
 * @param heading 标题
 * @param message 旧版提示原文（如"观看此数据，需要支付【5】积分，确认支付吗？"）
 * @param form    购买表单（已登录且可以购买时）
 * @param login   去登录页（游客时）
 * @param back    返回链接（视频或文章详情页）
 */
public record BuyConfirmPage(PageChrome chrome, String heading, String message, Optional<ContentForm> form,
                             Optional<SafeUrl> login, Optional<SafeUrl> back) {
}
