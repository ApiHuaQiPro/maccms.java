package com.maccms.backend.install.application;

import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.common.write.WriteDataSourceSettings;
import com.maccms.backend.install.infrastructure.DeployConfigFile;
import com.maccms.backend.install.infrastructure.InstallConnections;
import com.maccms.backend.install.infrastructure.InstallDatabaseProbe;
import com.maccms.backend.install.infrastructure.InstallTarget;
import com.maccms.backend.install.infrastructure.InstallWrites;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import com.maccms.backend.legacyconfig.application.VarExportWriter;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 安装向导的第四、五步（旧 install/controller/Index.php:109-272，B22 docs/architecture/31-b22-install-wizard.md 第 3～6 节）。
 *
 * <p>与旧版的主要差异（零结构变更，路线图第 1.2 节）：不建库、不导入 install.sql、不提供"覆盖"——目标库由部署方在库外建好并原样导入
 * install.sql，这里只读核对结构与 install.sql 完全一致且 26 张表全部为空（相当于旧版导入 install.sql 之后的状态），再写初始分类、
 * 第一个管理员、站点配置与安装锁。数据库连接写进新系统的部署配置文件（{@link DeployConfigFile}），不写 {@code database.php}。
 */
public class InstallWizard {

    private static final Logger LOG = LoggerFactory.getLogger(InstallWizard.class);
    private static final ZoneId PRC = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter LOCK_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 旧 {@code mac_get_rndstr} 的字符表（application/common.php:320，不含 1）。 */
    static final String RND_PATTERN = "234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    /** 旧 {@code database.php} 的全部键（第四步逐个核对提交的字段，Index.php:131-136）。 */
    static final Set<String> DATABASE_KEYS = Set.of("type", "hostname", "database", "username", "password", "hostport", "dsn",
            "params", "charset", "prefix", "debug", "deploy", "rw_separate", "master_num", "slave_no", "fields_strict",
            "resultset_type", "auto_timestamp", "datetime_format", "sql_explain", "builder", "query");
    /** 新版加在两个表单里的表单令牌字段（31 文档第 7 节），不参与旧版的字段核对。 */
    public static final String TOKEN_FIELD = "__token__";
    static final Pattern PREFIX = Pattern.compile("[a-z0-9]{1,20}[_]{1}");
    static final String PREFIX_RULE = "^[a-z0-9]{1,20}[_]{1}";
    /** 新版只支持旧版默认的表前缀：全部 SQL 写死 mac_（31 文档第 3.4 节）。 */
    static final String SUPPORTED_PREFIX = "mac_";

    // 新版新增的提示（旧版没有对应的情况，31 文档第 3.4 节）
    static final String MSG_PREFIX = "新版只支持数据库前缀 mac_（全部查询按 mac_ 表名编写）";
    static final String MSG_ROOT = "安装向导默认不接受 root 账号：请使用专用账号；本机开发确需使用时设置 MACCMS_INSTALL_ALLOW_ROOT=true";
    static final String MSG_NO_DATABASE = "数据库不存在。新版不在应用内建库建表：请部署方在库外建库，并用 mysql 命令行原样导入"
            + " application/install/sql/install.sql 后，再测试数据库连接";
    static final String MSG_SCHEMA = "目标库的表结构与 install.sql 不一致（共 %d 处，如：%s）。新版不在应用内建表或改表：请部署方在库外"
            + "重新原样导入 application/install/sql/install.sql 后，再测试数据库连接";
    static final String MSG_NOT_EMPTY = "目标库的数据与刚导入 install.sql 时不同（%s，共 %d 张表）。安装向导只用于刚原样导入 install.sql 的库"
            + "（除 mac_group 的 3 个默认会员组外各表为空），不会清空或覆盖已有数据";
    static final String MSG_INSTALL_DIR = "新版只支持安装在网站根目录（install_dir 为 /）";
    static final String MSG_LOCK = "[application/data/install/install.lock]";

    /** 一次回复：{@code url} 为 null 时按旧版默认（Ajax 空串，页面返回上一页）。 */
    public record Reply(boolean success, String msg, String url) {
        static Reply error(String msg) {
            return new Reply(false, msg, null);
        }
    }

    private final InstallSettings settings;
    private final DeployConfigFile deploy;
    private final InstallDatabaseProbe probe;
    private final LegacyConfigService config;
    private final LegacySiteFiles files;
    private final InstallWrites writes;
    private final SecureRandom random = new SecureRandom();
    private final Object installLock = new Object();

    public InstallWizard(InstallSettings settings, DeployConfigFile deploy, InstallConnections connections,
                         LegacyConfigService config, LegacySiteFiles files) {
        this.settings = settings;
        this.deploy = deploy;
        this.probe = new InstallDatabaseProbe(connections);
        this.config = config;
        this.files = files;
        this.writes = new InstallWrites(connections);
    }

    /** 已安装（安装锁存在，旧 install.php:30）。 */
    public boolean locked() {
        return files.installLocked();
    }

    // ------------------------------------------------------------------ 第四步：测试数据库连接

    /**
     * 旧 {@code step4}（Index.php:109-171）：{@code post} 为表单字段（提交顺序，标量；旧版 {@code input('post.')}）。
     *
     * @param tokenOk 表单令牌是否正确（新版，31 文档第 7 节）；字段校验之后、连接之前检查
     */
    public Reply testConnection(Map<String, String> post, boolean tokenOk, LegacyLang lang) {
        if (!deploy.writable()) {
            return Reply.error("[" + InstallEnvironment.DEPLOY_LABEL + "]" + lang.get("install/write_read_err"));
        }
        Map<String, String> data = new LinkedHashMap<>(post);
        data.remove(TOKEN_FIELD);
        data.put("type", "mysql");
        String m = validateConnection(data, lang);
        if (m != null) {
            return Reply.error(m);
        }
        String cover = data.remove("cover");
        for (String k : data.keySet()) {
            if (!DATABASE_KEYS.contains(k)) {
                return Reply.error(lang.get("param") + k + lang.get("install/not_found"));
            }
        }
        if (!tokenOk) {
            return Reply.error(lang.get("install/access_denied"));
        }
        if (!SUPPORTED_PREFIX.equals(data.get("prefix"))) {
            return Reply.error(MSG_PREFIX);
        }
        String host = data.get("hostname");
        String database = data.get("database");
        if (!InstallTarget.HOST.matcher(host).matches()) {
            return Reply.error(lang.get("install/server_address") + regexText(lang));
        }
        if (!InstallTarget.DATABASE.matcher(database).matches()) {
            return Reply.error(lang.get("install/database_name") + regexText(lang));
        }
        Integer port = port(data.get("hostport"));
        if (port == null) {
            return Reply.error(lang.get("install/database_connect_err"));
        }
        InstallTarget target = new InstallTarget(host, port, database, data.get("username"),
                data.get("password") == null ? "" : data.get("password"));
        if (isRoot(target) && !settings.allowRoot()) {
            return Reply.error(MSG_ROOT);
        }
        // 旧版：不带库名连接并执行 select version()，失败即"数据库连接失败"；新版另确认库已存在（不建库）
        switch (probe.checkServer(target)) {
            case CONNECT_FAILED -> {
                return Reply.error(lang.get("install/database_connect_err"));
            }
            case NO_DATABASE -> {
                return Reply.error(MSG_NO_DATABASE);
            }
            case OK -> {
            }
        }
        String problem = checkTarget(target, lang);
        if (problem != null) {
            return Reply.error(problem);
        }
        try {
            deploy.write(deploymentEntries(target));
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("安装向导写部署配置失败：{}", SafeExceptionLog.typeChain(e));
            return Reply.error("[" + InstallEnvironment.DEPLOY_LABEL + "]" + lang.get("write_err_database"));
        }
        // 旧版：不覆盖且库已存在时提示"已存在，可直接安装"，否则建库后提示"连接成功"；新版库总是已存在（由部署方建好）
        return new Reply(true, lang.get(InstallValidation.truthy(cover) ? "install/database_connect_ok"
                : "install/database_name_haved"), "");
    }

    /** 旧版 step4 的验证规则（Index.php:117-124），按顺序，第一条不通过即停。 */
    static String validateConnection(Map<String, String> d, LegacyLang lang) {
        String t;
        String m;
        t = lang.get("install/server_address");
        if ((m = InstallValidation.require(d.get("hostname"), t, lang)) != null) {
            return m;
        }
        t = lang.get("install/database_port");
        if ((m = InstallValidation.require(d.get("hostport"), t, lang)) != null
                || (m = InstallValidation.number(d.get("hostport"), t, lang)) != null) {
            return m;
        }
        t = lang.get("install/database_name");
        if ((m = InstallValidation.require(d.get("database"), t, lang)) != null) {
            return m;
        }
        t = lang.get("install/database_username");
        if ((m = InstallValidation.require(d.get("username"), t, lang)) != null) {
            return m;
        }
        t = lang.get("install/database_pre");
        if ((m = InstallValidation.require(d.get("prefix"), t, lang)) != null
                || (m = InstallValidation.regex(d.get("prefix"), PREFIX, PREFIX_RULE, t, lang)) != null) {
            return m;
        }
        t = lang.get("install/overwrite_database");
        if ((m = InstallValidation.require(d.get("cover"), t, lang)) != null
                || (m = InstallValidation.in(d.get("cover"), new String[] {"0", "1"}, t, lang)) != null) {
            return m;
        }
        return null;
    }

    // ------------------------------------------------------------------ 第五步：执行安装

    /**
     * 旧 {@code step5}（Index.php:177-272）。参数为原始表单值（缺失为 null）；{@code passwordBytes} 为密码的原始字节（md5 按字节计算）。
     */
    public Reply install(String account, String password, byte[] passwordBytes, String installDir, String initdata,
                         boolean tokenOk, LegacyLang lang) {
        InstallTarget target = readDeployTarget();
        if (target == null) {
            return Reply.error(lang.get("install/please_test_connect"));
        }
        if (account == null || account.isEmpty() || password == null || password.isEmpty()) {
            return Reply.error(lang.get("install/please_input_admin_name_pass"));
        }
        String t = lang.get("install/admin_name");
        String m;
        if ((m = InstallValidation.require(account, t, lang)) != null
                || (m = InstallValidation.alphaNum(account, t, lang)) != null) {
            return Reply.error(m);
        }
        t = lang.get("install/admin_pass");
        if ((m = InstallValidation.require(password, t, lang)) != null
                || (m = InstallValidation.length(password, 6, 20, t, lang)) != null) {
            return Reply.error(m);
        }
        if (!tokenOk) {
            return Reply.error(lang.get("install/access_denied"));
        }
        String dir = installDir == null || installDir.isEmpty() ? "/" : installDir;
        if (!dir.equals("/")) {
            return Reply.error(MSG_INSTALL_DIR);
        }
        if (isRoot(target) && !settings.allowRoot()) {
            return Reply.error(MSG_ROOT);
        }
        synchronized (installLock) {
            if (locked()) {
                return Reply.error(lang.get("install/access_denied"));
            }
            String problem = checkTarget(target, lang);
            if (problem != null) {
                return Reply.error(problem);
            }
            if (!writeSiteConfig(dir)) {
                return Reply.error(lang.get("write_err_config"));
            }
            InstallWrites.Result written;
            try {
                written = writes.write(target, settings.allowRoot(),
                        initdata != null && InstallValidation.looseEquals(initdata, "1") ? InitData.rows() : List.of(),
                        account, md5(passwordBytes));
            } catch (RuntimeException e) {
                LOG.warn("安装向导写库失败：{}", SafeExceptionLog.typeChain(e));
                return Reply.error(lang.get("install/init_err"));
            }
            if (written == InstallWrites.Result.INIT_DATA_FAILED) {
                return Reply.error(lang.get("install/init_data_err"));
            }
            if (written == InstallWrites.Result.ADMIN_FAILED) {
                return Reply.error(lang.get("install/admin_err"));
            }
            try {
                files.writeInstallLock(LocalDateTime.now(PRC).format(LOCK_TIME).getBytes(StandardCharsets.UTF_8));
            } catch (LegacySiteFiles.FileException e) {
                LOG.warn("安装向导写安装锁失败：{}", e.reason());
                return Reply.error(MSG_LOCK + lang.get("install/write_read_err"));
            }
        }
        return new Reply(true, lang.get("install/is_ok"), settings.adminUrl());
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 只读核对：结构与 install.sql 一致，各表行数与刚导入 install.sql 时相同（{@link InstallSchema#SEED_ROWS}：只有 mac_group 有 3 行，
     * 其余为空），即旧版第五步执行 install.sql 之后的状态。通过时为 null，否则为提示。
     */
    private String checkTarget(InstallTarget target, LegacyLang lang) {
        InstallDatabaseProbe.TargetState state;
        try {
            state = probe.inspect(target);
        } catch (InstallDatabaseProbe.ProbeFailedException e) {
            return lang.get("install/database_connect_err");
        }
        List<String> diff = InstallSchema.differences(state.fingerprint());
        if (!diff.isEmpty()) {
            List<String> shown = diff.subList(0, Math.min(InstallSchema.SHOWN, diff.size()));
            return String.format(MSG_SCHEMA, diff.size(), String.join("；", shown));
        }
        List<String> changed = new ArrayList<>();
        state.rows().forEach((table, rows) -> {
            if (rows != InstallSchema.SEED_ROWS.getOrDefault(table, 0L)) {
                changed.add(table + (rows < 0 ? " 的行数未知" : " 有 " + rows + " 行"));
            }
        });
        if (!changed.isEmpty()) {
            return String.format(MSG_NOT_EMPTY, String.join("、", changed.subList(0, Math.min(InstallSchema.SHOWN,
                    changed.size()))), changed.size());
        }
        return null;
    }

    /**
     * 旧 Index.php:203-218：在 {@code config('maccms')} 上改 5 项后整个写回 {@code extra/maccms.php}。旧版第 204-205 行把变量名写成
     * {@code $cofnig_new}，{@code app.cache_flag} 与 {@code app.lang} 实际没有写进去；新版照旧不写（31 文档 D-B22-6）。
     */
    private boolean writeSiteConfig(String installDir) {
        try {
            config.update(LegacyConfigFile.MACCMS, current -> {
                PhpArray c = current == null ? new PhpArray(Map.of()) : current;
                c = set(c, new PhpValue.PhpInt(0), "api", "vod", "status");
                c = set(c, new PhpValue.PhpInt(0), "api", "art", "status");
                c = set(c, new PhpValue.PhpInt(0), "interface", "status");
                c = set(c, new PhpValue.PhpString(rndstr(16)), "interface", "pass");
                c = set(c, new PhpValue.PhpString(installDir), "site", "install_dir");
                return VarExportWriter.macArr2File(c);
            });
            return true;
        } catch (LegacyConfigException | IllegalArgumentException e) {
            LOG.warn("安装向导写站点配置失败：{}", e.getClass().getSimpleName());
            return false;
        }
    }

    /** {@code $a[k1][k2]… = v}：已有的键原位替换，没有的键追加在末尾；中间一级不是数组时按 PHP 报错处理（抛出）。 */
    static PhpArray set(PhpArray a, PhpValue v, String... path) {
        Map<Object, PhpValue> m = new LinkedHashMap<>(a.entries());
        Object key = PhpArray.normalizeKey(path[0]);
        if (path.length == 1) {
            m.put(key, v);
        } else {
            PhpValue child = m.get(key);
            PhpArray sub;
            if (child == null || child instanceof PhpValue.PhpNull) {
                sub = new PhpArray(Map.of());
            } else if (child instanceof PhpArray pa) {
                sub = pa;
            } else {
                throw new IllegalArgumentException("配置项不是数组");
            }
            m.put(key, set(sub, v, java.util.Arrays.copyOfRange(path, 1, path.length)));
        }
        return new PhpArray(m);
    }

    /** 第四步写下的目标库（读回部署配置文件）；没有或不完整时为 null（旧版 database.php 的 hostname/database/username 为空）。 */
    private InstallTarget readDeployTarget() {
        try {
            Map<String, String> m = deploy.read();
            if (m == null) {
                return null;
            }
            java.util.regex.Matcher u = Pattern.compile("jdbc:mysql://([^:/?]+):([0-9]{1,5})/([^?]+)(?:\\?.*)?")
                    .matcher(m.getOrDefault("MACCMS_DB_URL", ""));
            String user = m.get("MACCMS_DB_USERNAME");
            if (!u.matches() || user == null || user.isEmpty()) {
                return null;
            }
            return new InstallTarget(u.group(1), Integer.parseInt(u.group(2)), u.group(3), user,
                    m.getOrDefault("MACCMS_DB_PASSWORD", ""));
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** 部署配置文件的内容：只读连接池与可写数据源都用向导里填的账号（旧版只有一个 database.php）。 */
    static Map<String, String> deploymentEntries(InstallTarget t) {
        String url = t.baseUrl() + "?characterEncoding=UTF-8";
        Map<String, String> out = new LinkedHashMap<>();
        out.put("MACCMS_DB_URL", url);
        out.put("MACCMS_DB_USERNAME", t.username());
        out.put("MACCMS_DB_PASSWORD", t.password());
        if (isRoot(t)) {
            out.put("MACCMS_DB_ALLOW_ROOT", "true");
        }
        out.putAll(WriteDataSourceSettings.deploymentEntries(url, t.username(), t.password(), isRoot(t)));
        return out;
    }

    private static boolean isRoot(InstallTarget t) {
        return "root".equalsIgnoreCase(t.username().strip());
    }

    /** 端口：十进制 1～65535（旧版只要求 is_numeric，再交给 PDO；其他写法新版按连接失败处理）。 */
    private static Integer port(String raw) {
        if (raw == null || !raw.matches("[0-9]{1,5}")) {
            return null;
        }
        int p = Integer.parseInt(raw);
        return p >= 1 && p <= 65535 ? p : null;
    }

    private static String regexText(LegacyLang lang) {
        return LegacyLang.DEFAULT_CODE.equals(lang.code()) ? "不符合指定规则" : " not conform to the rules";
    }

    String rndstr(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(RND_PATTERN.charAt(random.nextInt(RND_PATTERN.length())));
        }
        return sb.toString();
    }

    static String md5(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
