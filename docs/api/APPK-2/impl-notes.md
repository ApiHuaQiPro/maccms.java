# APPK-2 后端实现说明（第 1 批：方法策略、SCH-1 传输上限、API-030 安装令牌、OUT-1 出站校验；S3-21e：OUT-1-5 出站地址固定）

- 契约：`docs/api/APPK-2/openapi.yaml` @ `bc612e933aa67abab319b90534c5150c10a51452`（版本 1.3.0，本批未改契约）
- 技术设计：`docs/design/APPK-2/tech-design.md` @ `20490951e1a9c880e975caee0c72c5e0e92120ed`（第 1 批）；S3-21e 按 v10 @ `08043003d67209d79a7c2334407def58a58d2d56`（G1 放行评论 `01a19744-8158-7713-be42-807dfc9efac9`）
- 分支：`feature/APPK-2-be-php-template-migration`（尚未合入 deploy 分支 `release/APPK-2-php-template-migration`，等代码评审）
- 范围：只做调度派活写明的四项横切能力。数据库工具状态机（DBT-*）、模板兼容层、在线采集与图片同步的业务实现（API-040/041/043 的取数与解析）不在本批。

## 变更文件

| 文件 | 改动 |
| --- | --- |
| `src/main/java/com/maccms/backend/admin/web/AdminMethodPolicy.java` | 新增。`x-method-policy` 中后台 28 个 `控制器/动作` 的方法表；未登记路径沿用基线 GET/HEAD/POST |
| `src/main/java/com/maccms/backend/admin/web/AdminController.java` | ADM-6 方法判定；映射补 PUT/DELETE/PATCH，让它们同样返回带 `Allow` 的 405；TRN-1：正文上限从 256 KiB 提到 8 MiB，参数个数上限 1000，超出返回 `lang(param_err)`，不再抛 400 |
| `src/main/java/com/maccms/backend/install/web/InstallEndpoint.java` | INS-SEC-3：第 5 步只受理 POST + `application/x-www-form-urlencoded`；INS-SEC-4：令牌通过即从会话删除（按会话加锁） |
| `src/main/java/com/maccms/backend/install/application/InstallWizard.java` | `install(...)` 的令牌参数改为 `BooleanSupplier`，在账号密码规则之后、连库之前只调用一次，判定顺序不变 |
| `src/main/java/com/maccms/backend/install/InstallApplication.java` | 安装应用会话 cookie：`HttpOnly`、`SameSite=Strict`、`Path=/` |
| `src/main/java/com/maccms/backend/integration/http/OutboundPolicy.java` | OUT-1-1～OUT-1-4（open 模式）：拒绝 `#` 片段、内部服务端口、禁止主机名、非常规 IPv4 写法；解析出全部地址，只要有一个在禁止段就拒绝（解析失败也拒绝）；IPv4 映射、兼容地址和 NAT64 按内嵌 IPv4 判断。可注入 `Resolver` 供测试 |
| `src/test/java/.../install/InstallModeTest.java` | 新增 `step5AcceptsOnlyPostFormsAndConsumesTheTokenOnce`；原环境失败用例补表单 Content-Type（INS-SEC-3 先于环境判定） |
| `src/test/java/.../install/InstallWizardMySqlIT.java` | 第一次第 5 步之后重开第 3 步取新令牌（令牌一次性） |
| `src/test/java/.../integration/http/OutboundPolicyTest.java` | 新增 `openModeRejectsInternalTargetsAfterResolution`；原 open 用例改用注入解析器，原用例里的 `203.0.113.1` 属 OUT-1-4 禁止段，换成公网地址 |
| `src/test/java/.../admin/web/AdminMvcTest.java` | 新增 `registeredPathsRejectUnlistedMethodsWith405`、`inputLimitsReturnParamErrorInsteadOf400`；权限遍历用例对只收 POST 的动作改用 POST，权限断言不变 |

## API-ID → AC- → 测试

| 契约条目 | API-ID / AC- | 测试（文件::用例） | 覆盖 |
| --- | --- | --- | --- |
| ADM-6 方法表 | API-010～018、020～025、040～043 / AC-7、AC-8、AC-9、AC-11、AC-13 | `AdminMvcTest::registeredPathsRejectUnlistedMethodsWith405` | 正常：受理的方法进入登录判定（302）。异常：GET/HEAD/POST/PUT/DELETE/PATCH 未列方法 → 405，`Allow` 正确、`no-store`、不写 cookie，跨站 Origin 也先 405。边界：未登记路径 PUT → 405，`Allow: GET, HEAD, POST`；控制器不存在先 404 |
| ADM-6 与权限 | 同上 | `AdminMvcTest::everyAuthCodeIsDeniedToAnAdminWithoutPermissions` | 只收 POST 的动作用 POST 访问，仍然“没有权限” |
| SCH-1 TRN-1（后台） | INPUT_LIMIT_EXCEEDED | `AdminMvcTest::inputLimitsReturnParamErrorInsteadOf400` | 1001 个参数、查询串与表单合计超限、正文超过 8 MiB → HTTP 200「参数错误」；先判 ADM-6；恰好 1000 个参数、正文恰好 8 MiB 不触发 |
| INS-SEC-3/4 | API-030 / AC-12 | `InstallModeTest::step5AcceptsOnlyPostFormsAndConsumesTheTokenOnce` | GET/HEAD/text-plain/JSON → 非法访问，令牌保留；账号规则不过、令牌错误 → 不消耗令牌；首次通过即作废，重放 → 非法访问；重开第 3 步换新令牌；无令牌的会话 → 非法访问；cookie 属性 |
| INS-SEC-2 / 判定顺序 | API-030 / AC-12 | `InstallModeTest::lockRejectsEveryRequestAndFailedEnvironmentBlocksStep3`、`InstallWizardGoldenTest` | 安装锁优先；环境失败；页面黄金样本不变 |
| OUT-1-1～OUT-1-4 | API-040/041/043 / AC-13 | `OutboundPolicyTest::openModeRejectsInternalTargetsAfterResolution` | 片段；端口 0/22/3306/6379/11211/27017；localhost、`*.localhost`、metadata；IPv4 全部禁止段的边界地址；IPv6 `::`、`::1`、映射、兼容、NAT64、ULA、链路本地、组播；`2130706433`、`0x7f000001`、`0177.0.0.1`、`127.1`、`1.2.3`；解析结果混入私网、解析到 169.254.169.254、解析失败；异常信息只含 `host[:port]` |
| 出站护栏回归 | — | `*Outbound*Test`、`BacklinkFetcherTest`、`FastadminAddonMarketTest`、`SmsSenderTest`、`BaiduUrlPushTest`、`PaySafetyTest`、`OAuthLoginTest`、`UnregisteredCallerTest` | 本机模拟服务（loopback-only）全部通过 |

## 实际执行的命令与结果（可复跑）

需先设 `JDK_JAVA_OPTIONS=-Djdk.net.unixdomain.tmpdir=<专用目录>`（见 MULTICA.md「已知问题」）。

```text
./mvnw -B -q test -Dtest='InstallModeTest,InstallWizardGoldenTest,InstallTemplatePolicyTest,InstallValidationTest,InstallSchemaTest,InitDataTest,DeployConfigFileTest'
  → 24 条，0 失败
./mvnw -B -q test -Dtest='OutboundPolicyTest,*Outbound*Test,BacklinkFetcherTest,FastadminAddonMarketTest,SmsSenderTest,BaiduUrlPushTest,PaySafetyTest,OAuthLoginTest,UnregisteredCallerTest'
  → EXIT 0
./mvnw -B -q test -Dtest='com.maccms.backend.admin.**.*Test'
  → 241 条，0 失败
./mvnw -B verify
  → Tests run: 3009, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS（基线 3005 条 + 本批新增 4 条）
git diff --check a57e9cb2b94d3e500bea7dfe2c579daee093d969 HEAD → 无输出
```

`InstallWizardMySqlIT` 需要 `-Pmysql-it` 和 MACCMS_IT_ADMIN_* 账号，本次没有跑（改动只是在第二次第 5 步前重新取令牌）。

## 迁移与回滚

无数据库结构变更，也没有数据迁移。回滚就是回退这三个代码提交；安装锁、会话和配置文件的格式都没有变化。

## 后端自查清单（multica-backend-impl 第 10 节）

- 契约先行：按 v1.3.0 实现，未改契约 ✅
- TDD：每项行为都有对应单测；ADM-6 与 TRN-1 走 MockMvc，安装走安装应用的 MockMvc，OUT-1 是纯单测 ✅
- 错误码：405 `ADMIN_METHOD_NOT_ALLOWED`（text/plain + Allow）；后台 `INPUT_LIMIT_EXCEEDED` 返回 `lang(param_err)`；API-030 沿用 `install/access_denied` ✅
- 审计与日志：没有新增日志；出站异常信息仍然只含 `host[:port]` ✅
- 幂等：API-030 令牌一次性，同会话并发提交只有一个能通过（按会话对象加锁）✅
- 迁移：无 ✅
- 既有测试：没有删除或跳过任何测试。修改了 4 处，都是因为契约有意改了基线行为：环境失败用例补表单 Content-Type；IT 重新取令牌；open 用例换成非禁止段地址并注入解析器；权限遍历对只收 POST 的动作用 POST ✅

## 安全自查（自查不代替审计）

- SSRF：open 模式按解析后的地址判断；非常规数字写法直接拒绝，不交给 DNS。连接时固定已校验地址（OUT-1-5）由 S3-21e 实现，见文末“S3-21e”一节。
- CSRF：安装会话 cookie 设为 SameSite=Strict，令牌一次性；后台方法表在来源校验之前生效，只会更严。
- 输入：后台不再因为正文超限而抛出 400 异常；正文上限从 256 KiB 提到 8 MiB，是契约要求（fcontent 2 MiB 的分支要能到达）。内存占用：每个请求最多读 8 MiB + 1 字节。

## 已知问题与风险

1. 【已由 S3-21e 解决：架构按 DEC-14 选 (a)，见文末】原记录：**OUT-1-5（连接时固定已校验地址、防 DNS 重绑定）未实现，需要架构决定。** `OutboundHttpClient` 用的是 JDK `java.net.http.HttpClient`，它不能按请求指定解析结果。现有三条路都超出本批的设计边界：
   - (a) 换成带 `DnsResolver` 钩子的客户端（如 Apache HttpClient 5），要新增依赖；
   - (b) JDK `InetAddressResolverProvider` SPI：它从系统类加载器加载，Spring Boot 可执行 jar 里的类加载不到，而且一改就影响整个进程；
   - (c) 把 URI 改成 IP 字面量，并自己处理 Host 头（JDK 默认禁止设置）、TLS SNI 和证书主机名校验。

   现状：每一跳先解析并校验，然后由 HttpClient 按主机名重新解析再连接，DNS 重绑定仍有一个时间窗口。
2. OUT-1-7 规定“超时可调小、不可调大”，配置层还没有加上限校验（默认值本来就是 15 秒 / 30 秒 / 4 MiB）。
3. OUT-1-8 的“只接受 2xx”和 OUT-1-9 的固定错误体（`COLLECT_*`、`IMAGES_*`）属于 API-040/041/043 的业务实现，基线还没有在线采集，本批不做。
4. TRN-1 只在后台入口实现。API-002、API-007（返回 1001 JSON）以及 API-030 的参数个数上限（返回 500）没有做；API-030 的 1 MiB 正文上限是基线已有的。
5. 单值 8192 字符、数组 600 项都是各字段自己的业务分支，跟着各 operation 的实现走。
6. ADM-3 的 SRC-2（B 类写操作在三个来源头都缺失时拒绝）不在本批派活的四项里，没有实现。
7. open 模式下 `check` 会做 DNS 解析；解析失败从“连接失败”变成了“目标被拒绝”（`TARGET_REJECTED`）。S3-21e 之后引擎建连不再解析，每跳总共只解析一次（第 1 批时是两次）。

## S3-21e：出站地址固定（OUT-1-5；DEC-14 / NET-7；C-76～C-80；V-33）

- 依据：技术设计 v10 @ `08043003d67209d79a7c2334407def58a58d2d56`；契约 v1.3.0 @ `bc612e933aa67abab319b90534c5150c10a51452`（未改）；基线 `6f78400102ed8a1e26a28b88df90a96391a7ac1e`
- 对外不变：`OutboundRequest`、`OutboundResponse`、`OutboundException.Reason`、`OutboundHttpClient` 的公开构造器、两个 `send` 重载和 `mode()`；业务调用方一行没改
- C-76～C-80 放在同一个提交里，整体 `git revert` 即可回滚。回滚后会回到 JDK 客户端，重绑定窗口也会回来，所以 OUT-1-5 就不再满足了（设计 §10）

### 变更文件

| 改动项 | 文件 | 改动 |
| --- | --- | --- |
| C-76 | `pom.xml` | 加 `org.apache.httpcomponents.client5:httpclient5`，不写 `<version>`，版本由 Spring Boot 4.1.1 BOM 管理（5.6.4） |
| C-77 | `src/main/java/.../integration/http/OutboundPolicy.java` | `check(URI)` 改为返回 `Target(logHost, host, addresses)`。`logHost` 就是原来的返回值；`host` 去掉 IPv6 方括号和末尾点并转小写；open 模式的 `addresses` 是全部解析结果，逐个过禁止判定，顺序不变；loopback-only 模式直接由回环字面量构造（`localhost` → 127.0.0.1、::1），不查 DNS。拒绝规则不变。新增包内构造器 `(Mode, Resolver, Predicate<InetAddress>)`，只给测试用。SUG-1：`check` 写明是模块内部 API，生产代码只有 `OutboundHttpClient` 在初始请求和每次跳转时调用（`git grep -n "policy.check" -- src/main` 只有这两处） |
| C-78 | `src/main/java/.../integration/http/OutboundHttpClient.java`；新增 `PinnedDnsResolver.java` | 引擎从 `java.net.http.HttpClient` 换成 HttpClient 5 classic，下文“实现要点”逐条说明。新增包内构造器，多一个 `SSLContext` 参数，只给 TLS 用例用；公开构造器用 `SSLContext.getDefault()` |
| C-79 | `src/test/java/.../architecture/OutboundNetworkGuardTest.java` | `FORBIDDEN_ARTIFACTS` 去掉 `httpclient5`，`httpclient`、`httpasyncclient`、`okhttp` 等仍然禁止。新增 `httpClient5IsBomManaged`：pom 里只能有一个 `httpclient5` 依赖，groupId 为 `org.apache.httpcomponents.client5`，且不含 `<version>`。`OUTBOUND_TYPES` 和合成用例没动（`org.apache.hc.` 仍然只允许出现在 `integration.http`） |
| C-80 | `src/test/java/.../integration/http/OutboundPolicyTest.java` | 原有断言改为读 `Target.logHost()`，用例和断言范围都没减；新增 `targetCarriesNormalizedHostAndCheckedAddresses` |
| C-80 | 新增 `src/test/java/.../integration/http/OutboundAddressPinningTest.java`、`PinnedDnsResolverTest.java` | V-33 ①～⑦，另加一条“连接不复用”用例 |
| C-80 | 新增 `src/test/resources/outbound-tls/pin.invalid.p12`、`other.invalid.p12` | keytool 生成的自签 EC 证书，SAN 分别是 `pin.invalid`、`other.invalid`，有效期 100 年。口令 `test-only-not-a-secret` 写在测试代码里，只供测试使用 |
| 测试支撑 | 新增 `src/test/java/.../integration/demo/DemoOutboundCaller.java` | 出站客户端把“本包之外的第一个栈帧”当作调用方。`integration.http` 包里的测试直接调用会被判为未登记，所以经已登记的 `integration.demo` 转发一次。只在测试代码里 |

### 实现要点（NET-7 ②～⑥）

1. **每跳一套连接**：新建 `BasicHttpClientConnectionManager`（只有一条连接），连接操作器注入 `PinnedDnsResolver(target.host(), target.addresses())`。客户端设置：`DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE)` 直连，不调用 `useSystemProperties()`、`setProxy`、`setProxySelector`；另外 `disableRedirectHandling`、`disableAutomaticRetries`、`disableCookieManagement`、`disableAuthCaching`、`disableContentCompression`、`disableDefaultUserAgent`。TLS 用 `new DefaultClientTlsStrategy(sslContext)`，构造时建一次，主机名校验保持默认的 BUILTIN（JDK 端点识别 HTTPS）。
2. **与 NET-7 ② 字面写法的差异，请评审核对**：设计写的是 `BasicHttpClientConnectionManager.create(schemePortResolver, resolver, tls, null)`，实际改用 `new BasicHttpClientConnectionManager(new DefaultHttpClientConnectionOperator(DIRECT_SOCKETS, …, resolver, tls), null)`。原因是 `create(...)` 内部默认的套接字工厂用 `new Socket()`，JDK 在连接时会询问系统 `ProxySelector`，选择器若返回 SOCKS 代理就会经代理连接。这与 NET-7“不读系统代理”和 V-33 ④冲突，所以改用 `new Socket(Proxy.NO_PROXY)`。突变验证：把这里换回 `new Socket()`，`systemProxySettingsAreIgnored` 即失败。连接管理器、解析器、TLS 策略的组成与设计相同。
3. **地址只来自解析器**：请求目标 `HttpHost` 用原 scheme、原主机名（IPv6 去掉方括号）和原端口。`Host` 头取请求 URI 的 authority，SNI 和证书主机名都用原主机名。引擎只在 `HttpHost` 本身带地址时才跳过解析器，这里从不带地址，所以每次建连都走 `PinnedDnsResolver`。`PinnedDnsResolver` 对其他名字抛 `UnknownHostException`，类里没有任何系统解析调用。
4. **超时**：连接超时 = `connectTimeout`（`ConnectionConfig`），TLS 握手超时默认与它相同。总时限语义不变：每跳先算剩余时间，≤ 0 直接 `TIMEOUT`；跳内 `RequestConfig.responseTimeout` = 剩余时间，同时在客户端持有的单个守护线程定时器上排一个任务，到点就 `cancel()` 这次请求。被定时取消、连接超时、读超时都归 `TIMEOUT`。
5. **与基线一致的行为（NET-7 ⑤）**：HTTP/1.1；请求头原样复制，没带 `User-Agent` 时补 `LEGACY_USER_AGENT`；`host`、`connection`、`content-length`、`expect`、`upgrade`（不分大小写）抛 `IllegalArgumentException`，时机和 JDK 客户端一样，在剩余时间检查之后、建连之前。有请求体时用 `ByteArrayEntity(body, null)`，`Content-Type` 随复制的请求头原样发出：线上字节和“实体带类型”的写法相同，但不会改写调用方给的值。POST/PUT 没有请求体时，引擎发 `Content-Length: 0`。不发 `Accept-Encoding`，不解压。响应体边读边计数，超过上限立即 `cancel()` 并归 `RESPONSE_TOO_LARGE`，不信任 `Content-Length`。响应头按原 `lowerCaseHeaders` 合并。**另外去掉了引擎默认补的 `Connection: keep-alive`**：基线 JDK 客户端不发这个头。已用 JDK 21 `HttpClient` 实测，GET 只发 `Host`、`User-Agent`，POST 另有 `Content-Length`、`Content-Type`。V-33 ⑥ 断言了没有这个头。
6. **异常映射（NET-7 ⑥）**：判断顺序依次是：被定时取消、`ConnectTimeoutException`、`SocketTimeoutException` → `TIMEOUT`；线程已中断 → `INTERRUPTED`（中断标志保留，发请求前也先查一次）；`HttpHostConnectException`、`ConnectException`、`NoRouteToHostException`、解析器抛出的 `UnknownHostException` → `CONNECT_FAILED`；其余 `IOException`（含 `SSLException`）→ `IO_ERROR`。日志仍然只记类型链（`outbound failed reason=… host=… error=<类型链>`）。
7. **资源关闭顺序（SUG-2）**：每跳的 `finally` 里依次：撤销定时任务 → `req.cancel()`（中止交换，连接不复用，关闭响应时不再读剩余响应体，读超时后也不会因为排空而再等一轮）→ 关闭响应 → 关闭 `CloseableHttpClient` → `connections.close(CloseMode.IMMEDIATE)`。成功、超限、超时、连接失败、TLS 失败都走这一段。受限头和剩余时间 ≤ 0 这两种情况在建任何资源之前就抛出。
8. **守护调度器生命周期（SUG-2）**：每个 `OutboundHttpClient` 持有一个 `ScheduledThreadPoolExecutor(1)`，线程名 `outbound-deadline`，是守护线程。设置 `removeOnCancelPolicy=true`，撤销的任务立即出队；`allowCoreThreadTimeOut(true)`、keepAlive 30 秒，队列空闲 30 秒后线程自行退出，有请求时再建。生产里只有一个 Spring 单例客户端，所以最多一条线程，空闲时为零。应用停止时不需要显式关闭，守护线程也不阻止 JVM 退出。测试里新建的大量客户端同理，不会累积线程。

### V-33 → 测试

| V-33 | 测试（文件::用例） | 断言 |
| --- | --- | --- |
| ① 重绑定 | `OutboundAddressPinningTest::connectsToTheCheckedAddressWithoutResolvingAgain` | `rebind.invalid` 第 1 次解析到 127.0.0.1、之后解析到 127.0.0.2（被禁止）。请求到达 127.0.0.1 上的模拟服务，`Host` = `rebind.invalid:<端口>`，解析器只调用 1 次。下一次请求重新检查，拿到 127.0.0.2 → `TARGET_REJECTED`，不发连接 |
| ② 跳转 | `::eachRedirectHopIsPinnedAndRejectedHopsAreNeverConnected` | `hop1.invalid` 302 到 `hop2.invalid`，两跳各解析 1 次，各自的 `Host` 正确。跳到解析为 127.0.0.2 的 `evil.invalid` → `TARGET_REJECTED`，127.0.0.2 上的监听收到 0 条连接 |
| 连接不复用 | `::connectionsAreNeverReusedAcrossHopsOrRequests` | 服务端保持连接，一次跳转加一次普通请求共 3 跳，服务端收到 3 条连接，解析 3 次 |
| ③ TLS | `::tlsUsesTheOriginalHostNameForSniAndCertificateCheck` | 服务端用 `pin.invalid` 证书：200，服务端在 ClientHello 中记下的 SNI 为 `pin.invalid`，`Host` 正确。换成 `other.invalid` 证书（客户端信任该证书，只是主机名不符）→ `IO_ERROR`，服务端没有收到 HTTP 请求 |
| ④ 代理 | `::systemProxySettingsAreIgnored` | `ProxySelector.setDefault` 指向本机“代理”监听（SOCKS），并设了 `http.proxyHost/Port`，请求仍直连目标。选择器调用 0 次，代理监听 0 条连接。`finally` 恢复原值 |
| ⑤ 受限头 | `::restrictedHeadersAreRejectedBeforeConnecting` | `Host`、`connection`、`Content-Length`、`EXPECT`、`Upgrade` 均抛 `IllegalArgumentException`，模拟服务没有收到请求 |
| ⑥ 请求头 | `::requestHeadersCarryNoCompressionOrCookies` | 带 `Set-Cookie` 的 302 之后，下一跳不带 `Cookie`。所有请求都没有 `Accept-Encoding`、`Cookie`、`Connection`，`User-Agent` 为旧版字符串。POST 无请求体时 `Content-Length: 0` |
| ⑦ 解析器 | `PinnedDnsResolverTest`（3 条） | 固定主机返回地址副本，大小写和末尾点变体同样能匹配，IPv6 去掉方括号后比较；其他名字（含 `localhost`、IP 字面量、空串、null，以及引擎实际调用的 `resolve(host, port)`）都抛 `UnknownHostException`，信息里不带名字。源码检索：没有 `getAllByName`、`getByName` |
| 无系统 DNS 回退 | 上面所有用例的 `@AfterEach` | `.invalid` 名字一旦走到系统解析，测试期护栏 `LoopbackOnlyResolverProvider` 就会登记违规；每条用例都断言 `TestOutboundGuard.drainViolations()` 为空 |
| 依赖护栏 | `OutboundNetworkGuardTest`（5 条，含新增的 `httpClient5IsBomManaged`） | 见 C-79 |

**突变验证**（改完后已恢复，未提交）：
- 套接字工厂换回 `new Socket()`：`OutboundAddressPinningTest` 出现 1 个错误（④）。
- 解析器换成 `SystemDefaultDnsResolver.INSTANCE`：出现 6 个错误（①②③④⑥ 和连接不复用用例）。

说明这些用例确实能拦住地址固定与代理隔离被破坏的情况。

### 实际执行的命令与结果（S3-21e）

需先设 `JDK_JAVA_OPTIONS=-Djdk.net.unixdomain.tmpdir=<专用目录>`。

```text
./mvnw -B test -Dtest='OutboundAddressPinningTest,PinnedDnsResolverTest,OutboundPolicyTest,OutboundNetworkGuardTest'
  → Tests run: 23, Failures: 0, Errors: 0（pinning 7、resolver 3、policy 8、guard 5）
./mvnw -B -q test -Dtest='OutboundPolicyTest,RegisteredOutboundClientTest,UnregisteredCallerTest,OutboundNetworkGuardTest,TestOutboundGuardTest'
  → 0 失败
./mvnw -B dependency:tree -Dincludes=org.apache.httpcomponents.*
  [INFO] com.maccms:maccms-backend:jar:0.1.0-SNAPSHOT
  [INFO] \- org.apache.httpcomponents.client5:httpclient5:jar:5.6.4:compile
  [INFO]    +- org.apache.httpcomponents.core5:httpcore5:jar:5.4.3:compile
  [INFO]    \- org.apache.httpcomponents.core5:httpcore5-h2:jar:5.4.3:compile
  （完整依赖树中也只有这三项 httpcomponents，没有 httpclient 4.x、httpasyncclient、okhttp）
git grep -n 'getAllByName\|getByName' -- src/main/java/com/maccms/backend/integration/http/PinnedDnsResolver.java → 无结果
./mvnw -B verify
  → Tests run: 3021, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS；耗时 07:38（第 1 批 3009 条 + S3-21e 新增 12 条：pinning 7、resolver 3、policy 1、guard 1）
git diff --check 6f78400102ed8a1e26a28b88df90a96391a7ac1e HEAD → 无输出
```

出站回归（V-33 列出的类）都在 `verify` 里，断言没有修改：`OutboundPolicyTest`（只改了取值方式）、`RegisteredOutboundClientTest`、`UnregisteredCallerTest`、`OutboundNetworkGuardTest`、`AddonMarketGoldenTest`、`FastadminAddonMarketTest`、`BacklinkFetcherTest`、`OAuthLoginTest`、`PaySafetyTest`、`PaymentGoldenTest`、`SmsSenderTest`、`BaiduUrlPushTest`、`SmtpMailerTest`。

**没有运行**：`OAuthGoldenMySqlIT`、`PaymentGoldenMySqlIT`、`SmsMailGoldenMySqlIT`。它们需要 `-Pmysql-it` 和 `MACCMS_IT_ADMIN_URL/_USERNAME/_PASSWORD`，本运行环境没有配置这些变量。这三项须在有 MySQL IT 环境时补跑，复跑命令：`./mvnw -B verify -Pmysql-it -Dit.test='OAuthGoldenMySqlIT,PaymentGoldenMySqlIT,SmsMailGoldenMySqlIT'`。

### S3-21e 自查（自查不代替审计）

- 契约与对外接口没有改动；只新增设计允许的依赖，不写版本；`org.apache.hc.` 只出现在 `integration.http`，由护栏测试保证 ✅
- SSRF / DNS 重绑定：检查和建连用同一组地址。引擎侧的解析器只认这一跳的主机，不回退到系统 DNS；跳转逐跳重新检查；被拒绝的跳转目标没有连接 ✅
- TLS：证书链用 JDK 默认信任库（公开构造器），主机名校验保持开启，SNI 用原主机名；测试证书只在 `src/test/resources`，不进 jar ✅
- 代理：不读系统代理，包括 `ProxySelector`、`http.proxyHost` 和 SOCKS ✅
- 资源：每跳的客户端、连接管理器和定时任务都在 `finally` 里释放；定时器是单个守护线程，空闲自行退出 ✅
- 日志：只记用途、主机、状态码、字节数、耗时和异常类型链。不记 URL、查询串、地址列表和响应片段（非 2xx 的脱敏摘要沿用基线）✅
- 已知差异：每跳都新建 TCP/TLS 连接，同一主机的批量请求会比基线多做几次握手（与 PHP 每次新建 curl 句柄相同，RISK-T23 已接受）。TLS 协议和套件取 JDK 默认 `SSLContext`

## REV-BE-1：依赖漏洞审计与修复（后端评审第 1 轮）

- 起因：第 1 轮安全审计 REV-BE-1。OWASP Dependency-Check 和 OSS Index 都没有出报告。
- 审计报告：`docs/api/APPK-2/dep-audit.md`，原始报告在 `docs/api/APPK-2/dep-audit/`。改用 OSV：CycloneDX SBOM 加 osv-scanner 2.2.4，并直接调用 OSV querybatch 交叉核对。覆盖完整运行时依赖共 83 个构件。
- 结论：`httpclient5:5.6.4`、`httpcore5:5.4.3`、`httpcore5-h2:5.4.3` 没有已知漏洞。Spring Boot 4.1.1 BOM 自带的 Jackson 3.1.5（7 项）和 Tomcat 11.0.24（4 项）本来就在基线上，有已修复的公开漏洞。本次已修复，复扫 0 项，没有需要人类接受的风险。
- 修复：`pom.xml` 加 `jackson-bom.version=3.1.7` 和 `tomcat.version=11.0.27`，取 Spring Boot 4.1.2 BOM 的同一组版本。父版本保持 4.1.1，httpcomponents 版本不变，没有新增依赖。
- 回滚：revert 本修复提交即可，会回到原版本和原漏洞状态。不涉及数据迁移。

### 变更文件

| 文件 | 改动 |
| --- | --- |
| `pom.xml` | 覆盖 `jackson-bom.version`、`tomcat.version`，附漏洞编号说明 |
| `src/test/java/.../architecture/DependencyVulnerabilityBaselineTest.java` | 新增。断言运行时 Jackson core/databind ≥ 3.1.7、Tomcat ≥ 11.0.25，另有 1 条比较函数自检 |
| `docs/api/APPK-2/dep-audit.md`、`docs/api/APPK-2/dep-audit/*` | 审计报告、修复前后的 SBOM 和 osv-scanner JSON 报告 |

### 实际执行的命令与结果（REV-BE-1）

需先设 `JDK_JAVA_OPTIONS=-Djdk.net.unixdomain.tmpdir=<专用目录>`。

```text
./mvnw -B test -Dtest=DependencyVulnerabilityBaselineTest      # 改 pom 前
  → Tests run: 4, Failures: 3（jackson-core 3.1.5、jackson-databind 3.1.5、tomcat 11.0.24 低于修复版本）
./mvnw -B test -Dtest=DependencyVulnerabilityBaselineTest      # 改 pom 后
  → Tests run: 4, Failures: 0, Errors: 0
osv-scanner scan source -L <修复前 SBOM>.cdx.json → 3 个构件 11 项漏洞，退出码 1
osv-scanner scan source -L <修复后 SBOM>.cdx.json → No issues found，退出码 0
./mvnw -B dependency:list -DincludeScope=runtime
  → jackson-core/databind 3.1.7；tomcat-embed-core/-el/-websocket 11.0.27；httpclient5 5.6.4、httpcore5/-h2 5.4.3
./mvnw -B verify
  → Tests run: 3025, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS；耗时 07:27（3021 + 新增 4）
```

完整命令和数据源时间见 `dep-audit.md`。MySQL IT 的边界不变：`OAuthGoldenMySqlIT`、`PaymentGoldenMySqlIT`、`SmsMailGoldenMySqlIT` 仍然没有运行，原因是缺 `MACCMS_IT_ADMIN_*`。Tomcat 和 Jackson 升级以后，这三项更需要在 IT 环境补跑。

## S3-01：官方模板包入库（DEC-1；C-1～C-5；V-1、V-8）

依据：技术设计 v10 `docs/design/APPK-2/tech-design.md` @ `08043003d67209d79a7c2334407def58a58d2d56` §5.2 C-1～C-5、§6 DEC-1、§7.1 第 1～3 步、§8 V-1/V-8；PRD v9 附表 E（`docs/prd/APPK-2/E-template-package.md` @ `8fa362c25b62b52ed5b16afd9eca7f6ccfcafb9f`）。本批不含接口、不改 `src/main/**`，没有 API-ID。

### 变更文件

| 文件 | 改动 | 提交 |
| --- | --- | --- |
| `.gitattributes` | C-5：末尾加 `third_party/maccms-template-v2020.1000.1035/** -text -whitespace`（附注释）。按设计先于模板包单独提交 | `3f01ff0` |
| `third_party/maccms-template-v2020.1000.1035/template/default_pc/**` | C-1：147 个文件，官方原样 | `8fca22d` |
| `third_party/maccms-template-v2020.1000.1035/template/default_wap/**` | C-2：127 个文件，官方原样；没有 `ads/` | `8fca22d` |
| `third_party/maccms-template-v2020.1000.1035/LICENSE` | C-3：官方同一提交根目录 Apache License 2.0 原文 | `8fca22d` |
| `third_party/maccms-template-v2020.1000.1035/SOURCE.md` | C-4：来源仓库、标签、提交号、3 个目录树 SHA-1、取得命令、校验命令、“不得修改，改动须新开目录与 OP-”规则 | `8fca22d` |
| `docs/api/APPK-2/impl-notes.md` | 本说明：新增「S3-01」一节；REV-1 修订提交范围统计 | `f9df5f5`、REV-1 修订提交 |

### 取得方式

在仓库外的一次性空目录执行，没有运行官方仓库里的任何脚本：

```text
git clone --filter=blob:none --no-checkout https://github.com/magicblack/maccms10 src
git -C src rev-parse v2020.1000.1035^{commit}        → 4c418c2703692148a937ad92893ab8a494bff742
git -C src -c core.autocrlf=false archive --format=tar 4c418c2703692148a937ad92893ab8a494bff742 template LICENSE \
  | tar -x -C third_party/maccms-template-v2020.1000.1035
```

### 实际执行的命令与结果（S3-01）

```text
# V-1：入库后的目录树（HEAD = 模板包提交）
git rev-parse HEAD:third_party/maccms-template-v2020.1000.1035/template              → 9398a1ed539cc754b7513c76e12134b3b5b49a53
git rev-parse HEAD:third_party/maccms-template-v2020.1000.1035/template/default_pc   → 4c8d4ce6f8a68c343f94ccb15beda9157d77aea7
git rev-parse HEAD:third_party/maccms-template-v2020.1000.1035/template/default_wap  → a211f900e060c1d405f2c9d75523e02abac45b80
git rev-parse HEAD:third_party/maccms-template-v2020.1000.1035/LICENSE              → 261eeb9e9f8b2b4b0d119366dda99c6fd7d35c64（与官方提交内 LICENSE 相同）
git ls-tree -r --name-only HEAD third_party/maccms-template-v2020.1000.1035/template | wc -l → 274

# 逐文件对附表 E.3：从附表 E.3 取 274 行（路径、字节数、blob SHA-1），分别与
#   ① git ls-tree -r -l HEAD …/template（库内）
#   ② 工作区逐个 stat -c %s + git hash-object --no-filters（工作区字节）
# 排序后 diff → 两者都 0 差异
# 目录边界：template/ 下只有 default_pc、default_wap；…/template/default_wap/ads 不存在
# 路径边界：276 个条目全部是 100644 普通文件（无符号链接、无可执行位、无子模块）；
#   全部位于 template/default_pc/、template/default_wap/、LICENSE、SOURCE.md 之下；无 “..” 段、无反斜杠
git check-attr text whitespace -- …/template/default_pc/info.ini → text: unset；whitespace: unset
git ls-files --eol third_party/maccms-template-v2020.1000.1035 → i/lf 195、i/-text 77（二进制）、i/none 4

# 提交范围（REV-1 修订：写明对比的目标提交，并列出本说明文件）
git diff --name-only 8dee62e f9df5f5 | wc -l → 278
git diff --name-status 8dee62e f9df5f5 → M .gitattributes；M docs/api/APPK-2/impl-notes.md；A third_party/maccms-template-v2020.1000.1035/** 276 个
#   即 278 = .gitattributes 1 + 模板包 276（274 个模板文件 + LICENSE + SOURCE.md）+ docs/api/APPK-2/impl-notes.md 1
#   （只到模板包提交 8fca22d 为 277 个，不含本说明文件）
#   REV-1 修订提交只改 docs/api/APPK-2/impl-notes.md，相对 8dee62e 的路径集合仍是这 278 个
git diff --name-only 8dee62e f9df5f5 -- src/main | wc -l → 0
git diff --check 8dee62e f9df5f5 → 无输出，退出码 0（模板包在 -whitespace 属性下不做空白检查；.gitattributes 与本说明无空白问题）

# V-8：构建不受影响
./mvnw -B -q package -DskipTests → 退出码 0
jar tf target/maccms-backend-0.1.0-SNAPSHOT.jar | grep -c third_party → 0；grep -ci "default_pc\|default_wap" → 0

# 回归
./mvnw -B verify → Tests run: 3025, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS；耗时 10:29（与 REV-BE-1 相同的 3025 项，无退步）
```

### 迁移与回滚

- 无数据迁移、无配置取值变更；模板包是只读资产，不进 jar，运行代码不读取它（读取方在 S3-10 起才接入）。
- 回滚（设计 §9）：`git revert 8fca22d 3f01ff0`；删掉 `third_party/maccms-template-v2020.1000.1035/` 不影响任何运行代码。

### S3-01 自查（自查不代替审计）

- 内容未改：274 个文件的路径、字节数、blob SHA-1 与附表 E.3 逐行一致；3 个目录树 SHA-1 与附表 E.1、DEC-1 一致（BR-11、AC-1 ① 的仓库内部分，V-1）。
- 换行：`.gitattributes` 先于模板包提交；库内与工作区字节一致（本机 `core.autocrlf=true` 下工作区 `hash-object --no-filters` 仍与附表相同）。
- 许可：保留官方 `LICENSE`（Apache-2.0），`SOURCE.md` 写明来源。
- 范围：只改 `.gitattributes`、`third_party/maccms-template-v2020.1000.1035/**` 与本说明；未改 `src/main/**`、PRD、设计、API、T1、`tests/api/**`、deploy；未发接口请求、未部署。
- 不含的部分：C-9 `verify_template_package.py`（逐文件校验脚本，测试 S3-03）、C-11 部署样例（流水线）、C-12～C-16（S3-02）不在本批。

## S3-02：`{$maccms.path_tpl}` 映射修正（REV-1；PATH-2～PATH-4；C-12～C-16；V-9）

依据：技术设计 v10 `docs/design/APPK-2/tech-design.md` @ `08043003d67209d79a7c2334407def58a58d2d56` §1.1（PHP 四个路径量、`All.php:58-63`）、§3.1、§5.1 C-12～C-16、§6 DEC-3 推出的 PATH-1～PATH-4、§7.1 第 4 步、§8 V-9、§9；OP-15 已关闭（设计 v2 @ `ebd7aa60ae968229997221d3086b45ddb4fa67e1`）。本批不含接口，契约：N/A（只改站点选择结果到 `SiteVariant` 的映射与注释，没有 API-ID）。基于已放行的 S3-01 `200cffc5d01090faf4f61e153feb472b107b34df`。

### 变更文件

| # | 文件 | 改动 | 提交 |
| --- | --- | --- | --- |
| C-12 | `src/main/java/com/maccms/backend/site/web/SiteVariants.java` | `resolve` 第 95 行：`r.pathTpl()` → `r.pathTemplate()`，`SiteVariant.pathTpl` 改取 PATH-2 `MAC_PATH_TEMPLATE`；没有旧配置时仍为 `""` | `05ffd11` |
| C-13 | `src/main/java/com/maccms/backend/site/web/view/SiteVariant.java` | 只改注释：`@param pathTpl` = `MAC_PATH_TEMPLATE`（`All.php:62`），不含 `html_dir`；`@param pathAds` = `MAC_PATH_ADS`（`All.php:63`）。记录字段名与顺序不变 | `05ffd11` |
| C-14 | `src/main/java/com/maccms/backend/site/application/SiteTemplateSelection.java` | 只改类注释（原第 23 行）：三个路径量与 PHP 常量的对应，模板变量取 `pathTemplate`/`pathAds`，`pathTpl` 供“模板文件不存在”消息（`think_exception.tpl:366`）。`resolve` 与 `Result` 不改 | `05ffd11` |
| C-15 | `src/test/java/com/maccms/backend/site/web/SiteVariantsTest.java` | `filterResolvesEachRequestFromUserAgentAndHost` 第 75 行期望 `/template/wapt/`、第 82 行 `/template/default/`（第 76 行 `pathAds` 不变）；新增 `pathVariablesFollowLabelMaccms`（DEC-3 取值，`mob_status='2'`、`install_dir='/'`） | `05ffd11` |
| C-16 | `src/test/java/com/maccms/backend/site/application/SiteTemplateSelectionGoldenTest.java` | 比较处（原第 61 行）加注释“黄金键 path_tpl 记录的是 MAC_PATH_TPL，模板变量见 SiteVariant.pathTpl”；新增 `dec3ValuesSplitThePathConstants`。比较逻辑与黄金 JSON 不改 | `05ffd11` |
| — | `docs/api/APPK-2/impl-notes.md` | 本说明：新增「S3-02」一节 | 本节所在提交 |

按 §7.1 第 4 步，C-12～C-16 单独一个提交 `05ffd11`，只含这 5 个文件；说明另起提交。

### AC- / PATH- → 测试（V-9）

| 对象 | 测试 | 断言 |
| --- | --- | --- |
| PATH-2 `{$maccms.path_tpl}` = `MAC_PATH_TEMPLATE`（FR-1、FR-8、AC-1、AC-10） | `SiteVariantsTest.pathVariablesFollowLabelMaccms` | DEC-3 取值经过滤器：桌面 UA `pathTpl=/template/default_pc/`、`pathAds=/template/default_pc/ads/`；手机 UA `wap=true`、`pathTpl=/template/default_wap/`、`pathAds=/template/default_wap/ads/` |
| PATH-2 接线（原错值修正） | `SiteVariantsTest.filterResolvesEachRequestFromUserAgentAndHost` | 手机 `/template/wapt/`、无 UA 桌面 `/template/default/`；手机标志、语言、`pathAds`、`Vary`、手机 Logo 断言不变 |
| PATH-2～PATH-4 与 PHP 常量一一对应（RISK-T8） | `SiteTemplateSelectionGoldenTest.dec3ValuesSplitThePathConstants` | 桌面 `pathTemplate=/template/default_pc/`、`pathTpl=/template/default_pc/html/`、`pathAds=/template/default_pc/ads/`、`templateDir=default_pc`、`htmlDir=html`；手机对应 `default_wap` |
| `Result` 未变（黄金锁定） | `SiteTemplateSelectionGoldenTest.selectionMatchesLegacyInit`（php80、php73 两份，未改） | 92 个用例与 PHP `Init.php` 相同 |

### 实际执行的命令与结果（S3-02）

```text
# 工作目录：仓库根（Git Bash）
# Red：先改 C-15/C-16 测试，未改 C-12
./mvnw -B -q test -Dtest=SiteVariantsTest,SiteTemplateSelectionGoldenTest -Dsurefire.failIfNoSpecifiedTests=false
→ Tests run: 8, Failures: 2
  SiteVariantsTest.filterResolvesEachRequestFromUserAgentAndHost:75  expected "/template/wapt/"       but was "/template/wapt/html/"
  SiteVariantsTest.pathVariablesFollowLabelMaccms                     expected "/template/default_pc/" but was "/template/default_pc/html/"
  （SiteTemplateSelectionGoldenTest 3 项通过：dec3ValuesSplitThePathConstants 锁定的是不改的 Result，本就应通过）

# Green：改 C-12～C-14 后，V-9
./mvnw -B -q test -Dtest=SiteVariantsTest,SiteTemplateSelectionGoldenTest -Dsurefire.failIfNoSpecifiedTests=false
→ 退出码 0；SiteTemplateSelectionGoldenTest Tests run: 3, Failures: 0；SiteVariantsTest Tests run: 5, Failures: 0

# 产品代码里 pathTpl/pathAds/pathTemplate 的读取方（提交 05ffd11）
git grep -n -E "pathTpl\(\)|pathAds\(\)|pathTemplate\(\)" 05ffd11 -- src/main
→ 只有 SiteVariants.java:95（本次改的这一行）；仍没有产品代码读取 SiteVariant.pathTpl()，页面输出无可见变化

# 提交范围
git diff --name-only 200cffc 05ffd11 → 上表 C-12～C-16 的 5 个文件
git --attr-source=05ffd11 diff --check 200cffc 05ffd11 → 无输出，退出码 0
git rev-parse 05ffd11:third_party/maccms-template-v2020.1000.1035 → 75038ae7f8e05e4fb42fea6e72920507f6255d33（与 200cffc 相同，模板包未动）

# 全量回归（提交 05ffd11）
./mvnw -B verify → Tests run: 3027, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS；耗时 08:06
  （S3-01 的 3025 项 + 本批新增 2 项：pathVariablesFollowLabelMaccms、dec3ValuesSplitThePathConstants；无退步）
```

### 迁移与回滚

- 无数据库或数据迁移，不读写任何表；不改配置取值（DEC-3 的取值只在对照环境与部署方站点里设，C-7/C-11 不归本批）。
- 回滚（设计 §9）：`git revert 05ffd11`。FR-1 接入前没有产品代码读取 `SiteVariant.pathTpl`，回退无可见影响；FR-1（S3-10 起）接入后若要回退，须先回退 FR-1 再回退本项。

### S3-02 自查（自查不代替审计）

- 与设计一致：C-12 只换一个取值；C-13、C-14 只改注释；`SiteTemplateSelection.resolve`、`Result` 字段与 PHP 黄金未改，`selectionMatchesLegacyInit` 两份黄金仍通过。
- 测试先红后绿：Red 的 2 处失败原因都是多出 `html/`（即本次要修的错误），不是测试写错。断言是具体路径值，没有 mock 被测逻辑。
- 路径边界（OP-15）：模板读取根仍是 PATH-1 `template/<templateDir>/<htmlDir>/`，由 `Result.templateDir()`/`htmlDir()` 算，与 `path_tpl` 分开；后续渲染链通过 `Result.pathTemplate()`（经 `SiteVariant.pathTpl`）取模板变量，本批不实现 S3-10～S3-12。
- 范围：只改上表 5 个文件与本说明；未改模板原件（包目录树不变）、PRD、设计、API、T1、`tests/api/**`、deploy；未发接口请求，未部署。
- 安全：本批不新增路由、输入、查询、文件读写或出站请求；`pathTpl` 的值来自旧配置 `install_dir`/`template_dir`，与改前同源，只是不再拼 `html_dir`。没有凭据或个人数据。
- 不含的部分：C-11 部署说明（流水线）、V-4/V-5 接口对照（测试线，FR-1 实现之后）。

## S3-06：运行时值语义（C-17；RT-2～RT-5；V-11 的值语义部分）

依据：技术设计 v13 `docs/design/APPK-2/tech-design.md` @ `9c73bf6ced044f40eeb87ef7861e616b6157499a` §5.2 C-17、§6.3（`phptemplate.runtime` 只依赖 JDK）、§6.5 RT-2～RT-5、§7.4 S3-06、§8.2 V-11；PRD v10 @ `9dd36a73febe8bac4c5d02b2638dd57c3e642a49`（FR-2、FR-4、BR-3）；值语义黄金 `src/test/resources/phptemplate/semantics/php73-vectors.json` 与生成器 `src/test/php/tpl_runtime_golden.php`、说明 `docs/test/APPK-2/s3-05-golden.md` @ `b71b1aa4be0190f85686650a7b22459c995cf57e`（S3-05 两层检查 PASS）。本批不含接口，契约：N/A（没有 HTTP 路由、没有 API-ID）。基于已放行的 S3-02 `2de6979799b9ed9552900715806ab0513c13d833`。

### 变更文件

| # | 文件 | 改动 |
| --- | --- | --- |
| C-17 | `src/main/java/com/maccms/backend/phptemplate/runtime/ZValue.java` | 新增。值类型：`Null`、`Bool`、`Int`（`long`，64 位）、`Float`（`double`）、`Str`（不可变字节串，长度/下标/比较按字节）、`ZArray`；`typeName()` 同 PHP `gettype` |
| C-17 | `src/main/java/com/maccms/backend/phptemplate/runtime/ZArray.java` | 新增。有序数组：插入序遍历，覆盖保留原位；键规范化（字符串键规则同 `PhpArray.normalizeKey`；`bool`→0/1、`null`→`""`、`float`→`zend_dval_to_lval`、数组作键非法）；自动下标 `nNextFreeElement`（只增不减，到 `PHP_INT_MAX` 后拒绝追加）；`copy()` 逐层快照（供解释器实现写时复制 / MC-4） |
| C-17 | `src/main/java/com/maccms/backend/phptemplate/runtime/PhpSemantics.java` | 新增。RT-3 字符串化（`precision=14` 的 `%.14G`，按精确二进制值偶数舍入，同 `zend_dtoa` mode 2 + `php_gcvt`）；RT-4 `compare_function`、`==`/`<`/`<=`（`>`、`>=` 交换操作数）、`===`、`in_array`；`is_numeric_string_ex`、`zendi_smart_strcmp`、`zend_binary_strcmp`、`zend_is_true`、`zval_get_long`；RT-5 `fetchDim`（读：数组缺键为 null，字符串单字节、越界为空串，标量容器为 null）与 `fetchDimQuiet`（isset/empty 上下文） |
| C-34（部分） | `src/test/java/com/maccms/backend/phptemplate/runtime/PhpSemanticsGoldenTest.java` | 新增。V-11 值语义黄金逐条比对（8 项） |
| C-34（部分） | `src/test/java/com/maccms/backend/phptemplate/runtime/PhpSemanticsTest.java` | 新增。黄金之外的边界单测与“runtime 只依赖 JDK”检查（9 项） |
| 黄金 | `src/test/resources/phptemplate/semantics/php73-vectors.json` | 从 `b71b1aa` 原样取出（`git show b71b1aa:<路径>`），blob `d28769ad2744c12c8eb9f46170961e734347a13b`、SHA-256 `b76c85c9590137e52a9aa30d5a7f842d05502d8c036d3907884a0c662e49cf6b`，与放行提交、s3-05-golden.md §6.1 记载逐字节相同 |
| — | `.gitattributes` | 追加 S3-05 同一条 `src/test/resources/phptemplate/** -text -whitespace`（原文照抄 `b71b1aa`），黄金按字节检出 |
| — | `docs/api/APPK-2/impl-notes.md` | 本说明：新增「S3-06」一节 |

只引入值语义需要的 1 个黄金文件；S3-05 的生成器、编译/渲染/模型黄金、`.compare/` 临时目录与 `docs/test/APPK-2/s3-05-golden.md` 都没有带入本分支。

### RT- → 黄金分组 → 测试（V-11 值语义部分）

| 规则 | 黄金分组（条数） | 测试 | 断言方式 |
| --- | --- | --- | --- |
| RT-2 键规范化 | `rt2_array_keys`（10） | `PhpSemanticsGoldenTest.rt2ArrayKeysNormalizeLikePhp73` | 按生成器源码同序写 12 次（`'1'`、`'01'`、`1.7`、`true`、`null`、`'-3'`、`' 3'`、`'3 '`、`'+4'`、`'-0'`、`'1.0'`、溢出串），遍历结果的值、键类型、键逐条等于黄金 |
| RT-2 键序与自动下标 | `rt2_array_order`（5） | `rt2ArrayOrderAndAutoIndexMatchPhp73` | `['b']`、`[]`、`['a']`、`[10]`、`[]` 后键序/类型/值等于黄金（第二次追加为 11） |
| RT-2 与 `PhpArray.normalizeKey` 同规则 | — | `PhpSemanticsTest.stringKeysNormalizeExactlyLikePhpArray` | 22 个字符串键两边结果相同 |
| RT-3 字符串化 | `rt3_stringify`（22） | `rt3StringifyMatchesPhp73Precision14` | 输入表标签与黄金逐一对应；`gettype` 与 `(string)` 结果逐条相等（含 `-0`、`1.0E+14`、`1.0E-5`、`0.33333333333333`、`1.2345678901235E+17`、`PHP_INT_MIN`、`Array`） |
| RT-4 弱比较 | `rt4_compare`（21） | `rt4LooseComparisonMatchesPhp73` | 先按生成器 `hexOf`/`serializeArray` 校验输入编码等于黄金，再比 `==`、`!=`、`===`、`<`、`>`、`<=`、`>=` 共 7 个结果（147 个断言） |
| RT-4 `in_array` | `rt4_in_array`（6） | `rt4InArrayIsLooseByDefault` | 输入编码等于黄金；默认弱比较与 `strict=true` 两个结果 |
| RT-5 未定义取值 | `rt5_undefined`（5） | `rt5UndefinedValuesReadAsNull` | 未定义下标/嵌套下标读为 null、输出 `""`；`isset` 为假、`empty` 为真。“undefined var”只验证值层面（null → `""`），变量表查找归 S3-07 |
| RT-5 字符串下标 | `rt5_str_index`（6）、`rt5_str_index_bytes`（4） | `rt5StringOffsetsReadSingleBytes` | `'abc'` 的 0/1/2/3/-1/`'1'` 下标的字符与类型（越界为空串、类型 string）；`"你"` 三字节逐字节取 |
| 分组归属完整 | 全部 19 个分组 | `everyGoldenGroupIsCoveredHereOrAssignedToALaterTask` | 黄金分组 = 本类 8 个 + `NOT_COVERED_HERE` 11 个；黄金新增分组时测试失败 |
| 64 位边界、数值串、下标边界、字节串、JDK 依赖 | — | `PhpSemanticsTest` 其余 8 项 | 取模/截断、溢出整数串退回字节比较、NaN、7.3 不允许尾随空白、`Long.MIN_VALUE` 下标不抛异常、`phptemplate.runtime` 的 import 与全限定名只含 `java.*` |

期望值一律取自黄金；部分分组黄金没有记录输入值（`rt2_*`、`rt3_stringify`、`rt4_compare`），测试的输入表逐字照抄生成器 `b71b1aa` 同名分组源码，并用标签、条数和输入编码与黄金互相校验。没有跳过、没有按标签硬编码结果。

### V-11 中不属于本批的剩余覆盖

| 黄金分组 | 归属 | 说明 |
| --- | --- | --- |
| `rt3_json`（15） | 模板函数 C-27/S3-11（`TemplateFunctions`，V-15） | C-17 的 RT-2～RT-5 不含 `json_encode`；V-11 点名的 `json_encode` 向量待 S3-11 比对。C-22/S3-09 是标签编译，不承接本组（S306-1 复核意见改正：初版误记为 C-22） |
| `gap1_bare_identifier`（2） | S3-07 解释器（C-18/C-19） | 裸标识符 `color` 按未定义常量取名字作字符串；GAP-1 须在 S3-07 前完成设计处理 |
| `gap1_mac_day_color`（3）、`fn_mac_day`（44）、`fn_date`（24）、`fn_list`（58）、`fn_url_list`（10）、`fn_internal`（14） | `TemplateFunctionsGoldenTest`（模板函数 C-27/S3-11） | 函数返回值与签名 |
| `tag_names` | `TagLibCompileTest`（标签编译 C-22/S3-09） | TPL-11 原样输出的标签名 |
| `art_info_empty_where`、`art_info_not_found` | `TagModelsInfoDataTest`（S3-12b） | `Art.infoData` 1001/1002 分支 |
| 变量表、算术、`.` 连接、`echo` 输出缓冲、`ZDUMP-1` 编码 | S3-07（`InterpreterTest`）/ S3-12b | 本批只提供值与语义原语，不含执行；V-11 的 `InterpreterTest` 部分由 S3-07 交付 |

### 实际执行的命令与结果（S3-06）

```text
# 工作目录：仓库根（Git Bash）；JDK 21，JDK_JAVA_OPTIONS 同 MULTICA.md ⚠ 条
# 黄金原样引入
git show b71b1aa:src/test/resources/phptemplate/semantics/php73-vectors.json > src/test/resources/phptemplate/semantics/php73-vectors.json
git rev-parse b71b1aa:src/test/resources/phptemplate/semantics/php73-vectors.json → d28769ad2744c12c8eb9f46170961e734347a13b
git hash-object src/test/resources/phptemplate/semantics/php73-vectors.json   → d28769ad2744c12c8eb9f46170961e734347a13b
sha256sum …/php73-vectors.json → b76c85c9590137e52a9aa30d5a7f842d05502d8c036d3907884a0c662e49cf6b（= s3-05-golden.md §6.1）

# 相关测试（V-11 值语义部分）
./mvnw -B -q test -Dtest=PhpSemanticsGoldenTest,PhpSemanticsTest
→ 第 1 次：Tests run: 17, Errors: 3（rt2_array_keys、rt2_array_order、rt4_compare 的测试辅助方法把 int 键强转成 Str 抛
  ClassCastException，是测试工具写错，不是语义不符）；改为经 PhpSemantics.toStr 字符串化后
→ 第 2 次：退出码 0；PhpSemanticsGoldenTest Tests run: 8, Failures: 0；PhpSemanticsTest Tests run: 9, Failures: 0

# 先红：实现与测试同一轮写成，没有“先写测试、实现为空”的红灯记录；改用变异核验黄金测试能抓到错误语义
# （每次只改一处、跑 PhpSemanticsGoldenTest、再恢复原文件，恢复后 git diff --no-index 无差异）
M1 浮点舍入精度 14 → 15                         → Failures: 2（rt3StringifyMatchesPhp73Precision14、rt4LooseComparisonMatchesPhp73 的输入编码）
M2 非数字串与 int 改成 PHP 8 的按字符串比较      → Failures: 2（rt4LooseComparisonMatchesPhp73、rt4InArrayIsLooseByDefault）
M3 字符串下标越界读改为 null                    → Failures: 1（rt5StringOffsetsReadSingleBytes）
M4 键 "-0" 改为规范化成 int                      → Failures: 1（rt2ArrayKeysNormalizeLikePhp73）

# 提交范围与空白检查
git diff --name-status 2de6979 8a58086 → M .gitattributes；A 3 个 runtime 源文件、2 个测试、1 个黄金
git --attr-source=8a58086 diff --check 2de6979 8a58086 → 无输出，退出码 0

# 全量回归（工作区 = 提交 8a58086 的内容）
./mvnw -B verify → Tests run: 3044, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS；耗时 09:11
  （S3-02 的 3027 项 + 本批新增 17 项；无退步）
```

### 迁移与回滚

- 无数据库、配置或数据迁移；新包 `phptemplate.runtime` 目前没有任何产品代码调用（`site` 等模块未接入），不改变页面与接口输出。
- 回滚（设计 §9 “S3-06～S3-12”行）：`git revert 8a58086`（及本说明提交）；依赖它的 S3-07 起的提交须先回退。

### S3-06 自查（自查不代替审计）

- 与设计一致：只实现 C-17 的三个类与 RT-2～RT-5；`phptemplate.runtime` 只 import `java.*`（`PhpSemanticsTest.runtimeDependsOnlyOnJdk` 守住）；字符串键规则与 `PhpArray.normalizeKey` 同（逐键对照测试），未复用/改动 `legacyconfig`，也没有动各模块已有的 `PhpLoose` 等工具（设计 §1 第 159 行）。
- 黄金：只读不改，期望值全部取自黄金；没有发现与 PHP 7.3 事实或设计条目冲突的向量，没有需要停下回报的项。
- 范围：未实现 S3-07 词法/语法/解释器、函数表、模型调用、模板编译器或页面接入；未改 PRD、设计、API、T1、黄金期望、模板资产、`tests/api/**`；未合入 deploy，未部署。C-35（`ModuleBoundaryTest` 登记 `phptemplate` 分层）不在本单，JDK 依赖约束先由本单测试守住。
- 安全：不新增路由、输入、查询、文件读写、出站请求或日志；`ZValue.Str`/`ZArray` 的 `toString()` 不输出内容（模板变量可能含用户数据）；字符串下标对 `Long.MIN_VALUE`/`Long.MAX_VALUE` 等极端下标不抛异常（单测覆盖）；没有凭据或个人数据。
- 已知限制：PHP 的 Notice/Warning（数组转串、未定义下标、非法偏移）不在值层记录，是否写日志由 S3-07 解释器决定；数组作字符串下标按 `zval_get_long` 处理（非空为 1），模板包中无此写法。

### S306-1 返工：NaN 参与的弱比较（独立复核第 1 轮 FAIL 的修改项）

复核意见：NaN 与字符串弱比较不符合 PHP 7.3（`NAN == "0"`、`NAN <= "abc"` 等 Java 为真，PHP 为假）。修复提交 `e939e25`，接在 `1775e26` 之后，不改写已有提交；S3-05 放行黄金 `php73-vectors.json` 未动（blob 仍为 `d28769ad2744c12c8eb9f46170961e734347a13b`）。

**根因**：`numericCompare` 用 `x < y ? -1 : (x > y ? 1 : 0)`，NaN 落到 0（“相等”）。外层 `looseEquals`/`isSmallerOrEqual` 只在“原始两边都是 int/float”时另判 NaN；字符串、数组元素先转数值再进 `compare` 的路径没有这层判断，于是 NaN 与任何字符串都“相等”，`[0] == [NAN]`、分别构造的 `[NAN] == [NAN]` 也为真。

**PHP 7.3 实测规则**：任一侧是 NaN 的数值比较为“不可比较”，`compare_function` 两个方向都得 1，所以 `==`、`<`、`<=` 为假，`!=` 为真；交换操作数得到的 `>`、`>=` 同样为假；数组逐元素比较遇到 NaN 即停在“不可比较”（`[1,NAN] < [2,NAN]` 在第一个元素已分出大小，仍为真）。NaN 与 `null`/`bool` 走布尔比较（NaN 为真值），与数组比较时数组恒大，这两类不受影响。同一张哈希表（`$z = $x`）先判同表即相等，`ZArray` 的 `a == b` 短路与之一致。

**改法**（`PhpSemantics.java`）：`numericCompare` 改为 `x == y ? 0 : (x < y ? -1 : 1)`，NaN 时两个方向都返回 1，与数组键不对应时的“不可比较”同一约定；`looseEquals`、`isSmaller`、`isSmallerOrEqual` 去掉只覆盖纯数值的特判，统一经 `compare`。`in_array` 弱比较经 `looseEquals`，随之修正。设计口径未变（RT-4 仍是“PHP 7.3 `compare_function`”）。

**新增回归**（不改既有测试）：

| 文件 | 内容 |
| --- | --- |
| `src/test/php/c17_compare_matrix.php` | 生成器：27 个值（NaN、±INF、整数 0/1/-1、浮点 0.0/-0.0/1.5、数字串/非数字串/前导空格/尾随字母/`"NAN"`/`"INF"`/溢出串、null、true、false、`[]`、`[0]`、`[NAN]`、`["0"]`）两两组合 × 7 列（`==`、`!=`、`<`、`>`、`<=`、`>=`、`in_array(右, [左])`），外加 10 条分别构造的数组用例；只接受 PHP 7.3 运行 |
| `src/test/resources/phptemplate-runtime/rt4-compare-matrix.txt` | PHP 7.3.33（64 位）生成结果：729 行矩阵 + 10 行补充；不在 `phptemplate/**` 黄金目录内，S3-05 资产不受影响 |
| `src/test/java/com/maccms/backend/phptemplate/runtime/PhpSemanticsCompareMatrixTest.java` | `looseComparisonMatrixMatchesPhp73`：739 行逐行比对，行数与补充用例数也断言；`nanIsUncomparableWithNumbersAndStringsOnEitherSide`：NaN 在左、右两侧对 `0`、`1`、`0.0`、`1.5`、`"0"`、`"abc"`、`""`、`"1.5"`、`"1abc"`、NaN 均为 `0100000` |

同提交按复核意见改正 `PhpSemanticsGoldenTest.NOT_COVERED_HERE` 与上文“剩余覆盖”表的归属：`rt3_json` 与 `fn_*`、`gap1_mac_day_color` 属模板函数 C-27/S3-11（V-15）；`tag_names` 属标签编译 C-22/S3-09。

```text
# PHP 7.3.33 复现（复核意见原例）
php7.3 -r '$a=NAN; foreach ([0,"0","abc"] as $b) { echo json_encode(["rhs"=>$b,"eq"=>$a==$b,"lt"=>$a<$b,"lte"=>$a<=$b]), PHP_EOL; }'
→ 三行 eq/lt/lte 全为 false（矩阵中 NAN|i0、NAN|s0、NAN|sabc 及反向均为 0100000）

# 生成矩阵（D:\Tools\php\7.3\php.exe，PHP 7.3.33 NTS x64）
php7.3 src/test/php/c17_compare_matrix.php > src/test/resources/phptemplate-runtime/rt4-compare-matrix.txt → 退出码 0，740 行（表头 1 + 729 + 10）

# 先红：新测试对修复前实现（git show 1775e26:…/PhpSemantics.java）
./mvnw -B -q test -Dtest=PhpSemanticsCompareMatrixTest → Tests run: 2, Failures: 2；32 处不一致：
  NAN 对 11 个字符串（左右各 11 = 22 处，php=0100000 java=1000111）、[0]/["0"] 与 [NAN] 互比 4 处、
  分别构造的 [NAN]==[NAN]、<=、>=、in_array([NAN],[[NAN]])、[NAN,1]<[NAN,2]、[a=>NAN]==[a=>NAN] 6 处；
  其余 707 行修复前已一致（无关组合的既有行为被同一矩阵锁住）
# 后绿：修复后
./mvnw -B -q test -Dtest='PhpSemantics*' → 退出码 0；PhpSemanticsCompareMatrixTest 2、PhpSemanticsGoldenTest 8、PhpSemanticsTest 9，失败/错误/跳过 0

# 空白检查
git --attr-source=e939e25 diff --check 1775e26 e939e25 → 无输出，退出码 0

# 全量回归（工作区 = 提交 e939e25 的内容；本说明当时未提交，不参与构建）
./mvnw -B verify → Tests run: 3046, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS；耗时 08:47；退出码 0
  （S3-06 初版的 3044 项 + 本次新增 2 项；无退步）
```

## S3-07a：词法切分、AST 基础与 runtime 诊断（C-18 词法部分；C-23 runtime 部分；BARE-3）

依据：技术设计 v15 `docs/design/APPK-2/tech-design.md` @ `9671aae615450647d75647eb6a0e1c549c99001e`（G1 增量放行评论 `01a19a4e-1987-7eed-8dd0-edd70c0f69dd`）§5.2 C-18、C-23，§6.3（runtime 只依赖 JDK），§6.4 TPL-5、TPL-10，§6.5 RT-1，§6.5.1 BARE-3、BARE-7 与 S15-1～S15-3，§8.2 V-11；C-17 @ `ff3a1a659dcc15c7cc3eda18541bcd1406922b4e`；黄金 @ `b71b1aa4be0190f85686650a7b22459c995cf57e`；PRE-5 `docs/test/APPK-2/template-scan.md` @ `b550151b4243622fe7ac060c9ff6e720d83ae605`。OP-T7/OP-T8 方向按评论 `01a19a29-0da2-7580-867e-5aaa35b1c877`、核验 `01a19a2b-3f7b-796c-b374-4970aa9edec3`。本批不含接口，契约：N/A（没有 HTTP 路由、没有 API-ID）。

分支 `agent/agent/appk-5`，起始提交 `f98ad7d256adcc9809194471d6a4e23c659386f2`，只追加提交：

| 提交 | 内容 |
| --- | --- |
| `d25577a64bd8f4d81f12f040f9b97af1d5d05986` | 普通合并 `ff3a1a6`（C-17 两层 PASS），无冲突；保留起始血缘 |
| `d03773128ae7463e9422c8d93cc9d1522a506dd6` | 从 `b71b1aa` 按路径原样取黄金（见下“黄金引入”） |
| `a3e040e6df30a411fb7348133c867d57fc7272e8` | 本批代码与测试 |
| 本说明所在提交 | 本节 |

### 黄金引入（为什么不是整枝合并）

试过普通合并 `b71b1aa`：`.gitattributes` 冲突（两边对同一段各追加不同的行），且该分支带有 `.compare/APPK-2/**` 等 800 余个过程文件（`chore(agent): uncommitted changes from task` 提交）。按派单“冲突停报，不任意覆盖”，已 `git merge --abort`，改为 `git checkout b71b1aa -- <路径>` 只取本批要读的黄金：`src/test/resources/phptemplate/**`（185 个文件）、`src/test/php/tpl_runtime_golden.php`、`src/test/php/tpl_art_fixture.php`、`docs/test/APPK-2/s3-05-golden.md`。`git diff --stat b71b1aa HEAD -- <上述路径>` 为空（逐字节相同）；两份 `ajax_info.html` blob 为 `d5ec0086…`、`9817833c…`，与设计 S15-1 一致。`.gitattributes` 只追加 `b71b1aa` 已有的两行 `src/test/php/tpl_{runtime_golden,art_fixture}.php text eol=lf`（与上游取并集，不改已有行）。`PhpTemplateGoldenAssetsTest` 依赖 `.compare/`，没有带入。日后若整枝合并 `b71b1aa`，这些路径是相同内容的 add/add，不会冲突；`.gitattributes` 冲突仍需人工取并集。

### 变更文件

| # | 文件 | 改动 |
| --- | --- | --- |
| C-18 | `src/main/java/com/maccms/backend/phptemplate/runtime/PhpLexer.java` | 新增。按原始字节切成首尾相接的 `Segment.Literal` / `Segment.Code`；代码段内切记号 `Token`（`Op`、`Variable`、`Name`+`NameKind`、`Keyword`、`Cast`、`IntLit`、`FloatLit`、`Str`、`Interpolated`），偏移全为原始字节偏移 |
| C-18 | `src/main/java/com/maccms/backend/phptemplate/runtime/PhpAst.java` | 新增（本批基础）。`Node`/`Stmt`/`Expr`、`Program`、`InlineHtml`、`NullLiteral`、`BoolLiteral`、`IntLiteral`、`FloatLiteral`、`StringLiteral`、`Variable`、`BareConst`（只接受登记名）。语句与其余表达式节点由 S3-07b 追加 |
| C-23 | `src/main/java/com/maccms/backend/phptemplate/runtime/TemplateUnsupportedException.java` | 新增。`kind()`（`open_tag`、`token`、`unterminated`、`interpolation`、`bare_identifier`）、`offset()`（原始字节偏移）、`snippet()`（偏移起到 CR/LF 前、最多 80 字节、不截断 UTF-8）。不带变量值，不读配置/宿主/反射 |
| BARE-3 | `src/main/java/com/maccms/backend/phptemplate/runtime/BareIdentifiers.java` | 新增。`public static final Set<String> REGISTERED = Set.of("color")`，final 类、私有构造、只此一个字段 |
| 测试 | `src/test/java/com/maccms/backend/phptemplate/runtime/PhpLexerTest.java` | 新增，42 项 |
| 测试 | `src/test/java/com/maccms/backend/phptemplate/runtime/PhpLexerGoldenTest.java` | 新增，3 项（两包 S15 + 全量编译黄金） |
| 测试 | `src/test/java/com/maccms/backend/phptemplate/runtime/RuntimeDiagnosticsTest.java` | 新增，13 项 |
| — | `.gitattributes`、黄金 188 个文件 | 见上节 |
| — | `docs/api/APPK-2/impl-notes.md` | 本节 |

### 词法规则（逐条对照 PHP 7.3.33 `token_get_all` 实测）

| 规则 | 实现 | PHP 7.3.33 依据 |
| --- | --- | --- |
| 开标签 | `<?php`（不分大小写）+ 1 个空格/TAB/LF/CR 或整个 CRLF，空白属于开标签（`codeStart`） | 探针 `opentab`/`opencrlf`/`upper` |
| 其他 `<?` 形态 | 字面段里出现 `<?=`、`<?`+空白、`<?xml`、`<?phpx`、末尾 `<?php` 等 → `open_tag`。编译器（TPL-2 php 步，`short_open_tag` 开时）已把短标签改写为 `<?php echo '<?'; ?>`（黄金 20 处），残留写法的 PHP 行为取决于 `short_open_tag`，不能静默当字面输出 | 探针 `eofopen`/`phpx`（`short_open_tag` 关时为字面）；黄金里只有 `<?php ` 6032 处 |
| TPL-5 | `?>` 后紧跟一个 LF → `Close.TAG_LF`；整个 CRLF → `Close.TAG_CRLF`；否则 `Close.TAG`；无 `?>` 到末尾 → `Close.EOF`。被吃字节在 `[codeEnd, end)` 内，下一个字面段从 `end` 起，原样保留后续换行 | 探针 `lf`/`crlf`/`lflf` |
| `//` 注释 | 到 LF、CR 或 `?>` 前为止，不吞 `?>`；注释里的引号不开始字符串；随后的 `?>` 仍按 TPL-5 吃换行 | 探针 `cmt`/`cmtcr` |
| 字符串 | 单引号只解 `\'`、`\\`；双引号解 `\n \t \r \v \e \f \\ \$ \"`、八进制 1～3 位（取低 8 位）、`\xH{1,2}`、`\u{…}`，未知转义保留反斜杠；字符串里的 `?>`、`<?`、`//` 只是字节；未结束 → `unterminated`（偏移 = 起始引号） | 探针 `dq` |
| 插值 | 双引号串内 `$name` → `Interpolated`（`Part.Text`/`Part.Var`）；`{$`、`${`、`$a[`、`$a->b` 不在 RT-1 → `interpolation` | RT-1 只列 `$var` 插值；黄金 0 处插值 |
| 名字 | `[A-Za-z_\x80-\xff][A-Za-z0-9_\x80-\xff]*`；`a\b` 为 `QUALIFIED`、`\think\Collection`/`\color` 为 `FULLY_QUALIFIED`，整个记号从前导 `\` 起算（BV-5 偏移口径）；悬空 `\` → `token` | 探针 `fq` |
| 保留字 | PHP 7.3 保留字（不分大小写）→ `Keyword`；`true`/`false`/`null` 不是保留字，与 `color`、函数名一样是 `Name`，裸名/函数名在解析阶段按下一个记号判定（BARE-1/BARE-2，S3-07b）；`->` 之后一律是 `Name`（`$a->list`） | 探针 `prop` |
| `/think/…` | 归一化黄金里的 `/think/Collection` 切成 `/`、`Name think`、`/`、`Name Collection`，不当作完整类名（OP-T8） | 全量黄金测试：98 个 `think` 均为 `UNQUALIFIED` |
| 数字 | 十进制整数、`1.5`/`.5`/`2.`/指数浮点；带小数点或指数的前导零串（`00.5`、`01.5`、`00e1`、`00.`、`09.5`）是十进制 float（REV-1 修订）；超出 64 位的整数按 float；八进制整数（无小数点、无指数的前导零串：`00`、`07`、`012`、`00e` 的 `00`）与十六/二进制整数不在 RT-1 → `token` | 探针 `oct`、REV-1 探针 |
| 其他 | `(int)` 等类型转换为一个 `Cast` 记号（解析器拒绝）；运算符最长匹配（含 `<=>`、`**`、`...`）；块注释、`#`、反引号、heredoc、非法字节 → `token`；`$$a` 切成 `$` + `$a`（解析器拒绝） | 探针 `cast` |

### AC-307A → 测试映射

| AC | 测试 | 断言 |
| --- | --- | --- |
| AC-307A-1 原始字节与偏移、TPL-5 | `PhpLexerTest$Segmentation`（7）、`$Tpl5CloseTagNewline`（5）；`PhpLexerGoldenTest.wholeCompileGoldenSplitsIntoContiguousSegmentsWithoutNormalisation` | 字面段原样含 CRLF/TAB/多字节；段首尾相接覆盖全部字节；LF/CRLF 只吃一个，第二个换行原样留在下一字面段；168 个编译黄金逐文件分段连续、`codeBlocks=6032` |
| AC-307A-2 记号、引号转义、`//` 不吞 `?>`、名字/变量/类名区分 | `$Strings`（8）、`$Comments`（5）、`$NamesAndVariables`（8）、`$NumbersAndOperators`（7，含 REV-1 两项）、`$OpenTags`（2）、`sourceIsCopiedDefensively` | 字符串/注释边界各自独立；`color`、`color(`、`$color`、`\think\Collection`、`a\color` 记号种类与偏移；`/think/` 不成类名 |
| AC-307A-3 名单、JDK-only、诊断不读宿主 | `RuntimeDiagnosticsTest`（13）；既有 `PhpSemanticsTest.runtimeDependsOnlyOnJdk` | `REGISTERED == Set.of("color")` 且不可改；类 final、字段唯一且 `public static final`；异常 kind/偏移/片段、片段不截断 UTF-8；四个新文件不含 `System.getProperty/getenv`、反射、文件、日志等；`BareConst` 只接受登记名；词法器不拒绝任何名字（`color(1)`、`PHP_EOL` 都只是 `Name`） |
| AC-307A-4 真实黄金 R15/P15/B15 | `PhpLexerGoldenTest.s15SampleBoundaryAndLexerSegmentation[default_pc, default_wap]` | 原始字节读取；无 `0x0d`、26 个 LF；R15 91 字节、SHA-256 `2fca28e7…59d9b`、后接 LF；EX-15 `<?php`/`?>` 各 1 次、s=33、e=91；P15 33 字节、B15 58 字节且 SHA-256 `6d67a065…b3de7e`；词法切分 R15+LF = 字面 P15 + 代码段 [33, 92)、`TAG_LF`；R15 无 LF → `TAG`；B15 单独切分 [0, 58)；B15 记号与偏移逐个相等（`'user_last_login_time'` 是字符串记号，`color` 是 `Name`）；整份文件中该代码段位于原始偏移 |
| AC-307A-5 有效/无效/截断、先红后绿 | 以上全部；无效与截断见 `unterminatedStringsReportStartOffset`、`complexInterpolationIsUnsupported`、`nonDecimalIntegerLiteralsAreUnsupported`、`leadingZeroIntegerWithoutFractionOrExponentStaysUnsupported`、`unsupportedLexemes`、`shortEchoAndShortOpenTagsAreUnsupported`、`danglingNamespaceSeparatorIsUnsupported`、`otherCommentFormsAreOutsideRt1` | 每个拒绝都断言 kind 与原始字节偏移 |

本批只验证切分与定位：BV-6 的执行层（BV-6c/6d，S15-4 的 `STUB` 输出）、BV-1～BV-5 的求值与拒绝判定属 S3-07b/S3-07c，本批不冒充其通过。

### 实际执行的命令与结果

```bash
# 工作目录：仓库根（Git Bash）；JDK 21，JDK_JAVA_OPTIONS=-Djdk.net.unixdomain.tmpdir=<专用目录>（按 MULTICA.md ⚠ 条设置）
# PHP 7.3 词法探针（php，PHP 7.3.33 NTS x64；脚本在仓库外临时目录，不入库）
#   用例 lf/crlf/cr/lflf/cmt/cmtcr/eofopen/opentab/opencrlf/upper/phpx/prop/fq/dq/oct/cast，对每个源串打印 token_get_all 结果
#   关键输出：T_CLOSE_TAG "?>\n" / "?>\r\n" / "?>\r"；"a<?php" 为 T_INLINE_HTML；"// x '" 后 T_CLOSE_TAG "?>"；
#             $a->list 的 list 为 T_STRING；\think\Collection 为 T_NS_SEPARATOR+T_STRING 序列

# 先红：测试 + 只能编译的空壳（lex 抛 UnsupportedOperationException，名单为空集）
./mvnw -B -q test -Dtest='PhpLexerTest,PhpLexerGoldenTest,RuntimeDiagnosticsTest'
  → Tests run: 58, Failures: 10, Errors: 45（通过的 3 项是类结构与源码扫描）

# 后绿（含既有 PhpSemanticsTest 的 JDK-only 检查；第一次因 PhpLexer 里 import 同包嵌套类被该检查判为非 java.* 导入而失败，改为限定名引用后通过）
./mvnw -B -q test -Dtest='PhpLexerTest,PhpLexerGoldenTest,RuntimeDiagnosticsTest,PhpSemanticsTest'
  → Tests run: 67, Failures: 0, Errors: 0

# 空白检查（目标属性）
git diff --check f98ad7d HEAD → 无输出，退出码 0

# 全量回归
./mvnw -B verify（工作区 = 提交 a3e040e 的内容；本说明当时未提交，不参与构建）
  → Tests run: 3104, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS；耗时 11:21；退出码 0
  （S3-06 的 3046 项 + 本批新增 58 项；无退步）
```

### REV-1 修复（后端评审第 1 轮，评论 `01a19ab6-92bf-7192-94a6-b94aa6987c25`）

- 缺陷：`PhpLexer.number()` 原先在读完整数位后、识别小数点/指数之前，把“长度 > 1 且首字节为 `0`”的数字串一律按八进制拒绝，误拒 PHP 7.3 合法的十进制 `T_DNUMBER`（`00.5`、`01.5`、`00e1`）。
- 修复：`0x`/`0b` 前缀仍在读整数位后立即拒绝；八进制判断移到小数点/指数识别之后，只在结果**不是 float** 且整数位长度 > 1、首字节为 `0` 时拒绝。非十进制整数的拒绝范围不变，TPL-5、黄金、JDK-only、诊断边界不动。
- PHP 7.3.33 `token_get_all("<?php X;")` 实测（`php`，PHP 7.3.33 NTS x64；脚本在仓库外临时目录，不入库）：

| 输入 | PHP 7.3.33 记号 | 本实现 |
| --- | --- | --- |
| `00.5` / `01.5` / `00e1` | `T_DNUMBER` 各 1 个 | `FloatLit` 0.5 / 1.5 / 0.0 |
| `00.` / `09.5` / `08e1` / `0012.25` / `00E+2` / `00.5e-1` | `T_DNUMBER` 各 1 个 | `FloatLit` 0.0 / 9.5 / 80.0 / 12.25 / 0.0 / 0.05 |
| `0.5` / `0e1` | `T_DNUMBER` | `FloatLit`（原有行为） |
| `00` / `07` / `012` | `T_LNUMBER`（八进制） | 拒绝 `token`，偏移 6 |
| `00e` | `T_LNUMBER "00"` + `T_STRING "e"` | 拒绝 `token`，偏移 6（`00` 是八进制整数） |
| `0x1A` / `0b11` | `T_LNUMBER` | 拒绝 `token`，偏移 6（原有行为） |

| REV-1 | 测试 | 断言 |
| --- | --- | --- |
| 前导零浮点/指数合法 | `PhpLexerTest$NumbersAndOperators.leadingZeroDecimalFloatsAndExponentsAreDecimal` | 9 个输入逐个为单个 `FloatLit`，值与上表一致；`0012.25` 记号为原始字节 [6, 13) |
| 拒绝边界保留 | `…leadingZeroIntegerWithoutFractionOrExponentStaysUnsupported`；既有 `nonDecimalIntegerLiteralsAreUnsupported` | `00`、`07`、`00e`、`00...$a`（`...` 不是小数点）及 `012`/`0x1A`/`0b11` 都是 `token`、偏移 6 |

```bash
# 先红（只加测试、未改实现）
./mvnw -B -q test -Dtest='PhpLexerTest$NumbersAndOperators'
  → Tests run: 7, Failures: 0, Errors: 1（leadingZeroDecimalFloatsAndExponentsAreDecimal 抛 TemplateUnsupportedException；拒绝边界一项本就应通过）
# 后绿
./mvnw -B test -Dtest='PhpLexerTest,PhpLexerGoldenTest,RuntimeDiagnosticsTest,PhpSemanticsTest'
  → Tests run: 69, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS
# 全量回归（工作区 = REV-1 修复提交的代码与测试）
./mvnw -B verify
  → Tests run: 3106, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS；耗时 09:07；退出码 0（3104 + 新增 2 项，无退步）
# 空白检查（目标属性）
git diff --check 0b3fcd58cbbe67438035c554b8e61549821cce94 HEAD → 无输出，退出码 0
```

### 迁移与回滚

- 无数据库或数据迁移；不读写表、配置或文件；没有调用方（解析器/解释器尚未接入），产品行为不变。
- 回滚：`git revert` REV-1 修复提交、本节所在提交与 `a3e040e`（只删 4 个新类与 3 个测试类）；黄金提交 `d037731` 与 C-17 合并 `d25577a` 可保留（后续批次要用）。

### S3-07a 自查（自查不代替审计）

- 与设计一致：文件与包同 C-18/C-23；`BareIdentifiers` 原样同 BARE-3；异常只带 kind、编译产物偏移与片段，模板文件/行号留给 C-24（C-23 v11）；`runtime` 只依赖 JDK（既有检查通过）。
- 测试先红后绿：红灯 55 项失败都来自空壳未实现，不是测试写错；断言用具体字节、偏移、哈希，没有 mock 被测逻辑；期望值来自 PHP 7.3.33 实测与黄金。
- 安全：词法器不执行代码、不做任何 I/O；拒绝路径的片段只截取编译产物原文（模板文本），不含变量值；名单写死不可扩充；`BareConst` 构造即校验登记名，后续批次无法误造未登记裸名节点。没有新增路由、输入、查询、出站或凭据。
- 范围：未写 PhpParser、Interpreter、OriginMap、模型/数据库、编译器或页面；未改 PRD、设计、API、T1、模板、黄金、接口脚本或流水线；未发接口请求、未连库。

### 已知问题与剩余项

| # | 内容 | 归属 |
| --- | --- | --- |
| KI-1 | **TPL-5 与 PHP 的一处差异**：PHP 7.3.33 实测 `?>` 后的**单独 CR**（`\r` 后不是 `\n`）也被吃掉（探针 `cr`：`T_CLOSE_TAG "?>\r"`），设计 TPL-5 只写 LF 与整个 CRLF。本批按设计实现（单独 CR 原样输出，`loneCrIsNotEatenPerDesignTpl5` 锁定），模板包与 168 个编译黄金里 `0x0d` 为 0 处，不影响现有页面。是否把单独 CR 纳入 TPL-5 需架构师裁定，裁定后改一处判断与该测试 | 架构师-海外（经智能调度） |
| R-1 | 解析器：BARE-1/2/5、RT-7 函数调用节点、MC-1 `ModelCall`、OP-T7 `instanceof` 固定形态、`//` 注释与下标赋值的语法节点、BV-1～BV-5 | S3-07b（APPK-3） |
| R-2 | 解释执行、TPL-5 吃换行在输出上的体现、BV-6c/6d、RT-8 限额、日志断言 | S3-07c（APPK-4） |
| R-3 | `OriginMap` 与模板文件/行号补全（C-23 其余部分、TPL-9） | S3-08 / C-24 |

## S3-07b：C-18 语法解析与白名单边界（RT-1；BARE-1～BARE-5；MC-1/MC-2；OP-T7）

起点 `d50077f`，按派单以普通合并取得 S3-07a `8588474`（合并提交 `bb0d483`，无冲突）。只构造/校验语法树，不含解释器、业务函数、模型执行、编译器或页面；黄金未改。

### 变更文件

- `src/main/java/com/maccms/backend/phptemplate/runtime/PhpParser.java`（新增）：记号流 → `PhpAst.Program`；`?>` 充当 `;`；PHP 7.3 优先级/结合性（`. + -` 同级左结合、三元左结合、相等/比较不结合）；花括号与 `:`/`endXxx;` 两种写法；解析时注入 RT-7 白名单判定 `Predicate<String>`。
- `PhpAst.java`（增量）：语句 Echo/ExprStmt/If/Branch/Foreach/For/Switch/Case/Break；表达式 Interpolated/ArrayLiteral/ArrayItem/Index/Assign(`=`,`.=`)/Increment(`++`)/Unary/Binary/Ternary/Isset/Empty/FunctionCall/ModelCall（含 MC-2 `normalizedModel()`）/InstanceOf/IsEmptyCall；`REGISTERED_CLASSES = {\think\Collection, \think\Paginator}`。
- `TemplateUnsupportedException.java`：新增 kind `syntax`、`function`、`model_call`、`object`（追加在既有 5 个之后，既有代码不变）。
- 测试：`PhpParserTest`（新增，137 例）、`PhpParserGoldenTest`（新增）、`RuntimeDiagnosticsTest.kindCodesAreStable`（期望列表追加 4 个新 code，仍为精确匹配）。

### AC → 测试

| AC | 测试 |
| --- | --- |
| AC-307B-1 语法覆盖 | `PhpParserTest$Statements`（alt/花括号 if/elseif/else、foreach、for、switch、`{php}` 块含 `//` 注释与下标赋值、23 种语句拒绝）、`$Expressions`（优先级、赋值/下标/++/数组/插值/isset/empty，29+5 种表达式拒绝）；`PhpParserGoldenTest`：168 个真实编译产物中 166 个解析通过，节点计数与 41 个函数名锁定（覆盖表见测试断言），2 个按 GAP-2 拒绝 |
| AC-307B-2 BARE | `$BareIdentifiersAndFunctions`：BV-1/2 字面量与 `color`；BV-3 位置无关；BV-4 `color(1)`→`function`@11、`$color` 变量；BV-5 九例 `bare_identifier`@11、`constant`/`defined`→`function`@11、片段含名字；未执行分支 `if(false){ echo PHP_EOL; }`→@22；另 21 例（预定义/框架常量、魔术常量、`self::`/`static::`、命名空间函数、宿主函数、`call_user_func`、`Model(...)`）|
| AC-307B-2 MC-1 | `$ModelCalls`：MC-2 规范化；10 种非登记 `model` 形态→`model_call` |
| AC-307B-3 OP-T7 | `$ObjectForms`：两种固定形态（if 与 elseif 条件）通过；其他/非完整/大小写变体/动态类名、黄金 `/think/…`、固定形态外的 instanceof、主语不一致、顺序颠倒、缺项、`$b->isEmpty()`、未执行分支，以及任意成员访问、独立/变形/链式方法→`object`；`PhpParserGoldenTest.normalizedGoldenClassNamesAreRejectedAsObjectSyntax`：28 个原样黄金文件全部在 `/think/Collection` 处拒绝 |
| AC-307B-4 | 见下方命令；`PhpSemanticsTest.runtimeDependsOnlyOnJdk` 通过（解析器只 import `java.*`） |

### 实际执行的命令与结果（S3-07b）

```text
export JDK_JAVA_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:\Tools\Java\afunix-tmp
./mvnw -B test -Dtest='PhpParser*,PhpLexer*,RuntimeDiagnosticsTest,PhpSemantics*' → Tests run: 218, Failures: 0, Errors: 0, Skipped: 0
git --attr-source=HEAD diff --check d50077f HEAD -- src/main/java/com/maccms/backend/phptemplate src/test/java/com/maccms/backend/phptemplate → 退出码 0
./mvnw -B verify → 本次运行未执行（单次运行时限内未跑完，见剩余项）
```

### 设计说明与边界

- 函数白名单在**解析阶段**判定（注入的 `Predicate`），所以未执行分支里的白名单外函数同样拒绝；`model`/`isset`/`empty`/`array` 由语法层处理。白名单内容归 C-19/C-27。
- MC-3 方法白名单不在语法层（`TagModels` 唯一分派表）；语法层只保证 `model(<无插值字面量>)->名字(实参)` 且不接 `->`/下标/`::`。
- OP-T7：`instanceof` 只在整个 if/elseif 条件恰为形态 A/B 时被认领，未认领的 instanceof/isEmpty 在解析结束时按首个偏移报 `object`。值域（数组/标量）与短路不调用 `isEmpty`、到达即拒绝属 S3-07c 执行层。
- 有意不接受（未登记）：`--`、复合赋值（`+=` 等）、`??`、位运算、类型转换、`@`、`$a[]`、调用尾逗号、对非变量取下标、`break N`、`continue`。

### 已知问题与剩余

- **GAP-2（设计缺口，需架构师裁定）**：两份 `rss/index.html` 第 11 行模板原文 `{$maccms:site_url}{$maccms:path_tpl}` 编译为 `$maccms->site_url`、`$maccms->path_tpl`（各 1 次，偏移 437 起）。RT-1 写明“单独的 `->属性` 取值不支持”，PRE-5 未登记；PHP 7.3 对数组取属性得 null 并记 Notice（输出 `http://images/logo.png`，未实测）。解析器按现设计拒绝，测试锁定该拒绝；不自行定义语义。
- 全量 `./mvnw -B verify` 未在本次运行内执行，须下一次运行或复核时补跑。
