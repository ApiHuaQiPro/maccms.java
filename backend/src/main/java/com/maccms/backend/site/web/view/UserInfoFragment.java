package com.maccms.backend.site.web.view;

import com.maccms.backend.member.application.view.MemberProfile;
import com.maccms.backend.site.application.SafeUrl;

/** {@code user/ajax_info} 的片段（无外壳）。 */
public record UserInfoFragment(MemberProfile profile, SafeUrl avatar, SafeUrl indexUrl, SafeUrl logoutUrl) {
}
