package com.maccms.backend.comment.infrastructure;

import com.maccms.backend.common.write.WriteMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 后台评论与留言管理的写入（B13，docs/architecture/22-b13-admin-content.md 第 6 节；WriteRegistry 登记 comment 对 mac_comment 的
 * INSERT、UPDATE（B11）与 DELETE（B13），对 mac_gbook 的 INSERT（B11）与 UPDATE、DELETE（B13））。SQL 见
 * {@code resources/mapper-write/comment/CommentAdminWriteMapper.xml}。
 */
@WriteMapper
public interface CommentAdminWriteMapper {

    int insertComment(CommentAdminInsert row);

    int updateComment(@Param("c") Map<String, String> c, @Param("id") long id);

    int updateCommentByIds(@Param("c") Map<String, String> c, @Param("ids") List<Long> ids);

    int deleteCommentByIds(@Param("ids") List<Long> ids);

    /** 旧"清空"（{@code comment_id > 0}）。 */
    int deleteAllComments();

    int insertGbook(CommentAdminInsert row);

    int updateGbook(@Param("c") Map<String, String> c, @Param("id") long id);

    int updateGbookByIds(@Param("c") Map<String, String> c, @Param("ids") List<Long> ids);

    int deleteGbookByIds(@Param("ids") List<Long> ids);

    /** 旧"清空"（{@code gbook_id > 0}）。 */
    int deleteAllGbooks();
}
