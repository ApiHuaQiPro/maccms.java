package com.maccms.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.common.config.DatabaseAccountGuard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/** 静态护栏：只读 SQL、不输出敏感列、不内置凭证、缺配置时拒绝启动。 */
class ReadOnlyGuardrailsTest {

    private static final Path MAPPERS = Path.of("src", "main", "resources", "mapper");
    private static final Path MAIN_RESOURCES = Path.of("src", "main", "resources");

    private static List<Path> mapperFiles() throws IOException {
        try (Stream<Path> s = Files.walk(MAPPERS)) {
            return s.filter(p -> p.toString().endsWith(".xml")).toList();
        }
    }

    private static String sqlOnly(Path p) throws IOException {
        // 去掉 XML 注释，只检查真正会执行的 SQL
        return Files.readString(p, StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
    }

    @Test
    void mappersContainOnlySelects() throws IOException {
        assertThat(mapperFiles()).isNotEmpty();
        for (Path p : mapperFiles()) {
            String xml = sqlOnly(p);
            assertThat(xml).as(p.toString()).doesNotContain("<insert", "<update", "<delete", "${");
            String upper = xml.toUpperCase(Locale.ROOT);
            for (String kw : List.of("INSERT ", "UPDATE ", "DELETE ", "REPLACE ", "TRUNCATE ", "DROP ", "ALTER ",
                    "CREATE ", "GRANT ", "SET ", "FOR UPDATE", "LOCK ")) {
                assertThat(upper).as(p + " 含 " + kw).doesNotContain(kw);
            }
        }
    }

    @Test
    void mappersNeverSelectSensitiveVodColumns() throws IOException {
        Pattern col = Pattern.compile("\\b(vod_pwd\\w*|vod_play_\\w+|vod_down_\\w+|vod_points\\w*|vod_trysee|"
                + "group_id|vod_lock|vod_tpl\\w*|vod_reurl|vod_jumpurl|vod_author|vod_rel_\\w+|vod_plot\\w*|"
                + "vod_pic_screenshot|type_tpl\\w*|type_jumpurl|type_union)\\b");
        String vod = sqlOnly(MAPPERS.resolve("content/VodMapper.xml"));
        Matcher m = col.matcher(vod);
        while (m.find()) {
            // 唯一允许的出现：CHAR_LENGTH(vod_pwd) 的布尔结果
            String around = vod.substring(Math.max(0, m.start() - 12), m.end());
            assertThat(around).as("VodMapper 不应查询 " + m.group()).contains("CHAR_LENGTH(vod_pwd");
        }
        Matcher t = col.matcher(sqlOnly(MAPPERS.resolve("content/TypeMapper.xml")));
        assertThat(t.find()).as("TypeMapper 不应查询模板/跳转/union 列").isFalse();
        // GroupMapper 必须按 group_id 取游客组，这是唯一允许读 group_id 的地方
        assertThat(sqlOnly(MAPPERS.resolve("content/GroupMapper.xml"))).contains("FROM mac_group");
    }

    @Test
    void mainResourcesContainNoCredentials() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN_RESOURCES)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                assertThat(credentialViolations(text)).as(p.toString()).isEmpty();
            }
        }
        // 自检（B07 起可写数据源的密码占位符带空默认值 ${MACCMS_WRITE_DB_PASSWORD:}，规则相应收窄：占位符内只允许空默认值）
        assertThat(credentialViolations("password: ${MACCMS_DB_PASSWORD}\n    password: ${MACCMS_WRITE_DB_PASSWORD:}\n"
                + "url: ${MACCMS_DB_URL}")).isEmpty();
        for (String bad : List.of("password: secret", "spring.datasource.password=x",
                "password: ${MACCMS_WRITE_DB_PASSWORD:secret}", "password: ${MACCMS_WRITE_DB_PASSWORD: x}",
                "url: jdbc:mysql://127.0.0.1/db", "url: ${MACCMS_DB_URL:jdbc:mysql://127.0.0.1/db}", "username: root")) {
            assertThat(credentialViolations(bad)).as(bad).isNotEmpty();
        }
        String yml = Files.readString(MAIN_RESOURCES.resolve("application.yml"), StandardCharsets.UTF_8);
        assertThat(yml).contains("url: ${MACCMS_DB_URL}", "username: ${MACCMS_DB_USERNAME}",
                "password: ${MACCMS_DB_PASSWORD}", "read-only: true", "mode: never");
    }

    private static List<String> credentialViolations(String text) {
        List<String> out = new ArrayList<>();
        // 占位符整体换成 ${} 后再查"password 后面不是占位符"；占位符里的默认值另查：密码只允许空默认值
        String outsidePlaceholders = text.replaceAll("\\$\\{[^}]*}", "\\${}");
        for (String pattern : List.of("(?i)password\\s*[:=]\\s*(?!\\$\\{)\\S", "(?i)jdbc:mysql://[^$\\s]",
                "(?i)username\\s*:\\s*root")) {
            if (Pattern.compile(pattern).matcher(pattern.contains("password") ? outsidePlaceholders : text).find()) {
                out.add(pattern);
            }
        }
        if (Pattern.compile("(?i)\\$\\{[^}]*PASSWORD:[^}]").matcher(text).find()) {
            out.add("密码占位符不得带默认值");
        }
        return out;
    }

    @Test
    void startupFailsWithoutDatabaseConfiguration() {
        Assumptions.assumeTrue(System.getenv("MACCMS_DB_URL") == null && System.getenv("MACCMS_DB_USERNAME") == null,
                "当前 shell 设置了 MACCMS_DB_* 环境变量，跳过缺配置启动检查");
        SpringApplication app = new SpringApplication(MaccmsBackendApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        assertThatThrownBy(() -> app.run().close()).hasStackTraceContaining("MACCMS_DB_");
    }

    @Test
    void rootAccountRejectedUnlessExplicitlyAllowed() {
        assertThatThrownBy(() -> new DatabaseAccountGuard("root", false).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DatabaseAccountGuard("", false).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
        new DatabaseAccountGuard("root", true).afterPropertiesSet();
        new DatabaseAccountGuard("maccms_ro", false).afterPropertiesSet();
    }
}
