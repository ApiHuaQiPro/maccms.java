package com.maccms.backend.site.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.LegacyColumnSet;
import com.maccms.backend.content.application.LegacyRowOrder;
import com.maccms.backend.content.application.LegacyRowQuery;
import com.maccms.backend.content.application.LegacyRowReadService;
import com.maccms.backend.content.application.LegacyTable;
import com.maccms.backend.content.application.view.LegacyRowDto;
import com.maccms.backend.content.application.view.LegacyRowPageDto;
import com.maccms.backend.site.config.SiteConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 联想词打开态（B06，16 文档第 4.9 节）：maccms.site.search.enabled 与包含匹配开关都打开、旧 app.search=1（没有旧配置时按仓库值）。
 * 部署在子目录 {@code /sub/} 时（应用仍挂在根路径，子目录由反向代理映射）生成的地址带前缀。
 */
@WebMvcTest(controllers = SiteAjaxController.class, properties = {
        "maccms.site.enabled=true",
        "maccms.site.search.enabled=true",
        "maccms.site.name=合成站",
        "maccms.site.base-url=http://maccms.local.test/sub/"})
@Import(SiteConfiguration.class)
class SiteAjaxSuggestMvcTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    LegacyRowReadService rows;
    @MockitoBean
    ContentReadService content;

    @BeforeEach
    void setUp() {
        when(content.searchEnabled()).thenReturn(true);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", 1L);
        m.put("name", "星际穿越");
        m.put("en", "xingji");
        m.put("pic", "upload/vod/1.jpg");
        when(rows.read(any())).thenReturn(new LegacyRowPageDto(3, List.of(new LegacyRowDto(m))));
    }

    private MvcResult fetch(String path) throws Exception {
        return mvc.perform(get(URI.create(path))).andReturn();
    }

    private LegacyRowQuery lastQuery() {
        ArgumentCaptor<LegacyRowQuery> q = ArgumentCaptor.forClass(LegacyRowQuery.class);
        verify(rows, Mockito.atLeastOnce()).read(q.capture());
        Mockito.clearInvocations(rows);
        return q.getValue();
    }

    @Test
    void suggestQueriesNameOrEnglishNameAndKeepsTheLegacyShape() throws Exception {
        MvcResult r = fetch("/index.php/ajax/suggest?mid=1&wd=%E6%98%9F&limit=2");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("{\"code\":1,\"msg\":\"数据列表\","
                + "\"page\":1,\"pagecount\":2,\"limit\":2,\"total\":3,\"list\":[{\"id\":1,\"name\":\"星际穿越\",\"en\":\"xingji\","
                + "\"pic\":\"\\/sub\\/upload\\/vod\\/1.jpg\"}],\"url\":\"\\/sub\\/index.php\\/vod\\/search.html?wd=mac_wd\"}");
        LegacyRowQuery q = lastQuery();
        assertThat(q.table()).isEqualTo(LegacyTable.VOD);
        assertThat(q.columns()).isEqualTo(LegacyColumnSet.SUGGEST);
        assertThat(q.order()).isEqualTo(LegacyRowOrder.ID_DESC);
        assertThat(q.nameOrEnContains()).isEqualTo("星");
        assertThat(q.limit()).isEqualTo(2);
    }

    @Test
    void limitsAndParameters() throws Exception {
        fetch("/index.php/ajax/suggest?mid=1&wd=a&limit=0");
        assertThat(lastQuery().limit()).isEqualTo(20);
        fetch("/index.php/ajax/suggest?mid=1&wd=a&limit=100000");
        assertThat(lastQuery().limit()).as("新版上限 50").isEqualTo(50);
        // htmlspecialchars(urldecode(trim)) 后匹配（旧版语义）
        fetch("/index.php/ajax/suggest?mid=2&wd=%2520a%26b%2520");
        LegacyRowQuery q = lastQuery();
        assertThat(q.table()).isEqualTo(LegacyTable.ART);
        assertThat(q.nameOrEnContains()).isEqualTo(" a&amp;b ");
        assertThat(fetch("/index.php/ajax/suggest?mid=11&wd=a").getResponse().getContentAsString(StandardCharsets.UTF_8))
                .endsWith("\"url\":\"\\/sub\\/index.php\\/website\\/search.html?wd=mac_wd\"}");
        Mockito.clearInvocations(rows);
        for (String bad : List.of("mid=1", "mid=4&wd=a", "mid=1&wd=", "mid=1&wd=" + "a".repeat(65), "mid=1&wd=%250A")) {
            assertThat(fetch("/index.php/ajax/suggest?" + bad).getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .as(bad).isEqualTo("{\"code\":1001,\"msg\":\"参数错误\"}");
        }
        verify(rows, never()).read(any());
    }

    @Test
    void topicAndRoleQuirks() throws Exception {
        fetch("/index.php/ajax/suggest?mid=3&wd=a");
        assertThat(lastQuery().columns()).as("旧 Topic::listData 不用 field，返回整行").isEqualTo(LegacyColumnSet.FULL);
        String role = fetch("/index.php/ajax/suggest?mid=9&wd=a").getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(role).contains("\"pic\":\"\\/sub\\/upload\\/vod\\/1.jpg\",\"data\":null}");
    }

    @Test
    void overloadIs503Json() throws Exception {
        when(rows.read(any())).thenThrow(new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "服务暂时不可用"));
        MvcResult r = fetch("/index.php/ajax/suggest?mid=1&wd=a");
        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":503,\"msg\":\"服务暂时不可用\"}");
        assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo("5");
    }
}
