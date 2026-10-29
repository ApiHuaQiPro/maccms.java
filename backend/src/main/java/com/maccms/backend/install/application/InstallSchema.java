package com.maccms.backend.install.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 安装向导核对的目标表结构（B22，31 文档第 5.2 节）：在一次性库里原样导入仓库 {@code install.sql} 后、由
 * {@code InstallDatabaseProbe#fingerprint} 读出的指纹，存为 jar 内的 {@code install/schema-fingerprint.txt}（每行
 * {@code 键<TAB>定义}，定义里的反斜杠、制表符、换行转义为 {@code \\}、{@code \t}、{@code \n}）。{@code InstallWizardMySqlIT} 每次在新导入的
 * 库上重新读一遍并要求与这份文件完全相同，install.sql 或 MySQL 版本带来的变化都会让测试失败。
 */
public final class InstallSchema {

    static final String RESOURCE = "install/schema-fingerprint.txt";
    /** install.sql 的表数。 */
    public static final int TABLES = 26;
    /** 原样导入 install.sql 后各表的行数（install.sql:376-378 插入 3 个默认会员组），其余表为 0。 */
    public static final Map<String, Long> SEED_ROWS = Map.of("mac_group", 3L);
    /** 提示里最多列出的差异条数。 */
    static final int SHOWN = 5;

    private InstallSchema() {
    }

    /** 首次使用时读入（缺少或格式不对时抛出）。 */
    private static final class Expected {
        static final Map<String, String> VALUE = load();
    }

    public static Map<String, String> expected() {
        return Expected.VALUE;
    }

    /** 与预期不同的对象（排序后的键），为空表示一致。 */
    public static List<String> differences(Map<String, String> actual) {
        List<String> out = new ArrayList<>();
        Map<String, String> expected = expected();
        TreeSet<String> keys = new TreeSet<>(expected.keySet());
        keys.addAll(actual.keySet());
        for (String k : keys) {
            if (!Objects.equals(expected.get(k), actual.get(k))) {
                out.add(expected.containsKey(k) ? (actual.containsKey(k) ? "不同 " : "缺少 ") + k : "多出 " + k);
            }
        }
        return out;
    }

    /** 写出指纹文件的内容（测试用它生成与比对 {@link #RESOURCE}）。 */
    public static String format(Map<String, String> fingerprint) {
        StringBuilder sb = new StringBuilder();
        new TreeSet<>(fingerprint.keySet()).forEach(k -> sb.append(k).append('\t').append(escape(fingerprint.get(k))).append('\n'));
        return sb.toString();
    }

    static Map<String, String> parse(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab <= 0 || out.put(line.substring(0, tab), unescape(line.substring(tab + 1))) != null) {
                throw new IllegalStateException(RESOURCE + " 格式不对");
            }
        }
        return Map.copyOf(out);
    }

    private static Map<String, String> load() {
        try (InputStream in = InstallSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("缺少 " + RESOURCE);
            }
            Map<String, String> m = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            long tables = m.keySet().stream().filter(k -> k.startsWith("table ")).count();
            if (tables != TABLES) {
                throw new IllegalStateException(RESOURCE + " 应有 " + TABLES + " 张表");
            }
            return m;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String escape(String v) {
        return v.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String v) {
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char d = v.charAt(++i);
                sb.append(switch (d) {
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> d;
                });
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
