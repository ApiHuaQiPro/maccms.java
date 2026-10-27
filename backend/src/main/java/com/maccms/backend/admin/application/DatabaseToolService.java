package com.maccms.backend.admin.application;

import com.maccms.backend.admin.application.view.Reply;
import com.maccms.backend.admin.infrastructure.DatabaseReplaceWriteMapper;
import com.maccms.backend.admin.infrastructure.DatabaseToolMapper;
import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * 数据库工具中只读与纯 DML 的部分（旧 admin/controller/Database.php、common/util/Database.php；路线图 B15 行，24 文档第 9 节）：表状态、
 * 备份导出（{@code SHOW CREATE TABLE} 与 {@code SELECT} 写成备份文件，格式同旧版）、备份列表与删除、查看列、批量替换（一条 UPDATE）。
 * <b>还原备份、执行 SQL、优化表、修复表不在这里</b>：它们会执行结构语句或表维护语句，按零结构变更在入口拒绝（{@link #REFUSED}）。
 *
 * <p>备份文件里的 {@code DROP TABLE IF EXISTS} 与建表语句只是写进文件的文本，新系统从不执行它们（SchemaGuardTest 登记的例外，
 * {@code AdminSystemGoldenMySqlIT} 断言导出只发出 SHOW/SELECT）。
 */
public final class DatabaseToolService {

    /** 还原备份、执行 SQL、优化表、修复表的固定提示（新增的文字，24 文档 D-B15-1）。 */
    public static final String REFUSED = "新系统不在后台执行还原备份、执行 SQL、优化表、修复表：这些操作会改表结构或做表维护，"
            + "请由部署方用 mysqldump、mysql 命令行等库外手段完成";
    /** 备份目录不在允许的范围（新增的文字，D-B15-2）。 */
    public static final String BAD_BACKUP_PATH = "备份目录必须在 application/data/backup/ 下";

    private static final ZoneId PRC = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern BACKUP_FILE = Pattern.compile("^(\\d{8})-(\\d{6})-(\\d+)\\.sql(?:\\.gz)?$");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z0-9_]{1,64}");

    /** 备份文件头部里的连接信息（旧 {@code config('database.hostname'/'hostport'/'database')}，不含账号与密码）。 */
    public record Endpoint(String host, String port, String database) {
    }

    private final DatabaseToolMapper reader;
    private final DatabaseReplaceWriteMapper writer;
    private final LegacyConfigService config;
    private final LegacySiteFiles files;
    private final Clock clock;
    private final Endpoint endpoint;

    /** @param writer 可写数据源关闭时为 null（批量替换不可用） */
    public DatabaseToolService(DatabaseToolMapper reader, DatabaseReplaceWriteMapper writer, LegacyConfigService config,
                               LegacySiteFiles files, Clock clock, Endpoint endpoint) {
        this.reader = reader;
        this.writer = writer;
        this.config = config;
        this.files = files;
        this.clock = clock;
        this.endpoint = endpoint;
    }

    // ------------------------------------------------------------------ 表

    /** {@code SHOW TABLE STATUS} 的各行（值：字符串、整数或 NULL；时间列按 {@code Y-m-d H:i:s}）。 */
    public List<PhpArray> tableStatus() {
        List<PhpArray> out = new ArrayList<>();
        for (LinkedHashMap<String, Object> row : reader.tableStatus()) {
            PhpArray a = PhpArrays.empty();
            for (Map.Entry<String, Object> e : row.entrySet()) {
                a = PhpArrays.put(a, e.getKey(), php(e.getValue()));
            }
            out.add(a);
        }
        return out;
    }

    private List<String> tableNames() {
        List<String> names = new ArrayList<>();
        for (LinkedHashMap<String, Object> row : reader.tableStatus()) {
            names.add(text(row.get("Name")));
        }
        return names;
    }

    /** 旧 {@code isValidTable}：表名出现在 {@code SHOW TABLE STATUS} 里（新版另要求只含字母数字下划线，才能放进 SQL 标识符）。 */
    boolean validTable(String table) {
        return table != null && IDENT.matcher(table).matches() && tableNames().contains(table);
    }

    /** {@code SHOW COLUMNS FROM 表} 的各行（Field、Type、Null、Key、Default、Extra；NULL 照样保留）。 */
    public List<Map<String, Object>> columns(String table) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LinkedHashMap<String, Object> row : reader.columns(table)) {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            for (String k : List.of("Field", "Type", "Null", "Key", "Default", "Extra")) {
                Object v = row.get(k);
                m.put(k, v == null ? null : text(v));
            }
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ 批量替换

    /**
     * 旧 {@code Database::rep} 的 POST（Database.php:287-323，令牌由调用方先校验）：表不在库里 → "Table is invalid."；列、查找串、替换串都非空时，
     * 列必须是该表的列（{@code SHOW COLUMNS}）→ 否则 "Field is invalid."，再执行一条 {@code UPDATE 表 SET 列 = REPLACE(列, 查找串, 替换串)}；
     * 否则"参数错误"。旧表单里的 {@code where} 旧版已不使用，新版同样忽略。
     */
    public Reply replace(String table, String field, String find, String to, LegacyLang lang) {
        if (!empty(table) && !validTable(table)) {
            return Reply.error("Table is invalid.");
        }
        if (empty(field) || empty(find) || empty(to)) {
            return Reply.error(lang.get("param_err"));
        }
        if (empty(table) || !validTable(table)) {
            return Reply.error("Table is invalid.");
        }
        boolean known = false;
        for (Map<String, Object> c : columns(table)) {
            if (PhpLoose.equals(c.get("Field"), field)) {
                known = true;
                break;
            }
        }
        List<String> mapped = DatabaseReplaceWriteMapper.COLUMNS.get(table);
        if (!known || mapped == null || !mapped.contains(field)) {
            return Reply.error("Field is invalid.");
        }
        if (writer == null) {
            return Reply.error(lang.get("param_err"));
        }
        REPLACERS.get(table).apply(writer, new String[] {field, find, to});
        return Reply.success(lang.get("run_ok"));
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty() || s.equals("0");
    }

    /** 表名 → 写 Mapper 里该表的方法。 */
    private static final Map<String, BiFunction<DatabaseReplaceWriteMapper, String[], Integer>> REPLACERS = replacers();

    private static Map<String, BiFunction<DatabaseReplaceWriteMapper, String[], Integer>> replacers() {
        Map<String, BiFunction<DatabaseReplaceWriteMapper, String[], Integer>> m = new LinkedHashMap<>();
        for (String table : DatabaseReplaceWriteMapper.COLUMNS.keySet()) {
            StringBuilder name = new StringBuilder("replaceIn");
            for (String part : table.split("_")) {
                name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
            java.lang.reflect.Method method;
            try {
                method = DatabaseReplaceWriteMapper.class.getMethod(name.toString(), String.class, String.class, String.class);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(name.toString());
            }
            m.put(table, (w, a) -> {
                try {
                    return (Integer) method.invoke(w, a[0], a[1], a[2]);
                } catch (ReflectiveOperationException e) {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    throw c instanceof RuntimeException r ? r : new IllegalStateException(c.getClass().getName());
                }
            });
        }
        return m;
    }

    // ------------------------------------------------------------------ 备份目录

    /** 当前的 {@code db} 配置。 */
    private PhpValue db() {
        return config.read(LegacyConfigFile.MACCMS).get("db");
    }

    /**
     * {@code db.backup_path}（默认 {@code ./application/data/backup/database/}）规范成站点根目录下的相对路径：去掉开头的 {@code ./} 与结尾的
     * {@code /}；必须在 {@code application/data/backup/} 下，否则 null（旧版允许任意目录，D-B15-2）。
     */
    String backupDir() {
        String p = PhpValues.text(PhpArrays.get(db(), "backup_path")).strip();
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (!p.startsWith("application/data/backup/") && !p.equals("application/data/backup")) {
            return null;
        }
        return p;
    }

    /**
     * 旧 {@code Database::index?group=import}（Database.php:17-49）：备份目录里 {@code Ymd-His-N.sql[.gz]} 的文件按时间分组（卷数取最大、
     * 大小相加、压缩方式 {@code 无}/{@code GZ}）。旧版按文件系统的顺序，新版按文件名。目录不存在时创建。
     */
    public List<PhpArray> backups() {
        String dir = backupDir();
        if (dir == null) {
            return List.of();
        }
        files.mkdirs(dir);
        LinkedHashMap<String, PhpArray> list = new LinkedHashMap<>();
        for (LegacySiteFiles.Entry e : files.list(dir)) {
            Matcher m = BACKUP_FILE.matcher(e.name());
            if (e.directory() || !m.matches()) {
                continue;
            }
            String date = m.group(1).substring(0, 4) + "-" + m.group(1).substring(4, 6) + "-" + m.group(1).substring(6, 8);
            String time = m.group(2).substring(0, 2) + ":" + m.group(2).substring(2, 4) + ":" + m.group(2).substring(4, 6);
            String key = date + " " + time;
            long part = Long.parseLong(m.group(3));
            PhpArray info = list.get(key);
            long size = e.size();
            if (info != null) {
                part = Math.max(PhpIntval.of(PhpValues.text(info.get("part"))), part);
                size += PhpIntval.of(PhpValues.text(info.get("size")));
            }
            String ext = e.name().substring(e.name().lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
            long epoch = LocalDateTime.parse(key, DATE_TIME).atZone(PRC).toEpochSecond();
            PhpArray a = PhpArrays.empty();
            a = PhpArrays.put(a, "part", new PhpValue.PhpInt(part));
            a = PhpArrays.put(a, "size", new PhpValue.PhpInt(size));
            a = PhpArrays.put(a, "compress", ext.equals("SQL") ? "无" : ext);
            a = PhpArrays.put(a, "time", new PhpValue.PhpInt(epoch));
            list.put(key, a);
        }
        List<PhpArray> out = new ArrayList<>();
        list.forEach((k, v) -> out.add(PhpArrays.put(v, "key", k)));
        return out;
    }

    /**
     * 旧 {@code Database::del}（Database.php:222-235）：删除 {@code date('Ymd-His', $id)-*.sql*} 的各卷；删不掉时"删除失败"。
     */
    public Reply delete(String id, LegacyLang lang) {
        if (empty(id)) {
            return Reply.error(lang.get("admin/database/select_del_file"));
        }
        String dir = backupDir();
        if (dir == null) {
            return Reply.error(BAD_BACKUP_PATH);
        }
        String name = STAMP.format(Instant.ofEpochSecond(PhpIntval.of(id)).atZone(PRC)) + "-";
        boolean left = false;
        for (LegacySiteFiles.Entry e : files.list(dir)) {
            if (!e.directory() && e.name().startsWith(name) && e.name().substring(name.length()).contains(".sql")) {
                files.delete(dir + "/" + e.name());
                if (files.stat(dir + "/" + e.name()) != null) {
                    left = true;
                }
            }
        }
        return left ? Reply.error(lang.get("del_err")) : Reply.success(lang.get("del_ok"));
    }

    // ------------------------------------------------------------------ 备份导出

    /**
     * 旧 {@code Database::export}（Database.php:62-139）：
     * <ol>
     * <li>{@code ids} 为空 → "请选择您要备份的数据表"；名字里含 {@code _admin} 的表移到最后（旧版只保留最后一个这样的表，照旧）；</li>
     * <li>备份目录里有 {@code backup.lock} → "检测到有一个备份任务正在执行"；否则建目录、写锁文件（内容为请求时间）；</li>
     * <li>文件名 {@code Ymd-His-卷号.sql}（{@code db.compress} 为真时 {@code .sql.gz}，按 {@code db.compress_level} 压缩），每卷开头写头部注释；
     * 估算大小超过 {@code db.part_size} 时换下一卷（压缩时按一半估算，同旧版）；</li>
     * <li>每张表：{@code SHOW CREATE TABLE} 写成"删表 + 建表"两段文本，{@code SELECT COUNT(*)} 后每 1000 行一批 {@code SELECT}，每行写成
     * {@code INSERT INTO `表` VALUES ('…', '…');}（各值 {@code addslashes}、NULL 为空串，整行去掉 \n 与 \r）；</li>
     * <li>删除锁文件，"备份完成"。</li>
     * </ol>
     * 新版另：表名必须在库里（旧版直接拼进 SQL）；中途出错时也删除锁文件（旧版留下锁文件，之后的备份都被挡住）。
     *
     * @param start 旧版 {@code export($ids, $start)} 从请求里绑定的起始行（只作用于第一张表；按 {@code intval}）
     */
    public Reply export(List<String> ids, String start, LegacyLang lang) {
        if (ids == null || ids.isEmpty() || (ids.size() == 1 && empty(ids.getFirst()))) {
            return Reply.error(lang.get("admin/database/select_export_table"));
        }
        List<String> tables = new ArrayList<>();
        String admin = null;
        for (String t : ids) {
            if (t.contains("_admin")) {
                admin = t;
            } else {
                tables.add(t);
            }
        }
        if (admin != null) {
            tables.add(admin);
        }
        String dir = backupDir();
        if (dir == null) {
            return Reply.error(BAD_BACKUP_PATH);
        }
        String lock = dir + "/backup.lock";
        if (files.stat(lock) != null) {
            return Reply.error(lang.get("admin/database/lock_check"));
        }
        List<String> valid = tableNames();
        for (String t : tables) {
            if (!IDENT.matcher(t).matches() || !valid.contains(t)) {
                return Reply.error(lang.get("admin/database/backup_err"));
            }
        }
        long now = clock.instant().getEpochSecond();
        files.mkdirs(dir);
        files.replace(lock, Long.toString(now).getBytes(StandardCharsets.US_ASCII), null);
        PhpValue db = db();
        BackupWriter w = new BackupWriter(dir, STAMP.format(Instant.ofEpochSecond(now).atZone(PRC)),
                PhpIntval.of(PhpValues.text(PhpArrays.get(db, "part_size"))), PhpLoose.bool(PhpArrays.get(db, "compress")),
                (int) Math.max(0, Math.min(9, PhpIntval.of(PhpValues.text(PhpArrays.get(db, "compress_level"))))));
        try {
            w.create();
            long from = PhpIntval.of(start);
            for (String table : tables) {
                long next = backup(w, table, from);
                while (next != 0) {
                    next = backup(w, table, next);
                }
                from = 0;
            }
        } catch (IOException | RuntimeException e) {
            return Reply.error(lang.get("admin/database/backup_err"));
        } finally {
            w.close();
            files.delete(lock);
        }
        return Reply.success(lang.get("admin/database/backup_ok"));
    }

    /** 旧 {@code common/util/Database::backup($table, $start)}：返回下一批的起始行，没有了为 0。 */
    private long backup(BackupWriter w, String table, long start) throws IOException {
        if (start == 0) {
            String create = text(reader.createTable(table).get("Create Table"));
            w.write("\n-- -----------------------------\n-- Table structure for `" + table + "`\n-- -----------------------------\n"
                    + dropTableText(table) + "\n" + LegacyPhp.trim(create) + ";\n\n");
        }
        long count = reader.count(table);
        if (count != 0) {
            if (start == 0) {
                w.write("-- -----------------------------\n-- Records of `" + table + "`\n-- -----------------------------\n");
            }
            List<String> cols = new ArrayList<>();
            for (Map<String, Object> c : columns(table)) {
                String f = text(c.get("Field"));
                if (!IDENT.matcher(f).matches()) {
                    throw new IllegalStateException("列名");
                }
                cols.add(f);
            }
            StringBuilder select = new StringBuilder();
            for (String c : cols) {
                if (select.length() > 0) {
                    select.append(", ");
                }
                select.append("CAST(`").append(c).append("` AS CHAR) AS `").append(c).append('`');
            }
            for (LinkedHashMap<String, Object> row : reader.rows(table, select.toString(), start)) {
                StringBuilder one = new StringBuilder();
                for (int i = 0; i < cols.size(); i++) {
                    if (i > 0) {
                        one.append("', '");
                    }
                    Object v = row.get(cols.get(i));
                    one.append(addslashes(v == null ? "" : text(v)));
                }
                String line = one.toString().replace("\n", "").replace("\r", "");
                w.write("INSERT INTO `" + table + "` VALUES ('" + line + "');\n");
            }
            if (count > start + 1000) {
                return start + 1000;
            }
        }
        return 0;
    }

    /**
     * 写进备份文件的"删表"文本（旧 common/util/Database.php:118）。只是文件内容：新系统从不执行备份文件，也不把这段文字交给数据库
     * （SchemaGuardTest 登记的唯一例外；AdminSystemGoldenMySqlIT 断言导出期间只发出 SHOW/SELECT）。
     */
    static String dropTableText(String table) {
        return "DROP TABLE IF EXISTS `" + table + "`;";
    }

    /** PHP {@code addslashes}：{@code '}、{@code "}、{@code \}、NUL 前加反斜线（NUL 写成 {@code \0}）。 */
    static String addslashes(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\'', '"', '\\' -> sb.append('\\').append(c);
                case '\0' -> sb.append("\\0");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 分卷写备份文件（旧 {@code common/util/Database} 的 open/create/write）。 */
    private final class BackupWriter {
        private final String dir;
        private final String name;
        private final long partSize;
        private final boolean compress;
        private final int level;
        private int part = 1;
        private OutputStream out;
        private double size;

        BackupWriter(String dir, String name, long partSize, boolean compress, int level) {
            this.dir = dir;
            this.name = name;
            this.partSize = partSize;
            this.compress = compress;
            this.level = level;
        }

        void create() throws IOException {
            write("-- -----------------------------\n-- Think MySQL Data Transfer \n-- \n-- Host     : " + endpoint.host() + "\n"
                    + "-- Port     : " + endpoint.port() + "\n-- Database : " + endpoint.database() + "\n-- \n-- Part : #" + part
                    + "\n-- Date : " + DATE_TIME.format(clock.instant().atZone(PRC)) + "\n-- -----------------------------\n\n"
                    + "SET FOREIGN_KEY_CHECKS = 0;\n\n");
        }

        void write(String sql) throws IOException {
            byte[] b = sql.getBytes(StandardCharsets.UTF_8);
            double s = compress ? b.length / 2.0 : b.length;
            open(s);
            out.write(b);
        }

        private void open(double s) throws IOException {
            if (out != null) {
                size += s;
                if (size > partSize) {
                    close();
                    part++;
                    create();
                }
                return;
            }
            String rel = dir + "/" + name + "-" + part + ".sql" + (compress ? ".gz" : "");
            LegacySiteFiles.Entry existing = files.stat(rel);
            OutputStream raw = files.openAppend(rel);
            out = compress ? new GZIPOutputStream(raw, 8192) {
                {
                    def.setLevel(level);
                }
            } : raw;
            size = (existing == null ? 0 : existing.size()) + s;
        }

        void close() {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // 同旧版 @gzclose/@fclose
                }
                out = null;
            }
        }
    }

    // ------------------------------------------------------------------ 取值

    private static PhpValue php(Object v) {
        return switch (v) {
            case null -> PhpValue.PhpNull.INSTANCE;
            case Long l -> new PhpValue.PhpInt(l);
            case Integer i -> new PhpValue.PhpInt(i);
            case java.math.BigInteger b -> b.bitLength() < 64 ? new PhpValue.PhpInt(b.longValue()) : new PhpValue.PhpString(b.toString());
            default -> new PhpValue.PhpString(text(v));
        };
    }

    /** 数据库返回值的文字（byte[] 按 UTF-8；时间按 {@code Y-m-d H:i:s}）。 */
    static String text(Object v) {
        return switch (v) {
            case null -> "";
            case byte[] b -> new String(b, StandardCharsets.UTF_8);
            case java.util.Date t -> DATE_TIME.format(LocalDateTime.ofInstant(t.toInstant(), ZoneId.systemDefault()));
            case LocalDateTime t -> DATE_TIME.format(t);
            case BigDecimal d -> d.toPlainString();
            default -> String.valueOf(v);
        };
    }

    /** {@code round($bytes / 1024, 2)} 按 PHP 的浮点输出（去掉末尾的 0）。 */
    public static String kb(Object bytes) {
        long b = PhpIntval.of(PhpValues.text(bytes instanceof PhpValue p ? p : new PhpValue.PhpString(String.valueOf(bytes))));
        BigDecimal r = BigDecimal.valueOf(b).divide(BigDecimal.valueOf(1024), 2, RoundingMode.HALF_UP).stripTrailingZeros();
        return r.signum() == 0 ? "0" : r.toPlainString();
    }
}
