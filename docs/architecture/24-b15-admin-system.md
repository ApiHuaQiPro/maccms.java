## B15 后台系统配置、运维、模板在线编辑与本地插件（路线图 B15，MACC-39）

> **状态：交付待验收。** 依据：
> - 路线图 `09-roadmap.md` 第 3.2 节 B15 行、第 5.3 节 B15 验收行（配置写回逐字节、原子写、在线编辑演示、插件文件效果、数据库工具只读/纯 DML、`information_schema` 不变、回滚演练）、第 5.6 节（模板在线编辑，D4）、第 5.7 节（本地插件，D5）、写入方矩阵、第 1.2 节（零结构变更，硬性要求 2）；
> - B02 `11-guards-and-legacy-config.md`（旧配置只读层）、B07 `12-write-infrastructure.md`（写 SQL 规范、登记表、回滚演练工具）、B12 `21-b12-admin-framework.md`（后台入口、会话、权限、页面与脚本策略）、B13/B14（后台表单与黄金对照的做法）；
> - 用户决定（2026-10-17）：后台复用旧后台 HTML 结构与静态资产，转成 jte，不做新视觉设计。
>
> 旧代码位置一律相对仓库根目录；新代码写作 `backend/…`。

### 0. 起点与分支

| 项 | 值 |
|---|---|
| 起点 | 平台基线 `b0a7bf1`（树与 main `2d2b7e8` 相同） |
| 分支 | `agent/claude/macc-39`，只用普通提交，不 reset、rebase、amend、force，不自行合并 main |
| 回滚点 | main `2d2b7e84281a5cde2cf7f034d4df4f4d5af8461b` |
| 提交 | `42c3916`（写回基础与系统配置各页）、`918b5e5`（播放器/服务器组/下载器、站群、定时任务配置）、`5e95397`（本地插件）、`d509461`（数据库工具）、`112ae30`（模板管理与在线编辑、原子写测试），之后的文档与收尾提交见交付评论 |

### 1. 范围

| 旧控制器（`application/admin/controller/`） | 本批实现的动作 |
|---|---|
| `System.php` | 15 个配置页 `config` `configseo` `configuser` `configupload` `configcomment` `configweixin` `configpay` `configconnect` `configemail` `configsms` `configplay` `configapi` `configinterface` `configcollect` `configurl`（GET 页面、POST 写回）；`test_cache`（固定提示，D-B15-4） |
| `Index.php` | `quickmenu` 的 POST（B12 已有页面，保存归 B15） |
| `Urlsend.php` | `index`（推送配置页与保存） |
| `Vodplayer.php` `Vodserver.php` `Voddowner.php` | `index` `info` `del` `field` `export` `import`；`playerconfig.js` 的"参数""缓存"两段 |
| `Domain.php` | `index`（站群列表与保存） `del` `export` `import` |
| `Timming.php` | `index` `info` `del` `field`（只是配置；定时任务的执行与 runtime 回写在 B16） |
| `Template.php` | `index` `ads` `info`（含新增的"恢复上一版"） `del` `wizard`（第 7 节） |
| `Database.php` | `index`（表状态、备份列表） `export` `del` `columns` `rep`；`import` `optimize` `repair` `sql` 入口拒绝（第 9 节） |
| `Addon.php` | `index` `downloaded` `add` `config` `state` `uninstall` `local`（`closed`） `info`（第 11 节） |

| 不在本批（归属） | 说明 |
|---|---|
| 发信测试 `system/test_email`（E07） | B25；按 B12 分派返回"由 B25 批次提供" |
| 推送执行 `urlsend/push`（E09）、回链检测（E11） | B28 |
| 插件市场 `addon/install`、`addon/upgrade`（E13） | B29；B12 分派返回"由 B29 批次提供"，不发请求 |
| 定时任务的触发、执行与 runtime 回写 | B16 |
| 还原备份、执行 SQL、优化表、修复表 | 按硬性要求 2 排除（零结构变更），入口拒绝（D-B15-1） |

批次归属调整（`AdminPermissions`，21 文档第 4 节表格已同步）：`timming/*` 由 B16 改归 B15（配置页属于"定时任务配置"，执行仍在 B16）；`addon/downloaded` 由 B29 改归 B15（这份生产代码里它只列本地插件，`Addon.php:88` 注释"仅支持本地插件"）。

### 2. 模块与依赖

| 包 | 内容 |
|---|---|
| `legacyconfig.application` | `VarExportWriter`（`var_export` 逐字节等价，第 3.1 节）；`LegacyConfigService` 新增 `snapshot`、`replace`、`update`、`generation`（第 3.3 节）；`LegacySiteFiles`（旧站其他文件的受限访问，第 3.4 节） |
| `legacyconfig.infrastructure` | `AtomicFiles`（原子写与进程内文件锁）、`LegacySiteRoot`（站点根目录内的路径解析与文件操作） |
| `admin.application` | `SystemConfigService`（各配置页的保存）、`PlayerAdminService`、`ListConfigService`（站群、定时任务）、`AddonService`、`DatabaseToolService`；旧语义：`PhpHtmlEntities`、`PhpLoose`、`PhpCompare`、`PhpArrays`、`LegacyPhp`（第 4.2 节） |
| `admin.infrastructure` | 只读 `DatabaseToolMapper`（`mapper/admin/DatabaseToolMapper.xml`，只有 SHOW 与 SELECT）；写 `DatabaseReplaceWriteMapper`（`mapper-write/admin/`） |
| `admin.web` | `AdminSystemActions`、`AdminPlayerActions`、`AdminAddonActions`、`AdminDatabaseActions`、`AdminTemplateActions`；公共 `AdminForms`（第 4 节）；`view/system/ConfigPage` + `LegacyView`（旧模板取值的统一视图） |
| **`sitetemplate`（新模块）** | `application`：`SiteTemplateService`（实现 `site.application.SiteTemplateSource`）、`JteSecurityFilter`、`TemplateWizard`、装配 `SiteTemplateConfiguration`/`SiteTemplateProperties`；`infrastructure`：`JteOnlineCompiler`、`JteClassPath`、`OnlineHtmlPolicy`、`BuiltinTemplateSources`（第 7 节） |
| `site` | 新增接口 `site.application.SiteTemplateSource`；`SiteHtmlRenderer` 每次渲染取一次模板入口（`Supplier<Templates>`）；`SiteConfiguration` 有 `SiteTemplateSource` 时按它当前的引擎建 `DynamicTemplates`，没有或返回 null 时用内置 `StaticTemplates` |

- **边界**（`ModuleBoundaryTest`）：`admin` 可用的 site 类型加 `site.application.PhpJson`（`playerconfig.js` 与播放器导出用的 PHP `json_encode` 等价编码，纯计算）；`sitetemplate` 只引用 `site.application`（`SiteTemplateSource`）与 `legacyconfig.application`；`site` 仍不读文件、不编译模板，只经 `SiteTemplateSource` 拿引擎。
- **运行时编译只在 `sitetemplate.infrastructure`**：`SiteGuardrailsTest.runtimeTemplateCompilationOnlyInSiteTemplateInfrastructure` 锁定（`TemplateEngine.create`、`DirectoryCodeResolver`、`precompileAll`、`javax.tools` 等只能出现在那里）。jte 编译器（`gg.jte:jte`）由 test 作用域改为编译作用域（D4 的必要条件，`SiteGuardrailsTest.buildUsesPinnedPrecompiledJteWithoutStarter` 同步）。
- **注册条件**：`SiteTemplateConfiguration` 总是注册（组件扫描，后台的 `AdminConfiguration` 也引入它）；`maccms.site-template.enabled` 默认关闭，关闭时不建工作目录、不编译，前台一直用内置模板。数据库工具的 Bean 只在有只读数据源（`DatabaseToolMapper`）时注册，没有时这几个动作仍是"由 B15 批次提供"。

### 3. 配置写回

#### 3.1 `var_export` 与 `mac_arr2file`

- `VarExportWriter.macArr2File`：`"<?php\nreturn " . var_export($arr, true) . ";"`（旧 `mac_arr2file`，`application/common.php`）；`addonsFile`：`"<?php\n\nreturn …;"`（`Service::refresh` 写 `extra/addons.php`）；`addonConfigFile`：`"<?php\n\nreturn …;\n"`（`set_addon_fullconfig`）。
- 输出规则与 PHP 7.3/8.0 逐字节相同：键（整数键与数字串键）、字符串转义（`\\` 与 `\'`）、嵌套缩进与 `array (` 后的空格、空数组、布尔与 NULL、浮点（PHP 8 的 `serialize_precision=-1` 最短往返表示；`php_gcvt` 的指数形式与补 `.0`）。不能写的值（含 NUL 的字符串、`Long.MIN_VALUE`、NaN/∞）拒绝写，调用方报"参数错误"（D-B15-7）。
- 黄金：`backend/src/test/php/b15_php_semantics_golden.php` 在 PHP 8.0.30 与 7.3.33 上各生成 310 个值、242 个浮点、252 项 HTML 实体表、231 个 `htmlentities` 向量、7 个 `mac_txt_replace` 向量（`backend/src/test/resources/legacyconfig/php{80,73}-semantics-golden.json`）；`VarExportWriterGoldenTest` 逐字节比较，并把仓库里每个配置文件"读入 → 写出"与原文件对比（CRLF 规范为 LF 后相同）。

#### 3.2 `input()` 与 `htmlentities`

- 旧 `input()` = GET + POST + 路径参数（后者覆盖前者），按 PHP `parse_str` 还原多维数组（`AdminForms.input`）。旧版用 `input('', '', 'htmlentities')` 的页面（系统配置除 `configurl` 外的各页）每个值先过 `PhpHtmlEntities.encode`（`ENT_COMPAT | ENT_HTML401`、UTF-8、`double_encode`；非法 UTF-8 按 PHP 变成空串）；`configurl` 与 `urlsend` 用不过滤的 `input()`，照旧。
- 值不是合法 UTF-8 或含 NUL 时"参数错误"、不写（D-B15-7；旧版写进文件后自己也读不回）。

#### 3.3 原子写、并发与缓存

- `AtomicFiles.replace`：同目录临时文件（`.名字.*.tmp`）→ 写入 → `fsync` → `ATOMIC_MOVE` 覆盖；POSIX 权限沿用原文件（新文件 `rw-r--r--`）；失败时删除临时文件、原文件不变。Windows 上目标正被读取时改名会短暂共享冲突，重试约 2 秒（`AtomicFilesTest.readersOnlyEverSeeACompleteVersion` 在本机复现过该冲突）。
- 乐观并发：调用方带上读到时的版本（SHA-256，文件不存在为 `absent`），改名前在进程内文件锁里核对，不同则 `CONFLICT`。`LegacyConfigService.update` 在同一把锁里"读 → 算 → 写"，同进程并发保存串行、不丢更新；进程外（仍在运行的旧 PHP 或手工）在读写之间改过则 `CONFLICT`。
- 写出的内容必须能被新版重新解析，否则不写（`INVALID_VALUE`）。写回后立即清掉该文件的缓存（不等修改时间变化），`generation` 加一。
- 后台表单的"页面版本"：打开配置页时把本页涉及的几组配置的版本记进表单令牌（`AdminForms.issue`），保存时比较，期间被他人改过 → "配置已被他人修改，请刷新页面后重新保存"（D-B15-5）。
- 测试：`AtomicFilesTest`（创建/覆盖/版本不符拒绝、改名失败不留临时文件、12 个线程基于同一版本保存只有 1 个成功、读者从不读到半个文件、`locked` 串行化）；`LegacyConfigWriteTest`（读不回的内容不写、过期版本拒绝且新值立即可读、20 个线程并发 `update` 不丢更新、进程外修改 → 冲突）。

#### 3.4 旧站其他文件（`LegacySiteFiles`）

站点根目录 = `maccms.legacy-config.dir`（旧站 `application/`）的上一级。读写区域只有 `static/`、`template/`、`addons/`、`application/data/backup/`；只列目录的有 `application/lang`、`application/common/extend/`（取扩展类的显示名只读 `application/common/extend/<类型>/<类名>.php` 的源码文本）。路径一律相对根目录、逐段校验（不允许空段、`.`、`..`、反斜杠、冒号、控制字符），解析后的真实路径必须在根目录内（符号链接逃逸也拒绝）。`database.php` 等其他文件访问不到；区域根目录本身不能整体删除。

### 4. 后台表单的公共部分

#### 4.1 令牌

旧 `{$Request.token}` 与 `validate('Token')`：缺失 → "非法请求"，不一致 → "请不要重复提交表单"，校验后作废（同 B12 管理员表单）。B15 签发令牌时可同时记下页面数据的版本（第 3.3 节）。

#### 4.2 旧语义的等价实现

`PhpLoose`（PHP 8 宽松比较 `==` 与 `is_numeric`，模板的 `eq`/`neq`）、`PhpCompare`（`<=>`，含数组比较；`array_multisort` 平局按第二个数组升序）、`PhpArrays`（`array_merge` 等）、`LegacyPhp`（`trim`、`mac_txt_replace`、`md5`、`json_decode`、`parse_ini` 子集、`explode`/`join`、`mac_get_body`、非严格 `base64_decode`）；`Config::load` 的全局合并（`mob_ads_dir` 继承模板的 `adsdir` 等旧现象）照旧。

### 5. 系统配置各页

15 个配置页、`urlsend/index` 与快捷菜单的保存都在 `SystemConfigService`：`array_merge($config_old, $config_new)` 只替换本页的几组，写回 `extra/maccms.php`，以及本页附带的文件——`configseo`/`config` 的统计代码写 `static/js/tj.js`（`document.write` 包装照旧）；`configurl` 生成 `route.php`；`configplay` 重写 `static/js/playerconfig.js` 的"参数"段；快捷菜单写 `extra/quickmenu.php`。缓存标志 `cache_flag` 照旧随机生成；搜索规则一个都不勾时按 PHP 7.3（D-B15-6）。`test_cache` 不连接任何缓存服务（新系统没有外部缓存），返回固定提示（D-B15-4）。写入失败时报"保存失败"（D-B15-3）。

### 6. 播放器、服务器组、下载器、站群、定时任务配置、推送配置

- 播放器/服务器组/下载器（`PlayerAdminService`）：列表、表单、保存、删除、状态、导出（`base64` 文本附件）、导入（非严格 `base64_decode`、`info.ini` 风格解析）；保存与删除后照旧置"缓存待清理"标记，框架页的 `index/checkcache` 返回 `haved`，"清理缓存"（`index/clear`）按三个配置重写 `playerconfig.js` 的"缓存"段。服务器组的 `field` 照旧不看 `ids`（全部设置）。
- 站群（`ListConfigService`）：`extra/domain.php` 的列表、保存、删除、导出、导入（导入成功时提示语言包 `import_err`，旧版笔误照旧）。
- 定时任务配置：`extra/timming.php` 的列表、表单、保存（旧版把表单令牌也写进文件，照旧）、删除、状态。执行在 B16。
- 推送配置页（`urlsend/index`）只读写配置；推送执行在 B28。

### 7. 模板管理与在线编辑（D4）

#### 7.1 部署参数与目录

| 参数 | 默认 | 说明 |
|---|---|---|
| `maccms.site-template.enabled` | `false` | 开启后前台改用外部模板目录的编译结果，后台可以在线编辑 jte 模板 |
| `maccms.site-template.work-dir` | 空（每次启动在系统临时目录下新建） | 运行时编译的工作目录：每次编译一个 `gen-<n>/`（源码副本与生成的类）；可执行 jar 时另有解出的类路径 `boot-classpath/` |

- 外部模板目录 = `template/<site.template_dir>/<site.html_dir>/`（与旧版目录约定一致，默认 `template/default/html/`），其中的 `*.jte` 是**一整套**前台模板，模板名即相对路径（`site/pages/home.jte` 等，与内置模板同名）。
- 首次启用（目录里没有任何 jte）时从内置版本导出一份（71 个文件；构建时 `src/main/jte` 随 jar 带到 `classpath:/site-jte/`）。
- 启动时整套编译；任一文件编译失败或含安全过滤拒绝的写法 → 记录 ERROR 日志、前台用内置模板、后台欢迎页与模板管理页显示原因（模板名、行号）。站点不会因为模板错误而无法启动。
- 只有 JRE（没有 `javac`）时：开启也不生效，前台用内置模板，后台提示"当前运行环境不支持在线编译"，jte 拒绝保存。
- `template_dir`/`html_dir` 在后台改了以后，下一次保存或重启时按新目录编译（前台模板的切换不是即时的，D-B15-16）。

#### 7.2 保存流程（`SiteTemplateService.save`）

1. 旧版同等的路径检查（`./template` 开头、只有一个 `./`、逐段校验，第 3.4 节）与扩展名白名单 `jte/html/js/xml`（D-B15-14）。
2. jte：必须在当前模板目录下、文件名只含字母数字与 `_.-`；内容必须是 UTF-8；**安全过滤**（第 7.3 节）命中就拒绝。js/html/xml 等静态文件照旧版的过滤字符串（`<?`、`php`、`{$` 等，`mac_reg_replace` 的 `/is`）检查后直接写入，响应与旧版逐字节相同。
3. 乐观并发：编辑页带隐藏字段 `fversion`（打开时文件的 SHA-256），与当前不同 → "文件已被他人修改，请刷新页面后重新编辑"。
4. 把"外部目录当前的整套 jte + 本次修改"写进新的编译目录，用新的 jte 引擎**整套**编译（第 7.4 节）。失败：不写入外部目录、不切换，返回"模板编译失败，未保存，前台仍使用上一版：<模板名> 第 <行> 行"与 jte 的错误信息（去掉工作目录的绝对路径）；编辑页把原因显示在内容框上方、内容不丢，并换上响应里的新表单令牌（`template-info.js`）。
5. 成功：原文件存一份到 `application/data/backup/template/<相对路径>`（"恢复上一版"用），原子写入外部目录（版本再核对一次），再整体替换前台用的引擎引用。新请求用新版本，进行中的请求在取到的旧引擎上完成；再上一代的编译目录随后删除。
6. "恢复上一版"（编辑页按钮，`op=restore`）：把备份内容走同样的流程保存，换下来的内容成为新的上一版。

保存、恢复、删除与启动编译在 `SiteTemplateService` 里串行执行。

#### 7.3 安全过滤（`JteSecurityFilter`，命中就拒绝）

jte 模板会被编译成 Java 代码在服务器上运行，风险高于旧版（旧版至少挡住了 PHP 代码）。规则：

| 规则 | 说明 |
|---|---|
| `@import` | 只能是内置模板本身用到的那些（视图模型类、`gg.jte.Content`、`java.util.List`、`java.util.Optional`）；其余一律拒绝 |
| `<script>`、`<iframe>` | 只能出现在内置版本的同名模板本身含有它们的模板里（B05/B09/B10 已裁定的例外：播放页、播放器内页、会员页） |
| `!{…}` 代码块、`$unsafe{…}`、`@raw` | 拒绝 |
| Unicode 转义 `\u` | 拒绝（javac 在分词前解码，可拼出被禁止的写法，如 `new`） |
| `new`（后面不是 `-`）、`.class`、`Class`/`ClassLoader`/`forName`/`getClass` | 拒绝 |
| 反射：`reflect`、`invoke`、`getMethod(s)`、`getDeclared*`、`getField(s)`、`setAccessible`、`MethodHandle(s)`、`VarHandle`、`Unsafe`、`Proxy`、`Lookup` | 拒绝 |
| 进程与系统：`Runtime`、`ProcessBuilder`、`ProcessHandle`、`Process`、`System`、`exec`、`exit`、`halt`、`loadLibrary`、`Thread(Local)`、`ScriptEngine*`、`SecurityManager`、`ModuleLayer` | 拒绝 |
| 文件与网络：`File(s)`、`Path(s)`、`FileSystem(s)`、`RandomAccessFile`、`*Stream`、`*Reader`、`*Writer`、`URL`、`URI`、`URLConnection`、`HttpClient`、`Socket`、`ServerSocket`、`DatagramSocket`、`InetAddress`、`*Channel(s)` | 拒绝 |
| 全限定类名：`java.`、`javax.`、`jdk.`、`sun.`、`com.`、`org.`、`net.`、`gg.`、`io.`、`kotlin.`、`groovy.`、`scala.` 后跟小写字母 | 拒绝（防止绕过 `@import`） |
| Spring 容器：`ApplicationContext`、`BeanFactory`、`getBean`、`SpringApplication`、`Environment` | 拒绝 |
| `static`、`synchronized`、`native`、`transient`、`volatile`、`instanceof` | 拒绝 |

- 规则按整份文本匹配（含静态文字，与旧版过滤器一样"出现就拒绝"），所以页面文字里出现这些英文单词也会被拒绝；全部 71 个内置模板都能通过（`SiteTemplateServiceTest.builtinTemplatesPassTheFilterAndAreBundled`）。
- 编译时另用 `OnlineHtmlPolicy`：构建用的 `OwaspHtmlPolicy` 加上与 `SiteTemplatePolicyTest` 相同的站点规则（禁止内联事件属性、单引号属性、`style`/`svg`/`object` 等标签、`style`/`srcdoc`/`http-equiv`/`formaction` 属性；`<script>` 只能带 `nonce`、`src`）。
- **这只是纵深防御，不是沙箱**：模板仍能调用视图模型上的公开方法；拿到后台 `template/info` 权限的人等于能在服务器上运行代码（路线图第 6 节、D4）。部署说明：只给必要的管理员该权限；不需要在线编辑时保持 `maccms.site-template.enabled=false`。

#### 7.4 运行时编译（`JteOnlineCompiler`、`JteClassPath`）

- 每次编译：`TemplateEngine.create(DirectoryCodeResolver(副本), 新类目录, Html, 应用类加载器, "com.maccms.online.jte")` + `precompileAll()`；成功后前台用 `TemplateEngine.createPrecompiled(该类目录, …)`——**只加载这次的编译结果**，之后有人改了磁盘上的文件也不会被自动重新编译（必须经后台保存或重启，都会过过滤与编译）。
- 生成类的包名与内置模板（`com.maccms.backend.site.web.jte`）不同，否则父类加载器会先找到 jar 里预编译的同名类。
- javac 类路径：开发与测试时取锚点类（`SiteTemplateSource`、jte 运行时）所在的目录或 jar；Spring Boot 可执行 jar 时把 `BOOT-INF/classes` 与 `BOOT-INF/lib/*.jar` 解到工作目录（只在开启在线编辑时做一次；现场演示在可执行 jar 上进行，第 7.6 节）。
- 用时：本机整套 71 个模板一次编译约 2 秒（第 7.6 节记录）。

#### 7.5 删除、广告位、标签向导

- 删除 jte：先确认没有它整套仍能编译，否则拒绝并给出原因（多半是被其他模板引用，如 `site/components/pagination.jte`）；删除前原文件同样存进 `application/data/backup/template/`。其他文件照旧直接删除；路径不对的项照旧跳过，最后一律"删除成功"。
- 广告位（`template/ads`）：`template/<template_dir>/<ads_dir>/*.js`，目录不存在时创建；调用代码一栏照旧是 `<script src="{$maccms.path_ads}文件名"></script>`；广告文件是静态文件，不参与编译。
- 标签向导：jte 模板没有旧版那样按条件查库的标签（`{maccms:vod …}`），数据都来自页面的视图模型。新版按内置的页面模板列出 `@param`，并按视图模型（record）的字段生成可直接粘贴的输出、`@for` 循环与 `@if` 判断写法（`TemplateWizard`，D-B15-15）。

#### 7.6 现场演示记录（路线图第 5.6 节验收演示 ①–⑥）

2026-10-26 在本机用可执行 jar（`java -jar backend/target/maccms-backend-0.1.0-SNAPSHOT.jar`，Temurin JDK 21.0.11）、一次性库 `maccms_it_20261026174419_3aab1661`（导入 install.sql + 1 个管理员，演示后删除并确认不存在）与临时站点副本（`application/` 复制自仓库，只关闭后台登录验证码）演示，全部经真实 HTTP。驱动脚本在仓库外，完整记录随交付评论附上。

| 演示 | 结果 |
|---|---|
| 首次启用 | 启动 23.4 秒；从内置版本导出 71 个 jte 到 `template/default/html/` 并编译 |
| ① 编辑首页模板中的一段文字并保存，不重启刷新首页 | 保存（含整套编译）2.0 秒，"保存成功!"；`GET /` 出现 `<p class="site-home__note">B15 在线编辑演示</p>` |
| ② 保存有语法错误的模板 | 方法名写错："模板编译失败，未保存，前台仍使用上一版：site/pages/home.jte 第 8 行"；少一个 `@endif`：同样提示"第 41 行"；响应带新令牌；首页仍是 ① 的版本，文件未写入 |
| ③ 恢复上一版 | "保存成功!"；首页回到修改前，文件与导出时逐字节相同 |
| ④ 禁止内容 | `@import java.io.File`、`!{var x = 1;}`、`${System.getenv()}`、`$unsafe{…}`、`${new Object()}` 均"模板含有不允许的内容，未保存：…"，文件未改 |
| ⑥ 两个管理员同时保存同一文件 | 甲"保存成功!"；乙"文件已被他人修改，请刷新页面后重新编辑"；首页为甲的版本 |
| ⑤ 手工改坏外部目录的模板后重启 | 重启 22.3 秒；ERROR 日志"site templates in template/default/html failed to compile; falling back to built-in templates: site/pages/home.jte 第 8 行…"；首页正常（内置模板）；后台欢迎页与模板管理页提示"外部模板目录 template/default/html/ 编译失败，前台已回退到内置模板（site/pages/home.jte 第 8 行…）"；在后台把模板改好保存后，前台不重启切回外部模板 |

### 8. 页面：复用旧后台结构

- 旧 `application/admin/view/{system,vodplayer,vodserver,voddowner,domain,timming,urlsend,template,database,addon}/*.html` 逐个转写为 `backend/src/main/jte-admin/admin/pages/…/*.jte`，统一视图 `ConfigPage` + `LegacyView`（`at`/`each`/`eq`/`macDay` 等对应旧模板写法）；旧内联脚本逐行移到 `src/main/resources/static/admin-assets/*.js`，数据经 `data-*` 属性传入；旧 `static/js/jquery.cookie.js`、`jquery.clipboard.js` 构建时原样打包。
- 为满足 jte 与 `AdminTemplatePolicyTest` 的最小改动：去掉多出的 `</a>`、`</tbody>`、`</option>`，补上缺的 `</div>`；`checked`/`selected`/`readonly` 改为按条件输出的属性；旧模板里没有调用方的内联函数不再输出；各配置页"去服务商注册/申请"的外链照旧（`AdminTemplatePolicyTest` 登记的导航链接）；layui 不会触发的原生 `onChange` 去掉（D-B15-17）。
- 新增：模板编辑页的 `fversion`、`op` 隐藏字段，"恢复上一版"按钮，错误显示区；模板管理页表尾一行"前台当前使用的模板"；欢迎页的系统提示一行。

### 9. 数据库工具

#### 9.1 表状态、查看列、备份列表

`SHOW TABLE STATUS`、`SHOW COLUMNS FROM`、备份目录列表（文件名按字节序，旧版随文件系统顺序）。表名必须出现在 `SHOW TABLE STATUS` 的结果里且只含字母数字下划线（旧版只查前者）。

#### 9.2 SQL 入口的登记

只读 Mapper 里的 `${table}`、`${columns}` 是 `SqlEntryGuardTest` 登记的例外：表名同上校验，列名来自 `SHOW COLUMNS` 且逐个校验后拼成 ``CAST(`列` AS CHAR)``。`ReadOnlyGuardrailsTest` 对这个 Mapper 单独断言只有 5 条 SHOW/SELECT。

#### 9.3 备份导出

格式与旧 `common/util/Database.php` 相同（头部注释、`SET FOREIGN_KEY_CHECKS = 0;`、每张表的 `DROP TABLE IF EXISTS` + `SHOW CREATE TABLE` 的文本 + 每行一条 `INSERT INTO … VALUES (…);`（按 1000 行一批读取），`addslashes` 转义，按 `db.part_size` 分卷，`db.compress` 时 gzip 并按 `db.compress_level`）。名字含 `_admin` 的表移到最后（旧版只保留最后一个这样的表，照旧）。**备份文件里的 `DROP/CREATE TABLE` 只是写进文件的文本，新系统从不执行**：这是 `SchemaGuardTest` 静态 DDL 扫描唯一登记的例外；`AdminSystemGoldenMySqlIT` 包装数据源记录全部语句，断言导出只发出 SHOW 与 SELECT。备份目录（`db.backup_path`）必须在 `application/data/backup/` 下（D-B15-2）；中途出错也删除锁文件（旧版留下锁文件，之后的备份都被挡住）。

#### 9.4 批量替换

一条单表 `UPDATE t SET c = REPLACE(c, ?, ?) WHERE LOCATE(?, c) > 0`（`mapper-write/admin/DatabaseReplaceWriteMapper.xml`）：表与列按白名单逐个写成 `<when>` 分支，不拼接；登记 `WriteRegistry`（admin / 25 张 `mac_*` 表 / UPDATE / B15；`mac_admin` 沿用 B12 的登记）。旧表单里的 `where` 旧版已不使用，新版同样忽略（黄金 `rep` 带 `where=1=1;DROP TABLE mac_vod` 的请求只替换、不执行别的）。`LOCATE` 条件只让没有查找串的行不被写（结果相同，D-B15-8）。

#### 9.5 入口拒绝的四项（D-B15-1）

还原备份 `import`、优化 `optimize`、修复 `repair`：返回固定提示"新系统不在后台执行还原备份、执行 SQL、优化表、修复表：这些操作会改表结构或做表维护，请由部署方用 mysqldump、mysql 命令行等库外手段完成"；执行 SQL `sql`：页面只显示这段提示，不提供表单，POST 同样拒绝。期间不向数据库发出任何语句（`AdminSystemGoldenMySqlIT` 断言只有登录校验读管理员一行）。菜单与权限码照旧保留。B21 对齐总表记为"按硬性要求 2 排除"。

### 10. 测试与验证

| 内容 | 测试 / 证据 |
|---|---|
| PHP 黄金（PHP 8.0.30 与 7.3.33） | `backend/src/test/php/admin_system_golden.php` + `admin_system_child.php`：一次性库、php-cgi 跑完整的 `_admin.php` + ThinkPHP + 控制器，站点是临时副本；每个场景开始前还原会被写的区域。表单步骤先 GET 页面、按 jQuery `serialize()` 取字段再 POST。记录状态码、头、正文与"这一步之后变化的被跟踪文件"（`application/extra/*.php`、`route.php`、`static/js` 的三个文件、`static/player`、`static/addons`、`template/`、`addons/`、`application/data/backup/`）。两版各 **36 个场景、230 步** → `backend/src/test/resources/admin/system/php{80,73}-golden.json` |
| 黄金复放（不连库） | `AdminSystemGoldenTest`：按黄金的初始文件重建临时站点，同样的请求发给新版，逐步比较状态码、JSON/文本正文逐字节、页面的表单字段（`AdminForm`）与模板列表的表格行、变化的文件逐字节。两版共 **418 步**，除 `JAVA`/`USE_73` 登记的有意差异外 0 处不同 |
| 黄金复放（真实 MySQL） | `AdminSystemGoldenMySqlIT.databaseScenariosMatchPhpGolden`：`database`、`database-plain` 两个场景两版共 **42 步**（表列表、备份导出 gz 与明文逐字节——头部主机/端口/库名与 `mac_admin` 的登录字段归一、备份列表、查看列、批量替换后 `mac_vod` 的行、删除备份） |
| 数据库工具的 SQL 与结构 | 同一测试类：导出只发出 SHOW/SELECT、前后 26 张表数据不变；四个拒绝的入口不发语句；批量替换只改含查找串的行（区分大小写）；回滚演练：快照 → 替换 → `DmlRestore` 只用 DML 恢复 → diff 为空；结束时表结构指纹与导入 install.sql 时相同，删库并确认不存在 |
| 模板在线编辑 | `SiteTemplateServiceTest`（真实 javac：内置模板全部通过过滤并在 `OnlineHtmlPolicy` 下整套编译、首次导出、保存即生效并渲染、语法错误的模板名与行号、禁止内容 11 种、冲突、恢复上一版、删除被引用的模板、目录外与文件名、静态文件的旧过滤、启动回退）；`AdminTemplateMvcTest`（经后台 `template/info` 的保存、编译失败带新令牌、冲突、恢复、删除拒绝、向导、欢迎页）；现场演示第 7.6 节 |
| 原子写与并发 | `AtomicFilesTest`、`LegacyConfigWriteTest`（第 3.3 节） |
| 回滚演练（文件） | `AdminTemplateMvcTest.fileRollbackDrill`：整体复制会被写的区域 → 后台写快捷菜单、播放器状态、jte 模板（含上一版备份）→ 用复制件覆盖回去、删除新文件 → 被跟踪的文件逐字节相同，重新执行启动编译后前台用回滚后的模板 |
| 旧语义 | `VarExportWriterGoldenTest`、`PhpHtmlEntitiesGoldenTest`（第 3.1 节黄金） |
| 护栏 | `WriteGuardTest`、`WriteRegistryTest`、`ReadOnlyGuardrailsTest`、`SchemaGuardTest`、`SqlEntryGuardTest`、`ModuleBoundaryTest`、`OutboundNetworkGuardTest`、`AdminTemplatePolicyTest`、`SiteTemplatePolicyTest`、`SiteGuardrailsTest`（第 2 节） |
| 后台总体 | `AdminMvcTest` 改用仓库 `application/` 的临时副本（B15 起后台动作会写回配置文件；此前直接指向仓库，扫遍各动作时改坏了仓库里的 `extra/*.php` 与 `playerconfig.js`，已恢复并修正）；B15 的动作逐个确认不再是批次提示 |
| 测试总数 | 见交付评论（`./mvnw verify` 与 `./mvnw verify -Pmysql-it` 在最终树上的结果） |

### 11. 本地插件（D5）

- 列表（`index`、`downloaded`）：`addons/<名称>/info.ini` 与插件目录；`downloaded` 支持 JSON 与 JSONP（回调名只接受 JavaScript 标识符与点，不合法时用 ThinkPHP 默认名 `jsonpReturn`，D-B15-9）。
- 配置（`config`）：按 `config.php` 的 `type` 输出表单，保存写 `config.php`（`set_addon_fullconfig` 的外壳，第 3.1 节）；旧模板把 `{$item.extend}` 原样拼进标签，新版不输出（D-B15-12）。
- 启用/禁用（`state`）、卸载（`uninstall`）：文件效果与旧 `Service::enable/disable/uninstall` 相同——`info.ini` 的 `state`、资源目录 `static/addons/<名称>/`、全局文件、`static/js/addons.js` 与 `extra/addons.php` 的钩子表（`Service::refresh`；钩子按插件主类源码里的公开方法声明得出，不执行 PHP）。插件里的 PHP 代码在新系统里不能运行：只有登记了 Java 实现的插件可以启用（仓库里的 `adminloginbg`，钩子 `admin_login_init` 的等价实现给登录页换背景），其他插件启用时提示"该插件没有新系统实现，无法启用"、不改任何文件（D-B15-11）；禁用、配置、卸载照旧执行文件操作（插件自己的 `enable/disable/uninstall` 方法不执行）。
- `adminloginbg` 的外部背景图受浏览器侧外部地址开关 W2 约束，放行时把该主机加进登录页的 `img-src`（D-B15-13）。
- 本地上传安装 `local` 照旧返回 `closed`；`info` 照旧为空；`install`、`upgrade` 在 B29。

### 12. 配置写回后的生效范围（热加载：本批未完成）

- **已生效**：后台本身（各配置页、播放器、站群、定时任务、插件、模板管理）每次请求按文件的修改时间与大小重读（写回时立即清缓存），保存后再打开即是新值；前台模板（第 7 节）保存后立即生效；`application/extra/addons.php` 驱动的登录页背景按请求读取。
- **未生效（需要重启）**：前台与其他模块在**启动时**据旧配置构建的设置——站点设置 `SiteSettings`、URL 规则（`LegacyUrlBuilder`/`LegacyUrlParser`，即 `route.php` 与 `configurl` 的效果）、图片地址、手机版判断（`SiteVariants`）、播放器设置与前台输出的 `/static/js/playerconfig.js`（`PlayerSettings`）、`SiteFeatures`、`SiteMiscSettings`、验证码、搜索间隔，以及 content/member/comment 模块启动时读取的配置。旧版保存后下一个请求即生效。
- 原因：这些设置分布在约 25 个装配点、上百个使用者中，多为 record 或 final 类（无法用代理在原位替换）；在本批里逐个改成可重建的持有者，涉及 B02/B05/B09～B11 已交付的模块，风险与范围都超出本批。路线图 B15 行写的"写回后 site 设置随之生效（本批补上热加载）"**没有完成**，交付评论里列为待决事项并给出方案。
- 在此之前的做法：后台改了上述配置后重启新系统（部署说明）；`LegacyConfigService.generation()` 已提供写回计数，供后续实现判断何时重建。

### 13. 与旧版的差异

| 编号 | 旧版 | 新版 | 原因 |
|---|---|---|---|
| D-B15-1 | 后台可还原备份（逐条执行备份文件，含 `DROP/CREATE TABLE`）、执行任意 SQL（含 DDL）、`OPTIMIZE TABLE`、`REPAIR TABLE` | 四项入口拒绝，固定提示；`sql` 页面不提供表单 | 硬性要求 2（零结构变更），路线图 B15 行 |
| D-B15-2 | 备份目录 `db.backup_path` 可指向任意目录 | 必须在 `application/data/backup/` 下，否则"备份目录必须在 application/data/backup/ 下" | 写入范围限定 |
| D-B15-3 | `mac_arr2file` 不检查写入结果，写不进去也提示成功 | 写入失败（含原子改名失败）提示"保存失败"，原文件不变 | 正确性 |
| D-B15-4 | `test_cache` 连接 memcache/redis 测试 | 不连接（新系统没有外部缓存），固定提示"新系统不使用外部缓存服务，未进行连接测试" | 新系统不用外部缓存；不发出站连接 |
| D-B15-5 | 两个管理员打开同一配置页，后保存的一方无声覆盖前者 | 后保存的一方"配置已被他人修改，请刷新页面后重新保存"；模板文件同理（"文件已被他人修改…"） | 路线图要求（修改时间/哈希检查） |
| D-B15-6 | 搜索规则一个都不勾：PHP 8 `join('|', null)` TypeError 整页 500，PHP 7.3 写成 NULL | 按 PHP 7.3 | 版本差异取一种 |
| D-B15-7 | 值含 NUL 或非法 UTF-8 时照写，之后新旧系统都可能读不回 | "参数错误"，不写 | 正确性 |
| D-B15-8 | 批量替换 `UPDATE … SET c = REPLACE(c, …)` 不带条件 | 带 `WHERE LOCATE(查找串, c) > 0`；替换结果相同 | 只写有变化的行 |
| D-B15-9 | `addon/downloaded` 的 JSONP 回调名原样拼进响应 | 只接受标识符与点，否则 `jsonpReturn` | 加固（反射型脚本注入） |
| D-B15-10 | 备份中途出错留下锁文件 `backup.lock`，之后的备份都提示"检测到有一个备份任务正在执行，请稍后再试！！" | 出错也删除锁文件 | 缺陷 |
| D-B15-11 | 启用插件执行插件的 PHP 安装代码 | 只有登记了 Java 实现的插件能启用，其他插件提示"该插件没有新系统实现，无法启用"、不改文件 | 新系统不执行 PHP |
| D-B15-12 | 插件配置页把 `extend` 原样拼进标签（任意属性） | 不输出 `extend` | 加固 |
| D-B15-13 | 登录背景图直接写进内联样式 | 经脚本设置；外部主机的图片受 W2 开关约束，并加进该页 `img-src` | 内容安全策略 |
| D-B15-14 | 可编辑扩展名 `htm/html/js/xml`，提示"安全提示，后缀名只允许htm,html,js,xml" | `jte/html/js/xml`（`htm` 不再允许），提示"安全提示，后缀名只允许jte,html,js,xml" | 模板语法换成 jte（路线图第 5.6 节） |
| D-B15-15 | 标签向导生成 MacCMS 标签 | 按页面模板列出参数与 jte 写法 | jte 没有查库标签 |
| D-B15-16 | 模板（MacCMS/ThinkPHP 标签）保存即生效，出错时前台页面直接报错；删除不检查引用；安全过滤针对 PHP；改 `template_dir` 立即换模板 | jte 模板先过滤、整体编译，通过才生效，失败不写入并提示模板名与行号；可恢复上一版；删除被引用的 jte 拒绝；过滤规则针对 jte（第 7.3 节）；在线编辑须部署参数开启；`template_dir` 的切换在下一次保存或重启时生效；旧版模板文件不能直接放进来使用（与 D9 相关） | 路线图第 5.6 节 |
| D-B15-17 | 配置页里的原生 `onChange`、不成对的标签 | 去掉 layui 不会触发的 `onChange`；标签补齐成对；`configsms` 页里未使用的 `test_email` 脚本不再输出 | jte 与页面策略的最小改动 |
| D-B15-18 | 配置写回后前台下一个请求即生效 | 前台启动时构建的设置在重启后生效（第 12 节）；前台模板与后台本身即时生效 | 本批未完成热加载 |
| — | 扩展类列表（支付、短信、上传、推送等）按 `GLOB_NOSORT` 的顺序 | 按文件名字节序 | 确定性 |
| — | 模板保存失败（静态文件）后需刷新页面再保存（令牌已用掉） | 相同；jte 保存失败时响应带新令牌，可直接再保存 | 编辑体验（只对 jte） |
| — | 定时任务保存把表单令牌写进 `timming.php`；站群导入成功时提示 `import_err`；服务器组 `field` 不看 `ids` | 相同 | 行为兼容 |

### 14. 回滚

- **代码**：本批以一个合并提交进入 main；出问题时 `git revert -m 1 <合并提交>`，回到 `2d2b7e8` 的行为。后台默认关闭（`maccms.admin.enabled=false`），在线编辑默认关闭（`maccms.site-template.enabled=false`）；不涉及表结构、数据迁移。
- **文件**：后台写入的是旧站文件（`application/extra/*.php`、`route.php`、`static/js/{tj,playerconfig,addons}.js`、`static/addons/`、`template/`、`addons/`、`application/data/backup/`）。切换与开启后台写入前先整体复制这些区域；回滚时用复制件覆盖回去、删除新文件，再重启新系统（文件回滚演练见第 10 节）。写出的文件与 PHP 逐字节相同，切回 PHP 后照常读取。模板的上一版另存于 `application/data/backup/template/`，可单个恢复。
- **数据**：只有批量替换写库（对现有列的单表 UPDATE），回滚演练证明可用 DML 恢复；备份导出只读。
- **在线编辑**：关掉 `maccms.site-template.enabled` 并重启即回到内置模板，外部目录保留不动。

### 15. 风险与遗留

| 项 | 说明 |
|---|---|
| **待决：热加载** | 第 12 节。建议另开任务：按模块把启动时构建的设置改为"持有者 + 按 `LegacyConfigService.generation()` 与文件版本重建"，或在新系统里提供受控的整体重启；在此之前部署说明要求改配置后重启 |
| 模板在线编辑的风险 | 第 7.3 节：过滤是纵深防御不是沙箱，后台 `template/info` 权限等于服务器代码执行权限（与旧版可编辑模板同级，路线图第 6 节） |
| 运行时编译需要 JDK | 只有 JRE 时在线编辑拒绝保存、前台用内置模板；可执行 jar 首次开启时解出类路径（约 37 MB jar，一次） |
| 编译目录 | 每次保存一个 `gen-<n>/`，保留当前与上一代、删除更早的；Windows 上类加载器还开着时删不掉的旧目录留在工作目录里，不影响功能；默认工作目录在系统临时目录 |
| 外部目录里的非 jte 文件 | 不参与编译；旧版模板（`*.html`）可以和 jte 并存，但新系统不使用它们 |
| 前台的 `playerconfig.js` | 前台输出的 `/static/js/playerconfig.js` 由站点按启动时的播放器设置生成（B05，W1），后台改了播放器以后要重启才变（第 12 节）；后台照旧维护文件 `static/js/playerconfig.js`（切回 PHP 时使用） |
| 数据库工具 | 批量替换对任一 `mac_*` 表的任一列生效（与旧版相同的能力）；建议只给必要的管理员 `database/rep` 权限 |
| 本机遗留 | 早先批次留下的一次性库 `maccms_it_20261025065229_a6084353` 不是本任务创建的，未删除 |
