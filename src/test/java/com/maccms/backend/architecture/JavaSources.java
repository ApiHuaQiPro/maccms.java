package com.maccms.backend.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 护栏测试共用的源码读取与预处理（纯文本扫描，不编译、不加载类）。 */
final class JavaSources {

    static final Path MAIN_JAVA = Path.of("src", "main", "java");
    static final Path MAIN_RESOURCES = Path.of("src", "main", "resources");
    static final String ROOT = "com.maccms.backend";

    private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;");

    private JavaSources() {
    }

    /** src/main/java 下全部 .java 文件：相对路径 → 内容。 */
    static Map<String, String> mainJava() throws IOException {
        return read(MAIN_JAVA, ".java");
    }

    static Map<String, String> read(Path root, String suffix) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(f -> Files.isRegularFile(f) && f.toString().endsWith(suffix)).sorted().toList()) {
                out.put(root.relativize(p).toString().replace('\\', '/'), Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    /** 包名去掉 {@code com.maccms.backend.} 前缀（如 {@code content.infrastructure}）；不在本项目包内返回 null。 */
    static String modulePackage(String source) {
        Matcher m = PACKAGE.matcher(stripCommentsAndStrings(source));
        if (!m.find()) {
            return null;
        }
        String pkg = m.group(1);
        if (pkg.equals(ROOT)) {
            return "";
        }
        return pkg.startsWith(ROOT + ".") ? pkg.substring(ROOT.length() + 1) : null;
    }

    /** 去掉注释与字符串/字符字面量（含文本块），只留代码；字面量替换为空的 {@code ""}。 */
    static String stripCommentsAndStrings(String src) {
        return ModuleBoundaryTest.code(src);
    }

    /** 去掉注释（换成空格），保留字符串与文本块原文。 */
    static String stripComments(String src) {
        StringBuilder sb = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (src.startsWith("//", i)) {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                sb.append(' ');
            } else if (src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                int stop = end < 0 ? n : end + 3;
                sb.append(src, i, stop);
                i = stop;
            } else if (c == '"' || c == '\'') {
                int start = i;
                i++;
                while (i < n && src.charAt(i) != c && src.charAt(i) != '\n') {
                    i += src.charAt(i) == '\\' ? 2 : 1;
                }
                i = Math.min(i + 1, n);
                sb.append(src, start, i);
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** 去掉 XML 注释。 */
    static String stripXmlComments(String xml) {
        return xml.replaceAll("(?s)<!--.*?-->", " ");
    }
}
