import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * B05（MACC-29）中规模合成库测量：IN/OR 多值、年份区间、随机排序候选与旧标签条件的耗时与 EXPLAIN。
 * 报告见 docs/architecture/15-b05-perf-report.md。
 *
 * <p><b>不是测试，不进 {@code ./mvnw verify}</b>（Maven 不编译 src/test/perf/），以 Java 单文件方式运行，只依赖 JDK 21 与
 * mysql-connector-j-8.2.0.jar。只使用 MACCMS_IT_ADMIN_URL / _USERNAME / _PASSWORD；只创建、读写、删除名称为
 * {@code maccms_it_<yyyyMMddHHmmss>_<8位十六进制>} 的一次性库，不以其他库为默认库连接、不读其他库的数据或元数据；
 * 不改服务器全局变量；冷态只执行 {@code FLUSH TABLES <一次性库>.mac_vod}。
 *
 * <p>SQL 文本照抄 backend/src/main/resources/mapper/content/VodMapper.xml（分支 agent/claude/macc-29，6f5e2af）的
 * publishedInTypes + matchingConditions + legacyConditions + pageOrderAndLimit，条件顺序与 XML 相同；{@code http}
 * 子命令经被测应用的 MyBatis DEBUG 日志核对 API 可达形态的 SQL 文本（去掉全部空白后逐字）。
 *
 * <p>子命令：create | grow &lt;rows&gt; | context &lt;label&gt; | bench &lt;label&gt; [形态前缀,…] |
 * http &lt;label&gt; &lt;jar&gt; [形态前缀,…] | drop
 */
public class B05Perf {

    static final Pattern DB_NAME = Pattern.compile("^maccms_it_[0-9]{14}_[0-9a-f]{8}$");
    static final Path WORK = Path.of("target", "perf-b05");
    static final Path STATE = WORK.resolve("state.properties");
    static final Path INSTALL_SQL = Path.of("..", "application", "install", "sql", "install.sql");
    static final long SEED = 20261017L;
    /** 合成数据的"当前时间"（固定，便于复现）。 */
    static final long NOW = 1_790_000_000L;

    static String adminUrl;
    static String adminUser;
    static String adminPassword;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: create | grow <rows> | context <label> | bench <label> [prefixes] | "
                    + "http <label> <jar> [prefixes] | drop");
            System.exit(2);
        }
        adminUrl = env("MACCMS_IT_ADMIN_URL");
        adminUser = env("MACCMS_IT_ADMIN_USERNAME");
        adminPassword = env("MACCMS_IT_ADMIN_PASSWORD");
        Files.createDirectories(WORK);
        switch (args[0]) {
            case "create" -> create();
            case "grow" -> grow(Integer.parseInt(args[1]));
            case "context" -> context(args[1]);
            case "bench" -> bench(args[1], args.length > 2 ? args[2] : null);
            case "http" -> http(args[1], Path.of(args[2]), args.length > 3 ? args[3] : null);
            case "drop" -> drop();
            default -> throw new IllegalArgumentException("unknown command " + args[0]);
        }
    }

    // ================================================================== 连接与库生命周期（同 SearchPerf，库名前缀改为 maccms_it_）

    static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name);
        }
        return v;
    }

    static String jdbcUrlFor(String db, String extra) {
        int q = adminUrl.indexOf('?');
        String params = q >= 0 ? adminUrl.substring(q) : "?characterEncoding=UTF-8";
        String path = q >= 0 ? adminUrl.substring(0, q) : adminUrl;
        if (!path.endsWith("/")) {
            path = path + "/";
        }
        return path + (db == null ? "" : db) + params + (extra == null ? "" : "&" + extra);
    }

    static Connection admin(String db) throws SQLException {
        return DriverManager.getConnection(jdbcUrlFor(db, null), adminUser, adminPassword);
    }

    static String dbName() throws IOException {
        Properties p = new Properties();
        try (var in = Files.newBufferedReader(STATE)) {
            p.load(in);
        }
        String db = p.getProperty("db");
        if (db == null || !DB_NAME.matcher(db).matches()) {
            throw new IllegalStateException("state.properties 中的库名不合法");
        }
        return db;
    }

    static int schemaCount(Statement st, String db) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"
                + db + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    static void create() throws Exception {
        if (Files.exists(STATE)) {
            throw new IllegalStateException("已有 state.properties（上一个一次性库未删除？先执行 drop）");
        }
        byte[] rnd = new byte[4];
        new SecureRandom().nextBytes(rnd);
        String db = "maccms_it_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "_" + String.format("%02x%02x%02x%02x", rnd[0], rnd[1], rnd[2], rnd[3]);
        if (!DB_NAME.matcher(db).matches()) {
            throw new IllegalStateException("库名不合法: " + db);
        }
        try (Connection c = admin(null); Statement st = c.createStatement()) {
            if (schemaCount(st, db) != 0) {
                throw new IllegalStateException("库已存在: " + db);
            }
            st.execute("CREATE DATABASE `" + db + "` DEFAULT CHARACTER SET utf8");
        }
        Files.writeString(STATE, "db=" + db + "\n");
        log("created disposable database " + db);
        try (Connection c = admin(db); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT DATABASE(), COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE()")) {
                rs.next();
                if (!db.equals(rs.getString(1)) || rs.getInt(2) != 0) {
                    throw new IllegalStateException("一次性库不是空库，拒绝导入 install.sql");
                }
            }
            for (String sql : installStatements()) {
                st.execute(sql);
            }
            seedTypes(st);
        }
        log("schema imported from application/install/sql/install.sql; 30 categories seeded");
    }

    static void drop() throws Exception {
        String db = dbName();
        try (Connection c = admin(null); Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + db + "`");
            if (schemaCount(st, db) != 0) {
                throw new IllegalStateException("删除后仍存在: " + db);
            }
        }
        Files.delete(STATE);
        log("dropped disposable database " + db + " (information_schema.schemata count = 0, verified absent)");
    }

    static List<String> installStatements() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(INSTALL_SQL, StandardCharsets.UTF_8).split("\\r?\\n")) {
            if (!line.startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        List<String> out = new ArrayList<>();
        for (String stmt : sb.toString().split(";\\s*\\n")) {
            if (!stmt.isBlank()) {
                out.add(stmt.trim());
            }
        }
        return out;
    }

    // ================================================================== 合成数据

    /** 与 SearchPerf 相同的 30 个分类：可见 1..25（1–5 顶级，6–25 子类）；26–30 不可见（停用/父停用/游客无权限/文章/停用子类）。 */
    static void seedTypes(Statement st) throws SQLException {
        st.execute("DELETE FROM mac_type");
        String[] tops = {"电影", "电视剧", "综艺", "动漫", "纪录片"};
        for (int i = 1; i <= 5; i++) {
            type(st, i, tops[i - 1], i, 1, 0, 1);
        }
        for (int i = 6; i <= 25; i++) {
            type(st, i, "子类" + i, i, 1, (i - 6) / 4 + 1, 1);
        }
        type(st, 26, "停用顶级", 26, 1, 0, 0);
        type(st, 27, "停用父下子类", 27, 1, 26, 1);
        type(st, 28, "游客不可列表", 28, 1, 0, 1);
        type(st, 29, "文章分类", 29, 2, 0, 1);
        type(st, 30, "停用子类", 30, 1, 1, 0);
        String allBits = "{\"1\":\"1\",\"2\":\"2\",\"3\":\"3\",\"4\":\"4\",\"5\":\"5\"}";
        StringBuilder types = new StringBuilder(",");
        StringBuilder pop = new StringBuilder("{");
        for (int i = 1; i <= 30; i++) {
            types.append(i).append(',');
            pop.append(i == 1 ? "" : ",").append('"').append(i).append("\":")
                    .append(i == 28 ? "{\"2\":\"2\"}" : allBits);
        }
        pop.append('}');
        st.execute("UPDATE mac_group SET group_type='" + types + "', group_popedom='" + pop + "' WHERE group_id=1");
    }

    static void type(Statement st, int id, String name, int sort, int mid, int pid, int status) throws SQLException {
        st.execute("INSERT INTO mac_type (type_id,type_name,type_en,type_sort,type_mid,type_pid,type_status,"
                + "type_tpl,type_tpl_list,type_tpl_detail,type_extend) VALUES (" + id + ",'" + name + "','t" + id
                + "'," + sort + "," + mid + "," + pid + "," + status + ",'type.html','show.html','detail.html','{}')");
    }

    static final String[] WORDS = ("春风 秋月 江湖 长歌 少年 都市 情缘 归来 山河 往事 青春 星辰 大海 迷城 夜行 追光 "
            + "白鹿 红尘 烟火 风云 剑客 侠客 天涯 明月 花开 雪落 孤岛 边城 远方 故乡 家园 时光 记忆 秘密 真相 "
            + "迷雾 黎明 黄昏 晨曦 暗夜 光影 回声 心跳 微光 灯塔 港湾 码头 列车 站台 街角 小巷 胡同 庭院 "
            + "书生 将军 公主 王妃 太子 掌门 捕快 神探 律师 医生 警察 老师 学生 厨师 画家 歌手 舞者 "
            + "传说 奇缘 战记 风暴 烈火 寒冰 雷霆 飞鸟 白马 黑猫 金鱼 狐仙 青蛇 白蛇 熊猫 骏马 雄鹰").split(" ");
    static final String[] SYL = ("chun feng qiu yue jiang hu chang ge shao nian du shi qing yuan gui lai shan he wang "
            + "xing chen da hai mi cheng ye zhui guang bai lu hong yan huo yun jian ke xia tian ya ming hua kai")
            .split(" ");
    static final String[] CLASSES = {"剧情", "爱情", "动作", "喜剧", "科幻", "战争", "武侠", "冒险", "枪战", "恐怖",
            "微电影", "其它"};
    static final int[] CLASS_W = {25, 15, 15, 12, 8, 5, 5, 5, 3, 4, 1, 2};
    static final String[] AREAS = {"大陆", "美国", "韩国", "日本", "香港", "台湾", "泰国", "英国", "法国", "新加坡",
            "马来西亚", "印度", "加拿大", "西班牙", "俄罗斯", "其它"};
    static final int[] AREA_W = {35, 15, 10, 10, 8, 6, 3, 3, 2, 1, 1, 1, 1, 1, 1, 2};
    static final String[] LANGS = {"国语", "英语", "粤语", "闽南语", "韩语", "日语", "法语", "德语", "其它"};
    static final int[] LANG_W = {40, 20, 8, 2, 10, 10, 3, 2, 5};
    static final String[] VERSIONS = {"高清版", "超清版", "蓝光版", "抢先版", "TC", ""};
    static final int[] VERSION_W = {50, 20, 10, 5, 5, 10};
    static final String[] STATES = {"正片", "预告片", "花絮", ""};
    static final int[] STATE_W = {80, 8, 4, 8};
    static final String[] PEOPLE = ("张伟 王芳 李娜 刘洋 陈杰 杨静 赵磊 黄敏 周强 吴婷 徐涛 孙丽 胡军 朱红 高峰 林霞 "
            + "何平 郭亮 马超 罗琳 梁斌 宋佳 郑浩 谢雨 韩冰 唐宁 冯刚 于洁 董明 萧然").split(" ");
    static final String[] SENTENCES = buildSentences();

    static String[] buildSentences() {
        Random r = new Random(SEED);
        String[] out = new String[512];
        for (int i = 0; i < out.length; i++) {
            StringBuilder sb = new StringBuilder();
            int n = 6 + r.nextInt(8);
            for (int j = 0; j < n; j++) {
                sb.append(WORDS[r.nextInt(WORDS.length)]).append(r.nextInt(5) == 0 ? "，" : "");
            }
            out[i] = sb.append('。').toString();
        }
        return out;
    }

    static int weighted(Random r, int[] w) {
        int total = Arrays.stream(w).sum();
        int v = r.nextInt(total);
        for (int i = 0; i < w.length; i++) {
            v -= w[i];
            if (v < 0) {
                return i;
            }
        }
        return w.length - 1;
    }

    static final String INSERT_COLS = "vod_id,type_id,type_id_1,vod_name,vod_sub,vod_en,vod_status,vod_letter,"
            + "vod_tag,vod_class,vod_pic,vod_actor,vod_director,vod_blurb,vod_remarks,vod_area,vod_lang,vod_year,"
            + "vod_version,vod_state,vod_isend,vod_level,vod_hits,vod_hits_day,vod_hits_week,vod_hits_month,vod_up,"
            + "vod_down,vod_score,vod_score_all,vod_score_num,vod_time,vod_time_add,vod_content,vod_play_from,"
            + "vod_play_url,vod_down_url,vod_plot_name,vod_plot_detail";

    /** 第 id 行的插入值，只依赖 id 与固定种子。分布沿用 SearchPerf，另给 letter/state/version/isend/hits_week 加偏斜。 */
    static Object[] row(int id) {
        Random r = new Random(SEED * 31 + id);
        int typeRoll = r.nextInt(100);
        int typeId = typeRoll < 92 ? 6 + r.nextInt(20) : typeRoll < 95 ? 1 + r.nextInt(5) : 26 + r.nextInt(5);
        int typeId1 = typeId >= 6 && typeId <= 25 ? (typeId - 6) / 4 + 1 : 0;
        StringBuilder name = new StringBuilder();
        StringBuilder en = new StringBuilder();
        int n = 2 + r.nextInt(2);
        for (int i = 0; i < n; i++) {
            name.append(WORDS[r.nextInt(WORDS.length)]);
            en.append(SYL[r.nextInt(SYL.length)]).append(SYL[r.nextInt(SYL.length)]);
        }
        if (r.nextInt(8) == 0) {
            name.append("第").append(2 + r.nextInt(5)).append("季");
        }
        String sub = r.nextInt(100) < 30 ? WORDS[r.nextInt(WORDS.length)] + WORDS[r.nextInt(WORDS.length)] : "";
        int status = r.nextInt(100) < 95 ? 1 : 0;
        String letter = r.nextInt(100) < 5 ? String.valueOf((char) ('0' + r.nextInt(10)))
                : String.valueOf((char) ('A' + r.nextInt(26)));
        StringBuilder cls = new StringBuilder();
        List<Integer> used = new ArrayList<>();
        int nc = 1 + r.nextInt(3);
        while (used.size() < nc) {
            int c = weighted(r, CLASS_W);
            if (!used.contains(c)) {
                used.add(c);
                cls.append(cls.isEmpty() ? "" : ",").append(CLASSES[c]);
            }
        }
        String area = AREAS[weighted(r, AREA_W)];
        String lang = LANGS[weighted(r, LANG_W)];
        String year = r.nextInt(100) < 2 ? "" : String.valueOf(2021 - (int) (Math.pow(r.nextDouble(), 1.6) * 22));
        String version = VERSIONS[weighted(r, VERSION_W)];
        String state = STATES[weighted(r, STATE_W)];
        int isEnd = r.nextInt(100) < 55 ? 1 : 0;
        int level = r.nextInt(100) < 98 ? 0 : 1 + r.nextInt(9);
        int hits = (int) (Math.pow(r.nextDouble(), 4) * 1_000_000);
        int hitsWeek = (int) (Math.pow(r.nextDouble(), 4) * 50_000);
        long time = NOW - r.nextInt(5 * 365 * 86400);
        long timeAdd = time - r.nextInt(365 * 86400);
        String actor = PEOPLE[r.nextInt(PEOPLE.length)] + "," + PEOPLE[r.nextInt(PEOPLE.length)] + ","
                + PEOPLE[r.nextInt(PEOPLE.length)];
        StringBuilder content = new StringBuilder("<p>");
        int targetBytes = 1000 + r.nextInt(2000);
        while (content.length() * 3 < targetBytes) {
            content.append(SENTENCES[r.nextInt(SENTENCES.length)]);
        }
        content.append("</p>");
        String blurb = content.substring(3, Math.min(content.length() - 4, 3 + 80));
        StringBuilder play = new StringBuilder();
        int playTarget = 500 + r.nextInt(1000);
        for (int ep = 1; play.length() < playTarget; ep++) {
            play.append(ep == 1 ? "" : "#").append(String.format("第%02d集$https://media.example.invalid/v/%d/%02d.m3u8",
                    ep, id, ep));
        }
        int score = r.nextInt(100);
        return new Object[]{id, typeId, typeId1, name.toString(), sub, en.toString(), status, letter, "",
                cls.toString(), "https://img.example.invalid/vod/" + id + ".jpg", actor,
                PEOPLE[r.nextInt(PEOPLE.length)], blurb, "更新至第" + (1 + r.nextInt(40)) + "集", area, lang, year,
                version, state, isEnd, level, hits, hitsWeek / 7, hitsWeek, hitsWeek * 4 + r.nextInt(1000),
                r.nextInt(5000), r.nextInt(500), (score / 10) + "." + (score % 10), r.nextInt(100000),
                r.nextInt(10000), time, timeAdd, content.toString(), "m3u8", play.toString(), "", "", ""};
    }

    static void grow(int target) throws Exception {
        String db = dbName();
        try (Connection c = DriverManager.getConnection(jdbcUrlFor(db, "rewriteBatchedStatements=true"), adminUser,
                adminPassword); Statement st = c.createStatement()) {
            int have;
            try (ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(vod_id),0) FROM mac_vod")) {
                rs.next();
                have = rs.getInt(1);
            }
            if (have >= target) {
                log("already has " + have + " rows");
                return;
            }
            long t0 = System.nanoTime();
            st.execute("ALTER TABLE mac_vod DISABLE KEYS");
            String sql = "INSERT INTO mac_vod (" + INSERT_COLS + ") VALUES ("
                    + "?,".repeat(INSERT_COLS.split(",").length - 1) + "?)";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int batch = 0;
                for (int id = have + 1; id <= target; id++) {
                    Object[] v = row(id);
                    for (int i = 0; i < v.length; i++) {
                        ps.setObject(i + 1, v[i]);
                    }
                    ps.addBatch();
                    if (++batch == 400) {
                        ps.executeBatch();
                        batch = 0;
                    }
                    if (id % 100_000 == 0) {
                        log("inserted up to " + id);
                    }
                }
                if (batch > 0) {
                    ps.executeBatch();
                }
            }
            long t1 = System.nanoTime();
            st.execute("ALTER TABLE mac_vod ENABLE KEYS");
            long t2 = System.nanoTime();
            try (ResultSet rs = st.executeQuery("ANALYZE TABLE mac_vod")) {
                while (rs.next()) {
                    log("analyze: " + rs.getString(3) + " " + rs.getString(4));
                }
            }
            log(String.format("grow %d -> %d: insert %.1fs, enable keys %.1fs, analyze %.1fs", have, target,
                    (t1 - t0) / 1e9, (t2 - t1) / 1e9, (System.nanoTime() - t2) / 1e9));
        }
    }

    // ================================================================== 上下文

    static void context(String label) throws Exception {
        String db = dbName();
        StringBuilder sb = new StringBuilder();
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        sb.append("## machine\nos\t").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append("\ncpu\t").append(System.getenv("PROCESSOR_IDENTIFIER"))
                .append("; logical=").append(Runtime.getRuntime().availableProcessors()).append("\nmemory_total_mb\t")
                .append(os.getTotalMemorySize() / 1024 / 1024).append("\nmemory_free_mb\t")
                .append(os.getFreeMemorySize() / 1024 / 1024).append("\njava\t")
                .append(System.getProperty("java.vm.name")).append(' ').append(System.getProperty("java.version"))
                .append('\n');
        try (Connection c = admin(db); Statement st = c.createStatement()) {
            sb.append("\n## mysql\n");
            query(st, "SELECT VERSION(), @@version_comment, @@character_set_connection, @@collation_connection", sb);
            sb.append("\n## variables\n");
            query(st, "SHOW GLOBAL VARIABLES WHERE Variable_name IN ('key_buffer_size','query_cache_type',"
                    + "'read_buffer_size','read_rnd_buffer_size','sort_buffer_size','tmp_table_size','max_heap_table_size',"
                    + "'concurrent_insert','low_priority_updates','max_execution_time','eq_range_index_dive_limit',"
                    + "'range_optimizer_max_mem_size','optimizer_switch','max_length_for_sort_data','sql_mode')", sb);
            sb.append("\n## mac_vod table status\n");
            query(st, "SELECT table_rows, avg_row_length, data_length, index_length, row_format, engine, "
                    + "table_collation FROM information_schema.tables WHERE table_schema=DATABASE() "
                    + "AND table_name='mac_vod'", sb);
            sb.append("\n## distribution (whole table / visible = vod_status=1 AND type_id 1..25)\n");
            query(st, "SELECT COUNT(*) total, SUM(vod_status=1) status1, SUM(vod_status=1 AND type_id BETWEEN 1 AND 25) "
                    + "visible, MIN(vod_id) min_id, MAX(vod_id) max_id, ROUND(AVG(LENGTH(vod_content))) avg_content_bytes, "
                    + "ROUND(AVG(LENGTH(vod_play_url))) avg_play_url_bytes FROM mac_vod", sb);
            for (String col : List.of("vod_area", "vod_lang", "vod_year", "vod_letter", "vod_state", "vod_version",
                    "vod_isend")) {
                sb.append("\n### ").append(col).append(" (visible rows)\n");
                query(st, "SELECT " + col + " v, COUNT(*) n FROM mac_vod WHERE vod_status=1 AND type_id BETWEEN 1 AND 25 "
                        + "GROUP BY " + col + " ORDER BY n DESC", sb);
            }
            sb.append("\n## mac_vod indexes (cardinality)\n");
            query(st, "SELECT index_name, column_name, cardinality FROM information_schema.statistics WHERE "
                    + "table_schema=DATABASE() AND table_name='mac_vod' ORDER BY index_name", sb);
        }
        Path out = WORK.resolve(label + "-context.tsv");
        Files.writeString(out, sb.toString());
        log("wrote " + out);
    }

    static void query(Statement st, String sql, StringBuilder sb) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            for (int i = 1; i <= n; i++) {
                sb.append(rs.getMetaData().getColumnLabel(i)).append(i == n ? "\n" : "\t");
            }
            while (rs.next()) {
                for (int i = 1; i <= n; i++) {
                    sb.append(rs.getString(i)).append(i == n ? "\n" : "\t");
                }
            }
        }
    }

    // ================================================================== 条件与 SQL（VodMapper.xml 原文，条件顺序与 XML 相同）

    record Cond(String sql, List<Object> params) {
    }

    record Sql(String text, List<Object> params) {
    }

    static Sql noHint(Sql sql) {
        return new Sql(sql.text.replace(HINT, ""), sql.params);
    }

    static String marks(int n) {
        return "(" + String.join(",", Collections.nCopies(n, "?")) + ")";
    }

    static Cond eq(String col, Object v) {
        return new Cond("AND " + col + " = ?", List.of(v));
    }

    static Cond in(String col, List<?> vs) {
        return new Cond("AND " + col + " IN " + marks(vs.size()), List.copyOf(vs));
    }

    static Cond notIn(String col, List<?> vs) {
        return new Cond("AND " + col + " NOT IN " + marks(vs.size()), List.copyOf(vs));
    }

    /** classesPattern（单值 1.1.0 形式）。 */
    static Cond like(String col, String item) {
        return new Cond("AND " + col + " LIKE ? ESCAPE '!'", List.of(contains(item)));
    }

    /** classesPatterns / actorPatterns / directorPatterns：{@code AND (col LIKE ? ESCAPE '!' OR …)}。 */
    static Cond likeAny(String col, List<String> items) {
        List<String> parts = new ArrayList<>();
        List<Object> p = new ArrayList<>();
        for (String it : items) {
            parts.add(col + " LIKE ? ESCAPE '!'");
            p.add(contains(it));
        }
        return new Cond("AND (" + String.join(" OR ", parts) + ")", p);
    }

    static Cond raw(String sql, Object... params) {
        return new Cond(sql, List.of(params));
    }

    /** VideoFilter.contains：'%' + 以 '!' 转义 ! % _ + '%'。 */
    static String contains(String v) {
        StringBuilder sb = new StringBuilder("%");
        for (char ch : v.toCharArray()) {
            if (ch == '!' || ch == '%' || ch == '_') {
                sb.append('!');
            }
            sb.append(ch);
        }
        return sb.append('%').toString();
    }

    /** VideoFilter.years：区间逐年展开为 4 位字符串。 */
    static List<String> years(int from, int to) {
        List<String> out = new ArrayList<>();
        for (int y = Math.min(from, to); y <= Math.max(from, to); y++) {
            out.add(String.format("%04d", y));
        }
        return out;
    }

    static final String SUMMARY_COLUMNS = """
            vod_id AS id, type_id AS categoryId, vod_name AS name, vod_sub AS subTitle, vod_en AS enName,
            vod_letter AS letter, vod_color AS color, vod_class AS classes, vod_tag AS tag, vod_pic AS pic,
            vod_pic_thumb AS picThumb, vod_pic_slide AS picSlide, vod_actor AS actor, vod_director AS director,
            vod_blurb AS blurb, vod_remarks AS remarks, vod_area AS area, vod_lang AS lang, vod_year AS year,
            vod_version AS version, vod_state AS state, vod_serial AS serial, vod_total AS total, vod_isend AS isEnd,
            vod_level AS level, vod_copyright AS copyright, vod_score AS score, vod_score_num AS scoreNum,
            vod_hits AS hits, vod_hits_day AS hitsDay, vod_hits_week AS hitsWeek, vod_hits_month AS hitsMonth,
            vod_up AS up, vod_down AS down, vod_time AS updatedAt, vod_time_add AS addedAt,
            (CHAR_LENGTH(vod_pwd) > 0) AS detailPasswordRequired""";
    static final String HINT = "/*+ MAX_EXECUTION_TIME(3000) */ ";
    static final String ID_RANGE_SQL = "SELECT " + HINT + "MIN(vod_id) AS minId, MAX(vod_id) AS maxId FROM mac_vod";
    /** CategoryCatalog.allIds()：可见分类 1..25。 */
    static final List<Object> VISIBLE = new ArrayList<>(java.util.stream.IntStream.rangeClosed(1, 25).boxed().toList());

    /** publishedInTypes + 条件；conds 为空时是 1.0.0 的无条件语句（无执行时限）。 */
    static String where(List<Cond> conds, List<Object> p) {
        StringBuilder w = new StringBuilder("vod_status = 1 AND type_id IN " + marks(VISIBLE.size()));
        p.addAll(VISIBLE);
        for (Cond c : conds) {
            w.append(' ').append(c.sql);
            p.addAll(c.params);
        }
        return w.toString();
    }

    /** countPublished / countPublishedMatching。 */
    static Sql countSql(List<Cond> conds) {
        List<Object> p = new ArrayList<>();
        String w = where(conds, p);
        return new Sql("SELECT " + (conds.isEmpty() ? "" : HINT) + "COUNT(*) FROM mac_vod WHERE " + w, p);
    }

    /** selectPublishedPage / selectPublishedMatchingPage + pageOrderAndLimit（倒序）。 */
    static Sql pageSql(List<Cond> conds, String sortCol, long offset, int limit) {
        List<Object> p = new ArrayList<>();
        String w = where(conds, p);
        p.add(offset);
        p.add(limit);
        return new Sql("SELECT " + (conds.isEmpty() ? "" : HINT) + SUMMARY_COLUMNS + " FROM mac_vod WHERE " + w
                + " ORDER BY " + sortCol + " DESC, vod_id DESC LIMIT ?, ?", p);
    }

    static List<Long> spreadIds(int n, long step, long start) {
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(start + i * step);
        }
        return out;
    }

    // ================================================================== 形态

    /**
     * kind: list = 普通列表（count + 第 1 页 / 深分页，time 与 hits 倒序）；rnd1 = 随机排序算法一；rnd2 = 算法二。
     * api: 经 /api/v1/videos 可达时的查询串（不含 sort/page），null 表示只有内部入口（B05 旧标签条件、SSR tid）。
     */
    record Shape(String name, String kind, String api, List<Cond> conds) {
    }

    static final long DAY = 86400;
    static final List<Shape> SHAPES = List.of(
            new Shape("base", "list", "", List.of()),
            // a. IN 多值
            new Shape("a.area1", "list", "area=日本", List.of(eq("vod_area", "日本"))),
            new Shape("a.area3", "list", "area=日本,韩国,美国", List.of(in("vod_area", List.of("日本", "韩国", "美国")))),
            // API 的 area 上限 20 码点：两字地区最多 7 项（area7）；10 项只在语句层测量（内部入口/单字值时可达）
            new Shape("a.area7", "list", "area=" + String.join(",", Arrays.copyOfRange(AREAS, 1, 8)),
                    List.of(in("vod_area", List.of(Arrays.copyOfRange(AREAS, 1, 8))))),
            new Shape("a.area10", "list", null, List.of(in("vod_area", List.of(Arrays.copyOfRange(AREAS, 1, 11))))),
            new Shape("a.lang3", "list", "lang=粤语,韩语,日语", List.of(in("vod_lang", List.of("粤语", "韩语", "日语")))),
            new Shape("a.letter3", "list", "letter=A,B,C", List.of(in("vod_letter", List.of("A", "B", "C")))),
            new Shape("a.letter0-9", "list", "letter=0-9",
                    List.of(in("vod_letter", List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9")))),
            new Shape("a.state2", "list", "state=预告片,花絮", List.of(in("vod_state", List.of("预告片", "花絮")))),
            new Shape("a.version2", "list", "version=蓝光版,抢先版", List.of(in("vod_version", List.of("蓝光版", "抢先版")))),
            new Shape("a.ids100", "list", "ids=" + join(spreadIds(100, 2999, 7)),
                    List.of(in("vod_id", spreadIds(100, 2999, 7)))),
            // b. OR
            new Shape("b.class1", "list", "classes=科幻", List.of(like("vod_class", "科幻"))),
            new Shape("b.class3", "list", "classes=科幻,战争,武侠", List.of(likeAny("vod_class", List.of("科幻", "战争", "武侠")))),
            new Shape("b.actor2", "list", "actor=张伟,王芳", List.of(likeAny("vod_actor", List.of("张伟", "王芳")))),
            new Shape("b.director2", "list", "director=张伟,王芳", List.of(likeAny("vod_director", List.of("张伟", "王芳")))),
            new Shape("b.tid-top", "list", null, List.of(raw("AND (type_id = ? OR type_id_1 = ?)", 1L, 1L))),
            new Shape("b.tid-child", "list", null, List.of(raw("AND (type_id = ? OR type_id_1 = ?)", 7L, 7L))),
            // c. 年份
            new Shape("c.year1", "list", "year=2010", List.of(eq("vod_year", "2010"))),
            new Shape("c.year2000-2010", "list", "year=2000-2010", List.of(in("vod_year", years(2000, 2010)))),
            new Shape("c.year1990-2021", "list", "year=1990-2021", List.of(in("vod_year", years(1990, 2021)))),
            // d. 随机排序（rnd1：计数 ≤ 2000；rnd2：计数 > 2000）
            new Shape("d.rnd1-area-letter", "rnd1", "area=新加坡,印度&letter=Q,X,Z",
                    List.of(in("vod_area", List.of("新加坡", "印度")), in("vod_letter", List.of("Q", "X", "Z")))),
            new Shape("d.rnd2-none", "rnd2", "", List.of()),
            new Shape("d.rnd2-area", "rnd2", "area=大陆", List.of(eq("vod_area", "大陆"))),
            new Shape("d.rnd2-class", "rnd2", "classes=科幻", List.of(like("vod_class", "科幻"))),
            // e. B05 旧标签条件（legacyConditions，只有内部入口）
            new Shape("e.hits-between", "list", null, List.of(raw("AND vod_hits BETWEEN ? AND ?", 1000L, 50000L))),
            new Shape("e.hits-gt", "list", null, List.of(raw("AND vod_hits > ?", 200000L))),
            new Shape("e.timeadd-30d", "list", null, List.of(raw("AND vod_time_add > ?", NOW - 30 * DAY))),
            new Shape("e.timeadd-365d", "list", null, List.of(raw("AND vod_time_add > ?", NOW - 365 * DAY))),
            new Shape("e.isend1", "list", null, List.of(raw("AND vod_isend = ?", 1))),
            new Shape("e.notin100", "list", null, List.of(notIn("vod_id", spreadIds(100, 2999, 7)))),
            new Shape("e.combo-isend-hits-time", "list", null, List.of(raw("AND vod_time_add > ?", NOW - 365 * DAY),
                    raw("AND vod_hits > ?", 200000L), raw("AND vod_isend = ?", 1))));

    /** 形态过滤：逗号分隔的名称前缀（如 {@code a.,b.tid}），null 为全部。 */
    static boolean selected(Shape s, String filter) {
        return filter == null || Arrays.stream(filter.split(",")).anyMatch(s.name::startsWith);
    }

    static String join(List<?> l) {
        StringBuilder sb = new StringBuilder();
        for (Object o : l) {
            sb.append(sb.isEmpty() ? "" : ",").append(o);
        }
        return sb.toString();
    }

    static final Map<String, String> SORT_COL = new LinkedHashMap<>(Map.of("time", "vod_time", "hits", "vod_hits"));
    static final int PAGE_SIZE = 20;
    static final int HTTP_RUNS = 5;
    static final long DEEP_OFFSET = 10_000;
    static final int RANDOM_THRESHOLD = 2000;
    static final int RANDOM_CANDIDATES = 1000;

    /** VideoRandom.sampleIndexes：0..n-1 中不放回取 k 个（Floyd），升序。 */
    static List<Long> sample(Random r, long n, int k) {
        Set<Long> picked = new HashSet<>(k * 2);
        for (long j = n - k; j < n; j++) {
            long t = (long) (r.nextDouble() * (j + 1));
            if (!picked.add(t)) {
                picked.add(j);
            }
        }
        List<Long> out = new ArrayList<>(picked);
        Collections.sort(out);
        return out;
    }

    // ================================================================== 计时与计数

    static final String[] SESSION_KEYS = {"Handler_read_first", "Handler_read_key", "Handler_read_last",
            "Handler_read_next", "Handler_read_prev", "Handler_read_rnd", "Handler_read_rnd_next", "Created_tmp_tables",
            "Created_tmp_disk_tables", "Sort_merge_passes", "Sort_rows", "Sort_scan", "Sort_range", "Select_scan",
            "Select_range"};

    static Map<String, Long> status(Statement st) throws SQLException {
        Map<String, Long> m = new LinkedHashMap<>();
        StringBuilder in = new StringBuilder();
        for (String k : SESSION_KEYS) {
            in.append(in.isEmpty() ? "'" : ",'").append(k).append('\'');
        }
        try (ResultSet rs = st.executeQuery("SHOW SESSION STATUS WHERE Variable_name IN (" + in + ")")) {
            while (rs.next()) {
                m.put(rs.getString(1), rs.getLong(2));
            }
        }
        return m;
    }

    /** b − a − overhead × times，只列非零项。 */
    static String delta(Map<String, Long> a, Map<String, Long> b, Map<String, Long> overhead, int divide) {
        StringBuilder sb = new StringBuilder();
        for (String k : a.keySet()) {
            long d = (b.get(k) - a.get(k)) / divide - overhead.getOrDefault(k, 0L);
            if (d != 0) {
                sb.append(k.replace("Handler_read_", "hr_")).append('=').append(d).append(' ');
            }
        }
        return sb.toString().strip();
    }

    record Timed(long nanos, long value, String error) {
    }

    /** count 语句取值，其余取返回行数（selectIdRange 取 maxId − minId + 1）。3024/1028 等错误记为 E<码>。 */
    static Timed run(Connection c, Sql sql) {
        long t0 = System.nanoTime();
        try (PreparedStatement ps = c.prepareStatement(sql.text)) {
            for (int i = 0; i < sql.params.size(); i++) {
                ps.setObject(i + 1, sql.params.get(i));
            }
            long v = 0;
            try (ResultSet rs = ps.executeQuery()) {
                boolean count = sql.text.contains("COUNT(*)");
                boolean range = sql.text.contains("MIN(vod_id)");
                while (rs.next()) {
                    v = count ? rs.getLong(1) : range ? rs.getLong(2) - rs.getLong(1) + 1 : v + 1;
                }
            }
            return new Timed(System.nanoTime() - t0, v, null);
        } catch (SQLException e) {
            return new Timed(System.nanoTime() - t0, -1, "E" + e.getErrorCode());
        }
    }

    static List<String> explain(Connection c, Sql sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("EXPLAIN " + sql.text)) {
            for (int i = 0; i < sql.params.size(); i++) {
                ps.setObject(i + 1, sql.params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(String.join(" | ", "type=" + rs.getString("type"), "key=" + rs.getString("key"),
                            "key_len=" + rs.getString("key_len"), "rows=" + rs.getString("rows"),
                            "filtered=" + rs.getString("filtered"), "Extra=" + rs.getString("Extra"),
                            "possible_keys=" + rs.getString("possible_keys")));
                }
            }
        }
        return rows;
    }

    static double ms(long nanos) {
        return nanos / 1e6;
    }

    static long pct(List<Long> sorted, double p) {
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    /** p50/p95（ms）。 */
    static String stats(List<Long> nanos) {
        if (nanos.isEmpty()) {
            return "-\t-";
        }
        List<Long> s = new ArrayList<>(nanos);
        Collections.sort(s);
        return String.format("%.1f\t%.1f", ms(pct(s, 0.5)), ms(pct(s, 0.95)));
    }

    /** 一次"请求"由若干条语句组成；steps 由迭代序号生成（随机排序每次换候选段/随机页）。 */
    interface Plan {
        List<Sql> steps(int iteration);
    }

    record Result(List<Long> total, List<List<Long>> perStep, long[] values, String error, int errors) {
    }

    static Result exec(Connection c, Plan plan, int from, int n) {
        List<Long> total = new ArrayList<>();
        List<List<Long>> per = new ArrayList<>();
        long[] values = null;
        String error = "";
        int errors = 0;
        for (int it = from; it < from + n; it++) {
            List<Sql> steps = plan.steps(it);
            if (values == null) {
                values = new long[steps.size()];
                for (int i = 0; i < steps.size(); i++) {
                    per.add(new ArrayList<>());
                }
            }
            long sum = 0;
            for (int i = 0; i < steps.size(); i++) {
                Timed t = run(c, steps.get(i));
                if (t.error != null) {
                    error = error.isEmpty() ? "step" + i + ":" + t.error : error;
                    errors++;
                }
                per.get(i).add(t.nanos);
                values[i] = t.value;
                sum += t.nanos;
            }
            total.add(sum);
        }
        return new Result(total, per, values, error, errors);
    }

    // ================================================================== 基准

    static void bench(String label, String filter) throws Exception {
        String db = dbName();
        StringBuilder ex = new StringBuilder();
        StringBuilder tsv = new StringBuilder("shape\tkind\tsort\toffset\tstatements\tvalues\tcold_ms\tcold_steps_ms\t"
                + "cold_error\tn\thot_p50_ms\thot_p95_ms\thot_steps_p50_ms\thot_steps_p95_ms\thot_error\t"
                + "hot_errors\tuncapped_ms\tsys_cpu_load\tcold_session_delta\thot_session_delta_per_run\n");
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        try (Connection c = admin(db); Statement st = c.createStatement()) {
            Map<String, Long> o1 = status(st);
            Map<String, Long> o2 = status(st);
            Map<String, Long> overhead = new LinkedHashMap<>();
            o1.forEach((k, v) -> overhead.put(k, o2.get(k) - v));
            long[] idRange = new long[2];
            try (ResultSet rs = st.executeQuery("SELECT MIN(vod_id), MAX(vod_id), COUNT(*) FROM mac_vod")) {
                rs.next();
                idRange[0] = rs.getLong(1);
                idRange[1] = rs.getLong(2);
                log("bench " + label + " on " + rs.getLong(3) + " rows, vod_id " + idRange[0] + ".." + idRange[1]);
            }
            for (Shape s : SHAPES) {
                if (!selected(s, filter)) {
                    continue;
                }
                long total = run(c, noHint(countSql(s.conds))).value;
                ex.append("### ").append(s.name).append(" (total=").append(total).append(")\n");
                ex.append("count: ").append(countSql(s.conds).text.replace(SUMMARY_COLUMNS, "<summaryColumns>"))
                        .append('\n');
                explain(c, countSql(s.conds)).forEach(x -> ex.append("  ").append(x).append('\n'));
                List<String[]> cases = new ArrayList<>();
                switch (s.kind) {
                    case "list" -> {
                        for (String sort : SORT_COL.keySet()) {
                            cases.add(new String[]{sort, "0"});
                        }
                        // 深分页只测 time 倒序（机器负载高、时间预算有限；hits 深分页见冒烟 base）
                        if (total > DEEP_OFFSET + PAGE_SIZE) {
                            cases.add(new String[]{"time", String.valueOf(DEEP_OFFSET)});
                        }
                    }
                    case "rnd1" -> {
                        if (total > RANDOM_THRESHOLD) {
                            throw new IllegalStateException(s.name + " 计数 " + total + " 超过阈值");
                        }
                        cases.add(new String[]{"rnd", "random"});
                        cases.add(new String[]{"rnd", "0"});
                        cases.add(new String[]{"rnd", String.valueOf(((total - 1) / PAGE_SIZE) * PAGE_SIZE)});
                    }
                    case "rnd2" -> {
                        if (total <= RANDOM_THRESHOLD) {
                            throw new IllegalStateException(s.name + " 计数 " + total + " 未超过阈值");
                        }
                        cases.add(new String[]{"rnd", "0"});
                        cases.add(new String[]{"rnd", "20"});
                    }
                    default -> throw new IllegalStateException(s.kind);
                }
                for (String[] cs : cases) {
                    Plan plan = plan(s, cs[0], cs[1], total, idRange);
                    List<Sql> sample = plan.steps(0);
                    ex.append("-- sort=").append(cs[0]).append(" offset=").append(cs[1]).append('\n');
                    for (int i = 0; i < sample.size(); i++) {
                        if (s.kind.equals("list") && i == 0) {
                            continue;
                        }
                        String text = sample.get(i).text.replace(SUMMARY_COLUMNS, "<summaryColumns>");
                        ex.append("step").append(i).append(": ").append(abbreviate(text)).append('\n');
                        explain(c, sample.get(i)).forEach(x -> ex.append("  ").append(x).append('\n'));
                    }
                    st.execute("FLUSH TABLES `" + db + "`.`mac_vod`");
                    Map<String, Long> s0 = status(st);
                    Result cold = exec(c, plan, 0, 1);
                    Map<String, Long> s1 = status(st);
                    long coldNanos = cold.total.get(0);
                    int n = coldNanos < 100_000_000L ? 20 : coldNanos < 1_000_000_000L ? 10 : coldNanos < 2_000_000_000L ? 5 : 3;
                    if (coldNanos < 1_000_000_000L) {
                        exec(c, plan, 1, 1); // 预热一次，不计
                    }
                    Map<String, Long> h0 = status(st);
                    os.getCpuLoad();
                    Result hot = exec(c, plan, 2, n);
                    double cpu = os.getCpuLoad();
                    Map<String, Long> h1 = status(st);
                    String uncapped = "";
                    if (!cold.error.isEmpty() || hot.errors > 0) {
                        Plan np = it -> plan.steps(it).stream().map(B05Perf::noHint).toList();
                        Result u = exec(c, np, 2, 1);
                        uncapped = String.format("%.1f", ms(u.total.get(0))) + (u.error.isEmpty() ? "" : " " + u.error);
                    }
                    StringBuilder coldSteps = new StringBuilder();
                    StringBuilder hot50 = new StringBuilder();
                    StringBuilder hot95 = new StringBuilder();
                    for (int i = 0; i < hot.perStep.size(); i++) {
                        coldSteps.append(i == 0 ? "" : "+").append(String.format("%.1f", ms(cold.perStep.get(i).get(0))));
                        String[] p = stats(hot.perStep.get(i)).split("\t");
                        hot50.append(i == 0 ? "" : "+").append(p[0]);
                        hot95.append(i == 0 ? "" : "+").append(p[1]);
                    }
                    String[] hp = stats(hot.total).split("\t");
                    tsv.append(String.join("\t", s.name, s.kind, cs[0], cs[1], String.valueOf(hot.perStep.size()),
                            join(Arrays.stream(hot.values).boxed().toList()), String.format("%.1f", ms(coldNanos)),
                            coldSteps, cold.error, String.valueOf(n), hp[0], hp[1], hot50, hot95, hot.error,
                            String.valueOf(hot.errors), uncapped, String.format("%.2f", cpu),
                            delta(s0, s1, overhead, 1), delta(h0, h1, overhead, n))).append('\n');
                    log(String.format("%-24s %-4s off=%-6s values=%s cold=%.0fms hot p50/p95=%s/%s ms cpu=%.2f %s %s %s",
                            s.name, cs[0], cs[1], join(Arrays.stream(hot.values).boxed().toList()), ms(coldNanos), hp[0],
                            hp[1], cpu, cold.error, hot.errors > 0 ? hot.errors + "x" + hot.error : "", uncapped));
                }
            }
        }
        Files.writeString(WORK.resolve(label + "-bench.tsv"), tsv.toString());
        Files.writeString(WORK.resolve(label + "-explain.txt"), ex.toString());
        log("wrote " + WORK.resolve(label + "-bench.tsv"));
    }

    /** 把长 IN 列表缩写为 (?,…×N)，只用于 explain 文件的可读性。 */
    static String abbreviate(String sql) {
        return Pattern.compile("\\((\\?,){9,}\\?\\)").matcher(sql).replaceAll(m -> "(?×"
                + (m.group().length() / 2) + ")");
    }

    /**
     * 每次"请求"实际执行的语句（与 ContentReadService.doListVideos / randomPage 相同的顺序）：
     * list：count → page（count ≤ offset 时应用不执行 page，这里同样跳过）；
     * rnd1：count → 随机页（offset=random 时每次按 1..c/20+1 重新取页，否则固定 offset）；
     * rnd2：count（无条件时为 countPublished）→ selectIdRange → count(条件 + 1000 个候选) → page(同上，hits_week 倒序)。
     */
    static Plan plan(Shape s, String sort, String offset, long total, long[] idRange) {
        Sql count = countSql(s.conds);
        return switch (s.kind) {
            case "list" -> {
                Sql page = pageSql(s.conds, SORT_COL.get(sort), Long.parseLong(offset), PAGE_SIZE);
                yield it -> total > Long.parseLong(offset) ? List.of(count, page) : List.of(count);
            }
            case "rnd1" -> it -> {
                long off;
                if (offset.equals("random")) {
                    long page = 1 + (long) (new Random(SEED + it).nextDouble() * (total / PAGE_SIZE + 1));
                    off = (page - 1) * PAGE_SIZE;
                } else {
                    off = Long.parseLong(offset);
                }
                return off < total ? List.of(count, pageSql(s.conds, "vod_hits_week", off, PAGE_SIZE)) : List.of(count);
            };
            case "rnd2" -> it -> {
                List<Long> cand = sample(new Random(SEED * 7 + it), idRange[1] - idRange[0] + 1, RANDOM_CANDIDATES);
                List<Cond> withCand = new ArrayList<>(s.conds);
                withCand.add(in("vod_id", cand));
                return List.of(count, new Sql(ID_RANGE_SQL, List.of()), countSql(withCand),
                        pageSql(withCand, "vod_hits_week", Long.parseLong(offset), PAGE_SIZE));
            };
            default -> throw new IllegalStateException(s.kind);
        };
    }

    // ================================================================== 经应用（可选）：HTTP 耗时与 SQL 文本核对

    static void http(String label, Path jar, String filter) throws Exception {
        String db = dbName();
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        Path logFile = WORK.resolve(label + "-app.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-jar", jar.toString(),
                "--logging.level.com.maccms.backend.content.infrastructure.VodMapper=DEBUG");
        Map<String, String> e = pb.environment();
        e.put("MACCMS_DB_URL", jdbcUrlFor(db, null));
        e.put("MACCMS_DB_USERNAME", adminUser);
        e.put("MACCMS_DB_PASSWORD", adminPassword);
        e.put("MACCMS_DB_ALLOW_ROOT", "root".equalsIgnoreCase(adminUser) ? "true" : "false");
        e.put("MACCMS_SERVER_PORT", String.valueOf(port));
        e.put("MACCMS_SERVER_ADDRESS", "127.0.0.1");
        e.put("MACCMS_CONTENT_SEARCH_ENABLED", "true");
        e.put("MACCMS_CONTENT_SEARCH_MAX_CONCURRENT", "4");
        pb.redirectErrorStream(true).redirectOutput(logFile.toFile());
        Process app = pb.start();
        StringBuilder tsv = new StringBuilder("shape\tquery\tn\tstatus\ttotal\thttp_p50_ms\thttp_p95_ms\tsql_text_match\n");
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (true) {
                try {
                    if (get(port, "/actuator/health").status == 200) {
                        break;
                    }
                } catch (IOException ignored) {
                    // 未就绪
                }
                if (!app.isAlive() || System.nanoTime() > deadline) {
                    throw new IllegalStateException("应用未就绪，见 " + logFile);
                }
                Thread.sleep(300);
            }
            log("app pid " + app.pid() + " on 127.0.0.1:" + port);
            for (Shape s : SHAPES) {
                if (s.api == null || !selected(s, filter)) {
                    continue;
                }
                List<String[]> qs = new ArrayList<>();
                if (s.kind.equals("list")) {
                    // 时间预算：经应用只测 sort=time 第 1 页（直连已覆盖 hits 与深分页）
                    qs.add(new String[]{"time", "1", "0"});
                } else {
                    qs.add(new String[]{"rnd", "1", "0"});
                }
                for (String[] q : qs) {
                    String query = "/api/v1/videos?" + encode(s.api) + (s.api.isEmpty() ? "" : "&") + "sort=" + q[0]
                            + "&page=" + q[1];
                    long logStart = Files.size(logFile);
                    List<Long> times = new ArrayList<>();
                    Map<Integer, Integer> codes = new TreeMap<>();
                    String total = "";
                    for (int i = 0; i < HTTP_RUNS; i++) {
                        Resp r = get(port, query);
                        times.add(r.nanos);
                        codes.merge(r.status, 1, Integer::sum);
                        // 列表项也有 "total"（vod_total），取 items 数组之后的分页字段
                        var m = Pattern.compile("\"pageSize\":\\d+,\"total\":(\\d+)").matcher(r.body);
                        if (r.status == 200 && m.find()) {
                            total = total.isEmpty() ? m.group(1) : total.equals(m.group(1)) ? total : "varies";
                        }
                    }
                    Thread.sleep(200);
                    String logged = Files.readString(logFile).substring((int) logStart);
                    Set<String> prepared = new HashSet<>();
                    var pm = Pattern.compile("Preparing: (.*)").matcher(logged);
                    while (pm.find()) {
                        prepared.add(pm.group(1).replaceAll("\\s+", ""));
                    }
                    List<String> expected = new ArrayList<>();
                    expected.add(countSql(s.conds).text);
                    if (s.kind.equals("list")) {
                        expected.add(pageSql(s.conds, SORT_COL.get(q[0]), Long.parseLong(q[2]), PAGE_SIZE).text);
                    } else if (s.kind.equals("rnd1")) {
                        expected.add(pageSql(s.conds, "vod_hits_week", 0, PAGE_SIZE).text);
                    } else {
                        List<Cond> withCand = new ArrayList<>(s.conds);
                        withCand.add(in("vod_id", Collections.nCopies(RANDOM_CANDIDATES, 0L)));
                        expected.add(ID_RANGE_SQL);
                        expected.add(countSql(withCand).text);
                        expected.add(pageSql(withCand, "vod_hits_week", 0, PAGE_SIZE).text);
                    }
                    int matched = 0;
                    for (String x : expected) {
                        if (prepared.contains(x.replaceAll("\\s+", ""))) {
                            matched++;
                        }
                    }
                    String[] p = stats(times).split("\t");
                    tsv.append(String.join("\t", s.name, query, String.valueOf(HTTP_RUNS), codes.toString(), total, p[0], p[1],
                            matched + "/" + expected.size())).append('\n');
                    log(String.format("http %-22s %-40s %s total=%s p50/p95=%s/%s ms sql=%d/%d", s.name,
                            query.length() > 40 ? query.substring(0, 40) : query, codes, total, p[0], p[1], matched,
                            expected.size()));
                }
            }
        } finally {
            app.destroy();
            if (!app.waitFor(20, TimeUnit.SECONDS)) {
                app.destroyForcibly();
            }
            log("app pid " + app.pid() + " stopped");
        }
        Files.writeString(WORK.resolve(label + "-http.tsv"), tsv.toString());
    }

    static String encode(String apiQuery) {
        StringBuilder sb = new StringBuilder();
        for (String kv : apiQuery.split("&")) {
            if (kv.isEmpty()) {
                continue;
            }
            int i = kv.indexOf('=');
            sb.append(sb.isEmpty() ? "" : "&").append(kv, 0, i + 1)
                    .append(URLEncoder.encode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    record Resp(int status, String body, long nanos) {
    }

    static Resp get(int port, String pathAndQuery) throws IOException {
        long t0 = System.nanoTime();
        HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + pathAndQuery).toURL()
                .openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(60000);
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return new Resp(status, body, System.nanoTime() - t0);
    }

    static void log(String msg) {
        System.out.println("[b05perf " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME) + "] " + msg);
    }
}
