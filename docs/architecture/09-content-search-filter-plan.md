## 视频搜索与多条件筛选：只读契约与下一批实施方案（MACC-16）

> **状态：第 4 版已验收（父任务 MACC-2 记录）。T1（content API/SQL 与真实 MySQL 验证）已由 MACC-18 实施**：6 个新参数、`maccms.content.search.*` 配置、执行时限与并发上限已是已实现能力，正式契约升为 `docs/contracts/content-read.openapi.yaml` 1.1.0，草案文件已合并后删除；实施与本方案的差异见第 12 节。T2（性能测量）、T3（SSR 搜索页）、T4（样式）**仍未实施**，文中这些部分仍是拟实施。以下第 0–11 节保持第 4 版原文（"草案"指已并入正式契约的那份接口草案）。
>
> **T2 已测量（MACC-19）**：一次性合成库 10 万/50 万/100 万行的 EXPLAIN、耗时、表锁与 503 证据见 `docs/architecture/09-content-search-filter-perf-report.md`；结论是包含匹配**保持关闭**（默认值未改），并发现时限在排序阶段触发时报 1028、被映射为 500 的缺陷（报告第 6.3 节；已由 MACC-22 修复为 503，见第 12 节"超时异常翻译"行）。下文第 6.2 节"定性分析，未测量"与第 11 节"全部未测量"以该报告为准。
>
> **T3 已实施（MACC-20）**：SSR 搜索页（固定路径 + 查询串、`parseSearch`、`vodSearchLink`、`SiteLink`/`SiteQueries`、400 页、`maccms.site.search.enabled` 默认关闭）的实现记录、测试与差异见 `docs/architecture/10-site-search-page.md`；第 7 节规则按原文实现，补充决定只有一处：无条件而 `page>1` 时返回 404（不查库）。
> 配套接口草案（已并入契约 1.1.0 并删除）：`docs/contracts/content-search-filter.draft.openapi.yaml`（以下简称"草案"）。已发布契约 `docs/contracts/content-read.openapi.yaml`（以下简称"契约"）。
>
> 输入：父任务 MACC-2 评论、`AGENTS.md`、`docs/architecture/02-agent-development-rules.md`、`docs/architecture/03-frontend-readonly-plan.md`、`docs/architecture/08-site-pages.md`、`docs/frontend/02-url-golden-vectors.md`、契约，以及下文逐条引用的旧代码与 Java 代码。
>
> 证据标注：
> - **【代码】**：仓库代码可直接核对，一律给出**仓库根目录相对的完整路径**与行号（`路径:行号`）。行号基于本 worktree（主线 `3f81c72`；本任务只改文档，代码行号与主线相同），第 2 版已逐条复核。**例外**：`base-url`/站内地址的长度预算（`SiteSettings.MAX_BASE_URL_LENGTH`、`SitePaths.MAX_LENGTH`）来自已验收的 MACC-17（提交 `7f5706c`，已整合进父任务本地分支 `6f34d7d`），本 worktree 的 `3f81c72` 尚不含该提交；这类引用标为"`6f34d7d` 中"，行号按该提交。MACC-17 只改了 `SitePaths.java`、`SiteSettings.java`、`SiteSettingsTest.java` 与 `docs/architecture/05-site-skeleton.md`，本文其他代码行号不受影响。
> - **【已验收】**：父任务已验收的 Java 行为（首页/分类/详情三页、URL 生成解析、公开 API），依据 `docs/architecture/08-site-pages.md` 与父任务评论。
> - **【建议】**：本方案提出、尚未实现，需审查。
> - **【待核实】**：依赖线上配置、真实数据、缺失模板或未做的实验，本文不下结论。
>
> 本任务**没有**：运行旧 PHP 请求、连接任何数据库、读取仓库外配置、修改代码/正式契约/AGENTS.md。
>
> 第 4 版只改一处（其余沿用第 3 版，已通过的查询串/字段边界、原始请求长度限制与 `%`/`+` 单次解码语义不变）：**`base-url` 长度向量对齐 MACC-17**。旧的"`base-url` ≤ 200"与"200 字符子目录 `base-url`"改为：完整 `base-url`（协议、主机、端口、路径合计，去首尾空白后按 Java `String.length()`）≤ 768；站内地址 `B?Q` ≤ 1235 ≤ `SitePaths.MAX_LENGTH` 1280；最大 canonical = 768 + 25 + 1 + 1209 = 2003 ≤ `SafeUrl.MAX_LENGTH` 2048；测试向量区分完整 URL 与目录路径，覆盖根地址、含非根子目录且总长恰为 768 的完整 `base-url`，以及 769 启动拒绝（第 1.5、7.2.2、7.2.4、7.2.5、7.2.7 节与 T3）。这只是方案文档的更新：Java 行为没有因本版改变，搜索功能仍未实现。
>
> 第 3 版只改两处（其余沿用第 2 版，第 2 版已通过的项未改动）：① **SSR 链接改为"固定路径 + 查询串"**：Java 生成的搜索页链接（分页、筛选、canonical）一律把参数放在查询串里，SSR 也只接受查询串形式的参数；现有 `LegacyUrlBuilder.vodSearch` 的 PATHINFO 输出不再用于 SSR 搜索页。补充原始请求、解码值、生成后 URL 三类长度上限及超限处理，并列出 API 与 SSR 的全部差异（第 7.2 节）；② **`level` 增加"原始字符串额外约束"**：明确 `integer` schema 的 `minimum`/`maximum` 不覆盖"最多 10 位"的词法限制，补上校验顺序与 `00000000009` 示例（第 4.3 节）。
>
> 第 2 版主要变化：① 所有代码证据改为完整路径；② 参数规则改为"API 不做任何裁剪，schema 约束直接作用于容器解码一次后的原始值"，空串/仅空白/首尾空白/超长在方案、草案 schema 与错误示例中一致（第 4 节）；③ `keyword` 允许半角逗号（按字面匹配，与旧 `wd` 一致），只有精确/扩展分类参数禁止逗号；④ `level` 改用与既有整数参数相同的解析；⑤ SSR 层对旧参数做 `trim` 与 `''`/`'0'` 归一（第 7.2 节）；⑥ `ContentApiMySqlIT` 为既有测试，改为"扩展"。

### 0. 结论摘要

| 项 | 推荐 |
|---|---|
| 实现路径 | **方案 A**：在已发布的 `GET /api/v1/videos` 上增加 6 个可选参数（`keyword`、`area`、`lang`、`year`、`level`、`classes`），复用 `VideoPage`/`VideoSummary`、可见性、排序与分页。不新建接口，不引入外部搜索引擎、索引、表或缓存（第 2 节） |
| 兼容性 | 不传新参数时 SQL 文本、响应、排序、分页、错误与 1.0.0 完全相同，由 BoundSql 黄金测试与既有契约测试锁定（第 2.3 节） |
| 首批参数 | 关键字（name/sub/en 包含匹配）、地区、语言、年份（4 位单值）、推荐等级（1..9）、扩展分类（包含匹配），加上已有的分类与 11 种排序。暂缓：演员、导演、标签、首字母、状态、版本、年份区间、多值、`rnd`、`tid` 别名等（第 3 节） |
| 参数规范化 | API：取容器解码一次后的原始值，**不裁剪**；只有空字符串按未传（契约 1.0.0 既有约定）；仅空白、首尾空白、超长、非法字符一律 400。schema 约束即运行规则；`level` 另有 schema 之外的原始字符串约束（最多 10 个字符，第 4.3 节）。SSR 层负责像旧站一样 `trim`（第 7.2 节） |
| 成本控制 | 包含匹配（keyword/classes）默认关闭（`maccms.content.search.enabled=false`），开启后有进程内并发上限（默认 4）与服务端语句时限（`MAX_EXECUTION_TIME(3000)`），超限返回既有 503。上线前必须在一次性合成库上完成 EXPLAIN/耗时/表锁测量（第 6 节） |
| 前台 | Java 承接旧 `vod/search` 的**固定路径 + 查询串**形式（即现有搜索表单提交出的形式），链接与 canonical 也只生成这一种形式，所有被接受的值都能生成并回读；页面 `noindex`、`no-store`。路径参数形式（`…/vod/search/wd/x.html`）、`vod/show`（筛选页）、路由别名、`-` 分隔符、不支持的参数仍归 PHP（第 7 节） |
| 拆分 | T1 content API/SQL + 真实 MySQL 集成测试 → T2 一次性合成库性能测量（只读证据）→ T3 URL 解析 + SSR 搜索页 → T4 Kimi 样式（第 8 节） |
| 需要决策 | D1 关键字匹配字段；D2 反滥用（不复刻频率限制/验证码）；D3 `vod/show` 是否本批承接（第 10 节） |

### 1. 旧逻辑证据

注意仓库中有两个 `Vod.php`：前台控制器 `application/index/controller/Vod.php` 与模型 `application/common/model/Vod.php`，下文均写完整路径区分。

#### 1.1 入口与前置检查

| 事实 | 依据 | 性质 |
|---|---|---|
| `vod/search`：`mac_param_url()` → `check_search` → `label_search` → 输出模板 `vod/search`。控制器本身**不查列表**，列表由模板里的 `{maccms:vod paging="yes" …}` 标签调用 `listCacheData` 取得 | 【代码】`application/index/controller/Vod.php:38-44`；标签属性白名单 `application/common/taglib/Maccms.php:24` | 模板缺失（`AGENTS.md`"仓库不完整"），搜索页标签实际用了哪些属性（`num`、`order`、`by`、`type`）**【待核实】** |
| `vod/show`：`check_show` → `label_type`（含分类列表权限检查）→ 模板 | 【代码】`application/index/controller/Vod.php:23-28`；`application/common/controller/All.php:166-188` | 同上 |
| `check_search`：`app.search=0` 时报错关闭；**第 1 页**与上次搜索间隔小于 `search_timespan` 秒时报"搜索太频繁"；`search_verify=1` 时要求验证码会话 | 【代码】`application/index/controller/Base.php:53-71` | 仓库配置 `search=1`、`search_verify=0`、`search_timespan=3`（`application/extra/maccms.php:66-69`）；线上值**【待核实】** |
| 频率检查**写会话**：`mac_get_time_span` 每次调用都把当前时间写回 session | 【代码】`application/common.php:306-316` | 游客第 1 页搜索也会产生 PHP 会话 |
| `check_show`：`app.show=0` 时整页关闭；`show_verify=1` 时要求验证码 | 【代码】`application/index/controller/Base.php:29-43` | 仓库 `show=0`（`application/extra/maccms.php:64`），即仓库默认下筛选页关闭；线上**【待核实】** |
| 所有前台页先执行 `label_user`：带会员 cookie 时 `checkLogin`，会员组到期可能触发写库（`docs/architecture/03-frontend-readonly-plan.md` 第 6.2 节） | 【代码】`application/index/controller/Base.php:11-18`、`application/common/controller/All.php:118-147` | Java 只按游客处理，会员请求按 `docs/architecture/03-frontend-readonly-plan.md` 第 2 节留在 PHP |
| 页面级缓存：`cache_page=1` 时以 `HTTP_HOST + 模板 + 查询参数` 为键读写缓存 | 【代码】`application/common/controller/All.php:24-56` | 仓库 `cache_page=0`（`application/extra/maccms.php:58`）；线上**【待核实】** |

#### 1.2 参数读取与规范化

| 事实 | 依据 |
|---|---|
| `mac_param_url` 把 ThinkPHP `input()` 与 `$_REQUEST` 合并（查询串/表单会覆盖路径参数）；`page`、`tid`、`level` 等经 `intval`（`page<1` 当 1）；`wd`、`area`、`lang`、`year`、`class`、`order`、`by` 等经 `htmlspecialchars(urldecode(trim(...)))` | 【代码】`application/common.php:1560-1606`（合并 1565、`page` 1567、`tid` 1569、`level` 1576、`wd` 1582、`area` 1585、`year` 1586、`lang` 1587、`class` 1592、`order` 1593、`by` 1594） |
| 由此产生的旧行为：① **先 `trim`**（PHP `trim` 只去 ASCII 空白与 NUL）；② **二次解码**——Web 服务器/PHP 已解码一次，`urldecode` 再解一次，`+` 变空格（搜索 `C++` 实际是 `C`）、`%2541` 变 `A`；③ **实体编码**——`&` 变 `&amp;` 后再进 SQL；`'` 是否编码随 PHP 版本变化（8.1 起默认 `ENT_QUOTES`，`docs/frontend/02-url-golden-vectors.md` 第 1 节已观察到） | 【代码】`application/common.php:1580-1603` |
| `mac_search_len_check`：`wd/tag/class/letter/name/state/level/area/lang/version/actor/director/…` 超过 `search_len` 个字符时**截断**（不报错） | 【代码】`application/common.php:2879-2888` |
| `search_len` 仓库值为空串，`Init` 行为把小于 1 的值改为 **10** | 【代码】`application/extra/maccms.php:68`；`application/common/behavior/Init.php:78-79`。线上值**【待核实】** |
| `label_search` 对**回显用**参数做敏感词替换（`filter_words`）与 `wall_filter` 编码；但 `listCacheData` 自己重新调用 `mac_param_url`，**查询条件不经过敏感词替换** | 【代码】`application/common/controller/All.php:155-164`；`application/common/model/Vod.php:167-169` |

#### 1.3 查询条件（`application/common/model/Vod.php` 的 `listCacheData`，`paging="yes"` 分支）

| 条件 | 旧规则 | 依据 |
|---|---|---|
| 参数来源 | 分页标签用 URL 参数覆盖标签属性：`level/ids/tid/year/area/lang/tag/class/state/letter/version/actor/director/wd/name/by/order/page/isend`；只在 `!empty()` 时覆盖，`''` 与 `'0'` 都视为未传 | 【代码】`application/common/model/Vod.php:167-247` |
| 状态 | 固定 `vod_status=1` | 【代码】`application/common/model/Vod.php:272` |
| 推荐等级 | `vod_level IN (explode(',', level))`；URL 中 `level` 已被 `intval`，实际只可能是单个整数，0 视为未传 | 【代码】`application/common/model/Vod.php:290-295`；`application/common.php:1576` |
| 年份 | 4 字符 → `intval`；9 字符 → 取前后各 4 位展开成区间；其他长度 → **不筛选**；比较 `vod_year IN (...)`（整数与 varchar 列比较） | 【代码】`application/common/model/Vod.php:183-201,296-298` |
| 地区/语言/状态/版本 | `IN (explode(','))`，逗号即多值；空项会变成 `IN ('')` | 【代码】`application/common/model/Vod.php:299-310` |
| 首字母 | `vod_letter IN (...)`，`0-9` 特殊展开 | 【代码】`application/common/model/Vod.php:330-335` |
| 分类（标签属性 `type`） | 命中 `type_id` 或 `type_pid` 在列表中的分类（即本身 + 直接子分类） | 【代码】`application/common/model/Vod.php:336-352` |
| 分类（URL `tid`） | `type_id = tid OR type_id_1 = tid`（`type_id_1` 是冗余存的上级分类） | 【代码】`application/common/model/Vod.php:356-358` |
| 关键字 `wd` | `vod_name` 及 `search_vod_rule` 列出的列 `LIKE '%wd%'`（多列为 OR）；**不按逗号拆分**（逗号是字面字符）；`%`、`_` 不转义 | 【代码】`application/common/model/Vod.php:462-468`；仓库 `search_vod_rule='vod_en|vod_sub'`（`application/extra/maccms.php:70`），线上**【待核实】** |
| `name/tag/class/actor/director` | 逗号拆分后每项 `LIKE '%项%'`，项之间 OR | 【代码】`application/common/model/Vod.php:469-483`；`application/common.php:1284-1292`（`mac_like_arr`） |
| 游客分类过滤 | 仅当 `popedom_filter=1` 时排除游客组无权限分类 | 【代码】`application/common/model/Vod.php:489-499`；仓库 `popedom_filter=0`（`application/extra/maccms.php:49`） |
| 排序 | `by` 白名单（含 `rnd`），非法值回落 `time`；`order` 非 asc/desc 回落 desc；拼成单列 `vod_<by> <order>`，**没有 id 兜底**，分页可能不稳定；`rnd` 改写为随机页或随机 id 段后按 `hits_week` 排 | 【代码】`application/common/model/Vod.php:539-545`（白名单与拼接）、`application/common/model/Vod.php:502-537`（`rnd`） |
| 分页与计数 | `LIMIT (page-1)*num+start, num`；`totalshow=1` 时另执行一次 `count()`，二者用同一个 `$where` | 【代码】`application/common/model/Vod.php:32-67`（`LIMIT` 43、`count` 45、列表 48） |
| 结果缓存 | `cache_core=1` 时按条件 md5 读写缓存 | 【代码】`application/common/model/Vod.php:552-561`；仓库 `cache_core=0`（`application/extra/maccms.php:56`） |

#### 1.4 `VodSearch` 搜索结果缓存（Java 不得复用）

| 事实 | 依据 |
|---|---|
| 只有 `vod_search_optimise` 含 `frontend` 时启用；启用后每个关键字先查 `mac_vod_search`，未命中就 `LIKE` 扫 `mac_vod` 取全部 id 并 **INSERT**，命中则 **UPDATE** 命中次数——即**前台 GET 搜索会写库** | 【代码】`application/common/model/VodSearch.php:18-64,69-78`；调用处 `application/common/model/Vod.php:404-459`。仓库 `vod_search_optimise=''`（`application/extra/maccms.php:97`），线上**【待核实】** |
| id 列表超过 `maxIdCount=1000` 时退回 LIKE；否则以字符串拼接 `vod_id IN (...)` | 【代码】`application/common/model/VodSearch.php:11`；`application/common/model/Vod.php:416-419,456-459` |
| 采集时 `checkAndUpdateTopResults` 以字符串拼接执行 UPDATE | 【代码】`application/common/model/VodSearch.php:97-128` |
| 表 `mac_vod_search` 为 InnoDB/utf8mb4 | 【代码】`application/install/sql/install.sql:887-901` |

结论：`mac_vod_search` 属于旧系统的写入路径，Java **不读、不写**该表，也不实现任何等价的结果缓存（`docs/architecture/02-agent-development-rules.md` 第 3 节"只读"）。

#### 1.5 旧 URL

| 事实 | 依据 |
|---|---|
| `mac_url` 先丢弃 `empty()` 的值（`''`、`'0'`）、第 1 页置空、按键排序；`vod/search` 走默认分支 `url($model,$param)` | 【代码】`application/common.php:1732-1746,2372-2374`；`mac_url_search` `application/common.php:2462-2465` |
| 搜索/筛选 URL 在 `suffix_hide=1` 时只把 `.html` 换成 `/`，不做 `-/`、`_.` 清理 | 【代码】`application/common.php:2414-2424` |
| 路由别名 `vodsearch/<wd?>-<actor?>-…-<year?>`（14 个位置参数，用 `-` 连接） | 【代码】`application/route.php:331` |
| Java 生成器已能生成旧搜索地址（仍指向 PHP）：`vodSearch(filters, page)`，参数白名单 `SEARCH_KEYS`；与 `mac_url` 一样跳过 `''` 与 `'0'` | 【已验收】`backend/src/main/java/com/maccms/backend/site/application/LegacyUrlBuilder.java:28-30,105-109,131-153`（跳过空值 142-144）；PHP 黄金向量见 `docs/frontend/02-url-golden-vectors.md` |
| 三页外壳的搜索表单 `GET` 提交到 `vodSearch(Map.of(), 1)`，即 `/index.php/vod/search.html`，输入框 `name="wd"`、`maxlength="64"` | 【已验收】`backend/src/main/java/com/maccms/backend/site/web/SitePages.java:32`；`backend/src/main/jte/site/layout/page.jte:50-54` |
| Java 解析器目前**不认识** `vod/search`（返回 empty，本地 404），并拒绝一切查询串 | 【已验收】`backend/src/main/java/com/maccms/backend/site/application/LegacyUrlParser.java:93-96,167-183` |
| 现有解析器的输入上限：原始路径、原始查询串各 ≤ `MAX_INPUT=1024` 字符，超出返回 empty | 【代码】`backend/src/main/java/com/maccms/backend/site/application/LegacyUrlParser.java:35,61-62` |
| 生成地址的长度预算（MACC-17 已验收）：页面输出的 URL 都是 `SafeUrl`，总长 ≤ `SafeUrl.MAX_LENGTH` = 2048（不变）；站内地址（相对站点根）≤ `SitePaths.MAX_LENGTH` = 1280；完整 `base-url`（协议、主机、端口、路径合计，去首尾空白后按 `String.length()` 计）≤ `SiteSettings.MAX_BASE_URL_LENGTH` = 2048 − 1280 = 768，可以是根路径或长的合法子目录地址；769 起启动失败，错误信息为"最多 768 个字符"且不回显取值。旧的"`base-url` ≤ 200"已被取代 | 【代码】`6f34d7d` 中 `backend/src/main/java/com/maccms/backend/site/application/SafeUrl.java:24,44`、`backend/src/main/java/com/maccms/backend/site/application/SitePaths.java:10-19,33`、`backend/src/main/java/com/maccms/backend/site/application/SiteSettings.java:18-22,31-35,94`；【已验收】`6f34d7d` 中 `backend/src/test/java/com/maccms/backend/site/application/SiteSettingsTest.java:69-107`（199/200/201/512/767/768 接受、769 拒绝）、`:153-169`（现有 `vodSearch` 最长 1235）；`docs/architecture/05-site-skeleton.md` 第 2 节 |
| `vodSearch` 只生成 PATHINFO（值经 PHP `urlencode` 后放进路径段，`.` 不编码），输出必须通过 `SitePaths.isValid`：拒绝 `%2F`、`%5C`、`%2E`、控制字符与空段、`.`、`..` 段；`suffixHide=true` 时把输出中**所有** `.html` 换成 `/`（含值内的 `.html`） | 【代码】`backend/src/main/java/com/maccms/backend/site/application/LegacyUrlBuilder.java:166-167,177-180,254-269`；`backend/src/main/java/com/maccms/backend/site/application/SitePaths.java:23-51` |
| 由此产生的生成失败（第 3 版**实际执行**现有类得到；编译当前 worktree 源码的临时探针，未提交）：四种 URL 形态下 `wd` 或 `area` 为 `A/B`、`A\B` 均抛 `IllegalArgumentException`；`area` 为 `.`、`..` 在任何形态都失败（`wd` 在末位时 `wd/..html` 通过）；`suffixHide=true` 时 `.`、`..`、`A.html` 失败，`a.htmlb` **不报错但被改写**成 `wd/a/b/`（回读后值与键都错位） | 探针输出摘要见第 7.2.1 节 |
| 按 schema 上限组合（`wd` 64、`area` 20、`lang` 10、`class` 32 个 3 字节汉字）调用 `vodSearch`，路径长度：默认 1182、`suffixHide` 1178、`rewrite` 1172、两者都开 1168（加首个 `/` 再 +1）；再加 `year/level/by/order` 与 `page=1000` 为 1235/1231/1225/1221。均超过 1024，解析器直接返回 empty | 同上（探针实测）；队长复核给出的 1189 与本节口径不同（是否含前缀），结论一致：超过 1024 |
| ThinkPHP 只要查询串中有 `s` 参数，就用它**覆盖** PATH_INFO（与请求路径无关）；路径末尾的 `.html` 等后缀在路由前去掉 | 【代码】`thinkphp/library/think/Request.php:403-406,436-443`；`application/config.php:80,86` |

#### 1.6 表结构与索引（`mac_vod`）

| 事实 | 依据 |
|---|---|
| `ENGINE=MyISAM DEFAULT CHARSET=utf8`（未指定排序规则，即 `utf8_general_ci`：大小写不敏感、尾随空格不敏感、存不下 U+FFFF 以上字符） | 【代码】`application/install/sql/install.sql:685,803` |
| 相关列：`vod_name/vod_sub/vod_en varchar(255)`、`vod_status tinyint`、`vod_class varchar(255)`、`vod_area varchar(20)`、`vod_lang varchar(10)`、`vod_year varchar(10)`、`vod_level tinyint`；`vod_content`、`vod_play_url` 为 mediumtext | 【代码】`application/install/sql/install.sql:690-693,697,713-715,725,756,760` |
| 单列 B-tree 索引：`type_id`、`vod_level`、`vod_name`、`vod_year`、`vod_area`、`vod_lang`、`vod_class`、`vod_en`、`vod_time` 等；**`vod_status` 与 `vod_sub` 没有索引**；没有复合索引、没有 FULLTEXT | 【代码】`application/install/sql/install.sql:768-802` |
| 线上是否改过表结构/索引、数据量与数据分布 | **【待核实】**（本任务不连线上库） |

#### 1.7 现有 Java 内容服务（【已验收】，本批要保持兼容）

| 事实 | 依据 |
|---|---|
| `GET /api/v1/videos` 参数 `categoryId/sort/order/page/pageSize` 以字符串接收，由 `VideoListQuery.parse` 统一校验（page 1..1000、pageSize 1..100、categoryId 1..65535）；**只有空字符串按未传，不做 trim**（`" 2"` → 400）；整数只接受 ASCII 数字、最多 10 位，允许前导 0；出错时只报第一个参数 | `backend/src/main/java/com/maccms/backend/content/api/VideoController.java:27-34`；`backend/src/main/java/com/maccms/backend/content/application/VideoListQuery.java:30-45,51-62`（`blank` 51-53、`parseInt` 55-62） |
| 可见性：每次查询先构建分类目录（启用、游客列表权限、上级可见），`categoryId` 不可见 → 404；列表条件为 `vod_status=1 AND type_id IN (可见分类)` | `backend/src/main/java/com/maccms/backend/content/application/ContentReadService.java:88-110`；`backend/src/main/resources/mapper/content/VodMapper.xml:11-15` |
| count 与分页共用 `publishedInTypes` 片段；排序列由枚举 + `<choose>` 生成，追加 `vod_id` 同向排序；`total=0` 或 offset 越界时不执行分页语句 | `backend/src/main/resources/mapper/content/VodMapper.xml:57-90`；`backend/src/main/java/com/maccms/backend/content/application/ContentReadService.java:102-105` |
| 连接池只读（`hikari.read-only: true`），池大小默认 10；MyBatis 全局语句超时 10 秒 | `backend/src/main/resources/application.yml:11-13,28` |
| 数据库连接类故障在 `ContentReadService.read` 统一转为 503 `SERVICE_UNAVAILABLE`，日志只有类型链 | `backend/src/main/java/com/maccms/backend/content/application/ContentReadService.java:79-86`；`docs/architecture/08-site-pages.md` 第 5 节 |
| 契约声明"未声明的查询参数会被忽略；值为空字符串的参数按未传处理" | `docs/contracts/content-read.openapi.yaml:18` |
| 首页推荐位因契约"不能按 level 筛选"只能按 level 排序近似 | `docs/architecture/03-frontend-readonly-plan.md` 第 3.2 节；`docs/architecture/08-site-pages.md` 第 8 节 |

### 2. 实现路径比较

#### 2.1 候选

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A（推荐）** 扩展既有列表 | `GET /api/v1/videos` 增加可选参数；`VideoListQuery` 增加一个已校验的筛选值对象；Mapper 在共享谓词里用 `<if>` 追加条件 | 一个 DTO、一套可见性/排序/分页/错误；SSR 搜索页只是调用同一个 `listVideos`；首页推荐位可直接用 `level` 筛选；改动面最小 | 契约 1.0.0 "未声明参数被忽略"的调用方会感知到新参数生效（第 2.3 节）；需要用测试证明无参数时 SQL 不变 |
| B 独立搜索接口 | 新增 `GET /api/v1/video-search`（避免与 `/api/v1/videos/{id}` 冲突），只承接关键字，筛选另加在列表上 | 可以给搜索单独的上限、缓存头和错误语义 | 两个接口返回同一 `VideoPage`，可见性/排序/分页逻辑要么复制要么抽取；前端要按"有无关键字"选择接口；多一条正式路径要长期维护 |
| C 外部搜索引擎或全文索引 | Elasticsearch/Meilisearch/OpenSearch，或 MySQL FULLTEXT（ngram） | 包含匹配不再全表扫描，可做分词、相关度、高亮 | 需要新服务或 DDL（FULLTEXT 索引）、数据同步与写入方（与"新系统只读、单一写入方"冲突），超出本批授权 |

#### 2.2 推荐与暂缓

推荐 **A**。外部搜索引擎/FULLTEXT（C）**暂缓**，只有在第 6.5 节的合成库测量证明包含匹配在目标数据量下不可接受、且用户批准 DDL 或新服务后才另立任务；本方案不默认需要任何 DDL、新依赖、新表、缓存写入或外部服务。

#### 2.3 不加参数时的兼容保证

| 方面 | 保证 | 验收方法（实施任务） |
|---|---|---|
| SQL | 新条件全部包在 `<if test="filter.xxx != null">` 中，不传时生成的 SQL 文本与现状**逐字相同**（含不加 `MAX_EXECUTION_TIME` 提示） | MyBatis `BoundSql` 黄金测试：无新参数时 count/page 两条语句与提交前的文本相同 |
| 参数校验与错误 | `categoryId/sort/order/page/pageSize` 的取值、默认值、错误字段与文案、校验顺序不变；新参数在既有参数之后校验。重复的旧参数维持现状（由 Spring 合并成逗号值后按原规则判错，**实施时以测试确认并锁定**） | 既有 `backend/src/test/java/com/maccms/backend/content/ContentApiContractTest.java` 全部不改且通过 |
| 响应 | schema 不变（草案中逐字复制），`ErrorResponse.code` 不新增取值 | `backend/src/test/java/com/maccms/backend/contract/OpenApiContract.java` 字段集合比对；草案与契约 schema 逐键相等（本任务已检查，见交付评论） |
| 排序/分页 | 带不带条件都按"排序列 + `vod_id` 同向"；`page>totalPages` 仍是 200 空列表 | 契约测试 + 真实 MySQL 集成测试 |
| 503 | 新增的 503 原因只发生在带新参数的请求上（语句时限：任一新参数；并发上限：`keyword`/`classes`），响应体与数据库不可用时逐字相同 | 契约测试断言响应体 |
| 已知的语义变化 | 1.0.0 下被忽略的 `keyword=…` 等将开始生效。旧站参数名（`wd`、`tid`、`by`、`class`）继续被忽略，**不做别名**，避免"看起来支持、实际规则不同" | 契约测试：带 `wd=x` 的响应与不带时相同 |

### 3. 第一批参数范围

| 参数（API） | 旧参数 | 首批 | 匹配 | 说明 |
|---|---|---|---|---|
| `categoryId` | 标签 `type` / URL `tid` | ✅ 已有 | 本身 + 可见直接子分类 | 语义沿用 1.0.0；SSR 首批不认 `tid`（第 7.2 节） |
| `keyword` | `wd` | ✅（开关控制） | `vod_name`/`vod_sub`/`vod_en` 任一包含 | 字段集合见 D1；半角逗号按字面匹配（与旧 `wd` 一致） |
| `area` | `area` | ✅ | `vod_area =` 单值 | |
| `lang` | `lang` | ✅ | `vod_lang =` 单值 | |
| `year` | `year` | ✅ | `vod_year =` 4 位单值 | 区间暂缓 |
| `level` | `level` | ✅ | `vod_level =` 1..9 | 也解决首页推荐位只能排序近似的问题（`docs/architecture/03-frontend-readonly-plan.md` 第 3.2 节） |
| `classes` | `class` | ✅（开关控制） | `vod_class` 包含 | 题目中的"类型"；与响应字段同名 |
| `sort`/`order` | `by`/`order` | ✅ 已有 11 种 | — | `rnd` 暂缓（旧实现是随机页/随机 id 段，`application/common/model/Vod.php:502-537`） |
| `page`/`pageSize` | `page` | ✅ 已有 | — | 上限不变 |
| 暂缓 | `actor`、`director`、`tag`、`name`、`letter`、`state`、`version`、`isend`、`ids`、`timeadd` 等、年份区间、任何逗号多值 | ❌ | — | 演员/导演/标签都是包含匹配，成本与 `keyword` 同级，待第 6.5 节测量后再评估；`letter/state/version` 是索引列精确匹配，可在下一批低成本补上 |

### 4. 参数规则（明确决策）

适用于 6 个新参数；已有 5 个参数的规则、顺序与文案不变。草案 `info.description` 与各参数描述是同一套规则。

#### 4.1 校验对象：原始解码值，不裁剪

- **API 层不做任何裁剪或改写**。校验与匹配的对象都是"原始解码值"：Servlet 容器按 `application/x-www-form-urlencoded` 解码**一次**后得到的字符串（`+` 为空格）。这与 1.0.0 既有参数一致（`backend/src/main/java/com/maccms/backend/content/application/VideoListQuery.java:51-53` 只把 `""` 当未传，不 trim）。
- **草案中每个新参数的 schema（`maxLength`、`pattern`、`minimum`/`maximum`）都直接作用于原始解码值**，没有"先规范化再校验"的隐含步骤。5 个字符串参数的 schema 与运行规则一一对应；`level` 是 `integer`，其 `minimum`/`maximum` 只覆盖数值范围，"只含 ASCII 数字、最多 10 个字符"是 schema 之外的原始字符串额外约束（第 4.3 节 8a，草案以描述和 `x-maccms-raw-pattern` 写明）。
- 唯一例外沿用 1.0.0 通用约定：**原始解码值为空字符串 `""` 时按未传处理，不做 schema 校验**。字符串参数的 `pattern` 本身就接受 `""`；`level` 是 `integer`，OpenAPI 3.0 无法在 integer schema 中表达这一点，与 1.0.0 的 `categoryId/page/pageSize` 相同，由 `info.description` 的通用约定覆盖。
- 去掉首尾空白属于**用户输入体验**，放在 SSR 层（第 7.2 节，与旧站 `trim` 对应）；API 收到首尾带空白的值直接 400，不猜测调用方意图。

#### 4.2 字符集合定义

| 名称 | 定义 |
|---|---|
| 禁用字符 | U+0000–U+001F、U+007F–U+009F（C0/C1 控制字符）；U+D800–U+DFFF（Java 字符串中的代理项，即 U+FFFF 以上字符或非法 UTF-8 的残留）；U+FFFD（替换字符，与"非法 UTF-8 被容器替换"无法区分，统一拒绝）。U+FFFF 以上字符在旧表 utf8 列中不可能存在，拒绝也避免 utf8/utf8mb4 比较时的排序规则转换问题 |
| 空白字符 W | 与 ECMAScript 正则 `\s` 相同：U+0009–U+000D、U+0020、U+00A0、U+1680、U+2000–U+200A、U+2028、U+2029、U+202F、U+205F、U+3000、U+FEFF。实现时用显式集合，**不得**用 `String.trim()`、`String.strip()` 或 `Character.isWhitespace`（三者集合都与此不同） |
| 长度 | 按 Unicode 码点计。因代理项已被禁用，码点数等于 Java `String.length()`，也等于 JSON Schema `maxLength` 的计数 |

#### 4.3 校验顺序与错误（每个新参数，固定顺序；只报第一个错误）

参数间顺序：既有 5 个参数（不变）→ `keyword` → `area` → `lang` → `year` → `level` → `classes`。`fieldErrors` 只含第一个出错的参数，`message` 为下表固定文案，不回显输入。

| 步骤 | 适用参数 | 条件 | 结果（`field` = 参数名） |
|---|---|---|---|
| 1 | 全部 | 同名参数出现 ≥2 次（含 `area=&area=`）。实现用 `getParameterValues` 显式判断，不依赖 Spring 的逗号合并 | 400 `参数不能重复` |
| 2 | 全部 | 原始解码值为 `""` | 按未传，跳过后续步骤 |
| 3 | `keyword`、`classes` | `maccms.content.search.enabled=false` | 400 `当前未开放关键字搜索` |
| 4 | `keyword`、`area`、`lang`、`classes` | 含禁用字符；或查询串中该参数的百分号编码不是合法 UTF-8（见下注） | 400 `含不允许的字符` |
| 5 | `area`、`lang`、`classes` | 含半角逗号 `,`（全角 `，` 是普通字符） | 400 `不支持多个取值` |
| 6 | `keyword`、`area`、`lang`、`classes` | 第一个或最后一个字符属于 W（因此"仅空白"也在此报错） | 400 `首尾不能有空白字符` |
| 7 | `keyword`（64）、`area`（20）、`lang`（10）、`classes`（32） | 码点数 > 上限 | 400 `长度不能超过 N 个字符` |
| 8 | `year` | 不匹配 `^[0-9]{4}$`（ASCII 数字；空白、逗号、区间、全角数字都不匹配） | 400 `必须是 4 位数字年份` |
| 8a | `level` | **词法**（原始字符串额外约束）：原始解码值含 ASCII 数字以外的字符，或长度 > 10 个字符 | 400 `必须是 1 到 9 之间的整数` |
| 8b | `level` | **数值**：通过 8a 后按十进制取值，不在 1..9 内 | 400 `必须是 1 到 9 之间的整数` |

`level` 的 8a/8b 就是既有 `VideoListQuery.parseInt(field, raw, 1, 9)`（`backend/src/main/java/com/maccms/backend/content/application/VideoListQuery.java:55-62`：先查 `raw.length() > 10` 或含非数字，再查范围），与 1.0.0 的 `categoryId/page/pageSize` 同一实现，因此允许前导 0。草案中 `level` 仍是 `type: integer, minimum: 1, maximum: 9`，与既有整数参数一致，但**`minimum`/`maximum` 只约束数值，不表达 8a 的词法长度**；8a 作为"原始字符串额外约束"写在草案参数描述中，并用扩展字段 `x-maccms-raw-pattern: '^(?:0{0,9}[1-9])?$'` 给出与 2（空串）+ 8a + 8b 完全等价的原始字符串正则（最多 9 个前导 0 加一个 1–9 数字，总长 ≤ 10；空串 = 未传）。示例（第 3 版用现有 `parseInt` 实际执行，见第 7.2.1 节）：

| 原始解码值 | 长度 | 结果 |
|---|---|---|
| `9`、`09`、`0000000009` | 1、2、10 | 9 |
| `00000000009` | 11 | 400 `必须是 1 到 9 之间的整数`（数值为 9，但 8a 长度超限，在 8b 之前失败） |
| `0`、`10` | 1、2 | 400（8b） |
| `+9`、` 9`、`9.0` | 2、2、3 | 400（8a） |

1.0.0 的 `categoryId/page/pageSize` 也有同样的 10 位词法限制，但 1.0.0 契约未写明；草案按"逐字沿用"不改这三个参数的描述，只在 `info.description` 的通用约定中注明。T1 合并正式契约时也只在通用约定中补充，不改它们的 schema。

注：非法 UTF-8 百分号编码（如 `keyword=%FF`）的**验收用例固定为 400 `field=keyword`**。容器对它的处理（拒绝整个查询串、丢弃该参数或替换为 U+FFFD）实施时须先实测；若容器丢弃参数，Controller 须从原始查询串检测该参数名的存在，不得把它当成"未传"。

#### 4.4 输入→结果对照（与草案 `info.description` 同表）

| 原始解码值 | `keyword` | `area` / `lang` / `classes` | `year` | `level` |
|---|---|---|---|---|
| `""` | 未传 | 未传 | 未传 | 未传 |
| `"   "`（仅空白） | 400 首尾空白 | 400 首尾空白 | 400 年份格式 | 400 整数 |
| `" 2020"` / `"2020 "` | 400 首尾空白 | 400 首尾空白 | 400 年份格式 | 400 整数 |
| `"a b"`（中间空白） | 原样匹配 | 原样匹配 | 400 年份格式 | 400 整数 |
| `"a,b"` | 字面匹配 `a,b` | 400 多值 | 400 年份格式 | 400 整数 |
| 超长（如 `keyword` 65 码点、`area` 21 码点） | 400 长度 | 400 长度 | 400 年份格式 | 400 整数 |
| `"2020-2022"` | 原样匹配 | 原样匹配 | 400 年份格式 | 400 整数 |
| `"09"` | 原样匹配 | 原样匹配 | 400 年份格式 | 9 |
| `"0000000009"`（10 位） | 原样匹配 | 原样匹配（`lang` 恰好 10 个码点，未超限） | 400 年份格式 | 9 |
| `"00000000009"`（11 位） | 原样匹配 | `area`/`classes` 原样匹配；`lang` 400 长度 | 400 年份格式 | 400 整数（词法长度） |
| `"0"` | 原样匹配 | 字面值 `0` | 400 年份格式 | 400 整数 |
| `%FF`（非法 UTF-8） | 400 字符 | 400 字符 | 400 年份格式 | 400 整数 |
| 开关关闭时非空值 | 400 未开放 | `classes`：400 未开放；`area`/`lang` 不受影响 | 不受影响 | 不受影响 |

#### 4.5 其他规则

| 规则 | 决策 | 与旧版 |
|---|---|---|
| 未知参数 | API 继续忽略（契约 1.0.0）；SSR 规则见第 7.2 节 | — |
| 多值 | 首批不支持。`area/lang/classes` 含半角逗号 → 400；`keyword` 中的逗号是字面字符 | 旧版 `area/lang/class` 逗号即多值（精确字段 IN、包含字段 OR）；`wd` 不拆分 |
| 大小写/全半角/规范化 | 不做任何转换；大小写、尾随空格由列排序规则 `utf8_general_ci` 决定（不敏感）；全角/半角、繁简、NFC/NFD 不归一 | 与旧版一致（同样依赖列排序规则） |
| 解码次数 | 容器解码**一次**；不再 `urldecode` | 旧版二次解码，`+` 变空格 |
| 实体 | 输入中的实体**不解码**（`&amp;` 就按 5 个字符匹配）。`keyword` 含 `& " ' < >` 时，另生成 `mac_filter_xss` 形式（`&amp; &quot; &#039; &lt; &gt;`，`application/common.php:1222-1225`）各匹配一次，两组为 OR；不含这些字符时只匹配原值。`classes` 不做实体变体 | 旧版只用 `htmlspecialchars` 后的形式匹配（且 `'` 是否编码随 PHP 版本变），库内原样存的 `Tom & Jerry` 搜不到。库内各字段实际存储形式**【待核实】**（`docs/architecture/07-rich-text-filter.md` 第 4.1 节） |
| LIKE 字面语义 | 包含匹配一律 `col LIKE ? ESCAPE '!'`，参数在 Java 中构造为 `'%' + esc(v) + '%'`，`esc` 依次把 `!`、`%`、`_` 前加 `!`。反斜线不是转义符，按普通字符匹配（不受 `NO_BACKSLASH_ESCAPES` 影响） | 旧版不转义：`%`、`_` 是通配符 |
| 精确匹配 | `col = ?`；`year` 以字符串比较（`'2020'`） | 旧版 `year` 以整数比较，`'2020年'`、`' 2020'` 等也会被数值转换命中 |
| 字段白名单 | 列名只出现在 Mapper XML 中，与参数一一固定；不接受任何用户提供的列名、排序表达式或 SQL 片段 | 旧版 `search_vod_rule` 由后台配置拼进 where 键名 |
| 组合 | 所有条件 AND；`keyword` 内部各列/各变体 OR；与 `categoryId` 组合时先做分类可见性检查（不可见 → 404，不管其他条件）。参数错误（400）先于可见性检查（与 1.0.0 先 `parse` 后查库一致） | 与旧版一致（旧版条件也是 AND） |
| 开关 | `maccms.content.search.enabled=false`（默认）时见 4.3 步骤 3；精确匹配参数不受影响 | 对应旧 `app.search`，但默认关闭（第 6.4 节） |

### 5. 接口草案摘要

完整定义见草案。路径与方法：`GET /api/v1/videos`（扩展），无新路径。

| 状态 | code | 何时 | 是否新增情形 |
|---|---|---|---|
| 200 | — | 成功；无结果时 `items=[]`、`total=0`（不是 404） | 否 |
| 400 | `INVALID_PARAMETER` | 已有参数非法（不变）；新参数按第 4.3 节任一步骤失败 | 新参数带来的新情形 |
| 404 | `CATEGORY_NOT_FOUND` | `categoryId` 不存在或不可见 | 否 |
| 503 | `SERVICE_UNAVAILABLE` | 数据库不可用（不变）；带任一新参数时语句超过服务端时限；带 `keyword`/`classes` 时并发达到上限 | 后两种为新增原因，响应体不变 |
| 500 | `INTERNAL_ERROR` | 其他异常 | 否 |

- 字段安全：响应只用既有 `VideoSummary`，不新增播放/下载地址、密码、会员身份、授权字段；`detailPasswordRequired` 仍只是布尔值。
- 权限：游客身份不变；分类可见性、游客列表权限（`maccms.content.enforce-guest-popedom`）沿用 `backend/src/main/java/com/maccms/backend/content/application/CategoryCatalog.java`。
- 排序稳定：所有组合都追加 `vod_id` 同向排序；同一组条件下 count 与分页使用同一 SQL 片段。
- 错误 `message` 为固定文案，不回显用户输入；日志不记录参数值。

### 6. SQL 安全与性能

#### 6.1 共享谓词（拟实施，写在 `backend/src/main/resources/mapper/content/VodMapper.xml`）

```xml
<!-- 现有 publishedInTypes 扩展为可选条件；filter 为 null 或各字段为 null 时生成的 SQL 与现状逐字相同 -->
<sql id="publishedInTypes">
    vod_status = 1
    AND type_id IN <foreach collection="typeIds" item="tid" open="(" separator="," close=")">#{tid}</foreach>
    <if test="filter != null">
        <if test="filter.level != null">AND vod_level = #{filter.level}</if>
        <if test="filter.year != null">AND vod_year = #{filter.year}</if>
        <if test="filter.area != null">AND vod_area = #{filter.area}</if>
        <if test="filter.lang != null">AND vod_lang = #{filter.lang}</if>
        <if test="filter.classesPattern != null">AND vod_class LIKE #{filter.classesPattern} ESCAPE '!'</if>
        <if test="filter.keywordPatterns != null">
            AND (<foreach collection="filter.keywordPatterns" item="p" separator=" OR ">
                vod_name LIKE #{p} ESCAPE '!' OR vod_sub LIKE #{p} ESCAPE '!' OR vod_en LIKE #{p} ESCAPE '!'
            </foreach>)
        </if>
    </if>
</sql>
```

- count（`countPublished`）与分页（`selectPublishedPage`）都 `<include>` 这一片段，不另写一份；两条语句在**有任一新条件**时于 `SELECT` 后加固定提示 `/*+ MAX_EXECUTION_TIME(3000) */`（字面常量，不用 `${}`；无新条件时不加，保证 SQL 不变）。
- 只用 `#{}` 绑定；`keywordPatterns`（1 或 2 项）与 `classesPattern` 在 `content.application` 里由已校验的值构造，Mapper 不做字符串处理。
- 游客分类范围：`typeIds` 仍由 `CategoryCatalog` 给出（全部可见分类，或 `categoryId` 及其可见直接子分类），新条件只能在其内收窄。
- 稳定分页：`ORDER BY <白名单列> <方向>, vod_id <同方向>`，`LIMIT #{offset}, #{limit}`，offset ≤ 999×100。
- 新 application 类型（建议名 `VideoFilter`，不可变 record，构造器与 `parse` 同一套校验）放在 `backend/src/main/java/com/maccms/backend/content/application/`，不放进 `view` 子包（它不是响应类型）；`VideoListQuery` 增加该字段并保留现有 5 参数构造器（`filter=VideoFilter.NONE`），site 等调用方不必改动。

#### 6.2 现有结构的成本（定性分析，未测量）

| 查询形态 | 预期执行方式 | 风险 |
|---|---|---|
| 仅 `categoryId`/排序（现状） | `type_id` 索引或按排序列索引回表 | 不变 |
| `area`/`lang`/`year`/`level` 精确匹配 | 优化器可在 `type_id`、`vod_area` 等单列索引间选一个，或 index_merge；结果再 filesort | 低基数列（如 `vod_lang='国语'`）可能退化为扫描；JDBC 参数为 utf8mb4、列为 utf8 时 MySQL 5.7 能否用索引**须 EXPLAIN 确认** |
| `keyword`/`classes` 包含匹配 | `'%…%'` 不能用 B-tree，`vod_sub` 也无索引 → **扫描所有 `vod_status=1` 的候选行**；按排序列索引倒序走时，命中少的关键字可能把整个索引走完（MyISAM 随机回表比顺序扫描更慢） | 主要成本来源 |
| COUNT | 带 WHERE 时 MyISAM 不能用表行数缓存，与分页查询各扫一次（`total>0` 时每次请求 ≈ 2 次扫描） | 成本翻倍 |
| 深分页 | offset 越大，需要跳过的命中行越多；最坏仍受"扫描全部候选行"约束 | 已有上限 page≤1000 |
| **MyISAM 表锁** | 长 SELECT 持有表读锁；PHP 的点击计数 `UPDATE mac_vod`（每次详情页 `ajax/hits`，`docs/architecture/03-frontend-readonly-plan.md` 第 6.3 节）、采集、后台写入要等它结束；排队的写锁又会挡住后续所有读 → 全站读写堆积 | **最大风险**，必须测量（6.5） |
| 行格式 | `mac_vod` 含 `vod_content`、`vod_play_url` 等 mediumtext（`application/install/sql/install.sql:756,760`），动态行格式下扫描要读整行 | 扫描成本与正文/播放地址体积成正比，合成数据须模拟体积 |

#### 6.3 成本控制（不需要 DDL、依赖或外部服务）

1. **服务端语句时限**：带新条件的 count/page 语句加 `MAX_EXECUTION_TIME(3000)`（MySQL 5.7.8+ 只对 SELECT 生效）。超时后 MySQL 报错 3024；实施时须在真实 MySQL 上确认 Connector/J/Spring 的异常类型能被 `ContentReadService.read`（`backend/src/main/java/com/maccms/backend/content/application/ContentReadService.java:79-86`）归为 503（若翻译结果不是 `TransientDataAccessException` 子类，就在 `read` 中按类型链补一条映射，并补日志脱敏测试）。既有的 MyBatis 全局 10 秒 JDBC 超时保留为兜底。MariaDB 或 5.7.8 以下会忽略该提示（线上版本**【待核实】**）。
2. **并发上限**：带 `keyword`/`classes` 的请求在 `ContentReadService` 内经 `Semaphore.tryAcquire()`（不等待）进入，默认 4（`maccms.content.search.max-concurrent`，1..连接池大小−1，启动校验），拿不到 → 503。用意是给连接池（默认 10）中其他查询留出连接，并限制同时持有表读锁的长查询数量。
3. **默认关闭**：`maccms.content.search.enabled=false`，第 6.5 节测量完成并经队长确认前不开启。
4. **超限措施（按顺序考虑）**：降低时限或并发上限 → 要求 `keyword` 至少 2 个码点 → 只上线精确筛选、关键字继续留在 PHP → 改为前缀匹配（可用 `vod_name` 索引，但改变语义，需用户决定）→ FULLTEXT/外部引擎（需 DDL 或新服务批准）。
5. **不做**：应用内结果缓存（会产生缓存失效与内存问题，且 PHP 写入后无法通知）、对 `mac_vod_search` 的任何读写、计数截断（会改变 `total` 语义，需要契约变化）。

#### 6.4 配置来源

| 配置项 / 环境变量 | 默认 | 说明 |
|---|---|---|
| `maccms.content.search.enabled` / `MACCMS_CONTENT_SEARCH_ENABLED` | `false` | 控制 API 的 `keyword`、`classes`；对应旧 `app.search` 但不读取 PHP 配置 |
| `maccms.content.search.max-concurrent` / `MACCMS_CONTENT_SEARCH_MAX_CONCURRENT` | `4` | 包含匹配并发上限；非法值启动失败，只列配置项名、不回显取值 |
| `maccms.site.search.enabled` / `MACCMS_SITE_SEARCH_ENABLED` | `false` | 是否注册 SSR 搜索页路由（第 7 节）；需同时开启 `maccms.site.enabled` |

Java 只从本应用环境变量读取这些值，**不读取** `application/extra/maccms.php` 或任何 PHP 配置；`search_len`、`search_vod_rule`、`filter_words`、`wall_filter`、`vod_search_optimise` 不映射为配置。

#### 6.5 一次性合成 MySQL 库验证方法（T2 执行，本任务不执行）

| 项 | 方法 |
|---|---|
| 库 | 由管理账号新建 `maccms_perf_<yyyyMMddHHmmss>_<8位随机>`，导入 `application/install/sql/install.sql` 表结构（MyISAM/utf8，与旧系统一致），结束后只删除该库。**不读写 `maccms_dev` 或任何已有库**，不改服务器全局变量 |
| 规模 | `mac_vod` 三档：10 万、50 万、100 万行；`mac_type` 30 个分类（含停用、游客无权限的分类）；`mac_group` 游客组按 `application/install/sql/initdata.sql` 形式 |
| 分布 | `vod_status=1` 约 95%；名称由合成中文词库组合（含少量英文、`%`/`_`/`!`/`\`/`&`/`,` 等特殊字符样本）；`vod_area/lang/year` 取 `application/extra/maccms.php:84-86` 列表并偏斜分布；`vod_level` 约 98% 为 0；`vod_class` 1–3 个逗号分隔的值；`vod_content` 平均约 2 KB、`vod_play_url` 平均约 1 KB（模拟动态行体积）。生成脚本与种子提交到测试目录，数据不提交 |
| 查询形态 | 对每档规模、分别 `sort=time desc` 与 `sort=hits desc`：无条件（基线）、单精确条件（area/lang/year/level 各一）、精确组合、高频关键字（命中约 10%）、低频关键字（命中 < 0.01%）、无命中关键字、`classes`、关键字 + 分类、第 1/50/1000 页 |
| 记录 | 每个形态：`EXPLAIN` 全部列（type、key、rows、filtered、Extra）；冷/热各 N 次（冷启动用 `FLUSH TABLES`，仅限该一次性实例的本机 root）的 p50/p95/最大耗时；`SHOW SESSION STATUS` 的 `Handler_read_*`、`Created_tmp_disk_tables`、`Sort_merge_passes` 增量；Java 进程的 CPU 与堆（JFR 或 `jcmd`）；机器 CPU/内存/磁盘型号与 MySQL 版本 |
| 表锁实验 | 一边以 1/4/8 个并发运行低频关键字搜索，一边以固定速率执行 `UPDATE mac_vod SET vod_hits=vod_hits+1 WHERE vod_id=?`（只在该一次性库），记录 UPDATE 延迟分布、`Table_locks_waited`/`Table_locks_immediate` 增量与读请求堆积情况；验证 `MAX_EXECUTION_TIME` 与并发上限生效（503 计数） |
| 目标（非断言） | 参考目标：50 万行时单次带关键字请求（count+page）热态 p95 < 1 s，并发 4 时 UPDATE 等待 p95 < 1 s。**这是决定是否开启的参考，不写成自动测试断言，也不对外宣称吞吐或延迟** |
| 超限 | 任何形态超过 3 s 或出现明显锁堆积 → 按 6.3 第 4 条逐项处理，并在交付评论中给出数据与建议，由队长决定 |
| 产出 | 一份测量报告（规模、命令、原始数据、EXPLAIN、结论），只读证据；测量脚本不进 `verify` |

### 7. 前台对接方案（SSR 搜索页）

#### 7.1 路由与开关

- 只在 `maccms.site.enabled=true` **且** `maccms.site.search.enabled=true` 时注册：`/index.php/vod/search`、`/index.php/vod/search.html`、`/index.php/vod/search/`；`maccms.site.url.rewrite=true` 时另加 `/vod/search`、`/vod/search.html`、`/vod/search/`。只有这几个精确路径，不注册 `/vod/search/**`（路径参数形式归 PHP，第 7.2.3 节）。GET/HEAD；其他方法维持全局 405。
- 页面调用 `ContentReadService.listVideos`（不新增 site 侧 SQL/Mapper），因此 SSR 的关键字/扩展分类同时依赖 `maccms.content.search.enabled`；后者关闭而请求带非空 `wd`/`class` 时，content 返回 400 → 页面按 7.4 显示 400。
- 表单地址**不变**：外壳搜索框一直提交到旧地址 `/index.php/vod/search.html?wd=…`（`backend/src/main/java/com/maccms/backend/site/web/SitePages.java:32`）。所谓"改指向 Java"不是改模板，而是该路径由谁处理：本地开启 `maccms.site.search.enabled` 后由 Java 处理；部署时由 nginx 决定（**本批不做 nginx 分流、不 301、不实现交还**）。开关关闭时该路径在 Java 中不存在（本地 404），与现状一致；本地 404/503 不代表已部署的降级行为。

#### 7.2 搜索页 URL：链接编码、解析与长度（拟实施；第 3 版重写）

##### 7.2.1 现状：PATHINFO 无法承载所有可接受的值

【代码】现有 `LegacyUrlBuilder.vodSearch` 把参数写进路径段，输出必须通过 `SitePaths`（第 1.5 节）。第 3 版用临时探针**实际执行**了现有类：在仓库外的临时目录里用 `javac` 编译当前 worktree 的 `LegacyUrlBuilder`、`LegacyUrlParser`、`SitePaths`、`VideoListQuery` 等源码和一个调用它们的 `main` 类，四种 URL 形态各运行一遍；探针未提交，运行后已删除。结果摘要：

| 输入（`vodSearch(filters, 1)`） | 默认 | `suffixHide` | `rewrite` | 两者都开 |
|---|---|---|---|---|
| `wd` 或 `area` 为 `A/B`、`A\B` | 异常 | 异常 | 异常 | 异常 |
| `area` 为 `.`、`..`（后面还有 `wd`） | 异常 | 异常 | 异常 | 异常 |
| `wd=.`（末位） | `index.php/vod/search/wd/..html` | 异常 | `vod/search/wd/..html` | 异常 |
| `wd=A.html` | `index.php/vod/search/wd/A.html.html` | 异常 | `vod/search/wd/A.html.html` | 异常 |
| `wd=a.htmlb` | `index.php/vod/search/wd/a.htmlb.html` | `index.php/vod/search/wd/a/b/`（**被改写**） | `vod/search/wd/a.htmlb.html` | `vod/search/wd/a/b/`（**被改写**） |
| `wd=%41`（字面百分号） | `index.php/vod/search/wd/%2541.html` | `…/wd/%2541/` | `vod/search/wd/%2541.html` | `…/wd/%2541/` |
| 四项上限组合（`wd` 64、`area` 20、`lang` 10、`class` 32 个汉字）路径长度 | 1182 | 1178 | 1172 | 1168 |
| 同上再加 `year/level/by/order` 与 `page=1000` | 1235 | 1231 | 1225 | 1221 |
| 把四项上限组合的路径交给现有 `parse` | empty（> 1024） | empty | empty | empty |
| `vodSearch(Map.of(), 1)`（搜索基址） | `index.php/vod/search.html` | `index.php/vod/search/` | `vod/search.html` | `vod/search/` |

另外，即使生成成功，路径里的 `.`/`.html` 还会碰上 ThinkPHP 的后缀剥离（`thinkphp/library/think/Request.php:436-443`），回读不唯一。要让 PATHINFO 承载 `/`、`\`、点段，只能在路径里放 `%2F`/`%5C`/`%2E` 或二次编码，这正是 `SitePaths` 出于安全拒绝的，任务也明确不允许放宽。因此本版**不再用 PATHINFO 承载 SSR 搜索参数**。

##### 7.2.2 推荐方案：固定路径 + 查询串

| 项 | 决策 |
|---|---|
| 搜索基址 B | `LegacyUrlBuilder.vodSearch(Map.of(), 1)`（现有方法，**不改**）。site 的 URL 形态固定 `depr='/'`、不启用路由别名（`backend/src/main/java/com/maccms/backend/site/application/SiteUrlProperties.java:29`），所以 B 只有上表最后一行的 4 种取值，都是不含参数的常量，长度 ≤ 25，必然通过 `SitePaths`。B 就是现有外壳搜索表单的 `action`（`backend/src/main/java/com/maccms/backend/site/web/SitePages.java:32`） |
| 链接形态 | `B`（无条件且第 1 页）或 `B?Q`。Java 搜索页生成的**所有**链接（分页、移除筛选、组合筛选、排序、canonical）都是这一形态；不生成路径参数形式 |
| Q 的生成（新方法，建议名 `LegacyUrlBuilder.vodSearchLink(SearchFilters f, int page)`，返回 `SiteLink(path=B, query=Q)`） | ① 键按固定顺序 `area, by, class, lang, level, order, page, wd, year`（字母序，与 `mac_url` 按键排序一致，`application/common.php:1732-1746`）；② 只输出已设置的条件，`page` 只在 > 1 时输出；③ 值用现有 `LegacyUrlBuilder.urlencode`（PHP `urlencode`：字母数字与 `-_.` 原样，空格为 `+`，其余 UTF-8 字节为大写 `%HH`，`backend/src/main/java/com/maccms/backend/site/application/LegacyUrlBuilder.java:254-269`），因此 `/`→`%2F`、`\`→`%5C`、`%`→`%25`、`+`→`%2B`、`&`→`%26`、`=`→`%3D`，`.` 原样；④ `level` 输出规范整数（`09`→`9`），`by`/`order` 输出枚举值，`page` 为无前导 0 的十进制。Q 的字符只可能是 `[A-Za-z0-9._+-]`、`=`、`&` 与大写 `%HH` |
| 输出为 `SafeUrl` | 新增 `SiteSettings.publicPath(SiteLink)` 与 `SiteSettings.canonical(SiteLink)`：路径部分仍走现有 `SitePaths.check`（**规则不变**），查询部分走新的 `SiteQueries.check`：结构为 `键=值(&键=值)*`，键只能是上述 9 个且不重复，值只含 `[A-Za-z0-9._+-]` 与大写 `%HH`，`%HH` 不得是 `%00`–`%1F`、`%7F`。`%2F`、`%5C`、`%2E` **只在查询串中允许**：RFC 3986 第 5.2.4 节的点段消除只作用于路径，Servlet 容器与 nginx 的 location 匹配都不解码查询串，它们不会改变路径结构。整个站内地址 `B?Q` 的长度与 `SitePaths` 共用同一预算 `SitePaths.MAX_LENGTH` = 1280（MACC-17，`6f34d7d` 中 `backend/src/main/java/com/maccms/backend/site/application/SitePaths.java:19`；第 7.2.4 节计算得最大 1235），超出按"站内地址不合法"拒绝，不调整预算、不放宽 `SafeUrl`。这样任意合法 `base-url`（≤ 768）拼出的结果都不超过 2048。最后仍由现有 `SafeUrl` 构造器做总校验（总长 ≤ 2048，`backend/src/main/java/com/maccms/backend/site/application/SafeUrl.java:24`）。`SafeUrl`、`SitePaths` 都不放宽，也不做二次解码 |
| 与旧站的关系 | 旧 `mac_param_url` 把 `$_REQUEST` 合并进参数（`application/common.php:1565`），这些链接在旧站同样是合法的搜索地址；但旧站会再 `urldecode` 一次（第 1.2 节），含 `%` 或 `+` 的值在 PHP 下含义不同（例如 `wd=%2541`：Java 为 `%41`，PHP 为 `A`）。本批不做交还，此差异列入第 9 节与部署评审 |

##### 7.2.3 接受的请求（`LegacyUrlParser.parseSearch(rawPath, rawQuery)`，拟实施）

输入仍是未解码的原始路径与原始查询串，不读取 Host、X-Forwarded-*。结果沿用现有解析器的三类：认得（`Match`）/ 不认识（`Optional.empty()`，交 PHP，本地 404）/ 认得但参数不合法（`ApiException` `INVALID_PARAMETER`，搜索页显示 400，第 7.4 节）。按以下顺序处理：

| 步骤 | 检查 | 结果 |
|---|---|---|
| 1 | 原始路径 ≤ 1024 字符（现有 `MAX_INPUT`，不变）且**恰为** `/index.php/vod/search.html`、`/index.php/vod/search/`、`/index.php/vod/search` 之一；`rewrite=true` 时也接受去掉 `/index.php` 的同样三种。`suffixHide` 不影响接受哪种（与现有解析器对 `.html`、`/`、无后缀的处理一致） | 否则 empty：路径参数形式（`…/vod/search/wd/x.html`）、`vodsearch/…` 别名、`-` 分隔符、大写、`.htm` 等 |
| 2 | 原始查询串 ≤ 2048 字符（新常量 `MAX_SEARCH_QUERY`，只用于搜索；三页仍是 1024 且仍拒绝查询串） | 否则 empty |
| 3 | 按 `&` 切分，跳过空项（`a=1&&b=2`、结尾的 `&`）；每项第一个 `=` 之前为键，之后为值，无 `=` 的项值为 `''`。键**不解码**，须与已知键逐字相同（区分大小写；`w%64`、`WD`、`wd[]` 都不是 `wd`） | — |
| 4 | 任一键为 `s`（ThinkPHP 兼容模式，会覆盖路径，`thinkphp/library/think/Request.php:403-406`），或是旧站支持而首批不支持的键：`actor`、`director`、`tag`、`letter`、`state`、`version`、`tid`、`name`、`ids`、`isend`、`ajax` | empty |
| 5 | 按原始查询串中的出现顺序逐项检查，只报第一个错误：① 键不是 `wd/area/lang/year/level/class/by/order/page`（含 API 参数名 `keyword`、`classes`、`sort`、`pageSize` 与 `utm_source` 等）→ 400 `field=query`、`不支持的参数`（不回显键名）；② 同一键第二次出现（含 `wd=&wd=x`）→ 400 `field=<旧参数名>`、`参数重复`；③ 值按 `application/x-www-form-urlencoded` 解码**一次**（`+` 为空格，`%HH` 大小写十六进制均可）：原始字符不在 0x21–0x7E 内、`%` 后不足两位十六进制、或解码结果不是合法 UTF-8 → 400 `field=<旧参数名>`、`含不允许的字符` | 400 |
| 6 | `by` 的解码值去首尾 W 字符后恰为 `rnd` | empty |
| 7 | 旧站式归一（SSR 独有）：`wd/area/lang/year/level/class/by/order` 的解码值去掉首尾 W 字符（第 4.2 节；对应旧 `trim`，`application/common.php:1582-1594`），结果为 `''` 或 `'0'` 的按未传（对应旧 `!empty()`，`application/common/model/Vod.php:174-240`）。`page`：`''` 按未传；否则须为 1..1000 且无前导 0，否则 400 `field=page`（文案同现有解析器，`backend/src/main/java/com/maccms/backend/site/application/LegacyUrlParser.java:215-218`） | 400 / 继续 |
| 8 | 映射 `wd`→`keyword`、`class`→`classes`、`by`→`sort`，其余同名，交给 content 的同一套校验（第 4 节；`VideoFilter` 与 `VideoListQuery.parse`），SSR 不另写一份。归一后的值不会再触发第 4.3 节步骤 6（首尾空白） | 400 / 认得 |
| 9 | `Match.canonical` = 原始路径等于 `"/" + B` **且** 原始查询串与 `vodSearchLink(归一后的条件, page).query` 逐字相同（无条件第 1 页时须无查询串） | 非规范请求照常渲染，只输出 canonical，不 301 |

新路由类型：`SiteRoute.VodSearch(SearchFilters filters, int page)`，`SearchFilters` 只保存归一并校验后的值（`level` 为整数，`by`/`order` 为枚举）。`LegacyUrlBuilder.path(SiteRoute)` 只返回路径，搜索页的 canonical 改用 `vodSearchLink`。

##### 7.2.4 长度上限与超限处理

| 量 | 上限 | 超限处理 | 依据 |
|---|---|---|---|
| 原始请求路径 | 1024 字符 | 不认识 → 交 PHP（本地 404） | 现有 `MAX_INPUT`，不变；搜索路径本身 ≤ 26 字符 |
| 原始查询串 | 2048 字符 | 不认识 → 交 PHP（本地 404） | 新常量，只用于搜索；高于规范查询串的最大值 1209，为浏览器表单编码差异（如 `*` 不编码、小写十六进制）、SSR 会裁掉的首尾空白和空字段留余量，同时限制解析工作量 |
| 单值解码后、去首尾空白前 | 不单独设限 | — | 受原始查询串 2048 约束 |
| 单值归一后 | `wd` 64、`area` 20、`lang` 10、`class` 32 个码点；`year` 4 位；`level` 原始最多 10 个字符；`page` ≤ 1000 | 400 | 与 API 完全相同（第 4.3 节），由 content 校验 |
| 生成的查询串 Q | **≤ 1209 字符**（计算见下） | 不可能超出；生成方法仍断言，失败抛 `IllegalStateException` → 500，由测试锁定 | — |
| 搜索基址 B | **≤ 25 字符**（`index.php/vod/search.html`；四种形态为 25/21/15/11） | 常量，不会超出 | 第 7.2.1 节最后一行 |
| 生成的站内地址 `B + ? + Q` | **≤ 25 + 1 + 1209 = 1235 字符**，≤ `SitePaths.MAX_LENGTH` 1280 | 不可能超出；超出按站内地址不合法抛异常 → 500，由测试锁定 | MACC-17 预算（第 1.5 节） |
| 完整 `base-url` | **≤ 768 字符**（协议、主机、端口、路径合计，去首尾空白后按 `String.length()` 计；根路径或长子目录都可） | 769 起**启动失败**（现有 `SiteSettings.from`，MACC-17），不会在请求时出现 | `6f34d7d` 中 `backend/src/main/java/com/maccms/backend/site/application/SiteSettings.java:22,94`；768 不是目录路径长度 |
| 生成的 canonical（`base-url + B + ? + Q`） | **≤ 768 + 25 + 1 + 1209 = 2003 字符**，≤ `SafeUrl.MAX_LENGTH` 2048 | 不可能超出（由上两行推出） | `SafeUrl.MAX_LENGTH`（`backend/src/main/java/com/maccms/backend/site/application/SafeUrl.java:24`，不变） |
| 生成的页内链接（`base-url` 的路径部分 + B + `?` + Q） | **< 2003 字符**：路径部分是完整 `base-url` 去掉协议与主机后的部分，必然更短（例如 `https://maccms.local.test/…/` 共 768 时为 743，页内链接最长 1978） | 同上 | 同上 |

第 4 版用同样方式（仓库外临时目录，用 `javac` 编译父任务本地分支 `6f34d7d` 的源码，探针未提交，运行后已删除）**实际执行**了 MACC-17 后的现有类：`SiteSettings.MAX_BASE_URL_LENGTH`=768、`SitePaths.MAX_LENGTH`=1280、`SafeUrl.MAX_LENGTH`=2048；`SiteSettings.from` 接受前后带空格、去空白后总长恰为 768 的子目录 `base-url`（`https://maccms.local.test/aaa…a/`，`href` 长 768，路径部分 743），拒绝 769，错误信息含"最多 768 个字符"且不含取值；四项上限组合用现有 `LegacyUrlBuilder.urlencode` 按键序拼出 Q=1209，四种形态（默认/`suffixHide`/`rewrite`/两者都开）下 `B?Q` 为 1235/1231/1225/1221（都 ≤ 1280，B 都通过 `SitePaths.isValid`），768 字符 `base-url` 的 canonical 为 2003/1999/1993/1989、页内链接为 1978/1974/1968/1964，根地址 `http://maccms.local.test/` 的 canonical 为 1260/1256/1250/1246，全部通过现有 `SafeUrl.isAllowed`。总长 768 的根地址（长主机名、路径为 `/`）由 MACC-17 测试覆盖，按上表算式 canonical 同样 ≤ 2003。

第 3 版探针另外实际验证了（同第 7.2.1 节的临时探针，未提交）：① 16 个样例值（`A/B`、`A\B`、`.`、`..`、`A.html`、`a.htmlb`、`%41`、`%2F`、`C++`、`a b`、`中文`、`Love, Death & Robots`、`a=b&c`、`!%_`、64 个汉字、`~*'()`）经现有 `LegacyUrlBuilder.urlencode` 编码后，再用现有解析器的表单解码（`LegacyUrlParser.formDecode`，`backend/src/main/java/com/maccms/backend/site/application/LegacyUrlParser.java:222-253`）解码一次，全部得到原值，编码结果都只含 `[A-Za-z0-9._+-]` 与大写 `%HH`；② 四种形态下 B 都通过 `SitePaths.isValid`，而 `B?Q` 不通过（`?` 不在允许字符内）——所以查询串需要独立的 `SiteQueries`，不能也不应放宽 `SitePaths`；③ 当时（MACC-17 之前，`base-url` 上限为 200）用总长 200 的子目录 `base-url` 拼出四项上限组合的 canonical，长度为 1435/1431/1425/1421，都能通过现有 `SafeUrl.isAllowed`；该上限已被 768 取代，以上一段第 4 版结果为准。

Q 的最大值计算：第 4.2 节禁用 U+FFFF 以上字符，每个码点 UTF-8 最多 3 字节，编码后最多 9 个字符（`%HH%HH%HH`）。`area=`(5)+180、`&by=hits_month`(14)、`&class=`(7)+288、`&lang=`(6)+90、`&level=9`(8)、`&order=desc`(11)、`&page=1000`(10)、`&wd=`(4)+576、`&year=2020`(10)，合计 **1209**；探针用 `LegacyUrlBuilder.urlencode` 对同一组合拼出的长度也是 1209，在四种形态下加上 B 分别为 1235/1231/1225/1221。三页现有的 1024 上限与"拒绝查询串"不变。

##### 7.2.5 生成与回读

| 链接 | 生成方式（都经 `vodSearchLink`，再经 `publicPath`/`canonical` 成为 `SafeUrl`） |
|---|---|
| 分页 | `vodSearchLink(F, n)` |
| 移除某个筛选 | `vodSearchLink(F 去掉该键, 1)` |
| 组合 / 替换筛选 | `vodSearchLink(F 设置该键为候选值, 1)` |
| 排序 | `vodSearchLink(F 设置 by/order, 1)` |
| canonical | `canonical(vodSearchLink(F, page))` |
| 表单 | `action` = `publicPath(B)`（与现状相同）；当前条件中除 `wd`、`page` 外的值以隐藏字段保留，浏览器提交得到 `B?…`，回到第 7.2.3 节 |

**不变式**：对 SSR 能接受的任意 `(F, page)`，`parseSearch("/" + B, vodSearchLink(F, page).query)` 返回同一 `(F, page)` 且 `canonical=true`。理由：F 中的值都满足第 4 节规则，且不是 `''`、`'0'`，也没有首尾 W 字符（归一的不动点）；对这样的字符串，`urlencode` 后再按表单规则解码一次得到原值（UTF-8 合法，空格↔`+`，`+`↔`%2B`）；查询串不受 `suffixHide` 的 `.html` 替换和路径段规则影响；键固定且不重复；B 在四种形态下都是常量。

为保持这一不变式，**筛选候选值**（取自分类 `extend`）与隐藏字段的值除满足第 4 节外，还必须是归一的不动点：跳过 `''`、`'0'` 和带首尾 W 字符的值（否则生成的链接回读后条件会消失或变化）。

base-url 子目录：与三页相同，反向代理把前缀映射到应用根，解析器输入不含前缀；页内链接 = `base-url` 路径部分 + B + `?Q`，canonical = `base-url` + B + `?Q`，不读 Host/X-Forwarded-*。长度：完整 `base-url` ≤ 768（MACC-17），`B?Q` ≤ 1235，canonical ≤ 2003，页内链接的前缀不长于完整 `base-url`，同样闭环（第 7.2.4 节）。

##### 7.2.6 API 与 SSR 的差异（全部列出）

| 方面 | API `GET /api/v1/videos` | SSR 搜索页 |
|---|---|---|
| 参数名 | `keyword`、`classes`、`sort`、`area`、`lang`、`year`、`level`、`order`、`page`、`pageSize`、`categoryId` | 旧名 `wd`、`class`、`by`，其余同名；不接受 `pageSize`（固定 20）与 `categoryId`/`tid`（首批不认分类） |
| 参数位置 | 查询串 | 只认固定路径 + 查询串；路径参数形式交 PHP |
| 解码 | 容器按表单规则解码一次 | 解析器按同一规则解码一次（`%HH` 不合法或非 UTF-8 → 400） |
| 首尾空白 | 400 | 去掉首尾 W 字符后再校验 |
| `'0'` | `keyword/area/lang/classes` 为字面值 `0`；`level=0` → 400 | 一律按未传 |
| `''` | 按未传 | 按未传 |
| 未知参数 | 忽略（契约 1.0.0） | 400；`s` 与旧站支持而首批不支持的键 → 交 PHP |
| 重复参数 | 新参数 400；既有参数维持现状 | 任一键重复 → 400 |
| `page` | `parseInt`，允许前导 0（`02` 即 2） | 1..1000、无前导 0（与现有解析器一致），`''` 按未传 |
| `level` | 原始最多 10 个字符、允许前导 0 | 同 API（交给 content）；`'0'` 按未传；canonical 输出规范整数 |
| `by=rnd` | `sort=rnd` → 400 | 交 PHP |
| 长度 | 码点上限同左；原始查询串长度只受容器限制 | 码点上限同 API；另有原始查询串 ≤ 2048（超出交 PHP）、原始路径 ≤ 1024 |
| 错误输出 | JSON `ErrorResponse`，`fieldErrors` 含字段与文案 | HTML 400 页，固定文案，不显示字段与输入 |
| 开关 | `maccms.content.search.enabled` 控制 `keyword`/`classes` | 另需 `maccms.site.search.enabled`；`keyword`/`classes` 仍受前者控制 |

##### 7.2.7 T3 必须覆盖的向量（四种 URL 形态各一遍；`base-url` 用下列三种完整地址各一遍）

`base-url` 向量一律按**完整 URL**（协议、主机、端口、路径合计）计长度，不按目录路径计：① 根地址 `http://maccms.local.test/`；② 含非根子目录的完整地址，如 `https://maccms.local.test/sub/`；③ 含非根子目录、**完整总长恰为 768** 的地址（如 `https://maccms.local.test/` + 741 个 `a` + `/`，其路径部分为 743）。另确认（现有 MACC-17 行为，T3 只引用不重复实现）：完整总长 769 的 `base-url` 启动失败，因此不进入请求阶段。


- 解析：`wd=A/B`、`wd=A%2FB`、`wd=A%5CB` 与原始 `\`、`wd=.`、`wd=..`、`area=..`、`wd=A.html`、`wd=a.htmlb`（`suffixHide` 下尤其要测）、`wd=%2541`（值为 `%41`）、`wd=%252F`（值为 `%2F`，不得再解码）、`wd=C%2B%2B`（`C++`）、`wd=C++`（`C` 加两个空格，裁剪后为 `C`）、`wd=%e4%b8%ad`（接受，canonical 为大写且 `canonical=false`）、`wd=%FF`、`wd=%E4%B8`、`wd=%` → 400、`wd=%E3%80%80a`（U+3000 被裁掉）、`wd=0` → 未传、`level=0000000009` → 9（canonical 为 `level=9`）、`level=00000000009` → 400、`page=01` → 400、`page=1` → canonical 不带 `page`、`wd=&wd=x` → 400、`foo=1` → 400、`s=/vod/detail/id/1`、`actor=x`、`by=rnd`、路径 `/index.php/vod/search/wd/x.html`、2049 字符的查询串 → empty。
- 四项上限组合（4 个参数各取最大码点数的 3 字节汉字，另加 `year/level/by/order/page=1000`）：Q 长度为 1209，`B?Q` 为 1235/1231/1225/1221（≤ 1280）；在 768 字符 `base-url` 下 canonical 为 2003/1999/1993/1989（≤ 2048），页内链接更短；可解析回原条件。
- 生成与回读：对上述每个被接受的条件 F，分页（第 1、2、1000 页）、移除每个键、组合每个候选值、排序链接、canonical 都能构造 `SafeUrl`，长度在上限内，且 `parseSearch` 回读为同一 `(F, page)`、`canonical=true`；另以固定种子随机生成满足第 4 节字母表的 F 做同样检查。
- 不变：`vodSearch`、`SitePaths`、`SafeUrl`、`SiteSettings`（含 MACC-17 的 `SiteSettingsTest` 长度边界）现有测试与 PHP 黄金向量不改且通过；三页仍拒绝查询串。

##### 7.2.8 其他方案与不采用的理由

| 方案 | 不采用的理由 |
|---|---|
| 保留 PATHINFO，收窄 SSR 输入（禁止 `/`、`\`、纯点值、`suffixHide` 下含 `.html` 的值，并把长度压到组合 ≤ 1024） | 正常关键字（如 `AC/DC`、`Fate/Zero`、`1/2`）无法在 SSR 搜索；要让组合 ≤ 1024，SSR 的码点上限必须低于 API（例如 `class` 32 → 16 仍不够），造成两套长度；路径后缀剥离的歧义仍在 |
| 在路径中编码 `/`、`.`（`%2F`、`%2E`）或二次编码 | 违反 `SitePaths` 的安全边界，任务明确禁止 |
| 全局提高 `MAX_INPUT` 并继续用 PATHINFO | 解决不了 `/`、`\`、点段和 `.html` 改写；三页也不需要更长的输入 |

#### 7.3 页面内容

| 区域 | 方案 |
|---|---|
| 无任何条件（只有搜索基址 B 而无查询串，或所有键归一后都是未传） | 200，显示搜索表单与提示，不查库 |
| 有条件 | 一次 `listCategories()`（导航与候选值）+ 一次 `listVideos(filter, sort, order, page, 20)`；标题"搜索：<关键字>"或"筛选结果"，显示"共 N 条" |
| 表单 | `GET`，`role="search"`，`<label>` 关联输入框，`name="wd"`、`maxlength="64"`，回显经校验的关键字（jte 转义）；`action` 为 `publicPath(B)`，当前筛选（除 `wd`、`page`）以隐藏字段保留，提交关键字时 `page` 重置为 1（第 7.2.5 节） |
| 筛选链接 | 地区/语言/年份/扩展分类各一组普通 `<a href>`（无 JS 可用），候选值取所有可见分类 `extend` 中对应值的并集（去重、保持出现顺序、每组最多 30 个、跳过不满足第 4 节规则的值以及 `''`、`'0'`、带首尾 W 字符的值，保证链接可回读，第 7.2.5 节）；当前值 `aria-current="true"`，每组带"全部"链接（去掉该条件）。推荐等级首批不出筛选链接（仍可经 URL 使用） |
| 排序 | 时间 / 人气（`hits`）/ 评分 三个链接，其余 `by` 值仍可经 URL 使用 |
| 结果与分页 | 复用 MACC-13 `backend/src/main/jte/site/components/videoCard.jte`、`backend/src/main/jte/site/components/pagination.jte`；所有链接由新方法 `LegacyUrlBuilder.vodSearchLink` 生成固定路径 + 查询串（键排序、第 1 页不带页码，第 7.2.2 节），经 `SiteSettings.publicPath(SiteLink)` 输出 `SafeUrl`；现有 `vodSearch`（PATHINFO）不用于搜索页内链接 |
| canonical | 自引用：`vodSearchLink(归一后的条件, page)` 经 `SiteSettings.canonical(SiteLink)`，即 `base-url + B + ?Q`；非规范请求（键顺序不同、小写十六进制、`page=1`、首尾空白、去掉 `/index.php` 的形态等，第 7.2.3 节步骤 9）照常渲染，只输出 canonical，不 301 |
| robots | 所有搜索页（含无条件页）`<meta name="robots" content="noindex">` |
| 缓存头 | `Cache-Control: no-store`（关键字在 URL 中，不进入共享缓存；生产 TTL 另议） |
| 日志 | 不记录关键字、查询串、请求头；异常只经 `backend/src/main/java/com/maccms/backend/common/web/SafeExceptionLog.java`。部署时 nginx 访问日志会记录完整 URL（含关键字）——列入部署评审项 |

#### 7.4 状态与错误页

| 情况 | HTTP | 页面 |
|---|---|---|
| 正常、有结果 / 第 1 页无结果 | 200 | 结果页 / "没有找到相关视频"（保留表单与筛选） |
| `page>1` 且越界（`total=0` 或 `page>totalPages`） | 404 | 错误页（与分类页一致） |
| 参数不合法（content 或解析器给出 `INVALID_PARAMETER`） | **400** | 搜索页专用错误视图：固定文案"搜索条件不合法"、空白搜索表单，不回显原始输入；`noindex`、`no-store`。其他三页的 `INVALID_PARAMETER → 404` 不变 |
| 解析器不认识的形态 | 404 | 现有错误页（部署时应交 PHP） |
| 503（数据库不可用、并发上限、语句时限） | 503 | 现有维护/不可用页，`Retry-After: 120` |
| 站点关闭 | 503 | 现有拦截器，不查库 |
| 其他 | 500 | 完整错误页，不含已取到的数据 |

#### 7.5 无 JS 与键盘

页面不含脚本（CSP `script-src 'none'` 不变）；表单与所有筛选/排序/分页都是原生表单或链接，Tab 顺序与视觉顺序一致；焦点样式沿用 MACC-15；当前筛选除颜色外有文字或 `aria-current`；结果数量写在标题区文字中。

#### 7.6 继续留在 PHP

`vod/show`（筛选页，见 D3）、`ajax_search`/`ajax_show`、路径参数形式的搜索地址（`…/vod/search/wd/x.html` 等，即旧 `mac_url_search` 生成的形式，`application/common.php:2462-2465`）、查询串含 `s` 的请求、路由别名与 `-` 分隔符、第 7.2 节列出的未支持参数、`rnd`、文章/演员/专题等其他模块的搜索、热搜词（`search_hot`）与 `ajax/suggest`、带会员 cookie 的请求（`docs/architecture/03-frontend-readonly-plan.md` 第 2 节）。

### 8. 实施拆分

#### T1 content API/SQL 与真实 MySQL 验证（后端）

| 项 | 内容 |
|---|---|
| 前置 | 本方案审查通过、D1 定案 |
| 文件（同一提交） | `backend/src/main/java/com/maccms/backend/content/application/VideoFilter.java`（新）、`backend/src/main/java/com/maccms/backend/content/application/VideoListQuery.java`、`backend/src/main/java/com/maccms/backend/content/application/ContentReadService.java`（开关、并发上限、503 映射）、`backend/src/main/java/com/maccms/backend/content/application/ContentProperties.java`（`search.enabled`、`search.max-concurrent` 及校验）、`backend/src/main/java/com/maccms/backend/content/api/VideoController.java`（新参数、重复检测、非法 UTF-8 检测）、`backend/src/main/java/com/maccms/backend/content/infrastructure/VodMapper.java`、`backend/src/main/resources/mapper/content/VodMapper.xml`、`backend/src/main/resources/application.yml`、`backend/config/maccms-backend.env.example`、`docs/contracts/content-read.openapi.yaml`（合并本草案，版本 1.1.0）、删除 `docs/contracts/content-search-filter.draft.openapi.yaml`、`docs/architecture/02-agent-development-rules.md`（`VideoListQuery` 取值范围说明）、本文件状态更新 |
| 测试（同一提交） | `backend/src/test/java/com/maccms/backend/content/application/VideoFilterTest.java`（新；第 4.3 节每一步与第 4.4 节对照表每一格的正反例、LIKE 转义、实体变体）、`backend/src/test/java/com/maccms/backend/content/ContentApiContractTest.java`（扩展：新参数 200/400/503 与响应体、`wd=x` 被忽略、`keyword=%FF` → 400、旧用例不改）、`backend/src/test/java/com/maccms/backend/content/infrastructure/VodMapperBoundSqlTest.java`（新；无新参数时 SQL 逐字不变；有参数时只出现 `#{}` 占位与固定列名）、`backend/src/test/java/com/maccms/backend/content/ContentApiMySqlIT.java`（**既有，扩展**；真实 MySQL：每个条件、组合、`%`/`_`/`!`/`\`/`,` 字面匹配、实体变体、utf8 大小写不敏感、不可见分类/停用分类/未审核视频不出现、带条件分页无重复无遗漏、count 与列表一致、`MAX_EXECUTION_TIME` 超时被映射为 503）、`backend/src/test/java/com/maccms/backend/ReadOnlyGuardrailsTest.java`/`backend/src/test/java/com/maccms/backend/architecture/ModuleBoundaryTest.java` 保持通过、新增 503 分支的日志脱敏测试 |
| 验收 | `./mvnw verify` 与 `./mvnw verify -Pmysql-it` 通过（PowerShell 与 Git Bash 各一次）；集成测试前后一次性库表 `CHECKSUM` 不变 |
| 只读证据 | 集成测试日志中一次性库的创建/删除记录与 CHECKSUM 对比；不连 `maccms_dev` |
| 回滚 | `git revert`；或保持 `search.enabled=false`（精确筛选仍可用）；无数据回迁 |

#### T2 一次性合成库性能测量（后端，可与 T1 同一任务的后续提交）

| 项 | 内容 |
|---|---|
| 前置 | T1 合入 |
| 文件 | `backend/src/test/perf/`（数据生成与测量脚本，不进 `verify`）、`docs/architecture/09-content-search-filter-plan.md`（附测量报告或新增报告文件） |
| 验收 | 按第 6.5 节完成三档规模与表锁实验，报告给出原始数据与开关建议；不对外宣称吞吐 |
| 回滚 | 只有脚本与文档，删除即可 |

#### T3 URL 解析与 SSR 搜索页（后端）

| 项 | 内容 |
|---|---|
| 前置 | T1 合入；T2 报告给出"可开启"或限定条件（页面可先实现，开关默认关闭）；分支须包含 MACC-17（`base-url` ≤ 768、`SitePaths.MAX_LENGTH` = 1280，`6f34d7d`），T3 不改这两个常量与 `SafeUrl.MAX_LENGTH` |
| 文件 | `backend/src/main/java/com/maccms/backend/site/application/LegacyUrlParser.java`（`parseSearch`、`MAX_SEARCH_QUERY=2048`、旧站式归一；现有 `parse` 与 `MAX_INPUT` 不变）、`backend/src/main/java/com/maccms/backend/site/application/SiteRoute.java`（`VodSearch`）、`backend/src/main/java/com/maccms/backend/site/application/LegacyUrlBuilder.java`（新增 `vodSearchLink`；现有 `vodSearch`、`urlencode` 不变）、`backend/src/main/java/com/maccms/backend/site/application/SiteLink.java`（新，路径 + 查询串）、`backend/src/main/java/com/maccms/backend/site/application/SiteQueries.java`（新，查询串白名单校验）、`backend/src/main/java/com/maccms/backend/site/application/SiteSettings.java`（新增 `publicPath(SiteLink)`、`canonical(SiteLink)`；现有方法不变）、`backend/src/main/java/com/maccms/backend/site/application/SearchFilters.java`（新）、`backend/src/main/java/com/maccms/backend/site/application/SiteProperties.java`（或新增搜索开关配置类）、`backend/src/main/java/com/maccms/backend/site/web/SiteContentController.java`（条件注册路由）、`backend/src/main/java/com/maccms/backend/site/web/ContentPages.java`、`backend/src/main/java/com/maccms/backend/site/web/view/` 下新增搜索页视图模型、`backend/src/main/jte/site/pages/search.jte`（新）、`backend/src/main/java/com/maccms/backend/site/web/SiteExceptionHandler.java`（搜索页 400 视图）、`backend/src/main/resources/application.yml`、`backend/config/maccms-backend.env.example`、`docs/architecture/08-site-pages.md` 或新文档、`docs/frontend/02-url-golden-vectors.md` |
| 测试 | PHP 黄金向量扩展：`backend/src/test/php/mac_url_golden.php` 增加 `vod/search` 解析向量（只执行旧 URL/路由代码，不连库、不发请求），Java 认得的每条向量在 PHP 中也调度到 `vod/search`，且对不含 `%`、`+` 的值，`wd/area/lang/year/level/class/by/order/page` 的取值一致（PHP 二次解码，含 `%`、`+` 的值按第 9 节差异处理，不比对）；`backend/src/test/java/com/maccms/backend/site/application/LegacyUrlParserTest.java`（第 7.2.7 节全部解析向量）；`backend/src/test/java/com/maccms/backend/site/application/SearchLinkRoundTripTest.java`（新；第 7.2.7 节生成与回读、四项上限组合、长度上限、第 7.2.7 节三种完整 `base-url`（含总长恰为 768 的子目录地址）、`B?Q` ≤ 1280 与 canonical ≤ 2003 的断言、固定种子随机 F）；`backend/src/test/java/com/maccms/backend/site/application/SiteQueriesTest.java`（新；拒绝 `?`、`#`、小写十六进制、`%00`–`%1F`、`%7F`、未知键、重复键）；`backend/src/test/java/com/maccms/backend/site/web/SiteSearchPagesMvcTest.java`（新；200/400/404/503/500、noindex、no-store、canonical、Host 注入、日志不含关键字）、`backend/src/test/java/com/maccms/backend/site/web/SiteContentHtmlStructureTest.java` 扩展（表单标签、筛选链接名称、`aria-current`）、`backend/src/test/java/com/maccms/backend/site/SiteContentRealHttpTest.java` 扩展；开关关闭时路由不存在 |
| 浏览器验收 | `backend/src/test/browser/site-pages-check.mjs` 增加搜索页：320/360/1280 px 无横向溢出、Tab 可达所有筛选与分页、无 JS 可用、焦点可见 |
| 只读证据 | 用合成库运行候选 jar，请求前后 26 张表 CHECKSUM 与行数不变（同父任务三页验收方法） |
| 回滚 | `maccms.site.search.enabled=false` 即撤下路由；或 `git revert` |

#### T4 搜索页样式（Kimi，前端目录）

| 项 | 内容 |
|---|---|
| 前置 | T3 的模板与 class 约定确定（在 T3 交付评论中列出） |
| 文件 | 只改 `backend/src/main/resources/static/site/site.css`（及允许的样式测试）；约定 class：`search-page`、`search-summary`、`search-filters`、`filter-group`、`filter-chip`（含 `--current`）、`search-empty` |
| 验收 | 真实搜索页 HTML（T3 产出）在 320/360/1280 px 下无溢出、焦点环、减少动画；不改 JTE/Java |
| 回滚 | revert CSS |

### 9. 与旧版的差异（拟实施后）

| 方面 | 旧 PHP | 新版 |
|---|---|---|
| 参数解码 | 二次解码，`+` 变空格 | 解码一次 |
| 首尾空白 | 所有参数先 `trim`（ASCII） | API：不裁剪，首尾空白 → 400；SSR：去掉首尾 W 字符后再交给 API |
| `''` / `'0'` | `empty()` 都视为未传 | API：`''` 未传，`'0'` 按字面值（`level=0` → 400）；SSR：两者都视为未传 |
| 关键字实体 | 只匹配 `htmlspecialchars` 后的形式（`'` 随 PHP 版本） | 原值 + `mac_filter_xss` 形式两者 OR |
| 超长 | 按 `search_len`（默认 10）截断 | 400，不截断；上限 64 |
| `%`/`_` | 通配符 | 字面字符 |
| 多值 | `area/lang/class` 逗号多值；`wd` 逗号是字面字符 | `area/lang/classes` 含逗号 → 400（首批）；`keyword` 逗号仍是字面字符 |
| 年份 | 4 位或 9 位区间，整数比较，其他长度不筛选 | 只接受 4 位，字符串比较 |
| `level` | `intval`，0 视为未传 | 1..9（允许前导 0，原始值最多 10 个字符），其他 400；SSR 另把 `'0'` 视为未传，`00` 等仍 400 |
| 重复/冲突参数 | 取后一个，查询串覆盖路径 | 400 |
| 参数位置 | 路径参数与查询串都接受，查询串覆盖路径 | API：查询串；SSR：只认固定路径 + 查询串，路径参数形式交 PHP（第 7.2.3 节） |
| 搜索页内链接 | `mac_url` 生成 PATHINFO（`suffix_hide` 下值内的 `.html` 也被替换） | 固定路径 + 查询串，所有被接受的值都能生成并回读（第 7.2.5 节）；交给 PHP 处理时含 `%`、`+` 的值会被 PHP 二次解码成不同的值 |
| 未知参数 | 忽略 | API 忽略；SSR 400（`s` 与首批不支持的旧键交 PHP） |
| `rnd` 与暂缓参数 | 支持 | API 不支持；SSR 交 PHP |
| 排序 | 单列，分页可能不稳定 | 追加 `vod_id`，稳定 |
| 游客分类 | 仅 `popedom_filter=1` 时过滤 | 始终只列可见分类（与 1.0.0 一致） |
| 停用分类 | 列表仍可能出现其视频 | 不出现（与 1.0.0 一致） |
| 频率限制、验证码 | `search_timespan`（写会话）、`search_verify` | 不复刻（见 D2）；以并发上限、语句时限代替 |
| 搜索开关 | `app.search` | `maccms.content.search.enabled` / `maccms.site.search.enabled`，默认关闭 |
| 结果缓存 | `cache_core`、`cache_page`、`mac_vod_search`（写库） | 无缓存，不读写 `mac_vod_search` |
| 敏感词/`wall_filter` | 只作用于回显 | 不实现（页面 `noindex`，回显经 jte 转义） |
| 搜索页 SEO | 由模板决定 | `noindex` + 自引用 canonical |
| 错误 | `error` 跳转页 | 400/404/503 完整错误页 |
| 会员 | `label_user` 检查会员登录 | 只按游客；会员请求留在 PHP |

### 10. 需要决策的事项

| 编号 | 问题 | 选项 | 推荐 |
|---|---|---|---|
| D1 | 关键字匹配哪些字段 | ① `vod_name`+`vod_sub`+`vod_en`（仓库 `search_vod_rule` 默认值）；② 按线上 `search_vod_rule` 实际值（可能含演员等，成本更高）；③ 只 `vod_name` | **①**，并在上线前由用户提供线上 `search_vod_rule` 的值（只需告知取值，不需配置文件）；若线上不同再改 |
| D2 | 反滥用 | ① Java 不复刻会话频率限制与验证码，以并发上限 + 语句时限 + 默认关闭控制成本，部署时再评估 nginx `limit_req`；② 复刻（需要会话或 Redis 状态，与无会话的游客页面设计冲突） | **①**。若线上 `search_verify=1` 或依赖频率限制防爬，需用户确认可以接受这一差异 |
| D3 | `vod/show` 筛选页 | ① 本批不承接，筛选通过 `vod/search` 的参数实现；② 一并承接（需先核实线上 `app.show`，仓库为关闭） | **①**。`vod/show` 仍归 PHP，待线上 `app.show` 核实后另行评估 |

以下为普通实现选择，本方案已给出推荐、无需用户决定：关键字上限 64、单值、`ESCAPE '!'`、API 不裁剪而 SSR 裁剪、`noindex`/`no-store`、SSR 400 页、并发默认 4、时限 3000 ms、候选值来自分类 `extend`。

### 11. 风险与未验证

- **全部未测量**：第 6.2 节是定性分析，没有任何耗时、吞吐或锁等待数据；是否开启包含匹配取决于 T2。
- **MyISAM 表锁**可能让长搜索阻塞 PHP 的点击计数与采集写入并造成全站堆积；并发上限与时限是缓解，不是消除。
- utf8 列与 utf8mb4 连接参数比较时能否用索引、`MAX_EXECUTION_TIME` 超时的异常翻译、容器对非法 UTF-8 查询参数的处理，都要在实施时实测确认。
- 线上配置（`search`、`search_len`、`search_vod_rule`、`search_verify`、`search_timespan`、`show`、`popedom_filter`、`vod_search_optimise`、URL 形态）、MySQL 版本、数据量与分布、字段存储的实体形式均**【待核实】**。
- 模板缺失：旧搜索页标签属性（每页条数、默认排序、是否带分类）**【待核实】**；新版每页固定 20、默认 `time desc`。
- 关键字出现在 URL 中，部署时 nginx/CDN 访问日志会记录；需在部署评审中处理。
- SSR 搜索链接是固定路径 + 查询串；将来若把这类请求交还 PHP，PHP 的二次解码会把含 `%`、`+` 的值解释成不同的值（第 7.2.2 节），交还方案须在部署评审中处理。浏览器、代理对 2048 字符以内的查询串是否有更短的限制**【待核实】**（与部署环境有关）。
- 本方案只是设计；后端/前端实现由队长审查并确定方案后另行派发。

### 12. T1 实施记录（MACC-18）

按第 8 节 T1 实施，D1–D3 取第 10 节推荐值（关键字匹配 `vod_name`/`vod_sub`/`vod_en`；不复刻频率限制与验证码；不承接 `vod/show`）。包含匹配默认关闭，开启前仍须完成 T2 测量并结合线上配置评估。下表只列与方案文字不同或方案要求"实施时确认"的地方。

| 项 | 方案 | 实施 | 原因 / 证据 |
|---|---|---|---|
| 无新参数时的 SQL | 在 `publishedInTypes` 中用 `<if test="filter != null">` 追加条件 | 不带新条件时仍调用原 `countPublished`/`selectPublishedPage`；带条件时调用新增的 `countPublishedMatching`/`selectPublishedMatchingPage`。四条语句共用 `publishedInTypes`、列清单与新抽出的排序/分页片段 `pageOrderAndLimit`，带条件的两条共用 `matchingConditions` 并带 `MAX_EXECUTION_TIME(3000)` | 原语句不经过任何新分支；`backend/src/test/java/com/maccms/backend/content/infrastructure/VodMapperBoundSqlTest.java` 以本任务修改 XML **之前**生成的黄金文件（count 1 条 + 11 种排序 × 2 方向 = 23 条，空白规范化后比较）锁定，且原有 Mockito 契约测试不必修改 |
| 条件参数类型 | Mapper 使用 `VideoFilter` | Mapper 使用 infrastructure 层的 `VodFilterCriteria`（level/year/area/lang + 已转义的 LIKE 模式），由 `ContentReadService` 从 `VideoFilter` 构造 | `ModuleBoundaryTest` 禁止 infrastructure 新增对 application 的引用（已知例外只有 `VideoSort`） |
| 超时异常翻译 | 实施时实测 | MySQL 5.7.44 实测：语句超过 `MAX_EXECUTION_TIME` 报 3024，Spring 翻译为 `UncategorizedSQLException <- SQLException`（不是 `TransientDataAccessException`），原样会是 500。`ContentReadService.read` 按错误码 3024（`content.infrastructure.StatementTimeouts`，只看错误码、不读 message）补一条 503 映射，日志 `database statement timeout: <类型链>`。**MACC-22 补充**：时限在 filesort 阶段触发时 MySQL 5.7.44 报 1028 `ER_FILSORT_ABORT`（T2 报告第 6.3 节，修复前为 500），`StatementTimeouts` 现按错误码 3024 或 1028 识别，两者响应体与日志相同；1028 不区分中断原因，`KILL QUERY` 打断排序也归为 503 | `ContentSearchMySqlIT`：另一会话 `LOCK TABLES mac_vod WRITE` 时带 `area` 的请求约 3.7 s 后 503（说明时限也覆盖等锁时间，而不是 10 s JDBC 兜底）。1028：`StatementTimeoutsTest`、`ContentSearchApiContractTest.filesortAbortedByStatementTimeoutReturnsTheSame503`、`ContentReadServiceUnavailableTest`（异常链替身，未在真实 MySQL 上复现排序阶段中止） |
| 非法 UTF-8 | 实施时实测容器处理 | Tomcat 11 遇到任一参数的百分号编码非法（`%FF`、`%`、`%E4%B8`）时**拒绝解析整个查询串**（`InvalidParameterException`），1.0.0 下即 500。列表接口改为在方法内读取参数（不再用 `@RequestParam`，否则在进入方法前就已失败）：先扫描原始查询串；只有非法编码全部出现在 6 个新参数中时，改用同一表单规则自行解码一次的结果，得到 400 + 该参数名；非法编码出现在既有参数、未知参数或键名中时原样抛出，**仍是 1.0.0 的 500** | `content.api.RawQueryParams`；`ContentSearchRealHttpTest`（真实 Tomcat、原始套接字）锁定两类行为。MockMvc 不经过容器解码，另以 `URI.create` 覆盖 |
| 既有参数重复 | 实施时确认并锁定 | 与 1.0.0 相同：各值逗号连接后按原规则判错（`page=1&page=2` → 400 page，`order=&order=` → 400 order） | `ContentApiContractTest`、`ContentSearchRealHttpTest` |
| 并发上限的位置 | `ContentReadService` 内 `Semaphore.tryAcquire()` | 同方案；许可覆盖整次读取（含分类目录），所以并发满时带不可见 `categoryId` 的包含匹配请求得到 503 而不是 404 | 503 与 404 都不泄露数据；客户端按契约稍后重试 |
| 配置校验 | `max-concurrent` 1..连接池大小−1，启动校验 | `maccms.content.search.*` 按原始字符串绑定、`SearchSettings.from` 校验（与 site 配置相同做法），非法值启动失败，信息只列配置项名；连接池大小取 `spring.datasource.hikari.maximum-pool-size`（即 `MACCMS_DB_POOL_SIZE`） | `SearchSettingsTest`（含整应用启动失败且不回显取值） |
| 集成测试 | 扩展既有 `ContentApiMySqlIT` | 新增 `ContentSearchMySqlIT`，流程与既有 IT 相同（一次性库、合成数据、请求前后全部表 CHECKSUM/行数不变、只删除该库并确认已不存在）；既有 IT 未改 | 既有 IT 对其数据集的完整列表逐项断言，加入搜索样本会改变那些断言 |
| `utf8` 列与连接字符集 | 第 6.2 节要求 EXPLAIN 确认索引 | 未做（属于 T2）。正确性已在真实 MySQL 上验证：大小写不敏感、`%`/`_`/`!`/`\`/逗号字面匹配、实体变体 | — |

与旧 PHP 的差异即第 9 节"新版"一列中 API 相关的各行（解码一次、不裁剪、`'0'` 字面值、超长 400、`%`/`_` 字面、单值、年份字符串比较、`level` 1..9、重复参数 400、实体两种形式 OR、追加 `vod_id` 稳定排序、只列可见分类、不复刻频率限制/验证码、无结果缓存、默认关闭）。SSR 相关各行要到 T3 才实现。
