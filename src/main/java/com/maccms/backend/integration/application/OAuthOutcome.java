package com.maccms.backend.integration.application;

/**
 * {@link OAuthLogin#fetch} 的结果。
 *
 * @param status   结果
 * @param openid   成功时为服务端换取的 openid（只含 {@code [A-Za-z0-9_-]}，1～40 个字符，即 {@code user_openid_*} 列宽）
 * @param nickname 成功时为平台昵称（旧版自动注册时并不写入，见 34 文档 D-B26-6）
 * @param message  {@link Status#USER_INFO_FAILED} 时为平台给的错误说明（QQ {@code msg}、微信 {@code errmsg}），可为空串
 */
public record OAuthOutcome(Status status, String openid, String nickname, String message) {

    public enum Status {
        /** 取得 openid 与用户信息。 */
        OK,
        /** 换取令牌或 openid 失败（旧 {@code getAccessToken} 没有返回数组：{@code index/logincallback2}）。 */
        TOKEN_FAILED,
        /** 取用户信息失败（旧 {@code LoginEvent} 返回 code 0）。 */
        USER_INFO_FAILED
    }

    public static OAuthOutcome ok(String openid, String nickname) {
        return new OAuthOutcome(Status.OK, openid, nickname == null ? "" : nickname, "");
    }

    public static OAuthOutcome tokenFailed() {
        return new OAuthOutcome(Status.TOKEN_FAILED, null, null, "");
    }

    public static OAuthOutcome userInfoFailed(String message) {
        return new OAuthOutcome(Status.USER_INFO_FAILED, null, null, message == null ? "" : message);
    }

    @Override
    public String toString() {
        // 不输出 openid 与昵称
        return "OAuthOutcome[" + status + "]";
    }
}
