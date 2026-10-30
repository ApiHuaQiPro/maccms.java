package com.maccms.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.content.application.PhpJson;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 多维数组参数（B14 会员组权限 {@code group_popedom[分类][位]}，23 文档第 4.1 节）：{@link AdminParams#nested} 与 PHP {@code parse_str}
 * 的结果相同（PHP 8.0.30 实测，{@code json_encode} 后比较）；一维数组与标量的解析不变（B12/B13 的用法）。
 */
class AdminParamsNestedTest {

    @Test
    void nestedArraysMatchPhpParseStr() {
        AdminParams p = AdminParams.parse(("a[1][1]=x&a[1][3]=y&a[6][2]=z&a[5][]=p&a[5][]=q&a[2]=s&a[2][5]=t&a[7][x]y[z]=1"
                + "&a[8][=w&a[3][3]=m&a[3]=n").getBytes(StandardCharsets.ISO_8859_1));
        // PHP: {"1":{"1":"x","3":"y"},"6":{"2":"z"},"5":["p","q"],"2":{"5":"t"},"7":{"x":"1"},"8":"w","3":"n"}
        assertThat(PhpJson.encode(text(p.nested("a"))))
                .isEqualTo("{\"1\":{\"1\":\"x\",\"3\":\"y\"},\"6\":{\"2\":\"z\"},\"5\":[\"p\",\"q\"],\"2\":{\"5\":\"t\"},"
                        + "\"7\":{\"x\":\"1\"},\"8\":\"w\",\"3\":\"n\"}");
        assertThat(p.nested("missing")).isNull();
        assertThat(new String((byte[]) AdminParams.parse("a=abc".getBytes(StandardCharsets.ISO_8859_1)).nested("a"),
                StandardCharsets.UTF_8)).isEqualTo("abc");
        // 一维数组的取法不变：第一层的各项与键
        assertThat(p.list("a")).hasSize(11);
        assertThat(p.values().get("a").keys()).startsWith("1", "1", "6", "5", "5", "2", "2");
    }

    /** 叶子换成文字、键恰好是 0、1… 的数组换成列表（同 PHP json_encode）。 */
    @SuppressWarnings("unchecked")
    private static Object text(Object v) {
        if (v instanceof byte[] b) {
            return new String(b, StandardCharsets.UTF_8);
        }
        Map<String, Object> m = (Map<String, Object>) v;
        boolean list = true;
        int i = 0;
        for (String k : m.keySet()) {
            list &= k.equals(Integer.toString(i++));
        }
        if (list) {
            return m.values().stream().map(AdminParamsNestedTest::text).toList();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, x) -> out.put(k, text(x)));
        return out;
    }

    @Test
    void scalarAndFlatArraysAreUnchanged() {
        AdminParams p = AdminParams.parse("ids%5B%5D=1&ids%5B%5D=2&x=y".getBytes(StandardCharsets.ISO_8859_1));
        assertThat(p.list("ids")).extracting(b -> new String(b, StandardCharsets.UTF_8)).isEqualTo(List.of("1", "2"));
        assertThat(p.values().get("ids").rests()).containsExactly(null, null);
        assertThat(new String(p.scalar("x"), StandardCharsets.UTF_8)).isEqualTo("y");
    }
}
