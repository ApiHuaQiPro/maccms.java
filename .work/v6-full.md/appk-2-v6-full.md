## APPK-2 盘点清单 v6（完整版，可单独确认）

| 项 | 内容 |
|---|---|
| 版本 | v6，完整替代 v1～v5。本文件是 APPK-2 复核、复审和人类确认的**唯一清单对象**；阅读和确认本清单不需要打开任何旧版 |
| 当前源码依据（唯一） | `ApiHuaQiPro/maccms.java@a57e9cb2b94d3e500bea7dfe2c579daee093d969`（下文简写 `@a57e9cb2`） |
| 目录布局 | Java 工程在仓库根目录（`pom.xml`、`src/…`，在仓库根目录执行 `./mvnw`）；旧站 PHP 在 `php/` 下（`php/application/…`、`php/static/…`）。旧路径只出现在 §0.3 的新旧映射里 |
| 授权与派活 | 确认人 v6 授权评论 `01a18f25-c13d-7482-855a-6c84b0aab3f4`；调度派活评论 `01a18f26-6f34-7a1d-80e0-2c7112af7651` |
| 修订依据 | 调度对 v5 的复核 `01a18e86-8f80-743e-a5b8-b3c132e487e3`（1～5 项）；独立架构评审 `01a18e13-a27c-7e0d-9aca-68e725fb295a`（REV-1～6） |
| 作者 | 架构师-海外。本文不自判通过，以调度复核和原架构评审的结论为准 |

**测试执行（全文以此为准）**：v6 没有运行任何测试、Maven、PHP 黄金、浏览器脚本、性能脚本或候选任务。只用 `git show`、`git grep`、`git ls-tree`、`git cat-file`、`git rev-parse`、`git merge-base --is-ancestor` 读了 `@a57e9cb2`。v1 时架构师自述跑过 `./mvnw -B verify`（3005 条，0 失败），v3 时自述跑过两条限定命令；这些都在旧目录布局下执行，Leader 没有复跑，**不作为任何批次的验收证据，也不继承到新基线**。

**所有候选任务的总前提**：§3、§4 的每一项都只是候选，发起人确认最终清单之后才能建单、开工。“依赖”“前置”只描述确认之后的技术先后，不构成开工授权。Q1～Q11 都未决定，本清单不替你选择。

**待拆组的含义**：标为“待拆组”的条目，本版只写“现状、缺口、要先设计的问题”，不写验收条款、验证承诺或执行方案，也不直接建单。等接近上线时单独开设计单，过 G1 后再拆。**延期细化不表示对应的上线条件已经满足，也不表示已被排除。**

### 0. 修订处置

#### 0.1 调度 v5 复核 1～5 项（确认人 v6 授权第 1～5 条）

| 项 | 处置 | 位置 |
|---|---|---|
| 1 写全 | 全文不再引用旧版内容。原 14 单（E1a～E1g、E2a～E2g）的范围、计数和通用 AC，E6 的 11 单及其统一前置，B/C/D 各单的 AC，Q1～Q11 及 Q4 的 a～p 子项，都在本文展开。当前路径一律按 `@a57e9cb2` 新目录书写，本轮对正文引用的仓库路径逐条做了存在性核对（§0.3） | §1～§5 全文；§0.3 |
| 2 计数 | B21 只读对齐共 15 单：E1a～E1h 8 单（含安装向导 E1h）、E2a～E2g 7 单。§4.1 表、§4.4 E8a 前置、E8b AC-1、§4.7 执行顺序的计数一致 | §4.1；§4.4；§4.7 |
| 3 性能现状 | 删去“没有任何性能测试或压测工具”。§2.1 如实盘点 `src/test/perf/` 六个文件和 09/15/16 三份报告：不由 Maven 编译、不在 `./mvnw verify` 中，`SearchPerf` 用 `maccms_perf_*`，`run-all.sh` 全流程约 2 小时。另如实记录 `B05Perf.java`、`b06_perf_run.py` 用的是 `maccms_it_*`（§0.4 ①）。E9b/E9c 改为待拆组，只写现状、缺口和要先设计的问题，不要求整套执行，也不把旧报告当新基线 | §2.1；§4.3 E9b、E9c |
| 4 出站演练 | E10 改为待拆组。删除 v5 中“只连 127.0.0.1”与“访问本机非回环模拟服务”并存的条款，以及以 `TARGET_REJECTED` 日志作证据的条款。现状写明 `BaiduUrlPush.java:82–83` 捕获异常后返回失败结果、`26-b23-integration-foundation.md:162` 说明策略拒绝不单独记日志。“只连本机”限制下怎样验证、怎样客观证明没有对外连接，列为要先设计的问题；不放宽现有限制 | §4.3 E10 |
| 5 复验规则 | 删除“按影响路径集判断旧报告是否仍有效”的规则。上线前的所有演练、性能、护栏报告，一律在 E8a 宣布的 `$B21_FINAL` 上重新执行。E8 拆成 **E8a 宣布冻结** 和 **E8b 最终收口**：E9a、E3 复跑等只依赖 E8a，E8b 依赖它们，没有循环 | §3.0.5；§4.4 E8a/E8b；§4.7 |

确认人第 3 条要求 E5a～E5d、E9b/E9c、E10 统一列为待拆组，本版照做；E4a 条文保持不变，只在其后加一条术语说明（§4.2）。

#### 0.2 独立架构评审 REV-1～6

| REV | 处置 | 位置 |
|---|---|---|
| REV-1 浏览器登录与可执行性 | 已核实：`src/test/browser/b13_admin_content_check.py:122–124` 填写账号和密码，`:125` 等待跳转；`:35–38` 只接受 7 个位置参数，没有单步执行入口；脚本还需要运行中的可写后台、临时旧配置、临时站点根目录（下有 `upload/`）、只服务本次的 nginx 和样例 PNG（`docs/architecture/22-b13-admin-content.md` §9 第 258 行）。不再称“原脚本只读复跑”。浏览器走查拆成 P3a（设计单）和 P3b（实施待拆组）。登录态只由人在本次实例上手动登录后保存为 `APP_UI_STORAGE_STATE`（`MULTICA.md` §5），脚本只加载登录态，不填表。新系统的会话在进程内存里（`13-cutover-plan.md` §7.5 第 3 条），每次运行都要有人在场登录，由 Q10 决定。不改 `MULTICA.md` §0 | §3 P3a、P3b、B4c；§5 Q10 |
| REV-2 调用链与允许范围 | C3 写全调用链：`MemberOAuthService#callback`（`:136` 调 `openidLogin(qq, out.openid(), clientIp)`）→ `MemberAccountService#openidLogin`（`:597`）→ `openidRegister`（`:625`）→ `tryOpenidRegister`（`:629`）→ `MemberWriteMapper#registerOpenid`（`member/infrastructure/MemberWriteMapper.java:82`）→ `mapper-write/member/MemberWriteMapper.xml:164`（插入语句没有 `user_nick_name` 列）；昵称来自 `integration/application/OAuthOutcome.java:11`。C2 改正方法名（用户名规则在 `tryOpenidRegister`，`registerOpenid` 是 Mapper 方法）。解绑改为设计单 C4-D 加待拆组 C4-impl；安装失败清理改为设计单 C6-D 加待拆组 C6-impl（实际插入在 `install/infrastructure/InstallWrites.java:27–35`，`Result` 不带主键）。C8 写全用到的 Mapper 路径 | §3 C2、C3、C4-D、C4-impl、C6-D、C6-impl、C8 |
| REV-3 B21 完整性 | ① 新增 E1h，按功能逐项对齐 `php/application/install/controller/Index.php`，不按 public 方法计数。② 路线图 B21 行（`docs/architecture/09-roadmap.md:411`）的“全量护栏与性能冒烟”：E9a 全量护栏（保留 AC），E9b/E9c 性能冒烟（待拆组），口径由 Q11 决定。③ 出站应急 `loopback-only` 切换（`09-roadmap.md:508`、`26-b23-integration-foundation.md` §12 第 4 条）：E10（待拆组）。三项都是 E8b 的收口条件 | §4.1 E1h；§4.2 E9a；§4.3 E9b、E9c、E10；§4.4 E8b；§5 Q11 |
| REV-4 文件、指标与本机资源 | 评审指出的缺口（`13-cutover-plan.md` §5b 的“配置文件”项和完整错误率口径；§7.2/§7.4 的文件 diff 与恢复；PHP/Java/nginx 进程、站点副本、缓存的归属与唯一清理人；失败退出处置；生产禁入判据）全部保留，按确认人第 3 条改为 E5a～E5d 待拆组中“要先设计的问题”，不在本版写成执行 AC | §4.3 E5a～E5d；§4.5 |
| REV-5 规模恢复 | 已核实 `src/test/java/com/maccms/backend/writedrill/DmlRestore.java:41–75`：`plan` 接收两份内存中的 `DatabaseSnapshot`，把全部语句累积到 `List`（`:72`）；`TableSnapshot.java:23` 限制每表 200 000 行（`:66` 检查）。E4a 设计分段快照、分段 diff 和有界的分段 DML 恢复，含规模上界、中止与重跑、超过 20 万行的验证 IT、旧 API 回归；恢复库名限制不放宽。E4a 本版不变 | §4.2 E4a、E4b |
| REV-6 条件冲突、共享文件与验收版本 | §3.0.3 已批准差异的叠加规则；§3.0.4 共享文件表；E6 各单不直接改批次文档，由 E6-doc-* 汇总；§3.0.5 改为“冻结后统一重跑”规则（见 §0.1 第 5 项） | §3.0.3～3.0.5；§3 C2/C3；§4.4；§4.6 |

评审的三条不阻断建议也已落入：P4 参数白名单（§3 P4 AC-1）；Q9 增加规模与时间预算（§5 Q9）；演练清理结果要有最终提交可追溯——随 E5 改为待拆组，列为 E5c 要先设计的问题。

#### 0.3 新旧路径映射与存在性核对

映射规则（与 `@a57e9cb2` 上 `AGENTS.md`「仓库目录」、`MULTICA.md` §1 一致；历史提交没有重写）：

| 旧写法（`@3a3262e` 及更早） | 当前写法（`@a57e9cb2`） |
|---|---|
| `backend/pom.xml`、`backend/src/…`、`backend/target/…`、`cd backend && ./mvnw` | `pom.xml`、`src/…`、`target/…`，在仓库根目录执行 `./mvnw` |
| 旧站 `application/…`、`static/…`、`upload/`、`vendor/` | `php/application/…`、`php/static/…`、`php/upload/`、`php/vendor/` |

核对结果：
- 本文正文引用的仓库文件与目录，本轮都在 `@a57e9cb2` 上用 `git cat-file -t` 核对存在；明确写为“待建”“运行期产物”的路径除外（`tests/`、`tests/api/`、`tests/e2e/`、`docs/test/`、`.github/`、`.github/workflows/`、`ops/`、`target/…`、`docs/design/<KEY>/`）。
- §1 第 2 列的 39 个提交，本轮逐个用 `git merge-base --is-ancestor <SHA> a57e9cb2` 核对，都返回 0。
- `php/application/{index,api,admin}/controller/` 下分别有 23、6、39 个控制器文件；每个控制器的 `public function` 计数与 §4.1 表一致（本轮逐个 `git show … | grep -c "public function"`）。
- `git grep -L 'created disposable database' a57e9cb2 -- 'src/test/**/*MySqlIT.java'` 的结果是 `ContentApiMySqlIT`、`LibraryMySqlIT`、`StaticTimmingMySqlIT` 三个类（v5 时核对、调度复核时复跑）。
- 目录调整同时改了构建、测试和文档里的路径引用（例如 `WriteInfrastructureMySqlIT` 中 `Path.of("php","application",…)`、`pom.xml` 中 `php/static`、`pay_golden.php` 的 `$repo` 指向 `php/`）。所以旧布局下的任何测试结论都不继承，候选任务一律在新基线上执行。

#### 0.4 本轮发现的来源差异（只记录，不自行取舍）

1. **性能脚本的库名前缀不统一**：派活要求写明“使用 `maccms_perf_*`”。源码中只有 `SearchPerf.java`（`:56` 正则 `^maccms_perf_[0-9]{14}_[0-9a-f]{8}$`，`src/test/perf/README.md:9`）用这个前缀；`B05Perf.java:52`（`^maccms_it_…`，`:85` 注释“库名前缀改为 maccms_it_”）和 `b06_perf_run.py:78–79` 用的是 `maccms_it_*`。本文按源码如实写，并把“性能工具的库名约束与清理判据”列为 E9b 要先设计的问题。
2. **性能工具文字中的旧路径**：`src/test/perf/README.md:32` 仍写“导入 `application/install/sql/install.sql`”，`SearchPerf.java:158`、`B05Perf.java:161` 的日志文字同样如此；实际读取路径已是 `php/application/install/sql/install.sql`（`SearchPerf.java:59`、`B05Perf.java:55`、`b06_perf_run.py:31,95`）。只影响文字，不改。
3. **`AGENTS.md` 第 31 行**仍写“`backend/` Java 工程骨架”，属于 A2 的修改范围。

### 1. 现状证据映射

缩写：`{J}`=`src/main/java/com/maccms/backend/`，`{T}`=`src/test/java/com/maccms/backend/`，`{M}`=`src/main/`（模板写作 `{M}jte/…`、`{M}jte-admin/…`、`{M}jte-install/…`），`{A}`=`docs/architecture/`，`{W}`=`src/main/resources/mapper-write/`，`{R}`=`src/main/resources/mapper/`。第 2 列的提交都是 `@a57e9cb2` 的祖先（§0.3）。表中没有任何一行以测试运行结果作证据；“文档历史记录”一栏照录旧工作区留下的文字，不代表新小队门禁已过。

| 批次 | 代码已在 main（合入提交｜代码/模板路径｜测试路径） | 文档 | 文档历史记录（照录 `{A}09-roadmap.md` §5.1 或批次文档） |
|---|---|---|---|
| B01 MACC-25 | `c2e7562` `5a1730b` `2a6c317`｜`{J}content/api/VideoController.java`、`{M}jte/site/pages/search.jte`｜`{T}content/ContentSearchMySqlIT.java` | `{A}09-content-search-filter-plan.md`、`{A}10-site-search-page.md` | §5.1 写“待建”（与 main 不符）；合并提交写“合入整合分支” |
| B02 MACC-26 | `dcd3598`｜`{J}common/browser/BrowserExternalAccess.java`、`{J}legacyconfig/application/`｜`{T}architecture/OutboundNetworkGuardTest.java`、`{T}architecture/SchemaGuardTest.java` | `{A}11-guards-and-legacy-config.md` | §5.1 写“待建”；合并提交标题为 accept |
| B03 MACC-27 | `52b9e5f`｜`{J}content/api/ArticleController.java`、契约 `docs/contracts/content-read.openapi.yaml`｜`{T}content/LibraryMySqlIT.java` | `{A}11-content-read-completion.md` | §5.1 写“待建”；合并提交标题为 accept |
| B04 MACC-28 | `a0876b8`｜`{J}content/application/ContentReadService.java`｜`{T}content/ContentApiMySqlIT.java` | `{A}14-video-list-filter-completion.md` | §5.1 写“待建”；合并提交标题为 accept |
| B05 MACC-29/54 | `2313076`｜`{J}content/application/ArticleReadService.java`、`{M}jte/site/ajax/list.jte`｜`{T}content/LibraryMySqlIT.java` | `{A}15-b05-readonly-pages.md` | “已验收”，并在 `b2cdd2d` 上整批复验 |
| B06 MACC-30/55 | `b645b53`｜`{J}common/clientip/ClientIpResolver.java`、`{M}jte/site/pages/map.jte`｜`{T}openapi/ProvideGoldenMySqlIT.java` | `{A}16-b06-misc-pages-provide.md` | §5.1 写“待建”；合并提交标题为 accept |
| B07 MACC-31 | `a4ca3bd`｜`{J}common/write/WriteRegistry.java`｜`{T}architecture/SqlEntryGuardTest.java`、`{T}writedrill/WriteInfrastructureMySqlIT.java` | `{A}12-write-infrastructure.md`、`{A}13-cutover-plan.md` | §5.1 写“待建”；合并提交标题为 accept |
| B08 MACC-32 | `363d7bf`｜`{J}common/captcha/Captchas.java`、`{J}member/application/MemberSessionService.java`｜`{T}member/MemberGoldenMySqlIT.java` | `{A}17-b08-member-session-captcha.md` | “已验收” |
| B09 MACC-33 | `b5f1bbf`｜`{J}member/application/MemberAccountService.java`｜`{T}member/MemberAccountGoldenMySqlIT.java` | `{A}18-b09-member-account.md` | “已验收” |
| B10 MACC-34 | `3f26190`｜`{J}member/application/MemberWalletService.java`、`{M}jte/site/pages/buyConfirm.jte`｜`{T}member/MemberFinanceGoldenMySqlIT.java` | `{A}19-b10-member-finance.md` | “已验收” |
| B11 MACC-35 | `4db4c6d`｜`{J}comment/application/CommentService.java`｜`{T}comment/application/LegacyHtmlGoldenTest.java` | `{A}20-b11-comment-gbook-counters.md` | “已验收” |
| B12 MACC-36 | `6a9054d`｜`{J}admin/application/AdminLoginService.java`、`{M}jte-admin/admin/pages/login.jte`｜`{T}admin/AdminFrameGoldenTest.java` | `{A}21-b12-admin-framework.md` | “已验收” |
| B13 MACC-37 | `8bfce79`｜`{J}admin/web/AdminContentActions.java`、`{M}jte-admin/admin/pages/actor/index.jte`｜`{T}admin/web/AdminContentGoldenMySqlIT.java` | `{A}22-b13-admin-content.md` | “同作者复核后合入，未经队长独立测试，不算独立验收” |
| B14 MACC-38 | `769bfd6`｜`{J}member/application/FinanceAdminService.java`、`{M}jte-admin/admin/pages/card/index.jte`｜`{T}admin/web/AdminMemberGoldenMySqlIT.java` | `{A}23-b14-admin-member.md` | “已合并”：第二轮候选经同作者复核和前端独立只读复核 |
| B15 MACC-39 | `1a0ac63`｜`{J}admin/application/DatabaseToolService.java`、`{J}admin/application/AddonService.java`｜`{T}admin/application/PhpHtmlEntitiesGoldenTest.java` | `{A}24-b15-admin-system.md` | “已合并”：经前端独立只读验收 |
| B16 MACC-40 | `5f48e8b`｜`{J}staticgen/application/MakeService.java`、`{J}staticgen/web/TimmingController.java`｜`{T}staticgen/StaticTimmingMySqlIT.java` | `{A}25-b16-static-timming.md` | “不算独立验收” |
| B17 MACC-41 | `f4a4ebc`（新增该文件的提交，提交信息为 “uncommitted changes from task”，按文件内容归到 B17）、`9779fcd` `94e5f7b`｜`{J}collect/application/CollectAdminServices.java`｜`{T}collect/application/CollectBindingsGoldenTest.java` | `{A}25-b17-collect-config.md` | “已合并”，没写验收人 |
| B18 MACC-42 | `39b9902`｜`{J}admin/web/AdminCollectActions.java`｜`{T}admin/web/AdminCollectPreviewMySqlIT.java` | `{A}26-b18-collect-preview.md` | “已合并”，附合入时的 verify 计数 |
| B19 MACC-43 | `738352f` `644b74c` `bec5117`｜`{J}collect/application/CollectIngestService.java`、`{J}collect/api/ReceiveController.java`｜`{T}collect/ReceiveGoldenMySqlIT.java` | `{A}27-b19-collect-ingest.md` | “已合并”；增量 2 记有独立验收重生成黄金 |
| B20 MACC-44 | `9dca2fc`｜`{J}admin/web/AdminCjActions.java`、`{J}collect/application/CjRunService.java`｜`{T}admin/web/AdminCjMySqlIT.java` | `{A}28-b20-cj-local-html.md` | “已合并” |
| B22 MACC-45 | `7335a7d` `bc0df74`｜`{J}install/application/InstallWizard.java`、`{M}jte-install/install/pages/step3.jte`｜`{T}install/InstallWizardMySqlIT.java` | `{A}31-b22-install-wizard.md` | “已合并”；D-B22-1/3 写“请队长确认” |
| B23 MACC-46 | `a9ce377`｜`{J}integration/http/OutboundHttpClient.java`、`{J}integration/application/LogRedactor.java`｜`{T}integration/http/OutboundPolicyTest.java` | `{A}26-b23-integration-foundation.md` | “不算独立验收” |
| B24 MACC-47 | `633d0c5`（线性提交，没有 accept 合并）｜`{J}member/application/MemberPaymentService.java`、`{J}integration/pay/WeixinPay.java`、`{J}site/web/SitePaymentController.java`、`{M}jte/site/pages/userPayForm.jte`｜`{T}member/PaymentGoldenMySqlIT.java`、`{T}integration/pay/PaymentGoldenTest.java` | `{A}33-b24-payment.md` | §5.1 写“候选……待验收” |
| B25 MACC-48 | `2db7fab`，经 `b2cdd2d` 合入｜`{J}integration/sms/`、`{J}integration/mail/SmtpMailer.java`、`{M}jte/site/pages/userBind.jte`｜`{T}member/SmsMailGoldenMySqlIT.java` | `{A}32-b25-sms-mail.md` | “队长独立验收通过”；§5.1 写“待合并”（与 main 不符） |
| B26 MACC-49 | `19904cd` `8426a58` `36750f4`（线性提交）｜`{J}member/application/MemberOAuthService.java`、`{J}integration/oauth/ConnectOAuthLogin.java`、`{M}jte/site/member/oauthLinks.jte`｜`{T}member/OAuthGoldenMySqlIT.java`、`{T}site/web/SiteOAuthMvcTest.java` | `{A}34-b26-oauth.md` | “候选……待验收” |
| B27 MACC-50 | `f532cba`｜`{J}openapi/api/WechatController.java`｜`{T}openapi/WechatGoldenMySqlIT.java` | `{A}29-b27-wechat.md` | “不算独立验收” |
| B28 MACC-51 | `621bda8`｜`{J}staticgen/application/UrlsendService.java`、`{J}integration/backlink/BacklinkFetcher.java`｜`{T}admin/web/UrlsendBacklinkGoldenMySqlIT.java` | `{A}30-b28-urlsend-backlink.md` | “不算独立验收” |
| B29 MACC-52 | `9ca0300`（线性提交）｜`{J}admin/application/AddonMarketService.java`、`{J}admin/application/AddonSql.java`、`{J}integration/addonmarket/FastadminAddonMarket.java`｜`{T}admin/web/AddonMarketGoldenTest.java` | `{A}35-b29-addon-market.md` | “候选……待验收” |
| MACC-56 | `e0e7ef6`｜（只改测试）｜`{T}admin/application/AdminPermissionsGoldenTest.java` | `{A}09-content-search-filter-plan.md` §6 | 已合并 |

**文档与 main 不一致（只记录，不改）**：`{A}09-roadmap.md` §5.1（第 267～273、289～294 行附近）中 B01～B04、B06、B07 仍写“待建”，B24/B26/B29 写“候选”，B25 写“待合并”；`AGENTS.md` 第 31～32 行的“当前批次/仍待定”已过时（第 31 行还写 `backend/`）；`{A}02-agent-development-rules.md` §2 只到 2.10（B22），缺 B24/B25/B26/B29 的模块小节；`{A}34-b26-oauth.md` 的编号冲突见 Q4-k；B24 的前端配合项 `data-pay-autosubmit` 在 `src/main/resources/static/site/member.js` 中还没有实现（`git grep -c` 为 0）。

### 2. 还没做的部分

- **B21**（`{A}09-roadmap.md:411`）：功能对齐总表（index/api/admin 控制器，加上安装向导 `php/application/install/controller/Index.php`）；外部调用批次的“待联调”项（7 类，11 单）；在本地开发库的一次性副本上演练整体切换（`{A}13-cutover-plan.md` §9 仍为空）；`{A}09-roadmap.md` §5.5 六项安全差异逐条复验；全量护栏与性能冒烟；出站应急 `loopback-only` 切换与恢复（`09-roadmap.md:508`、`{A}26-b23-integration-foundation.md` §12 第 4 条）；手机模板。
- **App**：只有 content 只读的 JSON 接口（`docs/contracts/content-read.openapi.yaml`）。
- 仓库中还没有 `tests/`、`docs/test/`、`.github/`、`ops/`。

#### 2.1 现有性能测量工具与报告（如实盘点）

| 文件 | 作用 | 库名 | 运行方式与耗时 | 对应报告 |
|---|---|---|---|---|
| `src/test/perf/README.md` | `SearchPerf` 的说明。第 6 行：“不是测试，不进 `./mvnw verify`：Maven 不编译 `src/test/perf/`”，以 Java 单文件方式运行，依赖 JDK 21 和本地 Maven 仓库里的 `mysql-connector-j-8.2.0.jar`。第 8～9 行：只用 `MACCMS_IT_ADMIN_*`，库名必须匹配 `^maccms_perf_[0-9]{14}_[0-9a-f]{8}$`，`drop` 后查 `information_schema.schemata` 确认不存在。第 10 行：会只读 `information_schema.tables` 中 `maccms_dev` 的元数据（建库前、删库后各取一次比较）。第 13～14 行：被测应用是 `target/maccms-backend-0.1.0-SNAPSHOT.jar` 的子进程，结束时按 PID 结束。第 25 行：`bash src/test/perf/run-all.sh`，“全流程约 2 小时”。输出在 `target/perf/` | `maccms_perf_*` | 见左 | 09 报告 |
| `src/test/perf/SearchPerf.java` | MACC-19（09 方案 §6.5 / T2）：契约 1.1.0 搜索/筛选语句的耗时、EXPLAIN、Handler/排序/临时表计数、MyISAM 表锁等待。子命令 `create`/`grow`/`context`/`bench`/`sqlcheck`/`lock`/`timeout`/`drop` | `maccms_perf_*`（`:56`、`:134`） | 单步：`java -cp <mysql-connector-j-8.2.0.jar> src/test/perf/SearchPerf.java <子命令>` | 09 报告 |
| `src/test/perf/run-all.sh` | `SearchPerf` 的完整顺序：建库 → 10 万/50 万/100 万行三档各跑 `grow`/`context`/`bench`/`sqlcheck`/`lock`（默认 `LOCK_SECONDS=30`）→ `timeout s1m`；`trap cleanup EXIT` 在退出时（含失败）删除一次性库 | `maccms_perf_*` | 仓库根目录 Git Bash，先 `./mvnw -q package -DskipTests`；约 2 小时 | 09 报告 |
| `src/test/perf/summarize.py` | 把 `target/perf/` 的原始输出汇总成报告用的 Markdown 表；只读原始文件，不连库 | — | `python src/test/perf/summarize.py target/perf > target/perf/summary.md` | 09 报告 |
| `src/test/perf/B05Perf.java` | MACC-29：IN/OR 多值、年份区间、随机候选与旧标签条件的耗时和 EXPLAIN；Java 单文件，不进 `./mvnw verify`（15 报告头部） | `maccms_it_*`（`:52`） | 用法见文件头注释 | 15 报告 |
| `src/test/perf/b06_perf_run.py` | MACC-30：Provide、ajax/data、ajax/suggest、站点级 RSS/搜索引擎地图经真实 HTTP 的耗时和实际 SQL 的 EXPLAIN；合成 300 000 个视频（`:32`）；用 PHP CLI 生成临时旧配置；启动已打包的应用，结束时按 PID 结束；不进 `./mvnw verify`（`:2`） | `maccms_it_*`（`:78–79`） | `python src/test/perf/b06_perf_run.py --out <目录>`（仓库根目录，先 `./mvnw -o -q package -DskipTests`） | 16 报告 |

报告（都写明“本机一次运行的测量值，不是延迟或吞吐承诺”）：
- `{A}09-content-search-filter-perf-report.md`：被测代码 `ce531c1`，2026-10-15 测量；结论是包含匹配（`keyword`、`classes`）保持关闭，50 万行热态 p95 已超过方案 6.5 的参考目标；原始结果 `{A}09-content-search-filter-perf-raw/`。
- `{A}15-b05-perf-report.md`：被测 SQL 为分支 `agent/claude/macc-29` 的提交 `6f5e2af`，2026-10-17 测量，测量期间机器没有空闲；原始结果 `{A}15-b05-perf-raw/`。
- `{A}16-b06-perf-report.md`：B06 最终代码打包的应用，2026-10-17 测量，机器未空闲；原始结果 `{A}16-b06-perf-raw/`。

它们与 B21“性能冒烟”的关系见 §4.3 E9b、E9c。三份报告测的都是更早的提交，不能当作 `$B21_FINAL` 的性能证据。

### 3. 候选任务（发起人确认最终清单后才能建单、开工）

#### 3.0 通用约定

**3.0.1 上游、缩写与命令**
- 上游一律取 `@a57e9cb2`，缩写见 §1。
- 标准命令一律在**仓库根目录**用 Git Bash 执行；在 PowerShell 里，每个 `-D…` 参数要整体加引号。运行机须按 `MULTICA.md`「已知问题」设置 `JDK_JAVA_OPTIONS`。

| 记号 | 完整命令 | 实际执行范围 | 耗时（另加编译约 1 分钟） |
|---|---|---|---|
| `{U}` | `./mvnw -B test -Dtest=<类1,类2,…> -Dsurefire.failIfNoSpecifiedTests=false` | Surefire 只跑列出的单元测试类，不跑 IT，不连库 | 1～3 分钟 |
| `{I}` | `./mvnw -B verify -Pmysql-it -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=<IT类1,IT类2>` | Surefire 跑 0 条，Failsafe 只跑列出的 IT；要求 `MACCMS_IT_ADMIN_URL/USERNAME/PASSWORD`；每个 IT 自建并删除一次性库 `maccms_it_<14位时间>_<8位十六进制>` | 视 IT 而定 |
| `{UI}` | `./mvnw -B verify -Pmysql-it -Dtest=<单元类…> -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=<IT类…>` | 列出的单元类加列出的 IT | 同 `{I}` |
| `{F}` | `./mvnw -B verify` | 全量单元和契约测试，不跑 IT；`MULTICA.md` §3 记载为 3005 条（v6 未复跑） | 约 11 分钟 |

- PHP 黄金脚本按脚本文件头的用法在仓库根目录执行（例如 `src/test/php/pay_golden.php`），脚本自己定位 `php/` 下的旧代码；输出一律改写到 `target/golden-regen/<批次>/`（`target/` 已被 `.gitignore` 忽略）。
- **验收类任务**（P、B、E1、E2、E3、E9a）：只产出 `docs/test/<KEY>/` 下的报告，内容是命令、退出码和测试计数摘要；报告不贴原始报文，凭据只写变量名。不改 `src/main` 和已跟踪的测试资源；发现缺陷另开 bug-fix 单。这类任务只用 `{U}`/`{I}`/`{UI}`（E9a 另有全量命令）。
- **实现类任务**（C、D、E4b 等）：只改所列文件，外加对应批次文档的差异登记；交付自检必须跑 `{F}`，下面的时长已把这 11 分钟算进去。
- **档位**：评审版的实施单开工前要先出技术设计并过 G1；设计由架构师按单出，不计入实施时长。范围由设计决定的实施部分标为“待拆组”。最终档位由人决定。

**3.0.2 IT 清理判据**（凡是 AC 写了“本次的一次性库已删除”的单都按此执行；每单只核对、只删除自己创建的库）
- ① 打印库名的 IT：除下面三个类以外的 `*MySqlIT`（`git grep -L 'created disposable database' a57e9cb2 -- 'src/test/**/*MySqlIT.java'` 以外的 31 个类）会输出 `[mysql-it] created disposable database <库名>`。这类 IT 在共享实例上运行：运行前把 `SHOW DATABASES LIKE 'maccms_it_%'` 的结果存为 B0；从输出取出本次创建的精确库名，运行后这些库名不存在，且“运行后列表 − B0”中不含本次的库名。别人建的新库只记录，不删除，也不算本单的残留。
- ② 不打印库名的 IT（`StaticTimmingMySqlIT`、`ContentApiMySqlIT`、`LibraryMySqlIT`）只在 P4 的独占实例上运行：B0 必须为空，运行后列表也必须为空；B0 不为空就判为隔离不成立，本次作废、重跑，不算作本单的残留。
- 辅助证据（不能代替 ①②）：`target/failsafe-reports/TEST-<全类名>.xml` 中 errors=0、failures=0。例如 `{T}staticgen/StaticTimmingMySqlIT.java` 的 `@AfterAll tearDown`（第 123 行起）在 `dbCreated` 为真时执行 `DROP DATABASE`（第 138 行），失败时报类级错误。
- 本判据只覆盖 `maccms_it_*`；性能工具使用的 `maccms_perf_*`（§2.1）不在其内，见 E9b 要先设计的问题。

**3.0.3 已批准差异的叠加规则**
- 每个 C 单把获批的行为变化，按“用例 id + 列/字段 + 新值规则”登记进对应批次文档的“与旧版的差异”节（如 `{A}34-b26-oauth.md` §7、`{A}33-b24-payment.md` §10）。测试断言的口径是“PHP 黄金，加上截至本提交已登记的差异”。
- 后序单的 AC 一律写成“除本单登记的差异外，其余与**前序单合入提交**上的断言一致”，不写“与旧黄金一致”。
- C2 与 C3 都获批时，C3 的基线是 C2 的合入提交；`weixin-guest-underscore` 这类自动注册用例，用户名按 C2 的规则，昵称按 C3 的规则，两条差异都登记。只获批一单时，按那一单单独登记。
- 两个获批差异作用在同一字段上又互相矛盾时，不由实施者取舍，报 Leader 请确认人决定。

**3.0.4 共享文件（`{A}02-agent-development-rules.md` §7：同一文件同一时间只由一个任务修改；只有相关的单都获批时规则才成立）**

| 文件 | 可能修改的候选 | 规则 |
|---|---|---|
| `{A}09-roadmap.md` | A1；C6-impl（若 C6-D 要求改 §5.3 写入方矩阵） | 后建单排在先建单合入之后 |
| `{A}33-b24-payment.md` | C1；E6-doc-33 | C1 先于 E6-doc-33；E6-pay 各单不改这个文件 |
| `{A}32-b25-sms-mail.md` | E6-doc-32 | 只由 E6-doc-32 汇总 |
| `{A}34-b26-oauth.md` | A4、C2、C3、C4-D/C4-impl、E6-doc-34 | 按建单顺序串行；E6-doc-34 排在最后 |
| `{A}35-b29-addon-market.md` | C5b、E6-doc-35 | 按建单顺序串行 |
| `{A}31-b22-install-wizard.md` | C6-impl、C7b | 按建单顺序串行 |
| `{J}member/application/MemberAccountService.java`、`{J}member/infrastructure/MemberWriteMapper.java`、`{W}member/MemberWriteMapper.xml` | C2、C3、C4-impl | 顺序固定为 C2 → C3 → C4-impl，只排已获批的单 |
| `src/main/resources/static/site/member.js` | C4-impl（前端部分）、C10 | 先建单先做 |
| `{J}common/write/WriteRegistry.java`、`{T}architecture/WriteGuardTest.java` | C5b、C6-impl | 串行 |
| `src/test/browser/b13_admin_content_check.py` | P3b | 只有 P3b 改 |
| `{A}13-cutover-plan.md` | E5d（待拆组）拆出的那一单 | 只有它改 §9 |

**3.0.5 候选基线、验收提交与冻结后重跑**
- 每份报告的开头写两项：**候选基线**（任务开始时的 main 提交）和**验收提交**（实际跑命令的提交，`git rev-parse HEAD`，完整 40 位）。
- `$B21_FINAL` 由 E8a 宣布（§4.4）。**上线前的所有演练、性能、护栏报告，一律在 `$B21_FINAL` 上重新执行**，验收提交必须等于 `$B21_FINAL`。这类报告是：E3（安全差异复验，按护栏类处理）、E9a（全量护栏）、E5a～E5d / E9b～E9c / E10 待拆组将来拆出的执行子单。冻结前做过的同类运行只算预检，不作上线证据。
- **不再按影响路径集或 `git diff` 判断旧报告仍然有效**；这类判断规则已全部删除。
- 其他报告（A、B、C、D、E1/E2、E6）照常记录验收提交，不重跑；它们涉及的单元测试和 IT 在 `$B21_FINAL` 上的行为由 E9a 的全量运行覆盖。PHP 黄金重生成和浏览器走查不在 E9a 范围内，E8b 照列其验收提交。
- E8a 之后、E8b 完成之前 main 有新提交时，由 Leader 重新宣布 `$B21_FINAL`；前一次冻结后产生的报告全部作废，按新提交重跑。

#### P 公共测试支撑

| 编号 | 范围 | AC | 验证／产物 | 依赖 | 时长 | 档位｜唯一责任人 |
|---|---|---|---|---|---|---|
| P1 MySQL IT 执行条件 | 运行机环境，不进仓库 | AC-1 运行机已注入 `MACCMS_IT_ADMIN_*`（`MULTICA.md` §5）和 `JDK_JAVA_OPTIONS`。AC-2 用 `{I}` 跑 `WriteInfrastructureMySqlIT`，退出码 0。AC-3 按 §3.0.2 ① 核对本次的精确库名。AC-4 报告里没有账号和口令的值 | `{I}` 的摘要，加前后两次库列表的差集 | 人提供有建库、删库权限的账号 | 30m | 轻量｜运营打包 |
| P2 PHP 黄金运行条件 | 从仓库外的备份恢复 `php/vendor/`（不进 git） | AC-1 按 `AGENTS.md`「本地运行与测试」第 1 步，在 `php/` 下恢复，恢复后 `git status --porcelain` 为空。AC-2 用 `$PHP73`、`$PHP80`、`$PHP_CGI` 按文件头的用法跑 `src/test/php/pay_golden.php`，输出写到 `target/golden-regen/p2/`，文件以 JSON 开头 | 命令和文件头 | P1；备份只在人类的机器上 | 30m | 轻量｜运营打包 |
| P3a 浏览器走查支撑设计 | 只写文档：`docs/design/<KEY>/tech-design.md` | AC-1 登录方式符合 `MULTICA.md` §0“允许脚本填表登录：否”：脚本启动实例后暂停，由人在有界面的浏览器里手动登录本次实例，再保存 Playwright 登录态到 `$APP_UI_STORAGE_STATE`；脚本只做 `new_context(storage_state=…)`，不再接受账号和口令参数，`src/test/browser/b13_admin_content_check.py:122–124` 的填表段删除，“登录后进入框架页”改为“已加载登录态，进入框架页”。登录态文件放在运行机私有目录，用完即删，不进仓库和评论。AC-2 单步方式：为该脚本设计 `--only <步骤名>` 和 `--list`，参数改为具名参数（入口地址、输出目录、一次性库、站点根目录、样例 PNG 路径），给出 47 项与各步骤名的对应。AC-3 运行环境清单：用 `target/maccms-backend-0.1.0-SNAPSHOT.jar` 在 127.0.0.1 的私有端口启动；可写源只指向 `maccms_it_*`；临时旧配置目录（缩略图和水印打开）用 `MACCMS_LEGACY_CONFIG_DIR` 指定；临时站点根目录下放 `upload/`；独立 prefix 的 nginx（不动共用的 `D:/Tools/nginx`）；样例 PNG 的来源。AC-4 清理：列出进程（jar、nginx）的 PID/端口、目录（站点根目录、配置目录、nginx prefix、输出目录、登录态文件）和库，每项写明唯一清理人和核对方法；失败退出时同样执行清理。AC-5 把实施拆成 P3b 各子单，每单列出文件和 AC，控制在 60 分钟内 | G1 架构评审 | P1 | 60m | 评审版：涉及登录态｜架构师 |
| P3b 浏览器脚本改造与环境脚本（**待拆组**） | 已知至少改 `src/test/browser/b13_admin_content_check.py`，另新增环境启停脚本（位置由 P3a 定） | 由 P3a 给出；共同 AC：脚本中不出现任何填写登录表单的代码，`git grep -n "admin_pwd" -- src/test/browser/b13_admin_content_check.py` 为空 | 由 P3a 给出 | P3a 过 G1 | 由 P3a 给出 | 评审版｜由 P3a 指定 |
| P4 独占 IT 实例 | 运行机环境，不进仓库 | AC-1 用 `$MYSQLD`（与 P1 共享实例版本相同）在运行机临时目录新建并初始化数据目录。影响验证的服务端参数白名单：`version`、`sql_mode`、`character_set_server`、`collation_server`、`default_storage_engine`、`lower_case_table_names`、`explicit_defaults_for_timestamp`、`time_zone`，与共享实例一致，报告列出两边的值，差异为空；`server_uuid`、`datadir`、`port`、`socket`、`pid_file` 等天然不同的参数不比较。AC-2 只监听 127.0.0.1 上的 `$IT_PRIVATE_PORT`（不能是 3306），管理员口令运行时随机生成、只放在 `MACCMS_IT_ADMIN_*` 里；启动后 `SHOW DATABASES LIKE 'maccms_it_%'` 返回 0 行。AC-3 `MACCMS_IT_ADMIN_URL` 指向该端口，用 `{I}` 跑 `WriteInfrastructureMySqlIT`，退出码 0。AC-4 关闭实例、删除数据目录后，端口不再监听，目录不存在。AC-5 报告给出启动、核对、关闭、删除四步的完整命令（只写变量名），供 B5b-2、E3、E9a 照做；报告不含口令 | 报告和四步命令 | P1 AC-1 | 30m | 轻量｜运营打包 |

#### A 状态对齐（只改文档）

| 编号 | 范围（只允许改） | AC | 验证 | 依赖 | 时长 | 档位｜责任人 |
|---|---|---|---|---|---|---|
| A1 | `{A}09-roadmap.md` §5.1（第 267～273、289～294 行附近）和修订记录 | AC-1 B01～B04、B06、B07、B24、B25、B26、B29 各行的状态与 §1 的提交号一致，措辞按 Q1/Q2 的选择写。AC-2 §5.1 以外的表格不动 | `git diff --stat` 只有这一个文件 | Q1、Q2；§3.0.4 | 30m | 轻量｜架构师 |
| A2 | `AGENTS.md` 第 31～32 行 | AC-1 “当前批次”（现文仍写 `backend/`）改为指向 09 §5.1。AC-2 “仍待定”改为指向 09 §8、§9，不写新决定 | diff 只在这两行附近 | 无 | 15m | 轻量｜架构师 |
| A3 | `{A}02-agent-development-rules.md` §2 | AC 按各批文档已写的边界，补齐 B24/B25/B26/B29 的模块小节（排在 2.10 之后），不新增规则 | 每句都能在批次文档中找到原文 | 无 | 40m | 轻量｜架构师 |
| A4 | `{A}34-b26-oauth.md` §5、§9 | AC 消除解绑与 D-B26-10 的编号冲突，按 Q4-k 的结论写；§7 已有条目不改 | diff 只有这个文件 | Q4-k；§3.0.4 | 15m | 轻量｜架构师 |

#### B 补独立验收（只有 Q1、Q2 选了“补验收”的批次才建单；同一批次内的各单互不依赖）

**黄金重生成的统一做法**（B1c、B2c、B3b、B4d、B5c、B6c、B7b）：按脚本文件头或批次文档的用法在仓库根目录执行，只把输出改写到 `target/golden-regen/<批次>/php{73,80}-golden.json`。然后 `git diff --no-index --stat <跟踪的资源> <新文件>`，再按 JSON 键逐项比较。**统一判据**：每处差异都要能归为时间项或随机项，报告列出每个键路径及其归类；只要出现“其他”类差异，就判为不一致。跟踪的资源一律不覆盖，结束时 `git status --porcelain` 为空；报告不贴报文正文；各单报告按 §3.0.5 写明候选基线和验收提交。

| 编号 | 主模块｜对象 | AC | 验证命令／产物 | 依赖 | 时长 | 档位｜唯一责任人 |
|---|---|---|---|---|---|---|
| B1a | integration.pay｜B24 不连库部分 | AC-1 `PaymentGoldenTest`：支付宝表单 4 例、码支付/Zhapay 8 例与 PHP 7.3 逐字节相同，回调 50 例的回复相同。AC-2 `PaySafetyTest`：DOCTYPE、外部实体、带属性的元素都被拒绝；在测试配置的 `loopback-only` 下，微信下单在发出前失败，日志里没有密钥、商户号、单号。AC-3 `SitePaymentMvcTest`：W3 关闭后三种跳转方式都提示“未开启”；回调 POST 在可写源关闭时返回 503。AC-4 `git diff bc0df74 633d0c5 -- ':(glob)**/common/write/WriteRegistry.java'`（用 pathspec 匹配历史提交中的旧目录）只新增 `member / mac_order / UPDATE`（`{A}33-b24-payment.md` §5.3） | `{U}` `PaymentGoldenTest,PaySafetyTest,SitePaymentMvcTest,WriteGuardTest,OutboundNetworkGuardTest`，加上 diff 输出 | 无 | 30m | 评审版：支付｜测试人员 |
| B1b | member｜B24 到账 IT | AC-1 78 例复放：提示与 `mac_order`/`mac_user`/`mac_plog` 的行和 PHP 7.3 相同。AC-2 到账写入范围（33 §5.2）：认领 `UPDATE mac_order … AND order_status=0`，转移 `UPDATE mac_user … user_points+?`，记录 `INSERT mac_plog`（type=1）；其他表不变，表结构指纹不变。AC-3 15 轮 × 16 个并发重复回调（四种方式混合），每单只到账一次、只写一条流水。AC-4 补偿：用例 `failedStepsAreCompensatedAndUnknownOutcomesKeepTheClaim` 用支付宝回调（`notify.alipay.ok`）。加积分被拒或写流水被拒时回复 `fail2`，订单恢复为未支付，积分和流水都复原，重发后正常到账一次；写流水时连接中断回复 `fail2`，认领和积分保留，重发回复 `success2`、不重复加积分。33 §5.2 所说的“写入需人工对账”日志测试没有断言，报告记为“未覆盖”。微信渠道写库失败时现状仍回复 SUCCESS（33 §11 D-B24-a，待决），不属于本单的验收项。AC-5 对账（33 §5.4）的三条等式成立；`order_points>65535` 时到账但不写流水（D-B24-4）。AC-6 按 §3.0.2 ① 确认本次的一次性库已删除 | `{I}` `PaymentGoldenMySqlIT` | P1 | 40m | 评审版：资金写入｜测试人员 |
| B1c | 测试资源｜B24 黄金重生成 | AC-1 PHP 7.3 和 8.0 各生成一份到 `target/golden-regen/b24/`，与 `src/test/resources/integration/b24/php{73,80}-golden.json` 按统一判据比较。AC-2 跟踪文件不变 | `src/test/php/pay_golden.php`（33 §9 的用法，输出改到临时目录，口令用变量） | P2 | 45m | 评审版｜测试人员 |
| B2a | integration.oauth｜B26 不连库部分 | AC-1 伪造的、重放的、属于别的会话的 `state` 都在出站之前被拒绝。AC-2 请求里的 `openid`/`col`/`redirect`/`url`/`redirect_uri` 都被忽略。AC-3 令牌或 `client_id` 属于别的应用、openid 超过 40 位或含非法字符时失败。AC-4 日志里没有授权码、令牌、密钥、openid | `{U}` `OAuthLoginTest,SiteOAuthMvcTest,SiteUserMvcTest` | 无 | 30m | 评审版：鉴权｜测试人员 |
| B2b | member｜B26 IT | AC-1 两版共 44 例与 PHP 一致（返回码、cookie 名称和顺序、`mac_user` 每列）。AC-2 同一个新 openid 的 8 个并发回调只注册 1 个账号。AC-3 自动注册账号的密码不在 `md5(now±5)` 之中（D-B26-1）。AC-4 只写 `mac_user`。AC-5 按 §3.0.2 ① 确认本次的一次性库已删除 | `{I}` `OAuthGoldenMySqlIT` | P1 | 40m | 评审版：账号｜测试人员 |
| B2c | 测试资源｜B26 黄金 | AC 与 `src/test/resources/integration/b26/php{73,80}-golden.json`（各 22 例）按统一判据比较（34 文档预期只有 IP 限额种子的时间不同）；跟踪文件不变 | `src/test/php/oauth_golden.php` 文件头的用法，输出到临时目录 | P2 | 45m | 评审版｜测试人员 |
| B3a | admin｜B29 | AC-1 `AddonMarketGoldenTest` 46 步一致。AC-2 `AddonPackageTest`：路径穿越、zip 炸弹、坏包都整包拒绝，没有任何文件落盘。AC-3 `AddonSqlTest`：含非数据语句的整包拒绝；数据语句只校验、不执行，`WriteRegistry` 中没有 B29 的登记。AC-4 `AddonMarketServiceTest`：失败后回滚，没有残留文件 | `{U}` `AddonMarketGoldenTest,AddonPackageTest,AddonSqlTest,AddonMarketServiceTest,FastadminAddonMarketTest,WriteGuardTest` | 无 | 40m | 评审版：SQL 与文件落盘｜测试人员 |
| B3b | 测试资源｜B29 黄金 | AC 两版各 23 步，与 `src/test/resources/admin/addonmarket/php{73,80}-golden.json` 按统一判据比较；跟踪文件不变 | `src/test/php/addon_market_golden.php`，输出到临时目录 | P2 | 45m | 评审版｜测试人员 |
| B4a | admin｜B13 黄金复放 | AC-1 两版各 57 个场景的状态码、Location、JSON、页面结构、表行、`upload/` 清单都一致。AC-2 12 个管理员并发保存后，库里恰好是其中一份完整表单。AC-3 回滚演练的逐行 diff 为空，表结构指纹不变。AC-4 按 §3.0.2 ① 确认本次的一次性库已删除 | `{I}` `AdminContentGoldenMySqlIT` | P1 | 50m | 轻量：只读验收｜测试人员 |
| B4b | admin｜B13 附件与图片 | AC-1 `PortraitAnnexGoldenMySqlIT`：`mac_annex` 除 `annex_size` 外逐列一致。AC-2 `LegacyImagesGoldenTest` 180 个缩略图的几何一致。AC-3 `PhpTextGoldenTest` 两版各 1604 例一致。AC-4 按 §3.0.2 ① 确认本次的一次性库已删除 | `{UI}`：`-Dtest=LegacyImagesGoldenTest,PhpTextGoldenTest`、`-Dit.test=PortraitAnnexGoldenMySqlIT` | P1 | 30m | 轻量｜测试人员 |
| B4c | admin｜B13 浏览器走查（按 P3a 的设计执行） | AC-1 按 P3a 的步骤启动环境；登录只由人手动完成；任务里不出现账号和口令的值。AC-2 改造后的脚本全部步骤通过，并列出与原 47 项的对应关系；只作历史回归，不能代替 T3。AC-3 按 P3a AC-4 清理，逐项核对 | 脚本输出、截图、清理核对 | P1、P3a 过 G1、P3b 全部完成、Q10 选 (a) | 50m（以 P3a 的估算为准） | 轻量｜测试人员 |
| B4d | 测试资源｜B13 黄金 | AC 用 `src/test/php/admin_content_golden.php` 生成两版到临时目录，与 `src/test/resources/admin/content/php{73,80}-golden.json` 按统一判据比较；跟踪文件不变 | 差异摘要 | P2、Q2 点名 B13 | 50m | 轻量｜测试人员 |
| B5a | staticgen｜B16 不连库部分 | AC-1 72 条静态地址、20 组执行判断与 PHP 一致。AC-2 D2 ③：远端没有口令（含 `enforce=1`、IPv6、错误口令）时返回 403。AC-3 回滚只删本批的文件 | `{U}` `StaticPathsGoldenTest,TimmingGoldenTest,TimmingControllerMvcTest,MakeServiceTest,InternalPageRendererTest` | 无 | 30m | 评审版：鉴权（D2 ③）｜测试人员 |
| B5b-1 | staticgen｜B16 中 `AdminCjMySqlIT` 部分（共享实例） | AC-1 `colAllRoundFromAdminAndTimmingTask`（`{A}25-b16-static-timming.md` 验证表第 141 行）：后台 `cj/col_all` 和定时任务 `cj` 各对一个节点跑一轮，输出逐字节相同（只差节点内容），各导入 3 条视频；再跑一轮时没有待采内容；没有节点时提示“获取失败”，`collect` 固定提示“该功能未在新系统中提供”，这两种情况都不写库。AC-2 按 §3.0.2 ①：取出本次创建的全部精确库名，运行后都不存在；别的新库只记录，不删除 | `{I}`，用 `-Dit.test=AdminCjMySqlIT#colAllRoundFromAdminAndTimmingTask`（Failsafe 的“类#方法”写法），加前后两次库列表 | P1 | 30m | 轻量｜测试人员 |
| B5b-2 | staticgen｜B16 中 `StaticTimmingMySqlIT`（只在 P4 独占实例上运行） | AC-1 未鉴权的请求不改任何东西。AC-2 4 个 make 任务生成的文件和动态页面逐字节相同，只改 `*_time_make` 列。AC-3 回滚后站点根目录复原，表结构不变。AC-4 按 §3.0.2 ②：按 P4 的步骤新起实例，B0 为空；运行后列表也为空；Failsafe 报告中这个类 errors=0。B0 不为空就判为隔离不成立，本次作废、重跑。AC-5 本单是这个实例的唯一清理人：结束前关闭实例、删除数据目录，用 P4 AC-4 的方法确认 | 按 P4 的步骤，加 `{I}` `StaticTimmingMySqlIT` | P4 | 40m | 轻量｜测试人员 |
| B5c | 测试资源｜B16 黄金 | AC-1 用 `src/test/php/b16_static_timming_golden.php` 在 PHP 8.0 下生成到临时目录，与 `src/test/resources/staticgen/b16-php80-golden.json` 按统一判据比较（基线只有 8.0 这一份）。AC-2 跟踪文件不变 | 差异摘要 | P2、Q2 点名 B16 | 45m | 轻量｜测试人员 |
| B6a | integration｜B23 基础 | AC-1 未登记的调用方在静态检查和运行期都失败。AC-2 跳到外部主机被拒、换主机时去掉凭证头，这两项都在测试配置的 `loopback-only` 下验证；在 OPEN 下只验证协议、凭证、跳转次数、响应大小的限制。AC-3 日志里没有假密钥、签名、token、验证码、手机号。AC-4 仓库里的密钥字段为空。AC-5 白名单子包与 09 §4.3 ④ 一一对应 | `{U}` `OutboundNetworkGuardTest,UnregisteredCallerTest,RegisteredOutboundClientTest,OutboundPolicyTest,ThirdPartyConfigTest,LogRedactorTest,RepositorySecretFieldsTest,TestOutboundGuardTest` | 无 | 30m | 评审版：出站安全｜测试人员 |
| B6b | staticgen｜B28 | AC-1 两版共 102 步一致。AC-2 `BacklinkFetcherTest` 中由测试显式构造的 `OutboundPolicy.Mode.LOOPBACK_ONLY` 下：超时、响应过大、跳转循环、跳到外部主机、连接被拒、`file://`、`ftp://`、`gopher://`、带凭证、不合法地址、外部 IP、`.invalid` 主机都返回 null。这一条不表示生产默认的 OPEN 也拒绝外部或内网主机：OPEN 下与旧版相同，不限制内网地址，只限协议、凭证、跳转次数、响应大小和证书（30 文档 §4、D-B28-10、§11 风险）。AC-3 推送令牌不进日志（`BaiduUrlPushTest`）。AC-4 按 §3.0.2 ① 确认本次的一次性库已删除 | `{UI}`：`-Dtest=BaiduUrlPushTest,BacklinkFetcherTest,UrlsendServiceTest,AdminBacklinkCheckTest`、`-Dit.test=UrlsendBacklinkGoldenMySqlIT` | P1 | 40m | 评审版：SSRF 面｜测试人员 |
| B6c | 测试资源｜B28 黄金 | AC 用 `src/test/php/urlsend_backlink_golden.php` 生成两版到临时目录，与 `src/test/resources/integration/b28/php{73,80}-golden.json` 按统一判据比较；跟踪文件不变 | 差异摘要 | P2、Q2 点名 B28 | 45m | 评审版｜测试人员 |
| B7a | openapi｜B27 | AC-1 50 步与两版 PHP 逐字节一致，另有 8 步符合 D-B27-3/4/5。AC-2 `other`/`Index`/`wechatx` 返回 404，超过 1 MiB 返回 413。AC-3 签名大写、`0e1` 形式、缺参数都不通过。AC-4 全部请求前后表的校验和不变。AC-5 按 §3.0.2 ① 确认本次的一次性库已删除 | `{UI}`：`-Dtest=WechatServiceTest`、`-Dit.test=WechatGoldenMySqlIT` | P1 | 40m | 评审版：入站签名｜测试人员 |
| B7b | 测试资源｜B27 黄金 | AC 用 `src/test/php/wechat_golden.php` 生成两版到临时目录，与 `src/test/resources/openapi/wechat/php{73,80}-golden.json` 按统一判据比较（`CreateTime` 归为时间项）；跟踪文件不变 | 差异摘要 | P2、Q2 点名 B27 | 45m | 评审版｜测试人员 |

#### C 待决项落地（只有 Q4 对应子项选了“批准行为变化”才建单；每单先过 G1）

| 编号 | 主模块｜允许改的文件（完整路径） | AC | 验证 | 依赖 | 时长 | 档位｜唯一责任人 |
|---|---|---|---|---|---|---|
| C1 D-B24-a | integration.pay｜`{J}integration/pay/WeixinPay.java` 及其测试、`{A}33-b24-payment.md` §10/§11 | AC-1 到账写库被拒（补偿完成）时，微信回调回复 FAIL。AC-2 结果不确定（连接中断）时回复 FAIL、保留认领，重发不重复加积分。AC-3 认领匹配 0 行时回复什么，待技术设计确认：33 §5.2 只论证了“另一个回调已到账”这一种成因，其他成因（例如订单状态被后台改过）不能直接当作已到账，设计过 G1 后再定稿这条 AC。AC-4 支付宝、码支付、Zhapay 三种方式的回复字节不变。AC-5 差异按 §3.0.3 登记进 §10 | `{UI}`：`-Dtest=PaymentGoldenTest,PaySafetyTest`、`-Dit.test=PaymentGoldenMySqlIT`，再跑 `{F}` | Q4-d、P1、G1；§3.0.4 | 45m + 11m | 评审版：支付｜后端开发 |
| C2 微信 openid 用户名规则 | member｜`{J}member/application/MemberAccountService.java`（`tryOpenidRegister`，`:629` 起：用户名取 openid 前 10 位，经 `LegacyText.clean` 清洗，再做 `NAME` 正则和长度检查）、`{T}member/OAuthGoldenMySqlIT.java`、`{A}34-b26-oauth.md` §7。规则如果要求改插入条件，再加 `{J}member/infrastructure/MemberWriteMapper.java`（`registerOpenid`，`:82`）和 `{W}member/MemberWriteMapper.xml`（`:164`），是否需要由 G1 设计确定 | AC-1 含 `-`/`_` 的 openid 按批准的规则注册成功。AC-2 用户名冲突时按规则处理。AC-3 8 个并发回调只注册 1 个账号。AC-4 按 §3.0.3：只有 `weixin-guest-underscore` 一例登记为差异，其余 43 例与本单开工时的基线断言一致 | `{I}` `OAuthGoldenMySqlIT`，再跑 `{F}` | Q4-j（规则由你给）、P1、G1；§3.0.4 | 45m + 11m | 评审版：账号｜后端开发 |
| C3 D-B26-6 昵称 | member｜`{J}member/application/MemberOAuthService.java`（`:136` 把 `out.nickname()` 传下去）；`{J}member/application/MemberAccountService.java`（`openidLogin` `:597`、`openidRegister` `:625`、`tryOpenidRegister` `:629` 增加昵称参数，按旧版 `htmlspecialchars(urldecode(trim()))` 处理）；`{J}member/infrastructure/MemberWriteMapper.java`（`registerOpenid` 增加 `@Param("nick")`）；`{W}member/MemberWriteMapper.xml`（`:164` 的插入增加 `user_nick_name`）；测试 `{T}member/OAuthGoldenMySqlIT.java`、`{T}site/web/SiteOAuthMvcTest.java`（基线上调用 `openidLogin`/`registerOpenid` 的只有这两个测试）；`{A}34-b26-oauth.md` §7。`{J}integration/application/OAuthOutcome.java` 不改；`mac_user INSERT` 沿用 B09 的登记，不改 `WriteRegistry` | AC-1 自动注册时写入 `user_nick_name`，取值为旧版处理后的平台昵称；昵称为空时写空串。AC-2 已绑定用户再次登录时不改昵称（`recordOpenidLogin` 不变）。AC-3 按 §3.0.3：只登记自动注册用例的 `user_nick_name` 差异；C2 也获批时，以 C2 的合入提交为基线，`weixin-guest-underscore` 同时带 C2、C3 两条差异；其余列与基线断言一致。AC-4 `ModuleBoundaryTest` 通过（site 不直接调用 member 的 infrastructure） | `{UI}`：`-Dtest=SiteOAuthMvcTest,ModuleBoundaryTest`、`-Dit.test=OAuthGoldenMySqlIT`，再跑 `{F}` | Q4-i、P1、G1；条件依赖 C2（§3.0.4） | 45m + 11m | 评审版：账号｜后端开发 |
| C4-D openid 解绑设计 | 只写文档：`docs/design/<KEY>/tech-design.md` | AC-1 定下绑定状态的读取链路：`{R}member/MemberMapper.xml#selectAccount` → `{J}member/infrastructure/MemberAccountRow.java` → 已发布类型（扩展 `{J}member/application/view/MemberProfile.java`，或新增 view 类型，二选一并说明理由）→ `MemberAccountService#profile`（`:810`）→ `{J}site/web/SiteUserController.java:382` → `{J}site/web/view/UserInfoPage.java` → `{M}jte/site/pages/userInfo.jte`；列出 `MemberProfile` 的其他使用方（`UserIndexPage`、`UserInfoFragment`、`SiteInteractionController`）受不受影响，以及 `ajax_info` 的输出会不会因此改变。AC-2 写入：在 `{J}member/infrastructure/MemberWriteMapper.java` 和 `{W}member/MemberWriteMapper.xml` 新增条件更新，只清当前会员的一列（`user_openid_qq` 或 `user_openid_weixin`），未绑定时不写库，沿用 B09 的 `mac_user UPDATE` 登记。AC-3 站点入口：新路由与现有 `user/unbind`（手机/邮箱解绑）是什么关系；游客、别人的会话、非 POST 请求都拒绝；入口状态来自视图模型；交互要不要改 `src/main/resources/static/site/member.js`（02 §7 归前端），只用 `data-*` 钩子，`SiteTemplatePolicyTest` 通过。AC-4 拆成单模块的实施单（至少 member、site 各一单，前端按需加一单），每单列出文件和 AC，控制在 60 分钟内 | G1 | Q4-k（明确放开 09 §1.1） | 60m | 评审版：新增行为｜架构师 |
| C4-impl（**待拆组**） | 由 C4-D 给出（已知涉及 C4-D AC-1～3 所列的文件） | 由 C4-D 给出 | 由 C4-D 给出 | C4-D 过 G1；§3.0.4 | 由 C4-D 给出 | 评审版｜由 C4-D 指定 |
| C5a D-B29-3 设计 | 只写文档：`docs/design/<KEY>/tech-design.md` | AC-1 定下可执行的“表 + 操作”的登记方式、参数化的写入路径、失败回滚、执行前后的表结构护栏。AC-2 把实施拆成单模块的任务，每单列出文件、AC 和 60 分钟内的边界 | G1 | Q4-l | 60m | 评审版：SQL 执行｜架构师 |
| C5b D-B29-3 实施（**待拆组**） | 已知至少涉及 common.write（`{J}common/write/WriteRegistry.java`）和 admin（`{J}admin/application/AddonSql.java`、`{J}admin/application/AddonMarketService.java`），所以至少两单 | 由 C5a 给出 | 由 C5a 给出 | C5a 过 G1；§3.0.4 | 由 C5a 给出 | 评审版｜由 C5a 指定 |
| C6-D D-B22-18 安装失败清理设计 | 只写文档：`docs/design/<KEY>/tech-design.md` | AC-1 主键的取得：`{W}install/InstallWriteMapper.xml` 的 `insertType`、`insertAdmin` 怎样返回自增键（`mac_type` 的初始数据带不带显式 id，按 `src/main/resources/install/initdata-mac_type.json` 核对），`{J}install/infrastructure/InstallWrites.java#write`（`:27–35`）的 `Result` 怎样携带已插入的主键。AC-2 删除：`{J}install/infrastructure/InstallWriteMapper.java` 和 XML 新增按主键删除的语句；`{J}common/write/WriteRegistry.java` 新增 `install / mac_type / DELETE`、`install / mac_admin / DELETE` 两项登记；`{T}architecture/WriteGuardTest.java` 的期望清单相应更新。AC-3 `{J}install/application/InstallWizard.java:256–270` 怎样在第五步中途失败后只按记录的主键删除本次插入的行，删除后可以直接重跑；安装前已有的行和其他表不变；删除本身失败时提示“需在库外重新导入 install.sql”，不再继续写。MyISAM 没有事务，要写清只删除本次插入的行。AC-4 写入方矩阵是否要改 `{A}09-roadmap.md` §5.3。AC-5 拆成实施单：至少 common.write、install 各一单 | G1 | Q4-b | 60m | 评审版：删除数据｜架构师 |
| C6-impl（**待拆组**） | 由 C6-D 给出（已知涉及 `{J}install/infrastructure/InstallWrites.java`、`{J}install/infrastructure/InstallWriteMapper.java`、`{W}install/InstallWriteMapper.xml`、`{J}install/application/InstallWizard.java`、`{J}common/write/WriteRegistry.java`、`{T}architecture/WriteGuardTest.java`、`{T}install/InstallWizardMySqlIT.java`） | 由 C6-D 给出 | 由 C6-D 给出（已知会用 `{I}` `InstallWizardMySqlIT` 和 `{F}`） | C6-D 过 G1、P1；§3.0.4 | 由 C6-D 给出 | 评审版｜由 C6-D 指定 |
| C7a MySQL 8 实例 | 运行机环境，不进仓库 | AC-1 有一个 8.0.30 或更高版本的实例，能同时覆盖去掉 `int` 显示宽度和 `utf8mb3` 这两种变化（31 文档 §12）。AC-2 IT 账号以 `MACCMS_IT_ADMIN_*` 注入，只能建、删 `maccms_it_*`。AC-3 用 `{I}` 跑 `InstallWizardMySqlIT`，得到“结构不一致”的现状失败，记录下来作为 C7b 的基线 | `{I}` 摘要 | Q4-c、人提供实例 | 30m | 轻量｜运营打包 |
| C7b MySQL 8 结构指纹 | install｜`{J}install/application/InstallSchema.java`、`src/main/resources/install/schema-fingerprint.txt`（只在需要时改） | AC-1 在 5.7 和 8.0 上都判为结构一致：`int(10)` 与 `int`、`utf8` 与 `utf8mb3` 归一。AC-2 结构真的不同时仍然拒绝，要有反例测试。AC-3 5.7 上的现有黄金不变 | 两台实例上分别跑 `{I}` `InstallWizardMySqlIT`，再跑 `{F}` | C7a、G1；§3.0.4 | 45m + 11m | 评审版：结构校验｜后端开发 |
| C8 D-B20 清空范围 | collect｜`{J}collect/application/CjRunService.java#contentDel`（`:326`）；`{J}collect/infrastructure/CjWrites.java`、`{J}collect/infrastructure/CjWriteMapper.java`、`{W}collect/CjWriteMapper.xml`（`deleteAllContents` `:35`）；`{J}collect/infrastructure/CollectAdminWrites.java`、`{J}collect/infrastructure/CollectAdminWriteMapper.java`、`{W}collect/CollectAdminWriteMapper.xml`（`deleteCjHistories` `:173`）；读取 `{J}collect/infrastructure/CjMapper.java`、`{R}collect/CjMapper.xml`（`listContentUrls`）；测试 `{T}admin/web/AdminCjMySqlIT.java` | AC-1 带 `all=1` 时只删当前节点的内容和这些内容的采集历史，其他节点的行数不变。AC-2 不带 `all` 时行为不变。AC-3 不新增写入登记（如需新增，回到 G1） | `{I}` `AdminCjMySqlIT`，再跑 `{F}` | Q4-n、P1、G1 | 45m + 11m | 评审版：删除数据｜后端开发 |
| C9 D-B16-1 静态链接设计 | 只写文档：`docs/design/<KEY>/tech-design.md` | AC 列出所有链接生成点，并把实施拆成每单不超过 60 分钟的单模块任务；实施部分为待拆组 | G1 | Q4-o | 60m | 评审版：牵涉 B05 的全部链接｜架构师 |
| C10 支付宝表单自动提交（D-B24-6） | site 静态资源｜`src/main/resources/static/site/member.js` | AC-1 `form[data-pay-autosubmit]` 在页面加载后自动提交。AC-2 不加内联脚本，`SiteTemplatePolicyTest`、`SitePaymentMvcTest` 通过 | `{U}` `SiteTemplatePolicyTest,SitePaymentMvcTest`，加一次本机页面检查（不登录：用已有 MVC 测试页面，或由 G1 设计指定无需登录的入口） | Q4-e；§3.0.4 | 30m | 评审版：支付页｜前端开发 |

#### D 发布条件（不阻塞开发）

| 编号 | 范围 | AC | 验证 | 依赖 | 时长 | 档位｜责任人 |
|---|---|---|---|---|---|---|
| D1 版本探针 | common｜`pom.xml`（build-info 或 git 信息插件）、`src/main/resources/application.yml`（actuator 配置） | AC-1 有一个端点返回构建时的提交号，与 `git rev-parse HEAD` 相同。AC-2 不暴露环境变量和配置。AC-3 有测试 | `{F}`，加 curl 本机端点 | G1 | 40m + 11m | 评审版：新插件、新端点｜后端开发 |
| D2 CI | `.github/workflows/` | AC-1 PR 和推送 main 时，在 Linux、JDK 21、仓库根目录跑 `./mvnw -B verify`。AC-2 LF 检出也能通过。AC-3 可选的 IT 任务用 MySQL 5.7 服务和一次性库，仓库里没有凭据 | 一次 Actions 运行的链接 | Q7 | 45m | 轻量：不碰业务代码｜运营打包 |
| D3 测试骨架 | `tests/api/`、`tests/e2e/`（只放 conftest，不写用例） | AC-1 支持 `--base-url`，读取 `MULTICA.md` §5 中的变量名；e2e 的 conftest 只支持 `APP_UI_STORAGE_STATE`，不读 `APP_UI_USERNAME/PASSWORD`（§0 为“否”）。AC-2 本机 `pytest --collect-only` 能通过。对测试环境的冒烟不在本单：要等测试环境登记、D1 完成、G2.5 通过之后，在 T3 执行 | collect-only 的输出 | 无 | 30m | 轻量｜测试人员 |

人工事项，不建智能体任务：测试环境登记、主干保护、部署方式（见 Q7）。

### 4. B21 拆分与前置条件

#### 4.1 只读对齐（15 单：E1a～E1h 8 单、E2a～E2g 7 单）

E1a～E1g、E2a～E2g 按旧控制器的 `public function` 数量预先拆分，每单不超过 37 个；计数取自 `@a57e9cb2` 上 `php/application/<模块>/controller/<控制器>.php` 的 `grep -c "public function"`。E1h（安装向导）按功能逐项对齐，不按方法计数。15 单合计覆盖 index 23 个、api 6 个、admin 39 个控制器和 install 的 `Index.php`。各单档位都是轻量（只读），唯一责任人都是测试人员。

| 编号 | 范围（模块：控制器(方法数)） | 合计 | 产物 | 时长 |
|---|---|---|---|---|
| E1a | index：Vod(17) Art(10) Topic(7) | 34 | `docs/test/<KEY>/parity-index-a.md` | 55m |
| E1b | index：Actor(10) Role(9) Plot(7) Website(10) | 36 | `docs/test/<KEY>/parity-index-b.md` | 60m |
| E1c | index：Index(2) Label(2) Map(2) Rss(8) Search(2) Help(2) MyError(1) Base(2) Qrcode(2) Verify(3) Payment(2) | 28 | `docs/test/<KEY>/parity-index-c.md` | 45m |
| E1d | index：Comment(6) Gbook(5) Note(2) Ajax(11) | 24 | `docs/test/<KEY>/parity-index-d.md` | 40m |
| E1e | index：User 第 1～22 个方法（按文件中的顺序） | 22 | `docs/test/<KEY>/parity-index-user1.md` | 40m |
| E1f | index：User 第 23～43 个方法 | 21 | `docs/test/<KEY>/parity-index-user2.md` | 40m |
| E1g | api：Base(1) Index(2) Provide(11) Receive(8) Timming(2) Wechat(2) | 26 | `docs/test/<KEY>/parity-api.md` | 45m |
| E1h | install：`php/application/install/controller/Index.php`（按功能，见下） | 按功能 | `docs/test/<KEY>/parity-install.md` | 45m |
| E2a | admin：Vod(8) Art(7) Topic(5) Actor(5) Role(5) Website(7) | 37 | `docs/test/<KEY>/parity-admin-a.md` | 60m |
| E2b | admin：Type(8) Link(5) Comment(5) Gbook(5) Annex(7) Images(5) | 35 | `docs/test/<KEY>/parity-admin-b.md` | 60m |
| E2c | admin：User(6) Group(5) Card(4) Cash(4) Order(3) Plog(3) Ulog(3) Visit(3) Upload(4) | 35 | `docs/test/<KEY>/parity-admin-c.md` | 60m |
| E2d | admin：System(17) Index(12) Admin(5) Base(3) | 37 | `docs/test/<KEY>/parity-admin-d.md` | 60m |
| E2e | admin：Addon(11) Database(10) Template(6) Domain(4) Safety(0) Update(0) | 31 | `docs/test/<KEY>/parity-admin-e.md` | 55m |
| E2f | admin：Make(11) Vodplayer(7) Vodserver(5) Voddowner(5) Timming(5) Urlsend(4) | 37 | `docs/test/<KEY>/parity-admin-f.md` | 60m |
| E2g | admin：Collect(16) Cj(15) | 31 | `docs/test/<KEY>/parity-admin-g.md` | 55m |

通用 AC（E1a～E1g、E2a～E2g）：
- AC-1 本单内每个控制器的 `public function` 各占一行，行数等于上表的计数。
- AC-2 每行只能是以下之一：✅（附 Java 路由和测试类）、“用户决定排除”（附决定编号）、“按硬性要求 2 排除”（附 `{A}09-roadmap.md` 的行号）、“旧版死链或空类”（附旧文件的行号）、缺口（附新开的 bug-fix 单编号）。不允许出现“未判定”。
- AC-3 不改 `src/main`，不跑测试。报告按 §3.0.5 写明候选基线和验收提交。

E1h 的 AC：
- AC-1 按功能逐项列行：构造器 `:16`（只放行安装入口，否则 403）；`index($step)` `:26` 的 default 分支（语言选择）、2、3、4、5 分支及 `install_error` 拦截；`step2` `:83`（环境检测，含 `checkNnv` `:278`、`checkDir` `:305`、`checkFunc` `:343`）；`step3` `:97`；`step4` `:109`（连接测试、`mkDatabase` `:375`）；`step5` `:177`（建库、导入 `install.sql`、`initdata`、首个管理员、`maccms.php`、安装锁）；已安装时拒绝。对照 `{A}31-b22-install-wizard.md` §3、`{A}09-roadmap.md` B22 行（第 403 行）。
- AC-2 每行只能是：✅（附 Java 位置，如 `{J}install/web/InstallEndpoint.java`、`{J}install/application/InstallWizard.java`，以及测试 `{T}install/InstallWizardGoldenTest.java`、`{T}install/InstallWizardMySqlIT.java`、`{T}install/InstallModeTest.java`）；“按硬性要求 2 排除”（建库建表、`cover`，附 `{A}09-roadmap.md` 第 52 行）；或缺口（附 bug-fix 单号）。
- AC-3 不改 `src/main`，不跑测试；报告按 §3.0.5 写明候选基线和验收提交。

#### 4.2 其余 B21 任务（保留 AC 的部分）

| 编号 | 主模块｜范围 | AC／完成判据 | 产物 | 前置条件 | 时长 | 档位｜唯一责任人 |
|---|---|---|---|---|---|---|
| E3 | 测试｜`{A}09-roadmap.md` §5.5 ①～⑥ 六项安全差异复验（按护栏类处理，在 `$B21_FINAL` 上执行，§3.0.5） | AC-1 每项写出对应的测试类。AC-2 只用 `{U}`/`{I}`/`{UI}` 跑这些类，贴出计数摘要；验收提交等于 `$B21_FINAL`。AC-3 找不到测试的项记为缺口。AC-4 用到 IT 时按 §3.0.2 核对（不打印库名的类只在 P4 实例上运行，本单为该实例的唯一清理人） | `docs/test/<KEY>/security-diff.md` | P1；用到不打印库名的 IT 时加 P4；E8a 已宣布 `$B21_FINAL` | 45m | 评审版：安全｜测试人员 |
| E4a 快照、diff 与恢复的规模化设计 | 只写文档，对应 `{A}12-write-infrastructure.md` §6“B21 扩展”（第 165 行） | AC-1 快照和 diff 按主键分段，落到本地文件，内存占用有上界。AC-2 **有界的分段恢复**：基线 `{T}writedrill/DmlRestore.java#plan`（`:41`）接收两份内存快照，把全部语句累积到 `List`（`:72`）。设计要给出流式的替代方式：按表、按主键段，由“目标快照文件段”和“当前库的段”生成语句并逐批执行，内存与批大小有上界；语句顺序沿用 `plan` 的“删除 → 更新 → 插入”。AC-3 故障与重跑：执行到任一批失败就中止，记录表名和主键段位置；重跑时用当前库与目标快照重新算 diff，已恢复的部分 diff 为空，所以可以安全重跑；资金类表按 13 §7.3 由人确认。AC-4 规模上界按 Q9 给出（每表行数、总大小、时间预算），并写明超过上界时怎么办。AC-5 `DmlRestore#apply` 和新恢复入口**都只接受 `maccms_it_*`**（`requireDisposable`，`:98`），不放宽。AC-6 交接接口：H 里快照文件的格式和 manifest 校验方式；“库对库”diff 入口（E5c 依赖它）；离线“文件对文件”diff 做不做。AC-7 验证落点：新增一个 IT，在一次性库上给至少一张表造出超过 `TableSnapshot.MAX_ROWS`（200 000）行的合成数据，跑通“快照 → 写入 → 分段恢复 → diff 为空 → 结构指纹不变”，并测一次中途失败后重跑；`{T}writedrill/WriteInfrastructureMySqlIT.java`、`WriteDrillToolsTest.java` 对旧 API 的回归保留不变。AC-8 只动 `{T}writedrill/`。AC-9 把 E4b 拆成单模块、每单 60 分钟以内的任务，至少包括分段快照、分段 diff、分段恢复、规模 IT 四单 | 设计文档 | 无（规模上界取决于 Q9） | 60m | 评审版｜架构师 |
| E4b 测试侧 writedrill 实施（**待拆组**） | 由 E4a 给出 | 由 E4a 给出 | 由 E4a 给出 | E4a 过 G1 | 由 E4a 给出 | 评审版｜后端开发 |
| E7 手机模板与 `{:lang()}` 对齐（**待拆组**） | 等 D9-a 的材料到了再拆 | — | — | Q5-a | — | 待定 |
| E9a 全量护栏 | 测试，在 `$B21_FINAL` 上执行（§3.0.5） | AC-1 护栏类：用 `{U}` 跑 `ModuleBoundaryTest,OutboundNetworkGuardTest,SchemaGuardTest,SqlEntryGuardTest,WriteGuardTest,SafeExceptionLogReuseTest,ReadOnlyGuardrailsTest,SiteGuardrailsTest,SiteTemplatePolicyTest,InstallTemplatePolicyTest,TestOutboundGuardTest,RepositorySecretFieldsTest`，全部通过。AC-2 跑 `{F}`，0 失败，记录计数。AC-3 在 P4 独占实例上跑全量 `./mvnw -B verify -Pmysql-it`，0 失败，按 §3.0.2 ② 核对（运行后列表为空），实例由本单销毁。AC-4 报告的验收提交等于 `$B21_FINAL`。首次运行如果超过 50 分钟，按 IT 包分组拆成子单，子单都在 `$B21_FINAL` 上跑 | `docs/test/<KEY>/guards.md` | P1、P4、E8a 已宣布 `$B21_FINAL` | 60m（可能拆单） | 评审版｜测试人员 |

**E4a 的术语说明**（E4a 条文本身不变）：AC-6 中的“交接包 H”和“E5c”指切换演练的交接包与独立复核。E5a～E5d 本版已改为待拆组（§4.3），H 的具体内容由 E5 的设计单决定。E4a 只按 AC-6 的字面交付三样接口——快照文件格式与 manifest 校验方式、“库对库”diff 入口、是否做离线“文件对文件”diff 的结论——不以 E5 的任何旧条款为前置。

#### 4.3 待拆组：切换演练、性能冒烟、出站应急切换

以下 7 项都是**待拆组**：本版不写验收条款、验证承诺或清理执行方案，只写现状、缺口和要先设计的问题。接近上线时单独开设计单，过 G1 后再拆；拆出的执行子单都在 `$B21_FINAL` 上执行（§3.0.5）。它们都是 E8b 的收口条件；延期细化不表示已满足或已排除。

**E5a 切换演练脚本与清单**
- 现状：`{A}13-cutover-plan.md` §3～7 已写好停写入口、切换步骤、对账项（§5a 样本地址集，§5b 表结构、写入范围、积分、订单与卡密、计数、配置文件、人工对账项、错误率八项）、回滚门槛和整体切回（§7.1～7.5）；§9“演练记录”为空。测试侧工具只有 `{T}writedrill/` 下的 `DatabaseSnapshot.java`、`SnapshotDiff.java`、`DmlRestore.java`、`TableSnapshot.java`（内存快照，每表上限 200 000 行，恢复只接受 `maccms_it_*`），规模化扩展由 E4a 设计。仓库里没有 `ops/`，也没有任何演练脚本或清单。
- 缺口：没有按 13 §3～7 逐步可执行的脚本与清单；没有生产禁入的机械判据；没有交接包（数据、文件、日志）的定义；没有错误率的计算口径；没有演练资源清单、唯一清理人和失败退出处置。
- 要先设计的问题：① 生产禁入怎样判定（库名、主机、写入路径、配置来源），以及它与演练需要的本机 PHP（php-cgi）、nginx、Java 进程、模拟服务怎样同时成立。② 交接包的内容：数据导出时点与方式（13 T+3、§7.2、§7.3 之后）、文件快照范围（13 T+3 所列的站点副本目录：`application/extra/*.php`、`application/route.php`、`static/js/playerconfig.js`、`template/`、`addons/`、`upload/`、静态 HTML 目录）、日志摘录、校验方式。③ 13 §5b 错误率三项的口径：5xx 比例的分子、分母和基线怎样取；`MULTICA.md` §0 不允许脚本填表登录，登录失败率在没有人工登录样本时怎样记录；支付回调失败数用什么模拟网关和测试密钥。④ 演练资源（站点副本、`php/vendor/`、php-cgi、私有 nginx、Java 进程、模拟服务、`runtime/` 缓存、副本库、交接包）的创建、记录、唯一清理人和失败退出时的清理。⑤ 按 Q9 的规模与时间预算怎样拆成 60 分钟内的子单。⑥ 脚本落在哪个目录、由谁维护。

**E5b 演练执行**
- 现状：从没有在任何一次性副本上执行过整体切换演练；13 §4 的各步时长写着“由 B21 演练填写”。
- 缺口：每步耗时、静止确认结果、快照与 diff 的耗时和大小、13 §5b 各项（含配置文件和错误率）的对账结果、切回后的 diff、§7.4 文件恢复与缓存清理的结果，都没有记录。
- 要先设计的问题：① 执行依赖 E4b 和 E5a 的哪些产物。② 演练数据来源和规模（Q9）。③ 执行环境（同一运行机上要起哪些进程、端口、目录）以及与其他任务的隔离。④ 中途失败怎样中止、记录和重跑。⑤ 结束时哪些资源由本单删除、哪些交给复核方。⑥ 记录里怎样做到不含凭据和数据内容。

**E5c 演练独立复核**
- 现状：没有独立复核的方法；基线 `DatabaseSnapshot.take` 加 `SnapshotDiff.between` 只能在每表不超过 200 000 行时直接使用。
- 缺口：交接完整性的核对、数据重建与 diff 复算、文件清单复算、由日志推出的对账项复算，都没有定义。
- 要先设计的问题：① 复核方是否必须与执行方在同一台运行机上，交接包怎样不经仓库或评论传递。② 怎样从导出物重建数据库再复算 diff（依赖 E4a 的“库对库”入口）。③ 文件与配置文件（逐字节比较、`php -l`）怎样复算。④ 交接包、重建库、解压目录由谁、在什么时点删除，删除结果怎样落到报告的最终提交上（评审建议）。⑤ 复核方或执行方被取消时，清理责任怎样转交。

**E5d 填写切换方案 §9**
- 现状：`{A}13-cutover-plan.md` §9“演练记录（B21 填写）”为空。
- 缺口：§9 的每一行（步骤、耗时、对账结果——含配置文件和错误率三项、回滚后 diff）都没有内容。
- 要先设计的问题：① §9 的行项与 E5b/E5c、E10 报告的对应关系。② 引用哪个提交上的报告（必须是 `$B21_FINAL` 上的执行结果）。③ 只改 §9、不改 §1～§8 的约束是否足够。

**E9b 性能冒烟设计**
- 现状：路线图 B21 行（`{A}09-roadmap.md:411`）要求“全量护栏与性能冒烟”。现有工具见 §2.1：`SearchPerf.java`（加 `run-all.sh`、`summarize.py`）、`B05Perf.java`、`b06_perf_run.py`，都不在 `./mvnw verify` 中；`SearchPerf` 用 `maccms_perf_*`，另两个用 `maccms_it_*`；`run-all.sh` 全流程约 2 小时（`src/test/perf/README.md:25`）。三份报告 `{A}09-content-search-filter-perf-report.md`、`{A}15-b05-perf-report.md`、`{A}16-b06-perf-report.md` 分别测的是 `ce531c1`、`6f5e2af` 和 B06 当时的代码，都是本机一次运行，机器有时不空闲。
- 缺口：现有工具测的是内容搜索、筛选和 Provide 等特定语句与接口，没有覆盖 13 §5a 样本地址集的整站冒烟；没有与同机旧 PHP 的对比；没有通过口径（Q11）；没有在 `$B21_FINAL` 上的测量；全流程 2 小时超出单次运行时限。
- 要先设计的问题：① 冒烟对象：13 §5a 的样本地址集里哪些纳入；会员中心、后台页需要登录态，按 `MULTICA.md` §0 只能人工登录（Q10），还是不测。② 现有三套工具哪些部分可以复用（建库、合成数据、启动 jar、按 PID 结束、汇总），哪些要新写；是否需要新依赖。③ 库名约束：`maccms_perf_*` 不在 §3.0.2 的 IT 清理判据内，共享实例上怎样核对和清理；三套工具是否统一前缀。④ `SearchPerf` 会只读 `maccms_dev` 的元数据（README 第 10 行），在 B21 的运行机上是否允许。⑤ 同机旧 PHP 基线需要 php-cgi、nginx 和 `php/vendor/`（P2），环境与清理怎样定义。⑥ 指标（例如 p50/p95、错误率、内存峰值）与通过口径（Q11）；机器是否必须空闲。⑦ 怎样拆成每单 60 分钟以内、都在 `$B21_FINAL` 上执行的子单。

**E9c 性能冒烟实施**
- 现状：没有任何针对 B21 性能冒烟的脚本或报告；09/15/16 三份报告不是 `$B21_FINAL` 的证据。
- 缺口：冒烟脚本、执行记录和结论都没有。
- 要先设计的问题：全部由 E9b 决定，包括改哪些文件、执行顺序、耗时、库和进程的清理人。

**E10 出站应急 `loopback-only` 切换演练**
- 现状：`{J}integration/http/OutboundPolicy.java` 读取 `maccms.integration.outbound.mode`（环境变量 `MACCMS_INTEGRATION_OUTBOUND_MODE`，`application.yml` 默认 `open`），只接受 `open` 或 `loopback-only`，写错时启动失败；启动时读一次，不重启不生效（`{A}26-b23-integration-foundation.md` §12 第 1 条）。`loopback-only` 下经 `integration` 出站客户端发往非回环地址的请求以 `TARGET_REJECTED` 失败，入站接口不受影响（§12 第 2 条）。但**策略拒绝不单独记日志**：`OutboundPolicy.check` 直接抛 `OutboundException`，出站客户端不为它写日志，是否记录由调用方决定（`26-b23-integration-foundation.md:162`）；例如 `{J}integration/urlsend/BaiduUrlPush.java:82–83` 捕获 `OutboundException` 后直接返回失败结果，不记日志。单元层面的覆盖在 `{T}integration/http/OutboundPolicyTest.java`、`{T}integration/backlink/BacklinkFetcherTest.java` 等（B6a、B6b），都是测试内显式构造的策略。`09-roadmap.md:508` 和 26 §12 第 4 条把“切到 `loopback-only` 后出站全部被拒、切回后恢复”列为 B21 演练检查项。仓库里没有对应的演练脚本或报告。
- 缺口：没有在运行中的实例上验证“切换 → 出站被拒 → 切回 → 恢复”的步骤和记录；现有代码没有可直接观测“请求在发出前被拒”的日志；没有“全程没有对外连接”的客观证据来源。
- 要先设计的问题：① “只连本机”的限制下怎样验证：`loopback-only` 放行回环地址，所以回环上的模拟服务在两种模式下都会收到请求，无法区分；要显示“被拒”，目标必须是非回环地址。可选方向（只列，不定）：用本机网卡上的非回环地址起模拟服务（是否算“本机”、是否被允许，需确认人或运维确认）；用保留的不可路由地址或 `.invalid` 主机作目标（发出前就被拒，但“没发出”本身要另外证明）。② 怎样客观证明没有对外连接：候选来源包括操作系统层面的出站阻断与日志（防火墙规则限定 Java 进程、抓包或连接计数）、模拟服务的请求计数、调用方返回的失败结果；`TARGET_REJECTED` 不在任何日志里，不能当证据。若要新增日志或测试钩子，属于代码变更，需另走设计和确认。③ 触发哪个出站功能：要选不需要后台脚本登录的入口（`MULTICA.md` §0），例如定时任务触发的推送，具体由设计确认。④ 入站接口（如 B27 公众号）照常的验证方式。⑤ 取值写错时启动失败的验证方式。⑥ 本单启动的实例、模拟服务、库的唯一清理人与失败退出处置。

#### 4.4 B21 冻结与收口

| 编号 | 范围 | AC／完成判据 | 产物 | 前置条件 | 时长 | 档位｜唯一责任人 |
|---|---|---|---|---|---|---|
| E8a 宣布冻结 | 在 Issue 中宣布 `$B21_FINAL` | AC-1 `$B21_FINAL` 为宣布时 `origin/main` 的完整 40 位提交号，写明宣布时间。AC-2 列出冻结前已完成的单及其报告路径与验收提交，以及冻结后要在 `$B21_FINAL` 上执行的单：E3、E9a，以及 E5a～E5d、E9b～E9c、E10 拆出的执行子单。AC-3 E8b 完成之前 main 有新提交时，重新宣布，前一次冻结后产生的报告全部作废（§3.0.5） | Issue 评论 | E1a～E1h 8 单、E2a～E2g 7 单（共 15 单）已完成；获批的 C 单已合入；Q1/Q2 要求的 B 系列已完成或已排期；E5a～E5d、E9b～E9c、E10 的设计单已过 G1，其脚本类子单已合入（或确认人明示排除）；E6/E6-doc 已完成或 Q6 已决定；E7 已完成或已明示排除 | 15m | 轻量｜Leader（智能调度） |
| E8b B21 最终收口 | 测试｜汇总 | AC-0 记录 `$B21_FINAL`；执行时 `git rev-parse origin/main` 仍等于它，否则本单作废，等 E8a 重新宣布。AC-1 E1a～E1h 8 单、E2a～E2g 7 单（共 15 单）的报告都在，缺口已关闭或已决定排除；逐单列出报告路径和验收提交。AC-2 冻结后执行的报告（E3、E9a、E5/E9/E10 的执行子单）验收提交都等于 `$B21_FINAL`；任一份不等即判为未完成，不用其他方式判断旧报告仍有效。AC-3 E3 在 `$B21_FINAL` 上没有未关闭的缺口。AC-4 E9a 在 `$B21_FINAL` 上通过。AC-5 待拆组 E5a～E5d、E9b～E9c、E10：每项都已设计、过 G1、拆单并在 `$B21_FINAL` 上完成，或由确认人明示排除；只要有一项仍未细化或未完成，本单结论为“未完成”，并列为上线窗口的未满足项。AC-6 每类 E6 和对应的 E6-doc 都已完成，或 Q6 对该类另有明示决定。AC-7 E7 已完成或已明示排除。AC-8 Q1/Q2 要求的 B 系列已完成，逐份列出验收提交；B4c 只在 Q10 选 (a) 时要求，否则记为“按 `MULTICA.md` §0 未执行”。AC-9 列出全部已登记的差异（§3.0.3）及其批准来源 | `docs/test/<KEY>/b21-summary.md` | E8a；E3、E9a 及 E5/E9/E10 的执行子单都已在 `$B21_FINAL` 上完成 | 45m | 评审版｜测试人员 |
| （人工）上线窗口 | `{A}13-cutover-plan.md` §2 | 由人逐项确认：E8b 完成；D9 已补齐（Q5）；生产库 `sql_mode` 等参数已核对（DBA）；两个数据库账号已建好；部署包、环境变量、nginx 切换和回切配置已在预发验证（依赖 Q7 的测试环境、D1、D2）；你批准窗口 | — | E8b 和左列各项 | — | 人 |

依赖方向：E8a → {E3、E9a、E5/E9/E10 执行子单} → E8b。E9a 和 E3 只等 E8a 宣布，不等 E8b；E8b 等它们完成。没有循环。

#### 4.5 清理责任（每个对象只有一名清理人，“谁建谁删”）

| 对象 | 创建者 | 唯一清理人 | 何时清理 | 证据 |
|---|---|---|---|---|
| P4 独占实例（数据目录和端口） | 使用它的那一单（B5b-2、E3、E9a） | 同一单 | 该单结束之前 | P4 AC-4 的方法 |
| B4c 的 jar、nginx、临时目录、登录态文件、库 | B4c | B4c | B4c 结束之前（失败退出时同样清理） | P3a AC-4 的方法 |
| 各单 IT 自建的 `maccms_it_*` | 运行该 IT 的单 | 同一单 | 该单结束之前 | §3.0.2 |

E5a～E5d、E9b～E9c、E10 所需资源的清理人与清理方法，是这些待拆组“要先设计的问题”的一部分（§4.3），本版不预先指定。某张单被取消时，由 Leader 在取消时把清理责任明确转给一张具体的单，并写进那张单的 AC。

#### 4.6 E6 外部联调（每单只有满足以下全部条件才能开始；拿到账号本身不等于授权执行）

统一前置条件：
- ⓪ 发起人已确认最终清单，且其中包含本单。
- ① Q6 已提供对应的沙箱或测试账号。
- ② 你书面批准了本单的联调范围：接口、场景，以及“不使用真实资金或真实用户”。
- ③ 有隔离的测试环境：本机实例或登记过的测试环境，使用一次性库 `maccms_it_*`，不连接生产库和生产配置。
- ④ 配置就绪：凭据只通过环境变量或运行机私有配置注入；出站模式改为 `open` 只限这个实例。
- ⑤ 回调类（支付、公众号、第三方登录）还需要平台能访问到的回调地址，由测试环境或人提供。

统一产物：`docs/test/<KEY>/e6-<类>.md`（脱敏，只写字段名和差异，不贴报文）。E6 各单**不改批次文档**，批次文档的“待联调”结论由下面的 E6-doc 单汇总。发现需要改代码的差异，另开 bug-fix 或评审版单。

| 编号 | 主模块｜联调对象 | AC／完成判据 | 上游 | 额外前置 | 时长 | 档位｜唯一责任人 |
|---|---|---|---|---|---|---|
| E6-pay-alipay | integration.pay｜支付宝即时到账沙箱 | AC-1 下单表单、同步返回、异步回调走通，到账一次。AC-2 真实回调的字段与模拟服务的差异逐项列出。AC-3 记下回调的重试间隔和次数 | `{A}33-b24-payment.md` §3.1、§9 | ⑤ | 45m | 评审版：资金｜后端开发 |
| E6-pay-weixin | integration.pay｜微信 NATIVE 沙箱 | AC-1 统一下单拿到 code_url，回调到账一次。AC-2 核对 `total_fee` 的单位和字段，作为 D-B24-b 的输入 | `{A}33-b24-payment.md` §3.4、§11 | ⑤ | 45m | 评审版：资金｜后端开发 |
| E6-pay-gateway | integration.pay｜码支付、Zhapay | AC-1 先确认网关当前是否可用；不可用的话只记录结论，本单结束。AC-2 可用的话核对跳转参数和回调验签 | `{A}33-b24-payment.md` §3.2、§3.3、§9 | ⑤ | 45m | 评审版：资金｜后端开发 |
| E6-sms-aliyun | integration.sms｜阿里云短信 | AC-1 真实返回字段和错误提示与旧代码读取的字段一致。AC-2 确认是否改用 https 端点（只改部署参数） | `{A}32-b25-sms-mail.md` §10 | 测试签名和模板 | 40m | 评审版：验证码通道｜后端开发 |
| E6-sms-tencent | integration.sms｜腾讯云短信 | AC-1 v5 旧接口是否仍可用。AC-2 返回字段与旧代码读取的一致 | `{A}32-b25-sms-mail.md` §10 | 测试签名和模板 | 40m | 评审版｜后端开发 |
| E6-mail | integration.mail｜测试 SMTP 邮箱 | AC 465 和 587 两种方式的 TLS 与认证都能通过，信件格式与 32 §4.2 一致 | `{A}32-b25-sms-mail.md` §4.2、§10 | 测试邮箱 | 40m | 评审版｜后端开发 |
| E6-oauth-qq | integration.oauth｜QQ 互联测试应用 | AC-1 换令牌（POST、`fmt=json`）、`/me`、用户信息的字段与实现一致。AC-2 回调域名的登记方式 | `{A}34-b26-oauth.md` §9 | ⑤，回调域名已登记 | 45m | 评审版：账号｜后端开发 |
| E6-oauth-weixin | integration.oauth｜微信开放平台测试应用 | AC-1 `qrconnect` 扫码流程和 `sns/userinfo` 的字段一致。AC-2 如果遇到含 `-`/`_` 的 openid，记为 C2 的输入 | `{A}34-b26-oauth.md` §9 | ⑤，回调域名已登记 | 45m | 评审版：账号｜后端开发 |
| E6-wechat | openapi｜公众号测试号 | AC-1 接入验证能通过（含 D-B27-2 的字典序）。AC-2 关注回复、关键词回复、搜索回复在微信客户端里显示正常 | `{A}29-b27-wechat.md` 第 113 行 | ⑤ | 40m | 轻量：只读接口｜后端开发 |
| E6-urlsend | integration.urlsend｜百度站长测试站点 | AC-1 普通推送和快速收录的真实回复字段（`success`、`remain` 等）与旧代码读取的一致。AC-2 记录令牌错误、站点未验证时的 `message`。AC-3 是否改用 https 的结论 | `{A}30-b28-urlsend-backlink.md` §10 | 测试站点和令牌 | 40m | 轻量：只推送｜后端开发 |
| E6-addonmarket | integration.addonmarket｜插件市场测试源 | AC-1 测试源的包格式能否被 B29 的校验接受；不接受时只记录差异，不改校验。AC-2 下载、安装、升级在测试站点副本上走通，整包校验照旧 | `{A}35-b29-addon-market.md` 第 128 行（待联调） | 测试源地址 | 45m | 评审版：代码与 SQL 落地｜后端开发 |

批次文档汇总单（各单只改所列文件，把对应 E6 报告的结论追加到“待联调”一节）：

| 编号 | 只改 | 汇总哪些单 | 前置 | 时长 | 责任人 |
|---|---|---|---|---|---|
| E6-doc-33 | `{A}33-b24-payment.md` | E6-pay-alipay、E6-pay-weixin、E6-pay-gateway | 这三单都已结束或被 Q6 排除；C1 若获批，排在它合入之后 | 15m | 架构师 |
| E6-doc-32 | `{A}32-b25-sms-mail.md` | E6-sms-aliyun、E6-sms-tencent、E6-mail | 这三单都已结束或被 Q6 排除 | 15m | 架构师 |
| E6-doc-34 | `{A}34-b26-oauth.md` | E6-oauth-qq、E6-oauth-weixin | 这两单都已结束或被 Q6 排除；排在 §3.0.4 中这个文件的其他单之后 | 15m | 架构师 |
| E6-doc-29 | `{A}29-b27-wechat.md` | E6-wechat | E6-wechat 已结束或被 Q6 排除 | 15m | 架构师 |
| E6-doc-30 | `{A}30-b28-urlsend-backlink.md` | E6-urlsend | E6-urlsend 已结束或被 Q6 排除 | 15m | 架构师 |
| E6-doc-35 | `{A}35-b29-addon-market.md` | E6-addonmarket | E6-addonmarket 已结束或被 Q6 排除；排在 C5b 之后（§3.0.4） | 15m | 架构师 |

#### 4.7 执行顺序（全部以发起人确认最终清单为前提；没有确认的单不建、不开工）

- **没有技术前置**（建单后即可开始，彼此可以并行）：A2、A3、D3、E1a～E1h（8 单）、E2a～E2g（7 单）、E4a、P3a（其 G1 需要 P1 的环境描述）、D1（先出设计、过 G1）；C4-D、C5a、C6-D、C9 只在对应的 Q4 子项获批后建单。
- **等人提供资源**：P1（有建库、删库权限的账号）；P2（P1，加上 `php/vendor/` 的备份）；C7a（MySQL 8 实例）；E6 各单（§4.6 的 ⓪～⑤）；B4c（Q10）。
- **等前序任务**：P3b ← P3a 过 G1；P4 ← P1；凡是 AC 写了“本次的一次性库已删除”的 B、C 各单 ← P1；B5b-2 ← P4；B 系列黄金重生成 ← P2；C4-impl、C5b、C6-impl ← 各自的设计过 G1；E4b ← E4a 过 G1。
- **按拍板决定是否列入**：A1（Q1、Q2）、A4（Q4-k）、B 系列（Q1、Q2）、C 系列（Q4 对应子项，再过 G1）、D2（Q7）。同文件串行按 §3.0.4，只在相关的单都获批时成立；可选的单没有获批，不会卡住其他单。
- **待拆组**：E5a～E5d、E9b～E9c、E10 接近上线时单独开设计单；它们与 E4a/E4b、P2、Q9、Q10、Q11 的关系由设计单确定。
- **冻结与收口**：（15 单只读对齐、获批的 C 单、B 系列、E6/E6-doc、E7、待拆组的设计与脚本子单）→ E8a 宣布 `$B21_FINAL` → 在 `$B21_FINAL` 上执行 E3、E9a 和待拆组的执行子单 → E8b →（人工）上线窗口。main 在 E8b 完成前变化时，回到 E8a。

### 5. 需要你拍板的问题

确认人以主干 `MULTICA.md` §0 登记的成员为准。本清单只记录你的选择，不替你接受风险；不授权 revert，也不授权带着未完成的联调或演练上线。下面所有的“可选”都只是选项，**不表示已经有人确认**。Q1～Q11 都未决定。

1. **Q1 B24/B26/B29 以线性提交进入 main，没有验收**：可选 (a) 保持现状，按 B1～B3 补验收；(b) 你明确要求 revert、退回候选分支后重新走合并流程（另开单）；(c) 补充材料：如果旧工作区的 MACC-47/49/52 有验收结论，请提供原文。
2. **Q2 B13/B16/B23/B27/B28 只有同作者复核**：可选 (a) 接受现状，不建 B4～B7；(b) 全部或部分补验收（请点名批次），以及是否同时要黄金重生成（B4d、B5c、B6c、B7b）。
3. **Q3 旧本地仓库里有 35 个未推送的智能体分支**：可选 (a) 宣布作废；(b) 在那台机器上逐一核对（人工操作）。
4. **Q4 是否承接原“队长”留下的待决项？** 承接的话请逐项选择。“保持现状”就是维持 main 上现在的行为，不建对应的 C 单。

| 子项 | main 现状 | 保持现状 | 批准行为变化 | 需补材料 |
|---|---|---|---|---|
| a D-B22-1/3 安装模式和部署配置文件的形式 | 按 31 文档自定的方式实现 | 可选 | 可选：指出要改哪里 | — |
| b D-B22-18 安装失败后清理 | 不清理，在库外重新导入 | 可选 | 可选 → C6-D，再拆 C6-impl | — |
| c MySQL 8 | 只支持 5.7 | 可选 | 可选 → C7a、C7b | MySQL 8 实例 |
| d D-B24-a 微信回调写库失败时的回复 | 照旧回 SUCCESS | 可选 | 可选 → C1 | — |
| e D-B24-6 支付宝自动提交 | 要点一次按钮 | 可选 | 可选 → C10 | — |
| f D-B24-b 回调核对金额 | 不核对（旧版行为） | 可选 | 可选：联调后另做设计 | E6-pay 的字段 |
| g D-B24-c `TRADE_FINISHED` | 不到账（旧版行为） | 可选 | 可选：另开单 | — |
| h D-B24-d 订单标题里的 UID | 为空（旧版行为） | 可选 | 可选：另开单 | — |
| i D-B26-6 昵称 | 不写（旧代码如此） | 可选 | 可选 → C3 | — |
| j 微信 openid 含 `-`/`_` | 注册失败（旧版行为） | 可选 | 可选 → C2（请给出规则） | — |
| k openid 解绑 | 没有入口（旧版也没有） | 可选 | 可选 → C4-D，再拆 C4-impl（属于新增功能，需要你明确放开 09 §1.1 的“不增不减”） | — |
| l D-B29-3 插件里的数据语句 | 只校验、不执行 | 可选 | 可选 → C5a，再拆 C5b | — |
| m D-B25-4 验证码绑定地址 | **已是新行为**（修复了账号接管） | 可选（架构建议保持） | 可选：改回旧版会恢复漏洞 | — |
| n D-B20 “清空”范围 | 删除全部节点的内容（旧版行为） | 可选 | 可选 → C8 | — |
| o D-B16-1 静态链接 | 仍是动态地址 | 可选 | 可选 → C9（设计单，实施待拆） | — |
| p D-B15-18 配置生效方式 | 原队长 2026-10-26 已定为“重启生效” | 可选：承接 | 可选：推翻需另做设计 | — |

关于 k 的编号：`{A}34-b26-oauth.md` §7 里的 D-B26-10 指登录页入口（已实现），但 §5 写着“D-B26-10 未决，不显示 openid 解绑操作”，§9 的待确认里也没有解绑。所以解绑没有正式编号，由 A4 理顺。

5. **Q5 D9**：a 前台模板的来源；b 去掉密钥后的线上配置。请分别选择“提供（并说明方式）”或“暂不提供”。暂不提供的后果：E7 无法开始，上线窗口的条件不满足。切换演练（E5 组）用本机一次性副本，D9 不是它的已知前置；是否依赖由 E5 的设计单确认。
6. **Q6 外部沙箱**：请按 §4.6 的 11 单逐类说明是否提供、预计时间，以及批准的联调范围。没有提供的类，`{A}13-cutover-plan.md` §2 第 1 条就不满足；是否换一种处理，要你另外作出明确决定，本清单不提建议。
7. **Q7 发布链路**：CI 是否用 GitHub Actions（D2）、`-Pmysql-it` 是否进 CI、测试环境由谁提供以及地址（`MULTICA.md` §1）、部署方式、main 什么时候开保护（§0）。
8. **Q8 App**（只问范围）：要做哪些功能；09 §1.1 的“功能一样”是否适用于 App 的接口层；零结构变更是否同样约束 App（认证和存储方案由后续设计在约束内给出备选）；是否另建仓库；与 B21 并行还是排在它之后。
9. **Q9 演练数据**：用合成数据，还是由你提供脱敏副本（按红线，我们不读取线上数据）；另请给出**规模**（最大的几张表的行数、`upload/` 的大概大小）和**时间预算**（单次演练可接受的总时长）。E4a 的规模上界和 E5 组设计单的拆分都依据这两项。
10. **Q10 B13 浏览器走查的登录**：`MULTICA.md` §0 为“允许脚本填表登录：否”，新系统的会话又只在进程内存里，所以每次运行 B4c 都要有人在运行机上手动登录本次实例。请选择：(a) 提供人工在场登录（请说明谁、怎么约时间）；(b) 不执行 B4c，B21 记为“按 §0 未执行”。修改 §0 只能由你在主干上进行，本清单不提出这样的建议。
11. **Q11 性能冒烟的口径**（供 E9b 设计单使用）：(a) 只记录指标，不设门槛；(b) 以同机旧 PHP 副本为基线，Java 不得比它慢 X%，请给出 X；(c) 你给出绝对门槛（例如 p95 毫秒数）。选 (a) 时，性能冒烟仍是 B21 的收口条件，只是 E8b 不以指标判定成败。

### 6. 需求追溯（Issue 的三项交付）

| Issue 要求 | 落点 |
|---|---|
| 1 现状清单（已完成的模块/批次及证据；还没做的部分） | §1、§1 末“文档与 main 不一致”、§2、§2.1 |
| 2 后续开发拆分（每单约 60 分钟、集中在一个模块；范围、AC、依赖、档位） | §3（P/A/B/C/D）、§4（E1～E10、E8a/E8b、E6）、§4.7；待拆组按 §0 前言的含义处理 |
| 3 需要你拍板的问题 | §5 Q1～Q11 |

### 7. 修订记录

| 版本 | 基线 | 变化 |
|---|---|---|
| v1～v3 | `@3a3262e` | 初版盘点与两轮复核修订 |
| v4 | `@3a3262e` | 交接包、P4 独占实例、B5b 拆分、E1/E2 计数 |
| v5 | `@a57e9cb2` | 按 REV-1～6 修订并改新目录；`appk-2-v5-path-map.md` 路径映射 |
| v6 | `@a57e9cb2` | 完整展开为单一附件；E8 收口计数为 15 单；性能现状如实盘点；E5a～E5d、E9b/E9c、E10 改为待拆组；删除影响路径集复验规则，改为冻结后统一重跑，E8 拆成 E8a/E8b |
