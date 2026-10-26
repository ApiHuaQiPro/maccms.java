package com.maccms.backend.content.application;

import com.maccms.backend.content.application.view.AdminMessage;
import com.maccms.backend.content.application.view.AdminProgress;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import com.maccms.backend.content.infrastructure.AdminCriteria;
import com.maccms.backend.content.infrastructure.ContentAdminMapper;
import com.maccms.backend.content.infrastructure.ContentAdminWrites;
import com.maccms.backend.content.infrastructure.UploadStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * 后台网址（B13，22 文档第 7.6 节）：旧 {@code admin/controller/Website.php} → {@code common/model/Website.php}。
 */
public class WebsiteAdminService {

    private static final List<String> XSS_FIELDS = List.of("website_name", "website_sub", "website_en", "website_color",
            "website_jumpurl", "website_pic", "website_logo", "website_area", "website_lang", "website_tag", "website_class",
            "website_remarks", "website_tpl", "website_blurb");
    private static final List<String> FIELD_COLS = List.of("website_status", "website_lock", "website_level", "website_hits",
            "type_id");
    private static final ContentBatch.Spec BATCH = new ContentBatch.Spec(AdminTable.WEBSITE, "website", false, "multi_opt_ok",
            "ublock", false);

    private final ContentAdminMapper reader;
    private final ContentAdminWrites writer;
    private final UploadStore uploads;
    private final AdminConfig config;
    private final TypeAdminService types;
    private final Clock clock;

    public WebsiteAdminService(ContentAdminMapper reader, ContentAdminWrites writer, UploadStore uploads, AdminConfig config,
                               TypeAdminService types, Clock clock) {
        this.reader = reader;
        this.writer = writer;
        this.uploads = uploads;
        this.config = config;
        this.types = types;
        this.clock = clock;
    }

    /**
     * 列表（旧 Website::data）：分类（{@code type_id|type_id_1}）、推荐、状态、锁定、图片、关键字（{@code htmlspecialchars(urldecode())}）；
     * {@code repeat} 非空时只列同名超过一条的（按名称），否则按更新时间倒序。旧版查重第 1 页时重建临时表 {@code mac_tmpwebsite}（DDL），新版用子查询
     * （22 文档 D-B13-7）。
     */
    public AdminRowPage list(PhpForm p, AdminPaging paging) {
        AdminCriteria c = new AdminCriteria();
        c.typeEither = ContentAdminSupport.nonEmpty(p, "type");
        c.level = ContentAdminSupport.nonEmpty(p, "level");
        c.status = ContentAdminSupport.zeroOne(p, "status");
        c.lock = ContentAdminSupport.nonEmpty(p, "lock");
        c.pic = ContentAdminSupport.pic(p);
        String wd = ContentAdminSupport.wdHtml(p);
        c.wdLike = ContentAdminSupport.like(wd);
        c.repeat = !p.empty("repeat");
        return ContentAdminSupport.page(c, paging, wd, reader::countWebsites, reader::listWebsites);
    }

    public Optional<AdminRow> info(byte[] id) {
        return AdminIds.lookupId(id).map(reader::selectWebsite).map(AdminRow::new);
    }

    /**
     * 保存（旧 Website::info 的 POST）：控制器先把 {@code website_content} 里的 {@code 协议:} 换成 {@code mac:}（不论是否在图片地址里；
     * 没有这个字段时得到空串），再交给 {@code Website::saveData}。
     */
    public AdminResult save(PhpForm post) {
        return AdminWrites.guard(() -> {
            PhpForm data = post.copy();
            AdminText.requireScalar(data, "website_content");
            byte[] content = data.scalar("website_content");
            data.put("website_content", PhpText.strReplace(content == null ? new byte[0] : content,
                    (config.text("upload", "protocol") + ":").getBytes(StandardCharsets.UTF_8),
                    "mac:".getBytes(StandardCharsets.US_ASCII)));
            return saveData(data);
        });
    }

    /** 删除（旧 Website::del）：{@code ids} 时先删 upload/ 下的 {@code website_pic} 再删行；否则 {@code repeat} 时查重删除。 */
    public AdminResult delete(PhpForm param) {
        if (!param.empty("ids")) {
            return AdminWrites.guard(() -> {
                List<Long> ids = AdminIds.ids(param);
                deleteRows(ids);
                return AdminResult.ok("del_ok");
            });
        }
        if (!param.empty("repeat")) {
            boolean max = "max".equals(param.isArray("retain") ? null : param.text("retain"));
            return ContentAdminSupport.deleteRepeat(max, reader::websiteRepeatGroups,
                    (name, keep) -> ContentAdminWrites.run(() -> writer.mapper().deleteWebsiteRepeat(name, keep)),
                    ids -> writer.delete(AdminTable.WEBSITE.table(), ids));
        }
        return AdminResult.error("param_err");
    }

    public AdminResult field(PhpForm param) {
        return ContentAdminSupport.field(param, AdminTable.WEBSITE, FIELD_COLS, types.cacheList(), writer);
    }

    /** 批量处理（旧 Website::batch，22 文档第 7.7 节）。 */
    public AdminProgress batch(PhpForm param, UnaryOperator<String> lang) {
        try {
            return ContentBatch.run(BATCH, param, lang, new ContentBatch.Access() {
                @Override
                public long count(AdminCriteria c) {
                    return reader.countWebsites(c);
                }

                @Override
                public List<Map<String, String>> list(AdminCriteria c, long offset, long limit) {
                    return reader.listWebsites(c, offset, limit);
                }
            }, this::batchCriteria, this::deleteMatching, writer, null);
        } catch (AdminRejectedException e) {
            return new AdminProgress(List.of(AdminProgress.Line.of(AdminProgress.Part.markup(ContentBatch.STYLE))), null,
                    e.reason());
        } catch (com.maccms.backend.content.infrastructure.AdminDataException e) {
            return new AdminProgress(List.of(AdminProgress.Line.of(AdminProgress.Part.markup(ContentBatch.STYLE))), null,
                    AdminMessage.lang("param_err"));
        }
    }

    /** updateToday（旧 Website::updateToday → Website::updateToday）。 */
    public String today(PhpForm param) {
        boolean type = "type".equals(param.isArray("flag") ? null : param.text("flag"));
        return ContentAdminSupport.today(clock, reader::websiteToday, type);
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

    /** 批量删除：符合条件的全部行（旧 delData($where)：先删图片，再按条件删除）。 */
    private long deleteMatching(AdminCriteria c) {
        long n = 0;
        while (true) {
            List<Long> ids = new ArrayList<>();
            for (Map<String, String> r : reader.listWebsites(c, 0, AdminIds.MAX_IDS)) {
                ids.add(Long.parseLong(r.get("website_id")));
            }
            if (ids.isEmpty()) {
                return n;
            }
            n += deleteRows(ids);
        }
    }

    private int deleteRows(List<Long> ids) {
        for (Map<String, String> r : reader.selectWebsitesByIds(ids)) {
            uploads.delete(r.get("website_pic"));
        }
        return writer.delete(AdminTable.WEBSITE.table(), ids);
    }

    /** 旧 {@code Website::saveData}（common/model/Website.php:482-555）。 */
    private AdminResult saveData(PhpForm data) {
        if (!data.require("website_name")) {
            return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_name"));
        }
        if (!data.require("type_id")) {
            return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_type"));
        }
        long now = clock.instant().getEpochSecond();
        ContentSaveSteps.typeParent(data, types.cacheList());
        ContentSaveSteps.pinyin(data, "website_en", "website_name");
        ContentSaveSteps.letter(data, "website_letter", "website_en");
        screenshots(data, "website_pic_screenshot");
        ContentSaveSteps.uptime(data, "website_time", now);
        ContentSaveSteps.uptag(data, "website_tag");
        ContentSaveSteps.filterXss(data, XSS_FIELDS);
        if (!data.empty("website_id")) {
            long id = AdminIds.primaryKey(data.scalar("website_id"));
            writer.update(AdminTable.WEBSITE.table(), AdminRowValues.of(AdminTable.WEBSITE, data, Set.of("website_id")), id);
        } else {
            data.put("website_time_add", Long.toString(now));
            data.put("website_time", Long.toString(now));
            writer.insert(AdminTable.WEBSITE.table(), AdminRowValues.of(AdminTable.WEBSITE, data, Set.of("website_id")));
        }
        return AdminResult.ok("save_ok");
    }

    /** {@code if(!empty($data[x])) $data[x] = str_replace([chr(10), chr(13)], ['', '#'], $data[x])}。 */
    static void screenshots(PhpForm data, String col) {
        if (!data.empty(col)) {
            AdminText.requireScalar(data, col);
            byte[] v = data.scalar(col);
            v = PhpText.strReplace(v, new byte[] {'\n'}, new byte[0]);
            v = PhpText.strReplace(v, new byte[] {'\r'}, new byte[] {'#'});
            data.put(col, v);
        }
    }
}
