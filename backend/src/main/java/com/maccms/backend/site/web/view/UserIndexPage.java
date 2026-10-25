package com.maccms.backend.site.web.view;

import com.maccms.backend.member.application.view.MemberProfile;
import com.maccms.backend.site.application.SafeUrl;
import java.util.List;

/**
 * 会员中心首页（{@code user/index}）。B10 起加入会员权益与财务各页的入口（{@code finance}）与推广链接（{@code promotion}，
 * 19 文档第 8.1 节）；B11 的入口在对应批次加入。
 */
public record UserIndexPage(MemberShell shell, MemberProfile profile, SafeUrl avatar, SafeUrl infoUrl,
                            SafeUrl portraitUrl, SafeUrl logoutUrl, List<NavLink> finance,
                            List<MetadataItem> promotion) {

    public UserIndexPage {
        finance = List.copyOf(finance);
        promotion = List.copyOf(promotion);
    }

    public UserIndexPage(MemberShell shell, MemberProfile profile, SafeUrl avatar, SafeUrl infoUrl,
                         SafeUrl portraitUrl, SafeUrl logoutUrl) {
        this(shell, profile, avatar, infoUrl, portraitUrl, logoutUrl, List.of(), List.of());
    }
}
