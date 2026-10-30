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
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 视频搜索/筛选（契约 1.1.0）在一次性 MySQL 库上的性能、EXPLAIN 与 MyISAM 表锁测量（MACC-19，09 方案第 6.5 节 / T2）。
 *
 * <p><b>不是测试，不进 {@code ./mvnw verify}</b>：放在 src/test/perf/（Maven 不编译此目录），以 Java 单文件方式运行，
 * 用法见同目录 README.md。只使用环境变量 MACCMS_IT_ADMIN_URL / _USERNAME / _PASSWORD 指向的管理连接，
 * 只创建、读写、删除名称为 {@code maccms_perf_<yyyyMMddHHmmss>_<8位十六进制>} 的一次性库；
 * 对其他库只读 information_schema 元数据（表名、行数、长度、更新时间），不以其他库为默认库连接，不读其数据。
 * 不修改服务器全局变量；冷态只对一次性库的 mac_vod 执行 {@code FLUSH TABLES <库>.mac_vod}。
 *
 * <p>测量用的 SQL 与 src/main/resources/mapper/content/VodMapper.xml 逐字一致（去空白后），
 * {@code sqlcheck} 子命令用被测应用的 MyBatis DEBUG 日志核对 SQL 文本与绑定值。
 */
public class SearchPerf {

    static final Pattern DB_NAME = Pattern.compile("^maccms_perf_[0-9]{14}_[0-9a-f]{8}$");
    static final Path WORK = Path.of("target", "perf");
    static final Path STATE = WORK.resolve("state.properties");
    static final Path INSTALL_SQL = Path.of("php", "application", "install", "sql", "install.sql");
    static final Path APP_JAR = Path.of("target", "maccms-backend-0.1.0-SNAPSHOT.jar");
    static final long SEED = 20261015L;
    /** 合成数据的"当前时间"，固定以便复现。 */
    static final long NOW = 1_790_000_000L;

    static String adminUrl;
    static String adminUser;
    static String adminPassword;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: create | grow <rows> | context <label> | bench <label> [shapeFilter] | "
                    + "sqlcheck <label> | lock <label> <seconds> | timeout <label> | drop");
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
            case "sqlcheck" -> sqlcheck(args[1]);
            case "lock" -> lock(args[1], Integer.parseInt(args[2]));
            case "timeout" -> timeout(args[1]);
            case "drop" -> drop();
            default -> throw new IllegalArgumentException("unknown command " + args[0]);
        }
    }

    // ================================================================== 连接与库生命周期

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

    static void create() throws Exception {
        if (Files.exists(STATE)) {
            throw new IllegalStateException("已有 state.properties（上一个一次性库未删除？先执行 drop）");
        }
        byte[] rnd = new byte[4];
        new SecureRandom().nextBytes(rnd);
        String db = "maccms_perf_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "_" + String.format("%02x%02x%02x%02x", rnd[0], rnd[1], rnd[2], rnd[3]);
        if (!DB_NAME.matcher(db).matches()) {
            throw new IllegalStateException("库名不合法: " + db);
        }
        Files.writeString(WORK.resolve("guard-before.tsv"), guardSnapshot());
        try (Connection c = admin(null); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"
                    + db + "'")) {
                rs.next();
                if (rs.getInt(1) != 0) {
                    throw new IllegalStateException("库已存在: " + db);
                }
            }
            st.execute("CREATE DATABASE `" + db + "` DEFAULT CHARACTER SET utf8");
        }
        Files.writeString(STATE, "db=" + db + "\n");
        log("created disposable database " + db);
        try (Connection c = admin(db); Statement st = c.createStatement()) {
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
            st.execute("DROP DATABASE `" + db + "`");
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"
                    + db + "'")) {
                rs.next();
                if (rs.getInt(1) != 0) {
                    throw new IllegalStateException("删除后仍存在: " + db);
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.schemata "
                    + "WHERE schema_name LIKE 'maccms\\_perf\\_%'")) {
                rs.next();
                log("remaining maccms_perf_* schemas: " + rs.getInt(1));
            }
        }
        Files.delete(STATE);
        String after = guardSnapshot();
        Files.writeString(WORK.resolve("guard-after.tsv"), after);
        String before = Files.readString(WORK.resolve("guard-before.tsv"));
        log("dropped disposable database " + db + " (verified absent)");
        log("maccms_dev information_schema snapshot unchanged: " + before.equals(after));
    }

    /**
     * 共享开发库的只读元数据快照：只查 information_schema（不以 maccms_dev 为默认库、不读其数据），用于证明前后未变。
     */
    static String guardSnapshot() throws SQLException {
        StringBuilder sb = new StringBuilder("table\tengine\trows\tdata_length\tindex_length\tupdate_time\tchecksum\n");
        try (Connection c = admin(null); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT table_name, engine, table_rows, data_length, index_length, "
                     + "update_time, checksum FROM information_schema.tables WHERE table_schema='maccms_dev' "
                     + "ORDER BY table_name")) {
            while (rs.next()) {
                for (int i = 1; i <= 7; i++) {
                    sb.append(rs.getString(i)).append(i == 7 ? "\n" : "\t");
                }
            }
        }
        return sb.toString();
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

    /**
     * 30 个分类：1–5 顶级视频分类，6–25 为其子分类（每个顶级 4 个）。不可见：26 停用顶级、27 父分类停用、
     * 28 游客无列表权限、29 文章分类（type_mid=2）、30 自身停用的子分类。可见分类 = 1..25。
     */
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

    /** 名称词库（不含关键字样本 龙腾/麒麟阁/无此片名 的任何字符，启动时校验）。 */
    static final String[] WORDS = ("春风 秋月 江湖 长歌 少年 都市 情缘 归来 山河 往事 青春 星辰 大海 迷城 夜行 追光 "
            + "白鹿 红尘 烟火 风云 剑客 侠客 天涯 明月 花开 雪落 孤岛 边城 远方 故乡 家园 时光 记忆 秘密 真相 "
            + "迷雾 黎明 黄昏 晨曦 暗夜 光影 回声 心跳 微光 灯塔 港湾 码头 列车 站台 街角 小巷 胡同 庭院 "
            + "书生 将军 公主 王妃 太子 掌门 捕快 神探 律师 医生 警察 老师 学生 厨师 画家 歌手 舞者 "
            + "传说 奇缘 战记 风暴 烈火 寒冰 雷霆 飞鸟 白马 黑猫 金鱼 狐仙 青蛇 白蛇 熊猫 骏马 雄鹰 "
            + "约定 重逢 告别 守望 出发 冒险 逃离 寻找 等待 相遇 心动 初恋 婚礼 离婚 家族 兄弟 姐妹 父亲 母亲 "
            + "人生 命运 梦想 奋斗 创业 职场 校园 军营 医院 法庭 天空 森林 草原 沙漠 雪山 河流 湖泊 海岸 "
            + "极光 银河 宇宙 未来 过去 现在 永远 瞬间 一生 百年 千年 万里 九州 四海 东方 西方 南方 北方")
            .split(" ");
    static final String[] SYL = ("chun feng qiu yue jiang hu chang ge shao nian du shi qing yuan gui lai shan he wang "
            + "xing chen da hai mi cheng ye xing zhui guang bai lu hong yan huo yun jian ke xia tian ya ming hua kai "
            + "xue luo gu dao bian fang xiang jia shi ji mi zhen xiang wu li huang hun an ying hui sheng xin tiao wei "
            + "deng ta gang wan ma tou lie che zhan tai jie jiao xiao tong ting shu jun gong zhu fei tai zi zhang men")
            .split(" ");
    static final String[] EN_WORDS = ("Silent River Night Star Ocean Lost City Dawn Shadow Fire Ice Storm Wild Heart "
            + "Moon Road Home Secret Dream Last First Dark Light Golden Iron Blue Red Winter Summer").split(" ");
    static final String[] SPECIAL_NAMES = {"Tom & Jerry 合集", "Tom &amp; Jerry 特别篇", "100%真实", "a_b实验室",
            "叹号wow!剧场", "反斜C:\\dir片", "逗号,片", "Q&A时间", "<影>与\"光\"", "Rock'n'Roll"};
    static final String[] CLASSES = {"剧情", "爱情", "动作", "喜剧", "科幻", "战争", "武侠", "冒险", "枪战", "恐怖",
            "微电影", "其它"};
    static final int[] CLASS_W = {25, 15, 15, 12, 8, 5, 5, 5, 3, 4, 1, 2};
    static final String[] AREAS = {"大陆", "美国", "韩国", "日本", "香港", "台湾", "泰国", "英国", "法国", "新加坡",
            "马来西亚", "印度", "加拿大", "西班牙", "俄罗斯", "其它"};
    static final int[] AREA_W = {35, 15, 10, 10, 8, 6, 3, 3, 2, 1, 1, 1, 1, 1, 1, 2};
    static final String[] LANGS = {"国语", "英语", "粤语", "闽南语", "韩语", "日语", "法语", "德语", "其它"};
    static final int[] LANG_W = {40, 20, 8, 2, 10, 10, 3, 2, 5};
    static final String[] PEOPLE = ("张伟 王芳 李娜 刘洋 陈杰 杨静 赵磊 黄敏 周强 吴婷 徐涛 孙丽 胡军 朱红 高峰 林霞 "
            + "何平 郭亮 马超 罗琳 梁斌 宋佳 郑浩 谢雨 韩冰 唐宁 冯刚 于洁 董明 萧然").split(" ");
    static final String HIGH_KW = "龙腾";
    static final String LOW_KW = "麒麟阁";
    static final String NONE_KW = "无此片名ZQX";
    static final String[] SENTENCES = buildSentences();

    static String[] buildSentences() {
        Random r = new Random(SEED);
        String[] out = new String[512];
        for (int i = 0; i < out.length; i++) {
            StringBuilder sb = new StringBuilder();
            int n = 6 + r.nextInt(8);
            for (int j = 0; j < n; j++) {
                sb.append(WORDS[r.nextInt(WORDS.length)]);
                if (r.nextInt(5) == 0) {
                    sb.append('，');
                }
            }
            sb.append('。');
            out[i] = sb.toString();
        }
        return out;
    }

    static int weighted(Random r, int[] w) {
        int total = 0;
        for (int x : w) {
            total += x;
        }
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
            + "vod_version,vod_state,vod_level,vod_hits,vod_hits_day,vod_hits_week,vod_hits_month,vod_up,vod_down,"
            + "vod_score,vod_score_all,vod_score_num,vod_time,vod_time_add,vod_content,vod_play_from,vod_play_url,"
            + "vod_down_url,vod_plot_name,vod_plot_detail";

    /** 第 id 行的全部插入值；只依赖 id 与固定种子，分档追加时结果可复现。 */
    static Object[] row(int id) {
        Random r = new Random(SEED * 31 + id);
        int typeRoll = r.nextInt(100);
        int typeId;
        if (typeRoll < 92) {
            typeId = 6 + r.nextInt(20);
        } else if (typeRoll < 95) {
            typeId = 1 + r.nextInt(5);
        } else {
            typeId = 26 + r.nextInt(5);
        }
        int typeId1 = typeId >= 6 && typeId <= 25 ? (typeId - 6) / 4 + 1 : 0;
        StringBuilder name = new StringBuilder();
        StringBuilder en = new StringBuilder();
        boolean english = r.nextInt(100) < 5;
        if (english) {
            int n = 2 + r.nextInt(2);
            for (int i = 0; i < n; i++) {
                name.append(i == 0 ? "" : " ").append(EN_WORDS[r.nextInt(EN_WORDS.length)]);
            }
            en.append(name.toString().toLowerCase(Locale.ROOT).replace(' ', '-'));
        } else {
            int n = 2 + r.nextInt(2);
            for (int i = 0; i < n; i++) {
                name.append(WORDS[r.nextInt(WORDS.length)]);
                en.append(SYL[r.nextInt(SYL.length)]).append(SYL[r.nextInt(SYL.length)]);
            }
        }
        if (r.nextInt(10) == 0) {
            name.insert(r.nextBoolean() ? 0 : name.length(), HIGH_KW);
            en.append("longteng");
        }
        if (id % 20011 == 7) {
            name.append(LOW_KW);
        }
        if (r.nextInt(1000) == 0) {
            name.append(SPECIAL_NAMES[r.nextInt(SPECIAL_NAMES.length)]);
        }
        if (r.nextInt(8) == 0) {
            name.append("第").append(2 + r.nextInt(5)).append("季");
        }
        String sub = r.nextInt(100) < 30 ? WORDS[r.nextInt(WORDS.length)] + WORDS[r.nextInt(WORDS.length)] : "";
        int status = r.nextInt(100) < 95 ? 1 : 0;
        char letter = (char) ('A' + r.nextInt(26));
        StringBuilder cls = new StringBuilder();
        int nc = 1 + r.nextInt(3);
        List<Integer> used = new ArrayList<>();
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
        int level = r.nextInt(100) < 98 ? 0 : 1 + r.nextInt(9);
        int hits = (int) (Math.pow(r.nextDouble(), 4) * 1_000_000);
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
        BigDecimalLike score = new BigDecimalLike(r.nextInt(100));
        return new Object[]{id, typeId, typeId1, name.toString(), sub, en.toString(), status, String.valueOf(letter),
                "", cls.toString(), "https://img.example.invalid/vod/" + id + ".jpg", actor,
                PEOPLE[r.nextInt(PEOPLE.length)], blurb, "更新至第" + (1 + r.nextInt(40)) + "集", area, lang, year,
                "高清版", "正片", level, hits, hits / 300, hits / 50, hits / 12, r.nextInt(5000), r.nextInt(500),
                score.toString(), r.nextInt(100000), r.nextInt(10000), time, timeAdd, content.toString(), "m3u8",
                play.toString(), "", "", ""};
    }

    /** decimal(3,1)，避免引入 BigDecimal 的格式差异。 */
    record BigDecimalLike(int tenths) {
        @Override
        public String toString() {
            return (tenths / 10) + "." + (tenths % 10);
        }
    }

    static void grow(int target) throws Exception {
        for (String w : WORDS) {
            for (String kw : List.of(HIGH_KW, LOW_KW, NONE_KW)) {
                for (char ch : kw.toCharArray()) {
                    if (w.indexOf(ch) >= 0) {
                        throw new IllegalStateException("词库与关键字样本冲突: " + w);
                    }
                }
            }
        }
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
            long t3 = System.nanoTime();
            log(String.format("grow %d -> %d: insert %.1fs, enable keys %.1fs, analyze %.1fs", have, target,
                    (t1 - t0) / 1e9, (t2 - t1) / 1e9, (t3 - t2) / 1e9));
        }
    }

    // ================================================================== 上下文

    static void context(String label) throws Exception {
        String db = dbName();
        StringBuilder sb = new StringBuilder();
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        sb.append("## machine\n");
        sb.append("os\t").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
                .append('\n');
        sb.append("cpu\t").append(System.getenv("PROCESSOR_IDENTIFIER")).append("; logical=")
                .append(Runtime.getRuntime().availableProcessors()).append('\n');
        sb.append("memory_total_mb\t").append(os.getTotalMemorySize() / 1024 / 1024).append('\n');
        sb.append("memory_free_mb\t").append(os.getFreeMemorySize() / 1024 / 1024).append('\n');
        sb.append("java\t").append(System.getProperty("java.vm.name")).append(' ')
                .append(System.getProperty("java.version")).append('\n');
        try (Connection c = admin(db); Statement st = c.createStatement()) {
            sb.append("\n## mysql\n");
            query(st, "SELECT VERSION(), @@version_comment, @@character_set_connection, @@collation_connection, "
                    + "@@character_set_client", sb);
            sb.append("\n## variables\n");
            query(st, "SHOW GLOBAL VARIABLES WHERE Variable_name IN ('key_buffer_size','key_cache_block_size',"
                    + "'query_cache_type','query_cache_size','read_buffer_size','read_rnd_buffer_size','sort_buffer_size',"
                    + "'tmp_table_size','max_heap_table_size','concurrent_insert','low_priority_updates','table_open_cache',"
                    + "'myisam_sort_buffer_size','myisam_use_mmap','delay_key_write','max_execution_time','sql_mode',"
                    + "'character_set_server','collation_server','max_connections','thread_cache_size',"
                    + "'internal_tmp_disk_storage_engine','optimizer_switch','max_length_for_sort_data',"
                    + "'max_seeks_for_key','eq_range_index_dive_limit','performance_schema','datadir')", sb);
            sb.append("\n## mac_vod table status\n");
            query(st, "SELECT table_rows, avg_row_length, data_length, index_length, row_format, engine, "
                    + "table_collation FROM information_schema.tables WHERE table_schema=DATABASE() "
                    + "AND table_name='mac_vod'", sb);
            sb.append("\n## distribution\n");
            query(st, "SELECT COUNT(*) total, SUM(vod_status=1) status1, SUM(type_id BETWEEN 1 AND 25) visible_type, "
                    + "SUM(vod_status=1 AND type_id BETWEEN 1 AND 25) candidates, ROUND(AVG(LENGTH(vod_content))) "
                    + "avg_content_bytes, ROUND(AVG(LENGTH(vod_play_url))) avg_play_url_bytes FROM mac_vod", sb);
            query(st, "SELECT SUM(vod_name LIKE '%龙腾%') high_kw, SUM(vod_name LIKE '%麒麟阁%') low_kw, "
                    + "SUM(vod_name LIKE '%无此片名ZQX%') none_kw, SUM(vod_name LIKE '%Silent%' OR vod_en LIKE '%silent%') en_kw, "
                    + "SUM(vod_name LIKE '%Tom & Jerry%' OR vod_name LIKE '%Tom &amp; Jerry%') amp_kw, "
                    + "SUM(vod_class LIKE '%科幻%') cls_kehuan, SUM(vod_area='日本') area_jp, SUM(vod_lang='粤语') lang_yue, "
                    + "SUM(vod_year='2020') year_2020, SUM(vod_level=9) level9 FROM mac_vod", sb);
            sb.append("\n## mac_vod indexes (cardinality)\n");
            query(st, "SELECT index_name, column_name, cardinality FROM information_schema.statistics WHERE "
                    + "table_schema=DATABASE() AND table_name='mac_vod' AND index_name IN ('PRIMARY','type_id',"
                    + "'vod_level','vod_hits','vod_name','vod_year','vod_area','vod_lang','vod_class','vod_en','vod_time') "
                    + "ORDER BY index_name", sb);
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

    // ================================================================== 查询形态与 SQL（与 VodMapper.xml 一致）

    record Shape(String name, Integer categoryId, String keyword, String area, String lang, String year,
                 Integer level, String classes) {
        boolean matching() {
            return keyword != null || area != null || lang != null || year != null || level != null || classes != null;
        }

        String query() {
            StringBuilder q = new StringBuilder();
            add(q, "categoryId", categoryId == null ? null : categoryId.toString());
            add(q, "keyword", keyword);
            add(q, "area", area);
            add(q, "lang", lang);
            add(q, "year", year);
            add(q, "level", level == null ? null : level.toString());
            add(q, "classes", classes);
            return q.toString();
        }

        static void add(StringBuilder q, String k, String v) {
            if (v != null) {
                q.append(q.isEmpty() ? "" : "&").append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
            }
        }
    }

    static final List<Shape> SHAPES = List.of(
            new Shape("base", null, null, null, null, null, null, null),
            new Shape("category", 1, null, null, null, null, null, null),
            new Shape("area", null, null, "日本", null, null, null, null),
            new Shape("lang", null, null, null, "粤语", null, null, null),
            new Shape("year", null, null, null, null, "2020", null, null),
            new Shape("level", null, null, null, null, null, 9, null),
            new Shape("exact-combo", null, null, "大陆", "国语", "2020", null, null),
            new Shape("kw-high", null, HIGH_KW, null, null, null, null, null),
            new Shape("kw-low", null, LOW_KW, null, null, null, null, null),
            new Shape("kw-none", null, NONE_KW, null, null, null, null, null),
            new Shape("kw-en", null, "Silent", null, null, null, null, null),
            new Shape("kw-amp", null, "Tom & Jerry", null, null, null, null, null),
            new Shape("classes", null, null, null, null, null, null, "科幻"),
            new Shape("kw-high+cat", 1, HIGH_KW, null, null, null, null, null),
            new Shape("kw-low+area", null, LOW_KW, "大陆", null, null, null, null));
    static final List<String> DEEP_SHAPES = List.of("base", "area", "kw-high", "classes");
    static final List<String> SORTS = List.of("time", "hits");

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

    static final List<Integer> VISIBLE = rangeList(1, 25);

    static List<Integer> rangeList(int a, int b) {
        List<Integer> l = new ArrayList<>();
        for (int i = a; i <= b; i++) {
            l.add(i);
        }
        return l;
    }

    /** 与 CategoryCatalog.idsForFilter 相同：本分类 + 可见直接子分类。 */
    static List<Integer> typeIds(Shape s) {
        if (s.categoryId == null) {
            return VISIBLE;
        }
        List<Integer> ids = new ArrayList<>(List.of(s.categoryId));
        if (s.categoryId <= 5) {
            int first = 6 + (s.categoryId - 1) * 4;
            ids.addAll(rangeList(first, first + 3));
        }
        return ids;
    }

    record Sql(String text, List<Object> params) {
    }

    static String escapeLike(String v) {
        StringBuilder sb = new StringBuilder("%");
        for (char ch : v.toCharArray()) {
            if (ch == '!' || ch == '%' || ch == '_') {
                sb.append('!');
            }
            sb.append(ch);
        }
        return sb.append('%').toString();
    }

    static String htmlEntities(String v) {
        return v.replace("&", "&amp;").replace("\"", "&quot;").replace("'", "&#039;").replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    static String where(Shape s, List<Object> params) {
        List<Integer> ids = typeIds(s);
        StringBuilder w = new StringBuilder("vod_status = 1 AND type_id IN (");
        for (int i = 0; i < ids.size(); i++) {
            w.append(i == 0 ? "?" : ",?");
            params.add(ids.get(i));
        }
        w.append(')');
        if (s.level != null) {
            w.append(" AND vod_level = ?");
            params.add(s.level);
        }
        if (s.year != null) {
            w.append(" AND vod_year = ?");
            params.add(s.year);
        }
        if (s.area != null) {
            w.append(" AND vod_area = ?");
            params.add(s.area);
        }
        if (s.lang != null) {
            w.append(" AND vod_lang = ?");
            params.add(s.lang);
        }
        if (s.classes != null) {
            w.append(" AND vod_class LIKE ? ESCAPE '!'");
            params.add(escapeLike(s.classes));
        }
        if (s.keyword != null) {
            List<String> pats = new ArrayList<>(List.of(escapeLike(s.keyword)));
            String ent = htmlEntities(s.keyword);
            if (!ent.equals(s.keyword)) {
                pats.add(escapeLike(ent));
            }
            w.append(" AND (");
            for (int i = 0; i < pats.size(); i++) {
                w.append(i == 0 ? "" : " OR ")
                        .append("vod_name LIKE ? ESCAPE '!' OR vod_sub LIKE ? ESCAPE '!' OR vod_en LIKE ? ESCAPE '!'");
                params.add(pats.get(i));
                params.add(pats.get(i));
                params.add(pats.get(i));
            }
            w.append(')');
        }
        return w.toString();
    }

    static String hint(Shape s) {
        return s.matching() ? "/*+ MAX_EXECUTION_TIME(3000) */ " : "";
    }

    static Sql countSql(Shape s) {
        List<Object> p = new ArrayList<>();
        return new Sql("SELECT " + hint(s) + "COUNT(*) FROM mac_vod WHERE " + where(s, p), p);
    }

    static Sql pageSql(Shape s, String sort, int page, int pageSize) {
        List<Object> p = new ArrayList<>();
        String col = switch (sort) {
            case "time" -> "vod_time";
            case "hits" -> "vod_hits";
            default -> throw new IllegalArgumentException(sort);
        };
        String sql = "SELECT " + hint(s) + SUMMARY_COLUMNS + " FROM mac_vod WHERE " + where(s, p) + " ORDER BY " + col
                + " DESC, vod_id DESC LIMIT ?, ?";
        p.add((long) (page - 1) * pageSize);
        p.add(pageSize);
        return new Sql(sql, p);
    }

    static String noHint(String sql) {
        return sql.replace("/*+ MAX_EXECUTION_TIME(3000) */ ", "");
    }

    // ================================================================== 被测应用进程

    static final class App implements AutoCloseable {
        final Process process;
        final int port;
        final Path logFile;

        App(String db, String label, int maxConcurrent) throws Exception {
            try (ServerSocket ss = new ServerSocket(0)) {
                port = ss.getLocalPort();
            }
            logFile = WORK.resolve(label + "-app.log");
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            ProcessBuilder pb = new ProcessBuilder(java, "-jar", APP_JAR.toString(),
                    "--logging.level.com.maccms.backend.content.infrastructure.VodMapper=DEBUG");
            Map<String, String> e = pb.environment();
            e.put("MACCMS_DB_URL", jdbcUrlFor(db, null));
            e.put("MACCMS_DB_USERNAME", adminUser);
            e.put("MACCMS_DB_PASSWORD", adminPassword);
            e.put("MACCMS_DB_ALLOW_ROOT", "root".equalsIgnoreCase(adminUser) ? "true" : "false");
            e.put("MACCMS_SERVER_PORT", String.valueOf(port));
            e.put("MACCMS_SERVER_ADDRESS", "127.0.0.1");
            e.put("MACCMS_CONTENT_SEARCH_ENABLED", "true");
            e.put("MACCMS_CONTENT_SEARCH_MAX_CONCURRENT", String.valueOf(maxConcurrent));
            pb.redirectErrorStream(true).redirectOutput(logFile.toFile());
            process = pb.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (true) {
                try {
                    Resp r = get("/actuator/health");
                    if (r.status == 200 && r.body.contains("UP")) {
                        break;
                    }
                } catch (IOException ignored) {
                    // 未就绪
                }
                if (!process.isAlive() || System.nanoTime() > deadline) {
                    throw new IllegalStateException("应用未就绪，见 " + logFile);
                }
                Thread.sleep(300);
            }
            log("app pid " + process.pid() + " listening on 127.0.0.1:" + port + " (max-concurrent=" + maxConcurrent
                    + ")");
        }

        record Resp(int status, String body, long nanos) {
        }

        Resp get(String pathAndQuery) throws IOException {
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

        Duration cpu() {
            return process.toHandle().info().totalCpuDuration().orElse(Duration.ZERO);
        }

        String heap() throws Exception {
            String jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd").toString();
            Process p = new ProcessBuilder(jcmd, String.valueOf(process.pid()), "GC.heap_info").redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(30, TimeUnit.SECONDS);
            return out.strip();
        }

        @Override
        public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            log("app pid " + process.pid() + " stopped");
        }
    }

    // ================================================================== 基准

    static final String[] SESSION_KEYS = {"Handler_read_first", "Handler_read_key", "Handler_read_last",
            "Handler_read_next", "Handler_read_prev", "Handler_read_rnd", "Handler_read_rnd_next", "Created_tmp_tables",
            "Created_tmp_disk_tables", "Sort_merge_passes", "Sort_rows", "Sort_scan", "Sort_range", "Select_scan",
            "Select_range", "Select_full_join"};
    static final String[] GLOBAL_KEYS = {"Key_read_requests", "Key_reads", "Table_locks_waited",
            "Table_locks_immediate"};

    static Map<String, Long> status(Statement st, String scope, String[] keys) throws SQLException {
        Map<String, Long> m = new LinkedHashMap<>();
        StringBuilder in = new StringBuilder();
        for (String k : keys) {
            in.append(in.isEmpty() ? "'" : ",'").append(k).append('\'');
        }
        try (ResultSet rs = st.executeQuery("SHOW " + scope + " STATUS WHERE Variable_name IN (" + in + ")")) {
            while (rs.next()) {
                m.put(rs.getString(1), rs.getLong(2));
            }
        }
        return m;
    }

    static Map<String, Long> diff(Map<String, Long> a, Map<String, Long> b, Map<String, Long> overhead) {
        Map<String, Long> d = new LinkedHashMap<>();
        for (String k : a.keySet()) {
            d.put(k, b.get(k) - a.get(k) - (overhead == null ? 0 : overhead.getOrDefault(k, 0L)));
        }
        return d;
    }

    record Timed(long nanos, Long value, String error) {
    }

    /** 执行一条语句（count 取值；page 取行数），记录耗时；3024 等错误记为 error。 */
    static Timed run(Connection c, String sql, List<Object> params) {
        long t0 = System.nanoTime();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            long v = 0;
            try (ResultSet rs = ps.executeQuery()) {
                boolean count = sql.contains("COUNT(*)");
                while (rs.next()) {
                    v = count ? rs.getLong(1) : v + 1;
                }
            }
            return new Timed(System.nanoTime() - t0, v, null);
        } catch (SQLException e) {
            return new Timed(System.nanoTime() - t0, null, "E" + e.getErrorCode());
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
                            "filtered=" + rs.getString("filtered"), "possible_keys=" + rs.getString("possible_keys"),
                            "Extra=" + rs.getString("Extra")));
                }
            }
        }
        return rows;
    }

    static double ms(long nanos) {
        return nanos / 1e6;
    }

    static long pct(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    static String stats(List<Long> nanos) {
        List<Long> s = new ArrayList<>(nanos);
        Collections.sort(s);
        return String.format("n=%d p50=%.1f p95=%.1f max=%.1f", s.size(), ms(pct(s, 0.5)), ms(pct(s, 0.95)),
                ms(s.isEmpty() ? 0 : s.get(s.size() - 1)));
    }

    static void bench(String label, String filter) throws Exception {
        String db = dbName();
        StringBuilder explainOut = new StringBuilder();
        StringBuilder tsv = new StringBuilder("shape\tsort\tpage\ttotal\trows\tcold_count_ms\tcold_page_ms\t"
                + "cold_total_ms\tcold_error\thot_total\thot_count\thot_page\thot_errors\tuncapped_hot_ms\t"
                + "http\thttp_status\thttp_total_matches\tcold_session_delta\thot_session_delta_per_run\t"
                + "cold_key_reads\tcold_key_read_requests\n");
        long rows;
        try (Connection c = admin(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM mac_vod")) {
            rs.next();
            rows = rs.getLong(1);
        }
        log("bench " + label + " on " + rows + " rows");
        try (App app = new App(db, label, 4); Connection c = DriverManager.getConnection(jdbcUrlFor(db, null),
                adminUser, adminPassword); Statement st = c.createStatement()) {
            String heapBefore = app.heap();
            Duration cpuBefore = app.cpu();
            long httpRequests = 0;
            Map<String, Long> o1 = status(st, "SESSION", SESSION_KEYS);
            Map<String, Long> o2 = status(st, "SESSION", SESSION_KEYS);
            Map<String, Long> overhead = diff(o1, o2, null);
            for (Shape s : SHAPES) {
                if (filter != null && !s.name.matches(filter)) {
                    continue;
                }
                for (String sort : SORTS) {
                    List<Integer> pages = DEEP_SHAPES.contains(s.name) ? List.of(1, 50, 1000) : List.of(1);
                    for (int page : pages) {
                        Sql count = countSql(s);
                        Sql pg = pageSql(s, sort, page, 20);
                        explainOut.append("### ").append(s.name).append(" sort=").append(sort).append(" page=")
                                .append(page).append('\n');
                        if (sort.equals("time") && page == 1) {
                            explainOut.append("count: ");
                            explain(c, count).forEach(x -> explainOut.append(x).append('\n'));
                        }
                        explainOut.append("page:  ");
                        explain(c, pg).forEach(x -> explainOut.append(x).append('\n'));

                        // 冷：只关闭并刷新一次性库的 mac_vod（MyISAM 键缓存块随之释放；操作系统文件缓存不受影响）
                        st.execute("FLUSH TABLES `" + db + "`.`mac_vod`");
                        Map<String, Long> g0 = status(st, "GLOBAL", GLOBAL_KEYS);
                        Map<String, Long> s0 = status(st, "SESSION", SESSION_KEYS);
                        Timed cc = run(c, count.text, count.params);
                        long total = cc.value == null ? -1 : cc.value;
                        boolean needPage = cc.value != null && cc.value > (long) (page - 1) * 20;
                        Timed cp = needPage ? run(c, pg.text, pg.params) : new Timed(0, 0L, null);
                        Map<String, Long> s1 = status(st, "SESSION", SESSION_KEYS);
                        Map<String, Long> g1 = status(st, "GLOBAL", GLOBAL_KEYS);
                        Map<String, Long> coldDelta = diff(s0, s1, overhead);
                        String coldErr = cc.error != null ? "count:" + cc.error : cp.error != null ? "page:" + cp.error : "";
                        long coldTotal = cc.nanos + cp.nanos;
                        int n = coldTotal < 100_000_000L ? 30 : coldTotal < 1_000_000_000L ? 10 : 5;

                        List<Long> hotT = new ArrayList<>(), hotC = new ArrayList<>(), hotP = new ArrayList<>();
                        int hotErrors = 0;
                        Map<String, Long> h0 = status(st, "SESSION", SESSION_KEYS);
                        for (int i = 0; i < n; i++) {
                            Timed a = run(c, count.text, count.params);
                            Timed b = needPage ? run(c, pg.text, pg.params) : new Timed(0, 0L, null);
                            if (a.error != null || b.error != null) {
                                hotErrors++;
                            }
                            hotC.add(a.nanos);
                            hotP.add(b.nanos);
                            hotT.add(a.nanos + b.nanos);
                        }
                        Map<String, Long> h1 = status(st, "SESSION", SESSION_KEYS);
                        Map<String, Long> hotDelta = diff(h0, h1, null);
                        StringBuilder hotPer = new StringBuilder();
                        for (var e : hotDelta.entrySet()) {
                            long per = (e.getValue() - overhead.getOrDefault(e.getKey(), 0L)) / n;
                            if (per != 0) {
                                hotPer.append(e.getKey()).append('=').append(per).append(' ');
                            }
                        }
                        String uncapped = "";
                        if (!coldErr.isEmpty() || hotErrors > 0) {
                            Timed ua = run(c, noHint(count.text), count.params);
                            total = ua.value == null ? total : ua.value;
                            boolean np = total > (long) (page - 1) * 20;
                            Timed ub = np ? run(c, noHint(pg.text), pg.params) : new Timed(0, 0L, null);
                            uncapped = String.format("count=%.1f page=%.1f", ms(ua.nanos), ms(ub.nanos));
                        }

                        String q = "/api/v1/videos?" + s.query() + (s.query().isEmpty() ? "" : "&") + "sort=" + sort
                                + "&order=desc&page=" + page;
                        List<Long> httpT = new ArrayList<>();
                        Map<Integer, Integer> codes = new TreeMap<>();
                        String httpTotal = "";
                        for (int i = 0; i < n; i++) {
                            App.Resp r = app.get(q);
                            httpRequests++;
                            httpT.add(r.nanos);
                            codes.merge(r.status, 1, Integer::sum);
                            Matcher m = Pattern.compile("\"total\":(\\d+)").matcher(r.body);
                            if (r.status == 200 && m.find()) {
                                httpTotal = m.group(1);
                            }
                        }
                        StringBuilder coldStr = new StringBuilder();
                        coldDelta.forEach((k, v) -> {
                            if (v != 0) {
                                coldStr.append(k).append('=').append(v).append(' ');
                            }
                        });
                        tsv.append(String.join("\t", s.name, sort, String.valueOf(page), String.valueOf(total),
                                String.valueOf(cp.value), String.format("%.1f", ms(cc.nanos)),
                                String.format("%.1f", ms(cp.nanos)), String.format("%.1f", ms(coldTotal)), coldErr,
                                stats(hotT), stats(hotC), stats(hotP), String.valueOf(hotErrors), uncapped,
                                stats(httpT), codes.toString(), httpTotal, coldStr.toString().strip(),
                                hotPer.toString().strip(),
                                String.valueOf(g1.get("Key_reads") - g0.get("Key_reads")),
                                String.valueOf(g1.get("Key_read_requests") - g0.get("Key_read_requests")))).append('\n');
                        log(String.format("%-12s %-4s p%-4d total=%-7d cold=%.0fms%s hot[%s] http[%s %s]", s.name, sort,
                                page, total, ms(coldTotal), coldErr.isEmpty() ? "" : "(" + coldErr + ")", stats(hotT),
                                stats(httpT), codes));
                    }
                }
            }
            Duration cpuAfter = app.cpu();
            String heapAfter = app.heap();
            String jvm = String.format("app_cpu_ms=%d http_requests=%d cpu_ms_per_request=%.2f%n--- heap before%n%s%n"
                            + "--- heap after%n%s%n", cpuAfter.minus(cpuBefore).toMillis(), httpRequests,
                    httpRequests == 0 ? 0 : (double) cpuAfter.minus(cpuBefore).toMillis() / httpRequests, heapBefore,
                    heapAfter);
            Files.writeString(WORK.resolve(label + "-jvm.txt"), jvm);
            log(jvm.lines().findFirst().orElse(""));
        }
        Files.writeString(WORK.resolve(label + "-bench.tsv"), tsv.toString());
        Files.writeString(WORK.resolve(label + "-explain.txt"), explainOut.toString());
        log("wrote " + WORK.resolve(label + "-bench.tsv"));
    }

    // ================================================================== SQL 一致性核对

    /** 解析 bench 的应用 DEBUG 日志，核对每个形态的 count/page SQL 文本（去空白）与绑定值和本程序一致。 */
    static void sqlcheck(String label) throws Exception {
        List<String> lines = Files.readAllLines(WORK.resolve(label + "-app.log"), StandardCharsets.UTF_8);
        Map<String, List<String>> logged = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            int p = lines.get(i).indexOf("==>  Preparing: ");
            if (p < 0) {
                continue;
            }
            String sql = squash(lines.get(i).substring(p + "==>  Preparing: ".length()));
            for (int j = i + 1; j < Math.min(lines.size(), i + 5); j++) {
                int q = lines.get(j).indexOf("==> Parameters: ");
                if (q >= 0) {
                    logged.computeIfAbsent(sql, k -> new ArrayList<>())
                            .add(lines.get(j).substring(q + "==> Parameters: ".length()).strip());
                    break;
                }
            }
        }
        // bench 中 total=0 的形态：应用在 total=0 时不执行分页语句（ContentReadService），日志里本就没有
        Map<String, String> totals = new HashMap<>();
        for (String l : Files.readAllLines(WORK.resolve(label + "-bench.tsv"), StandardCharsets.UTF_8)) {
            String[] f = l.split("\t");
            totals.put(f[0] + " " + f[1] + " " + f[2], f[3]);
        }
        int ok = 0, bad = 0, skipped = 0;
        StringBuilder out = new StringBuilder();
        for (Shape s : SHAPES) {
            for (String sort : SORTS) {
                for (Sql sql : List.of(countSql(s), pageSql(s, sort, 1, 20))) {
                    boolean isCount = sql.text.contains("COUNT(*)");
                    List<String> params = logged.get(squash(sql.text));
                    String expected = paramsString(sql.params);
                    boolean match = params != null && params.contains(expected);
                    if (!match && !isCount && "0".equals(totals.get(s.name + " " + sort + " 1"))) {
                        out.append("SKIP ").append(s.name).append(' ').append(sort)
                                .append(" page (total=0: the app does not run the page statement)\n");
                        skipped++;
                        continue;
                    }
                    out.append(match ? "OK   " : "BAD  ").append(s.name).append(' ').append(sort).append(' ')
                            .append(isCount ? "count" : "page").append('\n');
                    if (match) {
                        ok++;
                    } else {
                        bad++;
                        out.append("    expected params: ").append(expected).append('\n')
                                .append("    logged SQL found: ").append(params != null).append('\n');
                    }
                }
            }
        }
        out.append("ok=").append(ok).append(" bad=").append(bad).append(" skipped=").append(skipped).append('\n');
        Files.writeString(WORK.resolve(label + "-sqlcheck.txt"), out.toString());
        log("sqlcheck ok=" + ok + " bad=" + bad + " skipped=" + skipped);
    }

    static String squash(String s) {
        return s.replaceAll("\\s+", "");
    }

    static String paramsString(List<Object> params) {
        List<String> parts = new ArrayList<>();
        for (Object o : params) {
            parts.add(o + "(" + (o instanceof Integer ? "Integer" : o instanceof Long ? "Long" : "String") + ")");
        }
        return String.join(", ", parts);
    }

    // ================================================================== 表锁实验

    record Sample(long scheduledNanos, long startNanos, long endNanos, String error) {
    }

    static void lock(String label, int seconds) throws Exception {
        String db = dbName();
        Shape low = SHAPES.stream().filter(s -> s.name.equals("kw-low")).findFirst().orElseThrow();
        int maxId;
        try (Connection c = admin(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(vod_id) FROM mac_vod")) {
            rs.next();
            maxId = rs.getInt(1);
        }
        StringBuilder out = new StringBuilder("mode\treaders\tseconds\tupdates\tupdate_latency_ms\tupdate_sched_lag_ms\t"
                + "update_errors\treads_done\tread_latency_ms\tread_status\ttable_locks_waited\ttable_locks_immediate\t"
                + "max_waiting_lock\tavg_waiting_lock\tmax_running_select\tapp_cpu_ms\n");
        try (App app = new App(db, label + "-lock", 4)) {
            String heapBefore = app.heap();
            out.append(lockRun(db, "update-only", 0, seconds, maxId, low, null));
            for (int k : new int[]{1, 4, 8}) {
                out.append(lockRun(db, "jdbc", k, seconds, maxId, low, null));
            }
            for (int k : new int[]{1, 4, 8}) {
                out.append(lockRun(db, "http", k, seconds, maxId, low, app));
            }
            out.append("\n--- heap before\n").append(heapBefore).append("\n--- heap after\n").append(app.heap())
                    .append('\n');
        }
        Files.writeString(WORK.resolve(label + "-lock.tsv"), out.toString());
        log("wrote " + WORK.resolve(label + "-lock.tsv"));
    }

    static String lockRun(String db, String mode, int readers, int seconds, int maxId, Shape shape, App app)
            throws Exception {
        AtomicBoolean stop = new AtomicBoolean(false);
        ConcurrentLinkedQueue<Sample> updates = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Long> reads = new ConcurrentLinkedQueue<>();
        Map<String, AtomicInteger> readStatus = new java.util.concurrent.ConcurrentHashMap<>();
        AtomicInteger maxWaiting = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        AtomicLong waitingSum = new AtomicLong();
        AtomicInteger samples = new AtomicInteger();
        Map<String, Long> g0;
        try (Connection c = admin(db); Statement st = c.createStatement()) {
            g0 = status(st, "GLOBAL", GLOBAL_KEYS);
        }
        Duration cpu0 = app == null ? Duration.ZERO : app.cpu();
        ExecutorService readerPool = Executors.newFixedThreadPool(Math.max(1, readers));
        Sql count = countSql(shape);
        Sql page = pageSql(shape, "time", 1, 20);
        String q = "/api/v1/videos?" + shape.query() + "&sort=time&order=desc&page=1";
        for (int i = 0; i < readers; i++) {
            readerPool.submit(() -> {
                try (Connection c = app == null ? admin(db) : null) {
                    while (!stop.get()) {
                        long t0 = System.nanoTime();
                        String status;
                        if (app == null) {
                            Timed a = run(c, count.text, count.params);
                            Timed b = a.error == null ? run(c, page.text, page.params) : new Timed(0, 0L, null);
                            status = a.error != null ? a.error : b.error != null ? b.error : "ok";
                        } else {
                            status = String.valueOf(app.get(q).status);
                        }
                        reads.add(System.nanoTime() - t0);
                        readStatus.computeIfAbsent(status, k -> new AtomicInteger()).incrementAndGet();
                        if (status.equals("503")) {
                            // 被拒绝的客户端隔 50 ms 重试，避免空转把测量变成 503 压测
                            Thread.sleep(50);
                        }
                    }
                } catch (Exception e) {
                    readStatus.computeIfAbsent("exception:" + e.getClass().getSimpleName(), k -> new AtomicInteger())
                            .incrementAndGet();
                }
                return null;
            });
        }
        // 固定速率 5 次/秒的点击计数 UPDATE：每次由独立连接执行（模拟彼此独立的 PHP 请求），被阻塞时不影响下一次调度
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        ExecutorService updaterPool = Executors.newFixedThreadPool(32);
        ConcurrentLinkedQueue<Connection> updConns = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < 32; i++) {
            updConns.add(admin(db));
        }
        Random rnd = new Random(SEED + readers);
        long start = System.nanoTime();
        ticker.scheduleAtFixedRate(() -> {
            long scheduled = System.nanoTime();
            int id = 1 + rnd.nextInt(maxId);
            updaterPool.submit(() -> {
                Connection c = updConns.poll();
                long s0 = System.nanoTime();
                String err = null;
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE mac_vod SET vod_hits=vod_hits+1 WHERE vod_id=?")) {
                    ps.setInt(1, id);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    err = "E" + e.getErrorCode();
                } finally {
                    updConns.add(c);
                }
                updates.add(new Sample(scheduled, s0, System.nanoTime(), err));
            });
        }, 0, 200, TimeUnit.MILLISECONDS);
        // 每 200 ms 采样一次进程列表（只统计本一次性库的会话）
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        Connection sc = admin(db);
        sampler.scheduleAtFixedRate(() -> {
            try (Statement st = sc.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT SUM(state='Waiting for table level lock'), SUM(command='Query' AND info LIKE 'SELECT%') "
                            + "FROM information_schema.processlist WHERE db=DATABASE() AND id<>CONNECTION_ID()")) {
                rs.next();
                int w = rs.getInt(1);
                int r = rs.getInt(2);
                maxWaiting.accumulateAndGet(w, Math::max);
                maxRunning.accumulateAndGet(r, Math::max);
                waitingSum.addAndGet(w);
                samples.incrementAndGet();
            } catch (SQLException ignored) {
                // 采样失败不影响测量
            }
        }, 100, 200, TimeUnit.MILLISECONDS);
        Thread.sleep(seconds * 1000L);
        ticker.shutdown();
        stop.set(true);
        sampler.shutdown();
        sampler.awaitTermination(10, TimeUnit.SECONDS);
        readerPool.shutdown();
        readerPool.awaitTermination(120, TimeUnit.SECONDS);
        updaterPool.shutdown();
        updaterPool.awaitTermination(120, TimeUnit.SECONDS);
        for (Connection c : updConns) {
            c.close();
        }
        sc.close();
        long elapsed = System.nanoTime() - start;
        Map<String, Long> g1;
        try (Connection c = admin(db); Statement st = c.createStatement()) {
            g1 = status(st, "GLOBAL", GLOBAL_KEYS);
        }
        List<Long> updLat = new ArrayList<>(), updLag = new ArrayList<>();
        int updErr = 0;
        for (Sample s : updates) {
            updLat.add(s.endNanos - s.startNanos);
            updLag.add(s.endNanos - s.scheduledNanos);
            if (s.error != null) {
                updErr++;
            }
        }
        String row = String.join("\t", mode, String.valueOf(readers), String.format("%.1f", elapsed / 1e9),
                String.valueOf(updates.size()), stats(updLat), stats(updLag), String.valueOf(updErr),
                String.valueOf(reads.size()), stats(new ArrayList<>(reads)), new TreeMap<>(readStatus).toString(),
                String.valueOf(g1.get("Table_locks_waited") - g0.get("Table_locks_waited")),
                String.valueOf(g1.get("Table_locks_immediate") - g0.get("Table_locks_immediate")),
                String.valueOf(maxWaiting.get()),
                String.format("%.2f", samples.get() == 0 ? 0 : (double) waitingSum.get() / samples.get()),
                String.valueOf(maxRunning.get()),
                String.valueOf(app == null ? 0 : app.cpu().minus(cpu0).toMillis())) + "\n";
        log("lock " + row.strip());
        return row;
    }

    // ================================================================== 执行时限与 503

    /**
     * 另一会话持有 mac_vod 写锁时：带条件的查询应在约 3 s 时被 MAX_EXECUTION_TIME 中止（3024），HTTP 为 503；
     * 并发超过上限的包含匹配请求应立即 503；不带条件的查询不受时限约束（只有 MyBatis 10 s 兜底）。
     */
    static void timeout(String label) throws Exception {
        String db = dbName();
        StringBuilder out = new StringBuilder();
        Shape low = SHAPES.stream().filter(s -> s.name.equals("kw-low")).findFirst().orElseThrow();
        Shape area = SHAPES.stream().filter(s -> s.name.equals("area")).findFirst().orElseThrow();
        try (App app = new App(db, label + "-timeout", 4); Connection locker = admin(db);
             Statement ls = locker.createStatement(); Connection reader = admin(db)) {
            ls.execute("LOCK TABLES mac_vod WRITE");
            long held = System.nanoTime();
            try {
                Timed jt = run(reader, countSql(low).text, countSql(low).params);
                out.append(String.format("jdbc count kw-low under WRITE lock: %.0f ms error=%s%n", ms(jt.nanos),
                        jt.error));
                ExecutorService pool = Executors.newFixedThreadPool(8);
                List<java.util.concurrent.Future<App.Resp>> fs = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    fs.add(pool.submit(() -> app.get("/api/v1/videos?" + low.query() + "&page=1")));
                    Thread.sleep(50);
                }
                List<String> res = new ArrayList<>();
                for (var f : fs) {
                    App.Resp r = f.get();
                    res.add(String.format("%d@%.0fms", r.status, ms(r.nanos)));
                }
                out.append("http kw-low x8 concurrent (max-concurrent=4) under WRITE lock: ").append(res).append('\n');
                App.Resp ra = app.get("/api/v1/videos?" + area.query() + "&page=1");
                out.append(String.format("http area (exact, hinted) under WRITE lock: %d @ %.0f ms body=%s%n",
                        ra.status, ms(ra.nanos), ra.body));
                pool.shutdown();
            } finally {
                ls.execute("UNLOCK TABLES");
                out.append(String.format("write lock held %.0f ms%n", ms(System.nanoTime() - held)));
            }
            // 不带条件的查询：没有 MAX_EXECUTION_TIME，等锁直到 MyBatis 10 s JDBC 超时或锁释放
            ls.execute("LOCK TABLES mac_vod WRITE");
            held = System.nanoTime();
            try {
                App.Resp rb = app.get("/api/v1/videos?page=1");
                out.append(String.format("http base (no filter, no hint) under WRITE lock: %d @ %.0f ms body=%s%n",
                        rb.status, ms(rb.nanos), rb.body.length() > 200 ? rb.body.substring(0, 200) : rb.body));
            } finally {
                ls.execute("UNLOCK TABLES");
                out.append(String.format("write lock held %.0f ms%n", ms(System.nanoTime() - held)));
            }
            App.Resp after = app.get("/api/v1/videos?" + low.query() + "&page=1");
            out.append(String.format("http kw-low after unlock: %d @ %.0f ms%n", after.status, ms(after.nanos)));

            // 时限在 filesort 阶段触发时 MySQL 返回的错误码（bench 中 kw-amp 出现过 HTTP 500）：
            // 不加锁，直接重复执行接近 3 s 的 kw-amp 分页语句，统计错误码；再经应用请求，统计状态码
            Shape amp = SHAPES.stream().filter(s -> s.name.equals("kw-amp")).findFirst().orElseThrow();
            Map<String, Integer> codes = new TreeMap<>();
            for (String sort : SORTS) {
                Sql pg = pageSql(amp, sort, 1, 20);
                for (int i = 0; i < 6; i++) {
                    long t0 = System.nanoTime();
                    try (PreparedStatement ps = reader.prepareStatement(pg.text)) {
                        for (int j = 0; j < pg.params.size(); j++) {
                            ps.setObject(j + 1, pg.params.get(j));
                        }
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                // 读完结果
                            }
                        }
                        codes.merge("ok", 1, Integer::sum);
                    } catch (SQLException e) {
                        String key = "E" + e.getErrorCode() + "/" + e.getSQLState();
                        if (!codes.containsKey(key)) {
                            out.append(String.format("jdbc kw-amp page sort=%s: error %d sqlstate=%s after %.0f ms "
                                    + "message=%s%n", sort, e.getErrorCode(), e.getSQLState(), ms(System.nanoTime() - t0),
                                    e.getMessage()));
                        }
                        codes.merge(key, 1, Integer::sum);
                    }
                }
            }
            out.append("jdbc kw-amp page statement x12 (time/hits): ").append(codes).append('\n');
            Map<Integer, Integer> httpCodes = new TreeMap<>();
            for (String sort : SORTS) {
                for (int i = 0; i < 6; i++) {
                    App.Resp r = app.get("/api/v1/videos?" + amp.query() + "&sort=" + sort + "&order=desc&page=1");
                    httpCodes.merge(r.status, 1, Integer::sum);
                    if (r.status == 500) {
                        out.append("http 500 body: ").append(r.body).append('\n');
                    }
                }
            }
            out.append("http kw-amp x12 (time/hits): ").append(httpCodes).append('\n');
        }
        List<String> appLog = Files.readAllLines(WORK.resolve(label + "-timeout-app.log"), StandardCharsets.UTF_8);
        out.append("--- app log lines (WARN/ERROR)\n");
        for (String l : appLog) {
            if (l.contains(" WARN ") || l.contains(" ERROR ")) {
                out.append(l).append('\n');
            }
        }
        Files.writeString(WORK.resolve(label + "-timeout.txt"), out.toString());
        log("wrote " + WORK.resolve(label + "-timeout.txt"));
        System.out.print(out);
    }

    static void log(String msg) {
        System.out.println("[perf " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME) + "] " + msg);
    }
}
