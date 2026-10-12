## 视频搜索与多条件筛选：只读契约与下一批实施方案（MACC-16）

> **状态：方案草案，待队长审查。** 本文只做设计，**不实现**任何代码；文中新接口、新参数、新配置、新页面全部是**拟实施**，不是已实现能力。
> 配套接口草案：`docs/contracts/content-search-filter.draft.openapi.yaml`（以下简称"草案"）。已发布契约 `docs/contracts/content-read.openapi.yaml`（1.0.0，以下简称"契约"）不变。
>
> 输入：父任务 MACC-2 评论、`AGENTS.md`、`02-agent-development-rules.md`（"02 规范"）、`03-frontend-readonly-plan.md`（"03 方案"）、`08-site-pages.md`（"08 文档"）、`docs/frontend/02-url-golden-vectors.md`（"URL 向量文档"）、契约，以及下文逐条引用的旧代码与 Java 代码。
>
> 证据标注：
> - **【代码】**：仓库代码可直接核对，给出 `文件:行号`。行号基于本 worktree（主线 `3f81c72`）。
> - **【已验收】**：父任务已验收的 Java 行为（首页/分类/详情三页、URL 生成解析、公开 API），依据 08 文档与父任务评论。
> - **【建议】**：本方案提出、尚未实现，需审查。
> - **【待核实】**：依赖线上配置、真实数据、缺失模板或未做的实验，本文不下结论。
>
> 本任务**没有**：运行旧 PHP 请求、连接任何数据库、读取仓库外配置、修改代码/正式契约/AGENTS.md。

### 0. 结论摘要

| 项 | 推荐 |
|---|---|
| 实现路径 | **方案 A**：在已发布的 `GET /api/v1/videos` 上增加 6 个可选参数（`keyword`、`area`、`lang`、`year`、`level`、`classes`），复用 `VideoPage`/`VideoSummary`、可见性、排序与分页。不新建接口，不引入外部搜索引擎、索引、表或缓存（第 2 节） |
| 兼容性 | 不传新参数时 SQL 文本、响应、排序、分页、错误与 1.0.0 完全相同，由 BoundSql 黄金测试与既有契约测试锁定（第 2.3 节） |
| 首批参数 | 关键字（name/sub/en 包含匹配）、地区、语言、年份（4 位单值）、推荐等级（1..9）、扩展分类（包含匹配），加上已有的分类与 11 种排序。暂缓：演员、导演、标签、首字母、状态、版本、年份区间、多值、`rnd`、`tid` 别名等（第 3 节） |
| 成本控制 | 包含匹配（keyword/classes）默认关闭（`maccms.content.search.enabled=false`），开启后有进程内并发上限（默认 4）与服务端语句时限（`MAX_EXECUTION_TIME(3000)`），超限返回既有 503。上线前必须在一次性合成库上完成 EXPLAIN/耗时/表锁测量（第 6 节） |
| 前台 | Java 承接旧 `vod/search`（默认与 rewrite 两种 URL 形态，路径参数 + 查询串），页面 `noindex`、`no-store`；`vod/show`（筛选页）、路由别名、`-` 分隔符、不支持的参数仍归 PHP（第 7 节） |
| 拆分 | T1 content API/SQL + 真实 MySQL 集成测试 → T2 一次性合成库性能测量（只读证据）→ T3 URL 解析 + SSR 搜索页 → T4 Kimi 样式（第 8 节） |
| 需要决策 | D1 关键字匹配字段；D2 反滥用（不复刻频率限制/验证码）；D3 `vod/show` 是否本批承接（第 10 节） |

### 1. 旧逻辑证据

#### 1.1 入口与前置检查

| 事实 | 依据 | 性质 |
|---|---|---|
| `vod/search`：`mac_param_url()` → `check_search` → `label_search` → 输出模板 `vod/search`。控制器本身**不查列表**，列表由模板里的 `{maccms:vod paging="yes" …}` 标签调用 `Vod::listCacheData` 取得 | 【代码】`application/index/controller/Vod.php:38-44`；标签属性白名单 `application/common/taglib/Maccms.php:24` | 模板缺失（`AGENTS.md`"仓库不完整"），搜索页标签实际用了哪些属性（`num`、`order`、`by`、`type`）**【待核实】** |
| `vod/show`：`check_show` → `label_type`（含分类列表权限检查）→ 模板 | 【代码】`Vod.php:23-28`；`application/common/controller/All.php:166-188` | 同上 |
| `check_search`：`app.search=0` 时报错关闭；**第 1 页**与上次搜索间隔小于 `search_timespan` 秒时报"搜索太频繁"；`search_verify=1` 时要求验证码会话 | 【代码】`application/index/controller/Base.php:53-71` | 仓库配置 `search=1`、`search_verify=0`、`search_timespan=3`（`application/extra/maccms.php:66-69`）；线上值**【待核实】** |
| 频率检查**写会话**：`mac_get_time_span` 每次调用都把当前时间写回 session | 【代码】`application/common.php:306-316` | 游客第 1 页搜索也会产生 PHP 会话 |
| `check_show`：`app.show=0` 时整页关闭；`show_verify=1` 时要求验证码 | 【代码】`Base.php:29-43` | 仓库 `show=0`（`maccms.php:64`），即仓库默认下筛选页关闭；线上**【待核实】** |
| 所有前台页先执行 `label_user`：带会员 cookie 时 `checkLogin`，会员组到期可能触发写库（03 方案 6.2） | 【代码】`Base.php:11-18`、`All.php:118-147` | Java 只按游客处理，会员请求按 03 方案第 2 节留在 PHP |
| 页面级缓存：`cache_page=1` 时以 `HTTP_HOST + 模板 + 查询参数` 为键读写缓存 | 【代码】`All.php:24-56` | 仓库 `cache_page=0`（`maccms.php:58`）；线上**【待核实】** |

#### 1.2 参数读取与规范化

| 事实 | 依据 |
|---|---|
| `mac_param_url` 把 ThinkPHP `input()` 与 `$_REQUEST` 合并（查询串/表单会覆盖路径参数）；`page`、`tid`、`level` 等经 `intval`（`page<1` 当 1）；`wd`、`area`、`lang`、`year`、`class`、`order`、`by` 等经 `htmlspecialchars(urldecode(trim(...)))` | 【代码】`application/common.php:1560-1606`（`page` 1567、`tid` 1569、`level` 1576、`wd` 1582、`area` 1585、`year` 1586、`lang` 1587、`class` 1592、`by` 1594） |
| 由此产生的旧行为：① **二次解码**——Web 服务器/PHP 已解码一次，`urldecode` 再解一次，`+` 变空格（搜索 `C++` 实际是 `C`）、`%2541` 变 `A`；② **实体编码**——`&` 变 `&amp;` 后再进 SQL；`'` 是否编码随 PHP 版本变化（8.1 起默认 `ENT_QUOTES`，URL 向量文档第 1 节已观察到） | 【代码】同上；PHP 版本差异见 `docs/frontend/02-url-golden-vectors.md` 第 1 节 |
| `mac_search_len_check`：`wd/tag/class/letter/name/state/level/area/lang/version/actor/director/…` 超过 `search_len` 个字符时**截断**（不报错） | 【代码】`application/common.php:2879-2888` |
| `search_len` 仓库值为空串，`Init` 行为把小于 1 的值改为 **10** | 【代码】`maccms.php:68`；`application/common/behavior/Init.php:78-79`。线上值**【待核实】** |
| `label_search` 对**回显用**参数做敏感词替换（`filter_words`）与 `wall_filter` 编码；但 `listCacheData` 自己重新调用 `mac_param_url`，**查询条件不经过敏感词替换** | 【代码】`All.php:155-164`；`application/common/model/Vod.php:167-169` |

#### 1.3 查询条件（`Vod::listCacheData`，`paging="yes"` 分支）

| 条件 | 旧规则 | 依据 |
|---|---|---|
| 参数来源 | 分页标签用 URL 参数覆盖标签属性：`level/ids/tid/year/area/lang/tag/class/state/letter/version/actor/director/wd/name/by/order/page/isend` | 【代码】`Vod.php:167-247` |
| 状态 | 固定 `vod_status=1` | 【代码】`Vod.php:272` |
| 推荐等级 | `vod_level IN (explode(',', level))`；URL 中 `level` 已被 `intval`，实际只可能是单个整数，0 视为未传 | 【代码】`Vod.php:290-295`；`common.php:1576` |
| 年份 | 4 字符 → `intval`；9 字符 → 取前后各 4 位展开成区间；其他长度 → **不筛选**；比较 `vod_year IN (...)`（整数与 varchar 列比较） | 【代码】`Vod.php:183-201,296-298` |
| 地区/语言/状态/版本 | `IN (explode(','))`，逗号即多值；空项会变成 `IN ('')` | 【代码】`Vod.php:299-310` |
| 首字母 | `vod_letter IN (...)`，`0-9` 特殊展开 | 【代码】`Vod.php:330-335` |
| 分类（标签属性 `type`） | 命中 `type_id` 或 `type_pid` 在列表中的分类（即本身 + 直接子分类） | 【代码】`Vod.php:336-352` |
| 分类（URL `tid`） | `type_id = tid OR type_id_1 = tid`（`type_id_1` 是冗余存的上级分类） | 【代码】`Vod.php:356-358` |
| 关键字 `wd` | `vod_name` 及 `search_vod_rule` 列出的列 `LIKE '%wd%'`（多列为 OR）；`%`、`_` 不转义 | 【代码】`Vod.php:462-468`；仓库 `search_vod_rule='vod_en|vod_sub'`（`maccms.php:70`），线上**【待核实】** |
| `name/tag/class/actor/director` | 逗号拆分后每项 `LIKE '%项%'`，项之间 OR | 【代码】`Vod.php:469-483`；`common.php:1284-1292`（`mac_like_arr`） |
| 游客分类过滤 | 仅当 `popedom_filter=1` 时排除游客组无权限分类 | 【代码】`Vod.php:489-499`；仓库 `popedom_filter=0`（`maccms.php:49`） |
| 排序 | `by` 白名单（含 `rnd`），非法值回落 `time`；`order` 非 asc/desc 回落 desc；**只按单列排序，没有 id 兜底**，分页可能不稳定 | 【代码】`Vod.php:502-545`；`listData` `Vod.php:43-48` |
| 分页与计数 | `LIMIT (page-1)*num+start, num`；`totalshow=1` 时另执行一次 `count()`，二者用同一个 `$where` | 【代码】`Vod.php:32-67` |
| 结果缓存 | `cache_core=1` 时按条件 md5 读写缓存 | 【代码】`Vod.php:552-561`；仓库 `cache_core=0`（`maccms.php:56`） |

#### 1.4 `VodSearch` 搜索结果缓存（Java 不得复用）

| 事实 | 依据 |
|---|---|
| 只有 `vod_search_optimise` 含 `frontend` 时启用；启用后每个关键字先查 `mac_vod_search`，未命中就 `LIKE` 扫 `mac_vod` 取全部 id 并 **INSERT**，命中则 **UPDATE** 命中次数——即**前台 GET 搜索会写库** | 【代码】`application/common/model/VodSearch.php:18-64,69-78`；`Vod.php:404-459`。仓库 `vod_search_optimise=''`（`maccms.php:97`），线上**【待核实】** |
| id 列表超过 `maxIdCount=1000` 时退回 LIKE；否则以字符串拼接 `vod_id IN (...)` | 【代码】`VodSearch.php:11`；`Vod.php:416-419,456-459` |
| 采集时 `checkAndUpdateTopResults` 以字符串拼接执行 UPDATE | 【代码】`VodSearch.php:97-128` |
| 表 `mac_vod_search` 为 InnoDB/utf8mb4 | 【代码】`application/install/sql/install.sql:887-901` |

结论：`mac_vod_search` 属于旧系统的写入路径，Java **不读、不写**该表，也不实现任何等价的结果缓存（02 规范第 3 节"只读"）。

#### 1.5 旧 URL

| 事实 | 依据 |
|---|---|
| `mac_url` 先丢弃 `empty()` 的值（`''`、`'0'`）、第 1 页置空、按键排序；`vod/search` 走默认分支 `url($model,$param)` | 【代码】`application/common.php:1732-1746,2372-2374`；`mac_url_search` `:2462-2465` |
| 搜索/筛选 URL 在 `suffix_hide=1` 时只把 `.html` 换成 `/`，不做 `-/`、`_.` 清理 | 【代码】`common.php:2414-2424` |
| 路由别名 `vodsearch/<wd?>-<actor?>-…-<year?>`（14 个位置参数，用 `-` 连接） | 【代码】`application/route.php:331` |
| Java 生成器已能生成旧搜索地址（仍指向 PHP）：`vodSearch(filters, page)`，参数白名单 `SEARCH_KEYS` | 【已验收】`backend/src/main/java/com/maccms/backend/site/application/LegacyUrlBuilder.java:28-30,105-109,131-153`；PHP 黄金向量见 URL 向量文档 |
| 三页外壳的搜索表单 `GET` 提交到 `vodSearch(Map.of(), 1)`，即 `/index.php/vod/search.html`，输入框 `name="wd"`、`maxlength="64"` | 【已验收】`backend/src/main/java/com/maccms/backend/site/web/SitePages.java:32`；`backend/src/main/jte/site/layout/page.jte:50-54` |
| Java 解析器目前**不认识** `vod/search`（返回 empty，本地 404），并拒绝一切查询串 | 【已验收】`backend/src/main/java/com/maccms/backend/site/application/LegacyUrlParser.java:93-96,167-183` |

#### 1.6 表结构与索引（`mac_vod`）

| 事实 | 依据 |
|---|---|
| `ENGINE=MyISAM DEFAULT CHARSET=utf8`（未指定排序规则，即 `utf8_general_ci`：大小写不敏感、尾随空格不敏感、存不下 U+FFFF 以上字符） | 【代码】`install.sql:803` |
| 相关列：`vod_name/vod_sub/vod_en varchar(255)`、`vod_class varchar(255)`、`vod_area varchar(20)`、`vod_lang varchar(10)`、`vod_year varchar(10)`、`vod_level tinyint`、`vod_status tinyint` | 【代码】`install.sql:690-697,713-715,725` |
| 单列 B-tree 索引：`type_id`、`vod_level`、`vod_name`、`vod_year`、`vod_area`、`vod_lang`、`vod_class`、`vod_en`、`vod_time` 等；**`vod_status` 与 `vod_sub` 没有索引**；没有复合索引、没有 FULLTEXT | 【代码】`install.sql:768-802` |
| 线上是否改过表结构/索引、数据量与数据分布 | **【待核实】**（本任务不连线上库） |

#### 1.7 现有 Java 内容服务（【已验收】，本批要保持兼容）

| 事实 | 依据 |
|---|---|
| `GET /api/v1/videos` 参数 `categoryId/sort/order/page/pageSize` 以字符串接收，由 `VideoListQuery.parse` 统一校验（page 1..1000、pageSize 1..100、categoryId 1..65535） | `backend/src/main/java/com/maccms/backend/content/api/VideoController.java:27-34`；`content/application/VideoListQuery.java:12-45` |
| 可见性：每次查询先构建分类目录（启用、游客列表权限、上级可见），`categoryId` 不可见 → 404；列表条件为 `vod_status=1 AND type_id IN (可见分类)` | `content/application/ContentReadService.java:88-110`；`backend/src/main/resources/mapper/content/VodMapper.xml:11-15` |
| count 与分页共用 `publishedInTypes` 片段；排序列由枚举 + `<choose>` 生成，追加 `vod_id` 同向排序 | `VodMapper.xml:57-90` |
| 连接池只读（`hikari.read-only: true`），池大小默认 10；MyBatis 全局语句超时 10 秒 | `backend/src/main/resources/application.yml:11-13,28` |
| 数据库连接类故障在 `ContentReadService.read` 统一转为 503 `SERVICE_UNAVAILABLE`，日志只有类型链 | `ContentReadService.java:79-86`；08 文档第 5 节 |
| 契约声明"未声明的查询参数会被忽略；值为空字符串的参数按未传处理" | `docs/contracts/content-read.openapi.yaml:18` |
| 首页推荐位因契约"不能按 level 筛选"只能按 level 排序近似 | 03 方案 3.2；08 文档第 8 节 |

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
| 参数校验与错误 | `categoryId/sort/order/page/pageSize` 的取值、默认值、错误字段与文案不变；重复的旧参数维持现状（由 Spring 合并成逗号值后按原规则判错，**实施时以测试确认**） | 既有 `ContentApiContractTest` 全部不改且通过 |
| 响应 | schema 不变（草案中逐字复制），`ErrorResponse.code` 不新增取值 | `OpenApiContract` 字段集合比对；草案与契约 schema 逐键相等（本任务已检查，见交付评论） |
| 排序/分页 | 带不带条件都按"排序列 + `vod_id` 同向"；`page>totalPages` 仍是 200 空列表 | 契约测试 + 真实 MySQL 集成测试 |
| 503 | 新增的 503 原因只发生在带新参数的请求上（语句时限：任一新参数；并发上限：`keyword`/`classes`），响应体与数据库不可用时逐字相同 | 契约测试断言响应体 |
| 已知的语义变化 | 1.0.0 下被忽略的 `keyword=…` 等将开始生效。旧站参数名（`wd`、`tid`、`by`、`class`）继续被忽略，**不做别名**，避免"看起来支持、实际规则不同" | 契约测试：带 `wd=x` 的响应与不带时相同 |

### 3. 第一批参数范围

| 参数（API） | 旧参数 | 首批 | 匹配 | 说明 |
|---|---|---|---|---|
| `categoryId` | 标签 `type` / URL `tid` | ✅ 已有 | 本身 + 可见直接子分类 | 语义沿用 1.0.0；SSR 首批不认 `tid`（第 7.2 节） |
| `keyword` | `wd` | ✅（开关控制） | `vod_name`/`vod_sub`/`vod_en` 任一包含 | 字段集合见 D1 |
| `area` | `area` | ✅ | `vod_area =` 单值 | |
| `lang` | `lang` | ✅ | `vod_lang =` 单值 | |
| `year` | `year` | ✅ | `vod_year =` 4 位单值 | 区间暂缓 |
| `level` | `level` | ✅ | `vod_level =` 1..9 | 也解决首页推荐位只能排序近似的问题（03 方案 3.2） |
| `classes` | `class` | ✅（开关控制） | `vod_class` 包含 | 题目中的"类型"；与响应字段同名 |
| `sort`/`order` | `by`/`order` | ✅ 已有 11 种 | — | `rnd` 暂缓（旧实现是随机页/随机 id 段，`Vod.php:502-537`） |
| `page`/`pageSize` | `page` | ✅ 已有 | — | 上限不变 |
| 暂缓 | `actor`、`director`、`tag`、`name`、`letter`、`state`、`version`、`isend`、`ids`、`timeadd` 等、年份区间、任何逗号多值 | ❌ | — | 演员/导演/标签都是包含匹配，成本与 `keyword` 同级，待第 6.5 节测量后再评估；`letter/state/version` 是索引列精确匹配，可在下一批低成本补上 |

### 4. 参数规则（明确决策）

适用于 6 个新参数；已有参数规则不变。草案 `info.description` 与各参数描述是同一套规则。

| 规则 | 决策 | 与旧版 |
|---|---|---|
| 空白 | 先去掉首尾空白（ASCII 空白、U+00A0、U+3000）；中间的空白原样保留，不合并 | 旧版只 `trim` ASCII 空白 |
| 空值 | 去空白后为空 → 按"未传"处理（与契约 1.0.0 空串规则一致） | 旧版 `empty()` 同样视为未传；但旧 `'0'` 也被丢弃，Java 中 `level=0` → 400、`area=0` 按字面值 `0` 匹配 |
| 重复参数 | 每个新参数最多出现一次，否则 400（field=参数名）。实现时用 `getParameterValues` 显式判断，不依赖 Spring 的逗号合并 | 旧版取后一个 |
| 多值 | 首批不支持。值含半角 `,` → 400 `不支持多个取值`；全角 `，` 视为普通字符 | 旧版逗号即多值（精确字段 IN、包含字段 OR） |
| 未知参数 | API 继续忽略（契约 1.0.0）；SSR 规则见第 7.2 节 | — |
| 字符集 | 只接受合法 UTF-8 解码结果；含控制字符（U+0000–U+001F、U+007F–U+009F）或 U+FFFF 以上字符 → 400。后者在 utf8 列中不可能存在，拒绝可避免 utf8/utf8mb4 比较时的排序规则转换问题 | 旧版原样进 SQL |
| 长度（码点） | `keyword` 1..64（与现有表单 `maxlength=64` 一致）、`area` 1..20、`lang` 1..10（与列宽一致）、`classes` 1..32；超长 → 400，**不截断** | 旧版超过 `search_len`（默认 10）静默截断 |
| 格式 | `year` 须 `^[0-9]{4}$`；`level` 须 `^[1-9]$` | 旧版 `intval` 宽松解析（`9abc` → 9）、非 4/9 位年份不筛选 |
| 大小写/全半角/规范化 | 不做任何转换；大小写、尾随空格由列排序规则 `utf8_general_ci` 决定（不敏感）；全角/半角、繁简、NFC/NFD 不归一 | 与旧版一致（同样依赖列排序规则） |
| 解码次数 | API 查询参数由容器解码**一次**；不再 `urldecode` | 旧版二次解码，`+` 变空格 |
| 实体 | 输入中的实体**不解码**（`&amp;` 就按 5 个字符匹配）。`keyword` 含 `& " ' < >` 时，另生成 `mac_filter_xss` 形式（`&amp; &quot; &#039; &lt; &gt;`，`application/common.php:1222-1225`）各匹配一次，两组为 OR；不含这些字符时只匹配原值。`classes` 不做实体变体 | 旧版只用 `htmlspecialchars` 后的形式匹配（且 `'` 是否编码随 PHP 版本变），库内原样存的 `Tom & Jerry` 搜不到。库内各字段实际存储形式**【待核实】**（07 文档 4.1） |
| LIKE 字面语义 | 包含匹配一律 `col LIKE ? ESCAPE '!'`，参数在 Java 中构造为 `'%' + esc(v) + '%'`，`esc` 依次把 `!`、`%`、`_` 前加 `!`。反斜线不是转义符，按普通字符匹配（不受 `NO_BACKSLASH_ESCAPES` 影响） | 旧版不转义：`%`、`_` 是通配符 |
| 精确匹配 | `col = ?`；`year` 以字符串比较（`'2020'`） | 旧版 `year` 以整数比较，`'2020年'`、`' 2020'` 等也会被数值转换命中 |
| 字段白名单 | 列名只出现在 Mapper XML 中，与参数一一固定；不接受任何用户提供的列名、排序表达式或 SQL 片段 | 旧版 `search_vod_rule` 由后台配置拼进 where 键名 |
| 组合 | 所有条件 AND；`keyword` 内部各列/各变体 OR；与 `categoryId` 组合时先做分类可见性检查（不可见 → 404，不管其他条件） | 与旧版一致（旧版条件也是 AND） |
| 开关 | `maccms.content.search.enabled=false`（默认）时 `keyword`、`classes` 非空 → 400 `当前未开放关键字搜索`；精确匹配参数不受影响 | 对应旧 `app.search`，但默认关闭（第 6.4 节） |

### 5. 接口草案摘要

完整定义见草案。路径与方法：`GET /api/v1/videos`（扩展），无新路径。

| 状态 | code | 何时 | 是否新增情形 |
|---|---|---|---|
| 200 | — | 成功；无结果时 `items=[]`、`total=0`（不是 404） | 否 |
| 400 | `INVALID_PARAMETER` | 已有参数非法（不变）；新参数超长/格式不符/重复/含逗号或非法字符/开关关闭时用 `keyword`、`classes` | 新参数带来的新情形 |
| 404 | `CATEGORY_NOT_FOUND` | `categoryId` 不存在或不可见 | 否 |
| 503 | `SERVICE_UNAVAILABLE` | 数据库不可用（不变）；带任一新参数时语句超过服务端时限；带 `keyword`/`classes` 时并发达到上限 | 后两种为新增原因，响应体不变 |
| 500 | `INTERNAL_ERROR` | 其他异常 | 否 |

- 字段安全：响应只用既有 `VideoSummary`，不新增播放/下载地址、密码、会员身份、授权字段；`detailPasswordRequired` 仍只是布尔值。
- 权限：游客身份不变；分类可见性、游客列表权限（`maccms.content.enforce-guest-popedom`）沿用 `CategoryCatalog`。
- 排序稳定：所有组合都追加 `vod_id` 同向排序；同一组条件下 count 与分页使用同一 SQL 片段。
- 错误 `message` 为固定文案，不回显用户输入；日志不记录参数值。

### 6. SQL 安全与性能

#### 6.1 共享谓词（拟实施，写在 `VodMapper.xml`）

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
- 新 application 类型（建议名 `VideoFilter`，不可变 record，构造器与 `parse` 同一套校验）放在 `content.application`，不放进 `.view`（它不是响应类型）；`VideoListQuery` 增加该字段并保留现有 5 参数构造器（`filter=VideoFilter.NONE`），site 等调用方不必改动。

#### 6.2 现有结构的成本（定性分析，未测量）

| 查询形态 | 预期执行方式 | 风险 |
|---|---|---|
| 仅 `categoryId`/排序（现状） | `type_id` 索引或按排序列索引回表 | 不变 |
| `area`/`lang`/`year`/`level` 精确匹配 | 优化器可在 `type_id`、`vod_area` 等单列索引间选一个，或 index_merge；结果再 filesort | 低基数列（如 `vod_lang='国语'`）可能退化为扫描；JDBC 参数为 utf8mb4、列为 utf8 时 MySQL 5.7 能否用索引**须 EXPLAIN 确认** |
| `keyword`/`classes` 包含匹配 | `'%…%'` 不能用 B-tree，`vod_sub` 也无索引 → **扫描所有 `vod_status=1` 的候选行**；按排序列索引倒序走时，命中少的关键字可能把整个索引走完（MyISAM 随机回表比顺序扫描更慢） | 主要成本来源 |
| COUNT | 带 WHERE 时 MyISAM 不能用表行数缓存，与分页查询各扫一次（每次请求 ≈ 2 次扫描） | 成本翻倍 |
| 深分页 | offset 越大，需要跳过的命中行越多；最坏仍受"扫描全部候选行"约束 | 已有上限 page≤1000 |
| **MyISAM 表锁** | 长 SELECT 持有表读锁；PHP 的点击计数 `UPDATE mac_vod`（每次详情页 `ajax/hits`，03 方案 6.3）、采集、后台写入要等它结束；排队的写锁又会挡住后续所有读 → 全站读写堆积 | **最大风险**，必须测量（6.5） |
| 行格式 | `mac_vod` 含 `vod_content`、`vod_play_url` 等 mediumtext，动态行格式下扫描要读整行 | 扫描成本与正文/播放地址体积成正比，合成数据须模拟体积 |

#### 6.3 成本控制（不需要 DDL、依赖或外部服务）

1. **服务端语句时限**：带新条件的 count/page 语句加 `MAX_EXECUTION_TIME(3000)`（MySQL 5.7.8+ 只对 SELECT 生效）。超时后 MySQL 报错 3024；实施时须在真实 MySQL 上确认 Connector/J/Spring 的异常类型能被 `ContentReadService.read` 归为 503（若翻译结果不是 `TransientDataAccessException` 子类，就在 `read` 中按类型链补一条映射，并补日志脱敏测试）。既有的 MyBatis 全局 10 秒 JDBC 超时保留为兜底。MariaDB 或 5.7.8 以下会忽略该提示（线上版本**【待核实】**）。
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
| 库 | 由管理账号新建 `maccms_perf_<yyyyMMddHHmmss>_<8位随机>`，导入 `install.sql` 表结构（MyISAM/utf8，与旧系统一致），结束后只删除该库。**不读写 `maccms_dev` 或任何已有库**，不改服务器全局变量 |
| 规模 | `mac_vod` 三档：10 万、50 万、100 万行；`mac_type` 30 个分类（含停用、游客无权限的分类）；`mac_group` 游客组按 `initdata.sql` 形式 |
| 分布 | `vod_status=1` 约 95%；名称由合成中文词库组合（含少量英文、`%`/`_`/`!`/`\`/`&` 等特殊字符样本）；`vod_area/lang/year` 取 `maccms.php:84-86` 列表并偏斜分布；`vod_level` 约 98% 为 0；`vod_class` 1–3 个逗号分隔的值；`vod_content` 平均约 2 KB、`vod_play_url` 平均约 1 KB（模拟动态行体积）。生成脚本与种子提交到测试目录，数据不提交 |
| 查询形态 | 对每档规模、分别 `sort=time desc` 与 `sort=hits desc`：无条件（基线）、单精确条件（area/lang/year/level 各一）、精确组合、高频关键字（命中约 10%）、低频关键字（命中 < 0.01%）、无命中关键字、`classes`、关键字 + 分类、第 1/50/1000 页 |
| 记录 | 每个形态：`EXPLAIN` 全部列（type、key、rows、filtered、Extra）；冷/热各 N 次（冷启动用 `FLUSH TABLES`，仅限该一次性实例的本机 root）的 p50/p95/最大耗时；`SHOW SESSION STATUS` 的 `Handler_read_*`、`Created_tmp_disk_tables`、`Sort_merge_passes` 增量；Java 进程的 CPU 与堆（JFR 或 `jcmd`）；机器 CPU/内存/磁盘型号与 MySQL 版本 |
| 表锁实验 | 一边以 1/4/8 个并发运行低频关键字搜索，一边以固定速率执行 `UPDATE mac_vod SET vod_hits=vod_hits+1 WHERE vod_id=?`（只在该一次性库），记录 UPDATE 延迟分布、`Table_locks_waited`/`Table_locks_immediate` 增量与读请求堆积情况；验证 `MAX_EXECUTION_TIME` 与并发上限生效（503 计数） |
| 目标（非断言） | 参考目标：50 万行时单次带关键字请求（count+page）热态 p95 < 1 s，并发 4 时 UPDATE 等待 p95 < 1 s。**这是决定是否开启的参考，不写成自动测试断言，也不对外宣称吞吐或延迟** |
| 超限 | 任何形态超过 3 s 或出现明显锁堆积 → 按 6.3 第 4 条逐项处理，并在交付评论中给出数据与建议，由队长决定 |
| 产出 | 一份测量报告（规模、命令、原始数据、EXPLAIN、结论），只读证据；测量脚本不进 `verify` |

### 7. 前台对接方案（SSR 搜索页）

#### 7.1 路由与开关

- 只在 `maccms.site.enabled=true` **且** `maccms.site.search.enabled=true` 时注册：`/index.php/vod/search`、`/index.php/vod/search.html`、`/index.php/vod/search/**`；`maccms.site.url.rewrite=true` 时另加 `/vod/search`、`/vod/search.html`、`/vod/search/**`。GET/HEAD；其他方法维持全局 405。
- 页面调用 `ContentReadService.listVideos`（不新增 site 侧 SQL/Mapper），因此 SSR 的关键字/扩展分类同时依赖 `maccms.content.search.enabled`；后者关闭而请求带 `wd`/`class` 时，content 返回 400 → 页面按 7.4 显示 400。
- 表单地址**不变**：外壳搜索框一直提交到旧地址 `/index.php/vod/search.html?wd=…`。所谓"改指向 Java"不是改模板，而是该路径由谁处理：本地开启 `maccms.site.search.enabled` 后由 Java 处理；部署时由 nginx 决定（**本批不做 nginx 分流、不 301、不实现交还**）。开关关闭时该路径在 Java 中不存在（本地 404），与现状一致；本地 404/503 不代表已部署的降级行为。

#### 7.2 URL 与查询串解析（`LegacyUrlParser` 新增 `parseSearch`，拟实施）

| 项 | 决策 |
|---|---|
| 支持形态 | 默认 PATHINFO：`/index.php/vod/search[/<键>/<值>]….html`（也接受无后缀或末尾 `/`，即 `suffix_hide`）；`rewrite=true` 时不带 `index.php` 的同样形态；`?s=/vod/search/…` 兼容模式不认（交 PHP） |
| 不认识 → 交 PHP（本地 404） | 路由别名 `vodsearch/…`（14 个位置参数用 `-` 连接，值本身可含 `-`，有歧义）、`-` 分隔符形态、大写控制器、`.htm` 等其他后缀；参数含旧站支持但 Java 首批不支持的键：`actor`、`director`、`tag`、`letter`、`state`、`version`、`tid`、`name`、`ids`、`isend`、`ajax`；`by=rnd` |
| 认识的键 | `wd`→`keyword`、`area`、`lang`、`year`、`level`、`class`→`classes`、`by`→`sort`（11 种）、`order`、`page`。与 `LegacyUrlBuilder.SEARCH_KEYS` 的交集，保证生成器输出可被解析回来 |
| 参数来源 | 路径参数与查询串**都接受**（表单提交是查询串，分页/筛选链接是生成器输出的路径参数）。同一个键同时出现在两处或在任一处重复 → 400；其他未知键（不在旧参数集合中，如 `utm_source`）→ 400 |
| 解码 | 每个值只解码**一次**。查询串按 `application/x-www-form-urlencoded`（`+` 为空格，`%HH` 必须完整且结果为合法 UTF-8，否则 400）；路径值按同一规则解码（与生成器 `urlencode` 的 `+` 互逆），路径段本身仍须通过 `SitePaths`（拒绝 `%2F`、`%5C`、`%2E`、控制字符、空段、`.`/`..` 段）。值中的 `/` 只能经查询串提交 |
| 值校验 | 解码后交给 content 的同一套规则（第 4 节），SSR 不另写一份；`page` 1..1000、无前导 0；`level=0`、`page=0` → 400 |
| 长度 | 路径与查询串各自 > 1024 字符 → 不认识（与现有解析器一致）；`wd` 64 个汉字编码后约 576 字符，在上限内 |
| base-url 子目录 | 与三页相同：反向代理把前缀映射到应用根，解析器输入不含前缀；页面内链接经 `SiteSettings.publicPath` 加前缀，canonical 只用 `base-url`，不读 Host/X-Forwarded-* |
| 新路由类型 | `SiteRoute.VodSearch(SearchFilters filters, int page)`；`LegacyUrlBuilder.path` 对其输出 `vodSearch(filters, page)` |

#### 7.3 页面内容

| 区域 | 方案 |
|---|---|
| 无任何条件（只有 `/index.php/vod/search.html`） | 200，显示搜索表单与提示，不查库 |
| 有条件 | 一次 `listCategories()`（导航与候选值）+ 一次 `listVideos(filter, sort, order, page, 20)`；标题"搜索：<关键字>"或"筛选结果"，显示"共 N 条" |
| 表单 | `GET`，`role="search"`，`<label>` 关联输入框，`name="wd"`、`maxlength="64"`，回显经校验的关键字（jte 转义）；当前筛选以隐藏字段保留，提交关键字时 `page` 重置为 1 |
| 筛选链接 | 地区/语言/年份/扩展分类各一组普通 `<a href>`（无 JS 可用），候选值取所有可见分类 `extend` 中对应值的并集（去重、保持出现顺序、每组最多 30 个、跳过不满足第 4 节规则的值）；当前值 `aria-current="true"`，每组带"全部"链接（去掉该条件）。推荐等级首批不出筛选链接（仍可经 URL 使用） |
| 排序 | 时间 / 人气（`hits`）/ 评分 三个链接，其余 `by` 值仍可经 URL 使用 |
| 结果与分页 | 复用 MACC-13 `videoCard`、`pagination` 组件；所有链接由 `LegacyUrlBuilder.vodSearch` 生成（键排序、第 1 页不带页码），经 `publicPath` 输出 `SafeUrl` |
| canonical | 自引用：`vodSearch(规范化后的条件, page)` 经 `SiteSettings.canonical`；非规范形态（查询串、键顺序不同、`page/1`）照常渲染，只输出 canonical，不 301 |
| robots | 所有搜索页（含无条件页）`<meta name="robots" content="noindex">` |
| 缓存头 | `Cache-Control: no-store`（关键字在 URL 中，不进入共享缓存；生产 TTL 另议） |
| 日志 | 不记录关键字、查询串、请求头；异常只经 `SafeExceptionLog`。部署时 nginx 访问日志会记录完整 URL（含关键字）——列入部署评审项 |

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

`vod/show`（筛选页，见 D3）、`ajax_search`/`ajax_show`、`?s=` 形态的搜索、路由别名与 `-` 分隔符、第 7.2 节列出的未支持参数、`rnd`、文章/演员/专题等其他模块的搜索、热搜词（`search_hot`）与 `ajax/suggest`、带会员 cookie 的请求（03 方案第 2 节）。

### 8. 实施拆分

#### T1 content API/SQL 与真实 MySQL 验证（后端）

| 项 | 内容 |
|---|---|
| 前置 | 本方案审查通过、D1 定案 |
| 文件（同一提交） | `content/application/VideoFilter.java`（新）、`VideoListQuery.java`、`ContentReadService.java`（开关、并发上限、503 映射）、`ContentProperties.java`（`search.enabled`、`search.max-concurrent` 及校验）、`content/api/VideoController.java`（新参数、重复检测）、`content/infrastructure/VodMapper.java`、`resources/mapper/content/VodMapper.xml`、`application.yml`、`config/maccms-backend.env.example`、`docs/contracts/content-read.openapi.yaml`（合并本草案，版本 1.1.0）、删除 `docs/contracts/content-search-filter.draft.openapi.yaml`、`docs/architecture/02-agent-development-rules.md`（`VideoListQuery` 取值范围说明）、本文件状态更新 |
| 测试（同一提交） | `VideoFilterTest`（每条第 4 节规则正反例、LIKE 转义、实体变体）、`ContentApiContractTest`（新参数 200/400/503 与响应体、`wd=x` 被忽略、旧用例不改）、`VodMapperBoundSqlTest`（无新参数时 SQL 逐字不变；有参数时只出现 `#{}` 占位与固定列名）、`ContentApiMySqlIT`（真实 MySQL：每个条件、组合、`%`/`_`/`!`/`\` 字面匹配、实体变体、utf8 大小写不敏感、不可见分类/停用分类/未审核视频不出现、带条件分页无重复无遗漏、count 与列表一致、`MAX_EXECUTION_TIME` 超时被映射为 503）、`ReadOnlyGuardrailsTest`/`ModuleBoundaryTest` 保持通过、新增 503 分支的日志脱敏测试 |
| 验收 | `./mvnw verify` 与 `./mvnw verify -Pmysql-it` 通过（PowerShell 与 Git Bash 各一次）；集成测试前后一次性库表 `CHECKSUM` 不变 |
| 只读证据 | 集成测试日志中一次性库的创建/删除记录与 CHECKSUM 对比；不连 `maccms_dev` |
| 回滚 | `git revert`；或保持 `search.enabled=false`（精确筛选仍可用）；无数据回迁 |

#### T2 一次性合成库性能测量（后端，可与 T1 同一任务的后续提交）

| 项 | 内容 |
|---|---|
| 前置 | T1 合入 |
| 文件 | `backend/src/test/perf/`（数据生成与测量脚本，不进 `verify`）、`docs/architecture/09-…`（附测量报告或新增报告文件） |
| 验收 | 按第 6.5 节完成三档规模与表锁实验，报告给出原始数据与开关建议；不对外宣称吞吐 |
| 回滚 | 只有脚本与文档，删除即可 |

#### T3 URL 解析与 SSR 搜索页（后端）

| 项 | 内容 |
|---|---|
| 前置 | T1 合入；T2 报告给出"可开启"或限定条件（页面可先实现，开关默认关闭） |
| 文件 | `site/application/LegacyUrlParser.java`（`parseSearch`）、`SiteRoute.java`（`VodSearch`）、`LegacyUrlBuilder.java`（`path` 分支）、`SiteSearchProperties` 或扩展现有站点配置、`site/web/SiteContentController.java`（条件注册路由）、`site/web/ContentPages.java`、`site/web/view/SearchPage.java` 等视图模型、`jte/site/pages/search.jte`、`SiteExceptionHandler`（搜索页 400 视图）、`application.yml`、env 示例、`docs/architecture/08-site-pages.md` 或新文档、URL 向量文档 |
| 测试 | PHP 黄金向量扩展：`backend/src/test/php/mac_url_golden.php` 增加 `vod/search` 解析向量（只执行旧 URL/路由代码，不连库、不发请求），Java 对每条认得的向量与 PHP 调度及 `wd/area/lang/year/level/class/by/order/page` 一致；`LegacyUrlParserTest`（路径 + 查询串、重复/冲突、编码、`+`、`%2F`、超长、不认识的键交 PHP、往返 `parse(build(x)) == x`）；`SiteSearchPagesMvcTest`（200/400/404/503/500、noindex、no-store、canonical、Host 注入、日志不含关键字）、`SiteContentHtmlStructureTest` 扩展（表单标签、筛选链接名称、`aria-current`）、`SiteContentRealHttpTest` 扩展；开关关闭时路由不存在 |
| 浏览器验收 | `site-pages-check.mjs` 增加搜索页：320/360/1280 px 无横向溢出、Tab 可达所有筛选与分页、无 JS 可用、焦点可见 |
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
| 关键字实体 | 只匹配 `htmlspecialchars` 后的形式（`'` 随 PHP 版本） | 原值 + `mac_filter_xss` 形式两者 OR |
| 超长 | 按 `search_len`（默认 10）截断 | 400，不截断；上限 64 |
| `%`/`_` | 通配符 | 字面字符 |
| 多值 | 逗号多值 | 400（首批） |
| 年份 | 4 位或 9 位区间，整数比较，其他长度不筛选 | 只接受 4 位，字符串比较 |
| `level` | `intval`，0 视为未传 | 1..9，其他 400 |
| 重复/冲突参数 | 取后一个，查询串覆盖路径 | 400 |
| 未知参数 | 忽略 | API 忽略；SSR 400 |
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

以下为普通实现选择，本方案已给出推荐、无需用户决定：关键字上限 64、单值、`ESCAPE '!'`、`noindex`/`no-store`、SSR 400 页、并发默认 4、时限 3000 ms、候选值来自分类 `extend`。

### 11. 风险与未验证

- **全部未测量**：第 6.2 节是定性分析，没有任何耗时、吞吐或锁等待数据；是否开启包含匹配取决于 T2。
- **MyISAM 表锁**可能让长搜索阻塞 PHP 的点击计数与采集写入并造成全站堆积；并发上限与时限是缓解，不是消除。
- utf8 列与 utf8mb4 连接参数比较时能否用索引、`MAX_EXECUTION_TIME` 超时的异常翻译，都要在真实 MySQL 上确认。
- 线上配置（`search`、`search_len`、`search_vod_rule`、`search_verify`、`search_timespan`、`show`、`popedom_filter`、`vod_search_optimise`、URL 形态）、MySQL 版本、数据量与分布、字段存储的实体形式均**【待核实】**。
- 模板缺失：旧搜索页标签属性（每页条数、默认排序、是否带分类）**【待核实】**；新版每页固定 20、默认 `time desc`。
- 关键字出现在 URL 中，部署时 nginx/CDN 访问日志会记录；需在部署评审中处理。
- 本方案只是设计；后端/前端实现由队长审查并确定方案后另行派发。
