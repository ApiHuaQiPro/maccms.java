package com.maccms.backend.install.application;

import com.maccms.backend.common.lang.LegacyLang;
import com.maccms.backend.install.infrastructure.DeployConfigFile;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import java.util.ArrayList;
import java.util.List;

/**
 * 第二步"运行环境检测"（旧 install/controller/Index.php:278-369 的 {@code checkNnv/checkDir/checkFunc}，B22 31 文档第 3.2 节）。
 * 三张表的行与旧版一一对应，内容换成新系统实际依赖的东西：
 * <ul>
 * <li>环境：操作系统（同旧版）；PHP 版本换成 Java 版本（要求 21 及以上）；</li>
 * <li>目录/文件：{@code ./application/database.php} 换成部署配置文件（{@code MACCMS_DEPLOY_CONFIG}）；新版不用的 {@code ./runtime} 去掉；
 * 其余照旧（{@link LegacySiteFiles#INSTALL_CHECKED}，另加安装锁所在的 {@code ./application/data/install}）；</li>
 * <li>函数/扩展：pdo/pdo_mysql/zip/fileinfo/curl/xml/file_get_contents/mb_strlen 换成 MySQL JDBC 驱动、zip、XML、图像（验证码）、MD5。
 * 只检查"能力是否存在"，不发任何请求（旧版 curl 一项同样只看扩展是否加载）。</li>
 * </ul>
 * 任何一项不通过即"环境检测未通过"（旧 {@code session('install_error', true)}），第三步起拒绝进入。
 */
public final class InstallEnvironment {

    /** 一行：环境 {@code [名称, 所需, 当前, ok|no]}；目录 {@code [路径, 所需, 当前, ok|no]}；函数 {@code [名称, 类型, 结果, yes|no]}。 */
    public record Row(String name, String required, String current, String state) {
    }

    /** 三张表与是否全部通过。 */
    public record Result(List<Row> env, List<Row> dir, List<Row> func, boolean failed) {
    }

    static final int JAVA_REQUIRED = 21;
    static final String DEPLOY_LABEL = "MACCMS_DEPLOY_CONFIG";

    private final LegacySiteFiles files;
    private final DeployConfigFile deploy;

    public InstallEnvironment(LegacySiteFiles files, DeployConfigFile deploy) {
        this.files = files;
        this.deploy = deploy;
    }

    public Result check(LegacyLang lang) {
        boolean failed = false;
        List<Row> env = new ArrayList<>();
        env.add(new Row(lang.get("install/os"), "Windows/Unix", System.getProperty("os.name", ""), "ok"));
        int feature = Runtime.version().feature();
        boolean javaOk = feature >= JAVA_REQUIRED;
        failed |= !javaOk;
        env.add(new Row("Java", JAVA_REQUIRED + "及以上", System.getProperty("java.version", ""), javaOk ? "ok" : "no"));

        String rw = lang.get("install/read_and_write");
        List<Row> dir = new ArrayList<>();
        boolean deployOk = deploy.writable();
        failed |= !deployOk;
        dir.add(new Row(DEPLOY_LABEL, rw, deployOk ? rw : lang.get("install/not_writable"), deployOk ? "ok" : "no"));
        for (String rel : LegacySiteFiles.INSTALL_CHECKED) {
            LegacySiteFiles.Access a = files.installAccess(rel);
            boolean file = rel.endsWith(".php");
            String current = switch (a) {
                case WRITABLE -> rw;
                case NOT_WRITABLE -> lang.get("install/not_writable");
                // 旧版：文件不存在按"不可写"，目录不存在按"不存在"
                case NOT_FOUND -> lang.get(file ? "install/not_writable" : "install/not_found");
            };
            boolean ok = a == LegacySiteFiles.Access.WRITABLE;
            failed |= !ok;
            dir.add(new Row("./" + rel, rw, current, ok ? "ok" : "no"));
        }

        List<Row> func = new ArrayList<>();
        String support = lang.get("install/support");
        // 旧语言包里 install/not_support 的值也是"支持"（zh-cn.php:2028），不支持时照旧显示这个值，靠整行标红区分
        String notSupport = lang.get("install/not_support");
        for (String[] f : new String[][] {
            {"mysql-connector-j", "install/class", "com.mysql.cj.jdbc.Driver"},
            {"java.util.zip", "install/model", "java.util.zip.ZipInputStream"},
            {"javax.xml", "install/model", "javax.xml.parsers.DocumentBuilderFactory"},
            {"javax.imageio", "install/model", "javax.imageio.ImageIO"},
            {"MD5", "install/function", null}}) {
            boolean ok = f[2] == null ? md5Available() : classPresent(f[2]);
            failed |= !ok;
            func.add(new Row(f[0], lang.get(f[1]), ok ? support : notSupport, ok ? "yes" : "no"));
        }
        return new Result(List.copyOf(env), List.copyOf(dir), List.copyOf(func), failed);
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, InstallEnvironment.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static boolean md5Available() {
        try {
            java.security.MessageDigest.getInstance("MD5");
            return true;
        } catch (java.security.NoSuchAlgorithmException e) {
            return false;
        }
    }
}
