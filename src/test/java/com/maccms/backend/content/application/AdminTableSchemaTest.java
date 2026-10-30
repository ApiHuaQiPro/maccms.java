package com.maccms.backend.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.content.application.AdminTable.Column;
import com.maccms.backend.content.application.AdminTable.Kind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 后台内容管理（B13，22 文档第 3 节）与后台会员、会员组（B14，23 文档第 3 节）的列清单：{@link AdminTable} 与 install.sql 逐列一致（列名、顺序、类型、长度、有无符号）；写 Mapper XML
 * 里每张表的新增、整行覆盖、按主键列表修改都列出了全部列（"表单字段多，遗漏字段导致数据被清空"的护栏，路线图 B13 行）。
 */
class AdminTableSchemaTest {

    private static final Path INSTALL_SQL = Path.of("php", "application", "install", "sql", "install.sql");
    private static final Path WRITE_MAPPERS = Path.of("src", "main", "resources", "mapper-write");
    private static final Pattern COLUMN = Pattern.compile(
            "^\\s*`(\\w+)`\\s+(\\w+)\\s*(?:\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\))?(\\s+unsigned)?", Pattern.CASE_INSENSITIVE);

    @Test
    void columnCatalogMatchesInstallSql() throws IOException {
        String sql = Files.readString(INSTALL_SQL, StandardCharsets.UTF_8);
        for (AdminTable t : AdminTable.values()) {
            Matcher m = Pattern.compile("(?s)CREATE TABLE `" + t.table() + "` \\((.*?)\\n\\)").matcher(sql);
            assertThat(m.find()).as(t.table()).isTrue();
            List<Column> expected = new ArrayList<>();
            for (String line : m.group(1).split("\n")) {
                Matcher c = COLUMN.matcher(line);
                if (c.find()) {
                    expected.add(column(c));
                }
            }
            assertThat(List.copyOf(t.columns().values())).as(t.table()).isEqualTo(expected);
            assertThat(t.pk()).isEqualTo(expected.get(0).name());
        }
    }

    @Test
    void writeMappersListEveryColumnOfEveryTable() throws IOException {
        String content = Files.readString(WRITE_MAPPERS.resolve("content/ContentAdminWriteMapper.xml"), StandardCharsets.UTF_8);
        String comment = Files.readString(WRITE_MAPPERS.resolve("comment/CommentAdminWriteMapper.xml"), StandardCharsets.UTF_8);
        // B14（23 文档第 3 节）：后台会员与会员组的整行覆盖
        String member = Files.readString(WRITE_MAPPERS.resolve("member/MemberAdminWriteMapper.xml"), StandardCharsets.UTF_8);
        // B17（25 文档第 6 节）：采集源与自定义规则节点的新增与整行覆盖（没有按编号列表修改的动作）
        String collect = Files.readString(WRITE_MAPPERS.resolve("collect/CollectAdminWriteMapper.xml"), StandardCharsets.UTF_8);
        Map<AdminTable, String> names = Map.ofEntries(Map.entry(AdminTable.VOD, "Vod"), Map.entry(AdminTable.ART, "Art"),
                Map.entry(AdminTable.TOPIC, "Topic"), Map.entry(AdminTable.ACTOR, "Actor"), Map.entry(AdminTable.ROLE, "Role"),
                Map.entry(AdminTable.WEBSITE, "Website"), Map.entry(AdminTable.LINK, "Link"), Map.entry(AdminTable.TYPE, "Type"),
                Map.entry(AdminTable.ANNEX, "Annex"), Map.entry(AdminTable.COMMENT, "Comment"),
                Map.entry(AdminTable.GBOOK, "Gbook"), Map.entry(AdminTable.USER, "User"), Map.entry(AdminTable.GROUP, "Group"),
                Map.entry(AdminTable.COLLECT, "Collect"), Map.entry(AdminTable.CJ_NODE, "CjNode"));
        assertThat(names).hasSize(AdminTable.values().length);
        for (Map.Entry<AdminTable, String> e : names.entrySet()) {
            AdminTable t = e.getKey();
            boolean b17 = t == AdminTable.COLLECT || t == AdminTable.CJ_NODE;
            String xml = t == AdminTable.COMMENT || t == AdminTable.GBOOK ? comment
                    : t == AdminTable.USER || t == AdminTable.GROUP ? member : b17 ? collect : content;
            List<String> columns = List.copyOf(t.columns().keySet());
            List<String> ids = b17 ? List.of("insert" + e.getValue(), "update" + e.getValue())
                    : List.of("insert" + e.getValue(), "update" + e.getValue(), "update" + e.getValue() + "ByIds");
            for (String id : ids) {
                Matcher m = Pattern.compile("(?s)<(insert|update) id=\"" + id + "\"[^>]*>(.*?)</\\1>").matcher(xml);
                assertThat(m.find()).as(id).isTrue();
                String body = m.group(2);
                assertThat(body).as(id).contains(" " + t.table() + " ");
                List<String> keys = new ArrayList<>();
                Matcher k = Pattern.compile("c\\.containsKey\\('(\\w+)'\\)").matcher(body);
                while (k.find()) {
                    keys.add(k.group(1));
                }
                // 新增的列名与取值各一遍
                assertThat(keys).as(id).isEqualTo(id.startsWith("insert") ? concat(columns, columns) : columns);
            }
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static Column column(Matcher c) {
        String name = c.group(1);
        String type = c.group(2).toLowerCase(Locale.ROOT);
        boolean unsigned = c.group(5) != null;
        return switch (type) {
            case "tinyint" -> integer(name, unsigned, 8);
            case "smallint" -> integer(name, unsigned, 16);
            case "mediumint" -> integer(name, unsigned, 24);
            case "int" -> integer(name, unsigned, 32);
            case "char", "varchar" -> new Column(name, Kind.CHAR, Integer.parseInt(c.group(3)), 0, 0);
            case "text" -> new Column(name, Kind.TEXT, 65535, 0, 0);
            case "mediumtext" -> new Column(name, Kind.TEXT, 16777215, 0, 0);
            case "decimal" -> new Column(name, Kind.DECIMAL, Integer.parseInt(c.group(3)), Integer.parseInt(c.group(4)),
                    unsigned ? 1 : 0);
            default -> throw new IllegalArgumentException(name + " " + type);
        };
    }

    private static Column integer(String name, boolean unsigned, int bits) {
        return unsigned ? new Column(name, Kind.INT, 0, 0L, (1L << bits) - 1)
                : new Column(name, Kind.INT, 0, -(1L << (bits - 1)), (1L << (bits - 1)) - 1);
    }
}
