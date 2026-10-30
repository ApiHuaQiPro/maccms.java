package com.maccms.backend.site;

import com.maccms.backend.legacyconfig.application.VarExportParser;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;

/**
 * 测试用的旧配置文件（B11）：读仓库的 {@code extra/maccms.php}（{@link VarExportParser}），按黄金样本的覆盖项合并（与
 * {@code interaction_golden.php} 的 {@code deep_merge} 相同：数组递归合并，其余整体替换），再按 PHP {@code var_export} 的格式写回临时目录。
 * 每次写出都把修改时间往后推一秒，保证旧配置的热加载（按修改时间与大小）能看到变化。
 */
final class PhpConfigFiles {

    private static final AtomicLong MTIME = new AtomicLong(System.currentTimeMillis() / 1000 * 1000);

    private PhpConfigFiles() {
    }

    static PhpArray read(Path file) throws IOException {
        return VarExportParser.parse(Files.readAllBytes(file));
    }

    /** 递归合并：{@code over} 的对象节点合并进同名数组，其他值替换（字符串、整数按原类型）。 */
    static PhpArray merge(PhpArray base, JsonNode over) {
        if (over == null || !over.isObject()) {
            return base;
        }
        Map<Object, PhpValue> m = new LinkedHashMap<>(base.entries());
        for (Map.Entry<String, JsonNode> e : over.properties()) {
            Object key = e.getKey().matches("0|[1-9][0-9]{0,17}") ? (Object) Long.parseLong(e.getKey()) : e.getKey();
            PhpValue cur = m.get(key);
            JsonNode v = e.getValue();
            if (v.isObject()) {
                m.put(key, merge(cur instanceof PhpArray a ? a : new PhpArray(Map.of()), v));
            } else if (v.isIntegralNumber()) {
                m.put(key, new PhpValue.PhpInt(v.asLong()));
            } else if (v.isBoolean()) {
                m.put(key, new PhpValue.PhpBool(v.asBoolean()));
            } else if (v.isNull()) {
                m.put(key, PhpValue.PhpNull.INSTANCE);
            } else {
                m.put(key, new PhpValue.PhpString(v.asString()));
            }
        }
        return new PhpArray(m);
    }

    static void write(Path file, PhpArray value) throws IOException {
        StringBuilder sb = new StringBuilder("<?php\nreturn ");
        export(sb, value, "");
        sb.append(";\n");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(MTIME.addAndGet(1000)));
    }

    private static void export(StringBuilder sb, PhpValue v, String indent) {
        switch (v) {
            case PhpArray a -> {
                sb.append("\n").append(indent).append("array (\n");
                for (Map.Entry<Object, PhpValue> e : a.entries().entrySet()) {
                    sb.append(indent).append("  ");
                    if (e.getKey() instanceof Long l) {
                        sb.append(l);
                    } else {
                        sb.append(quote((String) e.getKey()));
                    }
                    sb.append(" => ");
                    export(sb, e.getValue(), indent + "  ");
                    sb.append(",\n");
                }
                sb.append(indent).append(")");
            }
            case PhpValue.PhpString s -> sb.append(quote(s.value()));
            case PhpValue.PhpInt i -> sb.append(i.value());
            case PhpValue.PhpFloat f -> sb.append(f.value());
            case PhpValue.PhpBool b -> sb.append(b.value() ? "true" : "false");
            case PhpValue.PhpNull n -> sb.append("NULL");
        }
    }

    private static String quote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
