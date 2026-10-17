package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.application.view.RoleSummaryDto;
import com.maccms.backend.content.infrastructure.GroupMapper;
import com.maccms.backend.content.infrastructure.SortColumn;
import com.maccms.backend.content.infrastructure.TypeMapper;
import com.maccms.backend.content.infrastructure.VodMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * B05（独立验收返工第 2 项）模块筛选/搜索页的随机排序（旧 Art/Topic/Actor/Role/Website::listCacheData 的 by=rnd）：随机页的取值范围
 * 1..floor(c/每页)+1、忽略请求的页、hits_week 倒序取该页、total 为 c；c 为 0 或恰为每页整数倍时的空页。随机源可替换（与视频相同的
 * {@link VideoRandom}）。真实库上与 PHP 黄金样本（mt_srand 固定种子）的 ID 序列对照见 ModuleSearchGoldenMySqlIT。
 */
class LibrarySearchServiceTest {

    /** 记录每次调用的上限，按脚本返回随机页。 */
    static final class ScriptedRandom extends VideoRandom {
        final List<Long> maxima = new ArrayList<>();
        long next;

        @Override
        public long pageBetweenOneAnd(long max) {
            maxima.add(max);
            return next;
        }
    }

    /** 计数固定、按 offset/limit 给出行，并记录取行时的排序。 */
    static final class FakeSlice implements LibrarySlice<RoleSummaryDto> {
        final long count;
        final List<String> calls = new ArrayList<>();

        FakeSlice(long count) {
            this.count = count;
        }

        @Override
        public long count() {
            calls.add("count");
            return count;
        }

        @Override
        public List<RoleSummaryDto> rows(SortColumn sort, boolean descending, long offset, int limit) {
            calls.add(sort + (descending ? " desc " : " asc ") + offset + "+" + limit);
            List<RoleSummaryDto> out = new ArrayList<>();
            for (long i = offset; i < Math.min(count, offset + limit); i++) {
                out.add(mock(RoleSummaryDto.class));
            }
            return out;
        }
    }

    private static LibrarySearchService service(RoleReadService roles, ScriptedRandom random) {
        ContentReadService videos = new ContentReadService(mock(TypeMapper.class), mock(GroupMapper.class),
                mock(VodMapper.class), new ContentProperties(true, true, new ContentProperties.Search("true", null)));
        return new LibrarySearchService(mock(ArticleReadService.class), mock(TopicReadService.class),
                mock(ActorReadService.class), roles, mock(WebsiteReadService.class), videos, random);
    }

    private static PageResponse<RoleSummaryDto> run(long count, long randomPage, int requestedPage, FakeSlice[] out,
                                                    ScriptedRandom random) {
        RoleReadService roles = mock(RoleReadService.class);
        FakeSlice slice = new FakeSlice(count);
        out[0] = slice;
        when(roles.slice(any())).thenReturn(slice);
        random.next = randomPage;
        return service(roles, random).roles(LibraryListQuery.of(ContentKind.ROLE, null, "rnd", null, requestedPage, 20,
                null));
    }

    @Test
    void randomPageIgnoresTheRequestedPageAndSortsByWeeklyHits() {
        ScriptedRandom random = new ScriptedRandom();
        FakeSlice[] slice = new FakeSlice[1];
        PageResponse<RoleSummaryDto> r = run(45, 2, 1, slice, random);
        assertThat(random.maxima).as("floor(45/20)+1").containsExactly(3L);
        assertThat(slice[0].calls).containsExactly("count", "HITS_WEEK desc 20+20");
        assertThat(r.page()).as("响应中的页码是实际取的随机页").isEqualTo(2);
        assertThat(r.total()).isEqualTo(45);
        assertThat(r.items()).hasSize(20);

        r = run(45, 3, 2, slice, random);
        assertThat(slice[0].calls).containsExactly("count", "HITS_WEEK desc 40+20");
        assertThat(r.items()).hasSize(5);
        assertThat(r.page()).isEqualTo(3);
    }

    @Test
    void lastRandomPageIsEmptyWhenTheCountIsAMultipleOfThePageSizeAndZeroCountGivesPageOne() {
        ScriptedRandom random = new ScriptedRandom();
        FakeSlice[] slice = new FakeSlice[1];
        PageResponse<RoleSummaryDto> r = run(40, 3, 1, slice, random);
        assertThat(random.maxima).containsExactly(3L);
        assertThat(slice[0].calls).as("越界不取行").containsExactly("count");
        assertThat(r.items()).isEmpty();
        assertThat(r.total()).isEqualTo(40);
        assertThat(r.page()).isEqualTo(3);

        random.maxima.clear();
        r = run(0, 1, 5, slice, random);
        assertThat(random.maxima).containsExactly(1L);
        assertThat(r.items()).isEmpty();
        assertThat(r.page()).isEqualTo(1);
        assertThat(r.total()).isZero();
    }

    @Test
    void ordinaryPagesUseTheRequestedSortAndPage() {
        RoleReadService roles = mock(RoleReadService.class);
        FakeSlice slice = new FakeSlice(45);
        when(roles.slice(any())).thenReturn(slice);
        ScriptedRandom random = new ScriptedRandom();
        PageResponse<RoleSummaryDto> r = service(roles, random).roles(
                LibraryListQuery.of(ContentKind.ROLE, null, "id", "asc", 3, 20, null));
        assertThat(slice.calls).containsExactly("count", "ID asc 40+20");
        assertThat(r.items()).hasSize(5);
        assertThat(random.maxima).isEmpty();
    }
}
