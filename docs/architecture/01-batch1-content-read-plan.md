## 首批实施计划：Java 工程骨架与只读内容接口（MACC-4）

### 1. 授权与范围

- **已确认**（用户 2026-10-10 在 MACC-2 回复"那开始吧"）：Java + Spring Boot + MyBatis，先沿用 MySQL 旧库，模块化单体；采集以后也统一用 Java。今后主要由智能体开发。
- **本批交付**：可构建、可测试的 Java 工程（`backend/`）；`content` 模块的只读接口（视频分类、视频分页列表、视频公开详情元数据）和健康检查；正式契约 `docs/contracts/content-read.openapi.yaml`；智能体开发规范（`02-agent-development-rules.md`）。
- **本批不包含、也未获批准**：部署、切 nginx 流量、线上核实、任何 PHP 改动、数据库结构或账号变更、播放授权/收费、会员、Provide、采集、后台写入、搜索、模板还原。
- 本计划沿用已审阅的迁移草稿 `docs/analysis/03-migration-plan-draft.md`（以下简称 03）。本批只落实 03 阶段 1"只读前台"中**数据接口**的一小部分；03 里的模板方案、写入方切换、引擎/字符集迁移、PHP 兼容改动**仍是待定草稿**，本批没有实施。

### 2. 版本选择

| 组件 | 版本 | 理由 |
|---|---|---|
| JDK | 21（Temurin 21.0.11，LTS） | 本机已装；Spring Boot 4 要求 Java 17+。本机说明提到 Java 17+ 在此 LTSC 上可能出现 NIO loopback 失败，2026-10-10 实测 `Selector`/`Pipe`/端口绑定与 Tomcat 启动均正常 |
| Spring Boot | 4.1.1 | 截至 2026-10 的最新稳定分支。4.1 的开源支持到 2027-07-31；4.0 到 2026-12-31，3.5 已于 2026-06-30 结束（[endoflife.date/spring-boot](https://endoflife.date/spring-boot)）。新工程不应选即将停止支持的分支 |
| MyBatis Spring Boot Starter | 4.1.0（MyBatis 3.5.19、MyBatis-Spring 4.1.0） | 官方兼容表：Starter 4.x 对应 Spring Boot 4.0+、Java 17+（[mybatis-spring-boot-autoconfigure](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)）。4.1.0 的 POM 以 Spring Boot 4.1.0 构建 |
| MySQL Connector/J | **8.2.0**（覆盖 Spring Boot 默认的 9.7.0） | 旧库为 MySQL 5.7（本机 5.7.44，线上版本待核实）。Connector/J 8.3.0 起发布说明改为"can be used against MySQL Server version 8.0 and later"，9.x 同样只支持 8.0+；8.2.0 是最后一个写明"5.7 and later"的版本（[8.2.0 发布说明](https://dev.mysql.com/doc/relnotes/connector-j/en/news-8-2-0.html)、[8.3.0](https://dev.mysql.com/doc/relnotes/connector-j/en/news-8-3-0.html)），并已包含 CVE-2023-22102（影响 ≤8.1.0）的修复。若线上确认是 MySQL 8.0+，应改回 Spring Boot 管理的版本 |
| 构建 | Maven 3.9.16，经 Maven Wrapper 3.3.4（only-script，`distributionSha256Sum` 校验；SHA-256 取自与 Apache 官方 SHA-512 比对通过的分发包，Wrapper 不支持 SHA-512 属性） | Maven 是 Spring Boot/MyBatis 文档的默认示例，配置是声明式 XML，智能体读写出错面小；Wrapper 让本机不必预装 Maven。没有选 3.10/4.0 新版，以免引入不必要的变化 |
| 连接池 / Web / JSON | 由 Spring Boot 4.1.1 统一管理（HikariCP 7.0.2、Tomcat 11.0.24、Jackson 3） | 不单独锁版本，减少版本冲突 |

### 3. 模块边界

工程是一个 Maven 模块、一个可执行 jar，按 Java 包划分业务模块（模块化单体）：

| 包 | 职责 | 状态 |
|---|---|---|
| `com.maccms.backend.common` | 错误码、统一错误响应、分页响应、全局异常处理、数据库账号检查。不放业务规则 | 本批实现 |
| `com.maccms.backend.content` | 视频/分类的读取（03 第 1 节 `content` 模块的只读部分） | 本批实现 |
| `site`、`member`、`interact`、`collect`、`openapi`、`admin`、`integration` | 见 03 第 1 节 | **只在文档中预留**，不建空包、空服务或微服务 |

`content` 内部分层，依赖方向只能自上而下：

- `api`：Controller 与 DTO。DTO 与 OpenAPI 一一对应，是对外契约。
- `application`：查询服务、可见性规则（`CategoryCatalog`、`GuestPermission`）、参数校验（`VideoListQuery`、`VideoId`）、排序白名单（`VideoSort`）、行为开关（`ContentProperties`）。
- `infrastructure`：MyBatis Mapper 接口、行记录（record），SQL 在 `resources/mapper/content/*.xml`。

以后其他模块只能通过 `application` 层的服务调用 `content`，不能直接使用它的 Mapper。

### 4. 接口与旧逻辑对照

接口细节以 OpenAPI 为准，这里列出每条规则的旧代码依据。

**4.1 分类可见性**（`GET /api/v1/video-categories`，也用于列表和详情）。一个分类可见，需要同时满足：

| 规则 | 旧代码依据 |
|---|---|
| `type_mid=1`（视频分类） | `common/model/Type.php:130-131`（flag=vod） |
| `type_status=1` | `Type.php:189`（`listCacheData` 固定条件） |
| 游客组 group_id=1 的 `group_type` 包含该分类，且 `group_popedom[分类][1]` 按 PHP `!empty` 为真（"列表"权限） | 游客组：`common/controller/All.php:127,139,143`；判定：`index/controller/Base.php:97-100`；分类页调用：`All.php:180-185`（`check_user_popedom($type_id, 1)`）；无权限分支：`Base.php:170-173`；`user.status=0` 时跳过检查：`Base.php:115-117` |
| 上级分类也可见 | `mac_list_to_tree` 丢弃找不到上级的节点（`application/common.php:476-500`） |

开关 `maccms.content.enforce-guest-popedom`（默认 true）对应 PHP 的 `user.status`（仓库配置为 `'1'`，`application/extra/maccms.php:101`）。同级排序为 `type_sort` 升序、`type_id` 升序。

**4.2 视频列表**（`GET /api/v1/videos`）

| 规则 | 旧代码依据 |
|---|---|
| `vod_status=1` | `common/model/Vod.php:272` |
| 按分类筛选时包含该分类及其直接子分类 | `Vod.php:336-351`（`type_id` 或 `type_pid` 命中） |
| 排序字段白名单 time/time_add/id/score/hits/hits_day/hits_week/hits_month/up/down/level，默认 time desc | `Vod.php:539-545`（本批不支持 `rnd`） |
| 默认每页 20 | `application/extra/maccms.php:92`（`pagesize`） |

新增：并列时按 `vod_id` 同向排序（分页稳定）；`pageSize` 上限 100、`page` 上限 1000；只列出所属分类可见的视频。

**4.3 视频详情**（`GET /api/v1/videos/{id}`）

| 规则 | 旧代码依据 | 新接口行为 |
|---|---|---|
| `vod_id` 且 `vod_status=1` | `common.php:2715-2728`（`mac_label_vod_detail`） | 否则 404 |
| 游客"详情"权限位 2 | `All.php:408-414` → `Base.php:170-173` | 无权限 404（不暴露是否存在） |
| `vod_pwd` 非空时显示密码页 | `index/controller/Vod.php:61-63` | 403 `VIDEO_PASSWORD_REQUIRED`，不返回任何字段 |
| `vod_copyright=1` 且 `copyright_status=2` 时显示版权页 | `index/controller/Vod.php:58-60`；取值含义 `admin/view/system/config.html:391-396` | 开关 `copyright-hides-detail`（默认 true）时 403 `VIDEO_COPYRIGHT_RESTRICTED` |

**4.4 字段**

- 输出字段见 OpenAPI 的 `VideoSummary`、`VideoDetail`、`Category`。SQL 明确列出列名，不使用 `SELECT *`，不返回整行。
- **从不查询**：`vod_pwd*`（观看密码只以 `CHAR_LENGTH(vod_pwd) > 0` 的布尔值参与判断）、`vod_play_*`/`vod_down_*`（播放、下载地址）、`vod_points*`、`vod_trysee`、`group_id`、`vod_lock`、`vod_tpl*`、`vod_reurl`（采集来源）、`vod_jumpurl`、`vod_author`、`vod_time_make`、`vod_time_hits`、`vod_rel_*`、`vod_plot*`、`vod_pic_screenshot`、`type_tpl*`、`type_jumpurl`、`type_union`。由 `ReadOnlyGuardrailsTest` 静态检查、契约测试逐字段比对、集成测试用哨兵值确认响应中不出现。

**4.5 只读保证**

- 只有 `select`；没有 DDL、初始化器、迁移执行；不写缓存表 `mac_vod_search`、不更新点击数、不做会员到期降级（旧系统这三类副作用见 03 第 3.1 节）。
- 连接池只读会话；集成测试对一次性库所有表在请求前后做 `CHECKSUM TABLE ... EXTENDED` 与行数比对，必须完全相同。

### 5. 与旧 PHP 的差异

| # | 方面 | 旧 PHP | 新接口 | 原因 |
|---|---|---|---|---|
| 1 | 分类排序 | 标签默认 `type_pid asc, type_id desc`（`Type.php:117-122,192`），由模板参数决定 | 固定 `type_sort asc, type_id asc`，树形 | API 需要确定顺序；后台"排序"字段的本意 |
| 2 | 停用的分类 | 分类页、其下视频详情仍可访问（`mac_label_type` 用不过滤状态的全量缓存，`Type.php:352,373-387`） | 分类及其视频都不可见（404） | 停用的分类不应继续对外 |
| 3 | 列表权限 | 只有开启 `popedom_filter` 时才过滤列表（`Vod.php:489-499`） | 视频所属分类必须有游客列表权限 | 与分类页的权限检查一致，避免列出点进去就无权限的内容 |
| 4 | 无详情权限 | 显示"无权限"提示页 | 404 | 不暴露内容是否存在 |
| 5 | 观看密码为 `"0"` | PHP `empty("0")` 为真，视为无密码 | 视为有密码（403） | 偏保守，避免误公开 |
| 6 | 版权视频 | 仓库配置 `copyright_status=1`，详情照常展示 | 默认 403，可用开关对齐线上配置 | 线上配置未知（待核实），默认取严格一侧 |
| 7 | 登录态 | 会员按所在组权限看到更多内容 | 一律按游客判定 | 会员登录态校验属于后续批次（03 阶段 1 的 cookie 校验未实现） |
| 8 | 列表筛选与排序 | 支持 year/area/lang/letter/level/wd/tag/class/actor/director/tid/rnd 等 | 只支持 `categoryId`、白名单排序 | 本批范围；其余见第 7 节 |
| 9 | 分页 | 无上限 | `pageSize≤100`、`page≤1000` | 防止无界读取 |
| 10 | 图片地址 | 模板经 `mac_url_img` 转换 | 原样返回库内值 | URL 规则依赖线上 rewrite/path 配置（待核实），属于 `site` 模块 |
| 11 | 错误输出 | HTML 提示页 | JSON + 固定错误码 | API 契约 |
| 12 | 缓存 | `cache_core` 开启时有数据缓存 | 无缓存，每次查库 | 本批先保证正确性；缓存策略另议 |
| 13 | `type_extend` 缺省 | 详情缺省 JSON 用了键 `type` 而不是 `class`（`Type.php:226`，疑似旧 bug） | 统一输出 8 个键，缺省为 `""` | 契约固定 |

### 6. 执行顺序（本批实际过程）

1. 读父任务、MACC-3 文档和旧代码，确定可见性规则与字段清单（第 4 节）。
2. 固定版本（第 2 节），用 Maven Wrapper 建工程。
3. 先写 OpenAPI 契约，再写 DTO、Mapper、服务与 Controller。
4. 测试：单元（规则）→ HTTP 契约（MockMvc + 契约校验器，Mapper 替身）→ 真实 MySQL 集成（一次性库）。
5. 打包、用开发库只读启动、请求、停止。

### 7. 验收命令

在 `backend/` 下：

| 命令 | 内容 | 本批结果 |
|---|---|---|
| `./mvnw verify`（Git Bash）/ `.\mvnw.cmd verify`（PowerShell） | 编译、单元测试、HTTP 契约测试、静态护栏、异常日志脱敏（63 个用例） | 通过 |
| `./mvnw verify -Pmysql-it`（需 `MACCMS_IT_ADMIN_*` 环境变量） | 以上 + 真实 MySQL 5.7 集成测试（2 个用例） | 通过 |
| `java -jar target/maccms-backend-0.1.0-SNAPSHOT.jar` + `curl /actuator/health` | 启动、请求、停止 | 通过（见 MACC-4 交付评论） |

### 8. 暂缓能力与后续批次建议

- **暂缓**：播放页与 `player_aaaa`、播放/下载地址解析（03 第 4.2 节）、播放授权、试看、积分收费；会员登录态与会员组权限；Provide 资源站协议（03 阶段 2）；采集（03 阶段 3）；后台与一切写入；搜索；文章/专题/演员等内容；旧 URL 生成与 SEO（03 第 4.5 节）；模板。
- **建议下一批**（需队长/用户确认）：列表筛选参数（year/area/lang/letter/level 等，对照 `Vod.php:290-402`）；图片 URL 规范化；会员 cookie 只读校验（03 第 3.3 节阶段 1）；黄金样本对比工具。
- **仍待用户决定**（03 第 8 节）：模板走改写还是兼容层；写入方切换各阶段；MyISAM→InnoDB 与 utf8→utf8mb4 的时间窗口和不可逆边界；PHP 侧兼容改动 G1/E1/C1/C2；线上配置与 MySQL 版本核实。

### 9. 风险与遗留

- 线上 MySQL 版本、`user.status`、`copyright_status`、游客组权限未核实；两个开关默认取严格一侧，上线前要按线上配置设定。
- 每个请求读取一次分类表与游客组（都是小表）。分类数量很大时可加短期缓存，届时需评估与 PHP 后台改分类的一致性。
- `COUNT(*)` 在 MyISAM 上带条件时需要扫描索引；视频量很大时列表首屏耗时待线上数据量评估（03 第 4 节黄金样本阶段一并测）。
- 列表只校验游客"列表"权限，详情另校验"详情"权限；会员看到的范围会在会员模块接入后变化，前端不要把游客结果当成全量。
- 异常日志：`GlobalExceptionHandler` 只通过 `SafeExceptionLog` 记录异常类名、cause 链类名和栈帧，不记录任何 message（含嵌套 cause、suppressed），由 `ContentApiContractTest` 的日志捕获用例保证。框架自身在**启动阶段**的失败日志（如缺环境变量、连不上库）不经过该处理器，会带框架异常 message（变量名、驱动报错；密码不写进 URL，不会出现）。
- `mvnw.cmd` 含一处对官方 3.3.4 的本地修补（`~/.m2` 为普通目录时 Target 为空），升级 Wrapper 时需复核；分发包校验依赖 `distributionSha256Sum`，升级 Maven 版本必须同步更新。
