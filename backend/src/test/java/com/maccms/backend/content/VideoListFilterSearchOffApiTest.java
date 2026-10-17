package com.maccms.backend.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.contract.OpenApiContract;
import com.maccms.backend.content.api.VideoCategoryController;
import com.maccms.backend.content.api.VideoController;
import com.maccms.backend.content.application.ContentProperties;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.VideoPlotReadService;
import com.maccms.backend.content.infrastructure.GroupMapper;
import com.maccms.backend.content.infrastructure.TypeMapper;
import com.maccms.backend.content.infrastructure.VodMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 搜索开关关闭（默认）时契约 1.3.0 新参数的行为：actor/director/tag/name 与 keyword/classes 一样 400 "当前未开放关键字搜索"
 * 且不查库；letter/state/version/ids/年份区间与 sort=rnd 照常执行。
 */
@WebMvcTest(controllers = {VideoController.class, VideoCategoryController.class})
@Import(ContentReadService.class)
@EnableConfigurationProperties(ContentProperties.class)
class VideoListFilterSearchOffApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TypeMapper typeMapper;
    @MockitoBean
    GroupMapper groupMapper;
    @MockitoBean
    VodMapper vodMapper;
    @MockitoBean
    VideoPlotReadService videoPlotReadService;

    @BeforeEach
    void data() {
        when(typeMapper.selectEnabledVodTypes()).thenReturn(Fixtures.standardTypes());
        when(groupMapper.selectGroupPermission(1)).thenReturn(Fixtures.guestGroup());
    }

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, int status)
            throws Exception {
        MvcResult r = mvc.perform(req).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(status);
        JsonNode body = JSON.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        OpenApiContract contract = OpenApiContract.load(OpenApiContract.CONTENT_READ);
        contract.assertMatches(body, contract.responseSchema("/api/v1/videos", "get", status));
        return body;
    }

    @ParameterizedTest
    @ValueSource(strings = {"actor", "director", "tag", "name"})
    void listContainsParametersNeedTheSearchSwitch(String param) throws Exception {
        JsonNode body = call(get("/api/v1/videos").param(param, "x").param("sort", "rnd"), 400);
        assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo(param);
        assertThat(body.get("fieldErrors").get(0).get("message").asString()).isEqualTo("当前未开放关键字搜索");
        verify(typeMapper, never()).selectEnabledVodTypes();
        verify(vodMapper, never()).countPublishedMatching(any(), any());
    }

    @Test
    void exactParametersAndRandomSortWorkWithTheSwitchOff() throws Exception {
        when(vodMapper.countPublishedMatching(any(), any())).thenReturn(0L);
        call(get("/api/v1/videos").param("letter", "0-9").param("state", "正片").param("version", "v")
                .param("ids", "1,2").param("year", "2018-2020").param("sort", "rnd"), 200);
        verify(vodMapper).countPublishedMatching(any(), any());
    }
}
