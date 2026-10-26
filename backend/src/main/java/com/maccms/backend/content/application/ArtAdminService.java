package com.maccms.backend.content.application;

import com.maccms.backend.content.application.view.AdminMessage;
import com.maccms.backend.content.application.view.AdminProgress;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import com.maccms.backend.content.infrastructure.AdminCriteria;
import com.maccms.backend.content.infrastructure.AdminDataException;
import com.maccms.backend.content.infrastructure.ContentAdminMapper;
import com.maccms.backend.content.infrastructure.ContentAdminWrites;
import com.maccms.backend.content.infrastructure.UploadStore;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * 后台文章（B13，22 文档第 7.3 节）：旧 {@code admin/controller/Art.php} → {@code common/model/Art.php}。
 */
public class ArtAdminService {

    private static final List<String> XSS_FIELDS = List.of("art_name", "art_sub", "art_en", "art_color", "art_from",
            "art_author", "art_tag", "art_class", "art_pic", "art_pic_thumb", "art_pic_slide", "art_blurb", "art_remarks",
            "art_jumpurl", "art_tpl", "art_rel_art", "art_rel_vod", "art_pwd", "art_pwd_url");
    private static final List<String> FIELD_COLS = List.of("art_status", "art_lock", "art_level", "art_hits", "type_id");
    private static final ContentBatch.Spec BATCH = new ContentBatch.Spec(AdminTable.ART, "art", true, "multi_set_ok", "unlock",
            false);
    private static final byte[] SEP = {'$', '$', '$'};

    private final ContentAdminMapper reader;
    private final ContentAdminWrites writer;
    private final UploadStore uploads;
    private final AdminConfig config;
    private final TypeAdminService types;
    private final Clock clock;

    public ArtAdminService(ContentAdminMapper reader, ContentAdminWrites writer, UploadStore uploads, AdminConfig config,
                           TypeAdminService types, Clock clock) {
        this.reader = reader;
        this.writer = writer;
        this.uploads = uploads;
        this.config = config;
        this.types = types;
        this.clock = clock;
    }

    /**
     * 列表（旧 Art::data）：分类（{@code type_id|type_id_1}）、推荐、状态、锁定、图片、关键字（{@code mac_filter_xss(urldecode())}）；
     * {@code repeat} 时查重（旧版用临时表 mac_tmpart，新版子查询，D-B13-7）。
     */
    public AdminRowPage list(PhpForm p, AdminPaging paging) {
        AdminCriteria c = new AdminCriteria();
        c.typeEither = ContentAdminSupport.nonEmpty(p, "type");
        c.level = ContentAdminSupport.nonEmpty(p, "level");
        c.status = ContentAdminSupport.zeroOne(p, "status");
        c.lock = ContentAdminSupport.nonEmpty(p, "lock");
        c.pic = ContentAdminSupport.pic(p);
        String wd = ContentAdminSupport.wdXss(p);
        c.wdLike = ContentAdminSupport.like(wd);
        c.repeat = !p.empty("repeat");
        return ContentAdminSupport.page(c, paging, wd, reader::countArts, reader::listArts);
    }

    public Optional<AdminRow> info(byte[] id) {
        return AdminIds.lookupId(id).map(reader::selectArt).map(AdminRow::new);
    }

    /** 保存（旧 Art::info 的 POST，{@code input('post.')} → {@code Art::saveData}）。 */
    public AdminResult save(PhpForm post) {
        return AdminWrites.guard(() -> saveData(post.copy()));
    }

    /** 删除（旧 Art::del）：{@code ids} 时先删图片（art_pic、art_pic_thumb、art_pic_slide）再删行；否则 {@code repeat} 时查重删除。 */
    public AdminResult delete(PhpForm param) {
        if (!param.empty("ids")) {
            return AdminWrites.guard(() -> {
                deleteRows(AdminIds.ids(param));
                return AdminResult.ok("del_ok");
            });
        }
        if (!param.empty("repeat")) {
            boolean max = "max".equals(param.isArray("retain") ? null : param.text("retain"));
            return ContentAdminSupport.deleteRepeat(max, reader::artRepeatGroups,
                    (name, keep) -> ContentAdminWrites.run(() -> writer.mapper().deleteArtRepeat(name, keep)),
                    ids -> writer.delete(AdminTable.ART.table(), ids));
        }
        return AdminResult.error("param_err");
    }

    public AdminResult field(PhpForm param) {
        return ContentAdminSupport.field(param, AdminTable.ART, FIELD_COLS, types.cacheList(), writer);
    }

    /** 批量处理（旧 Art::batch：从最后一页往前处理）。 */
    public AdminProgress batch(PhpForm param, UnaryOperator<String> lang) {
        try {
            return ContentBatch.run(BATCH, param, lang, new ContentBatch.Access() {
                @Override
                public long count(AdminCriteria c) {
                    return reader.countArts(c);
                }

                @Override
                public List<Map<String, String>> list(AdminCriteria c, long offset, long limit) {
                    return reader.listArts(c, offset, limit);
                }
            }, this::batchCriteria, this::deleteMatching, writer, null);
        } catch (AdminRejectedException e) {
            return failed(e.reason());
        } catch (AdminDataException e) {
            return failed(AdminMessage.lang("param_err"));
        }
    }

    public String today(PhpForm param) {
        boolean type = "type".equals(param.isArray("flag") ? null : param.text("flag"));
        return ContentAdminSupport.today(clock, reader::artToday, type);
    }

    static AdminProgress failed(AdminMessage m) {
        return new AdminProgress(List.of(AdminProgress.Line.of(AdminProgress.Part.markup(ContentBatch.STYLE))), null, m);
    }

    private AdminCriteria batchCriteria(PhpForm p) {
        AdminCriteria c = new AdminCriteria();
        c.typeExact = ContentAdminSupport.nonEmpty(p, "type");
        c.level = ContentAdminSupport.nonEmpty(p, "level");
        c.status = ContentAdminSupport.zeroOne(p, "status");
        c.lock = ContentAdminSupport.nonEmpty(p, "lock");
        c.pic = ContentAdminSupport.pic(p);
        c.wdLike = ContentAdminSupport.like(ContentAdminSupport.wdHtml(p));
        c.order = "id_desc";
        return c;
    }

    private long deleteMatching(AdminCriteria c) {
        long n = 0;
        while (true) {
            List<Long> ids = new ArrayList<>();
            for (Map<String, String> r : reader.listArts(c, 0, AdminIds.MAX_IDS)) {
                ids.add(Long.parseLong(r.get("art_id")));
            }
            if (ids.isEmpty()) {
                return n;
            }
            n += deleteRows(ids);
        }
    }

    private int deleteRows(List<Long> ids) {
        for (Map<String, String> r : reader.selectArtsByIds(ids)) {
            for (String col : List.of("art_pic", "art_pic_thumb", "art_pic_slide")) {
                uploads.delete(r.get(col));
            }
        }
        return writer.delete(AdminTable.ART.table(), ids);
    }

    /**
     * 旧 {@code Art::saveData}（common/model/Art.php:451-545）：分页正文 {@code art_content[]} 非空时把 {@code art_content[]}、
     * {@code art_title[]}、{@code art_note[]} 各以 {@code $$$} 连接，正文图片协议换成 {@code mac:}，图片为空时取正文第一张图；简介为空时取
     * 去掉 {@code $$$} 后的正文前 100 字。
     */
    private AdminResult saveData(PhpForm data) {
        if (!data.require("art_name")) {
            return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_name"));
        }
        if (!data.require("type_id")) {
            return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_type"));
        }
        long now = clock.instant().getEpochSecond();
        ContentSaveSteps.typeParent(data, types.cacheList());
        ContentSaveSteps.pinyin(data, "art_en", "art_name");
        ContentSaveSteps.letter(data, "art_letter", "art_en");
        WebsiteAdminService.screenshots(data, "art_pic_screenshot");
        if (!data.empty("art_content")) {
            data.put("art_content", join(data, "art_content"));
            data.put("art_title", join(data, "art_title"));
            data.put("art_note", join(data, "art_note"));
            byte[] content = data.scalar("art_content");
            byte[] first = PhpText.firstImageSrc(content);
            data.put("art_content", PhpText.contentImageProtocol(content, config.text("upload", "protocol")));
            if (data.empty("art_pic")) {
                // 旧版 preg_match_all 的结果总是非空数组，没有图片时取到 null，写成空串
                data.put("art_pic", first == null ? new byte[0] : first);
            }
        }
        if (data.empty("art_blurb")) {
            AdminText.requireScalar(data, "art_content");
            byte[] c = data.scalar("art_content");
            data.put("art_blurb", PhpText.macSubstring(PhpText.strReplace(PhpText.stripTags(c == null ? new byte[0] : c), SEP,
                    new byte[0]), 100));
        }
        ContentSaveSteps.uptime(data, "art_time", now);
        ContentSaveSteps.uptag(data, "art_tag");
        ContentSaveSteps.filterXss(data, XSS_FIELDS);
        if (!data.empty("art_id")) {
            long id = AdminIds.primaryKey(data.scalar("art_id"));
            writer.update(AdminTable.ART.table(), AdminRowValues.of(AdminTable.ART, data, Set.of("art_id")), id);
        } else {
            data.put("art_time_add", Long.toString(now));
            data.put("art_time", Long.toString(now));
            writer.insert(AdminTable.ART.table(), AdminRowValues.of(AdminTable.ART, data, Set.of("art_id")));
        }
        return AdminResult.ok("save_ok");
    }

    /** {@code join('$$$', $data[name])}：不是数组时"参数错误"（旧版 PHP 8 报错，7.3 得到 null 后写库报错）。 */
    static byte[] join(PhpForm data, String name) {
        PhpForm.Arr a = data.array(name);
        if (a == null) {
            throw AdminRejectedException.paramError();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean first = true;
        for (byte[] v : a.entries().values()) {
            if (!first) {
                out.writeBytes(SEP);
            }
            first = false;
            out.writeBytes(v);
        }
        return out.toByteArray();
    }
}
