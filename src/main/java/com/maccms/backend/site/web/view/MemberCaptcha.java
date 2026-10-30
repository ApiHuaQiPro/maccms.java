package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;

/** 表单里的验证码（B08 本地验证码 {@code verify/index}；刷新时前端在地址后加 {@code ?r=随机数}）。 */
public record MemberCaptcha(SafeUrl src) {
}
