package com.maccms.backend.site.web.view;

import com.maccms.backend.member.application.view.MemberProfile;
import com.maccms.backend.site.application.SafeUrl;
import java.util.List;

/**
 * 资料页（{@code user/info}，18 文档第 3.7 节）：昵称、QQ、密保问题与答案回填当前值（旧版提交时这几项空值也照写），改密填新密码两次，
 * 当前密码必填。已打开的第三方平台提供绑定入口；D-B26-10 未决，不提供 openid 解绑操作。
 */
public record UserInfoPage(MemberShell shell, MemberProfile profile, SafeUrl action, SafeUrl indexUrl,
                           List<OAuthLink> oauthLinks) {

    public UserInfoPage {
        oauthLinks = List.copyOf(oauthLinks);
    }
}
