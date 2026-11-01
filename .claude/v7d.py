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

# §6.17
replace_line('| SEC-8（v6 更正',
    '| SEC-8（v6 更正：不是差异，无条件；v7 连提示也与 PHP 相同） | 执行 SQL 不执行以 `select` 开头或含 `outfile`/`dumpfile`/`load_file` 的输入 | DBT-2 ② | 与 PHP 逐字相同（`Database.php:252-263`）：整段不发送，返回“执行成功”；FR-12 相关行判“已对齐” |')
rep('SEC-8 v5 及以前误写为“PHP 照常执行”的收紧，v6 按 PHP 源码更正为与 PHP 一致的无条件行为（V-23）。前台不再发 CSP（DEC-11）不属于六项，见 RISK-T18。',
    'SEC-8 v5 及以前误写为“PHP 照常执行”的收紧，v6 按 PHP 源码更正为与 PHP 一致的无条件行为，v7 连提示文字也改回 PHP（V-23）。前台 CSP 的去留不属于六项，是待决 OP-T6（v7，REV-9）；关闭前沿用基线严格策略，不降级。')

# §7.4
rep('；f：PG-5（分两次运行：账号类、财务与记录类；含 `SiteMemberFinance`/`SitePaymentController` 的 `HTML_UTF8`）；g：PG-6 + ALIGN-7（`SiteCounterActions`）） |',
    '；f：PG-5（分两次运行：账号类、财务与记录类；含 `SiteMemberFinance`/`SitePaymentController` 的 `HTML_UTF8`）；g：PG-6 + ALIGN-7（`SiteCounterActions`）；a 同时落地 C-75，取值为基线严格策略） |')
rep('| 本组样本 V-19 子集通过；本组涉及的 V-32 断言；c 另需 V-30 |',
    '| 本组样本 V-19 子集通过；本组涉及的 V-32 ①～⑤ 断言；c 另需 V-30 |\n'
    '| S3-14h | 前台 CSP 落定（v7） | 前端 | C-75 改为 OP-T6 所选列的值；`SitePageResponsesTest` 的取值断言 | S3-14a；**OP-T6 已关闭** | V-32 ②⑥（按 OP-T6 所选列）；`./mvnw -B verify` |')
replace_line('| S3-19a |',
    '| S3-19a | 数据库工具：优化/修复、执行 SQL（PHP 整段语义）、锁、运行记录与状态机公共部分、`acknowledge` | 后端 | C-53（DBT-1、DBT-2、DBT-4 导出入锁、DBT-5 公共部分）、C-54（`sql.jte`、提示条、`acknowledge`）、C-55、C-73、C-74（`SqlStatementsTest`、`DatabaseRunsTest`、`DatabaseRunsLockIT`、`DatabaseRunsLockProbe`） | G1（不依赖模板，不依赖任何 OP-T） | V-23 ①②④、V-31 ①②③④ |')
replace_line('| S3-19b |',
    '| S3-19b | 数据库工具：还原备份（预检、自动备份与哈希、逐表状态、`recover`、删备份保护） | 后端 | C-53（DBT-3、DBT-4 删备份保护）、C-54（`import.jte`、`recover`）、C-74（`DatabaseToolMySqlIT`） | S3-19a | V-23 ③、V-31 ⑤～⑩ |')
rep('**条件门禁（OP-T4、OP-T5）**：S3-13b、S3-13c 依赖 OP-T5（v6 起 OP-T5 只含 SEC-7；SEC-8 已按 PHP 源码更正为无条件行为，S3-19a/b 不再依赖 OP-T5），S3-14c 依赖 OP-T4。',
    '**条件门禁（OP-T4、OP-T5、OP-T6）**：S3-13b、S3-13c 依赖 OP-T5（v6 起 OP-T5 只含 SEC-7；SEC-8 已按 PHP 源码更正为无条件行为，S3-19a/b 不再依赖 OP-T5），S3-14c 依赖 OP-T4，S3-14h 依赖 OP-T6（v7）。')
rep('a/b 两列的差别只在 C-43（`Content-Type` 补充三行）、C-44（放行范围）、C-68（`PasswordAttempts`）三处，不影响其他子任务。',
    'a/b 两列的差别只在 C-43（`Content-Type` 补充三行）、C-44（放行范围）、C-68（`PasswordAttempts`）、C-75（CSP 取值）四处，不影响其他子任务。OP-T6 未关闭时 S3-14a～g、S3-15 照常进行：C-75 取基线严格策略，不降低现有安全水平，各组测试断言“等于 C-75 的值”，S3-14h 改值时不需改这些测试；但 S3-14h 未合入前 deploy 分支不得发布（前台脚本在浏览器中被拦，RISK-T21）。')
rep('所以 S3-14c 之外的 PG 组、S3-15 与 S3-10 之前的模板线都不受这两项影响。',
    '所以 S3-14c、S3-14h 之外的 PG 组、S3-15 与 S3-10 之前的模板线都不受这三项影响。')

# §8.2
replace_line('| V-23 |',
    '| V-23 | AC-11、SEC-8（DBT-2 ②～④，v7 按 PHP 整段语义重写） | 单测 + MySQL 集成 + 对照 | `SqlStatementsTest`（C-74）按下表样本断言“是否发送、发送几次、提示”：①**PHP 跳过**：`select * from {pre}vod`、前导换行/制表符的 `  SeLeCt 1`、`/* x */select 1`、`-- x` 换行后 `select 1`、`SELECT 1; INSERT INTO mac_link …`、`insert into mac_link select load_file(\'/etc/passwd\')`、`INSERT … INTO/**/OUTFILE …`、`… DuMpFiLe …`、空输入 → 发送 0 次，提示“执行成功”；②**多语句**：`INSERT …; INSERT …`、`UPDATE …; DROP TABLE mac_x`、`INSERT …; -- c` 换行后 `DELETE …` → 发送 0 次，提示“一次只能执行一条语句”；`INSERT INTO mac_link VALUES (\'a;b\')`、`UPDATE … SET x=\'\\\';DROP\'`、`INSERT …;`（末尾单个分号）、`INSERT …; -- 尾注释` → 不算多语句；③**AC-11**：`DROP TABLE`、`TRUNCATE`、`ALTER`、`CREATE`、`RENAME`、`REPLACE`、`WITH … DELETE`、`# c` 换行后 `DROP …`、`/*!50000 DROP TABLE x*/`、`(SELECT 1)` → 发送 0 次，提示“结构类操作请由部署方在库外完成”；`INSERT`/`UPDATE`/`DELETE` 各大小写与前导注释变体 → 发送 1 次，文本与 `{pre}` 替换后的输入逐字相同；④`{pre}` 被替换为表前缀；提示与日志不含 SQL 原文（SUG-1）。`DatabaseToolMySqlIT`（C-74）：以 `it_app` 执行优化/修复各 1 张表（`general_log` 中只有这两条）；上表 ①②③ 的样本提交后 `general_log` 中该批 0 条，四类允许的 DML 各 1 条；对照：上表 ①② 各取一例在 PRE-2 对照环境的 PHP 后台提交，前后 `CHECKSUM TABLE` 不变（证明 PHP 同样不执行）；④**还原**：含 `DROP/CREATE` 的备份还原后数据还原、表仍在；备份中只有 `DROP/CREATE`、没有 `INSERT` 的表还原后为空（同 PHP）；前后 SNAP 差异 0 | G2 + 验收 |')
replace_line('| V-31 |',
    '| V-31 | DBT-5（REV-7、REV-10、REV-11） | 单测 + 跨进程集成 + MySQL 集成 | `DatabaseRunsTest`（单测，测试侧 `DataSource` 包装注入故障）：①**DBT-2 结果分类**：服务端返回 SQLState `42S02` → `DONE`/`outcome=FAILED`、不进入待处理；单值 `INSERT` 返回 `23000` → 同上；多行 `UPDATE` 返回非连接错误 → `UNKNOWN`、待处理；抛 `08S01` → `UNKNOWN`、无任何补偿语句、不重试。②**同时起跑**：同一 JVM 两个线程用 `CyclicBarrier` 同时调 `begin`，重复 200 轮，每轮恰好一个成功、另一个被拒且发出 0 条语句。`DatabaseRunsLockIT`（跨进程，`ProcessBuilder` 启动 C-74 的 `DatabaseRunsLockProbe`，同一临时站点根）：③两个子进程等待同一个起跑信号文件后同时 `begin`，重复 50 轮，每轮恰好一个输出 `ACQUIRED`、另一个输出 `BUSY`，`dbtool-run.json` 始终是一个合法记录；④**崩溃识别**：子进程拿锁、写 `RUNNING` 后挂起，父进程 `begin` → 拒绝“已有数据库操作在执行”、记录仍为 `RUNNING`；随后 `destroyForcibly()` 子进程，父进程再 `begin` → 记录被改为 `INTERRUPTED`（待处理）、普通操作被拒、`acknowledge` 可用；日志行字段齐全且不含 SQL 原文与数据值。`DatabaseToolMySqlIT`（一次性库 `maccms_it_*`）：⑤**中途失败与两种恢复**：备份含表 A、B、C，B 的第 3 条 `INSERT` 构造主键冲突 → A 已还原、B 部分插入、C 未动，记录 `FAILED` 指向 B，自动备份各卷哈希已记录；此时普通还原、执行 SQL、导出、删除该自动备份都被拒；`recover(AUTO_BACKUP)` → `RECOVERING` → `RECOVERED`，A、B、C 的 `CHECKSUM TABLE` 与还原开始前相同，期间另一会话的 `begin` 被拒；修正备份后另跑一轮，`recover(SOURCE)` → A、B、C 等于备份内容；⑥**恢复再失败**：`recover` 时在 B 表注入 `08S01` → `RECOVERY_UNKNOWN`，仍待处理；再次 `recover` 成功 → `RECOVERED`，`recovery.attempt=2`；⑦**确认规则**：`RECOVERED` 时 `acknowledge` → `DONE`；`FAILED` 时不勾选“接受当前数据”的 `acknowledge` 被拒，勾选后 → `DONE`、`accepted_as_is=true`；⑧**自动备份被改**：篡改自动备份一个分卷后 `recover(AUTO_BACKUP)` 被拒、0 条语句、记录不变；⑨**站点未关闭**：`site_status=1` 时还原与 `recover` 都被拒，`general_log` 中无 `DELETE`/`INSERT`；⑩**连接中断**：在 B 表执行期间由 `it_admin` `KILL` 应用连接 → 记录 `UNKNOWN`，`general_log` 中断点之后无该运行的语句（不补偿、不重试）；重复还原同一份正确备份两次，26 张表 `CHECKSUM TABLE` 两次相同（幂等） | G2 |')
replace_line('| V-32 |',
    '| V-32 | REV-8（`SiteHtmlRenderer` 调用方迁移）、REV-9（OP-T6） | 检索 + 单测 + 接口用例 + 人工浏览器核对 | ①S3-15 合入前：`git grep -n \'SiteHtmlRenderer\\|CONTENT_SECURITY_POLICY\\|PlayerFramePolicy\\|memberPolicy\' -- src/main/java/com/maccms/backend/site src/test/java/com/maccms/backend/site` 无结果（v7 更正：限定 `site` 包，`admin`/`install` 的同名常量不在范围），`git grep -n \'Content-Security-Policy\' -- src/main/java/com/maccms/backend/site` 只在 `SitePageResponses`（设置头）与 `SiteSecurityHeaders`，`git grep -n \'new MediaType("text", "html"\' -- src/main/java/com/maccms/backend/site` 只剩 `SiteRequests`；②`SitePageResponsesTest`：头部为 `Content-Type: text/html;charset=UTF-8`、`Content-Length`、`Cache-Control`、`X-Content-Type-Options: nosniff`，`varyUserAgent` 时有 `Vary: User-Agent`；CSP 头等于 `SiteSecurityHeaders.frontPolicy()`：S3-14a～g 时断言等于基线 `SiteHtmlRenderer.CONTENT_SECURITY_POLICY` 原文，S3-14h 后按 OP-T6 所选列断言（a 无该头；b 恰为三项）；③`SiteTestRoutes` 的 `/boom-large`：响应为完整 500 页，不含 `SYNTHETIC-PARTIAL-CHUNK`，状态码不是 200；④`SearchThrottleTest`：第 1 页过快 → HTTP 200、`public/jump` 页面、消息为 `lang(\'search_frequently\')+timespan+lang(\'seconds\')`、无 `Retry-After`，第 2 页不计时；⑤计数接口：非 ajax 的 POST `hits/digg/score` 返回与 PHP 同请求相同的 JSON（PRE-2 基线）；⑥S3-14h（OP-T6 已关闭后）：真实浏览器中 `public/jump` 倒计时跳转、播放页播放器脚本、会员支付下单页的网关表单都可执行，浏览器控制台无 CSP 拦截报告（人工核对一次，截图附交付说明）；选 b 时另核对官方模板页面没有 `<object>`/`<embed>`/`<base>` 被拦（S3-04 模板扫描给出出现位置） | G2 |')

# §9
rep('| S3-15（jte 与 D4 删除） | `git revert`，恢复 `sitetemplate`、`SiteHtmlRenderer`、`PlayerFramePolicy`、',
    '| S3-14h（前台 CSP 落定，v7） | `git revert` 即回到 C-75 的过渡值（基线严格策略），不涉及模板与数据；回退后 deploy 分支回到“不得发布”状态 | 最先回退（早于 S3-15） |\n'
    '| S3-15（jte 与 D4 删除） | `git revert`，恢复 `sitetemplate`、`SiteHtmlRenderer`、`PlayerFramePolicy`、')
replace_line('| S3-19a/b（数据库工具）',
    '| S3-19a/b（数据库工具） | `git revert` 即恢复“四项拒绝”（先回退 b 再回退 a）；回退前先确认 `dbtool-run.json` 不处于待处理状态（处于待处理则先按 DBT-5 ⑦ 处理完，否则回退后旧代码看不到待处理提示）；站点根下残留的 `dbtool-run.json`、`dbtool.lock` 与自动备份文件不影响旧代码（旧代码不读它们，备份列表只认 `Ymd-His-*.sql*`，自动备份按同名规则仍可被 B15 列出、删除）；回退 a 后导出恢复为 B15 的 `backup.lock` 检查 | 独立 |')
replace_line('| DEC-11（前台不发 CSP）若事后要求恢复',
    '| OP-T6 的答案事后改变（v7） | 只改 C-75 一处取值，单独提交，可单独 `git revert`；不涉及模板与数据 | 独立 |')
rep('可逆替代（v6，DBT-5）：DBT-3 每次执行前自动导出将被还原的表，失败或要撤销时用该备份按 DBT-5 ⑦ 还原，数据回到还原开始前；',
    '可逆替代（v6，DBT-5；v7 改为专用恢复动作）：DBT-3 每次执行前自动导出将被还原的表并记哈希，失败后只能经 `recover`（DBT-5 ⑨）用该次运行绑定的自动备份退回，数据回到还原开始前，期间保持互斥、记录保持待处理；')
rep('两者中途失败都不自动补偿，状态与恢复步骤见 DBT-5 ③⑥⑦。',
    '两者中途失败都不自动补偿，状态与恢复步骤见 DBT-5 ③⑥⑦⑨ 与状态机。')

# §10 risks
replace_line('| RISK-T13 |',
    '| RISK-T13 | 数据库还原/执行 SQL 改数据不可随代码回退；MyISAM 下中途失败留下部分结果 | 管理员操作；SQL 错误、连接中断、并发写入 | DBT-5：整批预检、逐条状态与失败分类、跨进程锁、运行记录状态机与提示条、还原前自动备份（带哈希）、`recover` 专用恢复、站点关闭前置、人工恢复步骤；验收只在一次性库（V-31） | DBT-2 无自动备份，靠管理员先导出；“结果不确定”须人工核对 |')
replace_line('| RISK-T18 |',
    '| RISK-T18 | 前台 CSP 被删除或放宽后，XSS 纵深防护减弱 | OP-T6 选 a 或 b 后（S3-14h） | v7 起不由设计单方决定：OP-T6 关闭前保持基线严格策略；选 b 保留 `object-src`、`base-uri`、`frame-ancestors` 三项；模板变量的转义按 PHP 模板原样（BR-11）；保留 `nosniff`；改回只动 C-75 一处（§9） | 按发起人所选：a 与 PHP 相同；b 不限制脚本 |')
replace_line('| RISK-T19 |',
    '| RISK-T19 | 还原或恢复期间其他管理员或定时任务写入被还原的表 | DBT-3、`recover` 执行中 | 站点关闭前置（DBT-5 ② d、⑨）挡住前台；工具内跨进程锁挡住其他数据库操作；确认步骤提示停定时任务与其他编辑；失败按 DBT-5 ⑦ 恢复 | 低：运维约定，未加库级锁 |\n'
    '| RISK-T20 | 数据库工具的文件锁在网络文件系统上失效，或 PHP 与 Java 后台同时对同一库使用数据库工具 | 站点根放在 NFS/SMB；切换期两套后台并存 | 部署说明写明站点根须在本机文件系统、同一库只由一套后台使用数据库工具；Java 持锁后仍检查 PHP 的 `backup.lock`；V-31 ②③ 只在本机文件系统上验证 | 低：部署约束，本期不部署 |\n'
    '| RISK-T21 | OP-T6 关闭前，前台 HTML 页沿用基线严格 CSP，浏览器里模板内联脚本、`player.js`、支付网关表单不能运行 | S3-14a 合入后到 S3-14h 合入前 | 页面对照不受影响；S3-14h 合入前 deploy 分支不得发布（§7.4 条件门禁）；S3-14h 做浏览器核对（V-32 ⑥） | 无（门禁兜住） |')

# §10 OP rows
rep('**待发起人确认（截至 v6 未关闭，REV-5）**', '**待发起人确认（截至 v7 未关闭，REV-5）**')
rep('**待发起人确认（截至 v6 未关闭，REV-6）**', '**待发起人确认（截至 v7 未关闭，REV-6）**')
rep('| **是**：阻塞 S3-13b、S3-13c 的派单与合入；v6 起不阻塞 S3-19a/b；不阻塞其他子任务（S3-13a 内置视图不受影响） | 发起人（经调度） | S3-13b 派单前 |',
    '| **是**：阻塞 S3-13b、S3-13c 的派单与合入；v6 起不阻塞 S3-19a/b；不阻塞其他子任务（S3-13a 内置视图不受影响） | 发起人（经调度） | S3-13b 派单前 |\n'
    '| OP-T6（v7，REV-9） | Java 前台现有 `Content-Security-Policy`（基线 `SiteHtmlRenderer.java:30-31`：`script-src \'none\'`，会员页按 nonce 放行，播放器内页另有策略）会拦住 PHP 模板与内置视图的脚本（`public/jump.html:23` 倒计时、`player.js` 的 `document.write`、支付网关自动提交表单）；PHP 前台不发该头。删除或放宽它属于六项安全修复之外的安全取舍 | **待发起人确认（v7 新增，未关闭）**。a：前台不发 CSP，与 PHP 相同，按已确认的差异记录“删除 Java 原有 CSP”；b（设计建议）：只发 `object-src \'none\'; base-uri \'self\'; frame-ancestors \'self\'`，不限制脚本，模板可正常运行，保留防插件对象、防 `<base>` 改写、防被第三方页面嵌套三项（这三项基线已有，PHP 没有；`frame-ancestors` 会让站点不能被其他网站用 iframe 嵌入）。两种答案的改动都只在 C-75，由 S3-14h 落地；关闭前沿用基线严格策略（§6.8） | **是**：阻塞 S3-14h 的派单与合入，因而阻塞 deploy 分支发布；不阻塞 S3-14a～g、S3-15 与其他子任务 | 发起人（经调度） | S3-14h 派单前 |')

# §11
rep('| C-36～C-40、C-43、C-44、C-46、C-68、C-70～C-72 | V-10、V-19、V-30、V-32 |',
    '| C-36～C-40、C-43、C-44、C-46、C-68、C-70～C-72、C-75 | V-10、V-19、V-30、V-32 |')
replace_line('| FR-9 / AC-11 |',
    '| FR-9 / AC-11 | DBT-1～DBT-5（v6：SEC-8 按 PHP 无条件；失败、并发、恢复契约 DBT-5；§4.12 DEC-12。v7：DBT-2 按 PHP 整段语义，§4.13 DEC-13；跨进程锁与状态机、`recover`；DBT-3 表集合含空表）。与 PHP 的差异判定：DBT-2 只有 AC-11 拒绝与提示文字；DBT-3 只有可恢复性保护与“站点须关闭”前置（DBT-5 ⑧） | C-53～C-55、C-73、C-74 | V-23、V-31 |')
replace_line('| AC-17 |',
    '| AC-17 | 每个 S3 子任务引用的 OP-T 在派单前已关闭：OP-T4 → S3-14c；OP-T5（v6 起只含 SEC-7）→ S3-13b、S3-13c；OP-T6（v7）→ S3-14h；未关闭不派单、不合入（§7.4 条件门禁）。截至 v7 三项都未关闭 | — | V-29 |')
rep('S3-14c 写明 OP-T4 所选答案，并附关闭该 OP 的 Issue 评论；',
    'S3-14c 写明 OP-T4 所选答案，S3-14h 写明 OP-T6 所选答案，并附关闭该 OP 的 Issue 评论；')
rep('SEC-8 与 PHP 一致；前台不发 CSP（DEC-11）不属于六项 | — | V-24；SEC-7、SEC-9 见 V-18、V-30；SEC-8 见 V-23 |',
    'SEC-8 与 PHP 一致；前台 CSP 去留不属于六项，是待决 OP-T6 | — | V-24；SEC-7、SEC-9 见 V-18、V-30；SEC-8 见 V-23；OP-T6 见 V-32 ②⑥ |')
open(P, 'w', encoding='utf-8', newline='').write(s)
print('ok')
