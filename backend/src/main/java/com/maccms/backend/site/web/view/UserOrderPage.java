package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;
import java.util.Optional;

/**
 * 订单详情页（{@code user/order_info} 的非 ajax 形态，B10，19 文档第 4.10 节）。只读本会员的订单；查不到时 {@code details} 为空，
 * 页面显示"获取失败"。
 *
 * @param shell   会员页外壳
 * @param details 订单全部字段
 * @param pay     未支付时的"去支付"链接
 * @param back    返回订单列表
 */
public record UserOrderPage(MemberShell shell, List<MetadataItem> details, Optional<SafeUrl> pay, NavLink back) {

    public UserOrderPage {
        details = List.copyOf(details);
    }
}
