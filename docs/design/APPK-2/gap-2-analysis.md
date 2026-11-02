## APPK-2 GAP-2 证据核对与修订建议（只读分析，v1）

本文是架构师对 APPK-3（S3-07b）交付中查出的 **GAP-2** 所做的只读分析。这里的 GAP-2 指“两份 `rss/index.html` 第 11 行 `{$maccms:site_url}{$maccms:path_tpl}` 编译为对象属性读取、被 C-18 以 `object@437` 拒绝”这一项，**不是** PRD §9.1 / `docs/test/APPK-2/gap-register.md` 台账里的同号条目。

本文**不改**技术设计、产品代码、黄金和模板，也**不做**架构级取舍。第 4 节的建议须经调度带回 APPK-2 主单决定是否采纳，采纳后才修订技术设计（v16），并走原架构评审与 G1 增量复判。

### 0. 元数据

| 项 | 值 |
| --- | --- |
| 派发 | APPK-3 评论 `01a19b5e-bd9c-7289-9a65-bad9d229d7e5`（调度“GAP-2 转交：只读架构分析”） |
| 模板原文 | `third_party/maccms-template-v2020.1000.1035/template/{default_pc,default_wap}/html/rss/index.html:11` @ `200cffc5d01090faf4f61e153feb472b107b34df`（两份 blob 相同：`7f631c2450dc5ddad073da3f620e189e2a30a8bf`） |
| 编译黄金 | `src/test/resources/phptemplate/compile-golden/{default_pc,default_wap}/html/rss/index.html` @ `b71b1aa4be0190f85686650a7b22459c995cf57e`（两份 blob 相同：`7c6d8ebcf08450a2bf9240edee6df18a2befd1f9`，1069 字节，SHA-256 `de5c82d5c3b5af461a5ae419d0da052cf1424eb45bbb90215c93d065d9662750`） |
| 技术设计 | `docs/design/APPK-2/tech-design.md` @ `9671aae615450647d75647eb6a0e1c549c99001e`（v15）：RT-1、RT-2、RT-5、§4.16 DEC-16、§6.5.1 BARE-1～7、§10 OP-T7 |
| PRE-5 | `docs/test/APPK-2/template-scan.md` @ `b550151b4243622fe7ac060c9ff6e720d83ae605`；扫描器 `tests/compare/APPK-2/scan_templates.py` @ 同提交 |
| PRD | `docs/prd/APPK-2.md` @ `9dd36a73febe8bac4c5d02b2638dd57c3e642a49`：BR-3、FR-2、AC-2、AC-5、§6.1 |
| S3-07b 交付 | `agent/agent/appk-3` @ `c02c686f3f5e32b504a2d1315586a2db6d2e0c12`：`runtime/PhpParser.java:668-697`（`->` 处理）、`PhpParserGoldenTest.java:131-138`（GAP-2 拒绝锁定） |
| PHP 源码 | 仓库 `php/`（`2023.1000.3051`，与基线 `a57e9cb` 一致） |
| 实测运行时 | PHP 7.3.33 CLI（NTS x64），离线执行，未连库、未发请求 |

### 1. 结论摘要

1. **范围是封闭的**：168 个编译产物（6032 个 PHP 代码块）里，模板作者写出的对象属性读取**恰好 4 处**，只在这两份文件第 12 行（模板原文第 11 行），主语都是 `$maccms`，属性名只有 `site_url`、`path_tpl`。其余 `->` 只有已登记的 `)->infoData` 2 处（MC-1）和 `]->isEmpty` 10 处（OP-T7）。
2. **PHP 里 `$maccms` 恒为数组**，所以这两次读取**恒为 `null`**。每次读取产生一条 Notice（errno 8），被 ThinkPHP 吞掉，不进响应体，`log.type=test` 也不落盘。页面状态 200，该行输出固定为 `<url>http://images/logo.png</url>`（站点值不出现）。
3. **按 BR-3，Java 必须同样输出 `http://images/logo.png`**，不做“修正”。现状（`object@437` → 500）不满足 BR-3、OP-4、AC-2、AC-5，影响前台 `rss/index`（PG-6 / S3-14g）和后台静态生成 `make/rss?ac2=index`（FR-6 / S3-17）。
4. **漏扫原因**有三层（§2.6）：PRE-5 的变量正则不认 `:`；v14 的逐记号扫描只查常量位置，有意跳过了 `->` 后面的名字；RT-1 里“单独的 `->属性` 取值不支持”这句否定断言，从未用 `T_OBJECT_OPERATOR` 全量扫描核实过。第三层是架构设计的疏漏。
5. **建议方案 D（登记形态）**：只放行 `$maccms->site_url`、`$maccms->path_tpl` 这两个固定右值，求值恒为 `null`，其余属性访问照旧拒绝。做法与 DEC-16（裸名 `color`）、MC-3、OP-T7 同为“登记才放行”，落在已确认的 BR-3 / OP-4 范围内，不需要新的产品决定。但它改写了 RT-1 的一条明文禁令，必须出技术设计 v16 并走架构评审与 G1 增量复判。是否需要人类确认，由调度裁定。

### 2. 证据核对

#### 2.1 模板原文与编译规则

- 原文第 11 行：`<url>http://{$maccms:site_url}{$maccms:path_tpl}images/logo.png</url>`。同一文件第 6、9、16 行用的是点号 `{$maccms.site_url}`，编译为 `$maccms['site_url']`。第 11 行用冒号，应是模板作者笔误，PHP 侧按冒号规则编译。
- ThinkPHP 5.0.24 `php/thinkphp/library/think/Template.php:842-873`：变量名里有 `.` 时走 `tpl_var_identify`（MacCMS 未覆盖，默认 `array` → `['k']`）；**没有 `.` 时执行 `str_replace(':', '->', $match[0])`（第 873 行）**，不看 `tpl_var_identify`。因此 `{$maccms:site_url}` 一定编译为 `$maccms->site_url`。黄金第 12 行与此一致：`<url>http://<?php echo $maccms->site_url; ?><?php echo $maccms->path_tpl; ?>images/logo.png</url>`（97 字节，SHA-256 `26d078e69ff3d420fc24fc1bbebd66a9cf26db9aa0207767d01a61774d7e8a49`）。黄金比原文多一行，因为首行 `<?xml` 被改写成 `<?php echo '<?'; ?>` 加换行。
- 两个 `->` 在黄金文件中的字节偏移是 **437** 与 **469**，两份文件相同。S3-07b 在第一个 `->`（437）处拒绝，与 `PhpParserGoldenTest.java:134-137` 的锁定一致。

#### 2.2 全包枚举（PHP 7.3.33 `token_get_all`，命令见 §8 ①）

| 形态 | 处数 | 位置 | 现有归属 |
| --- | --- | --- | --- |
| 属性 `$maccms->site_url` | 2 | 两份 `rss/index.html:12` | **无（GAP-2）** |
| 属性 `$maccms->path_tpl` | 2 | 两份 `rss/index.html:12` | **无（GAP-2）** |
| 方法 `)->infoData(` | 2 | `default_pc/html/art/detail.html:108,111` | MC-1～MC-6（DEC-15） |
| 方法 `]->isEmpty(` | 10 | `vod/show.html` 等 | OP-T7 形态 B |

扫描统计：`files=168 php_open_tags=6032`，与 BARE-3 的复跑结果相同。模板原文中带冒号的变量写法 `{$x:y}`，两个包合计也只有这 4 处（§8 ②）。

#### 2.3 真实变量值域

- `$maccms` 唯一的赋值点是 `php/application/common/controller/All.php:58-96` 的 `label_maccms()`：`$maccms = $GLOBALS['config']['site']`（`application/extra/maccms.php` 的 `'site'` 数组），再追加 `path`、`path_tpl`、`http_type` 等键，最后执行 `$this->assign(['maccms'=>$maccms])`。**类型恒为 PHP 数组**。
- 两条调用路径都会经过它：前台 `index/controller/Base.php:15`（构造函数），`Rss::index`（`index/controller/Rss.php:13-17`）执行 `label_fetch('rss/index')`；后台静态生成 `admin/controller/Make.php:209`，`Make::rss` 对 `ac2` 白名单中的 `index` 执行 `buildHtml(…,'rss/index')`（`Make.php:197-222`）。
- 168 个编译产物里没有给 `$maccms` 整体赋值或改写其下标的语句（§8 ③）。模板作用域内它始终是 `label_maccms()` 产出的那个数组。
- Java 侧 RT-2 的值类型里没有对象，`$maccms` 由 §6.7 页面变量装配为 `ZArray`。这条证据与 OP-T7 已授权方向的依据一致：`resultset_type => 'array'`，模板变量只有数组与标量。

#### 2.4 PHP 错误处理与响应体语义

- 在 PHP 7.3 中，对非对象读属性的结果是 `null`，同时报 `E_NOTICE`（errno 8）`Trying to get property '<名>' of non-object`。§8 ④ 的实测覆盖 RT-2 全部值类型：`null`、`true`、`false`、int、float、非空串、空串、带同名键的数组、空数组，结果**全部为 `NULL` 且各 1 条 errno 8**。变量未定义时同样为 `NULL`，另多 1 条“未定义变量”Notice。另外，`isset($maccms->site_url)` 为 `false`、`empty(...)` 为 `true`，两者都不报 Notice；双引号插值 `"$maccms->site_url"` 为空串。后三种写法黄金里没有，只用来划定拒绝边界。
- ThinkPHP 的处理链：`thinkphp/base.php:62` 执行 `Error::register()`，此时 `error_reporting(E_ALL)`；随后加载应用公共文件 `application/common.php:12`，改成 `error_reporting(E_ERROR | E_PARSE)`。渲染时，`Error::appError`（`Error.php:66-76`）判断 `error_reporting() & 8` 为 0，**不抛异常**，只调用 `Handle::report`（`exception/Handle.php:40-64`），后者执行 `Log::record(…,'error')`。而 `application/config.php:169-176` 配置的是 `log.type => 'test'`，日志驱动 `Test` **不写盘**。所以 Notice 对响应体、状态码和磁盘都没有影响。这与 BR-3 中“不含写入 PHP 日志的警告（PHPB-7）”、RT-5 和 BARE-4 的口径一致：Java 不复现这条 Notice。
- 响应体：`Rss::__construct` 发 `Content-Type:text/xml`（`Rss.php:10`），状态 200。第 12 行执行结果（§8 ⑤）：两份文件在“数组带真实键”“空数组”两种宿主值下，输出都是 `<url>http://images/logo.png</url>` 加 LF，各 2 条 errno 8。行内两处 `?>` 后面跟的都不是 LF，TPL-5 吃换行规则不涉及这一行。整份文件按宿主数组执行，`<url>` 行也相同（同次运行的其余 Notice 来自 `{maccms:vod}` 在黄金中未展开、`$vo` 未定义，与 GAP-2 无关）。对照组：若把 `$maccms` 换成 `stdClass`，输出会变成 `http://www.example.com/template/default_pc/images/logo.png` 且没有 Notice。这只用来说明“当作对象或数组下标取值”会输出站点值，与 PHP 实际结果不同（见 §3 方案 E）。
- 调度在评论 `01a19b5e…` 中的 PHP 7.3.33 复跑结果与本节一致。

#### 2.5 受影响页面

| 入口 | PHP | Java 落点 | 现状（C-18 拒绝） |
| --- | --- | --- | --- |
| 前台 `rss/index`（PRD §6.1“RSS（7 个）”之一，AC-5 样本） | 200，`text/xml`，`<url>http://images/logo.png</url>` | PG-6 `SiteMiscController`，S3-14g | 500，与 PHP 不一致 |
| 后台生成 `make/rss?ac2=index`（FR-6 / AC-7） | 生成 `rss/index.xml`，内容同上 | C-45～C-51，S3-17，V-6 | 生成失败 |
| 其余 6 个 RSS（`baidu`、`google`、`so`、`sogou`、`bing`、`sm`）及各 `*/rss.html` | 只用点号写法 | — | 不受影响（S3-07b 已解析通过） |

#### 2.6 漏扫原因

| 层 | 证据 | 原因 |
| --- | --- | --- |
| ① PRE-5 扫描器 | `scan_templates.py:111` 的 `RE_VAR = \{\$([A-Za-z_]\w*(?:\.[A-Za-z0-9_]+)*)\}`、`:132` 的 `RE_VAR_FILTER` 字符类 `[A-Za-z0-9_.]` 都不含 `:`；§8 ⑥ 实测两条正则对 `{$maccms:site_url}` 都不匹配，对 `{$maccms.site_url}` 匹配 | 变量写法按“点号层级”建模，冒号写法既没被计数，也没被列为“未识别写法”；template-scan §4 第 2 条只承诺“统计出现次数与行号”，没要求兜底未识别的 `{$…}`，所以静默漏掉 |
| ② v14 逐记号复核 | tech-design §6.5.1 BARE-3 的复跑脚本按设计跳过前一个记号是 `T_OBJECT_OPERATOR` 的名字（属性名不是常量取值）；OP-T7 的枚举从 `Cx.php:101,161,434,451` 出发，只数编译器生成的 `instanceof` 与 `->isEmpty()` | 两次扫描的目标都不是“全部 `->`”，`$maccms->site_url` 正好落在两者的空档里 |
| ③ RT-1 的否定断言 | RT-1：“对象只出现在 `model(...)->listCacheData(…)` 与 `model('art')->infoData(…)` 两种调用……单独的 `->属性` 取值不支持” | 这句是架构设计的断言，写入时没有用 `T_OBJECT_OPERATOR` 对 168 个产物做全量核实，**属架构设计疏漏**。S3-07b 的黄金全量解析测试按设计拒绝并锁定，起到了兜底作用 |

建议在 v16 的源码合理自检（§12）补一条：对编译产物按 `T_OBJECT_OPERATOR`、`T_DOUBLE_COLON`、`T_NS_SEPARATOR` 做全量枚举，并把复跑命令登记进设计（§8 ①）。之后 RT-1 再写“某写法不出现”，都以这类枚举为证。

### 3. 原有规则下的修订选项

“原有规则”指：BR-3 / OP-4（已确认，模板实际用到的都支持、输出以 PHP 为准、不做修正）、RT-1 末句（子集外写法显式报不支持）、RT-2（无对象类型）、“登记才放行”模式（RT-7、MC-3、BARE-3、OP-T7）。

| 方案 | 做法 | 改动量 | 与 PHP 的一致性 | 风险 | 沿用现有模式 | 需要的决定 | 评估 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A 维持拒绝 | RT-1 不变，`object@437` → 500 | 0 | 不一致：PHP 200，Java 500 | `rss/index` 与 `rss/index.xml` 生成两包都失败，违反 BR-3、OP-4、AC-2、AC-5、FR-6 | 是 | 要豁免，就须修订 PRD（人类 / PM），超出架构权限 | 不建议（与 DEC-16 方案 A 同理） |
| B 通用规则：任何 `X->名字` 右值恒为 `null` | 解析器接受任意变量后接 `->名字` | 小 | 本包一致 | 放开任意成员访问，与 AC-307B-2“拒绝任意成员访问”及 RT-1 冲突；拼错的写法会被静默接受；与 OP-T7“其他方法 / 属性一律拒绝”的已授权方向冲突 | 否 | 改 OP-T7 已授权的方向，须人类确认 | 不建议 |
| C 编译时改写 | C-20 把 `{$a:b}` 改成 `null` 或 `''` | 小 | 输出一致 | 编译产物与黄金不再逐字节相同（V-12；黄金第 12 行原样是 `->`），偏离 `Template.php:873` 的移植语义 | 否 | — | 不建议（与 DEC-16 方案 C 同理） |
| **D 登记形态（建议）** | 登记名单 = {`$maccms->site_url`、`$maccms->path_tpl`}；只作右值，求值恒为 `ZValue.NULL`；其余属性访问照旧报 `object`（§5 NP-1～NP-7） | 小：C-18 一处 + 1 个 AST 节点 + 1 个常量集合；C-19 一处求值；测试 | 本包 4 处全部一致（§2.4） | 模板包以外出现新的属性写法时，Java 500，而 PHP 输出空串（与 RISK-T27 同类） | 是：与 BARE-3、MC-3、OP-T7 同为登记才放行 | 不改 BR-3 / OP-4，不改 OP-T7 方向；只修订技术设计（RT-1 明文禁令）→ v16 + 架构评审 + G1 增量复判 | **建议** |
| E 当作数组下标（模仿 `tpl_var_identify` 的自动识别） | `$maccms->k` 按 `$maccms['k']` 取值 | 小 | **不一致**：会输出 `http://<站点>/template/<包>/images/logo.png`，PHP 输出 `http://images/logo.png` | 属于 BR-3 明令禁止的“修正” | 否 | 要改 BR-3，须人类决定 | 不建议 |
| F 产品豁免 | 把 `rss/index` 的这一行列为已知差异或 EX-* | 0 代码 | 不一致 | 须修订 PRD 附表 F / BR-7；EX-* 当前只有 EX-1、EX-2 | — | 人类 / PM | 不建议，列出供人类知悉 |

### 4. 建议与依据

**建议采纳方案 D。** 依据如下：

1. **与 PHP 逐字节一致**：§2.4 证明，在 RT-2 的任何值类型下，`$maccms->site_url`、`$maccms->path_tpl` 都求值为 `null`；`$maccms` 又恒为数组（§2.3）。Java 求值恒为 `ZValue.NULL`，经 RT-3 字符串化为空串，与 PHP 在全部真实输入下都相同，不依赖站点配置值。
2. **落在已确认范围内**：BR-3 / OP-4 已确认“模板实际用到的写法都支持、以 PHP 输出为准、不做修正”。D 只补齐这 4 处，不扩大产品范围，不改 PRD，不改 OP-T7 / OP-T8 的已授权方向（OP-T7 的“其他属性 / 方法一律拒绝”在 D 下依然成立，只是多了两个登记例外，依据相同：模板变量里没有对象）。
3. **安全面最小**：不读属性值、不读宿主对象、不用反射、不新增动态分派；名单写死，不能经配置扩充；主语只允许 `$maccms` 这一个变量名；拒绝边界与诊断 kind 不变。
4. **可回滚**：只涉及 C-18 / C-19 各一处和测试，`git revert` 即可回到现行的拒绝状态。
5. **决策层级**：D 修改了 RT-1 中“单独的 `->属性` 取值不支持”这条设计明文，属于技术设计修订，须出 v16 并经架构评审、调度 G1 增量复判。按 GAP-1 的先例（v14 由调度直接派发设计修订，“沿用 OP-4、BR-3，不改 PRD、不新增产品决策”），本项可走同样路径。是否需要先请人类确认，由调度裁定。若人类倾向 A 或 F，就需要另修订 PRD，这已超出本文范围。

### 5. 最小允许 / 拒绝边界（供 v16 采纳时写入 RT-1 与新增小节；编号 NP- 为本文暂定，v16 可重编）

| 编号 | 规则 |
| --- | --- |
| NP-1 判定 | 只有下面这一种记号序列被认领：`T_VARIABLE`（名字恰为 `maccms`，区分大小写）→ `->` → `T_STRING`（恰为 `site_url` 或 `path_tpl`，区分大小写；PHP 属性名区分大小写），且下一个记号不是 `(`、`[`、`{`、`->`、`::`、`=`、复合赋值、`++`、`--`。认领后生成节点 `PhpAst.NonObjectProperty(offset, "maccms", name)`（节点名暂定），`offset` 取 `$` 的偏移 |
| NP-2 位置 | 只作**右值**表达式。以下位置一律报 `object`：赋值左侧、`++`/`--`、引用、`foreach … as` 目标、`isset(…)`/`empty(…)` 的实参（PHP 在这里的语义是 `false`/`true` 且不报 Notice，黄金 0 处，不登记）、`instanceof` 左侧、OP-T7 固定形态内 |
| NP-3 求值 | 恒为 `ZValue.NULL`，不读 `$maccms` 的值，没有副作用；在 echo 中按 RT-3 输出空串。Java 不复现 PHP 的 Notice，也不写 WARN 日志（与 RT-5、BARE-4、INFO-7 同一口径） |
| NP-4 登记名单 | `REGISTERED_PROPERTIES = {("maccms","site_url"), ("maccms","path_tpl")}`，写死在 `runtime`（与 `PhpAst.REGISTERED_CLASSES`、`BareIdentifiers.REGISTERED` 同一种做法），不读配置、系统属性或环境变量。扩充须出设计修订并附 §8 ① 的扫描证据 |
| NP-5 拒绝（kind `object`，偏移指向 `->`，与现有 `object@437` 一致） | ① 其他主语：`$obj->site_url`、`$MACCMS->site_url`、`$maccms['x']->site_url`、`$$v->x`、`(…)->x`、函数返回值 `f()->x`；② 其他属性名：`$maccms->SITE_URL`、`$maccms->site_name` 等；③ 动态属性名：`$maccms->$k`、`$maccms->{'site_url'}`；④ 链式 / 下标 / 调用：`$maccms->site_url->x`、`$maccms->site_url[0]`、`$maccms->site_url()`；⑤ 静态访问 `$maccms::x`、空安全 `?->`（7.3 不存在，词法 / 语法本就拒绝）；⑥ NP-2 列出的位置 |
| NP-6 插值 | 双引号与 heredoc 里的 `"$maccms->site_url"`、`"{$maccms->site_url}"` 不认领，按现有插值规则报不支持（黄金 0 处） |
| NP-7 未执行分支 | 与 BARE-5、RT-7 相同，在解析阶段判定：登记形态在任何分支都接受，NP-5 / NP-6 的写法即使出现在未执行分支也拒绝 |

### 6. 验证落点

| 落点 | 内容 | 归属 / 批次 |
| --- | --- | --- |
| C-18 解析（`PhpParser.objectOperator` 一处、`PhpAst` 新增节点与常量集合） | `PhpParserTest` 新增 `$NonObjectProperty` 组：NP-1 两个接受例（节点字段与偏移）；NP-5 ①～⑥ 和 NP-6 每类至少 1 例，断言 kind=`object`、偏移指向 `->`、片段含原文；NP-7 `if(false){ echo $obj->x; }` 拒绝 | 后端（S3-07b 补丁或并入 S3-07c，由调度定） |
| C-18 黄金 | `PhpParserGoldenTest`：`rejected` 改为空，`parsed` 由 166 改为 168；覆盖表新增 `NonObjectProperty=4`，`Echo` 等相关计数按实测更新；新增断言：两份文件各 2 个节点，节点偏移（NP-1：取 `$` 的偏移）为 **430**（`site_url`）、**462**（`path_tpl`），对应的 `->` 偏移分别为 437、469（`git show b71b1aa:<路径> | grep -boa '\$maccms->'` 实测） | 后端 |
| C-19 解释器 | `InterpreterTest`：宿主 `$maccms` 取 ①带真实键的数组 ②空数组 ③未定义，三种情况执行黄金第 12 行 + LF，输出都等于 `<url>http://images/logo.png</url>` 加 LF（共 34 字节）；无 WARN 日志 | 后端（S3-07c） |
| 诊断（C-23） | 不新增 kind；`RuntimeDiagnosticsTest` 的 kind 精确列表不变；拒绝例的偏移和片段沿用现有 `object` 规则 | 后端 |
| JDK-only | 新增代码只用 `java.*`，沿用现有 JDK-only 检查 | 后端 |
| 黄金 | **不改** `b71b1aa`。期望值写进 v16 设计正文，并附 §8 ④⑤ 的复跑命令（与 BARE-7 S15-4 同样的做法）。如需把它固化成 `php73-vectors.json` 的新向量（例如 `gap2_nonobject_property`），须由调度另派测试修订 S3-05 资产；本文不建议为此改黄金 | 测试（可选） |
| V-12（S3-08 / S3-09） | 编译器 C-20 对无点号的 `{$a:b}` 按 `Template.php:873` 生成 `$a->b`，与黄金第 12 行逐字节相同。OP-T8 的归一化不涉及 `->` | 后端 |
| AC-5 / T3（S3-14g，PG-6） | `rss/index` 两包页面对照：200、`text/xml`、`<url>http://images/logo.png</url>` | 测试 |
| V-6（S3-17） | `make/rss?ac2=index` 生成文件内容与 PHP 一致 | 测试 |

### 7. 受影响的门禁与批次

| 对象 | 现状 | 采纳 D 后的影响 | 是否要重判 |
| --- | --- | --- | --- |
| G1 技术设计（v15 @ `9671aae`） | 已放行 | v16 增量：RT-1 文字、新增小节（NP-1～NP-7）、C-18 / C-19 增量、V-11 增项、§12 自检补全量 `->` 枚举、新 RISK（同 RISK-T27 类） | 是：架构评审只复审 v16 增量，调度重判 G1 |
| APPK-3 / S3-07b（`c02c686`，独立复核 PASS，代码评审进行中） | 按 v15 拒绝 GAP-2 并锁定 | 本批 AC 不受影响（拒绝与 v15 一致）。v16 生效后，`PhpParserGoldenTest` 的拒绝锁定要翻转，可以作为 S3-07b 补丁或并入 S3-07c | 否：本批按 v15 验收；补丁另行复核 |
| APPK-4 / S3-07c（backlog） | 待放行 | 若 v16 在派单前生效，NP-3 求值并入 S3-07c；否则 S3-07c 照常推进，GAP-2 继续显式拒绝，作为依赖约束保留到 S3-14g 之前 | 派单前由调度确认 v16 状态 |
| S3-05 黄金（`b71b1aa`） | 两层 PASS | 不改 | 否 |
| S3-08 / S3-09（V-12） | 未派 | 不变：编译器本来就应该生成 `->`，黄金已是这个结果 | 否 |
| S3-14g（PG-6）、S3-17（V-6）、T3 | 未派 / 冻结 | `rss/index` 由“必然 500”变为可对照；GAP-2 解决前，这两项的 `rss/index` 样本不能判通过 | 否（依赖 v16 落地） |
| PRD、API、模板资产、接口脚本、deploy | — | 不改 | 否 |

### 8. 复跑命令（Git Bash，仓库根；临时文件写到仓库外的空目录，不入库；`PHP73` 指向 PHP 7.3.33 CLI）

```bash
W=$(mktemp -d)
git archive b71b1aa4be0190f85686650a7b22459c995cf57e src/test/resources/phptemplate/compile-golden | tar -x -C "$W"
git archive 200cffc5d01090faf4f61e153feb472b107b34df third_party/maccms-template-v2020.1000.1035/template | tar -x -C "$W"
G="$W/src/test/resources/phptemplate/compile-golden"; S="$W/third_party"
php73="${PHP73:?先把 PHP73 设为 PHP 7.3.33 CLI 的可执行文件}"

# ① 全部 -> 的逐记号枚举
cat > "$W/arrow.php" <<'EOF'
<?php
$files=0; $chunks=0; $out=[];
foreach (new RecursiveIteratorIterator(new RecursiveDirectoryIterator($argv[1], FilesystemIterator::SKIP_DOTS)) as $f) {
  if (substr($f,-5)!=='.html') continue; $files++; $sig=[];
  foreach (token_get_all(file_get_contents($f)) as $t) {
    if (is_array($t) && in_array($t[0],[T_WHITESPACE,T_COMMENT,T_DOC_COMMENT],true)) continue;
    if (is_array($t) && $t[0]===T_OPEN_TAG) $chunks++;
    $sig[] = is_array($t)?$t:[$t,$t,0];
  }
  foreach ($sig as $i=>$t) {
    if ($t[0]!==T_OBJECT_OPERATOR) continue;
    $prev=$sig[$i-1]; $name=$sig[$i+1]; $form=(($sig[$i+2][0]??null)==='(')?'method':'property';
    $rel=substr(strtr($f,chr(92),'/'), strlen(strtr($argv[1],chr(92),'/'))+1);
    $out[$form.' '.(is_array($prev)?$prev[1]:$prev).'->'.$name[1]][]=$rel.':'.$name[2];
  }
}
echo "files=$files php_open_tags=$chunks\n"; ksort($out);
foreach ($out as $k=>$v) echo $k,"\t",count($v),"\t",implode(' ',array_slice($v,0,4)),"\n";
EOF
"$php73" "$W/arrow.php" "$G"
# 预期：files=168 php_open_tags=6032
#   method )->infoData 2；method ]->isEmpty 10
#   property $maccms->path_tpl 2、property $maccms->site_url 2（均为两份 rss/index.html:12）；无其他行

# ② 模板原文里的冒号变量写法：预期只有两份 rss/index.html:11 各 2 处
grep -rnoE '\{\$[A-Za-z_][A-Za-z0-9_]*:[^}]*\}' --include=*.html "$S"

# ③ 编译产物里改写 $maccms 的语句：预期 0 行
grep -rnE '\$maccms\s*=[^=]|\$maccms\[[^]]*\]\s*=[^=]' "$G" | wc -l

# ④ 非对象读属性的值域（RT-2 全部类型）
cat > "$W/dom.php" <<'EOF'
<?php
error_reporting(E_ERROR | E_PARSE);
$GLOBALS['e'] = []; set_error_handler(function ($n, $s) { $GLOBALS['e'][] = $n; return true; });
$vals = ['null'=>null,'true'=>true,'false'=>false,'int'=>7,'float'=>1.5,'string'=>'abc','empty_string'=>'','array_with_key'=>['site_url'=>'x'],'array_empty'=>[]];
foreach ($vals as $k => $maccms) { $GLOBALS['e'] = []; $r = $maccms->site_url; echo $k,' => ',var_export($r,true),' errnos=',json_encode($GLOBALS['e']),"\n"; }
unset($maccms); $GLOBALS['e'] = []; $r = $maccms->site_url; echo 'undefined => ',var_export($r,true),' errnos=',json_encode($GLOBALS['e']),"\n";
$maccms = ['site_url'=>'x']; $GLOBALS['e'] = [];
echo 'isset => ',var_export(isset($maccms->site_url),true),' empty => ',var_export(empty($maccms->site_url),true),' errnos=',json_encode($GLOBALS['e']),"\n";
echo 'interp => ',json_encode("$maccms->site_url"),"\n";
EOF
"$php73" -n "$W/dom.php"
# 预期：9 种类型都是 NULL errnos=[8]；undefined => NULL errnos=[8,8]；isset => false empty => true errnos=[]；interp => ""

# ⑤ 黄金第 12 行的执行结果
cat > "$W/run.php" <<'EOF'
<?php
error_reporting(E_ERROR | E_PARSE);
$GLOBALS['errs'] = []; set_error_handler(function ($no, $str) { $GLOBALS['errs'][] = [$no, $str]; return true; });
function run($src, $maccms) { $tmp = tempnam(sys_get_temp_dir(), 'g2'); file_put_contents($tmp, $src); ob_start(); include $tmp; $o = ob_get_clean(); unlink($tmp); return $o; }
foreach (['default_pc', 'default_wap'] as $p) {
  $l12 = explode("\n", file_get_contents($argv[1] . "/$p/html/rss/index.html"))[11];
  echo "$p L12 len=", strlen($l12), " sha256=", hash('sha256', $l12), "\n";
  foreach (['array' => ['site_url' => 'www.example.com', 'path_tpl' => '/template/default_pc/'], 'empty' => []] as $k => $m) {
    $GLOBALS['errs'] = []; $o = run($l12 . "\n", $m);
    echo "  [$k] out=", json_encode($o), " errs=", json_encode($GLOBALS['errs']), "\n";
  }
}
EOF
"$php73" -n "$W/run.php" "$G"
# 预期（两份相同）：L12 len=97 sha256=26d078e6…8a49；
#   两种宿主值 out="<url>http:\/\/images\/logo.png<\/url>\n"，各 2 条 errno 8（site_url、path_tpl）

# ⑥ PRE-5 扫描器正则对冒号写法的匹配：预期 RE_VAR ['maccms.site_url']、RE_VAR_FILTER ['{$maccms.site_url}']（冒号写法两条都不匹配）
git show b550151b4243622fe7ac060c9ff6e720d83ae605:tests/compare/APPK-2/scan_templates.py | sed -n '111p;132p'
```

### 9. 本文没有做的事

- 没有修改 `tech-design.md`、产品代码、测试、黄金、模板、PRD、API 契约或接口脚本；没有合入任何分支。
- 没有在方案之间做最终取舍：§4 只是建议，采纳与否由调度带回 APPK-2 主单决定。
- 没有判断 S3-07b 本批是否通过；该批按 v15 验收，代码评审与安全审计由后端评审另行交付。
