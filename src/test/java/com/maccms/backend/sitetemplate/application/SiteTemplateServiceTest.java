package com.maccms.backend.sitetemplate.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.legacyconfig.application.LegacyConfigProperties;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import com.maccms.backend.site.application.SiteTemplateSource;
import com.maccms.backend.sitetemplate.infrastructure.BuiltinTemplateSources;
import com.maccms.backend.sitetemplate.infrastructure.JteClassPath;
import com.maccms.backend.sitetemplate.infrastructure.JteOnlineCompiler;
import gg.jte.output.StringOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 模板在线编辑的服务（路线图第 5.6 节，24 文档第 7 节）：用真实的 javac 编译整套内置模板与修改后的版本。
 * 演示 ①–⑥ 的服务层部分在这里断言；前台页面的现场演示记在 24 文档第 7.6 节。
 */
class SiteTemplateServiceTest {

    private static final String ROOT = "template/default/html";
    private static final String HELLO = ROOT + "/site/demo/hello.jte";

    private Path site;
    private Path work;

    @BeforeEach
    void setUp() throws IOException {
        site = Files.createTempDirectory("maccms-b15-tpl-site");
        work = Files.createTempDirectory("maccms-b15-tpl-work");
        Files.createDirectories(site.resolve("application/extra"));
        Files.copy(Path.of("php", "application", "extra", "maccms.php"), site.resolve("application/extra/maccms.php"));
        Files.createDirectories(site.resolve("template"));
    }

    @AfterEach
    void tearDown() throws IOException {
        for (Path p : List.of(site, work)) {
            try (Stream<Path> s = Files.walk(p)) {
                s.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
    }

    private SiteTemplateService service(boolean enabled) {
        LegacyConfigProperties props = new LegacyConfigProperties(site.resolve("application").toString());
        JteOnlineCompiler compiler = new JteOnlineCompiler(work, JteClassPath.resolve(work, SiteTemplateSource.class,
                gg.jte.Content.class, gg.jte.html.HtmlTemplateOutput.class), getClass().getClassLoader());
        SiteTemplateService s = new SiteTemplateService(enabled, new LegacySiteFiles(props), new LegacyConfigService(props), compiler,
                BuiltinTemplateSources.load(getClass().getClassLoader()));
        s.start();
        return s;
    }

    private static String render(SiteTemplateService s, String name, Map<String, Object> params) {
        StringOutput out = new StringOutput();
        s.current().render(name, params, out);
        return out.toString();
    }

    private String file(String rel) throws IOException {
        return Files.readString(site.resolve(rel), StandardCharsets.UTF_8);
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void builtinTemplatesPassTheFilterAndAreBundled() {
        Map<String, byte[]> builtin = BuiltinTemplateSources.load(getClass().getClassLoader());
        assertThat(builtin).containsKeys("site/pages/home.jte", "site/layout/page.jte", "site/player/player.jte").hasSizeGreaterThan(60);
        JteSecurityFilter f = new JteSecurityFilter(builtin);
        for (Map.Entry<String, byte[]> e : builtin.entrySet()) {
            assertThat(f.check(e.getKey(), new String(e.getValue(), StandardCharsets.UTF_8))).as(e.getKey()).isEmpty();
        }
    }

    @Test
    void disabledServiceKeepsBuiltinTemplates() {
        SiteTemplateService s = service(false);
        assertThat(s.current()).isNull();
        assertThat(s.notice()).isNull();
        assertThat(s.save(HELLO, b("x"), null).message()).isEqualTo(SiteTemplateService.DISABLED);
        assertThat(Files.exists(site.resolve(ROOT))).isFalse();
    }

    @Test
    void firstStartExportsBuiltinsAndSavesTakeEffectWithoutRestart() throws IOException {
        SiteTemplateService s = service(true);
        // 首次启用：从内置版本导出整套 jte，并以外部目录的编译结果为准
        assertThat(s.notice()).isNull();
        assertThat(s.generation()).isPositive();
        assertThat(file(ROOT + "/site/pages/home.jte")).isEqualTo(
                new String(BuiltinTemplateSources.load(getClass().getClassLoader()).get("site/pages/home.jte"), StandardCharsets.UTF_8));
        long g0 = s.generation();

        // ① 保存即重新编译并替换引擎，不重启就生效
        assertThat(s.save(HELLO, b("@param String name\n<p>你好 ${name}</p>\n"), "absent").ok()).isTrue();
        assertThat(s.generation()).isGreaterThan(g0);
        assertThat(render(s, "site/demo/hello.jte", Map.of("name", "<b>"))).isEqualTo("<p>你好 &lt;b&gt;</p>\n");
        String v1 = s.version(HELLO);
        assertThat(s.save(HELLO, b("@param String name\n<p>再见 ${name}</p>\n"), v1).ok()).isTrue();
        assertThat(render(s, "site/demo/hello.jte", Map.of("name", "x"))).isEqualTo("<p>再见 x</p>\n");

        // ② 语法错误：不写入、不切换，给出模板名与行号
        String v2 = s.version(HELLO);
        long g2 = s.generation();
        SiteTemplateService.Result bad = s.save(HELLO, b("@param String name\n<p>${name.nosuch()}</p>\n"), v2);
        assertThat(bad.ok()).isFalse();
        assertThat(bad.template()).isEqualTo("site/demo/hello.jte");
        assertThat(bad.line()).isEqualTo(2);
        assertThat(bad.message()).contains("模板编译失败", "site/demo/hello.jte 第 2 行").doesNotContain(work.toString());
        assertThat(s.generation()).isEqualTo(g2);
        assertThat(file(HELLO)).contains("再见");
        assertThat(render(s, "site/demo/hello.jte", Map.of("name", "x"))).isEqualTo("<p>再见 x</p>\n");
        SiteTemplateService.Result unclosed = s.save(HELLO, b("@param String name\n@if(name.isEmpty())\n<p>x</p>\n"), v2);
        assertThat(unclosed.ok()).isFalse();
        assertThat(unclosed.template()).isEqualTo("site/demo/hello.jte");

        // ③ 恢复上一版：回到"你好"，前台随之切换
        assertThat(s.hasPrevious(HELLO)).isTrue();
        assertThat(s.restore(HELLO, s.version(HELLO)).ok()).isTrue();
        assertThat(render(s, "site/demo/hello.jte", Map.of("name", "x"))).isEqualTo("<p>你好 x</p>\n");

        // ④ 禁止的内容：拒绝且不写入
        for (String evil : List.of("!{var x = 1;}", "${new java.io.File(\"/\")}", "@import java.io.File\n${File.separator}",
                "${String.class}", "${System.getenv()}", "$unsafe{name}", "${\\u006eew Object()}", "<script>alert(1)</script>",
                "${Runtime.getRuntime()}", "${java.nio.file.Files.readString(null)}", "@raw<b>x</b>@endraw")) {
            SiteTemplateService.Result r = s.save(HELLO, b("@param String name\n" + evil + "\n"), s.version(HELLO));
            assertThat(r.ok()).as(evil).isFalse();
            assertThat(r.message()).as(evil).startsWith("模板含有不允许的内容");
        }
        assertThat(file(HELLO)).contains("你好");

        // ⑥ 并发：基于旧版本保存的一方收到冲突提示
        String seen = s.version(HELLO);
        assertThat(s.save(HELLO, b("@param String name\n<p>甲 ${name}</p>\n"), seen).ok()).isTrue();
        SiteTemplateService.Result late = s.save(HELLO, b("@param String name\n<p>乙 ${name}</p>\n"), seen);
        assertThat(late.ok()).isFalse();
        assertThat(late.message()).isEqualTo(SiteTemplateService.CONFLICT);
        assertThat(render(s, "site/demo/hello.jte", Map.of("name", "x"))).isEqualTo("<p>甲 x</p>\n");
    }

    @Test
    void deletingAReferencedTemplateIsRefused() throws IOException {
        SiteTemplateService s = service(true);
        SiteTemplateService.Result r = s.delete(ROOT + "/site/components/pagination.jte");
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).startsWith("删除后整套模板无法编译");
        assertThat(Files.exists(site.resolve(ROOT + "/site/components/pagination.jte"))).isTrue();
        assertThat(s.save(HELLO, b("<p>x</p>\n"), "absent").ok()).isTrue();
        assertThat(s.delete(HELLO).ok()).isTrue();
        assertThat(Files.exists(site.resolve(HELLO))).isFalse();
        assertThat(s.current().hasTemplate("site/demo/hello.jte")).isFalse();
    }

    @Test
    void jteOutsideTheTemplateRootAndStaticFilesFollowTheirOwnRules() throws IOException {
        SiteTemplateService s = service(true);
        assertThat(s.save("template/other/x.jte", b("<p>x</p>"), null).message()).isEqualTo(
                String.format(SiteTemplateService.OUTSIDE_ROOT, ROOT));
        assertThat(s.save(ROOT + "/site/a b.jte", b("<p>x</p>"), null).message()).isEqualTo(SiteTemplateService.BAD_NAME);
        // 静态文件照旧版：命中旧过滤（message 为 null，由后台换成语言包的提示）；否则直接写
        SiteTemplateService.Result php = s.save(ROOT + "/ads/a.js", b("document.write(\"<?php\")"), null);
        assertThat(php.ok()).isFalse();
        assertThat(php.message()).isNull();
        assertThat(s.save(ROOT + "/ads/a.js", b("document.write('ad')"), null).ok()).isTrue();
        assertThat(file(ROOT + "/ads/a.js")).isEqualTo("document.write('ad')");
    }

    @Test
    void brokenExternalTemplatesFallBackToBuiltinsAtStartup() throws IOException {
        service(true);
        // ⑤ 手工把外部目录里的模板改坏后重启：用内置模板启动，后台有提示
        Files.writeString(site.resolve(ROOT + "/site/pages/home.jte"), "@param NoSuchType page\n${page.x()}\n", StandardCharsets.UTF_8);
        SiteTemplateService restarted = service(true);
        assertThat(restarted.current()).isNull();
        assertThat(restarted.notice()).contains("编译失败", "回退到内置模板", "site/pages/home.jte");
        // 手工放进去的禁止内容同样不生效
        Files.writeString(site.resolve(ROOT + "/site/pages/home.jte"), "${System.exit(0)}", StandardCharsets.UTF_8);
        SiteTemplateService again = service(true);
        assertThat(again.current()).isNull();
        assertThat(again.notice()).contains("site/pages/home.jte", "进程与系统");
    }
}
