package com.maccms.backend.contract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;

/**
 * 读取 docs/contracts/*.openapi.yaml，按 schema 严格校验 JSON 响应：
 * 对象的键集合必须与 properties 完全相同（多出的键 = 契约外字段/泄露，缺少的键 = 违反 required），
 * 值类型、枚举、非 null 逐层检查。只实现本项目契约用到的 OpenAPI 子集。
 */
public final class OpenApiContract {

    public static final Path CONTENT_READ = Path.of("docs", "contracts", "content-read.openapi.yaml");

    private final Map<String, Object> root;

    private OpenApiContract(Map<String, Object> root) {
        this.root = root;
    }

    public static OpenApiContract load(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            Map<String, Object> root = new Yaml().load(in);
            return new OpenApiContract(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取契约文件 " + path.toAbsolutePath(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    public Map<String, Object> paths() {
        return map(root.get("paths"));
    }

    public Map<String, Object> operation(String path, String method) {
        Map<String, Object> item = map(paths().get(path));
        if (item == null || item.get(method) == null) {
            throw new AssertionError("契约中没有 " + method.toUpperCase() + " " + path);
        }
        return map(item.get(method));
    }

    /** 取某个操作在给定 HTTP 状态下的响应 schema（会解析 components.responses 引用）。 */
    public Map<String, Object> responseSchema(String path, String method, int status) {
        Map<String, Object> responses = map(operation(path, method).get("responses"));
        Object resp = responses.get(Integer.toString(status));
        if (resp == null) {
            throw new AssertionError("契约中 " + method.toUpperCase() + " " + path + " 没有声明状态码 " + status);
        }
        Map<String, Object> r = resolve(map(resp));
        Map<String, Object> content = map(r.get("content"));
        return resolve(map(map(content.get("application/json")).get("schema")));
    }

    public Map<String, Object> schema(String name) {
        return map(map(map(root.get("components")).get("schemas")).get(name));
    }

    private Map<String, Object> resolve(Map<String, Object> node) {
        Object ref = node.get("$ref");
        if (ref == null) {
            return node;
        }
        String[] parts = ref.toString().substring(2).split("/");
        Object cur = root;
        for (String p : parts) {
            cur = map(cur).get(p);
        }
        return resolve(map(cur));
    }

    /** 返回所有违反契约之处；空列表表示通过。 */
    public List<String> violations(JsonNode value, Map<String, Object> schema) {
        List<String> out = new ArrayList<>();
        check(value, resolve(schema), "$", out);
        return out;
    }

    public void assertMatches(JsonNode value, Map<String, Object> schema) {
        List<String> v = violations(value, schema);
        if (!v.isEmpty()) {
            throw new AssertionError("响应不符合 OpenAPI 契约:\n  " + String.join("\n  ", v) + "\n响应: " + value);
        }
    }

    private void check(JsonNode v, Map<String, Object> s, String at, List<String> out) {
        if (v == null || v.isNull()) {
            out.add(at + " 为 null（契约约定不输出 null）");
            return;
        }
        String type = (String) s.get("type");
        if (s.get("enum") instanceof List<?> allowed && v.isString() && !allowed.contains(v.stringValue())) {
            out.add(at + " 取值 " + v.stringValue() + " 不在枚举 " + allowed + " 中");
        }
        switch (type) {
            case "object" -> {
                if (!v.isObject()) {
                    out.add(at + " 应为 object");
                    return;
                }
                Map<String, Object> props = map(s.get("properties"));
                Set<String> actual = new LinkedHashSet<>();
                for (Iterator<String> it = v.propertyNames().iterator(); it.hasNext(); ) {
                    actual.add(it.next());
                }
                Set<String> extra = new LinkedHashSet<>(actual);
                extra.removeAll(props.keySet());
                if (!extra.isEmpty()) {
                    out.add(at + " 出现契约外字段 " + extra);
                }
                for (Object req : (List<?>) s.getOrDefault("required", List.of())) {
                    if (!actual.contains(req.toString())) {
                        out.add(at + " 缺少必需字段 " + req);
                    }
                }
                Set<String> declaredNotRequired = new LinkedHashSet<>(props.keySet());
                declaredNotRequired.removeAll(((List<?>) s.getOrDefault("required", List.of())).stream()
                        .map(Object::toString).toList());
                if (!declaredNotRequired.isEmpty()) {
                    out.add(at + " schema 中有未列入 required 的字段 " + declaredNotRequired + "（本项目约定全部必填）");
                }
                for (String k : actual) {
                    if (props.containsKey(k)) {
                        check(v.get(k), resolve(map(props.get(k))), at + "." + k, out);
                    }
                }
            }
            case "array" -> {
                if (!v.isArray()) {
                    out.add(at + " 应为 array");
                    return;
                }
                Map<String, Object> items = resolve(map(s.get("items")));
                for (int i = 0; i < v.size(); i++) {
                    check(v.get(i), items, at + "[" + i + "]", out);
                }
            }
            case "string" -> {
                if (!v.isString()) {
                    out.add(at + " 应为 string，实际 " + v.getNodeType());
                }
            }
            case "integer" -> {
                if (!v.isIntegralNumber()) {
                    out.add(at + " 应为 integer，实际 " + v.getNodeType());
                }
            }
            case "number" -> {
                if (!v.isNumber()) {
                    out.add(at + " 应为 number，实际 " + v.getNodeType());
                }
            }
            case "boolean" -> {
                if (!v.isBoolean()) {
                    out.add(at + " 应为 boolean，实际 " + v.getNodeType());
                }
            }
            default -> out.add(at + " schema 类型 " + type + " 未被校验器支持");
        }
    }
}
