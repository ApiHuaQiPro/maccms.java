package com.maccms.backend.admin.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * 后台菜单与权限码（B12，docs/architecture/21-b12-admin-framework.md 第 3 节）：逐项照搬 {@code application/admin/common/auth.php}
 * （分组与条目的键、顺序、是否显示、名称、控制器/动作、附加参数），由 {@code AdminPermissionsGoldenTest} 与 PHP 导出的
 * {@code admin/auth-php.json} 逐项比对。权限判定仍用 {@code mac_admin.admin_auth} 的 {@code ,控制器/动作,} 文本语义
 * （{@link AdminAuthorization}），这里只是菜单数据与"每个动作由哪个批次实现"的登记。
 *
 * <p>路线图第 5.2 节"同文件冲突"：B13、B14、B15 都会改后台菜单与权限映射，所以 B12 一次性落地全部权限码，后续批次只在
 * {@code AdminRoutes} 里接上自己的动作，不改这里的菜单数据。
 */
public final class AdminPermissions {

    /** 名称：旧 {@code lang('menu/…')} 的语言包键，或直接写在 auth.php 里的文字（隐藏条目的 {@code --…}）。 */
    public record Name(String langKey, String text) {

        public String resolve(UnaryOperator<String> lang) {
            return langKey != null ? lang.apply(langKey) : text;
        }
    }

    /**
     * 一个条目（auth.php 的 {@code sub} 元素）。
     *
     * @param action 动作原文；{@code make} 的"生成WAP首页"写成 {@code index?ac2=wap}（旧版就是这样）
     * @param param  附加查询串，没有时为空串（旧版 {@code !empty($v2['param'])} 才拼接）
     */
    public record Entry(int key, boolean show, Name name, String controller, String action, String param) {

        /** 权限码 {@code 控制器/动作}（admin_auth 中保存的形式，也是权限表单复选框的值）。 */
        public String code() {
            return controller + "/" + action;
        }
    }

    /** 一个分组（auth.php 的顶层元素）。 */
    public record Group(int key, Name name, String icon, List<Entry> entries) {
    }

    private static final List<Group> GROUPS = List.of(
            group(1, "menu/index", "xe625",
                    entry(11, 1, key("menu/welcome"), "index", "welcome"),
                    entry(12, 1, key("menu/quickmenu"), "index", "quickmenu"),
                    entry(1001, 0, text("--切换布局"), "index", "iframe"),
                    entry(1002, 0, text("--清理缓存"), "index", "clear"),
                    entry(1003, 0, text("--锁屏解锁"), "index", "unlocked"),
                    entry(1004, 0, text("--公共下拉选择框"), "index", "select"),
                    entry(1005, 0, text("--文件上传"), "upload", "upload")),
            group(2, "menu/system", "xe62e",
                    entry(21, 1, key("menu/config"), "system", "config"),
                    entry(210, 1, key("menu/configseo"), "system", "configseo"),
                    entry(211, 1, key("menu/configuser"), "system", "configuser"),
                    entry(212, 1, key("menu/configcomment"), "system", "configcomment"),
                    entry(213, 1, key("menu/configupload"), "system", "configupload"),
                    entry(22, 1, key("menu/configurl"), "system", "configurl"),
                    entry(23, 1, key("menu/configplay"), "system", "configplay"),
                    entry(24, 1, key("menu/configcollect"), "system", "configcollect"),
                    entry(25, 1, key("menu/configinterface"), "system", "configinterface"),
                    entry(26, 1, key("menu/configapi"), "system", "configapi"),
                    entry(27, 1, key("menu/configconnect"), "system", "configconnect"),
                    entry(28, 1, key("menu/configpay"), "system", "configpay"),
                    entry(29, 1, key("menu/configweixin"), "system", "configweixin"),
                    entry(291, 1, key("menu/configemail"), "system", "configemail"),
                    entry(292, 1, key("menu/configsms"), "system", "configsms"),
                    entry(2910, 1, key("menu/timming"), "timming", "index"),
                    entry(2911, 0, text("--定时任务信息维护"), "timming", "info"),
                    entry(2912, 0, text("--定时任务删除"), "timming", "del"),
                    entry(2913, 0, text("--定时任务状态"), "timming", "field"),
                    entry(2920, 1, key("menu/domain"), "domain", "index"),
                    entry(2922, 0, text("--站群删除"), "domain", "del"),
                    entry(2923, 0, text("--站群导出"), "domain", "export"),
                    entry(2924, 0, text("--站群导入"), "domain", "import")),
            group(3, "menu/base", "xe64b",
                    entry(31, 1, key("menu/type"), "type", "index"),
                    entry(3101, 0, text("--分类信息维护"), "type", "info"),
                    entry(3102, 0, text("--分类批量修改"), "type", "batch"),
                    entry(3103, 0, text("--分类删除"), "type", "del"),
                    entry(3104, 0, text("--分类状态"), "type", "field"),
                    entry(3105, 0, text("--分类扩展配置信息"), "type", "extend"),
                    entry(32, 1, key("menu/topic"), "topic", "data"),
                    entry(3201, 0, text("--专题信息维护"), "topic", "info"),
                    entry(3202, 0, text("--专题批量修改"), "topic", "batch"),
                    entry(3203, 0, text("--专题删除"), "topic", "del"),
                    entry(3204, 0, text("--专题状态"), "topic", "field"),
                    entry(33, 1, key("menu/link"), "link", "index"),
                    entry(3301, 0, text("--友链信息维护"), "link", "info"),
                    entry(3302, 0, text("--友链批量修改"), "link", "batch"),
                    entry(3303, 0, text("--友链删除"), "link", "del"),
                    entry(3304, 0, text("--友链状态"), "link", "field"),
                    entry(34, 1, key("menu/gbook"), "gbook", "data"),
                    entry(3401, 0, text("--留言信息维护"), "gbook", "info"),
                    entry(3402, 0, text("--留言删除"), "gbook", "del"),
                    entry(3404, 0, text("--留言状态"), "gbook", "field"),
                    entry(35, 1, key("menu/comment"), "comment", "data"),
                    entry(3501, 0, text("--评论信息维护"), "comment", "info"),
                    entry(3502, 0, text("--评论删除"), "comment", "del"),
                    entry(3504, 0, text("--评论状态"), "comment", "field"),
                    entry(36, 1, key("menu/images"), "annex", "data"),
                    entry(3604, 0, text("--附件文件夹"), "annex", "file"),
                    entry(3605, 0, text("--附件检测"), "annex", "check"),
                    entry(3606, 0, text("--附件数据初始化"), "annex", "init"),
                    entry(3601, 0, text("--附件删除"), "annex", "del"),
                    entry(3602, 0, text("--同步图片选项"), "images", "opt"),
                    entry(3603, 0, text("--同步图片方法"), "images", "sync")),
            group(5, "menu/art", "xe616",
                    entry(51, 1, key("menu/art_data"), "art", "data"),
                    entry(5101, 0, text("--文章信息维护"), "art", "info"),
                    entry(5102, 0, text("--文章删除"), "art", "del"),
                    entry(5103, 0, text("--文章状态"), "art", "field"),
                    entry(52, 1, key("menu/art_add"), "art", "info"),
                    entry(53, 1, key("menu/art_data_lock"), "art", "data", "lock=1"),
                    entry(54, 1, key("menu/art_data_audit"), "art", "data", "status=0"),
                    entry(59, 1, key("menu/art_batch"), "art", "batch"),
                    entry(591, 1, key("menu/art_repeat"), "art", "data", "repeat=1")),
            group(4, "menu/vod", "xe639",
                    entry(41, 1, key("menu/server"), "vodserver", "index"),
                    entry(4101, 0, text("--服务器组信息维护"), "vodserver", "info"),
                    entry(4102, 0, text("--服务器组删除"), "vodserver", "del"),
                    entry(4103, 0, text("--服务器组状态"), "vodserver", "field"),
                    entry(42, 1, key("menu/player"), "vodplayer", "index"),
                    entry(4201, 0, text("--播放器信息维护"), "vodplayer", "info"),
                    entry(4202, 0, text("--播放器删除"), "vodplayer", "del"),
                    entry(4203, 0, text("--播放器组状态"), "vodplayer", "field"),
                    entry(43, 1, key("menu/downer"), "voddowner", "index"),
                    entry(4301, 0, text("--下载器信息维护"), "voddowner", "info"),
                    entry(4302, 0, text("--下载器删除"), "voddowner", "del"),
                    entry(4303, 0, text("--下载器组状态"), "voddowner", "field"),
                    entry(44, 1, key("menu/vod_data"), "vod", "data"),
                    entry(4401, 0, text("--视频信息维护"), "vod", "info"),
                    entry(4402, 0, text("--视频删除"), "vod", "del"),
                    entry(4403, 0, text("--视频状态"), "vod", "field"),
                    entry(45, 1, key("menu/vod_add"), "vod", "info"),
                    entry(46, 1, key("menu/vod_data_url_empty"), "vod", "data", "url=1"),
                    entry(47, 1, key("menu/vod_data_lock"), "vod", "data", "lock=1"),
                    entry(48, 1, key("menu/vod_data_audit"), "vod", "data", "status=0"),
                    entry(481, 1, key("menu/vod_data_points"), "vod", "data", "points=1"),
                    entry(482, 1, key("menu/vod_data_plot"), "vod", "data", "plot=1"),
                    entry(49, 1, key("menu/vod_batch"), "vod", "batch"),
                    entry(491, 1, key("menu/vod_repeat"), "vod", "data", "repeat=1"),
                    entry(495, 1, key("menu/actor"), "actor", "data", ""),
                    entry(4951, 0, text("--演员信息维护"), "actor", "info"),
                    entry(4952, 0, text("--演员删除"), "actor", "del"),
                    entry(4953, 0, text("--演员状态"), "actor", "field"),
                    entry(4954, 0, text("--添加演员"), "actor", "info"),
                    entry(496, 1, key("menu/role"), "role", "data", ""),
                    entry(4961, 0, text("--角色信息维护"), "role", "info"),
                    entry(4962, 0, text("--角色删除"), "role", "del"),
                    entry(4963, 0, text("--角色状态"), "role", "field"),
                    entry(4964, 0, text("--添加角色"), "role", "info")),
            group(12, "menu/website", "xe616",
                    entry(121, 1, key("menu/website_data"), "website", "data"),
                    entry(12101, 0, text("--网址信息维护"), "website", "info"),
                    entry(12102, 0, text("--网址删除"), "website", "del"),
                    entry(12103, 0, text("--网址状态"), "website", "field"),
                    entry(122, 1, key("menu/website_add"), "website", "info"),
                    entry(123, 1, key("menu/website_data_lock"), "website", "data", "lock=1"),
                    entry(124, 1, key("menu/website_data_audit"), "website", "data", "status=0"),
                    entry(129, 1, key("menu/website_batch"), "website", "batch"),
                    entry(1291, 1, key("menu/website_repeat"), "website", "data", "repeat=1")),
            group(6, "menu/users", "xe62c",
                    entry(61, 1, key("menu/admin"), "admin", "index"),
                    entry(6101, 0, text("--管理员信息维护"), "admin", "info"),
                    entry(6102, 0, text("--管理员删除"), "admin", "del"),
                    entry(6103, 0, text("--管理员状态"), "admin", "field"),
                    entry(62, 1, key("menu/group"), "group", "index"),
                    entry(6201, 0, text("--会员组信息维护"), "group", "info"),
                    entry(6202, 0, text("--会员组删除"), "group", "del"),
                    entry(6203, 0, text("--会员组状态"), "group", "field"),
                    entry(63, 1, key("menu/user"), "user", "data"),
                    entry(6301, 0, text("--会员信息维护"), "user", "info"),
                    entry(6302, 0, text("--会员删除"), "user", "del"),
                    entry(6303, 0, text("--会员状态"), "user", "field"),
                    entry(64, 1, key("menu/card"), "card", "index"),
                    entry(6401, 0, text("--充值卡信息维护"), "card", "info"),
                    entry(6402, 0, text("--充值卡删除"), "card", "del"),
                    entry(65, 1, key("menu/order"), "order", "index"),
                    entry(6501, 0, text("--订单删除"), "order", "del"),
                    entry(66, 1, key("menu/ulog"), "ulog", "index"),
                    entry(6601, 0, text("--访问日志删除"), "ulog", "del"),
                    entry(67, 1, key("menu/plog"), "plog", "index"),
                    entry(6701, 0, text("--积分日志删除"), "plog", "del"),
                    entry(68, 1, key("menu/cash"), "cash", "index"),
                    entry(6801, 0, text("--提现删除"), "cash", "del"),
                    entry(6802, 0, text("--提现审核"), "cash", "audit")),
            group(7, "menu/templates", "xe72d",
                    entry(71, 1, key("menu/template"), "template", "index"),
                    entry(7101, 0, text("--模板信息维护"), "template", "info"),
                    entry(7102, 0, text("--模板删除"), "template", "del"),
                    entry(72, 1, key("menu/ads"), "template", "ads", ""),
                    entry(73, 1, key("menu/wizard"), "template", "wizard")),
            group(8, "menu/make", "xe63e",
                    entry(81, 1, key("menu/make_opt"), "make", "opt"),
                    entry(82, 1, key("menu/make_index"), "make", "index"),
                    entry(821, 1, key("menu/make_index_wap"), "make", "index?ac2=wap"),
                    entry(83, 1, key("menu/make_map"), "make", "map"),
                    entry(8101, 0, text("--生成入口"), "make", "make"),
                    entry(8102, 0, text("--生成RSS"), "make", "rss"),
                    entry(8103, 0, text("--生成分类"), "make", "type"),
                    entry(8104, 0, text("--生成专题首页"), "make", "topic_index"),
                    entry(8105, 0, text("--生成专题内容"), "make", "topic_info"),
                    entry(8106, 0, text("--生成内容页"), "make", "info"),
                    entry(8107, 0, text("--生成自定义页"), "make", "label")),
            group(9, "menu/cjs", "xe727",
                    entry(91, 0, key("menu/union"), "collect", "union"),
                    entry(9101, 0, text("--采集入口"), "collect", "api"),
                    entry(9102, 0, text("--断点采集"), "collect", "load"),
                    entry(9103, 0, text("--绑定分类"), "collect", "bind"),
                    entry(9104, 0, text("--采集视频"), "collect", "vod"),
                    entry(9105, 0, text("--采集文章"), "collect", "art"),
                    entry(92, 0, key("menu/collect_timming"), "collect", "timing"),
                    entry(93, 1, key("menu/collect"), "collect", "index"),
                    entry(9301, 0, text("--自定义资源信息维护"), "collect", "info"),
                    entry(9302, 0, text("--自定义资源删除"), "collect", "del"),
                    entry(94, 1, key("menu/cj"), "cj", "index"),
                    entry(9401, 0, text("--自定义规则信息维护"), "cj", "info"),
                    entry(9402, 0, text("--自定义规则删除"), "cj", "del"),
                    entry(9403, 0, text("--自定义规则发布方案"), "cj", "program"),
                    entry(9404, 0, text("--自定义规则采集网址"), "cj", "col_url"),
                    entry(9405, 0, text("--自定义规则采集内容"), "cj", "col_content"),
                    entry(9406, 0, text("--自定义规则发布内容"), "cj", "publish"),
                    entry(9407, 0, text("--自定义规则导出"), "cj", "export"),
                    entry(9408, 0, text("--自定义规则导入"), "cj", "import")),
            group(10, "menu/db", "xe621",
                    entry(101, 1, key("menu/database"), "database", "index"),
                    entry(10001, 0, text("--数据库备份"), "database", "export"),
                    entry(10002, 0, text("--数据库还原"), "database", "import"),
                    entry(10003, 0, text("--数据库优化"), "database", "optimize"),
                    entry(10004, 0, text("--数据库修复"), "database", "repair"),
                    entry(10005, 0, text("--数据库删除备份"), "database", "del"),
                    entry(10006, 0, text("--数据库表信息"), "database", "columns"),
                    entry(102, 1, key("menu/database_sql"), "database", "sql"),
                    entry(103, 1, key("menu/database_rep"), "database", "rep")),
            group(11, "menu/apps", "xe621",
                    entry(111, 1, key("menu/addon"), "addon", "index", ""),
                    entry(112, 1, key("menu/urlsend"), "urlsend", "index", ""),
                    entry(113, 1, key("menu/safety_file"), "safety", "file", ""),
                    entry(114, 1, key("menu/safety_data"), "safety", "data", ""),
                    entry(11200, 0, text("--推送入口"), "urlsend", "push"),
                    entry(11201, 0, text("--百度主动推送"), "urlsend", "baidu_push"),
                    entry(11202, 0, text("--百度熊掌推送"), "urlsend", "baidu_bear"),
                    entry(11100, 0, text("--应用插件列表"), "addon", "downloaded"),
                    entry(11101, 0, text("--应用插件安装"), "addon", "install"),
                    entry(11102, 0, text("--应用插件卸载"), "addon", "uninstall"),
                    entry(11103, 0, text("--应用插件配置"), "addon", "config"),
                    entry(11104, 0, text("--应用插件状态"), "addon", "state"),
                    entry(11105, 0, text("--应用插件上传"), "addon", "local"),
                    entry(11106, 0, text("--应用插件升级"), "addon", "upgrade"),
                    entry(11107, 0, text("--应用插件添加"), "addon", "add")));

    /**
     * 各控制器的实现批次（路线图第 3.2、5.3 节）。B12 实现 {@code index} 与 {@code admin} 两个控制器；例外在 {@link #ACTION_BATCH}。
     */
    private static final Map<String, String> CONTROLLER_BATCH = Map.ofEntries(
            Map.entry("index", "B12"), Map.entry("admin", "B12"),
            Map.entry("upload", "B13"), Map.entry("type", "B13"), Map.entry("topic", "B13"), Map.entry("link", "B13"),
            Map.entry("gbook", "B13"), Map.entry("comment", "B13"), Map.entry("annex", "B13"), Map.entry("images", "B13"),
            Map.entry("art", "B13"), Map.entry("vod", "B13"), Map.entry("actor", "B13"), Map.entry("role", "B13"),
            Map.entry("website", "B13"),
            Map.entry("group", "B14"), Map.entry("user", "B14"), Map.entry("card", "B14"), Map.entry("order", "B14"),
            Map.entry("ulog", "B14"), Map.entry("plog", "B14"), Map.entry("cash", "B14"), Map.entry("visit", "B14"),
            Map.entry("system", "B15"), Map.entry("domain", "B15"), Map.entry("vodserver", "B15"),
            Map.entry("vodplayer", "B15"), Map.entry("voddowner", "B15"), Map.entry("template", "B15"),
            Map.entry("database", "B15"), Map.entry("addon", "B15"), Map.entry("urlsend", "B15"),
            Map.entry("timming", "B15"), Map.entry("make", "B16"),
            Map.entry("collect", "B17"), Map.entry("cj", "B17"));

    /**
     * 按动作覆盖 {@link #CONTROLLER_BATCH}：{@code index/select} 是内容批量操作的弹窗（B13）；{@code index/check_back_link} 是外部回链检测
     * E11（B28）；发信测试 E07（B25）；资源站采集解析 B18、入库 B19；自定义规则的采集与发布 B20；推送执行 E09（B28）；插件市场 E13（B29）。
     * B15 起 {@code timming/*}（定时任务配置页，执行与 runtime 回写仍在 B16）与 {@code addon/downloaded}（本地插件列表，这份生产代码已去掉
     * 市场列表，路线图第 5.7 节）归 B15。
     */
    private static final Map<String, String> ACTION_BATCH = Map.ofEntries(
            Map.entry("index/select", "B13"), Map.entry("index/check_back_link", "B28"),
            Map.entry("system/test_email", "B25"),
            Map.entry("collect/api", "B18"), Map.entry("collect/load", "B18"), Map.entry("collect/vod", "B19"),
            Map.entry("collect/art", "B19"), Map.entry("collect/actor", "B19"), Map.entry("collect/role", "B19"),
            Map.entry("collect/website", "B19"), Map.entry("collect/timing", "B19"),
            Map.entry("cj/program", "B20"), Map.entry("cj/col_url", "B20"), Map.entry("cj/col_content", "B20"),
            Map.entry("cj/publish", "B20"), Map.entry("cj/col_all", "B20"), Map.entry("cj/show", "B20"),
            Map.entry("cj/content_del", "B20"), Map.entry("cj/content_into", "B20"), Map.entry("cj/show_url", "B20"),
            Map.entry("urlsend/push", "B28"), Map.entry("urlsend/data", "B28"),
            Map.entry("addon/install", "B29"), Map.entry("addon/upgrade", "B29"));

    /**
     * 旧控制器中存在、但 auth.php 没有列出的公开动作（小写；Base 的 {@code check_auth}/{@code _cache_clear} 不算动作）。
     * 由 {@code backend/src/test/php/admin_actions_export.php} 从旧控制器源码导出、{@code AdminPermissionsGoldenTest} 核对。
     */
    static final Map<String, List<String>> EXTRA_ACTIONS = Map.ofEntries(
            Map.entry("addon", List.of("info")), Map.entry("annex", List.of("info")),
            Map.entry("art", List.of("updatetoday")),
            Map.entry("cj", List.of("col_all", "show", "content_del", "content_into", "show_url")),
            Map.entry("collect", List.of("test", "clearbind", "actor", "role", "website")),
            Map.entry("images", List.of("data", "del")),
            Map.entry("index", List.of("login", "logout", "index", "checkcache", "check_back_link")),
            Map.entry("system", List.of("test_email", "test_cache")), Map.entry("type", List.of("move")),
            Map.entry("upload", List.of("index", "test")), Map.entry("urlsend", List.of("data")),
            Map.entry("user", List.of("reward")), Map.entry("visit", List.of("index", "del")),
            Map.entry("vod", List.of("iplot", "updatetoday")), Map.entry("vodplayer", List.of("export", "import")),
            Map.entry("website", List.of("updatetoday")));

    /**
     * 旧 admin/controller 下的全部控制器（小写，不含 Base）。{@code update} 与 {@code safety} 是空类：前者不做权限检查
     * （Base.php:33），后者的菜单项（{@code safety/file}、{@code safety/data}）在旧版里没有对应方法。
     */
    static final Set<String> CONTROLLERS = Set.of("actor", "addon", "admin", "annex", "art", "card", "cash", "cj",
            "collect", "comment", "database", "domain", "gbook", "group", "images", "index", "link", "make", "order",
            "plog", "role", "safety", "system", "template", "timming", "topic", "type", "ulog", "update", "upload",
            "urlsend", "user", "visit", "vod", "voddowner", "vodplayer", "vodserver", "website");

    /**
     * auth.php 里有、旧控制器里却没有方法的权限码（菜单或权限树里的死链）：旧版有权限的管理员访问时是"方法不存在"，新版同样 404，
     * 不归任何批次（21 文档第 3 节）。
     */
    static final Set<String> WITHOUT_LEGACY_METHOD = Set.of("topic/batch", "link/field", "safety/file", "safety/data",
            "urlsend/baidu_push", "urlsend/baidu_bear");

    private AdminPermissions() {
    }

    /** auth.php 的全部分组（按旧数组的顺序）。 */
    public static List<Group> groups() {
        return GROUPS;
    }

    /** 全部权限码（去重，按出现顺序）。 */
    public static List<String> codes() {
        Set<String> out = new LinkedHashSet<>();
        for (Group g : GROUPS) {
            for (Entry e : g.entries()) {
                out.add(e.code());
            }
        }
        return List.copyOf(out);
    }

    /** 控制器（小写）是否存在于旧版：未知控制器在登录检查之前就是 404（旧版"控制器不存在"）。 */
    public static boolean knownController(String controller) {
        return CONTROLLERS.contains(controller);
    }

    /**
     * 控制器/动作（小写）是否是旧版存在的动作：auth.php 中的动作（{@code ?} 之前，除去 {@link #WITHOUT_LEGACY_METHOD}）或
     * {@link #EXTRA_ACTIONS}。{@code safety} 与 {@code update} 是空类，没有任何动作。
     */
    public static boolean knownAction(String controller, String action) {
        if (controller.equals("safety") || controller.equals("update")
                || WITHOUT_LEGACY_METHOD.contains(controller + "/" + action)) {
            return false;
        }
        for (Group g : GROUPS) {
            for (Entry e : g.entries()) {
                if (e.controller().equals(controller) && baseAction(e.action()).equals(action)) {
                    return true;
                }
            }
        }
        return EXTRA_ACTIONS.getOrDefault(controller, List.of()).contains(action);
    }

    /** 实现该动作的批次（如 {@code B12}、{@code B13}）；不是已知动作时为 null。 */
    public static String batchOf(String controller, String action) {
        if (!knownAction(controller, action)) {
            return null;
        }
        String byAction = ACTION_BATCH.get(controller + "/" + action);
        return byAction != null ? byAction : CONTROLLER_BATCH.get(controller);
    }

    /** 全部已知动作（控制器/动作，小写，按控制器排序），供测试与文档逐项核对实现批次。 */
    public static List<String> knownActions() {
        List<String> out = new ArrayList<>();
        for (String c : new TreeSet<>(CONTROLLERS)) {
            Set<String> actions = new LinkedHashSet<>();
            for (Group g : GROUPS) {
                for (Entry e : g.entries()) {
                    if (e.controller().equals(c)) {
                        actions.add(baseAction(e.action()));
                    }
                }
            }
            actions.addAll(EXTRA_ACTIONS.getOrDefault(c, List.of()));
            for (String a : actions) {
                if (knownAction(c, a)) {
                    out.add(c + "/" + a);
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static String baseAction(String action) {
        int q = action.indexOf('?');
        return (q < 0 ? action : action.substring(0, q)).toLowerCase(Locale.ROOT);
    }

    private static Group group(int key, String nameKey, String icon, Entry... entries) {
        return new Group(key, key(nameKey), icon, List.of(entries));
    }

    private static Entry entry(int key, int show, Name name, String controller, String action) {
        return new Entry(key, show == 1, name, controller, action, "");
    }

    private static Entry entry(int key, int show, Name name, String controller, String action, String param) {
        return new Entry(key, show == 1, name, controller, action, param);
    }

    private static Name key(String langKey) {
        return new Name(langKey, null);
    }

    private static Name text(String text) {
        return new Name(null, text);
    }
}
