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
}
