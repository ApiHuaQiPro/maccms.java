## B23：integration 基础与本机模拟服务测试框架（MACC-46）

依据：路线图 `09-roadmap.md` 第 4.3 节（外部调用实施规则）与第 5.4 节 B23 行；B02 的出站护栏（`11-guards-and-legacy-config.md` 第 2 节）与旧配置读取层（同文第 4 节）；D-B15-18（配置写回后重启生效，`24-b15-admin-system.md` 第 12 节）。

### 1. 范围

本批只建机制，**不实现任何第三方接口、不登记任何业务子包、不发任何对外请求**：

| 编号 | 内容 | 位置 |
|---|---|---|
| ① | 白名单按子包登记，护栏改为"出站 API 只在客户端包，客户端只给已登记子包用" | `integration.application.IntegrationPackages`、`OutboundNetworkGuardTest` |
| ② | 统一出站客户端：超时、目标主机记录（不记凭证）、测试配置下只允许回环地址 | `integration.http.*` |
| ③ | 第三方配置读取（`maccms.php` 的 `pay/sms/email/connect/weixin/urlsend`） | `integration.application.ThirdPartyConfig`、`Secret` |
| ④ | 日志与异常脱敏 | `integration.application.LogRedactor`；客户端与异常的输出规则 |
| ⑤ | 本机模拟服务测试框架（供 B24～B29 复用） | 测试代码 `integration.testing.LoopbackMockServer` |
| ⑥ | 测试期出站护栏：`./mvnw verify` 中连接非回环地址即失败 | 测试代码 `integration.testing.TestOutboundGuard` 等 |

不涉及后台页面、模板与样式，不写库，不改 `mac_*` 结构，不改旧 PHP。

### 2. 白名单登记（①）

登记表在主代码 `IntegrationPackages`，编译期护栏与运行期检查用同一份：

- `RAW_API`：可以直接使用出站网络 API（JDK HTTP/URL/套接字、JavaMail、Spring 各类客户端、FTP、jsoup 联网等，清单见 `OutboundNetworkGuardTest.OUTBOUND_TYPES`）的子包。B23 只有 `integration.http`（出站客户端自己）。以后只有不走 HTTP 的协议（如 B25 的 SMTP）才可能另行登记，该批文档须说明它如何执行第 3.2 节的同一套目标检查。
- `REGISTERED`：可以经出站客户端发请求的子包 → 批次。**B23 交付时为空。** B24 起每批只登记本批的子包（`integration.pay`、`integration.sms`、`integration.mail`、`integration.oauth`、`integration.urlsend`、`integration.backlink`、`integration.addonmarket`），登记改动必须出现在该批的合并提交中。

`OutboundNetworkGuardTest`（静态扫描 `src/main/java`，去掉注释与字符串）：

1. 出站 API 只能出现在 `RAW_API` 的子包（含下级包）。**已登记的业务子包也不能直接用**，必须经客户端——这是相对 B02 的收紧：B02 的规则是"登记子包内可以用任何出站 API"。
2. 对 `com.maccms.backend.integration.http` 的任何引用（导入、通配导入、静态导入、全限定名）只能出现在客户端包自身或 `REGISTERED` 的子包。`integration.application` 的公开类型（`ThirdPartyConfig`、`LogRedactor` 等）不受限制，其他模块按 `ModuleBoundaryTest` 的跨模块规则只能用 `integration.application(.view)`。
3. `REGISTERED` 的键必须是 `integration.<名>`，不能是 `integration.http`、`integration.application`、`integration.testing`；批次号只能是 B24～B29。
4. pom 不得引入出站客户端依赖（B02 原样保留）。
5. `rulesOnSyntheticSources` 自检：33 种出站写法在业务模块、已登记业务子包、未登记子包、`integration` 根包、`integration.httpx` 等相近包里都被拦下，在 `integration.http` 里放行；4 种引用客户端的写法只在已登记子包放行，登记表为空时都被拦下；注释、字符串里出现不算。

运行期：客户端每次 `send` 先用 `StackWalker` 找到直接调用方（客户端包之外的第一个栈帧），其包不在 `REGISTERED`（相对包名，`integration.pay.alipay` 属于 `integration.pay`，`integration.payx` 不属于）即抛 `CALLER_NOT_REGISTERED`，不发请求。静态护栏防止新代码绕开登记；运行期检查兜住"已登记子包把客户端对象交给别处使用"这类静态扫描看不到的情形。

### 3. 出站客户端（②）

#### 3.1 行为

`integration.http.OutboundHttpClient`（JDK `HttpClient`，HTTP/1.1）：

| 项 | 新版 | 旧版 `mac_curl_get/post`（`application/common.php:576-635`） |
|---|---|---|
| 连接超时 | 15 秒（可配） | `CURLOPT_CONNECTTIMEOUT 15` |
| 整体时限 | 30 秒，从发出请求到读完响应，含跳转（可配） | `CURLOPT_TIMEOUT 30` |
| 跳转 | 跟随，最多 5 次；每一跳都做第 3.2 节检查；301/302/303 后 POST 改为不带请求体的 GET；换主机（协议、主机、端口任一不同）时去掉 `Authorization`、`Cookie`、`Proxy-Authorization` | `CURLOPT_FOLLOWLOCATION 1`，不限次数 |
| 响应体上限 | 4 MiB（可配），超出即中止 | 无 |
| TLS | 校验证书与主机名（JDK 默认） | `CURLOPT_SSL_VERIFYPEER 0` |
| User-Agent | 与旧版相同的字符串（调用方可覆盖） | 同左 |
| 返回 | 任何状态码都正常返回 `OutboundResponse`，由调用方按协议解释 | 返回响应体 |

请求用 `OutboundRequest(purpose, method, uri, headers, body)`；`purpose` 是 `[a-z0-9._-]{1,64}` 的用途标签，只用于日志（如 `pay.alipay.order`）。`OutboundRequest`、`OutboundResponse` 的 `toString` 不含路径、查询串、请求头与内容。

失败一律抛 `OutboundException`，原因 `CALLER_NOT_REGISTERED`、`TARGET_REJECTED`、`CONNECT_FAILED`、`TIMEOUT`、`RESPONSE_TOO_LARGE`、`REDIRECT_FAILED`、`IO_ERROR`、`INTERRUPTED`；信息只有原因与 `host[:port]`。

#### 3.2 目标检查（`OutboundPolicy`）

每次请求、每次跳转都检查：协议只能是 `http`/`https`；必须有主机；URL 里不得带用户名密码（凭证不进 URL）；`loopback-only` 模式下主机只能是 `localhost`、`127.0.0.0/8` 的点分十进制字面、`[::1]`——只看字面，不做 DNS 查询。不合规即 `TARGET_REJECTED`，不建立连接。

#### 3.3 部署参数

| 参数 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `maccms.integration.outbound.mode` | `MACCMS_INTEGRATION_OUTBOUND_MODE` | `open` | `open` 或 `loopback-only`；其他值启动失败 |
| `…connect-timeout` | `MACCMS_INTEGRATION_OUTBOUND_CONNECT_TIMEOUT` | `15s` | 必须大于 0 |
| `…read-timeout` | `MACCMS_INTEGRATION_OUTBOUND_READ_TIMEOUT` | `30s` | 必须大于 0 |
| `…max-response-bytes` | `MACCMS_INTEGRATION_OUTBOUND_MAX_RESPONSE_BYTES` | `4194304` | 必须大于 0 |

测试配置：`src/test/resources/config/application.properties` 设 `mode=loopback-only`（Spring Boot 的 `classpath:/config/` 叠加在 `application.yml` 之上，只在测试 classpath 上存在）。

### 4. 第三方配置读取（③）

`ThirdPartyConfig`：启动时经 B02 的 `LegacyConfigService` 读一次 `extra/maccms.php`，保留 `pay`、`sms`、`email`、`connect`、`weixin`、`urlsend` 六组；后台修改后**重启生效**（D-B15-18 口径）。未配置旧配置目录时全空；配置了但读不出（语法不合法等）时启动失败，信息只含原因与行列。

取值方式与旧版一致：旧代码 `$GLOBALS['config']['pay']['alipay']['appkey']` 按字符串使用，这里 `text(group, path…)` 按 PHP `(string)$v` 转换（`PhpValues.text`），`empty(group, path…)` 即 PHP `empty()`，键不存在得到空串。

密钥字段（键名属于 `ThirdPartyConfig.SECRET_KEYS`：`pass`、`pwd`、`password`、`cache_password`、`secret`、`secretkey`、`accesskey`、`appkey`、`key`、`token`、`cookie`、`mchid`、`encode_key`、`img_key`）只能用 `secret(group, path…)` 取得 `Secret`：`toString()` 是 `Secret[***]`/`Secret[empty]`，明文要显式 `reveal()`；用 `text()` 读密钥字段、用 `secret()` 读非密钥字段都直接拒绝。`secretValues()` 给出全部非空密钥值，客户端写日志前按原文遮盖。`ThirdPartyConfig.toString()` 只列组名。

`key` 在 `connect.qq/weixin` 里是应用编号、在 `seo.*` 里是关键词，一律按密钥处理（宁可多遮）。`pay.alipay.account`（仓库值 `111`）是收款账号而不是密钥，未列入。

### 5. 日志与异常脱敏（④）

`LogRedactor.redact(text[, knownSecrets])`，结果截断到 512 字符：

- 按字段名：查询串/表单 `name=value`（含 `user[mobile]` 形式）、JSON `"name": value`、XML `<name>value</name>`（含 CDATA），字段名（去掉非字母数字、转小写）以 `key/secret/token/sign/signature/password/passwd/pwd/pass/ticket/cookie/code/captcha/verify/vcode/mobile/phone/phonenumbers/tel/mchid/authorization/auth/credential(s)` 结尾，或含 `secret/token/password/privatekey/apikey` 时，值换成 `***`；`errcode`、`return_code`、`result_code`、`sub_code`、`TemplateCode` 等状态码字段除外。
- `Authorization`/`Cookie`/`Set-Cookie` 头、`Bearer`/`Basic` 令牌、URL 中的 `user:password@`。
- 中国大陆手机号（可带 `+86`/`86` 前缀，前后不是数字）。
- 调用方给出的已知密钥值，长度 ≥ 4 的按原文替换（长的先换）。

客户端的日志规则：成功只记 `purpose`、调用方类名、方法、`host[:port]`、状态码、字节数、耗时；非 2xx 另记前 1 KiB 响应的脱敏摘要（DEBUG 级别对 2xx 也记）；失败只记原因、主机与 `SafeExceptionLog.typeChain`（不取任何异常 message）。路径、查询串、请求头、请求体从不进日志。

### 6. 本机模拟服务测试框架（⑤）

测试代码 `com.maccms.backend.integration.testing.LoopbackMockServer`（JDK 自带的 `com.sun.net.httpserver`，只绑定 `127.0.0.1` 随机端口）：

```java
try (LoopbackMockServer mock = LoopbackMockServer.start()) {
    mock.stub("POST", "/gateway.do", MockResponse.json(200, "{\"code\":\"10000\"}"));
    mock.stub("GET", "/oauth/token", req -> MockResponse.json(200, "...按 req.query() 计算..."));
    mock.stub("GET", "/slow", MockResponse.text(200, "x").withDelay(Duration.ofSeconds(3)));
    OutboundResponse r = client.send(OutboundRequest.post("pay.test", mock.uri("/gateway.do"), "application/json", body));
    assertThat(mock.requests()).singleElement().satisfies(q -> assertThat(q.query()).contains("sign="));
}
```

按"方法 + 路径"登记固定或计算出的响应，记录每个请求（方法、路径、原始查询串、小写请求头、请求体）；未登记的路径返回 404；`redirect(status, location)` 造跳转。用法样例：`integration.demo.RegisteredOutboundClientTest`（`integration.demo` 只在测试里存在，扮演"已登记子包"，测试里以构造参数登记）。B24～B29 的第三方调用测试照此写，目标地址指向模拟服务，第三方的地址由配置注入、不写死。

### 7. 测试期出站护栏（⑥）

只在测试 JVM 生效，对全部测试自动启用（surefire 与 failsafe 相同）：

- `junit-platform.properties` 打开扩展自动注册，`META-INF/services/org.junit.jupiter.api.extension.Extension` 注册 `OutboundGuardExtension`：类加载时把默认 `ProxySelector` 换成 `TestOutboundGuard`。JDK `HttpClient`、`HttpURLConnection`、`Socket`（含 JDBC 驱动）连接前都会询问它；目标不是回环地址时登记违规并让连接失败（本机实测：这三条路径都在发包前被拦下）。`Socket` 连 IPv6 时 JDK 传入的是 `socket://%5b0:0:0:0:0:0:0:1%5d:端口` 这种解析不出主机的 URI，护栏取解码后的 authority 判断（现有 `ProvideRealHttpNoProxyTest` 监听 `::1`，靠这一点通过）。
- `META-INF/services/java.net.spi.InetAddressResolverProvider` 装入 `LoopbackOnlyResolverProvider`：`localhost`、`*.localhost`（结果须全是回环地址）与不带点的本机名（结果须全在本机网卡上）交给 JDK 内置解析，其余主机名不查询、直接失败并登记违规——所以按主机名连接的任何客户端都连不出去，也不会发出 DNS 查询。
- 扩展在每个测试之后、每个测试类之后检查违规，有则判该测试失败（`AssertionError`，列出目标）。
- 已知缺口：NIO `SocketChannel` 直接连 IP 字面地址不问代理也不解析名称，本机实测会真的发起连接。这类 API 在 `src/main` 只能出现在 `integration.http`（静态护栏），客户端本身又走 `HttpClient`，所以主代码不会走到这条路径；测试代码自己用 NIO 连外部 IP 的情形不在覆盖范围内。

`TestOutboundGuardTest` 自检：`HttpClient`、`Socket`、`HttpURLConnection` 连 TEST-NET-3 地址（203.0.113.0/24，文档地址，不可路由）都被拦下并登记；`.invalid` 主机名不解析；有违规时扩展的检查抛错；回环模拟服务按 IP 与 `localhost` 都能正常访问。

### 8. 仓库密钥字段静态检查

`RepositorySecretFieldsTest`：解析仓库 `application/extra/*.php` 全部文件，任意层级中键名属于 `SECRET_KEYS` 的字段都必须为空；另断言 27 个已知字段（`db.pass`、`pay.*.appkey`、`pay.weixin.mchid`、`connect.*.secret`、`weixin.token`、`email.phpmailer.password`、`sms.*.appkey`、`urlsend.*.token`、`upload.api.*` 的凭证等）确实被扫描到。失败信息只列字段路径，不输出值。

### 9. 测试

| 测试 | 覆盖 |
|---|---|
| `architecture.OutboundNetworkGuardTest` | ① 静态护栏（真实源码 + 合成用例） |
| `integration.unregistered.UnregisteredCallerTest` | ① 运行期调用方检查；登记表匹配规则 |
| `integration.demo.RegisteredOutboundClientTest` | ②④⑤ GET/POST、请求头与 UA、非 2xx、跳转（POST→GET、跳到外部主机被拒、次数上限、换主机去掉凭证头）、超时、响应过大、连接被拒、日志不含假密钥/签名/token/验证码/手机号与路径 |
| `integration.http.OutboundPolicyTest` | ② 目标检查两种模式、模式解析、装配默认值与非法参数启动失败、测试配置为 `loopback-only`、B23 登记表 |
| `integration.application.ThirdPartyConfigTest` | ③ 从仓库配置按旧版取值、密钥只能显式取出且不进 `toString`、未配置为空、经 `LegacyConfigService` 读取、解析失败信息不含内容 |
| `integration.application.LogRedactorTest` | ④ 各种格式与字段名规则 |
| `integration.application.RepositorySecretFieldsTest` | 仓库密钥字段为空 |
| `integration.testing.TestOutboundGuardTest` | ⑥ 测试期护栏自检 |

所有假值形如 `test-only-…`；外部地址只用 TEST-NET-3 与 `.invalid`/`.test` 保留名，拦截都发生在发包之前。

### 10. 与旧版的差异

| 编号 | 差异 | 原因 |
|---|---|---|
| D-B23-1 | 出站 HTTPS 校验证书与主机名；旧版 `CURLOPT_SSL_VERIFYPEER=0` 不校验 | 不校验即可被中间人截获密钥与签名 |
| D-B23-2 | 跳转最多 5 次、每跳检查目标、换主机时不带凭证头；旧版不限次数、不检查 | 防止跳转把请求（含凭证）带到任意主机 |
| D-B23-3 | 响应体超过 4 MiB 即中止；旧版不限 | 防止异常响应耗尽内存 |
| D-B23-4 | 只允许 `http`/`https`，URL 带用户名密码一律拒绝；旧版 curl 接受其他协议 | 第 4.3 节 ①：凭证不进 URL |
| D-B23-5 | 第三方配置启动时读一次，后台修改后重启生效；旧版每个请求重新读 | D-B15-18 |
| D-B23-6 | 出站请求写一行日志（只有主机与状态），非 2xx 记脱敏摘要；旧版不记 | 运维可见性；第 4.3 节 ① 脱敏 |

### 11. 待确认

- **D-B23-a 生产默认 `open`（已定，2026-10-28 队长采用方案 A）**：路线图只要求"测试配置下只允许回环地址"，生产默认是否放行没有明文。本批按 `open` 实施（B24 起各功能按旧版的站点配置开关决定是否发请求，与旧版一致）。队长决定保持 `open`，不加 `deny` 模式、不改 `application.yml` 默认值；紧急停止出站用 `loopback-only`（第 12 节）。
- 仓库值 `pay.alipay.account = '111'` 未列为密钥（见第 4 节）。

### 12. 回滚

本批没有写库、没有改结构、没有写文件；回滚即还原提交。部署后若需立即停止全部出站请求，B23 没有提供关闭模式（见 D-B23-a），可设 `MACCMS_INTEGRATION_OUTBOUND_MODE=loopback-only`，效果是所有对外请求以 `TARGET_REJECTED` 失败。

紧急切换的运维步骤（D-B23-a 定稿后补记）：

1. 在部署环境设 `MACCMS_INTEGRATION_OUTBOUND_MODE=loopback-only`（对应 `maccms.integration.outbound.mode`，`application.yml` 默认 `open`），重启服务。配置启动时读一次（D-B23-5），不重启不生效。取值只能是 `open` 或 `loopback-only`，写错会启动失败（`OutboundPolicy`）。
2. 生效后，所有经 `integration` 出站客户端发往非回环地址的请求都以 `TARGET_REJECTED` 失败，各功能按旧版"请求失败"的路径提示；入站接口（如 B27 公众号）和本地功能不受影响。被策略拒绝的请求在发出前就失败，出站客户端不为它单独写日志（`OutboundPolicy.check` 直接抛 `OutboundException`），是否记录由调用方决定。
3. 恢复时去掉该变量或设回 `open`，再重启。
4. B21 切换演练把"切到 `loopback-only` 后出站全部被拒、切回后恢复"列为检查项。

### 13. 风险与遗留

- 白名单设计过松的风险（路线图 B23 行）：已收紧为"业务子包不能直接用出站 API、只能经客户端"，并加运行期调用方检查。以后某批若把非 HTTP 协议子包登记进 `RAW_API`，该批要自行实现目标检查，评审时重点看。
- `open` 模式不限制目标是否为内网地址。B28 的友链回链检测（E11）会访问管理员录入的任意地址，已按路线图 B28 行定为与旧版一致、不额外加网段限制（只有友链权限的管理员能触发；保留超时、响应大小、跳转次数与协议限制）。
- 测试期护栏的 NIO 缺口见第 7 节。
- 沙箱联调：B23 不涉及第三方账号，不适用。
