## 首页、分类列表、视频公开详情 SSR（MACC-14）

> **状态：已在本地实现并通过自动测试，未部署、未切流量。** 依据：03 方案第 3 节（三页）、第 5 节（URL 与 canonical）、第 6 节（留在 PHP 的能力）、第 7.3 节（错误处理）；05 骨架；06 图片地址；07 富文本。
> 前置任务（主线 `a96cfd3` 均已包含）：MACC-9 骨架 `a98b53d`、MACC-10 URL 生成/解析 `255c471`（整合 `9413b74`）、MACC-11 图片地址 `b3e7e1c`、MACC-13 组件 `1461539`、MACC-12 富文本 `143af3c`/`ac068f4`/`a96cfd3`。

### 1. 路由

只注册下面这些路径（GET，HEAD 由 Spring 自动支持；其他方法仍是全局 JSON 405）。页面类型、参数是否合法、是不是规范地址，全部交给 `LegacyUrlParser` 判定，输入是原始请求路径（`getRequestURI`，未解码）与原始查询串。

| 注册的路径 | 解析结果 | 页面 |
|---|---|---|
| `/`、`/index.php`、`/index.php/` | `Home`；`/index.php?s=…` 按解析器的兼容模式识别成三页之一 | 首页（或对应页面） |
| `/index.php/vod/type/**`、`/vod/type/**` | `VodType(id, page)`，只认数字 id | 分类列表 |
| `/index.php/vod/detail/**`、`/vod/detail/**` | `VodDetail(id)`，只认数字 id | 视频公开详情 |

- 不带 `index.php` 的形态只在 `maccms.site.url.rewrite=true` 时被解析器认可，否则 404。
- 路由别名（`vodtype/…`、`voddetail/…`）与 `pathinfo_depr='-'` 形态**不注册**（任务要求不实现全部别名），对应配置项也不开放（第 3 节）。
- 解析器返回"不认识"（拼音/编码 id、大写、其他后缀、百分号编码、当前配置下不存在的形态）→ 应"交还 PHP"，交还机制未批准，本地返回 404 页。
- 认得但参数不合法（id/页码为 0、前导 0、越界、未知参数、重复参数、**任何查询串参数**）→ `INVALID_PARAMETER` → 404 页。这些情况都不调用 content 服务。
- 非规范形态（如 `…/id/1/page/1.html`、`…/id/1/`、`?s=` 兼容形态）照常渲染，只输出指向规范地址的 `<link rel="canonical">`，**不 301**（D4 未批准，`canonical-redirect` 仍只接受 false）。

### 2. 页面与视图模型

组装在 `site.web.ContentPages`，模板在 `backend/src/main/jte/site/pages/`。视图模型都在 `site.web.view`（不可变 record），URL 全部是 `SafeUrl`，由 `LegacyUrlBuilder` 生成后经 `SiteSettings.publicPath`/`canonical` 转换。

| 页面 | 模板 / 视图模型 | 数据读取（`ContentReadService`） | 内容 |
|---|---|---|---|
| 首页 | `home.jte` / `HomePage(chrome, heading, categories, sections)` | `listCategories()` 1 次；`listVideos(time desc, 12)` 1 次；前 4 个顶级分类各 `listVideos(categoryId, time desc, 6)` | 分类网格、"最近更新"、分类区块（带"更多"链接）。没有视频的区块不显示；全部为空显示"暂无内容"（200）。两次查询之间分类被停用（`CATEGORY_NOT_FOUND`）时只跳过该区块 |
| 分类列表 | `category.jte` / `CategoryPage(chrome, heading, breadcrumb, subcategories, videos, pagination)` | `listCategories()`；`listVideos(categoryId, time desc, page, 20)` | 面包屑、可见的直接子分类、视频网格、分页组件（上一页/下一页） |
| 视频详情 | `detail.jte` / `DetailPage(chrome, title, subTitle, poster, breadcrumb, metadata, body, blurb, related)` | `getVideo(id)`；`listCategories()`；`listVideos(categoryId, time desc, 7)`（同类推荐，去掉本片取 6 条） | 面包屑、海报、元数据表、正文（过滤后）或简介、同类推荐 |

- 外壳 `PageChrome` 新增 `nav`（顶级分类导航，错误页/维护页为空、不查库）、`searchAction`（旧 PHP `vod/search`，GET 表单）、`loginUrl`（旧 PHP `user/login`，`rel="nofollow"`）。
- **分类页**：只用分类、`page`、`pageSize`（固定 20，旧 `app.page_size` 默认值）与既有排序（固定 `time desc`，旧默认）；`show`/`search`、多条件筛选、排序切换仍归 PHP。title = 分类 SEO 标题（为空用分类名）[+ ` - 第n页`] + ` - ` + 站名；description/keywords 用分类字段，为空用站点值；canonical 自引用（第 n 页指向第 n 页）。分页最多到第 1000 页（与公开 API 上限一致），更大的页不链接。
- **详情页**：title = 视频名 + ` - ` + 站名；description = 简介去掉多余空白后前 150 字；keywords = 标签（为空用站点值）。元数据只列非空项：分类、类型、主演、导演、编剧、地区、语言、年份、上映、片长、状态、集数（`total>0`）、标签、评分（`scoreNum>0`）、豆瓣评分（>0）、更新时间（`Asia/Shanghai`，`yyyy-MM-dd`）。
- 语义 class（MACC-15 样式任务约定）：`site-home`、`category-page`、`category-grid`、`video-grid`、`video-card`（组件）、`pagination`（组件）、`video-detail`、`metadata-list`、`detail-content`。另有 BEM 子类与 `site-nav`、`site-search`、`site-login`、`breadcrumb`，供样式任务按需使用。

### 3. 配置（仍只来自 `maccms.site.*` 环境变量）

| 配置项 / 环境变量 | 默认 | 规则 |
|---|---|---|
| `maccms.site.url.rewrite` / `MACCMS_SITE_URL_REWRITE` | `false` | true/false（不区分大小写），对应旧 `rewrite.status` |
| `maccms.site.url.suffix-hide` / `MACCMS_SITE_URL_SUFFIX_HIDE` | `false` | true/false，对应旧 `rewrite.suffix_hide` |
| `maccms.site.image.protocol` / `MACCMS_SITE_IMAGE_PROTOCOL` | 空（按 http） | 对应旧 `upload.protocol`，校验见 06 第 4 节 |
| `maccms.site.image.mode` / `…_MODE` | 空（本地） | `remote` 时相对路径拼 `remote-url` |
| `maccms.site.image.remote-url` / `…_REMOTE_URL` | 空 | 远程模式必填，规则见 06 第 4 节 |
| `maccms.site.image.img-key` / `…_IMG_KEY` | 空 | 留空不启用；**只接受字母、数字与 `. - _ \| \ /`**（字面域名多选一） |
| `maccms.site.image.img-api` / `…_IMG_API` | 空 | 规则见 06 第 4 节 |

- `pathinfo_depr` 固定 `/`、路由别名固定关闭（首批不承接这两类形态）。
- 校验失败时启动失败，一次列出所有出错的配置项名称，不回显取值（`SiteUrlProperties.toStyle`、`SiteImageProperties.toNormalizer`；后者把 `ImageUrlNormalizer` 的 `upload.*` 名称换成本应用的配置项名）。
- `img-key` 的额外限制是对 07 文档第 8 节"正则回溯"风险的处理：不允许量词、分组、字符类，匹配耗时与地址长度成线性关系。

### 4. 错误、缓存与日志

| 情况 | HTTP | 页面 / 头 |
|---|---|---|
| 正常（含分类第 1 页无视频、首页全空） | 200 | `Cache-Control: no-cache`（生产 TTL 未定）、无 noindex |
| 分类不存在/停用/游客无权限、视频不存在/未审核/分类不可见/游客无详情权限 | 404 | 错误页，`no-store`、noindex |
| 越界页（`total=0 且 page>1`，或 `page>totalPages`） | 404 | 同上 |
| URL 参数不合法、解析器不认识的形态 | 404 | 同上；不查库 |
| 观看密码、版权（`VIDEO_PASSWORD_REQUIRED`/`VIDEO_COPYRIGHT_RESTRICTED`） | 503 | 同骨架：交还 PHP 未批准，不渲染详情、不提示原因，`Retry-After: 120` |
| 数据库连接类故障 | 503 | `Retry-After: 120`、`no-store`、noindex |
| 其他异常（含已取到部分数据后后续查询失败） | 500 | 完整错误页，不含已取到的数据与异常信息 |
| `maccms.site.status=closed` | 503 | 维护页，`Retry-After: 3600`；拦截器在 Controller 之前返回，不查库 |

- 先组装视图模型（读取全部数据）再整页渲染到缓冲区，成功后才写响应（`Content-Length`、非分块）；读取阶段的业务异常保持原来的错误码，不会被渲染器包装成 500。
- 日志只经 `SafeExceptionLog`（类型链与栈帧）；正文过滤结果为 `TOO_LONG`/`UNSTABLE` 时记录 `outcome` 与视频 id，不记录正文。不记录 cookie、请求头、查询串。

### 5. content.application 的数据库故障归一

`ContentReadService` 的三个公开方法在内部捕获 `DataAccessResourceFailureException`、`TransientDataAccessException`（与 `GlobalExceptionHandler` 原有 503 分支的判定相同），记录 `database unavailable: <类型链>`，抛出 `ApiException(SERVICE_UNAVAILABLE, "服务暂时不可用")`。

- 公开 API 的 503 响应体逐字不变：`{"code":"SERVICE_UNAVAILABLE","message":"服务暂时不可用","fieldErrors":[]}`，日志行也相同（`ContentApiContractTest.connectionFailureKeepsExact503Body` 对改动前、后的实现都通过）。
- 其他数据访问异常（如 `BadSqlGrammarException`）不转换，仍由全局处理返回 500；业务异常原样抛出。
- site 因此不需要 Spring DAO 类型，`ModuleBoundaryTest` 对 site 的 `org.springframework.dao` 禁令保持不变。`GlobalExceptionHandler` 的 DAO 分支保留，作为其他调用路径的兜底。

### 6. 输出安全

- 文本字段（视频名、副标题、演员、导演、编剧、简介、备注、地区、语言、年份、类型、标签、分类名/SEO 字段）经 `LegacyTextEntities.decodeOnce` 解码一次，再由 jte 按 HTML 上下文转义；`pubdate`、`duration` 不在 07 文档的字段清单内，不解码。
- 正文只输出 `RichTextSanitizer` 的产物：`RichTextBody`（`SafeHtml` → `gg.jte.Content` 适配）只放在 `<div class="detail-content">` 的元素内容位置。过滤结果按"视频 id + 更新时间 + 原文长度与哈希"缓存在进程内（`RichTextCache`，最多 512 条、过滤结果合计 800 万字符，LRU）。
- 图片只经 `ImageUrlNormalizer`，不合法或为空时显示占位；链接与表单地址都是 `SafeUrl.href()`。
- `vod_color`（标题颜色）不输出：严格模板策略禁止 `style` 属性。
- 页面没有脚本（CSP `script-src 'none'`），不引入 `static/js/home.js`，不链接 `ajax/*`、`user/ajax_*`、`user/visit`、播放、下载、评论等入口；不调用点击、评分、顶踩、观看密码、购买、会员或评论写入口。
- 播放入口：剧集索引接口（03 方案 P5 (a)）与降级链接 (b) 都未批准，详情页**不显示**播放按钮或播放链接。

### 7. 测试（`./mvnw verify`，不需要数据库）

| 测试 | 内容 |
|---|---|
| `SiteContentPagesMvcTest` | 三页正常/空结果/404/参数错误/500/503、非规范形态的 canonical、恶意文本与 URL、Host/X-Forwarded-* 注入、后续查询失败时完整 500 且无半页、日志不含 message/cookie、正文缓存随版本更新、首页查询次数上限 |
| `SiteContentClosedMvcTest` | 站点关闭时三页（含非法参数地址）503 维护页，content 服务零调用 |
| `SiteContentRewriteMvcTest` | rewrite + suffix_hide + base-url 子目录下的链接与 canonical；不注册的别名 404 |
| `SiteContentRealHttpTest` | 完整应用 + 真实 Tomcat + 原始套接字：完整缓冲、HEAD、错误页、canonical、连接池从未初始化 |
| `SiteContentHtmlStructureTest` | lang、viewport、唯一 h1、标题层级、跳到主要内容、图片 alt、链接与表单名称、无正 tabindex、nav 名称、列表语义、样式任务约定的 class |
| `SitePageConfigTest`、`SiteStartupTest` | URL/图片配置校验与启动失败不回显取值、`img-key` 限制 |
| `RichTextCacheTest` | 命中、原文变化重算、按条数/字符数淘汰、并发 |
| `ContentReadServiceUnavailableTest`、`ContentApiContractTest` | DB 故障归一的范围、日志脱敏、公开 API 503 响应体不变 |

另有浏览器验收脚本 `backend/src/test/browser/site-pages-check.mjs`（不在 `verify` 中，需要本机 Edge/Chrome 与运行中的合成数据服务，见第 9 节）。

### 8. 与旧版的差异

| 方面 | 旧 PHP | 新页面 |
|---|---|---|
| 页面结构与视觉 | 由模板决定（仓库缺模板） | 新设计；不还原旧站样式 |
| 首页区块 | 模板标签决定 | 最近更新 + 前 4 个顶级分类；没有推荐位（契约不能按 `level` 筛选）、热搜、幻灯片、文章/专题 |
| 分类不存在/停用/游客无权限 | error 跳转页 | 404 |
| 详情游客无权限 | error 跳 `user/index` | 404 |
| 越界分页 | 200 空页 | 404 |
| 查询串参数（如 `/?from=x`、`…/id/1.html?page=2`） | 忽略或覆盖路径参数 | 404（解析器不猜）；部署时应由 nginx 留给 PHP |
| 非规范 URL | 各形态都 200 | 200 + canonical，不 301 |
| 观看密码、版权 | PHP 输出密码页/版权页 | 503（交还 PHP 未批准） |
| 播放、剧集 | 播放列表与链接 | 不显示 |
| 评论、评分、顶踩、点击计数 | 页面内交互、`ajax/hits` | 只读显示评分；没有计数 |
| 每视频/每分类模板（`vod_tpl`、`type_tpl`） | 生效 | 不支持 |
| 移动端独立模板 | 可配置 | 响应式单模板 |
| 标题颜色 `vod_color` | 生效 | 不输出 |
| 正文 | 原样输出 | 白名单过滤（差异见 07 第 7 节） |
| 图片地址 | `mac_url_img` | 等价规范化（差异见 06 第 6 节）；`img_key` 只接受字面域名多选一 |
| 时间显示 | 模板决定 | `Asia/Shanghai`，`yyyy-MM-dd` |
| 站点关闭 | 输出关闭模板，未设状态码 | 503 维护页 |

### 9. 本地运行与浏览器验收

```bash
cd backend && ./mvnw -q package -DskipTests
MACCMS_DB_URL=jdbc:mysql://127.0.0.1:1/x MACCMS_DB_USERNAME=x MACCMS_DB_PASSWORD=x \
MACCMS_SITE_ENABLED=true MACCMS_SITE_NAME='MacCMS 本地站（合成）' MACCMS_SITE_BASE_URL=http://maccms.local.test/ \
java -jar target/maccms-backend-0.1.0-SNAPSHOT.jar
```

数据源指向不可达地址时三页都返回 503（验证故障路径）；要看到内容，需连接装有合成数据的一次性库（`maccms_it_*`），不要连接 `maccms_dev` 或其他已有库。浏览器验收脚本对一个运行中的站点检查 375/768/1280/1440/1920 px 下无横向滚动、键盘 Tab 顺序与焦点可见、无 JS 可用，并输出截图；使用方法见脚本头部注释。

### 10. 交还边界、回滚与遗留

- **回滚**：本地阶段 `git revert` 本任务提交即可；不写库，没有数据回迁。关闭 `MACCMS_SITE_ENABLED` 时不注册任何 site 路由。
- **仍交给 PHP**：观看密码、版权（需交还机制）、播放/下载、搜索、筛选（`vod/show`）、登录/会员、评论、计数、拼音/编码 id、路由别名与 `-` 分隔符形态、带查询串的请求。
- **未实现**：交还 PHP 的 `X-Accel-Redirect`、canonical 301、点击计数、生产缓存 TTL、访问日志字段（03 方案 7.4）、nginx 分流、剧集索引。
- **未验证**：线上 URL/图片配置、线上模板与视觉、真实数据中的实体与正文分布（正文可能自带 `<h1>`，导致页面出现多个 h1）、首页多查询在真实数据量下的耗时、并发与内存、nginx 与 base-url 子目录映射。
