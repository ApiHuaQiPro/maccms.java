# APPK-2 前端实现说明（S3-13a 内置视图）

| 字段 | 值 |
| --- | --- |
| ISSUE-KEY | APPK-2 |
| 作者 | @前端开发-海外 |
| 版本 | v1 |
| 状态 | 已完成 S3-13a；其余前端子任务未开工，原因见「已知问题与风险」 |
| 技术设计 | `docs/design/APPK-2/tech-design.md` @ `20490951e1a9c880e975caee0c72c5e0e92120ed`（改动清单 C-39，子任务 S3-13a） |
| UI 设计 | `docs/design/APPK-2/` @ `0910f7b5e12e779ad2b66f70823b16b601533cd2`（D-13、ALIGN-3） |
| 接口契约 | `docs/api/APPK-2/openapi.yaml` @ `bc612e933aa67abab319b90534c5150c10a51452`（S3-13a 不涉及接口） |
| PRD | `docs/prd/APPK-2.md`（本次只用到 B.1.2 内置视图） |
| 放行 | S3 放行评论（智能调度-海外，评论 `01a19655-34b6-7b2a-920d-2c4259fc5910`） |
| 基线 | deploy `release/APPK-2-php-template-migration` = `a57e9cb2b94d3e500bea7dfe2c579daee093d969` |

## 1. 页面与路由
本子任务不新增页面或路由。交付的是 `PhpTemplateRenderer`（后端 S3-10）将来渲染 `index/index`、`public/jump`、`public/msg` 时读取的 3 个内置视图资源。对应 AC：AC-1、AC-5（ALIGN-3、B.1.2）。

## 2. 状态表
N/A：纯静态资源，没有加载 / 空 / 错误 / 禁用 / 无权限状态。UI 设计 §1、§4 规定内置视图按 PHP 原文呈现，不新增状态。

## 3. 设计对照表
| DESIGN-ID | 实现 | 实现截图 | 设计截图 | 结论 |
| --- | --- | --- | --- | --- |
| D-13（官方模板 / JTE 下线 / 内置视图） | `src/main/resources/phptemplate/builtin/index/view/{index/index.html,public/jump.html,public/msg.html}` | 无：未接入渲染器，没有可截图的页面 | `shot-1280.png` 底部深色卡（唯一呈现来源） | 一致：内容与 PHP 原文逐字节相同（测试 `BuiltinViewResourcesTest.builtinViewsAreByteIdenticalToPhpOriginals`）；页面级截图对照在渲染器接入后随 S3-14a 补 |

## 4. 组件映射
| UI 区域 | 组件 | 复用 / 新建 | 理由 |
| --- | --- | --- | --- |
| 内置视图 | 3 个资源文件 | 新建 | C-39；从 `php/application/index/view/` 按字节复制 |

## 5. 接口对接 / 表单与校验 / Mock
不涉及。未用 mock。

## 6. 改动文件
- 新增 `src/main/resources/phptemplate/builtin/index/view/index/index.html`、`public/jump.html`、`public/msg.html`（内容取自基线提交的 git blob，LF）。
- 修改 `.gitattributes`：`src/main/resources/phptemplate/builtin/** -text -whitespace`（C-39 要求）；另加 `php/application/index/view/** -text -whitespace`。后一行不在 C-39 字面范围内：本机 `autocrlf=true` 会把 `php/` 下三个文件检出为 CRLF，逐字节测试会因此失败。它只改检出规则，不改 blob 内容（blob 本来就是 LF，`git ls-files --eol` 显示 `i/lf w/lf attr/-text`）。请架构师确认可接受。
- 新增 `src/test/java/com/maccms/backend/site/BuiltinViewResourcesTest.java`（3 个用例：与 `php/` 原文件逐字节相同；不含 CR；恰好交付这 3 个文件）。

## 7. 安全自查（`multica-review-security` 11 项）
- 2 鉴权越权、4 日志脱敏、7 错误信息泄露、9 XSS/CSRF：不适用，本子任务只增静态资源文件，不含代码路径；资源由后端引擎渲染，XSS 与 CSRF 在引擎接入时（S3-14a）评审。
- 3 敏感信息：3 个文件为 PHP 官方原文，已检索不含密钥或内部地址（原文即基线 `php/` 下已入库文件）。
- 5 依赖：未新增依赖，`dep_audit` 在 `MULTICA.md` 登记为 N/A。
- 其余后端项：不适用：后端项。

## 8. 验证记录
| 命令 | 结果 |
| --- | --- |
| `./mvnw -B test -Dtest=BuiltinViewResourcesTest` | Tests run: 3, Failures: 0, Errors: 0 → BUILD SUCCESS |
| `./mvnw -B test -Dtest=ModuleBoundaryTest,SiteGuardrailsTest,ReadOnlyGuardrailsTest,BuiltinViewResourcesTest` | Tests run: 27, Failures: 0, Errors: 0 → BUILD SUCCESS |
| `./mvnw -B -q package -DskipTests` | 退出码 0；jar 内含 `BOOT-INF/classes/phptemplate/builtin/index/view/{index/index.html,public/jump.html,public/msg.html}` |
| `git diff --check` | 通过 |
| `./mvnw -B verify`（全量，约 11 分钟） | **未执行**：超过单次前台命令的 10 分钟上限，本次改动只增资源与一个独立测试，已跑上面相关测试替代；全量交 S3-10 之后统一跑 |

AC 对应：C-39 / ALIGN-3 → `BuiltinViewResourcesTest`。

## 9. 实现对照清单记录
- ✅ 范围：只做 S3-13a，未改 `src/main` Java、`php/` 内容、jte、接口契约（`git diff --stat` 为证）。
- N/A DESIGN-ID 页面对照、六态、交互态、三档截图、a11y：无页面，见 §2、§3。
- N/A 接口对接、列表、mock。
- ✅ 测试：`BuiltinViewResourcesTest` 3 个用例，断言为字节比较。
- ✅ 安全：见 §7。
- ✅ 实现说明与代码、测试同一提交。

## 10. 已知问题与风险
1. **派活范围与技术设计的端归属不一致（需智能调度 / 架构师裁决）**：派活写的前端范围是“API-030 安装流程、后台表单方法与字段、状态/错误展示”。技术设计把这些改动行都归给后端（C-49 `wizard.jte`、C-54 `sql.jte`/`import.jte`、C-56 安装向导）；归前端的只有 C-36～C-46、C-70～C-75（前台页面接入、jte 下线）。我没有擅自接手后端行，避免和后端开发改同一批文件。
2. **前台页面接入暂不能开工**：S3-14a～g 依赖 S3-10/S3-11/S3-12（`PhpTemplateRenderer`、模板函数、标签模型），仓库里还没有 `phptemplate` 包；S3-13c 依赖 S3-13b（`readTemplateAsset`，后端）。
3. 全量 `./mvnw -B verify` 未跑，见 §8。

## 11. 修订记录
| 版本 | 日期 | 内容 |
| --- | --- | --- |
| v1 | 2026-11-01 | S3-13a：3 个内置视图 + 字节一致测试 + `.gitattributes` |
