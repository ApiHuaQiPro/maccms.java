package com.maccms.backend.comment.application.view;

import java.util.List;

/**
 * 评论与留言用到的旧配置（B11，每个请求读取一次，见 {@code InteractionSettingsSource}）。
 *
 * @param comment     {@code comment} 段
 * @param gbook       {@code gbook} 段
 * @param filterWords {@code app.filter_words} 按半角逗号拆开的各项（UTF-8 字节；空项保留，替换时跳过）
 */
public record InteractionSettings(BoardSettings comment, BoardSettings gbook, List<byte[]> filterWords) {

    /** 没有旧配置目录时：两者都打开、不审核、不要求登录、不要验证码、20 条、3 秒（仓库值，application/extra/maccms.php:127-144）、无过滤词。 */
    public static final InteractionSettings DEFAULTS = new InteractionSettings(
            new BoardSettings(true, false, false, false, 20, 3L), new BoardSettings(true, false, false, false, 20, 3L),
            List.of());
}
