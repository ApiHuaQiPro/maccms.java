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
        String vodXml = sqlOnly(MAPPERS.resolve("content/VodMapper.xml"));
        // B05：legacyConditions 片段只把 vod_rel_vod、vod_plot 用作 WHERE 条件（旧模板标签 rel、plot），单独检查后从其余 SQL 中去掉
        Matcher fragment = Pattern.compile("(?s)<sql id=\"legacyConditions\">(.*?)</sql>").matcher(vodXml);
        assertThat(fragment.find()).as("VodMapper 的 legacyConditions 片段").isTrue();
        Matcher inFragment = col.matcher(fragment.group(1));
        while (inFragment.find()) {
            String after = fragment.group(1).substring(inFragment.start(), Math.min(fragment.group(1).length(),
                    inFragment.end() + 6));
            assertThat(after).as("legacyConditions 只能以条件使用 " + inFragment.group())
                    .matches("(?s)(vod_rel_vod LIKE|vod_plot = ).*");
        }
        String vod = vodXml.substring(0, fragment.start()) + vodXml.substring(fragment.end());
        assertThat(vod).as("其余语句只经 include 引用该片段").contains("<include refid=\"legacyConditions\"/>");
        // B05：播放/下载入口的两条语句（selectPlayback、countPasswordMatch）是唯一读取播放/下载分组、积分与试看列的地方；
        // 密码列只能出现在布尔表达式中（是否非空、凭据 SHA2 比较、BINARY 逐字节相等），单独检查后从其余 SQL 中去掉
        for (String id : List.of("selectPlayback", "countPasswordMatch")) {
            Matcher stmt = Pattern.compile("(?s)<select id=\"" + id + "\".*?</select>").matcher(vod);
            assertThat(stmt.find()).as("VodMapper 的 " + id).isTrue();
            String body = stmt.group();
            Matcher pwd = Pattern.compile("\\bvod_pwd\\w*\\b").matcher(body);
            while (pwd.find()) {
                String around = body.substring(Math.max(0, pwd.start() - 32), Math.min(body.length(), pwd.end() + 32));
                assertThat(around).as(id + " 只能以布尔表达式使用 " + pwd.group())
                        .containsAnyOf("CHAR_LENGTH(" + pwd.group() + ") &gt; 0",
                                "SHA2(CONCAT(#{proofSalt}, " + pwd.group() + "), 256) = #{proofHash}",
                                "BINARY " + pwd.group() + " = BINARY #{candidate}");
            }
            assertThat(body).as(id + " 不读取其他内部列")
                    .doesNotContainPattern("\\b(group_id|vod_lock|vod_tpl\\w*|vod_reurl|vod_jumpurl|vod_author|vod_rel_\\w+|"
                            + "vod_plot\\w*|vod_pic_screenshot|vod_pwd_url|vod_pwd_play_url|vod_pwd_down_url)\\b");
            vod = vod.substring(0, stmt.start()) + vod.substring(stmt.end());
        }
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

    /**
     * 契约 1.2.0（MACC-27）的 Mapper：访问密码、积分只以 SQL 计算的布尔结果出现；模板、锁定、生成时间、关联、会员组等内部列不查询。
     * website_jumpurl 是网址本身（导航目标）属于公开字段；topic_rel_vod/topic_rel_art 只在专题详情中读出用于查询关联内容。
     */
    @Test
    void libraryMappersNeverSelectInternalColumns() throws IOException {
        Pattern internal = Pattern.compile("\\b((art|topic|actor|role|website)_(tpl|lock|time_make|time_hits)|"
                + "(art|actor|role)_jumpurl|art_rel_\\w+|art_pwd_url|topic_extend|website_time_referer|group_id|"
                + "type_id_1|vod_play_\\w+|vod_down_\\w+|vod_points\\w*|vod_trysee|vod_pwd_\\w+)\\b");
        for (String file : List.of("ArtMapper.xml", "TopicMapper.xml", "ActorMapper.xml", "RoleMapper.xml",
                "WebsiteMapper.xml", "LinkMapper.xml", "VodPlotMapper.xml")) {
            String sql = sqlOnly(MAPPERS.resolve("content/" + file));
            if (file.equals("ArtMapper.xml")) {
                // B05（15 文档第 7.2 节）：type_id_1 只允许出现在 SSR 搜索页的 tid 条件中，作 WHERE 比较，不是查询列
                String tid = "type_id = #{criteria.tid} OR type_id_1 = #{criteria.tid}";
                assertThat(sql.split(Pattern.quote(tid), -1)).as("ArtMapper 的 tid 条件只有一处").hasSize(2);
                sql = sql.replace(tid, "");
            }
            Matcher m = internal.matcher(sql);
            assertThat(m.find()).as(file + " 不应查询内部列" + (m.hitEnd() ? "" : "：" + m.group())).isFalse();
        }
        String art = sqlOnly(MAPPERS.resolve("content/ArtMapper.xml"));
        Matcher secret = Pattern.compile("\\b(art_pwd|art_points|art_points_detail)\\b").matcher(art);
        while (secret.find()) {
            String around = art.substring(Math.max(0, secret.start() - 26), Math.min(art.length(), secret.end() + 16));
            assertThat(around).as("ArtMapper 只能以布尔结果使用 " + secret.group())
                    .containsAnyOf("CHAR_LENGTH(art_pwd)", "&gt; 0", "SHA2(CONCAT(#{proofSalt}, art_pwd)",
                            "BINARY art_pwd = BINARY");
        }
        String plot = sqlOnly(MAPPERS.resolve("content/VodPlotMapper.xml"));
        Matcher pwd = Pattern.compile("\\bvod_pwd\\b").matcher(plot);
        while (pwd.find()) {
            assertThat(plot.substring(Math.max(0, pwd.start() - 12), pwd.end())).contains("CHAR_LENGTH(vod_pwd");
        }
        // vod_plot* 只出现在 VodPlotMapper.xml（mappersNeverSelectSensitiveVodColumns 仍禁止它出现在 VodMapper.xml）
        assertThat(plot).contains("vod_plot_name", "vod_plot_detail");
    }

    /**
     * B10（19 文档第 6 节）：财务读取与内容查价只读需要的列——不读会员密码与登录随机串、播放/下载地址、访问密码与内容正文；卡密只作
     * {@code WHERE card_pwd = #{cardPwd}} 的比较，从不作查询列（卡密列表也只给卡号）。
     */
    @Test
    void financeAndLookupMappersNeverSelectSecrets() throws IOException {
        Pattern secret = Pattern.compile("\\b(user_pwd|user_random|user_openid_\\w+|user_question|user_answer|"
                + "vod_play_\\w+|vod_down_\\w+|vod_pwd\\w*|art_pwd\\w*|vod_content|art_content|vod_plot\\w*)\\b");
        for (String file : List.of("member/MemberFinanceMapper.xml", "content/ContentLookupMapper.xml")) {
            String sql = sqlOnly(MAPPERS.resolve(file));
            assertThat(sql).as(file).doesNotContain("SELECT *", ".*");
            Matcher m = secret.matcher(sql);
            assertThat(m.find()).as(file + " 不应读取" + (m.hitEnd() ? "" : "：" + m.group())).isFalse();
        }
        String finance = sqlOnly(MAPPERS.resolve("member/MemberFinanceMapper.xml"));
        Matcher pwd = Pattern.compile("\\bcard_pwd\\b").matcher(finance);
        int uses = 0;
        while (pwd.find()) {
            uses++;
            assertThat(finance.substring(pwd.start())).as("卡密只作比较条件").startsWith("card_pwd = #{cardPwd}");
            String before = finance.substring(0, pwd.start());
            assertThat(before.lastIndexOf("WHERE")).as("卡密只出现在 WHERE 之后").isGreaterThan(before.lastIndexOf("SELECT"));
        }
        assertThat(uses).isEqualTo(1);
    }

    /**
     * B06 旧协议整行读取（LegacyRowMapper.xml，16 文档第 3.5、3.6 节）：访问密码列只能以 {@code CHAR_LENGTH(...) > 0} 的布尔结果出现
     * （不读出密码值）；其余整行列是旧协议输出的一部分，按 install.sql 列出。
     */
    @Test
    void legacyRowMapperNeverSelectsPasswords() throws IOException {
        String sql = sqlOnly(MAPPERS.resolve("content/LegacyRowMapper.xml"));
        Matcher pwd = Pattern.compile("\\b(vod_pwd\\w*|art_pwd\\w*)\\b").matcher(sql);
        int flags = 0;
        while (pwd.find()) {
            String around = sql.substring(Math.max(0, pwd.start() - 16), Math.min(sql.length(), pwd.end() + 24));
            assertThat(around).as("LegacyRowMapper 只能以布尔结果使用 " + pwd.group())
                    .matches("(?s).*CHAR_LENGTH\\(" + pwd.group() + "\\) &gt; 0\\) AS _pwd.*");
            assertThat(pwd.group()).isIn("vod_pwd", "vod_pwd_play", "vod_pwd_down", "art_pwd");
            flags++;
        }
        assertThat(flags).isEqualTo(4);
        assertThat(sql).doesNotContain("SELECT *", "vod_pwd_url", "art_pwd_url");
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
