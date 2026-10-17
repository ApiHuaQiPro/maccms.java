## 视频列表筛选参数补全与随机排序（路线图 B04，MACC-28，契约 1.3.0）

> **状态：已在本地实现并通过自动测试（含真实 MySQL 与 PHP 黄金样本对照），未部署。** 只读批次：不写库、不登记任何业务写入、不改 B02/B07 护栏，零结构变更。
> **补充（队长裁定 Q1/Q2/Q3，见第 5.2 节）**：`area`、`lang`、`classes` 的逗号多值已补入同一个尚未合并的 1.3.0（第 3.1 节）；`tid` 与 SSR 搜索页的承接归 B05；API 字面 `0`、严格 `categoryId` 与旧 `rnd` 算法不变。
> 依据：`docs/architecture/09-roadmap.md` 第 5 节 B04 行与 MACC-23 对 B04 的补充范围；`docs/architecture/09-content-search-filter-plan.md`（以下简称"09 方案"）第 3、4、6 节；旧代码 `application/common/model/Vod.php:108-567`（`listCacheData`）、`application/common.php:1560-1606`（`mac_param_url`）、`:2879-2888`（`mac_search_len_check`）、`:637-677`（`mac_substring`）、`:1284-1292`（`mac_like_arr`）、`application/route.php:321-334`。
> 证据标注沿用 09 方案：**【代码】**仓库代码；**【黄金】**PHP 黄金样本实测（`backend/src/test/resources/content/vodlist/php-golden.json` 为 PHP 8.0.30，`php73-golden.json` 为 PHP 7.3.33）；**【待核实】**依赖线上配置或数据。

### 1. 范围

| 项 | 本批 |
|---|---|
| 新参数（`GET /api/v1/videos`） | `letter`、`state`、`version`（精确匹配，逗号多值）；`actor`、`director`、`tag`、`name`（包含匹配，逗号多值，受搜索开关）；`ids`（`all` 或最多 100 个 ID）；`year` 增加 9 字符区间写法；`sort=rnd`（旧版随机取页） |
| 既有参数放开多值（Q1） | `area`、`lang`（精确匹配任一项）与 `classes`（任一项包含匹配，受搜索开关）接受逗号多值，保留空项；此前含逗号返回 400 的请求现在有结果 |
| 不变 | 1.1.0/1.2.0 已有参数的规则、文案与 SQL 文本（单个年份仍是 `vod_year = ?`；`area`/`lang`/`classes` 不含逗号时仍是 `vod_area = ?` 等单值语句）；API 中 `'0'` 是字面值、`categoryId` 严格为 1..65535 的整数；所有响应 schema 与错误码 |
| 不在本批（归 B05） | 旧模板标签专属的条件（第 2.1 节），由 B05 作为内部查询能力承接；`tid`（第 5.2 节 Q2）；SSR 搜索页对新键与 `by=rnd` 的承接（目前仍交 PHP，只自动获得年份区间与 `area/lang/class` 多值，第 2.4 节）；位置参数地址（`vodsearch/…`、`vodshow/…`）的 Java 路由；SSR 逐键零值归一 |
| 只读 | 新 SQL 只有 `SELECT`；`WriteRegistry` 未增登记；`mac_vod_search` 不读不写 |

### 2. 旧 URL 输入与新接口参数的映射

#### 2.1 URL 可达组与标签专属组

旧列表条件来自两处：分页标签 `{maccms:vod paging="yes" …}` 的属性，和 `paging=yes` 时被 URL 参数覆盖的那一部分（`Vod.php:167-247`，只在 `!empty()` 时覆盖）。URL 参数先经 `mac_param_url` 读出——**只有它读取的键**才可能从 URL 到达 `listCacheData`：

| 组 | 旧参数 | 依据 | 归属 |
|---|---|---|---|
| URL 可达（本批） | `letter`、`state`、`version`、`actor`、`director`、`tag`、`name`、`ids`、年份区间、`by=rnd` | 【代码】`common.php:1583-1605` 读出这些键；`Vod.php:174-247` 覆盖 | **B04**（本批） |
| URL 可达（已有） | `wd`、`area`、`lang`、`year`（4 位）、`level`、`class`、`by`、`order`、`page`；`area/lang/class` 的逗号多值在本批补齐（Q1） | 同上 | MACC-18 / 1.1.0；多值 B04 |
| URL 可达（未映射） | `tid`（`type_id = tid OR type_id_1 = tid`，`Vod.php:180-182,356-358`） | 同上 | **B05**：SSR 内部条件，API 不新增（第 5.2 节 Q2） |
| 标签专属 | `isend`、`timeadd`、`timehits`、`time`、`hitsmonth/hitsweek/hitsday/hits`（点击范围）、`not`、`rel`、`typenot`、`tv`、`weekday`、`plot`、`start`、`half`、`type`（标签属性） | 【代码】`mac_param_url` 不读这些键；`isend` 虽在 `Vod.php:244` 用 `isset($param['isend'])` 检查，但 `$param` 中永远没有这个键，URL 到达不了 | **B05** 内部查询能力（不新增前台 URL 输入）；路线图第 3 节、第 5 节同步修改 |

新接口对标签专属参数名（`isend=1`、`timeadd=…` 等）一律忽略，与未声明参数相同（`VideoListFilterApiContractTest.idsAllIsTheContract100QueryAndLegacyNamesAreStillIgnored`）。

#### 2.2 旧参数 → 新接口参数（固定映射）

| 旧参数 | 新参数 | 旧值处理（`mac_param_url`） | 旧 `search_len` 截断 | 新接口长度 / 格式 | 新接口固定文案（400） |
|---|---|---|---|---|---|
| `wd` | `keyword` | trim → urldecode → htmlspecialchars | 是 | ≤ 64 码点 | 见 09 方案 4.3 |
| `class` | `classes` | 同上 | 是 | ≤ 32（整个原始值），逗号多值 | 同上 |
| `area` / `lang` | 同名 | 同上 | 是 | ≤ 20 / ≤ 10（整个原始值），逗号多值 | 同上 |
| `year` | `year` | 同上 | **否** | 4 位数字；或 9 字符区间（第 3.2 节） | `必须是 4 位数字年份` |
| `level` | `level` | `intval`（单值） | 是（整数，无影响） | 1..9 | `必须是 1 到 9 之间的整数` |
| `letter` | `letter` | trim → htmlspecialchars（**不** urldecode，`common.php:1588`） | 是 | ≤ 64 码点 | `首尾不能有空白字符` / `含不允许的字符` / `长度不能超过 64 个字符` |
| `state` / `version` | 同名 | trim → urldecode → htmlspecialchars | 是 | ≤ 64 码点 | 同上 |
| `actor` / `director` / `tag` / `name` | 同名 | 同上 | 是 | ≤ 64 码点；开关关闭时 `当前未开放关键字搜索` | 同上 |
| `ids` | `ids` | 同上 | **否** | `all`，或 ≤ 100 项、每项为空或 ≤ 10 位数字且 ≤ 4294967295 | `最多 100 个 ID` / `必须是 all 或以逗号分隔的视频 ID` |
| `by` / `order` / `page` | `sort` / `order` / `page` | `by` 白名单含 `rnd` | 否 | `sort` 枚举增加 `rnd` | 既有文案 |
| `tid` | —（API 不新增） | `intval` | 否 | B05 在 SSR 按旧条件 `type_id = tid OR type_id_1 = tid` 作内部条件（Q2） | — |
| `vodshow` 的 `id` | （`categoryId`，语义近似） | 分类 ID | 否 | `vod/show` 归 PHP（09 方案 D3），本批不承接 | — |

- **截断只适用于名单中的键**：`mac_search_len_check` 的名单是 `wd/tag/class/letter/name/state/level/area/lang/version/actor/director/starsign/blood`（【代码】`common.php:2881`）；`ids`、`year` 不在名单中（【黄金】`urls[query.truncation]`：17 个字符的 `ids` 原样保留）。新接口不截断，超过上限 → 400；这一差异只描述名单中的键（第 5 节）。
- **空位规则**：旧版 `''` 与 `'0'` 都因 `!empty()` 为假而视为未传（【黄金】`urls[vodsearch.zero]` 中 `0` 在路由与 `mac_param_url` 阶段仍保留，`cases[letter.0]`、`cases[ids.0]` 到 `listCacheData` 才被丢弃）。新接口沿用 1.1.0 约定：只有 `''` 是未传，`'0'` 是字面值；把 `'0'` 当未传是 URL 层（SSR）的归一职责（09 方案第 7.2 节，已有的 SSR 搜索页即如此，只认整个值恰为 `'0'`）。旧版按键还有"`intval` 后为空"的情形（`level=00`、`year=0000` 不筛选），这些逐键归一随 B05 的 SSR 承接实施，API 不变（Q2）。

#### 2.3 位置参数地址（`application/route.php:321,331`）

【黄金】`urls[vodsearch.slotN.*]`、`urls[vodshow.slotN.*]`（`App::routeCheck` + `mac_param_url`，路由开启时）逐位确认：

| 地址 | 位置顺序（`-` 分隔，空位即未传） |
|---|---|
| `vodsearch/<…>.html` → `vod/search` | 1 `wd` · 2 `actor` · 3 `area` · 4 `by` · 5 `class` · 6 `director` · 7 `lang` · 8 `letter` · 9 `level` · 10 `order` · 11 `page` · 12 `state` · 13 `tag` · 14 `year` |
| `vodshow/<…>.html` → `vod/show` | 1 `id`（分类）· 2 `area` · 3 `by` · 4 `class` · 5 `lang` · 6 `letter` · 7 `level` · 8 `order` · 9 `page` · 10 `state` · 11 `tag` · 12 `year` |

- 值中的 `-` 与分隔符冲突：`vodsearch/a-b-------------.html` 得到 `wd=a-b`（多出的段并入第 1 位）；`vodsearch/-------------2018-2020.html` 得到 `wd=-`、`tag=2018`、`year=2020`——**年份区间不能写在位置参数地址里**，只能经查询串或路径参数（`…/year/2018-2020`）传入。`ids`、`name`、`version` 不在位置参数地址中，只能经查询串或路径参数传入。
- 每个位置的取值随后按第 2.2 节对应的新参数校验。位置参数地址本身仍归 PHP（10 文档第 1 节），本批只固定映射（`LegacyVodUrlMappingTest` 以两份黄金样本锁定），供承接这类地址的批次使用。

#### 2.4 SSR 搜索页（MACC-20）受到的影响

搜索页把 `year` 交给 content 的同一规则（10 文档第 2 节步骤 8），因此**自动接受年份区间**，链接生成与回读不变式照常成立（`SearchLinkRoundTripTest` 新增 8 个区间样本；`LegacyUrlParserSearchTest` 中 `year=2020-2022` 由 400 改为接受）。同理，`area`/`lang`/`class` 的逗号多值（Q1）也由 content 接受：搜索页把整个值（只去掉整个值首尾的空白，整个值为 `0` 时按未传）原样交给 content，旧链接 `/vod/search.html?area=大陆,香港` 从 400 变为与旧版一致的并集；当前值作为一个筛选项显示为当前态，隐藏字段、分页与筛选链接原样保留（逗号编码为 `%2C`），点候选值时换成该单值（`SiteSearchPagesMvcTest.multiValueFiltersArePassedThroughAndKeptInFormAndLinks`、`SiteSearchMySqlIT` 沿页面实际生成的上一页/下一页链接往返、`SiteSearchRealHttpTest` 真实 Tomcat 下未编码与编码的逗号）。没有改动解析器、模板或样式。`actor/director/tag/letter/state/version/name/ids`、`tid` 与 `by=rnd` 在搜索页仍按 10 文档第 2 节步骤 4、6 交 PHP（本地 404）；由 Java 搜索页承接归 B05，开工时先与前端定筛选区、隐藏字段与链接键序的契约（第 5.2 节 Q2）。

### 3. 参数规则（content 层，`VideoFilter`）

所有新参数与 1.1.0 相同的通用规则：校验原始解码值，不裁剪；`''` = 未传；同名参数出现两次 → 400 `参数不能重复`；禁用字符或非法 UTF-8 → 400 `含不允许的字符`；首尾为 ECMAScript `\s` 字符 → 400 `首尾不能有空白字符`。`area`/`lang`/`classes` 取消 1.1.0 的"逗号"一步（不再产生 `不支持多个取值`），其余规则与长度上限不变（上限作用于整个原始值）。校验顺序：既有 5 个参数 → `keyword → area → lang → year → level → classes → letter → state → version → actor → director → tag → name → ids`；只报第一个错误；参数错误先于分类可见性（404）。构造器与 `parse` 同一套规则（`VideoFilterListParamsTest.constructorEnforcesTheSameRulesAsParse`）。

#### 3.1 逗号多值（与 PHP `explode(',')` 相同，保留空项）

| 参数 | SQL（列名固定，值只绑定） | 空项 | 旧代码 |
|---|---|---|---|
| `area` / `lang`（Q1） | 多于一项时 `vod_area IN (…)` / `vod_lang IN (…)`；只有一项时仍是 1.1.0 的 `vod_area = ?` / `vod_lang = ?` | 命中该列为空的行 | `Vod.php:299-304` |
| `classes`（Q1） | 多于一项时 `(vod_class LIKE ? ESCAPE '!' OR …)`，每项 `%项%`；只有一项时仍是 1.1.0 的 `vod_class LIKE ? ESCAPE '!'` | `%%`，命中全部（与旧 `mac_like_arr` 相同） | `:475-477` |
| `letter` | `vod_letter IN (…)`；原始值的 UTF-8 第 1 字节为 `0` 且第 3 字节为 `9` 时为 `0`..`9` | 命中首字母为空的行 | `Vod.php:330-335` |
| `state` / `version` | `vod_state IN (…)` / `vod_version IN (…)` | 命中该列为空的行 | `:305-310` |
| `actor` / `director` / `tag` / `name` | `(vod_actor LIKE ? ESCAPE '!' OR …)`，每项 `%项%`；`name` 只匹配 `vod_name` | `%%`，命中全部（与旧 `mac_like_arr` 相同） | `:469-483` |
| `ids` | `vod_id IN (…)` | 按 0（旧版 `IN ('')` 同样命中不了任何行） | `:273-277` |

- `letter` 的展开判断按 PHP `substr` 的**字节**位置，并在 htmlspecialchars 之后的形式上进行（`0&9` → `0&amp;9`，第 3 字节是 `a`，不展开；`0中9` 中第 3 字节是汉字的中间字节，不展开）。展开后其余部分被忽略（`0-9,A` 只匹配数字）。【黄金】`letter.0-9`、`letter.0x9`、`letter.0,9`、`letter.0-9,A` 结果相同。
- 各项不再裁剪（`a, b` 的第二项是 ` b`）；全角逗号是普通字符。
- 包含匹配的每一项含 `& " ' < >` 时另以 htmlspecialchars 形式匹配一次（与 1.1.0 `keyword` 的规则相同；旧版只匹配实体形式，见第 5 节）；`classes` 的各项与 1.1.0 单值一样不做实体变体。`%`、`_`、`!` 按字面匹配。
- 【黄金】`area.multi`（并集 38 = 19 + 19）、`area.trailing-empty`（2020：香港 19 行 + 地区为空的批量行 2001）、`area.comma`、`area.fullwidth`（0）、`area.inner-space`（第二项 ` 香港` 不命中，19）、`lang.multi`、`lang.trailing-empty`、`class.one`（包含匹配命中 `动作冒险` 与 `动作，喜剧`）、`class.multi`、`class.trailing-empty`、`class.comma`（命中全部 2039）、`class.fullwidth`（只命中字面含 `动作，喜剧` 的 1 行）、`combo.area-lang-class`、`sort.area-multi-hits-asc`，与新接口逐项相同；`area.one`、`combo.area-tag` 等单值用例回归不变。
- `ids=all` 不产生条件，使用与 1.0.0 相同的语句（`isEmpty()`）。`ids` 只是条件，结果仍按 `sort`/`order` 排序。100 项上限是新版的请求大小上限，与旧 `VodSearch::$maxIdCount = 1000`（`application/common/model/VodSearch.php:11`，搜索结果缓存的阈值，09 方案第 1.4 节）无关，后者新版不实现。

#### 3.2 年份区间

接受 4 位 ASCII 数字，或 9 个字符：前 4 位、后 4 位为 ASCII 数字，第 5 个字符为任一可打印 ASCII（`0x20`–`0x7E`，旧版 `strlen==9` 时只取 `substr(0,4)` 与 `substr(5,4)`，【代码】`Vod.php:187-200`）。两端前大后小时交换；逐年展开为 4 位字符串（不足补 0），最多 10000 项（`0000-9999`）。展开后只有一年时仍生成 1.1.0 的 `vod_year = ?`。【黄金】`year.2018-2020`、`year.2020-2018`、`year.2018~2020`、`year.2018,2020` 结果相同。文案沿用 `必须是 4 位数字年份`（不改既有文案）。

**更正 09 方案**：09 方案第 4.5、9 节写"旧版 `year` 以整数比较，`'2020年'`、`' 2020'` 等也会被数值转换命中"。黄金样本否定了这一点：旧版 `year=2020` 命中库值 `2020` 与 `2020 `（尾随空格，`PAD SPACE`），**不**命中 ` 2020` 与 `2020年`（【黄金】`year.2020`，种子第 36–38 行）。原因是 ThinkPHP 按列类型给条件值选择绑定方式，`vod_year` 为 varchar，`IN` 中的整数以字符串绑定（【代码】`thinkphp/library/think/db/Builder.php:363` 按列取绑定类型；`thinkphp/library/think/db/Query.php:1973-1998` `getFieldsBind`：字符串列为 `PDO::PARAM_STR`）。新旧版的剩余差异只在 1000 年以前（第 5.1 节）。

#### 3.3 开关、并发、时限

| 参数 | 搜索开关（默认关） | 并发许可（`max-concurrent`，不排队） | 语句时限 `MAX_EXECUTION_TIME(3000)` |
|---|---|---|---|
| `actor`、`director`、`tag`、`name` | 关闭时 400 `当前未开放关键字搜索`（在字符检查之前） | 与 `keyword`/`classes` 共用，满时 503 | 是 |
| `classes` 的多值与空项（Q1） | 同 1.1.0 单值：关闭时 400（`,`、`a,`、`a,b` 都不能绕过） | 占同一许可，满时 503 | 是 |
| `letter`、`state`、`version`、`ids`、年份区间、`area`/`lang` 多值 | 不受影响 | 不占 | 是 |
| `sort=rnd`（无其他条件） | 不受影响 | 不占 | 候选段语句与 MIN/MAX 有；无条件时的计数与分页沿用 1.0.0 语句 |

- 许可覆盖整次读取（含随机排序的全部语句），正常结束、参数/可见性错误、数据库异常、超时都在 `finally` 中归还（`VideoRandomListTest.permitIsReleasedWhenTheRandomQueryFails`、`VideoListFilterApiContractTest.statementTimeoutIs503ForEveryNewCondition`）。
- 超时（MySQL 3024、排序阶段 1028）与并发满的响应体与数据库不可用逐字相同，日志只有固定说明与类型链，不含条件值（同上测试捕获日志）。
- 服务层在查询前再检查一次开关（其他模块直接构造带 `actor` 等的条件也绕不过），错误字段为第一个包含匹配参数（`firstContainsMatchParameter`）。

### 4. 随机排序（`sort=rnd`）

旧代码（【代码】`Vod.php:500-537`）先按全部条件计数 c（`countData`），再分两种算法；新版逐项照搬（`ContentReadService.randomPage`）：

| | 条件 | 旧版 | 新版 |
|---|---|---|---|
| 算法一 | c ≤ 2000，或全表 MIN/MAX 无效 | 随机页 `mt_rand(1, floor(c / num) + 1)`，覆盖请求的 page；按 `hits_week desc` 取该页 | 同：随机页 1..(c ÷ pageSize 取整 + 1)，忽略请求的 page；响应的 `page` 是实际取的随机页，`total` = c |
| 算法二 | c > 2000 且全表 `MIN(vod_id)` 非空非 0、`MAX > MIN` | `array_rand(range(min, max), 1000)`，以其结果为 `vod_id IN (…)` 追加条件；**page 用请求的值**；按 `hits_week desc` | 同：从下标 0..(max−min) 中不放回取 1000 个作为候选 ID，与原条件 AND；`total` 为加上候选段后的计数 |

- **候选范围照搬旧缺陷**：`array_rand` 返回的是数组的**键**（0..max−min），不是 ID。所以候选 ID 落在 0..max−min：ID 从 1 开始且连续时几乎都能命中；本数据集 ID 为 1..3001、条件只选中 1001..3001 时约 2/3 命中（【黄金】`rnd.threshold-2001` 为 663 条）；ID 稀疏时命中少（`rnd.sparse` 45 条）；最小 ID 大于 max−min 时一个也命中不了，结果为空（`rnd.high-ids`，最小 ID 1010010）。按"保留旧算法候选范围"的要求照搬；是否修正（改为 min..max）需要队长裁定，见第 5.2 节。
- 全表 MIN/MAX **不带任何条件**（含未审核行）：ThinkPHP 每次查询后清空条件（【代码】`thinkphp/library/think/db/Query.php:3013`），`Vod.php:509` 那条语句不含前一次计数的 where；新版 `VodMapper.selectIdRange` 同样不带条件。
- 分页语义：算法一的随机页可能大于请求允许的 1000（pageSize=1、c=2000 时上限 2001），响应 `page` 照实返回；c 恰为 pageSize 的整数倍（含 0）时最后一个随机页为空（与旧版相同）。算法二用请求的 page，越界为空页。
- 新版追加 `vod_id DESC` 作并列时的顺序（旧版没有）。`order` 照常校验但不起作用（旧版固定 desc）。
- 随机源 `content.application.VideoRandom`（`ThreadLocalRandom`，Floyd 抽样，不按区间分配内存）只要求取值范围与数量相同，不复刻 PHP 的随机序列；测试与集成测试替换为确定的实现覆盖每个分支（第 6 节）。
- 旧版 `cache_core=1` 时随机结果会被缓存（`Vod.php:546-561`）；新版不缓存（09 方案已决）。

### 5. 与旧版的差异

#### 5.1 已按既有决定或本批规则处理的差异

| 方面 | 旧 PHP（【黄金】用例） | 新接口 |
|---|---|---|
| `''` / `'0'` | 都视为未传（`letter.0`、`ids.0`：整表 2039 条） | `''` 未传，`'0'` 字面值（`letter=0` 只命中首字母为 0 的行，`ids=0` 为空）；URL 层归一 |
| 超长截断（仅名单中的键：`wd/class/area/lang/letter/state/version/actor/director/tag/name`，不含 `ids`/`year`） | 按 `search_len`（默认 10，线上值【待核实】）截断后再匹配（`actor.truncated`、`letter.truncated`） | 不截断；超过上限（64、32、20、10）→ 400 |
| 截断函数随 PHP 版本变化 | `mac_substring` 用 `' ' == 0` 判断单字节字符：PHP 7.3 正常截断；**PHP 8 把 ord 64–127 的字符（字母等）换成空格**，数字、标点与多字节字符不受影响（`substring` 向量：`abcdefghijkl` → PHP 8 为 10 个空格，PHP 7.3 为 `abcdefghij`；`letter.truncated` 在两个版本命中 4 / 19 行） | 不截断，与 PHP 版本无关。线上 PHP 版本【待核实】 |
| 二次解码、实体 | 再 `urldecode` 一次；值经 htmlspecialchars 后再匹配（`letter` 例外，不 urldecode） | 只解码一次；包含匹配原值与实体形式都匹配（`actor.entity`：旧版 5 行，新版 10 行，为旧版的超集）；精确匹配（letter/state/version）按原值 |
| `%`、`_` | LIKE 通配符 | 字面字符 |
| 年份 1000 以前 | `intval` 后去掉前导 0（`year.0005`、`year.0003-0006` 命中库值 `5`） | 按 4 位字符串（`0005`）比较，不命中 `5` |
| 非法格式 | 静默忽略（如 `year=2020年` 长度既不是 4 也不是 9，不筛选）或按库的类型转换比较 | 400（与 1.1.0 对 `level=abc` 等的处理一致） |
| `ids` 项数 | 不限 | ≤ 100，超出 400 |
| 排序并列 | 不确定 | 追加 `vod_id` 同向，稳定 |
| 随机排序缓存 | `cache_core=1` 时缓存 | 不缓存 |
| 可见范围 | `popedom_filter=1` 才过滤游客无权限分类；停用分类的视频可能出现 | 始终只列可见分类（1.0.0 起） |

#### 5.2 队长裁定（2026-10-16，MACC-23 / MACC-28）

| 编号 | 事项 | 证据 | 裁定与落点 |
|---|---|---|---|
| Q1 | `area`、`lang`、`class` 的逗号多值 | 旧版支持（`Vod.php:299-304` `IN explode`；`class` 为 `mac_like_arr`，`:475-477`）；PHP 8.0 / 7.3 黄金向量一致（第 3.1 节各用例） | **放开多值，补入本批尚未合并的 1.3.0**：`area/lang` 按 IN、`classes` 按各项 LIKE 的 OR，保留空项与尾逗号，全角逗号是字面字符；单值的 SQL 与结果不变；`classes` 的多值与空项仍受搜索开关（默认关闭）、时限、并发许可与 503 约束；长度上限、参数绑定与有界读取不变（第 3.1、3.3、7 节） |
| Q2 | `tid` | URL 可达（`common.php:1569`、`Vod.php:356-358`：`intval` 后 `type_id = tid OR type_id_1 = tid`）；与 `categoryId`（本身 + 可见直接子分类）在 `type_id_1` 与分类表不一致、分类不可见时结果不同 | **归 B05，API 不新增 `tid`、不映射为 `categoryId`**：SSR 承接时按旧 `intval` 与 `type_id OR type_id_1` 作内部条件，保留 1.0.0 起已接受的可见性限制；`intval` 为 0 时不筛选，为负时照旧版筛选（结果为空），不得归一为不筛选。B05 同时承接 SSR 搜索页的新键、`by=rnd`、位置参数路由与逐键零值归一，开工时先与前端定契约。路线图第 3.2 节、第 5.1 节、第 5.3 节 B05 行已同步 |
| Q3 | 随机排序候选范围的旧缺陷 | 按要求照搬（第 4 节）；ID 不从小数开始时随机结果偏少或为空 | **维持照搬旧算法**（契约不变） |
| — | 单个 `0` | 旧版各键 `'0'` 都按未传（第 2.2 节） | **API 维持字面 `0`**（1.1.0 已定）；归一属 URL 层，随 B05 |

### 6. 黄金样本与测试

| 文件 / 测试 | 内容 |
|---|---|
| `backend/src/test/php/vod_list_golden.php` | 一次性库 `maccms_it_*`（install.sql + `content/vodlist/seed.sql`），以 GET 参数调用旧 `Vod::listCacheData`（`paging=yes`）记录 ID 序列、总数与第 2 页窗口；随机排序先 `mt_srand(固定种子)` 再调用旧代码，并用同一种子与全表 MIN/MAX 重放 `array_rand` 得到旧代码实际使用的候选（核对旧结果全部在候选中）；依次执行 `phase-sparse.sql`、`phase-high.sql`（只有 DML）后再记录；最后打开路由记录位置参数地址与查询串的解析结果和 `mac_substring` 向量。不改旧 PHP、不连任何已有库、结束时删库 |
| `content/vodlist/php-golden.json`（PHP 8.0.30）、`php73-golden.json`（PHP 7.3.33） | 各 79 个列表用例（63 个 `equal`、8 个 `legacy-only`、8 个随机排序；其中 15 个 `equal` 为 Q1 补充的 `area/lang/class` 多值用例，种子为此给基础行的 `vod_class` 赋值，原有 64 个用例的结果逐项不变）、33 个 URL 向量、6 个截断向量；两份文件的 `equal` 用例与随机排序结果逐项相同 |
| `VideoListGoldenMySqlIT`（`-Pmysql-it`） | 同一份数据的一次性库 + 真实 HTTP：两份文件的 `equal` 用例比对 ID 序列、`total`（count 与列表条件一致）、第 2 页（每页 7 条）；`legacy-only` 逐条断言第 5.1 节差异（缺断言即失败）；随机排序把旧代码的随机页 / 候选交给新代码，比对 ID、`total`、`page`，并断言随机源的调用参数（随机页上限 `floor(c/num)+1`；候选范围 `max−min+1`、数量 1000）；2000 行阈值两侧（2000 / 2001）、稀疏 ID、候选全不命中各有用例；可见范围外的哨兵行从不出现；请求前后全部表 CHECKSUM/行数不变；删库前表结构指纹不变 |
| `LegacyVodUrlMappingTest` | 读两份黄金样本：位置参数地址每一位的映射、`-` 冲突、`0` 的保留；每个旧参数都有新参数；截断只作用于名单中的键（`ids`、`year` 不截断）；`mac_substring` 的 PHP 8 / 7.3 结果；查询串各键的解码差异 |
| `VideoFilterListParamsTest`、`VideoFilterTest`（更新） | 新参数的每条规则、`letter` 展开（字节位置、实体形式）、包含匹配模式与空项、`ids` 上限与格式、校验顺序、年份区间展开与上限 |
| `VideoRandomListTest`、`VideoRandomTest` | 可控制随机源下的每个分支：随机页上限（c = 0/1/19/20/21/40/2000 × 各页大小）、空的最后一页、阈值 2000/2001、候选段与其他条件 AND、稀疏/高 ID 的空结果、请求页越界、MIN/MAX 无效（null、0、相等、倒置）回落算法一、不可见分类 404、开关与许可归还；默认随机源的取值范围、互不相同、升序 |
| `VodMapperBoundSqlTest`（增加） | 新条件的固定列名与绑定值、count 与分页条件与绑定值相同、1.1.0 条件在前且文本不变、敌意输入不进 SQL 文本、`selectIdRange` 无参数 |
| `VideoListFilterApiContractTest`、`VideoListFilterSearchOffApiTest`、`ContentSearchRealHttpTest`（增加） | HTTP：映射、固定文案与顺序、并发许可共用与归还、精确条件与 rnd 不占许可、每个新条件超时 → 503、rnd 两种算法的响应形状；开关关闭；真实 Tomcat 下新参数的非法编码 → 400；Q1：多值成为列表条件、`classes` 多值/空项在开关关闭时 400、占许可、超时 503 后许可归还，`area/lang` 多值不占许可 |
| `ContentSearchMySqlIT`（Q1 增加） | 真实 HTTP + MySQL：`area`/`lang` 并集、尾逗号与只有空项、全角逗号、项内空白、`classes` 各项 OR 与空项命中全部、单值回归、多值条件下逐页拼接 = 一次取全且 total 一致；表锁下 `area` 多值、`classes` 多值与 `,` 都在约 3 s 返回 503；并发满时 `classes` 多值与 `,` 立即 503，之后许可恢复 |
| `SiteSearchMySqlIT`、`SiteSearchPagesMvcTest`、`SiteSearchRealHttpTest`、`LegacyUrlParserSearchTest`、`SearchLinkRoundTripTest`（Q1 增加） | SSR：多值并集与各项 OR、全角逗号、沿页面生成的分页链接往返；表单隐藏字段与链接保留整个值；canonical 统一编码逗号；链接生成与回读不变式覆盖多值、空项、尾逗号（随机样本字母表加入逗号） |

### 7. SQL 与读取上限

- count 与分页共用 `publishedInTypes` + `matchingConditions`（新条件追加在 1.1.0 条件之后），带条件的两条语句都有 `MAX_EXECUTION_TIME(3000)`；随机排序的计数、候选段计数与分页也走同一组语句。
- 每个 IN / OR 列表都有上限：`area` ≤ 21 项、`lang` ≤ 11 项、`classes` ≤ 33 个模式（由 20/10/32 码点推出）；年份区间 ≤ 10000 项；`letter/state/version` 与每个包含匹配参数 ≤ 33 项（64 码点内）、包含匹配模式 ≤ 66 个（含实体形式）；`ids` ≤ 100；候选段 = 1000。列表仍有 `LIMIT`（pageSize ≤ 100、page ≤ 1000，随机页按上文）。没有无界读取。
- `selectIdRange` 是全表 `MIN/MAX(vod_id)`，MyISAM 主键上由优化器直接取得。
- 性能：包含匹配仍默认关闭（MACC-19 结论未变）；新增的精确条件与随机排序未做规模测量（第 8 节）。

### 8. 未实施、未验证与回滚

- **未实施（已裁定归 B05）**：SSR 搜索页对新键、`tid` 与 `by=rnd` 的承接（需与前端协调筛选区、隐藏字段与链接键序）；位置参数地址的 Java 路由；SSR 逐键零值归一（第 5.2 节 Q2）。
- **未验证**：线上 `search_len`、`search_vod_rule`、PHP 版本（决定截断行为）与数据分布【待核实】；年份区间 10000 项、随机排序候选段在大表上的耗时与 EXPLAIN（未做 T2 式测量）。
- **回滚**：本批无写库、无数据迁移；回滚即 `git revert` 本批提交（或回到合入前的 main）。契约降回 1.2.0 时，新参数重新变为"未声明参数，被忽略"，`year` 区间、`sort=rnd` 与 `area/lang/classes` 的逗号多值重新返回 400。只回退 Q1 补充时 revert 其单独的提交即可，其余 1.3.0 内容不受影响。
