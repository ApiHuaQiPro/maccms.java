package com.maccms.backend.collect.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Provide 文件的拒绝规则（B18，26 文档第 3.3 节；旧版的对照见 {@code ProvideParserGoldenTest}）：XXE 与实体膨胀（含 DOCTYPE 一律拒绝，不读本地文件、
 * 不连接外部 DTD 的地址）、超深嵌套、新版另拒绝的 JSON 形态（D-B18-5）。超大文件的拒绝在 {@code CollectPreviewServiceTest}。
 */
class ProvideRejectionTest {

    @TempDir
    Path dir;

    private static ProvideDocument vodXml(String xml) {
        return ProvideParser.parse(ProvideKind.VOD, "1", "list", "", xml.getBytes(StandardCharsets.UTF_8));
    }

    private static ProvideDocument vodJson(String json) {
        return ProvideParser.parse(ProvideKind.VOD, "2", "list", "", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertDoctypeRejected(ProvideDocument d) {
        assertThat(d.ok()).isFalse();
        assertThat(d.code()).isEqualTo(1002);
        assertThat(d.errorKey()).isEqualTo("model/collect/xml_err");
        assertThat(d.detail()).contains("DOCTYPE");
        assertThat(d.rows()).isEmpty();
    }

    @Test
    void externalFileEntityIsRejectedWithoutReadingTheFile() throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "B18-SECRET-MARKER", StandardCharsets.UTF_8);
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]>"
                + "<rss><list page=\"1\"><video><id>1</id><tid>1</tid><name>&x;</name></video></list></rss>";
        ProvideDocument d = vodXml(xml);
        assertDoctypeRejected(d);
        assertThat(ProvideParser.bind(d, "f", null, "", k -> k).toString()).doesNotContain("B18-SECRET-MARKER");
        assertThatThrownBy(() -> ProvideXml.parse(xml.getBytes(StandardCharsets.UTF_8))).isInstanceOf(ProvideXml.DoctypeRejectedException.class);
    }

    @Test
    void externalDtdAndParameterEntitiesNeverConnect() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(1500);
            String base = "http://127.0.0.1:" + server.getLocalPort();
            for (String xml : new String[] {
                "<?xml version=\"1.0\"?><!DOCTYPE rss SYSTEM \"" + base + "/x.dtd\"><rss><list/></rss>",
                "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY % p SYSTEM \"" + base + "/p.dtd\"> %p;]><rss><list/></rss>",
                "<?xml version=\"1.0\"?><!DOCTYPE rss PUBLIC \"-//X//EN\" \"" + base + "/pub.dtd\"><rss><list/></rss>",
                "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY x SYSTEM \"" + base + "/e\">]><rss><list><video><name>&x;</name></video></list></rss>",
            }) {
                assertDoctypeRejected(vodXml(xml));
                // 自动判断（先 JSON 再 XML）也一样
                assertDoctypeRejected(ProvideParser.parse(ProvideKind.VOD, "0", "list", "", xml.getBytes(StandardCharsets.UTF_8)));
            }
            assertThatThrownBy(server::accept).as("解析期间不应有任何连接").isInstanceOf(SocketTimeoutException.class);
        }
    }

    @Test
    void entityExpansionIsRejectedQuickly() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lol\">");
        for (int i = 1; i <= 9; i++) {
            sb.append("<!ENTITY lol").append(i).append(" \"");
            for (int j = 0; j < 10; j++) {
                sb.append("&lol").append(i == 1 ? "" : String.valueOf(i - 1)).append(';');
            }
            sb.append("\">");
        }
        sb.append("]><rss><list><video><name>&lol9;</name></video></list></rss>");
        long t0 = System.nanoTime();
        assertDoctypeRejected(vodXml(sb.toString()));
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(2000);
    }

    @Test
    void undeclaredEntityAndXIncludeDoNothing() throws Exception {
        Path secret = dir.resolve("inc.txt");
        Files.writeString(secret, "B18-XINCLUDE-MARKER", StandardCharsets.UTF_8);
        // 未声明的实体：格式错误
        assertThat(vodXml("<rss><list><video><name>&x;</name></video></list></rss>").code()).isEqualTo(1002);
        // XInclude 不处理：带前缀的元素在 SimpleXML 语义下不可见
        ProvideDocument d = vodXml("<rss xmlns:xi=\"http://www.w3.org/2001/XInclude\"><list page=\"1\"><video><id>1</id><tid>1</tid>"
                + "<name><xi:include href=\"" + secret.toUri() + "\" parse=\"text\"/></name></video></list></rss>");
        assertThat(d.ok()).isTrue();
        assertThat(ProvideParser.bind(d, "f", null, "", k -> k).toString()).doesNotContain("B18-XINCLUDE-MARKER");
    }

    @Test
    void deepNestingFailsWithoutStackOverflow() {
        String deepJson = "{\"list\":[{\"d\":" + "[".repeat(100_000) + "]".repeat(100_000) + "}]}";
        assertThat(vodJson(deepJson).code()).isEqualTo(1002);
        String deepXml = "<rss>" + "<a>".repeat(100_000) + "</a>".repeat(100_000) + "</rss>";
        assertThat(vodXml(deepXml).code()).isEqualTo(1002);
    }

    @Test
    void jsonShapesTheLegacyCodeCannotHandleAreRejected() {
        // 顶层是非空标量（旧版得到全是 null 的分页与空列表，或在 PHP 8 上报错）
        for (String s : new String[] {"5", "true", "\"abc\"", "1.5"}) {
            ProvideDocument d = vodJson(s);
            assertThat(d.code()).as(s).isEqualTo(1002);
            assertThat(d.detail()).as(s).contains("顶层");
        }
        // 结果为无穷大的数
        assertThat(vodJson("{\"page\":1e400,\"list\":[]}").code()).isEqualTo(1002);
        // 数据行是字符串、数字、true（旧版 PHP 8 报错）
        for (String row : new String[] {"\"abc\"", "5", "1.5", "true"}) {
            assertThat(vodJson("{\"list\":[" + row + "]}").code()).as(row).isEqualTo(1002);
        }
        // 分类行是字符串
        assertThat(vodJson("{\"list\":[],\"class\":[\"x\"]}").code()).isEqualTo(1002);
        // 角色、评论不换算绑定：数字与 true 原样保留，字符串仍拒绝
        ProvideDocument role = ProvideParser.parse(ProvideKind.ROLE, "", "list", "", "{\"list\":[5,true,null]}".getBytes(StandardCharsets.UTF_8));
        assertThat(role.ok()).isTrue();
        assertThat(new java.util.ArrayList<Object>(((Map<?, ?>) ProvideParser.bind(role, "f", null, "", k -> k).get("data")).values())).containsExactly(5L, true, null);
        assertThat(ProvideParser.parse(ProvideKind.COMMENT, "", "list", "", "{\"list\":[\"x\"]}".getBytes(StandardCharsets.UTF_8)).code())
                .isEqualTo(1002);
    }

    @Test
    void cacheEncodingRoundTripsEveryValueShape() throws Exception {
        Object v = ProvideJson.decode(("{\"a\":[1,2.0,-0.0,1e300,\"\\u0000\\u001f\\ud83d\\ude00\",true,false,null,{}],\"5\":{\"0\":1,\"2\":3},"
                + "\"\":\"\",\"-1\":[[]]}").getBytes(StandardCharsets.UTF_8));
        assertThat(ProvideParserGoldenTest.dump(ProvideJson.decode(ProvideJson.encode(v).getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(ProvideParserGoldenTest.dump(v));
    }

    @Test
    void phpFloatToStringUsesPrecision14() {
        // 期望值取自 PHP 8.0 的 (string)$f
        assertThat(ProvideValues.str(1e20)).isEqualTo("1.0E+20");
        assertThat(ProvideValues.str(0.1)).isEqualTo("0.1");
        assertThat(ProvideValues.str(0.1 + 0.2)).isEqualTo("0.3");
        assertThat(ProvideValues.str(1.5e-7)).isEqualTo("1.5E-7");
        assertThat(ProvideValues.str(0.0001)).isEqualTo("0.0001");
        assertThat(ProvideValues.str(-0.0)).isEqualTo("-0");
        assertThat(ProvideValues.str(123456789012345.0)).isEqualTo("1.2345678901234E+14");
        assertThat(ProvideValues.str(12345678901234.0)).isEqualTo("12345678901234");
        assertThat(ProvideValues.str(2.5)).isEqualTo("2.5");
        assertThat(ProvideValues.str(0.00001)).isEqualTo("1.0E-5");
        assertThat(ProvideValues.str(1e15)).isEqualTo("1.0E+15");
        assertThat(ProvideValues.str(999999999999999.0)).isEqualTo("1.0E+15");
    }

    @Test
    void midDispatchFollowsLooseComparison() {
        assertThat(ProvideKind.ofMid("")).contains(ProvideKind.VOD);
        assertThat(ProvideKind.ofMid(null)).contains(ProvideKind.VOD);
        assertThat(ProvideKind.ofMid("01")).contains(ProvideKind.VOD);
        assertThat(ProvideKind.ofMid("2")).contains(ProvideKind.ART);
        assertThat(ProvideKind.ofMid("8")).contains(ProvideKind.ACTOR);
        assertThat(ProvideKind.ofMid("9")).contains(ProvideKind.ROLE);
        assertThat(ProvideKind.ofMid("11.0")).contains(ProvideKind.WEBSITE);
        assertThat(ProvideKind.ofMid("3")).isEmpty();
        assertThat(ProvideKind.ofMid("vod")).isEmpty();
    }
}
