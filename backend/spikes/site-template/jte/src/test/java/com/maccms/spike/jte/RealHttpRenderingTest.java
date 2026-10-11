package com.maccms.spike.jte;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.spike.jte.model.VideoCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 真实 Tomcat + 原始套接字：正常请求与渲染异常的真实状态码和完整响应。
 * /spike/view/* 走 jte-spring-boot-starter-4 的 JteView（PrintWriterOutput 直接写响应）。
 * 断言记录的是实测行为；"/view/boom-large" 一项是在刻画风险，不是期望的最终行为。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class RealHttpRenderingTest {

    @LocalServerPort
    int port;

    @Test
    void normalPageIsCompleteOnBothPaths() throws Exception {
        RawHttp.Response view = RawHttp.get(port, "/spike/view/normal");
        RawHttp.Response buffered = RawHttp.get(port, "/spike/buffered/normal");
        System.out.println("SPIKE-RESULT jte view/normal " + view.summary());
        System.out.println("SPIKE-RESULT jte buffered/normal " + buffered.summary());

        for (RawHttp.Response r : new RawHttp.Response[]{view, buffered}) {
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.complete()).isTrue();
            assertThat(r.headers().get("content-type")).isEqualToIgnoringCase("text/html;charset=UTF-8");
            assertThat(r.body().stripTrailing()).endsWith("</html>");
        }
        assertThat(buffered.headers()).containsKey("content-length");
        assertThat(buffered.body()).isEqualTo(view.body());
    }

    @Test
    void renderFailureBeforeBufferIsFlushedBecomes500ErrorPage(CapturedOutput output) throws Exception {
        RawHttp.Response r = RawHttp.get(port, "/spike/view/boom-small");
        System.out.println("SPIKE-RESULT jte view/boom-small " + r.summary());

        assertThat(r.status()).isEqualTo(500);
        assertThat(r.complete()).isTrue();
        assertThat(r.body()).contains("data-spike=\"error-page\"")
                .doesNotContain(SyntheticPages.PARTIAL_MARKER)
                .doesNotContain(VideoCard.LEAK_MARKER);
        System.out.println("SPIKE-RESULT jte view/boom-small leakMarkerInLog="
                + output.getAll().contains(VideoCard.LEAK_MARKER));
    }

    @Test
    void renderFailureAfterResponseCommittedLeaves200WithTruncatedPage(CapturedOutput output) throws Exception {
        RawHttp.Response r = RawHttp.get(port, "/spike/view/boom-large");
        System.out.println("SPIKE-RESULT jte view/boom-large " + r.summary());
        System.out.println("SPIKE-RESULT jte view/boom-large leakMarkerInLog="
                + output.getAll().contains(VideoCard.LEAK_MARKER));

        String body = r.body();
        System.out.println("SPIKE-RESULT jte view/boom-large tail=" + body.substring(Math.max(0, body.length() - 400))
                .replace("\n", "\\n"));
        // 风险刻画：响应头早已以 200 发出，正文是半页 HTML，且分块编码没有正常结束
        assertThat(r.status()).isEqualTo(200);
        assertThat(body).contains(SyntheticPages.PARTIAL_MARKER);
        assertThat(r.complete()).isFalse();
        // 实测：容器把错误页追加在半页之后（嵌在未闭合的 <li> 里），状态码仍是 200
        assertThat(body.indexOf("data-spike=\"error-page\"")).isGreaterThan(body.indexOf(SyntheticPages.PARTIAL_MARKER));
    }

    @Test
    void bufferedRenderFailureIsComplete500WithoutLeak(CapturedOutput output) throws Exception {
        RawHttp.Response r = RawHttp.get(port, "/spike/buffered/boom-large");
        System.out.println("SPIKE-RESULT jte buffered/boom-large " + r.summary());

        assertThat(r.status()).isEqualTo(500);
        assertThat(r.complete()).isTrue();
        assertThat(r.headers()).containsKey("content-length");
        assertThat(r.headers().get("cache-control")).isEqualTo("no-store");
        assertThat(r.body()).contains("data-spike=\"error-page\"").endsWith("</html>\n")
                .doesNotContain(SyntheticPages.PARTIAL_MARKER)
                .doesNotContain(VideoCard.LEAK_MARKER);
        assertThat(output.getAll()).contains("page render failed: ")
                .doesNotContain(VideoCard.LEAK_MARKER).doesNotContain("synthetic-host");
    }
}
