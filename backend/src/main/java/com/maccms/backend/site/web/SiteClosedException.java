package com.maccms.backend.site.web;

/** {@code maccms.site.status=closed} 时由 SiteStatusInterceptor 在调用页面 Controller 之前抛出。 */
public class SiteClosedException extends RuntimeException {

    public SiteClosedException() {
        super("site closed", null, false, false);
    }
}
