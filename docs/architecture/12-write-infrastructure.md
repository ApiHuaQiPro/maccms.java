## 12 写入基础设施：可写数据源、写入登记与护栏、写 SQL 规范、并发测试与回滚演练工具（B07 / MACC-31）

> **状态：已在本地实现并通过 `./mvnw verify` 与 `./mvnw verify -Pmysql-it`，未部署。** 依据：`docs/architecture/09-roadmap.md`（以下简称"路线图"）第 1.2 节、第 5.3 节 B07 行与写入方矩阵、第 6 节。D3 整体切换方案单独成文：`13-cutover-plan.md`。
> 本批**不实现任何业务写入**（会员、内容管理、采集都不在本批），正式登记表为空；不改旧 PHP、不改表结构与账号、不读 `prod-config`、不连线上。所有写入与恢复演练只在一次性库 `maccms_it_*` 上进行。路径相对仓库根目录，代码在 `backend/` 下。

### 1. 交付物一览

| 路线图 B07 | 实现 | 自动测试 |
|---|---|---|
| ① 可写数据源 | `common.write.WriteDatabase`（独立连接池 `maccms-rw` + 独立 MyBatis 会话工厂，不是 `DataSource` Bean）、`WriteDataSourceProperties`/`WriteDataSourceSettings`（`MACCMS_WRITE_*`，默认关闭）、`WriteDataSourceConfiguration` | `WriteDataSourceConfigurationTest`；`WriteInfrastructureMySqlIT` ①②③ |
| ② 写入登记表与护栏 | `common.write.WriteRegistry`/`WritePermission`/`WriteOperation`（模块 + 表 + 操作）；静态 `architecture.WriteGuardTest`（含写入方矩阵 `OWNER_MATRIX`）；运行期 `common.write.WriteGuardInterceptor` | `WriteGuardTest`（真实源码 + 合成用例 + 演练 Mapper）、`WriteRegistryTest`；`WriteInfrastructureMySqlIT` ②⑥ |
| ③ 写 SQL 规范 | `common.write.WriteStatement`（语法）、`WriteSequence`（固定顺序、匹配行数、补偿与上报）、`WriteConflictException`、`CompensationFailedException`；第 4 节约定 | `WriteStatementTest`、`WriteSequenceTest`；`WriteInfrastructureMySqlIT` ④⑤⑦ |
| ④ 并发写测试框架 | 测试侧 `writedrill.ConcurrentWrites` | `WriteDrillToolsTest`；`WriteInfrastructureMySqlIT` ④⑤ |
| ⑤ 回滚演练工具 | 测试侧 `writedrill.TableSnapshot`/`DatabaseSnapshot`/`SnapshotDiff`/`DmlRestore` | `WriteDrillToolsTest`；`WriteInfrastructureMySqlIT` ⑧；`WriteGuardTest.drillToolsUseOnlyDml` |
| ⑥ 整体切换方案 | `docs/architecture/13-cutover-plan.md` | 仅文档；B21 在一次性副本上演练 |
| ⑦ 02 规范第 3 节 | "DDL 独立评审"改为"禁止 DDL"；分阶段写入方切换的旧说法改为整体切换（另同步第 2、5 节） | — |

### 2. 可写数据源

| 配置项 / 环境变量 | 默认 | 说明 |
|---|---|---|
| `maccms.write.enabled` / `MACCMS_WRITE_ENABLED` | `false` | `false` 时不创建任何可写连接，应用与此前一样只读 |
| `maccms.write.url` / `MACCMS_WRITE_DB_URL` | 空 | 打开时必填。只接受 `jdbc:mysql://主机[:端口]/库名[?参数]`（单主机、带库名），主机、端口（缺省 3306）、库名必须与 `MACCMS_DB_URL` 相同 |
| `maccms.write.username` / `MACCMS_WRITE_DB_USERNAME` | 空 | 打开时必填；首尾不能有空白 |
| `maccms.write.password` / `MACCMS_WRITE_DB_PASSWORD` | 空 | 打开时必填 |
| `maccms.write.allow-root` / `MACCMS_WRITE_DB_ALLOW_ROOT` | `false` | 用 root 时必须为 `true`（仅本机开发） |
| `maccms.write.pool-size` / `MACCMS_WRITE_DB_POOL_SIZE` | `4` | 1～50 |

- **与只读连接池分开**：只读池（`spring.datasource.*`、`hikari.read-only: true`、池名 `maccms-ro`）一行没改。可写数据源是 `WriteDatabase` 对象自己持有的 Hikari 池，**不注册成 `DataSource` Bean**——这样 Spring Boot 的数据源与 MyBatis 自动配置、健康检查、事务管理器都只看到只读池，任何代码都没法经依赖注入拿到可写连接。写 Mapper 只能经 `WriteDatabase.mapper(类型)` 取得。
- **凭证单独提供**：打开时三项缺一即启动失败，不回落到 `MACCMS_DB_*`。地址里不能出现 `user`、`password` 参数。
- **由应用固定的连接参数**（地址里出现即启动失败）：`useAffectedRows=false`（UPDATE 返回**匹配**行数，第 4.2 节）、`allowMultiQueries=false`、`allowLoadLocalInfile=false`、`allowUrlInLocalInfile=false`；地址里也不能设 `allowLoadLocalInfileInPath`、`sessionVariables`。另设 `connectTimeout=5000`；自动提交、不开事务；MyBatis 语句超时 10 秒、关闭二级缓存。
- **启动检查**：取一个连接，确认不是只读会话、当前库与地址中的库名一致。连接池构造时不连库（`initializationFailTimeout=-1`），由这一步连接——连接失败时只报"可写数据源初始化失败：无法取得连接（检查 MACCMS_WRITE_DB_URL/USERNAME/PASSWORD）：<异常类型链>"，**不带 cause**（驱动与连接池的 message 含账号、主机），日志里也没有账号与密码（`WriteDataSourceConfigurationTest`、`WriteInfrastructureMySqlIT` ③ 用标记值核对）。`WriteDataSourceProperties`、`WriteDataSourceSettings` 的 `toString()` 不输出地址、账号、密码。
- **只加载写 Mapper**：会话工厂只加载 `classpath*:mapper-write/**/*.xml`，只读 Mapper（`mapper/`）不在里面；每条语句执行前经第 3.3 节的拦截器检查，**查询一律拒绝**（读取走只读池）。
- **生产账号**（本批未创建任何账号，由数据库管理员在获批部署时创建）：写账号只授予 `mac_*` 表的 `SELECT, INSERT, UPDATE, DELETE`，不授予任何结构、维护、账号权限；只读池继续用只授予 `SELECT` 的账号。

### 3. 写入登记表与护栏

#### 3.1 登记表

- `WriteRegistry.REGISTERED`：`WritePermission(模块, 表, 操作, 批次)` 的列表。模块是写 Mapper 所在的 `com.maccms.backend.<模块>.infrastructure`；表是 `mac_*`；操作是 `INSERT`/`UPDATE`/`DELETE`；批次形如 `B09`。同一"模块 + 表 + 操作"不能重复登记。
- **B07 交付时为空**（`WriteGuardTest.registeredPermissionsFollowTheOwnerMatrix` 同时锁定"为空"，B08 起登记时一并修改这条断言）。
- 每个写库批次在自己的合并提交里只登记本批条目；表与批次必须符合路线图第 5.3 节写入方矩阵，矩阵逐表写在 `WriteGuardTest.OWNER_MATRIX`（26 张表，与 `install.sql` 核对）。矩阵要改须先改路线图。正式登记表不能出现演练模块（`drill*`）。
- 同一张表可以由多个模块登记（例如 `mac_user`：会员 B09、积分 B10、后台 B14），这是路线图矩阵本身的设计；D3 已决整体切换，不再有"PHP 与 Java 同时写同一张表"的阶段，所以不需要"单一写入方"的时段约束。

#### 3.2 静态护栏 `WriteGuardTest`（`./mvnw verify`）

1. 写 SQL 只写在 `src/main/resources/mapper-write/<模块>/*.xml`：Java 源码（含字符串、相邻字面量拼接、文本块）里不得出现对 `mac_*` 的 `INSERT … INTO`、`UPDATE … SET`、`DELETE FROM`、`REPLACE`；不得用 MyBatis 的 `@Insert`/`@Update`/`@Delete`/`@SelectKey`/`@Options`。只读 Mapper XML 不能有写语句（原有 `ReadOnlyGuardrailsTest`）。
2. 写 Mapper XML：命名空间必须是 `<模块>.infrastructure` 下标了 `@WriteMapper` 的接口，文件放在 `mapper-write/<模块>/`；元素只能是 `insert`/`update`/`delete`，**不得有** `select`、`where`（条件全空时会变成整表写入）、`sql`/`include`（让静态检查看到完整语句）、`selectKey`、`cache`、`${}`；每条语句经 `WriteStatement` 校验（第 4.1 节）、元素类型与 SQL 一致，并且"模块 + 表 + 操作"已登记。
3. `@WriteMapper` 只能标在 `<模块>.infrastructure` 下的接口上，不能同时标 `@Mapper`（否则会被自动扫描并绑到只读池）。
4. 入口只在 `common.write`：`WriteDatabase.open`、`WriteRegistry.of`、Hikari、`SqlSessionFactory(Bean)`、`SqlSessionTemplate`、`WriteGuardInterceptor`、`maccms.write`/`MACCMS_WRITE_` 配置；除 `common.write` 外只有 `<模块>.infrastructure` 可以引用 `WriteDatabase`。
5. 不用 `@Transactional`、`TransactionTemplate`、`(Platform)TransactionManager`、`setAutoCommit(false)`：旧表是 MyISAM，事务不起作用（第 4.3 节），多表一致性用 `WriteSequence`。
6. 演练工具（`writedrill` 下的快照、diff、恢复）只用 DML：用 B02 的 `SchemaGuardTest` 规则扫描，不得出现结构语句与 `TRUNCATE`。

自检：`rulesOnSyntheticSources` 覆盖未登记的表、操作、模块，元素与 SQL 不符、`select`/`where`/`sql`/`include`/`selectKey`/`${}`、多语句、多表、`REPLACE`、`IGNORE`、无 WHERE，Java 中的写 SQL 文本（含拼接与文本块）、写注解、事务、越界入口与配置，登记表越出矩阵。`drillMappersAreCheckedLikeMainOnes` 把测试侧演练 Mapper 按同一规则检查：演练登记表下**恰好**剩下三条故意未登记的语句（表 `mac_gbook`、操作 `DELETE`、模块 `drillother`），正式登记表下全部语句都被拦下。

#### 3.3 运行期护栏 `WriteGuardInterceptor`

可写会话工厂上的 MyBatis 拦截器：每条写语句发送前，取 MyBatis **实际生成**的 SQL（动态 `<if>` 等已展开），按第 4.1 节解析、核对元素类型，再按命名空间得到模块、查登记表；不通过抛 `WriteRejectedException`，语句不执行。查询（`query`、`queryCursor`）一律拒绝。`WriteDatabase.mapper()` 返回的代理把 MyBatis 包了两层的异常取出来，调用方直接收到 `WriteRejectedException`。

`WriteInfrastructureMySqlIT` 验证：② 打开可写数据源后用正式（空）登记表，任何写入都被拒且数据不变；⑥ 用演练登记表时，未登记的表（`mac_gbook` INSERT）、操作（`mac_user` DELETE）、模块（`drillother`）都在执行前被拒，快照 diff 为空。

#### 3.4 与 B02 护栏的兼容与收窄的例外

| B02 护栏 | 本批改动 | 自检 |
|---|---|---|
| `ModuleBoundaryTest`：数据访问类型只在 infrastructure 与 `common.config` | 例外表 `DATA_ACCESS_OUTSIDE_INFRASTRUCTURE` 加入 `common.write`，**精确匹配包名**（不含子包） | 合成用例：`common.write` 通过；`common.web`、`common.writex`、`common.write.internal`、`common`、`member.application` 仍被拦；`common.write` 引用业务模块仍被拦 |
| `SqlEntryGuardTest`：Mapper XML 的 `${}` 只能是登记表达式 | 扫描范围加上 `mapper-write/`（写 Mapper 另由 `WriteGuardTest` 整体禁止 `${}`） | 原有合成用例不变 |
| `SchemaGuardTest`：结构/维护/账号语句 | 无改动；`mapper-write/` 在 `src/main/resources` 下，同样被扫描；演练工具也用它检查 | 原有合成用例不变 |
| `ReadOnlyGuardrailsTest.mainResourcesContainNoCredentials` | 原规则 `password\s*[:=]\s*(?!\$\{)\S` 会把 `${MACCMS_WRITE_DB_PASSWORD:}` 中的 `PASSWORD:}` 误判为明文密码。改为：先把 `${…}` 整体换成 `${}` 再查"password 后面不是占位符"，另查"占位符里的密码默认值只能为空"（`\$\{[^}]*PASSWORD:[^}]`） | 新增合成用例：空默认值通过；明文密码、`${…PASSWORD:secret}`、`${…PASSWORD: x}`、明文 JDBC 地址（含藏在占位符默认值里的）、`username: root` 都被拦 |
| `OutboundNetworkGuardTest` | 无改动（可写数据源只连 MySQL，`java.sql`/Hikari 不在出站名单内） | — |
| `-Pmysql-it` 结束时比对 information_schema 指纹 | 新增 `WriteInfrastructureMySqlIT` 照做：导入后取指纹，演练与恢复之后、删库之前比对，必须一致 | — |

### 4. 写 SQL 规范

#### 4.1 语法（`WriteStatement`，静态与运行期共用）

只接受三种单表语句：`INSERT INTO <表> (...) VALUES (...)`、条件插入 `INSERT INTO <表> (...) SELECT ... FROM DUAL WHERE NOT EXISTS (...)`、`UPDATE <表> [别名] SET ... WHERE ...`、`DELETE FROM <表> WHERE ...`。拒绝：其他语句（`REPLACE`、`SELECT`、`LOCK`、结构与维护语句……）、分号（多语句）、注释与优化器提示、`IGNORE`（把错误变成警告）、`ON DUPLICATE KEY UPDATE`（旧表几乎没有唯一键可依赖）、多表 `UPDATE`/`DELETE`（逗号、`JOIN`、`USING`）、带库名前缀的表名、**没有顶层 `WHERE` 的 `UPDATE`/`DELETE`**（括号里的 WHERE 不算）。字符串字面量先整体遮蔽再判断。拒绝信息只写原因，不回显语句。

#### 4.2 单语句原子、条件更新与匹配行数

- **数值变化一律原子表达式**：`SET user_points = user_points - ?`、`vod_hits = vod_hits + 1`，不做"先读出来、在 Java 里算好、再写回"。旧版的点击、积分多是先读后写（例如 `application/common/model/Card.php:142` 用 `setInc`，但前面的卡状态是先 `find` 再改），新版在此处更严，不改变结果的语义。
- **条件更新**：前置条件写进同一条语句的 `WHERE`（`AND user_points >= ?`、`AND card_use_status = 0`、`AND order_status = 0`），并发时只有满足条件的请求能命中。
- **检查匹配行数**：可写数据源固定 `useAffectedRows=false`，`UPDATE` 返回**匹配**行数——写入同值（例如把已是 0 的积分再设成 0）也返回 1，不会被误判为"没找到"。调用方必须把返回值与预期比较（`WriteSequence.requireMatched` 或序列步骤的 `expected`），不等即 `WriteConflictException`。`WriteInfrastructureMySqlIT` ④ 实测：同值写入返回 1、不存在的行返回 0。
- **没有唯一键时的去重**：用条件插入（`INSERT … SELECT … WHERE NOT EXISTS (…)`）。MyISAM 的写锁是表级的，`INSERT … SELECT` 对同一张表在一条语句内完成判断与插入；`WriteInfrastructureMySqlIT` ④ 用 5 轮 × 16 线程验证每组只插入 1 行。
- 每个"先查后写"的业务操作都必须改写成上面某种单语句形式，并有并发测试（第 5 节）。

#### 4.3 MyISAM 的非事务性边界

旧表全部是 MyISAM（零结构变更，不转 InnoDB），这意味着：

1. **没有事务**：`BEGIN … ROLLBACK` 对 MyISAM 不起作用。`WriteInfrastructureMySqlIT` ⑦ 实测：关闭自动提交、`UPDATE` 后 `ROLLBACK`，修改仍然生效（本机 Connector/J 8.2 + MySQL 5.7.44 没有把 1196 警告带回客户端，所以也不能靠警告发现）。因此可写数据源自动提交、不配事务管理器，护栏禁止 `@Transactional`。
2. **单行语句是原子的**：一条只命中一行的语句要么生效要么不生效。严格模式（MySQL 5.7 默认 `STRICT_TRANS_TABLES`，Connector/J 的 `jdbcCompliantTruncation` 也会保证）下，第一行就出错的语句整体不生效——`WriteInfrastructureMySqlIT` ⑤-2 用超长备注触发 1406，验证没有写入、前序步骤被补偿。
3. **多行语句出错会留下部分结果**：MyISAM 在第 N 行出错时前 N-1 行已经写入，没有回滚。所以序列中的步骤应是单行语句；预期多于 1 行的步骤一旦出错，`WriteSequence` 一律按"结果不确定"处理。
4. **结果不确定的情形**：连接中断（SQLState `08xxx`、客户端错误码 2000～2999）、语句超时或被中止（1028、1053、1317、1927、3024、`SQLTimeoutException`）、以及非 SQL 异常——客户端不知道语句是否已在服务端执行。此时**不做补偿**（补偿一个其实没生效的步骤，或在其实已生效时撤销前序步骤，都会制造新的不一致），直接抛 `CompensationFailedException(OUTCOME_UNKNOWN)` 交人工对账。
5. **表级锁**：MyISAM 写语句锁整表，长时间的扫描（例如 `LIKE '%x%'` 搜索）会挡住写入（MACC-19 已测量，D7 已接受）；写语句本身都是按主键或带索引的条件，持锁时间短。

#### 4.4 多表写入：固定顺序、补偿与上报（`WriteSequence`）

步骤分三种角色，顺序在构建时检查（违反即 `IllegalArgumentException`）：

1. **认领 CLAIM**（最多一步、必须第一步）：只有一个并发请求能成功的条件写入，同时是幂等键。例：`UPDATE mac_card SET card_use_status=1, card_sale_status=1, user_id=?, card_use_time=? WHERE card_id=? AND card_use_status=0`。
2. **转移 TRANSFER**：余额、积分、到期时间等数值变化（条件扣减 `AND user_points >= ?`）。
3. **记录 RECORD**：追加日志与流水（`mac_plog`、`mac_ulog` 等）。

执行规则：

- 每步返回匹配行数，与预期比较。**不等** → 已完成的步骤按**相反顺序**补偿，全部补偿成功后抛 `WriteConflictException`（含序列名、步骤名、匹配数），数据回到本次写入之前。第一步（认领）不命中时什么都没改。
- 某步**抛异常**：肯定没执行（本应用拒绝 `WriteRejectedException`，或服务端返回的非连接类、非中止类错误）→ 补偿已完成步骤后原样抛出；不能确定 → 不补偿，抛 `CompensationFailedException(OUTCOME_UNKNOWN)`。
- **补偿语句同样是条件写入**，条件里带上本次写入的值（例如释放卡时 `WHERE card_id=? AND card_use_status=1 AND user_id=? AND card_use_time=?`），被别人改过就匹配 0 行而不是盲目覆盖。补偿不命中或抛异常 → 继续补偿其余步骤，最后抛 `CompensationFailedException(COMPENSATION_FAILED)`，列出未恢复的步骤。
- **上报**：两种 `CompensationFailedException` 都以 ERROR 记一行日志 `写入需人工对账：sequence=… step=… reason=… unresolved=[…] cause=<异常类型链>`，**不含参数与业务数据**；后续批次负责把它变成后台可见的对账项（B12/B14 起）。
- 只有最后一步可以没有补偿。

旧版对照（以卡密充值为例，`application/common/model/Card.php:123-158`）：旧版先 `find` 未使用的卡，再给用户加积分（第 142 行），最后把卡改成已使用（第 152 行），两步都不检查影响行数——两个并发请求可以同时通过 `find`，积分加两次。新版顺序是"认领卡（条件更新，匹配 1 行才继续）→ 加积分 → 记流水"，`WriteInfrastructureMySqlIT` ⑤-1 用 16 个用户同时用同一张卡验证：恰好 1 个成功、积分只加一次、流水只有一条，其余 15 个在"card"步骤得到 `WriteConflictException`。这是"只会更严、不会更松"的内部实现差异（路线图第 1.1 节），对外结果与旧版正常路径相同；具体业务（卡密、订单、提现）由 B10/B14 按此实现并写进各自文档。

#### 4.5 幂等约定

- 不新增幂等表或幂等列（零结构变更），幂等键只能用**已有列的状态**：卡 `card_use_status`、订单 `order_status`（支付回调 E05：`WHERE order_code=? AND order_status=0`，B24）、提现 `cash_status`、审核状态等。重复请求在认领步骤匹配 0 行，什么都不改。
- 匹配 0 行时调用方**重读一次**区分"已经由本请求或别人完成"（幂等成功，按旧版返回成功提示）与"不存在 / 条件不满足"（返回旧版对应的错误），由各批次按旧版提示语实现。
- 不能用认领表达的计数类写入（点击、顶踩）本身不要求幂等，与旧版一致；不做"同一 IP 去重"等旧版没有的限制。
- 新系统自己的重试：只在 `WriteConflictException` 后由业务决定是否重试；`CompensationFailedException` 一律不自动重试。

#### 4.6 写 Mapper 的用法（供 B08 起参照）

```text
backend/src/main/java/com/maccms/backend/<模块>/infrastructure/XxxWriteMapper.java   // @WriteMapper 接口，不标 @Mapper
backend/src/main/resources/mapper-write/<模块>/XxxWriteMapper.xml                     // 只有 insert/update/delete
WriteRegistry.REGISTERED 增加 new WritePermission("<模块>", "mac_xxx", WriteOperation.UPDATE, "B<nn>")
<模块>.infrastructure 的配置类：@Bean @ConditionalOnBean(WriteDatabase.class) XxxWriteMapper m(WriteDatabase db) { return db.mapper(XxxWriteMapper.class); }
```

可写数据源关闭时没有 `WriteDatabase` Bean，依赖写 Mapper 的功能应整体不注册或返回旧版的"功能关闭"提示，由各批次决定并写进文档。

### 5. 并发写测试支撑（`writedrill.ConcurrentWrites`）

`ConcurrentWrites.run(线程数, i -> 任务)`：全部线程就绪后由同一个起跑信号同时开始，收集每个线程的返回值或异常（默认 60 秒超时）。写库批次对每个"先查后写"、认领、条件扣减、条件插入都要有并发测试，断言恰好几个成功、最终值、没有越界（负余额、重复记录），在一次性库上运行（照 `WriteInfrastructureMySqlIT` 的建库、导入、指纹、删库流程）。本批的用例：

| 场景 | 线程 | 结果（本机实测） |
|---|---|---|
| 条件扣减：每次扣 10，用户只有 200 | 40 | 恰好 20 个匹配 1 行、20 个匹配 0 行，最终 0，不为负 |
| 原子计数 `vod_hits + 1` | 100 | 100 个都匹配 1 行，最终 +100 |
| 条件插入（同一组 user/mid/type/rid） | 16 × 5 轮 | 每轮恰好 1 行 |
| 用卡序列（认领 → 加积分 → 记流水） | 16 | 恰好 1 个成功，积分 +50 一次，流水 1 条，其余在认领步骤冲突 |

### 6. 回滚演练工具（测试侧 `writedrill`）

| 工具 | 作用 |
|---|---|
| `TableSnapshot` / `DatabaseSnapshot` | 按主键排序读出指定表的全部行（每列 `getObject`）。表名、列名、主键只从 information_schema 读取并校验为 `[A-Za-z0-9_]+`；没有主键的表拒绝（`install.sql` 的 26 张表都有主键）；单表超过 200 000 行拒绝 |
| `SnapshotDiff` | 两份快照按主键逐行比对：新增、删除、变更（列出列名，`byte[]` 按内容比较）。表结构在两份快照之间变化即报错。`summary()` 只输出表名、行数、主键与列名，不输出列值 |
| `DmlRestore.plan` | 生成把当前数据恢复成快照的参数化语句：先 `DELETE` 快照之后新增的行，再 `UPDATE` 变更行的变更列，最后 `INSERT` 补回被删除的行（带原主键）。每条语句都经 `WriteStatement` 校验 |
| `DmlRestore.apply` | 只在当前库名形如 `maccms_it_<14 位时间>_<8 位十六进制>` 时执行，其他库（包括未选库的连接）一律拒绝；每条语句必须恰好匹配 1 行，否则停止 |

- **只用 DML**：不使用、也不需要 `TRUNCATE`、`ALTER`、重建表或导入；`WriteGuardTest.drillToolsUseOnlyDml` 用 B02 的结构语句规则扫描工具源码。
- **已知边界**：`AUTO_INCREMENT` 计数器不会回退（回退需要 `ALTER TABLE`）。恢复后再插入的行 id 更大，不影响行数据与对账；指纹本来就不含 `AUTO_INCREMENT`（B02）。`WriteInfrastructureMySqlIT` ⑧ 断言恢复前后 `mac_plog` 的 `AUTO_INCREMENT` 相同（删除新增行不改变计数器）。
- **前提**：取快照与恢复时没有其他写入（演练在一次性库上；整体切换时在停止写入之后，见 13 文档）。工具不加锁。
- **演练结果**（`WriteInfrastructureMySqlIT` ⑧，本机）：S0 之后各步写入共涉及 `mac_user` 2 行、`mac_card` 2 行、`mac_vod` 2 行变更，`mac_plog` 新增 1 行、`mac_ulog` 新增 5 行（其余步骤已被补偿或被拒，没有留下差异）；`DmlRestore` 执行 12 条语句；再取快照与 S0 的 diff 为空；表结构指纹与导入 `install.sql` 时一致。
- **B21 / 整体切换时的扩展**（本批不做）：生产规模的表（如 `mac_vod` 上百万行）不能整表放进内存，需要改成按主键分段、落到本地文件的快照与 diff；`apply` 的库名限制要放宽到"经确认的切换目标库"并加二次确认。届时在 B21 中实现并演练。

### 7. 验证记录（2026-10-16，本机）

| 命令 | 结果 |
|---|---|
| `./mvnw verify` | BUILD SUCCESS，1447 个测试，0 失败、0 错误、0 跳过。比 B02 交付时（1415）多 32 个：`WriteGuardTest` 7、`WriteSequenceTest` 8、`WriteRegistryTest` 5、`WriteDrillToolsTest` 5、`WriteDataSourceConfigurationTest` 4、`WriteStatementTest` 3；`ModuleBoundaryTest`、`ReadOnlyGuardrailsTest` 只在原有测试方法里加了断言，个数不变 |
| `./mvnw verify -Pmysql-it`（MySQL 5.7.44，`sql_mode=STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION`，本机管理账号，一次性库） | BUILD SUCCESS：单元 1447 个；集成 18 个（原有 8 个 + `WriteInfrastructureMySqlIT` 10 个）。各 IT 创建的 `maccms_it_*` 库均已删除并确认不存在（结束后查询 `maccms\_it\_%` 为 0）；没有连接 `maccms_dev` |

`WriteInfrastructureMySqlIT` 各步（按执行顺序）：① 默认不创建可写数据源，只读池写入被拒、会话 `tx_read_only=1`；② 打开可写数据源时正式登记表为空，演练写入被拒、数据不变；③ 错误账号与密码不出现在异常与日志中（约 5 秒，等连接超时）；④ 第 5 节表格中的并发用例；⑤ 用卡序列：并发只成功一次、服务端拒绝时补偿回原状、补偿失败时上报 `COMPENSATION_FAILED`；⑥ 未登记的表、操作、模块被拒；⑦ MyISAM 回滚不生效；⑧ 快照 → 逐行 diff → `DmlRestore`（12 条）→ diff 为空 → 表结构指纹不变 → `AUTO_INCREMENT` 不回退 → 未选库的连接上拒绝恢复。

### 8. 与旧版的差异

| 项 | 旧版 | 新版（本批） |
|---|---|---|
| 写库方式 | ThinkPHP 模型随处写库，多为先读后写，不检查影响行数 | 只经可写数据源与登记过的写 Mapper；数值变化用原子表达式，前置条件写进 WHERE，检查匹配行数（本批只建机制，没有业务写入） |
| 多表写入 | 无顺序约定、失败不回退（例如卡密先加积分后改卡状态） | 认领 → 转移 → 记录，失败按相反顺序条件补偿，补偿失败或结果不确定时上报人工对账 |
| 并发重复提交 | 可能重复生效（卡密、回调等） | 认领步骤保证只生效一次；属于"只会更严"的内部差异，正常路径结果相同 |
| 账号 | 一个账号读写 | 只读池与可写池分开配置；生产建议两个账号（本批未创建） |
| 结构语句 | 后台可执行 | 任何入口都不能执行（B02 护栏），恢复只用 DML |

### 9. 风险与遗留

- **静态护栏是文本扫描**：能被反射、动态拼接的类名等手法绕过；运行期拦截器按实际 SQL 补一道，代码评审仍要看。`WriteStatement` 不是完整的 SQL 解析器，只认上面三种形态，不认识的一律拒绝（宁可误拒）。
- **`WHERE` 只检查存在性**：`WHERE 1=1` 之类仍能整表写入。条件内容靠评审与并发测试。
- **补偿失败需要人工**：本批只有日志一行；后台可见的对账项在 B12/B14 起实现。
- **快照工具规模**：只适合演练（≤20 万行/表、全部在内存），生产规模见第 6 节，B21 实现。
- **严格模式依赖**：第 4.3 节第 2 条依赖 MySQL 严格模式（5.7 默认开启，Connector/J 也会补上）。生产库的 `sql_mode` 未核实（D9-b），B21 演练时核对。
- **写账号权限**需要数据库管理员在获批部署时创建，本批没有、也不能创建。

### 10. 回滚

本批是新增代码与文档，以一个合并提交进入 main，`git revert` 即可回到 B02 的 main（`dcd3598`）。可写数据源默认关闭、正式登记表为空，revert 前后应用对外行为相同（只读）；部署环境里如果设置过 `MACCMS_WRITE_*`，revert 后这些变量不再被读取，无需清理。不改表结构、不改旧 PHP、不建账号、不部署。
