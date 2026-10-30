package com.maccms.backend.admin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 后台 PHP 黄金样本（{@code admin/php80-golden.json}、{@code php73-golden.json}，由 src/test/php/admin_golden.php 生成）的读取。
 */
public final class AdminGolden {

    public static final String PHP80 = "admin/php80-golden.json";
    public static final String PHP73 = "admin/php73-golden.json";

    private final JsonNode root;

    private AdminGolden(JsonNode root) {
        this.root = root;
    }

    public static AdminGolden load(String resource) {
        try (InputStream in = AdminGolden.class.getClassLoader().getResourceAsStream(resource)) {
            return new AdminGolden(JsonMapper.builder().build().readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String php() {
        return root.get("php").asString();
    }

    public List<Scenario> scenarios() {
        List<Scenario> out = new ArrayList<>();
        for (JsonNode s : root.get("scenarios")) {
            out.add(new Scenario(s));
        }
        return out;
    }

    public Scenario scenario(String label) {
        return scenarios().stream().filter(s -> s.label().equals(label)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(label));
    }

    /** 一个场景。 */
    public record Scenario(JsonNode node) {

        public String label() {
            return node.get("label").asString();
        }

        /** 场景的配置覆盖（{@code app.*}）。 */
        public Map<String, String> config() {
            Map<String, String> out = new LinkedHashMap<>();
            JsonNode app = node.get("config").get("app");
            if (app != null) {
                app.properties().forEach(e -> out.put(e.getKey(), e.getValue().asString()));
            }
            return out;
        }

        /** 快捷菜单：null 表示用仓库的 extra/quickmenu.php。 */
        public List<String> quickmenu() {
            JsonNode q = node.get("quickmenu");
            if (q == null || q.isNull()) {
                return null;
            }
            List<String> out = new ArrayList<>();
            q.forEach(v -> out.add(v.asString()));
            return out;
        }

        public List<Row> rows0() {
            return Row.list(node.get("rows0"));
        }

        public List<Step> steps() {
            List<Step> out = new ArrayList<>();
            for (JsonNode st : node.get("steps")) {
                out.add(new Step(st));
            }
            return out;
        }

        public Step step(String label) {
            return steps().stream().filter(s -> s.label().equals(label)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(label() + "/" + label));
        }
    }

    /** 一个步骤：请求，或直接改库（sql），或写入验证码（captcha）。 */
    public record Step(JsonNode node) {

        public String label() {
            return node.get("label").asString();
        }

        public boolean isRequest() {
            return node.has("request");
        }

        public String sql() {
            return node.has("sql") ? node.get("sql").asString() : null;
        }

        public String captcha() {
            return node.has("captcha") ? node.get("captcha").asString() : null;
        }

        public String method() {
            return node.get("request").get("method").asString();
        }

        public String path() {
            return node.get("request").get("path").asString();
        }

        public String query() {
            return node.get("request").get("query").asString();
        }

        public String post() {
            return node.get("request").get("post").asString();
        }

        public boolean ajax() {
            return node.get("request").get("ajax").asBoolean();
        }

        public Map<String, String> headers() {
            Map<String, String> out = new LinkedHashMap<>();
            node.get("request").get("headers").properties().forEach(e -> out.put(e.getKey(), e.getValue().asString()));
            return out;
        }

        public int status() {
            return node.get("status").asInt();
        }

        public String location() {
            JsonNode l = node.get("location");
            return l == null || l.isNull() ? null : l.asString();
        }

        public String contentType() {
            JsonNode c = node.get("contentType");
            return c == null || c.isNull() ? null : c.asString();
        }

        public List<String> setCookies() {
            List<String> out = new ArrayList<>();
            node.get("setCookies").forEach(c -> out.add(c.asString()));
            return out;
        }

        /** 正文（UTF-8 文本）；没有记录时为 null。 */
        public String body() {
            JsonNode b = node.get("body");
            return b == null || b.isNull() ? null : new String(Base64.getDecoder().decode(b.asString()), StandardCharsets.UTF_8);
        }

        public long started() {
            return node.get("started").asLong();
        }

        public long after() {
            return node.get("after").asLong();
        }

        /** 请求后 mac_admin 的全部行；与上一次记录相同时为 null。 */
        public List<Row> rows() {
            JsonNode r = node.get("rows");
            return r == null || r.isNull() ? null : Row.list(r);
        }
    }

    /** mac_admin 的一行（文本列已从十六进制解出，按 UTF-8 解释；二进制密码另有 pwd）。 */
    public record Row(long id, String name, String pwd, String random, int status, String auth, long loginTime,
                      long loginIp, long loginNum, long lastLoginTime, long lastLoginIp) {

        static List<Row> list(JsonNode rows) {
            List<Row> out = new ArrayList<>();
            for (JsonNode r : rows) {
                out.add(new Row(r.get("admin_id").asLong(), text(r, "admin_name"), text(r, "admin_pwd"),
                        text(r, "admin_random"), r.get("admin_status").asInt(), text(r, "admin_auth"),
                        r.get("admin_login_time").asLong(), r.get("admin_login_ip").asLong(),
                        r.get("admin_login_num").asLong(), r.get("admin_last_login_time").asLong(),
                        r.get("admin_last_login_ip").asLong()));
            }
            return out;
        }

        private static String text(JsonNode r, String col) {
            return new String(HexFormat.of().parseHex(r.get(col).asString()), StandardCharsets.UTF_8);
        }
    }
}
