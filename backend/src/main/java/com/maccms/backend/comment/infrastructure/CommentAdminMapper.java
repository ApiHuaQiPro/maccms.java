package com.maccms.backend.comment.infrastructure;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 后台评论与留言管理的只读查询（B13，docs/architecture/22-b13-admin-content.md 第 5 节；SQL 见
 * {@code resources/mapper/comment/CommentAdminMapper.xml}）。每行按文字取回全部列，只供后台的评论服务使用。
 */
@Mapper
public interface CommentAdminMapper {

    Map<String, String> selectComment(@Param("id") long id);

    Map<String, String> selectGbook(@Param("id") long id);

    long countComments(@Param("c") CommentAdminCriteria c);

    List<Map<String, String>> listComments(@Param("c") CommentAdminCriteria c, @Param("offset") long offset,
                                           @Param("limit") long limit);

    long countGbooks(@Param("c") CommentAdminCriteria c);

    List<Map<String, String>> listGbooks(@Param("c") CommentAdminCriteria c, @Param("offset") long offset,
                                         @Param("limit") long limit);
}
