package com.maccms.backend.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.content.application.ContentViewer;
import com.maccms.backend.content.infrastructure.GroupMapper;
import com.maccms.backend.content.infrastructure.TypeMapper;
import com.maccms.backend.member.application.MemberAccountService;
import com.maccms.backend.member.application.MemberSessionService;
import com.maccms.backend.member.application.view.MemberGroup;
import com.maccms.backend.member.application.view.MemberPageSettings;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.application.view.PageMember;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 请求作用域观看者（{@link ContentViewer}，18 文档第 5.3、11 节）在真实 Tomcat 线程池上的隔离（MACC-23 队长门槛）：
 * <ul>
 * <li>处理器抛异常退出后作用域仍被清除，同一工作线程上的下一个游客请求按游客组 1；</li>
 * <li>工作线程复用（线程池只有 4 个线程，几百个请求必然复用）时不串组；</li>
 * <li>多个请求真正同时在途（处理器里用栅栏等齐）时各自看到自己的会员组；</li>
 * <li>公开 JSON API 带着会员 cookie 也按游客，且不做会员判定。</li>
 * </ul>
 * 会员判定是替身：请求头 {@code X-Viewer-Group} 给出该请求的有效会员组（没有该头为游客；替身经 {@link RequestContextHolder} 读当前请求）；处理器里经
 * {@code accounts.pageSettings()} 记录当时的 {@link ContentViewer#groupId()} 与线程名。数据源不可达。
 */
@SpringBootTest(classes = MaccmsBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:1/synthetic_never_connects",
                "spring.datasource.username=synthetic_ro",
                "spring.datasource.password=synthetic",
                "server.tomcat.threads.max=4",
                "server.tomcat.threads.min-spare=4",
                "maccms.site.enabled=true",
                "maccms.site.name=合成站",
                "maccms.site.base-url=http://maccms.local.test/"})
class SiteContentViewerIsolationTest {

    private static final String PAGE = "/index.php/user/ajax_login.html";
    private static final String MEMBER_COOKIES = "user_id=7; user_name=member07; user_check=synthetic";
    private static final int THREADS = 4;

    @LocalServerPort
    int port;
    @MockitoBean
    MemberAccountService accounts;
    @MockitoBean
    MemberSessionService sessions;
    @MockitoBean
    GroupMapper groups;
    @MockitoBean
    TypeMapper types;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();
    /** 请求编号 → 处理器里看到的 {有效会员组, 线程名}。 */
    private final Map<String, Seen> seen = new ConcurrentHashMap<>();
    private volatile CyclicBarrier barrier;

    private record Seen(int group, String thread) {
    }

    @BeforeEach
    void stubs() {
        when(accounts.available()).thenReturn(true);
        when(types.selectEnabledVodTypes()).thenReturn(List.of());
        when(groups.selectGroupPermission(anyInt())).thenAnswer(inv -> {
            seen.put(currentRequest().getHeader("X-Probe"), new Seen(inv.getArgument(0), Thread.currentThread().getName()));
            return null;
        });
        when(sessions.resolvePage(any())).thenAnswer(inv -> {
            String g = currentRequest().getHeader("X-Viewer-Group");
            if (g == null) {
                return new PageMember(MemberState.guest(null), List.of(), false);
            }
            int group = Integer.parseInt(g);
            MemberGroup mg = new MemberGroup(group, "合成组" + group, 1, ",1,", "{}");
            return new PageMember(new MemberState(true, 7, "member07", group, group, mg, 0, false), List.of(), true);
        });
        when(accounts.pageSettings()).thenAnswer(inv -> {
            HttpServletRequest request = currentRequest();
            seen.put(request.getHeader("X-Probe"), new Seen(ContentViewer.groupId(), Thread.currentThread().getName()));
            CyclicBarrier b = barrier;
            if (request.getHeader("X-Viewer-Barrier") != null && b != null) {
                b.await(15, TimeUnit.SECONDS);
            }
            if (request.getHeader("X-Viewer-Throw") != null) {
                throw new IllegalStateException("synthetic handler failure");
            }
            return new MemberPageSettings(true, false, false, true, false, false, false);
        });
    }

    /**
     * 站点页面每次都会重新进入作用域，单看站点页面发现不了泄漏；所以异常退出后用不设作用域的公开 API 去探同一个工作线程：
     * 作用域没清掉时 API 会按上一个会员组取权限（去掉 {@code afterCompletion} 的关闭即失败）。
     */
    @Test
    void scopeIsClearedAfterAnExceptionAndWorkerThreadsAreReusedWithoutLeaking() throws Exception {
        Set<String> allThreads = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            int group = 3 + i % 5;
            String failing = "fail-" + i;
            HttpResponse<String> r = send(failing, Map.of("X-Viewer-Group", String.valueOf(group),
                    "X-Viewer-Throw", "1", "Cookie", MEMBER_COOKIES));
            assertThat(r.statusCode()).as("处理器异常").isGreaterThanOrEqualTo(500);
            Seen f = seen.get(failing);
            assertThat(f.group()).as("抛异常前按该请求的会员组").isEqualTo(group);
            allThreads.add(f.thread());

            // 一直发到落在刚才异常退出的那个工作线程上为止（线程池会复用）；每一个都必须按游客组 1
            boolean reused = false;
            for (int k = 0; k < 64 && !reused; k++) {
                String probe = "after-" + i + "-" + k;
                Seen a = api(probe);
                assertThat(a.group()).as("线程 %s 上的公开 API 请求", a.thread()).isEqualTo(1);
                allThreads.add(a.thread());
                reused = a.thread().equals(f.thread());
            }
            assertThat(reused).as("第 %d 轮：复用了异常退出的会员请求所在线程 %s", i, f.thread()).isTrue();

            String guest = "guest-" + i;
            assertThat(send(guest, Map.of()).statusCode()).isEqualTo(200);
            assertThat(seen.get(guest).group()).as("游客站点页面").isEqualTo(1);
        }
        assertThat(allThreads).as("线程池只有 %d 个线程", THREADS).hasSizeLessThanOrEqualTo(THREADS);
    }

    @Test
    void concurrentRequestsInFlightTogetherEachSeeTheirOwnGroup() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int round = 0; round < 25; round++) {
                barrier = new CyclicBarrier(THREADS);
                List<Future<HttpResponse<String>>> futures = new ArrayList<>();
                for (int k = 0; k < THREADS; k++) {
                    String probe = "c-" + round + "-" + k;
                    int group = 3 + k;
                    futures.add(pool.submit(() -> send(probe, Map.of("X-Viewer-Group", String.valueOf(group),
                            "X-Viewer-Barrier", "1", "Cookie", MEMBER_COOKIES))));
                }
                Set<String> threads = new HashSet<>();
                for (int k = 0; k < THREADS; k++) {
                    assertThat(futures.get(k).get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                    Seen s = seen.get("c-" + round + "-" + k);
                    assertThat(s.group()).as("第 %d 轮请求 %d", round, k).isEqualTo(3 + k);
                    threads.add(s.thread());
                }
                assertThat(threads).as("4 个请求在 4 个线程上同时在途（栅栏等齐）").hasSize(THREADS);
                String guest = "cg-" + round;
                assertThat(send(guest, Map.of()).statusCode()).isEqualTo(200);
                assertThat(seen.get(guest).group()).as("并发会员请求之后的游客请求").isEqualTo(1);
                for (int k = 0; k < THREADS * 2; k++) {
                    assertThat(api("ca-" + round + "-" + k).group()).as("并发会员请求之后的公开 API").isEqualTo(1);
                }
            }
        } finally {
            barrier = null;
            pool.shutdownNow();
        }
    }

    @Test
    void publicApiIsAlwaysGuestEvenWithMemberCookies() throws Exception {
        clearInvocations(sessions);
        for (int k = 0; k < THREADS * 2; k++) {
            assertThat(api("api-" + k).group()).as("公开 API 按游客组取权限").isEqualTo(1);
        }
        verify(sessions, never()).resolvePage(any());

        // 对照：同一个会员组的站点页面确实按会员组（替身生效）
        assertThat(send("site-5", Map.of("X-Viewer-Group", "5", "Cookie", MEMBER_COOKIES)).statusCode()).isEqualTo(200);
        assertThat(seen.get("site-5").group()).isEqualTo(5);
    }

    /** 带会员 cookie 请求公开 API 的分类树，返回处理时取权限用的会员组与线程。 */
    private Seen api(String probe) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/video-categories")).timeout(Duration.ofSeconds(30)).header("X-Probe", probe)
                .header("Cookie", MEMBER_COOKIES).header("X-Viewer-Group", "9").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        return seen.get(probe);
    }

    private static HttpServletRequest currentRequest() {
        return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    }

    private HttpResponse<String> send(String probe, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PAGE))
                .timeout(Duration.ofSeconds(30)).header("X-Probe", probe).GET();
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
