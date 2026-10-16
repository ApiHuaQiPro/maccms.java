package com.maccms.backend.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.contract.OpenApiContract;
import com.maccms.backend.content.infrastructure.GroupMapper;
import com.maccms.backend.content.infrastructure.TypeMapper;
import com.maccms.backend.content.infrastructure.VodMapper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 真实 Tomcat 下的参数解码（MockMvc 不经过容器的查询串解析）：Tomcat 11 遇到任一参数的百分号编码不是合法 UTF-8 时
 * 拒绝解析整个查询串。契约 1.1.0 要求 {@code keyword=%FF} 等新参数得到 400 且 field 为该参数；既有参数、未知参数
 * 的非法编码维持 1.0.0 的行为（500）。也锁定既有参数重复时的行为与新参数的重复检测。
 * Mapper 为合成替身，数据源指向不可达的合成地址，不连接任何数据库。
 */
@SpringBootTest(classes = MaccmsBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:1/synthetic_never_connects",
                "spring.datasource.username=synthetic_ro",
                "spring.datasource.password=synthetic",
                "maccms.content.search.enabled=true"})
@ExtendWith(OutputCaptureExtension.class)
class ContentSearchRealHttpTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static OpenApiContract contract;

    @LocalServerPort
    int port;

    @MockitoBean
    TypeMapper typeMapper;
    @MockitoBean
    GroupMapper groupMapper;
    @MockitoBean
    VodMapper vodMapper;

    @BeforeAll
    static void loadContract() {
        contract = OpenApiContract.load(OpenApiContract.CONTENT_READ);
    }

    @BeforeEach
    void data() {
        when(typeMapper.selectEnabledVodTypes()).thenReturn(Fixtures.standardTypes());
        when(groupMapper.selectGroupPermission(1)).thenReturn(Fixtures.guestGroup());
        when(vodMapper.countPublished(any())).thenReturn(1L);
        when(vodMapper.selectPublishedPage(any(), any(), anyBoolean(), anyLong(), anyInt()))
                .thenReturn(List.of(Fixtures.summary(1, 1)));
        when(vodMapper.countPublishedMatching(any(), any())).thenReturn(1L);
        when(vodMapper.selectPublishedMatchingPage(any(), any(), any(), anyBoolean(), anyLong(), anyInt()))
                .thenReturn(List.of(Fixtures.summary(1, 1)));
    }

    private record Resp(int status, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }

    /** 原始请求行原样发送（不做任何编码），读取到连接关闭；只支持本应用的 Content-Length 响应。 */
    private Resp raw(String target) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(10_000);
            OutputStream out = s.getOutputStream();
            out.write(("GET " + target + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            in.transferTo(b);
            String text = b.toString(StandardCharsets.UTF_8);
            int status = Integer.parseInt(text.substring(9, 12));
            int headerEnd = text.indexOf("\r\n\r\n");
            String body = text.substring(headerEnd + 4);
            if (text.substring(0, headerEnd).toLowerCase().contains("transfer-encoding: chunked")) {
                body = dechunk(body);
            }
            return new Resp(status, body);
        }
    }

    /** 分块长度按字节计，这里的响应体先按 UTF-8 字节处理。 */
    private static String dechunk(String chunked) {
        byte[] in = chunked.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (true) {
            int lineEnd = i;
            while (in[lineEnd] != '\r') {
                lineEnd++;
            }
            int size = Integer.parseInt(new String(in, i, lineEnd - i, StandardCharsets.ISO_8859_1).trim(), 16);
            if (size == 0) {
                return out.toString(StandardCharsets.UTF_8);
            }
            out.write(in, lineEnd + 2, size);
            i = lineEnd + 2 + size + 2;
        }
    }

    private JsonNode checked(String query, int status) throws Exception {
        Resp r = raw("/api/v1/videos?" + query);
        assertThat(r.status()).as(query + " → " + r.body()).isEqualTo(status);
        JsonNode body = r.json();
        contract.assertMatches(body, contract.responseSchema("/api/v1/videos", "get", status));
        return body;
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "keyword=%FF            | keyword | 含不允许的字符",
            "keyword=%              | keyword | 含不允许的字符",
            "keyword=%2             | keyword | 含不允许的字符",
            "keyword=%E4%B8         | keyword | 含不允许的字符",
            "keyword=a%FFb&area=x   | keyword | 含不允许的字符",
            "keyword=%EF%BF%BD      | keyword | 含不允许的字符",
            "area=%FF               | area    | 含不允许的字符",
            "lang=%C0%AF            | lang    | 含不允许的字符",
            "classes=%ED%A0%80      | classes | 含不允许的字符",
            "year=%FF               | year    | 必须是 4 位数字年份",
            "level=%FF              | level   | 必须是 1 到 9 之间的整数",
            "keyword=%FF&keyword=x  | keyword | 参数不能重复",
            "area=%E5%A4%A7&area=%FF | area   | 参数不能重复",
            "page=0&keyword=%FF     | page    | 必须是 1 到 1000 之间的整数",
            "keyword=x&area=%FF     | area    | 含不允许的字符",
    })
    void malformedEncodingInNewParametersIs400ForThatParameter(String query, String field, String message)
            throws Exception {
        JsonNode body = checked(query, 400);
        assertThat(body.get("code").asString()).isEqualTo("INVALID_PARAMETER");
        assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo(field);
        assertThat(body.get("fieldErrors").get(0).get("message").asString()).isEqualTo(message);
    }

    @ParameterizedTest
    @CsvSource({"page=%FF", "foo=%FF", "foo=%FF&keyword=x", "%FF=1", "k%FFeyword=x"})
    void malformedEncodingElsewhereKeepsContract100Behaviour(String query, CapturedOutput output) throws Exception {
        Resp r = raw("/api/v1/videos?" + query);
        assertThat(r.status()).isEqualTo(500);
        assertThat(r.json().get("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(output.getAll()).contains("unhandled exception: org.apache.tomcat.util.http.InvalidParameterException");
    }

    @Test
    void validEncodingIsDecodedExactlyOnce() throws Exception {
        // %2541 → "%41"（不再解码成 A）；+ → 空格；%2B → +
        checked("keyword=%2541", 200);
        checked("keyword=C%2B%2B", 200);
        checked("keyword=a+b", 200);
        checked("keyword=%E5%90%88%E6%88%90&area=%E5%A4%A7%E9%99%86", 200);
        org.mockito.ArgumentCaptor<com.maccms.backend.content.infrastructure.VodFilterCriteria> c =
                org.mockito.ArgumentCaptor.forClass(com.maccms.backend.content.infrastructure.VodFilterCriteria.class);
        org.mockito.Mockito.verify(vodMapper, org.mockito.Mockito.times(4)).countPublishedMatching(any(), c.capture());
        assertThat(c.getAllValues()).extracting(x -> x.keywordPatterns().get(0))
                .containsExactly("%!%41%", "%C++%", "%a b%", "%合成%");
        assertThat(c.getAllValues().get(3).area()).isEqualTo("大陆");
    }

    @ParameterizedTest
    @CsvSource({"page=1&page=2, page", "sort=time&sort=time, sort", "order=&order=, order",
            "categoryId=&categoryId=, categoryId", "pageSize=20&pageSize=, pageSize",
            "area=&area=, area", "level=1&level=1, level", "keyword=a&keyword=a, keyword"})
    void repeatedParametersOverRealTomcat(String query, String field) throws Exception {
        JsonNode body = checked(query, 400);
        assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo(field);
    }

    @Test
    void unknownAndLegacyParametersAreIgnored() throws Exception {
        String plain = raw("/api/v1/videos").body();
        assertThat(raw("/api/v1/videos?wd=x&class=y&tid=2&by=hits&utm_source=z").body()).isEqualTo(plain);
    }
}
