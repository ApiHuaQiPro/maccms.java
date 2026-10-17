package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.PageResponse;
import com.maccms.backend.content.Fixtures;
import com.maccms.backend.content.application.view.VideoSummaryDto;
import com.maccms.backend.content.infrastructure.GroupMapper;
import com.maccms.backend.content.infrastructure.TypeMapper;
import com.maccms.backend.content.infrastructure.VodFilterCriteria;
import com.maccms.backend.content.infrastructure.VodIdRange;
import com.maccms.backend.content.infrastructure.VodMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 随机排序（契约 1.3.0 sort=rnd，旧 application/common/model/Vod.php:500-537）的每个分支，用可控制的随机源断言
 * 取了哪一页、候选段是什么、调用了哪些语句；Mapper 为替身。与旧版的 ID 序列对照见 VideoListGoldenMySqlIT。
 */
class VideoRandomListTest {

    /** 标准分类树中游客可见的全部分类（Fixtures.standardTypes，3 没有列表位）。 */
    private static final Set<Integer> VISIBLE = Set.of(1, 6, 7, 2, 13);

    private TypeMapper typeMapper;
    private GroupMapper groupMapper;
    private VodMapper vodMapper;
    private FixedRandom random;

    /** 记录每次调用的参数，返回预先给定的结果。 */
    static final class FixedRandom extends VideoRandom {
        final List<Long> pageMax = new ArrayList<>();
        final List<long[]> sampleArgs = new ArrayList<>();
        long page = 1;
        long[] sample;

        @Override
        public long pageBetweenOneAnd(long max) {
            pageMax.add(max);
            assertThat(page).isBetween(1L, max);
            return page;
        }

        @Override
        public long[] sampleIndexes(long n, int k) {
            sampleArgs.add(new long[]{n, k});
            return sample;
        }
    }

    @BeforeEach
    void setUp() {
        typeMapper = mock(TypeMapper.class);
        groupMapper = mock(GroupMapper.class);
        vodMapper = mock(VodMapper.class);
        when(typeMapper.selectEnabledVodTypes()).thenReturn(Fixtures.standardTypes());
        when(groupMapper.selectGroupPermission(1)).thenReturn(Fixtures.guestGroup());
        random = new FixedRandom();
    }

    private ContentReadService service(boolean searchEnabled) {
        ContentProperties props = new ContentProperties(true, true,
                new ContentProperties.Search(String.valueOf(searchEnabled), "1"));
        return new ContentReadService(typeMapper, groupMapper, vodMapper, props, 10, random);
    }

    private static VideoListQuery rnd(String page, String pageSize) {
        return VideoListQuery.parse("", "rnd", "asc", page, pageSize);
    }

    private static List<Long> ids(PageResponse<VideoSummaryDto> r) {
        return r.items().stream().map(VideoSummaryDto::id).toList();
    }

    // ------------------------------------------------------------------ 算法一：c <= 2000，随机页

    @Test
    void smallResultPicksARandomPageAndIgnoresTheRequestedPage() {
        when(vodMapper.countPublished(VISIBLE)).thenReturn(45L);
        when(vodMapper.selectPublishedPage(VISIBLE, VideoSort.HITS_WEEK, true, 20L, 20))
                .thenReturn(List.of(Fixtures.summary(9, 1), Fixtures.summary(8, 6)));
        random.page = 2;
        PageResponse<VideoSummaryDto> r = service(false).listVideos(rnd("7", "20"));
        assertThat(random.pageMax).as("随机页范围 1..floor(45/20)+1").containsExactly(3L);
        assertThat(r.page()).as("响应的 page 是实际取的随机页").isEqualTo(2);
        assertThat(r.total()).isEqualTo(45);
        assertThat(r.totalPages()).isEqualTo(3);
        assertThat(ids(r)).containsExactly(9L, 8L);
        verify(vodMapper, never()).selectIdRange();
        assertThat(random.sampleArgs).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            // count, pageSize, 随机页上限
            "0, 20, 1",
            "1, 20, 1",
            "19, 20, 1",
            "20, 20, 2",
            "21, 20, 2",
            "40, 20, 3",
            "2000, 20, 101",
            "2000, 1, 2001",
            "2000, 100, 21",
    })
    void randomPageUpperBoundIsCountDividedByPageSizePlusOne(long count, int pageSize, long expectedMax) {
        when(vodMapper.countPublished(VISIBLE)).thenReturn(count);
        random.page = expectedMax;
        PageResponse<VideoSummaryDto> r = service(false).listVideos(rnd("1", String.valueOf(pageSize)));
        assertThat(random.pageMax).containsExactly(expectedMax);
        assertThat(r.page()).isEqualTo((int) expectedMax);
        assertThat(r.total()).isEqualTo(count);
        verify(vodMapper, never()).selectIdRange();
        if ((expectedMax - 1) * pageSize >= count) {
            // 最后一个随机页在 count 恰为页大小整数倍（含 0）时为空，与旧版相同；不发分页语句
            assertThat(r.items()).isEmpty();
            verify(vodMapper, never()).selectPublishedPage(any(), any(), anyBoolean(), anyLong(), anyInt());
        } else {
            verify(vodMapper).selectPublishedPage(VISIBLE, VideoSort.HITS_WEEK, true, (expectedMax - 1) * pageSize,
                    pageSize);
        }
    }

    @Test
    void exactlyTwoThousandStaysOnTheRandomPageAlgorithm() {
        when(vodMapper.countPublished(VISIBLE)).thenReturn(2000L);
        random.page = 1;
        service(false).listVideos(rnd("1", "20"));
        verify(vodMapper, never()).selectIdRange();
        verify(vodMapper).selectPublishedPage(VISIBLE, VideoSort.HITS_WEEK, true, 0L, 20);
    }

    @Test
    void filtersAreKeptOnTheRandomPage() {
        VideoListQuery q = rnd("1", "20").withFilter(VideoFilter.parse(p -> VideoFilter.RawParam.of(
                Map.of("letter", "A", "year", "2018-2019").get(p)), false));
        VodFilterCriteria c = ContentReadService.criteria(q.filter());
        when(vodMapper.countPublishedMatching(VISIBLE, c)).thenReturn(30L);
        random.page = 2;
        PageResponse<VideoSummaryDto> r = service(false).listVideos(q);
        assertThat(r.page()).isEqualTo(2);
        verify(vodMapper).selectPublishedMatchingPage(VISIBLE, c, VideoSort.HITS_WEEK, true, 20L, 20);
        verify(vodMapper, never()).countPublished(any());
    }

    // ------------------------------------------------------------------ 算法二：c > 2000，随机 ID 段

    @Test
    void largeResultAddsOneThousandCandidateIdsFromTheKeyRangeAndUsesTheRequestedPage() {
        when(vodMapper.countPublished(VISIBLE)).thenReturn(2001L);
        when(vodMapper.selectIdRange()).thenReturn(new VodIdRange(1L, 3001L));
        random.sample = LongStream.range(0, 1000).map(i -> i * 3).toArray();
        List<Long> candidates = Arrays.stream(random.sample).boxed().toList();
        VodFilterCriteria c = VodFilterCriteria.candidatesOnly(candidates);
        when(vodMapper.countPublishedMatching(VISIBLE, c)).thenReturn(37L);
        when(vodMapper.selectPublishedMatchingPage(VISIBLE, c, VideoSort.HITS_WEEK, true, 20L, 20))
                .thenReturn(List.of(Fixtures.summary(300, 1)));
        PageResponse<VideoSummaryDto> r = service(false).listVideos(rnd("2", "20"));
        assertThat(random.sampleArgs).hasSize(1);
        assertThat(random.sampleArgs.get(0)).as("从 0..max-min（共 3001 个下标）中取 1000 个").containsExactly(3001L, 1000L);
        assertThat(random.pageMax).isEmpty();
        assertThat(r.page()).as("算法二使用请求的 page").isEqualTo(2);
        assertThat(r.total()).as("total 是加上候选段后的计数").isEqualTo(37);
        assertThat(ids(r)).containsExactly(300L);
    }

    @Test
    void candidatesAreAndedWithTheOtherConditions() {
        VideoListQuery q = rnd("1", "10").withFilter(VideoFilter.parse(p -> VideoFilter.RawParam.of(
                Map.of("ids", "5,6", "state", "正片").get(p)), false));
        VodFilterCriteria base = ContentReadService.criteria(q.filter());
        when(vodMapper.countPublishedMatching(VISIBLE, base)).thenReturn(5000L);
        when(vodMapper.selectIdRange()).thenReturn(new VodIdRange(10L, 20000L));
        random.sample = LongStream.range(0, 1000).toArray();
        VodFilterCriteria withCandidates = base.withCandidates(Arrays.stream(random.sample).boxed().toList());
        when(vodMapper.countPublishedMatching(VISIBLE, withCandidates)).thenReturn(1L);
        when(vodMapper.selectPublishedMatchingPage(VISIBLE, withCandidates, VideoSort.HITS_WEEK, true, 0L, 10))
                .thenReturn(List.of(Fixtures.summary(6, 1)));
        PageResponse<VideoSummaryDto> r = service(false).listVideos(q);
        assertThat(random.sampleArgs.get(0)).containsExactly(19991L, 1000L);
        assertThat(withCandidates.ids()).as("ids 与候选段同时生效").containsExactly(5L, 6L);
        assertThat(ids(r)).containsExactly(6L);
        assertThat(r.total()).isEqualTo(1);
    }

    @Test
    void sparseOrHighIdsCanLeaveNoCandidateMatchingWhichIsAnEmptyPage() {
        // ID 从 100001 开始：候选下标 0..2000 全部小于最小 ID，一个都命中不了（旧版 array_rand 取键的结果）
        when(vodMapper.countPublished(VISIBLE)).thenReturn(2001L);
        when(vodMapper.selectIdRange()).thenReturn(new VodIdRange(100001L, 102001L));
        random.sample = LongStream.range(0, 1000).map(i -> i * 2).toArray();
        VodFilterCriteria c = VodFilterCriteria.candidatesOnly(Arrays.stream(random.sample).boxed().toList());
        when(vodMapper.countPublishedMatching(VISIBLE, c)).thenReturn(0L);
        PageResponse<VideoSummaryDto> r = service(false).listVideos(rnd("3", "20"));
        assertThat(random.sampleArgs.get(0)).containsExactly(2001L, 1000L);
        assertThat(r.items()).isEmpty();
        assertThat(r.total()).isZero();
        assertThat(r.page()).isEqualTo(3);
        verify(vodMapper, never()).selectPublishedMatchingPage(any(), any(), any(), anyBoolean(), anyLong(), anyInt());
    }

    @Test
    void requestedPageBeyondTheCandidateTotalIsEmpty() {
        when(vodMapper.countPublished(VISIBLE)).thenReturn(9999L);
        when(vodMapper.selectIdRange()).thenReturn(new VodIdRange(1L, 9999L));
        random.sample = LongStream.range(0, 1000).toArray();
        when(vodMapper.countPublishedMatching(any(), any())).thenReturn(30L);
        PageResponse<VideoSummaryDto> r = service(false).listVideos(rnd("3", "15"));
        assertThat(r.items()).isEmpty();
        assertThat(r.total()).isEqualTo(30);
        assertThat(r.page()).isEqualTo(3);
        verify(vodMapper, never()).selectPublishedMatchingPage(any(), any(), any(), anyBoolean(), anyLong(), anyInt());
    }

    @ParameterizedTest
    @CsvSource(value = {"null, null", "0, 5000", "5000, 5000", "6000, 5000", "null, 5000", "1, null"}, nullValues = "null")
    void invalidIdRangeFallsBackToTheRandomPageAlgorithm(Long min, Long max) {
        // 旧 Vod.php:510-516：id_min/id_max 为空（含 0）或 max <= min 时不取 ID 段，按随机页处理
        when(vodMapper.countPublished(VISIBLE)).thenReturn(4000L);
        when(vodMapper.selectIdRange()).thenReturn(new VodIdRange(min, max));
        random.page = 150;
        PageResponse<VideoSummaryDto> r = service(false).listVideos(rnd("1", "20"));
        assertThat(random.sampleArgs).isEmpty();
        assertThat(random.pageMax).containsExactly(201L);
        assertThat(r.page()).isEqualTo(150);
        assertThat(r.total()).isEqualTo(4000);
        verify(vodMapper).selectPublishedPage(VISIBLE, VideoSort.HITS_WEEK, true, 2980L, 20);
    }

    @Test
    void nullIdRangeRowAlsoFallsBack() {
        when(vodMapper.countPublished(VISIBLE)).thenReturn(4000L);
        when(vodMapper.selectIdRange()).thenReturn(null);
        random.page = 1;
        service(false).listVideos(rnd("1", "20"));
        assertThat(random.sampleArgs).isEmpty();
        verify(vodMapper).selectPublishedPage(VISIBLE, VideoSort.HITS_WEEK, true, 0L, 20);
    }

    // ------------------------------------------------------------------ 与可见性、开关、并发许可的关系

    @Test
    void invisibleCategoryIs404BeforeAnyCount() {
        VideoListQuery q = VideoListQuery.parse("3", "rnd", "", "", "");
        assertThatThrownBy(() -> service(false).listVideos(q)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.CATEGORY_NOT_FOUND));
        verify(vodMapper, never()).countPublished(any());
    }

    @Test
    void randomWithContainsMatchNeedsTheSwitchAndReleasesThePermit() {
        VideoFilter actor = VideoFilter.parse(p -> VideoFilter.RawParam.of(Map.of("actor", "甲").get(p)), true);
        VideoListQuery q = rnd("1", "20").withFilter(actor);
        assertThatThrownBy(() -> service(false).listVideos(q)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.fieldErrors().get(0).field()).isEqualTo("actor"));

        ContentReadService enabled = service(true);
        when(vodMapper.countPublishedMatching(any(), any())).thenReturn(0L);
        enabled.listVideos(q);
        // 并发上限为 1：上一次的许可已归还，第二次仍能进入
        enabled.listVideos(q);
        assertThat(enabled.tryAcquireSearchPermit()).isTrue();
        enabled.releaseSearchPermit();
    }

    @Test
    void permitIsReleasedWhenTheRandomQueryFails() {
        ContentReadService enabled = service(true);
        VideoListQuery q = rnd("1", "20").withFilter(VideoFilter.parse(p -> VideoFilter.RawParam.of(
                Map.of("tag", "x").get(p)), true));
        when(vodMapper.countPublishedMatching(any(), any())).thenReturn(5000L);
        when(vodMapper.selectIdRange()).thenThrow(new IllegalStateException("合成故障"));
        assertThatThrownBy(() -> enabled.listVideos(q)).isInstanceOf(IllegalStateException.class);
        assertThat(enabled.tryAcquireSearchPermit()).as("异常时许可也已归还").isTrue();
        enabled.releaseSearchPermit();
    }

    // ------------------------------------------------------------------ 参数

    @Test
    void sortRndIsParsedIntoTheRandomFlagWithHitsWeekDescending() {
        VideoListQuery q = VideoListQuery.parse("", "rnd", "asc", "5", "10");
        assertThat(q.random()).isTrue();
        assertThat(q.sort()).isEqualTo(VideoSort.HITS_WEEK);
        assertThat(q.descending()).as("order 被忽略，固定倒序").isTrue();
        assertThat(q.page()).isEqualTo(5);
        assertThat(VideoListQuery.parse("", "hits_week", "", "", "").random()).isFalse();
        assertThat(q.withFilter(VideoFilter.NONE).random()).as("withFilter 保留随机标记").isTrue();
        // order 仍按原规则校验
        assertThatThrownBy(() -> VideoListQuery.parse("", "rnd", "up", "", "")).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.fieldErrors().get(0).field()).isEqualTo("order"));
        // 构造器拒绝不一致的随机查询
        assertThatThrownBy(() -> new VideoListQuery(null, VideoSort.TIME, true, 1, 20, VideoFilter.NONE, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VideoListQuery(null, VideoSort.HITS_WEEK, false, 1, 20, VideoFilter.NONE, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
