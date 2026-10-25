package com.maccms.backend.site.web.view;

import com.maccms.backend.member.application.view.MemberProfile;
import com.maccms.backend.site.application.SafeUrl;

/**
 * 资料页（{@code user/info}，18 文档第 3.7 节）：昵称、QQ、密保问题与答案回填当前值（旧版提交时这几项空值也照写），改密填新密码两次，
 * 当前密码必填。
 */
public record UserInfoPage(MemberShell shell, MemberProfile profile, SafeUrl action, SafeUrl indexUrl) {
}
