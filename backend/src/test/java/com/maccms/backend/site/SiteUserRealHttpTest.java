package com.maccms.backend.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.member.application.MemberAccountService;
import com.maccms.backend.member.application.MemberCookies;
import com.maccms.backend.member.application.MemberSessionService;
import com.maccms.backend.member.application.view.AccountReply;
import com.maccms.backend.member.application.view.MemberPageSettings;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.application.view.PageMember;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * B09 会员入口在真实 Tomcat 上的字节（原始套接字，18 文档第 4 节）：登录响应的 6 个 {@code Set-Cookie} 逐字节等于
 * {@link MemberCookies}（与 PHP 黄金相同的写法，{@code user_check} 带 HttpOnly），游客回写的 2 个同样；表单正文按字节到达服务；
 * JSON 的 Content-Type 与正文。数据源不可达（服务是替身）。
 */
@SpringBootTest(classes = MaccmsBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:1/synthetic_never_connects",
                "spring.datasource.username=synthetic_ro",
                "spring.datasource.password=synthetic",
                "maccms.site.enabled=true",
                "maccms.site.name=合成站",
                "maccms.site.base-url=http://maccms.local.test/"})
class SiteUserRealHttpTest {

    @LocalServerPort
    int port;
    @MockitoBean
    MemberAccountService accounts;
    @MockitoBean
    MemberSessionService sessions;

    @Test
    void loginCookiesAndGuestResetAreWrittenByteForByte() throws Exception {
        when(accounts.available()).thenReturn(true);
        when(accounts.pageSettings()).thenReturn(new MemberPageSettings(true, false, false, true, false, false, false));
        when(sessions.resolvePage(any())).thenReturn(new PageMember(MemberState.guest(null), List.of(), false));
        List<String> cookies = MemberCookies.login(21, "中文会员", 2, "默认会员", "b08ma4ctsl", "/static/images/touxiang.png",
                2_000_000_000L);
        when(accounts.login(any(), any(), any())).thenReturn(new AccountReply(1, "登录成功", cookies));

        Raw login = request("POST /index.php/user/login.html HTTP/1.1",
                List.of("Content-Type: application/x-www-form-urlencoded", "X-Requested-With: XMLHttpRequest"),
                "user_name=%E4%B8%AD&user_pwd=+%FFpw%00".getBytes(StandardCharsets.US_ASCII));
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.headers("Set-Cookie")).as("Tomcat 原样写出，顺序不变").isEqualTo(cookies);
        assertThat(login.headers("Set-Cookie").get(4)).endsWith("; HttpOnly");
        assertThat(login.headers("Content-Type")).containsExactly("application/json;charset=UTF-8");
        assertThat(login.headers("Cache-Control")).containsExactly("no-store");
        assertThat(new String(login.body(), StandardCharsets.UTF_8)).isEqualTo("{\"code\":1,\"msg\":\"登录成功\"}");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, PhpInput.Value>> params = ArgumentCaptor.forClass(Map.class);
        verify(accounts).login(params.capture(), any(), any());
        assertThat(params.getValue().get("user_pwd").bytes()).isEqualTo(new byte[] {' ', (byte) 0xFF, 'p', 'w', 0});
        assertThat(params.getValue().get("user_name").text()).isEqualTo("中");

        when(sessions.resolvePage(any())).thenReturn(new PageMember(MemberState.guest(null), MemberCookies.guestReset(),
                true));
        Raw page = request("GET /index.php/user/ajax_login.html HTTP/1.1",
                List.of("Cookie: user_id=1; user_name=a; user_check=b"), new byte[0]);
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.headers("Set-Cookie")).isEqualTo(MemberCookies.guestReset());
        assertThat(page.headers("Cache-Control")).containsExactly("no-store");
    }

    private record Raw(int status, List<String> lines, byte[] body) {

        List<String> headers(String name) {
            List<String> out = new ArrayList<>();
            for (String l : lines) {
                int c = l.indexOf(':');
                if (c > 0 && l.substring(0, c).equalsIgnoreCase(name)) {
                    out.add(l.substring(c + 1).trim());
                }
            }
            return out;
        }
    }

    private Raw request(String requestLine, List<String> headers, byte[] body) throws IOException {
        StringBuilder req = new StringBuilder(requestLine).append("\r\nHost: 127.0.0.1:").append(port).append("\r\n");
        for (String h : headers) {
            req.append(h).append("\r\n");
        }
        if (body.length > 0) {
            req.append("Content-Length: ").append(body.length).append("\r\n");
        }
        req.append("Connection: close\r\n\r\n");
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            in.transferTo(raw);
            byte[] all = raw.toByteArray();
            String text = new String(all, StandardCharsets.ISO_8859_1);
            int split = text.indexOf("\r\n\r\n");
            String[] lines = text.substring(0, split).split("\r\n");
            List<String> headerLines = new ArrayList<>(List.of(lines).subList(1, lines.length));
            // 响应头里的非 ASCII 字节按 ISO-8859-1 读出，再按 UTF-8 还原（Set-Cookie 的值已经 rawurlencode，都是 ASCII）
            headerLines.replaceAll(l -> new String(l.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
            byte[] payload = java.util.Arrays.copyOfRange(all, split + 4, all.length);
            return new Raw(Integer.parseInt(lines[0].split(" ")[1]), headerLines, payload);
        }
    }
}
