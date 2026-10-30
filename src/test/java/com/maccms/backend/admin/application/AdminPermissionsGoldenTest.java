package com.maccms.backend.admin.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.common.LegacyCheckout;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 菜单与权限码逐项照搬 auth.php（路线图 B12 验收："auth.php 每个权限码都有对应路由或明确标注由后续批次实现"）。
 * 对照文件 {@code admin/auth-php.json} 由 {@code src/test/php/admin_auth_export.php} 从仓库导出；auth.php 变了要重新导出
 * （SHA-256 锁定）。
 */
class AdminPermissionsGoldenTest {

    private static JsonNode golden() throws Exception {
        try (InputStream in = AdminPermissionsGoldenTest.class.getResourceAsStream("/admin/auth-php.json")) {
            return JsonMapper.builder().build().readTree(in);
        }
    }

    @Test
    void goldenIsFromTheCurrentAuthPhp() throws Exception {
        String sha = golden().get("sha256").asString();
        byte[] source = LegacyCheckout.read(Path.of("php", "application", "admin", "common", "auth.php"), sha);
        assertThat(LegacyCheckout.sha256(source))
                .as("application/admin/common/auth.php 变了：用 admin_auth_export.php 重新导出" + LegacyCheckout.HINT)
                .isEqualTo(sha);
    }

    @Test
    void groupsAndEntriesMatchAuthPhpExactly() throws Exception {
        List<String> expected = new ArrayList<>();
        for (JsonNode g : golden().get("groups")) {
            expected.add("G " + g.get("key").asInt() + " " + g.get("name").asString() + " " + g.get("icon").asString());
            for (JsonNode e : g.get("entries")) {
                JsonNode param = e.get("param");
                expected.add("  " + e.get("key").asInt() + " " + e.get("show").asInt() + " " + e.get("name").asString()
                        + " " + e.get("controller").asString() + "/" + e.get("action").asString() + " ["
                        + (param.isNull() ? "" : param.asString()) + "]");
            }
        }
        List<String> actual = new ArrayList<>();
        for (AdminPermissions.Group g : AdminPermissions.groups()) {
            actual.add("G " + g.key() + " @" + g.name().langKey() + " " + g.icon());
            for (AdminPermissions.Entry e : g.entries()) {
                String name = e.name().langKey() != null ? "@" + e.name().langKey() : e.name().text();
                actual.add("  " + e.key() + " " + (e.show() ? 1 : 0) + " " + name + " " + e.code() + " [" + e.param() + "]");
            }
        }
        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(AdminPermissions.codes()).hasSize(179).contains("index/welcome", "make/index?ac2=wap", "admin/info");
    }

    @Test
    void knownActionsAreExactlyTheLegacyControllersPublicMethods() throws Exception {
        Map<String, Set<String>> legacy = new TreeMap<>();
        golden().get("controllers").properties().forEach(e -> {
            Set<String> actions = new LinkedHashSet<>();
            e.getValue().forEach(a -> actions.add(a.asString()));
            legacy.put(e.getKey(), actions);
        });
        assertThat(legacy.keySet()).containsExactlyInAnyOrderElementsOf(AdminPermissions.CONTROLLERS);
        Map<String, Set<String>> java = new TreeMap<>();
        for (String c : AdminPermissions.CONTROLLERS) {
            java.put(c, new LinkedHashSet<>());
        }
        for (String ca : AdminPermissions.knownActions()) {
            String[] p = ca.split("/", 2);
            java.get(p[0]).add(p[1]);
        }
        for (Map.Entry<String, Set<String>> e : legacy.entrySet()) {
            // 已知动作正好是旧控制器的公开方法（auth.php 里没有方法的死链不算）
            Set<String> expected = new LinkedHashSet<>(e.getValue());
            expected.removeAll(Set.of("check_auth", "_cache_clear"));
            assertThat(java.get(e.getKey())).as(e.getKey()).containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    @Test
    void everyAuthCodeHasABatchAndB12OwnsIndexAndAdmin() {
        Map<String, String> byBatch = new LinkedHashMap<>();
        for (String code : AdminPermissions.codes()) {
            String[] p = code.split("/", 2);
            String action = p[1].contains("?") ? p[1].substring(0, p[1].indexOf('?')) : p[1];
            String batch = AdminPermissions.batchOf(p[0], action.toLowerCase());
            if (AdminPermissions.WITHOUT_LEGACY_METHOD.contains(p[0] + "/" + action)) {
                // 菜单或权限树里的死链：旧控制器没有这个方法（safety 是空类），新旧都是"页面不存在"
                assertThat(batch).as(code).isNull();
                continue;
            }
            assertThat(batch).as(code).isNotNull().matches("B1[2-9]|B2[0-9]");
            byBatch.put(code, batch);
        }
        assertThat(byBatch).containsEntry("index/welcome", "B12").containsEntry("index/quickmenu", "B12")
                .containsEntry("index/iframe", "B12").containsEntry("index/clear", "B12")
                .containsEntry("index/unlocked", "B12").containsEntry("index/select", "B13")
                .containsEntry("upload/upload", "B13").containsEntry("admin/index", "B12")
                .containsEntry("admin/info", "B12").containsEntry("admin/del", "B12").containsEntry("admin/field", "B12")
                .containsEntry("system/config", "B15").containsEntry("user/data", "B14").containsEntry("vod/data", "B13")
                .containsEntry("make/index?ac2=wap", "B16").containsEntry("collect/api", "B18")
                .containsEntry("cj/publish", "B20").containsEntry("urlsend/push", "B28")
                .containsEntry("addon/install", "B29");
        assertThat(AdminPermissions.batchOf("index", "check_back_link")).isEqualTo("B28");
        assertThat(AdminPermissions.batchOf("update", "step2")).isNull();
        assertThat(AdminPermissions.batchOf("index", "nosuch")).isNull();
    }
}
