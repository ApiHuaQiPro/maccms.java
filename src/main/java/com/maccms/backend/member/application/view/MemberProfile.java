package com.maccms.backend.member.application.view;

/**
 * 会员中心与资料页展示的资料（B09）。文本字段是库里的原值（旧版写入时已 {@code htmlspecialchars}），{@code *Input} 是回填表单用的值
 * （只还原那四个实体，提交后再处理得到同样的库值）。不含密码、{@code user_random}。
 *
 * @param groupName     有效会员组名（到期按 2 组）；组不存在时为空串
 * @param endTime       {@code user_end_time}（秒）
 * @param portraitUrl   头像的站内路径（旧 {@code mac_get_user_portrait}：文件存在时为上传的头像，否则默认头像）
 */
public record MemberProfile(long userId, String userName, String nickName, int groupId, String groupName, long endTime,
                            long points, String email, String phone, String qq, String regTimeText,
                            String loginTimeText, String endTimeText, String portraitUrl, String nickNameInput,
                            String qqInput, String questionInput, String answerInput) {
}
