package com.maccms.backend.common.write;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "模块 + 表 + 操作"写入登记表（B07，路线图第 5.3 节写入方矩阵；docs/architecture/12-write-infrastructure.md 第 3 节）。
 *
 * <p>同一份登记表有两道检查：静态护栏 {@code WriteGuardTest} 在 {@code ./mvnw verify} 时检查写 Mapper XML 中的每条语句，
 * 运行期由 {@link WriteGuardInterceptor} 在语句执行前再检查一次实际 SQL。未登记的模块、表或操作都会被拒绝。
 */
public final class WriteRegistry {

    /**
     * 已登记的写入权限。B07、B08 交付时为空。每个写库批次在自己的合并提交里只登记本批的条目，表与批次必须符合路线图的写入方矩阵
     * （{@code WriteGuardTest.OWNER_MATRIX} 锁定）。
     * <ul>
     * <li>B09（MACC-33，18 文档第 6 节）：会员注册（条件插入）；登录、注销轮换、资料与改密、密保重置、头像、推广关系、
     * {@code checkLogin} 到期单行降级。</li>
     * <li>B10（MACC-34，19 文档第 6.1 节）：记录与购买（mac_ulog）、积分流水（mac_plog）、卡密认领（mac_card）、提现（mac_cash）、
     * 本地建单（mac_order）、推广访问（mac_visit）；mac_user 的积分、冻结、升级与批量到期降级沿用 B09 已登记的
     * {@code member/mac_user/UPDATE}。</li>
     * <li>B11（MACC-35，20 文档第 10 节）：内容计数（点击、顶踩、评分、网址来路、Provide 的 detail_inc_hits）只改六张内容表的计数列
     * （content / UPDATE）；视频搜索缓存（content / mac_vod_search INSERT、UPDATE）；评论发表、举报与顶踩（comment / mac_comment
     * INSERT、UPDATE）；留言发表（comment / mac_gbook INSERT）。ajax/referer 的来路访问记录沿用 B10 已登记的
     * {@code member/mac_visit/INSERT}（同一条条件插入，{@code user_id = 0}）。</li>
     * <li>B12（MACC-36，21 文档第 5 节）：管理员登录记录、新增、编辑、删除与状态（admin / mac_admin INSERT、UPDATE、DELETE）；后台"清理缓存"
     * 删除过期的视频搜索缓存（content / mac_vod_search DELETE）。</li>
     * <li>B13（MACC-37，22 文档第 6 节）：后台内容管理——六张内容表的新增与删除（content / INSERT、DELETE；整行覆盖与字段修改沿用 B11
     * 已登记的 content / UPDATE）；友情链接、分类、附件的增删改（content / mac_link、mac_type、mac_annex）；评论的删除与留言的修改、删除
     * （comment / mac_comment DELETE、mac_gbook UPDATE、DELETE；评论的修改沿用 B11 已登记的 comment / mac_comment UPDATE）。
     * 新增视频时更新视频搜索缓存沿用 B11 已登记的 content / mac_vod_search INSERT、UPDATE。</li>
     * <li>B14（MACC-38，23 文档第 6 节）：后台会员与财务——会员删除（member / mac_user DELETE；新增、编辑、状态与到期降级沿用 B09 已登记的
     * INSERT、UPDATE）；会员组的增删改（mac_group）；生成与删除卡密（mac_card INSERT、DELETE）；删除订单（mac_order DELETE）；提现审核
     * （mac_cash UPDATE；删除与补偿插回沿用 B10 的 DELETE、INSERT）；删除推广访问（mac_visit DELETE）。积分日志与访问日志的删除沿用 B10 已登记的
     * mac_plog、mac_ulog DELETE。</li>
     * <li>B15（MACC-39，24 文档第 9.4 节）：数据库工具"批量替换"——管理员手工对任一 mac_* 表的一列做 {@code REPLACE}（admin / 26 张表 / UPDATE；
     * 每张表一条固定的单表语句，列名按 SHOW COLUMNS 与 install.sql 双重校验；mac_admin 沿用 B12 已登记的 admin / mac_admin / UPDATE）。
     * 不登记任何 INSERT/DELETE。</li>
     * </ul>
     */
    static final List<WritePermission> REGISTERED = List.of(
            new WritePermission("member", "mac_user", WriteOperation.INSERT, "B09"),
            new WritePermission("member", "mac_user", WriteOperation.UPDATE, "B09"),
            new WritePermission("member", "mac_ulog", WriteOperation.INSERT, "B10"),
            new WritePermission("member", "mac_ulog", WriteOperation.UPDATE, "B10"),
            new WritePermission("member", "mac_ulog", WriteOperation.DELETE, "B10"),
            new WritePermission("member", "mac_plog", WriteOperation.INSERT, "B10"),
            new WritePermission("member", "mac_plog", WriteOperation.DELETE, "B10"),
            new WritePermission("member", "mac_card", WriteOperation.UPDATE, "B10"),
            new WritePermission("member", "mac_cash", WriteOperation.INSERT, "B10"),
            new WritePermission("member", "mac_cash", WriteOperation.DELETE, "B10"),
            new WritePermission("member", "mac_order", WriteOperation.INSERT, "B10"),
            new WritePermission("member", "mac_visit", WriteOperation.INSERT, "B10"),
            new WritePermission("content", "mac_vod", WriteOperation.UPDATE, "B11"),
            new WritePermission("content", "mac_art", WriteOperation.UPDATE, "B11"),
            new WritePermission("content", "mac_topic", WriteOperation.UPDATE, "B11"),
            new WritePermission("content", "mac_actor", WriteOperation.UPDATE, "B11"),
            new WritePermission("content", "mac_role", WriteOperation.UPDATE, "B11"),
            new WritePermission("content", "mac_website", WriteOperation.UPDATE, "B11"),
            new WritePermission("content", "mac_vod_search", WriteOperation.INSERT, "B11"),
            new WritePermission("content", "mac_vod_search", WriteOperation.UPDATE, "B11"),
            new WritePermission("comment", "mac_comment", WriteOperation.INSERT, "B11"),
            new WritePermission("comment", "mac_comment", WriteOperation.UPDATE, "B11"),
            new WritePermission("comment", "mac_gbook", WriteOperation.INSERT, "B11"),
            new WritePermission("admin", "mac_admin", WriteOperation.INSERT, "B12"),
            new WritePermission("admin", "mac_admin", WriteOperation.UPDATE, "B12"),
            new WritePermission("admin", "mac_admin", WriteOperation.DELETE, "B12"),
            new WritePermission("content", "mac_vod_search", WriteOperation.DELETE, "B12"),
            new WritePermission("content", "mac_vod", WriteOperation.INSERT, "B13"),
            new WritePermission("content", "mac_vod", WriteOperation.DELETE, "B13"),
            new WritePermission("content", "mac_art", WriteOperation.INSERT, "B13"),
            new WritePermission("content", "mac_art", WriteOperation.DELETE, "B13"),
            new WritePermission("content", "mac_topic", WriteOperation.INSERT, "B13"),
            new WritePermission("content", "mac_topic", WriteOperation.DELETE, "B13"),
            new WritePermission("content", "mac_actor", WriteOperation.INSERT, "B13"),
            new WritePermission("content", "mac_actor", WriteOperation.DELETE, "B13"),
            new WritePermission("content", "mac_role", WriteOperation.INSERT, "B13"),
            new WritePermission("content", "mac_role", WriteOperation.DELETE, "B13"),
            new WritePermission("content", "mac_website", WriteOperation.INSERT, "B13"),
            new WritePermission("content", "mac_website", WriteOperation.DELETE, "B13"),
            new WritePermission("content", "mac_link", WriteOperation.INSERT, "B13"),
            new WritePermission("content", "mac_link", WriteOperation.UPDATE, "B13"),
            new WritePermission("content", "mac_link", WriteOperation.DELETE, "B13"),
            new WritePermission("content", "mac_type", WriteOperation.INSERT, "B13"),
            new WritePermission("content", "mac_type", WriteOperation.UPDATE, "B13"),
            new WritePermission("content", "mac_type", WriteOperation.DELETE, "B13"),
            new WritePermission("content", "mac_annex", WriteOperation.INSERT, "B13"),
            new WritePermission("content", "mac_annex", WriteOperation.UPDATE, "B13"),
            new WritePermission("content", "mac_annex", WriteOperation.DELETE, "B13"),
            new WritePermission("comment", "mac_comment", WriteOperation.DELETE, "B13"),
            new WritePermission("comment", "mac_gbook", WriteOperation.UPDATE, "B13"),
            new WritePermission("comment", "mac_gbook", WriteOperation.DELETE, "B13"),
            new WritePermission("member", "mac_user", WriteOperation.DELETE, "B14"),
            new WritePermission("member", "mac_group", WriteOperation.INSERT, "B14"),
            new WritePermission("member", "mac_group", WriteOperation.UPDATE, "B14"),
            new WritePermission("member", "mac_group", WriteOperation.DELETE, "B14"),
            new WritePermission("member", "mac_card", WriteOperation.INSERT, "B14"),
            new WritePermission("member", "mac_card", WriteOperation.DELETE, "B14"),
            new WritePermission("member", "mac_order", WriteOperation.DELETE, "B14"),
            new WritePermission("member", "mac_cash", WriteOperation.UPDATE, "B14"),
            new WritePermission("member", "mac_visit", WriteOperation.DELETE, "B14"),
            new WritePermission("admin", "mac_actor", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_annex", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_art", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_card", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_cash", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_cj_content", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_cj_history", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_cj_node", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_collect", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_comment", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_gbook", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_group", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_link", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_msg", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_order", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_plog", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_role", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_topic", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_type", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_ulog", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_user", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_visit", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_vod", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_website", WriteOperation.UPDATE, "B15"),
            new WritePermission("admin", "mac_vod_search", WriteOperation.UPDATE, "B15"));

    /** 写 Mapper 的全限定名：{@code com.maccms.backend.<module>.infrastructure[.子包].<类型>}。 */
    private static final Pattern MAPPER_TYPE = Pattern.compile(
            "com\\.maccms\\.backend\\.([a-z][a-z0-9]*)\\.infrastructure(?:\\.[a-z][a-z0-9_]*)*\\.[A-Z][A-Za-z0-9_]*");

    private final List<WritePermission> permissions;
    private final Set<String> keys;

    private WriteRegistry(Collection<WritePermission> permissions) {
        this.permissions = List.copyOf(permissions);
        Set<String> k = new LinkedHashSet<>();
        for (WritePermission p : this.permissions) {
            if (!k.add(key(p.module(), p.table(), p.operation()))) {
                throw new IllegalArgumentException("写入权限重复登记：" + p.module() + " " + p.operation() + " " + p.table());
            }
        }
        this.keys = Set.copyOf(k);
    }

    /** 生产使用的登记表（{@link #REGISTERED}）。 */
    public static WriteRegistry registered() {
        return new WriteRegistry(REGISTERED);
    }

    /** 只供一次性库上的演练与测试使用；静态护栏禁止 common.write 以外的正式代码调用。 */
    public static WriteRegistry of(Collection<WritePermission> permissions) {
        return new WriteRegistry(permissions);
    }

    public boolean allows(String module, String table, WriteOperation operation) {
        return keys.contains(key(module, table, operation));
    }

    /** 检查写 Mapper {@code mapperType} 的一条语句；未登记时抛 {@link WriteRejectedException}。 */
    public void check(String mapperType, WriteStatement statement) {
        String module = moduleOf(mapperType);
        if (!allows(module, statement.table(), statement.operation())) {
            throw new WriteRejectedException("未登记的写入：模块 " + module + " 对 " + statement.table() + " 的 "
                    + statement.operation() + "（登记见 WriteRegistry.REGISTERED）");
        }
    }

    /** 写 Mapper 所属模块；不在 {@code <module>.infrastructure} 下时拒绝。 */
    public static String moduleOf(String mapperType) {
        Matcher m = mapperType == null ? null : MAPPER_TYPE.matcher(mapperType);
        if (m == null || !m.matches()) {
            throw new WriteRejectedException("写 Mapper 必须位于 com.maccms.backend.<模块>.infrastructure 包下");
        }
        return m.group(1);
    }

    public List<WritePermission> permissions() {
        return permissions;
    }

    private static String key(String module, String table, WriteOperation operation) {
        return module + "|" + table + "|" + operation;
    }
}
