## B26：QQ/微信第三方登录与绑定（MACC-49）

依据：路线图 `09-roadmap.md` 第 4.1 节 E10 行、第 4.3 节 ③（E10 修复后的安全方案）、第 5.4 节 B26 行、第 5.5 节 D2 ②；B23 的出站客户端、白名单与本机模拟服务（`26-b23-integration-foundation.md`）；B08/B09 的会话、登录 cookie 与注册（`17-b08-member-session-captcha.md`、`18-b09-member-account.md`）。

### 1. 范围

| 编号 | 内容 | 旧版 | 新版 |
|---|---|---|---|
| ① | 发起授权 `user/oauth/type/<qq\|weixin>` | `index/controller/User.php:203-210`（`ThinkOauth::getRequestCodeURL`） | `MemberOAuthService#start`、`SiteUserController#oauthStart` |
| ② | 授权回调 `user/logincallback/type/<qq\|weixin>` | `User.php:212-285` | `MemberOAuthService#callback`、`SiteUserController#oauthCallback` |
| ③ | 服务端换令牌、openid、用户信息 | `extend/login`（**仓库与备份都缺失**）、`index/event/LoginEvent.php` | `integration.oauth.*`（接口 `integration.application.OAuthLogin`） |
| ④ | 已登录 → 改绑；游客 → 自动注册（未绑定时）再凭 openid 登录 | `User.php:232-279`、`common/model/User.php` 的 `register`、`login` | `MemberAccountService#openidBind`、`#openidLogin` |
| ⑤ | 登录页显示已打开的第三方登录入口 | 旧模板缺失（D9-a） | `site/member/loginForm.jte`（登录页与 `ajax_login` 片段共用） |
| ⑥ | 白名单登记 `integration.oauth` | — | `IntegrationPackages` |

零 DDL；不改旧 PHP；不改后台页面（"整合登录配置"页是 B15 已转换的旧页面，本批只读取它保存的 `connect.*`）；没有新增 openid 解绑入口（旧版没有）。

### 2. 安全方案（路线图第 4.3 节 ③）

1. **openid 只来自服务端换取**：回调只取请求里的 `type`、`code`、`state`；`openid`、`col`、任何跳转地址参数都不看。普通登录 `user/login` 从 B09 起忽略 `openid`/`col`，注册 `user/reg` 忽略 `user_openid_*`（本批复验，见第 6 节）。
2. **`state`**：发起授权时生成 256 位随机数（64 位十六进制），连同方式、发起时的会员编号（游客 0）、发起时刻存进进程内会话（B08 的 `SessionStore`，键 `member.oauth.pending`，每个会话只保留最近一次）。没有会话时新建并写出会话 cookie（`PHPSESSID`，HttpOnly、`SameSite=Lax`——从授权页跳回的顶层 GET 仍会带上）。
3. **回调校验**（全部在出站之前）：回调一到达就从会话里**取出并作废**待用的 `state`（失败也作废，不能再试）；然后 `code` 为空 →"参数错误"；方式未打开 →"参数错误"；没有会话或没有待用 `state`、值不等（常量时间比较）、方式不符、超过 10 分钟、登录身份与发起时不同 →"登录请求已失效，请重新登录"。会话绑定同时防住"登录 CSRF/强制绑定"：把攻击者自己的回调地址发给已登录的受害者，受害者会话里没有对应的 `state`。
4. **回调地址固定**：`maccms.site.base-url` + `index.php/user/logincallback/type/<方式>`，发起授权与换令牌用同一个值；不取 Host 等请求头（旧版用 `$_SERVER['HTTP_HOST']` 拼接，可被 Host 头影响）。
5. **登录 cookie**：凭 openid 登录成功后签发与普通登录相同的 6 个 cookie（B08 `MemberCookies.login`，`user_check` 同算法），并像普通登录一样轮换 `user_random`。
6. **绑定只在已登录时**：只有回调时已登录（B08 的 cookie 校验通过）才走改绑；游客回调永远不会把 openid 写到已有账号上。
7. **自动注册的密码不可猜测**：旧版 `user_pwd = md5(time())`（黄金实测），新版 `md5(<256 位随机数的十六进制>)`，没有人知道原文（D-B26-1）。

### 3. 协议（`integration.oauth`）

旧版依赖的 `extend/login`（ThinkOauth）缺失，具体请求【待核实】。按 QQ 互联、微信开放平台公开的网站应用授权码流程实现；应用标识 `connect.<方式>.key`、密钥 `connect.<方式>.secret`（经 `ThirdPartyConfig.secret` 读取），"已打开" = `connect.<方式>.status` 为 `1` 且两项都不为空。

#### 3.1 QQ

| 步骤 | 请求 | 判定 |
|---|---|---|
| 授权页（浏览器跳转） | `<qq-base>oauth2.0/authorize?response_type=code&client_id&redirect_uri&state&scope=get_user_info` | — |
| 换令牌 | POST `<qq-base>oauth2.0/token`，表单 `grant_type=authorization_code&client_id&client_secret&code&redirect_uri&fmt=json`（密钥只在请求体里） | HTTP 200；JSON 的 `access_token`，或查询串的 `access_token` + `expires_in`（ThinkSDK 的判定）；JSONP 错误 → 失败 |
| 取 openid | GET `<qq-base>oauth2.0/me?access_token&fmt=json` | JSON 或 JSONP；**`client_id` 必须是本应用**（防止换来别的应用的令牌），openid 必须是 `[A-Za-z0-9_-]{1,40}`（列宽 40） |
| 用户信息 | GET `<qq-base>user/get_user_info?access_token&oauth_consumer_key&openid&format=json` | JSON 且 `ret` 为 0 |

#### 3.2 微信

| 步骤 | 请求 | 判定 |
|---|---|---|
| 授权页（浏览器跳转） | `<weixin-open-base>connect/qrconnect?appid&redirect_uri&response_type=code&scope=snsapi_login&state#wechat_redirect` | — |
| 换令牌（含 openid） | GET `<weixin-api-base>sns/oauth2/access_token?appid&secret&code&grant_type=authorization_code` | HTTP 200、JSON、没有非 0 的 `errcode`、有 `access_token`、openid 形态同上 |
| 用户信息 | GET `<weixin-api-base>sns/userinfo?access_token&openid` | JSON、`errcode` 不存在或为 0；其中的 `openid`（如有）必须与换令牌得到的相同 |

结果：换令牌/openid 失败 →"获取第三方用户信息失败，请重试"（旧 `index/logincallback2`）；用户信息失败 →"获取腾讯QQ用户信息失败：<msg>"/"获取微信用户信息失败：<errmsg>"（旧 `LoginEvent` 原文，平台给的说明经模板转义输出）。连接失败、超时、`loopback-only` 下的外部地址都按失败返回，不抛出。日志每次一行 `oauth fetch type=<方式> result=<结果> ms=<耗时>`，出站客户端只记主机；授权码、令牌、密钥、openid、昵称都不进日志（`OAuthLoginTest` 断言）。

#### 3.3 部署参数

| 参数 | 环境变量 | 默认 |
|---|---|---|
| `maccms.integration.oauth.qq-base` | `MACCMS_INTEGRATION_OAUTH_QQ_BASE` | `https://graph.qq.com/` |
| `maccms.integration.oauth.weixin-open-base` | `MACCMS_INTEGRATION_OAUTH_WEIXIN_OPEN_BASE` | `https://open.weixin.qq.com/` |
| `maccms.integration.oauth.weixin-api-base` | `MACCMS_INTEGRATION_OAUTH_WEIXIN_API_BASE` | `https://api.weixin.qq.com/` |

必须是以 `/` 结尾、不带查询串与凭证的 http/https 地址，否则启动失败。应用标识与密钥照旧在后台"整合登录配置"页保存（B15），重启生效（D-B23-5）。在开放平台登记的回调地址：`<base-url>index.php/user/logincallback/type/qq`、`…/type/weixin`。

### 4. 拿到 openid 之后（与旧版逐条对照）

| 情形 | 旧版 | 新版（写入的 SQL 见 `MemberWriteMapper.xml` 的 B26 段） |
|---|---|---|
| 已登录、本方式已绑定这个 openid | JSON `{"code":1001,"msg":"已经绑定该账号"}` | 同 |
| 已登录、其他情况 | 清掉所有会员上的同一 openid，再写到当前会员；JSON `{"code":1,"msg":"绑定成功"}` | 同（`clearOpenid` + `setOpenid`） |
| 游客、openid 已绑定某会员（不看状态） | 不注册，直接凭 openid 登录 | 同 |
| 游客、未绑定 | `register()`：用户名 = openid 前 10 位；跳过验证码与手机/邮箱验证；未开放注册、用户名已存在、含字母数字以外的字符、不足 6 个字符、含过滤词、IP 限额任一不满足 → "同步信息注册失败，请联系管理员"；写 `group_id=2`、`reg_points`、`reg_status`、注册时间与 IP、openid 列 | 同样的检查与写入（`registerOpenid`：判重、该 openid 未绑定、IP 限额与插入合成一条条件插入）；密码见 D-B26-1 |
| 凭 openid 登录 | `user_status=1` 的会员；找不到 →"获取用户信息失败"；轮换 `user_random`、记登录时间/IP/次数、上次登录、到期降级；写 6 个 cookie；302 到会员中心 | 同（`selectOpenidLoginCandidate` + `recordOpenidLogin`），302 到 `user/index` |

旧版 `register()` **不写昵称**（控制器给的 `user_nick_name` 不在插入字段里，黄金里 `user_nick_name` 为空）；路线图 B26 行写的"昵称取平台昵称"与旧代码不符，新版按旧代码（D-B26-6，待确认）。

微信 openid 常以 `o` 开头并含 `-`、`_`：前 10 位含这些字符时旧版注册失败（字母数字检查），新版照旧（黄金 `weixin-guest-underscore`）。是否要放宽见第 9 节。

### 5. 页面与入口

- 登录页、`ajax_login` 片段：已打开的方式显示为登录表单下方 `member-links` 列表里的"QQ登录""微信登录"链接（`user/oauth/type/<方式>.html`），只用已有样式类；都未打开或本批未装配时与之前完全相同（路线图 E10 行"未配置时登录页不显示第三方登录"）。
- 回调结果：登录成功 302 到会员中心；改绑输出 JSON（旧版如此）；失败为提示页（ajax 时为 ThinkPHP `error()` 的 JSON），不给"返回"链接（旧版是 `javascript:history.back(-1)`，同 B09 其他提示页）。
- 装配：可写数据源与 `OAuthLogin` 都在时才装配 `MemberOAuthService`；否则 `oauth`、`logincallback` 与 B09 时一样 404，登录页没有入口。
- **需要前端配合**：`site/member/loginForm.jte` 是 C/K 共用的模板，本批只在已有的 `member-links` 列表末尾加了两个条件 `<li>`，没有新样式。入口的图标、位置、是否在会员中心显示"已绑定 QQ/微信"与"去绑定"入口，请前端在共享模板上定（旧版模板缺失，没有可照搬的样子）。

### 6. 测试与验证

| 内容 | 测试 / 证据 |
|---|---|
| PHP 黄金 | `backend/src/test/php/oauth_golden.php` + `oauth_golden_child.php` + `oauth_golden_thinkoauth.php`（缺失的 ThinkOauth 的替身，不发网络请求，按用例计划给出令牌、openid、用户信息）：一次性库 `maccms_it_*`，php-cgi 子进程执行**真实的旧 `logincallback`**（会员用 cookie 经 `checkLogin` 校验），记录返回与 `mac_user` 每行。22 个用例：QQ/微信新用户、已绑定、VIP 过期降级、微信 openid 含 `_`、用户名被占、openid 不足 6 位、未开放注册（未绑定/已绑定）、会员功能关闭、IP 限额、过滤词、注册状态 0、停用会员、改绑抢占他人、重复绑定、微信绑定、换绑、两种用户信息失败、换令牌失败、缺 `code`。PHP 7.3 与 8.0 结果相同（只有生成时写入的 IP 限额种子时间不同）。输出 `backend/src/test/resources/integration/b26/php73-golden.json`、`php80-golden.json` |
| 新版复放 | `OAuthGoldenMySqlIT`（`-Pmysql-it`）：同样的种子与配置，**新版走完整流程**——`start` 取得 `state` → `LoopbackMockServer` 扮演 QQ/微信、真实的 `integration.oauth` 经出站客户端换令牌/openid/用户信息 → `callback`。**两版共 44 个用例全部一致**：返回类别、返回码与提示原文、登录时写出的 cookie 名称与顺序、`mac_user` 每行每列（时间按相对"现在"比较）；新签发的 `user_check` 等于 `md5(user_random-user_name-user_id-)`。有意差异 D-B26-1 另行断言：旧版新账号密码在 `md5(now±5)` 之中，新版是 32 位十六进制且不在其中 |
| 安全（IT） | 同一 IT：伪造 `state`、伪造后再用真值、无会话、别的会话的 `state` → 拒绝，**模拟服务没有收到任何请求、库不变**；正常一次后重放 → 拒绝、无新请求、库不变；自动注册账号用 `time()` 前后 ±5 秒的值作密码登录都是 1003；`user/login` 带 `openid`+`col` → 1001 且没有 cookie（D2 ②复验）；`user/reg` 带 `user_openid_qq` 不写 openid；同一新 openid 的 8 个并发回调只注册 1 个账号、8 次都登录成功 |
| 回滚演练 | 同一 IT：本批每条写语句各执行一次（自动注册、同 openid 再注册被拒、凭 openid 登录、绑定、清除）后只用 DML 恢复到 S0，逐行 diff 为空、表结构指纹不变；删库并确认不存在 |
| HTTP 流程 | `SiteOAuthMvcTest`（9 个，真实 `MemberOAuthService` 与站点会话，协议与写库为替身）：登录页按开关显示入口（含 `ajax_login`）；发起授权写出会话 cookie、每次新的 64 位 `state`、未打开/不支持/缺 type 为"参数错误"、Host 头与 `redirect_uri` 参数不影响回调地址；正常游客流程 302 到会员中心并写登录 cookie，**请求里的 `openid`、`col`、`redirect`、`url` 被忽略**、交给写库的是换取的 openid；重放、伪造（且伪造后真值也失效）、缺 state、无会话、别的会话、方式不符、超时 → 拒绝且没有换取；缺 code →"参数错误"并作废 state；三种协议失败与注册失败的提示原文（平台消息经转义）；已登录改绑输出旧版 JSON；发起与回调之间登录身份变化 → 拒绝 |
| 协议 | `OAuthLoginTest`（10 个，`LoopbackMockServer`）："已打开"的判定；两种授权页地址逐项；QQ 换令牌请求体逐项（密钥只在请求体）、JSON 与查询串两种令牌响应、JSONP/JSON 两种 openid 响应、用户信息请求逐项；QQ 令牌错误、缺 `expires_in`、HTTP 500、非 JSON、**令牌属于别的应用**、openid 超长/含非法字符/为空 → 失败；用户信息 `ret≠0`、非 JSON、缺 `ret` → 失败并带平台说明；微信请求逐项、`errcode`、用户信息 openid 不符 → 失败；连接被拒、`loopback-only` 下默认外部地址、超时 → 按失败返回不抛出；根地址校验；令牌、授权码、密钥、openid 不进日志 |
| 既有护栏 | `OutboundPolicyTest.registryAtB26`（登记表）；`OutboundNetworkGuardTest`、`ModuleBoundaryTest`、`WriteGuardTest`（写 Mapper 不用 `<sql>/<include>`，列名用 `<choose>` 在两个固定值之间选）、`SqlEntryGuardTest`、`SiteTemplatePolicyTest`、`RepositorySecretFieldsTest` 覆盖新代码；`SiteUserMvcTest` 未改动全部通过 |

命令（`backend/`）：`./mvnw verify`；`MACCMS_IT_ADMIN_URL=… MACCMS_IT_ADMIN_USERNAME=… MACCMS_IT_ADMIN_PASSWORD=… ./mvnw verify -Pmysql-it`（只建一次性库 `maccms_it_*`）。重新生成黄金见 `oauth_golden.php` 文件头（需按 AGENTS.md 恢复 `vendor/`）。

### 7. 与旧版的差异

| 编号 | 旧版 | 新版 | 理由 |
|---|---|---|---|
| D-B26-1 | 自动注册的密码是 `md5(time())`，知道注册时刻（约等于 `user_reg_time`）就能用用户名 + 密码登录 | `md5(256 位随机数的十六进制)`，不可猜测 | 路线图第 4.3 节 ③ |
| D-B26-2 | `state` 的有无与校验取决于缺失的 ThinkOauth【待核实】 | 一次性、10 分钟、绑定会话与发起时的登录身份，回调先校验再出站；失败提示"登录请求已失效，请重新登录"（新提示） | 第 4.3 节 ③；防重放、防登录 CSRF/强制绑定 |
| D-B26-3 | 回调地址 = `http(s)://` + 请求的 `Host` + `/index.php/user/logincallback/type/` | `maccms.site.base-url` + `index.php/user/logincallback/type/`（base-url 带子目录时含子目录） | 第 4.3 节 ③"取部署配置中的固定值" |
| D-B26-4 | `oauth` 不看 `connect.<方式>.status`（ThinkOauth 的行为【待核实】）；不支持的方式抛异常（500） | 未打开或不支持 →"参数错误"，不跳转；回调同样要求已打开 | 后台关掉某方式后入口一并关闭 |
| D-B26-5 | 用户信息不是 JSON 或请求失败时，`null == 0` 成立，按成功继续；openid 为空时 `infoData([$col => ''])` 会命中任意未绑定会员，随后 `login` 按用户名密码分支返回"请填写必填项" | 用户信息必须是 JSON 且 `ret`/`errcode` 为 0，否则按失败；openid 必须是 1～40 个 `[A-Za-z0-9_-]`；QQ 另校验 `client_id`，微信另校验用户信息里的 openid | 不在不完整的数据上登录或注册 |
| D-B26-6 | 自动注册不写昵称（`register()` 不取 `user_nick_name`） | 同旧版，不写 | 路线图 B26 行写了"昵称取平台昵称"，与旧代码不符；按旧代码，**待队长确认**（第 9 节） |
| D-B26-7 | 凭 openid 登录的更新条件是 `col=openid AND user_status=1`，同一 openid 有多个会员时全部改成同一个 `user_random` | 更新查到的那一个会员（按编号取第一个） | openid 唯一时相同 |
| D-B26-8 | 先查后插：同一新 openid 的并发回调可能注册两个账号（或其中一个提示注册失败） | 条件插入要求该 openid 未绑定；插入失败后再查一次 openid，已被并发的回调注册时直接登录 | 并发测试：8 个回调 1 个账号 |
| D-B26-9 | 改绑不检查更新结果，总是"绑定成功" | 当前会员行不存在（并发删除）时返回 1002"绑定失败"（新提示） | 极少出现 |
| D-B26-10 | 登录页模板缺失，入口不详 | 登录页与 `ajax_login` 显示已打开方式的链接（第 5 节） | 路线图 E10 行 |
| D-B26-11 | ThinkOauth 的具体请求、超时、证书校验不详 | 按公开文档（第 3 节）；连接 15 秒、读取 30 秒；校验 TLS 证书；QQ 换令牌用 POST 并带 `fmt=json` | B23 统一口径；实际报文随沙箱联调核对 |
| D-B26-12 | 不记日志 | 每次换取一行（方式、结果、耗时），注册/登录/绑定各一行（只记方式） | 第 4.3 节 ① 脱敏 |

### 8. 验收要点（路线图第 5.4 节 B26 行）

| 要点 | 结果 |
|---|---|
| 伪造或重放 `state` → 拒绝 | ✅ `SiteOAuthMvcTest`、`OAuthGoldenMySqlIT`（拒绝在出站之前，库不变） |
| 直接向任何入口提交 `openid`/`col` → 不能登录（复验 D2 ②） | ✅ `user/login` 1001、`user/reg` 不写 openid、回调忽略请求中的 openid |
| 回调中的跳转地址参数被忽略 | ✅ `SiteOAuthMvcTest`（`redirect`、`url`、`redirect_uri`、Host 头） |
| 未登录不能绑定/解绑 | ✅ 绑定只在回调时已登录才发生（游客回调只注册/登录自己的账号，黄金 22 例）；旧版没有 openid 解绑入口，`user/unbind` 未登录为"未登录"（`SiteUserMvcTest`） |
| 正常授权流程（模拟服务）登录成功，写库效果与旧版一致 | ✅ 44 个黄金复放全部一致（有意差异 D-B26-1 另行断言） |
| 沙箱联调（测试应用） | ❌ 待联调：需要用户提供 QQ 互联、微信开放平台测试应用（第 4.3 节 ②），按规则转入 B21 清单 |
| 回滚演练 | ✅ |

### 9. 待确认与待联调

- **D-B26-6 昵称**：旧代码不写昵称，路线图写"昵称取平台昵称"。若要写，只需在 `registerOpenid` 插入 `user_nick_name`（按旧版 `htmlspecialchars(urldecode(trim()))` 处理），属于新行为。
- **微信 openid 含 `-`/`_` 时注册失败**（旧版行为）：微信网站应用的 openid 多数以 `o` 开头且常含这两个字符，按旧版，相当一部分微信用户首次登录会看到"同步信息注册失败"。是否改用其他用户名规则（例如去掉这些字符、冲突时加后缀）请队长/用户定；未改。
- **沙箱联调**（待联调，转 B21）：核对 QQ 令牌接口对 POST 与 `fmt=json` 的支持、`/me` 的返回形态、用户信息字段；微信 `qrconnect` 扫码流程与 `sns/userinfo`；回调地址在两个平台的登记方式（域名校验）；线上是否补过 `extend/login` 及其行为（若补过，D-B26-2/4/11 需按它复核）。
- 线上 `connect.*.status` 的实际取值未知（D9-b），决定上线后入口是否出现。

### 10. 风险

- **账号接管面**：凭 openid 登录只发生在回调内部、openid 只来自服务端换取、`state` 与会话绑定；剩余风险在第三方本身（应用密钥泄露可被用来伪造任意用户的令牌——QQ 的 `client_id` 校验只能防住换来别的应用的令牌）。
- 进程内会话：重启或多实例部署时，发起与回调落在不同进程会提示"登录请求已失效"（B08 会话的既有约束，多实例需会话粘滞）。
- 授权页地址带 `appid`（公开信息），不带密钥。

### 11. 回滚

回滚点：本批开工时的 main `633d0c5`（任务基线 `0a86c2e`）。本批没有改表结构；写入只有 `mac_user` 的自动注册、登录信息与 `user_openid_qq`/`user_openid_weixin`（与旧版相同的列）。回滚即在 main 上撤销本批的合并提交：撤销后 `oauth`、`logincallback` 回到 404，登录页不再显示入口；已注册的第三方账号仍可用（但只能再经第三方登录，原文密码无人知道；可由后台改密），已绑定的 openid 保留在列中，与旧版数据兼容，无需清理。新增的部署参数可留可删。只想临时关闭、不撤销代码时：后台把 `connect.qq.status`、`connect.weixin.status` 设为 0（重启生效），或设 `MACCMS_INTEGRATION_OUTBOUND_MODE=loopback-only` 并重启（换令牌全部失败）。
