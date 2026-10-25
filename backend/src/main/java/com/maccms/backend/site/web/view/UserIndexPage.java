package com.maccms.backend.site.web.view;

import com.maccms.backend.member.application.view.MemberProfile;
import com.maccms.backend.site.application.SafeUrl;

/** 会员中心首页（{@code user/index}）。B10/B11 的入口在对应批次加入。 */
public record UserIndexPage(MemberShell shell, MemberProfile profile, SafeUrl avatar, SafeUrl infoUrl,
                            SafeUrl portraitUrl, SafeUrl logoutUrl) {
}
