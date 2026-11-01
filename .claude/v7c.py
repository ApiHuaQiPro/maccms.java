import sys
P = 'docs/design/APPK-2/tech-design.md'
s = open(P, encoding='utf-8', newline='').read()

def replace_line(prefix, new):
    global s
    lines = s.split('\n')
    idx = [i for i, l in enumerate(lines) if l.startswith(prefix)]
    if len(idx) != 1:
        sys.exit(f'line prefix hits {len(idx)}: {prefix[:60]}')
    lines[idx[0]] = new
    s = '\n'.join(lines)

def rep(old, new, count=1):
    global s
    n = s.count(old)
    if n != count:
        sys.exit(f'count {n} != {count} for: {old[:90]}')
    s = s.replace(old, new)

replace_line('| ① 事务能力 |',
    '| ① 事务能力 | 25 张 MyISAM 表没有事务，`mac_vod_search`（InnoDB）也不开事务（护栏禁止，§1.3）。所以 DBT-3 **没有整批原子性**：执行单位是一条语句；单行语句要么生效要么不生效，多行语句（`DELETE FROM 表`、多值 `INSERT`、条件命中多行的 `UPDATE/DELETE`）中途出错会留下部分结果。DBT-2（v7）只发一条语句，同样可能在多行语句中途出错时部分生效。不做自动补偿，也不自动重试（补偿会覆盖并发写入，12 文档第 4.3 节第 4 条） |')
replace_line('| ② 整批预检',
    '| ② 整批预检（发送任何语句之前全部完成，任一不通过 → 整批拒绝、发出语句数为 0） | DBT-1：表名都在 26 张白名单内。DBT-2：DBT-2 ①～④（v7）。DBT-3：(a) 分卷齐全（同 PHP `count($list) === $last[0]`，否则“文件损坏”）；(b) 全部分卷可读、`.gz` 可解压；(c) 全部语句切分完成，还原表集合（DBT-3）都在 26 张白名单内（否则整批拒绝）；(d) **站点已关闭**（`site.site_status` 为 0，经 `LegacyConfigService` 读）——否则提示“还原数据前请先在系统设置中关闭站点”，原因见 ④；(e) 磁盘可写（自动备份目录存在且可写）。`recover` 的预检另见 ⑨。预检只读，不发任何写语句；预检不能发现的只有运行期错误（数据不合列定义、主键冲突、连接中断） |')
replace_line('| ③ 执行与逐条状态 |',
    '| ③ 执行与逐条状态 | 预检通过后 `DatabaseRuns.begin` 先取锁（④），持锁写运行记录（`state=RUNNING`、操作类型、管理员编号、开始时间、语句总数、DBT-3 的原备份时间戳与表集合）。DBT-3 接着做**还原前自动备份**：调用 B15 现有导出逻辑，把还原表集合导出到同一备份目录（文件名同 PHP 规则；若同一秒的分卷已存在则顺延到下一个不冲突的秒，不覆盖），写完后计算各分卷 SHA-256，连同时间戳、表清单记入 `auto_backup`；这次内部导出属于本次运行，已在锁内，不再查 DBT-4 的锁。导出失败 → `state=DONE`、`outcome=FAILED_BEFORE_WRITE`（已执行 0 条，数据未动，不进入待处理）。DBT-3 按顺序每条语句单独一次 JDBC 调用，每张表的顺序是 `DELETE FROM 表` → 该表全部 `INSERT`，每完成一张表更新一次记录（`tables_done`、`table_current`、`inserted_current`）。DBT-2 只有一条语句。**第一处失败立即停止**，其后的语句不执行，按 12 文档第 4.3 节分类：**确定未生效**——SQLState 以 `42` 开头（语法、对象不存在、无权限，数据库在执行前就拒绝），或服务端返回的非连接类、非中止类错误且出错的是单行语句（DBT-3 的单值 `INSERT`；DBT-2 中只有一个值组的 `INSERT … VALUES`）；**结果不确定**——SQLState `08xxx` 或客户端错误码 2000～2999、1028/1053/1317/1927/3024、`SQLTimeoutException`、非 SQL 异常，或出错的是多行语句（`DELETE FROM 表`、多值 `INSERT`、`UPDATE`/`DELETE`、`INSERT … SELECT`）。各操作的落点见下方状态机；全部成功 → `DONE`、`outcome=OK` |')
replace_line('| ④ 并发协调 |',
    '| ④ 并发协调 | (a) **工具内互斥（v7，REV-10）**：DBT-1、DBT-2、DBT-3、`recover`、`acknowledge` 与导出都先经 `LegacySiteFiles.tryExclusiveLock("application/data/backup/database/dbtool.lock")` 取独占 OS 文件锁（C-73），拿不到 → 立即拒绝“已有数据库操作在执行”，不等待、不发任何语句；拿到后才读运行记录、判断状态、写 `RUNNING`，直到本次操作写完最终记录才释放（`try-with-resources`）。“检查 → 占用”在同一把锁内完成，两个进程同时起跑时只有一个能拿到锁（V-31 ②）。锁的所有权就是持有它的进程；进程崩溃或被杀时由操作系统释放，后来的持锁者读到 `RUNNING`/`RECOVERING` 即可确定原占用者已不在，改记 `INTERRUPTED`/`RECOVERY_INTERRUPTED`（V-31 ③）。锁文件只创建、不删除（删除锁文件会让两个进程锁住不同的文件）。前提：站点根在本机文件系统，多个 Java 进程共用同一站点根时互斥成立；网络文件系统（NFS/SMB）上的锁语义不可靠，部署说明写明不支持（RISK-T20）。PHP 后台不参与这把锁，Java 持锁后仍检查 `backup.lock` 以避开正在导出的 PHP 后台，但这一步不是原子的，PHP 与 Java 后台不得同时对同一库使用数据库工具（RISK-T20）。(b) 与前台写入：DBT-3 与 `recover` 要求站点关闭（② d、⑨），否则前台在 `DELETE` 与 `INSERT` 之间写入的行会让 `INSERT` 主键冲突而中途失败，或在还原后被覆盖。(c) 与其他管理员、定时任务：本期不加库级锁（`it_app` 没有 `LOCK TABLES` 权限，加应用层全局锁要改所有写入口，超出范围）；页面在还原与恢复的确认步骤写明“请确认没有其他管理员在编辑内容、`api.php/timming` 定时任务已停”，属运维约定（RISK-T19）。(d) DBT-1：MySQL 对 MyISAM 的 `OPTIMIZE/REPAIR` 持表锁，期间该表写入排队，与 PHP 相同；逐表执行，一张表失败不影响其他表，结果逐表显示，DBT-1 总是以 `DONE` 结束、不进入待处理 |')
replace_line('| ⑤ 重试与幂等 |',
    '| ⑤ 重试与幂等 | DBT-1：幂等，可直接重试。DBT-2：一般不幂等（无主键的 `INSERT`、`x=x+1`）；“确定未生效”时数据未动，输入框保留原文，管理员修正后重新提交；“结果不确定”时进入待处理，管理员先在数据库客户端或后台对应列表核对该语句是否已生效（DBT-2 本身按 PHP 不执行 `SELECT`，不能用它查询），再 `acknowledge`，之后决定是否重新提交。DBT-3：每张表“先清空、再插入备份中的全部行”，在没有并发写入时整体幂等，所以失败后的两种恢复（重跑原备份、用自动备份退回）都可以反复执行，结果相同（V-31 ⑤）。任何情况都不自动重试 |')
replace_line('| ⑥ 审计与告警 |',
    '| ⑥ 审计与告警 | (a) 运行记录文件 `application/data/backup/database/dbtool-run.json`（C-73）：处于待处理状态（下方状态机）时，数据库工具各页面顶部显示红色提示条：操作类型、开始时间、管理员、失败序号与分类、DBT-3 的已完成表/当前表/自动备份时间戳、恢复次数与最近结果，以及 ⑦ 的恢复步骤和状态机允许的按钮；页面只读显示，记录为 `RUNNING`/`RECOVERING` 时显示“执行中”（显示不判断占用者死活，由下一个持锁者改写）。(b) 应用日志：logger `maccms.admin.dbtool`，开始与结束各一行 `INFO`，`FAILED`/`RECOVERY_FAILED` 为 `WARN`，`UNKNOWN`/`INTERRUPTED`/`RECOVERY_UNKNOWN`/`RECOVERY_INTERRUPTED` 为 `ERROR`，`acknowledge` 为 `INFO`（含 `accepted_as_is`）；字段 `run`、`op`、`state`、`admin_id`、`total`、`executed`、`failed_index`、`outcome`、`sqlstate`、`error_code`、`tables`、`backup_file`、`recovery_attempt`、`duration_ms`；**不写 SQL 原文、数据值、库连接串**（同 `SafeExceptionLog`）。部署方按 `ERROR` 级别告警 |')
replace_line('| ⑦ 管理员恢复步骤',
    '| ⑦ 管理员恢复步骤（写进提示条与页面帮助） | DBT-3 待处理（`FAILED`/`UNKNOWN`/`INTERRUPTED`/`RECOVERY_*`）：1. 保持站点关闭、停定时任务；2. 查明原因（提示条给出失败表与 SQLState）；3a. 原因已排除且要完成这次还原 → 点 `recover`，目标选“重跑原备份”；3b. 要回到还原前的数据 → 点 `recover`，目标选“还原前自动备份”；两种都只能用记录里绑定的文件，不能另选；4. 恢复成功（`RECOVERED`）后核对行数，点“我已处理”，再开站；5. 恢复本身又失败 → 仍是待处理，排除原因后可再次 `recover`（幂等）；若数据库工具已无法完成（例如磁盘或连接持续故障），由部署方用 `mysql` 客户端导入该次自动备份的分卷（它是 PHP 格式的完整导出，含删表与建表），完成后在页面勾选“接受当前数据”并点“我已处理”。DBT-2 `UNKNOWN`/`INTERRUPTED`：按 ⑤ 核对后点“我已处理”；DBT-2 没有自动备份，页面提示“执行前请先导出将被修改的表”，需要回退时用管理员事先导出的备份走一次普通还原 |')
replace_line('| ⑧ 与 PHP 的差异 |',
    '| ⑧ 与 PHP 的差异 | DBT-2（v7）：执行与否、执行什么与 PHP 相同（§4.13），差异只有 AC-11 结构语句拒绝（OP-6 已确认）、多语句与失败时的提示文字、运行记录与锁；成功路径与失败路径的数据结果都与 PHP 相同。DBT-3：PHP 还原没有预检站点状态、没有自动备份、没有运行记录与恢复动作，失败只提示“还原失败”；这些都是在“只还原数据”（OP-6）之上为可恢复性增加的保护，不改变成功路径的数据结果，其中“还原前站点须关闭”是新增前置条件。FR-9 不做页面对照，相应附表 C 行由 T-FR12 按本表判定 |\n'
    '| ⑨ `recover` 动作（v7，REV-11） | 只对 DBT-3 的待处理记录开放。入参只有 `run`（须等于当前记录的 `run`）与目标（`AUTO_BACKUP` 或 `SOURCE`），不接受文件名。预检（任一不通过 → 拒绝、记录不变、0 条语句）：持锁；站点已关闭；目标为 `AUTO_BACKUP` 时 `auto_backup` 存在且各分卷 SHA-256 与记录相符；目标为 `SOURCE` 时原备份分卷齐全可读；DBT-3 预检 (a)～(c)。执行：记录改 `RECOVERING`（保留原失败信息，`recovery.attempt` 加 1），按 DBT-3 同一流程逐表还原，**不再做自动备份**（此时数据已是部分状态，再备份会覆盖真正的回退点）。结果：成功 → `RECOVERED`；失败按 ③ 分类 → `RECOVERY_FAILED` 或 `RECOVERY_UNKNOWN`；进程中途退出 → 下一个持锁者记 `RECOVERY_INTERRUPTED` |')
rep('`SchemaGuardTest` 的登记例外增加 DBT-1、DBT-2 的实现类',
    '**运行记录状态机（v7，REV-11；所有转换都在锁内完成）**\n\n'
    '| 当前状态 | 动作 / 事件 | 前提 | 新状态 |\n'
    '| --- | --- | --- | --- |\n'
    '| 无记录 或 `DONE` | DBT-1/DBT-2/DBT-3/导出 `begin` | 拿到锁；`backup.lock` 不存在 | `RUNNING` |\n'
    '| `RUNNING` | 全部成功 | — | `DONE`（`outcome=OK`） |\n'
    '| `RUNNING`（DBT-1） | 有表失败 | — | `DONE`（逐表结果写入 `outcome`） |\n'
    '| `RUNNING`（DBT-2） | 确定未生效 | — | `DONE`（`outcome=FAILED`，数据未动，不进入待处理） |\n'
    '| `RUNNING`（DBT-3） | 自动备份失败 | — | `DONE`（`outcome=FAILED_BEFORE_WRITE`，数据未动） |\n'
    '| `RUNNING`（DBT-3） | 确定未生效 | — | `FAILED`（待处理：前 n-1 条已生效） |\n'
    '| `RUNNING`（DBT-2、DBT-3） | 结果不确定 | — | `UNKNOWN`（待处理） |\n'
    '| `RUNNING`（导出） | 失败 | — | `DONE`（`outcome=FAILED`，B15 原样提示） |\n'
    '| `RUNNING` | 进程退出 | 下一个持锁者发现 | `INTERRUPTED`（待处理；导出与 DBT-1 被中断时直接记 `DONE`、`outcome=INTERRUPTED`，因为它们不改数据或幂等） |\n'
    '| `FAILED`/`UNKNOWN`/`INTERRUPTED`/`RECOVERY_FAILED`/`RECOVERY_UNKNOWN`/`RECOVERY_INTERRUPTED`（DBT-3） | `recover(run, 目标)` | ⑨ 的预检 | `RECOVERING` |\n'
    '| `RECOVERING` | 成功 / 确定未生效 / 结果不确定 / 进程退出 | — | `RECOVERED` / `RECOVERY_FAILED` / `RECOVERY_UNKNOWN` / `RECOVERY_INTERRUPTED` |\n'
    '| `RECOVERED` | `acknowledge(run)` | 拿到锁 | `DONE`（`ack.accepted_as_is=false`） |\n'
    '| 其他待处理状态（含 DBT-2 的 `UNKNOWN`/`INTERRUPTED`） | `acknowledge(run, 接受当前数据=true)` | 拿到锁；页面勾选“接受当前数据” | `DONE`（`ack.accepted_as_is=true`，日志记录） |\n'
    '| 任一待处理状态或 `RECOVERED` | DBT-1/DBT-2/DBT-3/导出 `begin`；删除记录绑定的备份 | — | 拒绝，状态不变，0 条语句 |\n'
    '| `RUNNING`/`RECOVERING` 且锁被持有 | 任何动作 | — | 拒绝“已有数据库操作在执行” |\n\n'
    '“待处理”= `FAILED`、`UNKNOWN`、`INTERRUPTED`、`RECOVERY_FAILED`、`RECOVERY_UNKNOWN`、`RECOVERY_INTERRUPTED`、`RECOVERED`。`acknowledge` 是离开待处理的唯一出口；恢复不会自动确认，管理员核对后才确认。\n\n'
    '`SchemaGuardTest` 的登记例外增加 DBT-1、DBT-2 的实现类')
open(P, 'w', encoding='utf-8', newline='').write(s)
print('ok')
