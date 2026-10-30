package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;
import java.util.Optional;

/**
 * 发表评论/留言的表单（B11，{@code site/interaction/postForm.jte}）。提交地址是旧接口（{@code comment/saveData}、{@code gbook/saveData}），
 * 字段名沿用旧模板（{@code comment_content}、{@code gbook_content}、{@code verify}）。
 *
 * @param contentName 正文字段名
 * @param captcha     旧配置 {@code verify == 1} 时的验证码
 * @param loginNotice 旧配置 {@code login == 1} 且未登录时显示的登录链接（表单照常显示，提交时由服务端给出"登录后才可以发表留言"）
 */
public record BoardPostForm(String id, SafeUrl action, List<HiddenField> fields, String contentName, String label,
                            Optional<MemberCaptcha> captcha, Optional<SafeUrl> loginNotice) {

    public BoardPostForm {
        fields = List.copyOf(fields);
    }
}
