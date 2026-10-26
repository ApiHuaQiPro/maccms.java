package com.maccms.backend.comment.infrastructure;

/**
 * MySQL 拒绝了后台评论、留言写入的取值（严格模式下的数据错误）。旧版在这些情况下整页 500；后台归为"参数错误"（22 文档 D-B13-3）。
 * 不带原始异常信息（可能含取值）。
 */
public final class CommentDataException extends RuntimeException {

    public CommentDataException() {
        super(null, null, false, false);
    }
}
