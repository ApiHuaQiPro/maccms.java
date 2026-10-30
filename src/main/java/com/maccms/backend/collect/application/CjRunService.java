package com.maccms.backend.collect.application;

import com.maccms.backend.collect.infrastructure.CjMapper;
import com.maccms.backend.collect.infrastructure.CjWrites;
import com.maccms.backend.collect.infrastructure.CollectAdminMapper;
import com.maccms.backend.collect.infrastructure.CollectAdminWrites;
import com.maccms.backend.collect.infrastructure.CollectDataException;
import com.maccms.backend.collect.infrastructure.LocalHtmlFiles;
import com.maccms.backend.content.application.AdminIds;
import com.maccms.backend.content.application.AdminPaging;
import com.maccms.backend.content.application.AdminRejectedException;
import com.maccms.backend.content.application.AdminTable;
import com.maccms.backend.content.application.PhpForm;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.content.application.PhpJson;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * 自定义规则的采集与发布（B20，28 文档第 5、6 节）：旧 {@code admin/controller/Cj.php} 的 {@code show_url}、{@code col_url}、{@code col_content}、
 * {@code publish}、{@code show}、{@code content_del}、{@code content_into}、{@code program}、{@code col_all}。规则的执行见 {@link CjRules}，页面来自
 * 本地 HTML 目录（{@link LocalHtmlFiles}），不发请求。写库只有 {@code mac_cj_history}、{@code mac_cj_content}（本批登记）与节点的采集时间、
 * 发布方案（B17 登记的 {@code mac_cj_node} UPDATE）；导入经 B19 的 {@link CollectIngestService}（同一把入库锁、同一套规则）。
 *
 * <p>并发：同一网址的采集历史用条件插入记下，只有记下的那个请求写内容行；内容采集只改仍是"未采集"的行；发布只把"已采集"改成"已发布"；
 * 导入的查重与写入在入库锁内。所以两个后台请求同时操作同一个节点也不会写出重复行（28 文档第 6 节）。
 */
public class CjRunService {

    /** 旧 col_content 每批采集的条数（{@code $limit = 20}）。 */
    public static final int CONTENT_BATCH = 20;
    /** 旧 col_all 导入时的每页条数（{@code $param['limit'] = 999}）。 */
    static final int ALL_LIMIT = 999;
    /** 删除内容时一批取出的网址数。 */
    static final int URL_BATCH = 500;

    private final CollectAdminMapper nodes;
    private final CjMapper reader;
    private final CjWrites writer;
    private final CollectAdminWrites nodeWriter;
    private final LocalHtmlFiles html;
    private final CollectIngestService ingest;
    private final Clock clock;

    public CjRunService(CollectAdminMapper nodes, CjMapper reader, CjWrites writer, CollectAdminWrites nodeWriter, LocalHtmlFiles html,
                        CollectIngestService ingest, Clock clock) {
        this.nodes = nodes;
        this.reader = reader;
        this.writer = writer;
        this.nodeWriter = nodeWriter;
        this.html = html;
        this.ingest = ingest;
        this.clock = clock;
    }

    /** 本地 HTML 目录是否已配置。 */
    public boolean htmlConfigured() {
        return html.configured();
    }

    /** 节点（旧 {@code infoData('cj_node', nodeid = id)}）；没有时为空（旧版"获取失败"）。 */
    public Optional<Map<String, String>> node(byte[] id) {
        return AdminIds.lookupId(id).map(nodes::selectCjNode);
    }

    private CjRules rules(Map<String, String> node) {
        return new CjRules(node, url -> html.read(CjRules.bytes(url)).orElse(null), () -> clock.instant().getEpochSecond());
    }

    /** 字节文字的显示（按 UTF-8 解释，非法字节换成替换符）。 */
    static String text(String bytes) {
        return new String(CjRules.bytes(bytes), StandardCharsets.UTF_8);
    }

    private static String localPath(String url) {
        return LocalHtmlFiles.relativePath(CjRules.bytes(url)).orElse(null);
    }

    // ------------------------------------------------------------------ show_url

    /** 一个列表页地址与它在本地 HTML 目录里的位置（不能映射时 null）、文件是否存在。 */
    public record UrlEntry(String url, String localPath, boolean exists) {
    }

    /**
     * 旧 {@code Cj::show_url}（规则表单的"测试"）：按表单里的 {@code data} 与 {@code urlpage<sourcetype>} 展开列表页地址（不读库）。新版另列出
     * 每个地址对应的本地文件与是否存在（新系统运行所需的最小补充）。规则无法展开（页码递增为 0、超过上限）时 {@link CjRules.RuleException}。
     */
    public List<UrlEntry> showUrl(Object data, PhpForm param) {
        if (data != null && !(data instanceof Map<?, ?>)) {
            throw AdminRejectedException.paramError();
        }
        Map<String, String> node = new LinkedHashMap<>();
        if (data instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (v instanceof byte[] b) {
                    node.put(String.valueOf(k), new String(b, StandardCharsets.UTF_8));
                }
            });
        }
        String type = node.getOrDefault("sourcetype", "");
        if (param.isArray("urlpage" + type)) {
            throw AdminRejectedException.paramError();
        }
        byte[] up = param.scalar("urlpage" + type);
        node.put("urlpage", up == null ? "" : new String(up, StandardCharsets.UTF_8));
        List<UrlEntry> out = new ArrayList<>();
        for (String u : rules(node).urlList()) {
            String p = localPath(u);
            out.add(new UrlEntry(text(u), p, p != null && html.read(CjRules.bytes(u)).isPresent()));
        }
        return out;
    }

    // ------------------------------------------------------------------ col_url

    /** 列表页里的一条网址与它的处理结果。 */
    public record LinkRow(String url, String title) {
    }

    /**
     * 一次 {@code col_url} 的结果：{@code page} 是本次的页码（从 1 起），{@code totalPage} 是列表页总数；{@code total} 是本页取到的网址数，{@code added} 是新记下的数，
     * {@code re} 是重复或缺名称、地址而跳过的数，{@code bad} 是不符合表结构（超长、非法字符）而没有记下的数（新版）；{@code missing} 为真时
     * 本页的本地文件不存在。
     */
    public record ColUrl(String listUrl, String localPath, boolean missing, long page, int totalPage, int total, int re, int bad,
                         List<LinkRow> rows, int added) {
        /** 还有下一页（旧 {@code $total_page > $param['page']}）。 */
        public boolean more() {
            return totalPage > page;
        }
    }

    /**
     * 旧 {@code Cj::col_url}（{@code admin/controller/Cj.php:134-198}）：展开列表页地址；取第 {@code page} 页（没有 {@code page} 时 1），按规则取出
     * 内容网址；名称或地址为空的、采集历史里已有的计为重复；其余记下采集历史（{@code md5(网址)}）并新增"未采集"的内容行。最后一页时写节点的采集
     * 时间。列表页地址一个都没有时 {@link CjRules.RuleException}（旧 {@code url_list_err}）。
     */
    public ColUrl colUrl(Map<String, String> node, byte[] pageParam, UnaryOperator<String> lang) {
        long nodeid = Long.parseLong(node.get("nodeid"));
        CjRules rules = rules(node);
        List<String> urls = rules.urlList();
        if (urls.isEmpty()) {
            throw new CjRules.RuleException(lang.apply("admin/cj/url_list_err"));
        }
        long page = pageParam == null ? 1 : PhpIntval.of(new String(pageParam, StandardCharsets.ISO_8859_1));
        String listUrl = page >= 1 && page <= urls.size() ? urls.get((int) (page - 1)) : "";
        LinkedHashMap<Long, CjRules.Link> links = listUrl.isEmpty() ? null : rules.urlLists(listUrl);
        int re = 0;
        int bad = 0;
        List<LinkRow> rows = new ArrayList<>();
        int added = 0;
        if (links != null) {
            for (CjRules.Link v : links.values()) {
                rows.add(new LinkRow(text(v.url()), text(v.title())));
                if (empty(v.url()) || empty(v.title())) {
                    re++;
                    continue;
                }
                String title = new String(com.maccms.backend.content.application.PhpText.stripTags(CjRules.bytes(v.title())),
                        StandardCharsets.ISO_8859_1);
                String url = CjRules.decode(v.url());
                String t = CjRules.decode(title);
                if (!fits(url, 255) || !fits(t, 100)) {
                    bad++;
                    continue;
                }
                String md5 = CollectBindings.flag(CjRules.bytes(v.url()));
                if (reader.countHistory(md5) > 0 || writer.insertHistory(md5) == 0) {
                    re++;
                    continue;
                }
                try {
                    writer.insertContent(nodeid, url, t);
                    added++;
                } catch (CollectDataException e) {
                    // 写不进去时撤回采集历史，下次还能再采这个网址
                    nodeWriter.deleteCjHistories(List.of(md5));
                    bad++;
                }
            }
        }
        if (urls.size() <= page) {
            touch(nodeid);
        }
        return new ColUrl(text(listUrl), listUrl.isEmpty() ? null : localPath(listUrl), !listUrl.isEmpty() && links == null, page,
                urls.size(), links == null ? 0 : links.size(), re, bad, rows, added);
    }

    /** 能写进 utf8 表的文字：合法 UTF-8、不超过 {@code max} 个字符、没有 4 字节字符。 */
    private static boolean fits(String s, int max) {
        return s != null && s.codePointCount(0, s.length()) <= max && s.codePoints().noneMatch(c -> c > 0xffff);
    }

    private void touch(long nodeid) {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("lastdate", Long.toString(clock.instant().getEpochSecond()));
        nodeWriter.updateCjNode(c, nodeid);
    }

    // ------------------------------------------------------------------ col_content

    /** 一条内容的采集结果：{@code ok} 为真时已写入；{@code note} 是没写入或页面缺失的说明（新版）。 */
    public record ContentLine(long id, String url, boolean ok, String note) {
    }

    /** 一批内容采集：{@code empty} 为真时开始时就没有"未采集"的内容（旧版"内容采集完成"）；{@code remaining} 是这一批之后还剩的条数。 */
    public record ColContent(long total, long totalPage, long page, List<ContentLine> lines, List<Long> ids, boolean empty, long remaining) {
    }

    /**
     * 旧 {@code Cj::col_content}（{@code Cj.php:201-253}）：取节点下 20 条"未采集"的内容，逐条按规则从本地页面取出各字段，写成
     * {@code json_encode} 的文字并改为"已采集"（取不到页面时写 {@code null}，同旧版）。同一批共用一个 {@link CjRules}（旧版分页时访问过的地址
     * 在一次请求内累积）。还有"未采集"的内容时由调用方跳到下一批（旧版按页码判断结束，会剩下一部分，D-B20-8）；采完时写节点的采集时间。
     */
    public ColContent colContent(Map<String, String> node, byte[] pageParam) {
        long nodeid = Long.parseLong(node.get("nodeid"));
        long page = pageParam == null ? 1 : Math.max(1, PhpIntval.of(new String(pageParam, StandardCharsets.ISO_8859_1)));
        long total = reader.countPending(nodeid);
        long totalPage = (total + CONTENT_BATCH - 1) / CONTENT_BATCH;
        List<Map<String, String>> list = reader.listPending(nodeid, CONTENT_BATCH);
        if (list.isEmpty()) {
            return new ColContent(total, totalPage, page, List.of(), List.of(), true, 0);
        }
        CjRules rules = rules(node);
        List<ContentLine> lines = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (Map<String, String> v : list) {
            long id = Long.parseLong(v.get("id"));
            String url = CjRules.utf8(v.get("url"));
            String note = null;
            String data;
            try {
                LinkedHashMap<Object, Object> r = rules.content(url);
                if (r == null) {
                    note = html.configured() ? "本地文件不存在（" + nvl(localPath(url)) + "）" : "未配置本地 HTML 目录";
                }
                data = CjRules.json(r);
            } catch (CjRules.RuleException e) {
                note = e.reason();
                data = "null";
            }
            boolean ok;
            try {
                ok = writer.updateCollected(id, nodeid, data) > 0;
                if (!ok) {
                    note = "已由其他请求采集";
                }
            } catch (CollectDataException e) {
                ok = false;
                note = "采集结果写不进内容表";
            }
            if (ok) {
                ids.add(id);
            }
            lines.add(new ContentLine(id, v.get("url"), ok && note == null, note));
        }
        long remaining = reader.countPending(nodeid);
        if (remaining == 0) {
            touch(nodeid);
        }
        return new ColContent(total, totalPage, page, lines, ids, false, remaining);
    }

    private static String nvl(String s) {
        return s == null ? "网址不能映射到本地文件" : s;
    }

    // ------------------------------------------------------------------ publish、show、content_del

    /**
     * 旧 {@code Cj::publish}（{@code Cj.php:256-282}）：节点下的内容，{@code status} 非 {@code empty()} 时按状态筛选，{@code id desc}；页码
     * {@code intval < 1} 时 1，每页 {@code intval < 20} 时为后台每页条数。
     */
    public AdminRowPage publish(byte[] id, byte[] status, byte[] page, byte[] limit, int pageSize) {
        long nodeid = AdminIds.lookupId(id).orElse(0L);
        String st = PhpForm.phpEmpty(status) ? null : new String(status, StandardCharsets.UTF_8);
        long p = page == null ? 0 : PhpIntval.of(new String(page, StandardCharsets.ISO_8859_1));
        long l = limit == null ? 0 : PhpIntval.of(new String(limit, StandardCharsets.ISO_8859_1));
        AdminPaging paging = new AdminPaging(p < 1 ? 1 : p, (int) (l < 20 ? pageSize : Math.min(l, AdminPaging.MAX_LIMIT)));
        long total = reader.countContents(nodeid, st);
        List<AdminRow> rows = new ArrayList<>();
        if (!paging.beyond()) {
            for (Map<String, String> r : reader.listContents(nodeid, st, paging.offset(), paging.limit())) {
                rows.add(new AdminRow(r));
            }
        }
        return new AdminRowPage(rows, total, paging.page(), paging.limit(), null);
    }

    /** 查看一条内容：行（没有时为空行）与 {@code json_decode(data, true)} 的各项（键 → 显示文字）。 */
    public record Shown(AdminRow info, Map<String, String> data) {
    }

    /** 旧 {@code Cj::show}（{@code Cj.php:285-296}）：{@code where id = id}；{@code data} 解码后逐项显示。 */
    public Shown show(byte[] id) {
        Map<String, String> row = AdminIds.lookupId(id).map(reader::selectContent).orElse(null);
        Map<String, String> data = new LinkedHashMap<>();
        if (row != null) {
            Object d = PhpJson.decode(row.get("data"));
            if (d instanceof Map<?, ?> m) {
                m.forEach((k, v) -> data.put(String.valueOf(k), v instanceof String s ? s : v == null ? "" : PhpJson.encode(v)));
            } else if (d instanceof List<?> l) {
                for (int i = 0; i < l.size(); i++) {
                    Object v = l.get(i);
                    data.put(Integer.toString(i), v instanceof String s ? s : v == null ? "" : PhpJson.encode(v));
                }
            }
        }
        return new Shown(new AdminRow(row == null ? Map.of() : row), data);
    }

    /**
     * 旧 {@code Cj::content_del}（{@code Cj.php:298-321}）：{@code ids} 非空时删除这些内容（{@code all=1} 时是全部节点的全部内容，照旧），先按内容
     * 网址删采集历史。旧版只删了最后一个网址的采集历史（{@code $where2['md5'] = ['in', $md5]} 用错了变量），删掉的网址再采时仍被当作重复；
     * 新版删全部（D-B20-10）。{@code ids} 为空时不删，照旧"删除成功"。
     */
    public void contentDel(PhpForm param) {
        List<Long> ids = AdminIds.ids(param);
        if (ids == null) {
            return;
        }
        boolean all = "1".equals(param.text("all"));
        long after = 0;
        while (true) {
            List<Map<String, String>> batch = reader.listContentUrls(all ? null : ids, after, URL_BATCH);
            if (batch.isEmpty()) {
                break;
            }
            List<String> md5 = new ArrayList<>();
            for (Map<String, String> r : batch) {
                md5.add(CollectBindings.flag(r.get("url")));
                after = Long.parseLong(r.get("id"));
            }
            nodeWriter.deleteCjHistories(md5);
            if (batch.size() < URL_BATCH) {
                break;
            }
        }
        if (all) {
            writer.deleteAllContents();
        } else {
            writer.deleteContents(ids);
        }
    }

    // ------------------------------------------------------------------ content_into

    /** 导入的一行：{@code green} 为真时入库成功（新增或更新），{@code msg} 是旧版输出的说明。 */
    public record IntoLine(long id, boolean green, String msg) {
    }

    /**
     * 一页导入：{@code after} 是本页最后一行的编号（下一页从它之后接着取，D-B20-9），{@code more} 为真时还有下一页。
     */
    public record Into(long total, long totalPage, int limit, long page, List<IntoLine> lines, long after, boolean more) {
    }

    /**
     * 旧 {@code Cj::content_into}（{@code Cj.php:323-424}）：节点下"已采集"的内容（{@code ids} 或 {@code all=1} 时全部），按发布方案
     * （{@code program_config} 的 {@code map}、{@code funcs}）把采集到的各字段换成视频或文章的列，经 B19 入库（与 Receive、资源站入库同一把锁、
     * 同一套规则；{@code $param} 为空数组），成功的行改为"已发布"。分类字段（节点字段 {@code type}）不是数字时按 {@code interface.vodtype}/
     * {@code arttype} 的分类名映射换成编号。
     *
     * @param total 跳转带回的总数（第一页时为 null，按条件计数）
     * @param after 上一页最后一行的编号（第一页为 0）
     */
    public Into contentInto(Map<String, String> node, List<Long> ids, boolean all, long page, int limit, Long total, long after,
                            UnaryOperator<String> lang) {
        long nodeid = Long.parseLong(node.get("nodeid"));
        List<Long> filter = all ? null : ids == null ? List.of() : ids;
        long count = total != null ? total : filter != null && filter.isEmpty() ? 0 : reader.countImportable(nodeid, filter);
        long totalPage = (count + limit - 1) / limit;
        List<Map<String, String>> list = filter != null && filter.isEmpty() ? List.of() : reader.listImportable(nodeid, filter, after, limit);
        Object program = PhpJson.decode(node.get("program_config"));
        Map<?, ?> map = program instanceof Map<?, ?> pm && pm.get("map") instanceof Map<?, ?> m ? m : null;
        Map<?, ?> funcs = program instanceof Map<?, ?> pm2 && pm2.get("funcs") instanceof Map<?, ?> f ? f : Map.of();
        boolean art = "2".equals(node.get("mid"));
        Map<Object, Object> inter = ReceiveService.interfaceTypes(ingest.maccms(), art ? "arttype" : "vodtype", ingest.typeIdsByName());
        List<IntoLine> lines = new ArrayList<>();
        List<Long> published = new ArrayList<>();
        long last = after;
        for (Map<String, String> v : list) {
            long id = Long.parseLong(v.get("id"));
            last = id;
            if (map == null || map.isEmpty()) {
                // 旧版没有发布方案时 vod_data 没有行可入库，返回 null，只输出空行
                lines.add(new IntoLine(id, false, ""));
                continue;
            }
            Object decoded = PhpJson.decode(v.get("data"));
            Map<?, ?> content = decoded instanceof Map<?, ?> cm ? cm : Map.of();
            Map<Object, Object> row = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String a = String.valueOf(e.getKey());
                String b = e.getValue() instanceof String s ? s : String.valueOf(e.getValue());
                Object value = content.get(b);
                if ("trim".equals(funcs.get(a))) {
                    // 旧版这里取的是 $v['data'][$b]（JSON 文字的下标，PHP 7.3 得到 "{"，8.0 报错）；新版按原意对字段值去空格（D-B20-11）
                    value = value == null ? "" : CjRules.trim(IngestPhp.str(value));
                }
                if (b.equals("type") && !IngestPhp.isNumeric(content.get(b) instanceof String s ? s : null)) {
                    value = inter.get(ProvideValues.key(IngestPhp.str(content.get(b))));
                }
                row.put(ProvideValues.key(a), value);
            }
            try {
                VodIngest.Outcome o = art ? ingest.kind(KindIngest.Kind.ART, row, lang) : ingest.vod(row, VodIngest.Param.NONE, lang);
                lines.add(new IntoLine(id, o.green(), art ? o.name() + " " + o.des() : o.des()));
                if (o.green()) {
                    published.add(id);
                }
            } catch (VodIngest.Rejected e) {
                lines.add(new IntoLine(id, false, lang.apply("param_err")));
            } catch (CollectIngestService.Busy e) {
                lines.add(new IntoLine(id, false, "入库排队超时，请稍后重试（这一条仍是已采集）"));
            }
        }
        writer.updatePublished(published);
        return new Into(count, totalPage, limit, page, lines, last, totalPage > page && !list.isEmpty());
    }

    // ------------------------------------------------------------------ program

    /** 发布方案页：节点字段（键 → 名称）、视频或文章表的列、已保存的对应关系与处理函数。 */
    public record Program(Map<String, String> nodeFields, List<String> columns, Map<String, String> map, Map<String, String> funcs) {
    }

    /**
     * 旧 {@code Cj::program} 的 GET（{@code Cj.php:72-126}）：节点字段为标题、分类、内容与自定义项（英文名与名称都不为 {@code empty()} 的），
     * 列为 {@code SHOW COLUMNS FROM mac_vod}（{@code mid = 2} 时 {@code mac_art}）——新版取 install.sql 的列清单（{@link AdminTable}，与表结构
     * 相同，不执行 SHOW）。
     */
    public Program program(Map<String, String> node, UnaryOperator<String> lang) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", lang.apply("title"));
        fields.put("type", lang.apply("type"));
        fields.put("content", lang.apply("content"));
        Object diy = PhpJson.decode(node.get("customize_config"));
        Iterable<?> items = diy instanceof Map<?, ?> m ? m.values() : diy instanceof List<?> l ? l : List.of();
        for (Object item : items) {
            if (item instanceof Map<?, ?> im && im.get("en_name") instanceof String en && im.get("name") instanceof String nm
                    && !PhpForm.phpEmpty(en.getBytes(StandardCharsets.UTF_8)) && !PhpForm.phpEmpty(nm.getBytes(StandardCharsets.UTF_8))) {
                fields.put(en, nm);
            }
        }
        Object program = PhpJson.decode(node.get("program_config"));
        Map<String, String> map = new LinkedHashMap<>();
        Map<String, String> funcs = new LinkedHashMap<>();
        if (program instanceof Map<?, ?> pm) {
            copy(pm.get("map"), map);
            copy(pm.get("funcs"), funcs);
        }
        AdminTable table = "2".equals(node.get("mid")) ? AdminTable.ART : AdminTable.VOD;
        return new Program(fields, List.copyOf(table.columns().keySet()), map, funcs);
    }

    private static void copy(Object from, Map<String, String> to) {
        if (from instanceof Map<?, ?> m) {
            m.forEach((k, v) -> to.put(String.valueOf(k), v instanceof String s ? s : ""));
        }
    }

    /**
     * 旧 {@code Cj::program} 的 POST：{@code model_field[k]} 对应的 {@code node_field[k]} 非 {@code empty()} 时记下 {@code map[列] = 节点字段}、
     * {@code funcs[列] = funcs[k]}，{@code json_encode} 后与 {@code lastdate = time()} 一起写回节点（旧 {@code saveData}）。
     */
    public void saveProgram(Map<String, String> node, PhpForm param) {
        Map<Object, Object> map = new LinkedHashMap<>();
        Map<Object, Object> funcs = new LinkedHashMap<>();
        PhpForm.Arr model = param.isArray("model_field") ? param.array("model_field") : null;
        if (param.has("model_field") && model == null || param.has("node_field") && !param.isArray("node_field")
                || param.has("funcs") && !param.isArray("funcs")) {
            // 字符串参数：旧版对字符串取下标（PHP 8 报错）
            throw AdminRejectedException.paramError();
        }
        if (model != null) {
            Map<String, byte[]> nf = param.isArray("node_field") ? param.array("node_field").entries() : Map.of();
            Map<String, byte[]> fn = param.isArray("funcs") ? param.array("funcs").entries() : Map.of();
            model.entries().forEach((k, v) -> {
                byte[] f = nf.get(k);
                if (!PhpForm.phpEmpty(f)) {
                    Object col = ProvideValues.key(utf8(v));
                    map.put(col, utf8(f));
                    byte[] fx = fn.get(k);
                    funcs.put(col, fx == null ? null : utf8(fx));
                }
            });
        }
        Map<Object, Object> program = new LinkedHashMap<>();
        if (!map.isEmpty()) {
            program.put("map", map);
            program.put("funcs", funcs);
        }
        Map<String, String> c = new LinkedHashMap<>();
        c.put("lastdate", Long.toString(clock.instant().getEpochSecond()));
        c.put("program_config", phpJson(program));
        try {
            nodeWriter.updateCjNode(c, Long.parseLong(node.get("nodeid")));
        } catch (CollectDataException e) {
            throw AdminRejectedException.paramError();
        }
    }

    private static String utf8(byte[] b) {
        String s = CjRules.decode(new String(b, StandardCharsets.ISO_8859_1));
        if (s == null) {
            throw AdminRejectedException.paramError();
        }
        return s;
    }

    /** PHP {@code json_encode} 的数组：键是 0、1、2…… 连续整数时是 JSON 数组（含空数组 {@code []}），否则是对象。 */
    static String phpJson(Object v) {
        return PhpJson.encode(toJson(v));
    }

    private static Object toJson(Object v) {
        if (!(v instanceof Map<?, ?> m)) {
            return v;
        }
        long i = 0;
        boolean list = true;
        for (Object k : m.keySet()) {
            if (!(k instanceof Long l) || l != i++) {
                list = false;
                break;
            }
        }
        if (list) {
            List<Object> out = new ArrayList<>();
            m.values().forEach(x -> out.add(toJson(x)));
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, x) -> out.put(String.valueOf(k), toJson(x)));
        return out;
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty() || s.equals("0");
    }
}
