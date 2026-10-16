package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.Fixtures;
import com.maccms.backend.content.application.ContentProperties.Search;
import com.maccms.backend.content.infrastructure.GroupMapper;
import com.maccms.backend.content.infrastructure.TypeMapper;
import com.maccms.backend.content.infrastructure.VodMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** maccms.content.search.* 的默认值与校验（09 方案第 6.4 节）：非法值启动失败，只列配置项名、不回显取值。 */
@ExtendWith(OutputCaptureExtension.class)
class SearchSettingsTest {

    @Test
    void defaultsAreDisabledWithFourPermits() {
        assertThat(SearchSettings.from(null, 10)).isEqualTo(new SearchSettings(false, 4));
        assertThat(SearchSettings.from(new Search(null, null), 10)).isEqualTo(SearchSettings.DISABLED);
        assertThat(SearchSettings.from(new Search("", ""), 10)).isEqualTo(SearchSettings.DISABLED);
        assertThat(new ContentProperties(true, true).search()).isEqualTo(new Search(null, null));
    }

    @Test
    void acceptsValidValues() {
        assertThat(SearchSettings.from(new Search("true", "1"), 10)).isEqualTo(new SearchSettings(true, 1));
        assertThat(SearchSettings.from(new Search("false", "9"), 10)).isEqualTo(new SearchSettings(false, 9));
        assertThat(SearchSettings.from(new Search("true", "2"), 3)).isEqualTo(new SearchSettings(true, 2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "10", "11", "-1", " 4", "4 ", "four", "99999", "1.5"})
    void rejectsMaxConcurrentOutsideOneToPoolSizeMinusOne(String value) {
        assertThatThrownBy(() -> SearchSettings.from(new Search("true", value), 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("content 搜索配置不合法：maccms.content.search.max-concurrent 必须是 1 到连接池大小减 1"
                        + "（MACCMS_DB_POOL_SIZE − 1）之间的整数");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRUE", "yes", "1", " true"})
    void rejectsNonBooleanEnabled(String value) {
        assertThatThrownBy(() -> SearchSettings.from(new Search(value, null), 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("content 搜索配置不合法：maccms.content.search.enabled 只能是 true 或 false");
    }

    @Test
    void poolOfOneLeavesNoRoomForSearch() {
        assertThatThrownBy(() -> SearchSettings.from(new Search("false", null), 1))
                .hasMessageContaining("maccms.content.search.max-concurrent");
    }

    @Test
    void applicationRefusesToStartWithInvalidSearchConfigurationWithoutEchoingIt(CapturedOutput output) {
        String marker = "SYNTHETIC77";
        assertThatThrownBy(() -> new SpringApplicationBuilder(MaccmsBackendApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=jdbc:mysql://127.0.0.1:1/synthetic_never_connects",
                        "--spring.datasource.username=synthetic_ro", "--spring.datasource.password=synthetic",
                        "--maccms.content.search.enabled=" + marker + "x",
                        "--maccms.content.search.max-concurrent=" + marker).close())
                .hasStackTraceContaining("maccms.content.search.max-concurrent")
                .hasStackTraceContaining("maccms.content.search.enabled")
                .satisfies(e -> assertThat(stackTrace(e)).doesNotContain(marker));
        assertThat(output.getAll()).doesNotContain(marker);
    }

    private static String stackTrace(Throwable e) {
        java.io.StringWriter w = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }

    @Test
    void serviceRejectsContainsMatchWhenDisabledEvenIfQueryWasBuiltDirectly() {
        TypeMapper typeMapper = mock(TypeMapper.class);
        GroupMapper groupMapper = mock(GroupMapper.class);
        VodMapper vodMapper = mock(VodMapper.class);
        when(typeMapper.selectEnabledVodTypes()).thenReturn(Fixtures.standardTypes());
        when(groupMapper.selectGroupPermission(anyInt())).thenReturn(Fixtures.guestGroup());
        ContentReadService service = new ContentReadService(typeMapper, groupMapper, vodMapper,
                new ContentProperties(true, true));
        assertThat(service.searchEnabled()).isFalse();
        VideoListQuery base = new VideoListQuery(null, VideoSort.TIME, true, 1, 20);
        for (VideoFilter f : new VideoFilter[]{new VideoFilter("k", null, null, null, null, null),
                new VideoFilter(null, null, null, null, null, "c")}) {
            assertThatThrownBy(() -> service.listVideos(base.withFilter(f)))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMETER);
                        assertThat(e.fieldErrors().get(0).message()).isEqualTo("当前未开放关键字搜索");
                    });
        }
        verifyNoInteractions(vodMapper);
    }
}
