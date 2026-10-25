package com.maccms.backend.content.infrastructure;

import com.maccms.backend.common.write.WriteMapper;
import org.apache.ibatis.annotations.Param;

/**
 * 内容计数的写入（B11，docs/architecture/20-b11-comment-gbook-counters.md 第 5 节）。SQL 见
 * {@code resources/mapper-write/content/ContentCounterWriteMapper.xml}；只能经 {@code WriteDatabase.mapper()} 取得。
 * 全部是按主键（来路为按网址前缀）的单表 UPDATE，数值变化一律原子表达式（旧版先读后写，并发时会丢计数）；返回匹配行数。
 * 列值越界（{@code mediumint unsigned}、{@code decimal(3,1)}）时 MySQL 严格模式拒绝整条语句，调用方按"保存失败"处理。
 */
@WriteMapper
public interface ContentCounterWriteMapper {

    int hitVod(@Param("id") long id, @Param("p") HitPeriods periods);

    int hitArt(@Param("id") long id, @Param("p") HitPeriods periods);

    int hitTopic(@Param("id") long id, @Param("p") HitPeriods periods);

    int hitActor(@Param("id") long id, @Param("p") HitPeriods periods);

    int hitRole(@Param("id") long id, @Param("p") HitPeriods periods);

    int hitWebsite(@Param("id") long id, @Param("p") HitPeriods periods);

    /** @param up true 为顶（{@code *_up + 1}），false 为踩（{@code *_down + 1}） */
    int diggVod(@Param("id") long id, @Param("up") boolean up);

    int diggArt(@Param("id") long id, @Param("up") boolean up);

    int diggTopic(@Param("id") long id, @Param("up") boolean up);

    int diggActor(@Param("id") long id, @Param("up") boolean up);

    int diggRole(@Param("id") long id, @Param("up") boolean up);

    int diggWebsite(@Param("id") long id, @Param("up") boolean up);

    int scoreVod(@Param("id") long id, @Param("score") long score);

    int scoreArt(@Param("id") long id, @Param("score") long score);

    int scoreTopic(@Param("id") long id, @Param("score") long score);

    int scoreActor(@Param("id") long id, @Param("score") long score);

    int scoreRole(@Param("id") long id, @Param("score") long score);

    int scoreWebsite(@Param("id") long id, @Param("score") long score);

    /** 来路计数：匹配前缀的全部网址各自 {@code +1}（旧版把第一行算出的值写到所有匹配行，见 20 文档第 9 节）。 */
    int refererWebsite(@Param("httpPattern") String httpPattern, @Param("httpsPattern") String httpsPattern,
                       @Param("p") HitPeriods periods);

    /** Provide {@code detail_inc_hits}（api/controller/Provide.php:142-150）：只加总点击数。 */
    int provideHitVod(@Param("id") long id);
}
