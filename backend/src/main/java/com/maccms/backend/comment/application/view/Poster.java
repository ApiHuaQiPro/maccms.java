package com.maccms.backend.comment.application.view;

/**
 * 发表评论/留言的人（B11）：已登录会员为 {@code userId > 0}，{@code name} 是旧版的显示名（会员昵称不为空时用昵称，否则用户名）；
 * 游客为 {@code userId = 0}、{@code name} 不用（按旧版写"游客"）。登录态由调用方按 B08 的 {@code label_user} 判定（cookie 校验通过才算会员，
 * 旧版只看 {@code user_id} cookie，D-B11-2）。
 */
public record Poster(long userId, String name) {

    public static final Poster GUEST = new Poster(0, "");

    public boolean member() {
        return userId > 0;
    }
}
