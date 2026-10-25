package com.maccms.backend.content.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 计数接口的只读查询（B11）：按主键读计数列，不看审核状态与分类（旧 {@code infoData} 只按主键，Ajax.php:166、331、363）；来路接口按
 * {@code website_jumpurl} 前缀找网址（Ajax.php:238-242）。SQL 见 {@code mapper/content/ContentCounterMapper.xml}。
 */
@Mapper
public interface ContentCounterMapper {

    /** @param table {@code CounterTarget} 的名称（VOD、ART、TOPIC、ACTOR、ROLE、WEBSITE），XML 中按固定表名分支 */
    CounterRow selectCounters(@Param("table") String table, @Param("id") long id);

    /** 来路网址：{@code website_jumpurl LIKE http://<domain>% OR https://<domain>%}，按主键取第一行。 */
    CounterRow selectRefererSite(@Param("httpPattern") String httpPattern, @Param("httpsPattern") String httpsPattern);
}
