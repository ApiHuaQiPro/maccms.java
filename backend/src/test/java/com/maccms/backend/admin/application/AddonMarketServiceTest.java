package com.maccms.backend.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.integration.application.AddonMarket;
import com.maccms.backend.legacyconfig.application.LegacyConfigProperties;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 插件市场安装/升级的流程（B29，35 文档第 6～8 节）：先校验后落盘；校验失败与下载失败不留任何文件；写到一半失败时按原样回滚；
 * 升级的备份、配置值保留；没有 Java 实现的插件保持禁用。下载用假市场（不联网，联网部分见 {@code FastadminAddonMarketTest}）。
 */
class AddonMarketServiceTest {

    @TempDir
    Path site;

    private final Deque<Object> downloads = new ArrayDeque<>();
    private final Map<String, Map<String, String>> extends_ = new HashMap<>();
    private AddonMarketService service;
    private AddonService addons;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(site.resolve("application/extra"));
        Files.writeString(site.resolve("application/extra/addons.php"),
                "<?php\n\nreturn array (\n  'autoload' => false,\n  'hooks' => \n  array (\n  ),\n  'route' => \n  array (\n  ),\n);");
        Files.createDirectories(site.resolve("static/js"));
        Files.writeString(site.resolve("static/js/addons.js"), "define([], function () {\n    \n});");
        Files.createDirectories(site.resolve("addons"));
        LegacyConfigProperties props = new LegacyConfigProperties(site.resolve("application").toString());
        LegacySiteFiles files = new LegacySiteFiles(props);
        addons = new AddonService(files, new LegacyConfigService(props), "", Clock.systemUTC(), RandomGenerator.of("L64X128MixRandom"));
        AddonMarket market = new AddonMarket() {
            @Override
            public byte[] download(String name, Map<String, String> extend) {
                extends_.put(name, extend);
                Object next = downloads.poll();
                if (next instanceof RuntimeException e) {
                    throw e;
                }
                return (byte[]) next;
            }

            @Override
            public int maxPackageBytes() {
                return 1 << 20;
            }
        };
        service = new AddonMarketService(addons, files, market, AddonPackage.Limits.DEFAULT,
                Clock.fixed(Instant.parse("2026-10-29T04:05:06Z"), ZoneOffset.UTC));
    }

    private static String info(String name, int state) {
        return "name = " + name + "\ntitle = t\nintro = i\nauthor = a\nwebsite = \nversion = 1.0.0\nstate = " + state + "\n";
    }

    private static String cls(String name) {
        String c = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return "<?php\nnamespace addons\\" + name + ";\nclass " + c + " extends \\think\\Addons\n{\n    public function demoHook($p)\n    {\n    }\n}\n";
    }

    private Map<String, String> snapshot() throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(site)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                out.put(site.relativize(p).toString().replace('\\', '/'), Files.readString(p, StandardCharsets.ISO_8859_1));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 安装

    @Test
    void thirdPartyAddonIsInstalledDisabledWithoutAssetsOrSql() throws IOException {
        downloads.add(AddonPackageTest.zip("Demo.php", cls("demo"), "info.ini", info("demo", 1), "assets/a.css", "a{}",
                "static/demo/x.js", "x", "bootstrap.js", "console.log(1);",
                "install.sql", "INSERT INTO __PREFIX__link (link_name) VALUES ('x');\n"));
        service.install("demo", false, Map.of("uid", "1"));
        assertThat(extends_.get("demo")).containsEntry("uid", "1");
        assertThat(site.resolve("addons/demo/assets/a.css")).exists();
        assertThat(site.resolve("addons/demo/install.sql")).exists();
        assertThat(Files.readString(site.resolve("addons/demo/info.ini"))).contains("state = 0\n").contains("url = /addons/demo.html\n");
        assertThat(site.resolve("static/addons/demo")).doesNotExist();
        assertThat(site.resolve("static/demo/x.js")).doesNotExist();
        assertThat(Files.readString(site.resolve("static/js/addons.js"))).doesNotContain("console.log");
        assertThat(Files.readString(site.resolve("application/extra/addons.php"))).doesNotContain("demo_hook");
    }

    @Test
    void javaAddonIsInstalledEnabledWithAssetsAndHooks() throws IOException {
        downloads.add(AddonPackageTest.zip("Adminloginbg.php", cls("adminloginbg").replace("demoHook", "adminLoginInit"),
                "info.ini", info("adminloginbg", 0), "assets/css/a.css", "a{}", "bootstrap.js", "console.log(2);"));
        service.install("adminloginbg", false, Map.of());
        assertThat(Files.readString(site.resolve("addons/adminloginbg/info.ini"))).contains("state = 1\n");
        assertThat(site.resolve("static/addons/adminloginbg/css/a.css")).hasContent("a{}");
        assertThat(Files.readString(site.resolve("static/js/addons.js"))).contains("console.log(2);");
        assertThat(Files.readString(site.resolve("application/extra/addons.php"))).contains("'admin_login_init'");
    }

    @Test
    void validationFailuresWriteNothing() throws IOException {
        Map<String, String> before = snapshot();
        Object[][] cases = {
            {AddonPackageTest.zip("Demo.php", cls("demo"), "info.ini", info("demo", 0), "../../evil.php", "x"), AddonPackage.UNSAFE_PATH},
            {"PK\3\4garbage".getBytes(StandardCharsets.ISO_8859_1), AddonPackage.UNABLE_TO_OPEN},
            {"<html>404</html>".getBytes(StandardCharsets.UTF_8), AddonPackage.UNABLE_TO_OPEN},
            {AddonPackageTest.zip("Demo.php", cls("demo"), "info.ini", info("demo", 0), "install.sql",
                    "CREATE TABLE `__PREFIX__x` (`id` int);\nINSERT INTO __PREFIX__link (link_name) VALUES ('x');\n"),
                String.format(AddonMarketService.SQL_REFUSED, "CREATE")},
            {AddonPackageTest.zip("Demo.php", cls("demo"), "info.ini", info("demo", 0), "install.sql", "ALTER TABLE mac_vod ADD x int;\n"),
                String.format(AddonMarketService.SQL_REFUSED, "ALTER")},
            {AddonPackageTest.zip("info.ini", info("demo", 0)), "插件主启动程序不存在"},
            {AddonPackageTest.zip("Demo.php", cls("demo"), "info.ini", "name = demo\n"), "配置文件不完整"},
            {new AddonMarket.DownloadFailed(), AddonMarket.DOWNLOAD_FAILED},
        };
        for (Object[] c : cases) {
            downloads.add(c[0]);
            assertThatThrownBy(() -> service.install("demo", false, Map.of())).as(String.valueOf(c[1]))
                    .isInstanceOf(AddonService.Failed.class).hasMessage((String) c[1]);
            assertThat(snapshot()).as(String.valueOf(c[1])).isEqualTo(before);
        }
    }

    @Test
    void marketRefusalCarriesTheLegacyJsonValues() {
        downloads.add(new AddonMarket.MarketRefused("{\"code\":-1,\"msg\":\"请登录\",\"data\":{\"login\":1}}"));
        assertThatThrownBy(() -> service.install("demo", false, Map.of())).isInstanceOfSatisfying(AddonMarketService.Refused.class, r -> {
            assertThat(PhpValues.text(r.msg())).isEqualTo("请登录");
            assertThat(PhpValues.text(r.code())).isEqualTo("-1");
        });
        downloads.add(new AddonMarket.MarketRefused("{oops"));
        assertThatThrownBy(() -> service.install("demo", false, Map.of())).isInstanceOfSatisfying(AddonMarketService.Refused.class,
                r -> assertThat(r.msg()).isNull());
    }

    @Test
    void namesAreRestricted() {
        for (String n : List.of("../x", "a/b", "a.b", "", "a b")) {
            assertThatThrownBy(() -> service.install(n, true, Map.of())).hasMessage(AddonMarketService.BAD_NAME);
            assertThatThrownBy(() -> service.upgrade(n, Map.of())).hasMessage(AddonMarketService.BAD_NAME);
        }
        assertThat(downloads).isEmpty();
    }

    @Test
    void existingAddonNeedsForceAndConflictsAreReported() throws IOException {
        Files.createDirectories(site.resolve("addons/demo"));
        assertThatThrownBy(() -> service.install("demo", false, Map.of())).hasMessage("Addon already exists");
        Files.writeString(site.resolve("static/js/x.js"), "old");
        downloads.add(AddonPackageTest.zip("Other.php", cls("other"), "info.ini", info("other", 0), "static/js/x.js", "new"));
        assertThatThrownBy(() -> service.install("other", false, Map.of())).isInstanceOfSatisfying(AddonService.Failed.class, f -> {
            assertThat(f.getMessage()).isEqualTo("发现冲突文件");
            assertThat(f.code()).isEqualTo(-3);
        });
        assertThat(site.resolve("addons/other")).doesNotExist();
    }

    @Test
    void failureWhileWritingRollsBackEverything() throws IOException {
        downloads.add(AddonPackageTest.zip("Adminloginbg.php", cls("adminloginbg"), "info.ini", info("adminloginbg", 0),
                "assets/a.css", "a{}"));
        Map<String, String> before = snapshot();
        // addons.php 变成目录：刷新钩子表时失败
        Files.delete(site.resolve("application/extra/addons.php"));
        Files.createDirectories(site.resolve("application/extra/addons.php"));
        before.remove("application/extra/addons.php");
        assertThatThrownBy(() -> service.install("adminloginbg", false, Map.of())).isInstanceOf(AddonService.Failed.class);
        assertThat(site.resolve("addons/adminloginbg")).doesNotExist();
        assertThat(site.resolve("static/addons/adminloginbg")).doesNotExist();
        assertThat(snapshot()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 升级

    private void installed(String name, int state, String config) throws IOException {
        Path d = site.resolve("addons/" + name);
        Files.createDirectories(d);
        Files.writeString(d.resolve(Character.toUpperCase(name.charAt(0)) + name.substring(1) + ".php"), cls(name));
        Files.writeString(d.resolve("info.ini"), info(name, state));
        Files.writeString(d.resolve("old.txt"), "kept");
        if (config != null) {
            Files.writeString(d.resolve("config.php"), config);
        }
    }

    private static String config(String mode) {
        return "<?php\n\nreturn array (\n  0 => \n  array (\n    'name' => 'mode',\n    'title' => 'm',\n    'type' => 'radio',\n"
                + "    'content' => \n    array (\n    ),\n    'value' => '" + mode + "',\n  ),\n);\n";
    }

    @Test
    void upgradeMergesKeepsConfigValuesAndWritesABackup() throws IOException {
        installed("demo", 0, config("fixed"));
        downloads.add(AddonPackageTest.zip("Demo.php", cls("demo"), "info.ini", info("demo", 0).replace("1.0.0", "1.1.0"),
                "config.php", config("daily"), "new.txt", "n"));
        service.upgrade("demo", Map.of("token", "t"));
        assertThat(site.resolve("addons/demo/old.txt")).hasContent("kept");
        assertThat(site.resolve("addons/demo/new.txt")).hasContent("n");
        assertThat(Files.readString(site.resolve("addons/demo/info.ini"))).contains("version = 1.1.0");
        assertThat(Files.readString(site.resolve("addons/demo/config.php"))).isEqualTo(config("fixed"));
        // 旧 date("YmdHis")：PRC 时间
        assertThat(site.resolve("runtime/addons/demo-backup-20261029120506.zip")).exists();
    }

    @Test
    void upgradeRules() throws IOException {
        assertThatThrownBy(() -> service.upgrade("nothere", Map.of())).hasMessage("Addon not exists");
        installed("demo", 1, null);
        assertThatThrownBy(() -> service.upgrade("demo", Map.of())).hasMessage("Please disable addon first");
        assertThat(downloads).isEmpty();
    }

    @Test
    void thirdPartyUpgradeCannotSwitchItselfOn() throws IOException {
        installed("demo", 0, null);
        downloads.add(AddonPackageTest.zip("info.ini", info("demo", 1)));
        service.upgrade("demo", Map.of());
        assertThat(Files.readString(site.resolve("addons/demo/info.ini"))).contains("state = 0\n");
    }

    @Test
    void rejectedUpgradeLeavesNoBackupAndNoChange() throws IOException {
        installed("demo", 0, null);
        Map<String, String> before = snapshot();
        downloads.add(AddonPackageTest.zip("install.sql", "DROP TABLE mac_vod;\n"));
        assertThatThrownBy(() -> service.upgrade("demo", Map.of())).hasMessage(String.format(AddonMarketService.SQL_REFUSED, "DROP"));
        downloads.add(AddonPackageTest.zip("../../x", "y"));
        assertThatThrownBy(() -> service.upgrade("demo", Map.of())).hasMessage(AddonPackage.UNSAFE_PATH);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(site.resolve("runtime")).doesNotExist();
    }

    /**
     * 部署回滚演练（35 文档第 8 节）：开启插件市场前复制 {@code addons/}、{@code static/}、{@code application/extra/addons.php}；安装、升级之后
     * 用复制件覆盖回去、删掉新文件（含 {@code runtime/addons/} 的备份），站点与开启前逐字节相同。
     */
    @Test
    void snapshotRestoreDrill() throws IOException {
        Path snap = Files.createTempDirectory("maccms-b29-snap");
        try {
            Map<String, String> before = snapshot();
            for (String dir : List.of("addons", "static", "application/extra")) {
                copyTree(site.resolve(dir), snap.resolve(dir));
            }
            downloads.add(AddonPackageTest.zip("Adminloginbg.php", cls("adminloginbg"), "info.ini", info("adminloginbg", 0),
                    "assets/a.css", "a{}"));
            service.install("adminloginbg", false, Map.of());
            addons.disable("adminloginbg");
            downloads.add(AddonPackageTest.zip("info.ini", info("adminloginbg", 0).replace("1.0.0", "1.0.1")));
            service.upgrade("adminloginbg", Map.of());
            assertThat(snapshot()).isNotEqualTo(before);
            for (String dir : List.of("addons", "static", "application/extra", "runtime")) {
                deleteTree(site.resolve(dir));
            }
            for (String dir : List.of("addons", "static", "application/extra")) {
                copyTree(snap.resolve(dir), site.resolve(dir));
            }
            assertThat(snapshot()).isEqualTo(before);
        } finally {
            deleteTree(snap);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path d = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(d);
                } else {
                    Files.copy(p, d);
                }
            }
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    @Test
    void failedUpgradeRestoresTheOldDirectory() throws IOException {
        installed("demo", 0, null);
        Map<String, String> before = snapshot();
        Files.delete(site.resolve("application/extra/addons.php"));
        Files.createDirectories(site.resolve("application/extra/addons.php"));
        before.remove("application/extra/addons.php");
        downloads.add(AddonPackageTest.zip("Demo.php", "<?php // v2", "new.txt", "n"));
        assertThatThrownBy(() -> service.upgrade("demo", Map.of())).isInstanceOf(AddonService.Failed.class);
        Map<String, String> after = snapshot();
        after.keySet().removeIf(k -> k.startsWith("runtime/addons/"));
        assertThat(after).isEqualTo(before);
    }
}
