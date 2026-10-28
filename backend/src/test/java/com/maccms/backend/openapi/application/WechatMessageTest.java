package com.maccms.backend.openapi.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 消息 XML 的解析与 XXE 防护（B27，29 文档第 4.3 节、D-B27-3）。 */
class WechatMessageTest {

    private static WechatMessage parse(String xml) {
        return WechatMessage.parse(xml.getBytes(StandardCharsets.UTF_8)).orElseThrow();
    }

    @Test
    void readsDirectTextOfFirstChildLikeSimpleXml() {
        WechatMessage m = parse("<xml><A><![CDATA[x]]]]><![CDATA[>y]]></A><A>second</A><B>a&amp;b<i>skip</i>c</B>"
                + "<C/></xml>");
        assertThat(m.field("A")).isEqualTo("x]]>y");
        assertThat(m.field("B")).isEqualTo("a&bc");
        assertThat(m.field("C")).isEmpty();
        assertThat(m.field("Missing")).isEmpty();
    }

    @Test
    void rejectsDoctypeSoNoEntityIsExpandedOrFetched(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "SECRET-MARKER");
        String uri = secret.toUri().toString();
        for (String xml : new String[]{
                "<!DOCTYPE xml [<!ENTITY e SYSTEM \"" + uri + "\">]><xml><Content>&e;</Content></xml>",
                "<!DOCTYPE xml [<!ENTITY % p SYSTEM \"" + uri + "\"> %p;]><xml/>",
                "<!DOCTYPE xml SYSTEM \"" + uri + "\"><xml/>",
                "<!DOCTYPE xml [<!ENTITY e \"inner\">]><xml><Content>&e;</Content></xml>",
                "<!DOCTYPE xml [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">]><xml>&b;</xml>"}) {
            assertThat(WechatMessage.parse(xml.getBytes(StandardCharsets.UTF_8))).as(xml).isEmpty();
        }
    }

    @Test
    void malformedOrEmptyIsAbsent() {
        assertThat(WechatMessage.parse("<xml>".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(WechatMessage.parse("hello".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(WechatMessage.parse(new byte[0])).isEmpty();
    }
}
