import io, sys
p = 'docs/design/APPK-2/tech-design.md'
s = io.open(p, encoding='utf-8').read()


def rep(old, new, count=1):
    global s
    n = s.count(old)
    if n != count:
        sys.exit('MATCH %d != %d: %r' % (n, count, old[:80]))
    s = s.replace(old, new)


def rep_line(prefix, new):
    global s
    if s.count(prefix) != 1:
        sys.exit('LINE MATCH %d: %r' % (s.count(prefix), prefix[:80]))
    i = s.index(prefix)
    j = s.index('\n', i)
    s = s[:i] + new + s[j:]


# metadata
rep('v3 修订 2026-11-01 08:30（UTC+8） |', 'v3 修订 2026-11-01 08:30（UTC+8）；v4 修订 2026-11-01 08:50（UTC+8） |')
rep('| 版本 | v3 |', '| 版本 | v4 |')

# 1.3
rep('PHP 走 `ajax/pwd` 写 session（`Ajax.php:400-456`，`All.php:547-549`） |',
    'PHP 走 `ajax/pwd` 写 session（`Ajax.php:400-456`，`All.php:547-549`），并有按会话的频率限制：`mac_get_time_span("last_pwd") < 5` 时返回 `{"code":1003,"msg":lang(\'index/pwd_frequently\')}`（`Ajax.php:416-418`；`common.php:306-316` 每次调用都把 `last_pwd` 改为当前时间）。Java 的 `PasswordAttempts` 是 B05 用来“代替”这条限制的（按内容 10 次/60 秒、全站 30 次/秒，超限 429，D-B05-3） |')

# 4.8
rep('属于安全收紧，不进比较样本，见 SEC-7）；',
    '属于安全收紧，不进比较样本，见 SEC-7；**条件生效**：OP-T5 选 a 才这样做，选 b 按 §6.10 的 b 列放开）；')

# 5.2 change list
rep('ALIGN-4 恢复 `ajax/pwd` | FR-5、FR-8 | 前端 |',
    'ALIGN-4 恢复 `ajax/pwd`（含 PHP 的按会话 5 秒限制；`PasswordAttempts` 去留按 OP-T4，见 C-68） | FR-5、FR-8 | 前端 |')
rep('| C-43 | `B/site/web/TemplateAssetController.java` | 新增 | §6.10 `/template/**` 静态资源（白名单、只读、经 `LegacySiteFiles`） | DEC-8；OP-T1 | 前端 |',
    '| C-43 | `B/site/web/TemplateAssetController.java` | 新增 | §6.10 `/template/**` 静态资源（只读、经 `LegacySiteFiles`）；子目录与扩展名范围按 OP-T5：a = SEC-7 白名单，b = 同 PHP 放开（§6.10 b 列） | DEC-8；OP-T1；OP-T5 | 前端 |')
rep('DBT-3 `import` 只还原数据；删除 `REFUSED` 常量的四项用途 | FR-9；AC-11 | 后端 |',
    'DBT-3 `import` 只还原数据；删除 `REFUSED` 常量的四项用途；SEC-8 的文件读写拒绝规则按 OP-T5（a 有、b 无） | FR-9；AC-11；OP-T5 | 后端 |')
rep('INS-1～INS-4：空库 + SHA-256 判定后执行 `install.sql`；删除指纹只读校验分支 | FR-10；AC-12 | 后端 |',
    'INS-1～INS-4：空库 + SHA-256 判定后执行 `install.sql`（常量 `48dbe0fb…`，见 INS-1）；删除指纹只读校验分支 | FR-10；AC-12 | 后端 |')
rep('| 新增 / 修改 | 常量 = 仓库文件哈希；空库执行、已有表拒绝、哈希不同拒绝三种情况，各断言发出的语句集合 | AC-12 | 后端 |',
    '| 新增 / 修改 | 常量 = 仓库文件（git blob 字节）的哈希，且断言工作区文件不含 `\\r`（`autocrlf` 检出会让哈希变成 `152e5424…`，测试须直接失败并提示 C-67）；空库执行、已有表拒绝、哈希不同拒绝（1 字节差异、整文件改为 CRLF 两种）三种情况，各断言发出的语句集合 | AC-12 | 后端 |\n'
    '| C-67 | `.gitattributes` | 修改 | 末尾加 `php/application/install/sql/install.sql text eol=lf`（附注释：安装向导按 SHA-256 校验该文件，工作区必须与 blob 同为 LF；写法同已有的 `schema-fingerprint.txt text eol=lf`）。只改检出规则，不改 `php/` 下文件内容（blob 本来就是 LF，无需 renormalize） | INS-1；RISK-T17 | 后端 |\n'
    '| C-68 | `B/site/application/PasswordAttempts.java`；`B/site/config/SiteConfiguration.java`（装配）；`T/site/application/PlaybackPrimitivesTest.java`（`PasswordAttempts` 用例） | 按 OP-T4：a 修改 / b 删除 | a：保留类与测试，调用点从 `SiteLibraryController`/`SitePlaybackController` 的密码表单移到 `ajax/pwd`（ALIGN-4 ⑤），超限返回 PHP 已有的 1003 JSON；b：删除类、装配与其用例，`ajax/pwd` 只有 PHP 的按会话 5 秒限制 | ALIGN-4；OP-T4 | 前端 |')

# 6.8 ALIGN-4
rep_line('| ALIGN-4 | 观看密码：签名 cookie，`ajax/pwd` 返回 404 |',
         '| ALIGN-4 | 观看密码：签名 cookie，`ajax/pwd` 返回 404；按内容/全站限流 `PasswordAttempts`，超限 429 | '
         '恢复 `ajax/pwd`，判定顺序与 JSON 逐条按 PHP：① 参数校验 1001；② 本会话已通过 1002；③ 按会话 `last_pwd` 间隔 < 5 秒 1003（每次调用都刷新 `last_pwd`，含被拒的调用，同 `mac_get_time_span`）；④ 取数失败 1011/1021、密码不符 1012/1013/1014/1022；⑥ 通过后写会话并返回 `{"code":1,"msg":"ok"}`。“已通过”与 `last_pwd` 都放进程内会话（`SessionStore`，PHP 是 session，不进库、不参与比较）。'
         '⑤ **条件项（OP-T4）**：a = 在 ③ 之后、④ 查库之前再过 `PasswordAttempts`，超限返回与 ③ 相同的 `{"code":1003,"msg":lang(\'index/pwd_frequently\')}`（HTTP 200，不再用 429），与 PHP 的差异只在“哪一次请求得到 1003”，记为 SEC-9；b = 不做 ⑤，删除 `PasswordAttempts`（C-68），行为与 PHP 完全相同。OP-T4 关闭前 S3-14c 不派单、不合入 | '
         '`Ajax.php:400-456`（限制在 `:416-418`）；`common.php:306-316`；`All.php:547-549` |')

# 6.10
rep('| 扩展名白名单 | `css js png jpg jpeg gif ico svg webp woff woff2 ttf eot swf`；其他（含 `html htm ini php txt`）返回 404 |',
    '| 子目录与扩展名（**按 OP-T5 条件生效**） | a（SEC-7）：只映射 `css`、`js`、`images`、`ads` 四个子目录，扩展名白名单 `css js png jpg jpeg gif ico svg webp woff woff2 ttf eot swf`，其他（含 `html htm ini php txt`）返回 404。b（同 PHP）：`template/<目录>/` 下任意子目录与扩展名都按文件原样返回（PHP 内置服务与 nginx 对存在的静态文件直接返回，`.html` 模板源码、`info.ini` 也能取到）；只有 `.php` 返回 404（PHP 会执行而不是返回源码，Java 不执行，也不返回源码）。两种答案下路径规范化、不出站点根、只读三条都不变 |')
rep('| 响应 | `Content-Type` 按扩展名固定表；',
    '| 响应 | `Content-Type` 按扩展名固定表（b 时补 `html htm`→`text/html`、`ini txt`→`text/plain`、其他未知→`application/octet-stream`）；')

# 6.13
rep('另拒绝含 `INTO OUTFILE`、`INTO DUMPFILE`、`LOAD_FILE(` 的语句（读写服务器文件，属安全收紧，SEC-8）。',
    '**条件项（OP-T5）**：a = 另拒绝含 `INTO OUTFILE`、`INTO DUMPFILE`、`LOAD_FILE(` 的语句（读写服务器文件，安全收紧，SEC-8）；b = 不加这条，这类 `SELECT` 照常发给数据库，结果（含 MySQL 因 `it_app` 无 `FILE` 权限或 `secure_file_priv` 返回的错误）按 PHP 页面的错误展示方式输出。OP-T5 关闭前 S3-19 不派单、不合入。')

# 6.14
rep('Java 内置常量 `INSTALL_SQL_SHA256 = 152e54242d78a7c44f2030bfb0e04c0b5067c0d54180bd6cb77ddee7d3eaabcb`（= 仓库 `php/application/install/sql/install.sql` 的 SHA-256，基线提交核实；单测断言常量等于仓库文件的哈希，防漂移） |',
    'Java 内置常量 `INSTALL_SQL_SHA256 = 48dbe0fbd138f670919208d8bcc0ffe28d09b507c527d2a6a1a8f7b77ffa3aa4`（= 基线提交 `a57e9cb` 里 blob `php/application/install/sql/install.sql` 的 SHA-256，45049 字节、全部 LF、不含 `\\r`；核对命令 `git show a57e9cb2b94d3e500bea7dfe2c579daee093d969:php/application/install/sql/install.sql \\| sha256sum`）。`core.autocrlf=true` 的 Windows 工作区里该文件被检出为 CRLF（45950 字节，SHA-256 `152e5424…`，v3 误用了这个值），所以 C-67 用 `.gitattributes` 固定 LF；单测断言常量等于仓库文件的哈希且文件不含 `\\r`，防漂移 |')
rep('③读文件算 SHA-256，≠ 常量 → 提示“文件哈希不一致”，不执行；',
    '③读文件按字节算 SHA-256（不做换行归一化），≠ 常量 → 提示“文件哈希不一致”，不执行（CRLF 检出的同一文件也走这一支）；')

# 6.16
rep('六项安全修复（§6.17）和本设计列出的安全收紧（SEC-7、SEC-8）判 `排除-D2` / `排除-<本设计编号>` 时须经发起人按 OP- 确认，不能自行判“已对齐”；',
    '六项安全修复（§6.17 SEC-1～6）判 `排除-D2`；本设计的条件安全收紧 SEC-7、SEC-8（OP-T5）、SEC-9（OP-T4）只有在对应 OP 关闭且选 a 时，才按“已确认的安全差异”判 `排除-SEC-7` / `排除-SEC-8` / `排除-SEC-9`，报告引用关闭该 OP 的 Issue 评论；选 b 时不排除，按 PHP 逐字节比较；OP 未关闭时相关行一律记“未判定”，不能自行判“已对齐”或“排除”；')

# 6.17
rep_line('| SEC-7（本设计） |',
    '| SEC-7（本设计，条件：OP-T5 a） | `/template/**` 只给静态资源，不给 `.html`/`.ini` | DEC-8、§6.10 | a：不进比较样本，FR-12 相关行按“已确认的安全差异”判 `排除-SEC-7`；b：不存在本项，按 PHP 返回 |')
rep_line('| SEC-8（本设计） |',
    '| SEC-8（本设计，条件：OP-T5 a） | 执行 SQL 拒绝 `INTO OUTFILE/DUMPFILE`、`LOAD_FILE` | DBT-2 | a：`排除-SEC-8`；b：不存在本项，语句照常执行 |\n'
    '| SEC-9（本设计，条件：OP-T4 a） | 观看密码在 PHP 按会话 5 秒限制之外，再按内容/全站限流（`PasswordAttempts`），超限返回 PHP 已有的 1003 | ALIGN-4 ⑤ | a：只有“超限”样本与 PHP 不同，判 `排除-SEC-9`；b：不存在本项 |')
rep('\n\n验证：每项在所属批次',
    '\n\nSEC-7～SEC-9 不属于 OP-9 已确认的六项，是本设计提出、待发起人确认的行为差异；V-24 的“仍通过”只对 SEC-1～6 无条件适用，SEC-7～9 的用例按所选答案写（V-18、V-23、V-30）。\n\n验证：每项在所属批次')

# 7.4
rep('| S3-13 | `/template/**` 静态资源 + 内置视图 | 前端 | C-39、C-43 | S3-10 | V-18 |',
    '| S3-13 | `/template/**` 静态资源 + 内置视图 | 前端 | C-39、C-43 | S3-10；**OP-T5 已关闭** | V-18（按 OP-T5 所选列） |')
rep('c：PG-2 vod 详情/播放/下载/播放器/版权 + ALIGN-4；',
    'c：PG-2 vod 详情/播放/下载/播放器/版权 + ALIGN-4 + C-68（**须 OP-T4 已关闭**）；')
rep('| S3-11、S3-12 对应标签、S3-13 | 本组样本 V-19 子集通过 |',
    '| S3-11、S3-12 对应标签、S3-13；c 另需 OP-T4 已关闭 | 本组样本 V-19 子集通过；c 另需 V-30 |')
rep('| S3-19 | 数据库工具四项 | 后端 | C-53～C-55 | G1（不依赖模板） | V-23 |',
    '| S3-19 | 数据库工具四项 | 后端 | C-53～C-55 | G1（不依赖模板）；**OP-T5 已关闭** | V-23（按 OP-T5 所选列） |')
rep('| S3-20 | 安装向导建表 | 后端 | C-56、C-57 | G1 | V-25 |',
    '| S3-20 | 安装向导建表 | 后端 | C-56、C-57、C-67 | G1 | V-25 |')
rep('并行关系：S3-19、S3-20、S3-21、T-FR12 与模板线（S3-01～S3-18）互不依赖，可并行；',
    '**条件门禁（OP-T4、OP-T5）**：S3-13、S3-19 依赖 OP-T5，S3-14c 依赖 OP-T4。对应 OP 未关闭时调度不建单、不派单，已开工的也不得合入；建单时在单内写明所选答案与关闭该 OP 的 Issue 评论，实现者只实现所选那一列，评审按该列核对（V-29）。选 b 时改回 PHP 的改动只在 C-43、C-53、C-68 三处，不影响其他子任务；S3-14c 之外的 PG 组、S3-10 之前的模板线都不受这两项影响。\n\n'
    '并行关系：S3-19、S3-20、S3-21、T-FR12 与模板线（S3-01～S3-18）互不依赖，可并行；')

# 8.2
rep('| V-18 | DEC-8、SEC-7 | 接口用例 | `/template/default_pc/css/style.css` 200 且字节等于文件；`/template/default_pc/html/index/index.html`、`/template/default_pc/info.ini`、`/template/../application/extra/maccms.php`、`/template/default_pc/css/%2e%2e/info.ini` 均 404；`/template/default_wap/ads/x.js` 404 且不创建目录 | G2 |',
    '| V-18 | DEC-8、SEC-7（OP-T5） | 接口用例 | 两种答案都要：`/template/default_pc/css/style.css` 200 且字节等于文件；`/template/../application/extra/maccms.php`、`/template/default_pc/css/%2e%2e/%2e%2e/%2e%2e/application/extra/maccms.php` 404（不出站点根）；`/template/default_wap/ads/x.js` 404 且不创建目录。OP-T5 a：`/template/default_pc/html/index/index.html`、`/template/default_pc/info.ini`、`/template/default_pc/css/%2e%2e/info.ini` 均 404。OP-T5 b：这三个地址 200 且字节等于文件；`template/` 下任一 `.php` 404 | G2 |')
rep('| V-23 | AC-11 | 单测 + MySQL 集成 + 对照 | `SqlStatementsTest`：五类结构语句、注释/版本注释、`INTO OUTFILE` 全拒且分类器在执行前返回；',
    '| V-23 | AC-11、SEC-8（OP-T5） | 单测 + MySQL 集成 + 对照 | `SqlStatementsTest`：五类结构语句、注释/版本注释全拒且分类器在执行前返回；OP-T5 a：`SELECT … INTO OUTFILE`、`INTO DUMPFILE`、`LOAD_FILE(` 同样在执行前整批拒绝；OP-T5 b：这三类判为 `SELECT` 放行，`DatabaseToolMySqlIT` 以 `it_app` 执行后页面显示 MySQL 的拒绝错误（无 `FILE` 权限），与 PHP 同库输出一致；')
rep('`InstallSqlFileTest` + `InstallWizardMySqlIT`：空库执行后 SNAP 等于附表 A 基线；已有 26 表的库提示“已存在数据表”且无 DDL；1 字节差异的文件提示“文件哈希不一致”、`mac_` 表 0 张；',
    '`InstallSqlFileTest` + `InstallWizardMySqlIT`：常量等于 `48dbe0fbd138f670919208d8bcc0ffe28d09b507c527d2a6a1a8f7b77ffa3aa4`，也等于 `git show a57e9cb:php/application/install/sql/install.sql \\| sha256sum` 与工作区文件的 SHA-256（C-67 生效后，Windows `autocrlf=true` 检出也一致），文件不含 `\\r`；空库执行后 SNAP 等于附表 A 基线；已有 26 表的库提示“已存在数据表”且无 DDL；1 字节差异的文件、整文件改为 CRLF 的文件（SHA-256 `152e5424…`）都提示“文件哈希不一致”、`mac_` 表 0 张；')
rep('| V-29 | AC-17 | 人工核对 | 每个子任务收尾时，单内引用的 OP-/OP-T 均已关闭 | 每单 |',
    '| V-29 | AC-17 | 人工核对 | 每个子任务派单前与收尾时，单内引用的 OP-/OP-T 均已关闭；S3-13、S3-19 单内写明 OP-T5 所选答案，S3-14c 写明 OP-T4 所选答案，并附关闭该 OP 的 Issue 评论；实现与用例只含所选那一列 | 派单前 + 每单收尾 |\n'
    '| V-30 | AC-5（`ajax/pwd`）、ALIGN-4、SEC-9（OP-T4） | 接口用例 + 对照 | 两种答案都要：1001（缺参）、1002（本会话已通过后再验）、1003（同一会话 5 秒内第二次，含第一次被拒的情况）、1012/1013/1014/1022（密码错）、1（通过）与 PHP 同请求序列的 JSON 逐字节相同。OP-T4 a：每次换新会话、对同一内容在 60 秒内第 11 次请求得到 1003 且不查库（`general_log` 无该内容的 `SELECT`），HTTP 200；OP-T4 b：同一序列每次都与 PHP 相同，仓库无 `PasswordAttempts` | G2 + 对照 |')

# 9
rep('| S3-19（数据库工具） | `git revert` 即恢复“四项拒绝” | 独立 |',
    '| S3-19（数据库工具） | `git revert` 即恢复“四项拒绝” | 独立 |\n'
    '| OP-T4 / OP-T5 的答案事后改变 | 只改 C-43（静态资源范围）、C-53（SEC-8 一条规则）、C-68（`PasswordAttempts` 一个调用点）各自一处，单独提交，可单独 `git revert`；不涉及数据 | 独立 |')

# 10 risk
rep('| RISK-T16 | “启动时读一次配置”的站点 Bean',
    '| RISK-T17 | `install.sql` 在 Windows `core.autocrlf=true` 工作区被检出为 CRLF，SHA-256 与常量不符，安装向导拒绝执行（v3 的 `152e5424…` 就是这样算错的） | 在 Windows 上 clone 后直接运行或打包站点根 | C-67 `text eol=lf`；INS-1 常量按 blob 计算；C-57 断言文件不含 `\\r`；V-25 覆盖 CRLF 拒绝 | 已有 Windows 工作区在 C-67 合入后须删掉该文件再 `git checkout -- php/application/install/sql/install.sql` 一次（S3-20 交付说明写明） |\n'
    '| RISK-T16 | “启动时读一次配置”的站点 Bean')

# 10 OP-T4 / OP-T5
i = s.index('| OP-T4 | 观看密码的频率限制')
j = s.index('\n', s.index('| OP-T5 |', i))
s = s[:i] + (
    '| OP-T4 | 观看密码在 PHP 已有的“按会话 5 秒一次”限制（`Ajax.php:416-418`，1003）之外，是否再保留 Java 的按内容/全站限流 `PasswordAttempts`（SEC-9）。PHP 的 5 秒限制换一个会话就能绕过；`PasswordAttempts` 能限住换会话的爆破，代价是同一内容被大量试错时其他访客 60 秒内也会收到 1003 | **待发起人确认**。a（设计建议）：保留，超限返回 PHP 已有的 1003，按 SEC-9 记为已确认的安全差异；b：删除 `PasswordAttempts`，只保留 PHP 的 5 秒限制，与 PHP 完全相同（C-68）。两种答案下 ALIGN-4 ①～④⑥ 都按 PHP 恢复 | **是**：阻塞 S3-14c 的派单与合入；不阻塞其他子任务 | 发起人（经调度） | S3-14c 派单前 |\n'
    '| OP-T5 | SEC-7（`/template/**` 不返回 `.html`/`.ini` 等模板源码与配置）、SEC-8（执行 SQL 拒绝 `INTO OUTFILE/DUMPFILE`、`LOAD_FILE`）是六项安全修复之外的安全收紧，与“PHP 怎么做 Java 就怎么做”不同 | **待发起人确认**。a（设计建议）：接受两项收紧，按 SEC-7、SEC-8 记为已确认的安全差异，相关地址与语句不进页面比较样本；b：改回 PHP，`/template/**` 按文件原样返回（`.php` 除外），执行 SQL 不加文件读写规则（§6.10、§6.13 的 b 列；改动只在 C-43、C-53）。两项一起答；若要分开答，按 SEC-7、SEC-8 各自选 a/b，设计两列互不依赖 | **是**：阻塞 S3-13、S3-19 的派单与合入；不阻塞其他子任务 | 发起人（经调度） | S3-13、S3-19 派单前 |'
) + s[j:]

# 11
rep('| C-36～C-40、C-43、C-46 | V-10、V-19 |', '| C-36～C-40、C-43、C-46、C-68 | V-10、V-19、V-30 |')
rep('| FR-9 / AC-11 | DBT-1～DBT-4 | C-53～C-55 | V-23 |', '| FR-9 / AC-11 | DBT-1～DBT-4；DBT-2 的 SEC-8 按 OP-T5 | C-53～C-55 | V-23 |')
rep('| FR-10 / AC-12 / RISK-15 | INS-1～INS-4；§4.10 | C-44、C-56、C-57 | V-25 |', '| FR-10 / AC-12 / RISK-15 | INS-1～INS-4（常量 `48dbe0fb…`）；§4.10；RISK-T17 | C-44、C-56、C-57、C-67 | V-25 |')
rep('| AC-17 | 每个 S3 子任务引用的 OP-T 已关闭（OP-T4、OP-T5 有期限） | — | V-29 |', '| AC-17 | 每个 S3 子任务引用的 OP-T 在派单前已关闭：OP-T4 → S3-14c；OP-T5 → S3-13、S3-19；未关闭不派单、不合入（§7.4 条件门禁） | — | V-29 |')
rep('| BR-4 / OP-9 | §6.17 SEC-1～6 不改；ALIGN 只改呈现 | — | V-24 |', '| BR-4 / OP-9 | §6.17 SEC-1～6 不改；ALIGN 只改呈现；SEC-7～9 是待确认的条件差异，不计入六项 | — | V-24；SEC-7～9 见 V-18、V-23、V-30 |')

# 12
rep('| 与 PRD 无矛盾（完整设计） | 有两项待确认，已列 OP-T4、OP-T5（不阻塞开发）；',
    '| 完整设计：常量与哈希按 blob 计算 | 是（v4 修正） | `INSTALL_SQL_SHA256` 按 `git show a57e9cb:php/application/install/sql/install.sql \\| sha256sum` = `48dbe0fb…`；v3 的 `152e5424…` 是 `autocrlf` 工作区文件（CRLF）的哈希，已更正并加 C-67、RISK-T17 |\n'
    '| 完整设计：PHP 行为描述有源码出处 | 是（v4 修正） | v3 写“PHP 没有观看密码频率限制”不成立：`Ajax.php:416-418` 有按会话 5 秒限制；v4 把它列为 ALIGN-4 ③（按 PHP 恢复），OP-T4 只问是否在其之外保留 `PasswordAttempts` |\n'
    '| 与 PRD 无矛盾（完整设计） | 有两项待发起人确认，已列 OP-T4、OP-T5，各自阻塞 S3-14c、S3-13/S3-19 的派单与合入（§7.4 条件门禁），其余子任务不受影响；')

# 13
rep('## 14. 修订记录',
    'S1 完整设计独立复核（智能调度，对象 v3 `0dd59f5f72b67e286a93a60c25f19c03f9c91990`，评论 `01a19485-64c8-7afa-93c6-1c1061354ac5`）：\n\n'
    '| REV- | 处理 | 说明 | 修改位置 |\n| --- | --- | --- | --- |\n'
    '| REV-3-001 安装 SQL 哈希错误 | 已修改 | 复核结论正确。原因：v3 对 Windows `core.autocrlf=true` 工作区里的文件算了哈希（CRLF，45950 字节，`152e5424…`），基线 blob 是 LF（45049 字节，`48dbe0fb…`）。常量改为 `48dbe0fbd138f670919208d8bcc0ffe28d09b507c527d2a6a1a8f7b77ffa3aa4`；新增 C-67（`.gitattributes` 固定该文件 LF）与 RISK-T17；C-57、V-25 增加“不含 `\\r`”断言与“整文件 CRLF → 哈希不一致”用例；空库执行、已有表拒绝两种用例不变 | §6.14 INS-1、INS-2 ③；§5.2 C-56、C-57、C-67；§7.4 S3-20；§8.2 V-25；§10 RISK-T17；§11 FR-10；§12 |\n'
    '| REV-3-002 OP-T4/OP-T5 状态与门禁矛盾 | 已修改 | 两项改为“待发起人确认、阻塞对应子任务”；实现与验证都写成 a/b 两列：OP-T4 → ALIGN-4 ⑤、C-68、V-30、S3-14c；OP-T5 → §6.10、DBT-2、C-43、C-53、V-18、V-23、S3-13、S3-19。§7.4 新增条件门禁：OP 未关闭不派单、不合入，单内写明所选答案；选 b 改回 PHP。SEC-7～9 从“与六项同列的修复”改为条件差异，V-24 只对 SEC-1～6 无条件适用；FR-12 判定口径改为“OP 关闭且选 a 才判排除，否则按 PHP 比较” | §4.8；§5.2 C-37、C-43、C-53、C-68；§6.8；§6.10；§6.13；§6.16；§6.17；§7.4；§8.2 V-18、V-23、V-29、V-30；§9；§10 OP-T4/T5；§11 FR-5、FR-9、AC-17、BR-4；§12 |\n'
    '| （自查）OP-T4 前提写错 | 已修改 | 修 REV-3-002 时核对 `Ajax.php:400-456`：PHP **有**按会话 5 秒的观看密码限制（`:416-418`，返回 1003），v3“PHP 没有频率限制”不成立。v4 把这条限制列入 ALIGN-4 ③，两种答案下都按 PHP 恢复；OP-T4 改问“是否在其之外再保留 `PasswordAttempts`”，a 时超限也返回 PHP 已有的 1003，不再用 429 | §1.3；§6.8 ALIGN-4；§10 OP-T4 |\n\n'
    '## 14. 修订记录')

# 14
rep('关闭 OP-T1～T3，新增 OP-T4、OP-T5 | — |',
    '关闭 OP-T1～T3，新增 OP-T4、OP-T5 | — |\n'
    '| 2026-11-01 | v4 | @架构师-海外 | INS-1 常量改为 blob 哈希 `48dbe0fb…`，新增 C-67、RISK-T17；OP-T4/OP-T5 改为阻塞对应子任务的条件项，§6.8/§6.10/§6.13 写 a/b 两列，新增 SEC-9、C-68、V-30 与 §7.4 条件门禁；更正“PHP 没有观看密码频率限制”，ALIGN-4 按 PHP 恢复按会话 5 秒限制 | REV-3-001、REV-3-002 |')

io.open(p, 'w', encoding='utf-8', newline='\r\n').write(s)
print('ok')
