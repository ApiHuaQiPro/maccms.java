## B08 会员登录态、验证码与频率限制：契约、黄金向量与差异（路线图 B08，MACC-32）

本批按路线图 `09-roadmap.md` 第 5.3 节 B08 行与 03 草稿第 4.3 (4)(6) 节实施：会员 cookie 的校验与签发（只读校验，签发能力留给 B09 使用）、会员组到期的内存判定、本地验证码（含中文 `useZh`）、`mac_get_time_span` 语义的搜索频率限制（会话在进程内存）。**不写业务库**：到期降级语句只在测试侧演练（第 7 节），正式写库——`checkLogin` 单行降级与组/游客 cookie 回写、页面会员权限接线归 B09，批量到期任务归 B10，后台会员列表第 1 页 `expire()` 归 B14；账号写入归 B09。

### 0. 起点、分支与提交

| 项 | 值 |
|---|---|
| 运行起点 | 平台基线 `32edd6b`（本任务工作区的快照提交），其 tree 与已验收 main 相同 |
| 已验收 main | `b645b53b1b680080637393df943ae859f11375ac`（B06 + MACC-55），以普通 merge `aa2e452` 并入运行起点 |
| 分支 | `agent/claude/macc-23`（本任务工作区；只用普通提交与 merge，没有 reset/rebase/amend/force） |
| 提交 | 见交付评论（最终 SHA/tree 以评论为准） |
| 修正轮（第 9 节） | 队长裁定以 `333b73e` 为唯一修正基线（`2e6f5a6` 因接受空 `user_random` 伪造登录而排除，只移植其测试侧降级演练），在其上普通提交 |

### 1. 范围

| 在本批 | 不在本批（归属） |
|---|---|
| `common.web.PhpCookies`：PHP 兼容的 `Cookie` 头解析与 `Set-Cookie` 写出 | 登录、注册、注销、改密、找回（B09，含 D2 ①②⑥） |
| `member` 模块：`MemberSessionService`（按旧 `label_user` + `checkLogin` 判定会员，只读）、`MemberCookies`（旧格式的登录/注销/回写 cookie，`user_check` 带 HttpOnly，供 B09）、`UserCheck` | 会员组到期的**写库**降级：`checkLogin` 单行降级与 `group_id`/`group_name` 重发、游客 cookie 回写归 **B09**；批量到期任务归 **B10**；后台会员列表第 1 页 `expire()` 归 **B14**（`mac_user` 的写入方登记见 12 文档第 3.1 节） |
| 测试侧降级演练：`drill/DrillWriteMapper` 的批量（`<=`）、单行（`<`）降级与续费语句，一次性库上验证（第 7 节） | 正式业务写入登记（仍为空） |
| `common.session`：进程内会话（有界、空闲过期、只认本进程签发的标识）与 `mac_get_time_span` | `ajax/verify_check` 与 `show_verify`/`search_verify` 的放行（B11，15 文档第 7.4 节；在此之前仍按 B05 的临时关闭处理） |
| `common.captcha`：本地验证码（参数取 `extra/captcha.php`，含中文 `useZh`）、`verify/index`、`verify/check` | 评论/留言的验证码与 cookie 计时（B11）；后台验证码（B12） |
| 搜索频率限制：视频与各模块的搜索页、ajax_search 的第 1 页（旧 `search_timespan`） | 任何页面上的会员显示、权限按会员组判定（B09 接线：分类、播放、下载、试看按有效会员组，替换现在一律按游客的 `GuestPermission`；B10 的积分购买权限） |

客户端 IP：本批没有用到 IP 的逻辑（旧版的搜索计时、验证码都按会话，不按 IP），没有新增 IP 解析；B09 记录登录 IP 时复用 B06 的 `ClientIpResolver`（`common.clientip`，信任边界不变）。也没有新增按 IP 的限流——那属于旧版没有的功能。

### 2. 接口

#### 2.1 `common.web.PhpCookies`（纯计算）

- `parse(List<String> cookieHeaders)` / `parse(String)` → `Map<名, Value>`，`Value(bytes, array)`：复刻 PHP 填充 `$_COOKIE` 的规则——只按 `;` 分段（逗号不是分隔符）；名前空白去掉，名中的空格、点、未配对的 `[` 变成 `_`；名为空或以 `[` 开头的段忽略；`name[...]` 是数组（只标记）；同名标量**先到先得**，数组覆盖先到的标量、标量不覆盖先到的数组；名不做 URL 解码；值按 PHP 8.0 `rawurldecode`（`%XX`，`+` 不变）。
- `setCookie(name, value, expires, now, path[, httpOnly])`、`deleteCookie(name, path)`：PHP 8.0 `setcookie` 的头格式 `name=rawurlencode(value); expires=D, d-M-Y H:i:s GMT; Max-Age=N; path=/`，`httpOnly` 时末尾加 `; HttpOnly`（与 PHP 8.0/7.3 `httponly` 选项写出的字节相同，黄金 setcookie 用例）；删除写成 `name=deleted; expires=Thu, 01-Jan-1970 00:00:01 GMT; Max-Age=0; path=/`。
- `rawUrlEncode`、`rawUrlDecode`、`urlDecode`（PHP 三个函数的字节语义）。

#### 2.2 `member` 模块

- `MemberSessionService.resolve(Map<String, PhpCookies.Value>) → MemberState`：
  1. `label_user` 前置判断：`intval(user_id)`、`user_name`、`user_check` 三者在 PHP `empty()` 意义下都非空（`"0"` 也算空）；数组形式的值按游客。
  2. 三个值各做 `htmlspecialchars(urldecode(trim()))`（PHP 7.3/8.0 默认 `ENT_COMPAT`，只转 `& " < >`；非法 UTF-8 → 空串）；任一为空 → 游客。
  3. `SELECT … FROM mac_user WHERE user_id = ? AND user_name = ? AND user_status = 1 LIMIT 1`，两个参数都按字符串，比较交给 MySQL（与旧版 PDO 绑定字符串相同：`1abc`、`01`、`1.0`、`0.1e1`、`' 1'` 都等于 1；`user_name` 按列排序规则不分大小写、忽略尾部空格）。值含 4 字节字符时不查库，直接游客（utf8 列存不下）。
  4. 库里 `user_random` 为空 → 游客（D-B08-2）；`md5(库 user_random-库 user_name-库 user_id-)` 与 cookie 逐字节相同才算登录（D-B08-1）。
  5. 有效会员组：库里 `group_id > 2` 且 `user_end_time < 现在`（秒；等于现在**不**到期）→ 2 组，`expiredInMemory=true`，不写库、不发 cookie（D-B08-3）。组行按有效组取（不看 `group_status`，与旧 `Group::getCache` 相同）；库里没有该组 → `group=null`。
- `MemberState(member, userId, userName, groupId（有效）, storedGroupId, group, userEndTime, expiredInMemory)`；游客为 `userId=0`、`groupId=1`、`group=1 组`。
- `MemberCookies.login(userId, userName, groupId, groupName, userRandom, portrait, now)`：六个 `Set-Cookie`（`user_id`、`user_name`、`group_id`、`group_name`、`user_check`、`user_portrait`），30 天，`path=/`，不设 domain，不加 Secure/SameSite；**`user_check` 加 HttpOnly**（D-B08-7），其余 5 个 `home.js` 要读（`static/js/home.js:596-602`），按旧契约不加。`logout()`（与旧注销逐字节相同）、`guestReset()`（旧 `label_user` 失败时只回写 `user_id=0`、`user_name=游客` 两个会话 cookie）、`groupRefresh()`（旧到期降级后重发的两个）。本批没有任何响应调用它们，B09 接入。
- `MemberMapper`（只读，`mapper/member/MemberMapper.xml`）：`selectActiveUser`、`selectGroup`。

#### 2.3 `common.session` 与站点会话 cookie

- `SessionStore`：标识为 128 位随机数的 32 位小写十六进制；只认本进程签发且未过期的标识（客户端自带的未知标识一律换新；旧 PHP 默认会沿用客户端给的标识）；空闲 30 分钟失效（不短于验证码有效期）；最多 100 000 个会话，满时先清过期的、再淘汰最久未访问的。重启即全部失效。
- `SessionState.timeSpan(key, now)`：旧 `mac_get_time_span`——返回 `现在 − 上次`（没有记录时上次按 `1228348800`），**无论调用方是否放行**都把上次改为现在；读改写在会话对象上同步，同一会话的并发请求串行（与旧会话文件锁效果相同）。
- 站点会话 cookie（`site.web.SiteSessions`）：名称沿用 `PHPSESSID`（已有的代理/CDN 规则可继续按它识别有会话的请求），值与旧 PHP 会话不通用；`Path=站点根`、`HttpOnly`、`SameSite=Lax`、站点地址为 https 时 `Secure`；会话 cookie（无有效期）。只在需要写会话时（签发验证码、第 1 页搜索计时）才新建。

#### 2.4 本地验证码（`common.captcha` + `site.web.SiteVerifyController`）

- 参数：think-captcha 默认值，由 `extra/captcha.php` 覆盖（仓库值 `codeSet=1234567890`、`length=4`、`fontSize=16`、无杂点无曲线 → 图片 128×40）；宽高为 0 时按旧公式 `length×fontSize×1.5 + length×fontSize/2`、`fontSize×2.5`。英文码表含非 ASCII 可见字符、中文码表含没有内置字形的字、码长 1～16 或字号 4～100 以外 → 启动失败（信息只含键名；这些是旧版也画不出的配置）。没有旧配置目录时用仓库值；目录里没有 `captcha.php` 时用 think-captcha 默认值。
- 中文验证码（`useZh=true`）：码字从 `zhSet`（默认 think-captcha 自带的 1500 字码表 `captcha/think-zhset.txt`，原样复制，md5 `7cc4f3b4…`；旧配置可覆盖）按字均匀抽取，重复的字概率更高，与旧 `iconv_substr(zhSet, mt_rand(0, mb_strlen−1), 1)` 相同；`codeSet` 不用。版式按旧中文分支：第 i 个字左边距 `fontSize×(i+1)×1.5`、基线 `fontSize + 10～20`、旋转 ±40°。字形是打包在 jar 里的 16×16 点阵 `captcha/zh-glyphs.hex`（GNU Unifont 17.0.05 的 GB2312 一、二级汉字子集，6763 字，SIL OFL 1.1，来源与许可全文在同目录 `zh-glyphs-LICENSE.txt`），不加载字体文件。校验与英文共用同一规则（只转 ASCII 大写，中文按字逐个比较；简繁、全半角不互通）。
- 刷新验证码（接口契约）：前端重新 `GET verify/index.html?r=<随机数>`（`home.js:184-192` 的写法；`r` 只防缓存，服务端忽略）即换一张新图，新码覆盖会话里同槽的旧码。`verify/check` 返回 `0` 后（猜错、过期、已用过或没有验证码）旧码已作废，必须刷新后再提交；返回 `1` 后同样作废（一次一用）。
- 校验：旧 `captcha_check()` 不传配置，所以有效期恒为 1800 秒、通过后作废。规则见 `Captchas` 类注释，由黄金向量锁定；唯一差异是猜错也作废（D-B08-4）。
- `GET/HEAD /index.php/verify/index[.html|/]`（及去掉 `/index.php` 的形式）：PNG、no-store、nosniff；可选 `?id=`（`[A-Za-z0-9_-]{0,32}`，否则 404）选择验证码槽（旧 `entry($id)`；前台没有任何地方校验非默认槽）。
- `GET/HEAD/POST /index.php/verify/check[.html|/]?verify=…[&id=…]`：正文 `1`/`0`（`text/html`），同名多值取最后一个；没有有效会话时 `0`。**`id` 被忽略，永远校验默认槽**：旧 `Verify::check($verify, $id)` 接收 `id` 却调用 `captcha_check($verify)`（`application/index/controller/Verify.php:21-29`），黄金 `verify_check` 段经旧控制器锁定。

#### 2.5 搜索频率限制（`site.web.SearchThrottle`）

- 取值 `app.search_timespan`（启动时读一次；数值或数字字符串，空/非数字/≤0 → 不限制；没有旧配置目录 → 不限制）。
- 位置：视频搜索页与 ajax_search、各模块（文章/专题/演员/角色/网址/剧情）搜索页与 ajax_search；路由与参数解析成功、**第 1 页**时计时（旧 `check_search`：`page==1 && mac_get_time_span("last_searchtime") < search_timespan`）。
- 过快 → 429、`Retry-After: ⌈search_timespan⌉`、no-store、noindex 的提示页（沿用现有错误页样式），正文为旧语言包原文"请不要频繁操作，搜索时间间隔为3秒前"；不查库。

### 3. PHP 黄金向量

- 生成：`backend/src/test/php/member_golden.php`（父进程：一次性库 `maccms_it_*` 导入 `install.sql` + 27 个合成会员，逐用例以 php-cgi 执行 `member_golden_child.php`；子进程只读加载 `thinkphp/`、`application/` 与 `vendor/topthink/think-captcha`，调用旧代码本身：经反射调用 `All::label_user`、`User::login`、ThinkPHP `cookie()`、`Captcha::check/entry`、`mac_get_time_span`、`User::expire`）。PHP 自己解析 `HTTP_COOKIE`，所以 cookie 的拆分与解码也是真实行为。用完删库与临时目录。
- 输出：`backend/src/test/resources/member/php80-golden.json`（PHP 8.0.30）、`php73-golden.json`（PHP 7.3.33），字符串一律十六进制字节。各 40 个 cookie 解析、18 个写出（含注销、失败回写与 `httponly` 的 `user_check`）、74 个 `label_user`（含 7 个到期边界）、12 个新版签发 cookie 的复放、10 个真实登录及其复放、22 组验证码校验序列（含 8 组中文）、5 组经旧控制器 `Verify::check` 的序列、6 个图片（含 2 个 `useZh`）、8 个 `mac_get_time_span`、1 组 `expire` 边界。修正轮（第 9 节）用同一脚本重新生成两份文件：原有用例除时间戳、随机 `user_random` 与 `mac_get_time_span` 的绝对秒数外与上一版完全相同。
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
| 中文验证码 | 与英文相同的记录与校验：正确一次通过、重放失败、1800/1801 秒边界、猜错不作废；繁体 `們`、尾部空格、少一个字都不通过（`strtoupper` 不改多字节字符） |
| `verify/check?id=x` | 旧控制器不把 `id` 传给 `captcha_check`：默认槽的码带任意 `id` 都通过；`id=x` 槽的码永远校验不到 |
| `useZh=true`（备份的 vendor） | `vendor/topthink/think-captcha/assets/` 下没有 `zhttfs/`，旧 `entry()` 直接出错（`zh-backup-vendor`）；让字体指到英文字体时中文分支的图片同为 128×40（`zh-ttfs-font`） |

### 4. Cookie 互通

| 方向 | 证据 |
|---|---|
| PHP → Java | `MemberGoldenMySqlIT` ③：PHP 8.0/7.3 真实 `User::login` 签发、原样回送的 cookie，新版判定与 PHP 自己的判定相同（含 `a&b` 两边都不认）；① 74 个 `label_user` 用例逐条对照 |
| Java → PHP | `MemberCookiesGoldenTest`：`MemberCookies.login` 与 PHP 8.0 登录写出的六个 `Set-Cookie` 除 `Max-Age`（PHP 每个头取 `expires − time()`，可能跨秒）外逐字节相同，唯一差异是 `user_check` 末尾的 `; HttpOnly`，这一行又与 PHP 8.0/7.3 自己用 `httponly` 写出的字节相同；`JavaIssuedCookiesTest`：新版签发的 12 组 cookie 交给旧 `label_user`，**PHP 8.0 与 7.3 都认**（`a&amp;b` 例外，旧版自己的也不认） |
| 属性 | 同名、`path=/`、不设 domain、30 天、不加 Secure；`user_check` 加 HttpOnly（D-B08-7，队长 2026-10-18 裁定），`home.js` 读的其余 5 个不加。HttpOnly 只是浏览器端属性，浏览器回送的 `名=值` 与旧版完全相同，所以两个方向的互认结论不变（`java-issued.json` 未变，PHP 两版复放结果同上）。03 第 4.3 (4) 节把它列为需 PHP 同步的 C3，那是"并行期两边都签发"的前提，已随 D3 整体切换撤回（路线图第 100 行）；回滚到 PHP 后旧版重新签发不带 HttpOnly 的 `user_check`，功能不受影响，旧注销同样能删除带 HttpOnly 的 cookie |
| 版本选择 | 解析按 PHP 8.0（`+` 不解码，只影响首尾带 `+` 的伪造值）；写出按 PHP 8.0（`rawurlencode`）。两版 PHP 都能读新版写出的值（%XX 两版解码相同），回滚到任一版本都不会让新版签发的 cookie 失效 |

### 5. 与旧版的差异

| # | 旧版 | 新版 | 性质 |
|---|---|---|---|
| D-B08-1 | `checkLogin` 用 `!=` 比较 `user_check`：两边都是数值串时按数值比较。黄金：`user_random=b08ma4ctsl`、`magic`、21 的校验值是 `0e580925…`（"0e+数字"），cookie 写 `0e0`、`00`、`0e999`、`0.0` 都能登录 | 逐字节相同才登录（常量时间比较） | 安全修复，正常 cookie 不受影响 |
| D-B08-2 | 注册后从未登录（注册不写 `user_random`，默认空串）或后台建的会员，任何人都能用 `md5("-用户名-ID-")` 登录（黄金 `neverlogin-forged`） | 库里 `user_random` 为空时不认任何 cookie | 安全修复：旧版正常签发的 cookie 都带非空 random（登录时写入），不受影响 |
| D-B08-3 | 到期时 `checkLogin` 写库 `group_id=2` 并重发 `group_id`/`group_name` cookie；那一次请求里 `$info['group_id']` 仍是原组 | B08 只在内存中判定（`groupId=2`、`storedGroupId` 为原组），不写库、不发 cookie。**这是分批的功能缺口，不是终态差异**：单行降级写库与 cookie 重发、游客回写、页面权限接线已列入 B09 验收，批量任务列入 B10、后台第 1 页列入 B14（路线图第 5.3 节） | 按 B07 写入方矩阵分批；B09 合入前页面一律按游客组判定 |
| D-B08-4 | 验证码猜错不作废，30 分钟内可对同一个 4 位数字码无限次尝试（≤10 000 次必中） | 猜错即作废，必须换新图 | 安全修复；用户输错时需刷新验证码（前端本来就会刷新） |
| D-B08-5 | 验证码字形来自 vendor 里的 TTF 字体（不入 git），`useImgBg` 的背景目录在 vendor 中并不存在；`useZh=true` 需要 `assets/zhttfs/*.ttf`，备份里没有，开启后旧站验证码直接出错 | 英文用内置笔画字形（0-9、A-Z，小写按大写画）；中文用内置 Unifont 16×16 点阵（GB2312 一、二级汉字），都不加载字体文件；`useImgBg`、`fontttf`、`bg` 忽略。`useZh=true` 可用（修正轮实现） | 实现差异，尺寸、码表抽取与校验语义不变；中文验证码在新版可用而备份的旧站不可用 |
| D-B08-7 | `user_check` 与其余 5 个会员 cookie 一样不带 HttpOnly（`application/config.php:232`） | `user_check` 加 HttpOnly（字节与 PHP `httponly` 写法相同），其余 5 个不变 | 加固：30 天有效、等同凭证，前端无处读取（仓库内全量检索；线上自定义模板不在仓库，D9-a）；B09 起登录响应生效 |
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
| 验证码校验序列（含中文）、过期、重放、尺寸公式（含 `useZh`）、配置校验、字母大小写、中文码表原样复制与字形覆盖、中文码按字抽取 | `CaptchasTest` |
| 中文验证码经控制器：图片尺寸与笔画、GET/POST（UTF-8）通过一次、重放、猜错/少字/繁体/尾空格即作废、1800/1801 秒、`id` 被忽略 | `SiteVerifyZhMvcTest` |
| `verify/check` 忽略 `id`：经控制器复放黄金 `verify_check` 序列（8.0 与 7.3） | `SiteVerifyThrottleMvcTest.checkIgnoresIdAndAlwaysUsesTheDefaultSlotLikeLegacyController` |
| 测试侧降级演练：批量 `<=` 与 PHP `expire` 黄金一致、单行 16 线程并发只命中 1 次、`end == now`/续费/停用不降级、续费与降级竞态 20 轮、DML 恢复后逐行 diff 为空、结构指纹不变、正式登记表为空 | `MemberGoldenMySqlIT` ⑤（`-Pmysql-it`） |
| `verify/index`、`verify/check`（GET/POST、分槽、过期、伪造会话）、搜索 429/Retry-After/不查库/第 2 页不计时/ajax 与模块搜索/恢复/无 cookie 不受限/并发 16 个只放行 1 个 | `SiteVerifyThrottleMvcTest` |
| 真实 Tomcat 原始套接字：会话 cookie 往返、子目录与 https 的 Path/Secure、PHP 风格 Cookie 头、HEAD | `SiteVerifyRealHttpTest` |
| 依赖边界（site 新增 5 个 common 类型） | `ModuleBoundaryTest`，同步 02 规范第 2 节 |

### 7. 裁定与降级归属（队长 2026-10-18）

| # | 裁定 | 落实 |
|---|---|---|
| D-B08-6 | B08 只做测试侧降级演练，正式业务写入登记仍为空；单行到期写库、组/游客 cookie 回写与页面会员权限接线归 B09，批量到期任务归 B10，后台会员列表第 1 页 `expire()` 归 B14，并列入对应批次验收条件 | 测试侧 `drill/DrillWriteMapper`：`expireMembers`（`UPDATE mac_user SET group_id = 2 WHERE group_id > 2 AND user_end_time <= #{now}`，旧 `User::expire`）、`expireMember`（单行，条件加 `user_status = 1 AND group_id > 2 AND user_end_time < #{now}`，比旧 `checkLogin` 多了到期条件，所以并发续费后匹配 0 行）、`renewMember`（续费形态）。`MemberGoldenMySqlIT` ⑤ 在一次性库上演练（第 6 节）。路线图 B08/B09/B10/B14 行与 12 文档第 3.1 节已同步 |
| D-B08-4 | 保留"猜错一次即作废" | 不变；刷新契约见第 2.4 节 |
| useZh | 必须实现，不允许以启动失败代替 | 第 2.4 节；D-B08-5 |
| `verify/check` | 忽略 `id`、只校验默认槽，补经过 HTTP 控制器的回归 | 第 2.4 节；第 6 节 |
| `user_check` HttpOnly | 加；其余旧前端读取的 cookie 按既有契约；签发辅助方法本轮调整，实际登录响应由 B09 接入 | D-B08-7；第 4 节 |
| D-B08-1、2 | 安全收紧保留（`2e6f5a6` 因接受空 `user_random` 伪造登录被排除） | 不变 |

### 8. 回滚

- 代码：本批合并后如需回退，`git revert -m 1 <合并提交>`；不涉及数据库结构、数据迁移或配置格式。
- 数据：本批的生产代码不写任何表。`MemberGoldenMySqlIT` 在一次性库上：① ～ ④ 复放全部用例后 `mac_user`/`mac_group` 与导入后快照 S0 逐行比较为空差异（零写入）；⑤ 测试侧降级语句演练后只用 DML（`DmlRestore`）恢复到演练前快照，逐行 diff 为空、与 S0 相同，表结构指纹与导入 `install.sql` 时相同；结束时删库并确认库已不存在。
- 运行状态：会话只在进程内存，重启即清空；回滚到旧 PHP 时，新版签发的会员 cookie（B09 起才会签发）仍被旧 PHP 认（第 4 节）。

### 9. 修正轮（MACC-32，队长 2026-10-18 裁定）

| 项 | 修正 | 证据 |
|---|---|---|
| 中文验证码 `useZh` | 实现生成、绘制、校验（第 2.4 节）；不再启动失败 | `CaptchasTest`（黄金 8 组中文序列、码表原样与字形覆盖、按字抽取、过期与重放）、`SiteVerifyZhMvcTest`；黄金 `zh-*` |
| `verify/check` 的 `id` | 忽略，只校验默认槽 | 黄金 `verify_check`（经旧控制器）；`SiteVerifyThrottleMvcTest` 两条回归 |
| `user_check` HttpOnly | `PhpCookies.setCookie(…, httpOnly)`；`MemberCookies.login` 的 `user_check` 带 HttpOnly | 黄金 setcookie 新增 `httponly` 用例（8.0 与 7.3）；`PhpCookiesGoldenTest`、`MemberCookiesGoldenTest`；`JavaIssuedCookiesTest`（回送值不变，PHP 两版仍认） |
| 测试侧降级演练 | 从 `2e6f5a6` 只移植测试侧语句与演练，按本批 IT 的数据重写断言；正式登记表为空 | `MemberGoldenMySqlIT` ⑤ |
| 文档 | 路线图 B08/B09/B10/B14 行、12 文档第 3.1 节、本文第 1、2、5、7、8 节 | — |

黄金文件按第 3 节的命令重新生成（需恢复 `vendor/`，管理账号只经环境变量；一次性库用完即删）。
