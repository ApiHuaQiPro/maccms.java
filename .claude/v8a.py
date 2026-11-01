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

# ---------- metadata
rep('v7 修订 2026-11-01 11:20（UTC+8） |', 'v7 修订 2026-11-01 11:20（UTC+8）；v8 修订 2026-11-01 12:10（UTC+8） |')
rep('| 版本 | v7 |', '| 版本 | v8 |')
rep('deploy 分支 `release/APPK-2-php-template-migration` @ `a57e9cb2b94d3e500bea7dfe2c579daee093d969` |\n',
    'deploy 分支 `release/APPK-2-php-template-migration` @ `a57e9cb2b94d3e500bea7dfe2c579daee093d969` |\n'
    f'| 已关闭决策（v8） | OP-T4=a、OP-T5=a、OP-T6=b：Issue APPK-2 发起人确认评论 {CONF}（调度核验评论 `01a194e9-f7f0-7bf0-a6cc-09af877c86bd`）。本文件以该评论为三项的唯一决策源，实现、验收、门禁与回滚只写所选答案；将来改选须先由发起人在 Issue 另行确认，再出设计修订 |\n')
rep('DBT-3 还原表集合补上只有 `DROP/CREATE` 的空表 |',
    'DBT-3 还原表集合补上只有 `DROP/CREATE` 的空表。v8：按发起人确认收敛 OP-T4=a、OP-T5=a、OP-T6=b 为唯一实现（删除未选列；C-75 自 S3-14a 起即为三项策略，撤销 v7 的过渡值与 S3-14h，§4.11 方案 E）；删备份纳入 `dbtool.lock` 并在锁内保护绑定备份、预检移到取锁之后（DBT-4、DBT-5 ②④）；进程中断后按操作类型与阶段的唯一转移、页面按钮与日志（DBT-5 ⑩），V-31 ⑪～⑲ |')

# ---------- §3
rep('前台 CSP 只由 C-75 `SiteSecurityHeaders` 提供，过渡期沿用现有严格策略，最终值按 OP-T6（DEC-11，v7）',
    f'前台 CSP 只由 C-75 `SiteSecurityHeaders` 提供，值为 OP-T6=b 的三项 {CSPB}（DEC-11，v8）')
rep('过渡期播放器页改用 C-75 的严格策略，比它更严）', '删除后播放器页与其他前台 HTML 页共用 C-75 的三项策略，v8）')

# ---------- §4.8 / §4.11
rep('；**条件生效**：OP-T5 选 a 才这样做，选 b 按 §6.10 的 b 列放开）', '；OP-T5 已按 a 关闭（v8），这是唯一实现）')
rep('（v6 选定；v7 改为 OP-T6 的 a 列，不再由设计单方选定）', '（v6 选定，v7 撤回；即 OP-T6 a，发起人未选）')
line('| **D（v7 选定，DEC-11 修订）',
     '| D C 的共享边界 + CSP 单点 C-75 + OP-T6 门禁（v7 写法，v8 撤回） | `SitePageResponses` 同方案 C，CSP 只取自 C-75；OP-T6 未决期间取基线 `SiteHtmlRenderer.CONTENT_SECURITY_POLICY` 原文作过渡值，S3-14h 再按 OP-T6 改值 | 中 | 过渡期真实浏览器里模板内联脚本、`player.js`、支付网关表单被拦，须另设“S3-14h 合入前不得发布”门禁 | 好 | 是 |\n'
     f'| **E（v8 选定，DEC-11 修订）C 的共享边界 + C-75 固定为 OP-T6=b 的三项** | `SitePageResponses` 同方案 C；C-75 `SiteSecurityHeaders.frontPolicy()` 返回常量 {CSPB}，所有前台 HTML 页共用，随 S3-14a 落地即为此值、没有过渡值；会员 nonce、播放器内页专用策略、网关 `form-action` 变体都不再生成 | 中（比 D 少一个子任务、少一道发布门禁） | 不限制脚本，XSS 纵深防护比基线弱（RISK-T18，发起人已按 OP-T6=b 接受）；站点不能被第三方页面用 iframe 嵌入 | 好：C-75 随 S3-14a 提交；只改策略时单独提交 C-75 一处（§9） | 是 |')
sub(r'选 D（v7，回应 REV-9）：.*?也不参与比较。',
    f'选 E（v8，回应 REV-13）：OP-T6 已由发起人按 b 关闭（确认评论 {CONF}），方案 C（删除 Java 现有 CSP，即 OP-T6 a）未被选择；方案 D 的过渡值与 S3-14h 门禁只为“未决”而设，决定后不再需要，v8 撤回：C-75 从 S3-14a 起就是三项策略，浏览器里模板脚本可以运行，不存在“过渡期不得发布”。方案 B 的“兼容策略”（放行内联脚本与任意源）等于没有保护，不采用；所选三项都与脚本无关、官方模板不受影响（插件对象、`<base>` 改写、被第三方页面嵌套），基线策略已有。保留的 `nosniff`、`Cache-Control`、`Vary`、`X-Robots-Tag` 不改变页面渲染，也不参与比较。将来若改选，须先由发起人在 Issue 另行确认、再出设计修订，实现只改 C-75 一处（§9）。')

# ---------- §5 change list
rep('（含 PHP 的按会话 5 秒限制；`PasswordAttempts` 去留按 OP-T4，见 C-68）', '（含 PHP 的按会话 5 秒限制；`PasswordAttempts` 按 OP-T4=a 保留并移到 `ajax/pwd`，见 C-68）')
rep('CSP 头一律由 C-75 提供（DEC-11，值按 OP-T6；过渡期为基线严格策略） |', 'CSP 头一律由 C-75 提供（DEC-11，OP-T6=b 的三项策略） |')
rep('`Content-Type`（按 §6.10 固定表，b 时含补充的三行）', '`Content-Type`（按 §6.10 固定表）')
rep('不回显路径 | DEC-8；OP-T1；OP-T5 | 前端 |', '不回显路径 | DEC-8；OP-T1；SEC-7（OP-T5=a） | 前端 |')
line('| C-44 |',
     '| C-44 | `B/legacyconfig/application/LegacySiteFiles.java` | 修改 | 增加只读方法 `readTemplateAsset(String relative, int maxBytes)`，是 `/template/**` 唯一的放行边界（OP-T5=a，v8 起只此一种实现）：① `relative` 经 `LegacySiteRoot.validRelative`（拒绝 `..`、`.`、空段、反斜杠、`:`、空字节、控制字符），且以 `template/<目录>/` 开头、其后至少一段；② 经 `LegacySiteRoot.resolve` 规范化，真实路径须在 `<站点根>/template/` 内（比现有 `read` 只校验站点根更严：中间目录是符号链接时，解析后出 `template/` 或出站点根都拒绝）；③ 最终文件按 `NOFOLLOW_LINKS` 打开，本身是符号链接、目录或非普通文件都拒绝；④ 扩展名 `php`（按小写比较）一律拒绝；⑤ 只读：不建目录、不写、不改时间；超 `maxBytes` 拒绝（`TOO_LARGE`）；⑥ **SEC-7 白名单**：`<目录>` 后的第一段必须是 `css`/`js`/`images`/`ads` 之一，且扩展名（按小写）在 `css js png jpg jpeg gif ico svg webp woff woff2 ttf eot swf` 内，否则 `FORBIDDEN`（`.html`、`.htm`、`.ini`、`.txt`、无扩展名都拒绝）。不设运行时开关，不扩大写区域，不改现有 `read`（属 S3-13b） | DEC-8；SEC-7（OP-T5=a） | 后端 |')
rep('CSP 头取 C-75 `SiteSecurityHeaders.frontPolicy()`（有值才发，v7）', 'CSP 头取 C-75 `SiteSecurityHeaders.frontPolicy()`（v8：恒有值，即三项策略）')
rep('头部按 §4.11 方案 D，', '头部按 §4.11 方案 E，')
rep('“`Content-Security-Policy` 头等于 `SiteSecurityHeaders.frontPolicy()`（为空时断言没有该头）、`Content-Type` 等于 `text/html;charset=UTF-8`”，这样 S3-14h 改值时不必再改这些测试（v7）',
    '“`Content-Security-Policy` 头等于 `SiteSecurityHeaders.frontPolicy()`、`Content-Type` 等于 `text/html;charset=UTF-8`”，这样 C-75 将来改值时不必再改这些测试（v7；v8 起 C-75 固定为 OP-T6=b 的三项，原文只由 `SitePageResponsesTest` 断言）')
rep('DBT-4 导出改为在 C-73 的锁内执行；', 'DBT-4 导出与删备份 `delete` 改为在 C-73 的锁内执行（v8，REV-14：`delete` 在锁内先做中断判定、再读运行记录，拒绝删除 DBT-3 待处理记录绑定的备份，按卷号升序删除）；DBT-5 ⑩ 的中断判定与清理；')
rep('新增两个后台动作 `database/recover`、`database/acknowledge`（POST + 表单令牌，权限同 `database/import`） |',
    '新增两个后台动作 `database/recover`、`database/acknowledge`（POST + 表单令牌，权限同 `database/import`）；v8：`del` 分支（基线 `AdminDatabaseActions.java:60`）仍调用 `DatabaseToolService.delete`，入参与 JSON 结构不变，只新增 DBT-4 的两种拒绝提示；数据库工具各页面加载时调用 `DatabaseRuns.probe()` 做一次不等待的中断判定（DBT-5 ⑩），提示条与按钮按 ⑩ 表的“页面”列出现 |')
rep('持锁者读到 `RUNNING`/`RECOVERING`，说明上一个占用者已不在（否则拿不到锁），先把它改为 `INTERRUPTED`/`RECOVERY_INTERRUPTED` 并记 `detected_at`，再做本次判断；',
    '持锁者读到 `RUNNING`/`RECOVERING`，说明上一个占用者已不在（否则拿不到锁），先按 DBT-5 ⑩ 的中断结果表**按 `op` 与 `phase` 唯一改写**（v8，REV-15：DBT-1、导出、DBT-3 备份阶段 → `DONE` 并清理半成品；DBT-2、DBT-3 还原阶段 → `INTERRUPTED`；`RECOVERING` → `RECOVERY_INTERRUPTED`）并记 `interrupted`，再做本次判断；取锁的入口共八个：DBT-1、DBT-2、DBT-3、导出、`recover`、`acknowledge`、`delete` 与页面加载的 `probe()`（v8），中断判定是它们共用的第一步；')
rep('导出（DBT-4）在锁内执行，仍按 B15 写删 `backup.lock`。', '导出（DBT-4）与还原前自动备份在锁内执行，仍按 B15 写删 `backup.lock`，写入值同时记入 `backup_lock`；`delete` 在锁内执行（v8，REV-14）。')
rep('记录字段：`run`（UUID）、`op`、`state`、', '记录字段：`run`（UUID）、`op`（`OPTIMIZE`/`REPAIR`/`SQL`/`IMPORT`/`EXPORT`）、`phase`（v8：`IMPORT` 为 `AUTO_BACKUP` 或 `RESTORE`，其他为空）、`state`、')
rep('`auto_backup`（时间戳、分卷名与各卷 SHA-256、表清单）、', '`auto_backup`（时间戳、分卷名与各卷 SHA-256、表清单）、`export_files`（v8：导出或自动备份本次已创建的分卷文件名，每新建一卷先记入再创建）、`backup_lock`（v8：本次写入 `backup.lock` 的值）、`interrupted`（v8：判定时间、判定前状态与阶段、清理结果 `OK`/`LOCK_KEPT`/`FAILED` 与残留文件名）、')
rep('| FR-9；DBT-5；REV-7、REV-10、REV-11 | 后端 |', '| FR-9；DBT-5；REV-7、REV-10、REV-11、REV-14、REV-15 | 后端 |')
rep('持锁进程被杀后的中断识别、恢复状态机、重复还原幂等）', '持锁进程被杀后的中断识别、恢复状态机、重复还原幂等；v8：按操作类型与阶段被杀后的唯一转移与清理、删备份与恢复的竞争）')
line('| C-75 |',
     f'| C-75 | 新增 `B/site/web/SiteSecurityHeaders.java` | 新增 | 前台 HTML 页 CSP 的唯一来源（v7，REV-9；v8 按 OP-T6=b 定值，REV-13）：包内 `final` 类，常量 `FRONT_POLICY` = {CSPB}，`static String frontPolicy()` 返回它；随 S3-14a 落地即为此值，所有前台 HTML 页共用，没有过渡值；会员 nonce、`PlayerFramePolicy`、网关 `form-action` 变体不再生成。其他类不得出现 `Content-Security-Policy` 字面量（V-32 ① 检索） | REV-9、REV-13；OP-T6=b | 前端 |')
line('| C-68 |',
     '| C-68 | `B/site/application/PasswordAttempts.java`；`B/site/config/SiteConfiguration.java`（装配）；`T/site/application/PlaybackPrimitivesTest.java`（`PasswordAttempts` 用例） | 修改 | OP-T4=a（v8 起唯一实现）：保留类、装配与测试，调用点从 `SiteLibraryController`/`SitePlaybackController` 的密码表单移到 `ajax/pwd`（ALIGN-4 ⑤），超限返回 PHP 已有的 1003 JSON（HTTP 200，不再用 429） | ALIGN-4；SEC-9（OP-T4=a） | 前端 |')

# ---------- §6
rep('放行范围按 OP-T5 所选答案（§6.10、C-44），只实现所选那一列，不设运行时开关', '放行范围为 OP-T5=a 的 SEC-7 白名单（§6.10、C-44），不设运行时开关')
sub(r'⑤ \*\*条件项（OP-T4）\*\*：.*?OP-T4 关闭前 S3-14c 不派单、不合入',
    '⑤ **OP-T4=a（v8 已关闭）**：在 ③ 之后、④ 查库之前再过 `PasswordAttempts`，超限返回与 ③ 相同的 `{"code":1003,"msg":lang(\'index/pwd_frequently\')}`（HTTP 200，不再用 429），与 PHP 的差异只在“哪一次请求得到 1003”，记为 SEC-9（C-68）')
rep('CSP 只由 C-75 提供，过渡期为基线严格策略，最终值按 OP-T6（DEC-11，v7）', 'CSP 只由 C-75 提供，值为 OP-T6=b 的三项（DEC-11，v8）')
sub(r'\*\*前台 CSP（v7，REV-9，OP-T6）\*\*：.*?（RISK-T21）。',
    f'**前台 CSP（v8，REV-13，OP-T6=b）**：C-75 是唯一来源，自 S3-14a 起所有前台 HTML 页发 {CSPB}。三项都不限制脚本，模板内联脚本（如 `public/jump.html:23` 倒计时）、`player.js` 的 `document.write`、支付网关自动提交表单都能在浏览器中运行（V-32 ⑥）；页面对照（CMP-3 只比状态码、`Content-Type`、`Location` 与正文）不受影响。v7 的“过渡期严格策略 + S3-14h + 发布门禁”随 OP-T6 关闭撤销。')
rep('（OP-T5 a 时 `<相对路径>` 的第一段限 `css`/`js`/`images`/`ads`，见下行）', '（`<相对路径>` 的第一段限 `css`/`js`/`images`/`ads`，见下行）')
line('| 子目录与扩展名（**按 OP-T5 条件生效**）',
     '| 子目录与扩展名（OP-T5=a，SEC-7） | 只映射 `css`、`js`、`images`、`ads` 四个子目录，扩展名白名单 `css js png jpg jpeg gif ico svg webp woff woff2 ttf eot swf`，其他（含 `html htm ini php txt`）返回 404。与 PHP 的差异（PHP 内置服务与 nginx 会把 `.html` 模板源码、`info.ini` 原样返回）按 SEC-7 记为已确认的安全差异。路径规范化、不出 `template/`（因而不出站点根）、拒绝符号链接出根、只读四条同时生效；放行规则只在 C-44 一处实现，C-43 不另设白名单 |')
rep('`Content-Type` 按扩展名固定表（b 时补 `html htm`→`text/html`、`ini txt`→`text/plain`、其他未知→`application/octet-stream`）', '`Content-Type` 按扩展名固定表（只含白名单内的扩展名）')
rep('本设计的条件安全收紧 SEC-7（OP-T5）、SEC-9（OP-T4）只有在对应 OP 关闭且选 a 时，才按“已确认的安全差异”判 `排除-SEC-7` / `排除-SEC-9`（SEC-8 v6 起不是差异，见 §6.17），报告引用关闭该 OP 的 Issue 评论；选 b 时不排除，按 PHP 逐字节比较；OP 未关闭时相关行一律记“未判定”，不能自行判“已对齐”或“排除”；',
    f'本设计的安全收紧 SEC-7（OP-T5=a）、SEC-9（OP-T4=a）已由发起人确认（v8，评论 {CONF}），按“已确认的安全差异”判 `排除-SEC-7` / `排除-SEC-9`（SEC-8 v6 起不是差异，见 §6.17），报告引用该评论；')
rep('| SEC-7（本设计，条件：OP-T5 a） |', '| SEC-7（本设计，OP-T5=a 已确认） |')
rep('| a：不进比较样本，FR-12 相关行按“已确认的安全差异”判 `排除-SEC-7`；b：不存在本项，按 PHP 返回 |', '| 不进比较样本，FR-12 相关行按“已确认的安全差异”判 `排除-SEC-7` |')
rep('| SEC-9（本设计，条件：OP-T4 a） |', '| SEC-9（本设计，OP-T4=a 已确认） |')
rep('| a：只有“超限”样本与 PHP 不同，判 `排除-SEC-9`；b：不存在本项 |', '| 只有“超限”样本与 PHP 不同，判 `排除-SEC-9` |')
rep('是本设计提出、待发起人确认的行为差异；V-24 的“仍通过”只对 SEC-1～6 无条件适用，SEC-7、SEC-9 的用例按所选答案写（V-18、V-30）。',
    '是本设计提出、发起人已确认（v8：OP-T5=a、OP-T4=a）的行为差异；V-24 的“仍通过”只对 SEC-1～6 适用，SEC-7、SEC-9 的用例见 V-18、V-30。')
rep('前台 CSP 的去留不属于六项，是待决 OP-T6（v7，REV-9）；关闭前沿用基线严格策略，不降级。', '前台 CSP 不属于六项；OP-T6 已按 b 关闭（v8），只保留三项不拦脚本的策略（C-75）。')

open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok a')
