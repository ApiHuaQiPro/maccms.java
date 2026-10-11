## site 模板引擎兼容验证与模块接口确认（MACC-7）

> **状态：技术验证报告，待用户确认 D2。** 本任务只做可复跑实验与决策建议：不改正式 `backend/pom.xml`、生产配置或正式业务代码，不创建正式首页/分类/详情路由，不连接任何数据库。
>
> 输入：前台只读方案 `docs/architecture/03-frontend-readonly-plan.md`（"03 方案"，第 1.2 节 D2 标准、第 1.3 节 P2、第 3.1 节 P4、第 7 节）、开发规范 `docs/architecture/02-agent-development-rules.md`（"02 规范"）、契约 `docs/contracts/content-read.openapi.yaml`、兼容清单 `docs/frontend/01-pages-url-compat.md`。基点提交 `5303e8d`（MACC-4～6 已合入：后端只读接口、兼容清单、03 方案）。
>
> 证据标注：
> - **【实测】**：本任务在本机执行过，给出命令与结果；测试代码在 `backend/spikes/site-template/`。
> - **【资料】**：来自依赖元数据、源码或官方目录，未做运行实验。
> - **【未测】**：没有执行，不下结论。
> - **【建议】**：需要用户或队长确认的方案。

### 0. 结论摘要

| 决策 | 建议 | 依据 |
|---|---|---|
| D2 模板引擎 | **jte 3.2.4**（`gg.jte:jte-runtime` + `gg.jte:jte-models` 运行时，`gg.jte:jte-maven-plugin` 构建期生成），**不引入** `jte-spring-boot-starter-4`；页面经 jte-models 生成的类型安全接口调用，先渲染到字符串再写响应 | 第 2、3 节：模板字段写错（包括测试从未走到的分支）在 `compile` 阶段失败；Thymeleaf 只在测试实际渲染到的分支上发现错误【实测】 |
| 备选 | Thymeleaf 3.1.5（Boot 4.1.1 官方 starter）。若用户更看重"官方集成 + 设计师可直接打开模板"，可选它，但必须接受第 3.4 节的分支盲区 | 第 4 节 |
| 渲染方式（两个引擎通用） | **缓冲渲染**：不走 Spring MVC 视图解析的"边渲染边写响应"。实测两种引擎在大页面中途失败时都会返回 **200 + 半页 HTML + 追加的错误页** | 第 3.5 节【实测】 |
| P2 模块接口 | 把契约 DTO 从 `content.api.dto` **整体移包**到 `content.application.view`，作为 `content` 的"已发布只读类型"供 `site` 使用；外加三处最小改动（`VideoListQuery` 构造校验、`SafeExceptionLog` 公开、`site` 专用异常处理） | 第 5 节 |
| P4 站点设置 | 唯一来源 = Java 应用自己的外部化配置 `maccms.site.*`（环境变量），`maccms.site.enabled` 默认关闭；开启时站名、站点根 URL 必填且校验，缺失即启动失败；不读任何 PHP 配置 | 第 6 节 |

### 1. 版本基线与候选范围

- 正式工程：Spring Boot **4.1.1**（`backend/pom.xml:11`），Java 21（`backend/pom.xml:22`），Maven Wrapper 3.3.4 → Maven 3.9.16。本任务没有升级任何版本，两个 spike 子工程都继承同一个 `spring-boot-starter-parent` 4.1.1。【实测】
- 实测两个候选：
  - **A. Thymeleaf**：`spring-boot-starter-thymeleaf` 4.1.1 → `thymeleaf-spring6` / `thymeleaf` **3.1.5.RELEASE**（版本由 Boot 4.1.1 的 `spring-boot-dependencies` 管理，未覆盖）。【实测，`dependency:tree`】
  - **B. jte**：`gg.jte` **3.2.4**（`jte-maven-plugin`、`jte-models`、`jte-runtime`、`jte-extension-api`；spike 里为了测 Spring 视图集成也引入了 `jte-spring-boot-starter-4`）。【实测，`dependency:tree`】
- 只做资料比较、未实测：FreeMarker 2.3.34、Mustache（JMustache 1.16）——两者都在 Boot 4.1.1 的依赖管理里、有官方 starter【资料】，但都没有编译期字段检查，相对 Thymeleaf 没有额外优势，故未投入实验。【未测】

### 2. 逐项比较

| 标准（03 方案 1.2 节） | A. Thymeleaf 3.1.5 | B. jte 3.2.4 |
|---|---|---|
| 维护与官方集成 | Spring Boot 官方 starter，版本随 Boot 管理；Spring Initializr 对 Boot 4.1.1 提供【资料】。最新版 3.1.5.RELEASE，Maven Central 发布于 2026-04-21【资料】 | jte 项目自己维护 Boot 4 starter（`jte-spring-boot-starter-4` 3.2.4 的 POM 以 Boot **4.0.2** 编译）；Spring Initializr 目录中 `jte` 的适用范围是 `[4.0.0,4.1.0-M1)`，**不对 Boot 4.1.x 提供**【资料，2026-10-10 查询 start.spring.io】。最新版 3.2.4，发布于 2026-04-29【资料】。starter 在 4.1.1 上实测可用（第 3 节 `/spike/view/*`），但推荐方案**不依赖 starter**：运行时只要 `jte-runtime` + `jte-models`，与 Spring 版本无耦合【实测】 |
| 默认 HTML 转义 | `th:text`、`th:<attr>`、`[[...]]` 都转义 `& < > " '`；`th:inline="javascript"` 按 JS 字符串转义（`</script>` → `<\/script>`）；`th:onclick` 等事件属性**拒绝**字符串（渲染时抛 `TemplateProcessingException`），只放行数字/布尔【实测】 | `${}` 按上下文转义：文本转义 `& < >`，属性转义 `& < " '`，`<script>` 块按 JS 转义，`on*` 属性按 JS 属性转义；`<a href>` 以 `javascript:` 开头时整段丢弃【实测】。**缺陷**：①事件属性不转义 `&`，`&#39;` 会被浏览器解码成引号，跳出 JS 字符串（实测 `go('&#39;);alert(1);//')` 解码为 `go('');alert(1);//')`）；②`javascript:` 过滤只认字面前缀，`java<TAB>script:` 不被识别。两项都已用"禁止事件属性 + URL 协议白名单"规避（第 3.2 节）【实测 + 源码】 |
| 不转义输出的写法 | `th:utext`、`[(...)]` | `$unsafe{}` |
| 字段引用错误能否让 verify 失败 | **只能靠渲染测试**：测试实际渲染到的表达式写错 → 测试失败 → `verify` 失败；写在测试数据没走到的分支里 → `verify` **通过**【实测，第 3.4 节】 | **编译期失败**：模板生成 Java 源码后与业务代码一起 `javac`，任何分支里的错字段都 `COMPILATION ERROR`【实测，第 3.4 节】。Controller 经 jte-models 生成的接口（如 `Templates.pagesShell(ShellPage)`）调模板，参数类型/个数也在编译期检查；经 Spring `Map` 模型（starter 的视图解析）则不检查（生成代码是 `(ShellPage) params.get("page")`，缺参为 null）【实测 + 生成代码】 |
| 布局/片段复用 | `th:fragment` / `th:replace` 带参数片段；布局用片段组合即可，Layout Dialect（第三方，Boot 管理 4.0.1）未使用【实测】 | `@template.xxx(...)` 调用子模板，`gg.jte.Content` 参数实现布局插槽【实测】 |
| MockMvc 测试 | `@SpringBootTest` + `@AutoConfigureMockMvc` 直接断言 HTML，两条渲染路径输出完全一致【实测】 | 同左【实测】 |
| 渲染缓冲 / 失败响应 | 见第 3.5 节：视图解析路径大页面失败 = 200 + 半页；Boot 配置 `spring.thymeleaf.servlet.produce-partial-output-while-processing=false` 可改为完整 500【实测】；推荐统一用 Controller 内缓冲 | 见第 3.5 节：starter 的 `JteView` 直接写 `PrintWriter`，大页面失败 = 200 + 半页（且截在标签中间）【实测 + 源码】；无对应开关，必须自己缓冲 |
| 异常日志 | `org.thymeleaf.TemplateEngine` 在抛出前**自己以 ERROR 记录异常 message 及 cause 链**（含合成泄漏标记），违反 02 规范第 4 节；需把该 logger 设为 `off` 后由 Controller 只记类型链【实测】 | 引擎不自行记录日志；缓冲路径日志中无泄漏标记【实测】 |
| 运行时依赖（不含 Spring） | `thymeleaf` 945 KB、`thymeleaf-spring6` 195 KB、`attoparser` 246 KB、`unbescape` 174 KB【实测文件大小】 | `jte-runtime` 52 KB、`jte-models` 39 KB、`jte-extension-api` 4 KB；运行时**不需要** JDK 编译器（预编译）【实测】 |
| 许可证 | 均为 Apache-2.0（thymeleaf、attoparser、unbescape 的 POM）【资料】 | Apache-2.0（`jte-parent` POM）【资料】。测试专用的 jsoup 1.23.2 为 MIT【资料】 |
| 智能体开发体验 | 模板是合法 HTML，可直接在浏览器打开；表达式为 SpEL 字符串，IDE/编译器不校验 | 模板语法接近 Java，错误由 `javac` 报告；**错误位置报在生成的 Java 文件**（如 `JtecardGenerated.java:[50,56] cannot find symbol: method remakrs()`），需对照模板查找【实测】 |
| 未测 | 性能、热重载、模板缓存失效、i18n | 性能、开发模式热重载、IntelliJ 插件 |

### 3. 实验与结果

#### 3.1 实验工程与复跑命令

- 位置：`backend/spikes/site-template/`（聚合 POM，仅聚合、不继承），子工程 `thymeleaf/`、`jte/`。说明与命令见 `backend/spikes/site-template/README.md`。
- 合成数据：`SyntheticPages`（每个子工程一份）给出 `normal`（中文、全角标点、字面 `& < > " '`）、`xss`（文本/属性/标题/URL 恶意串）、`empty`、`boom-small`、`boom-large`（400 张卡片后放一张访问器会抛异常的卡片，异常 message 带合成标记 `SYNTHETIC-LEAK-7f3a` 与伪连接串）。
- 路由只有 `/spike/view/{fixture}`（Spring MVC 视图解析）与 `/spike/buffered/{fixture}`（Controller 内缓冲渲染）。不配置数据源，不读 `MACCMS_*` 环境变量；执行时本机 shell 中也没有设置任何 `MACCMS_*` / `SPRING_DATASOURCE*` 变量【实测】。

在 `backend/` 下执行（2026-10-10，JDK 21，Maven 3.9.16）：

| # | 命令 | 结果【实测】 |
|---|---|---|
| E1 | `./mvnw -f spikes/site-template/pom.xml clean verify` | BUILD SUCCESS；thymeleaf **19** 个测试、jte **22** 个测试，0 失败。PowerShell `.\mvnw.cmd -f spikes\site-template\pom.xml clean verify` 结果相同 |
| E2 | `./mvnw -f spikes/site-template/thymeleaf/pom.xml -Pmismatch clean verify` | **BUILD FAILURE**：19 个测试中 7 失败 4 错误，原因 `Property or field 'remakrs' cannot be found on object of type '...VideoCard'` |
| E3 | `./mvnw -f spikes/site-template/thymeleaf/pom.xml -Pmismatch-branch clean verify` | **BUILD SUCCESS**（19 个测试全过）——错字段 `picc` 在测试没走到的"无海报"分支里，未被发现 |
| E4 | `./mvnw -f spikes/site-template/jte/pom.xml -Pmismatch clean verify` | **BUILD FAILURE**（`COMPILATION ERROR`，`cannot find symbol: method remakrs()`），测试阶段未开始 |
| E5 | `./mvnw -f spikes/site-template/jte/pom.xml -Pmismatch-branch clean verify` | **BUILD FAILURE**（`COMPILATION ERROR`，`cannot find symbol: method picc()`） |
| E6 | `java -jar .../site-template-{thymeleaf,jte}-0.0.0-SPIKE.jar` 后 `curl` | 两者约 4.5 秒启动（`Started SpikeApplication`），监听 127.0.0.1:18291 / 18292；结果见 3.5 节；按启动时记录的 PID 停止 |
| E7 | 正式工程 `./mvnw clean verify` | BUILD SUCCESS，**63** 个测试 0 失败（正式代码未改动，第 8 节） |

失配实验通过 profile 把 `src/mismatch*/` 下改错的片段复制进构建目录，正式模板文件本身不变；跑完需 `clean`（README 已注明）。

#### 3.2 转义（HTML 文本、属性、URL、事件属性、脚本）

两个引擎都用同一组断言（`ShellPageMvcTest`，各 10 个用例，视图解析与缓冲两条路径都测）：

- **合法普通文本**：`流浪地球 2：第一季 & 特别篇 <预告>` 输出为 `流浪地球 2：第一季 &amp; 特别篇 &lt;预告&gt;`；中文与全角标点原样输出、不做数字实体编码；用 jsoup 解析回来的文本与输入逐字相等。【实测，两者】
- **恶意输入**：jsoup 解析输出后，页面中没有 `<script>`、`<svg>`、注入的 `<img>`，任何元素上都没有 `on*` 属性；`<h1>`、`img@src`、`meta@content` 的解析值与恶意输入逐字相等，`<title>` 等于恶意标题 + ` - ` + 恶意站名（说明它们只作为文本/属性值存在）。【实测，两者】
- **URL**：`javascript:alert(7)` 作为卡片链接时，Thymeleaf 原样转义输出 `href="javascript:alert(7)"`（可执行）；jte 输出 `href=""`。jte 的过滤只覆盖 `<a href>` 且只认字面前缀，`java<TAB>script:alert(9)` 原样输出（`EscapingContextsProbeTest.javascriptHrefFilterOnlyMatchesLiteralPrefix`）。**结论：转义不等于 URL 校验**，所有 URL 必须由 `site` 的 URL 生成器产生，外部来源的 URL（图片、logo）只接受 `http`/`https`/站内相对路径白名单。【实测】
- **事件属性**：Thymeleaf 对 `th:onclick="${字符串}"` 直接拒绝渲染；jte 允许，并存在上述 `&` 实体跳出问题。正式模板一律**不在事件属性和 `<script>` 块中放数据**，由两层检查保证：①`TemplateGuardTest`（正则扫描正式模板目录，两个子工程都有）；②jte 的 `SitePolicyCompileTest` 用 jte 自带的 HTML 解析器 + `OwaspHtmlPolicy`、`PreventInlineEventHandlers`、`PreventSingleQuotedAttributes` 在 `verify` 中重新编译全部正式模板（探针模板被拒：`Inline event handlers are not allowed: onclick`）。构建本身已启用 `OwaspHtmlPolicy`，未加引号的属性值会编译失败（`Unquoted HTML attribute values are not allowed: href`）。【实测】
- **富文本**：默认转义**不是**富文本过滤。spike 没有使用 `vod_content`/`content` 字段，护栏测试禁止模板引用它；`content` 白名单过滤是后续独立任务（第 7 节）。
- **实体二次转义**：两个引擎都会把已含实体的输入（如库里的 `Tom &amp; Jerry`）再转义一次，浏览器会显示 `&amp;`。这符合"引擎只做输出转义"的预期；"先解码一次实体再输出"（03 方案 3.5 节）必须在视图模型层做，并用真实数据样本验证【待验证】。

#### 3.3 片段复用

两个候选都实现了"页面外壳（head/SEO/canonical、页眉导航、页脚）+ 卡片片段循环"。测试断言：卡片 3 个、页眉/页脚/canonical/`<h1>` 各 1 个、输出不残留模板语法；视图解析与缓冲两条路径输出逐字相同。【实测】

#### 3.4 模板与模型失配

| 场景 | Thymeleaf | jte |
|---|---|---|
| 错字段在测试会渲染的分支（E2 / E4） | 渲染测试失败 → `verify` 失败；错误信息指明模板与行列（`fragments/card - line 11, col 69`） | `javac` 失败，测试未运行；错误指向生成的 Java 文件行列 |
| 错字段在测试不会渲染的分支（E3 / E5） | **`verify` 通过，错误进入交付物**，直到线上某条数据触发该分支才报 500 | `javac` 失败 |
| 自动化测试内的编译检查 | 不适用 | `TemplateCompileCheckTest` 用与插件相同的编译器编译 `src/test/jte-broken/card.jte`，断言错误恰为 `cannot find symbol ... nmae()`（排除了类路径类错误） |

结论：Thymeleaf **不具备**编译期字段检查。"渲染全部模板"的自动化测试只能覆盖测试数据实际走到的分支；要让它可靠，每个 `th:if`/`th:unless`/`th:switch` 分支都需要有专门的测试数据，这一点无法由工具强制，靠人工/智能体自觉。对"今后主要由智能体开发"（`AGENTS.md`"重构目标"），这是选择 jte 的决定性原因。

#### 3.5 HTTP 状态码与完整响应（真实 Tomcat）

`RealHttpRenderingTest` 用原始套接字读取到连接关闭为止（不被高层客户端的异常掩盖）；`curl` 结果来自 E6。

| 请求 | Thymeleaf（Boot 默认） | jte（starter 视图） |
|---|---|---|
| `/spike/view/normal` | 200，分块传输，完整 | 200，`Content-Length: 2259`（页面小于响应缓冲区），完整 |
| `/spike/buffered/normal` | 200，`Content-Length: 2283`，完整，与视图路径正文相同 | 200，`Content-Length: 2259`，完整 |
| `/spike/view/boom-small`（`Accept: text/html`） | 500，Boot 错误页（`error` 模板），完整 | 500，`Content-Length: 300`，错误页 |
| `/spike/view/boom-small`（`curl` 默认 `Accept: */*`） | **500，正文为空** | **500，正文为空** |
| `/spike/view/boom-large` | **200**，分块未正常结束，正文 137,963 字符：半页 + 嵌在未闭合 `<li>` 里的错误页 | **200**，分块未正常结束，134,434 字符，截断在 `<a href="..."` 标签中间后直接拼接错误页 |
| `/spike/buffered/boom-large` | **500**，`Content-Length: 300`，`Cache-Control: no-store`，完整错误页，无半页内容、无泄漏标记 | 同左 |
| `/spike/view/boom-large`，`produce-partial-output-while-processing=false` | 500，完整错误页 | 无对应配置 |
| 视图解析路径的服务器日志 | 含异常 message 中的泄漏标记（Tomcat `StandardWrapperValve` 记录根因） | 同左 |
| 缓冲路径的服务器日志 | 关闭 `org.thymeleaf.TemplateEngine` logger 后，只有 `page render failed: <类型链>`；不关闭时引擎自己记录完整 message【均实测】 | 只有 `page render failed: <类型链>` |

结论（两个引擎通用）：

1. **一律缓冲渲染**：页面 Controller 先把整页渲染为字符串，成功后以 `ResponseEntity<String>` 返回（带 `Content-Length`）；失败抛出，由 `site` 自己的异常处理输出完整 500 页并 `no-store`，只记录类型链。这同时满足 03 方案 7.3 节"不输出半页 HTML 被缓存"和 02 规范第 4 节的日志要求。
2. 不依赖 Boot 的 `/error` 转发：它取决于 `Accept` 头（`*/*` 时 500 正文为空），且会经过容器日志记录 message。
3. 缓冲的代价是每次请求多占一份页面大小的内存（本实验约 2 KB～140 KB），首批三页可以接受；性能未测。

### 4. 推荐（D2，待用户确认）

**推荐 jte 3.2.4，按下列方式使用：**

1. 依赖：`gg.jte:jte-runtime`、`gg.jte:jte-models`（运行时），`gg.jte:jte-maven-plugin`（`generate`，`contentType=Html`，`htmlPolicyClass=gg.jte.html.OwaspHtmlPolicy`，扩展 `gg.jte.models.generator.ModelExtension`）；版本固定 3.2.4，不使用快照。**不引入** `jte-spring-boot-starter-4`：推荐路径不经过 Spring 视图解析，starter 只会带来"必须设置 `gg.jte.usePrecompiledTemplates` 否则启动失败"的配置项和与 Boot 4.1 的兼容性疑问。去掉 starter 后的启动与渲染【未测】：spike 的缓冲路径（`BufferedHtmlRenderer`）只用到 `jte-models` 生成的 `StaticTemplates` 与 `jte-runtime` 的 `StringOutput`，不引用 starter 的类，风险低，但须在 site 骨架任务中实测。
2. Controller 只通过生成的 `Templates` 接口调用页面模板（编译期检查参数），只通过缓冲渲染输出。
3. `verify` 中执行三类检查：生成代码编译（构建自带）、严格 HTML 策略重编译（`SitePolicyCompileTest` 同款）、正式模板护栏（`TemplateGuardTest` 同款）。
4. URL、图片地址、颜色值等进入属性前，由 `site.application` 中的生成器/白名单产生，模板不拼接 URL（03 方案 7.2 节）。

**放弃 Thymeleaf 的理由**：分支盲区（E3）使"模板写错 → 构建失败"不成立；引擎自行记录异常 message，需要额外压制。**它更好的地方**：官方 starter 与版本管理、Initializr 支持 Boot 4.1、事件属性默认拒绝字符串、模板可直接用浏览器打开。若用户选 Thymeleaf，需追加：缓冲渲染、关闭 `org.thymeleaf.TemplateEngine` logger、每个条件分支的专门测试数据、`TemplateGuardTest`。

**jte 的风险与对策**：

| 风险 | 对策 |
|---|---|
| 非 Spring 官方，Initializr 不对 Boot 4.1 提供 | 不用 starter，运行时与 Spring 无耦合；升级 Boot 不影响 jte。jte 停更时，模板是 HTML + 少量 Java 表达式，迁移成本可估 |
| 事件属性 `&` 实体跳出、`javascript:` 过滤不完整（3.2 节） | 禁止事件属性与脚本块中放数据（编译期策略 + 护栏测试）；URL 白名单。这两项可考虑向上游报告，不阻塞本项目 |
| 编译错误报在生成的 Java 文件行号 | 生成文件里有 `JTE_LINE_INFO` 映射；在 site 开发说明中写明排查方式 |

### 5. P2：`site` 如何使用 `content` 的读服务

#### 5.1 现状【已证实】

- 02 规范第 2 节：分层"只能自上而下依赖"（`docs/architecture/02-agent-development-rules.md:16`），模块之间"不能使用对方的 Mapper、行记录或 DTO 内部类型"（`:17`）。
- `ContentReadService`（application 层）的三个公开方法返回契约 DTO：`listCategories()`（`backend/src/main/java/com/maccms/backend/content/application/ContentReadService.java:48`）、`listVideos(VideoListQuery)`（`:53`）、`getVideo(long)`（`:77`）。这些 record 位于 `content.api.dto`，由 application 层 import（`:6-11`）——**application 已经反向依赖本模块的 api 包**，与第 16 行的分层规则不一致。
- 错误通过 `common.web.ApiException` + `ErrorCode` 抛出；全局 `@RestControllerAdvice`（`backend/src/main/java/com/maccms/backend/common/web/GlobalExceptionHandler.java:19`）兜底 `Exception.class`（`:59`）输出 JSON。`site` 的页面 Controller 若不自带更高优先级的处理，异常会变成 JSON 错误响应。
- `VideoListQuery` 的 record 构造器不校验范围（`backend/src/main/java/com/maccms/backend/content/application/VideoListQuery.java:9`），只有 `parse(...)`（`:16`）校验；`site` 直接 `new VideoListQuery(...)` 就能绕过 `pageSize ≤ 100` 等上限。
- `SafeExceptionLog` 是包内可见类（`backend/src/main/java/com/maccms/backend/common/web/SafeExceptionLog.java:14`），`site` 无法复用。

#### 5.2 选项

| 方案 | 做法 | 评价 |
|---|---|---|
| 甲：契约 DTO 原地作为已发布类型 | 不改代码，只改规范，`site` import `content.api.dto` | 改动为零，但 `site` 依赖 `content` 的 api 包，application→api 的反向依赖继续存在 |
| **乙（建议）：移包为 application 层已发布只读类型** | 6 个 record（`CategoryDto`、`CategoryExtendDto`、`CategoryListResponse`、`CategoryRefDto`、`VideoDetailDto`、`VideoSummaryDto`）从 `content.api.dto` 移到 `content.application.view`，类名、字段、JSON 不变；`ContentReadService`、两个 Controller 改 import | 纯机械改动（约 9 个 Java 文件），修正分层方向；`site` 只依赖 `content.application`；这些类型仍与契约一一对应，`site` 天然只能用契约字段（03 方案 F2） |
| 丙：为 `site` 另建视图类型 | `content.application` 新增一套只读 record，由服务映射 | 与契约 DTO 字段重复、易漂移；`site` 仍只需契约字段，没有收益 |

#### 5.3 建议的最小改动（下一批第 1 个任务，不在本任务实施）

1. 方案乙移包；OpenAPI、契约测试、JSON 输出均不变（`ContentApiContractTest` 应原样通过）。
2. `VideoListQuery` 增加紧凑构造器校验（`page` 1..1000、`pageSize` 1..100、`categoryId` 1..65535、`sort` 非空），`parse` 行为不变。
3. `SafeExceptionLog` 改为 `public`（方法同步公开），供 `site` 的异常处理复用。
4. `GlobalExceptionHandler` 不改；`site` 新增自己的 `@ControllerAdvice(basePackages = "com.maccms.backend.site")`，优先级高于全局处理：`CATEGORY_NOT_FOUND`/`VIDEO_NOT_FOUND`/`INVALID_PARAMETER` → 404 页，`VIDEO_PASSWORD_REQUIRED`/`VIDEO_COPYRIGHT_RESTRICTED` → 交还 PHP（03 方案 4.2 节），`SERVICE_UNAVAILABLE` → 503 页，其他 → 500 页；全部只记类型链。
5. 依赖边界护栏测试（纯 Java 源码扫描，不引入 ArchUnit）：`site` 包只能 import `content.application`（含 `.view`）与 `common.web` 的 `PageResponse`、`ApiException`、`ErrorCode`、`SafeExceptionLog`；禁止 `content.api`、`content.infrastructure`、`org.apache.ibatis`、`java.sql`、`javax.sql`、`org.springframework.jdbc`；`content.application` 不得 import `content.api`。
6. 不新增 SQL/Mapper，不扩展公开 API 字段；改动后跑 `./mvnw verify`。按 02 规范第 5 节，移包不涉及 Mapper/SQL，`-Pmysql-it` 非必须，但建议顺带跑一次确认。

#### 5.4 规范修订建议（随上面的任务提交，修改 `docs/architecture/02-agent-development-rules.md`）

第 2 节替换为：

> - 模块内分层 `api → application → infrastructure`，只能自上而下依赖；**application 不得依赖本模块的 api 包**。Controller 不直接调用 Mapper。
> - 模块之间只能调用对方 `application` 层的公开服务，以及这些服务签名中出现的**已发布只读类型**（`<模块>.application.view` 包下的不可变 record）；不能使用对方的 api 层（Controller、HTTP 专用类型）、infrastructure 层（Mapper、行记录）。
> - 已发布只读类型与 `docs/contracts/` 中的 schema 一一对应时，修改它们等同修改契约，按第 1 节在同一提交里改 OpenAPI、类型与契约测试。
> - `site` 模块（服务端页面）只依赖 `content.application` 与 `common`；不拥有表、不新增 Mapper/SQL；页面需要新字段时走契约变更。

第 4 节追加：

> - 服务端页面一律先渲染到缓冲区再写响应；渲染异常由 `site` 的异常处理输出完整错误页，只用 `SafeExceptionLog` 记录类型链；模板引擎自身的错误日志不得输出异常 message。

第 7 节追加（见第 7 节文件责任）：

> - `backend/` 内的例外：`backend/src/main/jte/site/components/` 与 `backend/src/main/resources/static/site/` 由前端智能体维护，后端智能体维护其余部分（含页面模板、视图模型、模板参数签名）；组件模板的 `@param` 签名变更需双方在任务中确认。

### 6. P4：站点设置

#### 6.1 原则

- **唯一来源**：Java 应用自己的外部化配置 `maccms.site.*`（`@ConfigurationProperties` + `@Validated` 的 record，与现有 `ContentProperties` 同一风格），值从环境变量注入；样例只写在 `backend/config/maccms-backend.env.example`（占位或合成值），真实 `*.env` 不提交（02 规范第 4 节）。
- **不读 PHP 配置**：Java 不读取 `application/extra/*.php`、不执行 PHP 导出脚本、不读取仓库外的线上配置目录。03 草稿 3.5 节提出的"PHP CLI 只读导出脚本"不在首批范围，需另行批准。并行期内 PHP 与 Java 的站点设置由运维分别维护，存在漂移风险，切流量评审时处理。
- **默认不启用**：`maccms.site.enabled=false` 时不注册任何页面路由、不校验站点设置，现有"只提供 API"的运行方式不受影响。
- **不从请求推断站点地址**：canonical、站内绝对链接只用 `base-url`，绝不使用 `Host`、`X-Forwarded-Host` 等请求头（防止 Host 头注入污染 canonical）。

#### 6.2 配置项

| 配置项（环境变量） | 默认值 | 校验 | 旧 PHP 对应项（只作参照，不读取） |
|---|---|---|---|
| `maccms.site.enabled`（`MACCMS_SITE_ENABLED`） | `false` | 布尔 | — |
| `maccms.site.name`（`MACCMS_SITE_NAME`） | **无**，启用时必填 | 去首尾空白后 1～64 字符，不含控制字符 | `site.site_name`（`application/extra/maccms.php:20`） |
| `maccms.site.base-url`（`MACCMS_SITE_BASE_URL`） | **无**，启用时必填 | 绝对 URL；协议仅 `http`/`https`；有主机名；无用户信息、查询串、片段；路径以 `/` 结尾（允许子目录） | `site.site_url`（`:21`）+ `site.install_dir`（`:31`） |
| `maccms.site.keywords` / `description` | `""` | ≤200 / ≤500 字符 | `site_keywords` / `site_description`（`:24-25`） |
| `maccms.site.icp` | `""` | ≤64 字符 | `site_icp`（`:26`；仓库值是占位文本，不要照抄） |
| `maccms.site.logo-url` | `""` | 空，或 `http`/`https` 绝对 URL，或以 `/` 开头的站内路径 | `site_logo`（`:32`） |
| `maccms.site.status` | `open` | 枚举 `open` / `closed` | `site_status`（`:40`） |
| `maccms.site.closed-message` | `站点维护中，请稍后访问。` | 1～200 字符，按文本转义输出 | `site_close_tip`（`:41`） |
| `maccms.site.time-zone` | `Asia/Shanghai` | 合法 `ZoneId` | 线上时区【待验证】 |
| `maccms.site.canonical-redirect` | `false` | 布尔；D4 批准前保持关闭 | — |
| `maccms.site.hit-counter-enabled` | `false` | 布尔；D5 决定前保持关闭 | — |
| 页面缓存 TTL | **不设**：200 响应发 `Cache-Control: no-cache`，404 短缓存或不缓存，500/503 `no-store` | 生产 TTL 待部署评审 | — |

首批**不支持**：统计代码 `site_tj`（`:39`，任意 HTML/JS 原样输出，需单独安全评审）、移动端独立模板 `mob_*`（改为响应式）、`template_dir`、QQ/Telegram/邮箱等联系方式（可后续作为纯文本加入）。

#### 6.3 行为

- **缺配置**：`enabled=true` 而 `name`/`base-url` 缺失或不合法 → 启动失败，错误信息只给出配置项名称、不回显取值；不回落到任何默认站名或请求头推断的地址。
- **站点关闭**：`status=closed` 时所有 `site` 路由返回 **503** + `Retry-After` + `Cache-Control: no-store` + `noindex` 的关闭页，正文为转义后的 `closed-message`；不查询数据库。**与旧版差异**：旧 PHP 在 `index/Base` 构造时调用 `check_site_status`（`application/index/controller/Base.php:14`），输出关闭模板后 `die`（`:73-79`），没有设置状态码，即 HTTP 200（代码阅读，未实测；仓库缺模板，本地无法渲染该页）。生产环境的站点关闭建议在 nginx 统一处理（PHP 与 Java 同时关闭），Java 开关用于本地与单独停用 Java 页面。
- **本地合成值**：`MACCMS_SITE_NAME=MacCMS 本地站（合成）`、`MACCMS_SITE_BASE_URL=http://maccms.local.test/`（`.test` 为保留顶级域名，不会解析到真实站点）；首批本地 canonical 只基于该合成配置，301 关闭、计数脚本关闭，不设生产缓存 TTL。线上真实站名/域名未知，不阻塞本地实现。

### 7. 后续拆分建议（本任务不创建 issue）

| 顺序 | 任务 | 负责与文件 | 依赖 | 是否需等 D2 |
|---|---|---|---|---|
| 1 | P2 最小改动 + 02 规范修订（第 5.3、5.4 节） | Claude：`backend/src/main/java/com/maccms/backend/content/**`、`common/web/SafeExceptionLog.java`、相关测试、`docs/architecture/02-agent-development-rules.md` | 用户/队长确认 P2 | 否 |
| 2 | `site` 骨架：引入 jte 依赖与插件、`SiteProperties`（第 6 节）、缓冲渲染器、`site` 异常处理、外壳/404/500/503/关闭页、严格策略与护栏测试、依赖边界测试、访问日志字段 | Claude：`backend/pom.xml`、`backend/src/main/java/com/maccms/backend/site/**`、`backend/src/main/jte/site/layout/**`、`backend/src/main/jte/site/pages/**`、测试；Kimi：`backend/src/main/jte/site/components/**`（按 Claude 定下的 `@param` 签名写标记）、`backend/src/main/resources/static/site/**`（设计 token、组件样式） | 1、D2、P4 确认 | **是** |
| 3 | URL 生成器/解析器 + PHP 黄金向量（一次性库，03 方案 5.4 节） | Claude：`site.application`、`backend/src/test/resources/` 向量 | 2（包结构） | 否（纯 Java 逻辑） |
| 4 | 图片地址规范化（`mac_url_img` 等价）+ 黄金向量 | Claude | 2 | 否 |
| 5 | 富文本白名单过滤（`content`）与文本实体处理；过滤库选型在该任务内比较 | Claude；脱敏真实样本【待验证】 | 2 | 过滤逻辑否；输出组件是 |
| 6 | 三页实现（分类 → 详情 → 首页）与本地验收（03 方案第 8 节 F1～F14，含浏览器自动化截图） | Claude：Controller、视图模型、页面模板；Kimi：组件样式、移动端/键盘验收脚本 | 3、4、5 | **是** |

仍需另行评审、不在上述任务中决定：部署与 nginx 分流/交还演练（P8、03 方案 4.2 节）、canonical 301（D4）、点击计数脚本（D5）、剧集索引接口或降级链接（P5）、生产缓存 TTL、统计代码 `site_tj`。

### 8. 与旧版的差异、未测事项与风险

**与旧版的差异**：本任务未改动任何正式代码、旧 PHP、数据库或配置，运行行为无差异。方案层面的新差异（待随实现确认）：站点关闭由 200 改为 503（6.3 节）；`javascript:` 等非白名单 URL 不再输出；渲染失败返回完整 500 页（旧 PHP 的模板错误行为未核实）。

**正式工程**：`backend/pom.xml`、`backend/src/**`、`application.yml` 均未改动；`./mvnw clean verify` 63 个测试通过（E7）。正式 API、字段、状态码与只读护栏保持不变。

**未测**：

- 去掉 `jte-spring-boot-starter-4` 后的 jte 启动与渲染（推荐方案的形态，见第 4 节第 1 条）。
- 两个引擎的性能、内存（大页面缓冲）、并发；开发模式热重载；IDE 支持。
- FreeMarker、Mustache（只做资料比较）。
- 真实数据中的 HTML 实体与富文本分布；线上站点配置；nginx 交还。

**风险**：

- jte 社区规模与维护连续性弱于 Thymeleaf；Initializr 当前不对 Boot 4.1 列出 jte（第 2 节）。
- jte 的事件属性与 `javascript:` 处理缺陷依赖本项目规则规避，规则失效（例如有人在护栏外加了新模板目录）就会暴露；护栏应扫描整个模板根目录。
- 缓冲渲染对超大页面的内存占用未测；首批三页分页上限为 100 条，风险低。
