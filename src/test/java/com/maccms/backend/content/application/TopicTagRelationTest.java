package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.Fixtures;
import com.maccms.backend.content.application.view.TopicDetailDto;
import com.maccms.backend.content.infrastructure.ArtMapper;
import com.maccms.backend.content.infrastructure.GroupMapper;
import com.maccms.backend.content.infrastructure.TopicMapper;
import com.maccms.backend.content.infrastructure.TypeMapper;
import com.maccms.backend.content.infrastructure.VodMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 专题详情"按标签关联内容"（旧 Topic::infoData 的 topic_tag 分支，application/common/model/Topic.php:314-334）：
 * 只在搜索开关打开时执行，模式按 mac_like_arr 逐项构造并按字面转义，拿不到并发许可时 503，结果拼在按 ID 关联的结果之后。
 */
class TopicTagRelationTest {

    private final TopicMapper topicMapper = mock(TopicMapper.class);
    private final VodMapper vodMapper = mock(VodMapper.class);
    private final ArtMapper artMapper = mock(ArtMapper.class);
    private final TypeMapper typeMapper = mock(TypeMapper.class);
    private final GroupMapper groupMapper = mock(GroupMapper.class);

    @BeforeEach
    void data() {
        when(typeMapper.selectEnabledVodTypes()).thenReturn(Fixtures.standardTypes());
        when(typeMapper.selectEnabledTypes(2)).thenReturn(LibraryFixtures.articleTypes());
        when(groupMapper.selectGroupPermission(1)).thenReturn(LibraryFixtures.guestGroup());
    }

    private TopicReadService service(boolean searchEnabled, String maxConcurrent) {
        ContentProperties props = new ContentProperties(true, true,
                new ContentProperties.Search(Boolean.toString(searchEnabled), maxConcurrent));
        ContentReadService videos = new ContentReadService(typeMapper, groupMapper, vodMapper, props);
        return new TopicReadService(topicMapper, vodMapper, artMapper, new ContentCatalogs(typeMapper, groupMapper, props),
                videos);
    }

    @Test
    void tagMatchesAreAppendedAfterIdMatchesWhenSearchIsEnabled() {
        when(topicMapper.selectPublishedDetail(1)).thenReturn(LibraryFixtures.topicDetail(1, "a%b,,c_d", "5", ""));
        when(vodMapper.selectPublishedByIds(any(), any())).thenReturn(List.of(Fixtures.summary(5, 1)));
        when(vodMapper.selectPublishedByTagPatterns(any(), any()))
                .thenReturn(List.of(Fixtures.summary(5, 1), Fixtures.summary(8, 6)));
        when(artMapper.selectPublishedByTagPatterns(any(), any())).thenReturn(List.of(LibraryFixtures.artSummary(4, 21)));

        TopicDetailDto dto = service(true, null).getTopic(1);

        // 与旧版相同：两部分直接拼接，重复项保留
        assertThat(dto.relatedVideos()).extracting(v -> v.id()).containsExactly(5L, 5L, 8L);
        assertThat(dto.relatedArticles()).extracting(a -> a.id()).containsExactly(4L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> patterns = ArgumentCaptor.forClass(Collection.class);
        verify(vodMapper).selectPublishedByTagPatterns(any(), patterns.capture());
        // mac_like_arr：逗号拆分保留空项（'%%' 匹配所有行，与旧版相同）；% _ ! 按字面转义
        assertThat(patterns.getValue()).containsExactly("%a!%b%", "%%", "%c!_d%");
        verify(artMapper, never()).selectPublishedByIds(any(), any());
    }

    @Test
    void emptyOrZeroTagSkipsTagMatching() {
        when(topicMapper.selectPublishedDetail(1)).thenReturn(LibraryFixtures.topicDetail(1, "0", "", ""));
        TopicDetailDto dto = service(true, null).getTopic(1);
        assertThat(dto.relatedVideos()).isEmpty();
        verify(vodMapper, never()).selectPublishedByTagPatterns(any(), any());
        verify(vodMapper, never()).selectPublishedByIds(any(), any());
    }

    @Test
    void tagMatchingIsSkippedWhenSearchIsDisabled() {
        when(topicMapper.selectPublishedDetail(1)).thenReturn(LibraryFixtures.topicDetail(1, "a", "", ""));
        service(false, null).getTopic(1);
        verify(vodMapper, never()).selectPublishedByTagPatterns(any(), any());
        verify(artMapper, never()).selectPublishedByTagPatterns(any(), any());
    }

    @Test
    void exhaustedSearchPermitsGive503AndReleaseNothingExtra() throws Exception {
        when(topicMapper.selectPublishedDetail(1)).thenReturn(LibraryFixtures.topicDetail(1, "a", "", ""));
        // 唯一的许可被一个阻塞中的查询占用
        TopicReadService svc = service(true, "1");
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(vodMapper.selectPublishedByTagPatterns(any(), any())).thenAnswer(inv -> {
            entered.countDown();
            release.await();
            return List.of();
        });
        List<Throwable> errors = new ArrayList<>();
        Thread t = new Thread(() -> {
            try {
                svc.getTopic(1);
            } catch (Throwable e) {
                errors.add(e);
            }
        });
        t.start();
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> svc.getTopic(1)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        release.countDown();
        t.join(5000);
        assertThat(errors).isEmpty();
        // 许可已归还：再次请求可以进入
        when(vodMapper.selectPublishedByTagPatterns(any(), any())).thenReturn(List.of());
        svc.getTopic(1);
    }

    @Test
    void relatedIdParsingFollowsLegacyIntegerBinding() {
        assertThat(TopicReadService.parseIds(" 3,abc,5x,0,3,,4294967295,4294967296,\t7")).containsExactly(3L, 5L,
                4294967295L, 7L);
        assertThat(TopicReadService.parseIds("")).isEmpty();
        assertThat(TopicReadService.parseIds(null)).isEmpty();
    }
}
