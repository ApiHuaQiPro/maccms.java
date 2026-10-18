package com.maccms.backend.member.application;

import com.maccms.backend.common.web.PhpCookies;
import com.maccms.backend.member.application.view.MemberGroup;
import java.util.List;

/**
 * 旧会员 cookie 的写出（B08，17 文档第 2.2 节）；B09 起由登录、注销、改密与页面的 {@code label_user} 接线写出（18 文档第 4 节）。
 * 名称、编码与属性与 PHP 8.0 + ThinkPHP {@code cookie()} 写出的 {@code Set-Cookie} 逐字节相同（{@code member/php80-golden.json}
 * 的 setcookie、login 用例；有效期按传入的当前时刻计算），唯一的有意差异是 {@code user_check} 加 HttpOnly（D-B08-7）：
 * <ul>
 * <li>登录（{@code User.php:385-390}）：{@code user_id}、{@code user_name}、{@code group_id}、{@code group_name}、{@code user_check}、
 * {@code user_portrait} 六个，{@code expire=2592000}（30 天），{@code path=/}，不设 domain，不加 Secure。{@code user_check} 是 30 天
 * 有效、等同凭证的值，前端没有任何地方读取（仓库内 {@code *.js}/{@code *.html} 全量检索），所以加 HttpOnly，字节与 PHP 8.0
 * {@code setcookie(…, httponly: true)} 相同；其余五个旧 {@code home.js} 要读（{@code static/js/home.js:596-602}），保持旧契约
 * 不加 HttpOnly；</li>
 * <li>注销（{@code User.php:411-419}）：同名六个按 ThinkPHP {@code Cookie::delete} 写成已过期（与旧版逐字节相同；服务端
 * 的 {@code Set-Cookie} 可以覆盖/删除 HttpOnly cookie，回滚到 PHP 后旧注销同样能删掉它）；</li>
 * <li>{@code label_user} 校验失败时的回写（{@code All.php:136-138}）：只有 {@code user_id=0}、{@code user_name=游客} 两个会话 cookie
 * （第三句 {@code cookie('user_check','')} 在 ThinkPHP 助手里是读取，不会写出）。</li>
 * </ul>
 * {@code path} 固定为 {@code /}：旧版 cookie 配置的 path 就是 {@code /}，与站点是否在子目录无关。
 */
public final class MemberCookies {

    /** 旧版登录 cookie 的有效期（秒）。 */
    public static final long LOGIN_EXPIRE_SECONDS = 2_592_000L;
    /** 旧 {@code application/config.php:226}。 */
    public static final String PATH = "/";
    /** 旧 {@code lang('controller/visitor')}。 */
    public static final String VISITOR_NAME = "游客";

    private static final List<String> NAMES = List.of("user_id", "user_name", "group_id", "group_name", "user_check",
            "user_portrait");

    private MemberCookies() {
    }

    /**
     * 登录成功后写出的六个 cookie（{@code Set-Cookie} 头值）。
     *
     * @param userRandom 本次登录写入库的 {@code user_random}
     * @param portrait   旧 {@code mac_get_user_portrait(user_id)} 的结果（站点根路径 + 头像相对路径）
     */
    public static List<String> login(long userId, String userName, int groupId, String groupName, String userRandom,
                                     String portrait, long nowEpochSeconds) {
        long expires = nowEpochSeconds + LOGIN_EXPIRE_SECONDS;
        return List.of(
                PhpCookies.setCookie("user_id", Long.toString(userId), expires, nowEpochSeconds, PATH),
                PhpCookies.setCookie("user_name", userName, expires, nowEpochSeconds, PATH),
                PhpCookies.setCookie("group_id", Integer.toString(groupId), expires, nowEpochSeconds, PATH),
                PhpCookies.setCookie("group_name", groupName, expires, nowEpochSeconds, PATH),
                PhpCookies.setCookie("user_check", UserCheck.of(userRandom, userName, userId), expires,
                        nowEpochSeconds, PATH, true),
                PhpCookies.setCookie("user_portrait", portrait, expires, nowEpochSeconds, PATH));
    }

    /**
     * 登录 cookie，会员组可能不存在（B09）：旧版 {@code cookie('group_id', $group['group_id'])} 在组行不存在时值为 null，ThinkPHP
     * {@code cookie(name, null)} 是删除（{@code thinkphp/helper.php:323-341}），所以这两个写成删除 cookie，位置不变；组名为空串时
     * {@code cookie(name, '')} 是读取，不写出 {@code group_name}。
     *
     * @param group 有效会员组；不存在时为 null
     */
    public static List<String> login(long userId, String userName, MemberGroup group, String userRandom,
                                     String portrait, long nowEpochSeconds) {
        if (group != null && !group.name().isEmpty()) {
            return login(userId, userName, group.id(), group.name(), userRandom, portrait, nowEpochSeconds);
        }
        long expires = nowEpochSeconds + LOGIN_EXPIRE_SECONDS;
        if (group != null) {
            // 组名为空串：ThinkPHP cookie(name, '') 是读取，不写出
            return List.of(
                    PhpCookies.setCookie("user_id", Long.toString(userId), expires, nowEpochSeconds, PATH),
                    PhpCookies.setCookie("user_name", userName, expires, nowEpochSeconds, PATH),
                    PhpCookies.setCookie("group_id", Integer.toString(group.id()), expires, nowEpochSeconds, PATH),
                    PhpCookies.setCookie("user_check", UserCheck.of(userRandom, userName, userId), expires,
                            nowEpochSeconds, PATH, true),
                    PhpCookies.setCookie("user_portrait", portrait, expires, nowEpochSeconds, PATH));
        }
        return List.of(
                PhpCookies.setCookie("user_id", Long.toString(userId), expires, nowEpochSeconds, PATH),
                PhpCookies.setCookie("user_name", userName, expires, nowEpochSeconds, PATH),
                PhpCookies.deleteCookie("group_id", PATH),
                PhpCookies.deleteCookie("group_name", PATH),
                PhpCookies.setCookie("user_check", UserCheck.of(userRandom, userName, userId), expires,
                        nowEpochSeconds, PATH, true),
                PhpCookies.setCookie("user_portrait", portrait, expires, nowEpochSeconds, PATH));
    }

    /** 注销：六个 cookie 按 ThinkPHP {@code Cookie::delete} 写成已过期。 */
    public static List<String> logout() {
        return NAMES.stream().map(n -> PhpCookies.deleteCookie(n, PATH)).toList();
    }

    /** 旧 {@code label_user} 校验失败时的两个会话 cookie。 */
    public static List<String> guestReset() {
        return List.of(PhpCookies.setCookie("user_id", "0", 0, 0, PATH),
                PhpCookies.setCookie("user_name", VISITOR_NAME, 0, 0, PATH));
    }

    /** 到期降级后重发；2 组行不存在时两个都是删除 cookie（同 {@link #login(long, String, MemberGroup, String, String, long)}）。 */
    public static List<String> groupRefresh(MemberGroup group, long nowEpochSeconds) {
        if (group != null && !group.name().isEmpty()) {
            return groupRefresh(group.id(), group.name(), nowEpochSeconds);
        }
        if (group != null) {
            return List.of(PhpCookies.setCookie("group_id", Integer.toString(group.id()),
                    nowEpochSeconds + LOGIN_EXPIRE_SECONDS, nowEpochSeconds, PATH));
        }
        return List.of(PhpCookies.deleteCookie("group_id", PATH), PhpCookies.deleteCookie("group_name", PATH));
    }

    /** 旧 {@code checkLogin} 到期降级后重发的 {@code group_id}、{@code group_name}（{@code User.php:467-468}）。 */
    public static List<String> groupRefresh(int groupId, String groupName, long nowEpochSeconds) {
        long expires = nowEpochSeconds + LOGIN_EXPIRE_SECONDS;
        return List.of(PhpCookies.setCookie("group_id", Integer.toString(groupId), expires, nowEpochSeconds, PATH),
                PhpCookies.setCookie("group_name", groupName, expires, nowEpochSeconds, PATH));
    }
}
