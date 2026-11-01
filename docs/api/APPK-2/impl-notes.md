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
