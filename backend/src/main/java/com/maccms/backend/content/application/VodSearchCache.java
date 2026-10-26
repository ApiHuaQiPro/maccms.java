package com.maccms.backend.content.application;

import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.content.infrastructure.VodSearchMapper;
import com.maccms.backend.content.infrastructure.VodSearchRow;
import com.maccms.backend.content.infrastructure.VodSearchWriteMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

/**
 * 视频搜索缓存 mac_vod_search（B11，docs/architecture/20-b11-comment-gbook-counters.md 第 6 节）：旧配置 {@code app.vod_search_optimise}
 * 含 {@code frontend} 时，前台视频列表的 wd/name/tag/class/actor/director 先查缓存（旧 {@code VodSearch::getResultIdList}，
 * application/common/model/VodSearch.php:18-64），结果不超过 {@value #MAX_ID_COUNT} 个的条件改为 {@code vod_id IN (...)}
 * （Vod.php:404-459）。
 *
 * <p>照搬旧版的数组语义（含旧版的缺陷，D-B11-8）：结果是"键 → 值"的有序数组；多个字段、逗号多值的各项之间用 PHP 的数组 {@code +}
 * 合并，即<b>按键</b>合并（后者只补上前者没有的键），不是取并集；{@code array_filter} 保留原来的键；空结果为 {@code [0]}。
 * 未命中时扫描全部 mac_vod（不看审核与分类），新版最多取 {@value #SCAN_LIMIT} 个（D-B11-9：超过 1000 个时旧版也不用这份列表）。
 * 命中次数是原子 +1（旧版先读后写）。同一个词并发首次搜索时只有一个插入成功，其余按命中处理（旧版重复键报错，500）。
 */
public class VodSearchCache {

    /** 旧 {@code VodSearch::$maxIdCount}。 */
    public static final int MAX_ID_COUNT = 1000;
    static final int SCAN_LIMIT = MAX_ID_COUNT + 1;
    private static final int MAX_WORD_CODE_POINTS = 128;
    private static final Logger log = LoggerFactory.getLogger(VodSearchCache.class);

    private final VodSearchMapper reader;
    private final VodSearchWriteMapper writer;
    private final Clock clock;

    public VodSearchCache(VodSearchMapper reader, VodSearchWriteMapper writer, Clock clock) {
        this.reader = reader;
        this.writer = writer;
        this.clock = clock;
    }

    /** 可写数据源关闭时为 false：缓存不可用，查询按未开启优化处理（不写库）。 */
    public boolean writable() {
        return writer != null;
    }

    /**
     * 旧 {@code getResultIdList($word, $field, $multiple)}。
     *
     * @param word 旧版 {@code mac_param_url} 之后的值（{@code htmlspecialchars(urldecode(trim()))}）
     * @return 键 → vod_id 的有序数组（PHP 数组）
     */
    public LinkedHashMap<Long, Long> resultIds(String word, VodSearchField field, boolean multiple) {
        String w = phpTrim(word).replace(",,", "");
        if (w.isEmpty()) {
            return new LinkedHashMap<>();
        }
        if (multiple) {
            LinkedHashMap<Long, Long> out = new LinkedHashMap<>();
            for (String part : w.split(",", -1)) {
                union(out, resultIds(part, field, false));
            }
            return unique(out);
        }
        String key = md5(w + "@" + field.legacyName());
        long now = clock.instant().getEpochSecond();
        VodSearchRow row = reader.selectByKey(key);
        LinkedHashMap<Long, Long> ids = new LinkedHashMap<>();
        if (row == null) {
            List<Long> found = reader.selectMatchingIds(field.name(), likePattern(w), SCAN_LIMIT);
            for (int i = 0; i < found.size(); i++) {
                ids.put((long) i, found.get(i));
            }
            StringJoiner joined = new StringJoiner(",");
            found.forEach(id -> joined.add(Long.toString(id)));
            try {
                writer.insertResult(key, codePointPrefix(w, MAX_WORD_CODE_POINTS), field.legacyName(), now, found.size(),
                        joined.toString());
            } catch (DuplicateKeyException e) {
                // 并发的同词首次搜索：另一请求已插入，本次按命中计数（旧版此处报错）
                touch(key, now);
            }
        } else {
            String[] parts = (row.resultIds() == null ? "" : row.resultIds()).split(",", -1);
            for (int i = 0; i < parts.length; i++) {
                // array_filter：去掉 PHP 假值（"" 与 "0"），保留原来的键
                if (!parts[i].isEmpty() && !parts[i].equals("0")) {
                    ids.put((long) i, PhpIntval.of(parts[i]));
                }
            }
            touch(key, now);
        }
        if (ids.isEmpty()) {
            ids.put(0L, 0L);
        }
        return ids;
    }

    /** 旧 {@code VodSearch::$updateTopCount}：新增视频时检查的搜索结果行数。 */
    static final int UPDATE_TOP_COUNT = 50000;

    /**
     * 旧 {@code VodSearch::checkAndUpdateTopResults(['vod_id' => $id] + $data)}（VodSearch.php:97-128，后台新增视频且开启前台优化时调用，
     * common/model/Vod.php:724-726）：命中次数最多的前 50000 行里，每行的每个字段（{@code search_field} 按 {@code |} 拆分）在新视频里非空且
     * 不区分大小写地包含该词时，把编号追加到这一行（同一行多个字段都包含时追加多次，同旧版）。旧版把候选行缓存 1 小时或 1 天、缓存失效时顺带
     * 做一次非强制的过期清理；新版每次新增时读取候选行，不做附带清理（22 文档第 10 节）。
     *
     * @param vod 写库前的视频数据（列名 → 值；没有的列视为未设置）
     */
    public void appendNewVod(long vodId, Map<String, byte[]> vod) {
        if (writer == null || vodId <= 0) {
            return;
        }
        long now = clock.instant().getEpochSecond();
        for (Map<String, String> row : reader.selectTopRows(UPDATE_TOP_COUNT)) {
            String word = row.get("search_word");
            byte[] needle = (word == null ? "" : word).getBytes(StandardCharsets.UTF_8);
            String fields = row.get("search_field");
            for (String field : (fields == null ? "" : fields).split("\\|", -1)) {
                byte[] value = vod.get(field);
                if (value == null || value.length == 0 || !containsIgnoreAsciiCase(value, needle)) {
                    continue;
                }
                writer.appendResult(row.get("search_key"), vodId, now);
            }
        }
    }

    /** PHP {@code stripos($haystack, $needle) !== false}：按字节、只对 ASCII 字母不区分大小写；空词总是包含（PHP 8）。 */
    static boolean containsIgnoreAsciiCase(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (lowerAscii(haystack[i + j]) != lowerAscii(needle[j])) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static int lowerAscii(byte b) {
        int c = b & 0xFF;
        return c >= 'A' && c <= 'Z' ? c + 32 : c;
    }

    /**
     * 旧 {@code VodSearch::clearOldResult(true)}（后台"清理缓存"，admin/controller/Index.php:164；VodSearch.php:146-167）：删除结果更新时间早于
     * "现在 − {@code cacheMinutes} × 60"的全部行（强制清理不排除 vod_actor）。旧版"每天最多清理一次"的间隔记录只约束非强制清理，这里不需要。
     *
     * @return 可写数据源关闭时为 false（什么都不做）
     */
    public boolean clearOld(long cacheMinutes) {
        if (writer == null) {
            return false;
        }
        long now = clock.instant().getEpochSecond();
        if (cacheMinutes > now / 60) {
            // 界限早于 1970 年：更新时间是无符号整数，旧版（超大值按浮点计算）什么都不删；这里同时避免 long 溢出成"现在之后"而删光
            return true;
        }
        writer.deleteUpdatedBefore(now - cacheMinutes * 60);
        return true;
    }

    /**
     * 旧 Vod.php:404-459：按 wd、name、tag、class、actor、director 的顺序，结果不超过 1000 个的条件把结果按键合并进总列表、并去掉该字段的
     * LIKE 条件。返回去重后的 vod_id（为空时不加 IN 条件）与被替换掉的字段。
     *
     * @param words 字段 → 旧版的取值（已 htmlspecialchars；未设置的字段不放进来），迭代顺序无关
     */
    public Resolution resolve(Map<VodSearchField, String> words) {
        LinkedHashMap<Long, Long> all = new LinkedHashMap<>();
        Set<VodSearchField> replaced = new LinkedHashSet<>();
        for (VodSearchField f : VodSearchField.values()) {
            String w = words.get(f);
            if (w == null || w.isEmpty() || w.equals("0")) {
                continue;
            }
            LinkedHashMap<Long, Long> tmp = resultIds(w, f, f.multiple());
            if (tmp.size() <= MAX_ID_COUNT) {
                union(all, tmp);
                replaced.add(f);
            }
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(unique(all).values()));
        return new Resolution(ids, replaced);
    }

    /**
     * @param ids      要加的 {@code vod_id IN} 条件（空列表表示不加）
     * @param replaced 改用缓存结果、不再加 LIKE 条件的字段
     */
    public record Resolution(List<Long> ids, Set<VodSearchField> replaced) {
    }

    // ------------------------------------------------------------------ PHP 数组语义

    /** {@code $a += $b}：只补上 a 没有的键，追加在末尾。 */
    static void union(LinkedHashMap<Long, Long> a, Map<Long, Long> b) {
        for (Map.Entry<Long, Long> e : b.entrySet()) {
            a.putIfAbsent(e.getKey(), e.getValue());
        }
    }

    /** {@code array_unique}：值重复时保留第一次出现的键。 */
    static LinkedHashMap<Long, Long> unique(LinkedHashMap<Long, Long> in) {
        LinkedHashMap<Long, Long> out = new LinkedHashMap<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (Map.Entry<Long, Long> e : in.entrySet()) {
            if (seen.add(e.getValue())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /**
     * 旧版 {@code ['LIKE', '%' . $word . '%']}（未转义：% 与 _ 是通配符，反斜线让下一个字符按字面量匹配，末尾的反斜线是字面量）转写成
     * {@code ESCAPE '!'} 下匹配同样字符串的模式。
     */
    static String likePattern(String word) {
        StringBuilder sb = new StringBuilder(word.length() + 4).append('%');
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c == '\\' && i + 1 < word.length()) {
                char next = word.charAt(++i);
                sb.append('!').append(next);
                if (Character.isHighSurrogate(next) && i + 1 < word.length()) {
                    sb.append(word.charAt(++i));
                }
            } else if (c == '!') {
                sb.append("!!");
            } else {
                sb.append(c);
            }
        }
        return sb.append('%').toString();
    }

    private void touch(String key, long now) {
        try {
            writer.touchResult(key, now);
        } catch (DataAccessException e) {
            // 命中次数只是统计，失败不影响本次搜索结果
            log.warn("vod search cache touch failed: {}", SafeExceptionLog.typeChain(e));
        }
    }

    static String phpTrim(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && isTrimChar(s.charAt(a))) {
            a++;
        }
        while (b > a && isTrimChar(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(a, b);
    }

    private static boolean isTrimChar(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\0' || c == 0x0B;
    }

    private static String codePointPrefix(String s, int max) {
        return s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max));
    }

    static String md5(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
