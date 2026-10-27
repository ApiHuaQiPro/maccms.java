package com.maccms.backend.collect.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.admin.web.AdminParams;
import com.maccms.backend.content.application.PhpForm;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.legacyconfig.application.LegacyConfigProperties;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.writedrill.ConcurrentWrites;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 分类绑定（B17，25 文档第 4 节）在临时旧配置目录上的行为（不连库）：写出的 bind.php 与 PHP {@code mac_arr2file} 逐字节相同、
 * {@code data} 各字段的取法、清除绑定的两种方式、读不回或不存在的文件、并发绑定不丢更新。与 PHP 黄金的逐步对照在 {@code AdminCollectGoldenMySqlIT}。
 */
class CollectBindServiceTest {

    @TempDir
    Path root;

    private CollectBindService service() throws Exception {
        Files.createDirectories(root.resolve("extra"));
        LegacyConfigService legacy = new LegacyConfigService(new LegacyConfigProperties(root.toString()));
        TypeNames types = id -> id == 1 ? Optional.of("电影") : id == 6 ? Optional.of("A&B<型>\"") : Optional.empty();
        return new CollectBindService(legacy, types);
    }

    private static PhpForm form(String q) {
        return com.maccms.backend.admin.web.AdminContentActionsAccess.form(AdminParams.parse(q.getBytes(StandardCharsets.ISO_8859_1)));
    }

    private String bindFile() throws Exception {
        return Files.readString(root.resolve("extra/bind.php"), StandardCharsets.UTF_8);
    }

    @Test
    void bindWritesVarExportAndReturnsLegacyData() throws Exception {
        CollectBindService s = service();
        Files.writeString(root.resolve("extra/bind.php"), "<?php\nreturn array (\n  'a_1' => 1,\n  15 => 2,\n);", StandardCharsets.UTF_8);
        AdminResult r = s.bind(form("col=abc_3&val=6"));
        assertThat(r.ok()).isTrue();
        assertThat(r.data()).isEqualTo(Map.of("id", "abc_3", "st", 1L, "local_type_id", "6", "local_type_name", "A&B<型>\""));
        assertThat(bindFile()).isEqualTo("<?php\nreturn array (\n  'a_1' => 1,\n  15 => 2,\n  'abc_3' => 6,\n);");
        // 数字串的键与已有的整数键是同一个键（原位替换）；intval('2x') 为 2，但 '2x' 不是规范整数，分类名为 null
        AdminResult r2 = s.bind(form("col=15&val=2x"));
        assertThat(((Map<?, ?>) r2.data()).get("local_type_name")).isNull();
        assertThat(((Map<?, ?>) r2.data()).get("st")).isEqualTo(1L);
        assertThat(bindFile()).isEqualTo("<?php\nreturn array (\n  'a_1' => 1,\n  15 => 2,\n  'abc_3' => 6,\n);");
        // 非数字的值：intval 为 0，st 为 0，分类名为空串；引号与反斜杠按 var_export 转义
        AdminResult r3 = s.bind(form("col=" + java.net.URLEncoder.encode("a'b\\c", StandardCharsets.UTF_8) + "&val=abc"));
        assertThat(r3.data()).isEqualTo(Map.of("id", "a'b\\c", "st", 0L, "local_type_id", "abc", "local_type_name", ""));
        assertThat(bindFile()).endsWith("  'a\\'b\\\\c' => 0,\n);");
        // col 为空（含 '0'）时参数错误、不写
        String before = bindFile();
        assertThat(s.bind(form("val=1")).ok()).isFalse();
        assertThat(s.bind(form("col=0&val=1")).ok()).isFalse();
        assertThat(s.bind(form("col%5B%5D=x&val=1")).ok()).isFalse();
        assertThat(bindFile()).isEqualTo(before);
    }

    @Test
    void bindCreatesTheFileWhenMissingAndRefusesUnreadableContent() throws Exception {
        CollectBindService s = service();
        assertThat(s.bindings().entries()).isEmpty();
        assertThat(s.bind(form("col=f_1&val=1")).ok()).isTrue();
        assertThat(bindFile()).isEqualTo("<?php\nreturn array (\n  'f_1' => 1,\n);");
        // 旧版 config('bind') 会 include 任意 PHP；新版不执行 PHP，读不回的文件保存失败、不改动
        Files.writeString(root.resolve("extra/bind.php"), "<?php\nreturn array_merge([], []);", StandardCharsets.UTF_8);
        AdminResult r = s.bind(form("col=f_2&val=1"));
        assertThat(r.ok()).isFalse();
        assertThat(r.message().render(k -> k)).isEqualTo("save_err");
        assertThat(bindFile()).isEqualTo("<?php\nreturn array_merge([], []);");
    }

    @Test
    void clearKeepsOtherSitesOrClearsAll() throws Exception {
        CollectBindService s = service();
        Files.writeString(root.resolve("extra/bind.php"),
                "<?php\nreturn array (\n  'aaa_1' => 1,\n  'bbb_5' => 2,\n  15 => 2,\n  'x_y' => 1,\n);", StandardCharsets.UTF_8);
        assertThat(s.clear(form("cjflag=aaa"))).isTrue();
        assertThat(bindFile()).isEqualTo("<?php\nreturn array (\n  'bbb_5' => 2,\n  15 => 2,\n  'x_y' => 1,\n);");
        // strpos：整数键按文字比较
        assertThat(s.clear(form("cjflag=5"))).isTrue();
        assertThat(bindFile()).isEqualTo("<?php\nreturn array (\n  'x_y' => 1,\n);");
        // cjflag 为 empty()（缺失、空串、'0'）时全部清空
        assertThat(s.clear(form("cjflag=0"))).isTrue();
        assertThat(bindFile()).isEqualTo("<?php\nreturn array (\n);");
    }

    @Test
    void concurrentBindsKeepEveryKey() throws Exception {
        CollectBindService s = service();
        var outcome = ConcurrentWrites.run(24, i -> s.bind(form("col=k_" + i + "&val=" + (i + 1))).ok());
        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.count(true)).isEqualTo(24);
        for (int i = 0; i < 24; i++) {
            assertThat(CollectBindings.localTypeId(s.bindings(), "k_" + i)).isEqualTo(i + 1);
        }
    }
}
