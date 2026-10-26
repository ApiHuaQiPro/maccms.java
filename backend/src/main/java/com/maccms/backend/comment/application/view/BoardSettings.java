package com.maccms.backend.comment.application.view;

/**
 * 旧配置 {@code comment} 或 {@code gbook} 段（application/extra/maccms.php:127-144），按旧代码的比较方式解释：
 *
 * @param open           旧 {@code status == 0} 时关闭（构造函数直接输出 {@code comment is close} / {@code gbook is close}）
 * @param audit          旧 {@code audit == 1}：新发表的为未审核（status=0）
 * @param login          旧 {@code login == 1}：必须登录才能发表
 * @param verify         旧 {@code verify == 1}：发表时校验验证码
 * @param pageSize       旧 {@code pagesize}（模板的列表条数，intval，&lt; 1 时为 20），新版页面上限 100
 * @param cookieLifetime 旧 {@code timespan}：发表、举报、评论顶踩后写的频率 cookie 的有效秒数。ThinkPHP {@code cookie($name, 't', $timespan)}：
 *                       {@code is_numeric} 且非空时有效期为 {@code intval} 秒（可以是 0 或负数，即立即过期）；否则为 {@code null}，写会话 cookie
 */
public record BoardSettings(boolean open, boolean audit, boolean login, boolean verify, int pageSize,
                            Long cookieLifetime) {
}
