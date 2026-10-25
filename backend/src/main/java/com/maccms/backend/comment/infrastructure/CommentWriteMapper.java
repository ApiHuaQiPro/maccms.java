package com.maccms.backend.comment.infrastructure;

import com.maccms.backend.common.write.WriteMapper;
import org.apache.ibatis.annotations.Param;

/**
 * 评论与留言的写入（B11，docs/architecture/20-b11-comment-gbook-counters.md 第 4 节；登记 WriteRegistry：comment / mac_comment INSERT、UPDATE，
 * mac_gbook INSERT）。SQL 见 {@code resources/mapper-write/comment/CommentWriteMapper.xml}；只能经 {@code WriteDatabase.mapper()} 取得。
 * 返回匹配行数。前台只能新增评论/留言（旧控制器已去掉 comment_id/gbook_id，Comment.php:94-95、Gbook.php:83-84），不改已有行的内容。
 */
@WriteMapper
public interface CommentWriteMapper {

    int insertComment(@Param("mid") int mid, @Param("rid") long rid, @Param("pid") long pid,
                      @Param("userId") long userId, @Param("status") int status, @Param("name") String name,
                      @Param("ip") long ip, @Param("now") long now, @Param("content") String content);

    /** 举报 +1（旧 setInc，Comment.php:134）。 */
    int reportComment(@Param("id") long id);

    /** 顶或踩 +1（旧 setInc，Comment.php:161-165、Ajax.php:322-327 的 mid=4）。 */
    int diggComment(@Param("id") long id, @Param("up") boolean up);

    int insertGbook(@Param("rid") long rid, @Param("userId") long userId, @Param("status") int status,
                    @Param("name") String name, @Param("ip") long ip, @Param("now") long now,
                    @Param("content") String content);
}
