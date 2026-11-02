# APPK-2 后端实现说明 —— S3-19a/b 数据库工具（子单 APPK-8，进行中）

- 契约：`docs/api/APPK-2/openapi.yaml` @ `2de69797`（版本 1.3.0，API-010～API-018、`x-interrupt-transitions`、`x-page-banner`；本批未改契约）
- 技术设计：`docs/design/APPK-2/tech-design.md` @ `9c73bf6c`（v13；§4.12、§4.13、§6.13 DBT-1～DBT-5、C-53～C-55、C-73、C-74、§7.4 S3-19a/b、§9）
- 分支：`agent/agent/appk-8`（基线 = 合入 `feature/APPK-2-be-php-template-migration` @ `2de69797` 的合并提交 `5caeff2`）；评审 PASS 后按调度安排合入 `feature/APPK-2-be-php-template-migration` / deploy 分支 `release/APPK-2-php-template-migration`
- 状态：**S3-19a 进行中（第 1 次运行完成公共底座）**，尚未提交评审

## 本次运行已完成（S3-19a 的底座）

| 提交 | 改动项 | 内容 |
| --- | --- | --- |
| `62e3c61` | C-53（DBT-2 判定部分） | 新增 `admin/application/SqlStatements.java`：PHP `trim`/`empty`、`{pre}` 替换、PHP 跳过判定逐字照搬（Database.php:252-263）、MySQL 词法下的多语句检测（单/双引号与 `\` 转义、反引号、`-- `/`#`/`/* */`）、AC-11 首关键字只允许 INSERT/UPDATE/DELETE 且拒绝 `/*!`、1 MiB 上限；单行 INSERT 判定供 DBT-5 ③ 失败分类 |
| `1c54689` | C-73（锁） | `LegacySiteFiles.tryExclusiveLock(relative)`：读写区路径校验；锁文件不存在则创建、永不删除；进程内按规范化路径的单许可 `Semaphore` + `FileChannel.tryLock()`；拿不到返回空；句柄 `close()` 先放 OS 锁再放进程内锁 |
| `01f9d74` | C-73（记录与状态机） | 新增 `DatabaseRun`（运行记录字段，snake_case JSON）、`DatabaseRuns`：`open()` 取锁后先做中断判定（`x-interrupt-transitions` 六行唯一转移；清理只删 `export_files` 点名且符合分卷文件名的文件、以及内容等于 `backup_lock` 的 `backup.lock`，否则 `LOCK_KEPT`/`FAILED` 并列残留）；`probe()` 不等待、判定幂等；`Session.begin/write/blocksNewOperation`；`acknowledge`（RUN_MISMATCH / CONFIRM_REQUIRED / NOT_PENDING / OK）；`classify`（DBT-5 ③）。`WriteSequence.definitelyNotApplied` 改为 `public` 以复用（行为不变） |

| （本次第 4 个提交） | C-53 DBT-4、C-54（acknowledge）、C-74（跨进程） | `DatabaseToolService`：导出在锁内（`DBT_LOCKED`；待处理/RECOVERED → `lock_check`；`backup.lock` 存在 → `lock_check`；26 表白名单 → `TABLE_NOT_ALLOWED`；写 `RUNNING`（op=EXPORT）并记 `export_dir`/`backup_lock`，每新建一卷先记入 `export_files`；结束写 `DONE` + `OK`/`FAILED`）；删备份在锁内（`id` 须 1～20 位数字；`DBT_LOCKED`；`IMPORT` 待处理记录绑定的 `source_backup`/`auto_backup.stamp` → `BACKUP_BOUND`；按卷号升序删除；不写记录、日志一行 INFO）；`acknowledge`（API-018 参数校验 → 锁 → 状态机）与 `probe()`。`AdminDatabaseActions`：导出传管理员编号；新增 `database/acknowledge`（令牌 → 数组形态拒绝 → 服务）。`AdminAuthorization`：`database/acknowledge`、`database/recover` 沿用 `database/import` 权限码（契约 `x-permission`）。`AdminConfiguration` 装配 `DatabaseRuns`。测试侧新增 `DatabaseRunsLockIT` + `DatabaseRunsLockProbe` |

## API-ID → AC- → 测试（本次覆盖部分）

| 契约条目 | API-ID / AC- / V- | 测试（文件::用例） | 覆盖 |
| --- | --- | --- | --- |
| DBT-2 ①～④ | API-013 / AC-11 / V-23 ①～④ | `SqlStatementsTest`（44 条，含参数化） | 正常：单条 DML、末尾 `;`、字符串与注释内的 `;`、前导注释；边界：`empty("0")`、恰好 1 MiB、`--` 后无空白不是注释；异常：多语句、`;;`、结构语句与 `/*!`、超长、PHP 跳过（select 开头、outfile/dumpfile/load_file、注释拆分绕过） |
| `dbtool.lock` | API-010～018 / DBT-5 ④(a) / V-31 ② 单进程部分 | `LegacySiteFilesLockTest`（3 条） | 互斥与释放、重复 close、16 线程 x 20 轮只有 1 个胜出、越界路径与未配置站点根被拒、锁文件不删除 |
| 中断唯一转移 | `x-interrupt-transitions` / V-31 ⑪～⑯、⑲ | `DatabaseRunsTest`（12 条） | OPTIMIZE/REPAIR → DONE/INTERRUPTED（WARN）；SQL → INTERRUPTED（ERROR）；IMPORT/AUTO_BACKUP → DONE/INTERRUPTED_BEFORE_WRITE 并清理；EXPORT 清理遇到他人 `backup.lock` → LOCK_KEPT；非分卷文件名不删 → FAILED；IMPORT/RESTORE → INTERRUPTED；RECOVERING → RECOVERY_INTERRUPTED（attempt 不变）；判定幂等 |
| 状态机与 acknowledge | API-018 / V-31 ③ | `DatabaseRunsTest::acknowledgeFollowsTheStateMachine`、`pendingRecordBlocksNewOperations` | 无记录/DONE → NOT_PENDING；run 不符 → RUN_MISMATCH；未勾选 → CONFIRM_REQUIRED；RECOVERED 可不勾选；待处理阻止新动作 |
| DBT-4 导出入锁 | API-015 / V-31 ⑮、⑰ 单进程部分 | `DatabaseToolLockedFilesTest::exportRunsInsideTheLockAndRecordsItsFiles`、`exportIsRefusedWhileTheLockIsHeldAndNothingIsWritten`、`exportIsRefusedWhileARecordIsPending`、`exportOutsideTheWhitelistIsRefusedBeforeWriting`、`exportWithAForeignBackupLockIsRefused` | 正常：记录 EXPORT/DONE/OK、`export_files`、`backup_lock`，`backup.lock` 已删；异常：锁被占 → 0 文件 0 查询；待处理 → lock_check；白名单外 → 不写记录；他人 `backup.lock` → 不动它 |
| DBT-4 删备份入锁 | API-016 / V-31 ⑰、⑱ 单进程部分 | `DatabaseToolLockedFilesTest::deleteRemovesPartsInsideTheLock`、`deleteIsRefusedWhileTheLockIsHeld`、`deleteRejectsNonDigitIds`、`deleteOfABackupBoundToAPendingImportIsRefused` | 正常：只删该时间戳各卷、不写记录；边界：`""`/`0`/`abc`/`1e3`/`-1`/21 位 → select_del_file；异常：锁被占 → 0 删除；绑定 source/auto 备份 → BACKUP_BOUND，处理后可删 |
| acknowledge | API-018 | `DatabaseToolLockedFilesTest::acknowledgeValidatesInputTakesTheLockAndLeavesPending`；权限 `AdminAuthorizationTest::textSemanticsMatchTheLegacyCheck`（新增 4 行） | 参数非法 → param_err；无记录 → NOT_PENDING；锁被占 → LOCKED；run 不符 → RUN_MISMATCH；未勾选 → CONFIRM_REQUIRED；成功 → save_ok 且 DONE、记录处理人；重复 → NOT_PENDING。权限：`database/import` 放行 acknowledge/recover，`database/acknowledge`、`database/sql` 不放行 |
| 跨进程互斥与中断 | V-31 ②、④、⑪、⑫、⑮ | `DatabaseRunsLockIT`（4 条；子进程 `DatabaseRunsLockProbe`） | 持锁子进程活着：本进程取锁失败、页面 busy、记录仍 RUNNING；`destroyForcibly` 后：SQL → INTERRUPTED（待处理）、OPTIMIZE → DONE/INTERRUPTED、EXPORT → DONE/INTERRUPTED 且半成品分卷与本次 `backup.lock` 被删、锁文件保留；两进程同时起跑 3 轮，每轮恰好一个 GOT、一个 BUSY |
| 失败分类 | API-013/014/017 / DBT-5 ③ | `DatabaseRunsTest::failureClassificationFollowsDbt5` | 42xxx、单行 23000 → 确定未生效；多行 23000、08S01、2013、1317、超时、非 SQL 异常 → 结果不确定 |

## 实际执行的命令与结果（仓库根目录；需 `JDK_JAVA_OPTIONS=-Djdk.net.unixdomain.tmpdir=<专用目录>`）

- 先红：`./mvnw -B -q test -Dtest=SqlStatementsTest -Dsurefire.failIfNoSpecifiedTests=false` → 编译失败（`SqlStatements` 不存在），即功能未写导致的失败。
  `LegacySiteFilesLockTest`、`DatabaseRunsTest` 同样先写测试再写实现，但为节省单次运行时长**没有单独跑红**（它们引用的方法/类在实现前不存在，必然编译失败）；如评审要求，下次运行补跑红并附输出。
- 后绿：`./mvnw -B -q test -Dtest='DatabaseRunsTest,SqlStatementsTest,LegacySiteFilesLockTest,SchemaGuardTest,WriteSequence*' -Dsurefire.failIfNoSpecifiedTests=false`
  → DatabaseRunsTest 12/0、SqlStatementsTest 44/0、SchemaGuardTest 4/0、WriteSequenceTest 10/0、LegacySiteFilesLockTest 3/0。
- 全量：`./mvnw -B verify` → 结果见 Issue 回传评论（本文件提交时附在提交说明里）。

## 剩余工作（下次运行起按顺序做）

S3-19a 还差（第 1 项 BLOCKED，等架构师）：
1. **BLOCKED：执行通道**。技术设计 C-54“经可写数据源 + 按表登记 `WriteRegistry`”与 DBT-2 ⑤“整段原文一次 `Statement.execute`”在基线写入护栏下做不到：`WriteDatabase` 不暴露 `DataSource`，只能经写 Mapper；`WriteGuardInterceptor`/`WriteStatement` 拒绝表维护语句、注释与分号、无顶层 `WHERE` 的整表写、`IGNORE`、`ON DUPLICATE KEY UPDATE`、多表写；DBT-2 的表与列由输入决定，无法写成固定 Mapper。已在 Issue 退回架构师定唯一方案。
2. 方案定后：DBT-1 `optimize`/`repair`（逐表一条语句、PARTIAL）、DBT-2 `sql`（锁内一次执行，按 `classify` 落 DONE/FAILED 或 UNKNOWN）；删除 `REFUSED` 的四项用途；C-55 `SchemaGuardTest` 登记。
3. 页面：`probe()` 与提示条（`x-page-banner`）、`sql.jte` 按 PHP 视图移植、提示条上的“我已处理”表单。

S3-19b：DBT-3 还原（`mac_parse_sql` 移植、预检 (a)～(e)、自动备份与 SHA-256、阶段与逐表状态；同样受第 1 项阻塞）、`recover`、`import.jte`（删备份绑定保护已在本次提前完成）、`DatabaseToolMySqlIT`（需 `MACCMS_IT_ADMIN_*`，Q7 未提供 → 按 BLOCKED 记录）。

## 迁移与回滚

无结构变更（零 DDL，BR-1）。新增的站点根文件 `application/data/backup/database/dbtool.lock`、`dbtool-run.json` 不是数据库对象；回滚按技术设计 §9：`git revert` 本批提交，回退前先确认 `dbtool-run.json` 不在待处理状态。

## 后端自查清单 / 安全自查

S3-19a 全部完成、提交评审时再逐条填写依据（本次只交付底座，未接入 HTTP 入口）。本次已确认的点：日志与运行记录不含 SQL 原文、数据值与连接串（`DatabaseRuns` 日志字段只有 run/op/phase/state/admin_id/计数/清理结果）；中断清理不按通配删除，只删记录点名且符合分卷文件名规则的文件；锁文件路径经 `LegacySiteFiles` 读写区校验，越界被拒（`LegacySiteFilesLockTest::lockPathMustBeInsideAWritableArea`）。

## 已知问题与风险

- 文件锁要求站点根在本机文件系统（RISK-T20）；PHP 后台不参与该锁。
- `DatabaseToolMySqlIT` 依赖测试库凭据（Q7 未纳入本期），相关用例按 BLOCKED 记录。
