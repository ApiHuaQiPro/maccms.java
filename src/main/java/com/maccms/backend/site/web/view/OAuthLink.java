package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;

public record OAuthLink(String type, SafeUrl url, SafeUrl iconUrl) {
}
