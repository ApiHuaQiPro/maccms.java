package com.maccms.backend.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.MaccmsBackendApplication;
import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.LegacyRange;
import com.maccms.backend.content.application.VideoFilter;
import com.maccms.backend.content.application.VideoLegacyConditions;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.content.application.VideoSort;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.cache.CacheKey;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/**
 * B05 独立验收返工第 4 项：在最终树上对一次性合成库做中规模测量（docs/architecture/15-b05-perf-report.md 第 10 节）。
 *
 * <p><b>不是测试，不进 {@code ./mvnw verify}</b>（类名不匹配 surefire 的 *Test 与 failsafe 的 *IT），只按需显式运行：
 * <pre>
 *   java -cp mysql-connector-j-8.2.0.jar src/test/perf/B05Perf.java create   # 建一次性库，写 target/perf-b05/state.properties
 *   java -cp ... src/test/perf/B05Perf.java grow 300000                        # 30 万行视频（与第 0–9 节相同的生成器）
 *   ./mvnw -o -Pmysql-it verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=B05SsrPerfRun
 *   java -cp ... src/test/perf/B05Perf.java drop                               # 删除并确认不存在
 * </pre>
 * 只连接 state.properties 中名称匹配 {@code ^maccms_it_[0-9]{14}_[0-9a-f]{8}$} 的一次性库（管理账号只经 MACCMS_IT_ADMIN_* 环境变量）；
 * 在其中补齐文章/演员/角色/网址/专题合成数据与视频的分集剧情标记后，以真实 HTTP 启动<b>当前代码</b>（同一 JVM，端口随机，只听 127.0.0.1），
 * 请求 SSR 页面（视频搜索/筛选页、模块筛选/搜索页、剧情搜索页），并直接调用 {@link ContentReadService#listVideos} 测量内部标签条件
 * （{@link VideoLegacyConditions}，没有 URL 入口）。SQL 文本与绑定值由测试侧注册的 MyBatis 拦截器从实际执行的 MappedStatement 取得
 * （不是照抄 XML），逐条记录耗时与 MySQL 错误码，并用同一组绑定值在另一连接上执行 EXPLAIN。输出写到 target/perf-b05-ssr/。
 */
class B05SsrPerfRun {

    static final Pattern DB_NAME = Pattern.compile("^maccms_it_[0-9]{14}_[0-9a-f]{8}$");
    static final Path STATE = Path.of("target", "perf-b05", "state.properties");
    static final Path OUT = Path.of("target", "perf-b05-ssr");
    static final long NOW = 1_790_000_000L;
    static final int WARM_RUNS = Integer.getInteger("perf.runs", 5);

    static String adminUrl;
    static String adminUser;
    static String adminPassword;
    static String db;

    // ------------------------------------------------------------------ SQL 记录

    /** 实际执行的一条语句：MappedStatement id、SQL（空白归一）、绑定值、耗时、MySQL 错误码（无错为 null）。 */
    record Executed(String id, String sql, List<Object> binds, double ms, String error) {
    }

    /** 测试侧的 MyBatis 拦截器（只在本次运行的应用上下文里注册）：记录查询语句、绑定值与耗时，不改变执行。 */
    @Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class,
                    BoundSql.class})})
    static final class Recorder implements Interceptor {
        final List<Executed> executed = Collections.synchronizedList(new ArrayList<>());
        volatile boolean on;

        @Override
        public Object intercept(Invocation inv) throws Throwable {
            MappedStatement ms = (MappedStatement) inv.getArgs()[0];
            Object param = inv.getArgs()[1];
            BoundSql bs = inv.getArgs().length == 6 ? (BoundSql) inv.getArgs()[5] : ms.getBoundSql(param);
            List<Object> binds = binds(ms.getConfiguration(), bs, param);
            long t0 = System.nanoTime();
            String error = null;
            try {
                return inv.proceed();
            } catch (Throwable e) {
                error = sqlError(e);
                throw e;
            } finally {
                if (on) {
                    executed.add(new Executed(ms.getId().substring(ms.getId().lastIndexOf('.', ms.getId().lastIndexOf('.') - 1) + 1),
                            bs.getSql().replaceAll("\\s+", " ").trim(), binds, (System.nanoTime() - t0) / 1e6, error));
                }
            }
        }

        /** 与 DefaultParameterHandler 相同的取值顺序。 */
        static List<Object> binds(Configuration cfg, BoundSql bs, Object param) {
            List<Object> out = new ArrayList<>();
            for (ParameterMapping pm : bs.getParameterMappings()) {
                if (pm.getMode() == ParameterMode.OUT) {
                    continue;
                }
                String prop = pm.getProperty();
                Object v;
                if (bs.hasAdditionalParameter(prop)) {
                    v = bs.getAdditionalParameter(prop);
                } else if (param == null) {
                    v = null;
                } else if (cfg.getTypeHandlerRegistry().hasTypeHandler(param.getClass())) {
                    v = param;
                } else {
                    v = cfg.newMetaObject(param).getValue(prop);
                }
                out.add(v instanceof Enum<?> e ? e.name() : v);
            }
            return out;
        }

        static String sqlError(Throwable e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SQLException s) {
                    return Integer.toString(s.getErrorCode());
                }
            }
            return e.getClass().getSimpleName();
        }
    }

    // ------------------------------------------------------------------ 场景

    /** 一个形态：SSR 页面（path）或内部标签服务调用（path 为 null，note 为条件）。 */
    record Shape(String name, String group, String path, String note) {
        static Shape http(String name, String group, String path, String note) {
            return new Shape(name, group, path, note);
        }
    }

    record Result(Shape shape, List<Integer> statuses, List<Double> ms, String total, List<Executed> sample) {
    }

    static List<Shape> httpShapes(Connection c) throws SQLException {
        String names = String.join(",", firstValues(c, "SELECT website_name FROM mac_website WHERE website_status=1 "
                + "ORDER BY website_id LIMIT 3"));
        String ids = String.join(",", LongStream.rangeClosed(1, 50).map(i -> i * 997).mapToObj(Long::toString).toList());
        return List.of(
                // ---- 视频 SSR 搜索页（MACC-20 + B05 新键；与第 0–9 节的 API 形态同一条件，经 SSR 入口）
                Shape.http("v.area1", "a", "/index.php/vod/search.html?area=" + enc("日本"), "单值 =（1.1.0 写法）"),
                Shape.http("v.area3", "a", "/index.php/vod/search.html?area=" + enc("日本,韩国,美国"), "IN 3 项"),
                Shape.http("v.lang3", "a", "/index.php/vod/search.html?lang=" + enc("粤语,韩语,日语"), "IN 3 项"),
                Shape.http("v.letter3", "a", "/index.php/vod/search.html?letter=" + enc("A,B,C"), "IN 3 项"),
                Shape.http("v.ids50", "a", "/index.php/vod/search.html?ids=" + enc(ids), "主键 IN 50 项"),
                Shape.http("v.class3", "b", "/index.php/vod/search.html?class=" + enc("科幻,战争,武侠"), "LIKE OR 3 项"),
                Shape.http("v.actor2", "b", "/index.php/vod/search.html?actor=" + enc("张伟,王芳"), "LIKE OR 2 项"),
                Shape.http("v.tid-top", "b", "/index.php/vod/search.html?tid=1", "SSR tid：type_id OR type_id_1"),
                Shape.http("v.tid-child", "b", "/index.php/vod/search.html?tid=7", "SSR tid（子分类）"),
                Shape.http("v.year1", "c", "/index.php/vod/search.html?year=2010", "单个年份"),
                Shape.http("v.year2000-2010", "c", "/index.php/vod/search.html?year=2000-2010", "年份区间 11 项"),
                Shape.http("v.year1990-2021", "c", "/index.php/vod/search.html?year=1990-2021", "年份区间 32 项"),
                Shape.http("v.rnd1", "d", "/index.php/vod/search.html?by=rnd&area=" + enc("新加坡,印度")
                        + "&letter=" + enc("Q,X,Z"), "随机算法一（计数 ≤ 2000）"),
                Shape.http("v.rnd2-letter", "d", "/index.php/vod/search.html?by=rnd&letter=A", "随机算法二（候选 1000）"),
                Shape.http("v.rnd2-class", "d", "/index.php/vod/search.html?by=rnd&class=" + enc("科幻"), "随机算法二"),
                Shape.http("v.rnd2-tid", "d", "/index.php/vod/search.html?by=rnd&tid=7", "随机算法二 + SSR tid"),
                Shape.http("v.deep", "a", "/index.php/vod/search.html?letter=A&page=500", "偏移 9980"),
                // ---- vod/show（D-B05-7：分类本身 + 直接子分类）
                Shape.http("v.show-top", "f", "/index.php/vod/show/id/1.html", "顶级分类（本身 + 4 个子类）"),
                Shape.http("v.show-child-area", "f", "/index.php/vod/show/id/7.html?area=" + enc("大陆"), "子分类 + area"),
                Shape.http("v.show-rnd", "f", "/index.php/vod/show/id/1.html?by=rnd", "筛选页随机"),
                // ---- 模块筛选/搜索页（LibraryListQuery，返工第 2 项）
                Shape.http("art.wd", "m", "/index.php/art/search.html?wd=" + enc("星"), "LIKE art_name/en/sub"),
                Shape.http("art.letter3", "m", "/index.php/art/search.html?letter=" + enc("A,B,C"), "IN 3 项"),
                Shape.http("art.tag2", "m", "/index.php/art/search.html?tag=" + enc("科幻,战争"), "LIKE OR 2 项"),
                Shape.http("art.tid", "m", "/index.php/art/search.html?tid=41", "type_id OR type_id_1"),
                Shape.http("art.level", "m", "/index.php/art/search.html?level=5", "level intval"),
                Shape.http("art.show", "m", "/index.php/art/show/id/41.html", "筛选页（本身 + 子分类）"),
                Shape.http("art.show-rnd", "m", "/index.php/art/show/id/41.html?by=rnd", "模块随机页"),
                Shape.http("actor.area3", "m", "/index.php/actor/search.html?area=" + enc("大陆,香港,日本"), "IN 3 项"),
                Shape.http("actor.show-starsign", "m", "/index.php/actor/show/id/51.html?starsign="
                        + enc("白羊座,双子座"), "筛选页 + IN 2 项"),
                Shape.http("actor.show-rnd", "m", "/index.php/actor/show/id/51.html?by=rnd", "模块随机页"),
                Shape.http("role.show", "m", "/index.php/role/show.html", "无条件全部角色"),
                Shape.http("role.show-deep", "m", "/index.php/role/show.html?page=1000", "偏移 19980"),
                Shape.http("role.show-rnd", "m", "/index.php/role/show.html?by=rnd", "模块随机页（全表计数）"),
                Shape.http("role.rid", "m", "/index.php/role/search.html?rid=1234", "role_rid ="),
                Shape.http("role.wd", "m", "/index.php/role/search.html?wd=" + enc("星"), "LIKE role_name/en"),
                Shape.http("website.area-lang", "m", "/index.php/website/search.html?area=" + enc("大陆,香港")
                        + "&lang=" + enc("国语,粤语"), "IN 2 项 × 2"),
                Shape.http("website.in", "m", "/index.php/website/search.html?by=in&name=" + enc(names),
                        "name IN + FIND_IN_SET 排序"),
                Shape.http("website.referer", "m", "/index.php/website/show/id/61.html?by=referer", "referer 排序"),
                Shape.http("topic.wd", "m", "/index.php/topic/search.html?wd=" + enc("专题"), "LIKE topic_name/en/sub"),
                Shape.http("topic.rnd", "m", "/index.php/topic/search.html?by=rnd&letter=" + enc("A,B"), "模块随机页"),
                Shape.http("plot.letter", "m", "/index.php/plot/search.html?letter=" + enc("A,B"), "plot=1 + IN"),
                Shape.http("plot.tid", "m", "/index.php/plot/search.html?tid=7", "plot=1 + SSR tid"),
                Shape.http("plot.rnd", "m", "/index.php/plot/search.html?by=rnd&letter=A", "plot=1 + 随机"));
    }

    /** 内部标签条件（{@link VideoLegacyConditions}，只有内部入口）：名称、条件。 */
    static Map<String, VideoLegacyConditions> tagShapes() {
        Map<String, VideoLegacyConditions> m = new LinkedHashMap<>();
        m.put("t.isend1", VideoLegacyConditions.builder().isEnd(1).build());
        m.put("t.hits-between", VideoLegacyConditions.builder()
                .hits(LegacyRange.of(LegacyRange.Op.BETWEEN, 1000, 50000)).build());
        m.put("t.hits-gt", VideoLegacyConditions.builder().hits(LegacyRange.of(LegacyRange.Op.GT, 200000)).build());
        m.put("t.hitsweek-in", VideoLegacyConditions.builder()
                .hitsWeek(LegacyRange.of(LegacyRange.Op.IN, 0, 1, 2, 3)).build());
        m.put("t.timeadd-30d", VideoLegacyConditions.builder().addedAfter(NOW - 30L * 86400).build());
        m.put("t.timeadd-365d", VideoLegacyConditions.builder().addedAfter(NOW - 365L * 86400).build());
        m.put("t.not100", VideoLegacyConditions.builder()
                .notIds(LongStream.rangeClosed(1, 100).map(i -> i * 2003).boxed().toList()).build());
        m.put("t.rel-ids", VideoLegacyConditions.builder()
                .relIds(LongStream.rangeClosed(1, 30).map(i -> i * 7001).boxed().toList()).build());
        m.put("t.rel-text", VideoLegacyConditions.builder().rel("星辰").build());
        m.put("t.weekday", VideoLegacyConditions.builder().weekday("一,三").build());
        m.put("t.type-current", VideoLegacyConditions.builder().types(List.of(1)).build());
        m.put("t.typenot", VideoLegacyConditions.builder().typeNot(List.of(1, 2, 3)).build());
        m.put("t.tv", VideoLegacyConditions.builder().tvs(List.of("CCTV1", "")).build());
        m.put("t.plot", VideoLegacyConditions.builder().plot(1).build());
        m.put("t.level-multi", VideoLegacyConditions.builder().levels(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9)).build());
        m.put("t.start", VideoLegacyConditions.builder().start(50).isEnd(0).build());
        m.put("t.tid", VideoLegacyConditions.builder().tid(1L).build());
        m.put("t.combo", VideoLegacyConditions.builder().addedAfter(NOW - 365L * 86400)
                .hits(LegacyRange.of(LegacyRange.Op.GT, 200000)).isEnd(1).build());
        return m;
    }

    // ------------------------------------------------------------------ 运行

    @Test
    void measure() throws Exception {
        adminUrl = env("MACCMS_IT_ADMIN_URL");
        adminUser = env("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = env("MACCMS_IT_ADMIN_PASSWORD");
        Properties p = new Properties();
        try (var in = Files.newBufferedReader(STATE)) {
            p.load(in);
        }
        db = p.getProperty("db");
        if (db == null || !DB_NAME.matcher(db).matches()) {
            throw new IllegalStateException("state.properties 中的库名不合法");
        }
        Files.createDirectories(OUT);
        StringBuilder context = new StringBuilder();
        try (Connection c = admin(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT DATABASE()")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo(db);
            }
            growModules(st);
            context.append(contextOf(st));
        }
        Files.writeString(OUT.resolve("context.txt"), context, StandardCharsets.UTF_8);

        Recorder recorder = new Recorder();
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + jdbcUrl(db),
                "--spring.datasource.username=" + adminUser,
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--maccms.site.enabled=true",
                "--maccms.site.search.enabled=true",
                "--maccms.content.search.enabled=true",
                "--maccms.site.features.show=true",
                "--maccms.site.features.ajax-page=true",
                "--maccms.site.name=合成站",
                "--maccms.site.base-url=http://maccms.local.test/",
                "--logging.level.root=WARN"));
        if ("root".equalsIgnoreCase(adminUser)) {
            args.add("--maccms.datasource.allow-root=true");
        }
        System.setProperty("spring.datasource.password", adminPassword);
        List<Result> results = new ArrayList<>();
        try (ConfigurableApplicationContext app = new SpringApplicationBuilder(MaccmsBackendApplication.class)
                .initializers(ctx -> ((GenericApplicationContext) ctx).registerBean("b05PerfSqlRecorder",
                        Interceptor.class, () -> recorder))
                .run(args.toArray(String[]::new))) {
            String base = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
            HttpClient http = HttpClient.newHttpClient();
            List<Shape> shapes;
            try (Connection c = admin()) {
                shapes = httpShapes(c);
            }
            for (Shape s : shapes) {
                results.add(run(s, recorder, () -> {
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + s.path())).GET().build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    Matcher m = Pattern.compile("共 ([0-9]+) 条").matcher(r.body());
                    return new int[] {r.statusCode(), m.find() ? Integer.parseInt(m.group(1)) : -1};
                }));
            }
            ContentReadService content = app.getBean(ContentReadService.class);
            for (Map.Entry<String, VideoLegacyConditions> e : tagShapes().entrySet()) {
                Shape s = new Shape(e.getKey(), "t", null, "ContentReadService.listVideos：" + e.getValue());
                results.add(run(s, recorder, () -> {
                    try {
                        var page = content.listVideos(new VideoListQuery(null, VideoSort.TIME, true, 1, 20,
                                VideoFilter.NONE, false, e.getValue()));
                        return new int[] {200, (int) page.total()};
                    } catch (ApiException ex) {
                        return new int[] {ex.code().status().value(), -1};
                    }
                }));
            }
        } finally {
            System.clearProperty("spring.datasource.password");
        }
        write(results);
    }

    interface Call {
        int[] call() throws Exception;
    }

    static Result run(Shape s, Recorder recorder, Call call) throws Exception {
        List<Integer> statuses = new ArrayList<>();
        List<Double> ms = new ArrayList<>();
        List<Executed> sample = List.of();
        String total = "";
        // 第 1 次（冷）+ WARM_RUNS 次；只记录第 2 次的语句（热态样本）
        for (int i = 0; i <= WARM_RUNS; i++) {
            recorder.executed.clear();
            recorder.on = i == 1;
            long t0 = System.nanoTime();
            int[] r = call.call();
            double elapsed = (System.nanoTime() - t0) / 1e6;
            recorder.on = false;
            statuses.add(r[0]);
            ms.add(elapsed);
            if (i == 1) {
                sample = List.copyOf(recorder.executed);
                total = r[1] < 0 ? "" : Integer.toString(r[1]);
            }
        }
        System.out.printf("[b05-ssr-perf] %-22s statuses=%s ms=%s total=%s statements=%d%n", s.name(), statuses,
                ms.stream().map(v -> String.format("%.0f", v)).toList(), total, sample.size());
        return new Result(s, statuses, ms, total, sample);
    }

    static void write(List<Result> results) throws Exception {
        StringBuilder tsv = new StringBuilder("shape\tgroup\tpath_or_call\tstatuses\tcold_ms\twarm_p50_ms\twarm_max_ms"
                + "\ttotal\tstatements\tsql_ms\terrors\tsys_cpu_load\n");
        StringBuilder sql = new StringBuilder();
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        try (Connection c = admin()) {
            for (Result r : results) {
                List<Double> warm = new ArrayList<>(r.ms().subList(1, r.ms().size()));
                Collections.sort(warm);
                double p50 = warm.get(warm.size() / 2);
                String errors = String.join(",", r.sample().stream().filter(e -> e.error() != null)
                        .map(e -> e.id() + ":" + e.error()).toList());
                tsv.append(r.shape().name()).append('\t').append(r.shape().group()).append('\t')
                        .append(r.shape().path() != null ? r.shape().path() : r.shape().note()).append('\t')
                        .append(r.statuses()).append('\t').append(String.format("%.1f", r.ms().getFirst())).append('\t')
                        .append(String.format("%.1f", p50)).append('\t')
                        .append(String.format("%.1f", warm.getLast())).append('\t').append(r.total()).append('\t')
                        .append(r.sample().size()).append('\t')
                        .append(String.join("+", r.sample().stream().map(e -> String.format("%.1f", e.ms())).toList()))
                        .append('\t').append(errors).append('\t')
                        .append(String.format("%.2f", os.getCpuLoad())).append('\n');
                sql.append("=== ").append(r.shape().name()).append("  ")
                        .append(r.shape().path() != null ? r.shape().path() : r.shape().note()).append('\n');
                for (Executed e : r.sample()) {
                    sql.append("-- ").append(e.id()).append(String.format("  %.1f ms", e.ms()))
                            .append(e.error() == null ? "" : "  ERROR " + e.error()).append('\n');
                    sql.append(abbreviate(e.sql())).append('\n');
                    sql.append("-- binds: ").append(abbreviateBinds(e.binds())).append('\n');
                    sql.append(explain(c, e)).append('\n');
                }
            }
        }
        Files.writeString(OUT.resolve("ssr-bench.tsv"), tsv, StandardCharsets.UTF_8);
        Files.writeString(OUT.resolve("ssr-sql-explain.txt"), sql, StandardCharsets.UTF_8);
        System.out.println("[b05-ssr-perf] wrote " + OUT.toAbsolutePath() + " at " + LocalDateTime.now());
    }

    static String explain(Connection c, Executed e) {
        StringBuilder sb = new StringBuilder();
        try (PreparedStatement ps = c.prepareStatement("EXPLAIN " + e.sql())) {
            for (int i = 0; i < e.binds().size(); i++) {
                ps.setObject(i + 1, e.binds().get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    sb.append("   explain:");
                    for (String col : List.of("select_type", "table", "type", "key", "key_len", "rows", "filtered",
                            "Extra")) {
                        sb.append(' ').append(col).append('=').append(value(rs, md, col));
                    }
                    sb.append('\n');
                }
            }
        } catch (SQLException ex) {
            sb.append("   explain failed: ").append(ex.getErrorCode()).append('\n');
        }
        return sb.toString();
    }

    static String value(ResultSet rs, ResultSetMetaData md, String col) throws SQLException {
        for (int i = 1; i <= md.getColumnCount(); i++) {
            if (md.getColumnLabel(i).equalsIgnoreCase(col)) {
                return rs.getString(i);
            }
        }
        return "?";
    }

    /** 长列清单与长 IN 列表缩写（只影响报告的可读性，EXPLAIN 用原文）。 */
    static String abbreviate(String sql) {
        String s = sql.replaceAll("SELECT (/\\*\\+[^*]*\\*/ )?([a-z_]+ AS [a-zA-Z]+, ){6,}[^F]*FROM", "SELECT $1<summaryColumns> FROM");
        Matcher m = Pattern.compile("\\((\\?(?:, ?\\?){7,})\\)").matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, "(?×" + (m.group(1).length() - m.group(1).replace("?", "").length()) + ")");
        }
        m.appendTail(out);
        return out.toString();
    }

    static String abbreviateBinds(List<Object> binds) {
        if (binds.size() <= 12) {
            return binds.toString();
        }
        return binds.subList(0, 6) + " … " + binds.subList(binds.size() - 4, binds.size()) + " (" + binds.size() + " 个)";
    }

    // ------------------------------------------------------------------ 合成数据（只在一次性库中）

    static final String D10 = "(SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 "
            + "UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
    static final String WORDS = "'春风','秋月','江湖','长歌','少年','都市','情缘','归来','山河','往事','青春','星辰','大海',"
            + "'迷城','夜行','追光','白鹿','红尘','烟火','风云','剑客','侠客','天涯','明月','花开','雪落','孤岛','边城','远方',"
            + "'故乡','家园','时光','记忆','秘密','真相','迷雾','黎明','黄昏','晨曦','星光'";
    static final String CLASSES = "'剧情','爱情','动作','喜剧','科幻','战争','武侠','冒险','枪战','恐怖','微电影','其它'";
    static final String LETTERS = "'A','B','C','D','E','F','G','H','I','J','K','L','M','N','O','P','Q','R','S','T',"
            + "'U','V','W','X','Y','Z','0','1','2','3','4','5','6','7','8','9'";

    /** 0..n-1 的数列（n ≤ 100000）。 */
    static String numbers(int n) {
        return "(SELECT a.d + b.d * 10 + c.d * 100 + e.d * 1000 + f.d * 10000 AS n FROM " + D10 + " a, " + D10 + " b, "
                + D10 + " c, " + D10 + " e, " + D10 + " f) t WHERE n < " + n;
    }

    /** 补齐模块表与视频分集剧情标记（只在第一次运行时；库中已有文章时跳过）。分布只依赖行号的 CRC32。 */
    static void growModules(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM mac_art")) {
            rs.next();
            if (rs.getLong(1) > 0) {
                System.out.println("[b05-ssr-perf] module tables already grown");
                return;
            }
        }
        long t0 = System.currentTimeMillis();
        // 模块分类：文章 41（顶级）/42（41 的子类）/43；演员 51/52（51 的子类）；网址 61/62（61 的子类）
        int[][] types = {{41, 2, 0}, {42, 2, 41}, {43, 2, 0}, {51, 8, 0}, {52, 8, 51}, {61, 11, 0}, {62, 11, 61}};
        for (int[] t : types) {
            st.execute("INSERT INTO mac_type (type_id,type_name,type_en,type_sort,type_mid,type_pid,type_status,type_tpl,"
                    + "type_tpl_list,type_tpl_detail,type_extend) VALUES (" + t[0] + ",'模块" + t[0] + "','m" + t[0] + "',"
                    + t[0] + "," + t[1] + "," + t[2] + ",1,'type.html','show.html','detail.html','{}')");
        }
        String allBits = "{\"1\":\"1\",\"2\":\"2\",\"3\":\"3\",\"4\":\"4\",\"5\":\"5\"}";
        StringBuilder ids = new StringBuilder(",");
        StringBuilder pop = new StringBuilder("{");
        List<Integer> all = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            all.add(i);
        }
        Arrays.stream(types).forEach(t -> all.add(t[0]));
        for (int id : all) {
            ids.append(id).append(',');
            pop.append(pop.length() == 1 ? "" : ",").append('"').append(id).append("\":")
                    .append(id == 28 ? "{\"2\":\"2\"}" : allBits);
        }
        pop.append('}');
        st.execute("UPDATE mac_group SET group_type='" + ids + "', group_popedom='" + pop + "' WHERE group_id=1");
        String h = "CRC32(CONCAT('h', n))";
        String time = "(" + NOW + " - " + h + " % 157680000)";
        for (String table : List.of("mac_art", "mac_actor", "mac_role", "mac_website", "mac_topic")) {
            st.execute("ALTER TABLE " + table + " DISABLE KEYS");
        }
        st.execute("INSERT INTO mac_art (art_id,type_id,type_id_1,art_name,art_sub,art_en,art_status,art_letter,art_tag,"
                + "art_class,art_level,art_hits,art_hits_week,art_score,art_time,art_time_add,art_title,art_note,"
                + "art_content,art_pic_screenshot) SELECT n + 1, CASE WHEN " + h + " % 100 < 50 THEN 42 WHEN " + h
                + " % 100 < 80 THEN 41 ELSE 43 END, IF(" + h + " % 100 < 50, IF(" + h + " % 7 = 0, 43, 41), 0), "
                + "CONCAT(ELT(1 + " + h + " % 40, " + WORDS + "), ELT(1 + (" + h + " DIV 40) % 40, " + WORDS + "), n), "
                + "IF(" + h + " % 10 < 3, ELT(1 + (" + h + " DIV 7) % 40, " + WORDS + "), ''), CONCAT('art', n), "
                + "IF(" + h + " % 100 < 95, 1, 0), ELT(1 + (" + h + " DIV 3) % 36, " + LETTERS + "), "
                + "CONCAT(ELT(1 + " + h + " % 12, " + CLASSES + "), ',', ELT(1 + (" + h + " DIV 12) % 12, " + CLASSES + ")), "
                + "ELT(1 + (" + h + " DIV 5) % 12, " + CLASSES + "), (" + h + " DIV 11) % 10, (" + h + " DIV 13) % 1000000, "
                + "(" + h + " DIV 17) % 50000, (" + h + " % 100) / 10, " + time + ", " + time + " - " + h + " % 31536000, "
                + "'', '', REPEAT('合成文章正文，只用于测量。', 60), '' FROM " + numbers(100000));
        st.execute("INSERT INTO mac_actor (actor_id,type_id,actor_name,actor_en,actor_alias,actor_sex,actor_area,"
                + "actor_starsign,actor_blood,actor_status,actor_letter,actor_level,actor_hits_week,actor_time,"
                + "actor_time_add,actor_content) SELECT n + 1, IF(" + h + " % 3 = 0, 52, 51), "
                + "CONCAT(ELT(1 + " + h + " % 40, " + WORDS + "), n), CONCAT('actor', n), '', "
                + "ELT(1 + " + h + " % 2, '男', '女'), ELT(1 + (" + h + " DIV 2) % 8, '大陆','大陆','大陆','香港','台湾',"
                + "'日本','韩国','美国'), ELT(1 + (" + h + " DIV 16) % 12, '白羊座','金牛座','双子座','巨蟹座','狮子座',"
                + "'处女座','天秤座','天蝎座','射手座','摩羯座','水瓶座','双鱼座'), ELT(1 + (" + h + " DIV 192) % 4, 'A','B',"
                + "'O','AB'), IF(" + h + " % 100 < 95, 1, 0), ELT(1 + (" + h + " DIV 3) % 36, " + LETTERS + "), "
                + "(" + h + " DIV 11) % 10, (" + h + " DIV 17) % 50000, " + time + ", " + time + " - " + h
                + " % 31536000, REPEAT('合成演员简介，只用于测量。', 40) FROM " + numbers(50000));
        st.execute("INSERT INTO mac_role (role_id,role_rid,role_name,role_en,role_actor,role_status,role_letter,"
                + "role_level,role_hits_week,role_time,role_time_add,role_content) SELECT n + 1, 1 + " + h
                + " % 300000, CONCAT(ELT(1 + " + h + " % 40, " + WORDS + "), '角色', n), CONCAT('role', n), '', "
                + "IF(" + h + " % 100 < 95, 1, 0), ELT(1 + (" + h + " DIV 3) % 36, " + LETTERS + "), (" + h
                + " DIV 11) % 10, (" + h + " DIV 17) % 50000, " + time + ", " + time + " - " + h + " % 31536000, "
                + "REPEAT('合成角色介绍，只用于测量。', 40) FROM " + numbers(100000));
        st.execute("INSERT INTO mac_website (website_id,type_id,website_name,website_en,website_sub,website_tag,"
                + "website_class,website_area,website_lang,website_status,website_letter,website_level,"
                + "website_hits_week,website_referer,website_time,website_time_add,website_content,"
                + "website_pic_screenshot) SELECT n + 1, IF(" + h + " % 3 = 0, 62, 61), CONCAT(ELT(1 + " + h
                + " % 40, " + WORDS + "), '站', n), CONCAT('site', n), '', ELT(1 + (" + h + " DIV 5) % 12, " + CLASSES
                + "), ELT(1 + (" + h + " DIV 7) % 12, " + CLASSES + "), ELT(1 + (" + h + " DIV 2) % 6, '大陆','大陆',"
                + "'大陆','香港','台湾','美国'), ELT(1 + (" + h + " DIV 12) % 4, '国语','国语','粤语','英语'), "
                + "IF(" + h + " % 100 < 95, 1, 0), ELT(1 + (" + h + " DIV 3) % 36, " + LETTERS + "), (" + h
                + " DIV 11) % 10, (" + h + " DIV 17) % 50000, (" + h + " DIV 19) % 60000, " + time + ", " + time
                + " - " + h + " % 31536000, REPEAT('合成网址介绍，只用于测量。', 40), '' FROM " + numbers(50000));
        st.execute("INSERT INTO mac_topic (topic_id,topic_name,topic_en,topic_sub,topic_tag,topic_type,topic_status,"
                + "topic_letter,topic_level,topic_hits_week,topic_time,topic_time_add,topic_content,topic_extend,"
                + "topic_rel_vod,topic_rel_art) SELECT n + 1, CONCAT(ELT(1 + " + h + " % 40, " + WORDS + "), '专题', n), "
                + "CONCAT('topic', n), '', ELT(1 + (" + h + " DIV 5) % 12, " + CLASSES + "), ELT(1 + (" + h
                + " DIV 7) % 12, " + CLASSES + "), IF(" + h + " % 100 < 95, 1, 0), ELT(1 + (" + h + " DIV 3) % 36, "
                + LETTERS + "), (" + h + " DIV 11) % 10, (" + h + " DIV 17) % 50000, " + time + ", " + time + " - "
                + h + " % 31536000, REPEAT('合成专题介绍，只用于测量。', 40), '', '', '' FROM " + numbers(20000));
        for (String table : List.of("mac_art", "mac_actor", "mac_role", "mac_website", "mac_topic")) {
            st.execute("ALTER TABLE " + table + " ENABLE KEYS");
            st.execute("ANALYZE TABLE " + table);
        }
        // 视频的分集剧情：每 10 行一行（剧情搜索页 plot=1 的数据），以及少量周几/电视台，使对应条件有命中
        st.execute("UPDATE mac_vod SET vod_plot = 1 WHERE vod_id % 10 = 0");
        st.execute("UPDATE mac_vod SET vod_weekday = '一,三,五', vod_tv = 'CCTV1' WHERE vod_id % 97 = 0");
        st.execute("ANALYZE TABLE mac_vod");
        System.out.println("[b05-ssr-perf] module tables grown in " + (System.currentTimeMillis() - t0) + " ms");
    }

    static String contextOf(Statement st) throws SQLException {
        StringBuilder sb = new StringBuilder("database " + db + "\n");
        for (String q : List.of("SELECT VERSION()",
                "SELECT table_name, engine, table_rows, avg_row_length, data_length, index_length FROM "
                        + "information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('mac_vod','mac_art',"
                        + "'mac_actor','mac_role','mac_website','mac_topic') ORDER BY table_name",
                "SELECT COUNT(*) AS vod_visible FROM mac_vod WHERE vod_status = 1 AND type_id BETWEEN 1 AND 25",
                "SELECT COUNT(*) AS vod_plot FROM mac_vod WHERE vod_status = 1 AND vod_plot = 1",
                "SELECT type_id, COUNT(*) FROM mac_art WHERE art_status = 1 GROUP BY type_id",
                "SELECT type_id, COUNT(*) FROM mac_actor WHERE actor_status = 1 GROUP BY type_id",
                "SELECT type_id, COUNT(*) FROM mac_website WHERE website_status = 1 GROUP BY type_id",
                "SELECT COUNT(*) AS role_published FROM mac_role WHERE role_status = 1",
                "SELECT COUNT(*) AS topic_published FROM mac_topic WHERE topic_status = 1")) {
            sb.append("> ").append(q).append('\n');
            try (ResultSet rs = st.executeQuery(q)) {
                int cols = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    StringBuilder row = new StringBuilder("  ");
                    for (int i = 1; i <= cols; i++) {
                        row.append(i == 1 ? "" : "\t").append(rs.getString(i));
                    }
                    sb.append(row).append('\n');
                }
            }
        }
        return sb.toString();
    }

    static List<String> firstValues(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 连接

    static String enc(String v) {
        return java.net.URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name);
        }
        return v;
    }

    static String jdbcUrl(String database) {
        int q = adminUrl.indexOf('?');
        String params = q >= 0 ? adminUrl.substring(q) : "?characterEncoding=UTF-8";
        String path = q >= 0 ? adminUrl.substring(0, q) : adminUrl;
        if (!path.endsWith("/")) {
            path = path + "/";
        }
        return path + database + params;
    }

    static Connection admin() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(db), adminUser, adminPassword);
    }
}
