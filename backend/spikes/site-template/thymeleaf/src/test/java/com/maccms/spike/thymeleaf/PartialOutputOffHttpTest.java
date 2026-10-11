package com.maccms.spike.thymeleaf;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.spike.thymeleaf.model.VideoCard;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 只改一个 Boot 配置项：spring.thymeleaf.servlet.produce-partial-output-while-processing=false，
 * 实测标准视图解析路径在大页面渲染失败时的行为。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.thymeleaf.servlet.produce-partial-output-while-processing=false")
class PartialOutputOffHttpTest {

    @LocalServerPort
    int port;

    @Test
    void largePageRenderFailureBecomesComplete500() throws Exception {
        RawHttp.Response r = RawHttp.get(port, "/spike/view/boom-large");
        System.out.println("SPIKE-RESULT thymeleaf(partial-output=false) view/boom-large " + r.summary());

        assertThat(r.status()).isEqualTo(500);
        assertThat(r.complete()).isTrue();
        assertThat(r.body()).contains("data-spike=\"error-page\"")
                .doesNotContain(SyntheticPages.PARTIAL_MARKER)
                .doesNotContain(VideoCard.LEAK_MARKER);
    }

    @Test
    void normalPageStillComplete() throws Exception {
        RawHttp.Response r = RawHttp.get(port, "/spike/view/normal");
        System.out.println("SPIKE-RESULT thymeleaf(partial-output=false) view/normal " + r.summary());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.complete()).isTrue();
        assertThat(r.body().stripTrailing()).endsWith("</html>");
    }
}
