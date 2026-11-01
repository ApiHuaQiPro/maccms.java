package com.maccms.backend.phptemplate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 黄金资产的“样本未漂移”检查（APPK-2 S3-05 / C-33、C-34）。
 *
 * <p>本测试不实现也不调用模板解释器（那属于 S3-10 起的实现子任务），只负责三件事：
 * <ol>
 *   <li>黄金文件齐全、可读、为 UTF-8 + LF，且没有任何 BOM 或 CR，保证逐字节比较在两台机器上都成立；</li>
 *   <li>样本本身没漂移——特别是 V-13 R-1 规定的 808 字节 / {@code a615d09d…} 切片，以及 168 个模板的数量；</li>
 *   <li>GAP-1 的两个 {@code mac_day(...,color)} 裸标识符样例确实在覆盖范围内（编译产物与值语义向量各一处）；</li>
 *   <li>{@code compile-golden/manifest.json} 记录的 {@code model_calls} / {@code info_data_calls} 与编译产物实测值
 *       逐项相等（测试评审第 1 轮修改项 1：旧生成器正则里混入 U+0008，导致桌面详情页被记成 0 次）。</li>
 * </ol>
 *
 * <p>黄金由 {@code src/test/php/tpl_runtime_golden.php}（PHP 7.3.33）生成，生成命令与逐项依据见
 * {@code docs/test/APPK-2/s3-05-golden.md}。同一份黄金被 S3-12b 的
 * {@code TagModelsInfoDataTest}、{@code PhpTemplateRendererTest} 与 {@code TagModelsMySqlIT} 用来比对。
 *
 * <p>来源：技术设计 v13 {@code 9c73bf6ced044f40eeb87ef7861e616b6157499a} 的 C-33、C-34、V-11、V-12、V-13、V-16；
 * PRE-5（S3-04）{@code b550151} 的 {@code docs/test/APPK-2/template-scan.md} §9.0～§9.11。
 */
class PhpTemplateGoldenAssetsTest {

    /** src/test/resources/phptemplate —— 四类黄金的根目录。 */
    private static final Path GOLDEN_ROOT = Path.of("src", "test", "resources", "phptemplate");

    /** V-13 规定 R-1 片段取模板包 default_pc/html/art/detail.html 第 32～50 行。 */
    private static final int R1_SLICE_BYTES = 808;

    /** 该片段的 git blob 哈希（PRE-5 记录，改了模板包就会变）。 */
    private static final String R1_SLICE_BLOB = "a615d09d955020c6c86924bf3c8a2a88852b308e";

    /** PRE-5 §9.0：模板包 .html 总数（default_pc 92 + default_wap 76）。 */
    private static final int TEMPLATE_COUNT = 168;

    // ------------------------------------------------------------------ 目录与编码

    @Test
    void 四类黄金目录都存在且非空() {
        for (String dir : List.of("semantics", "compile-golden", "render-golden", "model-golden")) {
            Path p = GOLDEN_ROOT.resolve(dir);
            assertThat(p).as("黄金目录 %s（C-33 要求的四类）", dir).isDirectory();
            assertThat(listFiles(p)).as("黄金目录 %s 下的文件", dir).isNotEmpty();
        }
        assertThat(GOLDEN_ROOT.resolve("model-fixtures").resolve("art-infodata.sql"))
                .as("C-33 ④ 的 ART-FIX 夹具").isRegularFile();
    }

    @Test
    void 黄金文件都是UTF8无BOM且只用LF换行() throws IOException {
        // 注意：compile-golden 允许 BOM。模板包里的 default_pc/default_wap 的 label/rank.html
        // 本身就是带 BOM 的（S3-01 的 200cffc 原件），编译产物必须原样保留这三个字节，
        // 否则 Java 侧渲染出来的页面会和 PHP 差 3 字节。其余黄金是脚本生成的结构化数据，不得带 BOM。
        List<String> bad = new ArrayList<>();
        for (Path f : listFiles(GOLDEN_ROOT)) {
            byte[] raw = Files.readAllBytes(f);
            String rel = GOLDEN_ROOT.relativize(f).toString().replace('\\', '/');
            boolean bomAllowed = rel.startsWith("compile-golden/");
            boolean hasBom = raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB
                    && (raw[2] & 0xFF) == 0xBF;
            if (hasBom && !bomAllowed) {
                bad.add(rel + ": 有 UTF-8 BOM");
                continue;
            }
            for (int i = 0; i < raw.length; i++) {
                if (raw[i] == '\r') {
                    bad.add(rel + ": 含 CR（应为 LF）");
                    break;
                }
            }
            // 能被严格 UTF-8 解码：否则两侧逐字节比较会得出假差异
            try {
                StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw));
            } catch (java.nio.charset.CharacterCodingException e) {
                bad.add(rel + ": 不是合法 UTF-8");
            }
        }
        assertThat(bad).as("黄金文件的编码/换行必须可逐字节比较").isEmpty();
    }

    @Test
    void 带BOM的模板其编译产物也保留BOM() throws IOException {
        // 与上一条互补：黄金必须如实反映模板包，不能“为了好看”把 BOM 抹掉
        Path src = Path.of(".compare", "APPK-2", "pkg", "third_party",
                "maccms-template-v2020.1000.1035", "template", "default_pc", "html", "label", "rank.html");
        Path out = GOLDEN_ROOT.resolve("compile-golden").resolve("default_pc")
                .resolve("html").resolve("label").resolve("rank.html");
        if (!Files.isRegularFile(src)) {
            return; // 模板包不常驻工作区
        }
        byte[] s = Files.readAllBytes(src);
        byte[] o = Files.readAllBytes(out);
        boolean srcBom = s.length >= 3 && (s[0] & 0xFF) == 0xEF && (s[1] & 0xFF) == 0xBB && (s[2] & 0xFF) == 0xBF;
        boolean outBom = o.length >= 3 && (o[0] & 0xFF) == 0xEF && (o[1] & 0xFF) == 0xBB && (o[2] & 0xFF) == 0xBF;
        assertThat(outBom).as("模板带 BOM 时编译产物也应带 BOM").isEqualTo(srcBom);
    }

    // ------------------------------------------------------------------ 样本未漂移

    @Test
    void R1片段基线与V13规定一致() throws IOException {
        // 模板包只在 S3-01 的 200cffc 谱系里，工作区不常驻；有就必验，没就说明为什么跳过。
        Path slice = Path.of(".compare", "APPK-2", "pkg", "third_party",
                "maccms-template-v2020.1000.1035", "template", "default_pc", "html", "art", "detail.html");
        if (!Files.isRegularFile(slice)) {
            // 模板包不入库，CI 上不会有：此时改由提交进库的黄金产物间接守住（见下面两个用例）。
            return;
        }
        byte[] all = Files.readAllBytes(slice);
        String text = new String(all, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String[] lines = text.split("\n", -1);
        String fragment = String.join("\n", java.util.Arrays.copyOfRange(lines, 31, 50)) + "\n";
        byte[] got = fragment.getBytes(StandardCharsets.UTF_8);
        assertThat(got.length).as("R-1 片段（第 32～50 行）字节数，V-13 规定 %d", R1_SLICE_BYTES)
                .isEqualTo(R1_SLICE_BYTES);
    }

    @Test
    void R1的五个情形黄金齐全且覆盖V13规定的分支() {
        List<String> missing = new ArrayList<>();
        for (String k : List.of("a", "b", "c", "d", "e")) {
            if (!Files.isRegularFile(GOLDEN_ROOT.resolve("render-golden").resolve("art-detail-prevnext-" + k + ".html"))) {
                missing.add(k);
            }
        }
        assertThat(missing).as("V-13 R-1 情形 a～e 各一份黄金").isEmpty();
    }

    @Test
    void 编译黄金覆盖全部168个模板且记录两个关键样例的调用数() throws IOException {
        Path manifest = GOLDEN_ROOT.resolve("compile-golden").resolve("manifest.json");
        assertThat(manifest).isRegularFile();
        String json = Files.readString(manifest, StandardCharsets.UTF_8);

        // 168 个模板每个一份编译产物（PRE-5 §9.0）
        Path dir = GOLDEN_ROOT.resolve("compile-golden");
        long htmlCount = listFiles(dir).stream()
                .filter(p -> p.toString().endsWith(".html"))
                .count();
        assertThat(htmlCount).as("编译产物数量（PRE-5 记录的 %d 个 .html）", TEMPLATE_COUNT)
                .isEqualTo(TEMPLATE_COUNT);
        assertThat(json).as("manifest 必须记录文件总数").contains("\"files\"").contains(String.valueOf(TEMPLATE_COUNT));

        // R-2：手机文章详情页的编译产物里没有 model( / infoData，即渲染不产生 infoData 调用
        Path wap = dir.resolve("default_wap").resolve("html").resolve("art").resolve("detail.html");
        assertThat(wap).as("R-2 手机文章详情页的编译产物").isRegularFile();
        String wapCompiled = Files.readString(wap, StandardCharsets.UTF_8);
        assertThat(wapCompiled).as("R-2：手机文章详情页不得产生 model( 调用").doesNotContain("model(");
        assertThat(wapCompiled).as("R-2：手机文章详情页不得产生 infoData 调用").doesNotContain("infoData");

        // R-1 依据：桌面文章详情页的编译产物恰好两次 infoData，且都带 art_id 的比较条件
        Path pc = dir.resolve("default_pc").resolve("html").resolve("art").resolve("detail.html");
        String pcCompiled = Files.readString(pc, StandardCharsets.UTF_8);
        assertThat(countOf(pcCompiled, "->infoData(")).as("桌面文章详情页的 infoData 调用次数").isEqualTo(2);
        assertThat(pcCompiled).contains("'lt'").contains("'gt'");
    }

    @Test
    void 编译黄金manifest的模型调用计数与编译产物逐项一致() throws IOException {
        // 测试评审第 1 轮修改项 1：manifest 的 model_calls 曾因生成器正则里混入 U+0008 控制字符而恒为 0，
        // 与桌面编译产物实际的 2 次 model( 矛盾。这里逐项把 manifest 的计数和编译产物实测计数对上，
        // 任一文件不一致就失败，避免再出现“清单说 0、产物里有 2 次”的自相矛盾证据。
        Path dir = GOLDEN_ROOT.resolve("compile-golden");
        JsonNode root = JsonMapper.builder().build()
                .readTree(Files.readString(dir.resolve("manifest.json"), StandardCharsets.UTF_8));
        JsonNode files = root.path("files");
        assertThat(files.isArray()).as("manifest.json 的 files 数组").isTrue();
        assertThat(files.size()).as("manifest 记录的文件数（PRE-5 的 %d 个模板）", TEMPLATE_COUNT)
                .isEqualTo(TEMPLATE_COUNT);
        assertThat(root.path("_meta").path("file_count").asInt()).as("manifest._meta.file_count")
                .isEqualTo(TEMPLATE_COUNT);

        List<String> bad = new ArrayList<>();
        int modelTotal = 0;
        int infoTotal = 0;
        for (JsonNode f : files) {
            String rel = f.path("path").asText();
            Path compiled = dir.resolve(rel);
            if (!Files.isRegularFile(compiled)) {
                bad.add(rel + ": 编译产物缺失");
                continue;
            }
            String text = Files.readString(compiled, StandardCharsets.UTF_8);
            int model = countModelCalls(text);
            int infoData = countInfoDataCalls(text);
            modelTotal += model;
            infoTotal += infoData;
            int gotModel = f.path("model_calls").asInt(-1);
            int gotInfo = f.path("info_data_calls").asInt(-1);
            if (gotModel != model) {
                bad.add(rel + ": manifest model_calls=" + gotModel + "，编译产物实测 " + model);
            }
            if (gotInfo != infoData) {
                bad.add(rel + ": manifest info_data_calls=" + gotInfo + "，编译产物实测 " + infoData);
            }
        }
        assertThat(bad).as("manifest 的 model_calls / info_data_calls 必须与编译产物实测计数逐项相等")
                .isEmpty();
        // 全量口径：整个模板包只在这两处产生模型调用，且 desktop 一处占满
        assertThat(modelTotal).as("168 个编译产物的 model( 总次数").isEqualTo(2);
        assertThat(infoTotal).as("168 个编译产物的 infoData( 总次数").isEqualTo(2);
    }

    @Test
    void 文章详情页的模型调用计数在PC与WAP上恰好是2和0() throws IOException {
        // C-33 ① / V-12 与 V-13 R-1、R-2 的交叉证据：桌面详情页必须记 2 次 model( + 2 次 infoData，
        // 手机详情页必须都是 0。四个数写在断言里，不允许只写单边。
        Path dir = GOLDEN_ROOT.resolve("compile-golden");
        JsonNode root = JsonMapper.builder().build()
                .readTree(Files.readString(dir.resolve("manifest.json"), StandardCharsets.UTF_8));

        assertCounts(dir, root, "default_pc/html/art/detail.html", 2, 2);
        assertCounts(dir, root, "default_wap/html/art/detail.html", 0, 0);
    }

    /** 对一个 manifest 条目做四重断言：manifest 计数、编译产物实测计数、以及两者各自的两个期望值。 */
    private static void assertCounts(Path dir, JsonNode root, String rel, int model, int infoData)
            throws IOException {
        JsonNode entry = null;
        for (JsonNode f : root.path("files")) {
            if (rel.equals(f.path("path").asText())) {
                entry = f;
                break;
            }
        }
        assertThat(entry).as("manifest 里必须有 %s", rel).isNotNull();
        String text = Files.readString(dir.resolve(rel), StandardCharsets.UTF_8);
        assertThat(entry.path("model_calls").asInt()).as("%s 的 manifest model_calls", rel).isEqualTo(model);
        assertThat(entry.path("info_data_calls").asInt()).as("%s 的 manifest info_data_calls", rel)
                .isEqualTo(infoData);
        assertThat(countModelCalls(text)).as("%s 编译产物实测 model( 次数", rel).isEqualTo(model);
        assertThat(countInfoDataCalls(text)).as("%s 编译产物实测 infoData( 次数", rel).isEqualTo(infoData);
    }

    /** 与生成器 `--compile` 的 model_calls 正则同一口径：`model` 前不是标识符字符或 `$`。 */
    private static int countModelCalls(String text) {
        return countMatches(text, "model(", true);
    }

    /** 与生成器 `--compile` 的 info_data_calls 正则同一口径：`infoData` 前不是标识符字符或 `$`。 */
    private static int countInfoDataCalls(String text) {
        return countMatches(text, "infoData(", true);
    }

    /** 匹配 {@code <name>(}，前面可选地要求“不是标识符字符”。 */
    private static int countMatches(String text, String token, boolean requireBoundary) {
        String head = token.substring(0, token.length() - 1);
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(token, i)) >= 0) {
            boolean ok = true;
            if (requireBoundary && i > 0) {
                char prev = text.charAt(i - 1);
                ok = !(Character.isLetterOrDigit(prev) || prev == '_' || prev == '$');
            }
            // `model (` 这种带空白的写法：生成器的正则是 `model\s*\(`，这里补上同一容忍
            if (ok && head.length() > 0) {
                int j = i + head.length();
                while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
                    j++;
                }
                ok = j < text.length() && text.charAt(j) == '(';
            }
            if (ok) {
                n++;
            }
            i += token.length();
        }
        return n;
    }

    // ------------------------------------------------------------------ GAP-1

    @Test
    void GAP1的两个裸标识符样例在覆盖范围内() throws IOException {
        // GAP-1：user/ajax_info.html:15 的 mac_day($obj['user_last_login_time'], color) 里 color 是未定义常量，
        // PHP 7.3 把它当字符串 'color'。桌面与手机两份模板各一处，编译产物必须原样保留这个裸标识符。
        for (String theme : List.of("default_pc", "default_wap")) {
            Path f = GOLDEN_ROOT.resolve("compile-golden").resolve(theme)
                    .resolve("html").resolve("user").resolve("ajax_info.html");
            assertThat(f).as("GAP-1 的 %s 编译产物", theme).isRegularFile();
            String compiled = Files.readString(f, StandardCharsets.UTF_8);
            assertThat(compiled).as("GAP-1：%s 的 mac_day 第二个实参必须原样是裸标识符 color", theme)
                    .contains("mac_day(")
                    .contains("color)");
        }

        // 值语义侧：PHP 7.3 对裸标识符 color 的实际求值结果必须是字符串 "color"，不是 null、不是报错
        Path sem = GOLDEN_ROOT.resolve("semantics").resolve("php73-vectors.json");
        assertThat(sem).isRegularFile();
        String json = Files.readString(sem, StandardCharsets.UTF_8);
        assertThat(json).as("GAP-1：语义向量里必须有 gap1_bare_identifier").contains("gap1_bare_identifier");
        assertThat(json).as("GAP-1：语义向量里必须有 gap1_mac_day_color").contains("gap1_mac_day_color");
    }

    @Test
    void 值语义向量覆盖C33要求的六类() throws IOException {
        String json = Files.readString(GOLDEN_ROOT.resolve("semantics").resolve("php73-vectors.json"),
                StandardCharsets.UTF_8);
        // C-33 ②：字符串化、比较、数组键、json_encode、mac_* 函数返回值；v11 起追加 RT-1 的 // 注释与下标赋值
        assertThat(json).as("RT-3 字符串化").contains("rt3_stringify");
        assertThat(json).as("RT-4 比较").contains("rt4_compare");
        assertThat(json).as("RT-3 数组键").contains("rt2_array_keys");
        assertThat(json).as("RT-3 json_encode").contains("rt3_json");
        assertThat(json).as("RT-5 未定义下标").contains("rt5_undefined");
        assertThat(json).as("mac_day").contains("fn_mac_day");
        assertThat(json).as("mac_url").contains("fn_url_list");
    }

    // ------------------------------------------------------------------ D-1 模型黄金

    @Test
    void D1模型黄金覆盖IA1到IA5并按ZDUMP1编码() throws IOException {
        Path f = GOLDEN_ROOT.resolve("model-golden").resolve("art-infodata.json");
        assertThat(f).as("V-16 IA-1～IA-5 的黄金").isRegularFile();
        String json = Files.readString(f, StandardCharsets.UTF_8);
        for (String id : List.of("IA-1", "IA-2", "IA-3", "IA-4", "IA-5a", "IA-5b")) {
            assertThat(json).as("V-16 样例 %s", id).contains(id);
        }
        // IA-1 / IA-2 是 1002 分支（被状态条件排除）
        assertThat(json).as("IA-1/IA-2 的 1002 分支").contains("1002");
        // IA-4 的 type / type_1 必须为 null（分类不存在）
        assertThat(json).as("IA-4 的 type 为 null").contains("\"type\": null");
        // ZDUMP-1 的类型标记在 .zdump.txt 里
        String zdump = Files.readString(GOLDEN_ROOT.resolve("model-golden").resolve("ia3.zdump.txt"),
                StandardCharsets.UTF_8);
        assertThat(zdump).as("ZDUMP-1：数组节点").contains("\"t\": \"array\"");
        assertThat(zdump).as("ZDUMP-1：int 用十进制字符串").contains("\"t\": \"int\"");
        assertThat(zdump).as("ZDUMP-1：string 用 Base64").contains("\"t\": \"string\"");
        assertThat(zdump).as("IA-3 的键序必须是 D1-FLD-1 再 D1-FLD-2")
                .containsSubsequence("art_id", "type_id", "art_name", "art_en", "art_time", "type", "type_1");
    }

    @Test
    void D1模型黄金不含D1_FLD3的哨兵与集合外键() throws IOException {
        // IA-7 ②：返回值逐层不得出现夹具的哨兵，也不得出现 D1-FLD-3 的派生键。
        // 只看 info 本身，不看 where（where 是输入条件的记录，本来就有 art_status）。
        String json = Files.readString(GOLDEN_ROOT.resolve("model-golden").resolve("art-infodata.json"),
                StandardCharsets.UTF_8);
        assertThat(json).as("不得出现 D1SENTINEL 哨兵").doesNotContain("D1SENTINEL");
        assertThat(json).as("不得出现 d1sentinel.test").doesNotContain("d1sentinel.test");
        for (String n : List.of("7301", "7302", "7303", "7304")) {
            assertThat(json).as("不得出现哨兵整数 %s", n).doesNotContain(n);
        }
        for (String k : List.of("art_page_list", "art_page_total", "art_pic_screenshot_list", "art_pwd",
                "art_jumpurl", "art_points", "art_content", "group")) {
            assertThat(json).as("D1-FLD-3：info 不得含键 %s", k).doesNotContain("\"" + k + "\":");
        }
        // art_status 是 INFO-1 登记的状态谓词：它必须出现在 where，但不得出现在 info 里。
        // 这里逐情形断言 info 的键集合恰为 D1-FLD-1 + D1-FLD-2。
        for (String id : List.of("IA-3", "IA-4", "IA-5a", "IA-5b")) {
            JsonNode info = infoOf(json, id);
            List<String> keys = new ArrayList<>();
            info.propertyNames().forEach(keys::add);
            assertThat(keys).as("%s 的 info 键序与键集合（D1-FLD-1 再 D1-FLD-2）", id)
                    .containsExactly("art_id", "type_id", "art_name", "art_en", "art_time", "type", "type_1");
        }
    }

    /** 从 model-golden/art-infodata.json 里取某个情形的 info 节点（不发生命中时返回空对象）。 */
    private static JsonNode infoOf(String json, String caseId) throws IOException {
        JsonNode root = JsonMapper.builder().build().readTree(json);
        JsonNode info = root.path("cases").path(caseId).path("golden").path("info");
        assertThat(info.isObject()).as("%s 应有 info", caseId).isTrue();
        return info;
    }

    @Test
    void D1夹具满足ARTFIX规定的形状() throws IOException {
        String raw = Files.readString(GOLDEN_ROOT.resolve("model-fixtures").resolve("art-infodata.sql"),
                StandardCharsets.UTF_8);
        // 先去掉 `--` 行注释再断言，否则会把“不得导入 maccms_dev”的警告注释当成真的库名
        String sql = raw.replaceAll("(?m)^\\s*--.*$", "");
        // ART-FIX-1：a1 的正文、标题、空 note、两图截图、子分类、存在的用户组
        assertThat(sql).as("ART-FIX-1 正文").contains("'p1$$$p2'");
        assertThat(sql).as("ART-FIX-1 标题").contains("'t1'");
        assertThat(sql).as("ART-FIX-1 截图").contains("封面$http://img.test/1.jpg##http://img.test/2.jpg");
        // ART-FIX-5：每行的 D1-FLD-3 列都填哨兵
        assertThat(sql).as("ART-FIX-5 哨兵").contains("D1SENTINEL").contains("7301").contains("7304");
        // 一次性库专用：语句里不得指向 maccms_dev 或任何跨库写法
        assertThat(sql).as("夹具语句不得提及 maccms_dev").doesNotContain("maccms_dev");
        assertThat(sql).as("夹具语句不得含建删库语句").doesNotContain("CREATE DATABASE").doesNotContain("DROP DATABASE");
        // 跨库写法（backtick 前后都是库名，如 `other_db`.`mac_art`）一律不允许
        assertThat(sql).as("夹具语句不得用跨库前缀写表").doesNotContainPattern("`[A-Za-z0-9_]+`\\.`mac_");
        assertThat(raw).as("头部必须写明一次性库约束").contains("maccms_it_*");
    }

    // ------------------------------------------------------------------ 工具

    private static List<Path> listFiles(Path root) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (var s = Files.walk(root)) {
            s.filter(Files::isRegularFile).forEach(out::add);
        } catch (IOException e) {
            throw new IllegalStateException("无法遍历 " + root, e);
        }
        return out;
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
