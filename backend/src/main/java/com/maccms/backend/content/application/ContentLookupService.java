package com.maccms.backend.content.application;

import static com.maccms.backend.content.application.ContentCatalogs.nz;
import static com.maccms.backend.content.application.ContentCatalogs.str;
import static com.maccms.backend.content.application.ContentReadService.read;

import com.maccms.backend.content.application.view.ArticlePoints;
import com.maccms.backend.content.application.view.CategoryNode;
import com.maccms.backend.content.application.view.ContentTitle;
import com.maccms.backend.content.application.view.VideoPoints;
import com.maccms.backend.content.infrastructure.ArtPointsRow;
import com.maccms.backend.content.infrastructure.ContentLookupMapper;
import com.maccms.backend.content.infrastructure.ContentTitleRow;
import com.maccms.backend.content.infrastructure.TypeNodeRow;
import com.maccms.backend.content.infrastructure.VodPointsRow;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 会员权益（B10，docs/architecture/19-b10-member-finance.md 第 4.1、4.2 节）按编号查找内容的积分与名称。与旧 {@code infoData} 相同
 * <b>不看审核状态与分类可见性</b>：购买、记录与会员自己的记录列表都是按编号的操作，旧版对未审核的内容同样照常处理。公开 API 与页面不用它。
 * 只调用 Mapper 的 select；数据库故障归为 503（{@link ContentReadService#read}）。
 */
@Service
public class ContentLookupService {

    /** 视频 1、文章 2、专题 3、演员 8（旧 {@code ulog_mid}）。 */
    public static final Set<Integer> TITLE_MIDS = Set.of(1, 2, 3, 8);

    private final ContentLookupMapper mapper;
    private final ContentProperties properties;
    private final PlaybackSettings playback;

    public ContentLookupService(ContentLookupMapper mapper, ContentProperties properties, VideoPlaybackService videos) {
        this.mapper = mapper;
        this.properties = properties;
        this.playback = videos.settings();
    }

    /** {@code user.vod_points_type == '1'}：视频按整片收费（vod_points），购买记录的 sid、nid 为 0。 */
    public boolean videoPointsWhole() {
        return playback.pointsWholeVideo();
    }

    /** {@code user.art_points_type == '1'}：文章按整篇收费（art_points），购买记录的 sid、nid 为 0。 */
    public boolean articlePointsWhole() {
        return properties.artPointsWholeArticle();
    }

    public Optional<VideoPoints> videoPoints(long id) {
        if (id < 1) {
            return Optional.empty();
        }
        return read(() -> {
            VodPointsRow r = mapper.selectVodPoints(id);
            return r == null ? Optional.empty()
                    : Optional.of(new VideoPoints(r.id(), nz(r.whole()), nz(r.play()), nz(r.down())));
        });
    }

    public Optional<ArticlePoints> articlePoints(long id) {
        if (id < 1) {
            return Optional.empty();
        }
        return read(() -> {
            ArtPointsRow r = mapper.selectArtPoints(id);
            return r == null ? Optional.empty() : Optional.of(new ArticlePoints(r.id(), nz(r.whole()), nz(r.detail())));
        });
    }

    /**
     * 全部分类（含停用）的两级树（旧 {@code mac_list_to_tree}：顶级为 type_pid=0，其下为直接子分类；上级不存在的分类不出现），按 type_id。
     */
    public List<CategoryNode> typeTree() {
        return read(() -> {
            List<TypeNodeRow> rows = mapper.selectAllTypes();
            Map<Integer, List<CategoryNode>> children = new LinkedHashMap<>();
            for (TypeNodeRow r : rows) {
                if (nz(r.parentId()) != 0) {
                    children.computeIfAbsent(r.parentId(), k -> new ArrayList<>())
                            .add(new CategoryNode(nz(r.id()), str(r.name()), nz(r.mid()), List.of()));
                }
            }
            List<CategoryNode> roots = new ArrayList<>();
            for (TypeNodeRow r : rows) {
                if (nz(r.parentId()) == 0) {
                    roots.add(new CategoryNode(nz(r.id()), str(r.name()), nz(r.mid()),
                            children.getOrDefault(r.id(), List.of())));
                }
            }
            return roots;
        });
    }

    /**
     * 编号 → 内容；查不到的编号不在结果里。
     *
     * @param mid {@link #TITLE_MIDS} 之一，其他值得到空结果（旧版对其他模型不给 data）
     * @param ids 最多 {@link ContentLookupMapper#MAX_IDS} 个，非正数忽略
     */
    public Map<Long, ContentTitle> titles(int mid, Collection<Long> ids) {
        Set<Long> wanted = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null && id > 0) {
                wanted.add(id);
            }
        }
        if (wanted.size() > ContentLookupMapper.MAX_IDS) {
            throw new IllegalArgumentException("一次最多查找 " + ContentLookupMapper.MAX_IDS + " 个编号");
        }
        if (wanted.isEmpty() || !TITLE_MIDS.contains(mid)) {
            return Map.of();
        }
        return read(() -> {
            List<ContentTitleRow> rows = switch (mid) {
                case 1 -> mapper.selectVodTitles(wanted);
                case 2 -> mapper.selectArtTitles(wanted);
                case 3 -> mapper.selectTopicTitles(wanted);
                default -> mapper.selectActorTitles(wanted);
            };
            Map<Long, ContentTitle> out = new LinkedHashMap<>();
            for (ContentTitleRow r : rows) {
                out.put(r.id(), new ContentTitle(r.id(), str(r.name()), str(r.pic()), nz(r.typeFound()) != 0,
                        nz(r.typeId()), str(r.typeName())));
            }
            return out;
        });
    }
}
