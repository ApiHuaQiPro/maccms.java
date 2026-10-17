package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.infrastructure.ContentCriteria;
import com.maccms.backend.content.infrastructure.GroupMapper;
import com.maccms.backend.content.infrastructure.TypeMapper;
import com.maccms.backend.content.infrastructure.VodMapper;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * B05（D-B05-5）模块筛选/搜索页的内部条件：LIKE 模式与视频相同（ESCAPE '!'、htmlspecialchars 变体、mac_like_arr 逗号拆分），
 * 网址的 name 为精确 IN，各类型只接受自己的条件；包含匹配与视频列表共用搜索开关与并发许可（ContentReadService.guardedRead）。
 */
class LibraryMatchTest {

    @Test
    void criteriaUseTheSameLikePatternsAsVideos() {
        ContentCriteria base = new ContentFilter(3, "A", null, null, null, null, null, null, null).toCriteria();
        ContentCriteria c = new LibraryMatch("a_b%", "星际,城市", "科幻", "纪实,", "1,,3", -2L).apply(base, ContentKind.ARTICLE);
        assertThat(c.level()).isEqualTo(3);
        assertThat(c.letters()).containsExactly("A");
        assertThat(c.keywordPatterns()).containsExactly("%a!_b!%%");
        assertThat(c.namePatterns()).containsExactly("%星际%", "%城市%");
        assertThat(c.names()).isNull();
        assertThat(c.tagPatterns()).containsExactly("%科幻%");
        assertThat(c.classPatterns()).as("空项与旧 mac_like_arr 相同为 %%").containsExactly("%纪实%", "%%");
        assertThat(c.ids()).as("空项为 0，命中不了任何行").containsExactly(1L, 0L, 3L);
        assertThat(c.tid()).isEqualTo(-2L);
        assertThat(new LibraryMatch("<b>", null, null, null, null, null).apply(ContentCriteria.NONE, ContentKind.TOPIC)
                .keywordPatterns()).containsExactly("%<b>%", "%&lt;b&gt;%");
        // 网址的 name 是精确 IN（Website.php:369-371）
        ContentCriteria w = new LibraryMatch(null, "影视站,资讯站", null, null, null, null)
                .apply(ContentCriteria.NONE, ContentKind.WEBSITE);
        assertThat(w.names()).containsExactly("影视站", "资讯站");
        assertThat(w.namePatterns()).isNull();
        assertThat(LibraryMatch.NONE.apply(ContentCriteria.NONE, ContentKind.ROLE)).isSameAs(ContentCriteria.NONE);
    }

    @Test
    void eachKindAcceptsOnlyItsLegacyConditionsAndValuesFollowTheVideoRules() {
        assertThatThrownBy(() -> new LibraryMatch(null, null, null, null, null, 1L)
                .apply(ContentCriteria.NONE, ContentKind.TOPIC)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LibraryMatch(null, "x", null, null, null, null)
                .apply(ContentCriteria.NONE, ContentKind.ACTOR)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LibraryMatch(null, null, "x", null, null, null)
                .apply(ContentCriteria.NONE, ContentKind.ROLE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LibraryMatch("x".repeat(65), null, null, null, null, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new LibraryMatch(" x", null, null, null, null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new LibraryMatch(null, null, null, null, "1,x", null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new LibraryMatch(null, null, null, null, null, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new LibraryMatch(null, null, null, null, "all", null).apply(ContentCriteria.NONE, ContentKind.ARTICLE)
                .ids()).as("all 为不筛选").isNull();
        assertThat(new LibraryMatch(null, null, null, null, "1", 5L).usesContainsMatch()).isFalse();
        assertThat(new LibraryMatch(null, null, "x", null, null, null).firstContainsMatchParameter()).isEqualTo("tag");
    }

    private static ContentReadService videos(String enabled, String maxConcurrent) {
        return new ContentReadService(mock(TypeMapper.class), mock(GroupMapper.class), mock(VodMapper.class),
                new ContentProperties(true, true, new ContentProperties.Search(enabled, maxConcurrent)));
    }

    @Test
    void containsMatchSharesTheSearchSwitchAndPermitsWithVideos() throws Exception {
        LibraryMatch wd = new LibraryMatch("x", null, null, null, null, null);
        ContentReadService off = videos("false", null);
        assertThatThrownBy(() -> off.guardedRead(wd, () -> "queried")).isInstanceOfSatisfying(ApiException.class,
                e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMETER);
                    assertThat(e.fieldErrors().getFirst().field()).isEqualTo("wd");
                });
        // 精确条件与 ID 不需要开关与许可
        assertThat(off.guardedRead(new LibraryMatch(null, null, null, null, "1,2", null), () -> "ok")).isEqualTo("ok");

        ContentReadService on = videos("true", "1");
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Object> first = new AtomicReference<>();
        Thread t = new Thread(() -> first.set(on.guardedRead(wd, () -> {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "first";
        })));
        t.start();
        inside.await();
        // 许可用尽（与视频搜索共用一个上限）→ 503，不查库
        assertThatThrownBy(() -> on.guardedRead(wd, () -> List.of("second")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        release.countDown();
        t.join();
        assertThat(first.get()).isEqualTo("first");
        assertThat(on.guardedRead(wd, () -> "again")).as("许可已归还").isEqualTo("again");
    }
}
