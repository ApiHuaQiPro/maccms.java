package com.maccms.backend.staticgen.application;

import com.maccms.backend.content.application.PhpIntval;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 静态页的地址与文件名（B16，25 文档第 3 节），与旧代码逐字一致：
 * <ul>
 * <li>{@code mac_url} 的静态分支（application/common.php:1732-2428：{@code view.*} 为 2（播放/下载页 2～4）时按 {@code path.*} 规则替换
 * 占位符 {@code {id} {en} {page} {type_id} {type_en} {type_pid} {type_pen} {md5} {year} {month} {day} {sid} {nid}}）；</li>
 * <li>{@code reset_html_filename}（:2802-2824）：地址 → 站点根目录下的文件名；</li>
 * <li>{@code Make.php} 里不经 {@code mac_url} 的固定文件名（首页、手机首页、地图、RSS、自定义页）。</li>
 * </ul>
 * 只实现静态分支：{@code view} 不是静态模式时由调用方先按旧版提示"浏览模式非静态，无法生成"拒绝（{@link #isStatic}）。占位符替换按旧版
 * 的位置表（{@code str_replace} 数组形式，缺位按空串），所以旧版的怪现象（如分类规则里的 {@code {type_en}} 取不到值）照旧。
 * 输出与 PHP 黄金向量 {@code staticgen/b16-php80-golden.json} 逐条一致（{@code StaticPathsGoldenTest}）。不可变、无 I/O。
 *
 * <p>旧站 {@code MAC_PATH} 固定为 {@code /}（站点的部署前提，11 文档第 4.4 节），所以地址都以 {@code /} 开头。
 */
public final class StaticPaths {

    /** {@code mac_url} 的占位符，顺序即旧 {@code $replace_from}。 */
    private static final String[] FROM = {"{id}", "{en}", "{page}", "{type_id}", "{type_en}", "{type_pid}", "{type_pen}",
            "{md5}", "{year}", "{month}", "{day}", "{sid}", "{nid}"};

    /** 分类（{@code mac_type} 的一行）。 */
    public record TypeRef(long id, String en, long pid, int mid) {
        public TypeRef {
            en = en == null ? "" : en;
        }
    }

    /**
     * 视频或文章（旧 {@code listData(..., addition=1)} 的一行）：{@code type} 为本分类，{@code parent} 为上级分类（顶级分类、分类不存在时为 null，
     * 对应旧 {@code $type_list[0]} 取不到）。
     */
    public record Item(long id, String en, long time, long typeId, TypeRef type, TypeRef parent) {
        public Item {
            en = en == null ? "" : en;
        }
    }

    /** 专题。 */
    public record Topic(long id, String en) {
        public Topic {
            en = en == null ? "" : en;
        }
    }

    private final PhpArray view;
    private final PhpArray path;
    private final ZoneId zone;

    /**
     * @param maccms 旧 {@code extra/maccms.php} 的内容（用其中的 {@code view}、{@code path} 两组）
     * @param zone   旧站时区（{@code application/config.php} 的 {@code default_timezone}，PRC）
     */
    public StaticPaths(PhpArray maccms, ZoneId zone) {
        this.view = maccms != null && maccms.get("view") instanceof PhpArray v ? v : new PhpArray(java.util.Map.of());
        this.path = maccms != null && maccms.get("path") instanceof PhpArray p ? p : new PhpArray(java.util.Map.of());
        this.zone = zone;
    }

    /** {@code $config['view'][$key]} 按 PHP 转成整数（{@code intval}，旧代码用 {@code <2}、{@code ==2} 比较）。 */
    public long viewMode(String key) {
        return PhpIntval.of(PhpValues.text(view.get(key)));
    }

    /** 旧 {@code $config['view'][$key] < 2} 的反面。 */
    public boolean isStatic(String key) {
        return viewMode(key) >= 2;
    }

    public String pageSp() {
        return text(path.get("page_sp"));
    }

    public String suffix() {
        return text(path.get("suffix"));
    }

    // ---- Make.php 的固定文件名 ----

    /** 首页（Make::index）：{@code index.html}，手机首页 {@code wap_index.html}。 */
    public static String index(boolean wap) {
        return wap ? "wap_index.html" : "index.html";
    }

    /** 地图（Make::map）。 */
    public static String map() {
        return "map.html";
    }

    /** RSS（Make::rss）：{@code rss/<ac2>[<page_sp><页码>].xml}，页码 1 不带。 */
    public String rss(String ac2, int page) {
        return "rss/" + ac2 + (page > 1 ? pageSp() + page : "") + ".xml";
    }

    /** 自定义页（Make::label）：{@code label/<文件名>}，文件名原样（含扩展名）。 */
    public static String label(String file) {
        return "label/" + file;
    }

    // ---- mac_url 静态分支 ----

    /** 分类页（{@code mac_url_type($info, ['id'=>…,'page'=>$i])}，vod/type 或 art/type）。要求 {@code view.<tab>_type == 2}。 */
    public String type(String tab, TypeRef t, int page) {
        String model = tab + "_type";
        requireMode(model, 2);
        String p = rule(model);
        p = withIndex(p);
        String pageText = page == 1 ? "" : Integer.toString(page);
        List<String> to = new ArrayList<>(List.of(Long.toString(t.id()), t.en(), pageText, Long.toString(t.id()), "", "", ""));
        // vod/type 无条件补 md5；art/type 只在规则含 {md5} 时补（common.php:1823、1999-2001），位置相同
        if (tab.equals("vod") || p.contains("{md5}")) {
            to.add(md5(Long.toString(t.id())));
        }
        if (!pageText.isEmpty()) {
            p = p + pageSp() + pageText;
        }
        return finish(p, to);
    }

    /** 视频详情页（{@code mac_url_vod_detail}）。要求 {@code view.vod_detail == 2}。 */
    public String vodDetail(Item v) {
        requireMode("vod_detail", 2);
        String p = withIndex(rule("vod_detail"));
        List<String> to = itemReplacements(v);
        to.add(md5(Long.toString(v.id())));
        addDate(to, v.time());
        return finish(p, to);
    }

    /**
     * 播放页、下载页（{@code mac_url_vod_play}/{@code mac_url_vod_down}，{@code view.vod_play}/{@code vod_down} 为 2、3、4）。
     *
     * @param flag {@code play} 或 {@code down}
     */
    public String vodEpisode(String flag, Item v, int sid, int nid) {
        String model = "vod_" + flag;
        long mode = viewMode(model);
        if (mode < 2 || mode > 4) {
            throw new IllegalStateException("浏览模式非静态");
        }
        String p = withIndex(rule(model));
        List<String> to = itemReplacements(v);
        to.add(md5(Long.toString(v.id())));
        String query = "?" + v.id() + "-" + sid + "-" + nid;
        if (mode == 2) {
            p = p + "." + suffix() + query;
        } else if (mode == 3) {
            p = p + pageSp() + sid + pageSp() + nid;
        } else {
            p = p + pageSp() + sid + pageSp() + "1" + "." + suffix() + query;
        }
        addDate(to, v.time());
        to.add(Integer.toString(sid));
        to.add(Integer.toString(nid));
        return finish(p, to);
    }

    /** 文章详情页（{@code mac_url_art_detail($v, ['page'=>$i])}）。要求 {@code view.art_detail == 2}。 */
    public String artDetail(Item a, int page) {
        requireMode("art_detail", 2);
        String p = withIndex(rule("art_detail"));
        List<String> to = itemReplacements(a);
        to.add(md5(Long.toString(a.id())));
        if (page > 1) {
            p = p + pageSp() + page;
        }
        addDate(to, a.time());
        return finish(p, to);
    }

    /** 专题首页（{@code mac_url_topic_index(['page'=>$i])}）。要求 {@code view.topic_index == 2}。 */
    public String topicIndex(int page) {
        requireMode("topic_index", 2);
        String p = withIndex(rule("topic_index"));
        if (page > 1) {
            p = p + pageSp() + page;
        }
        return finish(p, List.of());
    }

    /** 专题详情页（{@code mac_url_topic_detail}）。要求 {@code view.topic_detail == 2}。 */
    public String topicDetail(Topic t) {
        requireMode("topic_detail", 2);
        String p = withIndex(rule("topic_detail"));
        List<String> to = new ArrayList<>(List.of(Long.toString(t.id()), t.en(), "", "", "", "", ""));
        if (p.contains("{md5}")) {
            to.add(md5(Long.toString(t.id())));
        }
        return finish(p, to);
    }

    // ---- reset_html_filename ----

    /**
     * 旧 {@code reset_html_filename}（common.php:2802-2824）：以 {@code /} 结尾补 {@code index}；整个串里没有 {@code .} 时补
     * {@code .<suffix>}；去掉 {@code ?} 之后；前面加 {@code ./} 并把 {@code //} 换成 {@code /}（两遍）。返回值去掉开头的 {@code ./}，
     * 即相对站点根目录的路径（{@code MAC_PATH} 固定为 {@code /}）。
     */
    public static String htmlFile(String link, String suffix) {
        String f = link;
        if (f.endsWith("/")) {
            f = f + "index";
        }
        if (f.indexOf('.') < 0) {
            f = f + "." + suffix;
        }
        int q = f.indexOf('?');
        if (q >= 0) {
            f = f.substring(0, q);
        }
        f = ("./" + f).replace("//", "/");
        f = f.replace("//", "/");
        return f.startsWith("./") ? f.substring(2) : f;
    }

    public String htmlFile(String link) {
        return htmlFile(link, suffix());
    }

    // ---- 内部 ----

    private void requireMode(String key, long mode) {
        if (viewMode(key) != mode) {
            // 旧版 view 为 3 及以上时 mac_url 走动态分支，生成的"文件名"是动态地址（如 ./index.php/vod/type/id/1.html），新版不写这种文件
            throw new IllegalStateException("浏览模式非静态");
        }
    }

    private String rule(String key) {
        String r = text(path.get(key));
        if (r.isEmpty() || r.equals("0")) {
            // 旧版 empty($path) 时退回动态地址（common.php:2376），同上
            throw new IllegalStateException("静态规则为空");
        }
        return r;
    }

    /** 规则以 / 结尾时补 index（各分支的 {@code if(substr($path,…)=='/') $path .= 'index';}）。 */
    private static String withIndex(String p) {
        return p.endsWith("/") ? p + "index" : p;
    }

    /** 视频/文章的前 7 个替换值：{@code [id, en, '', type_id, type.type_en, type_1.type_id, type_1.type_en]}。 */
    private static List<String> itemReplacements(Item v) {
        return new ArrayList<>(List.of(Long.toString(v.id()), v.en(), "", Long.toString(v.typeId()),
                v.type() == null ? "" : v.type().en(), v.parent() == null ? "" : Long.toString(v.parent().id()),
                v.parent() == null ? "" : v.parent().en()));
    }

    private void addDate(List<String> to, long time) {
        ZonedDateTime d = Instant.ofEpochSecond(time).atZone(zone);
        to.add(String.format("%04d", d.getYear()));
        to.add(String.format("%02d", d.getMonthValue()));
        to.add(String.format("%02d", d.getDayOfMonth()));
    }

    /** {@code mac_url} 的尾部（common.php:2361-2375）。 */
    private String finish(String p, List<String> to) {
        for (int i = 0; i < FROM.length; i++) {
            p = p.replace(FROM[i], i < to.size() ? to.get(i) : "");
        }
        p = p.replace("//", "/");
        boolean delimiter = false;
        if (p.endsWith("/index")) {
            delimiter = true;
            p = p.substring(0, p.length() - 5);
        }
        if (!delimiter && p.indexOf('.') < 0) {
            p = p + "." + suffix();
        }
        return p.startsWith("/") ? p : "/" + p;
    }

    private static String md5(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(PhpValue v) {
        return PhpValues.text(v);
    }
}
