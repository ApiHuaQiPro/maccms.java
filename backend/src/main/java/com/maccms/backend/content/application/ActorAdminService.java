package com.maccms.backend.content.application;

import com.maccms.backend.content.application.view.AdminMessage;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import com.maccms.backend.content.infrastructure.AdminCriteria;
import com.maccms.backend.content.infrastructure.ContentAdminMapper;
import com.maccms.backend.content.infrastructure.ContentAdminWrites;
import com.maccms.backend.content.infrastructure.UploadStore;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 后台演员（B13，22 文档第 7.5 节）：旧 {@code admin/controller/Actor.php} → {@code common/model/Actor.php}。
 */
public class ActorAdminService {

    private static final List<String> XSS_FIELDS = List.of("actor_name", "actor_en", "actor_alias", "actor_color", "actor_pic",
            "actor_blurb", "actor_remarks", "actor_area", "actor_height", "actor_weight", "actor_birthday", "actor_birtharea",
            "actor_blood", "actor_starsign", "actor_school", "actor_works", "actor_tag", "actor_class", "actor_tpl", "actor_jumpurl");
    private static final List<String> FIELD_COLS = List.of("actor_status", "actor_lock", "actor_level", "type_id", "actor_hits");

    private final ContentAdminMapper reader;
    private final ContentAdminWrites writer;
    private final UploadStore uploads;
    private final AdminConfig config;
    private final TypeAdminService types;
    private final Clock clock;

    public ActorAdminService(ContentAdminMapper reader, ContentAdminWrites writer, UploadStore uploads, AdminConfig config,
                             TypeAdminService types, Clock clock) {
        this.reader = reader;
        this.writer = writer;
        this.uploads = uploads;
        this.config = config;
        this.types = types;
        this.clock = clock;
    }

    /** 列表（旧 Actor::data）：分类、推荐、状态、图片、关键字；按更新时间倒序（页面上的"排序"下拉框旧控制器不读）。 */
    public AdminRowPage list(PhpForm p, AdminPaging paging) {
        AdminCriteria c = new AdminCriteria();
        c.typeEither = ContentAdminSupport.nonEmpty(p, "type");
        c.level = ContentAdminSupport.nonEmpty(p, "level");
        c.status = ContentAdminSupport.zeroOne(p, "status");
        c.pic = ContentAdminSupport.pic(p);
        String wd = ContentAdminSupport.wdHtml(p);
        c.wdLike = ContentAdminSupport.like(wd);
        return ContentAdminSupport.page(c, paging, wd, reader::countActors, reader::listActors);
    }

    public Optional<AdminRow> info(byte[] id) {
        return AdminIds.lookupId(id).map(reader::selectActor).map(AdminRow::new);
    }

    public AdminResult save(PhpForm post) {
        return AdminWrites.guard(() -> saveData(post.copy()));
    }

    /** 删除（旧 Actor::del → delData）：先删 upload/ 下的 actor_pic 再删行。 */
    public AdminResult delete(PhpForm param) {
        return AdminWrites.guard(() -> {
            List<Long> ids = AdminIds.ids(param);
            if (ids == null) {
                return AdminResult.error("param_err");
            }
            for (Map<String, String> r : reader.selectActorsByIds(ids)) {
                uploads.delete(r.get("actor_pic"));
            }
            writer.delete(AdminTable.ACTOR.table(), ids);
            return AdminResult.ok("del_ok");
        });
    }

    public AdminResult field(PhpForm param) {
        return ContentAdminSupport.field(param, AdminTable.ACTOR, FIELD_COLS, types.cacheList(), writer);
    }

    /** 旧 {@code Actor::saveData}（common/model/Actor.php:373-457）。 */
    private AdminResult saveData(PhpForm data) {
        if (!data.require("actor_name")) {
            return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_name"));
        }
        if (!data.require("type_id")) {
            return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_type"));
        }
        long now = clock.instant().getEpochSecond();
        ContentSaveSteps.typeParent(data, types.cacheList());
        ContentSaveSteps.pinyin(data, "actor_en", "actor_name");
        ContentSaveSteps.letter(data, "actor_letter", "actor_en");
        ContentSaveSteps.contentImages(data, "actor_content", config.text("upload", "protocol"));
        ContentSaveSteps.blurb(data, "actor_blurb", "actor_content");
        ContentSaveSteps.uptag(data, "actor_tag");
        ContentSaveSteps.uptime(data, "actor_time", now);
        ContentSaveSteps.filterXss(data, XSS_FIELDS);
        if (!data.empty("actor_id")) {
            long id = AdminIds.primaryKey(data.scalar("actor_id"));
            writer.update(AdminTable.ACTOR.table(), AdminRowValues.of(AdminTable.ACTOR, data, Set.of("actor_id")), id);
        } else {
            data.put("actor_time_add", Long.toString(now));
            data.put("actor_time", Long.toString(now));
            writer.insert(AdminTable.ACTOR.table(), AdminRowValues.of(AdminTable.ACTOR, data, Set.of("actor_id")));
        }
        return AdminResult.ok("save_ok");
    }
}
