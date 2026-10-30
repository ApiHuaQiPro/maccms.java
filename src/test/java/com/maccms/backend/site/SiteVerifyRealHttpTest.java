package com.maccms.backend.site;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.MaccmsBackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * B08 验证码在真实 Tomcat 上的往返（原始套接字，17 文档第 6 节）：会话 cookie 经容器原样回送、PHP 风格的 Cookie 头（无空格分隔、
 * 同名重复、带引号与 + 的其他 cookie）不影响取会话标识；数据源不可达（不建立任何数据库连接），验证码不需要数据库。
 */
@SpringBootTest(classes = MaccmsBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:1/synthetic_never_connects",
                "spring.datasource.username=synthetic_ro",
                "spring.datasource.password=synthetic",
                "maccms.site.enabled=true",
                "maccms.site.name=合成站",
                "maccms.site.base-url=https://maccms.local.test/sub/"})
class SiteVerifyRealHttpTest {

    @LocalServerPort
    int port;

    @Test
    void captchaRoundTripThroughRealContainer() throws Exception {
        RawHttp.Response image = RawHttp.get(port, "/index.php/verify/index.html");
        assertThat(image.status()).isEqualTo(200);
        assertThat(image.complete()).isTrue();
        assertThat(image.headers().get("content-type")).isEqualTo("image/png");
        assertThat(image.headers().get("cache-control")).isEqualTo("no-store");
        String setCookie = image.headers().get("set-cookie");
        // 站点在子目录、https：Path 为站点根，带 Secure
        assertThat(setCookie).matches("PHPSESSID=[0-9a-f]{32}; Path=/sub/; HttpOnly; SameSite=Lax; Secure");
        String id = setCookie.substring("PHPSESSID=".length(), setCookie.indexOf(';'));

        // 猜错即作废；之后同一会话再取一张，PHP 风格的 Cookie 头里照样取到会话（先到先得）
        RawHttp.Response wrong = RawHttp.get(port, "/index.php/verify/check?verify=abcd",
                "Cookie: user_name=a+b;PHPSESSID=" + id + ";PHPSESSID=ffffffffffffffffffffffffffffffff; q=\"x\"");
        assertThat(wrong.status()).isEqualTo(200);
        assertThat(wrong.body()).isEqualTo("0");
        assertThat(wrong.headers().get("content-type")).isEqualTo("text/html;charset=UTF-8");
        assertThat(wrong.headers()).doesNotContainKey("set-cookie");

        RawHttp.Response again = RawHttp.get(port, "/index.php/verify/index", "Cookie: PHPSESSID=" + id);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.headers()).as("已有会话不重新签发").doesNotContainKey("set-cookie");

        RawHttp.Response noSession = RawHttp.get(port, "/index.php/verify/check?verify=1234");
        assertThat(noSession.body()).isEqualTo("0");
    }

    @Test
    void headReturnsImageHeadersWithoutBody() throws Exception {
        RawHttp.Response head = RawHttp.head(port, "/index.php/verify/index");
        assertThat(head.status()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertThat(head.headers().get("content-type")).isEqualTo("image/png");
    }
}
