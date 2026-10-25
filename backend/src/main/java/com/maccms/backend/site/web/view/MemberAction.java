package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;

/**
 * 会员中心页上的一个小表单（B10）：由 {@code member.js} 以 ajax 提交（{@code form[data-member-form]}，B09 第 8.3 节），结果显示在表单内的
 * {@code [data-member-message]}，成功后跳到 {@code successUrl}（"刷新本页"即本页地址）。用于删除记录、清空、确认等。
 *
 * @param kind       {@code data-member-form} 的值
 * @param action     提交地址
 * @param fields     隐藏字段
 * @param label      按钮文字
 * @param successUrl 成功后的去处
 */
public record MemberAction(String kind, SafeUrl action, List<HiddenField> fields, String label, SafeUrl successUrl) {

    public MemberAction {
        fields = List.copyOf(fields);
    }
}
