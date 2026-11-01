import sys
P = 'docs/design/APPK-2/tech-design.md'
s = open(P, encoding='utf-8').read()
CONF = '`01a194e9-23e8-7682-8ae5-b7c5e19b0040`'

def rep(old, new):
    global s
    n = s.count(old)
    if n != 1:
        sys.exit(f'COUNT {n}: {old[:60]}')
    s = s.replace(old, new)

rep('## 13. 评审回应\n\n',
    '## 13. 评审回应\n\n'
    f'> 读法（v8，SUG-1）：下面各轮记录保留当时的原文。其中关于 OP-T4、OP-T5、OP-T6 的“待发起人决定 / 未关闭 / a、b 两列”是 v4～v7 当时的状态，已被发起人确认评论 {CONF}（OP-T4=a、OP-T5=a、OP-T6=b）取代；当前状态以元数据“已关闭决策”、§7.4、§10 与本节最后一轮为准。\n\n')

rep('\n## 14. 修订记录\n',
    '\nG1 第 2 轮（@架构评审-海外，对象 v7 `3da8b4b448ba4ef54051f2d54379461b667b0f08`，评论 `01a194fb-d97e-7a43-9e61-49aa483eaae6`，结论驳回；调度返工评论 `01a194fc-69ae-70d8-85a5-f952c94b95b5`）：\n\n'
    '| REV- | 处理 | 说明 | 修改位置 |\n'
    '| --- | --- | --- | --- |\n'
    f'| REV-13 已确认的 OP-T4/5/6 仍按待决与 a/b 双分支描述 | 已修改 | 以发起人确认评论 {CONF} 为唯一决策源（元数据新增“已关闭决策”行）。OP-T4=a：C-68 只剩“保留并移到 `ajax/pwd`”，ALIGN-4 ⑤ 去掉条件，V-30 只测保留限流。OP-T5=a：C-44 只剩 SEC-7 白名单（并入约束 ⑥），§4.8、§6.10、C-43 去掉 b 列与补充的 `Content-Type`，V-18 只测白名单。OP-T6=b：新增 §4.11 方案 E 并选定，C-75 改为常量三项策略、随 S3-14a 落地，撤销 v7 的过渡值、S3-14h 与“S3-14h 合入前不得发布”（RISK-T21 关闭），V-32 ② 断言三项原文、⑥ 改为各 PG 组收尾的浏览器核对。§7.4 条件门禁改为“已关闭决策”，S3-13b、S3-13c、S3-14c 不再受门禁；§10 三项记“已关闭”；§11 AC-17、BR-4 与 V-29 改为“单内引用确认评论、只实现所选答案”；§9 改为“改选须先由发起人另行确认、再出修订”；§6.16、§6.17、§12 同步 | 元数据；§3；§4.8；§4.11；§5 C-37、C-43、C-44、C-68、C-70、C-72、C-75；§6.3；§6.8 ALIGN-4、ALIGN-5 与“前台 CSP”段；§6.10；§6.16；§6.17；§7.4；§8.2 V-18、V-29、V-30、V-32；§9；§10；§11；§12 |\n'
    '| REV-14 删备份未纳入 `dbtool.lock` | 已修改 | 核对基线：`AdminDatabaseActions.java:60` 的 `del` → `DatabaseToolService.delete`（`DatabaseToolService.java:255-273`）列目录后逐个删除，不查锁与记录。v8 DBT-4：入参校验 → 取同一把 `dbtool.lock`（拿不到 → 拒绝、0 个删除）→ 锁内先做中断判定、再读运行记录 → `IMPORT` 待处理（含 `RECOVERED`）且时间戳等于 `source_backup`/`auto_backup` → 拒绝、0 个删除 → 否则在锁内按卷号升序删除（被杀留下的残缺备份必然缺第 1 卷，还原预检判“文件损坏”）。另把所有动作的顺序统一为“取锁 → 中断判定 → 状态检查 → 预检 → 写 `RUNNING`”，DBT-3 与 `recover` 读备份的预检移到锁内，消除预检与执行之间备份被删的窗口。V-31 新增 ⑰（锁忙 0 删除、升序删除）与 ⑱（绑定保护、非绑定可删、`recover` 与删除两进程同时 50 轮）；C-53、C-54、C-73、§9 写明回滚与清理规则 | §6.13 DBT-4、DBT-5 ②④ 与状态机；§5 C-53、C-54、C-73；§7.4 S3-19a/b；§8.2 V-31；§9；§10 RISK-T20；§11；§12 |\n'
    '| REV-15 中断状态分支不唯一、V-31 只测通用场景 | 已修改 | C-73 的“统一改为 `INTERRUPTED`”与状态机的例外合并为 DBT-5 ⑩ 与一张“进程中断后的唯一转移”表：DBT-1 → `DONE`/`INTERRUPTED`（不待处理，`WARN`）；DBT-2 → `INTERRUPTED`（待处理，只有“我已处理”且须勾选，`ERROR`）；DBT-3 新增 `phase`，`AUTO_BACKUP` 阶段 → `DONE`/`INTERRUPTED_BEFORE_WRITE` 并清理半成品（不待处理，`WARN`），`RESTORE` 阶段 → `INTERRUPTED`（待处理，`recover` 两目标 + “我已处理”，`ERROR`）；导出 → `DONE`/`INTERRUPTED` 并清理半成品（`WARN`）；`RECOVERING` → `RECOVERY_INTERRUPTED`（`ERROR`）。清理只删 `export_files` 点名的分卷与值相符的 `backup.lock`（RISK-T22）。页面加载经 `probe()` 不等待取锁完成判定，拿不到锁显示“执行中”、无按钮。状态机的中断行改为引用该表，`RECOVERING` 行去掉“进程退出”。V-31 ④ 限定为 DBT-2 例，新增 ⑪～⑯ 逐分支跨进程被杀断言（记录、按钮、日志、数据）与 ⑲ 判定幂等 | §6.13 DBT-5 ③④⑥⑨⑩、状态机与中断表；§5 C-53、C-54、C-73、C-74；§7.4；§8.2 V-31；§10 RISK-T22；§12 |\n'
    '| SUG-1 引用确认评论 | 采纳 | 元数据、§7.4、§10、§11 与本节开头都直接引用确认评论；本节开头说明历史记录中的“待确认”已被取代 | 元数据；§13 开头 |\n'
    '| REV-5 / REV-6（复审时未解决） | 已解决 | 随 REV-13：OP-T4=a、OP-T5=a 已关闭并收敛为唯一实现 | 同 REV-13 |\n'
    '| REV-10 / REV-11（复审时部分解决） | 已解决 | 随 REV-14（删备份入锁）与 REV-15（中断按操作类型唯一转移） | 同 REV-14、REV-15 |\n'
    '\n## 14. 修订记录\n')

s = s.rstrip('\n') + '\n' + ('| 2026-11-01 | v8 | @架构师-海外 | 按发起人确认（OP-T4=a、OP-T5=a、OP-T6=b）收敛为唯一实现：删除未选列，C-44 只剩 SEC-7 白名单，C-68 保留 `PasswordAttempts`，C-75 固定为三项策略并随 S3-14a 落地（§4.11 方案 E），撤销 S3-14h、过渡值与发布门禁（RISK-T21 关闭），条件门禁改为“已关闭决策”；删备份纳入 `dbtool.lock`、锁内保护绑定备份、按卷号升序删除，预检移到取锁之后；新增 DBT-5 ⑩ 与“进程中断后的唯一转移”表（`phase`、`export_files`、`backup_lock`、`probe()`、清理规则、页面按钮与日志级别），RISK-T22；V-18、V-29、V-30、V-32 改为单一答案，V-31 新增 ⑪～⑲ | REV-13、REV-14、REV-15、SUG-1 |\n')

open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok c')
