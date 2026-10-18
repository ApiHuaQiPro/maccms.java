## B08 会员登录态、验证码与频率限制：契约、黄金向量与差异（路线图 B08，MACC-32）

本批按路线图 `09-roadmap.md` 第 5.3 节 B08 行与 03 草稿第 4.3 (4)(6) 节实施：会员 cookie 的校验与签发（只读校验，签发能力留给 B09 使用）、会员组到期的内存判定、本地验证码、`mac_get_time_span` 语义的搜索频率限制（会话在进程内存）。**不写库**：账号写入归 B09，到期降级的批量任务归 B10（见第 7 节 D-B08-6）。

### 0. 起点、分支与提交

| 项 | 值 |
|---|---|
| 运行起点 | 平台基线 `32edd6b`（本任务工作区的快照提交），其 tree 与已验收 main 相同 |
| 已验收 main | `b645b53b1b680080637393df943ae859f11375ac`（B06 + MACC-55），以普通 merge `aa2e452` 并入运行起点 |
| 分支 | `agent/claude/macc-23`（本任务工作区；只用普通提交与 merge，没有 reset/rebase/amend/force） |
| 提交 | 见交付评论（最终 SHA/tree 以评论为准） |

### 1. 范围

| 在本批 | 不在本批（归属） |
|---|---|
| `common.web.PhpCookies`：PHP 兼容的 `Cookie` 头解析与 `Set-Cookie` 写出 | 登录、注册、注销、改密、找回（B09，含 D2 ①②⑥） |
| `member` 模块：`MemberSessionService`（按旧 `label_user` + `checkLogin` 判定会员，只读）、`MemberCookies`（旧格式的登录/注销/回写 cookie，供 B09）、`UserCheck` | 会员组到期的**写库**降级（B10 的批量任务；`mac_user` 的写入方登记见 12 文档第 3.1 节） |
| `common.session`：进程内会话（有界、空闲过期、只认本进程签发的标识）与 `mac_get_time_span` | `ajax/verify_check` 与 `show_verify`/`search_verify` 的放行（B11，15 文档第 7.4 节；在此之前仍按 B05 的临时关闭处理） |
| `common.captcha`：本地验证码（参数取 `extra/captcha.php`）、`verify/index`、`verify/check` | 评论/留言的验证码与 cookie 计时（B11）；后台验证码（B12） |
| 搜索频率限制：视频与各模块的搜索页、ajax_search 的第 1 页（旧 `search_timespan`） | 任何页面上的会员显示、权限按会员组判定（B09/B10 使用 `MemberSessionService`） |

客户端 IP：本批没有用到 IP 的逻辑（旧版的搜索计时、验证码都按会话，不按 IP），没有新增 IP 解析；B09 记录登录 IP 时复用 B06 的 `ClientIpResolver`（`common.clientip`，信任边界不变）。也没有新增按 IP 的限流——那属于旧版没有的功能。

### 2. 接口

#### 2.1 `common.web.PhpCookies`（纯计算）

- `parse(List<String> cookieHeaders)` / `parse(String)` → `Map<名, Value>`，`Value(bytes, array)`：复刻 PHP 填充 `$_COOKIE` 的规则——只按 `;` 分段（逗号不是分隔符）；名前空白去掉，名中的空格、点、未配对的 `[` 变成 `_`；名为空或以 `[` 开头的段忽略；`name[...]` 是数组（只标记）；同名标量**先到先得**，数组覆盖先到的标量、标量不覆盖先到的数组；名不做 URL 解码；值按 PHP 8.0 `rawurldecode`（`%XX`，`+` 不变）。
- `setCookie(name, value, expires, now, path)`、`deleteCookie(name, path)`：PHP 8.0 `setcookie` 的头格式 `name=rawurlencode(value); expires=D, d-M-Y H:i:s GMT; Max-Age=N; path=/`；删除写成 `name=deleted; expires=Thu, 01-Jan-1970 00:00:01 GMT; Max-Age=0; path=/`。
- `rawUrlEncode`、`rawUrlDecode`、`urlDecode`（PHP 三个函数的字节语义）。

#### 2.2 `member` 模块

- `MemberSessionService.resolve(Map<String, PhpCookies.Value>) → MemberState`：
  1. `label_user` 前置判断：`intval(user_id)`、`user_name`、`user_check` 三者在 PHP `empty()` 意义下都非空（`"0"` 也算空）；数组形式的值按游客。
  2. 三个值各做 `htmlspecialchars(urldecode(trim()))`（PHP 7.3/8.0 默认 `ENT_COMPAT`，只转 `& " < >`；非法 UTF-8 → 空串）；任一为空 → 游客。
  3. `SELECT … FROM mac_user WHERE user_id = ? AND user_name = ? AND user_status = 1 LIMIT 1`，两个参数都按字符串，比较交给 MySQL（与旧版 PDO 绑定字符串相同：`1abc`、`01`、`1.0`、`0.1e1`、`' 1'` 都等于 1；`user_name` 按列排序规则不分大小写、忽略尾部空格）。值含 4 字节字符时不查库，直接游客（utf8 列存不下）。
  4. 库里 `user_random` 为空 → 游客（D-B08-2）；`md5(库 user_random-库 user_name-库 user_id-)` 与 cookie 逐字节相同才算登录（D-B08-1）。
  5. 有效会员组：库里 `group_id > 2` 且 `user_end_time < 现在`（秒；等于现在**不**到期）→ 2 组，`expiredInMemory=true`，不写库、不发 cookie（D-B08-3）。组行按有效组取（不看 `group_status`，与旧 `Group::getCache` 相同）；库里没有该组 → `group=null`。
- `MemberState(member, userId, userName, groupId（有效）, storedGroupId, group, userEndTime, expiredInMemory)`；游客为 `userId=0`、`groupId=1`、`group=1 组`。
- `MemberCookies.login(userId, userName, groupId, groupName, userRandom, portrait, now)`：六个 `Set-Cookie`（`user_id`、`user_name`、`group_id`、`group_name`、`user_check`、`user_portrait`），30 天，`path=/`，不设 domain，不加 HttpOnly/Secure/SameSite——与旧版相同（03 第 4.3 (4) 节的兼容条件）。`logout()`、`guestReset()`（旧 `label_user` 失败时只回写 `user_id=0`、`user_name=游客` 两个会话 cookie）、`groupRefresh()`（旧到期降级后重发的两个）。本批没有任何响应调用它们。
- `MemberMapper`（只读，`mapper/member/MemberMapper.xml`）：`selectActiveUser`、`selectGroup`。

#### 2.3 `common.session` 与站点会话 cookie

- `SessionStore`：标识为 128 位随机数的 32 位小写十六进制；只认本进程签发且未过期的标识（客户端自带的未知标识一律换新；旧 PHP 默认会沿用客户端给的标识）；空闲 30 分钟失效（不短于验证码有效期）；最多 100 000 个会话，满时先清过期的、再淘汰最久未访问的。重启即全部失效。
- `SessionState.timeSpan(key, now)`：旧 `mac_get_time_span`——返回 `现在 − 上次`（没有记录时上次按 `1228348800`），**无论调用方是否放行**都把上次改为现在；读改写在会话对象上同步，同一会话的并发请求串行（与旧会话文件锁效果相同）。
- 站点会话 cookie（`site.web.SiteSessions`）：名称沿用 `PHPSESSID`（已有的代理/CDN 规则可继续按它识别有会话的请求），值与旧 PHP 会话不通用；`Path=站点根`、`HttpOnly`、`SameSite=Lax`、站点地址为 https 时 `Secure`；会话 cookie（无有效期）。只在需要写会话时（签发验证码、第 1 页搜索计时）才新建。

#### 2.4 本地验证码（`common.captcha` + `site.web.SiteVerifyController`）

- 参数：think-captcha 默认值，由 `extra/captcha.php` 覆盖（仓库值 `codeSet=1234567890`、`length=4`、`fontSize=16`、无杂点无曲线 → 图片 128×40）；宽高为 0 时按旧公式 `length×fontSize×1.5 + length×fontSize/2`、`fontSize×2.5`。`useZh=true`、码表含非 ASCII 可见字符、码长 1～16 或字号 4～100 以外 → 启动失败（信息只含键名）。没有旧配置目录时用仓库值；目录里没有 `captcha.php` 时用 think-captcha 默认值。
- 校验：旧 `captcha_check()` 不传配置，所以有效期恒为 1800 秒、通过后作废。规则见 `Captchas` 类注释，由黄金向量锁定；唯一差异是猜错也作废（D-B08-4）。
- `GET/HEAD /index.php/verify/index[.html|/]`（及去掉 `/index.php` 的形式）：PNG、no-store、nosniff；可选 `?id=`（`[A-Za-z0-9_-]{0,32}`，否则 404）选择验证码槽。
- `GET/HEAD/POST /index.php/verify/check[.html|/]?verify=…[&id=…]`：正文 `1`/`0`（`text/html`），同名多值取最后一个；没有有效会话时 `0`。

#### 2.5 搜索频率限制（`site.web.SearchThrottle`）

- 取值 `app.search_timespan`（启动时读一次；数值或数字字符串，空/非数字/≤0 → 不限制；没有旧配置目录 → 不限制）。
- 位置：视频搜索页与 ajax_search、各模块（文章/专题/演员/角色/网址/剧情）搜索页与 ajax_search；路由与参数解析成功、**第 1 页**时计时（旧 `check_search`：`page==1 && mac_get_time_span("last_searchtime") < search_timespan`）。
- 过快 → 429、`Retry-After: ⌈search_timespan⌉`、no-store、noindex 的提示页（沿用现有错误页样式），正文为旧语言包原文"请不要频繁操作，搜索时间间隔为3秒前"；不查库。

### 3. PHP 黄金向量

- 生成：`backend/src/test/php/member_golden.php`（父进程：一次性库 `maccms_it_*` 导入 `install.sql` + 27 个合成会员，逐用例以 php-cgi 执行 `member_golden_child.php`；子进程只读加载 `thinkphp/`、`application/` 与 `vendor/topthink/think-captcha`，调用旧代码本身：经反射调用 `All::label_user`、`User::login`、ThinkPHP `cookie()`、`Captcha::check/entry`、`mac_get_time_span`、`User::expire`）。PHP 自己解析 `HTTP_COOKIE`，所以 cookie 的拆分与解码也是真实行为。用完删库与临时目录。
- 输出：`backend/src/test/resources/member/php80-golden.json`（PHP 8.0.30）、`php73-golden.json`（PHP 7.3.33），字符串一律十六进制字节。各 40 个 cookie 解析、17 个写出（含注销与失败回写）、74 个 `label_user`（含 7 个到期边界）、12 个新版签发 cookie 的复放、10 个真实登录及其复放、14 组验证码校验序列、4 个图片尺寸、8 个 `mac_get_time_span`、1 组 `expire` 边界。
- 命令（仓库根目录；需按 AGENTS.md 恢复 `vendor/`，管理账号只经环境变量）：
  `PHP_CGI=D:/Tools/php/8.0/php-cgi.exe D:/Tools/php/8.0/php.exe backend/src/test/php/member_golden.php > backend/src/test/resources/member/php80-golden.json`（7.3 同理）。
- `member/java-issued.json`：`JavaIssuedCookiesTest` 用 `MemberCookies.login` 为合成会员签发、按浏览器回送形式拼好的 Cookie 头（`MEMBER_GOLDEN_WRITE=1` 时重写）；黄金脚本把它们原样交给旧 `label_user`，结果在 `java_issued` 段。

**黄金揭示的旧行为（新版照搬，除第 5 节的有意差异外）**：

| 输入 | 旧版结果 |
|---|---|
| 库里存 `a&amp;b`（后台建号的 `&`）/ `a&b` / `q"x` / `x<y` | 旧版签发的 cookie 自己也不认（`checkLogin` 再做一次 `htmlspecialchars`），**每次请求都是游客**；反而 cookie 写成 `a%26b` 时能登录 |
| 库里存 `a+b`、`a%41` | 不认（`checkLogin` 再做一次 `urldecode`） |
| 库里存 `pad `（尾部空格）；cookie 里的用户名大小写不同（`ALICE`）或带首尾空格（`%20alice`） | 认（trim；MySQL 忽略尾部空格、不分大小写；md5 用库里的原值） |
| `user_id` 为 `01`、`1abc`、`1.0`、`0.1e1`、`%2B1`、`%201` | 认；`1.5`、`0x1`、`%2531`、`1e999` 不认 |
| `user_check` 后接 `%20` 或首字母写成 `%65`、`%2565` | 认（trim、两次解码）；后接 `+`：8.0 不认，7.3 认 |
| 同名 cookie 重复 | 第一个生效 |
| 数组 cookie（`user_id[]=1`） | 8.0：TypeError（500）；7.3：游客 |
| 会员组到期 | `checkLogin` 用 `<`：`user_end_time == 现在` 不到期；`User::expire` 批量降级用 `<=`：等于现在**降级**（`expire` 段：offset 0 → 2 组）；组 ≤2 不动；组 9（不存在）>2 照样降级 |
| `label_user` 失败回写 | 只写 `user_id=0`、`user_name=游客`；第三句 `cookie('user_check','')` 是读取 |
| 写出编码 | 8.0 `rawurlencode`（空格 `%20`、`~` 原样）；7.3 `urlencode`（空格 `+`、`~` 为 `%7E`） |
| 验证码 | 输入空或 `0` → 不通过且不消耗；1800 秒内有效（等于 1800 有效，1801 过期并删除）；通过即作废；**猜错不作废**；比较前只转大写、不去空白；分槽 |

### 4. Cookie 互通

| 方向 | 证据 |
|---|---|
| PHP → Java | `MemberGoldenMySqlIT` ③：PHP 8.0/7.3 真实 `User::login` 签发、原样回送的 cookie，新版判定与 PHP 自己的判定相同（含 `a&b` 两边都不认）；① 74 个 `label_user` 用例逐条对照 |
| Java → PHP | `MemberCookiesGoldenTest`：`MemberCookies.login` 与 PHP 8.0 登录写出的六个 `Set-Cookie` 除 `Max-Age`（PHP 每个头取 `expires − time()`，可能跨秒）外逐字节相同；`JavaIssuedCookiesTest`：新版签发的 12 组 cookie 交给旧 `label_user`，**PHP 8.0 与 7.3 都认**（`a&amp;b` 例外，旧版自己的也不认） |
| 属性 | 同名、`path=/`、不设 domain、30 天；新版没有给会员 cookie 加 HttpOnly/Secure（03 第 4.3 (4) 节：`home.js` 读其中 5 个；`user_check` 加 HttpOnly 需 PHP 同步，属 C3，未批准） |
| 版本选择 | 解析按 PHP 8.0（`+` 不解码，只影响首尾带 `+` 的伪造值）；写出按 PHP 8.0（`rawurlencode`）。两版 PHP 都能读新版写出的值（%XX 两版解码相同），回滚到任一版本都不会让新版签发的 cookie 失效 |

### 5. 与旧版的差异

| # | 旧版 | 新版 | 性质 |
|---|---|---|---|
| D-B08-1 | `checkLogin` 用 `!=` 比较 `user_check`：两边都是数值串时按数值比较。黄金：`user_random=b08ma4ctsl`、`magic`、21 的校验值是 `0e580925…`（"0e+数字"），cookie 写 `0e0`、`00`、`0e999`、`0.0` 都能登录 | 逐字节相同才登录（常量时间比较） | 安全修复，正常 cookie 不受影响 |
| D-B08-2 | 注册后从未登录（注册不写 `user_random`，默认空串）或后台建的会员，任何人都能用 `md5("-用户名-ID-")` 登录（黄金 `neverlogin-forged`） | 库里 `user_random` 为空时不认任何 cookie | 安全修复：旧版正常签发的 cookie 都带非空 random（登录时写入），不受影响 |
| D-B08-3 | 到期时 `checkLogin` 写库 `group_id=2` 并重发 `group_id`/`group_name` cookie；那一次请求里 `$info['group_id']` 仍是原组 | 只在内存中判定（`groupId=2`、`storedGroupId` 为原组），不写库、不发 cookie；写库降级由 B10 的批量任务执行（`<=` 边界见第 3 节） | 按 03 草稿 3.3.1 与 B07 写入方矩阵；对页面的可见效果与旧版"降级后"相同 |
| D-B08-4 | 验证码猜错不作废，30 分钟内可对同一个 4 位数字码无限次尝试（≤10 000 次必中） | 猜错即作废，必须换新图 | 安全修复；用户输错时需刷新验证码（前端本来就会刷新） |
| D-B08-5 | 验证码字形来自 vendor 里的 TTF 字体（不入 git），`useImgBg` 的背景目录在 vendor 中并不存在 | 内置笔画字形（0-9、A-Z，小写按大写画），不加载字体文件；`useImgBg`、`fontttf`、`bg` 忽略；`useZh=true` 启动失败 | 实现差异，尺寸与校验语义不变 |
| — | 数组形式的会员 cookie：8.0 抛 TypeError（500） | 游客（同 7.3） | 不保留 PHP 8 的类型错误（同 B06 D-B06-8 的取向） |
| — | 会话：PHP 会话文件、沿用客户端给的会话标识、cookie 不带 HttpOnly | 进程内、有界、只认本进程签发的标识，HttpOnly + SameSite=Lax（https 加 Secure），Path 为站点根；重启后验证码与搜索计时清零 | 实现差异 + 加固 |
| — | 搜索过快：200 的跳转提示页；参数不合法的请求也计时；`search_timespan=0` 也开会话 | 429 + Retry-After 的提示页；400/404 不计时；未启用时不建会话 | 实现差异 |
| — | 站点关闭（`site_status=0`）时 `verify/*` 仍可用（Verify 不继承前台 Base） | 与其他站点页面一样返回维护页 503 | 站点关闭时没有需要验证码的页面可用 |
| — | `verify/check` 缺少 `verify` 参数：ThinkPHP 报错页 | 正文 `0` | 非法参数差异 |

### 6. 测试与覆盖

| 内容 | 测试 |
|---|---|
| cookie 解析与写出 | `PhpCookiesGoldenTest`（8.0 逐项相同；7.3 只差 `+`；写出逐字节；注销头；日期格式） |
| 会员 cookie 签发、注销、回写、到期重发、`UserCheck` 与宽松比较 | `MemberCookiesGoldenTest` |
| Java → PHP 互认 | `JavaIssuedCookiesTest`（资源文件与当前代码一致；两版 PHP 复放结果） |
| 会员判定（真实 MySQL 比较语义）、到期边界、PHP → Java、并发、不写库 | `MemberGoldenMySqlIT`（`-Pmysql-it`；每段后 `mac_user`/`mac_group` 与导入后快照逐行相同） |
| 会话、`mac_get_time_span`、并发只放行一个、被拒也刷新计时与恢复、有界淘汰、空闲过期、伪造标识 | `SessionStoreTest` |
| 验证码校验序列、过期、重放、尺寸公式、配置校验、字母大小写 | `CaptchasTest` |
| `verify/index`、`verify/check`（GET/POST、分槽、过期、伪造会话）、搜索 429/Retry-After/不查库/第 2 页不计时/ajax 与模块搜索/恢复/无 cookie 不受限/并发 16 个只放行 1 个 | `SiteVerifyThrottleMvcTest` |
| 真实 Tomcat 原始套接字：会话 cookie 往返、子目录与 https 的 Path/Secure、PHP 风格 Cookie 头、HEAD | `SiteVerifyRealHttpTest` |
| 依赖边界（site 新增 5 个 common 类型） | `ModuleBoundaryTest`，同步 02 规范第 2 节 |

### 7. 待队长裁定

| # | 事项 | 本批做法 |
|---|---|---|
| D-B08-6 | 路线图 B08 行写有"批量降级语句"，但 B07 写入方矩阵只允许 B09 起写 `mac_user`，路线图 B10 行写明"会员组到期降级批量任务"，队长本批指示"不扩大业务写权限" | 本批不写任何降级语句，只用黄金锁定旧 `expire` 的 `<=` 边界（第 3 节）供 B10 使用；回滚演练改为证明本批零写入（第 8 节） |
| D-B08-1～5 | 第 5 节 | 已实施，请确认 |

### 8. 回滚

- 代码：本批合并后如需回退，`git revert -m 1 <合并提交>`；不涉及数据库结构、数据迁移或配置格式。
- 数据：本批不写任何表。`MemberGoldenMySqlIT` 在一次性库上做了"零写入"演练：导入后快照 S0，复放全部用例后 `mac_user`/`mac_group` 与 S0 逐行比较为空差异，然后删库并确认库已不存在。
- 运行状态：会话只在进程内存，重启即清空；回滚到旧 PHP 时，新版签发的会员 cookie（B09 起才会签发）仍被旧 PHP 认（第 4 节）。
