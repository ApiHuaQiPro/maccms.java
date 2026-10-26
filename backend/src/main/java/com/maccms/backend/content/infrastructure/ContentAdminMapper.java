package com.maccms.backend.content.infrastructure;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 后台内容管理的只读查询（B13，docs/architecture/22-b13-admin-content.md 第 5 节；SQL 见 {@code resources/mapper/content/ContentAdminMapper.xml}）。
 * 每行一律按文字取回全部列（列名 → MySQL 的文字），只供后台的内容服务使用（{@code ModuleBoundaryTest} 锁定其他模块与公开接口不调用）。
 */
@Mapper
public interface ContentAdminMapper {

    Map<String, String> selectVod(@Param("id") long id);

    Map<String, String> selectArt(@Param("id") long id);

    Map<String, String> selectTopic(@Param("id") long id);

    Map<String, String> selectActor(@Param("id") long id);

    Map<String, String> selectRole(@Param("id") long id);

    Map<String, String> selectWebsite(@Param("id") long id);

    Map<String, String> selectLink(@Param("id") long id);

    Map<String, String> selectType(@Param("id") long id);

    Map<String, String> selectAnnex(@Param("id") long id);

    // ---- 友情链接

    long countLinks(@Param("like") String like);

    List<Map<String, String>> listLinks(@Param("like") String like, @Param("offset") long offset, @Param("limit") long limit);

    // ---- 分类

    List<Map<String, String>> listTypes(@Param("topOnly") boolean topOnly);

    List<Map<String, String>> listTypesById();

    List<Map<String, String>> selectTypesByIds(@Param("ids") List<Long> ids);

    List<Map<String, Object>> countVodByType();

    List<Map<String, Object>> countArtByType();

    List<Map<String, Object>> countActorByType();

    List<Map<String, Object>> countWebsiteByType();

    long countVodInType(@Param("id") long id);

    long countArtInType(@Param("id") long id);

    // ---- 专题

    long countTopics(@Param("status") String status, @Param("like") String like);

    List<Map<String, String>> listTopics(@Param("status") String status, @Param("like") String like,
                                         @Param("offset") long offset, @Param("limit") long limit);

    List<Map<String, String>> selectTopicsByIds(@Param("ids") List<Long> ids);

    // ---- 网址、文章、演员、角色（列表、查重、updateToday）

    long countWebsites(@Param("c") AdminCriteria c);

    List<Map<String, String>> listWebsites(@Param("c") AdminCriteria c, @Param("offset") long offset, @Param("limit") long limit);

    List<Map<String, Object>> websiteRepeatGroups(@Param("offset") long offset, @Param("limit") long limit);

    List<Long> websiteToday(@Param("since") long since, @Param("type") boolean type);

    long countArts(@Param("c") AdminCriteria c);

    List<Map<String, String>> listArts(@Param("c") AdminCriteria c, @Param("offset") long offset, @Param("limit") long limit);

    List<Map<String, Object>> artRepeatGroups(@Param("offset") long offset, @Param("limit") long limit);

    List<Long> artToday(@Param("since") long since, @Param("type") boolean type);

    long countActors(@Param("c") AdminCriteria c);

    List<Map<String, String>> listActors(@Param("c") AdminCriteria c, @Param("offset") long offset, @Param("limit") long limit);

    long countRoles(@Param("c") AdminCriteria c);

    List<Map<String, String>> listRoles(@Param("c") AdminCriteria c, @Param("offset") long offset, @Param("limit") long limit);

    List<Map<String, String>> selectArtsByIds(@Param("ids") List<Long> ids);

    List<Map<String, String>> selectActorsByIds(@Param("ids") List<Long> ids);

    List<Map<String, String>> selectWebsitesByIds(@Param("ids") List<Long> ids);

    List<Map<String, String>> vodNamesByIds(@Param("ids") List<Long> ids);

    long countVods(@Param("c") AdminCriteria c);

    List<Map<String, String>> listVods(@Param("c") AdminCriteria c, @Param("offset") long offset, @Param("limit") long limit);

    List<Map<String, Object>> vodRepeatGroups(@Param("offset") long offset, @Param("limit") long limit);

    List<Long> vodToday(@Param("since") long since, @Param("type") boolean type);

    List<Map<String, String>> selectVodsByIds(@Param("ids") List<Long> ids);
}
