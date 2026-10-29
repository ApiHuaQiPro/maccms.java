package com.maccms.backend.common.write;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 校验后的可写数据源设置。错误信息只含配置项名称与原因，不含地址、账号、密码的取值。
 *
 * <p>规则（docs/architecture/12-write-infrastructure.md 第 2 节）：
 * <ul>
 * <li>{@code url}、{@code username}、{@code password} 都必须单独提供，不回落到只读连接池的配置。</li>
 * <li>只接受 {@code jdbc:mysql://主机[:端口]/库名[?参数]}，库名与主机、端口必须与只读连接池（{@code spring.datasource.url}）一致。</li>
 * <li>由应用固定的连接参数不能出现在地址里：{@code useAffectedRows}（固定 false，UPDATE 返回匹配行数）、{@code allowMultiQueries}
 * （固定 false）、{@code allowLoadLocalInfile} 等本地文件导入参数、{@code sessionVariables}、地址中的 {@code user}/{@code password}。</li>
 * <li>root 账号需要显式设置 {@code allow-root=true}（仅本机开发）。</li>
 * </ul>
 */
public record WriteDataSourceSettings(String url, String username, String password, String database, int poolSize) {

    static final int DEFAULT_POOL_SIZE = 4;
    static final int MAX_POOL_SIZE = 50;

    private static final Pattern MYSQL_URL = Pattern.compile(
            "^jdbc:mysql://([A-Za-z0-9.\\-]+)(?::([0-9]{1,5}))?/([A-Za-z0-9_]+)(?:\\?(.*))?$");
    private static final Set<String> FIXED_PARAMETERS = Set.of("useaffectedrows", "allowmultiqueries",
            "allowloadlocalinfile", "allowloadlocalinfileinpath", "allowurlinlocalinfile", "sessionvariables", "user",
            "password", "password1", "password2", "password3");

    /** 由部署参数与只读连接池地址得到设置；{@code enabled} 不在这里判断。 */
    public static WriteDataSourceSettings from(WriteDataSourceProperties p, String readUrl) {
        String url = required(p.url(), "maccms.write.url（MACCMS_WRITE_DB_URL）").strip();
        String username = required(p.username(), "maccms.write.username（MACCMS_WRITE_DB_USERNAME）");
        String password = required(p.password(), "maccms.write.password（MACCMS_WRITE_DB_PASSWORD）");
        if (!username.equals(username.strip())) {
            throw invalid("maccms.write.username（MACCMS_WRITE_DB_USERNAME）首尾不能有空白");
        }
        if ("root".equalsIgnoreCase(username) && !Boolean.TRUE.equals(p.allowRoot())) {
            throw invalid("拒绝用 root 账号打开可写数据源：请使用专用写账号；本机开发确需使用时设置 "
                    + "maccms.write.allow-root（MACCMS_WRITE_DB_ALLOW_ROOT）=true");
        }
        int poolSize = p.poolSize() == null ? DEFAULT_POOL_SIZE : p.poolSize();
        if (poolSize < 1 || poolSize > MAX_POOL_SIZE) {
            throw invalid("maccms.write.pool-size（MACCMS_WRITE_DB_POOL_SIZE）须在 1～" + MAX_POOL_SIZE + " 之间");
        }

        Matcher w = MYSQL_URL.matcher(url);
        if (!w.matches()) {
            throw invalid("maccms.write.url（MACCMS_WRITE_DB_URL）须为 jdbc:mysql://主机[:端口]/库名[?参数] 形式（单主机、带库名）");
        }
        if (w.group(4) != null) {
            for (String pair : w.group(4).split("&")) {
                String key = (pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair).strip()
                        .toLowerCase(Locale.ROOT);
                if (FIXED_PARAMETERS.contains(key)) {
                    throw invalid("maccms.write.url（MACCMS_WRITE_DB_URL）不能设置连接参数 " + key
                            + "（由应用固定，凭证只经 USERNAME/PASSWORD 提供）");
                }
            }
        }
        Matcher r = readUrl == null ? null : MYSQL_URL.matcher(readUrl.strip());
        if (r == null || !r.matches()) {
            throw invalid("无法确认可写数据源与只读数据源指向同一个库：spring.datasource.url（MACCMS_DB_URL）"
                    + "须为 jdbc:mysql://主机[:端口]/库名[?参数] 形式");
        }
        if (!hostPort(w).equals(hostPort(r)) || !w.group(3).equals(r.group(3))) {
            throw invalid("maccms.write.url（MACCMS_WRITE_DB_URL）与 spring.datasource.url（MACCMS_DB_URL）"
                    + "须指向同一主机、端口与库名");
        }
        return new WriteDataSourceSettings(url, username, password, w.group(3), poolSize);
    }

    /**
     * 安装向导写部署配置文件（B22，31 文档第 5.3 节）时可写数据源的各项：键为环境变量名，值原样（由调用方转义）。
     * root 账号另写 {@code MACCMS_WRITE_DB_ALLOW_ROOT=true}（向导本身已按部署参数允许 root 才会走到这里）。
     */
    public static Map<String, String> deploymentEntries(String url, String username, String password, boolean root) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("MACCMS_WRITE_ENABLED", "true");
        out.put("MACCMS_WRITE_DB_URL", url);
        out.put("MACCMS_WRITE_DB_USERNAME", username);
        out.put("MACCMS_WRITE_DB_PASSWORD", password);
        if (root) {
            out.put("MACCMS_WRITE_DB_ALLOW_ROOT", "true");
        }
        return out;
    }

    /** 不输出地址、账号与密码。 */
    @Override
    public String toString() {
        return "WriteDataSourceSettings[poolSize=" + poolSize + "]";
    }

    private static String hostPort(Matcher m) {
        return m.group(1).toLowerCase(Locale.ROOT) + ":" + (m.group(2) == null ? "3306" : Integer.parseInt(m.group(2)));
    }

    private static String required(String value, String key) {
        if (value == null || value.isBlank()) {
            throw invalid("已打开可写数据源（maccms.write.enabled=true），但 " + key + " 未设置；"
                    + "可写数据源的地址与凭证必须单独提供，不沿用只读连接池的配置");
        }
        return value;
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("可写数据源配置错误：" + message);
    }
}
