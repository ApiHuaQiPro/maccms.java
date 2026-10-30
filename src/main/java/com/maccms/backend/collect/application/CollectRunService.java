package com.maccms.backend.collect.application;

import com.maccms.backend.collect.infrastructure.FileCollectBreakpointStore;
import com.maccms.backend.content.application.PhpIntval;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * 后台采集入库与断点续采（B19 增量 3，27 文档第 12 节）：旧 {@code admin/controller/Collect.php} 的 {@code vod/art/actor/role/website($param)}
 * 在 {@code ac != 'list'} 时的分支与 {@code Collect::{种类}_data($param, $res)} 的逐行输出、翻页与断点。每次请求入库一页：
 * <ol>
 * <li>从 B18 的预览缓存取这一页（{@link CollectPreviewService#select}；旧版向资源站请求这一页）；没有缓存时不入库、不记断点；</li>
 * <li>记下断点（这一页的地址，旧 {@code Cache::set}）；</li>
 * <li>每行经 {@link CollectIngestService}（与 Receive 相同的入库规则、入库锁与写入登记）入库，旧版整页 500 的行记为"参数错误"、入库锁等待超时的行记为
 * "入库排队超时"，都不中断这一页；</li>
 * <li>结束：本页有排队超时的行且重试不到 {@link #MAX_RETRY} 次 → 同一页重来（{@code retry + 1}，新版增加）；{@code cjsel} → 删除断点、完成，回到来源页；
 * 最后一页 → 删除断点、完成，回到预览；否则下一页。</li>
 * </ol>
 * 同一页重来或从断点续采时，已入库的行按查重规则走"更新"一支（同样的数据得到"无需更新"），不会新增重复行。
 */
public class CollectRunService {

    /** 本页有入库排队超时的行时，同一页自动重来的最多次数。 */
    public static final int MAX_RETRY = 3;
    /** 入库锁等待超时的行的说明。 */
    public static final String BUSY = "入库排队超时，请稍后重试";
    /** 断点的种类（旧 {@code collect_break_<种类>}；评论没有后台入口）。 */
    public static final List<ProvideKind> KINDS = List.of(ProvideKind.VOD, ProvideKind.ART, ProvideKind.ACTOR, ProvideKind.ROLE,
            ProvideKind.WEBSITE);

    private final CollectPreviewService preview;
    private final Ingest ingest;
    private final FileCollectBreakpointStore breaks;
    private final Clock clock;

    /** 入库一行（{@link CollectIngestService} 的这三个方法；单元测试替换成替身）。 */
    interface Ingest {
        VodIngest.Outcome vod(Object row, VodIngest.Param param, UnaryOperator<String> lang);

        VodIngest.Outcome kind(KindIngest.Kind kind, Object row, UnaryOperator<String> lang);

        Map<Object, Object> maccms();
    }

    public CollectRunService(CollectPreviewService preview, CollectIngestService ingest, FileCollectBreakpointStore breaks, Clock clock) {
        this(preview, new Ingest() {
            @Override
            public VodIngest.Outcome vod(Object row, VodIngest.Param param, UnaryOperator<String> lang) {
                return ingest.vod(row, param, lang);
            }

            @Override
            public VodIngest.Outcome kind(KindIngest.Kind kind, Object row, UnaryOperator<String> lang) {
                return ingest.kind(kind, row, lang);
            }

            @Override
            public Map<Object, Object> maccms() {
                return ingest.maccms();
            }
        }, breaks, clock);
    }

    CollectRunService(CollectPreviewService preview, Ingest ingest, FileCollectBreakpointStore breaks, Clock clock) {
        this.preview = preview;
        this.ingest = ingest;
        this.breaks = breaks;
        this.clock = clock;
    }

    /** 一行的结果（旧 {@code ($k + 1) . 名称 . <font color=…> . $des . </font>}）。 */
    public record Line(int no, String name, boolean green, String des) {
    }

    /** 本页结束后的去向。 */
    public enum End {
        /** 下一页。 */
        NEXT,
        /** 同一页重来（本页有入库排队超时的行）。 */
        RETRY,
        /** 最后一页已完成：断点已删除，回到预览。 */
        DONE,
        /** {@code cjsel} 完成：断点已删除，回到来源页（没有时回到预览）。 */
        SELECTED_DONE
    }

    /**
     * 一页入库的结果。
     *
     * @param pageNow   旧 {@code <title>} 里的页码（{@code strlen($param['page']) > 0 ? (int)$param['page'] : 1}，只有视频输出）
     * @param page      本页页码
     * @param pageCount 总页数
     * @param jump      跳转的查询参数（{@code url('api') . '?' . http_build_query(...)}）
     * @param seconds   跳转前等待的秒数（旧 {@code app.collect_timespan}，空时 3）
     */
    public record RunPage(ProvideKind kind, String sourceName, long pageNow, long page, long pageCount, List<Line> lines, int busy, End end,
                          Map<String, String> jump, long seconds) {
    }

    /** 种类 → 是否有断点（采集源列表与断点采集页的按钮）。 */
    public Map<ProvideKind, Boolean> breakpoints() {
        Map<ProvideKind, Boolean> m = new LinkedHashMap<>();
        for (ProvideKind k : KINDS) {
            m.put(k, breaks.get(k.code()).isPresent());
        }
        return m;
    }

    /** 旧 {@code Collect::load} 的断点地址（{@code flag} 不是五个种类之一时为空）。 */
    public Optional<String> breakpoint(String flag) {
        for (ProvideKind k : KINDS) {
            if (k.code().equals(flag)) {
                return breaks.get(k.code());
            }
        }
        return Optional.empty();
    }

    /**
     * 入库一页。
     *
     * @param source        预览来源（{@code cjflag}、{@code mid}、{@code type}、{@code param}）
     * @param param         旧 {@code $param}（{@code input()}，{@code pg} 已换成 {@code page}）
     * @param breakpointUrl 这一页的地址（旧 {@code url('collect/api') . '?' . http_build_query($param)}），记作断点
     * @return 没有载入的本地文件（或已过期）时为空
     */
    public Optional<RunPage> run(CollectPreviewService.Source source, Map<String, String> param, String breakpointUrl,
                                 UnaryOperator<String> lang) {
        Optional<ProvideKind> k = source.kind();
        if (k.isEmpty() || !KINDS.contains(k.get())) {
            return Optional.empty();
        }
        ProvideKind kind = k.get();
        String h = p(param, "h");
        if (kind == ProvideKind.VOD && IngestPhp.empty(h) && !IngestPhp.empty(p(param, "rday"))) {
            // 旧 vod_xml：h 为空时取 rday
            h = p(param, "rday");
        }
        CollectPreviewService.Selection sel = new CollectPreviewService.Selection(p(param, "t"), p(param, "wd"), p(param, "ids"),
                PhpIntval.of(h), clock.instant());
        Optional<CollectPreviewService.Batch> b = preview.select(source, sel, p(param, "page"), p(param, "limit"), lang);
        if (b.isEmpty()) {
            return Optional.empty();
        }
        CollectPreviewService.Batch batch = b.get();
        breaks.put(kind.code(), breakpointUrl);

        VodIngest.Param vodParam = new VodIngest.Param(param.get("opt"), param.get("filter"), param.get("filter_from"),
                param.get("sync_pic_opt"));
        List<Line> lines = new ArrayList<>();
        int busy = 0;
        int no = 0;
        for (Object row : batch.rows()) {
            no++;
            String name = ProvideValues.str(ProvideValues.get(row, kind.nameField()));
            try {
                VodIngest.Outcome o = kind == ProvideKind.VOD ? ingest.vod(row, vodParam, lang)
                        : ingest.kind(KindIngest.Kind.valueOf(kind.name()), row, lang);
                lines.add(new Line(no, o.name() == null ? name : o.name(), o.green(), o.des()));
            } catch (VodIngest.Rejected e) {
                lines.add(new Line(no, name, false, lang.apply("param_err")));
            } catch (CollectIngestService.Busy e) {
                busy++;
                lines.add(new Line(no, name, false, BUSY));
            }
        }

        String pageParam = p(param, "page");
        long pageNow = pageParam.isEmpty() ? 1 : PhpIntval.of(pageParam);
        long seconds = seconds(ingest.maccms());
        Map<String, String> jump = new LinkedHashMap<>(param);
        long retry = PhpIntval.of(p(param, "retry"));
        End end;
        if (busy > 0 && retry < MAX_RETRY) {
            jump.put("page", Long.toString(batch.page()));
            jump.put("retry", Long.toString(Math.max(retry, 0) + 1));
            end = End.RETRY;
        } else {
            jump.remove("retry");
            if ("cjsel".equals(p(param, "ac"))) {
                breaks.remove(kind.code());
                jump.remove("ids");
                jump.put("ac", "list");
                end = End.SELECTED_DONE;
            } else if (batch.page() >= batch.pageCount()) {
                breaks.remove(kind.code());
                jump.remove("page");
                jump.remove("ids");
                jump.put("ac", "list");
                end = End.DONE;
            } else {
                jump.put("page", Long.toString(batch.page() + 1));
                end = End.NEXT;
            }
        }
        return Optional.of(new RunPage(kind, batch.sourceName(), pageNow, batch.page(), batch.pageCount(), List.copyOf(lines), busy, end,
                jump, seconds));
    }

    /** 旧 {@code if(empty(collect_timespan)) collect_timespan = 3}；其余按 {@code intval}（负数取 0）。 */
    static long seconds(Map<Object, Object> maccms) {
        Object v = ProvideValues.get(ProvideValues.get(maccms, "app"), "collect_timespan");
        if (IngestPhp.empty(v)) {
            return 3;
        }
        return Math.max(0, Math.min(PhpIntval.of(IngestPhp.str(v)), 3600));
    }

    private static String p(Map<String, String> param, String key) {
        String v = param.get(key);
        return v == null ? "" : v;
    }
}
