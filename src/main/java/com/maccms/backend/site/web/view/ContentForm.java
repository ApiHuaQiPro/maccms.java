package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;

/**
 * 内容页上的普通 POST 表单（B10，19 文档第 5.2～5.4 节）：购买确认的"确认支付"、详情页的"收藏"。页面沿用严格策略（无脚本），
 * 提交后由 {@code user/ajax_buy_popedom}、{@code user/ajax_ulog} 返回提示页（非 ajax 请求，第 3.3 节）。
 *
 * @param action 提交地址
 * @param fields 隐藏字段（名字是固定的旧参数名，值是已校验的数字或固定值）
 * @param label  按钮文字
 */
public record ContentForm(SafeUrl action, List<HiddenField> fields, String label) {

    public ContentForm {
        fields = List.copyOf(fields);
    }
}
