package com.maccms.backend.content.application;

import com.maccms.backend.content.application.view.StaticSourceItem;
import com.maccms.backend.content.application.view.StaticSourceTopic;
import com.maccms.backend.content.application.view.StaticSourceType;
import com.maccms.backend.content.infrastructure.ContentAdminWrites;
import com.maccms.backend.content.infrastructure.StaticItemRow;
import com.maccms.backend.content.infrastructure.StaticSourceMapper;
import com.maccms.backend.content.infrastructure.StaticTopicRow;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 静态生成（B16，25 文档第 5 节）的数据来源与"已生成时间"回写。读走只读数据源（{@link StaticSourceMapper}）；回写旧
 * {@code Db::name($tab)->where([$tab.'_id'=>['in',$ids]])->update([$tab.'_time_make'=>time()])}（Make.php:546、858）沿用 B13 的
 * {@link ContentAdminWrites#updateByIds}（content / mac_vod、mac_art、mac_topic UPDATE，B11 登记），可写数据源关闭时不回写并如实报告。
 */
@Service
public class StaticSourceService {

    /** 内容页的筛选条件，同 {@link StaticSourceMapper.Filter}。 */
    public record Filter(Long typeId, List<Long> ids, Long since, boolean notMade) {
        public Filter {
            ids = ids == null ? null : List.copyOf(ids);
        }
    }

    private final StaticSourceMapper mapper;
    private final ObjectProvider<ContentAdminWrites> writes;

    public StaticSourceService(StaticSourceMapper mapper, ObjectProvider<ContentAdminWrites> writes) {
        this.mapper = mapper;
        this.writes = writes;
    }

    /** 全部分类（旧 {@code model('Type')->getCache('type_list')}，按编号升序）。 */
    public List<StaticSourceType> types() {
        return mapper.selectTypes().stream().map(t -> new StaticSourceType(t.id(), t.name(), t.en(), t.pid(), t.mid()))
                .toList();
    }

    /** 分类页的条数：{@code type_id|type_id_1 = 分类} 且已审核。 */
    public long countInType(String tab, long typeId) {
        return "art".equals(tab) ? mapper.countArtInType(typeId) : mapper.countVodInType(typeId);
    }

    public long count(String tab, Filter f) {
        StaticSourceMapper.Filter m = map(f);
        return "art".equals(tab) ? mapper.countArt(m) : mapper.countVod(m);
    }

    /** 第 {@code page} 页（从 1 开始），每页 {@code size} 条，按时间倒序。 */
    public List<StaticSourceItem> list(String tab, Filter f, long page, int size) {
        StaticSourceMapper.Filter m = map(f);
        long offset = Math.max(0, page - 1) * size;
        List<StaticItemRow> rows = "art".equals(tab) ? mapper.selectArt(m, offset, size) : mapper.selectVod(m, offset, size);
        return rows.stream().map(r -> new StaticSourceItem(r.id(), r.name(), r.en(), r.time(), r.typeId(), r.playFrom(),
                r.playUrl(), r.downFrom(), r.downUrl(), r.content())).toList();
    }

    /** 旧 {@code updateToday('type')}：时间晚于 {@code since} 的内容所在的分类（去重，保留首次出现的顺序）。 */
    public List<Long> typeIdsSince(String tab, long since) {
        List<Long> ids = "art".equals(tab) ? mapper.selectArtTypesSince(since) : mapper.selectVodTypesSince(since);
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }

    /** 已审核专题（旧 {@code listData(['topic_status'=>1], 'topic_id desc', 1, 999)}）。 */
    public List<StaticSourceTopic> topics() {
        return mapper.selectTopics().stream().map(StaticSourceService::topic).toList();
    }

    /** 已审核的某个专题；没有时为 null。 */
    public StaticSourceTopic topic(long id) {
        StaticTopicRow r = mapper.selectTopic(id);
        return r == null ? null : topic(r);
    }

    public long countTopics() {
        return mapper.countTopics();
    }

    /** 可写数据源是否打开（关闭时不能回写已生成时间）。 */
    public boolean canMarkMade() {
        return writes.getIfAvailable() != null;
    }

    /**
     * 回写已生成时间：{@code <tab>_time_make = now}，{@code tab} 为 vod、art、topic。可写数据源关闭时不写、返回 -1。
     */
    public int markMade(String tab, List<Long> ids, long now) {
        ContentAdminWrites w = writes.getIfAvailable();
        if (w == null || ids.isEmpty()) {
            return w == null ? -1 : 0;
        }
        String table = switch (tab) {
            case "vod" -> "mac_vod";
            case "art" -> "mac_art";
            case "topic" -> "mac_topic";
            default -> throw new IllegalArgumentException("不支持的表");
        };
        return w.updateByIds(table, Map.of(tab + "_time_make", Long.toString(now)), List.copyOf(ids));
    }

    private static StaticSourceTopic topic(StaticTopicRow r) {
        return new StaticSourceTopic(r.id(), r.name(), r.en());
    }

    private static StaticSourceMapper.Filter map(Filter f) {
        return new StaticSourceMapper.Filter(f.typeId(), f.ids() == null || f.ids().isEmpty() ? null : f.ids(), f.since(),
                f.notMade());
    }
}
