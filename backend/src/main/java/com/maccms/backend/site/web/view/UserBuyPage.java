package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.Optional;

/**
 * 充值页（{@code user/buy}，B10，19 文档第 4.5、8.1 节）：卡密充值与在线充值建单两个表单。建单成功后跳到订单列表（在那里"去支付"）。
 *
 * @param shell      会员页外壳
 * @param action     两个表单的提交地址（{@code user/buy}）
 * @param points     当前积分
 * @param minText    最小充值金额（{@code pay.min} 原文）
 * @param scaleText  每元积分（{@code pay.scale} 原文）
 * @param cardShop   "购买充值卡"链接（{@code pay.card.url} 是本站地址时）
 * @param selfUrl    本页（卡密充值成功后刷新）
 * @param ordersUrl  订单列表（建单成功后）
 * @param back       返回会员中心
 */
public record UserBuyPage(MemberShell shell, SafeUrl action, long points, String minText, String scaleText,
                          Optional<SafeUrl> cardShop, SafeUrl selfUrl, SafeUrl ordersUrl, NavLink back) {
}
