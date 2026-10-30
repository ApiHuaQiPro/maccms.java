## B25：短信和邮件（MACC-48）

依据：路线图 `09-roadmap.md` 第 4.1 节 E06、E07 行，第 4.3 节（外部调用实施规则），第 5.4 节 B25 行；B23 的出站客户端、白名单与本机模拟服务（`26-b23-integration-foundation.md`）；B09 的会员账号（`18-b09-member-account.md` 第 3.6 节"未配置"口径）；B15 的短信/邮件配置页（`24-b15-admin-system.md`）。

### 1. 范围

| 编号 | 内容 | 旧版 | 新版 |
|---|---|---|---|
| ① | 短信发送 E06（阿里云、腾讯云） | `mac_send_sms`（`application/common.php:379-403`）→ `common/extend/sms/Aliyun.php`、`Qcloud.php` | `integration.sms.*`（接口 `integration.application.SmsSender`） |
| ② | 邮件发送 E07（SMTP） | `mac_send_mail`（`application/common.php:406-431`）→ `common/extend/email/Phpmailer.php`（依赖的 PHPMailer 缺失） | `integration.mail.*`（接口 `integration.application.MailSender`） |
| ③ | 发送验证码 `reg_msg`、`findpass_msg`、`bindmsg`，写 `mac_msg` | `common/model/User.php:634-712`（`send_msg`） | `member.application.MemberVerification#send`、`MemberAccountService#sendMessage` |
| ④ | 校验：注册时的手机/邮箱验证、手机/邮箱找回与重置、绑定与解绑 | `User.php:189-228`、`:602-632`（`check_msg`）、`:714-764`、`:785-846` | `MemberAccountService#register`、`findpassReset`、`bind`、`unbind` |
| ⑤ | 后台"测试发信" `system/test_email` | `admin/controller/System.php:12-40` | `admin.application.MailTestService`、`AdminSystemActions` |
| ⑥ | 前台 `user/bind`、`user/unbind` 页面（B09 时为 404） | `index/controller/User.php:287-319`（模板缺失） | `pages/userBind.jte`、`pages/userUnbind.jte` |
| ⑦ | 白名单登记 `integration.sms`、`integration.mail` | — | `IntegrationPackages` |

零 DDL；不改旧 PHP；不改后台页面（邮件配置页的"发送测试邮件"按钮与脚本是 B15 已转换的旧页面，本批只实现它调用的动作）；不改共享样式（`static/site/` 未动，两个新前台页面只用已有的会员页样式类）。

### 2. 白名单与出站（⑦）

- `IntegrationPackages.REGISTERED` 新增 `integration.sms → B25`、`integration.mail → B25`；`RAW_API` 新增 `integration.mail → B25`（SMTP 不走 HTTP，要直接用套接字）。短信只经出站客户端发请求；`integration.mail` 引用 `integration.http` 的只有出站模式与超时参数。其他模块只经 `integration.application` 的 `SmsSender`、`MailSender`、`SendResult`、`LegacyMailTemplate` 使用。
- **SMTP 的目标检查**（B23 第 2 节要求 RAW_API 子包自行说明）：与 `OutboundPolicy` 同一套规则——主机必须非空且只含主机名字符（`[A-Za-z0-9.-]` 或 `[IPv6]`，因此不可能带用户名密码、换行或路径）；端口 1～65535；`maccms.integration.outbound.mode=loopback-only` 时主机只能是回环字面地址（`OutboundPolicy.isLoopbackLiteral`）。不合规即失败，不建立连接。连接超时、读超时用 `maccms.integration.outbound.connect-timeout`/`read-timeout`（默认 15 秒、30 秒）。测试期护栏（`TestOutboundGuard`）同样拦截 SMTP 套接字。
- 紧急停止出站（26 文档第 12 节）：`MACCMS_INTEGRATION_OUTBOUND_MODE=loopback-only` 并重启后，短信按通道失败（101，提示"验证码发送失败："）、邮件按连接失败返回，都不报错（`SmsSenderTest.failuresBeforeConnectingAreReportedNotThrown`、`SmtpMailerTest.targetAndAddressChecksHappenBeforeConnecting`）。

### 3. 短信（①）

#### 3.1 前置检查（`LegacySmsSender`，旧 `mac_send_sms`）

顺序与返回码照旧：`sms.type` 为空 → 9005"未配置短信发送服务"；号码不符合 `/^1[345789][0-9]{9}$/`（PCRE 的 `$` 也匹配末尾换行之前）→ 999"手机号格式不正确"；验证码为空 → 998"标题不能为空"；模板标识为空 → 997"模板编号不能为空"；`ucfirst(sms.type)` 不是 `Aliyun`/`Qcloud` → 991"未找到该短信发送方式"（旧版按类名自动加载 `extend/sms/<类>.php`，Linux 下区分大小写，`ALIYUN` 找不到）。

#### 3.2 阿里云（`AliyunSms`）

| 项 | 内容（与 PHP 黄金逐项相同） |
|---|---|
| 地址 | POST `<接口>`，默认 `http://dysmsapi.aliyuncs.com/`（旧版 `$security=false`） |
| 头 | `Content-Type: application/x-www-form-urlencoded`；旧代码的 `x-sdk-client` 头写成关联数组，curl 收到的是不带冒号的 `php/2.0.0`，没有发出——新版也不发 |
| 参数 | `AccessKeyId`（`sms.aliyun.appid`）、`Action=SendSms`、`Format=JSON`、`PhoneNumbers`、`RegionId=cn-hangzhou`、`SignName`（`sms.sign`）、`SignatureMethod=HMAC-SHA1`、`SignatureNonce`、`SignatureVersion=1.0`、`TemplateCode`（`sms.tpl_code_<reg/bind/findpass>`）、`TemplateParam={"code":"<验证码>"}`、`Timestamp`（UTC `Y-m-d\TH:i:s\Z`）、`Version=2017-05-25`；按键名字节序排序 |
| 签名 | `base64(HMAC-SHA1(appkey + "&", "POST&%2F&" + encode(排序后的查询串)))`，`encode` = `urlencode` 后 `+`→`%20`、`*`→`%2A`、`%7E`→`~`；请求体 `Signature=<签名>&<排序后的参数>` |
| 结果 | `Code == 'OK'` 或 `result == 1`（PHP 7 松散比较）→ 1；否则 101 + `Message`（请求失败、超时、不是 JSON 时为空串，与旧版 curl 失败后 `json_decode(false)` 相同） |

#### 3.3 腾讯云（`QcloudSms`）

| 项 | 内容 |
|---|---|
| 地址 | POST `<接口>?sdkappid=<sms.qcloud.appid>&random=<100000～999999>`，默认 `https://yun.tim.qq.com/v5/tlssmssvr/sendsms`；拼接方式照旧，不编码 |
| 头 | `Content-Type: application/x-www-form-urlencoded`（旧 curl 发 JSON 请求体时的默认值，照旧） |
| 请求体 | 与旧 `json_encode($data)` 逐字节相同：`{"tel":{"nationcode":"86","mobile":…},"sig":…,"tpl_id":"…","params":["<验证码>"],"sign":"\u…","time":<整数>,"extend":"","ext":""}`，`sig = sha256("appkey=…&random=…&time=…&mobile=…")` |
| 结果 | **D-B25-3**：HTTP 200 且 `result` 为数字 0 → 1；HTTP 不是 200 → 101 `"<状态码> "`；其余 → 101 + `errmsg`（请求失败为空串）。旧版判定写成 `$rsp['ErrorCode'] !== ''`，响应里没有这个键，**任何结果都算成功**并记下验证码（黄金：1014 错误、HTTP 500、断开、拒绝连接都返回 1） |

#### 3.4 部署参数

| 参数 | 环境变量 | 默认 |
|---|---|---|
| `maccms.integration.sms.aliyun-endpoint` | `MACCMS_INTEGRATION_SMS_ALIYUN_ENDPOINT` | `http://dysmsapi.aliyuncs.com/` |
| `maccms.integration.sms.qcloud-endpoint` | `MACCMS_INTEGRATION_SMS_QCLOUD_ENDPOINT` | `https://yun.tim.qq.com/v5/tlssmssvr/sendsms` |

必须是不带查询串、片段与凭证的 http/https 地址，否则启动失败。账号、签名、模板编号、密钥照旧取自旧配置 `sms.*`（后台"短信配置"页保存，B15），经 `ThirdPartyConfig.secret` 取出，重启生效（D-B23-5）。

### 4. 邮件（②）

#### 4.1 前置检查（`LegacyMailSender`，旧 `mac_send_mail`）

`email.type` 为空 → 9005"未配置邮箱发送服务"；地址不含 `\w+([-+.]\w+)*@\w+([-.]\w+)*\.\w+([-.]\w+)*`（不锚定）→ 999"邮箱格式不正确"；标题为空 → 998"标题不能为空"；正文为空 → 997"标题不能为空"（旧语言包 `body_not_empty` 的原文）；`ucfirst(email.type)` 不是 `Phpmailer` → 991"未找到该邮件发送方式"；之后发信，成功 1"发送成功"，失败 102"发生错误：" + 原因。

#### 4.2 SMTP（`SmtpMailer`，代替缺失的 PHPMailer）

- 参数：会员验证码用 `email.phpmailer.{host,port,secure,username,password}` 与 `email.nick`；后台测试用 `email.<提交的 type 小写>.*` 与提交的 `nick`（旧 `test_email` 的 `$conf`）。发件地址 = `username`（旧 `setFrom($config['username'], $config['nick'])`）。
- 加密：`secure=ssl` 直接 TLS；`tls` 必须 STARTTLS（服务器不支持时失败，不发凭证）；其他值（含仓库默认值 `tsl`）服务器声明 STARTTLS 时自动升级（PHPMailer 6 的 `SMTPAutoTLS`）。主机可以写成 `ssl://主机`、`tls://主机`。**TLS 校验证书与主机名**。
- 认证：总是认证（旧 `SMTPAuth = true`），按服务器声明的顺序选 CRAM-MD5、LOGIN、PLAIN；没有 AUTH 扩展时失败。
- 信件：`Date`（PRC 时区）、`To`、`From: 昵称 <用户名>`、`Subject`（非 ASCII 用 RFC 2047 B 编码，每段 ≤75 字符折行）、`Message-ID`、`MIME-Version: 1.0`、`Content-Type: text/html; charset=UTF-8`、`Content-Transfer-Encoding: base64`；一个收件人。标题与昵称去掉 CR/LF（PHPMailer `secureHeader`）；收件人与发件人必须是不含空白、控制字符与 `<>,;:"` 的 ASCII 地址，否则不连接（防止 `RCPT`/头部注入）。
- 失败提示用 PHPMailer 的英文原文：`SMTP connect() failed. https://github.com/PHPMailer/PHPMailer/wiki/Troubleshooting`、`SMTP Error: Could not authenticate.`、`You must provide at least one recipient email address.`、`SMTP Error: data not accepted.` 等（不含地址）。
- 日志：每次一行 `mail.smtp <主机:端口> sent|failed <耗时>ms`，不记地址、内容、口令。

#### 4.3 模板（`LegacyMailTemplate`）

旧版把 `email.tpl.*` 交给 ThinkPHP `View::display()` 编译执行。新版只替换变量输出标签：`{$code}`（验证码）、`{$time}`（`email.time` 原文）、`{$maccms.<键>}`（`site` 配置段，旧 `label_maccms`），其他 `{$名}`/`{$名.键}` 输出空串（旧版未赋值时同样为空）；函数调用 `{:…}`、过滤器 `|`、标签等**原样保留、不执行**（D-B25-7）。正文再 `htmlspecialchars_decode`（默认 `ENT_COMPAT`：还原 `&amp; &quot; &lt; &gt;`），标题不解码，与旧版相同。仓库默认模板的结果与旧版逐字相同（黄金核对）。

### 5. 会员验证码（③④）

#### 5.1 发送（`reg_msg` 类型 3、`findpass_msg` 类型 2、`bindmsg` 类型 1）

1. `to` 按 `htmlspecialchars(urldecode(trim()))` 处理；`ac` 不是 `email`/`phone` 或 `to` 为空 → 9001"参数错误"；`ac=email` 且类型 1、3 → 邮箱黑白名单（1001/1002"邮箱域名不允许"，找回不检查）。
2. **D-B25-5**：`to` 超过 30 个字符（`mac_msg.msg_to varchar(30)`）→ 9009"验证码发送失败：邮箱格式不正确"/"手机号格式不正确"，不发送。
3. 频率限制（照旧，不加强）：同一会员编号（游客为 0）、同类型、同地址在时间窗内发过 → 9002"请不要频繁发送"。时间窗：短信 5 分钟；邮箱 `intval(email.time) > 0` 时为该分钟数，否则 5 分钟。
4. 验证码 6 位数字（可 0 开头，`SecureRandom`）。邮件：标题/正文按 `email.tpl.user_<reg|bind|findpass>_title/_body` 渲染（第 4.3 节）；短信：记录用的文字 = `sms.content` 替换 `[用户]`（游客为"游客"）、`[类型]`（绑定/找回密码/注册）、`[时长]`（固定 5）、`[验证码]`（旧版这段文字不发给通道，通道只用模板编号）。
5. 发送成功 → `INSERT mac_msg (user_id, msg_type, msg_status=0, msg_to, msg_code, msg_content, msg_time)`（内容超过 255 字符截断、4 字节字符换成 `?`，与非严格模式下 MySQL 的结果相同）→ 1"验证码发送成功，请前往查看"；记录失败只记日志，照旧提示成功。发送失败 → 9009"验证码发送失败：" + 原因，不记录。

#### 5.2 校验（旧 `check_msg`）

参数检查同旧版（`code` 再处理一次；找回时处理两次，照旧）：`ac`、`to`、`code` 不全 → 9001；`ac=email` 且类型 1、3 → 黑白名单；之后查 `mac_msg`：同一会员编号、时间窗内、同类型、同验证码，**新版另要求 `msg_to` 等于本次使用的地址**（D-B25-4）。找不到 → 9002"验证信息错误，请重试"。验证码在时间窗内可以重复使用、不限猜测次数（照旧）。

#### 5.3 各入口

| 入口 | 行为 |
|---|---|
| `reg`（`user.reg_phone_sms=1` 或 `reg_email_sms=1`，手机优先） | 在 IP 限额之后校验类型 3 验证码（地址 = `to`）；号码/地址已被其他会员使用 → 1011"手机号已被使用，请更换"/1012"邮箱已被使用，请更换"；插入时写 `user_phone` 或 `user_email`，用户名、号码/地址判重与插入合成一条条件插入（并发时同一号码只注册一个）。`reg_phone_sms=1` 而 `ac=email` 时照旧把地址写进 `user_phone` |
| `findpass_reset` | 地址取 `user_email`，为空时取 `to`；密码长度 2002、两次不一致 2003；验证码（类型 2，地址 = 上述地址）；邮箱格式 2005、找不到会员 2006；手机格式（`/^1{1}\d{10}$/`）2007、找不到会员 2008；同号码/地址的会员全部改密（`md5(trim(原值))`），**同时轮换 `user_random`（D2 ⑥）**，旧登录 cookie 失效；1"密码重置成功" |
| `bind` POST（需登录） | 验证码（类型 1，地址 = `to`）；清掉所有会员上的同一号码/地址，再写到当前会员；1"绑定成功" |
| `unbind` POST（需登录） | `ac` 不是 `email`/`phone` → 2001"参数错误"；清空当前会员的对应列；1"解除绑定成功" |
| `bind`/`unbind` GET（需登录） | 新页面（第 7 节） |

写入：`member / mac_msg / INSERT` 登记为 B25；`mac_user` 的插入与更新沿用 B09 已登记的 `member / mac_user / INSERT、UPDATE`（写入方矩阵 `mac_user` 本来就列了 B25）。SQL 见 `resources/mapper-write/member/MemberWriteMapper.xml` 的 B25 段、`resources/mapper/member/MemberMapper.xml` 的 B25 段。

可写数据源关闭、或没有短信/邮件发送服务时，`MemberVerification` 不装配，各入口按 B09 的"未配置"口径返回（发送 9009"…未配置短信/邮箱发送服务"，校验 9002）。

### 6. 后台测试发信（⑤）

`system/test_email`（权限码照旧，GET/POST 都可以，参数 `type`、`nick`、`test`）：标题/正文取**当前**旧配置的 `email.tpl.test_title`、`test_body` 渲染（第 4.3 节），SMTP 参数取启动时读入的 `email.<type 小写>.*`（D-B23-5：保存邮件配置后要重启才会用新的服务器与账号），昵称用提交的 `nick`。返回 `{"code":1,"msg":"测试成功"}` 或 `{"code":1001,"msg":"测试失败：<原因>"}`（键顺序与编码同旧 `json()`）。没有发信服务的上下文仍按"由 B25 批次提供"。

### 7. 前台页面（⑥）

旧模板 `user/bind.html`、`user/unbind.html` 在仓库与备份中都缺失（D9-a），新页面按 B09 会员页的结构写，只用已有样式类（`member-page`、`member-form`、`member-links` 等），没有改 `static/site/` 与布局：

- `user/bind`（`ac` 缺省 `email`）：发送验证码区（提交 `bindmsg`）+ 验证码 + "绑定"，成功后回到会员资料页；"改为绑定手机/邮箱"切换链接。
- `user/unbind`（`ac=phone` 时为手机，其他按邮箱）：说明 + "解除绑定"。
- 表单由已有的 `site/member.js` 处理（`data-member-form`、`data-member-send`），不需要新脚本。

**需要前端配合（未做）**：会员资料页/会员中心目前没有到 `user/bind`、`user/unbind` 的入口链接，也不显示已绑定的手机/邮箱；入口放在哪里、是否显示号码掩码，请前端在共享模板上定。

### 8. 测试与验证

| 内容 | 测试 / 证据 |
|---|---|
| PHP 黄金 | `src/test/php/sms_mail_golden.php` + `sms_mail_golden_child.php` + `sms_mail_mock.py`：一次性库 `maccms_it_*`，php-cgi 子进程直接调用旧 `model('User')` 的各方法与 `System::test_email`，错误处理与真实应用相同；阿里云、腾讯云的地址在旧代码里写死，子进程设 `http_proxy`/`https_proxy` 指向本机模拟服务（腾讯云经 CONNECT 隧道，由运行时生成的一次性自签名证书终止 TLS，旧代码不校验证书），旧代码与副本都没有改动。**98 个用例，PHP 7.3 与 8.0 结果相同**（只有 PHPMailer"类不存在"的报错措辞不同）：三种发送入口 × 未配置/未知通道/阿里云/腾讯云；坏号码；会员与游客；阿里云业务错误、非 JSON、HTTP 500、断开、拒绝连接、超时；腾讯云 1014、HTTP 500、断开、拒绝连接；频率限制（100 秒内拦、301 秒放行、换地址/类型/会员放行、同一请求发两次、`email.time=10`/`0`、短信不看 `email.time`）；参数错误；邮箱黑白名单；邮件未配置/坏地址/未知方式/PHPMailer；找回（手机、邮箱、`user_email` 参数、过期、错码、发给别的地址的码、重复使用、类型不对、别的会员的码、2002/2003/2005～2008、`ac` 缺失、`ac` 与码的地址不符）；绑定（抢占他人号码/邮箱、错码、过期、别的地址的码、黑名单、`ac` 错）；解绑；注册（手机、邮箱、已占用、错码、过期、缺码、别的地址的码、`reg_phone_sms` 配 `ac=email`、`email.time=10`、黑名单、两个开关都开）；后台测试发信 4 种。输出 `src/test/resources/integration/b25/php73-golden.json`、`php80-golden.json` |
| 新版复放 | `SmsMailGoldenMySqlIT`（`-Pmysql-it`）：同样的种子与配置，`LoopbackMockServer` 扮演两个短信通道、`LoopbackSmtpServer` 扮演 SMTP，**两版共 196 个用例全部一致**：返回码与提示原文、`mac_msg` 每行（种子行逐字段；新行的会员、类型、状态、地址、内容按验证码换位后相同，验证码 6 位、时间为当前）、`mac_user` 每行（新注册的编号除外）、通道收到的每个请求（阿里云参数名与顺序、除签名/随机数/时间外的每个值、签名用假密钥复算、验证码等于记下的；腾讯云查询串、去掉 sig/time/验证码后的请求体逐字节、sig 复算）；找回成功时 `user_random` 已轮换。有意差异（第 9 节 D-B25-1、3、4）11 个用例按新版结果断言：腾讯云失败不记验证码；PHPMailer 用例经模拟 SMTP 发出，正文与记下的内容相同；发给别的地址的验证码被拒、会员行不变。改动 `[时长]` 的变异实验使该 IT 失败（已还原）。回滚演练：本批各写入语句执行一次后只用 DML 恢复到 S0，逐行 diff 为空，表结构指纹不变，删库并确认不存在 |
| 短信单元测试 | `SmsSenderTest`（9 个）：前置检查顺序与返回码；阿里云请求逐项、无 `x-sdk-client`；**PHP 黄金里旧代码发出的全部阿里云请求体用同样参数与假密钥重新签名逐字节相同（已知答案向量）**；腾讯云请求体逐字节、**黄金里的腾讯云请求体与 sig 复算相同**；结果判定（业务错误、HTTP 500、非 JSON、`result=1`、`Code=0`、数组 `Message`、超时）；连接被拒与 `loopback-only` 下默认外部地址不连接、不抛出；号码、验证码、密钥、签名不进日志；接口地址校验 |
| 邮件单元测试 | `SmtpMailerTest`（8 个，`LoopbackSmtpServer`，TLS 证书由 keytool 现生成、不入库）：AUTH LOGIN/PLAIN/CRAM-MD5 与错误口令、没有 AUTH、收件人被拒；STARTTLS 自动升级、`tls` 必须 STARTTLS、**不受信任的证书失败且不发凭证**、直接 TLS 与 `ssl://` 写法、**证书主机名不符失败**；外部主机、TEST-NET 地址、带换行或 `@` 的主机、非法端口、注入收件人都不连接；静默服务器超时；标题/昵称换行注入被去掉、长标题折行；`mac_send_mail` 的前置检查与后台测试参数；地址、口令、验证码不进日志 |
| 其他单元测试 | `MemberVerificationTest`（7 个：时间窗、发送与记录、频率限制、邮件渲染与解码、30 字符限制、记录失败照旧成功、校验带地址、内容截断）；`LegacyMailTemplateTest`（2 个）；`SiteUserMvcTest` 新增 `bindUnbindAndBindmsgRequireLoginAndPassTheMember`（未登录、登录后分派并传入登录态、`bindmsg` 不看方法、绑定/解绑页） |
| 既有护栏 | `OutboundPolicyTest.registryAtB25`（登记表）；`WriteGuardTest`、`WriteRegistryTest`、`WriteInfrastructureMySqlIT`（`member mac_msg INSERT B25`，不能改、不能删）；`SiteTemplatePolicyTest`（两个新页面按会员页的例外编译）；`OutboundNetworkGuardTest`、`ModuleBoundaryTest`、`SqlEntryGuardTest`、`RepositorySecretFieldsTest` 覆盖新代码 |

命令（仓库根目录）：`./mvnw verify`；`MACCMS_IT_ADMIN_URL=… MACCMS_IT_ADMIN_USERNAME=… MACCMS_IT_ADMIN_PASSWORD=… ./mvnw verify -Pmysql-it`（只建一次性库 `maccms_it_*`）。重新生成黄金见 `sms_mail_golden.php` 文件头。

### 9. 与旧版的差异

| 编号 | 旧版 | 新版 | 理由 |
|---|---|---|---|
| D-B25-1 | 仓库与备份都没有 PHPMailer（`extend/` 为空），`email.type=Phpmailer` 时发信与测试发信都是致命错误（HTTP 500，黄金记录） | 自带最小 SMTP 客户端发信（第 4.2 节）；失败提示用 PHPMailer 的英文原文 | E07 是保留功能（D1）；线上若补过 PHPMailer，其版本与线上行为【待核实】 |
| D-B25-2 | PHPMailer 的加密与证书行为取决于版本（6.x 默认校验证书） | `ssl` 直接 TLS，`tls` 必须 STARTTLS，其他值自动升级；一律校验证书与主机名 | D-B23-1 同一口径 |
| D-B25-3 | 腾讯云任何结果都算成功（判定写错），并记下验证码 | HTTP 200 且 `result=0` 才算成功；失败提示 `errmsg` 或状态码，不记验证码 | 旧版用户看到"发送成功"却收不到；路线图风险"第三方通道异常时的提示与旧版不一致"即指此项 |
| D-B25-4 | 校验只比会员编号、类型、验证码与时间，**不比地址**：发到自己手机的找回验证码可以重置任何手机/邮箱对应账号的密码；绑定、注册同理可以占用他人的号码 | 另要求 `msg_to` 等于本次使用的地址（找回：`user_email` 或 `to`；绑定、注册：`to`） | 账号接管漏洞（黄金 `reset-code-for-other-to` 实测改掉了他人密码）。**不在 D2 已决的六项之内，请队长确认**（第 10 节） |
| D-B25-5 | 地址超过 30 个字符：非严格模式下截断存入 `msg_to`，之后频率限制与校验都对不上；严格模式下发出后插入报错 | 不发送，提示"验证码发送失败：邮箱格式不正确"（`user_email` 也只有 30 个字符） | 发出去的验证码不可能用得上 |
| D-B25-6 | 绑定、注册写进会员资料的是未处理的 `to` 原值 | 写处理后的值（与发验证码、校验用的是同一个值） | 与 D-B25-4 一致；常规输入两者相同 |
| D-B25-7 | 邮件模板经 ThinkPHP 模板引擎编译执行（`{:函数()}` 等可以执行 PHP） | 只替换 `{$code}`、`{$time}`、`{$maccms.键}` 等变量标签，其余原样输出 | 不在服务器上执行配置里的代码；默认模板结果逐字相同 |
| D-B25-8 | 阿里云 curl 总时限 5 秒；腾讯云只设连接 60 秒、不限总时长；SMTP（PHPMailer 默认）300 秒 | 统一用出站参数：连接 15 秒、读取 30 秒 | B23 统一口径；不新增配置项 |
| D-B25-9 | 腾讯云 HTTPS 不校验证书；阿里云走 http | 腾讯云校验证书；阿里云默认地址照旧 http，可经部署参数改为 https | D-B23-1；阿里云是否改 https 随联调定（第 10 节） |
| D-B25-10 | 短信/邮件配置每个请求重新读 | 启动时读一次，后台修改后重启生效；测试发信的模板读当前配置、SMTP 参数要重启 | D-B23-5、D-B15-18 |
| D-B25-11 | 注册时先查号码是否被占用再插入，并发时可能两个会员用同一号码 | 判重与插入合成一条条件插入 | 同 B09 的并发注册方案 |
| D-B25-12 | 找回改密不换 `user_random` | 换（D2 ⑥），旧登录 cookie 失效 | 路线图 B25 验收要求 |
| D-B25-13 | 出站请求不带 User-Agent（短信）；SMTP 由 PHPMailer 组信 | 出站客户端带旧 `mac_curl` 的 User-Agent；信件格式见第 4.2 节（base64 正文） | 通道不看这些；具体线上效果随联调核对 |
| D-B25-14 | `msg_content` 超长或含 4 字节字符：严格模式下插入报错，非严格模式下截断/换成 `?` | 先截断到 255 字符、4 字节字符换成 `?` 再插入 | 记录不影响发送结果 |
| D-B25-15 | `user/bind`、`user/unbind` 用旧模板（缺失） | 新页面（第 7 节） | D9-a |
| D-B25-16 | 不记日志 | 每次发送记一行（类型、通道、返回码）；SMTP 记主机与结果；不记号码、地址、验证码、内容 | 第 4.3 节 ① 脱敏 |

### 10. 待确认与待联调

- **D-B25-4 请队长确认**：验证码与地址绑定是安全行为差异，路线图 D2 的六项里没有这一项。若要求完全照旧（保留漏洞），只需去掉 `selectValidMessage` 的 `msg_to` 条件与 `checkMessage` 的地址参数；不建议。
- **沙箱联调（待联调，转 B21）**：需要用户提供阿里云/腾讯云的测试签名与模板、测试 SMTP 邮箱（路线图第 4.3 节 ②）。要核对：两家通道的真实返回字段与错误提示；腾讯云 v5 旧接口是否仍可用（腾讯云已推出新版 API）；阿里云是否改用 `https://dysmsapi.aliyuncs.com/`（只改部署参数）；常见邮箱服务（如 QQ 邮箱 465/587）的 TLS 与认证方式能否通过；线上是否补过 PHPMailer 及其版本。
- 线上 `sms.type`、`email.type`、`user.reg_phone_sms`/`reg_email_sms` 的实际取值未知（D9-b），决定上线后哪些入口会真的发出请求。

### 11. 风险

- **验证码可被暴力猜测**：6 位数字、时间窗内可重复使用、不限尝试次数（照旧，路线图 B25 行"频率限制与旧版一致，不额外加强"）。D-B25-4 之后只能针对已发出验证码的地址猜，仍是约 100 万种组合；是否加尝试次数限制由用户按 D2 的方式决定。
- **短信/邮件可被刷**：频率限制按"会员编号（游客为 0）+ 类型 + 地址"，换地址即可连续发送（照旧）。
- SMTP 服务器不支持 STARTTLS 且 `secure` 不是 `ssl`/`tls` 时，账号口令以明文认证（与 PHPMailer 默认相同）；建议线上配置 `ssl` 或 `tls`。
- 自带的 SMTP 客户端只覆盖验证码与测试邮件需要的部分，没有对照真实 PHPMailer 的报文（PHPMailer 缺失）。

### 12. 回滚

回滚点：本批开工时的 main `7335a7d`（任务基线 `2216c80`）。本批没有改表结构；写入只有 `mac_msg` 的新增行（验证码记录，旧版同样写这张表）与 `mac_user` 的绑定/改密/注册（与旧版相同的列）。回滚即在 main 上撤销本批的合并提交：撤销后各入口回到 B09 的"未配置"口径，`user/bind`、`user/unbind`、`user/bindmsg` 回到 404，`system/test_email` 回到"由 B25 批次提供"；已写入的 `mac_msg` 行与旧版写的相同、只在时间窗内有效，无需清理。新增的部署参数 `MACCMS_INTEGRATION_SMS_ALIYUN_ENDPOINT`、`MACCMS_INTEGRATION_SMS_QCLOUD_ENDPOINT` 可留可删。只想临时停发、不撤销代码时：设 `MACCMS_INTEGRATION_OUTBOUND_MODE=loopback-only` 并重启，所有短信/邮件按通道失败返回；或在后台把短信/邮件的发送方式清空（重启生效），各入口回到"未配置"。
