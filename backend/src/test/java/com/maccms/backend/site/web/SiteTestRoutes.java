package com.maccms.backend.site.web;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.site.config.ConditionalOnSiteEnabled;
import gg.jte.TemplateOutput;
import gg.jte.models.runtime.JteModel;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 仅测试使用的内部路由（位于 site 包内，因此受 SiteExceptionHandler 与 SiteStatusInterceptor 管辖）。
 * 只在 site 启用时注册：@WebMvcTest 中由测试显式 @Import，完整应用测试中随组件扫描（测试类路径）注册。
 * 不在 src/main 中，不会进入交付的 jar。异常 message 里的标记均为合成值。
 */
@Controller
@ConditionalOnSiteEnabled
@RequestMapping("/__site-test")
public class SiteTestRoutes {

    public static final String LEAK = "SYNTHETIC-LEAK-9c1d";
    public static final String PARTIAL = "SYNTHETIC-PARTIAL-CHUNK";
    /** 每次进入本 Controller 的方法都会加一，用于证明站点关闭时 Controller（及其数据读取）没有执行。 */
    public static final AtomicInteger INVOCATIONS = new AtomicInteger();

    private final SiteHtmlRenderer renderer;

    public SiteTestRoutes(SiteHtmlRenderer renderer) {
        this.renderer = renderer;
    }

    @GetMapping("/api-error/{code}")
    public ResponseEntity<String> apiError(@PathVariable String code) {
        INVOCATIONS.incrementAndGet();
        throw new ApiException(ErrorCode.valueOf(code), "合成 message " + LEAK);
    }

    @GetMapping("/item/{id}")
    public ResponseEntity<String> item(@PathVariable long id) {
        INVOCATIONS.incrementAndGet();
        throw new IllegalStateException("不应到达：" + LEAK);
    }

    @GetMapping("/fail")
    public ResponseEntity<String> fail() {
        INVOCATIONS.incrementAndGet();
        throw new IllegalStateException(LEAK + " jdbc:mysql://synthetic-host/synthetic_db password=" + LEAK,
                new java.sql.SQLException("select * from mac_user where user_pwd='" + LEAK + "'"));
    }

    /** 先写出约 200 KB（远超容器响应缓冲区）再抛异常，模拟"渲染到一半失败"。 */
    @GetMapping("/boom-large")
    public ResponseEntity<String> boomLarge() {
        INVOCATIONS.incrementAndGet();
        return renderer.ok(t -> new FailingModel(), true);
    }

    static final class FailingModel implements JteModel {
        @Override
        public void render(TemplateOutput output) {
            String chunk = "<p>" + PARTIAL + " " + "x".repeat(80) + "</p>\n";
            for (int i = 0; i < 2000; i++) {
                output.writeContent(chunk);
            }
            throw new IllegalStateException(LEAK + " jdbc:mysql://synthetic-host/synthetic_db");
        }

        @Override
        public void writeTo(TemplateOutput output) {
            render(output);
        }
    }
}
