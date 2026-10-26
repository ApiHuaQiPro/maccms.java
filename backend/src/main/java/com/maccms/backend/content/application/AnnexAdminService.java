package com.maccms.backend.content.application;

import com.maccms.backend.content.application.view.AdminMessage;
import com.maccms.backend.content.application.view.AdminProgress;
import com.maccms.backend.content.application.view.AdminProgress.Line;
import com.maccms.backend.content.application.view.AdminProgress.Part;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import com.maccms.backend.content.application.view.AnnexDirectory;
import com.maccms.backend.content.infrastructure.AdminCriteria;
import com.maccms.backend.content.infrastructure.ContentAdminMapper;
import com.maccms.backend.content.infrastructure.ContentAdminWrites;
import com.maccms.backend.content.infrastructure.UploadStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 后台附件（B13，22 文档第 7.10 节）：旧 {@code admin/controller/Annex.php} → {@code common/model/Annex.php}。列表、查看、删除（连同
 * upload/ 下的文件）、目录浏览、检查（删掉文件已不存在的登记）与初始化（把各表里引用的本地图片补登记）；另供上传与会员头像登记附件。
 */
public class AnnexAdminService {

    /** 旧检查、初始化每页处理的行数。 */
    static final int PAGE_SIZE = 500;
    /** 旧 Annex::init 依次处理的表（演员出现两次，照旧）。 */
    static final List<String> INIT_TABLES = List.of("actor", "art", "topic", "type", "vod", "website", "actor", "role");
    /** 旧 init 取正文图片的正则（mac_buildregx(…, "is")）。 */
    private static final Pattern CONTENT_IMG = Pattern.compile(
            "<img[^>]*src\\s*=\\s*['\"]?([\\w/\\-:.]*)['\"]?[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BLANKS = Pattern.compile("[ \r\n\t\f]+");

    private final ContentAdminMapper reader;
    private final ContentAdminWrites writer;
    private final UploadStore uploads;
    private final Clock clock;

    public AnnexAdminService(ContentAdminMapper reader, ContentAdminWrites writer, UploadStore uploads, Clock clock) {
        this.reader = reader;
        this.writer = writer;
        this.uploads = uploads;
        this.clock = clock;
    }

    /**
     * 列表（旧 Annex::data）：类型、关键字（文件名包含，{@code htmlspecialchars(urldecode())}），按时间倒序。旧版另有 {@code mid} 条件，
     * 但 mac_annex 没有这一列（旧版报 SQL 错误），新版不加这个条件（22 文档第 10 节）。
     */
    public AdminRowPage list(PhpForm p, AdminPaging paging) {
        AdminCriteria c = new AdminCriteria();
        c.typeExact = ContentAdminSupport.nonEmpty(p, "type");
        String wd = ContentAdminSupport.wdHtml(p);
        c.wdLike = ContentAdminSupport.like(wd);
        return ContentAdminSupport.page(c, paging, wd, reader::countAnnexes, reader::listAnnexes);
    }

    public Optional<AdminRow> info(byte[] id) {
        return AdminIds.lookupId(id).map(reader::selectAnnex).map(AdminRow::new);
    }

    /** 保存（旧 Annex::info 的 POST → {@code Annex::saveData}）：文件名必填；有编号时整行覆盖，否则新增并记录时间。 */
    public AdminResult save(PhpForm post) {
        return AdminWrites.guard(() -> {
            PhpForm data = post.copy();
            if (!data.require("annex_file")) {
                return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_name"));
            }
            if (!data.empty("annex_id")) {
                long id = AdminIds.primaryKey(data.scalar("annex_id"));
                writer.update(AdminTable.ANNEX.table(), AdminRowValues.of(AdminTable.ANNEX, data, Set.of("annex_id")), id);
            } else {
                data.put("annex_time", Long.toString(clock.instant().getEpochSecond()));
                writer.insert(AdminTable.ANNEX.table(), AdminRowValues.of(AdminTable.ANNEX, data, Set.of("annex_id")));
            }
            return AdminResult.ok("save_ok");
        });
    }

    /**
     * 删除（旧 Annex::del）：{@code ids} 为数组时每项去掉 {@code ./}，为文字时按逗号拆分；编号或文件名在其中的登记行连同 upload/ 下的文件一起删
     * （文件名含 {@code ../} 的不删文件）。编号按 MySQL 把文字比作整数的规则取（{@code 12abc} 当 12）。
     */
    public AdminResult delete(PhpForm param) {
        if (param.empty("ids")) {
            return AdminResult.error("param_err");
        }
        return AdminWrites.guard(() -> {
            List<String> items = new ArrayList<>();
            if (param.isArray("ids")) {
                for (byte[] v : param.array("ids").entries().values()) {
                    items.add(PhpText.strReplace(AdminText.utf8(v), "./", ""));
                }
            } else {
                for (String v : AdminText.utf8(param.scalar("ids")).split(",", -1)) {
                    items.add(v);
                }
            }
            if (items.size() > AdminIds.MAX_IDS) {
                throw AdminRejectedException.paramError();
            }
            List<Long> ids = new ArrayList<>();
            for (String v : items) {
                Long n = mysqlInt(v);
                if (n != null && n > 0) {
                    ids.add(n);
                }
            }
            List<Long> rowIds = new ArrayList<>();
            for (Map<String, String> r : reader.selectAnnexesByIdsOrFiles(ids, items)) {
                String file = r.get("annex_file");
                if (!file.toLowerCase(java.util.Locale.ROOT).contains("../")) {
                    uploads.delete(file);
                }
                rowIds.add(Long.parseLong(r.get("annex_id")));
            }
            writer.delete(AdminTable.ANNEX.table(), rowIds);
            return AdminResult.ok("del_ok");
        });
    }

    /** MySQL 把文字与整数列比较时的取值：前导空白、可选正号与数字；之后是非零小数或指数时不等于任何整数（返回 null）。 */
    static Long mysqlInt(String v) {
        Matcher m = Pattern.compile("^[ \t\n\r]*\\+?(\\d{1,18})(.*)$", Pattern.DOTALL).matcher(v);
        if (!m.matches()) {
            return null;
        }
        String rest = m.group(2);
        if (rest.matches("^\\.\\d*[1-9].*") || rest.matches("^[eE][+-]?\\d.*") || rest.matches("^\\.\\d*[eE][+-]?\\d.*")) {
            return null;
        }
        return Long.parseLong(m.group(1));
    }

    /**
     * 目录浏览（旧 Annex::file）：{@code path} 去掉斜杠与反斜杠，以 {@code @} 表示目录分隔（{@code @upload@vod}）；为空或不以
     * {@code @upload} 开头时为 {@code @upload}；含 {@code ..@} 时"非法请求"。新版只列 upload/ 之内的目录（旧版 {@code @upload@..} 会列出站点
     * 根目录，22 文档 D-B13-8）。文件不含路径里有 {@code .htm} 的。
     *
     * @return null 表示"非法请求"
     */
    public AnnexDirectory files(String pathParam) {
        String path = pathParam == null ? "" : pathParam.replace("\\", "").replace("/", "");
        if (path.isEmpty() || !path.startsWith("@upload")) {
            path = "@upload";
        }
        if (path.contains("..@")) {
            return null;
        }
        String uppath = path.substring(0, path.lastIndexOf('@'));
        boolean child = !path.equals("@upload");
        List<AnnexDirectory.Item> items = new ArrayList<>();
        int dirs = 0;
        int files = 0;
        long sum = 0;
        String rel = path.equals("@upload") ? "" : path.substring("@upload".length()).replace('@', '/');
        if (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        List<UploadStore.Entry> list = path.startsWith("@upload@") || path.equals("@upload") ? uploads.list(rel) : null;
        if (list != null) {
            String base = "./upload" + (rel.isEmpty() ? "" : "/" + rel);
            for (UploadStore.Entry e : list) {
                String f = base + "/" + e.name();
                String tmp = f.replace("./upload/", "@upload/").replace('/', '@');
                String name = tmp.replace(path + "@", "");
                if (e.directory()) {
                    dirs++;
                    items.add(new AnnexDirectory.Item(false, name, tmp, "", 0));
                } else if (!f.contains(".html") && !f.contains(".htm")) {
                    files++;
                    sum += e.size();
                    items.add(new AnnexDirectory.Item(true, name, tmp.replace('@', '/'), formatSize(e.size()), e.modified()));
                }
            }
        }
        return new AnnexDirectory(uppath, child, items, dirs, files, formatSize(sum));
    }

    /** 旧 {@code mac_format_size($s)}：0 为 {@code 0 kb}，否则按 1024 进位保留两位小数加单位（b kb mb gb tb pb）。 */
    static String formatSize(long s) {
        if (s == 0) {
            return "0 kb";
        }
        String[] unit = {"b", "kb", "mb", "gb", "tb", "pb"};
        int i = (int) Math.floor(Math.log(s) / Math.log(1024));
        double v = s / Math.pow(1024, i);
        return PhpNumbers.round2Text(v) + " " + unit[i];
    }

    /**
     * 附件检查（旧 Annex::check）：每页 500 条（从最后一页往前），文件已不存在的登记按文件名删除，3 秒后到下一页；处理完时提示完成。旧版第二页起
     * 引用未定义的变量报错（500），新版每页都按全部登记行处理（22 文档第 10 节）。
     */
    public AdminProgress check(PhpForm param, UnaryOperator<String> lang) {
        List<Line> out = new ArrayList<>();
        out.add(Line.of(Part.markup(ContentBatch.STYLE)));
        LinkedHashMap<String, String> q = ContentBatch.query(param);
        long start = PhpIntval.of(ContentBatch.text(param, "start"));
        long pageCount = PhpIntval.of(ContentBatch.text(param, "page_count"));
        long dataCount = PhpIntval.of(ContentBatch.text(param, "data_count"));
        if (start < 1) {
            start = 1;
        }
        if (pageCount < 1) {
            pageCount = 1;
        }
        if (dataCount == 0) {
            dataCount = reader.countAnnexes(new AdminCriteria());
            pageCount = (dataCount + PAGE_SIZE - 1) / PAGE_SIZE;
            q.put("data_count", Long.toString(dataCount));
            q.put("page_count", Long.toString(pageCount));
            q.put("page_size", Integer.toString(PAGE_SIZE));
        }
        if (start > pageCount) {
            out.add(Line.of(Part.text(lang.apply("admin/annex/check_complete"))));
            return new AdminProgress(out, null, null);
        }
        out.add(Line.of(Part.text(ContentBatch.sprintf(lang.apply("admin/annex/info_tip"), q.getOrDefault("data_count", ""),
                q.getOrDefault("page_count", ""), q.getOrDefault("page_size", ""), Long.toString(start)))));
        for (Map<String, String> r : reader.listAnnexes(new AdminCriteria(), PAGE_SIZE * (pageCount - start), PAGE_SIZE)) {
            String file = r.get("annex_file");
            if (!uploads.exists(file)) {
                ContentAdminWrites.run(() -> writer.mapper().deleteAnnexByFile(file));
                out.add(Line.of(Part.text(file + "...del")));
            }
        }
        q.put("start", Long.toString(start + 1));
        return new AdminProgress(out, q, null);
    }

    /**
     * 附件初始化（旧 Annex::init，{@code ck} 非空时）：依次处理各表（{@code tbi}），每页 500 行（从最后一页往前），把行里 upload/ 下存在的
     * 图片（图片、缩略图、幻灯图列与正文里 {@code /upload} 开头的图片）补登记为附件。旧版取表里没有的列时报错（500），把同一页里先登记的图片
     * 反复再插入（重复行）；新版只取表里有的列，每个文件只登记一次（22 文档第 10 节）。
     *
     * @return {@code ck} 为空时为 null（显示初始化页）
     */
    public AdminProgress init(PhpForm param, UnaryOperator<String> lang) {
        if (param.empty("ck")) {
            return null;
        }
        List<Line> out = new ArrayList<>();
        out.add(Line.of(Part.markup(ContentBatch.STYLE)));
        LinkedHashMap<String, String> q = ContentBatch.query(param);
        long start = PhpIntval.of(ContentBatch.text(param, "start"));
        if (start < 1) {
            start = 1;
        }
        long tbi = PhpIntval.of(ContentBatch.text(param, "tbi"));
        q.put("tbi", Long.toString(tbi));
        if (tbi >= INIT_TABLES.size() || tbi < 0) {
            out.add(Line.of(Part.text(lang.apply("admin/annex/check_ok"))));
            return new AdminProgress(out, null, null);
        }
        String tab = INIT_TABLES.get((int) tbi);
        long count = reader.countAnnexScan(tab);
        long pageCount = (count + PAGE_SIZE - 1) / PAGE_SIZE;
        if (start > pageCount) {
            out.add(Line.of(Part.text(ContentBatch.sprintf(lang.apply("admin/annex/check_jump"), tab))));
            q.put("tbi", Long.toString(tbi + 1));
            q.put("start", "1");
            return new AdminProgress(out, q, null);
        }
        out.add(Line.of(Part.text(ContentBatch.sprintf(lang.apply("admin/annex/check_tip1"), tab, Long.toString(count),
                Long.toString(pageCount), Integer.toString(PAGE_SIZE), Long.toString(start)))));
        long now = clock.instant().getEpochSecond();
        for (Map<String, String> r : reader.listAnnexScan(tab, PAGE_SIZE * (pageCount - start), PAGE_SIZE)) {
            Set<String> imgs = new LinkedHashSet<>();
            for (String col : List.of("pic", "thumb", "slide")) {
                String v = r.get(col);
                if (v != null && v.startsWith("upload") && uploads.exists(v)) {
                    imgs.add(v);
                }
            }
            String content = r.get("content");
            if (content != null && !content.isEmpty()) {
                Matcher m = CONTENT_IMG.matcher(content);
                while (m.find()) {
                    String src = BLANKS.matcher(m.group(1)).replaceAll(" ").strip();
                    if (src.startsWith("/upload") && uploads.exists(src.substring(1))) {
                        imgs.add(src.substring(1));
                    }
                }
            }
            boolean added = false;
            for (String f : imgs) {
                if (register(f, "image", Long.toString(uploads.size(f)), now)) {
                    added = true;
                }
            }
            String name = new String(PhpText.stripTags((r.get("name") == null ? "" : r.get("name")).getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8);
            out.add(Line.of(Part.text(name + "..."), added ? Part.markup("<font color=green>ok</font>")
                    : Part.markup("<font color=red>" + escape(lang.apply("skip")) + "</font>")));
        }
        q.put("start", Long.toString(start + 1));
        return new AdminProgress(out, q, null);
    }

    /**
     * 登记一个附件（旧 {@code Annex::infoData(['annex_file' => …])} 后 {@code saveData}）：同名文件已登记时什么也不做。上传（B13）与会员头像
     * （D-B09-9）也用这里登记。
     *
     * @param size 旧版写入的大小（上传为 KB 保留两位小数，初始化与缩略图为字节；写库时按 MySQL 整数列的规则取整）
     * @return 是否新登记
     */
    public boolean register(String file, String type, String size) {
        return register(file, type, size, clock.instant().getEpochSecond());
    }

    private boolean register(String file, String type, String size, long now) {
        if (reader.selectAnnexByFile(file) != null) {
            return false;
        }
        PhpForm row = PhpForm.empty();
        row.put("annex_file", file);
        row.put("annex_type", type);
        row.put("annex_size", size);
        row.put("annex_time", Long.toString(now));
        writer.insert(AdminTable.ANNEX.table(), AdminRowValues.of(AdminTable.ANNEX, row, Set.of()));
        return true;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
