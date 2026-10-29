## B22：完整安装向导（MACC-45）

依据：路线图 `09-roadmap.md` 第 1.2 节（零结构变更）、第 3.2 节 D8、第 5.2 节 B22 行与第 5.3 节写入方矩阵（"新系统的部署配置文件与安装锁、`mac_admin` 第一个管理员、`initdata.sql` 的 `mac_type` 数据"归 B22）；B12 的管理员写入语义（`21-b12-admin-framework.md`）；B15 的配置文件写回（`24-b15-admin-system.md` 第 3 节）；B07 的写入基础（`12-write-infrastructure.md`）。

### 1. 范围

| 旧版（`install.php` → `application/install/controller/Index.php`） | 新版 |
|---|---|
| 首页：语言包选择、用户协议（`index` 默认分支，:62-75） | 同，`install/pages/index.jte` |
| 第二步：环境与目录权限、函数扩展检测（`checkNnv/checkDir/checkFunc`，:278-369） | 同样三张表，内容换成新系统的依赖（3.2 节） |
| 第三步：数据库配置页（:97-103） | 同，`install/pages/step3.jte` |
| 第四步：校验字段、连接测试、写 `database.php`、`CREATE DATABASE`（:109-171） | 校验与提示照旧；**不建库**，改为只读核对目标库，通过后写新系统的部署配置文件（第 5 节） |
| 第五步：写 `extra/maccms.php`、执行 `install.sql`、可选 `initdata.sql`、建管理员、写安装锁（:177-272） | **不执行 install.sql**（再次只读核对）；其余照旧（第 6 节） |
| 安装锁：存在时 `install.php` 只输出提示（`install.php:30-33`） | 同一个文件 `application/data/install/install.lock`，同一句提示（第 4 节） |

不改表结构，不改旧 PHP；只读核对只发 SELECT，写库只有登记的两条 INSERT（`install / mac_type、mac_admin / INSERT`）。后台界面复用要求（2026-10-17）：页面全部由旧 `application/install/view/index/*.html` 原样转写，样式仍是旧 `static/layui`、`admin_style.css`、`install.css`，只做第 7 节列出的最小适配。

### 2. 安装模式

#### 2.1 启动方式

旧版 `install.php` 是只绑定 install 模块的单独入口。新版对应地以环境变量 `MACCMS_INSTALL_MODE=true` 启动**只有安装向导**的应用（`install.InstallApplication`，`MaccmsBackendApplication.main` 按这个变量分派）：

- 不创建任何数据库连接池（排除 DataSource 与 MyBatis 自动配置）；数据库连接由向导第三、四步填写，向导自己按需连接（第 5 节）。
- 只引入旧配置层（`legacyconfig`，站点配置写回与安装锁）和 `install.config.InstallConfiguration`；没有前台、后台、接口、定时任务路由，其他路径一律 404。
- 安装向导的两个装配类都**不带**组件注解，正常模式的组件扫描找不到它们（`InstallModeTest.normalModeNeverRegistersTheInstaller`）：正常模式下没有 `/install.php`。
- 安装完成后，部署方去掉 `MACCMS_INSTALL_MODE` 重启为正常模式；正常模式经 `spring.config.import` 读向导写下的部署配置文件（5.3 节）。

#### 2.2 部署参数

| 变量 | 说明 |
|---|---|
| `MACCMS_INSTALL_MODE` | `true` 时以安装模式启动；其他值、留空为正常模式 |
| `MACCMS_LEGACY_CONFIG_DIR` | 安装模式必填：旧站 `application/` 目录（写 `extra/maccms.php` 与安装锁、做目录检查）；不要指向线上目录 |
| `MACCMS_DEPLOY_CONFIG` | 安装模式必填，正常模式可选：部署配置文件的绝对路径，以 `.properties` 结尾，所在目录必须已存在，不能在旧站目录里（网站目录可能被 nginx 直接提供，文件里有数据库密码） |
| `MACCMS_INSTALL_ALLOW_ROOT` | `true` 时向导接受 root 账号（仅本机开发；默认拒绝，同 `MACCMS_DB_ALLOW_ROOT`） |
| `MACCMS_ADMIN_ENTRY` | 可选：安装完成后跳转的后台入口（同 B12 的写法，如 `/_admin.php`）；留空时同旧版跳 `/admin.php` |
| `MACCMS_SERVER_ADDRESS`/`PORT` | 同正常模式，默认只监听 `127.0.0.1:18280`；装完前不要对公网开放 |

### 3. 各步与旧版的对应

#### 3.1 入口、会话与语言

- 地址：`/install.php`、`/install.php/`、`/install.php/index`、`/install.php/index/index`（可带 `.html` 等旧版伪静态后缀）；参数 `step` 取表单值、没有时取查询串（旧 `Request::param()`），按 PHP 宽松比较匹配 2～5，其余为首页。
- 会话里保存旧版的 `lang`、`install_error`（Servlet 会话，cookie 名为 `JSESSIONID`，旧版是 `PHPSESSID`）。首页按参数 `lang` 选语言包（只有 `zh-cn`、`zh-tw`，其他值按 `zh-cn`）并记进会话、清除 `install_error`；其余各步按会话里的语言包输出。
- 参数按 PHP 的规则按字节解析（`common.web.PhpInput`），只取标量，数组按缺失处理。
- Ajax 判定同 ThinkPHP `Request::isAjax`（`X-Requested-With: XMLHttpRequest` 或参数 `_ajax` 为真）：Ajax 返回 `{"code","msg","data","url","wait"}`，否则返回跳转提示页（`thinkphp/tpl/dispatch_jump.tpl` 的结构）。

#### 3.2 第二步：运行环境检测

三张表的行与旧版一一对应，任何一项不通过即"环境检测未通过"，第三、四、五步拒绝（同旧版 `session('install_error')`）：

| 表 | 旧版 | 新版 |
|---|---|---|
| 环境 | 操作系统；PHP ≥ 5.5 | 操作系统；Java ≥ 21（`Runtime.version()`） |
| 目录/文件 | `database.php`、`route.php`、`extra`、`data/backup`、`data/update`、`runtime`、`upload` 可写 | `MACCMS_DEPLOY_CONFIG`（文件可写或所在目录可写）、`route.php`、`extra`、`data/backup`、`data/update`、`data/install`（安装锁）、`upload`；去掉新版不用的 `runtime`；文件不存在显示"不可写"、目录不存在显示"不存在"（同旧版） |
| 函数/扩展 | pdo、pdo_mysql、zip、fileinfo、curl、xml、file_get_contents、mb_strlen | MySQL JDBC 驱动、`java.util.zip`、`javax.xml`、`javax.imageio`、MD5；只检查是否存在，不发请求 |

旧语言包 `install/not_support` 的值本身就是"支持"（`zh-cn.php:2028`），不支持时照旧显示这个值，靠整行标红区分。

#### 3.3 第四步：校验与提示

校验规则、顺序与提示文字同旧版（`Index.php:117-136`，ThinkPHP Validate 的语义见 `install.application.InstallValidation`）：服务器地址必填 → 端口必填且 `is_numeric` → 库名必填 → 账号必填 → 前缀必填且符合 `^[a-z0-9]{1,20}[_]{1}$` → 覆盖必填且在 `0,1` → 提交的每个字段都是旧 `database.php` 的键（否则"参数×不存在"）。繁体语言包下框架没有译文，提示是英文原文加繁体标题（如"數據庫端口 must be numeric"），与旧版相同。

旧版没有、新版在这之后增加的检查（每项都给出固定提示，不建库、不写文件）：

1. 表单令牌（第 7 节）；
2. 前缀只能是 `mac_`（全部 SQL 按 `mac_` 表名编写；旧版允许改前缀，路线图要求本批核对，结论：新版不支持其他前缀）；
3. 服务器地址只能是主机名或 IPv4（`[A-Za-z0-9.-]`），库名只能是 `[A-Za-z0-9_]`（它们要放进 JDBC 地址）；端口只能是 1～65535 的十进制数（其他 `is_numeric` 写法按"连接失败"）；
4. root 账号需要 `MACCMS_INSTALL_ALLOW_ROOT=true`；
5. 连接：同旧版先不带库名连接并执行 `SELECT VERSION()`，失败即"数据库连接失败，请检查数据库配置！"；再确认库已存在（不存在时提示由部署方在库外建库并导入 install.sql）；
6. 只读核对目标库（5.2 节）；
7. 写部署配置文件（5.3 节），失败时"[MACCMS_DEPLOY_CONFIG]数据库配置写入失败，请重试!"。

通过后提示同旧版：选"不覆盖"为"该数据库已存在，可直接安装。如需覆盖，请选择覆盖数据库！"，选"覆盖"为"数据库连接成功"（旧版此时会建库；新版库总是已由部署方建好，"覆盖"不再有其他作用）。

### 4. 安装锁

- 位置与旧版相同：`<旧站>/application/data/install/install.lock`，内容为北京时间 `Y-m-d H:i:s`。只经 `legacyconfig.application.LegacySiteFiles` 读写（新增 `installLocked`、只新建不覆盖的 `writeInstallLock`、目录检查 `installAccess`）。
- 文件存在时，任何安装入口（含 POST 第五步）都只输出旧版那一句 `如需重新安装请删除【To re install, please remove】 >>> /application/data/install/install.lock`（`Content-Type: text/html;charset=UTF-8`），每个请求都重新判断。
- 第五步在进程内串行执行，写库前再确认一次没有锁；锁文件只新建，两个并发的安装最多一个成功写锁。

### 5. 目标库与部署配置

#### 5.1 不建库、不建表（零结构变更）

旧版的 `CREATE DATABASE`（:162）、执行 `install.sql`（:220-235，每张表 `DROP TABLE IF EXISTS` + `CREATE TABLE`）与"覆盖已有数据库"选项都不在应用内执行。部署方先在库外建库并原样导入仓库 `install.sql`，例如：

```bash
mysql -u <管理账号> -p -e "CREATE DATABASE <库名> DEFAULT CHARACTER SET utf8"
mysql -u <管理账号> -p --default-character-set=utf8 <库名> < application/install/sql/install.sql
```

然后在向导第三步填写这个库。B21 对齐总表记为"按硬性要求 2 排除"。

#### 5.2 只读核对

第四步通过连接测试后、第五步写库前，各核对一次（`install.infrastructure.InstallDatabaseProbe`，只读会话 `SET SESSION TRANSACTION READ ONLY`，全部是写死的 SELECT，库名只出现在连接地址里，查询用 `DATABASE()`）：

1. **表结构与 install.sql 完全一致**：从 information_schema 读表（类型、引擎、行格式、排序规则、选项、注释）、列、索引、触发器、存储过程、事件、视图，与 jar 内的基准 `install/schema-fingerprint.txt` 逐项比较（与测试侧 `SchemaFingerprint` 相同的列，只少了库的默认字符集一项：库由部署方建，只要求表与列一致）。不一致时停在这一步，提示共几处差异并列出前 5 项（"缺少/多出/不同 对象名"），不执行任何 DDL。
2. **数据与刚导入 install.sql 时相同**：install.sql 只在 `mac_group` 插入 3 个默认会员组（`install.sql:376-378`），其余 25 张表为空——这正是旧版第五步执行 install.sql 之后的状态。MyISAM 表用 information_schema 的 `TABLE_ROWS`（MyISAM 下是精确行数）；唯一的 InnoDB 表 `mac_vod_search`（`install.sql:901`）的 `TABLE_ROWS` 只是估计值，改为 `SELECT 1 FROM mac_vod_search LIMIT 1`（固定表名、最多读 1 行；02 规范"不读写旧缓存表"在 B22 的唯一例外）。不同时停在这一步，列出前 5 张表与行数：**新版不会清空或覆盖已有数据**（旧版会 DROP 后重建）。

基准文件由 `InstallWizardMySqlIT` 在新导入的一次性库上重新读取并要求完全相同；install.sql 变了、或换了会改变 information_schema 表示的 MySQL 版本（例如 MySQL 8.0.19 起整数列不再显示宽度），测试都会失败【MySQL 8 未验证，见第 12 节】。

#### 5.3 部署配置文件

旧版第四步写 `application/database.php`。新版写 `MACCMS_DEPLOY_CONFIG` 指定的 Java properties 文件，键就是环境变量名：

| 键 | 值 |
|---|---|
| `MACCMS_DB_URL`、`MACCMS_WRITE_DB_URL` | `jdbc:mysql://<地址>:<端口>/<库名>?characterEncoding=UTF-8` |
| `MACCMS_DB_USERNAME`/`PASSWORD`、`MACCMS_WRITE_DB_USERNAME`/`PASSWORD` | 向导里填的账号与密码（旧版只有一个 `database.php`，所以只读连接池与可写数据源用同一个账号） |
| `MACCMS_WRITE_ENABLED` | `true` |
| `MACCMS_DB_ALLOW_ROOT`、`MACCMS_WRITE_DB_ALLOW_ROOT` | 只在账号是 root 时写 `true`（向导本身已按 `MACCMS_INSTALL_ALLOW_ROOT` 放行） |

- 正常模式：`application.yml` 的 `spring.config.import: optional:file:${MACCMS_DEPLOY_CONFIG:}` 读入；未设置或文件不存在时不读；**同名环境变量优先**（部署方可以只用环境变量覆盖其中任何一项，例如换成只授予 SELECT 的只读账号）。`InstallModeTest.normalModeImportsTheDeployConfig` 验证三种情况，`InstallWizardMySqlIT` 用向导写下的文件真实启动正常模式（只读连接池与可写数据源都连上，`/actuator/health` 为 UP）。
- 写法：同目录临时文件 → 原子改名；POSIX 系统上权限 600；只写 ASCII（值按 properties 规则转义），开头两行英文注释，没有时间戳。只在第四步全部检查通过后才写（旧版在连接成功后、核对库之前就写了 `database.php`）。
- 第五步读回这份文件得到目标库；文件不存在或不完整时"请先点击测试数据库连接！"（旧版是 `database.php` 的 hostname/database/username 为空）。
- 不进 git：路径在仓库外由部署方指定；若放在 `backend/config/` 下，`*.properties` 已加入 `backend/.gitignore`。

### 6. 第五步：执行安装

校验与提示同旧版（`Index.php:184-199`）：没有部署配置 → "请先点击测试数据库连接！"；账号或密码为空 → "请填写管理账号和密码！"；账号 `require|alphaNum`、密码 `require|length:6,20`（按码点数）。之后按顺序：

1. 表单令牌；`install_dir`（第三步隐藏字段，旧版为 `SCRIPT_NAME` 的目录）为空按 `/`，**不是 `/` 时拒绝**（新版前台只支持根目录安装，见 `11-guards-and-legacy-config.md` 第 4.4 节）；root 需要放行。
2. 已有安装锁则拒绝；再次只读核对目标库（5.2 节）。
3. **写站点配置**（旧 :203-218）：经 B15 的 `LegacyConfigService.update` 在当前 `extra/maccms.php` 上改 5 项后整体写回（`VarExportWriter.macArr2File`，与 `mac_arr2file` 逐字节相同）：`api.vod.status=0`、`api.art.status=0`、`interface.status=0`（整数）、`interface.pass`=16 位随机串（旧 `mac_get_rndstr` 的字符表 `234567890A…Z`，新版用 `SecureRandom`）、`site.install_dir`。旧版第 204-205 行把变量名写成 `$cofnig_new`，`app.cache_flag` 与 `app.lang` 实际没有写进去，新版照旧不写（D-B22-6）。失败时"配置文件写入失败，请重试!"。
4. **写库**（`install.infrastructure.InstallWrites`，经 B07 可写数据源 `WriteDatabase.openInstallTarget` 对这个目标库打开、连接池大小 1、正式登记表）：
   - `initdata` 宽松等于 `1` 时导入初始分类：仓库 `initdata.sql` 的 18 条 `INSERT INTO mac_type VALUES (…)` 按 MySQL 字面量规则解码后存成 jar 内的数据文件 `install/initdata-mac_type.json`（jar 里不带 SQL 脚本，`InitDataTest` 锁定与 initdata.sql 逐值一致），逐行经 `InstallWriteMapper.insertType` 绑定写入（写出列名，值按表定义顺序）；
   - 第一个管理员：`INSERT INTO mac_admin (admin_name, admin_pwd, admin_status, admin_auth)`，`md5(密码原始字节)`、状态 1、权限空串，其余列取默认值，与 B12 后台新增管理员的写入相同（旧 `Admin::saveData` 新增分支）。
   任何一行失败即停止并提示（初始数据"导入初始化数据SQL失败…"、管理员"管理员账号创建失败"、连接失败"初始失败！"），**不写安装锁**。
5. 写安装锁；成功时"系统安装成功，欢迎您使用苹果CMS建站"，跳转地址为 `MACCMS_ADMIN_ENTRY`（未设置时同旧版 `/admin.php`）。

旧表 MyISAM 没有事务：第 4 步中途失败时已写入的行留在库里，再次执行会因 5.2 节第 2 项停下，需要部署方在库外重新导入 install.sql（旧版重跑会自动 DROP 重建）。见第 12 节。

### 7. 页面与安全适配

页面：`backend/src/main/jte-install/install/`（`layout/page.jte` = 旧 `head.html` + `foot.html`，`pages/index|step2|step3|jump.jte`），生成到 `com.maccms.backend.install.web.jte`，与站点、后台模板分开（pom 的 `jte-install` 执行）。HTML 结构、class、文字逐处与旧版相同（`InstallWizardGoldenTest` 按 7 条规则规范化后逐字比较），只有下面这些最小适配：

| 适配 | 原因 |
|---|---|
| 内联脚本移到 `static/install-assets/{index,step3,jump}.js`，提示文字与地址经 `data-*` 传入 | 内容安全策略不允许内联脚本（同后台 21 文档第 8 节）；`jump.js` 与后台的同名脚本相同 |
| 去掉 `<head>` 里的 `<script>var ROOT_PATH…, ADMIN_PATH…</script>` 与 `<meta http-equiv="X-UA-Compatible">` | 安装页的脚本不用这两个变量；模板策略不允许 `http-equiv` |
| 两个表单都加隐藏字段 `__token__`（会话里的随机值），第四、五步在字段校验之后核对，不对时"非法访问" | 防跨站提交（旧版第二个表单有 `__token__` 但从不校验，第一个表单没有） |
| Ajax 回复的 `msg` 转义 `< > & " '`；跳转页按 `strip_tags` 的简化写法去掉标签 | 页面脚本用 layer.msg 把 `msg` 当 HTML 显示，旧版会把"参数×不存在"里的字段名原样显示 |
| 响应头：`Content-Security-Policy`（只允许本站脚本，保留 layui 需要的 `'unsafe-eval'` 与内联样式）、`no-store`、`nosniff`、`X-Frame-Options: DENY`、`noindex` | 同后台 |

`InstallTemplatePolicyTest` 锁定：没有内联脚本与事件属性，`<script>` 只带 `type/src/data-*`；不出现 object/embed/iframe/base/`http-equiv`；`<style>` 与 style 属性只是固定文字；原样 HTML 只能是 `InstallText.trusted(page.lang().get("语言包键"))`（用户协议里的 `<br />`）；模板与脚本不引用外部主机。

### 8. 代码位置

| 包 / 文件 | 内容 |
|---|---|
| `install.InstallApplication` | 安装模式的启动类（2.1 节）；`MaccmsBackendApplication.main` 按 `MACCMS_INSTALL_MODE` 分派 |
| `install.config.InstallConfiguration` | 装配（不带 `@Configuration`）；路由 `/install.php…` |
| `install.web.InstallEndpoint`、`InstallResponses`、`view.*` | 入口分派、会话、令牌、Ajax/跳转页响应 |
| `install.application.InstallWizard` | 第四、五步 |
| `install.application.InstallValidation` | ThinkPHP Validate 子集 |
| `install.application.InstallEnvironment` | 第二步 |
| `install.application.InstallSchema`、`InitData`、`InstallSettings` | 表结构基准、初始分类数据、部署参数 |
| `install.infrastructure.InstallConnections`、`InstallTarget` | 到目标库的只读会话与可写数据源 |
| `install.infrastructure.InstallDatabaseProbe` | 只读核对（5.2 节） |
| `install.infrastructure.InstallWrites`、`InstallWriteMapper` + `mapper-write/install/InstallWriteMapper.xml` | 两条 INSERT |
| `install.infrastructure.DeployConfigFile` | 部署配置文件 |
| `common.write.WriteDatabase.openInstallTarget`、`WriteDataSourceSettings.deploymentEntries` | 安装向导打开可写数据源的入口（`WriteGuardTest` 限定只有 `install.infrastructure` 能调用）；`MACCMS_WRITE_*` 键名仍只在 `common.write` |
| `common.write.WriteRegistry` | 登记 `install / mac_type / INSERT / B22`、`install / mac_admin / INSERT / B22` |
| `legacyconfig.application.LegacySiteFiles` | 安装锁与目录检查（第 4 节） |
| `resources/install/schema-fingerprint.txt`、`initdata-mac_type.json` | 表结构基准、初始分类 |
| `src/main/jte-install/`、`static/install-assets/`；pom 打包旧 `static/css/install.css` | 页面 |
| `application.yml` | `spring.config.import` 读部署配置文件 |

### 9. 测试与验证

黄金样本：`backend/src/test/php/install_golden.php`（+ `install_child.php`）用 php-cgi 跑**未修改**的旧 `install.php` 全流程，每个场景一份新复制的临时站点（`database.php` 为空配置 = 新下载的安装包）和一个新的空一次性库（旧向导在其中照旧执行 `CREATE DATABASE IF NOT EXISTS` 与 install.sql）；表单里的连接参数写成占位符，JSON 里没有任何账号密码。PHP 7.3 与 8.0 各一份（`src/test/resources/install/b22/php{73,80}-golden.json`），4 个场景 47 步：页面（首页、语言切换、繁体下的环境检测与配置页、返回首页、未知步骤、第四步的 GET、未测试就安装、错误的语言参数）、第四步 15 个请求（各项校验、连接失败、繁体下的英文提示、非 Ajax 跳转页）、两次完整安装（初始数据"创建"/"不创建"，"不覆盖"/"覆盖"，第五步的 5 种校验，Ajax 与非 Ajax，中文密码）、安装后的安装锁。两个版本的输出只在第二步表格里的 PHP 版本号与第三步页面里旧版的随机令牌上不同（都不在比较范围内），安装后的库与 `maccms.php`（除随机的 `interface.pass`）相同。

| 测试 | 内容 |
|---|---|
| `InstallWizardGoldenTest`（`verify`） | 不连库能复放的 2 个场景 × 2 个版本共 58 步，全部与旧版一致（端口换成本机没有监听的端口，"密码错误"同样得到"数据库连接失败"）；比较规则自检 |
| `InstallWizardMySqlIT`（`-Pmysql-it`） | ① 基准文件与新导入 install.sql 的指纹完全相同，且与测试侧 `SchemaFingerprint` 一致；② 4 个场景 × 2 个版本共 94 步与旧版一致；两次安装后 `mac_type`、`mac_admin` 全部行逐列与旧版相同（初始数据 = 旧版 PDO 执行 initdata.sql 的结果），`maccms.php` 除随机的 `interface.pass` 外逐字节相同，安装锁格式相同，部署配置文件内容正确，并用它真实启动正常模式；③ 新版分支：库不存在（不建库）、库里没有表（738 处差异，不建表）、多一列、表里有数据（不覆盖）、前缀 `abc_`、令牌不对、`install_dir=/sub/`、root 未放行，以及跳转到 `MACCMS_ADMIN_ENTRY`；④ 用 Connector/J `profileSQL` 记下驱动收到的每条语句：应用发出的只有 SELECT（版本、information_schema、`mac_vod_search LIMIT 1`）与 INSERT（`mac_type`、`mac_admin`），另有驱动自己的会话设置 `SET autocommit=1`、`set session transaction read only`；没有 SHOW（用不到），没有任何 DDL、UPDATE、DELETE；⑤ 每个一次性库结束时的表结构指纹与导入时相同，全部删除并确认不存在 |
| `InstallModeTest` | 安装模式不创建 DataSource/WriteDatabase、只有向导路由、静态资源可用；环境检测不通过时第三～五步拒绝；安装锁对所有入口生效；正常模式扫描不到安装向导；部署参数校验；正常模式读部署配置文件且环境变量优先 |
| `InstallTemplatePolicyTest` | 第 7 节的模板策略 |
| `InitDataTest`、`InstallValidationTest`、`DeployConfigFileTest` | 初始数据与 initdata.sql 逐值一致；PHP 语义细节（`is_numeric`、PCRE `$`、`mb_strlen`、`in_array` 宽松比较）；部署配置文件读写 |
| `WriteGuardTest` | 登记表新增两条；`openInstallTarget` 只能在 `install.infrastructure` 调用（合成源码自检） |

运行结果见 MACC-45 的交付评论。

### 10. 与旧版的差异

| 编号 | 旧版 | 新版 |
|---|---|---|
| D-B22-1 | `install.php` 与网站同一个入口目录，随时可访问（靠安装锁） | 单独的安装模式（`MACCMS_INSTALL_MODE=true`），正常模式没有 `/install.php` |
| D-B22-2 | 第四步建库（`CREATE DATABASE`），第五步执行 install.sql（DROP + CREATE 全部表），可选"覆盖" | 不建库、不建表、不覆盖：部署方在库外导入；向导只读核对结构与 install.sql 一致、数据与刚导入时相同，不一致即停（按硬性要求 2 排除） |
| D-B22-3 | 写 `application/database.php` | 写 `MACCMS_DEPLOY_CONFIG` 的 properties 文件，只读连接池与可写数据源用同一账号；只在全部检查通过后写 |
| D-B22-4 | 允许任意表前缀 | 只支持 `mac_` |
| D-B22-5 | 地址、库名、端口只要求非空/数字 | 地址 `[A-Za-z0-9.-]`、库名 `[A-Za-z0-9_]`、端口 1～65535；root 需放行 |
| D-B22-6 | `$cofnig_new` 拼写错误，`app.cache_flag`、`app.lang` 没写进 `maccms.php` | 照旧不写 |
| D-B22-7 | `install_dir` 原样写入 | 只接受 `/` |
| D-B22-8 | 创建管理员失败也报"安装成功"并写锁（`if (!$res['code']>1)` 恒为假） | 失败即提示，不写锁；初始数据、连接失败同样不写锁 |
| D-B22-9 | 两个表单没有有效的防跨站令牌 | 两个表单都带并校验 `__token__` |
| D-B22-10 | 环境检测 PHP 版本、`runtime` 等；`runtime` 不可写时 `install.php` 直接输出提示 | 检测 Java 与新系统用到的目录、能力（3.2 节）；不检查 `runtime` |
| D-B22-11 | 成功跳到 `/admin.php`（仓库里的后台入口已改名为 `_admin.php`，这个地址不存在） | 跳到 `MACCMS_ADMIN_ENTRY`，未设置时同旧版 |
| D-B22-12 | Ajax 的 `msg` 原样（含提交的字段名） | `msg` 转义 HTML 特殊字符 |
| D-B22-13 | 会话 cookie `PHPSESSID` | `JSESSIONID` |
| D-B22-14 | `step` 按 PHP 版本的宽松比较（PHP 7 下 `2abc` 等于 2） | 只有数字串按数值比较（同 PHP 8） |
| D-B22-15 | 语言包为 `application/lang/*.php` 的全部文件 | 只有打包的 `zh-cn`、`zh-tw`（仓库里也只有这两个） |
| D-B22-16 | ThinkPHP 路由下 `/install.php/任意/地址` 也会进入 | 只有 `/install.php` 与 `index/index` 的几种写法，其余 404 |
| D-B22-17 | 页面内联脚本、`X-UA-Compatible` | 第 7 节的适配 |
| D-B22-18 | 安装中途失败可直接重跑（install.sql 会 DROP 重建） | 已写入部分数据时重跑会被 5.2 节第 2 项拦下，需在库外重新导入 install.sql |

### 11. 待确认

- D-B22-1/3（安装模式与部署配置文件的形式）是本批按路线图"格式与位置在本批文档写明"自行确定的，请队长确认。
- 第五步中途失败后的处理（D-B22-18）：是否需要向导自动删除本次写入的行（需要再登记 `install / mac_type、mac_admin / DELETE`）。本批未做。

### 12. 风险

- **MySQL 8 未验证**：基准指纹在 MySQL 5.7.44 上生成。MySQL 8.0.19 起 `int(10)` 等显示宽度不再出现在 `COLUMN_TYPE`，8.0.30 起 `utf8` 显示为 `utf8mb3`，向导会判为"结构不一致"而拒绝安装。如需支持，要在 MySQL 8 上另做一份基准或做规范化，需先在一次性库上实测。
- 部署配置文件含明文密码（旧 `database.php` 同样）；只靠文件位置（不在网站目录）与 600 权限保护，Windows 上没有设置 ACL。
- 安装模式没有登录，任何能访问端口的人都能完成安装（旧版相同）；默认只监听回环地址，装完前不要对外开放。
- 只读连接池与可写数据源共用向导里的一个账号；生产应按 `backend/README.md` 换成分开授权的账号（环境变量覆盖文件里的值）。
- `TABLE_ROWS` 依赖 MyISAM 的精确行数；表结构核对已保证 25 张 MyISAM 表与 install.sql 一致后才使用。

### 13. 回滚

删除本批写入的部署配置文件（`MACCMS_DEPLOY_CONFIG`）与安装锁（`application/data/install/install.lock`），恢复 `extra/maccms.php` 的上一版（B15 写回不留备份，需部署方事先备份）；目标库是部署方新导入的库，直接删库重建即可。代码回滚到本批的起点提交（交付评论给出）。不涉及表结构，不需要迁移脚本。
