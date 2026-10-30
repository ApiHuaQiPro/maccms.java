package com.maccms.backend.integration.application;

import java.util.List;

/**
 * QQ/微信第三方登录（E10，B26；docs/architecture/34-b26-oauth.md）：旧 {@code login\ThinkOauth}（{@code extend/login}，仓库与备份都缺失）
 * 的授权跳转、服务端用 {@code code} 换取令牌与 openid、取用户信息（旧 {@code index/event/LoginEvent.php}）。实现在已登记子包
 * {@code integration.oauth}，经出站客户端发请求；其他模块只经本接口使用。
 *
 * <p>openid 只能来自 {@link #fetch} 的结果（服务端向 QQ/微信换取），不取自浏览器提交的任何参数（路线图第 4.3 节 ③）。
 */
public interface OAuthLogin {

    /** 支持的登录方式（旧 {@code user_openid_<type>} 两列）。 */
    List<String> TYPES = List.of("qq", "weixin");

    /** 该方式已在后台打开（{@code connect.<type>.status=1}）且填了应用标识与密钥。 */
    boolean enabled(String type);

    /**
     * 浏览器要跳转去的授权页地址（W 类外部地址，服务端不发请求）。
     *
     * @param state    本次授权的一次性 {@code state}
     * @param callback 回调地址（部署配置中的固定值，不取自请求）
     * @throws IllegalStateException 该方式未打开
     */
    String authorizeUrl(String type, String state, String callback);

    /**
     * 用授权码换取令牌、openid，再取用户信息。任何失败都以 {@link OAuthOutcome} 返回，不抛出。
     *
     * @param callback 与发起授权时相同的回调地址（换令牌时要再交一次）
     */
    OAuthOutcome fetch(String type, String code, String callback);
}
