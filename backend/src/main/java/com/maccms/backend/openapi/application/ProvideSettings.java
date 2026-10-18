package com.maccms.backend.openapi.application;

import com.maccms.backend.common.clientip.IpAddress;
import com.maccms.backend.content.application.LegacyCondition;
import com.maccms.backend.content.application.LegacyTable;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provide 的旧配置（B06，docs/architecture/16-b06-misc-pages-provide.md 第 3.1、3.4 节）：{@code extra/maccms.php} 的
 * {@code api.vod/art/actor/role/website} 与 {@code upload.protocol}，启动时读一次（改了要重启，与 site 相同）。
 * 取值不受支持时 {@link #from} 抛 {@link IllegalStateException}，信息只含键名，不含取值。
 *
 * @param modules        各模块设置
 * @param uploadProtocol {@code upload.protocol}（未设置时为 null，旧版 {@code isset} 为假时取 {@code http}）
 */
public record ProvideSettings(Map<ProvideModule, ModuleSettings> modules, String uploadProtocol) {

    /** 旧版 checkDomainAuth 固定加入的地址（Provide.php:788）。 */
    static final String LOOPBACK = "127.0.0.1";
    private static final Pattern TYPE_FILTER = Pattern.compile("[0-9]{1,5}(,[0-9]{1,5})*");
    private static final Pattern CONDITION = Pattern.compile("([a-z]+)_([a-z]+)\\s*(!=|<>|>=|<=|=|>|<)\\s*(-?[0-9]{1,9})");
    private static final Pattern AND = Pattern.compile("(?i)\\s+AND\\s+");

    /**
     * 一个模块的设置。
     *
     * @param open          {@code status == 1}
     * @param charge        {@code charge == 1}（IP 白名单）
     * @param auth          白名单（含 {@value #LOOPBACK}）
     * @param ignoredAuth   {@code auth} 中不是 IP 字面量的项数（域名、带端口；新版不解析域名）
     * @param pageSize      配置的每页条数
     * @param pageSizeJson  JSON 中 {@code limit} 的原值（旧配置是字符串 {@code '20'} 时为 {@link String}，是整数时为 {@link Long}）
     * @param imgUrl        {@code imgurl}；{@code null} 表示未设置（旧版 {@code isset} 为假）
     * @param typeFilterRaw {@code typefilter} 原文（{@code t} 参数按子串判定，Provide.php:53）
     * @param typeFilter    {@code typefilter} 的各项
     * @param dataFilter    {@code datafilter} 的白名单条件
     * @param cacheTime     {@code cachetime}（秒）
     * @param from          {@code from}（只用于视频）
     * @param detailIncHits {@code detail_inc_hits == 1}（本批只读，不写计数）
     */
    public record ModuleSettings(boolean open, boolean charge, Set<IpAddress> auth, int ignoredAuth, int pageSize,
                                 Object pageSizeJson, String imgUrl, String typeFilterRaw, List<Integer> typeFilter,
                                 List<LegacyCondition> dataFilter, int cacheTime, String from, boolean detailIncHits) {

        public ModuleSettings {
            auth = Collections.unmodifiableSet(new LinkedHashSet<>(auth));
            typeFilter = List.copyOf(typeFilter);
            dataFilter = List.copyOf(dataFilter);
        }

        static ModuleSettings closed() {
            return new ModuleSettings(false, false, Set.of(IpAddress.parse(LOOPBACK).orElseThrow()), 0, 20, "20", null,
                    "", List.of(), List.of(), 0, "", false);
        }
    }

    public ProvideSettings {
        EnumMap<ProvideModule, ModuleSettings> m = new EnumMap<>(ProvideModule.class);
        for (ProvideModule pm : ProvideModule.values()) {
            m.put(pm, modules == null ? ModuleSettings.closed() : modules.getOrDefault(pm, ModuleSettings.closed()));
        }
        modules = Collections.unmodifiableMap(m);
    }

    /** 没有旧配置：全部关闭。 */
    public static ProvideSettings closed() {
        return new ProvideSettings(Map.of(), null);
    }

    public ModuleSettings module(ProvideModule m) {
        return modules.get(m);
    }

    /**
     * @param maccms {@code extra/maccms.php} 的解析结果
     */
    public static ProvideSettings from(PhpArray maccms) {
        List<String> errors = new ArrayList<>();
        Map<ProvideModule, ModuleSettings> modules = new EnumMap<>(ProvideModule.class);
        PhpValue api = maccms.get("api");
        for (ProvideModule pm : ProvideModule.values()) {
            PhpValue v = api instanceof PhpArray a ? a.get(pm.key()) : null;
            if (v instanceof PhpArray group) {
                modules.put(pm, module(pm, group, errors));
            } else if (v != null && !(v instanceof PhpValue.PhpNull)) {
                errors.add("api." + pm.key() + "（应为数组）");
            }
        }
        String protocol = null;
        PhpValue upload = maccms.get("upload");
        if (upload instanceof PhpArray u && u.get("protocol") != null && !(u.get("protocol") instanceof PhpValue.PhpNull)) {
            protocol = scalar(u.get("protocol")).orElse(null);
            if (protocol == null || !protocol.matches("[a-z]{0,10}")) {
                errors.add("upload.protocol（空或小写字母）");
                protocol = "http";
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Provide 旧配置 extra/maccms.php 不受支持：" + String.join("；", errors), null);
        }
        return new ProvideSettings(modules, protocol);
    }

    private static ModuleSettings module(ProvideModule pm, PhpArray g, List<String> errors) {
        String k = "api." + pm.key() + ".";
        boolean open = flag(g, "status", k, errors);
        boolean charge = flag(g, "charge", k, errors);
        boolean incHits = pm == ProvideModule.VOD && flag(g, "detail_inc_hits", k, errors);

        Set<IpAddress> auth = new LinkedHashSet<>();
        auth.add(IpAddress.parse(LOOPBACK).orElseThrow());
        int ignored = 0;
        String authRaw = text(g, "auth", k, errors);
        if (!authRaw.isEmpty()) {
            for (String item : authRaw.split("#", -1)) {
                String t = PhpCompat.trim(item);
                if (t.isEmpty()) {
                    continue;
                }
                Optional<IpAddress> ip = IpAddress.parse(t);
                if (ip.isPresent()) {
                    auth.add(ip.get());
                } else {
                    ignored++;
                }
            }
        }

        int pageSize = 20;
        Object pageSizeJson = 20L;
        PhpValue ps = g.get("pagesize");
        if (ps instanceof PhpValue.PhpInt i && i.value() >= 1 && i.value() <= 1000) {
            pageSize = (int) i.value();
            pageSizeJson = i.value();
        } else if (ps instanceof PhpValue.PhpString s && s.value().matches("[1-9][0-9]{0,3}")
                && Integer.parseInt(s.value()) <= 1000) {
            pageSize = Integer.parseInt(s.value());
            pageSizeJson = s.value();
        } else if (!(ps == null || ps instanceof PhpValue.PhpNull
                || (ps instanceof PhpValue.PhpString s && s.value().isEmpty()))) {
            errors.add(k + "pagesize（1 到 1000 的整数，或留空）");
        }

        String imgUrl = null;
        PhpValue img = g.get("imgurl");
        if (img != null && !(img instanceof PhpValue.PhpNull)) {
            imgUrl = text(g, "imgurl", k, errors);
            if (!imgUrl.isEmpty() && !(imgUrl.matches("(?:https?:)?//[\\x21-\\x7e]+"))) {
                errors.add(k + "imgurl（空，或 http://、https://、// 开头且不含空白与控制字符）");
            }
        }

        String typeFilterRaw = text(g, "typefilter", k, errors);
        List<Integer> typeFilter = new ArrayList<>();
        if (!typeFilterRaw.isEmpty()) {
            if (!TYPE_FILTER.matcher(typeFilterRaw).matches()) {
                errors.add(k + "typefilter（空，或逗号分隔的分类 id）");
            } else {
                for (String s : typeFilterRaw.split(",")) {
                    typeFilter.add(Integer.parseInt(s));
                }
            }
        }

        List<LegacyCondition> dataFilter = dataFilter(pm, text(g, "datafilter", k, errors), k, errors);

        int cacheTime = 0;
        String ct = text(g, "cachetime", k, errors);
        if (!ct.isEmpty()) {
            if (!ct.matches("[0-9]{1,7}")) {
                errors.add(k + "cachetime（空或非负整数）");
            } else {
                cacheTime = Integer.parseInt(ct);
            }
        }

        String from = pm == ProvideModule.VOD ? text(g, "from", k, errors) : "";
        if (from.chars().anyMatch(c -> c < 0x20 || c == 0x7f) || from.length() > 1024) {
            errors.add(k + "from（不含控制字符）");
        }
        return new ModuleSettings(open, charge, auth, ignored, pageSize, pageSizeJson, imgUrl, typeFilterRaw, typeFilter,
                dataFilter, cacheTime, from, incHits);
    }

    /**
     * datafilter 的白名单语法（16 文档第 3.4 节）：空串，或 {@code <前缀>_<列> <运算符> <整数>} 以 AND 连接。旧版是原样拼进 WHERE 的
     * SQL（Provide.php:112-114），新版不执行配置中的 SQL。
     */
    static List<LegacyCondition> dataFilter(ProvideModule pm, String raw, String k, List<String> errors) {
        String s = raw.strip();
        if (s.isEmpty()) {
            return List.of();
        }
        List<LegacyCondition> out = new ArrayList<>();
        for (String part : AND.split(s, -1)) {
            Matcher m = CONDITION.matcher(part.strip().toLowerCase(Locale.ROOT));
            LegacyCondition.Column col = null;
            if (m.matches() && m.group(1).equals(pm.table().prefix())) {
                col = switch (m.group(2)) {
                    case "status" -> LegacyCondition.Column.STATUS;
                    case "level" -> LegacyCondition.Column.LEVEL;
                    case "lock" -> LegacyCondition.Column.LOCK;
                    case "isend" -> LegacyCondition.Column.ISEND;
                    case "copyright" -> LegacyCondition.Column.COPYRIGHT;
                    case "plot" -> LegacyCondition.Column.PLOT;
                    default -> null;
                };
            }
            if (col == null || !col.appliesTo(pm.table())) {
                errors.add(k + "datafilter（只支持 " + pm.table().prefix() + "_status/level/lock"
                        + (pm.table() == LegacyTable.VOD ? "/isend/copyright/plot" : "")
                        + " 与整数比较的 AND 组合；不执行配置中的 SQL）");
                return List.of();
            }
            LegacyCondition.Op op = switch (m.group(3)) {
                case "=" -> LegacyCondition.Op.EQ;
                case "!=", "<>" -> LegacyCondition.Op.NE;
                case ">" -> LegacyCondition.Op.GT;
                case ">=" -> LegacyCondition.Op.GE;
                case "<" -> LegacyCondition.Op.LT;
                default -> LegacyCondition.Op.LE;
            };
            out.add(new LegacyCondition(col, op, Long.parseLong(m.group(4))));
        }
        if (out.size() > 16) {
            errors.add(k + "datafilter（最多 16 个条件）");
            return List.of();
        }
        return out;
    }

    private static boolean flag(PhpArray g, String key, String k, List<String> errors) {
        PhpValue v = g.get(key);
        if (v == null || v instanceof PhpValue.PhpNull) {
            return false;
        }
        if (v instanceof PhpValue.PhpBool b) {
            return b.value();
        }
        if (v instanceof PhpValue.PhpInt i) {
            return i.value() == 1;
        }
        if (v instanceof PhpValue.PhpString s && (s.value().isEmpty() || s.value().matches("[0-9]{1,3}"))) {
            return !s.value().isEmpty() && Integer.parseInt(s.value()) == 1;
        }
        errors.add(k + key + "（0 或 1）");
        return false;
    }

    private static String text(PhpArray g, String key, String k, List<String> errors) {
        PhpValue v = g.get(key);
        if (v == null || v instanceof PhpValue.PhpNull) {
            return "";
        }
        Optional<String> s = scalar(v);
        if (s.isEmpty()) {
            errors.add(k + key + "（应为字符串）");
            return "";
        }
        return s.get();
    }

    private static Optional<String> scalar(PhpValue v) {
        return switch (v) {
            case PhpValue.PhpString s -> Optional.of(s.value());
            case PhpValue.PhpInt i -> Optional.of(Long.toString(i.value()));
            default -> Optional.empty();
        };
    }
}
