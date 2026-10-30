package com.maccms.backend.content.application.view;

import java.math.BigDecimal;
import java.util.List;

/**
 * 专题公开详情。字段与 OpenAPI schema TopicDetail 一一对应。relatedVideos/relatedArticles 对应旧 Topic::infoData 的
 * vod_list/art_list（application/common/model/Topic.php:291-334），规则见 docs/architecture/11-content-read-completion.md 第 4.4 节。
 */
public record TopicDetailDto(
        int id,
        String name,
        String subTitle,
        String enName,
        String letter,
        String color,
        int sort,
        String classes,
        String tag,
        String pic,
        String picThumb,
        String picSlide,
        String title,
        String keywords,
        String description,
        String blurb,
        String remarks,
        int level,
        BigDecimal score,
        int scoreAll,
        int scoreNum,
        int hits,
        int hitsDay,
        int hitsWeek,
        int hitsMonth,
        int up,
        int down,
        long updatedAt,
        long addedAt,
        String content,
        List<VideoSummaryDto> relatedVideos,
        List<ArticleSummaryDto> relatedArticles) {
}
