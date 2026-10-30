package com.maccms.backend.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.member.application.MemberAccountService;
import com.maccms.backend.member.application.MemberCookies;
import com.maccms.backend.member.application.MemberSessionService;
import com.maccms.backend.member.application.view.AccountReply;
import com.maccms.backend.member.application.view.MemberGroup;
import com.maccms.backend.member.application.view.MemberPageSettings;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.application.view.PageMember;
import com.maccms.backend.member.application.view.PortraitReply;
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
 * JSON 的 Content-Type 与正文；超过上传上限的头像请求与旧版 PHP 一样丢弃上传、仍按 JSON 答复。数据源不可达（服务是替身）。
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

        // 需登录的操作：旧版先由 label_user 写游客回写，再由构造函数的 logout() 删 6 个，浏览器按顺序收到 8 个
        Raw protectedPage = request("GET /index.php/user/index.html HTTP/1.1",
                List.of("Cookie: user_id=1; user_name=a; user_check=b"), new byte[0]);
        List<String> expected = new ArrayList<>(MemberCookies.guestReset());
        expected.addAll(MemberCookies.logout());
        assertThat(protectedPage.headers("Set-Cookie")).isEqualTo(expected);
    }

    /**
     * 上传上限（{@code spring.servlet.multipart} 2 MB / 3 MB）：旧版 PHP 丢弃超限的文件（{@code $_FILES} 没有 {@code tmp_name}），
     * 超过 {@code post_max_size} 时连其他字段一起丢弃，头像返回"未找到上传的文件…"的 JSON。新版按需解析 multipart，解析失败时把
     * 文件与 {@code imgdata} 都当作没有，交给服务得出同一提示；此前在进入处理器前就成了 500。
     */
    @Test
    void oversizedPortraitUploadsAreDroppedLikeLegacyPhp() throws Exception {
        when(accounts.available()).thenReturn(true);
        when(accounts.pageSettings()).thenReturn(new MemberPageSettings(true, false, false, true, false, false, true));
        MemberState member = new MemberState(true, 7, "member07", 3, 3, new MemberGroup(3, "VIP会员", 1, ",1,", "{}"), 0,
                false);
        when(sessions.resolvePage(any())).thenReturn(new PageMember(member, List.of(), true));
        String noInput = "未找到上传的文件(原因：表单名可能错误，默认表单名“file”或“imgdata”)！";
        when(accounts.portrait(any(), any(), any(), any())).thenReturn(new PortraitReply(0, noInput, "/?1234", false));
        // 文件超过 2 MB、整个请求不到 3 MB（头像实际会遇到的情形）。请求超过 3 MB 时 Tomcat 按 Content-Length 直接拒绝且不读完
        // 正文（超过默认 2 MB 的 maxSwallowSize），直连的客户端会收到连接重置；经 nginx 的表现见 18 文档第 14 节
        for (int size : new int[] {2 * 1024 * 1024 + 1, 2 * 1024 * 1024 + 4096, 3 * 1000 * 1000 - 4096}) {
            clearInvocations(accounts);
            String boundary = "----b09webp";
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"imgdata\"\r\n\r\n"
                    + "data:image/webp;base64,AAAA\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; "
                    + "filename=\"big.webp\"\r\nContent-Type: image/webp\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            body.writeBytes(new byte[size]);
            body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
            Raw r = request("POST /index.php/user/portrait.html HTTP/1.1",
                    List.of("Content-Type: multipart/form-data; boundary=" + boundary, "X-Requested-With: XMLHttpRequest"),
                    body.toByteArray());
            assertThat(r.status()).as("文件 %d 字节", size).isEqualTo(200);
            assertThat(r.headers("Content-Type")).containsExactly("application/json;charset=UTF-8");
            assertThat(new String(r.body(), StandardCharsets.UTF_8)).isEqualTo(
                    "{\"msg\":\"" + noInput + "\",\"code\":0,\"file\":\"\\/?1234\"}");
            verify(accounts).portrait(any(), isNull(), isNull(), isNull());
        }
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
