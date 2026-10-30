package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;

/**
 * 支付宝下单（{@code user/gopay}，B24，33 文档第 6.2 节）：旧版输出的表单 {@code alipaysubmit}（POST 到网关，W3）。旧版在表单后用内联脚本
 * 自动提交；站点模板不允许脚本，页面保留旧版的"正在提交"按钮，表单带 {@code data-pay-autosubmit} 供前台脚本自动提交（前端配合项）。
 *
 * @param shell  会员页外壳
 * @param action 网关地址（部署参数，默认同旧版）
 * @param fields 隐藏字段，顺序同旧版
 * @param back   返回订单列表
 */
public record UserPayFormPage(MemberShell shell, SafeUrl action, List<HiddenField> fields, NavLink back) {

    public UserPayFormPage {
        fields = List.copyOf(fields);
    }
}
