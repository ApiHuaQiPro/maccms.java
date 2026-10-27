package com.maccms.backend.admin.web;

import com.maccms.backend.admin.application.AdminMenus;
import com.maccms.backend.admin.application.AdminPermissions;
import com.maccms.backend.admin.application.AdminSettings;
import com.maccms.backend.admin.application.view.AdminFormData;
import com.maccms.backend.admin.application.view.AdminListPage;
import com.maccms.backend.admin.application.view.AdminListRow;
import com.maccms.backend.admin.application.view.AdminPrincipal;
import com.maccms.backend.admin.web.AdminController.Ctx;
import com.maccms.backend.admin.web.view.AdminHead;
import com.maccms.backend.admin.web.view.AdminInfoView;
import com.maccms.backend.admin.web.view.AdminListView;
import com.maccms.backend.admin.web.view.FramePage;
import com.maccms.backend.admin.web.view.LoginPage;
import com.maccms.backend.admin.web.view.QuickmenuPage;
import com.maccms.backend.admin.web.view.WelcomePage;
import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.legacyconfig.application.LegacyConfigException;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 各后台页面的视图模型（旧 admin/view 下的模板变量）与渲染。 */
public class AdminPages {

    /** 旧 {@code default_timezone = PRC}。 */
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Logger log = LoggerFactory.getLogger(AdminPages.class);

    private final AdminEndpoint endpoint;
    private final AdminSettings settings;
    private final AdminResponses responses;
    private final LegacyConfigService legacy;
    private final Clock clock;
    private final String maxUpload;
    private java.util.function.Supplier<String> loginBackground = () -> null;
    private java.util.function.Supplier<String> notice = () -> null;
    private boolean externalImages = true;

    /**
     * @param legacy    旧配置（快捷菜单按修改时间热加载）；未配置时快捷菜单为空
     * @param maxUpload 欢迎页"最大上传限制"显示的值（本应用的 {@code spring.servlet.multipart.max-file-size}）
     */
    public AdminPages(AdminEndpoint endpoint, AdminSettings settings, AdminResponses responses, LegacyConfigService legacy,
                      Clock clock, String maxUpload) {
        this.endpoint = endpoint;
        this.settings = settings;
        this.responses = responses;
        this.legacy = legacy;
        this.clock = clock;
        this.maxUpload = maxUpload;
    }

    /**
     * B15：登录页背景（{@code admin_login_init} 钩子，{@code AddonService#loginBackground}）与浏览器侧外部图片放行（W2，部署参数
     * {@code maccms.browser-external.w2}；关闭时外部主机的背景图不输出）。
     */
    /** B15：欢迎页的系统提示（模板在线编辑启动回退，{@code SiteTemplateService#notice}）。 */
    public void notice(java.util.function.Supplier<String> notice) {
        this.notice = notice;
    }

    public void loginBackground(java.util.function.Supplier<String> background, boolean externalImages) {
        this.loginBackground = background;
        this.externalImages = externalImages;
    }

    private AdminHead head(String titleKey) {
        return new AdminHead(settings.lang().get(titleKey), endpoint.sitePath(), endpoint.root(), settings.versionCode());
    }

    /** 登录页（旧 Index::login 的 GET）。 */
    ResponseEntity<String> login(Ctx ctx) {
        String bg = loginBackground.get();
        String origin = AdminResponses.externalOrigin(bg);
        if (origin != null && !externalImages) {
            bg = null;
            origin = null;
        }
        LoginPage page = new LoginPage(head("admin/index/login/title"), settings.lang(), settings.loginVerify(),
                ctx.url().build("index/login"), ctx.url().build("index/index"),
                endpoint.root() + "/admin/index/verify.html", bg == null ? "" : bg);
        return origin == null ? responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesLogin(page))
                : responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesLogin(page), origin);
    }

    /** 框架页（旧 Index::index）。 */
    ResponseEntity<String> frame(Ctx ctx, AdminPrincipal admin) {
        FramePage page = new FramePage(head("admin/index/title"), settings.lang(),
                AdminMenus.build(admin, settings.lang(), quickmenuLines(), ctx.url()),
                ctx.url().build("index/clear"), ctx.url().build("index/logout"), ctx.url().build("index/welcome"),
                ctx.url().build("checkcache"));
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesIndex(page));
    }

    /** 欢迎页（旧 Index::welcome）。 */
    ResponseEntity<String> welcome(Ctx ctx, AdminPrincipal admin) {
        String host = ctx.request().getHeader(HttpHeaders.HOST);
        WelcomePage page = new WelcomePage(head("admin/index/welcome/title"), settings.lang(), admin.loginNum(),
                long2ip(admin.lastLoginIp()), macDay(admin.lastLoginTime()), System.getProperty("os.name"),
                ctx.request().getServletContext().getServerInfo(), host == null ? "" : host, maxUpload,
                LocalDate.now(clock.withZone(ZONE)).format(DATE), java.util.Objects.requireNonNullElse(notice.get(), ""));
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesWelcome(page));
    }

    /** 快捷菜单配置页（旧 Index::quickmenu 的 GET）。 */
    ResponseEntity<String> quickmenu(Ctx ctx, String token) {
        QuickmenuPage page = new QuickmenuPage(head("admin/index/quickmenu/title"), settings.lang(), token,
                String.join("\r", quickmenuLines()));
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesQuickmenu(page));
    }

    /** 管理员列表（旧 Admin::index）。 */
    ResponseEntity<String> adminList(Ctx ctx, AdminPrincipal admin, AdminListPage list) {
        String today = LocalDate.now(clock.withZone(ZONE)).format(DATE);
        List<AdminListView.Item> rows = new ArrayList<>();
        for (AdminListRow r : list.rows()) {
            String time = macDay(r.lastLoginTime());
            rows.add(new AdminListView.Item(r.adminId(), r.adminName(), r.adminStatus(), time,
                    !time.isEmpty() && time.contains(today), long2ip(r.lastLoginIp()), r.loginNum(),
                    r.adminId() == admin.adminId(),
                    ctx.url().build("field?col=admin_status&ids=" + r.adminId()),
                    ctx.url().build("info?id=" + r.adminId()), ctx.url().build("del?ids=" + r.adminId())));
        }
        AdminListView page = new AdminListView(head("admin/admin/title"), settings.lang(), List.copyOf(rows), list.total(),
                list.limit(), list.page(), ctx.url().build("admin/index", pagingVars(ctx.params(), list)),
                ctx.url().build("info"), ctx.url().build("del"));
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesAdminList(page));
    }

    /** 管理员表单（旧 Admin::info 的 GET）。 */
    ResponseEntity<String> adminInfo(Ctx ctx, AdminFormData form, String token) {
        String auth = "," + (form == null || form.adminAuth() == null ? "" : form.adminAuth());
        List<AdminInfoView.Group> groups = new ArrayList<>();
        int k1 = 0;
        for (AdminPermissions.Group g : AdminPermissions.groups()) {
            k1++;
            List<AdminInfoView.Item> items = new ArrayList<>();
            boolean all = true;
            for (AdminPermissions.Entry e : g.entries()) {
                boolean hit = auth.indexOf(e.code()) > 0;
                all &= hit;
                boolean welcome = e.key() == 11;
                items.add(new AdminInfoView.Item(e.code(), e.name().resolve(settings.lang()::get), hit || welcome, welcome));
            }
            groups.add(new AdminInfoView.Group(k1, g.name().resolve(settings.lang()::get), all, List.copyOf(items)));
        }
        AdminInfoView page = new AdminInfoView(head("admin/admin/title"), settings.lang(),
                form == null ? "" : String.valueOf(form.adminId()), form == null ? "" : form.adminName(),
                form != null && form.adminStatus() == 1, token, List.copyOf(groups));
        return responses.page(ctx.ajax(), HttpStatus.OK, t -> t.adminPagesAdminInfo(page));
    }

    /**
     * 分页地址的参数（旧 {@code $param}：{@code input()} 的全部标量参数按出现顺序，{@code wd} 换成处理后的值，{@code page}、{@code limit}
     * 换成占位；没有时依次追加在末尾）。
     */
    static Map<String, String> pagingVars(AdminParams params, AdminListPage list) {
        Map<String, String> vars = new LinkedHashMap<>();
        for (Map.Entry<String, AdminParams.Value> e : params.values().entrySet()) {
            if (!e.getValue().array()) {
                vars.put(e.getKey(), new String(e.getValue().scalar(), StandardCharsets.UTF_8));
            }
        }
        if (list.wd() != null) {
            vars.put("wd", list.wd());
        }
        vars.put("page", "{page}");
        vars.put("limit", "{limit}");
        return vars;
    }

    /** 快捷菜单的各行（旧 {@code config('quickmenu')} 的值，按数组顺序）。 */
    List<String> quickmenuLines() {
        if (legacy == null || !legacy.isConfigured()) {
            return List.of();
        }
        PhpArray menu;
        try {
            menu = legacy.read(LegacyConfigFile.QUICKMENU);
        } catch (LegacyConfigException e) {
            if (e.reason() != LegacyConfigException.Reason.NOT_FOUND) {
                log.warn("legacy quickmenu unreadable, treated as empty: {}", SafeExceptionLog.typeChain(e));
            }
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (PhpValue v : menu.entries().values()) {
            out.add(PhpValues.text(v));
        }
        return out;
    }

    /** 旧 {@code mac_day($t)}（application/common.php:238-249）的时间文字部分：0 为空串，否则 {@code Y-m-d H:i:s}（PRC）。 */
    String macDay(long t) {
        return t == 0 ? "" : DATE_TIME.format(Instant.ofEpochSecond(t).atZone(ZONE));
    }

    /** PHP {@code long2ip}。 */
    static String long2ip(long v) {
        return ((v >> 24) & 0xFF) + "." + ((v >> 16) & 0xFF) + "." + ((v >> 8) & 0xFF) + "." + (v & 0xFF);
    }
}
