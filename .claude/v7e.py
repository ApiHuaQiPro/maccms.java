import sys
P = 'docs/design/APPK-2/tech-design.md'
s = open(P, encoding='utf-8', newline='').read()

def rep(old, new, count=1):
    global s
    n = s.count(old)
    if n != count:
        sys.exit(f'count {n} != {count} for: {old[:90]}')
    s = s.replace(old, new)

# §12
rep('`DatabaseToolService.REFUSED` 只在 `AdminDatabaseActions`；',
    '`DatabaseToolService.REFUSED` 只在 `AdminDatabaseActions`；v7 新增的 `LegacySiteFiles.tryExclusiveLock` 是新方法，不改现有方法签名，调用方只有 C-73；`DatabaseToolService.export` 的调用方只有 `AdminDatabaseActions`（改为在锁内执行，不改其入参与返回）；')
rep('注入：DBT-2 先分类后执行、表名来自白名单；',
    '注入：DBT-2 先按 PHP 跳过判定、再拒绝多语句与结构语句，之后整段一次发送（v7），表名来自白名单；CSP：OP-T6 关闭前不降级（C-75）；')
rep('数据层：DBT-3 有还原前自动备份，DBT-2 要求先导出（DBT-5） |',
    '数据层：DBT-3 有还原前自动备份并经 `recover` 退回，DBT-2 要求先导出（DBT-5）；回退 S3-19 前先处理完待处理记录（§9） |')
rep('并有中途失败、恢复、幂等、并发、连接中断的验证（V-31） |',
    '并有中途失败、恢复、幂等、并发、连接中断的验证（V-31）。v7：v6 的“查记录后原子替换”不是互斥（REV-10），改为跨进程独占 OS 文件锁，锁内读写记录，崩溃由“拿到锁时记录仍为 RUNNING”识别，V-31 ②③④ 验证同时起跑与被杀；v6 的恢复步骤与锁状态冲突（REV-11），改为状态机与专用 `recover`，V-31 ⑤～⑧ 按状态机验证 |')
rep('有两项待发起人确认，已列 OP-T4、OP-T5（v6 起只含 SEC-7），各自阻塞 S3-14c、S3-13b/S3-13c 的派单与合入（§7.4 条件门禁），其余子任务不受影响；',
    '有三项待发起人确认，已列 OP-T4、OP-T5（v6 起只含 SEC-7）、OP-T6（v7，前台 CSP），各自阻塞 S3-14c、S3-13b/S3-13c、S3-14h 的派单与合入（§7.4 条件门禁），OP-T6 另阻塞 deploy 分支发布，其余子任务不受影响；DBT-2 v7 改回 PHP 整段语义，不再有未声明的语义差异（REV-12）；')
rep('；前台不发 CSP（DEC-11）与 PHP 一致 | §6.8、§6.13、§10 |',
    '；前台 CSP 去留不再由设计决定（OP-T6） | §6.8、§6.13、§10 |')

# §13 append after REV-8/SUG rows of v5 review: add new review table before "## 14."
rep('\n## 14. 修订记录',
    '\nS1 完整设计独立复核（智能调度，对象 v6 `55afe8645440de83120b75bb1360ab7dd73fbb4a`，评论 `01a194bb-d7b2-76e2-8962-d247596fa529`；巡检补派评论 `01a194c4-379f-71f6-89d0-f328b05e4ca3`）：\n\n'
    '| REV- | 处理 | 说明 | 修改位置 |\n'
    '| --- | --- | --- | --- |\n'
    '| REV-5 / REV-6 OP-T4、OP-T5 未关闭 | **待发起人决定，本版仍无法关闭** | 截至 v7 提交，Issue 中仍没有发起人对 OP-T4、OP-T5 的确认。两项继续保留 a/b 两列与门禁，§10、§11 AC-17 改为“截至 v7 未关闭”。发起人确认后出下一版：§10 引用关闭评论，删除未选列 | §10；§11 AC-17 |\n'
    '| REV-9 删除前台 CSP 需要人类安全确认 | 已修改（新增待决 OP-T6） | 撤回 v6 的 DEC-11“选 C、前台不发 CSP”。新增 C-75 `SiteSecurityHeaders` 作为前台 CSP 唯一来源；OP-T6 关闭前取基线 `SiteHtmlRenderer.CONTENT_SECURITY_POLICY` 原文，所有前台 HTML 页共用，安全水平不低于基线（会员 nonce、播放器内页与网关 `form-action` 变体都比它宽，不再生成）。OP-T6：a 不发（同 PHP）；b（建议）只发 `object-src`/`base-uri`/`frame-ancestors` 三项。新增 S3-14h（只改 C-75，受 OP-T6 门禁），S3-14h 未合入前 deploy 分支不得发布（RISK-T21）；V-32 不再断言“无 CSP”，改为“等于 C-75 的值”，S3-14h 再按所选列断言并做浏览器核对。顺带更正 V-32 ① 的检索范围（v6 的检索会命中 `admin`/`install` 的同名常量，永远不会为空） | §3.2；§4.11 方案 D；§5.2 C-37/C-40/C-70/C-72/C-75；§6.8 ALIGN-5、迁移表与“前台 CSP”段；§6.17；§7.4 S3-14、S3-14h、条件门禁；§8.2 V-32；§9；§10 RISK-T18/T21、OP-T6；§11 |\n'
    '| REV-10 运行记录不是跨进程锁 | 已修改 | 复核结论正确：v6 的“查记录与 `backup.lock` → 原子替换写 `RUNNING`”两步之间没有互斥；Java 基线 `export` 的 `stat` 后 `replace` 与 PHP 的 `is_file` 后 `file_put_contents` 同样有竞态。改为 `LegacySiteFiles.tryExclusiveLock` 取 `dbtool.lock` 的独占 OS 文件锁（同 JVM 另加进程内锁），所有数据库工具动作与导出都在锁内读写运行记录；所有权 = 持锁进程，进程退出由操作系统释放，持锁者读到 `RUNNING`/`RECOVERING` 即可判定中断，不需租约或心跳；锁文件不删除；本机文件系统前提与 PHP 并存约束列 RISK-T20。V-31 ② 同 JVM 同时起跑 200 轮、③ 两个子进程同时起跑 50 轮、④ 持锁子进程挂起与被杀 | §4.12；§6.13 DBT-4、DBT-5 ③④⑥；§6.3；C-73、C-74；V-31；RISK-T20 |\n'
    '| REV-11 恢复步骤与锁状态矛盾 | 已修改 | 新增运行记录状态机与专用 `recover(run, 目标)`：只对 DBT-3 待处理记录开放，只能用记录绑定的自动备份（校验 SHA-256）或原备份，不能另选文件；恢复全程持锁、站点须关闭、不再做自动备份；结果 `RECOVERED`/`RECOVERY_FAILED`/`RECOVERY_UNKNOWN`/`RECOVERY_INTERRUPTED`，失败后仍待处理、可再次 `recover`；`acknowledge` 是离开待处理的唯一出口，`RECOVERED` 直接确认，其余须勾选“接受当前数据”；待处理期间普通操作与删除绑定备份都被拒；工具已无法恢复时由部署方用 `mysql` 客户端导入自动备份再确认。V-31 ⑤～⑧ 按状态机验证 | §6.13 DBT-4、DBT-5 ⑤⑥⑦⑨、状态机；C-54、C-73；V-31；§9 |\n'
    '| REV-12 DBT-2 拆句改变 PHP 语义 | 已修改（选保持 PHP 整段语义，不新增待决项） | 核对基线：`sql()` 整段一次 `Db::execute`，连接 `ATTR_EMULATE_PREPARES=false`、`break_reconnect=false`，数据库只接受一条语句，多语句整体被拒。v7 DBT-2：PHP 跳过判定逐字照搬（含“执行成功”提示）→ 多语句在发送前拒绝（结果同 PHP：一条都不执行）→ AC-11 结构保护（OP-6 已确认）→ 整段一次发送、不重试。`SELECT 1; INSERT …` 整段跳过，与 PHP 相同；v6 的“`SELECT` 执行后不展示结果集”也更正为不执行。逐条状态只保留给 DBT-3。V-23 改为样本表（跳过、多语句、AC-11、允许的 DML），并在对照环境对 PHP 取样确认不执行 | §4.13 DEC-13；§6.13 DBT-2、DBT-5 ①②③⑤⑧；§6.17 SEC-8；C-53；V-23；§11 FR-9 |\n'
    '| （自查）DBT-3 空表 | 已修改 | PHP 导出对 0 行的表只写删表与建表，PHP 还原后该表为空；v6 只清空备份里有 `INSERT` 的表，这类表会留下现有数据，与“结果与 PHP 相同”不符。v7 还原表集合改为备份中 `DROP`/`CREATE`/`INSERT` 出现过的全部表 | §6.13 DBT-3；V-23 ④ |\n'
    '\n## 14. 修订记录')
rep('| 2026-11-01 | v6 | @架构师-海外 |',
    '| 2026-11-01 | v7 | @架构师-海外 | 前台 CSP 收敛到 C-75，删除/放宽列为待决 OP-T6，关闭前保持基线严格策略，新增 S3-14h 与发布门禁、RISK-T21，V-32 改断言并更正检索范围（§4.11 方案 D）；数据库工具互斥改为跨进程 OS 文件锁（`LegacySiteFiles.tryExclusiveLock`）、运行记录状态机与专用 `recover`、删备份保护、RISK-T20，V-31 重写（同时起跑、被杀、恢复状态机）；DBT-2 改回 PHP 整段语义（§4.13 DEC-13），V-23 改为样本表；DBT-3 还原表集合补空表；OP-T4、OP-T5 仍待发起人确认 | REV-5、REV-6（未关闭）、REV-9、REV-10、REV-11、REV-12 |\n'
    '| 2026-11-01 | v6 | @架构师-海外 |')
open(P, 'w', encoding='utf-8', newline='').write(s)
print('ok')
