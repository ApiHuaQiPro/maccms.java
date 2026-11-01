# APPK-2 后端实现说明（第 1 批：方法策略、SCH-1 传输上限、API-030 安装令牌、OUT-1 出站校验）

- 契约：`docs/api/APPK-2/openapi.yaml` @ `bc612e933aa67abab319b90534c5150c10a51452`（版本 1.3.0，本批未改契约）
- 技术设计：`docs/design/APPK-2/tech-design.md` @ `20490951e1a9c880e975caee0c72c5e0e92120ed`
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

- SSRF：open 模式按解析后的地址判断；非常规数字写法直接拒绝，不交给 DNS。**连接时没有固定地址（OUT-1-5 未实现），见下文风险 1**。
- CSRF：安装会话 cookie 设为 SameSite=Strict，令牌一次性；后台方法表在来源校验之前生效，只会更严。
- 输入：后台不再因为正文超限而抛出 400 异常；正文上限从 256 KiB 提到 8 MiB，是契约要求（fcontent 2 MiB 的分支要能到达）。内存占用：每个请求最多读 8 MiB + 1 字节。

## 已知问题与风险

1. **OUT-1-5（连接时固定已校验地址、防 DNS 重绑定）未实现，需要架构决定。** `OutboundHttpClient` 用的是 JDK `java.net.http.HttpClient`，它不能按请求指定解析结果。现有三条路都超出本批的设计边界：
   - (a) 换成带 `DnsResolver` 钩子的客户端（如 Apache HttpClient 5），要新增依赖；
   - (b) JDK `InetAddressResolverProvider` SPI：它从系统类加载器加载，Spring Boot 可执行 jar 里的类加载不到，而且一改就影响整个进程；
   - (c) 把 URI 改成 IP 字面量，并自己处理 Host 头（JDK 默认禁止设置）、TLS SNI 和证书主机名校验。

   现状：每一跳先解析并校验，然后由 HttpClient 按主机名重新解析再连接，DNS 重绑定仍有一个时间窗口。
2. OUT-1-7 规定“超时可调小、不可调大”，配置层还没有加上限校验（默认值本来就是 15 秒 / 30 秒 / 4 MiB）。
3. OUT-1-8 的“只接受 2xx”和 OUT-1-9 的固定错误体（`COLLECT_*`、`IMAGES_*`）属于 API-040/041/043 的业务实现，基线还没有在线采集，本批不做。
4. TRN-1 只在后台入口实现。API-002、API-007（返回 1001 JSON）以及 API-030 的参数个数上限（返回 500）没有做；API-030 的 1 MiB 正文上限是基线已有的。
5. 单值 8192 字符、数组 600 项都是各字段自己的业务分支，跟着各 operation 的实现走。
6. ADM-3 的 SRC-2（B 类写操作在三个来源头都缺失时拒绝）不在本批派活的四项里，没有实现。
7. open 模式下 `check` 会做 DNS 解析，所以已登记的出站方（支付、OAuth、短信、插件市场等）每一跳会多一次解析；解析失败从“连接失败”变成了“目标被拒绝”（`TARGET_REJECTED`）。
