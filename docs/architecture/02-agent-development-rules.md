## 智能体开发规范（新系统 backend/）

适用于在 `backend/`、`docs/contracts/`、`docs/architecture/` 中工作的所有智能体与人工开发者。与根目录 `AGENTS.md` 冲突时以 `AGENTS.md` 为准。

### 1. 契约优先

- **DTO 与 `docs/contracts/*.openapi.yaml` 是前后端唯一契约。** 前端只依据 OpenAPI 开发，不读后端实现。
- 改接口（路径、参数、字段、类型、错误码、状态码、分页或排序规则）必须在**同一个提交**里同时修改：OpenAPI、DTO/Controller、契约测试。
- 契约测试（`OpenApiContract` 校验器）要求响应字段集合与 schema 完全一致：新增字段不写进 schema 会失败，这是有意的——它同时防止字段泄露。
- 已发布字段不改名、不改类型、不删除。确需破坏性变更时，新增版本路径（如 `/api/v2/...`）或经队长/用户批准并通知前端。
- 所有字段必填、不输出 `null`；空值用 `""` / `0` / `[]`。错误响应统一为 `ErrorResponse`，前端按 `code` 分支。

### 2. 模块职责与依赖

- 模块化单体：一个 jar，按包划分模块（规划见 `01-batch1-content-read-plan.md` 第 3 节）。**不建空模块、空服务，不拆微服务。** 新模块在第一个真实需求出现时再建。
- 模块内分层 `api → application → infrastructure`，只能自上而下依赖；**application 不得依赖本模块的 api 包**。Controller 不直接调用 Mapper。
  - 已知例外（MACC-4 遗留，不得新增）：`content.infrastructure.VodMapper` 引用 `content.application.VideoSort` 生成排序白名单。
- 模块之间只能调用对方 `application` 层的公开服务，以及这些服务签名中出现的**已发布只读类型**（`<模块>.application.view` 包下的不可变 record）；不能使用对方的 api 层（Controller、HTTP 专用类型）、infrastructure 层（Mapper、行记录），也不能使用 `application` 下除 `view` 以外的子包。
  - `content` 的已发布只读类型（MACC-8 起）：`content.application.view` 下的 `CategoryListResponse`、`CategoryDto`、`CategoryExtendDto`、`CategoryRefDto`、`VideoSummaryDto`、`VideoDetailDto`，分别对应 `docs/contracts/content-read.openapi.yaml` 中的同名（去掉 `Dto`）schema，Controller 直接以它们作为响应。
    契约 1.2.0（MACC-27）新增：`VideoPlotListResponse`、`PlotDto`、`ArticleContentPageDto`、`ScreenshotDto`、`ArticleSummaryDto`、`ArticleDetailDto`、`TopicSummaryDto`、`TopicDetailDto`、`ActorSummaryDto`、`ActorDetailDto`、`RoleVideoDto`、`RoleSummaryDto`、`RoleDetailDto`、`WebsiteSummaryDto`、`WebsiteDetailDto`、`LinkDto`，对应规则相同；对应的公开服务为 `ArticleReadService`、`TopicReadService`、`ActorReadService`、`RoleReadService`、`WebsiteReadService`、`LinkReadService`、`VideoPlotReadService`（列表参数类型 `ContentListQuery`/`ContentFilter` 与 `VideoListQuery` 一样自带校验）。多值字段解码 `content.application.LegacyMultiValue`（播放/下载分组、文章分页、分集剧情、截图）由 PHP 黄金向量锁定，见 `11-content-read-completion.md` 第 3 节。
  - 已发布只读类型与 `docs/contracts/` 中的 schema 一一对应时，修改它们等同修改契约，按第 1 节在同一提交里改 OpenAPI、类型与契约测试（`PublishedViewContractTest` 校验 record 组件与 schema 字段集合一致）。
  - 跨模块传入的查询参数类型自带校验：例如 `VideoListQuery` 的构造器与 `parse` 采用相同范围（`page` 1..1000、`pageSize` 1..100、`sort` 非空、`categoryId` 为 `null`（不筛选）或 1..65535），越界抛 `ApiException`（`INVALID_PARAMETER`）。调用方不能靠直接构造绕过上限。
  - 搜索/筛选条件 `VideoFilter`（契约 1.1.0，MACC-18；`VideoListQuery.filter`，5 参数构造器为 `VideoFilter.NONE`）同样如此：构造器与 `parse` 使用同一套规则（`docs/architecture/09-content-search-filter-plan.md` 第 4 节）——值是解码一次后的原始字符串，不裁剪、不改写；`keyword` ≤ 64、`area` ≤ 20、`lang` ≤ 10、`classes` ≤ 32 个码点，不含 C0/C1 控制字符、代理项、U+FFFD，首尾不得为 ECMAScript `\s` 空白（1.1.0 时 `area/lang/classes` 不含半角逗号，1.3.0 起按旧版逗号多值，见下）；`year` 恰为 4 位 ASCII 数字；`level` 1..9（原始字符串最多 10 位数字）。"参数重复""非法 UTF-8"只在 HTTP 层判断，由 `content.api` 从容器参数与原始查询串得到；搜索开关（`maccms.content.search.enabled`）由 `ContentReadService` 在查询前再检查一次，其他模块直接构造带 `keyword`/`classes` 的条件也绕不过。契约 1.3.0（B04，`14-video-list-filter-completion.md` 第 3、4 节）在同一类型上新增 `letter/state/version`（逗号多值精确匹配）、`actor/director/tag/name`（逗号多值包含匹配，同样受搜索开关与并发许可约束）、`ids`（`all` 或最多 100 项）与 `year` 的 9 字符区间，并让 `area/lang`（IN）、`classes`（各项包含匹配 OR）接受逗号多值（MACC-28 Q1，长度上限仍作用于整个原始值，只有一项时 SQL 与 1.1.0 相同）；`VideoListQuery.random`（`sort=rnd`）按旧版两种算法取页，随机源是可替换的 `content.application.VideoRandom`。
- `site` 模块（服务端页面，MACC-9 起为骨架，见 `05-site-skeleton.md`）只依赖 `content.application`（含 `.view`）与 `common`——`common` 中限 `common.web` 的 `PageResponse`、`ApiException`、`ErrorCode`、`SafeExceptionLog`，以及 `common.browser` 的 `BrowserExternalAccess`、`BrowserExternalConfiguration`（B02，浏览器侧外部地址放行开关）；另可引用 `legacyconfig.application` 的 `LegacyConfigService`、`LegacyConfigFile`、`LegacyConfigException`、`LegacyConfigConfiguration` 与 `.view` 的 `PhpArray`、`PhpValue`（B02，启动时读旧 `extra/maccms.php` 并按字段并入站点设置，见 `11-guards-and-legacy-config.md` 第 4.4 节），不能引用 `LegacyConfigProperties` 与 `legacyconfig.infrastructure`。需要更多时在任务中说明并同步修改护栏；不拥有表、不新增 Mapper/SQL，不引用 MyBatis、JDBC、`javax.sql`、Spring JDBC/DAO 类型；不读文件、环境变量（只经 `maccms.site.*` 配置绑定与 `LegacyConfigService`），不启动进程，不发起 HTTP 请求（不回环调用旧 PHP 或本应用 API；jsoup 只能用解析/节点/过滤包，禁止 `Jsoup`、`Connection` 等网络 API），模板表达式只读取视图模型；页面需要新字段时走契约变更。
  - `site` 的每个 Spring 组件都带 `@ConditionalOnSiteEnabled`；`maccms.site.enabled` 默认关闭，关闭时不注册任何 site 组件与路由。
- MyBatis/JDBC 类型只出现在各模块的 `infrastructure` 包（例外只有两个包本身：`common.config` 的数据源检查、`common.write` 的可写数据源与写入护栏（B07））。
- 旧配置文件（`application/extra/*.php`、`application/route.php`）只经 `legacyconfig.application.LegacyConfigService` 读取（B02，`11-guards-and-legacy-config.md` 第 4 节）：只解析 `var_export` 数据，不执行 PHP，不读 `database.php` 等其他文件；其他模块不自己读这些文件。
- 以上依赖规则由 `backend/src/test/java/com/maccms/backend/architecture/ModuleBoundaryTest.java` 扫描源码检查（注释和字符串不计），合成源码自检规则本身；`site` 的模板、构建与装配规则由 `backend/src/test/java/com/maccms/backend/site/SiteGuardrailsTest.java` 与 `SiteTemplatePolicyTest.java` 检查。
- `common` 只放与业务无关的支持代码（错误处理、分页、配置检查），不得引用业务模块。

### 3. 数据库

- **禁止 DDL**（路线图第 1.2 节"零结构变更"，B07 起取代原"DDL 独立评审"）：不建表、不加/改/删列与索引、不转引擎或字符集，不建触发器、存储过程、视图、事件；不执行表维护语句与账号/权限语句；应用代码与资源中不得有 `schema.sql`/`data.sql`、Flyway/Liquibase 或任何初始化器。数据库账号由数据库管理员在获批部署时创建，代码不建、不改账号。没有结构迁移，旧数据不需要迁移；回滚只用 DML（`12-write-infrastructure.md` 第 6 节）。
- **整体切换（D3 已决）**：上线时在一个窗口内停止旧 PHP 的全部写入，由新系统一次接管；不修改旧 PHP、不做分阶段（按表或按功能）的写入方切换。切换前的整个并行开发期，新系统对生产**只读**、不连接生产；写入只发生在本地一次性库 `maccms_it_*`（或经确认的本地开发库副本）。方案见 `13-cutover-plan.md`。
- **写入只经 B07 写入基础设施**（`12-write-infrastructure.md`）：
  - 可写数据源 `common.write.WriteDatabase` 默认关闭（`MACCMS_WRITE_ENABLED=false`），打开时地址与凭证经 `MACCMS_WRITE_DB_*` 单独提供；它不是 `DataSource` Bean，写 Mapper 只能经 `WriteDatabase.mapper()` 取得。
  - 写 SQL 只写在 `src/main/resources/mapper-write/<模块>/*.xml`，接口在 `<模块>.infrastructure`、标 `@WriteMapper`（不标 `@Mapper`）；不用 MyBatis 写注解，不在 Java 里写 SQL 文本。
  - 每个"模块 + 表 + 操作"先在 `WriteRegistry.REGISTERED` 登记（表与批次须符合路线图写入方矩阵），由 `WriteGuardTest`（静态）与 `WriteGuardInterceptor`（运行期）检查；未登记的写入在执行前被拒。
  - 写语句只能是单表 `INSERT`（含 `INSERT … SELECT … WHERE NOT EXISTS` 条件插入）、带顶层 `WHERE` 的单表 `UPDATE`/`DELETE`；不允许 `REPLACE`、`IGNORE`、`ON DUPLICATE KEY UPDATE`、多语句、多表写、写 Mapper 中的 `<where>`/`<sql>`/`<include>`/`${}`。
  - 数值变化用原子表达式，前置条件写进 `WHERE`，检查**匹配**行数（`useAffectedRows=false`）；多表写入用 `WriteSequence`（认领 → 转移 → 记录，失败按相反顺序条件补偿，补偿失败或结果不确定时上报人工对账）。旧表是 MyISAM、没有事务：不用 `@Transactional`/`setAutoCommit(false)`。
  - 写库批次另加：每个"先查后写"、认领、条件扣减、条件插入都有并发测试（`writedrill.ConcurrentWrites`，一次性库）；回滚演练（快照 → 本批写操作 → `DmlRestore` → diff 为空 → 表结构指纹不变）。
- SQL 规则：
  - 只用 `#{}` 参数绑定，禁止 `${}` 拼接；排序/筛选字段用枚举 + XML `<choose>` 生成固定列名（白名单）。
  - 用户输入只能作为绑定值，不能决定列名、运算符或 SQL 片段：筛选条件的列名固定写在 XML 里，一个参数对应一个固定条件（`VodMapper.xml` 的 `matchingConditions`）。包含匹配一律 `col LIKE ? ESCAPE '!'`，模式在 application 层由已校验的值构造（`!`、`%`、`_` 前加 `!`），Mapper 不做字符串处理。
  - 同一组条件的 count 与分页必须 `<include>` 同一个条件片段，保证 `total` 与列表一致；分页排序追加主键同向排序。
  - 可能扫描大量行的查询（包含匹配等）要有服务端执行时限（MySQL `/*+ MAX_EXECUTION_TIME(n) */`，字面常量）与进程内并发上限，超限归为 503，并默认关闭、经合成库测量后再开启。不新增应用内结果缓存，不读写旧系统的缓存表（如 `mac_vod_search`）。
  - 明确列出列名，不写 `SELECT *`，不把数据库整行返回给前端。
  - 列表必须有 `LIMIT` 和页大小上限；不做无界全表读取。
  - 涉及旧表的每条规则在注释或文档中注明旧代码位置（`文件:行号`）。
- 只读连接：`spring.datasource.hikari.read-only: true` 不得移除。生产使用只授予 SELECT 的专用账号；写账号（若获批）只授予 `mac_*` 的 SELECT/INSERT/UPDATE/DELETE。
- 零结构变更由护栏锁定（B02，`11-guards-and-legacy-config.md` 第 3 节）：`SchemaGuardTest`（任何位置不出现 DDL、表维护、账号语句、SQL 脚本与自动迁移）、`SqlEntryGuardTest`（SQL 执行入口只接受常量 SQL；`${}` 只允许在 `REGISTERED_DOLLAR` 登记、经白名单校验的标识符，登记写进本批文档）、`-Pmysql-it` 结束时比对 information_schema 指纹（新增 `*MySqlIT` 照 `SchemaGuardMySqlIT` 与现有 IT 的做法，导入后取指纹、删库前比对）。

### 4. 配置与密钥

- 仓库、日志、测试输出、提交信息、任务评论中**不得出现**真实密码、密钥、token、带凭证的 URL。
- 数据库等凭证只从环境变量读取，`application.yml` 中不写默认值；缺失时必须启动失败，不能回落到任何默认库或 root。可写数据源（B07）默认关闭，所以 `MACCMS_WRITE_DB_*` 的占位符带空默认值；打开时三项缺一即启动失败，不回落到只读池的配置，错误信息与日志不回显地址、账号、密码。
- 样例配置只用占位符（`config/*.env.example`）；真实 `*.env` 已被忽略，不提交。
- 不读取仓库外的线上配置（`D:\Ai\MACCMS\prod-config\`），不连接线上库或服务器。
- 运行时出站网络 API（HTTP、SMTP、FTP、套接字、DNS 查询等）只允许出现在 `OutboundNetworkGuardTest.REGISTERED` 登记的 `integration` 子包（B02 起；登记表为空，B23 起按批登记，路线图第 4.3 节）。
- 页面不输出让浏览器访问外部主机的地址，除非对应的部署参数 `maccms.browser-external.w1/w2/w3` 已打开（B02；按路线图第五轮 W1/W2 默认打开、W3 默认关闭）；"本站"只按 `maccms.site.base-url` 的主机判定。
- 日志不记录 cookie、session、会员身份、密码；异常处理只返回错误码与通用说明。
- 请求处理中的异常**不得**把 Throwable 或 `getMessage()` 交给日志框架（message 可能含 SQL 参数、连接串、账号）；统一用 `common.web.SafeExceptionLog` 只记录类型与栈帧。新增异常分支要补日志捕获测试（`OutputCaptureExtension` + 合成标记，不用真实密钥）。
  - `SafeExceptionLog` 已公开（MACC-8），只公开 `typeChain`（单行类型链）与 `describe`（类型 + 栈帧）两个方法，其他模块直接复用，不要自行拼接异常信息。
- 服务端页面（`site`，MACC-9 骨架已实施，依据 `04-site-template-spike.md` 第 3.5、5.3 节；用法见 `05-site-skeleton.md`）：
  - 一律先把整页渲染到缓冲区，成功后再写响应（带 `Content-Length`）；不使用边渲染边输出的视图路径，避免失败时返回 200 + 半页 HTML 被缓存。
  - 渲染或读取失败时由 `site` 自己的异常处理（`@ControllerAdvice(basePackages = "com.maccms.backend.site")`，优先于全局 JSON 处理）输出完整错误页并带 `Cache-Control: no-store`，不依赖 Boot 的 `/error` 转发；日志只用 `SafeExceptionLog` 记录类型链。
  - 模板引擎自身的错误日志不得输出异常 message（引擎会自行记录时须关闭对应 logger 并有测试）。
  - 模板（jte 3.2.4，只放在 `backend/src/main/jte/site/`）：只通过生成的 `Templates` 接口与 `SiteHtmlRenderer` 渲染；不用 `$unsafe{}`/`@raw`/`!{}`；事件属性、`<script>`、`<style>`、`style` 属性中不放数据（严格策略直接禁止这些标签与属性）；URL 属性（`href`、`src` 等）的动态值只能是 `SafeUrl.href()`，模板不拼接 URL；库内图片字段（`vod_pic` 等）只经 `site.application.ImageUrlNormalizer` 转成 `SafeUrl`（MACC-11，`06-image-url-normalizer.md`），为空时显示占位图；默认转义不是富文本过滤，不得引用 `vod_content`/`content`；富文本只经 `site.application.RichTextSanitizer` 得到 `SafeHtml`，包成 `gg.jte.Content` 放在元素内容位置输出，旧文本字段的字符引用只经 `LegacyTextEntities.decodeOnce` 按字段清单解码一次（MACC-12，`07-rich-text-filter.md`）。

### 5. 测试与完成条件

在 `backend/` 下：

| 命令 | 何时必须通过 |
|---|---|
| `./mvnw verify` | 每次提交 |
| `./mvnw verify -Pmysql-it` | 改动 Mapper/SQL（含写 Mapper）、可见性规则、数据访问配置、写入基础设施时 |

- MySQL 相关验证必须使用**独立的一次性库**（集成测试自动创建 `maccms_it_*` 并在结束时删除）和合成数据。**不得清空、改写或导入到 `maccms_dev` 等已有库。不得用 H2 或 Mock 冒充 MySQL 验证。**
- 编译通过不等于验收通过。无法执行的检查（缺工具、缺环境）必须在交付评论中如实列出。
- 修 bug 先写能复现的失败测试，再修复。

**完成条件（Definition of Done）**

1. 需求中的每条验收标准都有对应的自动测试或明确的手工验证记录。
2. 上表中适用的命令全部通过。
3. OpenAPI、DTO、测试一致；与旧 PHP 的行为差异写进对应的 `docs/architecture/` 文档。
4. 写库路径只经 B07 写入基础设施并已登记（第 3 节）；写库批次附并发写测试与回滚演练结果。
5. 提交范围符合第 6 节。
6. 交付评论包含：提交 SHA、改动要点、运行过的命令与结果、未运行的检查、与旧版差异、遗留问题。

### 6. 提交范围

- 只提交本任务涉及的文件。**不提交**：`backend/target/`、`runtime/`、`upload/`、`vendor/`、`application/data/install/install.lock`、本地修改的 `application/database.php`、`*.env`、日志、临时回复文件、`.multica/`、`.agent_context/`、各 worktree 自动生成的 `CLAUDE.md`/`.claude/`。
- 提交前执行 `git status`，逐个确认新增文件。
- 提交信息格式：`<类型>(<范围>): <说明> (MACC-<编号>)`，如 `feat(content): 视频列表接口 (MACC-4)`。

### 7. 协作

- 每个目录有明确的负责人：`backend/` 与 `docs/contracts/` 由后端智能体维护；`docs/frontend/` 由前端智能体维护，后端不修改。需要对方配合时在任务评论中提出，不直接改对方目录，不给其他智能体派活。
- `backend/` 内的例外（MACC-9 起生效；`static/site/` 已建立，`jte/site/components/` 在首个组件出现时建立）：`backend/src/main/jte/site/components/` 与 `backend/src/main/resources/static/site/` 由前端智能体维护，后端智能体维护其余部分（含页面模板、视图模型、模板参数签名）；组件模板的 `@param` 签名变更需双方在任务中确认。
- 同一文件同一时间只由一个任务修改；发现冲突先在任务中说明。
- 不确定的业务规则先问，不猜；推断出来的规则标注"待核实"。
