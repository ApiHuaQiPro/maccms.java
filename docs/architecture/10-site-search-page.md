## SSR 搜索页：URL 解析与页面实现记录（MACC-20，09 方案 T3）

> **状态：已在本地实现并通过自动测试与浏览器实测，未部署、未切流量，默认关闭。** 依据：`docs/architecture/09-content-search-filter-plan.md`（以下简称"09 方案"）第 7 节与第 8 节 T3；T2 测量（MACC-19，`docs/architecture/09-content-search-filter-perf-report.md`）结论为包含匹配**保持关闭**，本任务未改任何默认值。
> 分支基于父任务主线 `agent/codex/macc-2@670b052`（含 MACC-16/17/18/19/22），以合并提交并入。

### 1. 开关与路由

| 配置项 / 环境变量 | 默认 | 作用 |
|---|---|---|
| `maccms.site.search.enabled` / `MACCMS_SITE_SEARCH_ENABLED` | `false` | true 时注册搜索页路由；需同时 `maccms.site.enabled=true`。只接受 true/false（不区分大小写），其他值启动失败、不回显取值；site 关闭时不校验本项（`site.web.SiteSearchEnabledCondition`） |
| `maccms.content.search.enabled`（既有，MACC-18） | `false` | 关键字 `wd`、扩展分类 `class` 是包含匹配，仍受它控制：关闭时搜索页对这两个参数返回 400，并且不输出"类型"筛选链接；`area/lang/year/level` 精确筛选与排序不受影响 |

- 开启时 `site.web.SiteSearchController` 只注册 `/index.php/vod/search`、`/index.php/vod/search.html`、`/index.php/vod/search/`，以及 `/vod/search`、`/vod/search.html`、`/vod/search/`（后三种只在 `maccms.site.url.rewrite=true` 时被解析器认可，否则 404 页）。不注册 `/vod/search/**`：路径参数形式归 PHP。GET/HEAD；其他方法维持全局 405。
- 关闭时这些路径在 Java 中不存在（落到全局 JSON 404，与 MACC-14 现状一致），`SiteSearchSwitchMvcTest` 锁定。外壳搜索表单的地址不变（仍是 `vodSearch(Map.of(), 1)` 的基址），由谁处理取决于开关与部署分流（本批不做 nginx 分流、不做交还）。
- 站点关闭（`maccms.site.status=closed`）时搜索页同样被 `SiteStatusInterceptor` 拦截为 503 维护页，不查库。

### 2. 解析：`LegacyUrlParser.parseSearch(rawPath, rawQuery)`

输入是 `getRequestURI()`/`getQueryString()` 的原始值；控制器不调用 `getParameter`，所以 Tomcat 不会因为非法百分号编码拒绝整个查询串（真实 Tomcat 测试锁定）。不读 Host、X-Forwarded-*。步骤与 09 方案第 7.2.3 节一一对应：

1. 原始路径 ≤ 1024（现有 `MAX_INPUT`）且恰为上述固定路径之一；URL 形态为路由别名或 `-` 分隔符时一律不认 → empty（交 PHP，本地 404）。
2. 原始查询串 ≤ `MAX_SEARCH_QUERY` = 2048 → 否则 empty。三页仍是 1024 且仍拒绝查询串。
3. 按 `&` 切分、跳过空项；键不解码、逐字比较（`WD`、`w%64`、`wd[]` 都不是 `wd`）。
4. 出现 `s`（ThinkPHP 兼容模式）或 `actor/director/tag/letter/state/version/tid/name/ids/isend/ajax` → empty。
5. 按出现顺序逐项：未知键 → 400 `field=query`；同键第二次出现 → 400；值按表单规则**只解码一次**（`+` 为空格，`%HH` 大小写均可，`H` 只认 ASCII `0-9`/`A-F`/`a-f`——全角 `%４１`、阿拉伯-印度数字 `%٤١` 不是转义，判 400；`?s=` 兼容路径同一解码规则，此类值交 PHP），原始字符不在 0x21–0x7E、`%` 不完整或结果不是合法 UTF-8 → 400。
6. `by` 去首尾空白后为 `rnd` → empty。
7. 旧站式归一：`wd/area/lang/year/level/class/by/order` 去掉首尾 W 字符（ECMAScript `\s` 集合，与 content 相同），结果为 `''` 或 `'0'` 按未传；`page` 为 `''` 按未传，否则 1..1000、无前导 0。
8. 交给 content 的同一套校验：`VideoListQuery.parse`（`by`→`sort`、`order`）在前，`VideoFilter.parse`（`wd`→`keyword`、`class`→`classes`，码点上限、禁用字符、逗号、年份、`level` 词法与数值；契约 1.3.0 起 `area/lang/class` 接受逗号多值、`year` 接受 9 字符区间，搜索页随之接受，见 14 文档第 2.4 节）在后；开关不在解析器判断，由页面在查库前判断。结果是 `SiteRoute.VodSearch(SearchFilters, page)`。
9. `canonical` = 原始路径等于搜索基址 B，且原始查询串与 `vodSearchLink(条件, page).query()` 逐字相同。非规范请求（键顺序、小写十六进制、`page=1`、首尾空白、空项、`level=09` 等）照常渲染，只输出 canonical，不 301。

`SearchFilters` 是不可变 record：文本值必须满足 content 规则**且是归一的不动点**（不是 `''`/`'0'`、首尾无 W），`level` 为整数，`by`/`order` 为枚举；不满足时 `IllegalArgumentException`。

### 3. 链接：`LegacyUrlBuilder.vodSearchLink(SearchFilters, page)` → `SiteLink`

- 路径 B = 现有 `vodSearch(Map.of(), 1)`（四种形态 `index.php/vod/search.html`、`index.php/vod/search/`、`vod/search.html`、`vod/search/`）；查询串 Q 的键按 `area, by, class, lang, level, order, page, wd, year` 排序，只输出已设置的条件，`page` 只在 > 1 时输出，值用现有 PHP `urlencode`。Q ≤ 1209（超出抛 `IllegalStateException`，由第 4 节码点上限推出不可能）。
- `SiteLink(path, query)` 构造时：路径走现有 `SitePaths.check`（**规则不变**），查询串走新的 `SiteQueries`（键白名单、不重复、值非空且只含 `[A-Za-z0-9._+-]` 与大写 `%HH`、拒绝 `%00-%1F`/`%7F`；`%2F`/`%5C`/`%2E` 只在查询串中允许），`B?Q` 总长 ≤ `SitePaths.MAX_LENGTH` 1280。`SiteSettings.publicPath(SiteLink)`/`canonical(SiteLink)` 拼接后仍由 `SafeUrl` 构造器校验（≤ 2048）。`SafeUrl`、`SitePaths`、`SiteSettings.MAX_BASE_URL_LENGTH` 及其现有方法未改。
- 实测长度（`SearchLinkRoundTripTest`）：四项上限组合 + `year/level/by/order/page=1000` 的 `B?Q` 为 1235/1231/1225/1221；完整 768 字符 `base-url` 下 canonical 为 2003/1999/1993/1989、页内链接 1978/1974/1968/1964；根地址 canonical 1260。
- 不变式：对任意可接受的 `(F, page)`，`parseSearch("/" + B, vodSearchLink(F, page).query())` 回读为同一 `(F, page)` 且 canonical。测试覆盖第 7.2.7 节全部样例、分页 1/2/1000、移除每个键、组合候选值、11 种排序，以及固定种子随机生成的 2000 组条件（四种形态 × 三种完整 base-url）。

### 4. 页面（`site.web.SearchPages` → `pages/search.jte` / `view.SearchPage`）

| 情况 | HTTP | 内容与读取 |
|---|---|---|
| 无任何条件（含所有键归一后为空） | 200 | 标题"搜索视频"、表单与提示；**不查库**、无分类导航；canonical = base-url + B |
| 无条件但 `page>1` | 404 | 不查库 |
| 有条件 | 200 | `listCategories()` 1 次（导航与候选值）+ `listVideos(categoryId=null, sort, order, page, 20, filter)` 1 次；默认 `time desc`；h1 为"搜索：<关键字>"或"筛选结果"，title 另加"- 第 n 页"与站名；"共 N 条" |
| 有条件、第 1 页无结果 | 200 | "没有找到相关视频"，保留表单与筛选 |
| 越界页（`page>1` 且 `total=0`，或 `page>totalPages`） | 404 | 错误页 |
| 参数不合法（解析器或 content 给出 `INVALID_PARAMETER`；含包含匹配开关关闭时的 `wd`/`class`，此时在查库前就判定） | **400** | 搜索页本身的错误状态：h1"搜索条件不合法"、固定提示、空白表单（输入框 `aria-describedby` 指向提示），**不回显任何输入**、无 canonical、无导航、不查库。三页的 `INVALID_PARAMETER → 404` 不变 |
| 不认识的形态（第 2 节步骤 1、2、4、6） | 404 | 现有错误页（部署时应交 PHP） |
| 503（数据库不可用、并发上限、语句时限 3024/1028）/ 500 | 503 / 500 | 现有错误页（`Retry-After: 120`）；500 页不含已取到的数据 |

- 所有搜索页（含 400）`Cache-Control: no-store`、`X-Robots-Tag: noindex` 与 `<meta name="robots" content="noindex">`（`SiteHtmlRenderer.noStore`）；404/503/500 沿用现有错误页（同样 no-store、noindex）。
- 表单：`GET`、`role="search"`、`aria-label="搜索视频"`，`<label for>` 关联、`name="wd"`、`maxlength="64"`，回显经校验的关键字（jte 属性转义）；当前条件中除 `wd`、`page` 外以隐藏字段保留，提交后回到第 1 页。
- 筛选：地区、语言、年份、类型（类型只在包含匹配开启时）各一组普通链接，候选值取所有可见分类 `type_extend` 中对应值的并集（去重、保持出现顺序、每组最多 30 个），跳过 `''`、`'0'`、首尾空白及不满足 content 规则的值；当前值不在候选中时也列出；每组带"全部"；当前项 `aria-current="true"` + `filter-chip--current`。排序：时间 / 人气 / 评分（其余 `by` 可经 URL 使用，未设置时"时间"为当前项）。推荐等级不出链接（可经 URL 使用）。
- 结果卡片与分页复用 MACC-13 组件，只用 `VideoSummaryDto` 的公开摘要字段（名称、备注、海报经 `ImageUrlNormalizer`、详情链接）；没有播放/下载地址、观看密码、会员字段。
- 日志不记录关键字、查询串、请求头；异常只经 `SafeExceptionLog`（测试断言关键字原文与编码形式都不出现在日志中）。部署时 nginx 访问日志会记录完整 URL，列入部署评审。
- **T4 样式约定的 class**：`search-page`（含 `__title`、`__error`、`__hint`、`__results`、`__results-title`）、`search-form`（`__label`、`__input`、`__button`）、`search-summary`、`search-filters`、`filter-group`（`__label`、`__list`、`__item`）、`filter-chip`、`filter-chip--current`、`search-empty`；结果区复用 `video-grid`、`video-card`、`pagination`。本任务未改 CSS。

### 5. 测试与实测

| 测试 | 内容 |
|---|---|
| `LegacyUrlParserSearchTest` | 第 7.2.7 节全部解析向量 × 四种 URL 形态：固定路径、单次解码、`%`/`+`、`%2541`/`%252F` 不再解码、非法 UTF-8、重复/未知/交 PHP 的键、`level` 词法、`page` 规则、2048/2049、四项上限组合、canonical 判定 |
| `SearchLinkRoundTripTest` | 生成与回读不变式、长度断言、三种完整 base-url（根、`/sub/`、总长恰为 768）、固定种子随机条件、`SiteLink` 预算 |
| `SiteQueriesTest` | 查询串白名单（拒绝 `?`、`#`、小写十六进制、控制字符、未知/重复键等），`SitePaths` 仍拒绝 `?` 与编码分隔符 |
| `SearchGoldenVectorsTest` + PHP 黄金向量 `search.q.*` | 旧代码对同一请求也调度到 `vod/search`；不含 `% + & < > " '` 的值两边取值一致；`%41`、`C++`、`Tom & Jerry` 三条差异逐条锁定（见第 6 节） |
| `SiteSearchPagesMvcTest` | 200/400/404/503/500、noindex/no-store、canonical、Host/Forwarded 注入、筛选/排序/分页链接、只读调用参数、开关、日志脱敏 |
| `SiteSearchStatementTimeoutMvcTest` | 真实 `ContentReadService` + mapper 替身：MySQL 3024 与 1028（排序阶段中止，MACC-22）都成为 503 页，页面与日志不含错误 message、SQL 与筛选值 |
| `SiteSearchSwitchMvcTest` | 默认与显式 false 不注册路由、取值校验、site 关闭时不校验、站点维护时 503 不查库 |
| `SiteSearchHtmlStructureTest` | 唯一 h1、标题层级、label、GET 表单、无正 tabindex/伪按钮/脚本、nav 名称不重复、`aria-current`、错误提示关联、T4 class |
| `SiteSearchRealHttpTest` | 真实 Tomcat + 原始套接字：完整缓冲、HEAD、非法编码由解析器判 400、交 PHP 与超长 404、503/500、子目录 base-url、连接池从未初始化 |
| `SiteSearchMySqlIT`（`-Pmysql-it`） | 一次性库 `maccms_it_*`：可见范围、精确筛选、`%` 字面匹配、候选值来自 `type_extend`、分页与越界、400/404、全部表 CHECKSUM/行数前后不变、删除库并确认不存在 |
| `backend/src/test/browser/site-search-check.mjs` | 无 JS：只用键盘 Tab 到搜索框输入并 Enter 提交、结果页 main 内每个链接都可 Tab 到达且焦点可见、Enter 筛选与下一页、400 提示关联（不在 verify 中运行） |

本地浏览器实测（2026-10-16，Edge 无头，一次性库 `maccms_t3_*` + 候选 jar，结束后删库并确认不存在）：`site-search-check.mjs` 全部通过；`site-pages-check.mjs` 对三个搜索地址在 375/768/1280/1440/1920 px 无横向滚动、Tab 顺序与焦点可见、无脚本；请求前后 26 张表 CHECKSUM 与行数不变。MACC-20 修订（ASCII 十六进制）后于同日按同样方式重跑：一次性库 `maccms_t3_*`，关键字 `合成`/`分页样本`（含 Enter 下一页 → 第 2 / 2 页），三个地址五种视口无横向滚动，26 张表 CHECKSUM 与行数不变，删库后确认不存在。

### 6. 与旧 PHP 的差异（本任务实现的 SSR 部分；API 部分见 09 方案第 9、12 节）

| 方面 | 旧 PHP | Java 搜索页 |
|---|---|---|
| 参数位置 | 路径参数与查询串都接受，查询串覆盖路径；搜索页内链接是 PATHINFO | 只认固定路径 + 查询串；生成的链接也只有这一种；路径参数形式、别名、`-` 分隔符交 PHP |
| 解码 | `mac_param_url` 再 `urldecode` 一次：`wd=%2541` 为 `A`、`+` 变空格（`C++` → `C  `） | 只解码一次：`%2541` 为 `%41`、`C%2B%2B` 为 `C++`（黄金向量 `search.q.pct`、`search.q.plus`） |
| 实体 | 值经 `htmlspecialchars`（`Tom & Jerry` → `Tom &amp; Jerry`） | 值不做实体编码，匹配规则见 09 方案第 4.5 节（原值 + 实体形式 OR）（`search.q.amp`） |
| 首尾空白 | PHP `trim`（只去 ASCII 空白与 NUL） | 去首尾 W 字符（含 U+3000、U+00A0 等） |
| `''` / `'0'` | `!empty()` 视为未传 | 同样视为未传 |
| 未知参数 / 重复参数 | 忽略 / 取后一个 | 400 / 400；`s` 与暂缓的旧键交 PHP |
| 非法编码、超长、`level` 越界 | 截断（`search_len`）或静默忽略 | 400（码点上限同 API）；原始查询串 > 2048 交 PHP |
| `page` | `intval`，`<1` 当 1 | 1..1000、无前导 0，否则 400；越界页 404 |
| `by=rnd` | 随机 | 交 PHP |
| 错误 | `error` 跳转页 | 400/404/503/500 完整 HTML 页，400 不回显输入 |
| SEO / 缓存 | 由模板决定；`cache_page` 可缓存 | noindex + 自引用 canonical；no-store |
| 频率限制、验证码、`mac_vod_search` 写库、会员检查 | 有 | 不复刻（D2）；不读写 `mac_vod_search`；只按游客 |
| 交还 PHP 后 | — | 若将来把 Java 生成的链接交给 PHP，含 `%`、`+` 的值会被二次解码成不同的值（部署评审项） |

### 7. 未实施 / 未验证 / 遗留

- **未实施**：nginx 分流、交还 PHP（`X-Accel-Redirect`）、canonical 301、搜索页 CSS（T4）、`vod/show`、`tid` 分类参数、多值筛选、热搜/联想。
- **未验证**：线上 URL 形态与 `search_*` 配置、真实数据下 `type_extend` 候选值分布、真实数据量下的耗时（T2 已测：包含匹配在 10 万行以上不满足参考目标，开关保持关闭）、反向代理子目录映射、浏览器/代理对 2048 字符查询串的限制。
- **依赖**：MACC-22（MySQL 1028 → 503）已并入本分支，排序阶段超时在搜索页上为 503 页（`SiteSearchStatementTimeoutMvcTest`）；包含匹配仍按 T2 结论默认关闭。
- **回滚**：`MACCMS_SITE_SEARCH_ENABLED=false`（默认）即撤下路由；或 `git revert`。不写库，无数据回迁。
