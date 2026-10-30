package com.maccms.backend.content.infrastructure;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 会员权益（B10）用的按编号查找，只读。SQL 见 resources/mapper/content/ContentLookupMapper.xml。与旧 {@code Vod/Art::infoData}
 * 相同，<b>不看审核状态</b>：购买、记录与会员自己的记录列表按编号找内容（docs/architecture/19-b10-member-finance.md 第 4.1、4.2 节）。
 * 只读出价格、名称、图片与分类名，密码、播放/下载地址等列不在这里出现（ReadOnlyGuardrailsTest 锁定）。
 */
@Mapper
public interface ContentLookupMapper {

    /** 一次最多查找的编号数。 */
    int MAX_IDS = 100;

    VodPointsRow selectVodPoints(@Param("id") long id);

    ArtPointsRow selectArtPoints(@Param("id") long id);

    /** @param ids 1～{@link #MAX_IDS} 个编号 */
    List<ContentTitleRow> selectVodTitles(@Param("ids") Collection<Long> ids);

    List<ContentTitleRow> selectArtTitles(@Param("ids") Collection<Long> ids);

    List<ContentTitleRow> selectTopicTitles(@Param("ids") Collection<Long> ids);

    List<ContentTitleRow> selectActorTitles(@Param("ids") Collection<Long> ids);

    /** 全部分类（含停用，旧 {@code Type::getCache('type_tree')}），按 type_id；最多 {@link #MAX_TYPES} 个。 */
    List<TypeNodeRow> selectAllTypes();

    int MAX_TYPES = 2000;
}
