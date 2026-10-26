## maccms-backend

MacCMS 重构的 Java 后端（模块化单体）。本批（MACC-4）只包含 `content` 模块的只读接口与公共支持：

| 接口 | 说明 |
|---|---|
| `GET /api/v1/video-categories` | 视频分类树 |
| `GET /api/v1/videos` | 视频分页列表，可按分类筛选；契约 1.1.0（MACC-18）起可按关键字、地区、语言、年份、推荐等级、扩展分类查询；1.3.0（B04）起另有首字母、资源类别、版本、主演、导演、标签、片名、ID 列表、年份区间与随机排序（`sort=rnd`），地区、语言、扩展分类可按逗号传多个值 |
| `GET /api/v1/videos/{id}` | 视频公开详情元数据（不含播放/下载地址） |
| `GET /api/v1/videos/{id}/plots` | 契约 1.2.0（MACC-27）：视频分集剧情，可见性同视频详情 |
| `GET /api/v1/{article,actor,website}-categories` | 1.2.0：文章、演员、网址分类树（规则同视频分类） |
| `GET /api/v1/{articles,topics,actors,roles,websites}`、`…/{id}` | 1.2.0：文章、专题、演员、角色、网址的分页列表与公开详情 |
| `GET /api/v1/links` | 1.2.0：友情链接列表 |
| `GET /actuator/health` | 健康检查，只返回 `{"status":"UP"}` / `DOWN` |

正式契约：`docs/contracts/content-read.openapi.yaml`。设计与验收：`docs/architecture/01-batch1-content-read-plan.md`；
搜索/筛选（1.1.0）的规则与实施记录见 `docs/architecture/09-content-search-filter-plan.md`（第 4、6、12 节）。
关键字与扩展分类（`keyword`、`classes`）是包含匹配，默认关闭（`MACCMS_CONTENT_SEARCH_ENABLED=false`，关闭时这两个参数返回 400）；
开启后受并发上限 `MACCMS_CONTENT_SEARCH_MAX_CONCURRENT`（默认 4）与 3 秒服务端执行时限约束，超限返回 503。开启前须先按 09 方案第 6.5 节做合成库测量。
1.2.0（文章/专题/演员/角色/网址/友链与多值字段解码）的规则、与旧版差异和 PHP 黄金样本见 `docs/architecture/11-content-read-completion.md`。
1.3.0（视频列表其余筛选参数与随机排序）见 `docs/architecture/14-video-list-filter-completion.md`；其中 `actor`、`director`、`tag`、`name`
同样是包含匹配，与 `keyword`、`classes` 共用上述开关、并发上限与时限。
开发规范：`docs/architecture/02-agent-development-rules.md`。

另有 `site` 服务端渲染页面（jte 3.2.4）：默认关闭（`MACCMS_SITE_ENABLED=false`）。骨架（MACC-9，占位页 `GET /_site/placeholder`、
错误/维护页）见 `docs/architecture/05-site-skeleton.md`；首页、分类列表（`vod/type`）、视频公开详情（`vod/detail`）三页（MACC-14，只读、
只承接数字 id，路由与旧 URL 兼容规则、配置、与旧版差异）见 `docs/architecture/08-site-pages.md`。

B02（MACC-26）加了四样东西，见 `docs/architecture/11-guards-and-legacy-config.md`：出站护栏、表结构与 SQL 入口护栏（静态测试 +
`-Pmysql-it` 的 information_schema 比对）、旧配置只读层（`MACCMS_LEGACY_CONFIG_DIR`，只解析 `var_export`，不执行 PHP）、
浏览器侧外部地址放行参数（`MACCMS_BROWSER_EXTERNAL_W1/W2/W3`，W1/W2 默认打开、W3 默认关闭；W2 设为 false 时页面中的外部图片显示占位）。
设置了 `MACCMS_LEGACY_CONFIG_DIR` 时，site 启动时读一次旧 `extra/maccms.php`，留空的站点项按字段取旧值（环境变量优先，`base-url` 仍必填），
旧配置里新版不支持的 URL 形态会让启动失败（11 文档第 4.4 节）。

B07（MACC-31）建了写入基础设施，见 `docs/architecture/12-write-infrastructure.md`：与只读池分开的可写数据源（`MACCMS_WRITE_ENABLED`，
**默认关闭**，打开时 `MACCMS_WRITE_DB_URL/USERNAME/PASSWORD` 必须单独提供，地址须与 `MACCMS_DB_URL` 同库）、"模块 + 表 + 操作"写入登记表
与静态/运行期护栏、写 SQL 规范（条件更新、匹配行数、固定顺序与补偿），以及测试侧的并发写测试支撑与回滚演练工具（快照、逐行 diff、
只用 DML 恢复）。B09 起正式登记会员模块对 `mac_user` 的插入与更新（`docs/architecture/18-b09-member-account.md` 第 6 节），关闭可写数据源时会员入口 `user/*` 返回 503。整体切换（D3）方案见 `docs/architecture/13-cutover-plan.md`。

B12（MACC-36）后台框架见 `docs/architecture/21-b12-admin-framework.md`：`MACCMS_ADMIN_ENABLED` **默认关闭**；启用时需要可写数据源，
`MACCMS_ADMIN_ENTRY`（后台入口，如 `/_admin.php`）与 `MACCMS_ADMIN_BASE_URL` 必填。登录（`md5(原值)`、验证码）、进程内会话（每个请求重读
`mac_admin`，停用/改密/删除立即踢出，D2 ⑤）、`admin_auth` 鉴权（auth.php 全部权限码登记，后续批次的页面给出"由 Bxx 批次提供"）、
管理员管理；页面复用旧后台 HTML 结构与 `static/` 下的 layui/admin 资产。PHP 黄金样本 `src/test/php/admin_golden.php`，对照测试
`AdminGoldenMySqlIT`；浏览器检查 `src/test/browser/b12_admin_check.py`。

B06（MACC-30）见 `docs/architecture/16-b06-misc-pages-provide.md`：客户端 IP 组件（`MACCMS_TRUSTED_PROXIES`、`MACCMS_CLIENT_IP_HEADER`，
默认只信连接对端）；Provide 资源站接口（`/api.php/provide/**`，`MACCMS_OPENAPI_ENABLED` **默认关闭**，各模块再按旧配置 `api.*.status`
判定，JSON/XML、白名单、`cachetime` 转为 HTTP 缓存头，只读）；site 新增地图、帮助、版权提示、站点级 RSS 与 6 种搜索引擎地图、
自定义标签页（无内置标签页时一律"非法请求"）、本地生成的二维码、桌面快捷方式、`ajax/suggest`（随搜索开关）与 `ajax/data`。
PHP 黄金样本：`src/test/php/provide_golden.php`（php-cgi 跑完整 ThinkPHP 路由）；对照测试 `ProvideGoldenMySqlIT`、`AjaxGoldenMySqlIT`。

### 环境

- JDK 21（本机 `D:\Tools\Java\jdk-21`，`JAVA_HOME` 已指向它）。
- 不需要预装 Maven：使用仓库内的 Maven Wrapper 3.3.4（`mvnw` / `mvnw.cmd`），首次运行会下载 Maven 3.9.16 到 `~/.m2/wrapper/dists`（或 `MAVEN_USER_HOME`），
  并按 `.mvn/wrapper/maven-wrapper.properties` 的 `distributionSha256Sum` 校验 SHA-256，不一致则拒绝执行。
  该 SHA-256 来自与 Apache 官方 `.sha512` 比对通过的分发包（Wrapper 只支持 SHA-256 属性），见属性文件注释。
- 以下命令都在 `backend/` 目录下执行。Git Bash 用 `./mvnw`，PowerShell/cmd 用 `.\mvnw.cmd`，两种入口都已在本机实测。
- `mvnw.cmd` 在官方 3.3.4 脚本上有一处本地修补：官方脚本在 `~/.m2` 是普通目录时会报
  `Cannot index into a null array`（Windows PowerShell 5.1），已改为同时支持普通目录和链接目录。升级 Wrapper 时要保留或确认上游已修复。

### 构建与测试

```bash
./mvnw verify                 # 编译 + 单元测试 + HTTP 契约测试（不连数据库）
```

MySQL 集成测试（真实 MySQL，使用一次性库与合成数据）：

```bash
export MACCMS_IT_ADMIN_URL='jdbc:mysql://127.0.0.1:3306/?characterEncoding=UTF-8&useSSL=false'
export MACCMS_IT_ADMIN_USERNAME=<有建库/删库权限的本机账号>
export MACCMS_IT_ADMIN_PASSWORD=<密码>
./mvnw verify -Pmysql-it
```

- 测试会创建 `maccms_it_<时间>_<随机>`，导入 `application/install/sql/install.sql` 的表结构（与旧系统相同的 MyISAM/utf8），
  写入合成数据，通过真实 HTTP 调用全部接口，结束后**只删除这个库**。不读写 `maccms_dev` 或任何已有库。
- 缺少上述环境变量时测试直接失败，不会回落到默认账号。
- 管理账号只用于建库、灌数据、比对校验和；被测应用连接同一个一次性库时连接池是只读会话。
- `WriteInfrastructureMySqlIT`（B07）另外用同一个管理账号打开可写数据源，只在这个一次性库上做并发写入、补偿与 DML 恢复演练，
  结束时数据恢复到快照、表结构指纹不变，然后删库。
- 每个集成测试在导入 `install.sql` 后记录 information_schema 指纹，删库前再比对，表结构有任何变化即失败（B02）。

### 本地运行

```bash
./mvnw -q package -DskipTests
# 环境变量见 config/maccms-backend.env.example
export MACCMS_DB_URL='jdbc:mysql://127.0.0.1:3306/<库名>?characterEncoding=UTF-8&useSSL=false'
export MACCMS_DB_USERNAME=<只读账号>
export MACCMS_DB_PASSWORD=<密码>
java -jar target/maccms-backend-0.1.0-SNAPSHOT.jar
```

- 默认监听 `127.0.0.1:18280`。启动完成的标志是日志 `Started MaccmsBackendApplication`，或 `curl http://127.0.0.1:18280/actuator/health` 返回 `{"status":"UP"}`。
- **停止**：前台运行时按 Ctrl+C（会优雅关闭）；后台运行时按记录的 PID 结束，例如 PowerShell
  `$p = Start-Process java -ArgumentList '-jar','target\maccms-backend-0.1.0-SNAPSHOT.jar' -PassThru` 启动，
  `Stop-Process -Id $p.Id` 停止。不要按进程名批量结束 java。
- 也可以不打包直接运行：`./mvnw spring-boot:run`（同样需要上面的环境变量）。
- 本机没有只读账号时，可用开发库 + root，并显式设置 `MACCMS_DB_ALLOW_ROOT=true`；连接仍是只读会话（`SET SESSION TRANSACTION READ ONLY`），应用无法写库。

### 数据库账号

生产/预发应使用**只授予 SELECT 的专用账号**，由数据库管理员创建（本批没有创建或修改任何账号）。参考语句：

```sql
CREATE USER 'maccms_ro'@'<应用服务器地址>' IDENTIFIED BY '<强密码>';
GRANT SELECT ON `<库名>`.* TO 'maccms_ro'@'<应用服务器地址>';
```

应用的防线：

1. 不内置任何地址或凭证，缺配置拒绝启动；`root` 账号默认拒绝启动。
2. 连接池 `read-only: true`：驱动拒绝写语句，MySQL 会话为 `READ ONLY`（对 MyISAM 表同样生效，错误 1792，已由集成测试验证）。
3. Mapper 只有 `select`，不含 `${}` 拼接、DDL、初始化脚本（`spring.sql.init.mode=never`）；静态测试 `ReadOnlyGuardrailsTest` 会检查。
4. 账号本身只有 SELECT 权限（需管理员配合）。

可写数据源（B07）默认关闭。获批写库时，管理员另建写账号，只授予 `mac_*` 表的 `SELECT, INSERT, UPDATE, DELETE`，
经 `MACCMS_WRITE_DB_*` 提供；应用只执行 `WriteRegistry` 登记过的写入，详见 `docs/architecture/12-write-infrastructure.md` 第 2、3 节。

### 目录

```
backend/
  pom.xml                      依赖与版本（理由见 docs/architecture/01-batch1-content-read-plan.md 第 2 节）
  mvnw, mvnw.cmd, .mvn/        Maven Wrapper
  config/*.env.example         运行配置样例（真实 *.env 不提交）
  src/main/java/com/maccms/backend/
    common/                    公共支持：错误码、统一错误响应、分页、数据库账号检查、浏览器侧外部地址放行开关（common/browser）、
                               可写数据源与写入护栏（common/write，默认关闭）
    content/api/               HTTP 层（Controller、DTO）
    content/application/       查询服务、可见性规则、参数校验
    content/infrastructure/    MyBatis Mapper 接口与行记录
    legacyconfig/              旧配置只读层（var_export 解析、配置目录访问）
    admin/                     后台（B12）：入口与页面（web）、登录与鉴权（application）、mac_admin 读写（infrastructure）
  src/main/jte-admin/          后台页面模板（B12，按旧后台 HTML 结构转写）
  src/main/resources/
    application.yml            默认配置（无凭证）
    mapper/content/*.xml       只读 SQL
    mapper-write/<模块>/*.xml  写 SQL（B07 起；目前没有正式的写 Mapper，测试侧演练 Mapper 在 src/test/resources/mapper-write）
  src/test/java/...            单元、HTTP 契约、MySQL 集成测试
```
