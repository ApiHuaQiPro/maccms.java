package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;
import java.util.Optional;

/**
 * 微信扫码支付（{@code user/gopay} 的 {@code payment=weixin}，旧模板 {@code user/payment_weixin}，B24，33 文档第 6.3 节）：订单信息与本站生成的
 * 二维码（{@code user/qrcode?data=<code_url>}）。统一下单失败时没有二维码，显示"获取微信二维码失败"。旧模板缺失（D9-a），页面只用会员页已有的样式类。
 *
 * @param shell   会员页外壳
 * @param details 订单信息
 * @param qrcode  二维码图片地址（本站）；失败时为空
 * @param orders  支付完成后查看订单
 * @param back    返回订单列表
 */
public record UserPayWeixinPage(MemberShell shell, List<MetadataItem> details, Optional<SafeUrl> qrcode, SafeUrl orders,
                                NavLink back) {

    public UserPayWeixinPage {
        details = List.copyOf(details);
    }
}
