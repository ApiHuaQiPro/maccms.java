package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;
import java.util.Optional;

/**
 * 内容详情页上的互动区（B11，{@code site/interaction/panel.jte}）：旧模板由 {@code home.js} 调用 {@code ajax/hits}（打开页面即计一次点击）、
 * {@code ajax/digg}、{@code ajax/score}，并在 {@code .mac_comment} 里加载评论。新版页面不带脚本：点击由页内一个不显示的图片请求
 * {@code ajax/hits?type=update} 计数（只在 {@code maccms.site.hit-counter-enabled=true} 时输出，03 方案第 6.3 节），顶踩与评分是普通表单
 * （提交后返回提示页），评论是链接。
 *
 * @param hitsBeacon   点击计数地址（{@code <img hidden>} 请求）；点击计数开关关闭时为空
 * @param scoreAction  评分表单的提交地址（{@code ajax/score}）
 * @param scoreFields  评分表单的隐藏字段（mid、id）
 * @param scoreOptions 评分选项（旧模板 1～10 分）
 * @param comments     评论页地址（评论关闭时为空）
 */
public record InteractionPanel(Optional<SafeUrl> hitsBeacon, InlineForm upForm, InlineForm downForm, SafeUrl scoreAction,
                               List<HiddenField> scoreFields, List<Integer> scoreOptions, Optional<SafeUrl> comments) {

    public InteractionPanel {
        scoreFields = List.copyOf(scoreFields);
        scoreOptions = List.copyOf(scoreOptions);
    }
}
