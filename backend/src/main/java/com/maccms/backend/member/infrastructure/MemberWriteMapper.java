package com.maccms.backend.member.infrastructure;

import com.maccms.backend.common.write.WriteMapper;
import org.apache.ibatis.annotations.Param;

/**
 * 会员账号的写入（B09，18 文档第 6 节）。SQL 见 {@code resources/mapper-write/member/MemberWriteMapper.xml}；只能经
 * {@code WriteDatabase.mapper()} 取得（{@link MemberInfrastructureConfiguration}）。每个方法返回<b>匹配</b>行数，调用方与预期比较。
 */
@WriteMapper
public interface MemberWriteMapper {

    int register(@Param("name") String name, @Param("pwd") String pwd, @Param("points") long points,
                 @Param("status") int status, @Param("now") long now, @Param("ip") long ip,
                 @Param("regNum") long regNum, @Param("since") long since);

    int setInviter(@Param("userId") long userId, @Param("pid") long pid, @Param("pid2") long pid2,
                   @Param("pid3") long pid3);

    int recordLogin(@Param("userId") long userId, @Param("pwd") String pwd, @Param("random") String random,
                    @Param("now") long now, @Param("ip") long ip);

    int rotateRandom(@Param("userId") long userId, @Param("oldRandom") String oldRandom,
                     @Param("random") String random);

    /**
     * @param nickName 为 null 时不写昵称
     * @param pwd      为 null 时不改密码（此时 {@code random} 不用）
     */
    int updateProfile(@Param("userId") long userId, @Param("oldPwd") String oldPwd, @Param("qq") String qq,
                      @Param("question") String question, @Param("answer") String answer,
                      @Param("nickName") String nickName, @Param("pwd") String pwd, @Param("random") String random);

    int resetPassword(@Param("userId") long userId, @Param("name") String name, @Param("question") String question,
                      @Param("answer") String answer, @Param("pwd") String pwd, @Param("random") String random);

    int updatePortrait(@Param("userId") long userId, @Param("portrait") String portrait);

    int expireMember(@Param("userId") long userId, @Param("now") long now);

    // ------------------------------------------------------------------ B25 短信/邮件验证码（32 文档第 5 节）

    /** 发送成功后记一条验证码（旧 {@code User.php:696-706}，登记 member / mac_msg / INSERT）。 */
    int insertMessage(@Param("userId") long userId, @Param("type") int type, @Param("to") String to,
                      @Param("code") String code, @Param("content") String content, @Param("now") long now);

    /**
     * 注册时验证了手机/邮箱（旧 {@code User.php:189-228}）：同 {@link #register}，另写 {@code user_phone} 或 {@code user_email}，
     * 并在同一条语句里要求该号码/地址没有被其他会员使用。
     *
     * @param phone 验证的是手机时为号码，否则 null
     * @param email 验证的是邮箱时为地址，否则 null
     */
    int registerWithContact(@Param("name") String name, @Param("pwd") String pwd, @Param("points") long points,
                            @Param("status") int status, @Param("now") long now, @Param("ip") long ip,
                            @Param("regNum") long regNum, @Param("since") long since, @Param("phone") String phone,
                            @Param("email") String email);

    /** 绑定前清掉所有会员上的同一号码（旧 {@code bind}，{@code User.php:733}）。返回匹配行数，可以为 0。 */
    int clearPhone(@Param("phone") String phone);

    int clearEmail(@Param("email") String email);

    int setPhone(@Param("userId") long userId, @Param("phone") String phone);

    int setEmail(@Param("userId") long userId, @Param("email") String email);

    /** 用手机/邮箱验证码重置密码（旧 {@code findpass_reset}，{@code User.php:836-840}）：同号码/地址的会员全部改密，同时轮换 {@code user_random}（D2 ⑥）。 */
    int resetPasswordByPhone(@Param("phone") String phone, @Param("pwd") String pwd, @Param("random") String random);

    int resetPasswordByEmail(@Param("email") String email, @Param("pwd") String pwd, @Param("random") String random);
}
