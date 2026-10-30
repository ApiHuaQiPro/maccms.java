package com.maccms.backend.content.application;

import static com.maccms.backend.content.application.ContentCatalogs.dec;
import static com.maccms.backend.content.application.ContentCatalogs.nz;
import static com.maccms.backend.content.application.ContentCatalogs.str;
import static com.maccms.backend.content.application.ContentReadService.read;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.view.ArticleSummaryDto;
import com.maccms.backend.content.application.view.TopicDetailDto;
import com.maccms.backend.content.application.view.TopicSummaryDto;
import com.maccms.backend.content.application.view.VideoSummaryDto;
import com.maccms.backend.content.infrastructure.ArtMapper;
import com.maccms.backend.content.infrastructure.ContentCriteria;
import com.maccms.backend.content.infrastructure.SortColumn;
import com.maccms.backend.content.infrastructure.TopicDetailRow;
import com.maccms.backend.content.infrastructure.TopicMapper;
import com.maccms.backend.content.infrastructure.TopicSummaryRow;
import com.maccms.backend.content.infrastructure.VodMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 专题只读查询（契约 1.2.0）。专题没有分类与权限位，可见性只有 topic_status=1（Topic.php:147）。
 *
 * <p>详情的关联内容对应旧 Topic::infoData（application/common/model/Topic.php:291-334），依次拼接：
 * ① topic_rel_vod / topic_rel_art 中的 ID（已审核，按更新时间倒序，各最多 999 条）；
 * ② topic_tag 按逗号拆开后在 vod_tag / art_tag 中包含匹配（同样排序与上限）。两部分直接拼接，重复项保留（与旧版相同）。
 * 新增：关联的视频/文章还须所属分类可见；②是全表包含匹配，只在搜索开关 {@code maccms.content.search.enabled} 打开时执行，
 * 并与视频搜索共用并发上限（拿不到许可 → 503），关闭时只返回①。规则见 docs/architecture/11-content-read-completion.md 第 4.4 节。
 */
@Service
public class TopicReadService {

    private static final Logger log = LoggerFactory.getLogger(TopicReadService.class);

    private final TopicMapper topicMapper;
    private final VodMapper vodMapper;
    private final ArtMapper artMapper;
    private final ContentCatalogs catalogs;
    private final ContentReadService videos;

    public TopicReadService(TopicMapper topicMapper, VodMapper vodMapper, ArtMapper artMapper, ContentCatalogs catalogs,
                            ContentReadService videos) {
        this.topicMapper = topicMapper;
        this.vodMapper = vodMapper;
        this.artMapper = artMapper;
        this.catalogs = catalogs;
        this.videos = videos;
    }

    public PageResponse<TopicSummaryDto> listTopics(ContentListQuery query) {
        return read(() -> page(query, query.filter().toCriteria()));
    }

    /** 一页（异常转换由调用方的 read 负责）。 */
    PageResponse<TopicSummaryDto> page(ContentListQuery query, ContentCriteria criteria) {
        if (query.kind() != ContentKind.TOPIC) {
            throw new IllegalArgumentException("kind");
        }
        return LibrarySlice.page(slice(criteria), query.sort(), query.descending(), query.page(), query.pageSize());
    }

    /** 条件下的切片（专题没有分类）；B05 的 LibrarySearchService 以 SSR 内部条件调用。 */
    LibrarySlice<TopicSummaryDto> slice(ContentCriteria criteria) {
        return new LibrarySlice<>() {
            @Override
            public long count() {
                return topicMapper.countPublished(criteria);
            }

            @Override
            public List<TopicSummaryDto> rows(SortColumn sort, boolean descending, long offset, int limit) {
                return topicMapper.selectPublishedPage(criteria, sort, descending, offset, limit).stream()
                        .map(TopicReadService::toSummary).toList();
            }
        };
    }

    public TopicDetailDto getTopic(long id) {
        return read(() -> {
            TopicDetailRow row = topicMapper.selectPublishedDetail(id);
            if (row == null) {
                throw new ApiException(ErrorCode.TOPIC_NOT_FOUND, "专题不存在");
            }
            GroupPermission viewer = catalogs.viewer();
            CategoryCatalog vodCatalog = catalogs.vodCatalog(viewer);
            CategoryCatalog artCatalog = catalogs.catalog(ArticleReadService.TYPE_MID, viewer);
            List<VideoSummaryDto> relatedVideos = new ArrayList<>();
            List<ArticleSummaryDto> relatedArticles = new ArrayList<>();

            Set<Long> vodIds = parseIds(row.relatedVideoIds());
            if (!vodIds.isEmpty() && !vodCatalog.allIds().isEmpty()) {
                vodMapper.selectPublishedByIds(vodCatalog.allIds(), vodIds)
                        .forEach(r -> relatedVideos.add(ContentReadService.toSummary(r, vodCatalog)));
            }
            Set<Long> artIds = parseIds(row.relatedArticleIds());
            if (!artIds.isEmpty() && !artCatalog.allIds().isEmpty()) {
                artMapper.selectPublishedByIds(artCatalog.allIds(), artIds)
                        .forEach(r -> relatedArticles.add(ArticleReadService.toSummary(r, artCatalog)));
            }
            // 旧版 if(!empty($info['topic_tag']))：""、"0" 都不匹配
            if (!LegacyMultiValue.empty(row.tag()) && videos.searchEnabled()) {
                List<String> patterns = tagPatterns(row.tag());
                if (!videos.tryAcquireSearchPermit()) {
                    log.warn("content search rejected: concurrency limit reached");
                    throw ContentReadService.unavailable();
                }
                try {
                    if (!vodCatalog.allIds().isEmpty()) {
                        vodMapper.selectPublishedByTagPatterns(vodCatalog.allIds(), patterns)
                                .forEach(r -> relatedVideos.add(ContentReadService.toSummary(r, vodCatalog)));
                    }
                    if (!artCatalog.allIds().isEmpty()) {
                        artMapper.selectPublishedByTagPatterns(artCatalog.allIds(), patterns)
                                .forEach(r -> relatedArticles.add(ArticleReadService.toSummary(r, artCatalog)));
                    }
                } finally {
                    videos.releaseSearchPermit();
                }
            }
            return toDetail(row, relatedVideos, relatedArticles);
        });
    }

    /**
     * topic_rel_vod / topic_rel_art 的 ID 列表：旧版把原文交给 {@code IN}，ThinkPHP 按逗号拆开并以整数类型绑定
     * （thinkphp/library/think/db/Builder.php:404-425）。这里按同样的逗号拆分，每项跳过前导空白后取前导数字，
     * 在 1..4294967295 内的才保留（取不到数字的项在旧版中也不会命中任何行）。
     */
    static Set<Long> parseIds(String raw) {
        Set<Long> ids = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) {
            return ids;
        }
        for (String part : LegacyMultiValue.explode(",", raw)) {
            int i = 0;
            while (i < part.length() && (part.charAt(i) == ' ' || part.charAt(i) == '\t' || part.charAt(i) == '\n'
                    || part.charAt(i) == '\r')) {
                i++;
            }
            int start = i;
            while (i < part.length() && part.charAt(i) >= '0' && part.charAt(i) <= '9' && i - start < 11) {
                i++;
            }
            if (i == start) {
                continue;
            }
            long v = Long.parseLong(part.substring(start, i));
            if (v >= 1 && v <= VideoId.MAX) {
                ids.add(v);
            }
        }
        return ids;
    }

    /** mac_like_arr（application/common.php:1284-1292）：按逗号拆开，每项 '%值%'；新版按字面匹配（{@code ! % _} 以 '!' 转义）。 */
    static List<String> tagPatterns(String tag) {
        return LegacyMultiValue.explode(",", tag).stream().map(VideoFilter::contains).distinct().toList();
    }

    private static TopicSummaryDto toSummary(TopicSummaryRow r) {
        return new TopicSummaryDto(nz(r.id()), str(r.name()), str(r.subTitle()), str(r.enName()), str(r.letter()),
                str(r.color()), nz(r.sort()), str(r.classes()), str(r.tag()), str(r.pic()), str(r.picThumb()),
                str(r.picSlide()), str(r.title()), str(r.keywords()), str(r.description()), str(r.blurb()),
                str(r.remarks()), nz(r.level()), dec(r.score()), nz(r.scoreNum()), nz(r.hits()), nz(r.hitsDay()),
                nz(r.hitsWeek()), nz(r.hitsMonth()), nz(r.up()), nz(r.down()), nz(r.updatedAt()), nz(r.addedAt()));
    }

    private static TopicDetailDto toDetail(TopicDetailRow r, List<VideoSummaryDto> relatedVideos,
                                           List<ArticleSummaryDto> relatedArticles) {
        return new TopicDetailDto(nz(r.id()), str(r.name()), str(r.subTitle()), str(r.enName()), str(r.letter()),
                str(r.color()), nz(r.sort()), str(r.classes()), str(r.tag()), str(r.pic()), str(r.picThumb()),
                str(r.picSlide()), str(r.title()), str(r.keywords()), str(r.description()), str(r.blurb()),
                str(r.remarks()), nz(r.level()), dec(r.score()), nz(r.scoreAll()), nz(r.scoreNum()), nz(r.hits()),
                nz(r.hitsDay()), nz(r.hitsWeek()), nz(r.hitsMonth()), nz(r.up()), nz(r.down()), nz(r.updatedAt()),
                nz(r.addedAt()), str(r.content()), List.copyOf(relatedVideos), List.copyOf(relatedArticles));
    }
}
