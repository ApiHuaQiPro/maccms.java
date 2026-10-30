package com.maccms.backend.comment.infrastructure;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 评论与留言的只读查询（B11）。SQL 见 {@code mapper/comment/CommentMapper.xml}。 */
@Mapper
public interface CommentMapper {

    long countComments(@Param("c") CommentListCriteria criteria);

    List<CommentRow> selectComments(@Param("c") CommentListCriteria criteria, @Param("offset") long offset,
                                    @Param("limit") int limit);

    /** 已审核的回复（旧 listData 对每条评论再查 {@code comment_pid = id AND comment_status = 1}，Comment.php:46-54），排序同列表。 */
    List<CommentRow> selectReplies(@Param("pids") Collection<Long> pids, @Param("sort") String sort,
                                   @Param("descending") boolean descending, @Param("limit") int limit);

    /** 按主键读顶踩数（旧 infoData，不看审核状态，Comment.php:197-210）。 */
    CommentRow selectComment(@Param("id") long id);

    long countGbooks(@Param("rid") long rid, @Param("userId") long userId);

    /** @param sort ID、TIME、REPLY_TIME（旧 by=id/time/reply_time） */
    List<GbookRow> selectGbooks(@Param("rid") long rid, @Param("userId") long userId, @Param("sort") String sort,
                                @Param("descending") boolean descending, @Param("offset") long offset,
                                @Param("limit") int limit);
}
