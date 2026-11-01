import sys
P = 'docs/design/APPK-2/tech-design.md'
s = open(P, encoding='utf-8', newline='').read()

def rep(old, new, count=1):
    global s
    n = s.count(old)
    if n != count:
        sys.exit(f'count {n} != {count} for: {old[:90]}')
    s = s.replace(old, new)

def replace_line(prefix, new):
    global s
    lines = s.split('\n')
    idx = [i for i, l in enumerate(lines) if l.startswith(prefix)]
    if len(idx) != 1:
        sys.exit(f'line prefix hits {len(idx)}: {prefix[:60]}')
    lines[idx[0]] = new
    s = '\n'.join(lines)

# ---------- §5.2 ----------
rep('会员页不再生成 nonce/CSP（DEC-11） | FR-5、FR-8 | 前端 |',
    '会员页不再生成 nonce；CSP 头一律由 C-75 提供（DEC-11，值按 OP-T6；过渡期为基线严格策略） | FR-5、FR-8 | 前端 |')
rep('删除前 `git grep -n \'SiteHtmlRenderer\\|CONTENT_SECURITY_POLICY\\|PlayerFramePolicy\' -- src/main src/test` 必须无结果（V-32）',
    '删除前 V-32 ① 的检索必须无结果（v7 更正：检索限定在 `site` 包，`admin`/`install` 各自的 `CONTENT_SECURITY_POLICY` 不在本期范围）')
rep('头部按 §4.11 方案 C，状态码取 `RenderedPage.status`，不发 CSP；',
    '头部按 §4.11 方案 D，状态码取 `RenderedPage.status`，CSP 头取 C-75 `SiteSecurityHeaders.frontPolicy()`（有值才发，v7）；')
rep('五个 CSP 断言改为“响应没有 `Content-Security-Policy` 头、`Content-Type` 等于 `text/html;charset=UTF-8`”',
    '五个 CSP 断言改为“`Content-Security-Policy` 头等于 `SiteSecurityHeaders.frontPolicy()`（为空时断言没有该头）、`Content-Type` 等于 `text/html;charset=UTF-8`”，这样 S3-14h 改值时不必再改这些测试（v7）')
rep('新增 `SitePageResponsesTest`：头部集合、`Vary`、`no-store`、长度；',
    '新增 `SitePageResponsesTest`：头部集合、`Vary`、`no-store`、长度、CSP 取自 C-75；')

replace_line('| C-53 |',
    '| C-53 | `B/admin/application/DatabaseToolService.java`；新增 `B/admin/application/SqlStatements.java`（`mac_parse_sql` 移植，只供 DBT-3；DBT-2 的整段跳过判定、多语句检测与 AC-11 分类器） | 修改 / 新增 | DBT-1 `optimize`/`repair`；DBT-2 `sql` 按 PHP 整段语义（v7，DEC-13：`{pre}` 替换、PHP 跳过判定、多语句发送前拒绝、AC-11 分类、整段一次发送，见 §6.13 DBT-2）；DBT-3 `import` 只还原数据（v7：还原表集合含只有 `DROP/CREATE` 的空表）与 `recover`；DBT-4 导出改为在 C-73 的锁内执行；DBT-5 的预检、逐条状态、失败分类、还原前自动备份与哈希；删除 `REFUSED` 常量的四项用途 | FR-9；AC-11；DBT-5 | 后端 |')
replace_line('| C-54 |',
    '| C-54 | `B/admin/web/AdminDatabaseActions.java`；`B/admin/infrastructure/DatabaseToolMapper.java` 与 `src/main/resources/mapper/admin/DatabaseToolMapper.xml`（或写 Mapper 对应 XML）；`src/main/jte-admin/admin/pages/database/import.jte`（修改）、`sql.jte`（新增，按 PHP `admin@database/sql` 视图移植） | 修改 / 新增 | L63-65 的拒绝改为调用 C-53；语句执行经可写数据源，`DELETE`/`INSERT` 按表登记 `WriteRegistry`；页面显示 DBT-5 的运行结果（还原：总数、已执行、失败表与序号、结果分类；执行 SQL：成功 / 未生效 / 结果不确定）与“待处理”提示条；提示条上的按钮只按 DBT-5 状态机出现：`recover`（目标“还原前自动备份”或“重跑原备份”，仅 DBT-3）、`acknowledge`（“我已处理”，未恢复的 DBT-3 记录须勾选“接受当前数据”）；新增两个后台动作 `database/recover`、`database/acknowledge`（POST + 表单令牌，权限同 `database/import`） | FR-9；DBT-5 | 后端 |')
replace_line('| C-73 |',
    '| C-73 | 新增 `B/admin/application/DatabaseRuns.java`、`DatabaseRun.java`（运行记录、互斥、状态机、失败分类）；修改 `B/legacyconfig/application/LegacySiteFiles.java`（新增 `tryExclusiveLock(String relative)`） | 新增 / 修改 | DBT-5 ③④⑥ 与状态机（v7，REV-10/REV-11）。**锁**：`LegacySiteFiles.tryExclusiveLock` 按现有路径校验解析站点根相对路径 `application/data/backup/database/dbtool.lock`（不存在则创建，永不删除），`FileChannel.open(CREATE, WRITE)` 后 `tryLock()` 取独占锁，拿不到返回空；同一 JVM 内另加按规范化路径登记的 `ReentrantLock.tryLock()`（`FileLock` 只在进程之间互斥，同进程重叠加锁会抛 `OverlappingFileLockException`，按“占用”处理）；返回的句柄 `close()` 时先放 OS 锁再放进程内锁。**所有权**：持锁者就是占用者；锁随持有进程退出由操作系统释放，所以不需要租约或心跳。**规则**：运行记录 `dbtool-run.json` 只在持锁时读后写（经 `LegacySiteFiles.replace` 原子替换），页面显示可以不持锁只读；持锁者读到 `RUNNING`/`RECOVERING`，说明上一个占用者已不在（否则拿不到锁），先把它改为 `INTERRUPTED`/`RECOVERY_INTERRUPTED` 并记 `detected_at`，再做本次判断；持锁后另查 `backup.lock`（只为兼容 PHP 后台，存在则拒绝）；导出（DBT-4）在锁内执行，仍按 B15 写删 `backup.lock`。记录字段：`run`（UUID）、`op`、`state`、`admin_id`、`owner`（`host`、`pid`、进程启动时间，只作审计显示）、`started_at`、`total`、`executed`、`failed_index`、`outcome`、`sqlstate`、`error_code`、`tables_done`、`table_current`、`inserted_current`、`source_backup`（原备份时间戳）、`auto_backup`（时间戳、分卷名与各卷 SHA-256、表清单）、`recovery`（目标、次数、最近一次的结果与时间）、`ack`（处理人、时间、`accepted_as_is`）。失败分类按 12 文档第 4.3 节第 3、4 条（与 `WriteSequence` 的判定一致，复用其 SQLState/错误码表，不复制）；只记语句序号、类别、表名、SQLState 与错误码，不记 SQL 原文与数据值 | FR-9；DBT-5；REV-7、REV-10、REV-11 | 后端 |')
replace_line('| C-74 |',
    '| C-74 | 新增 `T/admin/application/SqlStatementsTest.java`、`DatabaseRunsTest.java`、`T/admin/DatabaseRunsLockIT.java`（跨进程）、`T/admin/DatabaseRunsLockProbe.java`（测试侧子进程入口，只在 `src/test`）、`T/admin/DatabaseToolMySqlIT.java` | 新增 | V-23、V-31 的单测与集成测试（整段语义样本、中途失败、结果不确定、两个进程同时起跑的互斥、持锁进程被杀后的中断识别、恢复状态机、重复还原幂等）；故障注入用测试侧的 `DataSource` 包装（在第 n 条语句抛指定 SQLState），不在产品代码里留测试钩子 | AC-11；DBT-5 | 后端 |\n'
    '| C-75 | 新增 `B/site/web/SiteSecurityHeaders.java` | 新增 | 前台 HTML 页 CSP 的唯一来源（v7，REV-9）：包内 `final` 类，`static Optional<String> frontPolicy()`。S3-14a 落地时取值为基线 `SiteHtmlRenderer.CONTENT_SECURITY_POLICY`（`SiteHtmlRenderer.java:30-31`）原文，所有前台 HTML 页共用；会员 nonce、`PlayerFramePolicy`、网关 `form-action` 变体不再生成。S3-14h 按 OP-T6 所选列改值：a → `Optional.empty()`；b → `object-src \'none\'; base-uri \'self\'; frame-ancestors \'self\'`。其他类不得出现 `Content-Security-Policy` 字面量（V-32 ① 检索） | REV-9；OP-T6 | 前端 |')

# ---------- §6.3 interface ----------
replace_line('| `DatabaseRuns.begin/step/finish/acknowledge`',
    '| `DatabaseRuns.begin/step/finish/recover/acknowledge`（v6；v7 改为 OS 文件锁与状态机，`admin.application`） | DBT-5 的锁、运行记录与状态机；只由 `DatabaseToolService` 调用；`LegacySiteFiles.tryExclusiveLock` 只由它调用 | 拿不到锁 → “已有数据库操作在执行”；记录处于待处理状态 → “上次操作未处理”（只放行 `recover`/`acknowledge`）；两者都不发任何语句 |')

# ---------- §6.8 ----------
rep('HTML 页只经 C-70 `SitePageResponses` 输出，类型常量只用 `SiteRequests.HTML_UTF8`；前台不发 CSP（DEC-11） | CMP-3 ② |',
    'HTML 页只经 C-70 `SitePageResponses` 输出，类型常量只用 `SiteRequests.HTML_UTF8`；CSP 只由 C-75 提供，过渡期为基线严格策略，最终值按 OP-T6（DEC-11，v7） | CMP-3 ② |')
rep('会员页不再生成 nonce；支付宝下单页不再改写 CSP 的 `form-action`（页面内容按 PHP 对应输出，PRE-2 基线） | S3-14a～g 各组 |',
    '会员页不再生成 nonce；支付宝下单页不再改写 CSP 的 `form-action`（页面内容按 PHP 对应输出，PRE-2 基线；CSP 统一取 C-75） | S3-14a～g 各组 |')
rep('| 断言 CSP 等于常量 | 改为断言无 CSP 头、`Content-Type` 正确（C-72），或随 C-46 整类删除 |',
    '| 断言 CSP 等于常量 | 改为断言 CSP 等于 C-75 的值（为空时无该头）、`Content-Type` 正确（C-72），或随 C-46 整类删除 |')
rep('S3-15 删除 `SiteHtmlRenderer` 之前，上表其余各行必须都已合入；S3-15 的门禁是 V-32 的检索结果为空。',
    'S3-15 删除 `SiteHtmlRenderer` 之前，上表其余各行必须都已合入；S3-15 的门禁是 V-32 ① 的检索结果为空。\n\n'
    '**前台 CSP（v7，REV-9，OP-T6）**：C-75 是唯一来源。S3-14a 起到 S3-14h 合入前，所有前台 HTML 页发基线严格策略（`script-src \'none\'` 等），安全水平不低于基线；代价是真实浏览器里模板内联脚本（如 `public/jump.html:23` 倒计时）、`player.js` 的 `document.write`、支付网关自动提交表单不能运行，页面对照（CMP-3 只比状态码、`Content-Type`、`Location` 与正文）不受影响。S3-14h 按 OP-T6 所选列改 C-75 一处，并做 V-32 ⑥ 的浏览器核对；S3-14h 未合入时 deploy 分支不得发布（RISK-T21）。')

# ---------- §6.13 DBT-2 / DBT-3 / DBT-4 ----------
replace_line('| DBT-2 | 执行 SQL |',
    '| DBT-2 | 执行 SQL（v7 改回 PHP 整段语义，DEC-13，§4.13） | 输入当作**一整段**处理，不切分、不逐条执行。① 按 PHP `trim`；为空 → 不执行、返回“执行成功”（PHP 同）；把 `{pre}` 替换为表前缀（`Database.php:246-249`）。② **PHP 跳过判定，逐字照搬**：副本转小写、`/\\*.*?\\*/`（跨行）换空格、`--` 至行尾换空格、压缩空白并 `trim`；以 `select` 开头，或含 `outfile`、`dumpfile`、`load_file` 之一 → 不发送、返回“执行成功”（`Database.php:252-263`，提示文字也同 PHP）。所以 `SELECT 1; INSERT …` 整段不执行，含文件读写关键字的输入整段不执行。③ **多语句**：按词法扫描（识别单引号、双引号、反引号字符串及其中的 `\\` 转义，`--`/`#`/`/* */` 注释），第一个不在字符串与注释里的 `;` 之后若还有非空白、非注释内容 → 不发送，提示“一次只能执行一条语句”。PHP 把整段交给数据库，数据库拒绝多语句，结果同为一条都不执行，只有提示不同。末尾单个 `;` 允许并原样发送（数据库同样接受）。④ **AC-11 结构保护**（OP-6 已确认的差异）：同一词法下去掉注释（含 `#`；出现 `/*!` 版本注释视为不可识别，拒绝），取首个关键字，只允许 `INSERT`、`UPDATE`、`DELETE`（`SELECT` 已在 ② 跳过；其他如 `REPLACE`、`WITH`、`(`、`DROP`、`TRUNCATE`、`ALTER` 一律拒绝），不通过 → 不发送，提示“结构类操作请由部署方在库外完成”。⑤ 通过后在 DBT-5 的锁内把整段（`{pre}` 已替换、文本不再改动）用一次 `Statement.execute` 发出，连接 `allowMultiQueries=false`、自动提交、不重试（PHP `break_reconnect=false`）；成功 → “执行成功”（PHP `run_ok`），不展示结果集。⑥ 失败按 DBT-5 ③ 分类，提示不回显 SQL 原文 |')
replace_line('| DBT-3 | 还原备份 |',
    '| DBT-3 | 还原备份 | 读取备份文件（只限 `application/data/backup/database/` 下由本系统或 PHP 导出的分卷），按 `mac_parse_sql` 切分；`DROP TABLE`、`CREATE TABLE` 以及其他非 DML 语句**跳过不执行**并计数显示；**还原表集合**（v7 补全）= 备份里 `DROP TABLE`、`CREATE TABLE`、`INSERT INTO` 出现过的全部表（必须在 26 张白名单内）——PHP 导出对 0 行的表只写删表与建表，PHP 还原后该表为空，所以这些表也要清空；对集合中每张表先执行一次 `DELETE FROM <表>`，再执行该表在备份中的全部 `INSERT`（可能为 0 条）（“只还原数据”：结果与 PHP 删表重建后插入的数据相同，结构不动） |')
replace_line('| DBT-4 | 其余 |',
    '| DBT-4 | 其余 | 列表、列、导出、删备份、批量替换保持 B15 现状；v7：导出在 DBT-5 的锁内执行（拿不到锁或记录待处理 → 拒绝，提示同 `lock_check`）；删备份时若要删的时间戳是待处理记录绑定的原备份或自动备份 → 拒绝，提示“该备份是未处理数据库操作的恢复点” |')
open(P, 'w', encoding='utf-8', newline='').write(s)
print('ok')
