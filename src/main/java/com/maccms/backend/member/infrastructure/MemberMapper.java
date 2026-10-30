package com.maccms.backend.member.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 只读。SQL 见 resources/mapper/member/MemberMapper.xml。写入在 {@link MemberWriteMapper}（B09，可写数据源）；
 * 到期降级的批量任务归 B10。
 */
@Mapper
public interface MemberMapper {

    /**
     * 旧 {@code checkLogin} 的查询（{@code common/model/User.php:436-441}）：{@code user_id}、{@code user_name} 都按字符串比较，
     * 比较规则交给 MySQL（整数列与字符串比较时按数值、{@code user_name} 按列的排序规则不分大小写并忽略尾部空格），与旧版绑定字符串参数相同。
     */
    MemberLoginRow selectActiveUser(@Param("userId") String userId, @Param("userName") String userName);

    MemberGroupRow selectGroup(@Param("groupId") int groupId);

    /**
     * 旧 {@code login()} 的查询（{@code common/model/User.php:338-357}）：按用户名或邮箱、{@code user_pwd} 与 {@code user_status=1}。
     * 只比较 {@code md5(trim(原值))}（D2 ①，不再有 {@code OR user_pwd = 输入}）。旧版 {@code find()} 不排序，这里按 {@code user_id}
     * 取第一行，同名/同邮箱多行时结果确定。
     */
    MemberLoginCandidate selectLoginCandidate(@Param("byEmail") boolean byEmail, @Param("account") String account,
                                              @Param("pwd") String pwd);

    /** 账号的完整资料（按 {@code user_id}，不看状态）。 */
    MemberAccountRow selectAccount(@Param("userId") long userId);

    /** 同名会员是否存在（旧 {@code where('user_name', $name)->find()}，MySQL 比较规则）。 */
    Long selectIdByName(@Param("name") String name);

    /** 同邮箱会员是否存在（旧 {@code regcheck} 的 {@code user_email}）。 */
    Long selectIdByEmail(@Param("email") String email);

    /** 今天（{@code since} 之后）该 IP 注册的会员数（旧 {@code User.php:168-176}）。 */
    long countRegisteredSince(@Param("ip") long ip, @Param("since") long since);

    /** 推广人（{@code User.php:238-240}）。 */
    MemberInviterRow selectInviter(@Param("userId") long userId);

    /** 密保找回（{@code User.php:504-509}）：三项都按 MySQL 比较规则，不看 {@code user_status}。 */
    Long selectIdByQuestion(@Param("name") String name, @Param("question") String question,
                            @Param("answer") String answer);

    // ------------------------------------------------------------------ 短信/邮件验证码（B25，32 文档第 5 节）

    /** 同手机号会员（旧 {@code where('user_phone', $to)->find()}）。 */
    Long selectIdByPhone(@Param("phone") String phone);

    /**
     * 频率限制（旧 {@code send_msg}，{@code common/model/User.php:668-677}）：该会员（游客为 0）在 {@code since} 之后给同一地址发过同类验证码。
     */
    Long selectRecentMessage(@Param("userId") long userId, @Param("since") long since, @Param("type") int type,
                             @Param("to") String to);

    /**
     * 校验（旧 {@code check_msg}，{@code User.php:617-628}）：该会员在 {@code since} 之后收到的同类、同验证码的记录；新版另要求发送地址与
     * 本次使用的地址相同（D-B25-4）。
     */
    Long selectValidMessage(@Param("userId") long userId, @Param("since") long since, @Param("type") int type,
                            @Param("code") String code, @Param("to") String to);

    // ------------------------------------------------------------------ B26 第三方登录（34 文档第 4 节）

    /**
     * 绑定了该 openid 的会员（旧 {@code infoData([$col => $openid])}，{@code index/controller/User.php:255-257}）：不看状态，按编号取第一行。
     *
     * @param qq true 查 {@code user_openid_qq}，false 查 {@code user_openid_weixin}
     */
    Long selectIdByOpenid(@Param("qq") boolean qq, @Param("openid") String openid);

    /** 凭 openid 登录要找的会员（旧 {@code login(['col','openid'])}，{@code common/model/User.php:347-357}）：{@code user_status=1}，按编号取第一行。 */
    MemberLoginCandidate selectOpenidLoginCandidate(@Param("qq") boolean qq, @Param("openid") String openid);

    /** 会员当前绑定的 openid（旧 {@code $check['info'][$col]}）；会员不存在时为 null。 */
    String selectOpenid(@Param("userId") long userId, @Param("qq") boolean qq);
}
