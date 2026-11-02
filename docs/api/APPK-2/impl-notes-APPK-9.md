## APPK-9（APPK-2 子单 4）后端实现说明：S3-20 安装向导建表（第 1 段）

- 上游：契约 v1.3.0 `bc612e933aa67abab319b90534c5150c10a51452`（API-030）；技术设计 v13 `9c73bf6c…` §6.14 INS-1～INS-4、C-56/C-57/C-67/C-69
- 分支：`agent/agent/appk-9`（已合入 `feature/APPK-2-be-php-template-migration` @ `2de6979` 作为契约 1.3.0 基线）
- 实现提交：`5f11644`
- 本段范围：S3-20 的产品代码与单测。**未完成**：C-57 的 `InstallWizardMySqlIT` 改写（见“已知问题”）；S3-21a～d 未开工

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
| 设计未列（需评审确认） | `T/architecture/SqlEntryGuardTest.java` | 新增 `REGISTERED_DYNAMIC`：只登记 `InstallConnections.java` 的 1 处非常量 `execute`；每项只抵消一次，登记失效（代码里不再有该调用）时测试失败 |

### API-ID → AC → 测试

| API / 规则 | AC | 测试 |
| --- | --- | --- |
| API-030 INS-1（常量 = blob 哈希、工作区 LF） | AC-12 | `InstallSqlFileTest.constantEqualsRepositoryFileAndFileIsLf` |
| API-030 INS-2 ③（1 字节差异、整文件 CRLF → 不一致） | AC-12 | `InstallSqlFileTest.oneByteDifferenceOrCrlfDoesNotMatch` |
| API-030 INS-2 ④（切分：26 DROP + 26 CREATE + 3 INSERT，无 `CREATE DATABASE`） | AC-12 | `InstallSqlFileTest.repositoryFileSplitsIntoDropCreateAndSeedStatements`、`parseFollowsMacParseSql` |
| INS-SEC-2～5 与判定顺序前段（不连库的分支） | AC-12 | 既有 `InstallModeTest`（6 条，未改，全过） |
| INS-2 ①②④⑤⑥ 在真实库上的执行、语句集合 | AC-12 | `InstallWizardMySqlIT`——**待改写**，本段未覆盖 |

### 实际执行的命令与结果

```text
# 修正工作区换行后核对哈希（Windows autocrlf 检出原为 CRLF）
rm php/application/install/sql/install.sql && git checkout -- php/application/install/sql/install.sql
sha256sum php/application/install/sql/install.sql   # 48dbe0fbd138f670919208d8bcc0ffe28d09b507c527d2a6a1a8f7b77ffa3aa4，45049 字节

# 相关测试类（JDK_JAVA_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:\Tools\Java\afunix-tmp）
./mvnw -B -q test -Dtest='InstallSqlFileTest,SqlEntryGuardTest,SchemaGuardTest,WriteGuardTest,ModuleBoundaryTest,InstallModeTest,InstallWizardGoldenTest,InstallSchemaTest,InitDataTest'
# 9 个类 37 条：0 失败 0 错误

# 全量
./mvnw -B verify   # Tests run: 3031, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS
```

先红后绿：`InstallSqlFileTest` 先于 `InstallSqlFile` 写好；在改 `.gitattributes` 之前，本机工作区的 install.sql 是 CRLF（45950 字节），`constantEqualsRepositoryFileAndFileIsLf` 的“不含 \r”断言对它必然失败。本段没有单独留存红灯运行的输出。

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

1. **`InstallWizardMySqlIT` 未改写（C-57 后半）**：它仍按 B22 前提（库外预导入 install.sql、第 4 步报“表结构不一致”、驱动只见 SELECT/INSERT）编写，`-Pmysql-it` 下会失败。下一段要做：黄金场景改用空库；`InstallGolden.Site` 放入 LF 的 install.sql；新增“已有 `mac_` 表 / 1 字节差异 / CRLF”三个拒绝场景；语句断言改为“install.sql 切分结果 + SELECT/INSERT”；收尾比对改为与 `InstallSchema.expected()` 比较。默认 `./mvnw -B verify` 不跑它。
2. **第 4 步行为变化**：删除指纹分支（INS-3）后，第 4 步只测连接并确认库存在，不再拒绝空库或结构不同的库。契约说“第 4 步行为不变（基线 B22）”，但不删这一步，空库过不了第 4 步，AC-12 无法达成。请架构评审确认这样理解 INS-3。
3. **`SqlEntryGuardTest` 登记**：设计 C-56/C-57 没列这个文件；没有这条登记，INS-2 ④ 逐条执行文件内容就过不了护栏。写法与 `SchemaGuardTest.REGISTERED_TEXT`（C-55）相同。请后端评审判定。
4. install.sql 不存在、读不出或超过 1 MiB，都按 `INSTALL_SQL_HASH_MISMATCH` 返回（PHP 在文件不存在时直接跳过建表；契约没有单列这种情况）。
