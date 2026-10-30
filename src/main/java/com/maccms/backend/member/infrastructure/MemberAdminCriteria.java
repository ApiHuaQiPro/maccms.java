package com.maccms.backend.member.infrastructure;

/**
 * 后台会员与财务列表的筛选条件（B14，docs/architecture/23-b14-admin-member.md 第 5 节）。每个字段对应 Mapper XML 里写死列名的一个条件
 * （为 null 时不加）；比较值是旧版交给 ThinkPHP 的原值（由 MySQL 按列类型比较）；包含匹配是已转写成 {@code ESCAPE '!'} 形式的模式。
 */
public final class MemberAdminCriteria {

    /** 会员 {@code user_status}、会员组 {@code group_status}、提现 {@code cash_status}、订单 {@code order_status} 的原值。 */
    public String status;
    /** 会员列表的 {@code group_id}。 */
    public String group;
    /** 订单、提现、积分日志、访问日志、推广访问的 {@code user_id}。 */
    public String uid;
    /** 会员名、会员组名、卡号、单号、银行账号的包含匹配模式。 */
    public String like;
    /** 推广会员：{@code user_pid|user_pid_2|user_pid_3 = 推广人}（{@link #pidLevel} 为 0 时三列任一）。 */
    public Long pid;
    /** 推广等级 1/2/3：只比较对应的一列；0 为三列任一。 */
    public int pidLevel;
    /** 卡密的售出、使用状态。 */
    public String saleStatus;
    public String useStatus;
    /** 卡密 {@code card_add_time >=}、推广访问 {@code visit_time >=}。 */
    public Long timeFrom;
    /** 积分日志 {@code plog_type}、访问日志 {@code ulog_type}。 */
    public String type;
    /** 访问日志 {@code ulog_mid}。 */
    public String mid;
    /** 推广访问来源的两个前缀模式（{@code visit_ly LIKE a OR visit_ly LIKE b}）。 */
    public String lyLikeA;
    public String lyLikeB;

    public String getStatus() {
        return status;
    }

    public String getGroup() {
        return group;
    }

    public String getUid() {
        return uid;
    }

    public String getLike() {
        return like;
    }

    public Long getPid() {
        return pid;
    }

    public int getPidLevel() {
        return pidLevel;
    }

    public String getSaleStatus() {
        return saleStatus;
    }

    public String getUseStatus() {
        return useStatus;
    }

    public Long getTimeFrom() {
        return timeFrom;
    }

    public String getType() {
        return type;
    }

    public String getMid() {
        return mid;
    }

    public String getLyLikeA() {
        return lyLikeA;
    }

    public String getLyLikeB() {
        return lyLikeB;
    }
}
