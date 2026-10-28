package com.maccms.backend.collect.application;

import com.maccms.backend.collect.infrastructure.CollectDataException;
import com.maccms.backend.collect.infrastructure.CollectIngestMapper;
import com.maccms.backend.collect.infrastructure.CollectIngestWrites;
import com.maccms.backend.content.application.AdminRowValues;
import com.maccms.backend.content.application.AdminTable;
import com.maccms.backend.content.application.VodSearchCache;
import com.maccms.backend.content.application.VodSearchField;
import com.maccms.backend.legacyconfig.application.LegacyConfigFile;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.IntBinaryOperator;
import java.util.function.UnaryOperator;

/**
 * 采集入库（B19，27 文档第 2、4 节）：旧 {@code Collect::vod_data} 的一行在<b>入库锁</b>内执行——查重、新增或更新之间不会插入另一条入库，
 * 同一视频的并发提交不会都判成"新增"而写出重复行（旧版没有锁，D-B19-2）。锁是进程内的公平锁，等待超过 {@link #LOCK_WAIT_SECONDS} 秒时
 * 放弃本行（{@link Busy}）。多实例部署时各实例的锁互不相干，入库请求要落在同一实例（27 文档第 9 节）。
 */
public class CollectIngestService {

    /** 等待入库锁的上限（秒）。 */
    static final long LOCK_WAIT_SECONDS = 30;

    private final ReentrantLock lock = new ReentrantLock(true);
    private final CollectIngestMapper reader;
    private final CollectIngestWrites writer;
    private final LegacyConfigService legacy;
    private final Function<String, Object> typePid;
    private final java.util.function.Supplier<Map<String, Long>> typeIdsByName;
    private final VodSearchCache search;
    private final Clock clock;
    private final IntBinaryOperator random;

    /**
     * @param typePid 本站分类（旧 {@code getCache('type_list')[$id]}，键按 PHP 数组规则）→ 含 {@code type_pid} 的数组；没有时 null
     * @param typeIdsByName 本站分类名 → 编号（旧 {@code mac_interface_type} 里的 {@code $type_names}：按分类缓存的顺序，同名时后者覆盖）
     * @param search  视频搜索缓存（content 模块；没有时不更新）
     * @param random  {@code rand(min, max)}（含两端；{@code min > max} 时取 {@code [max, min]}，同 PHP）
     */
    public CollectIngestService(CollectIngestMapper reader, CollectIngestWrites writer, LegacyConfigService legacy,
                                Function<String, Object> typePid, java.util.function.Supplier<Map<String, Long>> typeIdsByName,
                                VodSearchCache search, Clock clock, IntBinaryOperator random) {
        this.reader = reader;
        this.writer = writer;
        this.legacy = legacy;
        this.typePid = typePid;
        this.typeIdsByName = typeIdsByName;
        this.search = search;
        this.clock = clock;
        this.random = random;
    }

    /** 入库锁忙（等待超时）。 */
    public static final class Busy extends RuntimeException {
        Busy() {
            super("入库排队超时", null, false, false);
        }
    }

    /** 本站分类名 → 编号。 */
    Map<String, Long> typeIdsByName() {
        return typeIdsByName.get();
    }

    /** 当前的 {@code maccms.php}（PHP 值）。 */
    Map<Object, Object> maccms() {
        return castMap(ProvideValues.of(legacy.read(LegacyConfigFile.MACCMS)));
    }

    /**
     * 视频入库一行。
     *
     * @throws VodIngest.Rejected 旧版在这一行上会报错（PHP 8 类型错误或 MySQL 严格模式错误）
     * @throws Busy               入库锁等待超时
     */
    public VodIngest.Outcome vod(Object row, VodIngest.Param param, UnaryOperator<String> lang) {
        Map<Object, Object> maccms = maccms();
        Map<Object, Object> config = castMap(ProvideValues.get(ProvideValues.get(maccms, "collect"), "vod"));
        boolean searchEnabled = search != null && List.of(IngestPhp.str(ProvideValues.get(ProvideValues.get(maccms, "app"),
                "vod_search_optimise")).split("\\|", -1)).contains("collect");
        VodIngest.Settings settings = new VodIngest.Settings(config == null ? Map.of() : config, players(LegacyConfigFile.VODPLAYER),
                players(LegacyConfigFile.VODDOWNER), typePid, searchEnabled, lang, random);
        VodIngest ingest = new VodIngest(settings);
        lock();
        try {
            return ingest.process(row, param, new Store(), clock.instant().getEpochSecond());
        } catch (CollectDataException e) {
            throw new VodIngest.Rejected("写库时数据错误");
        } finally {
            lock.unlock();
        }
    }

    /**
     * 文章、演员、角色、网址、评论入库一行（B19 增量 2）。
     *
     * @throws VodIngest.Rejected 旧版在这一行上会报错（PHP 8 类型错误、MySQL 严格模式错误或不允许 NULL 的列写入 null）
     * @throws Busy               入库锁等待超时
     */
    public VodIngest.Outcome kind(KindIngest.Kind kind, Object row, UnaryOperator<String> lang) {
        Map<Object, Object> config = castMap(ProvideValues.get(ProvideValues.get(maccms(), "collect"), kind.prefix()));
        KindIngest ingest = new KindIngest(kind, new KindIngest.Settings(config == null ? Map.of() : config, typePid, lang, random));
        lock();
        try {
            return ingest.process(row, new KindStore(), clock.instant().getEpochSecond());
        } catch (CollectDataException e) {
            throw new VodIngest.Rejected("写库时数据错误");
        } finally {
            lock.unlock();
        }
    }

    private void lock() {
        try {
            if (!lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new Busy();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Busy();
        }
    }

    private Map<Object, Object> players(LegacyConfigFile f) {
        Map<Object, Object> m = castMap(ProvideValues.of(legacy.read(f)));
        return m == null ? Map.of() : m;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> castMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<Object, Object>) m : null;
    }

    /** 查重与写入（在入库锁内调用）。 */
    private final class Store implements VodIngest.Store {

        @Override
        public Map<String, String> find(VodIngest.VodMatch m) {
            return reader.findVod(m.name(), m.typeId(), m.year(), m.area(), m.lang(), m.actorLikes(), m.director(), m.blend(),
                    m.blendIds());
        }

        @Override
        public long insert(Map<String, Object> row) {
            return writer.insertVod(columns(row));
        }

        @Override
        public void update(long vodId, Map<String, Object> columns) {
            writer.updateVod(columns(columns), vodId);
        }

        @Override
        public List<Long> actorSearchIds(String word) {
            return new ArrayList<>(search.resultIds(word, VodSearchField.ACTOR, true).values());
        }

        @Override
        public void appendSearch(long vodId, Map<String, Object> row) {
            Map<String, byte[]> vod = new LinkedHashMap<>();
            row.forEach((k, x) -> {
                if (x != null && !(x instanceof Map<?, ?>)) {
                    vod.put(k, IngestPhp.str(x).getBytes(StandardCharsets.UTF_8));
                }
            });
            search.appendNewVod(vodId, vod);
        }
    }

    /** 文章、演员、角色、网址、评论的查重、关联查找与写入（在入库锁内调用）。 */
    private final class KindStore implements KindIngest.Store {

        @Override
        public Map<String, String> findArt(String name, String typeId) {
            return reader.findArt(name, typeId);
        }

        @Override
        public Map<String, String> findActor(String name, String sex, String typeId) {
            return reader.findActor(name, sex, typeId);
        }

        @Override
        public Map<String, String> findWebsite(String name, String typeId) {
            return reader.findWebsite(name, typeId);
        }

        @Override
        public Map<String, String> findRole(String name, String actor, String rid) {
            return reader.findRole(name, actor, rid);
        }

        @Override
        public Map<String, String> findComment(String rid, String content, String name) {
            return reader.findComment(rid, content, name);
        }

        @Override
        public String findRoleVod(String doubanId, String vodName, List<String> actorLikes, List<String> directorLikes) {
            return reader.findRoleVod(doubanId, vodName, actorLikes, directorLikes);
        }

        @Override
        public String findRelId(int mid, String doubanId, String name) {
            return reader.findRelId(mid, doubanId, name);
        }

        @Override
        public long insert(KindIngest.Kind kind, Map<String, Object> row) {
            return writer.insert(table(kind), columns(TABLES.get(kind), row));
        }

        @Override
        public void update(KindIngest.Kind kind, long id, Map<String, Object> columns) {
            writer.update(table(kind), columns(TABLES.get(kind), columns), id);
        }

        private static CollectIngestWrites.Table table(KindIngest.Kind kind) {
            return CollectIngestWrites.Table.valueOf(kind.name());
        }
    }

    private static final Map<KindIngest.Kind, AdminTable> TABLES = Map.of(KindIngest.Kind.ART, AdminTable.ART, KindIngest.Kind.ACTOR,
            AdminTable.ACTOR, KindIngest.Kind.ROLE, AdminTable.ROLE, KindIngest.Kind.WEBSITE, AdminTable.WEBSITE, KindIngest.Kind.COMMENT,
            AdminTable.COMMENT);

    static Map<String, String> columns(Map<String, Object> row) {
        return columns(AdminTable.VOD, row);
    }

    /**
     * 写给 MySQL 的列值（旧 ThinkPHP 按表结构过滤后绑定）：表里没有的键与主键丢弃；值按 PHP 字符串转换后经 {@link AdminRowValues#value}
     * （数值列的空串写 0，超长、非法 UTF-8、4 字节字符拒绝）；null（旧版绑定 NULL，不允许 NULL 的列报错）与数组拒绝。
     */
    static Map<String, String> columns(AdminTable table, Map<String, Object> row) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            AdminTable.Column c = table.column(e.getKey());
            if (c == null || e.getKey().equals(table.pk())) {
                continue;
            }
            Object x = e.getValue();
            if (x == null || x instanceof Map<?, ?>) {
                throw new VodIngest.Rejected("列 " + e.getKey() + " 的值不能写库");
            }
            try {
                out.put(e.getKey(), AdminRowValues.value(c, IngestPhp.str(x).getBytes(StandardCharsets.UTF_8)));
            } catch (RuntimeException rejected) {
                throw new VodIngest.Rejected("列 " + e.getKey() + " 的值不合规");
            }
        }
        return out;
    }
}
