## maccms-backend

MacCMS 重构的 Java 后端（模块化单体）。本批（MACC-4）只包含 `content` 模块的只读接口与公共支持：

| 接口 | 说明 |
|---|---|
| `GET /api/v1/video-categories` | 视频分类树 |
| `GET /api/v1/videos` | 视频分页列表，可按分类筛选；契约 1.1.0（MACC-18）起可按关键字、地区、语言、年份、推荐等级、扩展分类查询 |
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
开发规范：`docs/architecture/02-agent-development-rules.md`。

另有 `site` 服务端渲染页面（jte 3.2.4）：默认关闭（`MACCMS_SITE_ENABLED=false`）。骨架（MACC-9，占位页 `GET /_site/placeholder`、
错误/维护页）见 `docs/architecture/05-site-skeleton.md`；首页、分类列表（`vod/type`）、视频公开详情（`vod/detail`）三页（MACC-14，只读、
只承接数字 id，路由与旧 URL 兼容规则、配置、与旧版差异）见 `docs/architecture/08-site-pages.md`。

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

### 目录

```
backend/
  pom.xml                      依赖与版本（理由见 docs/architecture/01-batch1-content-read-plan.md 第 2 节）
  mvnw, mvnw.cmd, .mvn/        Maven Wrapper
  config/*.env.example         运行配置样例（真实 *.env 不提交）
  src/main/java/com/maccms/backend/
    common/                    公共支持：错误码、统一错误响应、分页、数据库账号检查
    content/api/               HTTP 层（Controller、DTO）
    content/application/       查询服务、可见性规则、参数校验
    content/infrastructure/    MyBatis Mapper 接口与行记录
  src/main/resources/
    application.yml            默认配置（无凭证）
    mapper/content/*.xml       只读 SQL
  src/test/java/...            单元、HTTP 契约、MySQL 集成测试
```
