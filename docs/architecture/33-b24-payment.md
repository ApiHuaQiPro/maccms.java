## B24：在线支付下单、回调与 W3 跳转（MACC-47）

依据：路线图 `09-roadmap.md` 第 4.1 节 E04、E05 行，第 4.2 节 W3 行，第 4.3 节（外部调用实施规则），第 5.4 节 B24 行；B10 的本地建单与"未配置支付"（`19-b10-member-finance.md` 第 4.5、4.6 节）；B23 的出站客户端、白名单与本机模拟服务（`26-b23-integration-foundation.md`）；B07 的写入序列（`12-write-infrastructure.md` 第 4.4 节）。

### 1. 范围

| 编号 | 内容 | 旧版 | 新版 |
|---|---|---|---|
| ① | E04 下单：支付宝、码支付、Zhapay 生成网关地址/表单，微信 NATIVE 服务端统一下单 | `index/controller/User.php:491-533`（`gopay`）→ `common/extend/pay/{Alipay,Codepay,Weixin,Zhapay}.php` 的 `submit` | `member.application.MemberPaymentService#gopay` → `integration.pay.*`（接口 `integration.application.PaymentGateways`） |
| ② | W3：浏览器跳到网关（打开 W3 分项） | `mac_redirect`、支付宝自动提交表单 | `site.web.SiteMemberFinance#gopay`；`pages/userPayForm.jte`、`pages/userPayWeixin.jte` |
| ③ | E05 回调：验签与到账 | `index/controller/Payment.php` → `<方式>::notify` → `common/model/Order.php:104-155`（`notify`） | `site.web.SitePaymentController` → `MemberPaymentService#notify` → `PaymentGateways#notify` + `MemberPaymentService#credit` |
| ④ | 白名单登记 `integration.pay`；写入登记 `member / mac_order / UPDATE` | — | `IntegrationPackages`、`WriteRegistry` |

零 DDL，不改 `mac_*` 结构，不改旧 PHP，不改 nginx。后台页面不涉及（支付配置页是 B15 已转换的旧页面，本批只读取它保存的 `pay.*`）。不新增后台手工改单入口（MACC-38 已裁定）。不改共享样式与脚本（`static/site/` 未动，两个新页面只用会员页已有的样式类；自动提交见第 6.2 节的前端配合项）。

**参考版本：PHP 7.3。** 黄金在 PHP 7.3 与 8.0 各生成一份。PHP 8.0 下旧代码有两处直接 500：支付宝调用已删除的 `each()`（`Alipay.php:83`，下单与回调都是），微信下单时 `mac_curl_post` 的 `curl_setopt(CURLINFO_CONTENT_LENGTH_UPLOAD)` 抛 ValueError（`application/common.php:585`）；另有一处结果不同（码支付/Zhapay 的 `paytype` 不是数字时，PHP 7 中整数 0 `== ''` 成立而略去通道参数，PHP 8 不成立）。所以只有 PHP 7.x 上四种方式都能用，新版以 7.3 为准（D-B24-1）；两版相同的用例两版都比对。

### 2. 下单 `user/gopay`

检查顺序与提示同旧版（黄金逐条核对）：

1. `order_code = clean`、`order_id = intval(trim)`、`payment = strtolower(clean)`；三者都为空 → "参数错误"。
2. `pay.<payment>.appid == ''`（含配置里没有这一项）→ "该支付选项未开启！"。`pay` 段里的标量项（`min`、`scale`）在 PHP 7 中取 `['appid']` 得到首字符，判断通过，后面找不到类（`PaymentGateways.Availability.NO_METHOD`）。**新增**：W3 关闭时支付宝、码支付、Zhapay 同样按"该支付选项未开启！"（第 8 节）。
3. 按 `order_id`、`order_code` 与当前会员**三者**查订单，查不到 → "获取单据失败"（只传单号、或单号与 id 不匹配都查不到，同旧版）。
4. 已支付 → "该单据已支付完成"。
5. `NO_METHOD` → 200 空正文（旧版 PHP 7.3 的结果）。
6. 下单（第 3 节），输出见第 6 节。

`gopay` 只读库，不写库。

### 3. 四种方式（`integration.pay`）

所有字符串按字节处理（`PhpPay`：ISO-8859-1 "字节串"），排序为 `ksort`（参数名都是字母，即按字节比较）。

#### 3.1 支付宝（`AlipayPay`，即时到账 `create_direct_pay_by_user`）

| 项 | 内容（与 PHP 7.3 黄金逐项相同） |
|---|---|
| 参数 | `service、payment_type=1、quantity=1、_input_charset=utf-8、partner=trim(pay.alipay.appid)、seller_email=trim(pay.alipay.account)、out_trade_no、notify_url、return_url（与 notify_url 相同）、subject="积分充值（UID：）"、total_fee=%.2f(order_price)` |
| 签名 | 去掉空串与 `sign/sign_type` → 排序 → `md5("k=v&…" + pay.alipay.appkey)`（appkey 不 trim，照旧）；再加 `sign`、`sign_type=MD5` |
| 输出 | POST 到网关的表单，字段顺序为排序后的参数 + `sign` + `sign_type` |
| 回调 | `input()`（GET → POST → 路由）去掉 `pay_type` 与旧 rewrite 留下的路径键，同样复算；一致时 `trade_status == 'TRADE_SUCCESS'` 才到账（结果 > 1 → `fail2`，否则 `success2`），其他状态 `success`；不一致 `fail`。数组参数按 `Array` 参与签名（旧版行为） |

#### 3.2 码支付（`FormGatewayPay.CODEPAY`）

参数 `id=trim(appid)、type=intval(paytype)（缺省或空/"0" 时 1）、price=order_price、pay_id=order_code、notify_url、return_url、debug=1、ac=trim(pay.codepay.ac)、param=''`；排序后跳过 `== ''` 的值（PHP 7 中 `type=0` 也跳过；`ac` 读的是配置里不存在的 `ac` 键，总是空——旧代码如此，后台保存的是 `act`），`sign = md5("k=v&…" + trim(appkey))`，网关 `<网关>?k=urlencode(v)&…&sign=…`。回调只看 `$_POST`，去掉路径键与 `paytype`，排序、跳过空值与 `sign`；`pay_no` 为 PHP 假值（缺失、空、`"0"`）或签名不一致 → `fail`；否则到账，结果 > 1 → `fail2`，否则 `success`。

#### 3.3 Zhapay（`FormGatewayPay.ZHAPAY`）

参数 `mch_uid、pay_type_id、total_fee、out_trade_no、notify_url、return_url、debug=1、mepay_type=trim(pay.zhapay.act)、return_type=1、param=''`，签名与网关地址同码支付。回调只看 `$_POST`，什么都不去掉；流水号字段是 `transaction_id`；验签用**未经 trim 的** appkey（旧版如此，黄金 `notify.zhapay.key-space*` 锁定）。

#### 3.4 微信 NATIVE（`WeixinPay`、`WeixinXml`）

- 下单参数（顺序同旧版）：`appid=trim(appid)、mch_id=trim(mchid)、nonce_str（32 位，取自 234567890A-Z，首位 0 换成 1～9）、body="积分充值（UID：）"、fee_type=CNY、out_trade_no、total_fee=order_price*100（PHP 7 浮点转字符串，precision 14，19.99 → 1999）、spbill_create_ip=客户端 IP、notify_url、trade_type=NATIVE、product_id=1`。签名：`array_filter`（去掉空串与 "0"）→ 排序 → `http_build_query` 后 `urldecode` → `&key=trim(appkey)` → MD5 → 大写。请求体是 `mac_array2xml` 的结果（数字值不包 CDATA，控制字符段换成空格）。
- 经出站客户端 POST 统一下单接口，头与旧 curl 相同（`Content-Type: application/x-www-form-urlencoded`、`Referer: <接口>`、旧版 User-Agent）。返回 `return_code`、`result_code` 都是 `SUCCESS` 时取 `code_url`；否则（含 HTTP 500、不是 XML、拒绝连接、超时）按失败。不校验返回的签名（旧版不校验）。
- 回调：解析原始正文（`mac_xml2array` 的形态：根元素名丢弃、空元素是空数组、同名元素成列表），`sign` 与复算严格相等且两个状态都是 `SUCCESS` → 到账（结果不影响回复）并回复 `SUCCESS/OK`；否则 `FAIL/签名失败`。DOCTYPE 与带属性的元素按解析失败（D-B24-5）。

#### 3.5 保留的旧版行为（不改）

- 支付宝 `subject`、微信 `body` 的 UID 为空：旧 `gopay` 把未定义的 `$this->user`（null）传给 `submit`（黄金 `assignedPayment.user_id` 为 null）。是否补上 UID 见第 11 节 D-B24-d。
- 回调不比对金额（旧 `Order::notify` 只按单号到账；黄金 `notify.alipay.amount-mismatch` 照样到账）。篡改金额而不重新签名会验签失败（`*.tampered`）。见 D-B24-b。
- 支付宝 `TRADE_FINISHED` 回复 `success` 但不到账；微信验签通过即回复成功、不看到账结果（D-B24-a）。
- 回调的 GET（含三种跳转方式支付后浏览器回到 `return_url`）不验签，显示"支付完成！"。

### 4. 部署参数

| 参数 | 环境变量 | 默认（与旧版写死的地址相同） |
|---|---|---|
| `maccms.integration.pay.alipay-gateway` | `MACCMS_INTEGRATION_PAY_ALIPAY_GATEWAY` | `https://mapi.alipay.com/gateway.do?_input_charset=utf-8` |
| `maccms.integration.pay.codepay-gateway` | `MACCMS_INTEGRATION_PAY_CODEPAY_GATEWAY` | `https://api.xiuxiu888.com/creat_order/` |
| `maccms.integration.pay.zhapay-gateway` | `MACCMS_INTEGRATION_PAY_ZHAPAY_GATEWAY` | `https://www.zhapay.com/mapay.html` |
| `maccms.integration.pay.weixin-endpoint` | `MACCMS_INTEGRATION_PAY_WEIXIN_ENDPOINT` | `https://api.mch.weixin.qq.com/pay/unifiedorder` |

必须是不带凭证与片段的 http/https 地址（支付宝之外也不能带查询串），否则启动失败，信息只含参数名。商户号、appid、密钥照旧取自旧配置 `pay.*`（后台"支付配置"页，B15），经 `ThirdPartyConfig.secret` 取出（`appkey`、`mchid` 是密钥字段），重启生效（D-B23-5）。紧急停止出站（26 文档第 12 节）后，微信下单在发出前失败、按"获取微信二维码失败"处理；三种跳转方式不受出站模式影响（服务端不发请求），由 W3 控制。

白名单：`IntegrationPackages.REGISTERED` 新增 `integration.pay → B24`。只有微信统一下单经出站客户端发请求；其余三种只生成地址。其他模块只经 `integration.application` 的 `PaymentGateways`、`PayStart`、`PayOrder`、`PayRequest`、`PayNotify`、`OrderCredit` 使用；site 只经 member 的 `MemberPaymentService` 与 `view.PaymentStep`/`PaymentContext`/`PayCallback`。

### 5. 到账（E05 写库）

#### 5.1 旧版

`Order::notify`：单号或方式为空 → 1001；按单号 `find()` 订单，不存在 → 1002；`order_status == 1` → 1"订单已支付完毕"；会员不存在 → 1002（订单不改）；`UPDATE mac_order SET order_status=1, order_pay_time, order_pay_type WHERE order_code=?`；`user_points` 加 `order_points`；写 `mac_plog`（类型 1、积分 `order_points`）；返回 1。先查后改、三条语句之间没有任何保护：并发的重复回调都能通过状态检查，重复加积分。

#### 5.2 新版（`MemberPaymentService#credit`）

前置检查与返回码同旧版，然后按 B07 `WriteSequence("member.pay_notify")`：

| 步骤 | 语句 | 补偿 |
|---|---|---|
| 认领 CLAIM | `UPDATE mac_order SET order_status=1, order_pay_time=?, order_pay_type=? WHERE order_id=? AND order_status=0` | `… SET order_status=0, order_pay_time=<原值>, order_pay_type=<原值> WHERE order_id=? AND order_status=1 AND order_pay_time=<本次> AND order_pay_type=<本次>` |
| 转移 TRANSFER | `UPDATE mac_user SET user_points = user_points + ? WHERE user_id=?` | `… user_points - ? WHERE user_id=? AND user_points >= ?` |
| 记录 RECORD | `INSERT INTO mac_plog (user_id, plog_type=1, plog_points, plog_time, plog_remarks='')` | — |

- 认领匹配 0 行（另一个回调已到账）→ 什么都不改，返回 1（同"已支付完毕"），回复与旧版的重复通知相同。
- 后续步骤匹配 0 行或被服务端拒绝 → 按相反顺序补偿，订单恢复为未支付，返回失败（支付宝/码支付/Zhapay 回复 `fail2`，平台会重发）。结果不确定（连接中断、超时）→ 不补偿，认领保留（重发的回调在认领一步匹配 0 行，不会再加积分），日志"写入需人工对账"，返回失败。
- 订单按 `order_id` 单行认领（旧版按单号更新所有同号订单；B10 起新建的订单单号不重复）。
- `order_points > 65535`（`mac_plog.plog_points` 是 `smallint unsigned`）：旧版写流水时报"超出范围"→ 500，订单与积分已改；新版到账、不写流水、回复成功，记一条日志（D-B24-4）。写库效果与旧版相同（黄金 `notify.alipay.plog-overflow`）。

#### 5.3 写入登记

`WriteRegistry` 新增 `member / mac_order / UPDATE`（B24，写入方矩阵已含 B24）；加积分、流水沿用 B09 的 `mac_user UPDATE` 与 B10 的 `mac_plog INSERT`。

#### 5.4 对账

一次性库上（`PaymentGoldenMySqlIT.assertReconciled`）：每个会员 `user_points = 种子积分 + Σ 本次到账订单的 order_points`；类型 1 的流水积分之和 = 到账订单中 `order_points ≤ 65535` 的部分；每张到账订单只有一条流水；没有其他类型的流水。线上对账可用同样的三条 SQL（按时间窗口限定）。

### 6. 页面（W3 与微信）

#### 6.1 码支付、Zhapay

302 到网关地址，`Cache-Control: no-store`。旧版输出 `<script>location.href="…";</script>`（D-B24-6）。

#### 6.2 支付宝

`pages/userPayForm.jte`：表单 `id/name=alipaysubmit`，`method=post`，action 为网关地址，隐藏字段同旧版，按钮"正在提交"。内容安全策略在会员页策略上只为网关的源放行 `form-action`（`SiteHtmlRenderer.memberGatewayForm`）。旧版在表单后用内联脚本自动提交；站点模板不允许脚本（`SiteTemplatePolicyTest`），本批不改共享的 `member.js`，表单带 `data-pay-autosubmit`——**前端配合项**：在 `static/site/member.js` 里对 `form[data-pay-autosubmit]` 自动 `submit()`（几行），之前用户需点一次按钮（D-B24-6）。

#### 6.3 微信

`pages/userPayWeixin.jte`（旧模板 `user/payment_weixin` 缺失，D9-a）：订单信息、本站二维码 `user/qrcode?data=<code_url>`（B10 的二维码接口，`weixin` 开头才生成）、"支付完成后查看订单"；统一下单失败时显示"获取微信二维码失败"，不带微信的 `return_msg`。

#### 6.4 外部地址

两张新页面自身的资源都是本站的；支付宝页唯一的外部主机是网关（W3），微信页没有外部主机（`SitePaymentMvcTest` 断言）。

### 7. 回调入口 `payment/notify`

`SitePaymentController`：`/index.php/payment/notify[.html]/pay_type/<方式>`，`rewrite` 打开时另认 `/payment/…`；其他操作 404。

- 不是 POST → "支付完成！"提示页（链接会员中心）；ajax 时 `{"code":1,"msg":"支付完成！","data":"","url":"/index.php/user/index.html","wait":3}`。
- POST → `pay_type` 取自 `input()`（路由覆盖 POST 覆盖 GET）；未开启 → "该支付选项未开启！"；没有对应类 → "未找到支付选项！"；否则按第 3 节回复，`text/html; charset=utf-8`、`no-store`。正文上限 64 KiB。
- 可写数据源关闭时 POST 返回 503（不验签、不写库，平台会重发）。
- 站点关闭时与其他前台页面一样先出维护页（旧 Base 的 `check_site_status`，同旧版）。
- 回调地址由 `base-url` 生成：`<base-url>index.php/payment/notify/pay_type/<方式>`（旧版用 `HTTP_HOST`，D-B24-7）。

### 8. W3

`maccms.browser-external.w3`（`MACCMS_BROWSER_EXTERNAL_W3`）自本批起接受 `true`/`false`，**默认 `true`**（路线图第 4.2 节："W3 在 B24 合入后打开"）。关闭时支付宝、码支付、Zhapay 在 `gopay` 第 2 步按"该支付选项未开启！"，不生成网关地址、不跳转；微信 NATIVE 是本站二维码、服务端请求受出站模式控制，不属于 W3，照常可用。回调是入站，不受 W3 影响。

### 9. 测试与验证

| 测试 | 覆盖 | 结果 |
|---|---|---|
| `src/test/php/pay_golden.php`（+ `_child.php`，复用 B25 的 `sms_mail_mock.py`，新增 `--cn` 参数） | 在一次性库上以 php-cgi 运行旧 `User::gopay` 与 `Payment::notify`，78 个用例（gopay 28、回调 50 + GET 1），记录输出、提示、统一下单请求与三张表 | PHP 7.3、8.0 各一份：`integration/b24/php{73,80}-golden.json` |
| `integration.pay.PaymentGoldenTest` | 支付宝表单 4 例、码支付/Zhapay 网关地址 8 例与 PHP 7.3 逐字节相同（PHP 8.0 能运行的 7 例同样相同）；微信统一下单 8 例：去掉 nonce 与签名后的请求体逐字节相同、签名可用假密钥复算、头、各种返回下是否得到 code_url；回调 50 例回复与 7.3 相同（8.0 能运行的 27 例同样相同）、到账调用与旧版改动的订单一致 | 通过 |
| `integration.pay.PaySafetyTest` | DOCTYPE/外部实体与属性被拒绝；SimpleXML 形态与嵌套签名；PHP 7 浮点与 `is_numeric`、`urlencode`；网关参数校验；`loopback-only` 下微信下单发出前失败且日志不含密钥、商户号、单号 | 通过 |
| `member.PaymentGoldenMySqlIT`（`-Pmysql-it`） | ① 78 例逐条复放：提示/输出与 mac_order、mac_user、mac_plog 的行与 PHP 7.3 相同；② W3 关闭；③ 15 轮 × 16 个并发的重复回调（四种方式混合）只到账一次、只写一条流水；④ 对账；⑤ 回滚演练：加积分被拒绝 → 订单恢复、重发后到账一次；写流水被拒绝 → 先撤积分再恢复订单；写流水时连接中断 → 保留认领、重发不重复加积分；⑥ DML 恢复到 S0、表结构指纹不变、删库确认 | 通过 |
| `site.web.SitePaymentMvcTest` | gopay 的提示、空页面、302、支付宝表单与 CSP、微信页（本站二维码/失败提示）、页面外部主机；回调 GET 提示与 ajax JSON、POST 参数合并与原文回复、404、503 | 通过 |
| 已有护栏 | `OutboundNetworkGuardTest`、`ModuleBoundaryTest`、`WriteGuardTest`、`SiteTemplatePolicyTest` 等（登记表与例外清单同步更新） | 通过 |

黄金用法（Git Bash，仓库根目录）：

```bash
MACCMS_IT_ADMIN_URL=jdbc:mysql://127.0.0.1:3306/ MACCMS_IT_ADMIN_USERNAME=root MACCMS_IT_ADMIN_PASSWORD=<本机密码> \
PHP_CGI=D:/Tools/php/7.3/php-cgi.exe D:/Tools/php/7.3/php.exe src/test/php/pay_golden.php \
    > src/test/resources/integration/b24/php73-golden.json
```

所有假值形如 `test-only-…`（商户号 `1900000109` 是微信文档的示例号）；外部请求只到 127.0.0.1。

**沙箱联调：未做（❌）。** 支付沙箱商户号、密钥与回调地址需用户提供（路线图第 4.3 节 ②），在 B21 之前补齐。真实渠道与模拟服务的差异（例如码支付/Zhapay 网关当前是否可用、回调的重试间隔与次数，13 文档第 32 行）要到联调时才能确认。

### 10. 与旧版的差异

| 编号 | 差异 | 原因 |
|---|---|---|
| D-B24-1 | 以 PHP 7.3 为准：PHP 8.0 下旧版支付宝（下单、回调）与微信下单是 500，非 XML 的微信回调是 500；码支付/Zhapay 的非数字 `paytype` 略去通道参数 | 只有 PHP 7.x 上四种方式都能用（第 1 节） |
| D-B24-2 | 支付宝、码支付、Zhapay 的签名比较为严格相等（常量时间）；旧版是 `==`（`0e…` 形式的 MD5 会被当成相等） | 路线图 B24 行 |
| D-B24-3 | 到账改为按 `order_id` 的认领式条件更新 + 补偿；旧版先查后改、按单号更新、三步之间无保护。重复/并发回调只到账一次；加积分被拒绝时订单恢复为未支付并回复失败（旧版订单已改、500） | 路线图 E05；B07 |
| D-B24-4 | 积分超出 `plog_points` 范围时旧版 500（订单与积分已改），新版回复成功、不写流水并记日志；写库效果相同 | 避免平台反复重发同一笔已到账的通知 |
| D-B24-5 | 微信回调与统一下单返回的 XML 拒绝 DOCTYPE、实体与带属性的元素，按解析失败处理 | 防 XXE；微信报文没有这些成分 |
| D-B24-6 | 码支付/Zhapay 用 302 跳转（旧版是内联脚本）；支付宝表单不内联自动提交脚本，保留按钮，等前端在 `member.js` 加 `data-pay-autosubmit` 钩子 | 站点模板禁止内联脚本；共享脚本归前端 |
| D-B24-7 | `notify_url`/`return_url` 用 `base-url`（含子目录）；旧版用请求的 `Host` | 防 Host 注入把回调指向他处（与 B02 canonical 同口径） |
| D-B24-8 | 微信支付页由新版提供（旧模板缺失）；失败时显示"获取微信二维码失败"，不带 `return_msg` | D9-a |
| D-B24-9 | 微信统一下单经出站客户端：校验证书与主机名、跳转限制、响应上限、日志脱敏 | D-B23-1～6 |
| D-B24-10 | W3 关闭时三种跳转方式按"未开启"；默认打开 | 路线图第 4.2 节 |
| D-B24-11 | 可写数据源关闭时回调 503 | 新系统部署形态 |

### 11. 待确认（不阻塞验收，均按旧版实现）

- **D-B24-a 微信回调在写库失败时仍回复 SUCCESS**（旧版不看 `Order::notify` 的结果）。认领式到账可以安全重试，改为写库失败时回复 FAIL 能让微信重发、减少人工补单。建议改，需要队长定。
- **D-B24-b 回调不比对金额**（旧版）。签名保证金额未被篡改；若要加"通知金额 = 订单金额"的检查，需要各渠道的金额字段与单位（码支付 `money`/`price`、Zhapay `total_fee`、微信 `total_fee` 分、支付宝 `total_fee`），沙箱联调时一起核对。
- **D-B24-c 支付宝 `TRADE_FINISHED` 不到账**（旧版只认 `TRADE_SUCCESS`）。即时到账接口通常先发 `TRADE_SUCCESS`；保持旧版。
- **D-B24-d 订单标题里的 UID 为空**（旧版 `$this->user` 未定义）。补上 UID 会改变签名内容与支付页显示，不影响到账；建议保持旧版，除非用户要求。
- **前端配合**：`member.js` 的 `form[data-pay-autosubmit]` 自动提交（第 6.2 节）。

### 12. 回滚

回滚点 main `bc0df74`。本批没有 DDL；新增的写入只有回调到账（`mac_order` 改为已支付、加积分、写类型 1 流水）。回滚代码后，已到账的订单保持已支付（与旧版到账结果相同，旧版接管时不会重复到账：旧 `Order::notify` 见到 `order_status=1` 即返回）。只停用支付不回滚代码：后台把四种方式的 appid 清空（全部"未开启"），或设 `MACCMS_BROWSER_EXTERNAL_W3=false`（停三种跳转方式）、`MACCMS_INTEGRATION_OUTBOUND_MODE=loopback-only`（停微信下单）。回调入口在任何开关下都只对已开启的方式验签到账。
