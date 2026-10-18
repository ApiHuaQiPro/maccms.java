package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.Optional;

/** 头像页（{@code user/portrait}，18 文档第 3.8 节）；功能关闭时 {@code action} 为空，只显示说明。 */
public record UserPortraitPage(MemberShell shell, SafeUrl avatar, Optional<SafeUrl> action, SafeUrl indexUrl) {
}
