## B09 会员账号：注册、登录、资料、头像、密保找回（路线图 B09，MACC-33）

> **状态：后端已实施（2026-10-18，后端 Claude）；前端 K1–K4（第 8 节）待队长安排。** 契约稿 v1 随提交 `3008228` 发布；实施中按黄金向量与实现修订的地方见第 13 节"契约修订记录"。实现说明在第 11 节，验证记录与验收对照在第 12 节。
> 依据：路线图 `09-roadmap.md` 第 5.3 节 B09 行与第 5.5 节 D2 ①②⑥；03 草稿第 4.3 (1)(4)(6) 节；B08 文档 `17-b08-member-session-captcha.md`；B07 文档 `12-write-infrastructure.md`。旧代码位置一律相对仓库根目录。

### 0. 起点、分支与提交

| 项 | 值 |
|---|---|
| 运行起点 | 平台基线 `836653af739688c47a79b903cb87d9d14021ecb6`（本任务工作区的快照提交），tree `03c0e610c948c63e4bc6fa597016614dfec8b87a`，与已验收 main `363d7bf0a72e873aafad6aaae98d88d88fa35335`（B08 普通 merge）的 tree 相同 |
| 分支 | `agent/claude/macc-33`（只用普通提交；没有 reset/rebase/amend/force，不自行合并 main） |
| 起始工作树 | 只有平台生成的 `.claude/`、`CLAUDE.md`（未跟踪，不提交）；没有发现重叠工作或冲突 |
| 提交 | 见交付评论（最终 SHA/tree 以评论为准） |

### 1. 范围

| 在本批 | 不在本批（归属） |
|---|---|
| `user/login`（页面 + 提交）、`user/logout`、`user/ajax_login`、`user/ajax_info`、`user/index`（会员中心首页） | `ajax_ulog`、`ajax_buy_popedom`、`buy`、`pay`、`gopay`、`qrcode`、`upgrade`、`popedom`、`plays`、`downs`、`favs`、`ulog*`、`plog*`、`cash*`、`reward`、`orders`、`order_info`、`cards`、`visit`（**B10**）；`comment`、`gbook`（**B11**） |
| `user/reg`（页面 + 提交，含 `uid` 推广 cookie 与 `user_pid` 链）、`user/regcheck` | 推广注册给邀请人加积分与 `mac_plog` 流水（`common/model/User.php:252-263`，`mac_plog` 归 **B10**，写入方矩阵） |
| `user/info`（资料与改密）、`user/portrait`（本地头像上传） | 头像的 `mac_annex` 附件登记（`common/model/Upload.php:268-289`，`mac_annex` 归 **B13**） |
| `user/findpass`（密保问题找回与重置） | 短信/邮件验证码的发送、校验与绑定（**B25**）：本批的 `reg_msg`、`findpass_msg`、`findpass_reset` 按旧版"未配置短信/邮件"时的结果返回，不出站、不读写 `mac_msg`；`bind`、`unbind`、`bindmsg` 不在本批（404） |
| B08 接线：登录/注销签发 `MemberCookies`；`checkLogin` 到期**单行降级写库** + `group_id`/`group_name` 重发；`label_user` 失败时游客 cookie 回写；页面分类、详情、播放、下载、试看按**有效会员组**判定（替换一律按游客的 `GuestPermission`） | 积分购买记录（`mac_ulog`）的查询与扣费（**B10**）：本批会员组 ≤2 遇到收费数据时与游客一样给出"需要积分"的结果，不查是否已购买 |
| D2 ①（不接受库内 md5 当密码）、②（普通入口忽略 `openid`/`col`）、⑥（注销、改密、找回重置轮换 `user_random`） | 第三方登录 `oauth`/`logincallback`（**B26**，404） |
| 同名并发注册只产生 1 行（第 6.3 节） | 批量到期任务（B10）、后台会员管理（B14） |

### 2. 路由总表

地址形态与 B05/B06 相同：`/index.php/user/<操作>[.html|.htm|.shtml|.xml][/键/值…]`，`rewrite` 打开时另认去掉 `/index.php` 的形态；路径按 PATH_INFO 解码一次，路径里的 `键/值` 是 ThinkPHP 路由参数。站点链接一律由 `LegacyUrlBuilder.user(<操作>)` 生成（例：`/index.php/user/login.html`，与旧 `mac_url('user/login')` 相同）。

| 操作 | 方法 | 需登录 | 响应 |
|---|---|---|---|
| `login` | GET/HEAD | 否 | 登录页（`pages/userLogin.jte`）；请求带的 `user_id`、`user_name` cookie 都为真值时 302 → `user/index`（旧 `User.php:187-189`，只看 cookie 是否非空，不校验） |
| `login` | POST | 否 | JSON `{"code":…,"msg":"…"}`；成功时写 6 个登录 cookie |
| `logout` | 任意 | 否 | 写 6 个删除 cookie；已登录时先轮换 `user_random`（D2 ⑥）；ajax → JSON `{"code":1,"msg":"退出成功"}`，否则 302 → `user/login` |
| `ajax_login` | GET/HEAD | 否 | 登录表单片段（无外壳，`ajax/userLogin.jte`） |
| `ajax_info` | GET/HEAD | 是 | 会员信息片段（无外壳，`ajax/userInfo.jte`） |
| `index` | GET/HEAD | 是 | 会员中心首页（`pages/userIndex.jte`） |
| `reg` | GET/HEAD | 否 | 注册页（`pages/userReg.jte`）；带 `uid` 参数时写 `uid` 会话 cookie |
| `reg` | POST | 否 | JSON；注册成功后按旧版立即登录（成功时写 6 个登录 cookie） |
| `regcheck` | GET/HEAD/POST | **是**（旧白名单 `User.php:19` 不含 `regcheck`，游客调用得到"未登录"，照搬） | JSON `{"code":1,"msg":"ok"}`，或（已注册/验证码错）正文为处理后的 `str`，`text/html` |
| `reg_msg` | POST | 否 | JSON（B25 前固定为发送失败，第 3.6 节） |
| `info` | GET/HEAD | 是 | 资料页（`pages/userInfo.jte`） |
| `info` | POST | 是 | ajax → JSON `{"code":1|0,"msg":"…","data":"","url":…,"wait":3}`；非 ajax → 跳转提示页（`pages/userJump.jte`，200） |
| `portrait` | GET/HEAD | 是 | 头像页（`pages/userPortrait.jte`） |
| `portrait` | POST | 是 | JSON `{"msg":"…","code":1|0,"file":"…"}`（`multipart/form-data`） |
| `findpass` | GET/HEAD | 否 | 密保找回页（`pages/userFindpass.jte`） |
| `findpass` | POST | 否 | JSON |
| `findpass_msg` | GET/HEAD | 否 | 邮箱/手机找回页（`pages/userFindpassMsg.jte`，`ac=email|phone`） |
| `findpass_msg`、`findpass_reset` | POST | 否 | JSON（B25 前按未配置，第 3.6 节） |
| 其他 `user/*` | 任意 | 按旧白名单 | 不在白名单的操作先检查登录态（游客得到"未登录"，同旧构造函数），已登录时 404；白名单里未实现的 `oauth`、`logincallback`、`visit` 直接 404。归属见第 1 节 |

- **需登录的操作未登录时**（旧 `index/controller/User.php:19-25`）：先写 6 个删除 cookie（旧 `logout()`），再按 ThinkPHP `error('未登录', url('user/login'))`：ajax → JSON `{"code":0,"msg":"未登录","data":"","url":"\/index.php\/user\/login.html","wait":3}`；非 ajax → 提示页（`pages/userJump.jte`，200，带去登录页的链接；旧模板用脚本倒计时跳转，新版页面不带脚本、不自动跳转）。
- **ajax 判定**（ThinkPHP `Request::isAjax`）：请求头 `X-Requested-With` 不分大小写等于 `XMLHttpRequest`，或合并后的参数 `_ajax` 为 PHP 真值。
- **可写数据源关闭时**（`MACCMS_WRITE_ENABLED=false`，没有 `WriteDatabase`）：全部 `user/*` 返回 503 维护提示（ajax 为 JSON `{"code":503,"msg":"会员功能暂未开放"}`），页面不提供无法提交的表单；`label_user` 判定照常（只读），到期只做内存判定、不写库也不重发组 cookie（同 B08）。
- 站点关闭（`site_status=0`）时与其他页面相同，返回维护页 503。
- 所有 `user/*` 响应：`Cache-Control: no-store`、`X-Robots-Tag: noindex`、`X-Content-Type-Options: nosniff`；页面带 `<meta name="robots" content="noindex">`。

### 3. 各入口契约

#### 3.1 通用：参数与字节语义

- **参数合并**按 ThinkPHP `input()`：查询串 → 表单正文（POST 覆盖 GET）→ 路径 `键/值`（覆盖前两者）。查询串与 `application/x-www-form-urlencoded` 正文按 PHP 的规则**按字节**解析（`&` 分段、`+` 为空格、`%XX` 解码一次；名字里的空格与 `.` 变 `_`；同名取最后一个），不经 Servlet 的字符集转换，所以非法 UTF-8、`\0` 等字节原样进入下面的处理。名字带 `[` 的数组参数：该字段按"缺失"处理（旧版 PHP 8 对数组调用 `trim` 抛类型错误、500；新版不复刻 500）。表单正文上限 64 KiB，超过时按参数不合法返回 400（站点错误页）。
- **清洗**：旧代码大量使用 `htmlspecialchars(urldecode(trim($x)))`（下文记作 `clean(x)`）：PHP `trim` 去首尾 `" \t\n\r\0\x0B"`，`urldecode` 再解一次 `%XX` 与 `+`，`htmlspecialchars` 只转 `& " < >`、非法 UTF-8 得空串（与 B08 `LegacyCookieText.clean` 相同）。
- **密码字节语义**（03 第 4.3 (1) 节，黄金向量逐入口锁定）：

  | 入口 | 写入或比较的值 |
  |---|---|
  | 注册（`common/model/User.php:124,180`） | 存 `md5(trim(原值))`；"非空""两次一致"用 `clean(原值)` 判断 |
  | 登录（`:324,346`） | 只比较 `user_pwd = md5(trim(原值))`（**D2 ①**：去掉 `OR user_pwd = clean(原值)`） |
  | 资料改密（`:297-299,315-316` → `saveData :77`） | 旧密码接受 `md5(trim(原值))` 或 `md5(clean(原值))`；新密码存 `md5(trim(user_pwd2))` |
  | 密保找回（`:483,515`） | 存 `md5(trim(原值))` |
  | 后台、OAuth | 不在本批（B14、B26） |

  `trim` 只去上面 6 个 ASCII 字节，不去全角空格；`md5` 对字节计算，结果 32 位小写十六进制。
- **"空"** 一律是 PHP `empty()`：空串与 `"0"`。
- **JSON**：`Content-Type: application/json; charset=utf-8`，编码与 PHP `json_encode(…, JSON_UNESCAPED_UNICODE)` 逐字节相同（`common.web.LegacyJson`：中文原样、`/` 转义为 `\/`）。键顺序按旧代码数组的顺序（`code` 在前，portrait 是 `msg` 在前）。
- **验证码**：B08 的本地验证码，`verify` 字段；图片 `verify/index.html`，刷新加 `?r=<随机数>`。新版猜错也作废（D-B08-4），所以**每次提交后不论成败都要刷新验证码**（前端行为，第 8.3 节）。
- **客户端 IP**：只经 `common.clientip.ClientIpResolver`（D2 ④），写库时按旧 `mac_get_ip_long` 转成无符号 32 位整数；不是 IPv4 时为 0（旧版 `ip2long` 对 IPv6 返回 false → 0）。
- **时间**：`time()` 按秒；"今天零点"（注册 IP 限额）按旧版时区 `PRC`（`Asia/Shanghai`，`application/config.php:42`）。

#### 3.2 `login`（`index/controller/User.php:180-191`、`common/model/User.php:321-393`）

字段：`user_name`（用户名或邮箱）、`user_pwd`、`verify`（`user.login_verify=1` 时需要）。`openid`、`col` **忽略**（D2 ②）。

按顺序：

| 条件 | code | msg |
|---|---|---|
| `clean(user_name)` 或 `clean(user_pwd)` 为空 | 1001 | 请填写必填项 |
| `login_verify=1` 且验证码不通过 | 1002 | 验证码错误 |
| 按 `clean(user_name)` 查 `user_status=1` 的会员：`clean(user_name)` 匹配邮箱正则 `\w+([-+.]\w+)*@\w+([-.]\w+)*\.\w+([-.]\w+)*`（不锚定、按字节）时按 `user_email`，否则按 `user_name`；且 `user_pwd = md5(trim(原值))`；查不到 | 1003 | 获取用户信息失败 |
| 写登录信息匹配 0 行（读与写之间密码或状态被改） | 1003 | 获取用户信息失败 |
| 成功 | 1 | 登录成功 |

- 写库（单条 `UPDATE`，条件为读到的 `user_id`、本次比较的 `user_pwd` 与 `user_status=1`）：`user_random = md5(rand(10000000, 99999999))`（随机数改用 `SecureRandom`，形态不变），`user_last_login_time = user_login_time`、`user_last_login_ip = user_login_ip`（先于下两项赋值），`user_login_time = 现在`、`user_login_ip = IP`、`user_login_num = user_login_num + 1`，并且 `group_id = IF(group_id > 2 AND user_end_time < 现在, 2, group_id)`（旧 `:363-366` 的登录时到期降级，原子判定）。
- 成功时写 B08 `MemberCookies.login` 的 6 个 cookie（`user_check` 带 HttpOnly）：`user_id`、`user_name`（库里的原值）、`group_id`/`group_name`（降级后的有效组；库里没有该组时这两个写成删除 cookie，同旧 `cookie(name, null)`）、`user_check = md5(新 random-用户名-ID-)`、`user_portrait`（旧 `mac_get_user_portrait`：头像文件 `upload/user/<id%10>/<id>.jpg` 存在时为站点根路径 + 该相对路径，否则站点根路径 + `static/images/touxiang.png`）。
- 用户名含 4 字节字符（库是 utf8）时不查库，按 1003。

#### 3.3 `logout`（`index/controller/User.php:193-201`、`common/model/User.php:411-420`）

- 当前请求按 B08 判定为已登录时，先 `UPDATE mac_user SET user_random = <新随机值> WHERE user_id = ? AND user_random = <校验通过的旧值>`（D2 ⑥：旧 `user_check` 在所有设备上立即失效）；匹配 0 行（并发注销/登录已经换过）不影响结果。
- 写 6 个删除 cookie（与旧版逐字节相同）；ajax → JSON `{"code":1,"msg":"退出成功"}`，否则 302 → `user/login`。

#### 3.4 `reg`（`index/controller/User.php:349-374`、`common/model/User.php:119-267`）

GET：`uid` 参数非空时写会话 cookie `uid=<rawurlencode(值)>; path=/`（旧 `cookie('uid', $param['uid'])`）。页面按配置显示验证码（`reg_verify=1`）与手机/邮箱验证区（`reg_phone_sms=1` 或 `reg_email_sms=1`）。

POST 字段：`user_name`、`user_pwd`、`user_pwd2`、`verify`；`reg_phone_sms`/`reg_email_sms` 打开时另有 `ac`（`phone`/`email`）、`to`、`code`。推广人：`uid` cookie 非空时取 `intval(cookie('uid'))`，否则取合并参数里的 `uid`，再 `intval`（旧 `User.php:353-355` 与 `common/model/User.php:129,236`）。`user_openid_qq`、`user_openid_weixin` 参数**忽略**（D2 ② 的延伸：旧版带这两个参数即跳过验证码并把它们写进新账号）。

| 顺序 | 条件 | code | msg |
|---|---|---|---|
| 1 | `user.status=0` 或 `reg_open=0` | 1001 | 未开放注册 |
| 2 | `clean(user_name)`、`clean(user_pwd)`、`clean(user_pwd2)` 有一个为空 | 1002 | 请填写必填项 |
| 3 | 验证码不通过且 `reg_verify=1`（旧版无论开关都会调用一次校验，通过即作废；新版相同） | 1003 | 验证码错误 |
| 4 | `clean(user_pwd) != clean(user_pwd2)`（新版按字节比较，第 10 节 D-B09-6） | 1004 | 密码与确认密码不一致 |
| 5 | 已有同名会员（MySQL 比较：不分大小写、忽略尾部空格） | 1005 | 用户名已被注册，请更换 |
| 6 | 用户名不是 `^[a-zA-Z\d]*$` | 1006 | 用户名只能包含字母和数字，请更换 |
| 7 | 用户名少于 6 个字符 / 密码为空（ThinkPHP 验证器 `add` 场景） | 1007 | 参数错误：名称最少不能低于6个字符 / 参数错误：密码必须 |
| 8 | 用户名含 `user.filter_words`（逗号分隔，区分大小写）任一项 | 1008 | 用户名禁止包含：<filter_words 原文>等字符，请重试 |
| 9 | `reg_num>0` 且本 IP 今天（PRC 零点之后）已注册 ≥ `reg_num` 个 | 1009 | 每IP每日限制注册<reg_num>次 |
| 10 | `reg_phone_sms=1` 或 `reg_email_sms=1`：`ac` 不是 `email`/`phone`、`clean(to)` 或 `clean(code)` 为空 → 9001 参数错误；邮箱黑白名单（`ac=email`）不通过 → 1001/1002 邮箱域名不允许；否则 → 9002 验证信息错误，请重试（B25 前不会有可用的验证码，第 3.6 节） | 9001/1001/1002/9002 | 同左 |
| 11 | 插入匹配 0 行：重读，同名已存在 → 1005；IP 限额已满 → 1009 | 1005/1009 | 同上 |
| — | 插入出错（例如超长被严格模式拒绝） | 1010 | 注册失败，请重试 |

- 插入（条件插入，第 6.3 节）：`user_name = clean(user_name)`、`user_pwd = md5(trim(原值))`、`group_id = 2`、`user_points = intval(reg_points)`、`user_status = intval(reg_status)`、`user_reg_time = 现在`、`user_reg_ip = IP`、`user_openid_qq = ''`、`user_openid_weixin = ''`，其余列取表默认值（`user_random` 为空串，所以注册后未登录前没有任何 cookie 能通过校验，D-B08-2）。
- 推广：`uid > 0` 且该会员存在时，`UPDATE` 新会员的 `user_pid = uid`、`user_pid_2 = 邀请人.user_pid`、`user_pid_3 = 邀请人.user_pid_2`；邀请人加 `invite_reg_points` 积分与 `mac_plog` 流水**归 B10**（第 1 节）。
- 注册成功后按旧版以同一组参数调用登录（`login_verify` 视为 0）：返回登录的 code，`msg = "注册成功，" + 登录的 msg`（例：`{"code":1,"msg":"注册成功，登录成功"}`；`reg_status=0` 时为 `{"code":1003,"msg":"注册成功，获取用户信息失败"}`）。注册失败直接返回注册的 JSON。

#### 3.5 `regcheck`（`index/controller/User.php:337-347`、`common/model/User.php:269-290`）

参数 `t`、`str`，都 `clean`。`t=user_name`：同名存在 → 正文为 `clean(str)`（`text/html; charset=utf-8`），否则 JSON `{"code":1,"msg":"ok"}`；`t=user_email` 同理按邮箱；`t=verify`：验证码校验（通过或猜错都作废），不通过 → 正文 `clean(str)`；其他 `t` → JSON ok。

#### 3.6 短信/邮件相关入口（B25 前，按"未配置"）

| 入口 | 结果 |
|---|---|
| `reg_msg`、`findpass_msg` POST | 参数检查同旧 `send_msg`（`ac` 不是 `email`/`phone` 或 `clean(to)` 为空 → `{"code":9001,"msg":"参数错误"}`；`ac=email` 时黑白名单 → 1001/1002）；之后一律 `{"code":9009,"msg":"验证码发送失败：未配置短信发送服务"}`（`ac=phone`）或 `…：未配置邮箱发送服务`（`ac=email`）。不出站、不写 `mac_msg` |
| `findpass_reset` POST | 旧顺序：`strlen(clean(user_pwd)) < 6` → 2002 密码最少6个字符；`clean(user_pwd) != clean(user_pwd2)` → 2003 两次输入的新密码不一致；验证码参数不全 → 9001 参数错误；否则 9002 验证信息错误，请重试。不改密码 |

差异：旧版"未配置"时的提示取决于线上 `sms.type`/`email.type` 与 `extend/` 是否存在（仓库的 `email.type=Phpmailer` 而 `extend/` 为空，旧版会走到 `class_exists` 失败的 991"未找到该邮件发送方式"）；新版固定按"未配置"，见第 10 节 D-B09-8。

#### 3.7 `info`（`index/controller/User.php:321-335`、`common/model/User.php:292-319,58-94`）

POST 字段：`user_pwd`（当前密码，必填）、`user_pwd1`、`user_pwd2`（新密码，可空）、`user_nick_name`、`user_qq`、`user_question`、`user_answer`。

| 顺序 | 条件 | 结果 |
|---|---|---|
| 1 | `user_pwd` 原值为空 | 请输入原密码 |
| 2 | 库里 `user_pwd` 既不是 `md5(trim(原值))` 也不是 `md5(clean(原值))` | 原密码错误 |
| 3 | `user_pwd1 != user_pwd2`（原值，按字节比较，D-B09-6） | 两次输入的新密码不一致 |
| 4 | 库里的用户名少于 6 个字符（ThinkPHP `edit` 场景校验的是库里的 `user_name`） | 参数错误：名称最少不能低于6个字符 |
| 5 | 写库匹配 0 行（期间密码被改） | 原密码错误 |
| 6 | 成功 | 保存成功! |

- 写库：`user_qq = clean`、`user_question = clean`、`user_answer = clean`（**空值也照写**，所以表单必须回填当前值）、`user_nick_name = clean`（原值非空时才写）、`user_pwd = md5(trim(user_pwd2))`（`user_pwd2` 原值非空时才写）；条件 `user_id = ? AND user_pwd = <校验通过时读到的值>`。
- **改了密码时**（D2 ⑥）同一条语句写新的 `user_random`，并在本响应里重新签发 6 个登录 cookie：当前设备保持登录，其他设备上的旧 `user_check` 立即失效。
- 响应：ajax → `{"code":1,"msg":"保存成功!","data":"","url":<Referer 或 null>,"wait":3}` / `{"code":0,"msg":"…","data":"","url":"","wait":3}`；非 ajax → 提示页（成功/失败的提示 + 回到资料页的"继续"链接，不自动跳转）。

#### 3.8 `portrait`（`index/controller/User.php:384-398`、`common/model/Upload.php`）

- `user.portrait_status=0` 或未配置头像目录（第 7 节）→ `{"code":0,"msg":"未开启自定义头像功能"}`（键顺序同旧版：`code` 在前）。
- 输入：`multipart/form-data` 的 `file` 字段，或表单参数 `imgdata`（`data:image/<类型>;base64,…`，旧版两种都接受）。上限 2 MiB（旧版取 PHP `upload_max_filesize`，默认 2M）。
- 失败结果 `{"msg":<提示>,"code":0,"file":"<站点根路径>?<4 位随机数>"}`（旧 `upload_return` 在没有文件时的拼法）：没有文件 → "未找到上传的文件(原因：表单名可能错误，默认表单名“file”或“imgdata”)！"；扩展名不是 `jpg,jpeg,png,gif,webp`（`imgdata` 按 data URL 的类型）→ "非系统允许的上传格式！"；内容不能解码为图片 → "文件上传失败！"（旧 `make_thumb_faild` 的文案）。
- 成功：缩放为 `user.portrait_size`（如 `100x100`，只给一个数时为正方形；旧 `think\Image::thumb(w, h, 6)` 固定尺寸拉伸）后以 JPEG 写到 `<头像目录>/user/<id%10>/<id>.jpg`，`UPDATE mac_user SET user_portrait = 'upload/user/<id%10>/<id>.jpg' WHERE user_id = ?`；返回 `{"msg":"文件上传成功！","code":1,"file":"<站点根路径>upload/user/<id%10>/<id>.jpg?<4 位随机数>"}`。
- 新版只把**重新编码后的图片**写盘，上传的原始字节不落盘（旧版先把原文件存成 `<id>.jpg`，再用缩略图覆盖；扩展名在允许表内但不是图片的文件会留在磁盘上）。`webp` 本机 JDK 没有解码器，按"文件上传失败！"处理（第 10 节）。
- `/upload/…` 静态文件仍由部署层（nginx）直接提供，新系统不提供该路径。

#### 3.9 `findpass`（`index/controller/User.php:400-409`、`common/model/User.php:480-526`）

字段：`user_name`、`user_question`、`user_answer`、`user_pwd`、`user_pwd2`、`verify`（总是需要）。

| 顺序 | 条件 | code | msg |
|---|---|---|---|
| 1 | 六个字段（前五个 `clean`，`verify` 原值）有一个为空 | 1001 | 参数错误 |
| 2 | 验证码不通过 | 1002 | 验证码错误 |
| 3 | `clean(user_pwd) != clean(user_pwd2)`（按字节） | 1003 | 两次输入的新密码不一致 |
| 4 | 按 `user_name`、`user_question`、`user_answer`（都是 `clean` 后的值，MySQL 比较规则）找不到会员（不看 `user_status`，同旧版） | 1004 | 获取用户失败，账号、问题、答案可能不正确 |
| 5 | 成功：`UPDATE mac_user SET user_pwd = md5(trim(原值)), user_random = <新随机值> WHERE user_id = ? AND user_name = ? AND user_question = ? AND user_answer = ?`（D2 ⑥；匹配 0 行按 1004） | 1 | 密码找回成功成功（旧文案原样） |

#### 3.10 `index`、`ajax_info`、`ajax_login`

- `index`：会员中心首页，展示用户名、昵称、会员组（有效组）、到期时间（组 > 2 时）、积分、注册时间、最近登录时间与头像，以及本批已有的入口（资料、头像、退出）。B10/B11 的入口在对应批次加入。
- `ajax_info`：同样的信息的片段（旧模板缺失，D9-a，结构由本契约定义）。
- `ajax_login`：登录表单片段，字段与 `login` 页相同。

### 4. Cookie 契约（全部经 B08 `MemberCookies`/`PhpCookies`）

| 场景 | Set-Cookie |
|---|---|
| 登录、注册后登录、改密后重新签发 | `user_id`、`user_name`、`group_id`、`group_name`、`user_check`（HttpOnly）、`user_portrait`；30 天，`path=/`，不设 domain、不加 Secure/SameSite（D-B08-7） |
| 注销、需登录操作未登录 | 6 个删除 cookie（与旧版逐字节相同） |
| `label_user` 校验失败（请求带了三个会员 cookie 但不通过） | `user_id=0`、`user_name=游客` 两个会话 cookie（旧 `All.php:136-138`） |
| 到期降级写库成功（第 5.1 节） | `group_id=2`、`group_name=<2 组名>`，30 天 |
| `reg` GET 带 `uid` | `uid=<值>; path=/`（会话 cookie） |

### 5. `label_user` 接线（每个前台页面）

适用于全部站点页面与 `ajax/*`、`user/*`（旧版继承 `Base` 的控制器）；不适用于 `verify/*`（旧 `Verify` 不继承 `Base`）、播放器配置与静态资源。只有请求带了 `user_id`、`user_name`、`user_check` 三个 cookie 且前置判断（B08 第 2.2 节）通过时才查库；其他请求不查库、不写 cookie，按游客组。

#### 5.1 到期单行降级（B08 D-B08-3 的正式实现）

- 判定为已登录且库里 `group_id > 2 AND user_end_time < 现在`：执行 B08 测试侧已验证的单行语句 `UPDATE mac_user SET group_id = 2 WHERE user_id = ? AND user_status = 1 AND group_id > 2 AND user_end_time < ?`（`现在` 为本次请求判定时的秒）。
  - 匹配 1 行 → 本请求按 2 组，重发 `group_id`/`group_name`（第 4 节）。
  - 匹配 0 行 → 重读一次：已被并发请求降为 2 组 → 本请求按 2 组并同样重发；已续费（组 > 2 且未到期）→ 按续费后的组，不重发、不降级。
- 条件里带"到期"判断，所以与续费并发时不会误降（B08 ⑤ 已演练 20 轮）；16 个并发请求只有 1 个匹配。
- 可写数据源关闭时只做内存判定（B08 行为），不重发 cookie。
- 对照基准：每次请求恰好 2 个 `Set-Cookie`（`group_id=2`、`group_name=默认会员`），`expires` 按请求开始的秒 + 30 天（旧 ThinkPHP `Cookie::set` 用 `$_SERVER['REQUEST_TIME']`），见第 9 节。

#### 5.2 游客回写

请求带了三个会员 cookie、前置判断通过但校验失败（查不到、`user_check` 不符、`user_random` 为空、停用）时写第 4 节的两个游客 cookie；前置判断就不通过时（例如 `user_id=0`）不写，同旧版。

#### 5.3 页面权限按有效会员组

- 有效组 = 已登录时 B08 `MemberState.groupId`（到期按 2），否则游客组 1；组行取 `mac_group`（不看 `group_status`，同旧 `Group::getCache`）；库里没有该组时没有任何权限、组号按 0 参与下面的比较（旧 `$group` 为空）。
- 判定照搬旧 `check_user_popedom`（`index/controller/Base.php:92-227`），权限位 1 列表、2 详情、3 播放、4 下载、5 试看：
  - 分类可见（导航、列表页、`ajax/*`）与视频详情：对应位为真即可（`user.status=0` 时不判定，同 B05）。
  - 文章/演员/网址详情（位 2）：无权限 → 无权限页；组 < 3 且积分列 > 0 → 需要积分（购买与"已购买"判定归 B10）。
  - 播放（位 3）：无播放位且（无试看位或试看分钟为 0）→ 无权限；组 < 3 且无播放位、有试看位、试看分钟 > 0 → 试看；组 < 3 且积分 > 0 → 需要积分；其余放行（组 ≥ 3 有试看位而无播放位时旧版直接放行，照搬）。
  - 下载（位 4）：无下载位 → 无权限；组 = 1 且积分 > 0 → "此页面为收费数据，请先登录后访问！"；组 = 2 且积分 > 0 → 需要积分（"下载此数据，需要支付【N】积分"，购买归 B10）；其余放行。
- 实现：`content.application` 新增 `ContentViewer`（当前请求的观看者会员组，默认游客），由 `site` 的拦截器在 `label_user` 判定后设置、请求结束时清除；`ContentCatalogs` 按它取组权限（`GuestPermission` 改为 `GroupPermission`，带组号）。公开 JSON API（`/api/v1/**`）不设置，始终按游客。理由与边界写在实现说明里（第 11 节）。
- **缓存**：带会员 cookie 的请求，页面响应改为 `Cache-Control: private, no-cache`（原为 `no-cache`），防止共享缓存把会员组页面给别人；写了 `Set-Cookie` 的响应为 `no-store`。

### 6. 写库

#### 6.1 登记（`WriteRegistry.REGISTERED`，写入方矩阵 `mac_user` = B09/B10/B14…）

| 模块 | 表 | 操作 | 用途 |
|---|---|---|---|
| `member` | `mac_user` | `INSERT` | 注册（条件插入） |
| `member` | `mac_user` | `UPDATE` | 登录、注销轮换、资料与改密、密保重置、头像、推广关系、到期单行降级 |

写 SQL 只在 `src/main/resources/mapper-write/member/MemberWriteMapper.xml`，接口 `member.infrastructure.MemberWriteMapper`（`@WriteMapper`）。全部是带主键或带前置条件的单行语句，检查匹配行数；不用事务。

#### 6.2 语句清单

| 语句 | 条件（前置条件写进 WHERE） | 预期匹配 |
|---|---|---|
| 注册 `INSERT … SELECT … FROM DUAL WHERE NOT EXISTS (同名) AND (今日同 IP 数) < reg_num` | 同名不存在；`reg_num>0` 时 IP 限额未满 | 1；0 → 重读区分 1005/1009 |
| 推广关系 `UPDATE … SET user_pid…` | `user_id = 新会员 AND user_pid = 0` | 1 |
| 登录 | `user_id AND user_pwd = 比较值 AND user_status = 1` | 1；0 → 1003 |
| 注销轮换 | `user_id AND user_random = 校验通过的值` | 0 或 1 都算成功 |
| 资料/改密 | `user_id AND user_pwd = 校验通过时读到的值` | 1；0 → 原密码错误 |
| 密保重置 | `user_id AND user_name AND user_question AND user_answer` | 1；0 → 1004 |
| 头像 | `user_id` | 1 |
| 到期降级 | `user_id AND user_status = 1 AND group_id > 2 AND user_end_time < 现在` | 0 或 1（第 5.1 节） |

#### 6.3 MyISAM 下的同名并发注册

`mac_user` 只有 `user_name` 的普通索引、没有唯一约束，也不能加（零结构变更）。注册用一条条件插入完成"判重 + 插入"：MyISAM 的 `INSERT … SELECT` 对目标表加表级写锁，同一条语句内的 `NOT EXISTS` 子查询与插入之间没有其他写入能插进来，所以同名（按列排序规则，不分大小写、忽略尾部空格）并发注册只会有 1 行。作用范围：只覆盖经新系统的注册入口；后台建号（B14）与第三方自动注册（B26）须用同样的条件插入；数据库工具或手工 SQL 不受约束。IP 限额放进同一条语句，并发时同样不会超额（旧版先数再插，可以超额）。验证：一次性库上 16 线程 × 多轮同名注册各只产生 1 行（第 12 节）。

### 7. 配置

| 项 | 来源 | 说明 |
|---|---|---|
| `user.*`（`status`、`reg_open`、`reg_status`、`reg_verify`、`login_verify`、`reg_points`、`reg_num`、`filter_words`、`reg_phone_sms`、`reg_email_sms`、`email_white_hosts`、`email_black_hosts`、`portrait_status`、`portrait_size`） | 旧配置 `extra/maccms.php` 的 `user` 段（`LegacyConfigService`，启动时读一次）；没有旧配置目录时用仓库值 | 取值规则按旧代码（`== 1`、`intval`）；`portrait_size` 解析为 1～1000 的整数，非法时头像功能按关闭并在启动时记一条 warn |
| `maccms.member.upload-dir` / `MACCMS_MEMBER_UPLOAD_DIR` | 部署参数，默认空 | 旧站根目录下的 `upload/` 的本地路径（头像写到其下 `user/`）；为空时头像上传按"未开启" |
| 可写数据源 | B07 `MACCMS_WRITE_*` | 关闭时 `user/*` 503（第 2 节） |

### 8. 页面模板、视图模型与前端分工

#### 8.1 所有权

| 文件 | 负责 | 内容 |
|---|---|---|
| `backend/src/main/jte/site/pages/user*.jte`、`ajax/user*.jte`、`site.web.view.User*Page` 视图模型、控制器、CSP | 后端（Claude） | 结构、字段、文案、链接、class 与 `data-*` 钩子（第 8.2 节） |
| `backend/src/main/resources/static/site/site.css`（追加"B09"注释段） | 前端（Kimi） | 第 8.2 节 class 的样式 |
| `backend/src/main/resources/static/site/member.js`（新文件） | 前端（Kimi） | 第 8.3 节的行为 |
| `backend/src/test/js/site/member.test.js` | 前端（Kimi） | `node --test`，无外部依赖 |
| `docs/frontend/04-b09-member-ui.md` | 前端（Kimi） | 前端说明与检查记录 |
| `jte/site/layout/`、`jte/site/components/` | 不改 | 外壳的"登录 / 会员中心"链接保持不变 |

会员页面的 CSP 在站点默认策略上把 `script-src 'none'` 换成 `script-src 'nonce-<每次随机>'`（与播放器内页相同的做法），页面末尾一个 `<script nonce src=".../site/member.js" defer>`；不允许内联脚本与事件属性。

#### 8.2 DOM 钩子（后端输出，前端只依赖这些）

```html
<section class="member-page member-page--login" aria-labelledby="member-title">
  <h1 id="member-title" class="member-page__title">会员登录</h1>
  <form class="member-form" method="post" action="/index.php/user/login.html"
        data-member-form="login" data-success-url="/index.php/user/index.html">
    <div class="member-form__field">
      <label class="member-form__label" for="member-user_name">用户名或邮箱</label>
      <input class="member-form__input" id="member-user_name" name="user_name" type="text" autocomplete="username" required>
    </div>
    <div class="member-form__field member-form__field--captcha">            <!-- 只在需要验证码时输出 -->
      <label class="member-form__label" for="member-verify">验证码</label>
      <input class="member-form__input member-form__input--captcha" id="member-verify" name="verify" autocomplete="off" required>
      <button class="member-captcha" type="button" data-captcha-refresh aria-label="看不清，换一张">
        <img class="member-captcha__image" src="/index.php/verify/index.html" data-captcha-src="/index.php/verify/index.html"
             alt="验证码" width="128" height="40">
      </button>
    </div>
    <p class="member-form__message" role="status" aria-live="polite" data-member-message hidden></p>
    <button class="member-form__submit" type="submit">登录</button>
  </form>
  <ul class="member-links">…注册、找回密码…</ul>
  <noscript><p class="notice member-noscript">本页的表单需要启用 JavaScript。</p></noscript>
</section>
```

- `data-member-form` 取值：`login`、`reg`、`info`、`portrait`、`findpass`、`findpass_msg`（发送验证码，B25 前固定失败）、`findpass_reset`、`reg_msg`（注册页的"发送验证码"按钮所在的小表单）。`data-success-url` 只在成功后需要跳转的表单上输出（`login`、`reg` → 会员中心；`findpass` → 登录页）。
- 其他 class：`member-form__hint`（字段说明）、`member-form__value`（只读显示的值，如资料页的用户名）、`member-form__field--inline` 与其中的 `member-form__send`（"发送验证码"按钮，带 `data-member-send`、`data-send-url`）；会员中心 `member-profile`（`<dl>`）、`member-profile__avatar`（`<img data-member-portrait>`）、`member-nav`（资料/头像/退出链接）；头像页 `member-portrait`、`<input type="file" name="file" accept="image/jpeg,image/png,image/gif,image/webp">`；提示页 `jump-page`、`jump-page__message`（成功 `role="status"`，失败 `role="alert"` 并带 `notice--error`）、`jump-page__link`（不自动跳转、不依赖脚本）；片段 `member-fragment`。
- 状态：脚本在表单上设置 `data-state="pending|success|error"`，样式按它区分。

#### 8.3 `member.js` 行为契约

1. 对每个 `form[data-member-form]` 拦截提交：`fetch(form.action, {method: 'POST', credentials: 'same-origin', headers: {'X-Requested-With': 'XMLHttpRequest'}, body})`；`portrait` 用 `FormData`（multipart），其余用 `new URLSearchParams(new FormData(form))`（UTF-8 urlencoded）。请求期间禁用提交按钮，`data-state="pending"`。
2. 响应按 JSON 解析，成功条件 `code === 1`：在 `[data-member-message]` 用 `textContent` 显示 `msg`；有 `data-success-url` 时跳转过去（`location.assign`）；`portrait` 成功时把页面上 `img[data-member-portrait]` 的 `src` 换成响应的 `file`（只接受以单个 `/` 开头的站内路径，否则不换）。
3. 失败（`code !== 1`）：显示 `msg`，`data-state="error"`；表单内有 `[data-captcha-refresh]` 时**刷新验证码并清空 `verify` 输入**（新版猜错即作废）。成功但不跳转时同样刷新验证码。
4. 网络错误或响应不是 JSON：显示固定文案"请求失败，请稍后重试。"。
5. 点击 `[data-captcha-refresh]`：把其中 `img` 的 `src` 设为 `data-captcha-src` + `?r=` + 随机数。
6. 点击 `[data-member-send]`（注册页的手机/邮箱验证区、邮箱/手机找回页）：取所在表单的 `ac`、`to` 两个字段，按第 1 条的方式 POST 到 `data-send-url`，在该表单的 `[data-member-message]` 显示 `msg`（B25 前固定是"验证码发送失败：未配置短信/邮箱发送服务"）。
7. 不读写 cookie，不使用 `innerHTML`，不访问外部域名，不引入依赖。

### 9. B08 的 PHP 7.3 `label_user/expiry-5-g3--1`：复现与结论

- 现象：`333b73e` 的 7.3 黄金记录了 4 个 `Set-Cookie`（`group_id`、`group_name` 各两遍），`0417c14` 重新生成后为 2 个；8.0 两轮都是 2 个。
- 原因（已在一次性库复现）：黄金脚本的 `label_user` 用例为保证"写到期时间与判定在同一秒"，在跨秒时于**同一个 PHP 进程内**重试（`member_golden_child.php` 的 `do … while (time() !== $t)`），而 `setcookie` 写出的头会累加、重试前没有清除。首轮跨秒一次就多出一组完全相同的 `group_id/group_name`。证据：`333b73e` 那条记录 `now=1792305814`，而两组 cookie 的 `expires` 都是 `REQUEST_TIME(…813) + 30 天`——请求在 …813 开始、首轮判定跨到 …814 后重试。与 PHP 版本无关，只取决于那一轮是否跨秒。
- 复现（`member_golden.php` 新增的 `expiry_repro` 段，PHP 7.3.33 与 8.0.30 各跑两次）：

  | 组合 | 尝试次数 | Set-Cookie 个数 |
  |---|---|---|
  | B08 原样累加 + 强制首轮跨秒 | 2 | **4**（两组逐字节相同） |
  | 每轮清除 + 强制首轮跨秒 | 2 | 2 |
  | 每轮清除、不强制 | 1 或 2 | 2 |

- 修正：`member_golden_child.php` 每轮尝试前 `header_remove('Set-Cookie')`，只保留最后一轮（即一次 `label_user`）写出的头，并记录 `attempts` 与 `request_time`；两份黄金文件已重新生成，`label_user` 的 7 个到期用例两版都是 2 个或 0 个。
- 对照基准：**一次请求的到期降级恰好写出 2 个 `Set-Cookie`**（`group_id=2`、`group_name=默认会员`），`expires = 请求开始的秒 + 2592000`。真实请求里 `label_user` 只调用一次，不会出现 4 个；新版按 2 个对照。

### 10. 与旧版的差异（契约稿，实施后补全）

| # | 旧版 | 新版 | 性质 |
|---|---|---|---|
| D-B09-1（D2 ①） | 登录接受 `user_pwd = 输入原文`（库里的 md5 值可直接当密码） | 只比较 `md5(trim(原值))`；输入恰好是库内哈希时按 1003 | 安全修复 |
| D-B09-2（D2 ②） | `user/login` 带非空 `openid` 时按 `col + openid` 直接登录；`user/reg` 带 `user_openid_qq/weixin` 跳过验证码并写进新账号 | 两个入口都忽略这些参数 | 安全修复 |
| D-B09-3（D2 ⑥） | 注销只删 cookie；改密、找回重置不换 `user_random` | 三者都换 `user_random`，旧 `user_check` 立即失效；改密的当前设备重新签发 cookie | 安全修复：一处注销/改密后其他设备都要重新登录 |
| D-B09-4 | 注册判重与插入分两步、IP 限额先数后插，并发可重复/超额 | 一条条件插入（第 6.3 节） | 只会更严 |
| D-B09-5 | 登录写库条件与查询条件相同（同邮箱同密码的多个账号会被一起改写），不检查影响行数 | 按查到的 `user_id` 写一行，匹配 0 行按 1003 | 只会更严 |
| D-B09-6 | "两次密码一致"用 PHP `!=`（数值串按数值比较，`10` 与 `1e1` 相等） | 按字节比较 | 只会更严 |
| D-B09-7 | 推广注册给邀请人加积分并记 `mac_plog` | 本批只写 `user_pid` 链；加积分与流水在 B10 | 分批缺口 |
| D-B09-8 | 短信/邮件"未配置"时的提示取决于线上配置与 `extend/` | 固定为"未配置短信/邮箱发送服务"；不读 `mac_msg` | 分批缺口（B25） |
| D-B09-9 | 头像：原始上传先落盘再被缩略图覆盖；允许扩展名内的非图片文件留在磁盘；写 `mac_annex` | 只写重新编码后的 JPEG；不写 `mac_annex`（B13）；`webp` 不支持 | 加固 + 分批缺口 |
| D-B09-10 | 超长字段（如 31 个字符的用户名、17 位 QQ）在严格模式下 SQL 报错，页面为异常页 | 返回该入口的失败提示（注册 1010、资料"保存失败!"） | 非法输入差异 |
| D-B09-11 | 会员组 ≤2 访问收费数据时查 `mac_ulog` 判断是否已购买 | B10 前不查，统一按"需要积分" | 分批缺口（B10） |
| D-B09-12 | 可写数据源不存在的情形 | 会员入口 503 | 部署差异 |

### 11. 实现说明

| 层 | 文件 | 要点 |
|---|---|---|
| common | `common.web.PhpInput` | 查询串与 urlencoded 正文按字节解析（PHP `php_default_treat_data` 的名字规则，后到覆盖），`merge` 按 ThinkPHP `input()` 合并 GET → POST → 路由参数 |
| common | `common.write.ConditionalOnWriteEnabled` | 写 Mapper 的装配条件（与 `WriteDatabase` 同条件；`maccms.write` 配置名只在 `common.write`，`WriteGuardTest` 锁定） |
| content | `GroupPermission`（原 `GuestPermission`）、`ContentViewer`、`ContentCatalogs`、`VideoPlaybackService`、`ArticleReadService` | 组权限带组号（组不存在为 0）；`ContentViewer` 是请求作用域的观看者会员组，未设置时为游客 1；播放/下载/文章积分按旧 `check_user_popedom` 的组号分支 |
| member | `MemberSessionService.resolvePage`、`MemberAccountService`、`MemberSettings`、`PortraitImages`、`MemberCookies`（可空会员组）、`LegacyText`（原 `LegacyCookieText`） | 第 3、5 节的规则；`MemberAccountService` 只在站点启用时装配（`MemberAccountConfiguration`），站点根路径取 `maccms.site.base-url` 的路径 |
| member.infrastructure | `MemberMapper`（新增 7 条只读查询）、`MemberWriteMapper` + `mapper-write/member/MemberWriteMapper.xml`（8 条写语句）、`PortraitStore`、`MemberInfrastructureConfiguration` | 写 Mapper 只经 `WriteDatabase.mapper()`；头像先写临时文件再改名 |
| site | `SiteUserController`、`SiteMemberInterceptor`、`SiteMemberCacheFilter`、`SitePages.member/memberUnavailable`、`SiteHtmlRenderer.member/memberPlain`、`LegacyUrlBuilder.user(action, vars)`、`view/User*`、`view/Member*` | 路由、参数、JSON 与提示页、cookie、`label_user` 接线、会员页的 nonce CSP |
| jte | `pages/user*.jte`（7 页 + 提示页）、`ajax/userLogin.jte`、`ajax/userInfo.jte`、`member/script.jte`、`member/captcha.jte`、`member/loginForm.jte`、`member/messageBlock.jte` | 只输出第 8.2 节的结构与钩子，不带样式与内联脚本 |
| 配置 | `application.yml`：`maccms.member.upload-dir`、`spring.servlet.multipart`（2 MB / 3 MB）；`config/maccms-backend.env.example` | — |

**为什么用 `ContentViewer`（请求作用域）而不是给内容服务加参数**：页面对内容服务的调用有 79 处（`site.web` 中），逐个加"观看者会员组"参数改动面大、容易漏；旧版本身也是"每个请求一个 `$GLOBALS['user']`"。作用域只由 `SiteMemberInterceptor.preHandle` 打开、`afterCompletion` 关闭（异常时 Spring 同样调用），嵌套时逐层恢复；公开 JSON API 与 Provide 不经过它，始终按游客。内容服务都在请求线程上同步执行（没有线程池或异步调用）。测试：`GroupPopedomGoldenTest.viewerScopeRestoresThePreviousGroup`、`SiteUserMvcTest.labelUserWritesGuestResetAndScopesTheViewerGroupForTheRequestOnly`（请求中为 3 组，结束后回到游客）。

**护栏调整**（随本批提交）：`ModuleBoundaryTest` 允许 site 引用 `member.application(.view)`、`common.web.PhpInput`、`common.clientip.ClientIpResolver`；`WriteGuardTest`/`WriteRegistryTest` 锁定正式登记表只有两条 B09 条目；`SiteTemplatePolicyTest.EXCEPTIONS` 与 `SiteGuardrailsTest` 允许 `member/script.jte` 一处 nonce 外部脚本（引用它的会员页面按例外编译）；`WriteInfrastructureMySqlIT` ②、`MemberGoldenMySqlIT` ⑤ 原来断言"正式登记表为空"，改为"只有 member 的 mac_user 写入、没有演练模块"。02 规范第 2、3、4 节同步。

**黄金脚本**：`backend/src/test/php/account_golden.php`（父进程，用例定义）+ `account_golden_child.php`（php-cgi 子进程，调用旧 `model('User')` 的各方法与 `Base::check_user_popedom`）；输出 `member/account-php80-golden.json`、`account-php73-golden.json`，各 203 个用例：注册 40、登录 18、资料 11、密保找回 7、regcheck 6、短信/邮件"未配置" 13、页面权限 108（4 种组 × 3 种权限位 × 播放 4 / 下载 2 / 文章 2 / 视频详情 1）。两版 PHP 的返回码与提示逐条相同。命令（仓库根目录，需恢复 `vendor/`，管理账号只经环境变量）：
`PHP_CGI=D:/Tools/php/8.0/php-cgi.exe D:/Tools/php/8.0/php.exe backend/src/test/php/account_golden.php > backend/src/test/resources/member/account-php80-golden.json`（7.3 同理）。

### 12. 验证记录与验收对照

（实施后补。）
