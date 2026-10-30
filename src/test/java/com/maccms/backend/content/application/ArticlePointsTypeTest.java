package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.infrastructure.ArtMapper;
import com.maccms.backend.content.infrastructure.GroupMapper;
import com.maccms.backend.content.infrastructure.GroupPermissionRow;
import com.maccms.backend.content.infrastructure.TypeMapper;
import com.maccms.backend.content.infrastructure.TypeRow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * B05：文章积分按旧配置 user.art_points_type 取列（index/controller/Base.php:108-111）——'1' 只看 art_points（整篇收费），
 * 否则只看 art_points_detail（按页收费）；判定顺序 404 → 积分 → 密码不变，错误码沿用 1.2.0，响应不含积分或密码。
 */
class ArticlePointsTypeTest {

    private static ArticleReadService service(String artPointsType, boolean enforce) {
        TypeMapper types = mock(TypeMapper.class);
        GroupMapper groups = mock(GroupMapper.class);
        ArtMapper arts = mock(ArtMapper.class);
        when(types.selectEnabledTypes(2)).thenReturn(List.of(new TypeRow(21, "资讯", "zixun", 1, 0, "", "", "", "", "",
                "")));
        when(groups.selectGroupPermission(1)).thenReturn(new GroupPermissionRow(1, ",21,",
                "{\"21\":{\"1\":\"1\",\"2\":\"2\"}}"));
        for (int points = 0; points <= 1; points++) {
            for (int detail = 0; detail <= 1; detail++) {
                for (int pwd = 0; pwd <= 1; pwd++) {
                    long id = 100 + points * 4 + detail * 2 + pwd;
                    when(arts.selectPublishedDetail(id)).thenReturn(LibraryFixtures.artDetail(id, 21, pwd, points,
                            detail));
                }
            }
        }
        ContentProperties props = new ContentProperties(enforce, true, null, artPointsType);
        return new ArticleReadService(arts, new ContentCatalogs(types, groups, props));
    }

    private static long id(int points, int detail, int pwd) {
        return 100 + points * 4 + detail * 2 + pwd;
    }

    private static ErrorCode outcome(ArticleReadService s, long id) {
        try {
            s.getArticle(id);
            return null;
        } catch (ApiException e) {
            return e.code();
        }
    }

    @ParameterizedTest(name = "art_points_type={0} art_points>0={1} art_points_detail>0={2} 有密码={3} → {4}")
    @CsvSource({
        // 类型 1：只看 art_points
        "1,0,0,0,OK", "1,0,1,0,OK", "1,1,0,0,POINTS", "1,1,1,0,POINTS",
        "1,0,0,1,PASSWORD", "1,0,1,1,PASSWORD", "1,1,0,1,POINTS", "1,1,1,1,POINTS",
        // 类型 0：只看 art_points_detail
        "0,0,0,0,OK", "0,1,0,0,OK", "0,0,1,0,POINTS", "0,1,1,0,POINTS",
        "0,0,0,1,PASSWORD", "0,1,0,1,PASSWORD", "0,0,1,1,POINTS", "0,1,1,1,POINTS",
    })
    void pointsColumnFollowsArtPointsTypeAndPrecedesPassword(String type, int points, int detail, int pwd,
                                                            String expected) {
        ErrorCode code = outcome(service(type, true), id(points, detail, pwd));
        switch (expected) {
            case "OK" -> assertThat(code).isNull();
            case "POINTS" -> assertThat(code).isEqualTo(ErrorCode.ARTICLE_POINTS_REQUIRED);
            default -> assertThat(code).isEqualTo(ErrorCode.ARTICLE_PASSWORD_REQUIRED);
        }
    }

    @Test
    void userStatusOffSkipsPointsButNotPassword() {
        // user.status=0（Base.php:115-117）：不检查权限与积分；密码页仍在 Art::detail 中判断
        ArticleReadService s = service("1", false);
        assertThat(outcome(s, id(1, 1, 0))).isNull();
        assertThat(outcome(s, id(1, 1, 1))).isEqualTo(ErrorCode.ARTICLE_PASSWORD_REQUIRED);
    }

    @Test
    void missingArticleIsNotFoundBeforeAnyPointsCheck() {
        assertThat(outcome(service("0", true), 999)).isEqualTo(ErrorCode.ARTICLE_NOT_FOUND);
    }

    @Test
    void defaultIsRepositoryValueOne() {
        assertThat(new ContentProperties(true, true).artPointsWholeArticle()).isTrue();
        assertThat(new ContentProperties(true, true, null, " ").artPointsWholeArticle()).isTrue();
        assertThat(new ContentProperties(true, true, null, "0").artPointsWholeArticle()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", "01", "true", "1.0", "-1", "x"})
    void otherValuesFailWithoutEchoingTheValue(String v) {
        assertThatThrownBy(() -> new ContentProperties(true, true, null, v))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("maccms.content.art-points-type（0 或 1）");
    }
}
