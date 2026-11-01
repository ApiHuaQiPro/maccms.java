import sys, io
P = 'docs/design/APPK-2/tech-design.md'
s = open(P, encoding='utf-8', newline='').read()

def rep(old, new, count=1):
    global s
    n = s.count(old)
    if n != count:
        sys.exit(f'count {n} != {count} for: {old[:80]}')
    s = s.replace(old, new)

def line_starting(prefix):
    lines = s.split('\n')
    hits = [l for l in lines if l.startswith(prefix)]
    if len(hits) != 1:
        sys.exit(f'line prefix hits {len(hits)}: {prefix[:60]}')
    return hits[0]

def replace_line(prefix, new):
    old = line_starting(prefix)
    rep(old, new)

# ---------- metadata ----------
rep('v6 修订 2026-11-01 10:40（UTC+8） |', 'v6 修订 2026-11-01 10:40（UTC+8）；v7 修订 2026-11-01 11:20（UTC+8） |')
rep('| 版本 | v6 |', '| 版本 | v7 |')
rep('SEC-8 按 PHP 源码更正为无条件行为，OP-T5 只剩 SEC-7 |',
    'SEC-8 按 PHP 源码更正为无条件行为，OP-T5 只剩 SEC-7。v7：前台 CSP 收敛到单点 C-75，删除与否列为待决 OP-T6，过渡期沿用现有严格策略、新增 S3-14h 门禁（§4.11、§6.8）；数据库工具互斥改为跨进程 OS 文件锁、补运行记录状态机与专用恢复动作（DBT-5、C-73）；DBT-2 改回 PHP 整段执行语义（§4.13、DBT-2）；DBT-3 还原表集合补上只有 `DROP/CREATE` 的空表 |')
rep('v6 增 C-70～C-74）；验证 V-10～V-29（v4 增 V-30；v6 增 V-31、V-32）',
    'v6 增 C-70～C-74；v7 增 C-75）；验证 V-10～V-29（v4 增 V-30；v6 增 V-31、V-32；v7 改写 V-23、V-31、V-32）')

# ---------- §3.2 ----------
rep('`Content-Type` 常量只留 `SiteRequests.HTML_UTF8`，前台页面不再发 CSP（DEC-11） |',
    '`Content-Type` 常量只留 `SiteRequests.HTML_UTF8`；前台 CSP 只由 C-75 `SiteSecurityHeaders` 提供，过渡期沿用现有严格策略，最终值按 OP-T6（DEC-11，v7） |')
rep('`site/application/PlayerFramePolicy`（v6 补，只为 jte 播放器内页生成 CSP） | OP-3 下线；DEC-11 |',
    '`site/application/PlayerFramePolicy`（v6 补，只为 jte 播放器内页生成 CSP；过渡期播放器页改用 C-75 的严格策略，比它更严） | OP-3 下线；DEC-11 |')
rep('`DatabaseToolService`（v6 增 `DatabaseRuns` 运行记录与互斥，DBT-5）、',
    '`DatabaseToolService`（v6 增 `DatabaseRuns` 运行记录与互斥，DBT-5；v7 互斥改为跨进程 OS 文件锁，导出也持同一把锁）、')
rep('| FR-7、FR-9、FR-11、CACHE-3 | 同名类，§1.3 |',
    '| FR-7、FR-9、FR-11、CACHE-3 | 同名类，§1.3；v7 另改 `legacyconfig.application.LegacySiteFiles`（新增 `tryExclusiveLock`，调用方只有 C-73） |')

# ---------- §4.11 ----------
rep('| **C（选定，DEC-11）新共享边界 `SitePageResponses`，前台页面不发 CSP** |',
    '| C 新共享边界 `SitePageResponses`，前台页面不发 CSP（v6 选定；v7 改为 OP-T6 的 a 列，不再由设计单方选定） |')
rep('| 中 | 前台失去 CSP 这层纵深防护（RISK-T18）；不影响比较（CMP-3 只比状态码、`Content-Type`、`Location`） | 好（按 PG 组提交） | 是：与 `admin.web.AdminResponses`、`install.web.InstallResponses` 同样是“各入口一个响应工具类” |',
    '| 中 | 前台失去 CSP 这层纵深防护（RISK-T18）；不影响比较（CMP-3 只比状态码、`Content-Type`、`Location`） | 好（按 PG 组提交） | 是：与 `admin.web.AdminResponses`、`install.web.InstallResponses` 同样是“各入口一个响应工具类” |\n'
    '| **D（v7 选定，DEC-11 修订）C 的共享边界 + CSP 单点 C-75 + OP-T6 门禁** | `SitePageResponses` 同方案 C，但 CSP 头只取自新增的 `SiteSecurityHeaders.frontPolicy()`（C-75，`Optional<String>`，空则不发）。过渡值 = 基线 `SiteHtmlRenderer.CONTENT_SECURITY_POLICY` 原文（所有前台 HTML 页一律用它，不再生成会员 nonce、播放器内页专用策略、网关 `form-action` 变体，三者都只比它更宽）。最终值由 OP-T6 决定，只在 S3-14h 改 C-75 一处：a = 不发（同 PHP）；b = 只含不拦脚本的三项 `object-src \'none\'; base-uri \'self\'; frame-ancestors \'self\'` | 中 | 过渡期没有安全降级，但真实浏览器里模板内联脚本、`player.js`、支付网关表单被拦（RISK-T21），所以 S3-14h 合入前 deploy 分支不得发布 | 好：S3-14h 单独提交，回退即回到过渡值 | 是 |')
rep('选 C：PHP 模板是本期页面的唯一来源（BR-11 不改模板），A、B 都会让真实浏览器里的页面与 PHP 行为不同，或只剩名义上的策略。CSP 不在六项安全修复内（OP-9），按“PHP 为准”。保留的 `nosniff`、`Cache-Control`、`Vary`、`X-Robots-Tag` 不改变页面渲染，也不参与比较。若评审或发起人认为删除 CSP 需要确认，可另设 OP-T6，备选为方案 B；只影响 C-70 一处常量。',
    '选 D（v7，回应 REV-9）：删除 Java 现有 CSP 会降低 XSS 纵深防护，属于六项安全修复（OP-9）之外的安全取舍，“PHP 没有该头”不能代替人类确认，所以 v6 的“选 C”撤回，改为待决 OP-T6（§10）。在 OP-T6 关闭前，设计只做不改变安全水平的部分：共享边界与 CSP 单点照常随 S3-14a 落地，取值保持基线最严的那条策略，不降级；删不删、放宽到哪由发起人在 a/b 中选择，实现只在 S3-14h 改 C-75 一处，并在真实浏览器核对（V-32 ⑥）。方案 B 的“兼容策略”不再采用 v6 的“放行内联脚本与任意源”写法——那样等于没有保护；OP-T6 b 只保留与脚本无关、模板不受影响的三项（插件对象、`<base>` 改写、被第三方页面嵌套），这三项基线策略已有。保留的 `nosniff`、`Cache-Control`、`Vary`、`X-Robots-Tag` 不改变页面渲染，也不参与比较。')

# ---------- §4.12 ----------
rep('| **C（选定，DEC-12）预检 + 逐条状态 + 运行记录互斥 + 还原前自动备份 + 人工恢复步骤** | 见 §6.13 DBT-5：发送任何语句前完成整批预检；执行时逐条记录，第一处失败即停、不补偿，按 12 文档第 4.3 节区分“确定未生效”与“结果不确定”；运行记录文件同时充当互斥锁与审计线索；还原前自动导出将被还原的表，作为数据层回退点；还原要求站点已关闭 | 中 | 自动备份增加耗时与磁盘；仍无法阻止其他管理员在同一时段改内容（运维约定 + 提示） | 数据：用自动备份还原；代码：`git revert` | 是：失败分类同 `WriteSequence`，锁文件同 `backup.lock`，导出复用 B15 `export` |',
    '| **C（选定，DEC-12；v7 修订互斥与状态机）预检 + 逐条状态 + OS 文件锁互斥 + 运行记录状态机 + 还原前自动备份 + 专用恢复动作** | 见 §6.13 DBT-5：发送任何语句前完成整批预检；执行时逐条记录，第一处失败即停、不补偿，按 12 文档第 4.3 节区分“确定未生效”与“结果不确定”；互斥靠跨进程的独占 OS 文件锁 `dbtool.lock`（持锁期间才读写运行记录，进程退出时由操作系统释放）；运行记录 `dbtool-run.json` 只做状态与审计；还原前自动导出将被还原的表并记哈希，失败后只能经专用恢复动作 `recover` 用该次运行绑定的备份恢复 | 中 | 自动备份增加耗时与磁盘；仍无法阻止其他管理员在同一时段改内容（运维约定 + 提示）；文件锁要求站点根在本机文件系统（RISK-T20） | 数据：`recover` 用自动备份恢复；代码：`git revert` | 是：失败分类同 `WriteSequence`，导出复用 B15 `export`，仍兼容 PHP 的 `backup.lock` |\n'
    '| D 只用运行记录文件当锁（v6 写法） | 先查记录与 `backup.lock`，再原子替换写 `RUNNING` | 小 | “检查 → 占用”不是原子操作，两个进程可同时通过检查（REV-10）；崩溃后无法判断占用者是否还活着 | — | 部分 |')
rep('选 C：不改结构、不引入事务，在“无法原子化”的前提下让每次失败都有确定的可见状态、可执行的恢复步骤和数据层回退点。',
    '选 C：不改结构、不引入事务，在“无法原子化”的前提下让每次失败都有确定的可见状态、可执行的恢复步骤和数据层回退点。v7 撤回 v6 的 D 写法：原子替换只保证一次写入不被撕裂，不保证“检查不存在 → 占用”互斥；PHP `export` 的 `is_file` 后 `file_put_contents`（`Database.php:96-105`）与 Java 基线 `DatabaseToolService.export` 的 `stat` 后 `replace`（`DatabaseToolService.java:313-330`）是同样的检查后创建竞态，不能当跨进程占用依据。\n\n'
    '### 4.13 “执行 SQL”按整段执行还是切分后逐条执行（REV-12，v7 新增）\n\n'
    '基线 PHP `sql()`（`Database.php:237-271`）：`trim`、替换 `{pre}` 后，把**整个输入**交给一次 `Db::execute($sql)`；连接是 `PDO::ATTR_EMULATE_PREPARES => false`（`thinkphp/library/think/db/Connection.php:121`）、`break_reconnect => false`（同文件 `:112`），即服务端预处理一次、断线不重连。MySQL 的服务端预处理只接受一条语句，所以含两条及以上语句的输入整体被数据库拒绝、一条都不执行；单条语句末尾的一个 `;` 允许。\n\n'
    '| 方案 | 做法 | 改动量 | 风险 | 可回滚 | 沿用现有模式 |\n'
    '| --- | --- | --- | --- | --- | --- |\n'
    '| **A（v7 选定，DEC-13）保持 PHP 整段语义** | 跳过判定（以 `select` 开头或含文件读写关键字 → 不执行、返回“执行成功”）完全按 PHP 对整段做；之后 AC-11 结构保护对整段做；整段作为一条语句一次发送，连接 `allowMultiQueries=false`，多语句由 Java 在发送前拒绝（PHP 由数据库拒绝，结果同为一条都不执行） | 小 | 只剩两处可观察差异：AC-11 已确认的结构语句拒绝（OP-6）；失败时的提示文字（PHP 为框架异常页） | 好 | 是（PHP 原样） |\n'
    '| B 切分后逐条分类、逐条执行（v6 写法） | 按 `mac_parse_sql` 切分 | 中 | 改变 PHP 语义：`SELECT 1; INSERT …` 在 PHP 整体跳过，B 会执行后面的 `INSERT`；多语句在 PHP 一条都不执行，B 会逐条执行并可能部分生效；需要发起人按 OP-6 另行确认 | 好 | 否 |\n\n'
    '选 A：PHP 本身没有漏洞的地方以 PHP 为准，不需要新增待决项；v6 说“切分只为分类、成功路径不变”不成立（REV-12），撤回。逐条状态、失败序号这些只对还原（DBT-3，PHP 本来就逐条执行）保留。')
open(P, 'w', encoding='utf-8', newline='').write(s)
print('ok')
