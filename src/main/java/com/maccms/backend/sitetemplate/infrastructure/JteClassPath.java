package com.maccms.backend.sitetemplate.infrastructure;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 运行时编译模板时 javac 的类路径（24 文档第 7.4 节）。生成的模板类要引用视图模型（本工程的类）与 jte 运行时：
 * <ul>
 * <li>开发与测试（类在目录或普通 jar 里）：取各锚点类所在的位置；</li>
 * <li>Spring Boot 可执行 jar（类在 {@code BOOT-INF/classes}、依赖在 {@code BOOT-INF/lib/*.jar}，javac 读不了嵌套 jar）：把这两部分解到
 * 工作目录，类路径指向解出的目录与 jar。只在开启在线编辑时做一次。</li>
 * </ul>
 */
public final class JteClassPath {

    private JteClassPath() {
    }

    public static List<String> resolve(Path workDir, Class<?>... anchors) {
        Set<String> out = new LinkedHashSet<>();
        boolean packed = false;
        for (Class<?> anchor : anchors) {
            var source = anchor.getProtectionDomain().getCodeSource();
            var loc = source == null ? null : source.getLocation();
            if (loc != null && "file".equalsIgnoreCase(loc.getProtocol())) {
                try {
                    out.add(Path.of(loc.toURI()).toString());
                } catch (URISyntaxException e) {
                    throw new IllegalStateException("类路径", e);
                }
            } else {
                packed = true;
            }
        }
        if (packed) {
            for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
                Path jar = entry.isBlank() ? null : Path.of(entry);
                if (jar != null && Files.isRegularFile(jar) && isBootJar(jar)) {
                    out.addAll(extract(jar, workDir.resolve("boot-classpath")));
                }
            }
        }
        return List.copyOf(out);
    }

    private static boolean isBootJar(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            return z.getEntry("BOOT-INF/classes/") != null || z.getEntry("BOOT-INF/lib/") != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** 解出 {@code BOOT-INF/classes/**} 与 {@code BOOT-INF/lib/*.jar}，返回 [classes 目录, 各 jar]。 */
    static List<String> extract(Path jar, Path target) {
        List<String> out = new ArrayList<>();
        Path classes = target.resolve("classes").toAbsolutePath().normalize();
        Path lib = target.resolve("lib").toAbsolutePath().normalize();
        out.add(classes.toString());
        try (ZipFile z = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                Path to;
                if (name.startsWith("BOOT-INF/classes/")) {
                    to = classes.resolve(name.substring("BOOT-INF/classes/".length())).normalize();
                    if (!to.startsWith(classes)) {
                        continue;
                    }
                } else if (name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar") && name.indexOf('/', 13) < 0) {
                    to = lib.resolve(name.substring("BOOT-INF/lib/".length())).normalize();
                    if (!to.startsWith(lib)) {
                        continue;
                    }
                    out.add(to.toString());
                } else {
                    continue;
                }
                if (e.isDirectory()) {
                    Files.createDirectories(to);
                    continue;
                }
                Files.createDirectories(to.getParent());
                try (InputStream in = z.getInputStream(e)) {
                    Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("解出可执行 jar 的类路径失败", e);
        }
        return out;
    }
}
