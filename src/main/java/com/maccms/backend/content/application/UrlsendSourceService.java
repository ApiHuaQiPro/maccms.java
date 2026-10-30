package com.maccms.backend.content.application;

import com.maccms.backend.content.application.view.UrlsendItem;
import com.maccms.backend.content.infrastructure.UrlsendSourceMapper;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 后台 URL 推送（B28，30 文档第 2 节）的数据来源：旧 {@code Urlsend::data} 里 {@code model($m)->listData($where, '<tab>_id asc', $page, $limit)}
 * 的条数与当前页。只读。
 */
@Service
public class UrlsendSourceService {

    /** 旧 {@code data()} 的 switch 认得的模型。 */
    public static final Set<Integer> MIDS = Set.of(1, 2, 3, 8, 9, 11);
    /** 页码上限（偏移量 {@code limit * (page - 1)} 不溢出）。 */
    public static final long MAX_PAGE = 1_000_000_000L;

    private final UrlsendSourceMapper mapper;

    public UrlsendSourceService(UrlsendSourceMapper mapper) {
        this.mapper = mapper;
    }

    /** 筛选条件，见 {@link UrlsendSourceMapper.Query}。 */
    public record Filter(int mid, Long since, boolean added, List<Long> ids) {
        public Filter {
            ids = ids == null ? null : List.copyOf(ids);
        }
    }

    public long count(Filter f) {
        return MIDS.contains(f.mid()) ? mapper.count(query(f)) : 0;
    }

    /** 第 {@code page} 页（1～{@link #MAX_PAGE}），每页 {@code limit} 条（1 起），按编号升序。 */
    public List<UrlsendItem> page(Filter f, long page, int limit) {
        if (page < 1 || page > MAX_PAGE || limit < 1) {
            throw new IllegalArgumentException("页码或每页条数越界");
        }
        if (!MIDS.contains(f.mid())) {
            return List.of();
        }
        long offset = (long) limit * (page - 1);
        return mapper.select(query(f), offset, limit).stream()
                .map(r -> new UrlsendItem(r.id(), r.name(), r.en(), r.time(), r.typeId())).toList();
    }

    private static UrlsendSourceMapper.Query query(Filter f) {
        return new UrlsendSourceMapper.Query(f.mid(), f.since(), f.added(), f.ids() == null || f.ids().isEmpty() ? null : f.ids());
    }
}
