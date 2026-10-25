package com.maccms.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.admin.application.AdminSettings;
import com.maccms.backend.admin.application.LegacyInput;
import com.maccms.backend.admin.infrastructure.AdminMapper;
import com.maccms.backend.admin.infrastructure.AdminWriteMapper;
import com.maccms.backend.common.captcha.Captchas;
import com.maccms.backend.content.application.ContentCacheMaintenance;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 后台在真实 Tomcat 上的响应头字节（原始套接字；MockMvc 会重写 Set-Cookie）：会话 cookie 的属性（子目录部署的 Path、https 的 Secure、
 * HttpOnly、SameSite=Strict）、登录时换新标识、退出时删除、is_iframe 与旧版 setcookie 相同的写法、JSON 的 Content-Type、页面的内容安全策略。
 * 库用内存替身（不连库）。
 */
@SpringBootTest(classes = MaccmsBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:1/synthetic_never_connects",
                "spring.datasource.username=synthetic_ro",
                "spring.datasource.password=synthetic",
                "maccms.admin.enabled=true",
                "maccms.admin.entry=/_admin.php",
                "maccms.admin.base-url=https://cms.local.test/sub/"})
@Import(AdminRealHttpTest.Fakes.class)
class AdminRealHttpTest {

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeAdminTable fakeAdminTable() {
            FakeAdminTable t = new FakeAdminTable();
            t.put(1, "admin", LegacyInput.md5Hex("admin888".getBytes(StandardCharsets.UTF_8)), 1, "");
            return t;
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    AdminSessions sessions;
    @Autowired
    AdminSettings settings;
    @Autowired
    FakeAdminTable table;
    @MockitoBean
    ContentCacheMaintenance contentCache;

    record Response(int status, List<String> headers, String body) {
        String header(String name) {
            return headers.stream().filter(h -> h.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT) + ":"))
                    .map(h -> h.substring(h.indexOf(':') + 1).trim()).findFirst().orElse(null);
        }

        List<String> all(String name) {
            return headers.stream().filter(h -> h.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT) + ":"))
                    .map(h -> h.substring(h.indexOf(':') + 1).trim()).toList();
        }
    }

    private Response send(String method, String path, String body, String... headers) throws IOException {
        StringBuilder req = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: 127.0.0.1:").append(port).append("\r\n");
        for (String h : headers) {
            req.append(h).append("\r\n");
        }
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.ISO_8859_1);
        if (body != null) {
            req.append("Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ").append(payload.length)
                    .append("\r\n");
        }
        req.append("Connection: close\r\n\r\n");
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(payload);
            out.flush();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = in.read(buf)) != -1) {
                    raw.write(buf, 0, n);
                }
            } catch (SocketTimeoutException e) {
                // 按已读到的字节解析
            }
            String all = raw.toString(StandardCharsets.UTF_8);
            int split = all.indexOf("\r\n\r\n");
            String[] lines = all.substring(0, split).split("\r\n");
            List<String> hs = new ArrayList<>(List.of(lines).subList(1, lines.length));
            return new Response(Integer.parseInt(lines[0].split(" ")[1]), hs, all.substring(split + 4));
        }
    }

    private static String sid(Response r) {
        for (String c : r.all("Set-Cookie")) {
            if (c.startsWith(AdminSessions.COOKIE + "=")) {
                return c.substring(AdminSessions.COOKIE.length() + 1, c.indexOf(';'));
            }
        }
        return null;
    }

    @Test
    void cookieAndHeaderBytes() throws Exception {
        Response img = send("GET", "/_admin.php/admin/index/verify.html", null);
        assertThat(img.status()).isEqualTo(200);
        String pre = sid(img);
        assertThat(img.header("Set-Cookie"))
                .isEqualTo(AdminSessions.COOKIE + "=" + pre + "; Path=/sub/_admin.php; HttpOnly; SameSite=Strict; Secure");
        // 写入已知的验证码再登录
        java.util.ArrayDeque<Integer> idx = new java.util.ArrayDeque<>();
        for (char c : "8642".toCharArray()) {
            idx.add(settings.captcha().codeSet().indexOf(c));
        }
        SplittableRandom rest = new SplittableRandom(3);
        Captchas.issue(sessions.store().find(pre).orElseThrow(), Captchas.DEFAULT_SLOT, settings.captcha(),
                sessions.nowEpochSeconds(), new RandomGenerator() {
                    @Override
                    public long nextLong() {
                        return rest.nextLong();
                    }

                    @Override
                    public int nextInt(int bound) {
                        return idx.isEmpty() ? rest.nextInt(bound) : idx.poll();
                    }
                });
        Response login = send("POST", "/_admin.php/admin/index/login.html", "admin_name=admin&admin_pwd=admin888&verify=8642",
                "Cookie: " + AdminSessions.COOKIE + "=" + pre, "X-Requested-With: XMLHttpRequest",
                "Origin: https://cms.local.test");
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.header("Content-Type")).isEqualTo("application/json;charset=UTF-8");
        assertThat(login.body()).isEqualTo("{\"code\":1,\"msg\":\"登录成功\",\"data\":\"\","
                + "\"url\":\"\\/sub\\/_admin.php\\/admin\\/index\\/login.html\",\"wait\":3}");
        String sid = sid(login);
        assertThat(sid).isNotEqualTo(pre).matches("[0-9a-f]{32}");
        assertThat(login.header("Set-Cookie"))
                .isEqualTo(AdminSessions.COOKIE + "=" + sid + "; Path=/sub/_admin.php; HttpOnly; SameSite=Strict; Secure");

        Response frame = send("GET", "/_admin.php/admin/index/index.html", null, "Cookie: " + AdminSessions.COOKIE + "=" + sid);
        assertThat(frame.status()).isEqualTo(200);
        assertThat(frame.header("Content-Security-Policy")).isEqualTo(AdminResponses.CONTENT_SECURITY_POLICY);
        assertThat(frame.header("Cache-Control")).isEqualTo("no-store");
        assertThat(frame.header("X-Frame-Options")).isEqualTo("SAMEORIGIN");
        // 子目录部署：页面里的站内路径带前缀（旧 __ROOT__、SCRIPT_NAME）
        assertThat(frame.body()).contains("href=\"/sub/static/layui/css/layui.css\"",
                "data-admin-path=\"/sub/_admin.php\"", "src=\"/sub/_admin.php/admin/index/welcome.html\"",
                "href=\"/sub/_admin.php/admin/index/logout.html\"");

        Response on = send("POST", "/_admin.php/admin/index/iframe", "val=1", "Cookie: " + AdminSessions.COOKIE + "=" + sid,
                "X-Requested-With: XMLHttpRequest");
        assertThat(on.all("Set-Cookie")).containsExactly("is_iframe=yes; path=/");
        Response off = send("POST", "/_admin.php/admin/index/iframe", "val=0", "Cookie: " + AdminSessions.COOKIE + "=" + sid,
                "X-Requested-With: XMLHttpRequest");
        assertThat(off.all("Set-Cookie"))
                .containsExactly("is_iframe=deleted; expires=Thu, 01-Jan-1970 00:00:01 GMT; Max-Age=0; path=/");

        Response out = send("GET", "/_admin.php/admin/index/logout.html", null, "Cookie: " + AdminSessions.COOKIE + "=" + sid);
        assertThat(out.status()).isEqualTo(302);
        assertThat(out.header("Location")).isEqualTo("/sub/_admin.php/admin/index/login.html");
        assertThat(out.all("Set-Cookie"))
                .containsExactly(AdminSessions.COOKIE + "=; Path=/sub/_admin.php; Max-Age=0; HttpOnly; SameSite=Strict; Secure");

        // 不同源的表单提交被拒绝
        Response csrf = send("POST", "/_admin.php/admin/index/login.html", "admin_name=admin&admin_pwd=admin888",
                "Origin: https://evil.test");
        assertThat(csrf.status()).isEqualTo(403);
        assertThat(table.writes).containsExactly("login 1 2130706433 32");
    }
}
