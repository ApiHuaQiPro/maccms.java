package com.maccms.backend.install.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * jar 内的初始分类数据 {@code install/initdata-mac_type.json} 与仓库 {@code application/install/sql/initdata.sql} 逐值一致（31 文档第 6.1 节）。
 * 这里按 MySQL 字符串字面量规则（非 NO_BACKSLASH_ESCAPES）独立解码 initdata.sql 再比较；写库结果与 PHP 执行 initdata.sql 的结果相同由
 * {@code InstallWizardMySqlIT} 锁定。
 */
class InitDataTest {

    private static final Path INITDATA_SQL = Path.of("php", "application", "install", "sql", "initdata.sql");
    private static final String PREFIX = "INSERT INTO `mac_type` VALUES (";

    @Test
    void jsonMatchesRepositoryInitdataSql() throws Exception {
        List<List<String>> sql = parse(Files.readString(INITDATA_SQL, StandardCharsets.UTF_8));
        assertThat(sql).hasSize(18);
        assertThat(InitData.rows()).isEqualTo(sql);
        // 第一行的扩展字段里是字面的 \\u559c（SQL 里写成 \\\\u559c），不是解码后的汉字
        assertThat(InitData.rows().getFirst().get(16)).startsWith("{\"class\":\"\\u559c\\u5267,");
        assertThat(InitData.rows().getFirst().subList(0, 4)).containsExactly("1", "分类1", "fenlei1", "1");
    }

    @Test
    void parserRejectsOtherShapes() {
        assertThatThrownBy(() -> parse("DROP TABLE mac_type;")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> parse(PREFIX + "'1');")).isInstanceOf(IllegalStateException.class);
        assertThat(parse(PREFIX + String.join(",", java.util.Collections.nCopies(19, "'a'")) + ",'b\\'c''d\\\\e\\%');")
                .getFirst().get(19)).isEqualTo("b'c'd\\e\\%");
    }

    /** initdata.sql 的固定格式：注释与空行之外，每行一条 20 个单引号字符串的 INSERT。 */
    static List<List<String>> parse(String text) {
        List<List<String>> out = new ArrayList<>();
        for (String raw : text.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isBlank() || line.startsWith("--")) {
                continue;
            }
            if (!line.startsWith(PREFIX) || !line.endsWith(");")) {
                throw new IllegalStateException("不是固定格式");
            }
            String s = line.substring(PREFIX.length(), line.length() - 2);
            List<String> vals = new ArrayList<>();
            int i = 0;
            while (true) {
                while (i < s.length() && s.charAt(i) == ' ') {
                    i++;
                }
                if (i >= s.length() || s.charAt(i) != '\'') {
                    throw new IllegalStateException("值不是单引号字符串");
                }
                i++;
                StringBuilder v = new StringBuilder();
                while (true) {
                    if (i >= s.length()) {
                        throw new IllegalStateException("字符串没有结束");
                    }
                    char c = s.charAt(i);
                    if (c == '\\' && i + 1 < s.length()) {
                        char d = s.charAt(i + 1);
                        v.append(switch (d) {
                            case '0' -> "\0";
                            case 'b' -> "\b";
                            case 'n' -> "\n";
                            case 'r' -> "\r";
                            case 't' -> "\t";
                            case 'Z' -> "\u001A";
                            case '%' -> "\\%";
                            case '_' -> "\\_";
                            default -> String.valueOf(d);
                        });
                        i += 2;
                    } else if (c == '\'' && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                        v.append('\'');
                        i += 2;
                    } else if (c == '\'') {
                        i++;
                        break;
                    } else {
                        v.append(c);
                        i++;
                    }
                }
                vals.add(v.toString());
                while (i < s.length() && s.charAt(i) == ' ') {
                    i++;
                }
                if (i >= s.length()) {
                    break;
                }
                if (s.charAt(i) != ',') {
                    throw new IllegalStateException("值之间不是逗号");
                }
                i++;
            }
            if (vals.size() != 20) {
                throw new IllegalStateException("每行应有 20 个值");
            }
            out.add(vals);
        }
        return out;
    }
}
