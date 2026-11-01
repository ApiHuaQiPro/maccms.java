### 6.8 前台页面接入（FR-5、FR-8，v3 新增）

**做法**：路由与业务逻辑不动（BR-4：URL 解析、权限、计数、积分、密码校验仍用现有 Java 服务）；每个页面控制器把“组 jte 视图模型”改为“组 PHP 页面变量（`PageVars`）+ 模板名”，交给 `PhpTemplateRenderer`。页面变量里的整行数据取自 `content.application.LegacyRowReadService`（已按 PHP 整行返回，D.7），派生键按 PHP `All.php` 的 `label_*` 函数补齐（新增 `site/web/legacy/` 下的页面装配类，一个 PHP `label_*` 函数对应一个方法）。

| 编号 | 页面组（附表 B.1.1 编号范围以 PRE-3 为准） | 现有控制器 | PHP 对照 | 模板名规则 |
| --- | --- | --- | --- | --- |
| PG-1 | 首页 | `SiteContentController` | `index/controller/Index.php:13` | `index/index` |
| PG-2 | 视频分类、筛选、搜索、详情、播放、下载、播放器、版权、剧情、角色、RSS、ajax 片段 | `SiteContentController`、`SiteSearchController`、`SiteVodShowController`、`SitePlaybackController`、`SiteLibraryController`、`SiteMiscController` | `index/controller/Vod.php:14-131`；`All.php:398-564` | `mac_tpl_fetch('vod', type_tpl/type_tpl_list/vod_tpl/vod_tpl_play/vod_tpl_down, 默认名)`（F-D3 覆盖点） |
| PG-3 | 文章、专题、演员、角色、网址、剧情 | `SiteLibraryController`、`SiteModuleListController` | `Art.php`、`Topic.php`、`Actor.php`、`Role.php`、`Website.php`、`Plot.php` | 同上，各自 `*_tpl` |
| PG-4 | 评论、留言 | `SiteInteractionController` | `Comment.php`、`Gbook.php` | 字面名；`comment/ajax`、`gbook/ajax` 返回 JSON（FR-5） |
| PG-5 | 会员中心 28 页、登录注册找回 | `SiteUserController`、`SiteMemberFinance`、`SiteMemberBoards`、`SitePaymentController` | `User.php` | 字面名 |
| PG-6 | 地图、RSS、帮助、公告、搜索聚合、自定义标签页 | `SiteMiscController`、`SiteAjaxController`（`label/**`） | `Map.php`、`Rss.php`、`Help.php`、`Note.php`、`Search.php`、`Label.php:19-22` | 字面名；`label/<文件名>`（F-D1，文件名含 `/` 或不存在按 PHP 非法请求） |
| PG-7 | 公共提示页：站点关闭、404、跳转、验证码页、浏览器提示 | `SiteExceptionHandler`、`SiteStatusInterceptor`、`SiteVerifyGate` | `Base.php:39,67,77,87`；`All.php:98-116` | `public/close`、`public/<app.page_404，空则 jump>`（F-D2）、`public/verify`、`public/browser` |

**与 PHP 输出不一致、须随 FR-5 改成 PHP 行为的点**（“PHP 无漏洞即以 PHP 为准”；六项安全修复除外，见 §6.14）：

| 编号 | Java 现状 | 改为 | 依据 |
| --- | --- | --- | --- |
| ALIGN-1 | 站点关闭返回 503 + `Retry-After` | 状态码、页面按 PHP `public/close` | `Base.php:77`；比较项 CMP-3 ① |
| ALIGN-2 | 前台未匹配地址返回 JSON 404“接口不存在” | 站点路径（`/api/v1/**`、`/api.php/**`、后台入口之外）按 PHP `_empty` → `page_error`（404 + `public/<page_404>`）；`/api/v1/**` 仍为 JSON | `Base.php` `_empty`；`All.php:98-116` |
| ALIGN-3 | 错误页、跳转页是 jte 自绘（`ErrorPage`、`UserJumpPage`） | 一律经模板包的 `public/jump`、`public/msg` 或 PHP 内置视图（`php/application/index/view/public/jump.html`、`msg.html`，按字节随 jar 打包为资源，经同一引擎渲染）；模板文件缺失时按 TPL“模板文件不存在” | B.1.2“内置视图”；`Init.php:67-68` |
| ALIGN-4 | 观看密码：签名 cookie，`ajax/pwd` 返回 404 | 恢复 `ajax/pwd` 接口与 PHP 相同的请求与响应（JSON）；“已通过密码”的记录仍放进程内会话（`SessionStore`，PHP 是 session，二者都不进库、不参与比较）；保留现有频率限制 `PasswordAttempts`（PHP 无频率限制，此处保留属“不改变成功路径输出”的防爆破，若对照出现差异按 OP-T4 处理） | `Ajax.php:400-456`；`All.php:547-549` |
| ALIGN-5 | 响应头 `Content-Type` 由各控制器设 | 统一按 PHP：HTML 页 `text/html; charset=utf-8`，RSS/地图 XML 与 ajax JSON 按 PHP 控制器（PRE-2 基线记录实际值） | CMP-3 ② |

页面变量的逐页清单（每页 `assign` 的键、层级、取值来源）在各 PG 子任务的设计说明里按 PHP 控制器逐行列出，由 AC-4/AC-5 的样本验证；不允许出现 PHP 没有的键（会改变 `{if isset}` 类分支）。

**手机（FR-8）**：手机/站群选择沿用 `SiteTemplateSelection` + `SiteVariants`（黄金锁定）；`PhpTemplateRenderer` 按 `SiteVariant` 选读取根（PATH-1）与 `path_tpl`/`path_ads`（PATH-2/4，C-12～C-16 修正后）。手机模板目录缺失时不回退桌面（`21-b12-admin-framework.md` 第 12 节）。语言键对照（KPI-5）直接比 `LegacyLang.get(key)` 与 PHP `lang(key)`，不经页面。

### 6.9 jte 前台与 D4 在线编辑下线（OP-3，v3 新增）

- 删除：`src/main/jte/site/**`（76 个）、`sitetemplate` 模块全部类、`SiteConfiguration.OnlineTemplates` 与 `SiteHtmlRenderer`、`site/web/view/*Page` 等只为 jte 服务的视图 record（逐个以“删除后编译通过”为准）、`pom.xml` 中 site 的 `jte-maven-plugin` execution 与 `site-jte` 资源拷贝、只在在线编辑用到的 `gg.jte:jte` 编译器依赖（后台、安装仍用预编译的 `jte-runtime`，保留）、`src/main/resources/static/site/**` 与 `static/site-player/player-boot.js`（只被 jte 页面引用；删除前用 `git grep` 确认模板包与 `php/static` 不引用）、`src/test/jte-probe/**`、`SiteTemplatePolicyTest`、`SiteTemplateServiceTest`。
- 改写：约 34 个断言 jte 页面的测试类（§3.2 列出）改为断言 PHP 模板输出（用 `third_party` 下的真实模板包做输入），或在页面黄金对照（AC-5）覆盖后删除；逐类决定写在对应 PG 子任务里，不留“暂时跳过”的测试。
- 下线是**按页面组逐批**做的：某 PG 组切到 PHP 模板并通过本组样本后，才删除该组 jte 文件与测试；全部 PG 完成后删 `sitetemplate` 与插件配置（C-40）。

### 6.10 `/template/**` 静态资源（DEC-8，关闭 OP-T1，v3 新增）

| 项 | 取值 |
| --- | --- |
| URL | `GET/HEAD /template/<目录>/<css|js|images|ads>/<相对路径>`；`<目录>` 必须是 `template/` 下实际存在的子目录（不限于 `default_pc`/`default_wap`，与 PHP+nginx 一样跟随配置） |
| 文件 | 站点根 `template/<目录>/<子目录>/<相对路径>`，只经 `LegacySiteFiles` 读取（只读区域，路径规范化后仍在 `template/` 内，拒绝 `..`、反斜杠、空字节、出根符号链接） |
| 扩展名白名单 | `css js png jpg jpeg gif ico svg webp woff woff2 ttf eot swf`；其他（含 `html htm ini php txt`）返回 404 |
| 响应 | `Content-Type` 按扩展名固定表；`Last-Modified` + 条件请求；不设缓存控制以外的额外头 |
| 位置 | `site/web/TemplateAssetController`（新增），只在 `maccms.site.enabled=true` 时装配 |

### 6.11 后台模板管理（FR-7，v3 新增；目录见 §6.1）

| 项 | 改为 | 依据 |
| --- | --- | --- |
| 扩展名白名单 | `html,htm,js,xml`（撤销 D-B15-14，`jte` 去掉、`htm` 恢复） | `Template.php:170`；W-06 |
| 内容过滤 | 规则文本与判定方式不变（`LEGACY_FILTER`，与 PHP 逐字相同，命中即拒绝，提示用 `admin/template/php_safe_tip`）；常量从 `SiteTemplateService` 移到 `admin.application.TemplateFileService` | `Template.php:177,192`；W-05 |
| 保存 | 经 `LegacySiteFiles.replace` 原子写 + 版本冲突检查（保留 B15 约定，W-07）；保存后由 TPL-8 的修改时间检查使下一次请求重编译（W-04） | BR-6 |
| 备份与恢复 | 删除 jte 专用的“覆盖前备份 / `op=restore`”（PHP 没有） | PHP 无此功能 |
| 删除 | 同 PHP：只能删 `template/` 内文件，路径校验同保存 | W-08 |
| 标签向导 | `wizard` 改为 PHP `admin@template/wizard` 的同一表单与生成规则，输出 `{maccms:vod …}…{/maccms:vod}` 片段（逐项按 PHP 视图里的 JS 生成逻辑移植，生成在浏览器端完成的部分照搬为后台静态脚本） | `Template.php:237`；W-09、AC-9 |
| 权限 | 沿用 `admin_auth` 的 `template/*` 权限码（W-10） | B12 |

### 6.12 静态生成（FR-6，v3 新增；目录见 §6.2）

- 页面内容仍经 `StaticPageSource` → `InternalPageRenderer`（进程内 GET），FR-5 完成后自动使用 PHP 模板；`ac2=wap` 时 `InternalPageRenderer` 用手机 `SiteVariant`（手机 UA 等价的选择结果），读 PATH-1 手机根，变量取 PATH-2/4 手机值。
- 按 PHP 补齐与 B16 的差异（PHP 为准）：`label` 生成（撤销 D-B16-5：用 `label/` 下文件逐个生成）、RSS 分页（撤销 D-B16-6）、`*_time_make` 写入扩到 `actor`/`role`/`website`（K-12，D-19；写入需在 `WriteRegistry` 登记这三张表的该列 `UPDATE`）、失败列表输出格式按 PHP（K-11）。
- 生成失败（模板报错）记入失败输出，继续下一页（K-11）。

### 6.13 数据库工具四项（FR-9，v3 新增）

| 编号 | 动作 | 设计 |
| --- | --- | --- |
| DB-1 | 优化表、修复表 | 入参表名必须属于附表 A 的 26 张表（白名单常量 + 前缀校验），对每张选中的表各发一条 `OPTIMIZE TABLE <名>` / `REPAIR TABLE <名>`（表名用反引号，来自白名单而不是请求原文）；返回结果按 PHP 页面展示。`it_app` 的 `SELECT, INSERT` 权限足够执行这两条语句 |
| DB-2 | 执行 SQL | 先按 PHP `mac_parse_sql` 的规则切分语句（逐字移植该函数），再对每条语句做**词法分类**：去掉注释（`-- `、`#`、`/* */`，含 `/*! */` 版本注释一律视为不可识别而拒绝）、跳过空白后取首个关键字，只允许 `SELECT`、`INSERT`、`UPDATE`、`DELETE`；另拒绝含 `INTO OUTFILE`、`INTO DUMPFILE`、`LOAD_FILE(` 的语句（读写服务器文件，属安全收紧，SEC-8）。**任意一条不通过则整批拒绝，不向数据库发送任何语句**（AC-11 ②），提示“结构类操作请由部署方在库外完成”；全部通过后逐条执行，连接关闭多语句（`allowMultiQueries=false`） |
| DB-3 | 还原备份 | 读取备份文件（只限 `application/data/backup/database/` 下由本系统或 PHP 导出的分卷），按 `mac_parse_sql` 切分；`DROP TABLE`、`CREATE TABLE` 以及其他非 DML 语句**跳过不执行**并计数显示；对备份中出现 `INSERT INTO <表>` 的每张表（必须在 26 张白名单内），先执行一次 `DELETE FROM <表>`，再执行该表的 `INSERT`（“只还原数据”：结果与 PHP 删表重建后插入的数据相同，结构不动） |
| DB-4 | 其余 | 列表、列、导出、删备份、批量替换保持 B15 现状 |

`SchemaGuardTest` 的登记例外增加 DB-1、DB-2 的实现类（它们的源码里出现 `OPTIMIZE TABLE`、`REPAIR TABLE` 字样与拒绝关键字表），写明理由与 AC-11 编号（C-55）。

### 6.14 安装向导建表（FR-10，v3 新增）

| 编号 | 规则 |
| --- | --- |
| INS-1 | 待执行文件 = 站点根 `application/install/sql/install.sql`（经 `LegacySiteFiles` 只读，区域白名单增加该一个文件）；Java 内置常量 `INSTALL_SQL_SHA256 = 152e54242d78a7c44f2030bfb0e04c0b5067c0d54180bd6cb77ddee7d3eaabcb`（= 仓库 `php/application/install/sql/install.sql` 的 SHA-256，基线提交核实；单测断言常量等于仓库文件的哈希，防漂移） |
| INS-2 | 第 5 步顺序：①用第 3 步填写、存于 `MACCMS_DEPLOY_CONFIG` 的账号开连接（AC-12 为 `it_install`）；②查 `information_schema.TABLES` 中该库 `mac_` 开头的表数，>0 → 提示“已存在数据表”，不执行；③读文件算 SHA-256，≠ 常量 → 提示“文件哈希不一致”，不执行；④按 `mac_parse_sql` 切分后逐条执行（前缀固定 `mac_`，Java 已要求前缀为 `mac_`，所以不做替换，“一字不改”）；⑤按 PHP 写初始数据（`initdata` 选 1 时执行 `initdata.sql` 的 INSERT，现有 `InstallWrites`）、写首个管理员、写 `extra/maccms.php`；⑥写 `install.lock` |
| INS-3 | 不发 `CREATE DATABASE`（库由部署方或验收脚本预建，A.3）；不执行除该文件外的任何 DDL；删除 `InstallSchema` 指纹校验分支（改为 INS-2 ②③），指纹资源保留给 `InstallWizardMySqlIT` 做结果核对 |
| INS-4 | 账号分离：建表连接只在第 5 步请求内使用，用后关闭；正常模式运行期只读 `MACCMS_DB_*`（AC-15 的 `it_app`），不读 `MACCMS_DEPLOY_CONFIG`、不读 `database.php`（RISK-15 的交接方式）。PHP 第 5 步写 `application/database.php`（D-23 文件栏）；Java 现状写部署配置文件，是否另写 `database.php` 由 FR-12 对 install 行（附表 C 安装向导 5 行）判定，本设计不预设 |

### 6.15 在线采集、远程图片、规则在线抓取、远程存储、定时采集（FR-11，v3 新增）

| 编号 | 功能 | 设计 |
| --- | --- | --- |
| NET-1 | 资源站在线采集（`collect/api` 在线、`collect/test`） | 新增 `integration.collect`（登记 `IntegrationPackages.REGISTERED`）：按 PHP `Collect` 模型的请求构造（`ac`、`t`、`pg`、`h`、`ids`、`wd` 参数，XML/JSON 两种协议）经 `OutboundHttpClient` 取数；`collect` 模块只经 `integration.application.ResourceSiteClient` 接口调用，取回的文本交给已有的解析与入库（B18/B19，`CollectPreviewService`、`CollectRunService`） |
| NET-2 | 定时采集（`collect/timing`、定时任务 `collect`） | 从 `AdminPermissions.NOT_PROVIDED` 与 `TimmingTasks` 的拒绝项移除，按 PHP `Collect::timing` 与 `api/Timming` 的 `collect` 分支调用 NET-1（撤销 D-B19-15） |
| NET-3 | 远程图片下载（采集入库 `sync_pic_opt`、`images/sync`） | 新增 `integration.image`：按 PHP `common/model/Image.php:7` `down_load` 的规则下载、校验类型与大小、按 `upload` 配置落盘（经 `LegacySiteFiles` 的 `upload/` 写区域）并写 `mac_annex`；`VodIngest` 与 `images/sync` 改调此接口 |
| NET-4 | 自定义规则在线抓取（`cj`） | `CjRunService` 的取页从 `LocalHtmlFiles` 扩为“本地文件或经 `integration.collect` 在线取页”，规则执行不变 |
| NET-5 | 远程存储 | 新增 `integration.storage`：六个上传器按 PHP `common/extend/upload/{Upyun,Qiniu,Ftp,Weibo,Alibaba,Uomg}.php` 移植；HTTP 类经 `OutboundHttpClient`（签名用 JDK `javax.crypto`），FTP 用 JDK `ftp:` URL 连接（不加依赖，登记为 `RAW_API` 并自行执行同一套目标检查，同 `integration.mail` 的做法）；`AdminUploadService` 按 `upload.mode` 选择 |
| NET-6 | 出站白名单与护栏 | 三个新包登记进 `IntegrationPackages`（AC-13“登记进出站白名单”）；`OutboundNetworkGuardTest`、`RegisteredOutboundClientTest` 同步；验收在 `OutboundPolicy.LOOPBACK_ONLY` 下用本机模拟服务（沿用 `integration.testing` 的做法），断言无本机以外的连接 |

### 6.16 旧控制器对齐（FR-12，v3 新增）

- 判定由测试人员按附表 C 逐行做（AC-14），报告 `docs/test/APPK-2/parity-*.md`，缺口登记 `docs/test/APPK-2/gap-register.md`（§9.1 格式）；本设计不预判结果。
- 判定口径（交测试人员）：PHP 无漏洞的行为以 PHP 为准；六项安全修复（§6.17）和本设计列出的安全收紧（SEC-7、SEC-8）判 `排除-D2` / `排除-<本设计编号>` 时须经发起人按 OP- 确认，不能自行判“已对齐”；历史 D-Bxx 决定与 PHP 不同、又不是安全修复的（如 D-B15-14、D-B15-15、D-B16-5、D-B16-6、D-B19-15、D-B13-2、D-B19-14），本设计已按 PHP 改回（§6.11～§6.15），对应行判定以改回后的行为为准。
- 缺口迁移：每个 GAP- 单独建单、单独设计说明（引用本设计的模块边界与附表 D 越界判定），不在本设计里列实现。

### 6.17 六项安全修复（BR-4、OP-9，v3 新增）

| 编号 | 修复 | 本期改动是否触及 | 保持方式 |
| --- | --- | --- | --- |
| SEC-1 | 会员登录只比 `md5(输入)` | FR-5 PG-5 改登录页输出 | `MemberAccountService.login` 不改；PG-5 只换呈现 |
| SEC-2 | 普通登录/注册忽略 `openid`/`col` | 同上 | 同上 |
| SEC-3 | `api.php/timming` 鉴权 | FR-6、FR-11（NET-2 定时采集） | `TimmingAccess` 不改，NET-2 只增加任务类型 |
| SEC-4 | 可信代理才信转发头 | FR-11（在线采集记录来源 IP）、评论 | 一律经 `ClientIpResolver` |
| SEC-5 | 管理员每请求重读 | FR-7、FR-9 后台 | `AdminController` 不改 |
| SEC-6 | 注销/改密/重置轮换 `user_random` | PG-5 | 不改 |
| SEC-7（本设计） | `/template/**` 只给静态资源，不给 `.html`/`.ini` | DEC-8 | 不进比较样本；FR-12 相关行按 OP 确认 |
| SEC-8（本设计） | 执行 SQL 拒绝 `INTO OUTFILE/DUMPFILE`、`LOAD_FILE` | DB-2 | 同上 |

验证：每项在所属批次已有的用例（“旧行为不再成立”断言）必须在 FR-5～FR-11 合入后仍通过（V-24），列入回归点。

### 6.18 缓存（关闭 OP-T2 / V9-001，v3 新增）

| 编号 | 规则 |
| --- | --- |
| CACHE-1 | 标签结果缓存、详情缓存、页面缓存只在 `app.cache_core=1`、`app.cache_page=1` 时启用，键规则同 PHP；进程内实现（有界 LRU，按 `cache_time`/`cachetime` 过期），不落库；对照环境用基线值 `0`，不缓存 |
| CACHE-2 | 编译缓存（TPL-8）按文件修改时间自动失效，对照时不影响结果；仍随 CACHE-3 清空，保证“冷启动”路径也被覆盖 |
| CACHE-3 | `CacheResetHook.resetAll()`：清 CACHE-1、CACHE-2 和 `RichTextCache`。触发方式：①后台“清理缓存”入口（PHP `admin/index/clear`，现有 Java 入口加一行调用）；②对照专用：Java 进程启动参数 `maccms.phptemplate.compare.reset-file=<路径>`（只在对照环境设置，生产不设，默认关闭），每个请求开始时若该文件的修改时间变了就先执行一次 `resetAll()`——测试脚本在 ENV-9 里“touch”该文件，不需要新增 HTTP 接口 |
| CACHE-4 | “启动时读一次配置”的站点 Bean（`SiteSettings`、`LegacyUrlBuilder`、`SiteVariants`、`PlayerSettings` 等）：对照中会改配置的样本只有 F-D2（`app.page_404`）与后台保存类样本；F-D2 两侧都通过“先改配置文件、再重启该侧进程”执行（在样本清单写明），ENV-5 恢复配置后同样重启该侧；PHP 每请求读配置，不需要重启。是否把这些 Bean 改为按 `LegacyConfigService.generation()` 刷新，属于 FR-12 对“改配置立即生效”行为的判定，不在本设计预设 |

### 6.19 数据表与字段（v3 新增）

- 本设计**不新增、不修改任何表、列、索引**（BR-1）。
- 读写范围逐功能以附表 D 为准：标签库 D-02/D.6 TAGMAP-1～19（只读，`mac_vod_search` 写入沿用 D-01）；前台页面 D-01、D-03～D-07e；静态生成 D-19（`vod/art/topic/actor/role/website` 的 `*_time_make`）；数据库工具 D-18/DYN-7；安装 D-23/DYN-9；采集与图片 D-14～D-16/DYN-5～6（`mac_annex` 写入在 OP-5 放开后启用）；模板管理 D-20（只有文件）。
- 新增的写入（静态生成 `actor_time_make`、`role_time_make`、`website_time_make`；图片下载 `mac_annex`；数据库还原的 `DELETE`/`INSERT`）都先在 `WriteRegistry.REGISTERED` 登记，表与列必须在附表 D 对应行内（AC-16）。

### 6.20 对照数据快照与恢复（PRE-2，CMP-2，v3 新增）

| 编号 | 规则 |
| --- | --- |
| SNAPD-1 | 快照 = `it_admin` 用 `mysqldump --no-create-info --skip-triggers --complete-insert --hex-blob --skip-extended-insert` 导出 26 张表数据到 `.compare/APPK-2/data-snapshot.sql`，另记录每张表的 `CHECKSUM TABLE` 值与 `information_schema.TABLES.AUTO_INCREMENT` 到 `docs/test/APPK-2/data-snapshot.json`（脚本 `tests/compare/APPK-2/data_snapshot.py`） |
| SNAPD-2 | 恢复（每个单元前）：对 26 张表 `DELETE FROM`、执行快照 SQL、对每张表 `ALTER TABLE <表> AUTO_INCREMENT=<记录值>`（由 `it_admin` 在库外执行，只改计数器，SNAP 不含该值，CMP-2 ① 明确允许）、再校验 `CHECKSUM TABLE` 与 `AUTO_INCREMENT`，不等重试一次，仍不等标“环境失败” |
| SNAPD-3 | 恢复记录按 CMP-2“报告义务”逐单元写一行；脚本输出机器可读结果供对照报告汇总 |
| SNAPD-4 | MySQL 会话时区 `+08:00`（PHPB-8，关闭 OP-T3 一半）：PHP 侧在 ENV-2 生成的 `database.php` 里加 `'params' => [PDO::MYSQL_ATTR_INIT_COMMAND => "SET time_zone='+08:00'"]`；Java 侧 `MACCMS_DB_URL` 加 `sessionVariables=time_zone='+08:00'`（只在对照环境）；两侧都在基线报告里记录 `@@session.time_zone` |
| SNAPD-5 | `php/vendor/`（关闭 OP-T3 另一半）：按 `AGENTS.md`「本地运行与测试」从仓库外生产备份解压到 `pristine/vendor/`，基线报告记录 `vendor/` 目录的文件数与按路径排序后的 SHA-256 汇总值；备份文件只在本机，不入库 |
