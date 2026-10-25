## B09 会员账号：注册、登录、资料、头像、密保找回（路线图 B09，MACC-33）

> **状态：已验收（MACC-23 队长，2026-10-24；前后端 2026-10-18 整合）。** 后端由 Claude 实施；前端 K1–K4 由 Kimi 交付（`4f15676`），以普通 merge 并入本分支，最终接线与真实页面检查由后端完成（第 12.7 节）。WebP 头像按 MACC-23 队长裁定方案 B 实施（第 14 节），WebP 像素上限 2048×2048 由用户 2026-10-24 确认接受（D-B09-13）。 契约稿 v1 随提交 `3008228` 发布；实施中按黄金向量与实现修订的地方见第 13 节"契约修订记录"。实现说明在第 11 节，验证记录与验收对照在第 12 节。
> 依据：路线图 `09-roadmap.md` 第 5.3 节 B09 行与第 5.5 节 D2 ①②⑥；03 草稿第 4.3 (1)(4)(6) 节；B08 文档 `17-b08-member-session-captcha.md`；B07 文档 `12-write-infrastructure.md`。旧代码位置一律相对仓库根目录。

### 0. 起点、分支与提交

| 项 | 值 |
|---|---|
| 运行起点 | 平台基线 `836653af739688c47a79b903cb87d9d14021ecb6`（本任务工作区的快照提交），tree `03c0e610c948c63e4bc6fa597016614dfec8b87a`，与已验收 main `363d7bf0a72e873aafad6aaae98d88d88fa35335`（B08 普通 merge）的 tree 相同 |
| 分支 | `agent/claude/macc-33`（只用普通提交；没有 reset/rebase/amend/force，不自行合并 main） |
| 起始工作树 | 只有平台生成的 `.claude/`、`CLAUDE.md`（未跟踪，不提交）；没有发现重叠工作或冲突 |
| 提交 | 见交付评论（最终 SHA/tree 以评论为准）；前端 `4f15676` 经普通 merge 并入（只合该提交，不合 Kimi 分支头 `9fe0506` 的 `.qwen/tmp/` 临时文件） |

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
- 输入：`multipart/form-data` 的 `file` 字段，或表单参数 `imgdata`（`data:image/<类型>;base64,…`，旧版两种都接受）。上限 2 MiB（旧版取 PHP `upload_max_filesize`，默认 2M）。超过上限（文件超过 2 MB，或整个请求超过 3 MB）时与旧版一样丢弃上传，按"没有文件"返回下面的失败 JSON（第 14.4 节）。
- 失败结果 `{"msg":<提示>,"code":0,"file":"<站点根路径>?<4 位随机数>"}`（旧 `upload_return` 在没有文件时的拼法）：没有文件 → "未找到上传的文件(原因：表单名可能错误，默认表单名“file”或“imgdata”)！"；扩展名不是 `jpg,jpeg,png,gif,webp`（`imgdata` 按 data URL 的类型）→ "非系统允许的上传格式！"；内容不能解码为图片 → "文件上传失败！"（旧 `make_thumb_faild` 的文案）。
- 成功：缩放为 `user.portrait_size`（如 `100x100`，只给一个数时为正方形；旧 `think\Image::thumb(w, h, 6)` 固定尺寸拉伸）后以 JPEG 写到 `<头像目录>/user/<id%10>/<id>.jpg`，`UPDATE mac_user SET user_portrait = 'upload/user/<id%10>/<id>.jpg' WHERE user_id = ?`；返回 `{"msg":"文件上传成功！","code":1,"file":"<站点根路径>upload/user/<id%10>/<id>.jpg?<4 位随机数>"}`。
- 新版只把**重新编码后的图片**写盘，上传的原始字节不落盘（旧版先把原文件存成 `<id>.jpg`，再用缩略图覆盖；扩展名在允许表内但不是图片的文件会留在磁盘上）。`webp` 由 TwelveMonkeys `imageio-webp` 解码后同样重新编码为 JPEG，动图取第一帧（第 14 节，D-B09-13）。
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

会员页面的 CSP 在站点默认策略上把 `script-src 'none'` 换成 `script-src 'nonce-<每次随机>'`（与播放器内页相同的做法），页面内容末尾（全部表单之后）一个 `<script nonce src=".../site/member.js">`，只带 `nonce` 与 `src` 两个属性（`SiteGuardrailsTest` 按文本锁定，所以不加 `defer`；脚本自己按 `readyState` 决定立即还是在 `DOMContentLoaded` 后挂接）；不允许内联脚本与事件属性。

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
| D-B09-9 | 头像：原始上传先落盘再被缩略图覆盖；允许扩展名内的非图片文件留在磁盘；写 `mac_annex` | 只写重新编码后的 JPEG；不写 `mac_annex`（B13） | 加固 + 分批缺口 |
| D-B09-10 | 超长字段（如 31 个字符的用户名、17 位 QQ）在严格模式下 SQL 报错，页面为异常页 | 返回该入口的失败提示（注册 1010、资料"保存失败!"） | 非法输入差异 |
| D-B09-11 | 会员组 ≤2 访问收费数据时查 `mac_ulog` 判断是否已购买 | B10 前不查，统一按"需要积分" | 分批缺口（B10） |
| D-B09-12 | 可写数据源不存在的情形 | 会员入口 503 | 部署差异 |
| D-B09-13 | WebP 头像（GD 带 WebP 时）：静态图按原格式把 WebP 字节写进 `.jpg`；动图 PHP Fatal error、返回错误页；GD 不带 WebP 时"文件上传失败！"（第 14 节） | 静态图与动图都重新编码为真实 JPEG（100×100 等 `portrait_size`），透明处按白底，动图取第一帧；宽×高超过 2048×2048、截断、伪造或声明尺寸自相矛盾的 WebP 按"文件上传失败！" | 有意差异（MACC-23 裁定方案 B）+ 加固；WebP 像素上限 2048×2048（4 194 304，等于时通过；其他格式仍为 40 000 000）：**用户 2026-10-24 确认接受** |

### 11. 实现说明

| 层 | 文件 | 要点 |
|---|---|---|
| common | `common.web.PhpInput` | 查询串与 urlencoded 正文按字节解析（PHP `php_default_treat_data` 的名字规则，后到覆盖），`merge` 按 ThinkPHP `input()` 合并 GET → POST → 路由参数 |
| common | `common.write.ConditionalOnWriteEnabled` | 写 Mapper 的装配条件（与 `WriteDatabase` 同条件；`maccms.write` 配置名只在 `common.write`，`WriteGuardTest` 锁定） |
| content | `GroupPermission`（原 `GuestPermission`）、`ContentViewer`、`ContentCatalogs`、`VideoPlaybackService`、`ArticleReadService` | 组权限带组号（组不存在为 0）；`ContentViewer` 是请求作用域的观看者会员组，未设置时为游客 1；播放/下载/文章积分按旧 `check_user_popedom` 的组号分支 |
| member | `MemberSessionService.resolvePage`、`MemberAccountService`、`MemberSettings`、`PortraitImages`、`WebPHeaders`、`MemberCookies`（可空会员组）、`LegacyText`（原 `LegacyCookieText`） | 第 3、5 节的规则；`MemberAccountService` 只在站点启用时装配（`MemberAccountConfiguration`），站点根路径取 `maccms.site.base-url` 的路径；WebP 解码的限制见第 14.3 节 |
| member.infrastructure | `MemberMapper`（新增 7 条只读查询）、`MemberWriteMapper` + `mapper-write/member/MemberWriteMapper.xml`（8 条写语句）、`PortraitStore`、`MemberInfrastructureConfiguration` | 写 Mapper 只经 `WriteDatabase.mapper()`；头像先写临时文件再改名 |
| site | `SiteUserController`、`SiteMemberInterceptor`、`SiteMemberCacheFilter`、`SitePages.member/memberUnavailable`、`SiteHtmlRenderer.member/memberPlain`、`LegacyUrlBuilder.user(action, vars)`、`view/User*`、`view/Member*` | 路由、参数、JSON 与提示页、cookie、`label_user` 接线、会员页的 nonce CSP |
| jte | `pages/user*.jte`（7 页 + 提示页）、`ajax/userLogin.jte`、`ajax/userInfo.jte`、`member/script.jte`、`member/captcha.jte`、`member/loginForm.jte`、`member/messageBlock.jte` | 只输出第 8.2 节的结构与钩子，不带样式与内联脚本 |
| 配置 | `application.yml`：`maccms.member.upload-dir`、`spring.servlet.multipart`（2 MB / 3 MB，`resolve-lazily`，第 14.4 节）；`config/maccms-backend.env.example` | — |
| 依赖 | `pom.xml`：`com.twelvemonkeys.imageio:imageio-webp:3.12.0`；随包许可 `src/main/resources/licenses/twelvemonkeys-imageio-3.12.0.txt` | 第 14.1 节 |

**为什么用 `ContentViewer`（请求作用域）而不是给内容服务加参数**：页面对内容服务的调用有 79 处（`site.web` 中），逐个加"观看者会员组"参数改动面大、容易漏；旧版本身也是"每个请求一个 `$GLOBALS['user']`"。作用域只由 `SiteMemberInterceptor.preHandle` 打开、`afterCompletion` 关闭（异常时 Spring 同样调用），嵌套时逐层恢复；公开 JSON API 与 Provide 不经过它，始终按游客。内容服务都在请求线程上同步执行（没有线程池或异步调用）。测试：`GroupPopedomGoldenTest.viewerScopeRestoresThePreviousGroup`、`SiteUserMvcTest.labelUserWritesGuestResetAndScopesTheViewerGroupForTheRequestOnly`（请求中为 3 组，结束后回到游客）。

**护栏调整**（随本批提交）：`ModuleBoundaryTest` 允许 site 引用 `member.application(.view)`、`common.web.PhpInput`、`common.clientip.ClientIpResolver`；`WriteGuardTest`/`WriteRegistryTest` 锁定正式登记表只有两条 B09 条目；`SiteTemplatePolicyTest.EXCEPTIONS` 与 `SiteGuardrailsTest` 允许 `member/script.jte` 一处 nonce 外部脚本（引用它的会员页面按例外编译）；`WriteInfrastructureMySqlIT` ②、`MemberGoldenMySqlIT` ⑤ 原来断言"正式登记表为空"，改为"只有 member 的 mac_user 写入、没有演练模块"。02 规范第 2、3、4 节同步。

**黄金脚本**：`backend/src/test/php/account_golden.php`（父进程，用例定义）+ `account_golden_child.php`（php-cgi 子进程，调用旧 `model('User')` 的各方法与 `Base::check_user_popedom`）；输出 `member/account-php80-golden.json`、`account-php73-golden.json`，各 203 个用例：注册 40、登录 18、资料 11、密保找回 7、regcheck 6、短信/邮件"未配置" 13、页面权限 108（4 种组 × 3 种权限位 × 播放 4 / 下载 2 / 文章 2 / 视频详情 1）。两版 PHP 的返回码与提示逐条相同。命令（仓库根目录，需恢复 `vendor/`，管理账号只经环境变量）：
`PHP_CGI=D:/Tools/php/8.0/php-cgi.exe D:/Tools/php/8.0/php.exe backend/src/test/php/account_golden.php > backend/src/test/resources/member/account-php80-golden.json`（7.3 同理）。

### 12. 验证记录与验收对照

#### 12.1 命令（本机，2026-10-18）

| 命令 | 结果 |
|---|---|
| `./mvnw verify` | BUILD SUCCESS，2371 个测试，0 失败、0 错误、0 跳过 |
| `./mvnw verify -Pmysql-it`（MySQL 5.7.44，本机管理账号，一次性库 `maccms_it_*`；`MACCMS_IT_PHP_CGI` 指向 PHP 8.0.30 与 7.3.33 的 php-cgi） | BUILD SUCCESS：单元 2371、集成 53（新增 `MemberAccountGoldenMySqlIT` 6 个），0 失败、0 错误、0 跳过；PHP 8.0 与 7.3 的 cookie 互认两步都实际执行（日志 `PHP label_user accepted the Java-issued cookie`）；`DmlRestore` 47 条后 diff 为空、表结构指纹不变；运行前后 `maccms\_it\_%` 均为 0；没有连接 `maccms_dev`。（这一轮在 `2746e56` 上执行；之后的修正只改了 `SiteUserController` 的 cookie 顺序，见第 12.3 节，`./mvnw verify` 重新通过） |
| 打包 jar 实际 HTTP（第 12.3 节） | 通过：16 个请求，见第 12.3 节；结束后进程已停、一次性库已删并确认不存在、临时目录已删 |
| 黄金生成 | `account_golden.php` 8.0.30 / 7.3.33 各 203 用例，0 个 PHP 侧错误，两版返回码与提示逐条相同；`member_golden.php` 两版重新生成（含 `expiry_repro`） |
| `git diff --check`、UTF-8 无 BOM、路径与密钥扫描 | 通过（提交前逐次执行；测试只用假密码与一次性库，管理账号只经环境变量） |
| WebP 轮（MACC-23 裁定方案 B，最终树） | Node 23/23；`./mvnw verify` 2386 个测试；`-Pmysql-it` 单元 2386 + 集成 53；打包 jar HTTP 46 项与浏览器 12 项（第 14.5 节） |

#### 12.2 测试与覆盖

| 内容 | 测试 |
|---|---|
| 03 第 4.3 (1) 节逐入口密码字节语义（注册 40、登录 18、资料 11、找回 7 个用例：首尾空格、制表、换行、`\0`、`\x0B`、`+`、`%xx`、`& < > " '`、中文、4 字节、全角空格、空串、`0`、只有空格、非法 UTF-8）、各入口返回码与提示原文、regcheck、短信/邮件"未配置" | `MemberAccountGoldenMySqlIT` ①（PHP 8.0 与 7.3 黄金逐条复放：返回码、提示、库里存的 `user_pwd` 与各列、登录 cookie 名与值） |
| D2 ①②⑥ | 黄金中的 `login-stored-hash`、`login-openid`、`reg-openid-skips-verify`（PHP 成功、新版拒绝）；`MemberAccountGoldenMySqlIT` ②：库内 md5 当密码 1003、openid/col 1001、注销/改密/找回重置后旧 cookie 失效、改密响应的新 cookie 有效 |
| 新签发的 cookie 与 PHP 互认 | `MemberAccountGoldenMySqlIT` ② 末尾：找回重置后登录得到的 cookie 交给 PHP 8.0 与 7.3 的旧 `label_user`（连同一个一次性库）均认；B08 的 `JavaIssuedCookiesTest` 仍成立 |
| 同名并发注册只 1 行、IP 限额不超额 | `MemberAccountGoldenMySqlIT` ③：5 轮 × 16 线程（大小写混合同名）每轮 1 成功 15 个 1005、库里 1 行；`reg_num=2` 时 16 个不同用户名并发只成功 2 个 |
| 到期单行降级、并发只命中 1 行、续费不误降、组/游客 cookie 字节 | `MemberAccountGoldenMySqlIT` ④：16 个并发页面请求都按 2 组、库里降一次；`expireMember` 16 线程恰好 1 次匹配；续费与降级竞态 20 轮后都是 3 组且不重发 cookie；重发的 2 个组 cookie 与 PHP 8.0/7.3 黄金 `expiry-5-g3--1`（修正后为 2 个）除有效期外逐字节相同；游客回写与黄金 `guest_reset` 逐字节相同；前置判断不通过时不写 cookie |
| 有效组与到期后 2 组的页面权限 | `GroupPopedomGoldenTest`：黄金 96 个播放/下载/文章用例 × 2 版（游客、2 组、3 组、组不存在）；到期会员的有效组为 2（`MemberSessionService` 与 IT ④） |
| 头像 | `MemberAccountUnitTest`（检查顺序、扩展名、`<?php`、危险内容正则、重新编码尺寸）、`PortraitImagesWebPTest`（WebP，第 14.5 节）、`MemberAccountGoldenMySqlIT` ⑥（写盘、`user_portrait`、登录 cookie 的头像地址；WebP 动图写成第一帧的 JPEG、截断时不动已有头像）、`SiteUserRealHttpTest`（超过上传上限时的 JSON，第 14.4 节） |
| HTTP 契约 | `SiteUserMvcTest`（11 个：字节参数、JSON 字节与键顺序、跳转、未登录与 503、注销、uid cookie、regcheck、info 的 success/error 形态、头像、label_user 接线与观看者作用域、verify 不参与）、`SiteUserRealHttpTest`（真实 Tomcat：6 个登录 `Set-Cookie` 与 2 个游客回写逐字节、表单字节到达服务） |
| 回滚演练与零 DDL | `MemberAccountGoldenMySqlIT` ⑦：S0 → 本 IT 全部写入 → `DmlRestore` → diff 为空 → 表结构指纹不变；删库后确认不存在 |
| 护栏 | `WriteGuardTest`、`WriteRegistryTest`、`ModuleBoundaryTest`、`SiteTemplatePolicyTest`、`SiteGuardrailsTest`、`SchemaGuardTest`（不变） |

#### 12.3 打包 jar 实测

`./mvnw package -DskipTests` 得到的 `maccms-backend-0.1.0-SNAPSHOT.jar`，一次性库 `maccms_it_<时间>_<随机>`（导入 `install.sql`），可写数据源打开、站点启用、旧配置目录为仓库 `application/`、头像目录为临时目录，端口 18281。脚本只用假密码；输出保存在本机，不入库。

| 请求 | 结果 |
|---|---|
| `GET user/reg` | 200，`no-store`，CSP `script-src 'nonce-…'` |
| `POST user/reg`（`user_pwd=%20pa%2Bss%20`） | `{"code":1,"msg":"注册成功，登录成功"}`；6 个 `Set-Cookie` 与 `MemberCookies` 相同（`user_check` 带 HttpOnly）；库里 `user_pwd = MD5('pa+ss')`（`md5(trim(原值))`） |
| `GET user/index`（带上一步的 cookie） | 200 会员中心 |
| `POST user/login`，密码填库里的 md5 值 | `{"code":1003,"msg":"获取用户信息失败"}`（D2 ①） |
| `POST user/login`，`openid=x&col=user_openid_qq` | `{"code":1001,"msg":"请填写必填项"}`（D2 ②） |
| `POST user/login`，`HTTPUSER01` / `pa+ss` | 登录成功（用户名不分大小写）；注册时签发的 cookie 随之失效（同旧版） |
| `POST user/info` 改密 | `{"code":1,"msg":"保存成功!","data":"","url":null,"wait":3}` + 重新签发的 6 个 cookie；改密前的 cookie 访问 `user/index` 得到"未登录"（D2 ⑥） |
| `POST user/portrait`（300×200 PNG） | `{"msg":"文件上传成功！","code":1,"file":"\/upload\/user\/1\/1.jpg?…"}`；磁盘上 `user/1/1.jpg`（100×100 JPEG）；`user_portrait = upload/user/1/1.jpg` |
| 库里改为 3 组、已到期后访问 `user/index` | 写出 `group_id=2`、`group_name=默认会员` 两个 cookie；库里 `group_id = 2` |
| 伪造 cookie 访问 `user/index` | 依次写出游客回写 2 个 + 删除 6 个（与旧版 `label_user` → 构造函数 `logout()` 的顺序相同），提示页"未登录" |
| `GET user/regcheck?t=user_name&str=HTTPUSER01`（已登录） | 正文 `HTTPUSER01`（已注册） |
| `GET user/logout` | 302 → `user/login.html` + 6 个删除 cookie；之后旧 cookie 访问 `user/index` 为"未登录"（D2 ⑥） |
| `GET user/findpass`、`POST user/findpass_reset` | 200；`{"code":9002,"msg":"验证信息错误，请重试"}` |
| 结束 | 进程已停；表结构指纹（列与索引）前后相同；库已删除，`maccms\_it\_%` 为 0；应用日志 28 行，0 行 ERROR，不含 JDBC 地址；临时目录已删 |

这一轮实测发现并修正了一个问题：`user/*` 的响应自己带 `Set-Cookie` 时，Spring 写出 `ResponseEntity` 会替换拦截器先加上的同名头，游客回写的 2 个 cookie 丢失（浏览器的最终结果相同，但与旧版的头不一致）。修正：`SiteUserController.withLabelUserCookies` 把 `label_user` 的 cookie 按旧顺序放在前面；`SiteUserRealHttpTest` 增加回归（真实 Tomcat 上 8 个 `Set-Cookie` 的顺序与字节）。

#### 12.4 验收对照（任务 MACC-33）

| # | 验收标准 | 结果 |
|---|---|---|
| 1 | 03 第 4.3 (1) 节逐入口密码字节语义 PHP 黄金通过；注册、登录、找回、资料、头像 HTTP 契约覆盖 | ✅ 第 12.2 节 |
| 2 | D2 ①②⑥；轮换 `user_random`、旧 `user_check` 即时失效、新 cookie 与 PHP 互认；差异逐条记录 | ✅ 第 10 节 D-B09-1～3，第 12.2 节 |
| 3 | 到期实际降级、并发只命中 1 行、续费不误降；组/游客 cookie 字节对照黄金；有效组与到期后 2 组页面权限对照黄金 | ✅ 第 5、9、12.2 节 |
| 4 | MyISAM 无唯一约束下同名并发注册只 1 行，方案与作用范围；只登记必要写路径；可写数据源默认关闭，B07 零 DDL/写入护栏保留 | ✅ 第 6 节；登记两条；`MACCMS_WRITE_ENABLED` 默认 false |
| 5 | `./mvnw verify` 与 `-Pmysql-it`；并发写、DML 回滚演练、前后 information_schema 一致；打包 jar 实际 HTTP、测试库与进程清理有证据 | 见第 12.1、12.3 节 |
| 6 | `git diff --check`、UTF-8 无 BOM、路径与密钥检查；交付 SHA/tree、分支、验证摘要、差异、未验证项、回滚点；文档与路线图状态 | ✅ 本文、路线图 B09 行；SHA/tree 见交付评论 |
| — | 契约与前端接线（整批验收） | ✅ 第 12.7 节：`4f15676` 普通 merge、`member.js` 补第 6 条、根路径与子目录接线、四宽度/纯键盘/reduced-motion 实测 |
| — | WebP 头像（MACC-23 裁定方案 B） | ✅ 第 14 节：依赖与许可核对、打包 jar 能发现读取器、有损/无损透明/有损透明/动图首帧、截断/伪造/超限、上传大小上限的失败 JSON、根路径与子目录真实 HTTP 上传与读回 |

#### 12.5 未验证与遗留

- 浏览器内的完整交互已在第 12.7 节实测；只验证了 Chromium（Playwright 1.60 / Chromium 148），没有测 Firefox、Safari 和真机。
- 线上 `maccms.php` 的 `user` 段、`sms`/`email` 配置与 `extend/` 是否存在无法核实（D9-b），"未配置"时的提示按仓库值与黄金的"未配置"设定。
- `mac_annex` 登记、推广积分与流水、组 ≤2 的已购买判定为分批缺口（第 10 节）。
- 头像目录 `/upload/` 和默认头像 `/static/images/touxiang.png` 都由部署层提供。第 12.7 节已在一个只为检查而起的 nginx（子目录 `/sub/`）上验证过，生产 nginx 配置不在本批范围。
- WebP 的遗留见第 14.6 节。

#### 12.7 前后端整合（MACC-23 队长指令，2026-10-18）

- **合并**：`git merge --no-ff 4f15676`（Kimi 的 K1–K4），没有冲突。双方历史都保留，没有合 Kimi 分支头 `9fe0506`。
- **接线**：
  - 会员页面末尾的 `site/member/script.jte` 输出 `<script nonce src="<站点根路径>site/member.js">`，nonce 与本响应 CSP 中的相同（第 8.1 节）。
  - 表单 `action`、`data-success-url`、`data-captcha-src`、默认头像和上传后的头像地址，都由 `SiteSettings.publicPath` 按 base-url 的路径部分生成，子目录部署时带前缀。
  - 契约第 8.2 节的钩子（`data-member-form`、`data-success-url`、`data-captcha-src`、`data-captcha-refresh`、`img[data-member-portrait]`、`data-member-message`、`data-member-send`/`data-send-url`、`data-state`）与 `member.js` 的选择器逐个对过。
  - 前端依据的契约稿 v1 只有 6 条，没有第 6 条"发送验证码"（第 13 节修订记录），整合时补进 `member.js`，测试增加 2 项。
- **ContentViewer 门槛**：新增 `SiteContentViewerIsolationTest`，在真实 Tomcat 上运行，线程池 4 个线程，共 3 项：
  1. 处理器抛异常退出 60 轮。每轮之后不断发公开 API 请求，直到有一个落在刚才那个工作线程上，并断言它按游客组 1 取权限。站点页面每次都会重新进入作用域，看不出泄漏，所以用不设作用域的 API 去探测。
  2. 4 个会员组不同的请求在处理器里用栅栏等齐，确保同时在途，各自看到自己的组。共 25 轮，每轮之后游客页面和 API 都按组 1。
  3. 带会员 cookie 的公开 API 按组 1 取权限，而且不调用会员判定。

  变异检查：去掉 `afterCompletion` 里的 `close()` 后，3 项全部失败；恢复后全部通过。
- **真实页面**：四个宽度、纯键盘、reduced-motion、表单状态、出错后刷新验证码、头像更新、CSP nonce、根路径与子目录接线，结果见 `docs/frontend/04-b09-member-ui.md` 第 4.3 节。
- **部署要求（补充）**：除 `/upload/` 外，默认头像 `<站点根路径>static/images/touxiang.png` 也由 web 服务器从旧站 `static/` 提供，新应用不提供 `/static/`。这与 B05 起旧静态资源的做法相同。

#### 12.6 回滚

- 代码：合并后如需回退，`git revert -m 1 <合并提交>`。不改表结构、不改旧 PHP、不建账号。
- 数据：新版只按旧语义写 `mac_user` 现有列（密码仍是 `md5(trim(原值))`），回滚到 PHP 后旧版能读懂全部写入；新版签发的 cookie 旧版照认（第 12.2 节）。新版轮换过 `user_random` 的会员在回滚后需重新登录一次以外没有影响。头像文件与旧版同路径同格式。

### 13. 契约修订记录（相对 `3008228` 的契约稿 v1）

| 项 | v1 | 现行 | 原因 |
|---|---|---|---|
| `regcheck` 是否需登录 | 否 | 是 | 旧构造函数的白名单（`index/controller/User.php:19`）不含 `regcheck`，游客得到"未登录"；照搬 |
| 未实现的 `user/*` | 一律 404 | 先按白名单检查登录态，游客"未登录"，已登录 404 | 与旧构造函数的顺序一致 |
| 非 ajax 的提示页 | `<meta refresh>` 自动跳转 | 只给链接，不自动跳转 | 页面不输出额外的跳转机制，保持无脚本 |
| 表单正文超限 | 413 | 400 | 不新增对外错误码（`ErrorCode` 属于 OpenAPI 契约） |
| `member.js` | 6 条 | 7 条，新增 `[data-member-send]` 发送验证码 | 注册页/找回页的"发送验证码"按钮需要从同一表单取 `ac`、`to` |
| 头像 `accept` | 含 webp | 含 webp（曾改为不含，WebP 裁定方案 B 后恢复） | 曾因 JDK 没有 webp 解码器去掉；方案 B 引入解码器后恢复（第 14 节） |
| 超过上传上限的 multipart | 未定义 | 同旧版丢弃上传，头像返回"未找到上传的文件…"的 JSON | 此前在进入处理器前就成了 500（`INTERNAL_ERROR`），不符合失败 JSON 契约（第 14.4 节） |

### 14. WebP 头像：旧版实际行为、裁定与实施（MACC-23）

**裁定**（MACC-23 队长，2026-10-18）：方案 B。旧版在 GD 带 WebP 时可以上传静态 WebP，新版保留这项能力，用 TwelveMonkeys `imageio-webp` 3.12.0 纯 Java 解码；输出统一为真实 JPEG；透明处沿用现有头像处理（白底）；动图取第一帧，作为与旧版错误页的有意差异（D-B09-13）。裁定前的三个方案（A 保持现状、B、C 外部工具）记录在提交 `5747ae0` 的本节。

**像素上限裁定**（用户 2026-10-24 确认接受）：WebP 头像宽×高超过 2048×2048 时按"文件上传失败！"拒绝（第 14.3 节，`PortraitImages.MAX_WEBP_PIXELS`），其他格式仍为 40 000 000 像素。理由：正常头像不会超过 2048 像素；这个上限防的是文件很小、像素很多的 WebP 占满 CPU（同样像素数 WebP 解码约是 PNG 的 8 倍）。作为 D-B09-13 的一部分。

**旧版实际行为**：用仓库代码与恢复的 `vendor/topthink/think-image` 在本机实测，PHP 7.3.33 与 8.0.30 的 GD 都带 WebP。

- 扩展名表 `jpg,jpeg,png,gif,webp`（`common/model/Upload.php:55`）允许 webp。原文件先移动成 `<id>.jpg`，再执行 `think\Image::open(...)->thumb(w, h, 6)->save('./upload/user/<n>/<id>.jpg')`（`Upload.php:186-199`）。
- `save()` 没有传类型，所以按原图类型保存（think-image `Image.php:118-144`）：静态 WebP（有损，以及无损带透明）得到 **WebP 字节、文件名是 `.jpg`** 的 100×100 头像，返回"文件上传成功！"。PNG、GIF 也同样按原格式写进 `.jpg`，新版统一重新编码为 JPEG，已列为 D-B09-9 的加固。
- **动图 WebP**：GD 解码时报 PHP Fatal error（`gd-webp cannot allocate temporary buffer`），请求得到的是错误页，不是 JSON。
- PHP 编译时不带 WebP 的 GD：没有 `imagecreatefromwebp`，`think-image` 抛异常，返回"文件上传失败！"（`make_thumb_faild`）。线上 PHP 的 GD 是否带 WebP 无法核实（D9-b）。

#### 14.1 依赖、来源与许可

`./mvnw dependency:tree` 显示 `imageio-webp` 只带 5 个传递依赖，全部是 compile 作用域、版本 3.12.0。6 个 jar 都取自 Maven Central（本地仓库的 `_remote.repositories` 记为 `central`），原样打进可执行 jar 的 `BOOT-INF/lib/`：

| 构件 | 字节 | SHA-256 | 经 SPI 注册（`META-INF/services`） |
|---|---|---|---|
| `com.twelvemonkeys.imageio:imageio-webp:3.12.0` | 79 713 | `43dc447c…68526` | `ImageReaderSpi`：`WebPImageReaderSpi`（只读，没有写入器） |
| `com.twelvemonkeys.imageio:imageio-core:3.12.0` | 119 909 | `34c6f91b…d9815` | `ImageInputStreamSpi`：`BufferedFileImageInputStreamSpi`、`BufferedRAFImageInputStreamSpi`、`BufferedInputStreamImageInputStreamSpi`、`ProfileDeferralActivator$Spi` |
| `com.twelvemonkeys.imageio:imageio-metadata:3.12.0` | 83 615 | `797d026b…0caa` | 无 |
| `com.twelvemonkeys.common:common-lang:3.12.0` | 121 817 | `5c65e504…3bc66d` | 无 |
| `com.twelvemonkeys.common:common-io:3.12.0` | 92 346 | `bfacfeb0…6f6647` | 无 |
| `com.twelvemonkeys.common:common-image:3.12.0` | 79 655 | `62a3821c…dd92bc` | 无 |

合计 576 055 字节。完整 SHA-256 见随包许可文件。

- **许可**：父 POM `com.twelvemonkeys:twelvemonkeys:3.12.0` 声明 "The BSD License"；上游标签 `twelvemonkeys-3.12.0` 的 `LICENSE.txt` 是 BSD 3-Clause。又逐个检查了 6 个构件同版本 `-sources.jar` 里的全部源文件头：
  - 都是同一份 BSD 3 条款，版权人 Harald Kuhr（2008～2022）；`imageio-webp` 的 `vp8/` 下 11 个文件（VP8 有损解码）版权人是 "Brooss, Harald Kuhr"。
  - `common-image` 的 `IndexImage.java` 另带 Apache Software License 1.1（取自 Batik），要求在文档或软件里给出致谢语。
  - `ResampleOp.java`、`LittleEndianDataInputStream/OutputStream.java` 带公有领域说明。
  - 没有发现 GPL、LGPL 等其他许可。
- **随包声明**：这些 jar 本身不带许可文件。所以把 BSD 全文、Brooss 共同版权、ASF 1.1 全文和致谢语、公有领域说明逐字照录到 `backend/src/main/resources/licenses/twelvemonkeys-imageio-3.12.0.txt`。它打进可执行 jar 的 `BOOT-INF/classes/licenses/`（打包后已核对），不在任何对外 URL 下。
- **格式范围不扩大**：
  - `PortraitImagesWebPTest.onlyAWebPReaderIsAddedToImageIo`：JDK 之外注册的 `ImageReaderSpi` 恰好只有 WebP 一个，没有新增 `ImageWriterSpi`。
  - 扩展名表 `jpg,jpeg,png,gif,webp` 与 data URL 类型表不变，`.bmp` 仍是"非系统允许的上传格式！"。
  - 本应用用 ImageIO 读图只有 `PortraitImages` 一处（验证码、二维码只写 PNG）。它显式使用 `MemoryCacheImageInputStream`，不经 imageio-core 注册的流 SPI，也不写临时文件。
  - `ModuleBoundaryTest.twelveMonkeysIsOnlyUsedForThePortraitWebPReader`：主代码中只有 `PortraitImages` 引用 TwelveMonkeys，而且只引用 `WebPImageReaderSpi`。`common-io` 里有网络工具类 `com.twelvemonkeys.net`，不使用。
- **读取器的发现**：`PortraitImages` 加载时用 `IIORegistry.registerServiceProvider(new WebPImageReaderSpi())` 显式注册（同一个类重复注册只是替换）。这样不依赖首次初始化 ImageIO 的线程能否通过上下文类加载器看到 `BOOT-INF/lib`。打包 jar 的实测见第 14.5 节。

#### 14.2 行为

- 支持有损（`VP8 `）、无损（`VP8L`）、带透明（无损 alpha、有损 + `ALPH`）和动图（`VP8X` + `ANIM`/`ANMF`）。解码后与其他格式走同一路径：拉伸为 `portrait_size`，以 JPEG（质量 80）写盘。透明像素画在白底上，在两个采样点上与同样内容的 PNG 透明图相差不超过 4。动图取第 0 帧。
- 检查顺序与失败提示不变：没有文件 → 扩展名 / `<?php` → 危险内容正则 → 解码。失败 JSON 的键顺序也不变。
- 头像页 `accept` 恢复 `image/webp`，提示改为"支持 jpg、png、gif、webp（动图取第一帧），不超过 2 MB。"

#### 14.3 资源限制：实测发现的问题与处理

1. **帧尺寸绕过像素检查，导致 OutOfMemoryError**。读取器的 `getWidth/getHeight` 只报画布尺寸。一个 140 字节的伪造文件，画布 100×100，首帧（`ANMF` + `VP8L`）声明 16384×16384，能通过原有的画布像素检查，解码时按帧尺寸分配内存，`-Xmx512m` 下 OutOfMemoryError。
   - 处理：`WebPHeaders` 在解码前逐块检查。画布、每个 `ANMF` 帧、每个 `VP8 `/`VP8L` 位流的宽×高都不能超过上限；帧必须在画布内；位流不能大于所属的帧（静态图则不能大于画布）；没有动图标志时不能出现帧；块长度不能越界；有损位流必须有起始码 `9d 01 2a`。
2. **截断或损坏的数据让解码器空转**。`LSBBitReader.refillBuffer` 在流尾捕获 `EOFException` 后继续用残留的位解码。1600 个随机变异样本的测试 9 分钟没有跑完，线程栈停在 `refillBuffer` ← `HuffmanTable.readCodeLengths`。
   - 处理：解码用的流在"读到流尾"超过 1024 次后抛非受检异常，该样本按"文件上传失败！"。合法样本解码时最多 31 次（含 2000×2000 有损/无损）。加上后最慢的变异样本约 0.1 s。
3. **解码耗时，以及 WebP 自己的像素上限**。本机实测（JDK 21，单线程，预热后）：

   | 样本 | 耗时 |
   |---|---|
   | WebP 有损 2000×2000 / 3000×3000 / 4096×4096（噪声） | 0.9 s / 2.3～2.6 s / 4.6～4.9 s |
   | WebP 无损 2000×2000 / 4096×4096（噪声） | 0.7 s / 3.0～3.4 s |
   | WebP 有损 6000×6000 纯色（文件只有 64 KB） | 4.2～4.8 s |
   | JDK PNG 6000×6000 纯色 / 4096×4096 噪声；JPEG 6000×6000 | 0.6 s / 0.45 s；0.4～0.6 s |

   同样的像素数，WebP 约是 PNG 的 8 倍。所以 WebP 另设像素上限 2048×2048（4 194 304，等于时通过），超过时按"文件上传失败！"；其他格式仍是 40 000 000。按这个上限，单个 WebP 最多约 1 s CPU，与其他格式在原上限下同一量级。另外，3.12.0 解码 6000×6000 以上的纯色无损图会抛 `ArrayIndexOutOfBoundsException`，这已超出上限，不影响结果。

#### 14.4 上传大小上限的失败 JSON

- **旧版**：超过 `upload_max_filesize` 的文件被 PHP 丢弃（`$_FILES` 没有 `tmp_name`，`request()->file()` 为空，`thinkphp/library/think/Request.php:917`），`Upload.php:136-139` 返回 `no_input_file`。超过 `post_max_size` 时整个 POST 被丢弃，提示相同。
- **新版此前**：Spring 在进入处理器之前解析 multipart，超限时抛 `MaxUploadSizeExceededException`，被全局处理成 500 `{"code":"INTERNAL_ERROR",…}`，并记一行 ERROR 日志。打包 jar 实测如此，与图片格式无关。
- **现在**：
  - 设置 `spring.servlet.multipart.resolve-lazily=true`。`SiteUserController` 在取参数和文件时捕获 `MultipartException`，当作没有 POST 字段和文件。头像返回 HTTP 200 `{"msg":"未找到上传的文件(原因：表单名可能错误，默认表单名“file”或“imgdata”)！","code":0,"file":"<站点根路径>?<4 位随机数>"}`，已有头像不变。
  - 其他读取 multipart 参数的入口（ajax、verify、Provide、公开 API）此前在解析失败时就是 500，现在仍是（只是发生在处理器内）。不读参数的入口不再因此 500。
- **部署注意**：
  - 直连 Tomcat 时，整个请求超过 3 MB 会被 Tomcat 按 `Content-Length` 直接拒绝，正文不读完。剩余部分超过默认 2 MB 的 `maxSwallowSize` 时 Tomcat 关闭连接，客户端可能收到连接重置。
  - 部署在 nginx 之后（nginx 先收完请求体），实测仍得到上面的 JSON。
  - nginx 的 `client_max_body_size` 需要不小于 3 MB。默认是 1 MB，这时 nginx 自己返回 413。

#### 14.5 验证

- **样本**：`backend/src/test/resources/member/webp/` 下 6 个，由同目录的 `generate.py`（Pillow 12.3.0）合成，没有外部素材：
  - 有损 300×200 渐变；
  - 无损带透明、有损带透明各一，300×200，左半透明、右半红；
  - 两帧动图 120×80，第一帧蓝、第二帧绿；
  - 2048×2048 与 2049×2048 纯色无损。
  伪造、截断和变异样本在测试里构造。
- **`PortraitImagesWebPTest`（10 项）**：
  - 只多 WebP 读取器；
  - 有损的像素；
  - 两种透明都是白底红右，并与 PNG 一致；
  - 动图第一帧，样本确有 2 帧；
  - `imgdata` 的 WebP；扩展名表不变；危险内容仍先于解码；
  - 上限 2048×2048 通过、2049×2048 失败；
  - 10 种伪造头部（过大的无损位流、过大的有损位流、大画布、小画布大位流、小画布大帧、帧内位流大于帧、帧越出画布、无动图标志的帧、块长度越界、缺起始码）都在解码前拒绝；
  - 合法样本通过头部检查；
  - 乱码位流；4 个样本截断到每一种长度；
  - 4 × 400 个随机变异样本，每个要么得到 100×100 JPEG，要么"文件上传失败！"，最慢的小于 10 s（实测约 0.1 s）。
- **`MemberAccountGoldenMySqlIT` ⑥**：WebP 动图经服务写成同路径的真实 JPEG（SOI、100×100、第一帧蓝）；截断的 WebP 返回"文件上传失败！"，磁盘上的头像字节和 `user_portrait` 都不变。
- **`SiteUserRealHttpTest.oversizedPortraitUploadsAreDroppedLikeLegacyPhp`**：在真实 Tomcat 上，文件为 2 MiB + 1、2 MiB + 4 KiB、2 995 904 字节时，都得到上面的 JSON，服务收到的文件和 `imgdata` 都是 null。
- **`ModuleBoundaryTest.twelveMonkeysIsOnlyUsedForThePortraitWebPReader`**：见第 14.1 节。
- **命令（最终树，串行）**：
  - `node --test backend/src/test/js/site/*.test.js`：23/23；
  - `./mvnw verify`：2386 个测试，0 失败、0 错误、0 跳过（比整合轮的 2374 多 12 个：WebP 10、边界护栏 1、超限 1）；
  - `./mvnw verify -Pmysql-it`：单元 2386、集成 53，0 跳过。

  实际输出摘要与 SHA/tree 见交付评论。
- **打包 jar 实测**：用 `-Pmysql-it` 产出的最终 jar 起两个实例。根路径实例的 base-url 为 `/`，子目录实例为 `/sub/`。两个实例各用一个一次性库 `maccms_it_b09webp_*`（导入 `install.sql`），各有一个临时头像目录，可写数据源打开，旧配置目录为仓库 `application/`（`portrait_size=100x100`）。前面是一个只为检查而起的 nginx：根路径实例由它提供 `/upload/`，子目录实例提供 `/sub/upload/`，`client_max_body_size 4m`。脚本只用假密码，经 nginx 发请求。每个实例 23 项，共 46 项，全部通过：
  - 注册后头像页 `accept` 含 `image/webp`，CSP 带 nonce；
  - 有损、无损透明、有损透明、动图、2048×2048 五种 WebP 上传都得到 `{"msg":"文件上传成功！","code":1,"file":"<前缀>upload/user/1/1.jpg?…"}`（键顺序同旧版）。再经 nginx GET 该地址：200 `image/jpeg`，内容是 100×100 的 JPEG，与磁盘文件逐字节相同；透明处是白底，动图中心像素为 (0,0,254)；
  - `imgdata=data:image/webp;base64,…` 成功；
  - 截断、乱码、"小画布大帧"（修复前会 OOM）、大画布、2049×2048、6000×6000 有损（64 KB）都得到 `{"msg":"文件上传失败！","code":0,"file":"<前缀>?…"}`，每个都在 0.2 s 内返回；
  - `.bmp` 扩展名仍是"非系统允许的上传格式！"；
  - 失败后头像文件不变，会员中心显示上传后的头像；
  - 文件 2 MiB + 1 与 2 MiB + 4 KiB 时得到"未找到上传的文件…"的 JSON，头像不变；整个请求 3.5 MiB 时经 nginx 同样得到该 JSON；略小于 2 MiB 的 WebP 照常成功。
- **浏览器实测**：Playwright 1.60 / Chromium，1280 宽，用真实头像页和 `member.js`，根路径和子目录各 6 项，全部通过：
  - `accept` 与提示文案正确；
  - 选动图 WebP 提交后，表单 `data-state=success`，消息"文件上传成功！"。`img[data-member-portrait]` 换成 `<前缀>upload/user/1/1.jpg?…`，经 nginx 加载为 100×100，中心像素为蓝；
  - 截断的 WebP 得到 `data-state=error`、"文件上传失败！"，头像不变；
  - 没有页面错误或控制台错误（包括 CSP 违规）。
- **清理**：
  - 两个 jar 进程按启动时记录的 PID 结束；自起的 nginx 用 `-s stop` 停止，共享 nginx 服务没有动；
  - 两个一次性库已删除，并确认 `maccms\_it\_%` 为 0；
  - 应用日志 0 行 ERROR、0 行 WARN，不含 JDBC 地址；
  - 临时目录（样本、脚本、nginx 前缀、头像目录）已删除。

#### 14.6 遗留与请队长确认

- **WebP 像素上限 2048×2048 是新增的限制**（第 14.3 节），**用户 2026-10-24 确认接受**（第 14 节开头、D-B09-13）。旧版在 GD 带 WebP、内存够用时能处理更大的 WebP，新版对这类图片返回"文件上传失败！"，例如 4000×3000 的手机照片转成的 WebP（文件小于 2 MB 时）。常量在 `PortraitImages.MAX_WEBP_PIXELS`。
- imageio-core 的 `ImageInputStreamSpi` 对整个 JVM 生效。以后如果有代码用 `ImageIO.createImageInputStream(File/InputStream)`，拿到的会是 TwelveMonkeys 的流实现。目前主代码没有这种用法（第 14.1 节）。
- 没有专门处理 ICC 配置文件和 EXIF 方向，与其他格式的现状相同。只测了 Chromium。
- 部署要求：nginx 的 `client_max_body_size` 不小于 3 MB（第 14.4 节）。
- 已有限制，不在本轮范围：`imgdata` 用 urlencoded 提交时受表单正文 64 KiB 上限约束（第 3.1 节，超出返回 400 站点错误页）；用 multipart 提交时上限 3 MB。
