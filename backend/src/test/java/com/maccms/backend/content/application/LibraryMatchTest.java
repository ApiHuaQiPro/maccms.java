package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.infrastructure.ContentCriteria;
import com.maccms.backend.content.infrastructure.GroupMapper;
import com.maccms.backend.content.infrastructure.SortColumn;
import com.maccms.backend.content.infrastructure.TypeMapper;
import com.maccms.backend.content.infrastructure.VodMapper;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * B05（D-B05-5，独立验收返工第 2 项）模块筛选/搜索页的内部条件与排序：LIKE 模式与视频相同（ESCAPE '!'、htmlspecialchars 变体、
 * mac_like_arr 逗号拆分），网址的 name 为精确 IN；letter/area/lang/starsign/blood 为旧 explode 多值（保留空项、0?9 展开）；level、
 * rid、tid、专题 id 为 intval 后的任意非 0 整数；by 按各模型的旧白名单（含 rnd、演员/网址的 in、网址的 referer*）。各类型只接受自己的
 * 条件；包含匹配与视频列表共用搜索开关与并发许可（ContentReadService.guardedRead）。与旧版的 ID 序列对照见 ModuleSearchGoldenMySqlIT。
 */
class LibraryMatchTest {

    private static LibraryMatch only(String key, String value) {
        return new LibraryMatch(key.equals("wd") ? value : null, key.equals("name") ? value : null,
                key.equals("tag") ? value : null, key.equals("class") ? value : null, key.equals("ids") ? value : null,
                key.equals("tid") ? Long.valueOf(value) : null, key.equals("level") ? Long.valueOf(value) : null,
                key.equals("letter") ? value : null, key.equals("sex") ? value : null, key.equals("area") ? value : null,
                key.equals("lang") ? value : null, key.equals("starsign") ? value : null,
                key.equals("blood") ? value : null, key.equals("rid") ? Long.valueOf(value) : null,
                key.equals("id") ? Long.valueOf(value) : null);
    }

    @Test
    void criteriaUseTheSameLikePatternsAsVideos() {
        ContentCriteria c = new LibraryMatch("a_b%", "星际,城市", "科幻", "纪实,", "1,,3", -2L, 3L, "A", null, null, null,
                null, null, null, null).toCriteria(ContentKind.ARTICLE, false);
        assertThat(c.level()).as("契约 1.2.0 的单值 level 不用").isNull();
        assertThat(c.legacyLevel()).isEqualTo(3L);
        assertThat(c.letters()).containsExactly("A");
        assertThat(c.keywordPatterns()).containsExactly("%a!_b!%%");
        assertThat(c.namePatterns()).containsExactly("%星际%", "%城市%");
        assertThat(c.names()).isNull();
        assertThat(c.tagPatterns()).containsExactly("%科幻%");
        assertThat(c.classPatterns()).as("空项与旧 mac_like_arr 相同为 %%").containsExactly("%纪实%", "%%");
        assertThat(c.ids()).as("空项为 0，命中不了任何行").containsExactly(1L, 0L, 3L);
        assertThat(c.tid()).isEqualTo(-2L);
        assertThat(c.nameOrder()).isNull();
        assertThat(only("wd", "<b>").toCriteria(ContentKind.TOPIC, false).keywordPatterns())
                .containsExactly("%<b>%", "%&lt;b&gt;%");
        // 网址的 name 是精确 IN（Website.php:369-371）；by=in 时另给 FIND_IN_SET 名单（去掉反斜线）
        ContentCriteria w = only("name", "影视站,资\\讯站").toCriteria(ContentKind.WEBSITE, true);
        assertThat(w.names()).containsExactly("影视站", "资\\讯站");
        assertThat(w.namePatterns()).isNull();
        assertThat(w.nameOrder()).isEqualTo("影视站,资讯站");
        assertThat(LibraryMatch.NONE.toCriteria(ContentKind.ROLE, false))
                .isEqualTo(new ContentCriteria(null, null, null, null, null, null, null, null, null));
    }

    @Test
    void multiValueKeysFollowTheLegacyExplode() {
        assertThat(only("letter", "X,C").toCriteria(ContentKind.ARTICLE, false).letters()).containsExactly("X", "C");
        assertThat(only("letter", "C,").toCriteria(ContentKind.ARTICLE, false).letters()).containsExactly("C", "");
        assertThat(only("letter", "0,9").toCriteria(ContentKind.TOPIC, false).letters()).as("0?9 展开")
                .containsExactly("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
        assertThat(only("letter", "X，C").toCriteria(ContentKind.ROLE, false).letters()).as("全角逗号不是分隔符")
                .containsExactly("X，C");
        assertThat(only("area", "日本,香港").toCriteria(ContentKind.ACTOR, false).areas()).containsExactly("日本", "香港");
        assertThat(only("area", "香港,").toCriteria(ContentKind.WEBSITE, false).areas()).containsExactly("香港", "");
        assertThat(only("lang", "粤语,国语").toCriteria(ContentKind.WEBSITE, false).langs()).containsExactly("粤语", "国语");
        assertThat(only("starsign", "白羊座,双子座").toCriteria(ContentKind.ACTOR, false).starSigns())
                .containsExactly("白羊座", "双子座");
        assertThat(only("blood", "A,").toCriteria(ContentKind.ACTOR, false).bloods()).containsExactly("A", "");
        ContentCriteria sex = only("sex", "男,女").toCriteria(ContentKind.ACTOR, false);
        assertThat(sex.sex()).as("sex 为精确比较（旧 eq），逗号不拆").isEqualTo("男,女");
        assertThat(sex.area()).isNull();
        assertThat(only("rid", "-1").toCriteria(ContentKind.ROLE, false).videoId()).as("负数照常比较").isEqualTo(-1L);
        assertThat(only("level", "10").toCriteria(ContentKind.WEBSITE, false).legacyLevel()).isEqualTo(10L);
        // 专题 id：ids 未设置时为唯一一项；ids 设置时被覆盖（Topic.php 的 paging 分支）
        assertThat(only("id", "2").toCriteria(ContentKind.TOPIC, false).ids()).containsExactly(2L);
        assertThat(only("id", "-2").toCriteria(ContentKind.TOPIC, false).ids()).containsExactly(-2L);
        assertThat(new LibraryMatch(null, null, null, null, "3", null, null, null, null, null, null, null, null, null,
                2L).toCriteria(ContentKind.TOPIC, false).ids()).containsExactly(3L);
        assertThat(only("letter", ",".repeat(VideoFilter.MAX_LIST_LENGTH - 1)).toCriteria(ContentKind.ARTICLE, false)
                .letters()).as("码点上限同时限定项数").hasSize(VideoFilter.MAX_LIST_LENGTH);
        assertThatThrownBy(() -> only("letter", "x".repeat(VideoFilter.MAX_LIST_LENGTH + 1)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> only("area", "x".repeat(21))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> only("blood", "A ")).isInstanceOf(ApiException.class);
    }

    @Test
    void eachKindAcceptsOnlyItsLegacyConditionsAndValuesFollowTheVideoRules() {
        assertThatThrownBy(() -> only("tid", "1").toCriteria(ContentKind.TOPIC, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> only("name", "x").toCriteria(ContentKind.ACTOR, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> only("tag", "x").toCriteria(ContentKind.ROLE, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> only("area", "x").toCriteria(ContentKind.ARTICLE, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> only("id", "1").toCriteria(ContentKind.WEBSITE, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> only("wd", "x".repeat(65))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> only("wd", " x")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> only("ids", "1,x")).isInstanceOf(ApiException.class);
        for (String k : List.of("tid", "level", "rid", "id")) {
            assertThatThrownBy(() -> only(k, "0")).as(k).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(only("ids", "all").toCriteria(ContentKind.ARTICLE, false).ids()).as("all 为不筛选").isNull();
        assertThat(new LibraryMatch(null, null, null, null, "1", 5L).usesContainsMatch()).isFalse();
        assertThat(only("letter", "A").usesContainsMatch()).isFalse();
        assertThat(only("tag", "x").firstContainsMatchParameter()).isEqualTo("tag");
    }

    @Test
    void sortFollowsEachModelsLegacyWhitelist() {
        LibraryListQuery t = LibraryListQuery.of(ContentKind.ARTICLE, null, null, null, 1, 20, null);
        assertThat(t.sort()).isEqualTo(SortColumn.TIME);
        assertThat(t.descending()).isTrue();
        LibraryListQuery rnd = LibraryListQuery.of(ContentKind.ROLE, null, "rnd", "asc", 3, 20, null);
        assertThat(rnd.random()).isTrue();
        assertThat(rnd.sort()).isEqualTo(SortColumn.HITS_WEEK);
        assertThat(rnd.descending()).as("旧版固定 hits_week desc").isTrue();
        // 演员 by=in：name 只来自模板，URL 下等同 time（方向照常）
        LibraryListQuery actorIn = LibraryListQuery.of(ContentKind.ACTOR, null, "in", "asc", 1, 20, null);
        assertThat(actorIn.sort()).isEqualTo(SortColumn.TIME);
        assertThat(actorIn.descending()).isFalse();
        // 网址 by=in：有 name 时按名单升序（忽略 order），没有时等同 time
        LibraryListQuery websiteIn = LibraryListQuery.of(ContentKind.WEBSITE, null, "in", "desc", 1, 20,
                only("name", "a,b"));
        assertThat(websiteIn.sort()).isEqualTo(SortColumn.NAME_ORDER);
        assertThat(websiteIn.descending()).isFalse();
        assertThat(websiteIn.criteria().nameOrder()).isEqualTo("a,b");
        assertThat(LibraryListQuery.of(ContentKind.WEBSITE, null, "in", null, 1, 20, null).sort())
                .isEqualTo(SortColumn.TIME);
        assertThat(LibraryListQuery.of(ContentKind.WEBSITE, null, "referer_week", "asc", 1, 20, null).sort())
                .isEqualTo(SortColumn.REFERER_WEEK);
        // 不在该模型白名单中的 by/order：旧版回落 time/desc，新版 400（已登记差异）
        for (String[] bad : new String[][] {{"art", "in", null}, {"art", "referer", null}, {"role", "foo", null},
            {"topic", "time", "up"}}) {
            ContentKind kind = bad[0].equals("art") ? ContentKind.ARTICLE : bad[0].equals("role") ? ContentKind.ROLE
                    : ContentKind.TOPIC;
            assertThatThrownBy(() -> LibraryListQuery.of(kind, null, bad[1], bad[2], 1, 20, null))
                    .as(String.join(",", bad[0], bad[1])).isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMETER));
        }
        assertThat(LibraryListQuery.acceptsSort(ContentKind.ACTOR, "in")).isTrue();
        assertThat(LibraryListQuery.acceptsSort(ContentKind.ROLE, "in")).isFalse();
        assertThat(LibraryListQuery.acceptsSort(ContentKind.TOPIC, "rnd")).isTrue();
        // 公开 API 的排序白名单不含 NAME_ORDER、rnd、in
        assertThat(ContentKind.WEBSITE.sorts()).doesNotContainKeys("in", "rnd").doesNotContainValue(SortColumn.NAME_ORDER);
    }

    private static ContentReadService videos(String enabled, String maxConcurrent) {
        return new ContentReadService(mock(TypeMapper.class), mock(GroupMapper.class), mock(VodMapper.class),
                new ContentProperties(true, true, new ContentProperties.Search(enabled, maxConcurrent)));
    }

    @Test
    void containsMatchSharesTheSearchSwitchAndPermitsWithVideos() throws Exception {
        LibraryMatch wd = only("wd", "x");
        ContentReadService off = videos("false", null);
        assertThatThrownBy(() -> off.guardedRead(wd, () -> "queried")).isInstanceOfSatisfying(ApiException.class,
                e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMETER);
                    assertThat(e.fieldErrors().getFirst().field()).isEqualTo("wd");
                });
        // 精确条件、多值条件与 ID 不需要开关与许可
        assertThat(off.guardedRead(only("ids", "1,2"), () -> "ok")).isEqualTo("ok");
        assertThat(off.guardedRead(only("area", "a,b"), () -> "ok")).isEqualTo("ok");

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
