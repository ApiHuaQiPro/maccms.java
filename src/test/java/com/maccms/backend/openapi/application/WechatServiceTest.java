package com.maccms.backend.openapi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.LegacyRowReadService;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.legacyconfig.application.VarExportParser;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** B27 微信公众号接口的单元测试（29 文档第 6 节）；与 PHP 的逐字节对照在 WechatGoldenMySqlIT。 */
class WechatServiceTest {

    private static final String TOKEN = "unit-token";

    private static WechatSettings settings(String weixinOverrides) {
        String php = "<?php return array('weixin' => array('status' => '1', 'token' => '" + TOKEN + "', 'sousuo' => 'wx.example.test',"
                + " 'guanzhu' => '欢迎', 'wuziyuan' => '没找到', 'wuziyuanlink' => 'demo.example.test', 'bofang' => '0',"
                + " 'msgtype' => '0', 'gjc1' => '关键词1', 'gjcm1' => '标题1', 'gjci1' => 'p1', 'gjcl1' => 'l1'"
                + weixinOverrides + "));";
        return WechatSettings.from(VarExportParser.parse(php.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, PhpInput.Value> query(String q) {
        return PhpInput.parseQuery(q);
    }

    private static String sign(String... parts) {
        return WechatService.sha1(String.join("", parts));
    }

    private static WechatService service(WechatSettings s, ContentReadService content) {
        return new WechatService(() -> s, content, mock(LegacyRowReadService.class),
                Clock.fixed(Instant.ofEpochSecond(1700000000), ZoneOffset.UTC));
    }

    private static String handle(WechatService svc, String q, String body) {
        return new String(svc.handle(query(q), body.getBytes(StandardCharsets.UTF_8)).body(), StandardCharsets.UTF_8);
    }

    @Test
    void handshakeAcceptsLegacyAndDictionaryOrderAndEscapesEchostr() {
        WechatSettings s = settings("");
        // 时间戳 10 位、随机数 9 位：PHP sort() 按数值排序（随机数在前），微信文档按字典序（时间戳在前）
        String ts = "1700000000";
        String nonce = "987654321";
        String legacy = sign(nonce, ts, TOKEN);
        String dictionary = sign(ts, nonce, TOKEN);
        assertThat(legacy).isNotEqualTo(dictionary);
        String echo = "&echostr=%3Cb%3Ea%3C%2Fb%3E%22%27%26";
        assertThat(WechatService.valid(s, query("signature=" + legacy + "&timestamp=" + ts + "&nonce=" + nonce + echo)))
                .isEqualTo("a&quot;&#039;&amp;");
        assertThat(WechatService.valid(s, query("signature=" + dictionary + "&timestamp=" + ts + "&nonce=" + nonce + echo)))
                .isEqualTo("a&quot;&#039;&amp;");
    }

    @Test
    void handshakeRejectsWrongMissingUppercaseAndArraySignatures() {
        WechatSettings s = settings("");
        String ok = sign("1700000000", "n0nce", TOKEN);
        assertThat(WechatService.valid(s, query("signature=" + ok + "&timestamp=1700000000&nonce=n0nce&echostr=e")))
                .isEqualTo("e");
        assertThat(WechatService.valid(s, query("signature=" + ok.toUpperCase() + "&timestamp=1700000000&nonce=n0nce&echostr=e")))
                .isEmpty();
        assertThat(WechatService.valid(s, query("signature=0&timestamp=1700000000&nonce=n0nce&echostr=e"))).isEmpty();
        assertThat(WechatService.valid(s, query("echostr=e"))).isEmpty();
        assertThat(WechatService.valid(s, query("signature=" + ok + "&nonce=n0nce&echostr=e"))).isEmpty();
        assertThat(WechatService.valid(s, query("signature[]=" + ok + "&timestamp=1700000000&nonce=n0nce&echostr=e")))
                .isEmpty();
        assertThat(WechatService.valid(s, query("signature=" + ok + "&timestamp=1700000000&nonce=n0nce&echostr[]=e")))
                .isEmpty();
    }

    @Test
    void comparesSignatureByBytesNotAsPhpNumbers() {
        // 旧版 == 把两个"0e+数字"串按数值比较（都等于 0）；新版逐字节比较，只有算出的签名本身才通过
        WechatSettings s = settings("");
        assertThat(WechatService.valid(s, query("signature=0e1&timestamp=1&nonce=2&echostr=e"))).isEmpty();
        assertThat(WechatService.valid(s, query("signature=00&timestamp=1&nonce=2&echostr=e"))).isEmpty();
    }

    @Test
    void closedEmptyBodyAndMalformedXml() {
        assertThat(handle(service(settings(""), mock(ContentReadService.class)), "", "")).isEqualTo("other msg");
        assertThat(handle(service(settings(""), mock(ContentReadService.class)), "", "0")).isEqualTo("other msg");
        assertThat(handle(service(settings(""), mock(ContentReadService.class)), "", "<xml>")).isEqualTo("unknow msg type: ");
        assertThat(handle(service(settings(", 'status' => '0'"), mock(ContentReadService.class)), "echostr=1", ""))
                .isEqualTo("closed");
        assertThat(handle(service(WechatSettings.closed(), mock(ContentReadService.class)), "", "<xml/>")).isEqualTo("closed");
    }

    @Test
    void keywordReplyDoesNotSearch() {
        ContentReadService content = mock(ContentReadService.class);
        String out = handle(service(settings(""), content), "", "<xml><ToUserName>gh</ToUserName><FromUserName>u</FromUserName>"
                + "<MsgType>text</MsgType><Content>我要关键词1</Content></xml>");
        assertThat(out).contains("<Title><![CDATA[标题1]]></Title>").contains("<Url><![CDATA[l1]]></Url>")
                .contains("<CreateTime>1700000000</CreateTime>").contains("<ToUserName><![CDATA[u]]></ToUserName>");
        verify(content, never()).listVideos(any());
    }

    @Test
    void searchDisabledOrRejectedKeywordRepliesNotFound() {
        ContentReadService content = mock(ContentReadService.class);
        when(content.listVideos(any())).thenThrow(ApiException.invalidParameter("keyword", "当前未开放关键字搜索"));
        String out = handle(service(settings(""), content), "", "<xml><MsgType>text</MsgType><Content>星际</Content></xml>");
        assertThat(out).contains("<Title><![CDATA[没找到]]></Title>").contains("<Url><![CDATA[demo.example.test]]></Url>");
        ArgumentCaptor<VideoListQuery> q = ArgumentCaptor.forClass(VideoListQuery.class);
        verify(content).listVideos(q.capture());
        assertThat(q.getValue().filter().keyword()).isEqualTo("星际");
        assertThat(q.getValue().pageSize()).isEqualTo(1);
    }

    @Test
    void searchUnavailablePropagates() {
        ContentReadService content = mock(ContentReadService.class);
        when(content.listVideos(any())).thenThrow(new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "服务暂时不可用"));
        WechatService svc = service(settings(", 'msgtype' => '1'"), content);
        assertThatThrownBy(() -> handle(svc, "", "<xml><MsgType>text</MsgType><Content>星际</Content></xml>"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void unsupportedMessageTypesAreLegacyFatal() {
        WechatService svc = service(settings(""), mock(ContentReadService.class));
        for (String t : new String[]{"image", "voice", "location", "video"}) {
            WechatService.Result r = svc.handle(Map.of(), ("<xml><MsgType>" + t + "</MsgType></xml>")
                    .getBytes(StandardCharsets.UTF_8));
            assertThat(r.status()).isEqualTo(500);
            assertThat(r.body()).isEmpty();
        }
    }

    @Test
    void imageUrlFollowsMacUrlImg() {
        WechatSettings s = settings("");
        assertThat(WechatService.urlImg(s, "mac://img.example.test/a.jpg")).isEqualTo("http://img.example.test/a.jpg");
        assertThat(WechatService.urlImg(s, "http://img.example.test/a.jpg?x=1&y=2")).isEqualTo("http://img.example.test/a.jpg?x=1&y=2");
        assertThat(WechatService.urlImg(s, "http://img.example.test/\"a")).isEqualTo("http://img.example.test/&quot;a");
    }

    @Test
    void settingsFollowLegacyComparisonsAndHideToken() {
        assertThat(settings("").open()).isTrue();
        assertThat(settings(", 'status' => 'abc'").open()).isFalse();
        assertThat(settings(", 'status' => ''").open()).isFalse();
        assertThat(settings(", 'status' => '1abc'").open()).isTrue();
        assertThat(settings("").sousuo()).isEqualTo("http://wx.example.test");
        assertThat(settings(", 'sousuo' => 'https://a.example.test'").sousuo()).isEqualTo("https://a.example.test");
        assertThat(settings("").toString()).doesNotContain(TOKEN);
        assertThat(WechatSettings.closed().open()).isFalse();
        assertThat(WechatPhp.number("2")).isEqualTo(2.0);
        assertThat(WechatPhp.number(" 1e1x")).isEqualTo(10.0);
        assertThat(WechatPhp.compareRegular("9", "10")).isNegative();
        assertThat(WechatPhp.compareBytes("9", "10")).isPositive();
    }
}
