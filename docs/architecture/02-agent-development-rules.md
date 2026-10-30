## 智能体开发规范（新系统 Java 工程）

适用于在 Java 工程（仓库根目录的 `pom.xml`、`src/` 等）、`docs/contracts/`、`docs/architecture/` 中工作的所有智能体与人工开发者。与根目录 `AGENTS.md` 冲突时以 `AGENTS.md` 为准。

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
- `member` 模块（B08 起；B09 账号写入，`18-b09-member-account.md`；B10 权益与财务，`19-b10-member-finance.md`）：`application`（`MemberSessionService`、`MemberAccountService`、`MemberCookies`；B10 的 `MemberRecordService`、`MemberPurchaseService`、`MemberWalletService`、`MemberReferralService`、`MemberExpiryService`，视图类型在 `.view`）→ `infrastructure`（只读 `MemberMapper`、`MemberFinanceMapper`，写 `MemberWriteMapper`、`MemberFinanceWriteMapper`，头像文件 `PortraitStore`）。页面权限按会员组经 `content.application.ContentViewer`（请求作用域，只由 site 的会员拦截器设置，公开 API 始终按游客）；B10 起作用域另带已购买查询 `ContentPurchases`（实现在 member，只读 `mac_ulog`；游客与公开 API 为 `ContentPurchases.NONE`）。member 查内容的积分与标题只经 `content.application.ContentLookupService`（不读播放/下载地址与密码，`ReadOnlyGuardrailsTest` 锁定）。
- 模块之间只能调用对方 `application` 层的公开服务，以及这些服务签名中出现的**已发布只读类型**（`<模块>.application.view` 包下的不可变 record）；不能使用对方的 api 层（Controller、HTTP 专用类型）、infrastructure 层（Mapper、行记录），也不能使用 `application` 下除 `view` 以外的子包。
  - `content` 的已发布只读类型（MACC-8 起）：`content.application.view` 下的 `CategoryListResponse`、`CategoryDto`、`CategoryExtendDto`、`CategoryRefDto`、`VideoSummaryDto`、`VideoDetailDto`，分别对应 `docs/contracts/content-read.openapi.yaml` 中的同名（去掉 `Dto`）schema，Controller 直接以它们作为响应。
    契约 1.2.0（MACC-27）新增：`VideoPlotListResponse`、`PlotDto`、`ArticleContentPageDto`、`ScreenshotDto`、`ArticleSummaryDto`、`ArticleDetailDto`、`TopicSummaryDto`、`TopicDetailDto`、`ActorSummaryDto`、`ActorDetailDto`、`RoleVideoDto`、`RoleSummaryDto`、`RoleDetailDto`、`WebsiteSummaryDto`、`WebsiteDetailDto`、`LinkDto`，对应规则相同；对应的公开服务为 `ArticleReadService`、`TopicReadService`、`ActorReadService`、`RoleReadService`、`WebsiteReadService`、`LinkReadService`、`VideoPlotReadService`（列表参数类型 `ContentListQuery`/`ContentFilter` 与 `VideoListQuery` 一样自带校验）。多值字段解码 `content.application.LegacyMultiValue`（播放/下载分组、文章分页、分集剧情、截图）由 PHP 黄金向量锁定，见 `11-content-read-completion.md` 第 3 节。
  - 已发布只读类型与 `docs/contracts/` 中的 schema 一一对应时，修改它们等同修改契约，按第 1 节在同一提交里改 OpenAPI、类型与契约测试（`PublishedViewContractTest` 校验 record 组件与 schema 字段集合一致）。
  - 跨模块传入的查询参数类型自带校验：例如 `VideoListQuery` 的构造器与 `parse` 采用相同范围（`page` 1..1000、`pageSize` 1..100、`sort` 非空、`categoryId` 为 `null`（不筛选）或 1..65535），越界抛 `ApiException`（`INVALID_PARAMETER`）。调用方不能靠直接构造绕过上限。
  - 搜索/筛选条件 `VideoFilter`（契约 1.1.0，MACC-18；`VideoListQuery.filter`，5 参数构造器为 `VideoFilter.NONE`）同样如此：构造器与 `parse` 使用同一套规则（`docs/architecture/09-content-search-filter-plan.md` 第 4 节）——值是解码一次后的原始字符串，不裁剪、不改写；`keyword` ≤ 64、`area` ≤ 20、`lang` ≤ 10、`classes` ≤ 32 个码点，不含 C0/C1 控制字符、代理项、U+FFFD，首尾不得为 ECMAScript `\s` 空白（1.1.0 时 `area/lang/classes` 不含半角逗号，1.3.0 起按旧版逗号多值，见下）；`year` 恰为 4 位 ASCII 数字；`level` 1..9（原始字符串最多 10 位数字）。"参数重复""非法 UTF-8"只在 HTTP 层判断，由 `content.api` 从容器参数与原始查询串得到；搜索开关（`maccms.content.search.enabled`）由 `ContentReadService` 在查询前再检查一次，其他模块直接构造带 `keyword`/`classes` 的条件也绕不过。契约 1.3.0（B04，`14-video-list-filter-completion.md` 第 3、4 节）在同一类型上新增 `letter/state/version`（逗号多值精确匹配）、`actor/director/tag/name`（逗号多值包含匹配，同样受搜索开关与并发许可约束）、`ids`（`all` 或最多 100 项）与 `year` 的 9 字符区间，并让 `area/lang`（IN）、`classes`（各项包含匹配 OR）接受逗号多值（MACC-28 Q1，长度上限仍作用于整个原始值，只有一项时 SQL 与 1.1.0 相同）；`VideoListQuery.random`（`sort=rnd`）按旧版两种算法取页，随机源是可替换的 `content.application.VideoRandom`。B05（`15-b05-readonly-pages.md` 第 2 节）新增 `VideoListQuery.legacy`（`content.application.VideoLegacyConditions`：旧模板标签专属条件与 SSR 的 `tid`），只由其他模块构造、公开 API 一律为 `NONE`，同样自带校验（列表 ≤ 100 项、文本 ≤ 64 码点、包含匹配受搜索开关与许可）；换算旧属性用 `LegacyTagAttributes`、`LegacyRange`、`PhpIntval`。
  - B06（`16-b06-misc-pages-provide.md` 第 3.5、3.6 节）新增旧协议整行读取 `content.application.LegacyRowReadService`（查询参数 `LegacyRowQuery`/`LegacyCondition`/`LegacyTable`/`LegacyColumnSet`/`LegacyRowOrder` 自带校验），结果类型 `view.LegacyRowDto`、`LegacyRowPageDto`、`LegacyTypeDto` 不对应 OpenAPI schema（只供 openapi 与 site 按旧协议输出）；行范围固定为"已审核 + 可见分类"，访问密码列从不读出（`ReadOnlyGuardrailsTest.legacyRowMapperNeverSelectsPasswords`）。
- `openapi` 模块（B06，Provide 资源站接口，`16-b06-misc-pages-provide.md` 第 3 节）：`api`（Controller）→ `application`（参数、旧配置、JSON/XML 输出）→ 只经 `content.application.LegacyRowReadService` 读数据，自己不拥有表、没有 Mapper；旧配置只经 `LegacyConfigService` 读取；客户端 IP 只经 `common.clientip.ClientIpResolver`。每个组件带 `@ConditionalOnOpenApiEnabled`（`maccms.openapi.enabled` 默认关闭）。
- `common.clientip`（B06，D2 ④）：客户端 IP 的唯一来源（只信连接对端，可信代理网段内才采用转发头，`16` 文档第 2 节）；登录 IP、评论 IP、频率限制（B08/B09/B11）都必须用它，不得自行读取 `X-Forwarded-For`、`CF-Connecting-IP` 等请求头。
- `site` 模块（服务端页面，MACC-9 起为骨架，见 `05-site-skeleton.md`）只依赖 `content.application`（含 `.view`）、`member.application`（含 `.view`，B09：会员账号服务、`label_user` 判定与会员视图类型，`18-b09-member-account.md`）与 `common`——`common` 中限 `common.web` 的 `PageResponse`、`ApiException`、`ErrorCode`、`SafeExceptionLog`、`LegacyJson`（B06，PHP `json_encode` 等价编码），`common.clientip.IpAddress`（B06，IP 字面量解析），`common.web.PhpCookies`、`common.session` 的 `SessionStore`/`SessionState`、`common.captcha` 的 `Captchas`/`CaptchaSettings`（B08，PHP 兼容 cookie 读写、进程内会话与本地验证码，均为纯内存计算，见 `17-b08-member-session-captcha.md` 第 2 节），`common.web.PhpInput`（B09，按字节解析查询串与表单）与 `common.clientip.ClientIpResolver`（B09，登录与注册 IP，D2 ④），`common.lang.LegacyLang`（B12 承接 D-B05-8，旧语言包，前台按 `app.lang` 输出旧版 `lang()` 的文字，21 文档第 12 节），以及 `common.browser` 的 `BrowserExternalAccess`、`BrowserExternalConfiguration`（B02，浏览器侧外部地址放行开关）；另可引用 `legacyconfig.application` 的 `LegacyConfigService`、`LegacyConfigFile`、`LegacyConfigException`、`LegacyConfigConfiguration` 与 `.view` 的 `PhpArray`、`PhpValue`（B02，启动时读旧 `extra/maccms.php` 并按字段并入站点设置，见 `11-guards-and-legacy-config.md` 第 4.4 节），不能引用 `LegacyConfigProperties` 与 `legacyconfig.infrastructure`。需要更多时在任务中说明并同步修改护栏；不拥有表、不新增 Mapper/SQL，不引用 MyBatis、JDBC、`javax.sql`、Spring JDBC/DAO 类型；不读文件、环境变量（只经 `maccms.site.*` 配置绑定与 `LegacyConfigService`），不启动进程，不发起 HTTP 请求（不回环调用旧 PHP 或本应用 API；jsoup 只能用解析/节点/过滤包，禁止 `Jsoup`、`Connection` 等网络 API），模板表达式只读取视图模型；页面需要新字段时走契约变更。
  - `site` 的每个 Spring 组件都带 `@ConditionalOnSiteEnabled`；`maccms.site.enabled` 默认关闭，关闭时不注册任何 site 组件与路由。
- MyBatis/JDBC 类型只出现在各模块的 `infrastructure` 包（例外只有两个包本身：`common.config` 的数据源检查、`common.write` 的可写数据源与写入护栏（B07））。
- 旧配置文件（`application/extra/*.php`、`application/route.php`）只经 `legacyconfig.application.LegacyConfigService` 读取（B02，`11-guards-and-legacy-config.md` 第 4 节）：只解析 `var_export` 数据，不执行 PHP，不读 `database.php` 等其他文件；其他模块不自己读这些文件。
- 以上依赖规则由 `src/test/java/com/maccms/backend/architecture/ModuleBoundaryTest.java` 扫描源码检查（注释和字符串不计），合成源码自检规则本身；`site` 的模板、构建与装配规则由 `src/test/java/com/maccms/backend/site/SiteGuardrailsTest.java` 与 `SiteTemplatePolicyTest.java` 检查。
- `common` 只放与业务无关的支持代码（错误处理、分页、配置检查），不得引用业务模块。

### 2.1 B11 评论与计数的已实施例外

- B11 的 `comment` 模块负责评论、留言的业务规则与读写，分层为 `application`（`CommentService`，不可变视图在 `.view`）→ `infrastructure`（读 `CommentMapper`，写 `CommentWriteMapper`）。`site` 可调用其 `application` 公开服务与 `.view` 类型；`comment` 仅经 `content.application.PhpIntval` 复用旧参数语义，不访问其他模块的 Mapper。
- B11 起 `openapi` 的 Provide 通过 `content.application.ContentCounterService` 执行 `detail_inc_hits`，仍不直接访问 Mapper；写入受独立可写数据源和登记表约束。
- 下节“不读写旧缓存表”在 B11 起仅对 `content` 拥有的 `mac_vod_search` 设例外：站点视频搜索/筛选在旧 `vod_search_optimise` 含 `frontend` 且写池开启时按旧语义使用；公开 API 不触发。查询保留搜索开关、执行时限、并发许可和有界编号列表，写入只经登记的 `mapper-write/content/VodSearchWriteMapper.xml`。其他缓存表仍禁止。
- 契约、写入方及黄金/并发/DML 回滚依据：`20-b11-comment-gbook-counters.md`。本节取代前文对模块依赖和缓存访问的旧限制中与 B11 范围冲突的部分。

### 2.2 B12 后台模块

- `admin` 模块（`21-b12-admin-framework.md` 第 2 节）：`web`（入口 `AdminController` 与页面）→ `application`（登录、会话核对、`admin_auth` 鉴权、管理员管理、菜单与权限码登记 `AdminPermissions`）→ `infrastructure`（只读 `AdminMapper`，写 `AdminWriteMapper`）；`config` 装配。application/infrastructure 不引用本模块 web/config，web 不直接用 Mapper，不引用 `site`（`ModuleBoundaryTest.adminRulesOnSyntheticSources`）。每个组件带 `@ConditionalOnAdminEnabled`（`maccms.admin.enabled` 默认关闭）。
- 后续后台批次（B13～B20、B28、B29）在 `AdminController` 的分派里接上自己的动作，**不改** `AdminPermissions` 的菜单与权限码数据（路线图第 5.2 节"同文件冲突"）；权限一律经 `AdminAuthorization`（旧 `check_auth` 的文本语义）。
- 后台页面模板在 `src/main/jte-admin/`（生成到 `admin.web.jte`，与站点模板分开），按旧后台 HTML 结构原样转写，不做新视觉设计；旧内联脚本外移到 `src/main/resources/static/admin-assets/`，数据经 `data-*` 属性传入；旧 `static/layui`、`admin_style.css`、`admin_common.js` 构建时原样打包。`AdminTemplatePolicyTest` 锁定：无内联脚本与事件属性、`<script>` 只带 `type/src/data-*`、style 无输出、原样 HTML 只能是语言包文字（`AdminText.trusted`）。内容安全策略见 21 文档第 8 节（为复用旧脚本保留 `'unsafe-eval'` 与几段固定代码的 `'unsafe-hashes'`）。
- 旧语言包只经 `common.lang.LegacyLang`（jar 内由 `lang_export.php` 导出的 JSON）；新页面上旧版也有的文字取语言包键，不写死简体（21 文档第 12.2 节）。

### 2.3 B14 后台会员与财务

- 会员与财务表（`mac_user`、`mac_group`、`mac_card`、`mac_order`、`mac_cash`、`mac_plog`、`mac_ulog`、`mac_visit`）的后台读写在 `member` 模块（`23-b14-admin-member.md` 第 2 节）：`member.application` 的 `MemberAdminServices`（只供 admin 调用，`@ConditionalOnWriteEnabled`）→ `member.infrastructure` 的只读 `MemberAdminMapper` 与写 `MemberAdminWriteMapper`/`MemberAdminWrites`。admin 只经 `AdminMemberActions` 调用这些服务；会员表单的取值规则复用 content 的 `AdminTable`（`USER`、`GROUP`）与 `AdminRowValues`（同 B13 comment 模块的用法）。
- 提现审核、删除与会员中心（B10）共用"认领 → 转移 → 条件补偿"的写法与语句；资金一致性以 23 文档第 6.3 节的对账语句检查。

### 2.4 B15 后台系统配置、模板在线编辑与本地插件

- 旧配置文件只经 `legacyconfig.application.LegacyConfigService` 写回（`replace`/`update`：先确认新版能读回，再经 `legacyconfig.infrastructure.AtomicFiles` 原子写入并核对读到时的版本），文件内容只由 `VarExportWriter` 生成（与 PHP `var_export`/`mac_arr2file` 逐字节相同）；旧站的其他文件只经 `legacyconfig.application.LegacySiteFiles`（读写区域 `static/`、`template/`、`addons/`、`application/data/backup/`），其他模块不自己读写旧站文件（`24-b15-admin-system.md` 第 3 节）。
- `admin` 可用的 site 类型加 `site.application.PhpJson`（`ModuleBoundaryTest.ADMIN_SITE`）。
- `sitetemplate` 模块（前台模板在线编辑，D4，24 文档第 7 节）：`application`（`SiteTemplateService` 实现 `site.application.SiteTemplateSource`，安全过滤 `JteSecurityFilter`，装配 `SiteTemplateConfiguration`）→ `infrastructure`（`JteOnlineCompiler`、`JteClassPath`、`OnlineHtmlPolicy`）。运行时编译模板只能出现在 `sitetemplate.infrastructure`（`SiteGuardrailsTest.runtimeTemplateCompilationOnlyInSiteTemplateInfrastructure`）；jte 编译器为此进入编译作用域。`site` 仍不编译模板、不读模板文件，只经 `SiteTemplateSource` 取当前引擎（没有或为 null 时用内置的预编译模板）；第 4 节的模板规则对外部模板目录同样适用（编译时用同一套站点 HTML 策略，`<script>`/`<iframe>` 只限内置版本本身含有它们的模板）。
- 数据库工具（24 文档第 9 节）只做只读查询与单表 DML：只读 Mapper `mapper/admin/DatabaseToolMapper.xml` 的 SHOW 语句与 `${table}`/`${columns}` 是 `ReadOnlyGuardrailsTest`、`SqlEntryGuardTest` 登记的例外；备份文件里的 DDL 文本是 `SchemaGuardTest` 登记的唯一例外（只写进文件、从不执行）；还原、执行 SQL、优化、修复在入口拒绝。

### 2.5 B17 采集配置管理

- 采集源与自定义规则节点的后台读写在 `collect` 模块（`25-b17-collect-config.md` 第 2 节）：`collect.application` 的 `CollectAdminServices`（只供 admin 调用，`@ConditionalOnWriteEnabled`）→ `collect.infrastructure` 的只读 `CollectAdminMapper` 与写 `CollectAdminWriteMapper`/`CollectAdminWrites`；取值规则复用 content 的 `AdminTable`（`COLLECT`、`CJ_NODE`）与 `AdminRowValues`。只读查询不取 `collect_appid`、`collect_appkey`（`ReadOnlyGuardrailsTest` 锁定）。
- 分类绑定的键与取值只用 `collect.application.CollectBindings`（`md5(采集地址) . '_' . 远程分类编号`，`intval` 大于 0 为已绑定），B18/B19 沿用；`application/extra/bind.php` 只经 `CollectBindService` → `LegacyConfigService.update` 写回。
- 采集相关批次不发起任何出站请求（"测试连接"类入口返回固定提示，E01 只做本地文件源）。

### 2.6 B18 本地文件源解析与预览

- Provide 文件（JSON/XML）只经 `collect.application.ProvideParser` 解析（`26-b18-collect-preview.md` 第 3 节）：`parse` 得到与绑定无关、可缓存的 `ProvideDocument`，`bind` 按当时的 `bind.php` 换算出与旧方法返回值同构的数组，`precheck` 给出旧 `{type}_data` 的跳过原因；B19 入库沿用这三步，不另写解析。PHP 语义（`json_decode`、SimpleXML 子集、字符串转换、`empty`）只在 `ProvideJson`、`ProvideXml`、`ProvideValues`，由 PHP 黄金（`ProvideParserGoldenTest`）锁定。
- XML 一律拒绝 DOCTYPE；新增 XML 入口（B19 的 Receive 等）也只经 `ProvideXml`，不要另建解析器或打开 DTD 支持。
- 预览缓存只经 `collect.infrastructure.FileProvidePreviewStore`，本地文件只经 `DirectoryProvideSourceFiles`（只认目录下直接存放的普通文件）；B18 的类不引用数据访问与网络类型（`CollectPreviewGuardTest`）。
### 2.7 B20 自定义规则采集

- 规则（`mac_cj_node` 的各项规则）只经 `collect.application.CjRules` 执行（`28-b20-cj-local-html.md` 第 3 节）：文字按字节（ISO-8859-1 字符串）处理，过滤规则的 PCRE 只经 `CjPcre` 转写与替换（带匹配时限），不要另写正则执行；PHP 语义由黄金（`CjRulesGoldenTest`）锁定。
- 页面只经 `collect.infrastructure.LocalHtmlFiles` 按网址的镜像布局读取本地 HTML 目录（只读、不跟随符号链接），不发起请求。
- 内容的状态（未采集 → 已采集 → 已发布）只用带前置条件的 UPDATE 推进，采集历史用条件插入；导入只经 B19 的 `CollectIngestService`。
### 2.8 B16 静态生成与定时任务

- `staticgen` 模块（`25-b16-static-timming.md` 第 2 节）：`application`（`StaticPaths`、`MakeService`、`StaticFiles`、`TimmingSchedule`、`TimmingService`、`TimmingTasks`）→ `web`（`TimmingController`、`TimmingAccess`）、`config`。静态页的内容只经 `site.application.StaticPageSource` 取得（前台对动态地址的进程内游客 GET，实现 `site.web.InternalPageRenderer`），不另写页面模板；静态页文件只经 `LegacySiteFiles.replaceStaticPage`（扩展名与目录白名单）写入并登记，回滚按登记删除。
- 定时任务入口的放行只看部署级参数（`MACCMS_TIMMING_ENABLED`、`MACCMS_TIMMING_TOKEN`，第 4 节的密钥规则同样适用）与 `ClientIpResolver` 的结果；不得新增按请求参数放行的分支。
- 定时任务 `cj` 只经 `collect.application.CjColAll`（与后台 `cj/col_all` 同一实现），`collect` 不提供（D-B19-15），不要再接资源站采集；B28 接入推送时替换 `TimmingTasks` 的 `urlsend` 分支，runtime 回写与鉴权不变。
### 2.9 B23 integration 基础

- `integration` 模块（`26-b23-integration-foundation.md`）：`integration.application`（登记表 `IntegrationPackages`、第三方配置 `ThirdPartyConfig`/`Secret`、脱敏 `LogRedactor`，其他模块可用）；`integration.http`（出站客户端，唯一可以直接用出站 API 的包）；B24～B29 的业务子包 `integration.pay` 等经客户端发请求，登记写进 `IntegrationPackages.REGISTERED`，与该批的合并提交一起出现。B28 登记了第一批：`integration.urlsend`（百度推送）、`integration.backlink`（回链检测），其他模块只经 `integration.application` 的 `UrlPush`、`BacklinkPages` 接口使用（`30-b28-urlsend-backlink.md` 第 5 节）。B25 登记 `integration.sms`（短信，经客户端）与 `integration.mail`（SMTP，同时登记为 `RAW_API`，自行执行同一套目标检查），其他模块只经 `SmsSender`、`MailSender`、`LegacyMailTemplate` 使用；SMTP 测试用 `integration.testing.LoopbackSmtpServer`（TLS 证书测试时现生成，不入库）（`32-b25-sms-mail.md` 第 2 节）。B24 登记 `integration.pay`（在线支付，只有微信统一下单经客户端），其他模块只经 `integration.application.PaymentGateways` 使用，site 只经 member 的 `MemberPaymentService`（`33-b24-payment.md` 第 4 节）。
- 第三方配置只经 `ThirdPartyConfig` 读取（启动时读一次，修改后重启生效）；密钥字段只能用 `secret()` 取 `Secret`，明文不得写入日志、异常信息或评论。第三方请求与响应要写日志时先经 `LogRedactor`（客户端已对自己的日志这样做）。
- 第三方接口的测试用 `LoopbackMockServer`（回环地址、随机端口），假值一律形如 `test-only-…`；外部地址的反例只用 TEST-NET（203.0.113.0/24）与 `.invalid`/`.test` 保留名。

### 2.10 B22 安装向导

- `install` 模块（`31-b22-install-wizard.md`）只在安装模式（`MACCMS_INSTALL_MODE=true`，`install.InstallApplication`）下装配：`InstallApplication` 与 `install.config.InstallConfiguration` 都不带组件注解，正常模式扫描不到；模块里不要加 `@Component`/`@Configuration`/`@Controller` 等注解（`InstallModeTest` 锁定）。分层 `web → application → infrastructure`，JDBC 只在 `install.infrastructure`。
- 安装向导不执行任何 DDL：目标库由部署方在库外导入 install.sql，向导只读核对（`InstallDatabaseProbe` 的固定 SELECT；`SELECT 1 FROM mac_vod_search LIMIT 1` 是"不读写旧缓存表"在本批的唯一例外）。表结构基准 `install/schema-fingerprint.txt` 由 `InstallWizardMySqlIT` 锁定，install.sql 有变化时同步更新。
- 写库只经 `WriteDatabase.openInstallTarget`（只给 `install.infrastructure`，`WriteGuardTest` 锁定）与登记的 `install / mac_type、mac_admin / INSERT`；安装锁只经 `LegacySiteFiles.writeInstallLock`。
- 页面模板在 `src/main/jte-install/`（生成到 `install.web.jte`），规则同后台模板（`InstallTemplatePolicyTest`）。

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
- 运行时出站网络 API（HTTP、SMTP、FTP、套接字、DNS 查询等）只允许出现在 `integration.application.IntegrationPackages.RAW_API` 登记的子包（B23 起只有出站客户端 `integration.http`；B25 起另有 SMTP 发信的 `integration.mail`）；第三方 HTTP 请求一律经 `integration.http.OutboundHttpClient`，它只能被 `IntegrationPackages.REGISTERED` 登记的 `integration` 子包使用（B23 交付时为空，B24～B29 每批只登记本批子包，B28 起有 `integration.urlsend`、`integration.backlink`，B25 起有 `integration.sms`、`integration.mail`，B24 起有 `integration.pay`；`OutboundNetworkGuardTest` 静态锁定，客户端运行期再查调用方）。见第 2.9 节与路线图第 4.3 节。
- 测试不得访问外部主机：测试期出站护栏（B23）对全部测试自动生效，连接非回环地址或解析外部主机名即判测试失败；第三方接口一律用 `integration.testing.LoopbackMockServer` 在回环地址上模拟。
- 页面不输出让浏览器访问外部主机的地址，除非对应的部署参数 `maccms.browser-external.w1/w2/w3` 已打开（B02；按路线图第五轮 W1/W2 默认打开、W3 默认关闭）；"本站"只按 `maccms.site.base-url` 的主机判定。
- 日志不记录 cookie、session、会员身份、密码；异常处理只返回错误码与通用说明。
- 请求处理中的异常**不得**把 Throwable 或 `getMessage()` 交给日志框架（message 可能含 SQL 参数、连接串、账号）；统一用 `common.web.SafeExceptionLog` 只记录类型与栈帧。新增异常分支要补日志捕获测试（`OutputCaptureExtension` + 合成标记，不用真实密钥）。
  - `SafeExceptionLog` 已公开（MACC-8），只公开 `typeChain`（单行类型链）与 `describe`（类型 + 栈帧）两个方法，其他模块直接复用，不要自行拼接异常信息。
- 服务端页面（`site`，MACC-9 骨架已实施，依据 `04-site-template-spike.md` 第 3.5、5.3 节；用法见 `05-site-skeleton.md`）：
  - 一律先把整页渲染到缓冲区，成功后再写响应（带 `Content-Length`）；不使用边渲染边输出的视图路径，避免失败时返回 200 + 半页 HTML 被缓存。
  - 渲染或读取失败时由 `site` 自己的异常处理（`@ControllerAdvice(basePackages = "com.maccms.backend.site")`，优先于全局 JSON 处理）输出完整错误页并带 `Cache-Control: no-store`，不依赖 Boot 的 `/error` 转发；日志只用 `SafeExceptionLog` 记录类型链。
  - 模板引擎自身的错误日志不得输出异常 message（引擎会自行记录时须关闭对应 logger 并有测试）。
  - 模板（jte 3.2.4，只放在 `src/main/jte/site/`）：只通过生成的 `Templates` 接口与 `SiteHtmlRenderer` 渲染；不用 `$unsafe{}`/`@raw`/`!{}`；事件属性、`<script>`、`<style>`、`style` 属性中不放数据（严格策略直接禁止这些标签与属性）；URL 属性（`href`、`src` 等）的动态值只能是 `SafeUrl.href()`，模板不拼接 URL；库内图片字段（`vod_pic` 等）只经 `site.application.ImageUrlNormalizer` 转成 `SafeUrl`（MACC-11，`06-image-url-normalizer.md`），为空时显示占位图；默认转义不是富文本过滤，不得引用 `vod_content`/`content`；富文本只经 `site.application.RichTextSanitizer` 得到 `SafeHtml`，包成 `gg.jte.Content` 放在元素内容位置输出，旧文本字段的字符引用只经 `LegacyTextEntities.decodeOnce` 按字段清单解码一次（MACC-12，`07-rich-text-filter.md`）。`<script>` 只允许出现在两处、只能带 `nonce` 与 `src`：播放器内页 `site/player/player.jte`（B05）与会员页面共用的 `site/member/script.jte`（B09，`member.js`；引用它的 `pages/user*.jte` 随之按例外编译，`SiteTemplatePolicyTest.EXCEPTIONS`、`SiteGuardrailsTest` 锁定），对应响应的 CSP 用每次随机的 nonce。

### 5. 测试与完成条件

在仓库根目录下：

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

- 只提交本任务涉及的文件。**不提交**：`target/`、`runtime/`、`upload/`、`vendor/`、`application/data/install/install.lock`、本地修改的 `application/database.php`、`*.env`、日志、临时回复文件、`.multica/`、`.agent_context/`、各 worktree 自动生成的 `CLAUDE.md`/`.claude/`。
- 提交前执行 `git status`，逐个确认新增文件。
- 提交信息格式：`<类型>(<范围>): <说明> (MACC-<编号>)`，如 `feat(content): 视频列表接口 (MACC-4)`。

### 7. 协作

- 每个目录有明确的负责人：Java 工程（`src/`、`pom.xml` 等）与 `docs/contracts/` 由后端智能体维护；`docs/frontend/` 由前端智能体维护，后端不修改。需要对方配合时在任务评论中提出，不直接改对方目录，不给其他智能体派活。
- Java 工程内的例外（MACC-9 起生效；`static/site/` 已建立，`jte/site/components/` 在首个组件出现时建立）：`src/main/jte/site/components/` 与 `src/main/resources/static/site/` 由前端智能体维护，后端智能体维护其余部分（含页面模板、视图模型、模板参数签名）；组件模板的 `@param` 签名变更需双方在任务中确认。
- 同一文件同一时间只由一个任务修改；发现冲突先在任务中说明。
- 不确定的业务规则先问，不猜；推断出来的规则标注"待核实"。
