package com.maccms.backend.collect.application;

import com.maccms.backend.collect.infrastructure.DirectoryProvideSourceFiles;
import com.maccms.backend.collect.infrastructure.FileProvidePreviewStore;
import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * 资源站解析与预览的本地文件版本（B18，26 文档第 4、5 节；旧 {@code admin/controller/Collect.php:140-171} 的 {@code api} 与各类型的
 * {@code ac=list} 分支）。文件来自后台上传或配置的本地目录（{@link DirectoryProvideSourceFiles}），经 {@link ProvideParser} 解析后存进本机的预览缓存
 * （{@link FileProvidePreviewStore}），预览时按当前的分类绑定换算、按过滤词给出入库预检结果。<b>不写库、不发起网络请求、不写旧站目录。</b>
 *
 * <p>缓存键：资源站标识 {@code cjflag}、种类（{@code mid}）、接口类型（{@code type}）与 {@code param} 一起决定一项；同一资源站再载入时覆盖。
 * 缓存项写入后 {@code ttl} 内有效，过期的在下一次载入、清除或读取时删除；项数超过上限时删最旧的。
 */
public class CollectPreviewService {

    /** 每页默认条数（旧资源站接口默认 20）。 */
    public static final int DEFAULT_LIMIT = 20;
    /** 每页最多条数。 */
    public static final int MAX_LIMIT = 1000;
    /** 本地目录最多列出的文件数。 */
    public static final int MAX_LISTED_FILES = 500;
    private static final int CACHE_VERSION = 1;

    private final FileProvidePreviewStore store;
    private final DirectoryProvideSourceFiles files;
    private final CollectBindService binds;
    private final TypeNames types;
    private final LegacyConfigService legacy;
    private final Clock clock;
    private final Duration ttl;
    private final long maxBytes;
    private final int maxEntries;

    public CollectPreviewService(FileProvidePreviewStore store, DirectoryProvideSourceFiles files, CollectBindService binds, TypeNames types,
                                 LegacyConfigService legacy, Clock clock, Duration ttl, long maxBytes, int maxEntries) {
        this.store = store;
        this.files = files;
        this.binds = binds;
        this.types = types;
        this.legacy = legacy;
        this.clock = clock;
        this.ttl = ttl;
        this.maxBytes = maxBytes;
        this.maxEntries = maxEntries;
    }

    /**
     * 一个资源站的预览来源（旧 {@code collect/api} 的参数）。
     *
     * @param cjflag      资源站标识（{@code md5(collect_url)}，绑定键的前缀）
     * @param mid         资源种类（旧 {@code mid}）
     * @param collectType 接口类型（旧 {@code type}，1 XML，2 JSON，其他先 JSON 再 XML）
     * @param param       旧 {@code param}（{@code base64_encode(collect_param)}）
     */
    public record Source(String cjflag, String mid, String collectType, String param) {

        public Source {
            cjflag = cjflag == null ? "" : cjflag;
            mid = mid == null ? "" : mid;
            collectType = collectType == null ? "" : collectType;
            param = param == null ? "" : param;
        }

        public Optional<ProvideKind> kind() {
            return ProvideKind.ofMid(mid);
        }

        /** 缓存项的编号：资源站部分 + 种类、接口类型、param 的摘要（只含小写十六进制与 -）。 */
        String id(ProvideKind kind) {
            return sitePrefix(cjflag) + sha256(kind.code() + "\0" + collectType + "\0" + param).substring(0, 40);
        }
    }

    /** 载入的结果。 */
    public record LoadOutcome(boolean ok, String message, int rows) {
    }

    /** 本地目录是否已配置。 */
    public boolean localDirectoryConfigured() {
        return files != null && files.configured();
    }

    /** 本地目录里的一个文件。 */
    public record LocalFile(String name, long size) {
    }

    /** 本地目录里的文件。 */
    public List<LocalFile> localFiles() {
        return localDirectoryConfigured()
                ? files.list(MAX_LISTED_FILES).stream().map(f -> new LocalFile(f.name(), f.size())).toList() : List.of();
    }

    /** 每个文件的大小上限（字节）。 */
    public long maxBytes() {
        return maxBytes;
    }

    /** 载入上传的文件。 */
    public LoadOutcome load(Source s, String fileName, byte[] content, UnaryOperator<String> lang) {
        Optional<ProvideKind> kind = s.kind();
        if (kind.isEmpty()) {
            return new LoadOutcome(false, lang.apply("param_err"), 0);
        }
        if (content == null) {
            return new LoadOutcome(false, "请选择要载入的文件", 0);
        }
        if (content.length > maxBytes) {
            return new LoadOutcome(false, tooLarge(), 0);
        }
        ProvideDocument doc = ProvideParser.parse(kind.get(), s.collectType(), "list", s.param(), content);
        if (!doc.ok()) {
            return new LoadOutcome(false, lang.apply(doc.errorKey()) + (doc.detail().isEmpty() ? "" : "：" + doc.detail()), 0);
        }
        Instant now = clock.instant();
        store.purge(now.minus(ttl), maxEntries);
        store.put(s.id(kind.get()), encode(s, kind.get(), safeName(fileName), now, doc));
        store.purge(now.minus(ttl), maxEntries);
        return new LoadOutcome(true, "载入成功，共 " + doc.rows().size() + " 条", doc.rows().size());
    }

    /** 载入本地目录里的文件。 */
    public LoadOutcome loadLocal(Source s, String name, UnaryOperator<String> lang) {
        if (!localDirectoryConfigured()) {
            return new LoadOutcome(false, "未配置本地文件目录", 0);
        }
        byte[] content;
        try {
            content = files.read(name, maxBytes);
        } catch (DirectoryProvideSourceFiles.NotFoundException e) {
            return new LoadOutcome(false, "文件不存在", 0);
        } catch (DirectoryProvideSourceFiles.TooLargeException e) {
            return new LoadOutcome(false, tooLarge(), 0);
        }
        return load(s, name, content, lang);
    }

    /** 清除这个来源的预览缓存。 */
    public boolean clear(Source s) {
        Optional<ProvideKind> kind = s.kind();
        store.purge(clock.instant().minus(ttl), maxEntries);
        return kind.isPresent() && store.delete(s.id(kind.get()));
    }

    /** 清除一个资源站的全部预览缓存。 */
    public int clearSite(String cjflag) {
        store.purge(clock.instant().minus(ttl), maxEntries);
        return store.deletePrefix(sitePrefix(cjflag == null ? "" : cjflag));
    }

    // ------------------------------------------------------------------ 预览

    /** 远程分类与它的绑定（旧 {@code Collect::vod} 等的 {@code ac=list} 分支：{@code isbind}、{@code local_type_id}、{@code local_type_name}）。 */
    public record TypeBinding(String typeId, String typeName, boolean bound, long localTypeId, String localTypeName) {
    }

    /** 一行数据与它的入库预检结果（语言包键，能入库时为空串）。 */
    public record PreviewRow(Object data, String check) {

        /** 字段的字符串值（旧模板 {@code {$vo.字段}}）。 */
        public String s(String field) {
            return ProvideValues.str(ProvideValues.get(data, field));
        }
    }

    /**
     * 一页预览。
     *
     * @param sourceName 载入的文件名
     * @param loadedAt   载入时间
     * @param filePage   文件里的分页信息（旧结果的 {@code page}，不含 url）
     * @param types      远程分类（只有换算绑定的种类有）
     * @param rows       本页的数据行
     * @param total      按 {@code t}、{@code wd} 筛选后的条数
     * @param result     与旧方法返回值同构的完整结果（全部数据行，已换算绑定；供入库批次 B19 使用）
     */
    public record Preview(ProvideKind kind, String sourceName, Instant loadedAt, Map<Object, Object> filePage, List<TypeBinding> types,
                          List<PreviewRow> rows, long total, long page, long limit, Map<Object, Object> result) {
    }

    /**
     * 读出预览：没有缓存或已过期时为空。{@code t} 非空时只列远程分类编号等于它的行，{@code wd} 非空时只列名称含它的行（旧版由资源站按
     * {@code t}、{@code wd} 筛选，本地文件由新版筛选，D-B18-2）；按 {@code page}、{@code limit} 分页。
     */
    public Optional<Preview> preview(Source s, String t, String wd, String page, String limit, UnaryOperator<String> lang) {
        Optional<ProvideKind> kind = s.kind();
        if (kind.isEmpty()) {
            return Optional.empty();
        }
        Optional<Cached> cached = read(s, kind.get());
        if (cached.isEmpty()) {
            return Optional.empty();
        }
        Cached c = cached.get();
        PhpArray bindList = binds.bindings();
        Map<Object, Object> result = ProvideParser.bind(c.doc(), s.cjflag(), bindList, c.sourceName(), lang);

        List<TypeBinding> typeList = new ArrayList<>();
        if (c.doc().types() != null) {
            for (Map<Object, Object> ty : c.doc().types()) {
                String id = ProvideValues.str(ty.get("type_id"));
                long local = CollectBindings.localTypeId(bindList, CollectBindings.key(s.cjflag(), id));
                String localName = "";
                if (local > 0) {
                    localName = types.name(local).filter(n -> !ProvideValues.empty(n)).orElse(lang.apply("unknown_type"));
                }
                typeList.add(new TypeBinding(id, ProvideValues.str(ty.get("type_name")), local > 0, local, localName));
            }
        }

        String filter = filterWords(kind.get());
        List<PreviewRow> all = new ArrayList<>();
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        int i = 0;
        for (Object row : data.values()) {
            ProvideDocument.Row source = c.doc().rows().get(i++);
            if (t != null && !t.isEmpty() && (source.remote() == null || !source.remote().equals(t))) {
                continue;
            }
            if (wd != null && !wd.isEmpty() && !ProvideValues.str(ProvideValues.get(row, kind.get().nameField())).contains(wd)) {
                continue;
            }
            all.add(new PreviewRow(row, ProvideParser.precheck(kind.get(), row, filter)));
        }
        long lim = PhpIntval.of(limit == null ? "" : limit);
        if (lim < 1 || lim > MAX_LIMIT) {
            lim = DEFAULT_LIMIT;
        }
        long pages = Math.max(1, (all.size() + lim - 1) / lim);
        long pg = PhpIntval.of(page == null ? "" : page);
        pg = Math.min(Math.max(pg, 1), pages);
        int from = (int) Math.min((pg - 1) * lim, all.size());
        int to = (int) Math.min(from + lim, all.size());
        return Optional.of(new Preview(kind.get(), c.sourceName(), c.loadedAt(), c.doc().page(), typeList,
                List.copyOf(all.subList(from, to)), all.size(), pg, lim, result));
    }

    /** {@code maccms.php} 的 {@code collect.<种类>.filter}（没有旧配置时为空）。 */
    private String filterWords(ProvideKind kind) {
        if (legacy == null) {
            return "";
        }
        LegacyConfigService.Snapshot snap = legacy.snapshotOrNull(LegacyConfigFile.MACCMS);
        if (snap == null) {
            return "";
        }
        PhpValue v = snap.value().path("collect", kind.code(), "filter");
        return v == null || v instanceof PhpArray ? "" : ProvideValues.str(ProvideValues.of(v));
    }

    // ------------------------------------------------------------------ 缓存项的编码

    private record Cached(String sourceName, Instant loadedAt, ProvideDocument doc) {
    }

    private static byte[] encode(Source s, ProvideKind kind, String sourceName, Instant now, ProvideDocument d) {
        Map<Object, Object> m = ProvideValues.array();
        m.put("v", (long) CACHE_VERSION);
        m.put("cjflag", s.cjflag());
        m.put("kind", kind.code());
        m.put("type", s.collectType());
        m.put("param", s.param());
        m.put("source", sourceName);
        m.put("loaded", now.getEpochSecond());
        m.put("format", d.format());
        m.put("page", d.page());
        if (d.types() != null) {
            Map<Object, Object> ts = ProvideValues.array();
            long i = 0;
            for (Map<Object, Object> t : d.types()) {
                ts.put(i++, t);
            }
            m.put("types", ts);
        }
        Map<Object, Object> rows = ProvideValues.array();
        long i = 0;
        for (ProvideDocument.Row r : d.rows()) {
            Map<Object, Object> x = ProvideValues.array();
            x.put("k", r.key());
            x.put("r", r.remote());
            x.put("d", r.data());
            rows.put(i++, x);
        }
        m.put("rows", rows);
        return ProvideJson.encode(m).getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private Optional<Cached> read(Source s, ProvideKind kind) {
        String id = s.id(kind);
        Optional<FileProvidePreviewStore.Entry> e = store.get(id);
        if (e.isEmpty()) {
            return Optional.empty();
        }
        if (e.get().writtenAt().isBefore(clock.instant().minus(ttl))) {
            store.delete(id);
            return Optional.empty();
        }
        try {
            Map<Object, Object> m = (Map<Object, Object>) ProvideJson.decode(e.get().body());
            if (!Long.valueOf(CACHE_VERSION).equals(m.get("v")) || !s.cjflag().equals(m.get("cjflag")) || !kind.code().equals(m.get("kind"))
                    || !s.collectType().equals(m.get("type")) || !s.param().equals(m.get("param"))) {
                return Optional.empty();
            }
            List<Map<Object, Object>> types = null;
            if (m.get("types") instanceof Map<?, ?> ts) {
                types = new ArrayList<>();
                for (Object t : ts.values()) {
                    types.add((Map<Object, Object>) t);
                }
            }
            List<ProvideDocument.Row> rows = new ArrayList<>();
            for (Object x : ((Map<Object, Object>) m.get("rows")).values()) {
                Map<Object, Object> r = (Map<Object, Object>) x;
                rows.add(new ProvideDocument.Row(r.get("k"), (String) r.get("r"), r.get("d")));
            }
            ProvideDocument doc = new ProvideDocument(kind, (String) m.get("format"), 1, "", "",
                    (Map<Object, Object>) m.get("page"), types, rows);
            return Optional.of(new Cached((String) m.get("source"), Instant.ofEpochSecond((Long) m.get("loaded")), doc));
        } catch (ProvideJson.MalformedJsonException | RuntimeException bad) {
            // 损坏的缓存项当作没有，并删除
            store.delete(id);
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ 公共

    private String tooLarge() {
        return "文件超过大小上限（" + maxBytes + " 字节）";
    }

    /** 缓存里记下的文件名：去掉路径与控制字符，最多 200 个字符。 */
    static String safeName(String name) {
        if (name == null) {
            return "";
        }
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        return n.length() > 200 ? n.substring(0, 200) : n;
    }

    /** 资源站部分：规范的 cjflag（32 位小写十六进制）原样，否则取它的摘要，后跟 {@code -}。 */
    static String sitePrefix(String cjflag) {
        String f = cjflag.matches("[0-9a-f]{32}") ? cjflag : sha256(cjflag).substring(0, 32);
        return f + "-";
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
