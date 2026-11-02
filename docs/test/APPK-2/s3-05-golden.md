# APPK-2 S3-05：PHP 模板解释器黄金资产（C-33、C-34）

本目录文件是 S3-05 的产物：**PHP 模板兼容层（D-1 起）用来逐字节对照的黄金**，以及生成它们的脚本。
S3-10 起的实现子任务（`runtime.Interpreter`、`TagModels`、`PhpTemplateRenderer`、`TemplateCompiler`）
不自己造期望值，一律读这里的文件比对。

- 依据：技术设计 **v13** `9c73bf6ced044f40eeb87ef7861e616b6157499a` 的 C-33、C-34、V-11、V-12、V-13、V-16、
  §6.21 INFO-1～INFO-7、D1-FLD-1～5、PROJ-1、ZDUMP-1、QG-1～QG-7；
  PRE-5（S3-04）`b550151` 的 `docs/test/APPK-2/template-scan.md`。
- 生成器：PHP **7.3.33**（`D:/Tools/php/7.3/php.exe`），只读 `php/` 与模板包，不写产品代码。
- Java 侧：`src/test/java/com/maccms/backend/phptemplate/PhpTemplateGoldenAssetsTest.java` 守住样本不漂移；
  V-13/V-16 的实现比对测试由 S3-12b、S3-14d 按本节命令补齐。

---

## 1. 四类黄金、各自回答什么问题

| 目录 | 内容 | 依据 | 谁用 |
| --- | --- | --- | --- |
| `src/test/resources/phptemplate/semantics/php73-vectors.json` | PHP 7.3 的**值语义向量**：字符串化、比较、数组键序、`json_encode`、未定义下标、字符串下标按字节、`mac_day`/`mac_url`/`mac_page_param` 等宏的实际返回值 | C-33 ②、RT-2～RT-6 | `InterpreterTest`、`TemplateFunctionsGoldenTest` |
| `src/test/resources/phptemplate/compile-golden/` | 168 个模板的 **ThinkPHP 编译产物** + `manifest.json`（每文件的字节数、chunk 数、`model(`/`infoData` 计数） | C-33 ①、V-11/V-12 | `TemplateCompilerGoldenTest`、`TagLibCompileTest` |
| `src/test/resources/phptemplate/render-golden/art-detail-prevnext-{a..e}.html` + `.json` | V-13 **R-1 桌面片段**五个情形的渲染结果，以及桩函数记录的调用实参 | C-33 ③、V-13 | `PhpTemplateRendererTest` |
| `src/test/resources/phptemplate/model-golden/` | V-16 **IA-1～IA-5** 的 PHP `infoData` 返回值（先 PROJ-1 投影，再 ZDUMP-1 编码）+ `art-infodata.explain.txt` | C-33 ④、V-16 | `TagModelsInfoDataTest`、`TagModelsMySqlIT` |
| `src/test/resources/phptemplate/model-fixtures/art-infodata.sql` | D-1 的 MySQL 夹具（ART-FIX-1～5），**由生成器产出** | V-16、ART-FIX | 上面两侧共用同一份夹具 |

## 2. 重新生成（在仓库根目录，Git Bash）

```bash
PHP=D:/Tools/php/7.3/php.exe
PKG=.compare/APPK-2/pkg/third_party/maccms-template-v2020.1000.1035/template

# ① 值语义向量（不连库）
$PHP src/test/php/tpl_runtime_golden.php --semantics --out src/test/resources/phptemplate

# ② 168 个模板的编译产物（不连库）
$PHP src/test/php/tpl_runtime_golden.php --compile --package "$PKG" --out src/test/resources/phptemplate

# ③ R-1 渲染样例（不连库）
$PHP src/test/php/tpl_runtime_golden.php --render-sample art-detail-prevnext --package "$PKG" --out src/test/resources/phptemplate

# ④ 夹具 + D-1 模型调用黄金（需要一次性库，见下节）
$PHP src/test/php/tpl_art_fixture.php --out src/test/resources/phptemplate/model-fixtures/art-infodata.sql
$PHP src/test/php/tpl_runtime_golden.php --model-calls art-infodata \
     --fixture src/test/resources/phptemplate/model-fixtures/art-infodata.sql \
     --out src/test/resources/phptemplate \
     --explain-out src/test/resources/phptemplate/model-golden/art-infodata.explain.txt
```

两点必须知道：

1. **模板包不在仓库里**。`third_party/maccms-template-v2020.1000.1035/` 只在 S3-01 的 `200cffc` 谱系中，
   当前分支的工作区没有它。生成 ②③ 前先从该提交取出模板包，放回 `.compare/APPK-2/pkg/…`（路径任意，
   用 `--package` 指过去即可）。**不要把模板包提交到本分支。**
2. ②③ 只读模板包，因此在没有模板包的机器上可以**比对已有黄金**，但不能**重新生成**。

## 3. 连库那一部分怎么跑（④）

`--model-calls art-infodata` 是四类里**唯一**连数据库的，因为它要问 MySQL 而不是问模板：
PRE-5 记录的 80 处时间来源里 79 处是 DATA-1，但 `infoData` 的返回值取决于 MySQL 实际返回哪一行
（PHP 不给 `ORDER BY`，见 INFO-2），只有真跑才知道。

只允许**一次性库**，用完即删。库名由你临时生成，账号口令**只从已登记的环境变量读取**，本文件不写任何
账号或口令值（下面用 `<管理员账号>`、`<管理员口令>` 这种无值占位表示“你自己在环境里填”）：

```bash
DBN=maccms_it_s305_$(date +%s)
mysql -u "$MACCMS_IT_ADMIN_USERNAME" -p -e "CREATE DATABASE \`$DBN\` DEFAULT CHARACTER SET utf8mb4;"
mysql -u "$MACCMS_IT_ADMIN_USERNAME" -p --default-character-set=utf8 "$DBN" < php/application/install/sql/install.sql
mysql -u "$MACCMS_IT_ADMIN_USERNAME" -p --default-character-set=utf8 "$DBN" < src/test/resources/phptemplate/model-fixtures/art-infodata.sql

# MACCMS_IT_ADMIN_URL / MACCMS_IT_ADMIN_USERNAME / MACCMS_IT_ADMIN_PASSWORD 由本机环境提供，值不入库
export MACCMS_IT_ADMIN_URL="mysql://<管理员账号>:<管理员口令>@<主机>:<端口>/$DBN"
export GOLDEN_DB_NAME="$DBN"

$PHP src/test/php/tpl_runtime_golden.php --model-calls art-infodata \
     --fixture src/test/resources/phptemplate/model-fixtures/art-infodata.sql \
     --out src/test/resources/phptemplate \
     --explain-out src/test/resources/phptemplate/model-golden/art-infodata.explain.txt

mysql -u "$MACCMS_IT_ADMIN_USERNAME" -p -e "DROP DATABASE \`$DBN\`;"
```

脚本自身有护栏，不满足就**报 BLOCKED 并退出（状态码 4）**，不写任何文件：

- `MACCMS_IT_ADMIN_URL` / `_USERNAME` / `_PASSWORD` 缺一个；
- `GOLDEN_DB_NAME` 不是 `maccms_it_*` 形状；
- `MACCMS_IT_ADMIN_URL` 里不含 `maccms_it_`。

**绝不指向 `maccms_dev` 或任何共享库。** 库名、账号、口令只在环境变量里给出实际值，
不写进脚本、黄金、夹具、文档或提交信息；本文件只引用变量名（`MACCMS_IT_ADMIN_URL`、
`MACCMS_IT_ADMIN_USERNAME`、`MACCMS_IT_ADMIN_PASSWORD`、`GOLDEN_DB_NAME`）和 `<…>` 无值占位。

## 4. 夹具 ART-FIX 的形状（`art-infodata.sql`）

由 `src/test/php/tpl_art_fixture.php` 生成，不手改。生成器末尾带断言，任何一条不成立就非 0 退出、不写文件：

| 编号 | 内容 |
| --- | --- |
| ART-FIX-1 | a1 = 910001：`art_content='p1$$$p2'`、`art_title='t1'`、`art_note=''`、`art_pic_screenshot='封面$http://img.test/1.jpg##http://img.test/2.jpg'`、`type_id` 指向 `type_pid≠0` 的子分类 9102、`group_id=9101` |
| ART-FIX-2 | an = 910052：`type_id=9199`（不存在的分类）、`art_content` 与 `art_pic_screenshot` 为空串、`group_id=0` |
| ART-FIX-3 | 编号大于 an 的只有一行（910053）且 `art_status=0` |
| ART-FIX-4 | 编号小于 a1 的只有一行（910000）且 `art_status=0` |
| ART-FIX-5 | 每行的 D1-FLD-3 列都填哨兵：`D1SENTINEL-*` 文本与 7301～7304 整数；**其他列不出现这些值** |

规模：`mac_art` 共 **54 行**（≥ 50，满足 AC-3 前置），启用行 a1..an 为 910001..910052（n = 52，严格升序），
停用行恰好 2 行。另有 4 行 `mac_type`（两棵子树，供 IA-5 的多候选）与 1 行 `mac_group`。

一个实现细节：`art_pwd` 是 `varchar(10)`，装不下 `D1SENTINEL-pwd-<编号>`，所以该列用
`D1SENT-<编号后三位>`。IA-7 ② 的断言是“不以 `D1SENTINEL` 开头/不含 `d1sentinel.test`”，这个前缀仍然满足。

## 5. 逐条对照：设计里的每一个断言落在哪个文件

| 设计条目 | 黄金证据 |
| --- | --- |
| C-33 ① / V-12：168 个模板的编译产物 | `compile-golden/` 168 个 `.html` + `manifest.json` |
| C-33 ② / RT-2～RT-6：值语义 | `semantics/php73-vectors.json`：`rt2_array_keys`(10)、`rt2_array_order`(5)、`rt3_stringify`(22)、`rt3_json`(15)、`rt4_compare`(21)、`rt4_in_array`(6)、`rt5_undefined`(5)、`rt5_str_index`(6)、`rt5_str_index_bytes`(4)、`fn_mac_day`(44)、`fn_date`(24)、`fn_list`(58)、`fn_url_list`(10)、`fn_internal`(14) |
| C-33 ③ / V-13 R-1：808 字节切片、五情形 | `render-golden/art-detail-prevnext-{a,b,c,d,e}.html` + `art-detail-prevnext.json` |
| R-1 ② 调用实参 | `art-detail-prevnext.json` 的 `calls`：恰好 2 次，先 `lt` 后 `gt`，模型 `Art`，`field='*'`，`cache=1` |
| C-33 ① / V-12 模型调用计数：桌面页 2 次 `model(`、2 次 `infoData` | `compile-golden/default_pc/html/art/detail.html`（`manifest.json` 记 `model_calls:2`、`info_data_calls:2`） |
| V-13 R-2：手机页 0 次 `model(`/`infoData` | `compile-golden/default_wap/html/art/detail.html`（`manifest.json` 记 `model_calls:0`、`info_data_calls:0`） |
| C-33 ④ / V-16 IA-1～IA-5 | `model-golden/ia{1,2,3,4,5a,5b}.zdump.txt` + `art-infodata.json` |
| V-16 IA-8 ②：两侧 EXPLAIN 一致 | `model-golden/art-infodata.explain.txt`（含 `SELECT VERSION()`） |
| PROJ-1 投影 | `art-infodata.json` 的 `cases[*].golden`：只有 `code`、`msg` 与 D1-FLD-1 + D1-FLD-2 |
| ZDUMP-1 编码 | `*.zdump.txt`：`{"t":"int","v":"910001"}`、`{"t":"string","v":"<Base64>"}`、`{"t":"array","v":[[键,值],…]}` |
| **GAP-1**（PRE-5 §9.11） | ①`compile-golden/{default_pc,default_wap}/html/user/ajax_info.html` 原样保留 `mac_day($obj['user_last_login_time'],color)` 这个**裸标识符**；②`php73-vectors.json` 的 `gap1_bare_identifier`（`color` → 字符串 `'color'`）与 `gap1_mac_day_color`（今天/昨天/null 三个分支的实际输出） |

### GAP-1 为什么单列一条

PRE-5 在 S3-04 查出 `user/ajax_info.html:15` 写的是 `mac_day($obj['user_last_login_time'],color)`，
第二个实参 `color` 没加引号，是**未定义常量**。PHP 7.3 把未定义常量当字符串 `'color'` 用（7.2 起会发
deprecated，但仍照旧求值），所以这一页实际输出的是 `<font color="#FF0000">…`，即**颜色参数无效但页面正常**。
Java 侧若按“未定义标识符”报错，这一页就会 500。这条差异必须留在覆盖里，所以：

- 编译产物里保留原样的裸标识符（证明它没有被前端或生成过程悄悄修掉）；
- 值语义里记录 `color` 的真实求值结果与三个分支的 `mac_day` 输出（证明实现应得的结果是什么）。

## 6. 可重复性怎么保证

`PhpTemplateGoldenAssetsTest` 逐条守住：

- 所有黄金是合法 UTF-8、只用 LF、无 CR（`compile-golden` 例外地允许 BOM——模板包里的
  `label/rank.html` 原件本身带 BOM，编译产物必须原样保留这三个字节，否则两侧会差 3 字节）；
- `render-golden` 五份齐全；
- `compile-golden` 恰好 168 个 `.html`，`manifest.json` 记 168；
- `manifest.json` 的 `model_calls` / `info_data_calls` 与编译产物逐项一致（生成器正则修正后重新生成，见 §6.2）；
- R-2 的手机编译产物不含 `model(`、`infoData`，`manifest.json` 记 `model_calls:0`、`info_data_calls:0`；
- 桌面编译产物恰好 2 次 `->infoData(`、2 次 `model(`，`manifest.json` 记 `model_calls:2`、`info_data_calls:2`；
- GAP-1 的两处样例在位；
- IA-1/IA-2 是 1002 分支，IA-4 的 `type`/`type_1` 为 `null`，`info` 的键**恰为**
  `art_id, type_id, art_name, art_en, art_time, type, type_1`（D1-FLD-1 再 D1-FLD-2）；
- 黄金里没有 `D1SENTINEL`、没有 7301～7304、没有 D1-FLD-3 的键。

四类黄金都做过**连跑两次 `diff -r` 无差异**的验证（②③ 用同一模板包，④ 用同一一次性库）。
`compile-golden` 的比对还额外做了归一化：去掉编译产物头部的 serialize 块（含绝对路径与 mtime），
把 `\` 统一成 `/`，站点根前缀替换成 `<SITE>/`，否则换台机器就一定会差异。

### 6.1 值语义黄金的稳定化规则（S3-05 复核阻断项 1 修复）

`--semantics` 曾经不能跨工作目录重复生成：`fn_url_list` / `fn_internal` 里的 `reflection` 字段直接来自
`(string) new ReflectionFunction($fn)`，而该字符串**带定义函数的源文件绝对路径**，例如

```
@@ C:\Users\…\worktree/php/application/common.php 1633 - 1657
```

这段路径随生成机、随检出目录而变，写进黄金就等于把“生成机工作目录”一起提交了。现在由
`stableReflection()` 统一稳定化：

- 仓库根绝对路径（`\` 与 `/` 两种写法、Windows 盘符大小写变体、`realpath` 归一后的写法）一律替换成
  稳定占位符 **`<REPO>`**；
- 剩下的分隔符统一成 `/`；
- 占位后仍然保留 **PHP 版本**（`_meta.php`）、**函数名**、**参数签名**、**相对源文件路径与行号区间**，
  所以“这个函数在 PHP 7.3 里是什么签名、定义在哪一段”依旧可核验，只是不再指向某台机器。
- 生成器在落盘前加了一道护栏：若 JSON 里仍出现仓库绝对路径（原文或 JSON 转义后两种写法都查），
  以退出码 **7** 中止且**不写文件**，避免再悄悄产出一份不可复现的黄金。

**实测（PHP 7.3.33）**：在两个不同的检出目录、三个不同的工作目录各跑一次 `--semantics`，
四份输出的 SHA-256 全部为 `b76c85c9590137e52a9aa30d5a7f842d05502d8c036d3907884a0c662e49cf6b`，与提交中的
`semantics/php73-vectors.json` 逐字节一致。

### 6.2 编译黄金的模型调用计数（测试评审第 1 轮修改项 1 修复）

`manifest.json` 的 `model_calls` 原先是 0，与桌面编译产物里实际存在的 2 次 `model(` 矛盾。
根因是生成器的正则字面量里混入了一个 **U+0008 控制字符**（原写法 `'/\bmode l\s*\(/'` 的首字符被写成
0x08），它不是单词边界 `\b`，所以一次都匹配不到。现在改为显式否定型零宽断言：

```php
'model_calls' => preg_match_all('/(?<![A-Za-z0-9_$])model\s*\(/', $compiled),
```

即“`model` 前面不是标识符字符或 `$`”，等价于单词边界但不会在 `$model(` 这类写法上漏判。
`info_data_calls` 的正则本来就是 `infoData\s*\(`，未改动。

**重新生成与核对（PHP 7.3.33，从模板包放行提交 `200cffc` 的 `git archive` 目录取包）**：

```bash
$PHP src/test/php/tpl_runtime_golden.php --compile --package "$PKG" --out src/test/resources/phptemplate
```

- 168 个编译产物、6032 个 PHP chunk，与修复前的黄金**逐字节相同**（只有 `manifest.json` 的一处计数变了）；
- 桌面 `default_pc/html/art/detail.html`：`source_sha1` `293b8549…`、`compiled_sha1` `86c9626d…`，
  `model_calls:2`、`info_data_calls:2`（该文件实际含 2 次 `model('art')`）；
- 手机 `default_wap/html/art/detail.html`：`source_sha1` `7f356e1d…`、`compiled_sha1` `99405bf0…`，
  `model_calls:0`、`info_data_calls:0`；
- 其余 166 个文件的两个计数均为 0，且与各自编译产物按同一正则实测的计数逐项相等；
- `PhpTemplateGoldenAssetsTest` 的 `编译黄金覆盖全部168个模板且记录两个关键样例的调用数` 对上述四个数做精确断言，
  并对 `manifest.json` 的全部 168 条记录做“清单计数 = 编译产物实测计数”的一致性断言。

## 7. 尚未交付 / 未验证

- **`--model`（旧的 `art-info` 分支）未实现**，调用一律返回 BLOCKED。C-33 ④ 规定的是
  `--model-calls art-infodata`，已在上面实现并跑通；旧名字保留转 BLOCKED 是为了不让旧的调用方式
  悄悄跑出假的“通过”。
- **IA-6～IA-11 是 Java 行为断言，没有 PHP 黄金**：INFO-5 不缓存、QG-1～QG-7 的护栏在 PHP 里没有对应物
  （QG-7）。它们的期望值写在 V-16 文字里，由 S3-12b 的单测与 IT 断言，不落在本目录。
- **IA-8 ①（20 万行扫描耗时）没有在本交付里跑**：它要求先追加 200,000 行再测，属于 S3-12b 的
  `impl-notes`。本交付只给 IA-8 ② 需要的 `EXPLAIN` 与版本号。
- **R-3（不支持写法返回 500）没有黄金**：它断言的是 Java 侧 500 与日志内容，PHP 里没有“不支持”这回事，
  由 S3-12b 的 `PhpTemplateRendererTest` 按 V-13 R-3 的文字自建临时模板。
- **模板包的 168 份 `.html` 没有逐页渲染黄金**：只有 R-1 一个切片做了端到端渲染。其余页面的端到端对照
  是 V-19 的页面级样本（S3-14 起）。
