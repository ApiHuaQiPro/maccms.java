package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;

/**
 * 订单支付页（{@code user/pay}，B10，19 文档第 4.6 节）：订单信息与支付方式。每种方式是一个提交到 {@code gopay} 的 GET 表单；B24 前
 * {@code gopay} 一律返回"该支付选项未开启！"，不生成网关地址、不跳转。
 *
 * @param shell   会员页外壳
 * @param details 订单信息（单号、金额、积分、状态、时间）
 * @param paid    已支付（不列支付方式）
 * @param gopay   {@code user/gopay}
 * @param fields  每个支付表单都带的隐藏字段（{@code order_code}、{@code order_id}）
 * @param methods 支付方式（旧 {@code mac_extends_list('pay')}）
 * @param back    返回订单列表
 */
public record UserPayPage(MemberShell shell, List<MetadataItem> details, boolean paid, SafeUrl gopay,
                          List<HiddenField> fields, List<Method> methods, NavLink back) {

    public UserPayPage {
        details = List.copyOf(details);
        fields = List.copyOf(fields);
        methods = List.copyOf(methods);
    }

    /** 一种支付方式：{@code payment} 参数值与名称。 */
    public record Method(String code, String name) {
    }
}
