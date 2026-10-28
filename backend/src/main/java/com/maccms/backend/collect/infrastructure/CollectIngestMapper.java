package com.maccms.backend.collect.infrastructure;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 采集入库的查重（B19，27 文档第 4 节；SQL 见 {@code resources/mapper/collect/CollectIngestMapper.xml}）。行按文字取回。
 */
@Mapper
public interface CollectIngestMapper {

    /**
     * 旧 {@code model('Vod')->where($where)->find()}：不排序取第一行。参数为 null 的条件不参与。
     *
     * @param actorLikes {@code vod_actor LIKE} 的模式，任一匹配（不转义通配符，同旧版）
     * @param blend      为真时主演与导演组成 {@code (vod_director = ? OR 主演条件)}；主演条件在 {@code blendIds} 非空时为
     *                   {@code vod_id IN (...)}
     */
    Map<String, String> findVod(@Param("name") String name, @Param("typeId") String typeId, @Param("year") String year,
                                @Param("area") String area, @Param("lang") String lang, @Param("actorLikes") List<String> actorLikes,
                                @Param("director") String director, @Param("blend") boolean blend,
                                @Param("blendIds") List<Long> blendIds);

    /** 文章查重：名称相等，{@code typeId} 非 null 时另按分类（inrule b）。 */
    Map<String, String> findArt(@Param("name") String name, @Param("typeId") String typeId);

    /** 演员查重：名称相等，另按性别（inrule b）、分类（inrule c）。 */
    Map<String, String> findActor(@Param("name") String name, @Param("sex") String sex, @Param("typeId") String typeId);

    /** 网址查重：名称相等，另按分类（inrule b）。 */
    Map<String, String> findWebsite(@Param("name") String name, @Param("typeId") String typeId);

    /** 角色查重：角色名、演员名、关联视频编号都相等。 */
    Map<String, String> findRole(@Param("name") String name, @Param("actor") String actor, @Param("rid") String rid);

    /** 评论查重：关联编号相等，另按内容（inrule b）、昵称（inrule c）。 */
    Map<String, String> findComment(@Param("rid") String rid, @Param("content") String content, @Param("name") String name);

    /**
     * 角色的关联视频编号（旧 {@code role_data} 的 {@code $where2}）：{@code doubanId} 非 null 时按豆瓣编号，否则按视频名称；主演、导演的
     * LIKE 条件非 null 时参与，两者都参与时组成 {@code (导演条件 OR 主演条件)}。没有时 null。
     */
    String findRoleVod(@Param("doubanId") String doubanId, @Param("vodName") String vodName,
                       @Param("actorLikes") List<String> actorLikes, @Param("directorLikes") List<String> directorLikes);

    /** 评论的关联数据编号（{@code mid} 只能是 1、2、3、8、9、11；1 时 {@code doubanId} 非 null 按豆瓣编号）。没有时 null。 */
    String findRelId(@Param("mid") int mid, @Param("doubanId") String doubanId, @Param("name") String name);
}
