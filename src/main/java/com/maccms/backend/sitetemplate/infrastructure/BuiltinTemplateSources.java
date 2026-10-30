package com.maccms.backend.sitetemplate.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * jar 内置的前台模板源码（构建时把 src/main/jte 复制到 classpath:/site-jte/，pom.xml）：首次启用在线编辑时导出到外部目录；
 * 安全过滤取它的 {@code @import} 与 {@code <script>}/{@code <iframe>} 用法作为允许范围；标签向导读它的参数声明。
 */
public final class BuiltinTemplateSources {

    private static final String PREFIX = "site-jte/";

    private BuiltinTemplateSources() {
    }

    /** 模板名（如 {@code site/pages/home.jte}）→ 源码字节，按名字排序。 */
    public static Map<String, byte[]> load(ClassLoader loader) {
        Map<String, byte[]> out = new TreeMap<>();
        try {
            Resource[] all = new PathMatchingResourcePatternResolver(loader).getResources("classpath*:" + PREFIX + "**/*.jte");
            for (Resource r : all) {
                String url = r.getURI().toString();
                int i = url.lastIndexOf("/" + PREFIX);
                if (i < 0) {
                    continue;
                }
                String name = url.substring(i + 1 + PREFIX.length());
                try (InputStream in = r.getInputStream()) {
                    out.putIfAbsent(name, in.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取内置模板源码失败", e);
        }
        return out;
    }
}
