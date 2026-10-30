## 旧 URL 生成/解析兼容与 PHP 黄金向量（MACC-10）

> 范围：`site.application` 中的 URL 生成器 `LegacyUrlBuilder`、解析器 `LegacyUrlParser`、形态配置 `LegacyUrlStyle`、页面路由 `SiteRoute`，以及锁定它们的 PHP 黄金向量。不含首页/分类/详情页面实现，不改公开 content API、OpenAPI、SQL、数据库结构、旧 PHP 或部署。依据：`docs/frontend/01-pages-url-compat.md` 第 2 节、`docs/architecture/03-frontend-readonly-plan.md` 第 5 节。

### 1. 向量怎么来的

| 项 | 内容 |
|---|---|
| 生成脚本 | `src/test/php/mac_url_golden.php`。只读 `require` 仓库内 `thinkphp/base.php`、`thinkphp/helper.php`、`application/config.php`、`application/common.php`、`application/extra/maccms.php`、`application/route.php`，在内存中覆盖 URL 配置项后**执行旧代码**；不改任何旧 PHP 文件，不连数据库，不读仓库外配置，不访问网络，`RUNTIME_PATH` 指向不存在的临时目录（不读仓库 `runtime/` 下的路由缓存，也不写文件） |
| 命令（仓库根目录） | `php src/test/php/mac_url_golden.php > src/test/resources/site/url-golden/php-vectors.json` |
| 结果文件 | `src/test/resources/site/url-golden/php-vectors.json`，每行一条向量，不要手改 |
| PHP 版本 | 2026-10-11 用本机 7.2.34、7.3.33、8.0.30 生成，三者**逐字节相同**（提交的是 8.0 的输出）。8.2.32 只有一处不同：向量 `search.wd.html` 往返解析出的 `wd` 中单引号为 `&#039;`（PHP 8.1 起 `htmlspecialchars` 默认带 `ENT_QUOTES`，`mac_param_url` 受影响）；Java 不依赖该值 |
| 合成数据 | 分类 `type_id=1`/`65535`、`type_en=hechengfenlei`；视频 `vod_id=123`/`1`/`4294967295`、`vod_en=hechengshipin`；关键词与筛选值均为合成文本 |

每条向量的字段：

- 生成（`build`）：`id`、`call`（等价的旧函数调用）、`source`（旧代码位置）、`check`（`php-executed` = 旧代码实际执行得到）、`output`（经 `/index.php` 访问时的输出）、`outputViaSiteRoot`（只在经站点根访问时输出不同才出现）、`hostIndependent`（把 Host、X-Forwarded-Host、X-Forwarded-Proto、HTTPS 换成恶意值后输出是否不变）、`roundTrip`（把输出送回旧代码解析的调度结果；`viaRewrite=true` 表示假定部署把整段路径作为 PATH_INFO 交给 `index.php`——这一步**未验证**，属部署行为）。
- 解析（`parse`）：`input`（`pathInfo` = Web 服务器交给 PHP 的已解码 PATH_INFO，或 `s` = `?s=` 参数，`query` = 其他 GET 参数）、`dispatch`（`App::routeCheck` 得到的 控制器/操作，已按 `App::module` 规范化）、`handlerExists`（控制器文件与公开方法是否存在）、`param`（控制器实际使用的 `mac_param_url()` 结果中非空的 id/page/sid/nid/wd/area/year）、`error`（旧代码抛出的 HTTP 错误）。
- 原始 URL → PATH_INFO 的映射（去掉 `/index.php`、百分号解码）是 Web 服务器的行为，**只做了静态核对**，没有经 nginx 实测。

### 2. 配置分支

| 分支 | 配置 | 性质 |
|---|---|---|
| `default` | `pathinfo_depr=/`、`rewrite.status=0`、`route_status=0`、`suffix_hide=0` | **仓库当前默认**（`application/extra/maccms.php:47,488-490`） |
| `rewrite` | `rewrite.status=1` | 条件分支：生成不含 `index.php`；这种地址能否到达 PHP 取决于部署转发 |
| `suffix_hide` | `rewrite.suffix_hide=1` | 条件分支 |
| `route` | `route_status=1`，路由表 = 仓库 `application/route.php` | 条件分支：别名只在此时启用 |
| `rewrite_route` | `rewrite.status=1` + `route_status=1` | 条件分支 |
| `depr_dash` | `app.pathinfo_depr=-` | 条件分支 |

固定前提（所有分支）：`site.install_dir='/'`、`rewrite.type_id=0`/`vod_id=0`（数字 id）、`view.*=0`（动态页）、入口 `index.php` 绑定模块 `index`（`index.php:20`）、ThinkPHP `url_html_suffix='html|htm|shtm|shtml|xml'`（`application/config.php:86`，生成取首项 `html`；`app.suffix`/`path.suffix` 只用于静态化路径）。拼音/编码 id、静态化路径、子目录安装不在本批，Java 不支持。**线上实际配置未核实**（清单 2.4）。

主要输出（全部为旧代码执行结果）：

| 页面 | default | rewrite | suffix_hide | route | rewrite_route | depr_dash |
|---|---|---|---|---|---|---|
| 分类第 1 页 | `/index.php/vod/type/id/1.html` | `/vod/type/id/1.html` | `/index.php/vod/type/id/1/` | `/index.php/vodtype/1.html` | `/vodtype/1.html` | `/index.php/vod-type-id-1.html` |
| 分类第 2 页 | `…/vod/type/id/1/page/2.html` | `/vod/type/id/1/page/2.html` | `…/vod/type/id/1/page/2/` | `/index.php/vodtype/1-2.html` | `/vodtype/1-2.html` | `/index.php/vod-type-id-1-page-2.html` |
| 详情 | `/index.php/vod/detail/id/123.html` | `/vod/detail/id/123.html` | `/index.php/vod/detail/id/123/` | `/index.php/voddetail/123.html` | `/voddetail/123.html` | `/index.php/vod-detail-id-123.html` |
| 首页链接 | `/`（`MAC_PATH`，模板 `{$maccms.path}`，`application/common/controller/All.php:61`），各分支相同 |||||

### 3. 向量揭示的旧行为（均为执行结果）

1. **路由开启后模块路径 404**：`route_status=1` 时，`/index.php/vod/type/id/1.html`、`/index.php/vod/detail/id/123.html`、`?s=/vod/detail/id/123`、`/index.php/index/index.html` 在 `Route::parseUrl` 中抛 404（`thinkphp/library/think/Route.php:1264-1273`：该控制器/操作已定义路由时禁止直接访问）。所以"别名与 PATHINFO 并存"不成立：开启路由后只有别名可达。
2. **`-` 分隔符下 `/` 仍可用**：`pathinfo_depr=-` 时 `vod/type/id/1` 与 `vod-type-id-1` 都解析为分类页（`Route::parseUrlPath` 先把分隔符换成 `|` 再换回 `/` 切分）；默认 `/` 时 `vod-detail-id-123` 不可达（调度到不存在的控制器）。
3. **`?s=` 兼容**：默认配置下 `?s=/vod/detail/id/123`、`?s=vod/detail/id/123.html` 可达；`?s=vod-detail-id-123` 只在 `pathinfo_depr=-` 时可达。
4. **旧解析很宽松**：后缀 `.htm`/`.HTML`、大写控制器、无后缀、末尾 `/`、参数任意顺序、未知参数、`page/0`/`page/abc`（当第 1 页）、`page/2x`（当第 2 页）、`id/1<b>`（`strip_tags` 后为 1）都能到达；重复参数取后一个；**查询串会覆盖路径参数**（`?id=2` 让 `/vod/type/id/1.html` 变成分类 2，因为 `mac_param_url` 合并 `$_REQUEST`）。
5. **分页链接**：分页模板用 `mac_url_page(PAGELINK)`，第 1 页链接为 `…/page/1.html`（路由时 `vodtype/1-1.html`），与无页码的第 1 页地址不同。
6. **首页分页在路由开启时损坏**：`mac_url('index/index',['page'=>2])` 输出 `/index.php/2.html`（`Url::build` 的模块绑定截断吃掉了 `index-` 前缀），往返解析调度到不存在的控制器 `2`。
7. **与请求上下文有关的两处输出**：值里含 `index.php` 时（`search.wd.indexphp`），经站点根访问的页面生成的链接缺少 `/index.php`；`index.pagelink` 的 `/index-PAGELINK.html` 特殊分支只在经站点根访问时生效。其余输出与入口、Host、X-Forwarded-*、HTTPS 无关。
8. **值的编码**：PHP `urlencode`（空格为 `+`，`~` 编码为 `%7E`，`*` 为 `%2A`）；`empty()` 的值（`''`、`'0'`）被丢弃；`suffix_hide=1` 时关键词 `..` 生成 `/../`，`x.html` 生成 `x//`；值含 `/` 编码为 `%2F`，Web 服务器解码后路径被切开，往返解析出的 `wd` 被截断。

### 4. Java 规则

**生成器** `LegacyUrlBuilder(LegacyUrlStyle)`：

- 方法：`home()`、`vodType(id, page)`、`vodDetail(id)`、`path(SiteRoute)`（Java 承接页面），以及只用于链接到 PHP 的 `vodPlay(id, sid, nid)`、`vodSearch(filters, page)`、`vodShow(typeId, filters, page)`、`user(action)`。
- 输出是相对站点根的路径（旧输出去掉开头的 `/`），交给 `SiteSettings.canonical()` / `publicPath()` 变成 `SafeUrl`；canonical 只用配置的 `base-url`，不读请求头。
- 复刻 `mac_url` 的参数预处理（丢弃 `''`/`'0'`、第 1 页不带页码、按键排序）、ThinkPHP `url()`（`/index.php` 根、模块绑定、`urlencode`、空白值跳过、路由反解 `getRuleUrl`）与尾部替换（`index.php/` 去除、`suffix_hide`、`-.`/`/.` 清理）；与旧输出不同时取"经 `/index.php` 访问"的结果。
- 非法输入抛 `IllegalArgumentException`，不输出：分类 id ∉ 1..65535、视频 id ∉ 1..4294967295、页码 ∉ 1..1000、sid/nid < 1、未知参数名、非法 Unicode（孤立代理项）、会员操作名不是 `[a-z_]{1,32}`；以及**输出不是规范站内路径**的情况（`SitePaths`：编码后的 `%2F`、`%5C`、`%2E`、控制字符 `%00-%1F`/`%7F`，空段，`.`/`..` 段）。

**解析器** `LegacyUrlParser.parse(rawPath, rawQuery)`（原始、未解码的请求路径与查询串）：

| 结果 | 条件 |
|---|---|
| 认得 → `Match(route, path, canonical)` | 入口 `/`、`/index.php`、`/index.php/`（首页，或带唯一参数 `s=` 的兼容模式）；`/index.php/<PATHINFO>`；`rewrite=true` 时也认不含 `index.php` 的 `/<PATHINFO>`。PATHINFO 后缀为 `.html`、无后缀或末尾 `/`。默认/`-` 模式：`vod/type/id/<n>[/page/<n>]`（参数顺序不限）、`vod/detail/id/<n>`、`index/index`；`-` 模式同时按 `-` 与 `/` 切分。路由模式：只认 `vodtype/<n>`、`vodtype/<n>-<n>`、`voddetail/<n>`。`canonical=true` 当且仅当没有查询串且路径等于生成器输出 |
| 不认识 → `Optional.empty()`（交给 PHP；交还机制待部署评审，本地等同 404） | 其他控制器/操作（播放、搜索、筛选、会员等）；拼音/编码等非数字取值（含 `page/abc`、`page/2x`）；大写、`.htm` 等其他后缀、`.html.html`；路径中任何百分号编码、`+`、空白、控制字符、反斜线、`//`、`.`/`..` 段；不以单个 `/` 开头（含绝对形式、协议相对）；当前配置下旧站会 404 的形态（路由模式下的模块路径等）；`?s=` 值解码失败或不规范；超长输入（> 1024） |
| 抛 `ApiException(INVALID_PARAMETER)`（既有错误语义，site 显示 404） | 形态认得但参数不合法：未知参数名、重复、不成对、缺 `id`；`0`、前导 0、越界（页码 > 1000、分类 > 65535、视频 > 4294967295）；任何查询串（`?s=` 兼容参数除外），因为旧代码中查询串会覆盖路径参数 |

### 5. 测试

| 测试 | 内容 |
|---|---|
| `LegacyUrlGoldenVectorsTest` | 6 个分支 × 39 条生成向量：Java 输出与 PHP **逐字相同**（160 条），或在有意拒绝清单（理由逐条列出）/本批不实现清单中；新增向量而不补 Java 预期即失败。所有向量 `hostIndependent=true`；只有两条已知向量依赖入口。页面向量的 PHP 往返调度与参数等于 Java 路由，Java 解析生成器输出得到同一路由且 `canonical=true`。6 × 45 条解析向量逐条对照 Java 预期，Java 认得的每一条，旧代码都调度到同一控制器/操作且 id/page 相同（健全性） |
| `LegacyUrlParserTest` | 规范与非规范形态、`?s=`、恶意输入（协议相对、绝对形式、点段、编码斜线/点、控制字符、CRLF、反斜线、非法 UTF-8、`javascript:`）、参数错误的字段名、路由模式、canonical 只来自 `base-url`（含子目录）、超长输入、未覆盖的配置组合被拒绝 |
| `LegacyUrlBuilderTest` | 越界与未知参数、`empty()` 语义、孤立代理项、`urlencode` 与 PHP 一致、恶意关键词在 4 种配置下仍是站内相对路径并通过 `SafeUrl` |
| `SiteSettingsTest` | 站内路径规则扩展到旧编码形态后，仍拒绝 `%2F`/`%2f`、`%2E`、`%5C`、控制字符、小写或不完整的 `%` |
| `SearchGoldenVectorsTest`（MACC-20） | 6 × 14 条 `search.q.*` 解析向量（搜索页的固定路径 + 查询串，`query` 为 Web 服务器已解码一次的 GET 值）：Java `parseSearch` 认得的每一条，旧代码都调度到 `vod/search`；值不含 `% + & < > " '` 时 `wd/area/lang/year/level/class/by/order/page` 取值一致（Java 未设置 ⇔ PHP 为 `''`/`'0'`）；`search.q.pct`（`%41` 对 `A`）、`search.q.plus`（`C++` 对 `C  `）、`search.q.amp`（`Tom & Jerry` 对 `Tom &amp; Jerry`）三条差异逐条锁定；`route_status=1`（旧站 404）与 `pathinfo_depr=-` 分支 Java 不承接。`LegacyUrlGoldenVectorsTest` 中这些向量对 `parse()` 的预期是 unsupported。新增向量只记录 `search.q.*` 的额外参数键，2026-10-16 用 PHP 8.0.30 重新生成，原有向量逐字不变 |

运行：`./mvnw verify`（Git Bash）或 `.\mvnw.cmd verify`（PowerShell），不需要数据库环境变量。重新生成向量需要 PHP 7.2～8.0 的 CLI（8.2 的差异见第 1 节），不是构建的一部分。

### 6. 与旧版的差异

- **分页第 1 页链接**：旧分页为 `…/page/1.html`，Java 用无页码的规范地址；`…/page/1.html` 仍被解析为第 1 页（非规范）。
- **非法输入**：旧代码把页码 0/负数当第 1 页、sid/nid < 1 改为 1、照常输出含 `%2F`/`/../` 的链接；Java 生成器拒绝。旧解析接受的 `page/0`、`id/01`、未知参数、查询串参数等，Java 返回 INVALID_PARAMETER（404），而旧站会照常出页；在交还机制设计后可改为交还 PHP。
- **宽松形态交给 PHP**：大写、`.htm`、拼音 id、非数字页码等旧站可达的形态 Java 不认，由 PHP 处理。
- **依赖上下文的旧输出**：Java 统一取经 `/index.php` 访问的结果（第 3 节第 7 条）。
- 本任务不改变任何运行时行为：生成器/解析器尚未接到页面路由，`LegacyUrlStyle` 未接外部化配置。

### 7. 未验证与遗留

- 线上 `pathinfo_depr`、`rewrite.*`、`route_status`、路由表文本、`view.*`、`install_dir` 未核实；canonical 的规范形态必须以线上配置为准（03 方案 5.2）。
- 不含 `index.php` 的地址（`rewrite=1`）与 Web 服务器对 PATH_INFO 的解码（含 `%2F`）只做了静态核对，没有 nginx 实测。
- `route_status=1` 只覆盖仓库 `application/route.php` 的默认路由表；自定义路由文本、`route_status=1` + `pathinfo_depr=-` 不支持（`LegacyUrlStyle` 构造时拒绝）。
- 拼音/编码 id（依赖 `encode_key`）、静态化路径、子目录安装、`domain.php` 域名绑定不在本批。
- 查询串一律 INVALID_PARAMETER 会让带追踪参数（如 `utm_source`）的链接在 Java 侧 404；切流量前需与交还机制一起评审。
- 配置项接入（`maccms.site.url.*` 之类）与页面路由注册留给三页任务。
