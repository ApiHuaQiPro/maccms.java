package com.maccms.backend.content.infrastructure;

import com.maccms.backend.common.write.WriteMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 后台内容管理的写入（B13，docs/architecture/22-b13-admin-content.md 第 6 节；WriteRegistry 登记 content 对
 * mac_vod/art/topic/actor/role/website/link/type/annex 的 INSERT、UPDATE、DELETE）。SQL 见
 * {@code resources/mapper-write/content/ContentAdminWriteMapper.xml}：每张表一条新增、一条按主键整行覆盖（只写参数里有的列，列名写死在
 * XML 中）与一条按主键列表删除；其余是各功能的条件更新。旧表是 MyISAM，没有事务。
 */
@WriteMapper
public interface ContentAdminWriteMapper {

    int insertVod(AdminInsert row);

    int updateVod(@Param("c") Map<String, String> c, @Param("id") long id);

    int updateVodByIds(@Param("c") Map<String, String> c, @Param("ids") List<Long> ids);

    int deleteVodByIds(@Param("ids") List<Long> ids);

    int insertArt(AdminInsert row);

    int updateArt(@Param("c") Map<String, String> c, @Param("id") long id);

    int updateArtByIds(@Param("c") Map<String, String> c, @Param("ids") List<Long> ids);

    int deleteArtByIds(@Param("ids") List<Long> ids);

    int insertTopic(AdminInsert row);

    int updateTopic(@Param("c") Map<String, String> c, @Param("id") long id);

    int updateTopicByIds(@Param("c") Map<String, String> c, @Param("ids") List<Long> ids);

    int deleteTopicByIds(@Param("ids") List<Long> ids);

    int insertActor(AdminInsert row);

    int updateActor(@Param("c") Map<String, String> c, @Param("id") long id);

    int updateActorByIds(@Param("c") Map<String, String> c, @Param("ids") List<Long> ids);

    int deleteActorByIds(@Param("ids") List<Long> ids);

    int insertRole(AdminInsert row);

    int updateRole(@Param("c") Map<String, String> c, @Param("id") long id);

    int updateRoleByIds(@Param("c") Map<String, String> c, @Param("ids") List<Long> ids);

    int deleteRoleByIds(@Param("ids") List<Long> ids);

    int insertWebsite(AdminInsert row);

    int updateWebsite(@Param("c") Map<String, String> c, @Param("id") long id);

    int updateWebsiteByIds(@Param("c") Map<String, String> c, @Param("ids") List<Long> ids);

    int deleteWebsiteByIds(@Param("ids") List<Long> ids);

    int insertLink(AdminInsert row);

    int updateLink(@Param("c") Map<String, String> c, @Param("id") long id);

    int updateLinkByIds(@Param("c") Map<String, String> c, @Param("ids") List<Long> ids);

    int deleteLinkByIds(@Param("ids") List<Long> ids);

    int insertType(AdminInsert row);

    int updateType(@Param("c") Map<String, String> c, @Param("id") long id);

    int updateTypeByIds(@Param("c") Map<String, String> c, @Param("ids") List<Long> ids);

    int deleteTypeByIds(@Param("ids") List<Long> ids);

    int insertAnnex(AdminInsert row);

    int updateAnnex(@Param("c") Map<String, String> c, @Param("id") long id);

    int updateAnnexByIds(@Param("c") Map<String, String> c, @Param("ids") List<Long> ids);

    int deleteAnnexByIds(@Param("ids") List<Long> ids);

    // ---- 分类转移（参数是旧版原样的取值，由 MySQL 转换）

    int moveVodType(@Param("to") String to, @Param("toPid") String toPid, @Param("from") long from);

    int moveArtType(@Param("to") String to, @Param("toPid") String toPid, @Param("from") long from);

    // ---- 查重删除（保留最小编号）

    int deleteWebsiteRepeat(@Param("name") String name, @Param("keep") long keep);

    int deleteArtRepeat(@Param("name") String name, @Param("keep") long keep);

    int deleteVodRepeat(@Param("name") String name, @Param("keep") long keep);

    /** 附件登记：同名文件未登记时插入，返回插入行数（0 或 1）。 */
    int registerAnnex(@Param("c") Map<String, String> c);

    int deleteAnnexByFile(@Param("file") String file);
}
