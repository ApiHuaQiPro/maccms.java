import re, sys
P = 'docs/design/APPK-2/tech-design.md'
s = open(P, encoding='utf-8').read()
CONF = '`01a194e9-23e8-7682-8ae5-b7c5e19b0040`'
CSPB = "`object-src 'none'; base-uri 'self'; frame-ancestors 'self'`"

def rep(old, new, count=1):
    global s
    n = s.count(old)
    if n != count:
        sys.exit(f'COUNT {n}!={count}: {old[:80]}')
    s = s.replace(old, new)

def line(prefix, new):
    global s
    lines = s.split('\n')
    idx = [i for i, l in enumerate(lines) if l.startswith(prefix)]
    if len(idx) != 1:
        sys.exit(f'LINE {len(idx)}: {prefix}')
    lines[idx[0]] = new
    s = '\n'.join(lines)

def sub(pattern, new):
    global s
    s2, n = re.subn(pattern, lambda m: new, s, flags=re.S)
    if n != 1:
        sys.exit(f'SUB {n}: {pattern[:60]}')
    s = s2

def cells(prefix, repl):
    """replace selected cells (by index) of a table row starting with prefix"""
    global s
    lines = s.split('\n')
    idx = [i for i, l in enumerate(lines) if l.startswith(prefix)]
    if len(idx) != 1:
        sys.exit(f'CELLS {len(idx)}: {prefix}')
    parts = lines[idx[0]].split(' | ')
    for k, v in repl.items():
        parts[k] = v
    lines[idx[0]] = ' | '.join(parts)
    s = '\n'.join(lines)

# ---------- §6.13 DBT-4
line('| DBT-4 |',
     '| DBT-4 | 其余 | 列表、列、批量替换保持 B15 现状。导出（v7）在 DBT-5 的锁内执行（拿不到锁或记录待处理 → 拒绝，提示同 `lock_check`）。**删备份 `delete`（v8，REV-14）**：基线调用链 `AdminDatabaseActions.java:60`（`del`）→ `DatabaseToolService.delete`（`DatabaseToolService.java:255-273`：列目录后逐个 `files.delete`，不查任何锁与记录）。v8 顺序：① 入参校验同 B15（`id` 为空 → `select_del_file`；备份目录不合法 → `BAD_BACKUP_PATH`），不取锁、不删文件；② `tryExclusiveLock(dbtool.lock)`，拿不到 → 拒绝“已有数据库操作在执行”，0 个文件被删；③ 锁内先做中断判定（DBT-5 ⑩），再读运行记录：记录是 `IMPORT` 且处于待处理（含 `RECOVERED`），并且要删的前缀（`Ymd-His-`，与 B15 同样由 `STAMP` 从 `id` 算出）等于 `source_backup` 或 `auto_backup` 的前缀 → 拒绝“该备份是未处理数据库操作的恢复点”，0 个文件被删；④ 否则在锁内按卷号升序删除各分卷（中途被杀留下的残缺备份必然缺第 1 卷，还原预检 ② (a) 判“文件损坏”，不会被当作完整备份还原），返回与 B15 相同（`del_ok`/`del_err`）；⑤ 不写运行记录（删除不进入状态机），日志一行 `INFO`（`op=DELETE`、时间戳、结果）。PHP `del` 同样不查锁；PHP 与 Java 后台不同时使用数据库工具（RISK-T20） |')
rep('| DBT-5 | 失败、并发与恢复（v6，REV-7；方案见 §4.12） | 见下表，适用于 DBT-1～DBT-3 |',
    '| DBT-5 | 失败、并发与恢复（v6，REV-7；方案见 §4.12） | 见下表，适用于 DBT-1～DBT-3；互斥（④）与中断判定（⑩）另适用于导出与删备份 |')

# ② ordering
rep('`recover` 的预检另见 ⑨。预检只读，不发任何写语句；',
    '`recover` 的预检另见 ⑨。**顺序（v8，REV-14）**：所有动作统一为“取锁（④）→ 中断判定（⑩）→ 状态检查（状态机）→ 预检 → 写 `RUNNING`”；DBT-3 与 `recover` 的预检要读备份文件，放在取锁之后，预检与执行之间备份不会被删除；预检不通过 → 释放锁、记录不变。预检只读，不发任何写语句；')
# ③
rep('预检通过后 `DatabaseRuns.begin` 先取锁（④），持锁写运行记录（`state=RUNNING`、操作类型、',
    '持锁（④）且预检通过后，`DatabaseRuns.begin` 写运行记录（`state=RUNNING`、操作类型 `op`、')
rep('DBT-3 接着做**还原前自动备份**：', 'DBT-3 先记 `phase=AUTO_BACKUP`，接着做**还原前自动备份**：')
rep('写完后计算各分卷 SHA-256，连同时间戳、表清单记入 `auto_backup`；这次内部导出属于本次运行，已在锁内，不再查 DBT-4 的锁。',
    '每新建一卷先把文件名记入 `export_files` 再创建；写完后计算各分卷 SHA-256，连同时间戳、表清单记入 `auto_backup`，然后把 `phase` 改为 `RESTORE` 并落盘，之后才发第一条 `DELETE`（所以 `phase=RESTORE` 时 `auto_backup` 必然完整，`phase=AUTO_BACKUP` 时必然一条写语句都没发）；这次内部导出属于本次运行，已在锁内，不再查 DBT-4 的锁，与普通导出一样写 `backup.lock` 并把写入值记入 `backup_lock`。')
# ④
rep('(a) **工具内互斥（v7，REV-10）**：DBT-1、DBT-2、DBT-3、`recover`、`acknowledge` 与导出都先经',
    '(a) **工具内互斥（v7，REV-10；v8 补删备份与页面判定，REV-14）**：DBT-1、DBT-2、DBT-3、`recover`、`acknowledge`、导出、删备份 `delete` 与页面加载的 `probe()` 都先经')
rep('后来的持锁者读到 `RUNNING`/`RECOVERING` 即可确定原占用者已不在，改记 `INTERRUPTED`/`RECOVERY_INTERRUPTED`（V-31 ③）。',
    '后来的持锁者读到 `RUNNING`/`RECOVERING` 即可确定原占用者已不在，按 ⑩ 的中断结果表唯一改写（V-31 ④、⑪～⑯）。')
# ⑥
rep('页面只读显示，记录为 `RUNNING`/`RECOVERING` 时显示“执行中”（显示不判断占用者死活，由下一个持锁者改写）。',
    '页面加载时先调用 `probe()` 做一次不等待的取锁（v8）：拿不到锁 → 显示“执行中”，不出任何操作按钮；拿到锁 → 先完成 ⑩ 的中断判定、释放锁，再按记录显示提示条与 ⑩ 表“页面”列的按钮；记录已不是 `RUNNING`/`RECOVERING` 时 `probe()` 不改写记录、不写日志。')
rep('`acknowledge` 为 `INFO`（含 `accepted_as_is`）；字段 `run`、`op`、`state`、',
    '`acknowledge` 为 `INFO`（含 `accepted_as_is`）；中断判定按 ⑩ 表“日志”列（DBT-1、导出、DBT-3 备份阶段 `WARN`，清理失败升为 `ERROR`；其余 `ERROR`）；字段 `run`、`op`、`phase`、`state`、')
rep('`recovery_attempt`、`duration_ms`；', '`recovery_attempt`、`duration_ms`、`cleanup`；')
# ⑨
rep('进程中途退出 → 下一个持锁者记 `RECOVERY_INTERRUPTED` |', '进程中途退出 → 下一个持锁者按 ⑩ 记 `RECOVERY_INTERRUPTED` |')
rep('| ⑨ `recover` 动作（v7，REV-11） |', '| ⑨ `recover` 动作（v7，REV-11） |')
# add ⑩ row after ⑨
lines = s.split('\n')
i9 = [i for i, l in enumerate(lines) if l.startswith('| ⑨ `recover` 动作')]
assert len(i9) == 1
lines.insert(i9[0] + 1, '| ⑩ 进程中断后的唯一转移（v8，REV-15） | 中断判定是每个取锁入口（④ (a) 的八个）的第一步：读到 `RUNNING`/`RECOVERING` → 按下方“进程中断后的唯一转移”表由 `op`、`phase`、`state` 唯一确定新状态、清理动作、页面按钮与日志级别，原子替换写回（`interrupted` 记判定时间、判定前状态与阶段、清理结果）后才继续本次动作；每条记录只判定一次，判定后不再是 `RUNNING`/`RECOVERING`。清理只在锁内、只删记录点名的文件：`export_files` 列出的分卷，以及内容与 `backup_lock` 相同的 `backup.lock`（内容不同说明是 PHP 后台写的，不删，记 `LOCK_KEPT`）；不按通配删除。清理失败不阻止状态转移，记 `FAILED` 与残留文件名，`backup.lock` 残留时之后的导出与还原按 B15 `lock_check` 被拒，页面提示部署方核对后手动删除。`acknowledge` 只做一次原子替换、`delete` 不写记录，二者被杀都不留下 `RUNNING` |')
s = '\n'.join(lines)

# state machine rows
line('| `RUNNING` | 进程退出 | 下一个持锁者发现 |',
     '| `RUNNING`/`RECOVERING` | 进程退出 | 下一个持锁者（任一取锁入口，含页面加载）发现 | 按下方“进程中断后的唯一转移”表，由 `op` 与 `phase` 唯一确定（v8，REV-15） |')
rep('| `RECOVERING` | 成功 / 确定未生效 / 结果不确定 / 进程退出 | — | `RECOVERED` / `RECOVERY_FAILED` / `RECOVERY_UNKNOWN` / `RECOVERY_INTERRUPTED` |',
    '| `RECOVERING` | 成功 / 确定未生效 / 结果不确定 | — | `RECOVERED` / `RECOVERY_FAILED` / `RECOVERY_UNKNOWN`（进程退出见下表） |')
rep('| 其他待处理状态（含 DBT-2 的 `UNKNOWN`/`INTERRUPTED`） |', '| 其他待处理状态（含 DBT-2 的 `UNKNOWN`/`INTERRUPTED`；全部 `RECOVERY_*`） |')
line('| 任一待处理状态或 `RECOVERED` |',
     '| 任一待处理状态或 `RECOVERED` | DBT-1/DBT-2/DBT-3/导出 `begin` | — | 拒绝，状态不变，0 条语句 |\n'
     '| 任一状态（含无记录） | 删备份 `delete`（v8，REV-14） | 拿到锁；中断判定已完成 | 记录为 `IMPORT` 的待处理（含 `RECOVERED`）且要删的前缀等于 `source_backup` 或 `auto_backup` → 拒绝，0 个文件被删；否则按卷号升序删除；记录不变 |')
rep('| `RUNNING`/`RECOVERING` 且锁被持有 | 任何动作 | — | 拒绝“已有数据库操作在执行” |',
    '| `RUNNING`/`RECOVERING` 且锁被持有 | 任何动作（含 `delete`）；页面加载 | — | 动作拒绝“已有数据库操作在执行”，0 条语句、0 个文件被删；页面显示“执行中”、无操作按钮 |')

# interruption table after "待处理" definition paragraph
rep('`acknowledge` 是离开待处理的唯一出口；恢复不会自动确认，管理员核对后才确认。\n',
    '`acknowledge` 是离开待处理的唯一出口；恢复不会自动确认，管理员核对后才确认。\n\n'
    '**进程中断后的唯一转移（v8，REV-15；DBT-5 ⑩，所有改写都在锁内完成）**\n\n'
    '| 被中断的记录（`op` / `phase` / `state`） | 判定后状态 | 清理 | 待处理 | 页面（提示条与按钮） | 日志 | 理由 | 验证 |\n'
    '| --- | --- | --- | --- | --- | --- | --- | --- |\n'
    '| `OPTIMIZE`/`REPAIR`（DBT-1）/ — / `RUNNING` | `DONE`，`outcome=INTERRUPTED`，保留 `tables_done` | 无 | 否 | 灰色说明一行“上次优化/修复在第 k 张表中断，可重新执行”；无 `recover`、“我已处理”；普通操作可用 | `WARN` | 表维护语句由 MySQL 自行完成或放弃，不改行数据的取值；重跑即可（与 PHP 相同） | V-31 ⑪ |\n'
    '| `SQL`（DBT-2）/ — / `RUNNING` | `INTERRUPTED` | 无 | 是 | 红色提示条“该语句可能已生效，也可能未生效”，附 ⑤ 的核对步骤；按钮只有“我已处理”，须勾选“接受当前数据”；无 `recover`（没有自动备份） | `ERROR` | 无法区分“发送前被杀”与“执行中被杀”，按结果不确定处理 | V-31 ⑫ |\n'
    '| `IMPORT`（DBT-3）/ `AUTO_BACKUP` / `RUNNING` | `DONE`，`outcome=INTERRUPTED_BEFORE_WRITE` | 删除 `export_files` 列出的半成品分卷；`backup.lock` 内容等于 `backup_lock` 时删除 | 否 | 灰色说明“上次还原在备份阶段中断，数据未改动，可重新发起”；无恢复按钮；普通操作可用 | `WARN`（清理失败 `ERROR`） | 还原语句只在 `phase=RESTORE` 落盘后发送，此阶段数据未动；半成品分卷不是可用备份 | V-31 ⑬ |\n'
    '| `IMPORT`（DBT-3）/ `RESTORE` / `RUNNING` | `INTERRUPTED` | 无（自动备份是恢复点，保留） | 是 | 红色提示条（已完成表、当前表、自动备份时间戳）；按钮 `recover`（“还原前自动备份”“重跑原备份”）与“我已处理”（须勾选“接受当前数据”）；普通操作与删除绑定备份被拒 | `ERROR` | 数据处于部分还原状态 | V-31 ⑭ |\n'
    '| `EXPORT`（导出）/ — / `RUNNING` | `DONE`，`outcome=INTERRUPTED` | 同上一行备份阶段的清理 | 否 | 灰色说明“上次导出中断，不完整的分卷已删除，请重新导出”；清理为 `LOCK_KEPT`/`FAILED` 时改为列出残留文件并提示部署方手动处理；无恢复按钮 | `WARN`（清理失败 `ERROR`） | 导出只读数据库，不改数据；半成品分卷不是可用备份 | V-31 ⑮ |\n'
    '| `IMPORT`（DBT-3）/ `RESTORE` / `RECOVERING` | `RECOVERY_INTERRUPTED`，`recovery.attempt` 不变，最近结果记 `INTERRUPTED` | 无 | 是 | 同 `RESTORE`/`RUNNING` 一行：`recover`（两个目标）与“我已处理”（须勾选） | `ERROR` | 恢复本身中途停止，数据仍是部分状态；`recover` 幂等，可再次执行 | V-31 ⑯ |\n')

# ---------- §7.4
cells('| S3-13b |', {4: 'G1（OP-T5=a 已关闭，v8）', 5: 'V-18 单测部分 |'})
cells('| S3-13c |', {4: 'S3-13b（OP-T5=a 已关闭，v8）', 5: 'V-18 接口用例部分 |'})
rep('+ ALIGN-4 + C-68（**须 OP-T4 已关闭**）+ `PlaybackPages`', '+ ALIGN-4 + C-68（OP-T4=a）+ `PlaybackPages`')
rep('；a 同时落地 C-75，取值为基线严格策略）', '；a 同时落地 C-75，取值为 OP-T6=b 的三项策略）')
rep('b～g 另需 S3-14a（共享边界）；c 另需 OP-T4 已关闭 | 本组样本 V-19 子集通过；本组涉及的 V-32 ①～⑤ 断言；c 另需 V-30 |',
    'b～g 另需 S3-14a（共享边界） | 本组样本 V-19 子集通过；本组涉及的 V-32 ①～⑤ 断言与 ⑥ 的本组页面（a：跳转页；c：播放页；f：支付页）；c 另需 V-30 |')
line('| S3-14h |', '| S3-14h | （v8 撤销）v7 的“前台 CSP 落定”并入 S3-14a：OP-T6 已按 b 关闭，C-75 随 S3-14a 落地即为最终值 | — | — | — | — |')
rep('| C-53（DBT-1、DBT-2、DBT-4 导出入锁、DBT-5 公共部分）、C-54（`sql.jte`、提示条、`acknowledge`）、C-55、C-73、C-74（`SqlStatementsTest`、`DatabaseRunsTest`、`DatabaseRunsLockIT`、`DatabaseRunsLockProbe`） | G1（不依赖模板，不依赖任何 OP-T） | V-23 ①～④ 与集成测试中 DBT-1/DBT-2 部分、V-31 ①～④ |',
    '| C-53（DBT-1、DBT-2、DBT-4 导出与删备份入锁、DBT-5 公共部分与 ⑩ 中断判定）、C-54（`sql.jte`、提示条、`acknowledge`、页面 `probe()`）、C-55、C-73、C-74（`SqlStatementsTest`、`DatabaseRunsTest`、`DatabaseRunsLockIT`、`DatabaseRunsLockProbe`、`DatabaseToolMySqlIT` 的 DBT-1/DBT-2/导出部分） | G1（不依赖模板，不依赖任何 OP-T） | V-23 ①～④ 与集成测试中 DBT-1/DBT-2 部分、V-31 ①～④、⑪、⑫、⑮、⑰、⑲（DBT-1/DBT-2/导出部分） |')
rep('| 数据库工具：还原备份（预检、自动备份与哈希、逐表状态、`recover`、删备份保护） | 后端 | C-53（DBT-3、DBT-4 删备份保护）、C-54（`import.jte`、`recover`）、C-74（`DatabaseToolMySqlIT`） | S3-19a | V-23 ⑤、V-31 ⑤～⑩ |',
    '| 数据库工具：还原备份（预检、自动备份与哈希、阶段与逐表状态、`recover`、绑定备份保护） | 后端 | C-53（DBT-3、DBT-4 绑定备份保护、⑩ 的 `IMPORT`/`RECOVERING` 分支）、C-54（`import.jte`、`recover`）、C-74（`DatabaseToolMySqlIT` 其余部分） | S3-19a | V-23 ⑤、V-31 ⑤～⑩、⑬、⑭、⑯、⑱、⑲（DBT-3/`RECOVERING` 部分） |')
sub(r'\*\*条件门禁（OP-T4、OP-T5、OP-T6）\*\*：.*?所以 S3-14c、S3-14h 之外的 PG 组、S3-15 与 S3-10 之前的模板线都不受这三项影响。',
    f'**已关闭决策（v8，REV-13；取代 v4～v7 的条件门禁）**：OP-T4=a、OP-T5=a、OP-T6=b 已由发起人在 Issue 确认（评论 {CONF}，本设计三项的唯一决策源）。S3-13b、S3-13c、S3-14c 不再等待决定，按 G1 与表中依赖派单；S3-14h 撤销（并入 S3-14a），deploy 分支不再有“前台 CSP 未落定不得发布”的门禁。建单时 S3-13b、S3-13c、S3-14a、S3-14c 单内引用该确认评论；实现与用例只有一种写法（C-43/C-44 的 SEC-7 白名单、C-68 保留 `PasswordAttempts`、C-75 三项策略），评审按此核对（V-29）。将来要改选，须先由发起人在 Issue 另行确认、再出设计修订，不在子任务里直接改。S3-14 的 PG 组只依赖 S3-13a（内置视图），不依赖 S3-13b/c：页面渲染只经 `PhpTemplateRenderer` 用现有 `read` 读模板，不经 `/template/**`；S3-14 各组的 V-19 子集只含页面样本，样本清单里若有 `/template/**` 地址，归 S3-13c 收尾时核对；S3-15 也不依赖 S3-13b/c（C-42 不再注册 C-43）。')

# ---------- §8.2
line('| V-18 |',
     '| V-18 | DEC-8、SEC-7（OP-T5=a） | 单测 + 接口用例 | 单测 `LegacySiteFilesTemplateAssetTest`（C-44，S3-13b）：`template/` 下的目录符号链接与文件符号链接，各自指向站点根内的 `application/extra/maccms.php` 和站点根外的一个文件，均拒绝；最终文件本身是符号链接（即使目标在 `template/` 内）拒绝；`template/default_pc` 目录本身、`template/default_pc/a.php`、`template/default_pc/x.PHP` 拒绝；不存在的文件拒绝且不创建目录；`template/default_pc/html/index/index.html`、`template/default_pc/info.ini`、`template/default_pc/css/x.txt` 抛 `FORBIDDEN`；`template/default_pc/css/style.css` 返回字节等于文件（符号链接用例的写法同现有 `LegacyConfigDirectoryTest`）。评审另核：C-43 不含子目录或扩展名判断，C-44 没有运行时开关。接口用例（C-43，S3-13c）：`/template/default_pc/css/style.css` 200 且字节等于文件；`/template/../application/extra/maccms.php`、`/template/default_pc/css/%2e%2e/%2e%2e/%2e%2e/application/extra/maccms.php` 404（不出站点根）；`/template/default_wap/ads/x.js` 404 且不创建目录；`/template/default_pc/html/index/index.html`、`/template/default_pc/info.ini`、`/template/default_pc/css/%2e%2e/info.ini` 均 404；`template/` 下任一 `.php` 404 | G2 |')
line('| V-29 |',
     f'| V-29 | AC-17 | 人工核对 | 每个子任务派单前与收尾时，单内引用的 OP-/OP-T 均已关闭；S3-13b、S3-13c、S3-14a、S3-14c 单内引用确认评论 {CONF}（OP-T5=a、OP-T6=b、OP-T4=a）；实现与用例只含所选答案：C-44 有且只有 SEC-7 白名单、无运行时开关；C-43 的 `Content-Type` 表只含白名单扩展名；`SiteSecurityHeaders.FRONT_POLICY` 原文等于三项策略；`PasswordAttempts` 仍在且只被 `ajax/pwd` 调用 | 派单前 + 每单收尾 |')
line('| V-30 |',
     '| V-30 | AC-5（`ajax/pwd`）、ALIGN-4、SEC-9（OP-T4=a） | 接口用例 + 对照 | 1001（缺参）、1002（本会话已通过后再验）、1003（同一会话 5 秒内第二次，含第一次被拒的情况）、1012/1013/1014/1022（密码错）、1（通过）与 PHP 同请求序列的 JSON 逐字节相同；每次换新会话、对同一内容在 60 秒内第 11 次请求得到 1003 且不查库（`general_log` 无该内容的 `SELECT`），HTTP 200；该超限样本按 SEC-9 判 `排除-SEC-9`，不进逐字节比较 | G2 + 对照 |')
rep('| V-32 | REV-8（`SiteHtmlRenderer` 调用方迁移）、REV-9（OP-T6） |', '| V-32 | REV-8（`SiteHtmlRenderer` 调用方迁移）、REV-9、REV-13（OP-T6=b） |')
rep('CSP 头等于 `SiteSecurityHeaders.frontPolicy()`：S3-14a～g 时断言等于基线 `SiteHtmlRenderer.CONTENT_SECURITY_POLICY` 原文，S3-14h 后按 OP-T6 所选列断言（a 无该头；b 恰为三项）；',
    f'CSP 头等于 `SiteSecurityHeaders.frontPolicy()`，且 `SitePageResponsesTest` 断言其原文恰为 {CSPB}（OP-T6=b），不含 `script-src`；')
rep('⑥S3-14h（OP-T6 已关闭后）：真实浏览器中', '⑥各 PG 组收尾时核对本组页面（S3-14a 跳转页、S3-14c 播放页、S3-14f 支付页）：真实浏览器中')
rep('；选 b 时另核对官方模板页面没有', '；另核对官方模板页面没有')

# V-31
rep('| V-31 | DBT-5（REV-7、REV-10、REV-11） |', '| V-31 | DBT-5（REV-7、REV-10、REV-11、REV-14、REV-15） |')
sub(r'④\*\*崩溃识别\*\*：子进程拿锁、写 `RUNNING` 后挂起，父进程 `begin` → 拒绝“已有数据库操作在执行”、记录仍为 `RUNNING`；随后 `destroyForcibly\(\)` 子进程，父进程再 `begin` → 记录被改为 `INTERRUPTED`（待处理）、普通操作被拒、`acknowledge` 可用；',
    '④**持锁拒绝与被杀识别**（以 DBT-2 为例）：子进程拿锁、写 `RUNNING` 后挂起，父进程 `begin`、`delete`、页面加载 → 前两者拒绝“已有数据库操作在执行”（0 条语句、0 个文件被删），页面显示“执行中”且无操作按钮，记录仍为 `RUNNING`；随后 `destroyForcibly()` 子进程，父进程再 `begin` → 记录被改为 `INTERRUPTED`（待处理）、本次 `begin` 被拒、`acknowledge` 可用（其他操作类型的分支见 ⑪～⑯）；')
rep('重复还原同一份正确备份两次，26 张表 `CHECKSUM TABLE` 两次相同（幂等） | G2 |',
    '重复还原同一份正确备份两次，26 张表 `CHECKSUM TABLE` 两次相同（幂等）。'
    '**按操作类型与阶段的被杀断言（v8，REV-15；`DatabaseToolMySqlIT` 的跨进程部分：子进程 `DatabaseRunsLockProbe` 连接同一一次性库执行指定操作，测试侧 `DataSource` 包装在指定点写出 `PAUSED` 后挂起，父进程 `destroyForcibly()`；父进程随后只打开一次数据库工具页面，断言记录、页面按钮、`maccms.admin.dbtool` 日志行与数据，均对照 DBT-5 “进程中断后的唯一转移”表）**：'
    '⑪DBT-1 在第 2 张表的 `OPTIMIZE` 前被杀 → `DONE`、`outcome=INTERRUPTED`、`tables_done=1`，不进入待处理；页面一行灰色说明、无 `recover`/“我已处理”，随后 DBT-1 可直接再执行；一行 `WARN`；'
    '⑫DBT-2 分别在“写 `RUNNING` 后、发送前”与“语句执行中（`SELECT SLEEP` 包装挂起）”被杀 → 两次都为 `INTERRUPTED`（待处理）；页面只有“我已处理”且未勾选“接受当前数据”时被拒，没有 `recover`；DBT-1/DBT-2/DBT-3/导出 `begin` 被拒、0 条语句；一行 `ERROR`；'
    '⑬DBT-3 在 `phase=AUTO_BACKUP`、第 1 个自动备份分卷写到一半时被杀 → `DONE`、`outcome=INTERRUPTED_BEFORE_WRITE`；`export_files` 列出的分卷与值相符的 `backup.lock` 被删除、其他备份文件逐个比对不变；被还原表的 `CHECKSUM TABLE` 与开始前相同，`general_log` 中该运行无 `DELETE`/`INSERT`；不进入待处理，页面无恢复按钮，同一备份可立即重新发起还原；一行 `WARN`（含清理的文件名）；'
    '⑭DBT-3 在 `phase=RESTORE`、B 表 `INSERT` 执行中被杀 → `INTERRUPTED`（待处理），`auto_backup` 各卷哈希完整；页面有 `recover`（两个目标）与“我已处理”（须勾选）；普通操作与删除绑定备份被拒；`recover(AUTO_BACKUP)` 后 A、B、C 的 `CHECKSUM TABLE` 等于还原前；一行 `ERROR`；'
    '⑮导出在第 2 张表第 1 批 `SELECT` 后被杀 → `DONE`、`outcome=INTERRUPTED`；半成品分卷与值相符的 `backup.lock` 被删除，备份列表中无该时间戳，之后导出成功；另一轮在被杀后把 `backup.lock` 改写为其他值（模拟 PHP 后台）→ 不删除它，`interrupted.cleanup=LOCK_KEPT`，页面列出残留文件，之后导出按 `lock_check` 被拒；一行 `WARN`；'
    '⑯`recover` 在 `RECOVERING`、B 表执行中被杀 → `RECOVERY_INTERRUPTED`（待处理），`recovery.attempt` 不变；页面有 `recover`（两个目标）与“我已处理”（须勾选）；再次 `recover(AUTO_BACKUP)` → `RECOVERED`、`attempt` 加 1、A、B、C 等于还原前；一行 `ERROR`。'
    '**删备份（v8，REV-14）**：⑰子进程持锁挂起时，父进程删除任一备份（含非绑定）→ 拒绝“已有数据库操作在执行”，目录文件逐个比对不变（0 个删除）；没有待处理记录时删除成功、返回与 B15 相同；单测断言按卷号升序删除，删到第 2 卷前被杀后留下的备份在还原预检 (a) 判“文件损坏”；'
    '⑱存在 DBT-3 待处理记录（`FAILED`、`RECOVERED` 各一轮）时，删除其 `source_backup` 或 `auto_backup` 时间戳 → 拒绝“该备份是未处理数据库操作的恢复点”、0 个删除；删除另一个非绑定备份 → 成功；两个子进程同时发起 `recover(SOURCE)` 与删除原备份，重复 50 轮：每轮或删除被拒（锁忙或绑定保护）、或 `recover` 被拒（锁忙），绑定备份的分卷与哈希始终完整，`recover` 若执行则 A、B、C 等于备份内容；'
    '⑲**页面判定幂等**：⑪～⑯ 每个场景中第二次打开页面不再改写记录、不再写日志，按钮与第一次相同 | G2 |')

# ---------- §9
line('| S3-14h（前台 CSP 落定，v7） |', '| C-75（前台 CSP，v8 随 S3-14a） | 随 S3-14a 回退（S3-14h 已撤销，没有单独提交）；只需改策略时（发起人另行改选后），单独提交改 C-75 一处、可单独 `git revert`，不涉及模板与数据 | 随 S3-14a |')
rep('回退 a 后导出恢复为 B15 的 `backup.lock` 检查 | 独立 |',
    '回退 a 后导出恢复为 B15 的 `backup.lock` 检查、`delete` 恢复为 B15 的不取锁删除（v8：所以回退前必须先处理完待处理记录，否则绑定备份失去保护）；v8 的中断清理只删记录点名的半成品分卷与值相符的 `backup.lock`，它们不是可用备份，回退不需要恢复 | 独立 |')
line('| OP-T4 / OP-T5 的答案事后改变 |',
     '| OP-T4 / OP-T5 / OP-T6 的决定事后改变（v8） | 现行实现只有已确认的一种（OP-T4=a、OP-T5=a、OP-T6=b）。改选须先由发起人在 Issue 确认、再出设计修订；届时改动只在 C-44（放行范围）与 C-43（`Content-Type` 表）、C-68（`PasswordAttempts` 调用点）、C-75（CSP 取值），各自单独提交、可单独 `git revert`，不涉及数据 | 独立 |')
line('| OP-T6 的答案事后改变（v7） |', '')
s = s.replace('\n\n| S3-20（安装） |', '\n| S3-20（安装） |')
rep('两者中途失败都不自动补偿，状态与恢复步骤见 DBT-5 ③⑥⑦⑨ 与状态机。',
    '两者中途失败都不自动补偿，状态与恢复步骤见 DBT-5 ③⑥⑦⑨⑩ 与状态机。删备份本身不可逆（B15 现状，与 PHP 相同）；v8 只加保护：拿不到锁不删，待处理记录绑定的恢复点不能删（DBT-4）。')

# ---------- §10
line('| RISK-T18 |',
     f'| RISK-T18 | 前台 CSP 从基线严格策略放宽为三项后，XSS 纵深防护减弱 | S3-14a 起（OP-T6=b） | 发起人已确认 OP-T6=b（评论 {CONF}）；保留 `object-src`、`base-uri`、`frame-ancestors` 三项；模板变量的转义按 PHP 模板原样（BR-11）；保留 `nosniff`；改动只在 C-75 一处（§9） | 不限制脚本；与 PHP（不发 CSP）相比多三项防护；发起人已接受 |')
rep('Java 持锁后仍检查 PHP 的 `backup.lock`；V-31 ②③ 只在本机文件系统上验证 |', 'Java 持锁后仍检查 PHP 的 `backup.lock`；删备份也在同一把锁内（v8）；V-31 ②③ 只在本机文件系统上验证 |')
line('| RISK-T21 |',
     '| RISK-T21 | （v8 关闭）v7 的“过渡期严格 CSP 拦截模板脚本、S3-14h 合入前不得发布”随 OP-T6=b 关闭不再存在：C-75 自 S3-14a 起即为三项策略，S3-14h 撤销 | — | — | 无 |\n'
     '| RISK-T22（v8） | 中断清理误删文件 | 导出或还原前自动备份被杀后的中断判定 | 只删 `export_files` 点名、由本次运行创建的分卷（每卷先记名再创建），`backup.lock` 只在内容等于 `backup_lock` 时删除；不按通配删除；清理结果写入记录与日志（DBT-5 ⑩）；V-31 ⑬⑮ | 低 |')
cells('| OP-T4 |', {2: f'**已关闭（v8）：a**——发起人确认评论 {CONF}（调度核验 `01a194e9-f7f0-7bf0-a6cc-09af877c86bd`）。保留 `PasswordAttempts`，超限返回 PHP 已有的 1003，按 SEC-9 记为已确认的安全差异（ALIGN-4 ⑤、C-68、V-30）；ALIGN-4 ①～④⑥ 按 PHP 恢复。未选的 b（删除 `PasswordAttempts`）不出现在实现与验证中', 3: '否（S3-14c 按 G1 与依赖派单）', 4: '—', 5: '— |'})
cells('| OP-T5 |', {2: f'**已关闭（v8）：a**——发起人确认评论 {CONF}。接受 SEC-7：`/template/**` 只给白名单静态资源，按已确认的安全差异记录，相关地址不进页面比较样本（§6.10、C-43、C-44、V-18）。未选的 b（按 PHP 原样返回 `.html`/`.ini`）不出现在实现与验证中', 3: '否（S3-13b、S3-13c 按 G1 与依赖派单）', 4: '—', 5: '— |'})
cells('| OP-T6（v7，REV-9） |', {2: f'**已关闭（v8）：b**——发起人确认评论 {CONF}。前台只发 {CSPB}，不限制脚本，保留防插件对象、防 `<base>` 改写、防被第三方页面嵌套三项（`frame-ancestors` 使站点不能被其他网站用 iframe 嵌入）；C-75 随 S3-14a 落地即为此值，S3-14h 与发布门禁撤销（§4.11 方案 E、§6.8、V-32 ②⑥）。未选的 a（不发 CSP）不出现在实现与验证中', 3: '否', 4: '—', 5: '— |'})

# ---------- §11
cells('| AC-17 |', {1: f'每个 S3 子任务引用的 OP-T 在派单前已关闭。v8：OP-T4=a、OP-T5=a、OP-T6=b 已关闭（确认评论 {CONF}），S3-13b、S3-13c、S3-14a、S3-14c 单内引用该评论、只实现所选答案；S3-14h 撤销；当前没有未关闭的 OP-T（§7.4、§10）', 2: 'C-43、C-44、C-68、C-75'})
rep('SEC-7、SEC-9 是待确认的条件差异，不计入六项；SEC-8 与 PHP 一致；前台 CSP 去留不属于六项，是待决 OP-T6 |',
    'SEC-7、SEC-9 是发起人已确认的差异（OP-T5=a、OP-T4=a，v8），不计入六项；SEC-8 与 PHP 一致；前台 CSP 不属于六项，按 OP-T6=b 只保留三项（C-75） |')
rep('DBT-3 只有可恢复性保护与“站点须关闭”前置（DBT-5 ⑧） |', 'DBT-3 只有可恢复性保护与“站点须关闭”前置（DBT-5 ⑧）。v8：删备份入锁并保护绑定备份（DBT-4）；进程中断按操作类型与阶段唯一转移（DBT-5 ⑩） |')

# ---------- §12
rep('CSP：OP-T6 关闭前不降级（C-75）', 'CSP：按发起人确认的 OP-T6=b 只保留三项不拦脚本的策略（C-75）')
rep('V-31 ⑤～⑧ 按状态机验证 |\n| 完整设计：PHP 行为描述有源码出处（v6）', 'V-31 ⑤～⑧ 按状态机验证。v8：删备份纳入同一把锁、在锁内保护绑定备份，预检移到取锁之后（REV-14）；进程中断按 `op`/`phase` 唯一转移，并统一页面按钮与日志级别（REV-15），V-31 ⑪～⑲ 逐分支验证 |\n| 完整设计：PHP 行为描述有源码出处（v6）')
rep('`DatabaseToolService.export` 的调用方只有 `AdminDatabaseActions`（改为在锁内执行，不改其入参与返回）；',
    '`DatabaseToolService.export` 的调用方只有 `AdminDatabaseActions`（改为在锁内执行，不改其入参与返回）；v8：`DatabaseToolService.delete` 的调用方只有 `AdminDatabaseActions.java:60`（`del` 分支，改为在锁内执行，不改其入参与返回结构）；')
sub(r'\| 与 PRD 无矛盾（完整设计） \| 有三项待发起人确认，已列 OP-T4、OP-T5（v6 起只含 SEC-7）、OP-T6（v7，前台 CSP），各自阻塞 S3-14c、S3-13b/S3-13c、S3-14h 的派单与合入（§7\.4 条件门禁），OP-T6 另阻塞 deploy 分支发布，其余子任务不受影响；',
    f'| 与 PRD 无矛盾（完整设计） | 是（v8）：OP-T4、OP-T5、OP-T6 已由发起人确认（评论 {CONF}：a、a、b），设计只保留所选答案，没有未关闭的待决项；')
rep('；前台 CSP 去留不再由设计决定（OP-T6） | §6.8、§6.13、§10 |', '；前台 CSP 按 OP-T6=b（C-75） | §6.8、§6.13、§10 |')

open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok b')
