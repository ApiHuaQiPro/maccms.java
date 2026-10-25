package com.maccms.backend.member.application.view;

/**
 * 会员中心各列表的行（B10，docs/architecture/19-b10-member-finance.md 第 4.3、4.9、4.10 节）。都是库里一行的原值，页面上的文字与链接由
 * site 生成。
 */
public final class FinanceRecords {

    private FinanceRecords() {
    }

    /** mac_ulog 一行（{@code type}：1 浏览、2 收藏、3 想看、4 播放、5 下载；{@code mid}：1 视频、2 文章、3 专题、8 演员）。 */
    public record Ulog(long id, long userId, int mid, int type, long rid, int sid, int nid, int points, long time) {
    }

    /** mac_plog 一行（{@code type} 1～9，旧 {@code mac_get_plog_type_text}，common.php:1162-1176）。 */
    public record Plog(long id, int type, int points, long time, String remarks) {
    }

    /** mac_cash 一行（{@code status}：0 未审核、1 已审核）；{@code money} 为库里 decimal 的文本。 */
    public record Cash(long id, int status, int points, String money, String bankName, String bankNo,
                       String payeeName, long time, long timeAudit) {
    }

    /** mac_order 一行（{@code status}：0 未支付、1 已支付）；{@code price} 为库里 decimal 的文本。 */
    public record Order(long id, long userId, int status, String code, String price, long time, int points,
                        String payType, long payTime, String remarks) {
    }

    /** 已使用的 mac_card 一行（不含卡密）。 */
    public record Card(long id, String cardNo, int money, int points, long useTime) {
    }

    /** 推广会员（只含用户名、昵称与注册、登录时间）。 */
    public record Invitee(long id, String userName, String nickName, long regTime, long loginTime) {
    }

    /** 可升级的会员组与四种时长的价格（{@code status}：0 关闭）。 */
    public record GroupOffer(int id, String name, int status, int day, int week, int month, int year) {
    }

    /** 会员当前的积分、冻结积分、会员组与到期时间（写库前重读的值）。 */
    public record Wallet(long userId, String userName, int groupId, long points, long froze, long endTime) {
    }
}
