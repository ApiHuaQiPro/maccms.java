package com.maccms.backend.staticgen.application;

import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.content.application.view.StaticSourceType;
import com.maccms.backend.content.application.view.UrlsendItem;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import com.maccms.backend.site.application.LegacyPage;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.LegacyUrlStyle;
import com.maccms.backend.site.application.SiteRoute;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 推送地址里的详情页路径（B28，30 文档第 2.2 节）：旧 {@code mac_url_vod_detail}、{@code mac_url_art_detail}、{@code mac_url_topic_detail}、
 * {@code mac_url_actor_detail}、{@code mac_url_role_detail}、{@code mac_url_website_detail}（application/common.php:1732-2428），
 * 以 {@code /} 开头（旧 {@code MAC_PATH} 固定为 {@code /}）。
 * <ul>
 * <li>动态地址：{@link LegacyUrlBuilder}（与前台、后台列表链接同一套规则，按 {@code rewrite.status/suffix_hide/route_status}、
 * {@code app.pathinfo_depr}）；</li>
 * <li>静态地址（{@code view.vod_detail}/{@code art_detail}/{@code topic_detail} 为 2）：{@link StaticPaths}，与静态生成同一套规则。</li>
 * </ul>
 * 新版不支持的地址形态（{@code rewrite.<模型>_id} 为 1 拼音或 2 加密编号、旧路由表与分隔符的组合、静态规则为空、演员/角色/网址的
 * 静态地址）在构造或取路径时报 {@link Unsupported}，调用方不推送（D-B28-6：宁可不推，也不把错误地址推给搜索引擎）。
 */
final class UrlsendLinks {

    /** 新版不支持的地址形态，信息是给管理员看的提示。 */
    static final class Unsupported extends RuntimeException {
        Unsupported(String message) {
            super(message, null, false, false);
        }
    }

    private static final Map<Integer, String> MODEL = Map.of(1, "vod", 2, "art", 3, "topic", 8, "actor", 9, "role", 11, "website");

    private final PhpArray maccms;
    private final LegacyUrlBuilder builder;
    private final StaticPaths paths;
    private final Supplier<List<StaticSourceType>> types;
    private Map<Long, StaticSourceType> typeCache;

    private UrlsendLinks(PhpArray maccms, LegacyUrlBuilder builder, StaticPaths paths, Supplier<List<StaticSourceType>> types) {
        this.maccms = maccms;
        this.builder = builder;
        this.paths = paths;
        this.types = types;
    }

    /**
     * @param maccms 旧 {@code extra/maccms.php}
     * @param types  全部分类（视频、文章的静态地址要用分类拼音与上级分类），只在需要时取一次
     */
    static UrlsendLinks of(PhpArray maccms, ZoneId zone, Supplier<List<StaticSourceType>> types) {
        boolean rewrite = intval(maccms, "rewrite", "status") == 1;
        boolean hide = intval(maccms, "rewrite", "suffix_hide") == 1;
        boolean route = intval(maccms, "rewrite", "route_status") == 1;
        String deprText = text(maccms, "app", "pathinfo_depr");
        char depr = deprText.isEmpty() ? '/' : deprText.charAt(0);
        LegacyUrlStyle style;
        try {
            style = new LegacyUrlStyle(rewrite, hide, route, depr);
        } catch (IllegalArgumentException e) {
            throw new Unsupported("当前的伪静态设置（路由与分隔符的组合）新系统不能生成推送地址");
        }
        return new UrlsendLinks(maccms, new LegacyUrlBuilder(style), new StaticPaths(maccms, zone), types);
    }

    /** 模型 {@code mid} 的一行的详情页路径（以 {@code /} 开头）。 */
    String path(int mid, UrlsendItem it) {
        String model = MODEL.get(mid);
        if (model == null) {
            throw new IllegalArgumentException("未知模型");
        }
        if (PhpIntval.of(text(maccms, "view", model + "_detail")) == 2) {
            return staticPath(model, it);
        }
        if (intval(maccms, "rewrite", model + "_id") != 0) {
            throw new Unsupported("当前的伪静态设置（" + model + "_id 使用拼音或加密编号）新系统不能生成推送地址");
        }
        return "/" + switch (model) {
            case "vod" -> builder.vodDetail(it.id());
            case "art" -> builder.legacy(SiteRoute.Legacy.of(LegacyPage.ART_DETAIL, it.id()));
            case "topic" -> builder.legacy(SiteRoute.Legacy.of(LegacyPage.TOPIC_DETAIL, it.id()));
            case "actor" -> builder.legacy(SiteRoute.Legacy.of(LegacyPage.ACTOR_DETAIL, it.id()));
            case "role" -> builder.legacy(SiteRoute.Legacy.of(LegacyPage.ROLE_DETAIL, it.id()));
            default -> builder.legacy(SiteRoute.Legacy.of(LegacyPage.WEBSITE_DETAIL, it.id()));
        };
    }

    private String staticPath(String model, UrlsendItem it) {
        try {
            return switch (model) {
                case "vod" -> paths.vodDetail(item(it));
                case "art" -> paths.artDetail(item(it), 1);
                case "topic" -> paths.topicDetail(new StaticPaths.Topic(it.id(), it.en()));
                default -> throw new Unsupported("新系统不能生成" + model + "的静态地址");
            };
        } catch (IllegalStateException e) {
            throw new Unsupported("静态地址规则 path." + model + "_detail 为空，新系统不能生成推送地址");
        }
    }

    private StaticPaths.Item item(UrlsendItem it) {
        if (typeCache == null) {
            typeCache = new LinkedHashMap<>();
            for (StaticSourceType t : types.get()) {
                typeCache.put(t.id(), t);
            }
        }
        StaticSourceType type = typeCache.get(it.typeId());
        StaticSourceType parent = type == null ? null : typeCache.get(type.pid());
        return new StaticPaths.Item(it.id(), it.en(), it.time(), it.typeId(), ref(type), ref(parent));
    }

    private static StaticPaths.TypeRef ref(StaticSourceType t) {
        return t == null ? null : new StaticPaths.TypeRef(t.id(), t.en(), t.pid(), t.mid());
    }

    private static long intval(PhpArray a, String... path) {
        return PhpIntval.of(text(a, path));
    }

    private static String text(PhpArray a, String... path) {
        return a == null ? "" : PhpValues.text(a.path(path));
    }
}
