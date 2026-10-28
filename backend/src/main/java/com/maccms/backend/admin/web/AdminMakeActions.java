package com.maccms.backend.admin.web;

import com.maccms.backend.admin.application.AdminSettings;
import com.maccms.backend.admin.application.LegacyAdminUrl;
import com.maccms.backend.admin.web.AdminController.Ctx;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.JumpPage;
import com.maccms.backend.admin.web.view.MakeOptView;
import com.maccms.backend.content.application.StaticSourceService;
import com.maccms.backend.content.application.view.StaticSourceTopic;
import com.maccms.backend.content.application.view.StaticSourceType;
import com.maccms.backend.staticgen.application.MakeOutput;
import com.maccms.backend.staticgen.application.MakeService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * B16 生成静态的后台动作（旧 admin/controller/Make.php，25 文档第 8 节）：{@code make/opt} 页面与 {@code make/make}、{@code make/index}
 * （含 {@code ?ac2=wap}）、{@code make/map}、{@code make/rss} 等。生成本身在 {@link MakeService}（后台入口）；本类把它的输出按旧
 * {@code echo} + {@code mac_jump} 的样子输出（跳转改为 progress.js，同 B13 批量处理，22 文档第 8.4 节）。
 */
public class AdminMakeActions {

    static final Set<String> ACTIONS = Set.of("make", "index", "map", "rss", "type", "topic_index", "topic_info", "info",
            "label");

    private final AdminEndpoint endpoint;
    private final AdminSettings settings;
    private final AdminResponses responses;
    private final ObjectProvider<MakeService> make;
    private final ObjectProvider<StaticSourceService> sources;
    private final Clock clock;
    private final ZoneId zone;

    public AdminMakeActions(AdminEndpoint endpoint, AdminSettings settings, AdminResponses responses, ObjectProvider<MakeService> make,
                            ObjectProvider<StaticSourceService> sources, Clock clock, ZoneId zone) {
        this.endpoint = endpoint;
        this.settings = settings;
        this.responses = responses;
        this.make = make;
        this.sources = sources;
        this.clock = clock;
        this.zone = zone;
    }

    /** 处理本类的动作；不是本类的动作（或没有装配静态生成）时返回 null。 */
    ResponseEntity<String> handle(Ctx ctx) {
        MakeService service = make.getIfAvailable();
        if (!ctx.route().controller().equals("make") || service == null) {
            return null;
        }
        String action = ctx.route().action();
        if (action.equals("opt")) {
            return opt(ctx);
        }
        if (!ACTIONS.contains(action)) {
            return null;
        }
        String referer = ctx.request().getHeader("Referer");
        MakeOutput o = service.run(action, params(ctx), MakeService.Entrance.ADMIN, referer);
        return output(ctx, o);
    }

    /** 旧 Make::opt（Make.php:57-124）。 */
    private ResponseEntity<String> opt(Ctx ctx) {
        StaticSourceService source = sources.getIfAvailable();
        List<MakeOptView.Option> vod = new ArrayList<>();
        List<MakeOptView.Option> art = new ArrayList<>();
        List<String> vodIds = new ArrayList<>();
        List<String> artIds = new ArrayList<>();
        List<MakeOptView.Option> topics = new ArrayList<>();
        List<String> topicIds = new ArrayList<>();
        String vodToday = "";
        String artToday = "";
        if (source != null) {
            for (StaticSourceType t : source.types()) {
                if (t.mid() == 1) {
                    vod.add(new MakeOptView.Option(Long.toString(t.id()), t.name()));
                    vodIds.add(Long.toString(t.id()));
                } else if (t.mid() == 2) {
                    art.add(new MakeOptView.Option(Long.toString(t.id()), t.name()));
                    artIds.add(Long.toString(t.id()));
                }
            }
            long today = LocalDate.now(clock.withZone(zone)).atStartOfDay(zone).toEpochSecond();
            vodToday = join(source.typeIdsSince("vod", today));
            artToday = join(source.typeIdsSince("art", today));
            for (StaticSourceTopic t : source.topics()) {
                topics.add(new MakeOptView.Option(Long.toString(t.id()), t.name()));
                topicIds.add(Long.toString(t.id()));
            }
        }
        // 自定义页：旧版列出模板 label 目录下的文件；新版没有内置标签页（16 文档第 4.3 节），列表为空
        AdminHead head = new AdminHead(settings.lang().get("admin/make/title"), endpoint.sitePath(), endpoint.root(),
                settings.versionCode());
        MakeOptView view = new MakeOptView(head, settings.lang(), ctx.url().build("make/make"), vod, String.join(",", vodIds),
                vodToday, art, String.join(",", artIds), artToday, topics, String.join(",", topicIds), List.of(), "");
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesMakeOpt(view));
    }

    private static String join(List<Long> ids) {
        return String.join(",", ids.stream().map(String::valueOf).toList());
    }

    /** 旧 {@code input()}：标量按 UTF-8；数组（多选的 {@code vodtype[]} 等）按逗号连接（旧代码对两种写法都 {@code explode(',')}）。 */
    static Map<String, String> params(Ctx ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        ctx.params().values().forEach((k, v) -> {
            if (v.array()) {
                List<String> items = new ArrayList<>();
                for (byte[] b : v.items()) {
                    items.add(new String(b, StandardCharsets.UTF_8));
                }
                out.put(k, String.join(",", items));
            } else {
                out.put(k, v.scalar() == null ? "" : new String(v.scalar(), StandardCharsets.UTF_8));
            }
        });
        return out;
    }

    /** 已输出的内容 + 结尾的 {@code error()}（跳转提示页）或 {@code mac_jump}（progress.js）。 */
    private ResponseEntity<String> output(Ctx ctx, MakeOutput o) {
        StringBuilder sb = new StringBuilder(o.html());
        if (o.error() != null) {
            sb.append(ctx.ajax() ? AdminResponses.legacyJson(0, o.error(), "")
                    : responses.jumpHtml(false, o.error(), JumpPage.BACK));
            return responses.echoed(sb.toString());
        }
        if (o.jump() != null) {
            String url;
            if (o.jump().action() == null) {
                // ref=1 跳回来源页（旧版不检查来源）；新版只跳回本后台同源的地址，否则回到生成页
                String ref = o.jump().referer();
                url = ref != null && ref.startsWith(endpoint.origin() + "/") ? ref : ctx.url().build("make/opt");
            } else {
                url = ctx.url().build(o.jump().action());
                if (!o.jump().query().isEmpty()) {
                    url = url + "?" + LegacyAdminUrl.httpBuildQuery(o.jump().query());
                }
            }
            String u = escape(url);
            sb.append("<script src=\"").append(escape(endpoint.sitePath())).append("/admin-assets/progress.js\" data-url=\"")
                    .append(u).append("\" data-seconds=\"").append(o.jump().seconds()).append("\"></script><span>")
                    .append(escape(settings.lang().get("pause"))).append(o.jump().seconds())
                    .append(escape(settings.lang().get("continue_in_second"))).append("  >>>  </span><a href=\"")
                    .append(u).append("\" >").append(escape(settings.lang().get("browser_jump"))).append("</a><br>");
        }
        return responses.echoed(sb.toString());
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
