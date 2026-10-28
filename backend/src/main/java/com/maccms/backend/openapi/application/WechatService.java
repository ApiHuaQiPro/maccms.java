package com.maccms.backend.openapi.application;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.LegacyColumnSet;
import com.maccms.backend.content.application.LegacyRowOrder;
import com.maccms.backend.content.application.LegacyRowQuery;
import com.maccms.backend.content.application.LegacyRowReadService;
import com.maccms.backend.content.application.LegacyTable;
import com.maccms.backend.content.application.PhpText;
import com.maccms.backend.content.application.VideoFilter;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.content.application.VideoSort;
import com.maccms.backend.content.application.view.LegacyRowDto;
import com.maccms.backend.content.application.view.VideoSummaryDto;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 微信公众号入站接口（B27，E12，docs/architecture/29-b27-wechat.md）：等价旧 api/controller/Wechat.php:14-28 与
 * common/util/WechatPublic.php。只读：不写库、不发出站请求。
 *
 * <ul>
 * <li>{@code weixin.status} 为 0 → {@code closed}。</li>
 * <li>查询串带 {@code echostr} → 接入验证（{@link #valid}）；否则读正文回复消息（{@link #reply}）。消息回复不校验签名（与旧版相同）。</li>
 * <li>文本消息先按 4 组关键词匹配，没命中就按关键词搜索视频（与旧 {@code Vod::listCacheData(['wd'=>..,'num'=>..])} 相同的条件与排序，
 * 经 content 模块的读取服务与搜索护栏）。</li>
 * </ul>
 *
 * <p>回复模板的空白与旧文件相同，换行按仓库保存的 LF（旧站检出为 CRLF 时字节不同，见 29 文档 D-B27-1）。
 */
public class WechatService {

    /** 旧版找不到处理方法（图片、语音、位置、视频消息）时整页 500，新版同样返回 500，正文为空（D-B27-4）。 */
    public static final int LEGACY_FATAL = 500;

    private static final String TEXT_TPL = """
            <xml>
                        <ToUserName><![CDATA[%s]]></ToUserName>
                        <FromUserName><![CDATA[%s]]></FromUserName>
                        <CreateTime>%s</CreateTime>
                        <MsgType><![CDATA[text]]></MsgType>
                        <Content><![CDATA[%s]]></Content>
                        </xml>""";
    private static final String ITEM_TPL = """
            <item>
                    <Title><![CDATA[%s]]></Title>
                    <Description><![CDATA[%s]]></Description>
                    <PicUrl><![CDATA[%s]]></PicUrl>
                    <Url><![CDATA[%s]]></Url>
                    </item>""";
    private static final String NEWS_TPL = """
            <xml>
                    <ToUserName><![CDATA[%s]]></ToUserName>
                    <FromUserName><![CDATA[%s]]></FromUserName>
                    <CreateTime>%s</CreateTime>
                    <MsgType><![CDATA[news]]></MsgType>
                    <ArticleCount>%s</ArticleCount>
                    <Articles>%s</Articles>
                    </xml>""";
    private static final String SITE_PATH = "/";

    private final WechatSettingsSource settings;
    private final ContentReadService content;
    private final LegacyRowReadService rows;
    private final Clock clock;

    public WechatService(WechatSettingsSource settings, ContentReadService content, LegacyRowReadService rows, Clock clock) {
        this.settings = settings;
        this.content = content;
        this.rows = rows;
        this.clock = clock;
    }

    /** 处理结果：状态码与正文（{@code text/html; charset=utf-8}）。 */
    public record Result(int status, byte[] body) {

        static Result ok(String text) {
            return new Result(200, text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * @param query PHP {@code $_GET}
     * @param body  原始正文（{@code php://input}）
     */
    public Result handle(Map<String, PhpInput.Value> query, byte[] body) {
        WechatSettings s = settings.get();
        if (!s.open()) {
            return Result.ok("closed");
        }
        if (query.containsKey("echostr")) {
            return Result.ok(valid(s, query));
        }
        // empty($postStr)：空串与 "0"
        if (body.length == 0 || (body.length == 1 && body[0] == '0')) {
            return Result.ok("other msg");
        }
        return reply(s, body);
    }

    // ------------------------------------------------------------------ 接入验证

    /**
     * WechatPublic::valid/checkSignature：签名通过时输出 {@code htmlspecialchars(strip_tags($echostr), ENT_QUOTES)}，否则输出空串。
     * 签名按 {@code sha1(implode(sort([token, timestamp, nonce])))} 计算，排序既接受旧代码的 {@code sort()} 默认规则，也接受微信
     * 文档的字典序（D-B27-2）；比较用逐字节相等（D-B27-2）。
     */
    static String valid(WechatSettings s, Map<String, PhpInput.Value> query) {
        Optional<String> signature = scalar(query, "signature");
        Optional<String> timestamp = scalar(query, "timestamp");
        Optional<String> nonce = scalar(query, "nonce");
        if (signature.isEmpty() || timestamp.isEmpty() || nonce.isEmpty()) {
            return "";
        }
        String sig = signature.get();
        String[] parts = {s.token(), timestamp.get(), nonce.get()};
        String[] legacy = parts.clone();
        Arrays.sort(legacy, WechatPhp::compareRegular);
        String[] dictionary = parts.clone();
        Arrays.sort(dictionary, WechatPhp::compareBytes);
        if (!sig.equals(sha1(String.join("", legacy))) && !sig.equals(sha1(String.join("", dictionary)))) {
            return "";
        }
        PhpInput.Value echostr = query.get("echostr");
        if (echostr.array()) {
            return "";
        }
        return new String(PhpText.htmlspecialchars(PhpText.stripTags(echostr.bytes())), StandardCharsets.UTF_8);
    }

    private static Optional<String> scalar(Map<String, PhpInput.Value> query, String name) {
        PhpInput.Value v = query.get(name);
        if (v == null || v.array()) {
            return Optional.empty();
        }
        return Optional.of(new String(v.bytes(), StandardCharsets.UTF_8));
    }

    static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ 消息回复

    private Result reply(WechatSettings s, byte[] body) {
        Optional<WechatMessage> parsed = WechatMessage.parse(body);
        if (parsed.isEmpty()) {
            // 旧版 simplexml_load_string 失败得到 false，trim(false->MsgType) 为空串
            return Result.ok("unknow msg type: ");
        }
        WechatMessage m = parsed.get();
        String type = phpTrim(m.field("MsgType"));
        return switch (type) {
            case "text" -> Result.ok(receiveText(s, m));
            case "link" -> Result.ok(transmitText(m, "你发送的是链接已收到，请等待处理"));
            case "event" -> Result.ok(receiveEvent(s, m));
            case "image", "location", "voice", "video" -> new Result(LEGACY_FATAL, new byte[0]);
            default -> Result.ok("unknow msg type: " + type);
        };
    }

    private String receiveEvent(WechatSettings s, WechatMessage m) {
        String event = m.field("Event");
        String msg = switch (event) {
            case "subscribe" -> s.guanzhu();
            case "unsubscribe" -> "拜拜了您内~";
            // 旧代码把"你点击了: "写进 $res 后又被 transmitText 的结果覆盖，回复的内容为空
            case "CLICK" -> "";
            default -> "receive a new event: " + event;
        };
        return transmitText(m, msg);
    }

    private String receiveText(WechatSettings s, WechatMessage m) {
        String content = phpTrim(m.field("Content"));
        StringBuilder txt = new StringBuilder("请点击下方链接：\n");
        List<String[]> data = new ArrayList<>();
        boolean news = WechatPhp.number(s.msgtype()) != 1;
        WechatSettings.Keyword hit = null;
        for (WechatSettings.Keyword k : s.keywords()) {
            if (!k.word().isEmpty() && content.contains(k.word())) {
                hit = k;
                break;
            }
        }
        if (hit != null) {
            txt.append("<a href=\"").append(hit.link()).append("\">").append(hit.title()).append("</a>\n");
            data.add(new String[]{hit.title(), "", hit.pic(), hit.link()});
        } else {
            List<LegacyRowDto> list = search(content, news ? 1 : 7);
            if (list.isEmpty()) {
                txt.append("<a href=\"").append(s.wuziyuanlink()).append("\">").append(s.wuziyuan()).append("</a>\n");
                data.add(new String[]{s.wuziyuan(), "", "", s.wuziyuanlink()});
            } else if (WechatPhp.number(s.bofang()) == 2) {
                String url = s.sousuo() + searchPath(s, content);
                txt.append("<a href=\"").append(url).append("\">点击进入搜索页面查看</a>\n");
                data.add(new String[]{"点击进入搜索页面查看", "恭喜您找到了相关资源，由于微信限制请进入搜索页查看", "", url});
            } else {
                LegacyUrlBuilder urls = new LegacyUrlBuilder(s.urlStyle());
                boolean play = WechatPhp.number(s.bofang()) > 0;
                for (int k = 0; k < list.size(); k++) {
                    LegacyRowDto v = list.get(k);
                    long id = v.number("vod_id");
                    String url = s.sousuo() + SITE_PATH + (play ? urls.vodPlay(id, 1, 1) : urls.vodDetail(id));
                    String pic = v.string("vod_pic");
                    String picUrl = pic.startsWith("http") || pic.startsWith("mac:") ? urlImg(s, pic) : s.sousuo() + "/" + pic;
                    txt.append("<a href=\"").append(url).append("\">").append(k + 1).append(',').append(v.string("vod_name"))
                            .append(' ').append(v.string("vod_remarks")).append("</a>\n");
                    String desc = new String(PhpText.macSubstring(PhpText.stripTags(
                            v.string("vod_content").getBytes(StandardCharsets.UTF_8)), 20), StandardCharsets.UTF_8);
                    data.add(new String[]{v.string("vod_name"), desc, picUrl, url});
                }
            }
        }
        return news ? transmitNews(m, data) : transmitText(m, txt.toString());
    }

    /**
     * 旧 {@code model('Vod')->listCacheData(['wd'=>$content,'num'=>$num])}：已审核、关键字在名称/英文名/副名中包含匹配（关键字为空时
     * 不加条件），按 vod_time 倒序取前 num 条。条件与排序经 content 模块（可见分类、字面匹配，D-B27-5），再按编号取整行。
     * 搜索开关关闭、关键字不被搜索接受（超长等）时视为没有结果；数据库或搜索并发不可用时抛出（503）。
     */
    private List<LegacyRowDto> search(String wd, int num) {
        List<VideoSummaryDto> found;
        try {
            VideoFilter filter = wd.isEmpty() ? VideoFilter.NONE : new VideoFilter(wd, null, null, null, null, null);
            found = content.listVideos(new VideoListQuery(null, VideoSort.TIME, true, 1, num, filter)).items();
        } catch (ApiException e) {
            if (e.code() == ErrorCode.INVALID_PARAMETER) {
                return List.of();
            }
            throw e;
        }
        if (found.isEmpty()) {
            return List.of();
        }
        List<String> ids = found.stream().map(v -> Long.toString(v.id())).toList();
        Map<String, LegacyRowDto> byId = new HashMap<>();
        for (LegacyRowDto r : rows.read(new LegacyRowQuery(LegacyTable.VOD, LegacyColumnSet.FULL, LegacyRowOrder.TIME_DESC,
                ids, null, null, null, null, null, null, null, null, null, null, null, 0, ids.size())).rows()) {
            byId.put(Long.toString(r.number("vod_id")), r);
        }
        List<LegacyRowDto> out = new ArrayList<>();
        for (String id : ids) {
            LegacyRowDto r = byId.get(id);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    /** {@code mac_url('vod/search', ['wd'=>$content])}；关键字生成不了合法路径时退回不带关键字的搜索页（D-B27-6）。 */
    private static String searchPath(WechatSettings s, String wd) {
        LegacyUrlBuilder urls = new LegacyUrlBuilder(s.urlStyle());
        try {
            return SITE_PATH + urls.vodSearch(wd.isEmpty() ? Map.of() : Map.of("wd", wd), 1);
        } catch (IllegalArgumentException e) {
            return SITE_PATH + urls.vodSearch(Map.of(), 1);
        }
    }

    /**
     * 旧 {@code mac_url_img($url)}（application/common.php:1633-1657）在这里只会收到以 http 或 mac: 开头的地址：{@code mac:} 换成
     * {@code upload.protocol}（空时 http）；否则匹配 {@code upload.img_key} 时前面加 {@code upload.img_api}；最后经
     * {@code mac_filter_xss}，去掉 {@code &quot;&gt;}，{@code &amp;} 换回 {@code &}。
     */
    static String urlImg(WechatSettings s, String url) {
        String u = url;
        if (u.startsWith("mac:")) {
            String p = s.uploadProtocol().isEmpty() ? "http" : s.uploadProtocol();
            u = u.replace("mac:", p + ":");
        } else if (!s.imgKey().isEmpty() && imgKeyMatches(s.imgKey(), u)) {
            u = s.imgApi() + u;
        }
        String f = new String(PhpText.filterXss(u.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        return f.replace("&quot;&gt;", "").replace("&amp;", "&");
    }

    /** 旧版 {@code preg_match('/'.$img_key.'/', $url)}；正则不合法时旧版不匹配（PHP 警告后返回 false）。 */
    private static boolean imgKeyMatches(String key, String url) {
        try {
            return Pattern.compile(key).matcher(url).find();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    private String transmitText(WechatMessage m, String content) {
        return TEXT_TPL.formatted(m.field("FromUserName"), m.field("ToUserName"), now(), content);
    }

    private String transmitNews(WechatMessage m, List<String[]> items) {
        StringBuilder sb = new StringBuilder();
        for (String[] i : items) {
            sb.append(ITEM_TPL.formatted(i[0], i[1], i[2], i[3]));
        }
        return NEWS_TPL.formatted(m.field("FromUserName"), m.field("ToUserName"), now(), items.size(), sb);
    }

    private String now() {
        return Long.toString(clock.instant().getEpochSecond());
    }

    /** PHP {@code trim()}：两端去掉空格、\t、\n、\r、\0、\x0B。 */
    static String phpTrim(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && isTrim(s.charAt(a))) {
            a++;
        }
        while (b > a && isTrim(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(a, b);
    }

    private static boolean isTrim(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0 || c == 0x0B;
    }
}
