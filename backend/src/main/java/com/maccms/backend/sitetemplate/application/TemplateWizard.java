package com.maccms.backend.sitetemplate.application;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标签向导（旧 {@code Template::wizard} 生成 MacCMS 标签；路线图第 5.6 节第 5 点改为生成等价的 jte 片段，24 文档第 7.5 节）。
 * jte 模板没有旧版那样按条件查库的标签，数据都来自页面的视图模型，所以向导按内置的页面模板列出它们的参数，并按视图模型（record）的字段
 * 生成可直接粘贴的输出、循环与判断写法。只读内置源码与本工程的视图模型类，不读库。
 */
public final class TemplateWizard {

    /** 一个页面模板的参考片段。 */
    public record Entry(String template, String snippet) {
    }

    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*@import\\s+([\\w.]+)\\s*$");
    private static final Pattern PARAM = Pattern.compile("(?m)^\\s*@param\\s+([\\w.<>, ]+?)\\s+(\\w+)\\s*(?:=.*)?$");

    private TemplateWizard() {
    }

    public static List<Entry> entries(Map<String, byte[]> builtin, ClassLoader loader) {
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : builtin.entrySet()) {
            if (!e.getKey().startsWith("site/pages/")) {
                continue;
            }
            String text = new String(e.getValue(), StandardCharsets.UTF_8);
            Map<String, String> imports = new HashMap<>();
            Matcher im = IMPORT.matcher(text);
            while (im.find()) {
                String fqn = im.group(1);
                imports.put(fqn.substring(fqn.lastIndexOf('.') + 1), fqn);
            }
            StringBuilder sb = new StringBuilder();
            Matcher pm = PARAM.matcher(text);
            while (pm.find()) {
                String type = pm.group(1).strip();
                String name = pm.group(2);
                sb.append("@param ").append(type).append(' ').append(name).append('\n');
                Class<?> c = load(imports.getOrDefault(type, type), loader);
                if (c != null && c.isRecord()) {
                    describe(sb, name, c, loader, 0);
                } else {
                    sb.append("${").append(name).append("}\n");
                }
                sb.append('\n');
            }
            out.add(new Entry(e.getKey(), sb.toString().stripTrailing()));
        }
        return out;
    }

    private static void describe(StringBuilder sb, String expr, Class<?> record, ClassLoader loader, int depth) {
        String indent = "    ".repeat(depth);
        for (RecordComponent rc : record.getRecordComponents()) {
            String call = expr + "." + rc.getName() + "()";
            Class<?> t = rc.getType();
            if (Collection.class.isAssignableFrom(t)) {
                Class<?> item = typeArgument(rc.getGenericType());
                String var = depth == 0 ? "item" : "item" + depth;
                sb.append(indent).append("@for(var ").append(var).append(" : ").append(call).append(")\n");
                if (item != null && item.isRecord() && depth < 1) {
                    describe(sb, var, item, loader, depth + 1);
                } else {
                    sb.append(indent).append("    ${").append(var).append("}\n");
                }
                sb.append(indent).append("@endfor\n");
            } else if (t == Optional.class) {
                sb.append(indent).append("@if(").append(call).append(".isPresent())\n");
                sb.append(indent).append("    ${").append(call).append(".get()}\n");
                sb.append(indent).append("@endif\n");
            } else if (t == boolean.class || t == Boolean.class) {
                sb.append(indent).append("@if(").append(call).append(")…@endif\n");
            } else if (t.isRecord() && depth < 1) {
                describe(sb, call, t, loader, depth + 1);
            } else if (scalar(t)) {
                sb.append(indent).append("${").append(call).append("}\n");
            } else {
                sb.append(indent).append("${").append(call).append("}  <%-- ").append(t.getSimpleName()).append(" --%>\n");
            }
        }
    }

    private static boolean scalar(Class<?> t) {
        return t.isPrimitive() || t == String.class || Number.class.isAssignableFrom(t) || t.isEnum()
                || t.getName().equals("gg.jte.Content");
    }

    private static Class<?> typeArgument(Type generic) {
        if (generic instanceof ParameterizedType p && p.getActualTypeArguments().length == 1
                && p.getActualTypeArguments()[0] instanceof Class<?> c) {
            return c;
        }
        return null;
    }

    private static Class<?> load(String name, ClassLoader loader) {
        if (!name.startsWith("com.maccms.backend.site.")) {
            return null;
        }
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
