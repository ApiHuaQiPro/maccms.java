package com.maccms.backend.sitetemplate.application;

import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.LegacySiteFiles;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import com.maccms.backend.site.application.SiteTemplateSource;
import com.maccms.backend.sitetemplate.infrastructure.JteOnlineCompiler;
import gg.jte.TemplateEngine;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 前台模板在线编辑（D4；路线图第 5.6 节，24 文档第 7 节）。外部模板目录是 {@code template/<site.template_dir>/<site.html_dir>/}
 * （与旧版目录约定一致），其中的 {@code *.jte} 是一整套前台模板（模板名即相对路径，如 {@code site/pages/home.jte}）。
 * <ul>
 * <li>启动：开启了在线编辑（部署参数）且有 javac 时，外部目录里没有任何 jte 就先从内置版本导出一份；然后整体编译，失败（或含安全过滤
 * 拒绝的内容）则记录错误、后台首页提示、前台用内置模板。</li>
 * <li>保存：路径与扩展名检查 → 安全过滤 → 乐观并发检查 → "外部目录当前内容 + 本次修改"整体编译 → 失败不写入、不切换，返回模板名与行号；
 * 成功则把原文件存一份到 {@code application/data/backup/template/}（"恢复上一版"用），原子写入，再整体替换前台用的引擎。</li>
 * <li>删除 jte：先确认没有它整套仍能编译，否则拒绝（多半是被其他模板引用）。js/xml/html 等静态文件照旧版直接读写（含旧版的安全过滤）。</li>
 * </ul>
 * 保存、恢复、删除与启动编译串行执行。
 */
public class SiteTemplateService implements SiteTemplateSource {

    private static final Logger log = LoggerFactory.getLogger(SiteTemplateService.class);

    public static final String DISABLED = "在线编辑前台模板未开启（部署参数 maccms.site-template.enabled），jte 模板不能在后台保存";
    public static final String NO_COMPILER = "当前运行环境不支持在线编译";
    public static final String CONFLICT = "文件已被他人修改，请刷新页面后重新编辑";
    public static final String NOT_UTF8 = "模板内容必须是 UTF-8 编码";
    public static final String NO_PREVIOUS = "没有可恢复的上一版";
    public static final String OUTSIDE_ROOT = "jte 模板只能放在当前模板目录 %s/ 下";
    public static final String BAD_NAME = "模板文件名只能由字母、数字、下划线、点与横线组成，扩展名为 .jte";
    public static final String BACKUP_DIR = "application/data/backup/template/";

    /** 旧 {@code Template::info} 的安全过滤（静态文件 js/xml/html 仍按它检查，{@code mac_reg_replace} 的 /is）。 */
    public static final String LEGACY_FILTER = "<\\?|php|eval|server|assert|get|post|request|cookie|session|input|env|config|call|global"
            + "|dump|print|phpinfo|fputs|fopen|global|chr|strtr|pack|system|gzuncompress|shell|base64|file|proc|preg|call|ini|{:|{$|{~"
            + "|{-|{+|{/|{assign|{define|=\"\\s*:|='\\s*:";
    private static final Pattern LEGACY = Pattern.compile(LEGACY_FILTER.replace("/", "\\/").replace("$", "\\$").replace("+", "\\+")
            .replace("-", "\\-").replace("{", "\\{"), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DIR_SEGMENT = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_BYTES = 1024 * 1024;

    /** 保存、删除、恢复的结果：{@code ok} 为 false 时 {@code message} 是提示；编译失败时带模板名与行号。 */
    public record Result(boolean ok, String message, String template, int line) {
        static Result success() {
            return new Result(true, null, null, 0);
        }

        static Result refused(String message) {
            return new Result(false, message, null, 0);
        }
    }

    private record Active(JteOnlineCompiler.Compiled compiled, String root) {
    }

    private final boolean enabled;
    private final LegacySiteFiles files;
    private final LegacyConfigService config;
    private final JteOnlineCompiler compiler;
    private final Map<String, byte[]> builtin;
    private final JteSecurityFilter filter;
    private final Object lock = new Object();
    private volatile Active active;
    private JteOnlineCompiler.Compiled retired;
    private volatile String notice;

    /**
     * @param compiler 运行时编译器；没有 javac 时为 null
     * @param builtin  内置模板源码（模板名 → 内容）
     */
    public SiteTemplateService(boolean enabled, LegacySiteFiles files, LegacyConfigService config, JteOnlineCompiler compiler,
                               Map<String, byte[]> builtin) {
        this.enabled = enabled;
        this.files = files;
        this.config = config;
        this.compiler = compiler;
        this.builtin = Map.copyOf(builtin);
        this.filter = new JteSecurityFilter(this.builtin);
    }

    @Override
    public TemplateEngine current() {
        Active a = active;
        return a == null ? null : a.compiled().engine();
    }

    public boolean enabled() {
        return enabled;
    }

    /** 不能在线保存 jte 的原因；能保存时为 null。 */
    public String unavailable() {
        if (!enabled) {
            return DISABLED;
        }
        if (compiler == null) {
            return NO_COMPILER;
        }
        if (!files.isConfigured()) {
            return "没有配置旧站目录（maccms.legacy-config.dir）";
        }
        return null;
    }

    /** 后台首页的提示（启动时外部模板编译失败、回退到内置模板）；没有时为 null。 */
    public String notice() {
        return notice;
    }

    /** 当前前台用的模板编译版本号（0 = 内置模板）。 */
    public long generation() {
        Active a = active;
        return a == null ? 0 : a.compiled().generation();
    }

    /** 外部模板目录（站点根目录下的相对路径）。 */
    public String root() {
        String tpl = "default";
        String html = "html";
        try {
            PhpValue site = config.read(LegacyConfigFile.MACCMS).get("site");
            if (site instanceof PhpArray a) {
                String t = PhpValues.text(a.get("template_dir"));
                String h = PhpValues.text(a.get("html_dir"));
                tpl = DIR_SEGMENT.matcher(t).matches() ? t : tpl;
                html = DIR_SEGMENT.matcher(h).matches() ? h : html;
            }
        } catch (RuntimeException e) {
            // 读不到配置时用旧版默认目录
        }
        return "template/" + tpl + "/" + html;
    }

    /** 安全过滤规则说明（编辑页显示）。 */
    public List<String> rules() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        out.add("@import 只能是内置模板用到的：" + String.join("、", filter.allowedImports()));
        out.add("<script>、<iframe> 只能出现在内置版本本身含有它们的模板里");
        for (String r : JteSecurityFilter.RULES.keySet()) {
            out.add("不允许：" + r);
        }
        return out;
    }

    // ------------------------------------------------------------------ 启动

    /** 启动时调用：按部署参数启用外部模板，失败时回退到内置模板并留下后台提示。 */
    public void start() {
        if (!enabled) {
            return;
        }
        if (compiler == null) {
            notice = NO_COMPILER + "，前台使用内置模板";
            log.warn("site template online editing enabled but no javac available; using built-in templates");
            return;
        }
        if (!files.isConfigured()) {
            notice = "没有配置旧站目录，前台使用内置模板";
            return;
        }
        synchronized (lock) {
            String root = root();
            try {
                if (readSet(root).isEmpty()) {
                    for (Map.Entry<String, byte[]> e : builtin.entrySet()) {
                        files.replace(root + "/" + e.getKey(), e.getValue(), null);
                    }
                    log.info("exported {} built-in site templates to {}", builtin.size(), root);
                }
                Map<String, byte[]> set = readSet(root);
                for (Map.Entry<String, byte[]> e : set.entrySet()) {
                    List<String> bad = filter.check(e.getKey(), utf8(e.getValue()));
                    if (!bad.isEmpty()) {
                        fallback(root, e.getKey() + "：" + bad.getFirst());
                        return;
                    }
                }
                swap(compiler.compile(set), root);
                notice = null;
                log.info("site templates compiled from {} (generation {})", root, generation());
            } catch (JteOnlineCompiler.CompileFailure f) {
                JteOnlineCompiler.Failure x = f.failure();
                fallback(root, x.template() + (x.line() > 0 ? " 第 " + x.line() + " 行" : "") + "：" + firstLine(x.message()));
            } catch (RuntimeException e) {
                fallback(root, e.getClass().getSimpleName());
            }
        }
    }

    private void fallback(String root, String reason) {
        notice = "外部模板目录 " + root + "/ 编译失败，前台已回退到内置模板（" + reason + "）";
        log.error("site templates in {} failed to compile; falling back to built-in templates: {}", root, reason);
    }

    // ------------------------------------------------------------------ 保存、恢复、删除

    /**
     * 保存一个文件（站点根目录下的相对路径，已通过旧版的路径与扩展名检查）。
     *
     * @param expectedVersion 编辑页打开时文件的版本（{@link #version}）；为 null 时不检查
     */
    public Result save(String relative, byte[] content, String expectedVersion) {
        if (content.length > MAX_BYTES) {
            return Result.refused("文件太大");
        }
        if (!relative.endsWith(".jte")) {
            if (LEGACY.matcher(new String(content, StandardCharsets.ISO_8859_1)).find()) {
                return Result.refused(null);
            }
            return write(relative, content, expectedVersion);
        }
        String name = templateName(relative);
        Result pre = checkJte(relative, name);
        if (pre != null) {
            return pre;
        }
        String text = utf8(content);
        if (text == null) {
            return Result.refused(NOT_UTF8);
        }
        List<String> bad = filter.check(name, text);
        if (!bad.isEmpty()) {
            return Result.refused("模板含有不允许的内容，未保存：" + String.join("；", bad));
        }
        synchronized (lock) {
            return replaceAndSwap(relative, name, content, expectedVersion);
        }
    }

    /** "恢复上一版"：把上次保存前的内容（同样经过编译）换回来；换下来的内容成为新的"上一版"。 */
    public Result restore(String relative, String expectedVersion) {
        byte[] previous = previous(relative);
        if (previous == null) {
            return Result.refused(NO_PREVIOUS);
        }
        return save(relative, previous, expectedVersion);
    }

    /** 有没有可恢复的上一版。 */
    public boolean hasPrevious(String relative) {
        return previous(relative) != null;
    }

    /** 删除一个文件；jte 模板先确认没有它整套仍能编译。返回 false 的 Result 带拒绝原因。 */
    public Result delete(String relative) {
        if (!relative.endsWith(".jte") || !enabled) {
            files.delete(relative);
            return Result.success();
        }
        String name = templateName(relative);
        if (name == null || compiler == null || !files.isConfigured()) {
            files.delete(relative);
            return Result.success();
        }
        synchronized (lock) {
            String root = root();
            Map<String, byte[]> set = readSet(root);
            if (set.remove(name) == null) {
                return Result.success();
            }
            JteOnlineCompiler.Compiled compiled;
            try {
                compiled = compiler.compile(set);
            } catch (JteOnlineCompiler.CompileFailure f) {
                JteOnlineCompiler.Failure x = f.failure();
                return new Result(false, "删除后整套模板无法编译，可能仍被其他模板引用，未删除：" + x.template()
                        + (x.line() > 0 ? " 第 " + x.line() + " 行" : "") + " " + firstLine(x.message()), x.template(), x.line());
            }
            byte[] old = files.read(relative, MAX_BYTES);
            files.replace(BACKUP_DIR + relative, old, null);
            files.delete(relative);
            swap(compiled, root);
            return Result.success();
        }
    }

    /** 文件当前的版本（编辑页的隐藏字段；不存在时为 {@code absent}）。 */
    public String version(String relative) {
        return files.version(relative);
    }

    private Result replaceAndSwap(String relative, String name, byte[] content, String expectedVersion) {
        String current = files.version(relative);
        if (expectedVersion != null && !expectedVersion.equals(current)) {
            return Result.refused(CONFLICT);
        }
        String root = root();
        Map<String, byte[]> set = readSet(root);
        set.put(name, content);
        JteOnlineCompiler.Compiled compiled;
        try {
            compiled = compiler.compile(set);
        } catch (JteOnlineCompiler.CompileFailure f) {
            JteOnlineCompiler.Failure x = f.failure();
            return new Result(false, "模板编译失败，未保存，前台仍使用上一版：" + x.template()
                    + (x.line() > 0 ? " 第 " + x.line() + " 行" : "") + "\n" + x.message(), x.template(), x.line());
        }
        try {
            if (!"absent".equals(current)) {
                files.replace(BACKUP_DIR + relative, files.read(relative, MAX_BYTES), null);
            }
            files.replace(relative, content, current);
        } catch (LegacySiteFiles.FileException e) {
            compiler.discard(compiled);
            return Result.refused(e.reason() == LegacySiteFiles.Reason.CONFLICT ? CONFLICT : "保存失败");
        }
        swap(compiled, root);
        return Result.success();
    }

    private Result write(String relative, byte[] content, String expectedVersion) {
        try {
            files.replace(relative, content, expectedVersion);
            return Result.success();
        } catch (LegacySiteFiles.FileException e) {
            return Result.refused(e.reason() == LegacySiteFiles.Reason.CONFLICT ? CONFLICT : "保存失败");
        }
    }

    private Result checkJte(String relative, String name) {
        String reason = unavailable();
        if (reason != null) {
            return Result.refused(reason);
        }
        if (name == null) {
            return Result.refused(relative.startsWith(root() + "/") ? BAD_NAME : String.format(OUTSIDE_ROOT, root()));
        }
        return null;
    }

    /** 站点相对路径 → 模板名（不在当前模板目录下或名字不合法时为 null）。 */
    public String templateName(String relative) {
        String prefix = root() + "/";
        if (!relative.startsWith(prefix)) {
            return null;
        }
        String name = relative.substring(prefix.length());
        return JteOnlineCompiler.validName(name) ? name : null;
    }

    private void swap(JteOnlineCompiler.Compiled compiled, String root) {
        Active old = active;
        active = new Active(compiled, root);
        if (retired != null) {
            compiler.discard(retired);
        }
        retired = old == null ? null : old.compiled();
    }

    /** 外部目录里的整套 jte（模板名 → 内容）。 */
    private Map<String, byte[]> readSet(String root) {
        Map<String, byte[]> out = new TreeMap<>();
        for (String rel : files.walkFiles(root)) {
            if (rel.endsWith(".jte")) {
                out.put(rel, files.read(root + "/" + rel, MAX_BYTES));
            }
        }
        return out;
    }

    private byte[] previous(String relative) {
        try {
            return files.stat(BACKUP_DIR + relative) == null ? null : files.read(BACKUP_DIR + relative, MAX_BYTES);
        } catch (LegacySiteFiles.FileException e) {
            return null;
        }
    }

    static String utf8(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    /** 内置模板源码（标签向导用）。 */
    public Map<String, byte[]> builtin() {
        return builtin;
    }
}
