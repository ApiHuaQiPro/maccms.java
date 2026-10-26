package com.maccms.backend.content.application;

import com.maccms.backend.content.application.view.AdminMessage;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import com.maccms.backend.content.infrastructure.AdminCriteria;
import com.maccms.backend.content.infrastructure.ContentAdminMapper;
import com.maccms.backend.content.infrastructure.ContentAdminWrites;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 后台角色（B13，22 文档第 7.5 节）：旧 {@code admin/controller/Role.php} → {@code common/model/Role.php}。
 */
public class RoleAdminService {

    private static final List<String> FIELD_COLS = List.of("role_status", "role_lock", "role_level", "role_hits");

    private final ContentAdminMapper reader;
    private final ContentAdminWrites writer;
    private final AdminConfig config;
    private final Clock clock;

    public RoleAdminService(ContentAdminMapper reader, ContentAdminWrites writer, AdminConfig config, Clock clock) {
        this.reader = reader;
        this.writer = writer;
        this.config = config;
        this.clock = clock;
    }

    /** 列表（旧 Role::data）：推荐、状态、所属视频（rid）、图片、关键字；按更新时间倒序。 */
    public AdminRowPage list(PhpForm p, AdminPaging paging) {
        AdminCriteria c = new AdminCriteria();
        c.level = ContentAdminSupport.nonEmpty(p, "level");
        c.status = ContentAdminSupport.zeroOne(p, "status");
        c.rid = ContentAdminSupport.nonEmpty(p, "rid");
        c.pic = ContentAdminSupport.pic(p);
        String wd = ContentAdminSupport.wdHtml(p);
        c.wdLike = ContentAdminSupport.like(wd);
        AdminRowPage page = ContentAdminSupport.page(c, paging, wd, reader::countRoles, reader::listRoles);
        // 每行的所属视频（旧版 $vo.data，模板显示视频名与链接）：键 data.vod_id、data.vod_name
        java.util.Set<Long> rids = new java.util.LinkedHashSet<>();
        for (AdminRow r : page.rows()) {
            if (r.n("role_rid") > 0) {
                rids.add(r.n("role_rid"));
            }
        }
        java.util.Map<String, java.util.Map<String, String>> vods = new java.util.HashMap<>();
        if (!rids.isEmpty()) {
            for (java.util.Map<String, String> v : reader.vodNamesByIds(new java.util.ArrayList<>(rids))) {
                vods.put(v.get("vod_id"), v);
            }
        }
        java.util.List<AdminRow> rows = new java.util.ArrayList<>();
        for (AdminRow r : page.rows()) {
            java.util.Map<String, String> m = new java.util.LinkedHashMap<>(r.values());
            java.util.Map<String, String> v = vods.get(r.s("role_rid"));
            if (v != null) {
                m.put("data.vod_id", v.get("vod_id"));
                m.put("data.vod_name", v.get("vod_name"));
            }
            rows.add(new AdminRow(m));
        }
        return new AdminRowPage(rows, page.total(), page.page(), page.limit(), page.wd());
    }

    public Optional<AdminRow> info(byte[] id) {
        return AdminIds.lookupId(id).map(reader::selectRole).map(AdminRow::new);
    }

    /** 表单里显示的所属视频（旧 Role::info：按 {@code role_rid} 读视频）。 */
    public Optional<AdminRow> video(String rid) {
        return AdminIds.lookupId(rid == null ? null : rid.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .map(reader::selectVod).map(AdminRow::new);
    }

    public AdminResult save(PhpForm post) {
        return AdminWrites.guard(() -> saveData(post.copy()));
    }

    /** 删除（旧 Role::delData：先删行再查这些行删图片，所以图片从来没被删掉；新版同样不删文件）。 */
    public AdminResult delete(PhpForm param) {
        return AdminWrites.guard(() -> {
            List<Long> ids = AdminIds.ids(param);
            if (ids == null) {
                return AdminResult.error("param_err");
            }
            writer.delete(AdminTable.ROLE.table(), ids);
            return AdminResult.ok("del_ok");
        });
    }

    public AdminResult field(PhpForm param) {
        return ContentAdminSupport.field(param, AdminTable.ROLE, FIELD_COLS, null, writer);
    }

    /** 旧 {@code Role::saveData}（common/model/Role.php:311-365）：名称、演员必填；没有 xss 过滤与简介。 */
    private AdminResult saveData(PhpForm data) {
        if (!data.require("role_name")) {
            return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_name"));
        }
        if (!data.require("role_actor")) {
            return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_actor"));
        }
        long now = clock.instant().getEpochSecond();
        ContentSaveSteps.pinyin(data, "role_en", "role_name");
        ContentSaveSteps.letter(data, "role_letter", "role_en");
        ContentSaveSteps.contentImages(data, "role_content", config.text("upload", "protocol"));
        ContentSaveSteps.uptime(data, "role_time", now);
        if (!data.empty("role_id")) {
            long id = AdminIds.primaryKey(data.scalar("role_id"));
            writer.update(AdminTable.ROLE.table(), AdminRowValues.of(AdminTable.ROLE, data, Set.of("role_id")), id);
        } else {
            data.put("role_time_add", Long.toString(now));
            data.put("role_time", Long.toString(now));
            writer.insert(AdminTable.ROLE.table(), AdminRowValues.of(AdminTable.ROLE, data, Set.of("role_id")));
        }
        return AdminResult.ok("save_ok");
    }
}
