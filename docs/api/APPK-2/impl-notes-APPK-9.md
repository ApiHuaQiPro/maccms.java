## APPK-9（APPK-2 子单 4）后端实现说明：S3-20 安装向导建表

- 上游：契约 v1.3.0 `bc612e933aa67abab319b90534c5150c10a51452`（API-030）；技术设计 v13 `9c73bf6c…` §6.14 INS-1～INS-4、C-56/C-57/C-67/C-69
- 分支：`agent/agent/appk-9`（已合入 `feature/APPK-2-be-php-template-migration` @ `2de6979` 作为契约 1.3.0 基线）
- 实现提交：第 1 段 `5f1164405875a9bd76ce1d484b17538e5f993748`（产品代码、C-57 单测部分）；第 2 段 `d75e9ba8128ed7469e6522c62f6d61a10529fe24`（M-2、M-3）及其后的 IT 改写提交（M-4，提交号见 Issue 回传）
- 范围：S3-20 全部（C-56、C-57、C-67、C-69；API-030；AC-12；V-25）。S3-21a～d 已由智能调度拆为 APPK-14～APPK-17，不在本单本轮

### 变更文件

| 设计编号 | 文件 | 改动 |
| --- | --- | --- |
| C-56 | `install/application/InstallSqlFile.java`（新增） | `INSTALL_SQL_SHA256` 常量；按原始字节 SHA-256 校验（`MessageDigest.isEqual`）；`statements` 照搬 PHP `mac_parse_sql($sql, 0, …)` + `array_filter`，不做前缀替换 |
| C-56 | `install/application/InstallWizard.java` | 第 5 步按契约判定顺序：互斥区内复查锁 → INS-2 ①② `mac_` 表数 → ③哈希 → 写 `extra/maccms.php` → ④执行 → ⑤初始数据、管理员 → ⑥安装锁；删除 `checkTarget`（指纹 + 行数只读核对，INS-3），第 4 步同时去掉该调用 |
| C-56 | `install/infrastructure/InstallConnections.java` | 新增 `executeInstallSql`：本次调用内打开、用后关闭的普通会话（同样禁止多语句、本地文件导入、反序列化），逐条执行，失败即停；日志只记语句序号与异常类型链 |
| C-56 | `install/infrastructure/InstallDatabaseProbe.java` | 新增 `macTableCount`（只读会话，`information_schema.TABLES`，`LIKE 'mac\_%'`） |
| C-69 | `legacyconfig/application/LegacySiteFiles.java` | 新增 `readInstallSql(int)`：固定路径 `application/install/sql/install.sql`，经 `LegacySiteRoot` 校验、`NOFOLLOW_LINKS` 读原始字节 |
| C-67 | `.gitattributes` | `php/application/install/sql/install.sql text eol=lf` |
| C-57（单测部分） | `T/install/InstallSqlFileTest.java`（新增） | 见下表 |
| C-56（INS-3） | `install/application/InstallSchema.java` | 第 2 段：删除不再使用的 `SHOWN`；类注释改为“运行期不再调用，保留给 `InstallSchemaTest` / `InstallWizardMySqlIT` 核对表结构”；`expected()` / `differences()` / `format()` / `TABLES` / `SEED_ROWS` 保留 |
| C-56 / API-030（单测） | `T/install/InstallWizardTest.java`（新增） | 第 2 段：桩 `InstallConnections`（不连外部 MySQL）覆盖第 4 步与第 5 步判定顺序，见下表 |
| C-69（单测） | `T/legacyconfig/application/LegacySiteFilesTest.java`（新增） | 第 2 段：`readInstallSql` 六条，见下表 |
| C-57 / V-25 | `T/install/InstallGolden.java` | 第 2 段：`Site.create()` 把仓库 `php/application/install/sql/install.sql`（LF）原样复制到临时站点 `application/install/sql/install.sql` |
| C-57 / V-25 | `T/install/InstallWizardMySqlIT.java` | 第 2 段：按 INS-1～INS-4 改写，见下表与“实际执行的命令” |
| 设计未列（需评审确认） | `T/architecture/SqlEntryGuardTest.java` | 新增 `REGISTERED_DYNAMIC`：只登记 `InstallConnections.java` 的 1 处非常量 `execute`；每项只抵消一次，登记失效（代码里不再有该调用）时测试失败 |

### API-ID → AC → 测试

| API / 规则 | AC | 测试 |
| --- | --- | --- |
| API-030 INS-1（常量 = blob 哈希、工作区 LF） | AC-12 | `InstallSqlFileTest.constantEqualsRepositoryFileAndFileIsLf` |
| API-030 INS-2 ③（1 字节差异、整文件 CRLF → 不一致） | AC-12 | `InstallSqlFileTest.oneByteDifferenceOrCrlfDoesNotMatch` |
| API-030 INS-2 ④（切分：26 DROP + 26 CREATE + 3 INSERT，无 `CREATE DATABASE`） | AC-12 | `InstallSqlFileTest.repositoryFileSplitsIntoDropCreateAndSeedStatements`、`parseFollowsMacParseSql` |
| INS-SEC-2～5 与判定顺序前段（不连库的分支） | AC-12 | 既有 `InstallModeTest`（6 条，未改，全过） |
| INS-3：第 4 步只测连接、空库通过 | AC-12 | `InstallWizardTest.step4AcceptsAnEmptyDatabase` |
| 互斥区内复查安装锁 → “非法访问”，不连库 | AC-12 | `InstallWizardTest.lockCreatedBeforeEnteringTheCriticalSectionIsRejected` |
| INS-2 ① 数表时连不上 → `database_connect_err` | AC-12 | `InstallWizardTest.connectFailureWhileCountingTables` |
| INS-2 ② 已有 `mac_` 表 → `INSTALL_TABLES_EXIST` | AC-12 | `InstallWizardTest.existingMacTablesAreRejected`；真实库：`InstallWizardMySqlIT.refusesWithoutDdlAndInstallsAnEmptyDatabase` |
| INS-2 ③ 缺失 / 1 字节 / CRLF → `INSTALL_SQL_HASH_MISMATCH` | AC-12 | `InstallWizardTest.missingChangedOrCrlfInstallSqlIsAHashMismatch`；真实库（1 字节、CRLF `152e5424…`）：同上 IT |
| INS-2 ④ 先写 `maccms.php`，55 条语句（26 DROP + 26 CREATE + 3 INSERT）原样交给 `executeInstallSql`；`CONNECT_FAILED` → `database_connect_err`、`SQL_FAILED` → `init_err`，不写安装锁 | AC-12 | `InstallWizardTest.matchingFileWritesSiteConfigThenExecutesAllStatements` |
| 以上拒绝分支：0 条语句送执行、`maccms.php` 未改、无安装锁 | AC-12 | `InstallWizardTest` 各条的 `nothingExecutedOrWritten` |
| C-69 `readInstallSql`：原始字节（LF / CRLF 不转换）、不存在为 null、`TOO_LARGE`、目录 `NOT_REGULAR_FILE`、站点外符号链接 `OUTSIDE_ROOT`、未配置 `NOT_CONFIGURED` | AC-12 | `LegacySiteFilesTest`（6 条） |
| V-25 黄金复放（PHP 7.3 / 8.0，空库）：响应一致；建出 26 张表、指纹与 `InstallSchema.expected()` 完全一致、`mac_group` 3 行、`mac_admin` 1 行；正常模式启动前后表结构不变 | AC-12 | `InstallWizardMySqlIT.goldenScenariosMatchPhp73And80` |
| V-25 语句：DDL 只来自 install.sql 切分结果（55 条全部出现），无 `CREATE DATABASE`；其余只有 SELECT / INSERT（mac_type、mac_admin）/ 驱动会话设置 | AC-12 | 同上两个 IT 的 `assertInstallStatements` |

### 实际执行的命令与结果

```text
# 修正工作区换行后核对哈希（Windows autocrlf 检出原为 CRLF）
rm php/application/install/sql/install.sql && git checkout -- php/application/install/sql/install.sql
sha256sum php/application/install/sql/install.sql   # 48dbe0fbd138f670919208d8bcc0ffe28d09b507c527d2a6a1a8f7b77ffa3aa4，45049 字节

# 相关测试类（本机 JDK 的 AF_UNIX 临时目录需经 JDK_JAVA_OPTIONS=-Djdk.net.unixdomain.tmpdir=<本机可写目录> 指定，与本单无关）
./mvnw -B -q test -Dtest='InstallSqlFileTest,SqlEntryGuardTest,SchemaGuardTest,WriteGuardTest,ModuleBoundaryTest,InstallModeTest,InstallWizardGoldenTest,InstallSchemaTest,InitDataTest'
# 9 个类 37 条：0 失败 0 错误

# 全量
./mvnw -B verify   # Tests run: 3031, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS
```

#### 第 2 段（M-2～M-4）

```text
./mvnw -B -q test -Dtest='InstallWizardTest,LegacySiteFilesTest,InstallSchemaTest,InstallSqlFileTest,InstallModeTest,InstallWizardGoldenTest'
# InstallWizardTest 6、LegacySiteFilesTest 6（符号链接用例实际执行，未跳过）、InstallSchemaTest 3、InstallSqlFileTest 4、
# InstallModeTest 6、InstallWizardGoldenTest 2：0 失败 0 错误 0 跳过

# 真实 MySQL 5.7.44（本机），一次性库 maccms_it_*；MACCMS_IT_ADMIN_URL / _USERNAME / _PASSWORD 只在本次进程环境里设置，未写入任何文件
./mvnw -B verify -Pmysql-it -Dit.test=InstallWizardMySqlIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false
# [mysql-it] B22 golden: 94 steps compared against PHP 7.3/8.0
# [mysql-it] statement kinds seen by the driver: [CREATE, DROP, INSERT, SELECT, SET] (79 distinct)
# [mysql-it] statement kinds seen by the driver: [CREATE, DROP, INSERT, SELECT, SET] (78 distinct)
# [mysql-it] dropped 12 disposable databases (verified absent)
# Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
# 事后 SHOW DATABASES LIKE 'maccms_it_%' 为 0 行；未连接 maccms_dev

./mvnw -B verify   # 全量结果见 Issue 回传
```

第 2 段的单测针对第 1 段已写好的产品代码，属于补测，没有红灯阶段可留存；IT 首次运行暴露两处测试自身问题（驱动在语句末尾附加 `[Created on: …]`、令牌作废后仍用旧令牌）并已修正，产品代码未改。

先红后绿（第 1 段）：`InstallSqlFileTest` 先于 `InstallSqlFile` 写好；在改 `.gitattributes` 之前，本机工作区的 install.sql 是 CRLF（45950 字节），`constantEqualsRepositoryFileAndFileIsLf` 的“不含 \r”断言对它必然失败。本段没有单独留存红灯运行的输出。

### 迁移与回滚

- 无库结构迁移。安装向导本身按 install.sql 建表，只作用于部署方新建的空库。
- 回滚：`git revert 5f11644`，恢复 B22 的“只读核对指纹”行为（技术设计 §11 S3-20 行）。

### 后端自查（multica-backend-impl 清单的相关项）

- 错误码：`INSTALL_TABLES_EXIST`“已存在数据表”、`INSTALL_SQL_HASH_MISMATCH`“文件哈希不一致”与契约原文一致；④ 失败 → `lang(install/init_err)`，不含 SQL；④ 打不开连接 → `lang(install/database_connect_err)`。
- 鉴权与幂等：判定顺序前段（INS-SEC-2～5、一次性令牌）未改；写库阶段仍在 `installLock` 互斥区内，进入后先复查安装锁。
- 注入面：执行的语句只来自哈希与内置常量一致的固定文件；路径不由调用方传入；会话 `allowMultiQueries=false`、`allowLoadLocalInfile=false`。
- 日志：不记 SQL 原文、连接串、密码与绝对路径（只记序号、类型链、`FileException.reason()`）。
- 凭据：未写入代码与测试数据。

### 已知问题与风险

1. **`InstallWizardMySqlIT` 已改写（C-57 后半，第 2 段）**：黄金场景改用空库，由第 5 步建表；新增“已有 `mac_` 表 / 1 字节差异 / CRLF”三个拒绝场景；B22 的“空库在第 4 步报结构不一致”“结构多一列”“表里已有数据”三个场景随 INS-3 删除。在本机 MySQL 5.7.44 上通过；未在 MySQL 8 上跑过。默认 `./mvnw -B verify` 不跑它。
2. **第 4 步行为变化**：删除指纹分支（INS-3）后，第 4 步只测连接并确认库存在，不再拒绝空库或结构不同的库。智能调度已确认这符合技术设计 §5.2 C-56 与 §6.14 INS-3（API-030 的“第 4 步行为不变”只说明 operation 只登记 step=5），随 S3-20 交后端评审复核。
3. **`SqlEntryGuardTest` 登记**：设计 C-56/C-57 没列这个文件；没有这条登记，INS-2 ④ 逐条执行文件内容就过不了护栏。写法与 `SchemaGuardTest.REGISTERED_TEXT`（C-55）相同。请后端评审判定。
4. install.sql 不存在、读不出或超过 1 MiB，都按 `INSTALL_SQL_HASH_MISMATCH` 返回（PHP 在文件不存在时直接跳过建表；契约没有单列这种情况）。
5. **`readInstallSql` 与符号链接**：`LegacySiteFiles.Reason` 没有单独的 `SYMLINK`。指向站点根之外的链接（文件或 `install` 目录）按 `OUTSIDE_ROOT` 拒绝（`LegacySiteFilesTest` 已测）；指向站点根之内另一普通文件的链接与 `LegacySiteFiles` 其余读取方法一样按真实路径读取，读到的字节仍须通过 INS-2 ③ 的 SHA-256 才会执行。是否要对 install.sql 另加“禁止任何链接”，请后端评审判定。
