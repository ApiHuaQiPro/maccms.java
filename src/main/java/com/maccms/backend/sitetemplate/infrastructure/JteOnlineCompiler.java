package com.maccms.backend.sitetemplate.infrastructure;

import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.TemplateException;
import gg.jte.resolve.DirectoryCodeResolver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;

/**
 * 整套外部模板的运行时编译（路线图第 5.6 节第 2、3 点，24 文档第 7.4 节）。每次编译用一个新目录（源码副本 + 生成的类），
 * 编译通过后返回只加载这次编译结果的引擎（{@link TemplateEngine#createPrecompiled}：之后有人改了磁盘上的文件也不会被自动重新编译）。
 * 生成类的包名与内置模板不同，否则父类加载器会先找到 jar 里预编译的同名类。
 */
public class JteOnlineCompiler {

    /** 生成类的包名（内置模板是 com.maccms.backend.site.web.jte）。 */
    public static final String PACKAGE = "com.maccms.online.jte";

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]*(/[A-Za-z0-9_][A-Za-z0-9_.-]*)*\\.jte");
    private static final Pattern LOCATION = Pattern.compile("([A-Za-z0-9_][A-Za-z0-9_./-]*\\.jte)(?::|, error at line |\\D{0,20}line )(\\d+)");

    /** 一次编译的结果：引擎与它的目录（换下来以后删除）。 */
    public record Compiled(long generation, TemplateEngine engine, Path directory) {
    }

    /** 编译失败的位置与信息（文件名为模板名，行号从 1 起；不确定时为 0）。 */
    public record Failure(String template, int line, String message) {
    }

    public static final class CompileFailure extends Exception {
        private final transient Failure failure;

        CompileFailure(Failure failure) {
            super(failure.message(), null, false, false);
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }

    private final Path workDir;
    private final List<String> classPath;
    private final ClassLoader parent;
    private final AtomicLong sequence = new AtomicLong();

    public JteOnlineCompiler(Path workDir, List<String> classPath, ClassLoader parent) {
        this.workDir = workDir;
        this.classPath = List.copyOf(classPath);
        this.parent = parent;
    }

    /** 运行环境有没有 javac（只有 JRE 时为 false，在线编辑拒绝保存）。 */
    public static boolean available() {
        return ToolProvider.getSystemJavaCompiler() != null;
    }

    public static boolean validName(String name) {
        return name != null && name.length() <= 255 && NAME.matcher(name).matches() && !name.contains("..");
    }

    /** 把整套模板源码（模板名 → 内容）写进新目录并全部编译。 */
    public Compiled compile(Map<String, byte[]> sources) throws CompileFailure {
        long n = sequence.incrementAndGet();
        Path dir = workDir.resolve("gen-" + n);
        Path src = dir.resolve("src");
        Path classes = dir.resolve("classes");
        try {
            for (Map.Entry<String, byte[]> e : sources.entrySet()) {
                if (!validName(e.getKey())) {
                    throw new CompileFailure(new Failure(e.getKey(), 0, "模板文件名不合法"));
                }
                Path p = src.resolve(e.getKey());
                Files.createDirectories(p.getParent());
                Files.write(p, e.getValue());
            }
            Files.createDirectories(classes);
        } catch (IOException e) {
            deleteTree(dir);
            throw new UncheckedIOException(e);
        } catch (CompileFailure e) {
            deleteTree(dir);
            throw e;
        }
        TemplateEngine compiler = TemplateEngine.create(new DirectoryCodeResolver(src), classes, ContentType.Html, parent, PACKAGE);
        compiler.setHtmlPolicy(new OnlineHtmlPolicy());
        compiler.setClassPath(classPath);
        try {
            compiler.precompileAll();
        } catch (TemplateException | IllegalStateException e) {
            deleteTree(dir);
            throw new CompileFailure(failure(e));
        }
        return new Compiled(n, TemplateEngine.createPrecompiled(classes, ContentType.Html, parent, PACKAGE), dir);
    }

    /** 删除一次编译的目录（被换下的旧版本；进行中的请求在它上面完成后不再使用）。 */
    public void discard(Compiled compiled) {
        if (compiled != null) {
            deleteTree(compiled.directory());
        }
    }

    /** jte 的错误信息里取模板名与行号；信息本身只保留模板相关的部分（不含工作目录的绝对路径）。 */
    static Failure failure(Exception e) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = e; t != null && text.length() < 4000; t = t.getCause()) {
            if (t.getMessage() != null) {
                text.append(t.getMessage()).append('\n');
            }
        }
        String msg = text.toString().replace('\\', '/');
        msg = msg.replaceAll("(?m)^\\S*/gen-\\d+/(?:src|classes)/", "").replaceAll("\\S*/gen-\\d+/(?:src|classes)/", "");
        Matcher m = LOCATION.matcher(msg);
        String template = "";
        int line = 0;
        if (m.find()) {
            template = m.group(1);
            line = Integer.parseInt(m.group(2));
        }
        String brief = msg.strip();
        if (brief.length() > 1500) {
            brief = brief.substring(0, 1500) + "…";
        }
        return new Failure(template, line, brief);
    }

    static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // 删不掉的旧编译目录留在工作目录里（Windows 上类加载器还开着 jar 时会这样），不影响功能
        }
    }
}
