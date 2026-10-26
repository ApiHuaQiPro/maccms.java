package com.maccms.backend.content.application;

import com.maccms.backend.content.application.view.AdminMessage;
import com.maccms.backend.content.application.view.AdminProgress;
import com.maccms.backend.content.application.view.AdminProgress.Part;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import com.maccms.backend.content.infrastructure.AdminCriteria;
import com.maccms.backend.content.infrastructure.AdminDataException;
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
import java.util.function.UnaryOperator;

/**
 * 后台视频（B13，22 文档第 7.2 节）：旧 {@code admin/controller/Vod.php} → {@code common/model/Vod.php}、
 * {@code common/validate/Vod.php}。
 */
public class VodAdminService {

    /** 旧 {@code VodValidate::formatDataBeforeDb} 的字段与 {@code mb_substr} 长度（common/validate/Vod.php:26-74）。 */
    private static final Map<String, Integer> FORMAT_FIELDS = formatFields();
    private static final List<String> FIELD_COLS = List.of("vod_status", "vod_lock", "vod_level", "vod_hits", "type_id",
            "vod_copyright");
    private static final List<String> ORDERS = List.of("vod_id", "vod_hits", "vod_hits_month", "vod_hits_week", "vod_hits_day");
    private static final ContentBatch.Spec BATCH = new ContentBatch.Spec(AdminTable.VOD, "vod", true, "multi_opt_ok", "unlock",
            true);
    private static final byte[] SEP = {'$', '$', '$'};

    private final ContentAdminMapper reader;
    private final ContentAdminWrites writer;
    private final UploadStore uploads;
    private final AdminConfig config;
    private final TypeAdminService types;
    private final VodSearchCache search;
    private final VodSearchOptimise optimise;
    private final Clock clock;

    public VodAdminService(ContentAdminMapper reader, ContentAdminWrites writer, UploadStore uploads, AdminConfig config,
                           TypeAdminService types, VodSearchCache search, VodSearchOptimise optimise, Clock clock) {
        this.reader = reader;
        this.writer = writer;
        this.uploads = uploads;
        this.config = config;
        this.types = types;
        this.search = search;
        this.optimise = optimise;
        this.clock = clock;
    }

    /**
     * 列表（旧 Vod::data）：分类（{@code type_id|type_id_1}）、推荐、状态、版权、完结、锁定、资源状态、地区、语言、剧情、播放地址为空、积分、
     * 图片、周期、关键字（名称/主演/副标题，{@code mac_filter_xss(urldecode())}）、播放器、下载器、服务器组；排序白名单；{@code repeat}
     * 时查重（旧版临时表 mac_tmpvod，新版子查询，D-B13-7）。
     */
    public AdminRowPage list(PhpForm p, AdminPaging paging) {
        AdminCriteria c = new AdminCriteria();
        c.typeEither = ContentAdminSupport.nonEmpty(p, "type");
        c.level = ContentAdminSupport.nonEmpty(p, "level");
        c.status = ContentAdminSupport.zeroOne(p, "status");
        c.copyright = ContentAdminSupport.zeroOne(p, "copyright");
        c.isend = ContentAdminSupport.zeroOne(p, "isend");
        c.locked = ContentAdminSupport.nonEmpty(p, "lock");
        c.state = ContentAdminSupport.nonEmpty(p, "state");
        c.area = ContentAdminSupport.nonEmpty(p, "area");
        c.lang = ContentAdminSupport.nonEmpty(p, "lang");
        c.plot = ContentAdminSupport.zeroOne(p, "plot");
        c.urlEmpty = !p.empty("url") && !p.isArray("url") && PhpLoose.equalsOne(p.scalar("url"));
        c.points = !p.empty("points");
        c.pic = ContentAdminSupport.pic(p);
        c.weekdayLike = ContentAdminSupport.like(ContentAdminSupport.nonEmpty(p, "weekday"));
        String wd = ContentAdminSupport.wdXss(p);
        c.wdLike = ContentAdminSupport.like(wd);
        players(p, c);
        c.serverLike = ContentAdminSupport.like(ContentAdminSupport.nonEmpty(p, "server"));
        String order = p.isArray("order") ? null : p.text("order");
        c.order = order != null && ORDERS.contains(order) ? order : null;
        c.repeat = !p.empty("repeat");
        return ContentAdminSupport.page(c, paging, wd, reader::countVods, reader::listVods);
    }

    /** 播放器、下载器筛选：{@code no} 为空或 no，否则包含匹配。 */
    private static void players(PhpForm p, AdminCriteria c) {
        String player = ContentAdminSupport.nonEmpty(p, "player");
        if ("no".equals(player)) {
            c.playerNo = true;
        } else {
            c.playerLike = ContentAdminSupport.like(player);
        }
        String downer = ContentAdminSupport.nonEmpty(p, "downer");
        if ("no".equals(downer)) {
            c.downerNo = true;
        } else {
            c.downerLike = ContentAdminSupport.like(downer);
        }
    }

    public Optional<AdminRow> info(byte[] id) {
        return AdminIds.lookupId(id).map(reader::selectVod).map(AdminRow::new);
    }

    /** 保存（旧 Vod::info 的 POST，{@code input('post.')} → {@code Vod::saveData}）。 */
    public AdminResult save(PhpForm post) {
        return AdminWrites.guard(() -> saveData(post.copy()));
    }

    /** 分集剧情保存（旧 Vod::iplot 的 POST → {@code Vod::savePlot}）。 */
    public AdminResult savePlot(PhpForm post) {
        return AdminWrites.guard(() -> savePlotData(post.copy()));
    }

    /** 删除（旧 Vod::del）：{@code ids} 时先删图片（vod_pic、vod_pic_thumb、vod_pic_slide）再删行；否则 {@code repeat} 时查重删除。 */
    public AdminResult delete(PhpForm param) {
        if (!param.empty("ids")) {
            return AdminWrites.guard(() -> {
                deleteRows(AdminIds.ids(param));
                return AdminResult.ok("del_ok");
            });
        }
        if (!param.empty("repeat")) {
            boolean max = "max".equals(param.isArray("retain") ? null : param.text("retain"));
            return ContentAdminSupport.deleteRepeat(max, reader::vodRepeatGroups,
                    (name, keep) -> ContentAdminWrites.run(() -> writer.mapper().deleteVodRepeat(name, keep)),
                    ids -> writer.delete(AdminTable.VOD.table(), ids));
        }
        return AdminResult.error("param_err");
    }

    public AdminResult field(PhpForm param) {
        return ContentAdminSupport.field(param, AdminTable.VOD, FIELD_COLS, types.cacheList(), writer);
    }

    /** 批量处理（旧 Vod::batch：从最后一页往前处理；另有版权、积分与删除播放组/下载组）。 */
    public AdminProgress batch(PhpForm param, UnaryOperator<String> lang) {
        try {
            return ContentBatch.run(BATCH, param, lang, new ContentBatch.Access() {
                @Override
                public long count(AdminCriteria c) {
                    return reader.countVods(c);
                }

                @Override
                public List<Map<String, String>> list(AdminCriteria c, long offset, long limit) {
                    return reader.listVods(c, offset, limit);
                }
            }, VodAdminService::batchCriteria, this::deleteMatching, writer, new Extras());
        } catch (AdminRejectedException e) {
            return ArtAdminService.failed(e.reason());
        } catch (AdminDataException e) {
            return ArtAdminService.failed(AdminMessage.lang("param_err"));
        }
    }

    public String today(PhpForm param) {
        boolean type = "type".equals(param.isArray("flag") ? null : param.text("flag"));
        return ContentAdminSupport.today(clock, reader::vodToday, type);
    }

    private static AdminCriteria batchCriteria(PhpForm p) {
        AdminCriteria c = new AdminCriteria();
        c.typeExact = ContentAdminSupport.nonEmpty(p, "type");
        c.level = ContentAdminSupport.nonEmpty(p, "level");
        c.status = ContentAdminSupport.zeroOne(p, "status");
        c.copyright = ContentAdminSupport.zeroOne(p, "copyright");
        c.isend = ContentAdminSupport.zeroOne(p, "isend");
        c.locked = ContentAdminSupport.nonEmpty(p, "lock");
        c.state = ContentAdminSupport.nonEmpty(p, "state");
        c.area = ContentAdminSupport.nonEmpty(p, "area");
        c.lang = ContentAdminSupport.nonEmpty(p, "lang");
        c.urlEmpty = !p.empty("url") && !p.isArray("url") && PhpLoose.equalsOne(p.scalar("url"));
        c.pic = ContentAdminSupport.pic(p);
        c.wdLike = ContentAdminSupport.like(ContentAdminSupport.wdHtml(p));
        c.wdNameOnly = true;
        c.weekdayLike = ContentAdminSupport.like(ContentAdminSupport.nonEmpty(p, "weekday"));
        players(p, c);
        c.order = "id_desc";
        return c;
    }

    private long deleteMatching(AdminCriteria c) {
        long n = 0;
        while (true) {
            List<Long> ids = new ArrayList<>();
            for (Map<String, String> r : reader.listVods(c, 0, AdminIds.MAX_IDS)) {
                ids.add(Long.parseLong(r.get("vod_id")));
            }
            if (ids.isEmpty()) {
                return n;
            }
            n += deleteRows(ids);
        }
    }

    private int deleteRows(List<Long> ids) {
        for (Map<String, String> r : reader.selectVodsByIds(ids)) {
            for (String col : List.of("vod_pic", "vod_pic_thumb", "vod_pic_slide")) {
                uploads.delete(r.get(col));
            }
        }
        return writer.delete(AdminTable.VOD.table(), ids);
    }

    /**
     * 旧 {@code Vod::saveData}（common/model/Vod.php:626-733）。播放组、下载组 {@code vod_play_from[]} 非空时四个数组各以 {@code $$$} 连接、
     * 地址里的换行去掉、回车换成 {@code #}，否则四列为空串；新增时剧情清空、记录添加时间，开启前台搜索优化时把新视频追加进已有的搜索结果。
     */
    private AdminResult saveData(PhpForm data) {
        if (!data.require("vod_name")) {
            return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_name"));
        }
        if (!data.require("type_id")) {
            return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_type"));
        }
        long now = clock.instant().getEpochSecond();
        ContentSaveSteps.typeParent(data, types.cacheList());
        ContentSaveSteps.pinyin(data, "vod_en", "vod_name");
        ContentSaveSteps.letter(data, "vod_letter", "vod_en");
        ContentSaveSteps.contentImages(data, "vod_content", config.text("upload", "protocol"));
        ContentSaveSteps.blurb(data, "vod_blurb", "vod_content");
        if (data.empty("vod_play_url")) {
            data.put("vod_play_url", "");
        }
        if (data.empty("vod_down_url")) {
            data.put("vod_down_url", "");
        }
        WebsiteAdminService.screenshots(data, "vod_pic_screenshot");
        groups(data, "vod_play");
        groups(data, "vod_down");
        ContentSaveSteps.uptime(data, "vod_time", now);
        ContentSaveSteps.uptag(data, "vod_tag");
        format(data);
        if (!data.empty("vod_id")) {
            long id = AdminIds.primaryKey(data.scalar("vod_id"));
            writer.update(AdminTable.VOD.table(), AdminRowValues.of(AdminTable.VOD, data, Set.of("vod_id")), id);
        } else {
            data.put("vod_plot", "0");
            data.put("vod_plot_name", "");
            data.put("vod_plot_detail", "");
            data.put("vod_time_add", Long.toString(now));
            data.put("vod_time", Long.toString(now));
            long id = writer.insert(AdminTable.VOD.table(), AdminRowValues.of(AdminTable.VOD, data, Set.of("vod_id")));
            if (id > 0 && optimise.frontendEnabled()) {
                Map<String, byte[]> vod = new LinkedHashMap<>();
                for (String k : data.names()) {
                    if (!data.isArray(k) && !data.isNull(k)) {
                        vod.put(k, data.scalar(k));
                    }
                }
                vod.put("vod_id", Long.toString(id).getBytes(StandardCharsets.US_ASCII));
                search.appendNewVod(id, vod);
            }
        }
        return AdminResult.ok("save_ok");
    }

    /** 播放组或下载组（{@code pre} 为 {@code vod_play}、{@code vod_down}）的四列。 */
    private static void groups(PhpForm data, String pre) {
        if (!data.empty(pre + "_from")) {
            data.put(pre + "_from", ArtAdminService.join(data, pre + "_from"));
            data.put(pre + "_server", ArtAdminService.join(data, pre + "_server"));
            data.put(pre + "_note", ArtAdminService.join(data, pre + "_note"));
            byte[] url = ArtAdminService.join(data, pre + "_url");
            url = PhpText.strReplace(url, new byte[] {'\n'}, new byte[0]);
            url = PhpText.strReplace(url, new byte[] {'\r'}, new byte[] {'#'});
            data.put(pre + "_url", url);
        } else {
            data.put(pre + "_from", "");
            data.put(pre + "_server", "");
            data.put(pre + "_note", "");
            data.put(pre + "_url", "");
        }
    }

    /** 旧 {@code VodValidate::formatDataBeforeDb}：列出的字段（{@code isset}）{@code mac_filter_xss} 后按字符数截断。 */
    private static void format(PhpForm data) {
        for (Map.Entry<String, Integer> e : FORMAT_FIELDS.entrySet()) {
            String f = e.getKey();
            if (data.has(f) && !data.isNull(f)) {
                AdminText.requireScalar(data, f);
                data.put(f, PhpText.mbSubstr(PhpText.filterXss(data.scalar(f)), e.getValue()));
            }
        }
    }

    /**
     * 旧 {@code Vod::savePlot}（common/model/Vod.php:735-770）：同样先验证名称与分类；{@code vod_plot_name[]} 非空时剧情标记为 1、名称与详情
     * 各以 {@code $$$} 连接，否则清空；有 {@code vod_id} 时按提交的字段整行写（表单里的名称、拼音、分类随之写回），没有时"保存失败："。
     */
    private AdminResult savePlotData(PhpForm data) {
        if (!data.require("vod_name")) {
            return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_name"));
        }
        if (!data.require("type_id")) {
            return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_type"));
        }
        if (!data.empty("vod_plot_name")) {
            data.put("vod_plot", "1");
            data.put("vod_plot_name", ArtAdminService.join(data, "vod_plot_name"));
            data.put("vod_plot_detail", ArtAdminService.join(data, "vod_plot_detail"));
        } else {
            data.put("vod_plot", "0");
            data.put("vod_plot_name", "");
            data.put("vod_plot_detail", "");
        }
        if (data.empty("vod_id")) {
            return AdminResult.error(AdminMessage.lang("save_err").thenText("："));
        }
        long id = AdminIds.primaryKey(data.scalar("vod_id"));
        writer.update(AdminTable.VOD.table(), AdminRowValues.of(AdminTable.VOD, data, Set.of("vod_id")), id);
        return AdminResult.ok("save_ok");
    }

    /** 视频批量处理独有的部分（旧 Vod::batch，admin/controller/Vod.php:150-160、268-335）。 */
    private static final class Extras implements ContentBatch.VodBatchExtras {

        @Override
        public AdminMessage precheck(PhpForm param) {
            if (ckDel(param, 2) && param.empty("player")) {
                return AdminMessage.lang("admin/vod/del_play_must_select_play");
            }
            if (ckDel(param, 3) && param.empty("downer")) {
                return AdminMessage.lang("admin/vod/del_down_must_select_down");
            }
            return null;
        }

        @Override
        public void copyright(PhpForm param, UnaryOperator<String> lang, Map<String, String> update, List<Part> des) {
            if (!param.empty("ck_copyright") && param.has("val_copyright") && !param.isNull("val_copyright")) {
                update.put("vod_copyright", value("vod_copyright", param.scalar("val_copyright")));
                des.add(Part.markup("&nbsp;"));
                // 旧版关闭时少了右方括号，照旧
                des.add(Part.text(lang.apply("copyright") + "：" + (PhpLoose.equalsOne(param.scalar("val_copyright"))
                        ? "[" + lang.apply("open") + "]" : "[" + lang.apply("close")) + "；"));
            }
        }

        @Override
        public void row(PhpForm param, UnaryOperator<String> lang, AdminRow v, Map<String, String> update, List<Part> des) {
            if (!param.empty("ck_points") && notBlank(param, "val_points_play")) {
                update.put("vod_points_play", value("vod_points_play", param.scalar("val_points_play")));
                des.add(Part.markup("&nbsp;"));
                des.add(Part.text(lang.apply("points_play") + "：" + ContentBatch.text(param, "val_points_play") + "；"));
            }
            if (!param.empty("ck_points") && notBlank(param, "val_points_down")) {
                update.put("vod_points_down", value("vod_points_down", param.scalar("val_points_down")));
                des.add(Part.markup("&nbsp;"));
                des.add(Part.text(lang.apply("points_down") + "：" + ContentBatch.text(param, "val_points_down") + "；"));
            }
            boolean play = ckDel(param, 2);
            if (!play && !ckDel(param, 3)) {
                return;
            }
            String pre = play ? "vod_play" : "vod_down";
            String par = play ? "player" : "downer";
            des.add(Part.markup("&nbsp;"));
            des.add(Part.text(lang.apply(play ? "play_group" : "down_group") + "："));
            String target = ContentBatch.text(param, par);
            if (PhpValues.looseEqualsString(target, v.s(pre + "_from"))) {
                update.put(pre + "_from", "");
                update.put(pre + "_server", "");
                update.put(pre + "_note", "");
                update.put(pre + "_url", "");
                des.add(Part.text(lang.apply("del_empty") + "；"));
                return;
            }
            List<String> from = explode(v.s(pre + "_from"));
            int key = -1;
            for (int i = 0; i < from.size(); i++) {
                if (PhpValues.looseEqualsString(target, from.get(i))) {
                    key = i;
                    break;
                }
            }
            if (key < 0) {
                des.add(Part.text(lang.apply("jump_over") + "；"));
                return;
            }
            for (String col : List.of("_from", "_server", "_note", "_url")) {
                List<String> parts = new ArrayList<>(explode(v.s(pre + col)));
                if (key < parts.size()) {
                    parts.remove(key);
                }
                update.put(pre + col, String.join("$$$", parts));
            }
            des.add(Part.text(lang.apply("del") + "；"));
        }

        /** {@code $param['ck_del'] == n}（宽松比较）。 */
        private static boolean ckDel(PhpForm param, int n) {
            return !param.isArray("ck_del") && param.scalar("ck_del") != null && PhpLoose.equalsNumber(param.scalar("ck_del"), n);
        }

        private static boolean notBlank(PhpForm p, String k) {
            byte[] v = p.scalar(k);
            return v != null && v.length > 0;
        }

        private static String value(String col, byte[] raw) {
            if (raw == null) {
                throw AdminRejectedException.paramError();
            }
            return AdminRowValues.value(AdminTable.VOD.column(col), raw);
        }

        /** PHP {@code explode('$$$', $s)}（空串得到一项空串）。 */
        private static List<String> explode(String s) {
            return List.of(s.split(java.util.regex.Pattern.quote("$$$"), -1));
        }
    }

    private static Map<String, Integer> formatFields() {
        Map<String, Integer> m = new LinkedHashMap<>();
        Object[][] list = {{"vod_name", 255}, {"vod_sub", 255}, {"vod_en", 255}, {"vod_color", 6}, {"vod_tag", 100},
            {"vod_class", 255}, {"vod_pic", 1024}, {"vod_pic_thumb", 1024}, {"vod_pic_slide", 1024},
            {"vod_pic_screenshot", 65535}, {"vod_actor", 255}, {"vod_director", 255}, {"vod_writer", 100},
            {"vod_behind", 100}, {"vod_blurb", 255}, {"vod_remarks", 100}, {"vod_pubdate", 100}, {"vod_serial", 20},
            {"vod_tv", 30}, {"vod_weekday", 30}, {"vod_area", 20}, {"vod_lang", 10}, {"vod_year", 10}, {"vod_version", 30},
            {"vod_state", 30}, {"vod_author", 60}, {"vod_jumpurl", 150}, {"vod_tpl", 30}, {"vod_tpl_play", 30},
            {"vod_tpl_down", 30}, {"vod_duration", 10}, {"vod_reurl", 255}, {"vod_rel_vod", 255}, {"vod_rel_art", 255},
            {"vod_pwd", 10}, {"vod_pwd_url", 255}, {"vod_pwd_play", 10}, {"vod_pwd_play_url", 255}, {"vod_pwd_down", 10},
            {"vod_pwd_down_url", 255}, {"vod_play_from", 255}, {"vod_play_server", 255}, {"vod_play_note", 255},
            {"vod_down_from", 255}, {"vod_down_server", 255}, {"vod_down_note", 255}};
        for (Object[] e : list) {
            m.put((String) e[0], (Integer) e[1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }
}
