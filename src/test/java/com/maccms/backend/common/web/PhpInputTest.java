package com.maccms.backend.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** PHP 查询串/表单正文的按字节解析与 ThinkPHP input() 的合并（B09，18 文档第 3.1 节）。 */
class PhpInputTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void decodesBytesLikePhp() {
        Map<String, PhpInput.Value> m = PhpInput.parse(b("a=%00x%FF&b=+1+&c=%zz&d&=skip&e=1=2&f=%E4%B8%AD"));
        assertThat(m.get("a").bytes()).isEqualTo(new byte[] {0, 'x', (byte) 0xFF});
        assertThat(m.get("b").bytes()).isEqualTo(b(" 1 "));
        assertThat(m.get("c").bytes()).as("不完整的 % 原样").isEqualTo(b("%zz"));
        assertThat(m.get("d").bytes()).isEmpty();
        assertThat(m.get("e").bytes()).isEqualTo(b("1=2"));
        assertThat(m.get("f").text()).isEqualTo("中");
        assertThat(m).doesNotContainKey("");
    }

    @Test
    void namesFollowRegisterVariableRules() {
        Map<String, PhpInput.Value> m = PhpInput.parse(b("a.b=1&a+c=2&%20d=3&x[]=4&y[=5&a=1&a=2&&"));
        assertThat(m.get("a_b").text()).isEqualTo("1");
        assertThat(m.get("a_c").text()).isEqualTo("2");
        assertThat(m.get("d").text()).as("名前空格去掉").isEqualTo("3");
        assertThat(m.get("x").array()).isTrue();
        assertThat(PhpInput.scalar(m, "x")).as("数组按缺失").isNull();
        assertThat(m.get("y_").text()).isEqualTo("5");
        assertThat(m.get("a").text()).as("后到的覆盖先到的").isEqualTo("2");
        assertThat(List.copyOf(m.keySet())).as("覆盖时保留首次出现的位置").containsExactly("a_b", "a_c", "d", "x", "y_", "a");
    }

    @Test
    void mergeIsGetThenPostThenRoute() {
        Map<String, PhpInput.Value> get = PhpInput.parseQuery("a=g&b=g&c=g");
        Map<String, PhpInput.Value> post = PhpInput.parse(b("b=p&c=p"));
        Map<String, String> route = new LinkedHashMap<>();
        route.put("c", "r");
        Map<String, PhpInput.Value> m = PhpInput.merge(get, post, route);
        assertThat(m.get("a").text()).isEqualTo("g");
        assertThat(m.get("b").text()).isEqualTo("p");
        assertThat(m.get("c").text()).isEqualTo("r");
        assertThat(PhpInput.parseQuery(null)).isEmpty();
    }
}
