package com.maccms.backend.comment.infrastructure;

/**
 * 后台评论、留言列表的筛选条件（B13，22 文档第 7.9 节）。每个字段对应 Mapper XML 里写死列名的一个条件（为 null/false 时不加）；
 * 包含匹配是已转写成 {@code ESCAPE '!'} 形式的模式。
 */
public final class CommentAdminCriteria {

    /** 审核状态（旧版严格 in_array('0','1')）。 */
    public String status;
    /** 评论的模块（宽松 in_array('1','2','3') 时的原值）。 */
    public String mid;
    public String uid;
    /** 评论：未被举报（{@code comment_report = 0}）/ 被举报（{@code > 0}）。 */
    public boolean reportZero;
    public boolean reportPositive;
    /** 留言：留言（{@code gbook_rid = 0}）/ 报错（{@code > 0}）。 */
    public boolean ridZero;
    public boolean ridPositive;
    /** 留言：已回复（{@code gbook_reply_time > 0}）。 */
    public boolean replied;
    /** 昵称或内容包含匹配的模式。 */
    public String wdLike;

    public String getStatus() {
        return status;
    }

    public String getMid() {
        return mid;
    }

    public String getUid() {
        return uid;
    }

    public boolean isReportZero() {
        return reportZero;
    }

    public boolean isReportPositive() {
        return reportPositive;
    }

    public boolean isRidZero() {
        return ridZero;
    }

    public boolean isRidPositive() {
        return ridPositive;
    }

    public boolean isReplied() {
        return replied;
    }

    public String getWdLike() {
        return wdLike;
    }
}
