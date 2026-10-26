package com.maccms.backend.content.application;

import com.maccms.backend.content.application.view.AdminMessage;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import com.maccms.backend.content.infrastructure.ContentAdminMapper;
import com.maccms.backend.content.infrastructure.ContentAdminWrites;
import com.maccms.backend.content.infrastructure.UploadStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 后台专题（B13，22 文档第 7.4 节）：旧 {@code admin/controller/Topic.php} → {@code common/model/Topic.php}。
 */
public class TopicAdminService {

    private static final List<String> XSS_FIELDS = List.of("topic_name", "topic_en", "topic_sub", "topic_color", "topic_tpl",
            "topic_type", "topic_pic", "topic_pic_thumb", "topic_pic_slide", "topic_key", "topic_des", "topic_title", "topic_blurb",
            "topic_remarks", "topic_tag", "topic_rel_vod", "topic_rel_art", "topic_content", "topic_extend");

    private final ContentAdminMapper reader;
    private final ContentAdminWrites writer;
    private final UploadStore uploads;
    private final AdminConfig config;
    private final Clock clock;

    public TopicAdminService(ContentAdminMapper reader, ContentAdminWrites writer, UploadStore uploads, AdminConfig config,
                             Clock clock) {
        this.reader = reader;
        this.writer = writer;
        this.uploads = uploads;
        this.config = config;
        this.clock = clock;
    }

    /**
     * 列表（旧 Topic::data）：{@code status} 严格等于 {@code '0'}/{@code '1'} 时筛选；{@code wd} 按
     * {@code topic_name LIKE '%' . htmlspecialchars(urldecode(wd)) . '%'}；{@code topic_time} 倒序。
     */
    public AdminRowPage list(PhpForm param, AdminPaging paging) {
        String status = null;
        byte[] s = param.scalar("status");
        if (s != null && (s.length == 1 && (s[0] == '0' || s[0] == '1'))) {
            status = new String(s, StandardCharsets.US_ASCII);
        }
        String word = null;
        String like = null;
        if (!param.empty("wd") && !param.isArray("wd")) {
            word = new String(PhpText.htmlspecialcharsCompat(PhpText.urldecode(param.scalar("wd"))), StandardCharsets.UTF_8);
            like = PhpText.legacyLike(word);
        }
        long total = reader.countTopics(status, like);
        List<AdminRow> rows = new ArrayList<>();
        if (!paging.beyond()) {
            for (Map<String, String> r : reader.listTopics(status, like, paging.offset(), paging.limit())) {
                rows.add(new AdminRow(r));
            }
        }
        return new AdminRowPage(rows, total, paging.page(), paging.limit(), word);
    }

    /** 表单（旧 Topic::info 的 GET）。 */
    public Optional<AdminRow> info(byte[] id) {
        return AdminIds.lookupId(id).map(reader::selectTopic).map(AdminRow::new);
    }

    /** 保存（旧 Topic::info 的 POST，{@code input('post.')}）。 */
    public AdminResult save(PhpForm post) {
        return AdminWrites.guard(() -> saveData(post.copy()));
    }

    /**
     * 删除（旧 Topic::del → Topic::delData）：先删这些专题在 {@code upload/} 下的图片（{@code topic_pic}、{@code topic_pic_thumb}、
     * {@code topic_pic_slide}），再删行。旧版另删静态页文件（{@code view.topic_detail == 2}），新版没有静态页（B16）。
     */
    public AdminResult delete(PhpForm param) {
        return AdminWrites.guard(() -> {
            List<Long> ids = AdminIds.ids(param);
            if (ids == null) {
                return AdminResult.error("param_err");
            }
            for (Map<String, String> r : reader.selectTopicsByIds(ids)) {
                for (String col : List.of("topic_pic", "topic_pic_thumb", "topic_pic_slide")) {
                    uploads.delete(r.get(col));
                }
            }
            writer.delete(AdminTable.TOPIC.table(), ids);
            return AdminResult.ok("del_ok");
        });
    }

    /** 字段（旧 Topic::field）：{@code col} 只能是 {@code topic_status}、{@code topic_level}；缺 {@code val} 时"参数错误"（fieldData 的 isset）。 */
    public AdminResult field(PhpForm param) {
        return AdminWrites.guard(() -> {
            List<Long> ids = AdminIds.ids(param);
            String col = param.isArray("col") ? null : param.text("col");
            if (ids == null || col == null || !List.of("topic_status", "topic_level").contains(col)) {
                return AdminResult.error("param_err");
            }
            if (!param.has("val") || param.isArray("val")) {
                return AdminResult.error("param_err");
            }
            Map<String, String> c = new LinkedHashMap<>();
            c.put(col, AdminRowValues.value(AdminTable.TOPIC.column(col), param.scalar("val")));
            writer.updateByIds(AdminTable.TOPIC.table(), c, ids);
            return AdminResult.ok("set_ok");
        });
    }

    /** 旧 {@code Topic::saveData}（common/model/Topic.php:268-348），步骤与顺序照旧。 */
    private AdminResult saveData(PhpForm data) {
        if (!data.require("topic_name")) {
            return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_name"));
        }
        if (!data.require("topic_tpl")) {
            return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_tpl"));
        }
        long now = clock.instant().getEpochSecond();
        if (data.empty("topic_extend")) {
            data.put("topic_extend", "");
        }
        ContentSaveSteps.pinyin(data, "topic_en", "topic_name");
        ContentSaveSteps.contentImages(data, "topic_content", config.text("upload", "protocol"));
        ContentSaveSteps.blurb(data, "topic_blurb", "topic_content");
        ContentSaveSteps.uptime(data, "topic_time", now);
        ContentSaveSteps.filterXss(data, XSS_FIELDS);
        if (!data.empty("topic_id")) {
            long id = AdminIds.primaryKey(data.scalar("topic_id"));
            writer.update(AdminTable.TOPIC.table(), AdminRowValues.of(AdminTable.TOPIC, data, Set.of("topic_id")), id);
        } else {
            data.put("topic_time_add", Long.toString(now));
            data.put("topic_time", Long.toString(now));
            writer.insert(AdminTable.TOPIC.table(), AdminRowValues.of(AdminTable.TOPIC, data, Set.of("topic_id")));
        }
        return AdminResult.ok("save_ok");
    }
}
