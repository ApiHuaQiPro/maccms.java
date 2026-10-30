# MULTICA — 本仓 Squad 上下文（maccms.java）
> 2026-10-30 按 GitHub `ApiHuaQiPro/maccms.java` 主干 `9ca0300` 的现状填写 §1–§3；§0 是确认人的声明。仓库从旧 maccms 工作区的本地仓库原样迁来（main 的 298 个提交）；同日调整目录：Java 工程移到仓库根目录，旧站 PHP 移到 `php/`。项目现状、已定方案与开发规范见仓库根 `AGENTS.md` 和 `docs/architecture/02-agent-development-rules.md`（改 Java 工程前必读）。带 ⚠ 的是已知问题，汇总在文末。
> 复制到产品仓根目录（分仓各一份）。@Tester / @FrontendDev / @BackendDev / @DevOps / Leader 开工前必读；路径以本文件为准，禁止猜测。缺此文件 = BLOCKED。

## 0. 人工声明（只由人类填写与修改，经主干合入）
agent 只读主干上的这一节（`git show origin/<主干>:MULTICA.md`），不读 feature 或 deploy 分支上的版本；任何分支改动本节 → 范围检查 FAIL，升级人类。确认规则见 `skills/shared/conventions.md`「人类确认」。
| 字段 | 填写 |
| --- | --- |
| 确认人（Multica 成员 ID） | fc9235d2-e00c-400c-98f7-f90b6a065bce |
| 主干保护（需人工审批）：是/否 | 否（公开仓库可以在 GitHub 设置里保护 main；保护好、合入需人工审批之后再改“是”） |
| 主干推送触发生产部署：是/否 | 否（仓库没有 GitHub Actions 流水线，也没有 Webhook） |
| 允许脚本填表登录：是/否 | 否 |

## 1. 仓库身份
| 字段 | 填写 |
| --- | --- |
| layout | Java 为主（仓库根目录：新系统，Java 21 + Spring Boot 4.1.1 + MyBatis 模块化单体，jte 服务端模板，Maven Wrapper；`php/`：旧系统 MacCMS v10，PHP + ThinkPHP 5.0.24，只读，只作对照与黄金样本来源，构建时从 `php/static/` 打包旧静态资源；文档里的旧站路径 `application/…`、`static/…` 等都相对 `php/`） |
| 主干分支 | main |
| 服务名 | maccms-backend（`target/maccms-backend-0.1.0-SNAPSHOT.jar`，默认监听 127.0.0.1:18280；环境变量示例 `config/maccms-backend.env.example`） |
| 关联仓库（分仓时） | N/A（之后的 App 另建仓库时在这里登记） |
| 测试环境地址 | ⚠ 待你提供：目前没有登记测试环境 |
| 版本探针（返回部署提交号的地址） | ⚠ 缺失：只有 `/actuator/health`（只返回 UP/DOWN），不含部署提交号 → 补上之前 G2.5 BLOCKED |

## 2. 测试自动化路径（只填本仓真实存在的）
| 类型 | 路径 |
| --- | --- |
| 用例文档、评估、报告与证据 | docs/test/<ISSUE-KEY>/（固定，不改） |
| UI E2E | tests/e2e/（本 Issue 的脚本放 tests/e2e/<ISSUE-KEY>/） |
| 已有页面检查 | src/test/browser/（各批次的浏览器检查脚本，如 b12_admin_check.py），T3 新脚本仍按上面的固定路径放 |
| API 用例 | tests/api/<ISSUE-KEY>/（公共框架放 tests/api/） |
| 单元测试 | ./mvnw -B verify |
| MySQL 集成测试 | ./mvnw -B verify -Pmysql-it（要 MACCMS_IT_ADMIN_URL / _USERNAME / _PASSWORD；测试自建并只删除一次性库 maccms_it_*，见 README.md「构建与测试」） |

## 3. 构建与验证命令
```text
install:   N/A（mvnw 首次运行自动下载 Maven 与依赖）
unit_test: ./mvnw -B verify   # 编译 + 单元测试 + HTTP 契约测试，不连数据库；本机实测 3005 条全过、约 11 分钟（2026-10-30，需下文 JDK_JAVA_OPTIONS）；开发中先跑相关测试类 `./mvnw -B test -Dtest=<类名>`，交付前再跑全量
build:     ./mvnw -B -q package -DskipTests
e2e:       <!-- 页面自动化，仅 T3 对测试环境执行，在仓库根目录：python -m pytest tests/e2e/<ISSUE-KEY> --base-url "<测试环境地址>" -->
api_test:  <!-- 接口自动化，仅 T3 对测试环境执行，在仓库根目录：python -m pytest tests/api/<ISSUE-KEY> --base-url "<测试环境地址>" -->
lint:      N/A（未配置）
typecheck: N/A（Java 编译即类型检查，已含在 unit_test）
api_lint:  N/A（未配置独立的规范校验；接口契约在 docs/contracts/）
dep_audit: N/A（未配置）
```

## 4. 分支约定（接 GitHub，与 Issue deploy branch 对齐）
| 类型 | 命名 |
| --- | --- |
| deploy | release/<ISSUE-KEY>-<slug> |
| feature | feature/<ISSUE-KEY>-<端>-<slug>，<端>：prd / arch / ui / fe / be / test / ci |

文档产物（PRD、技术设计、UI 设计、T3 报告、发布清单）留在各自的 feature 分支、按提交号引用，不合入 deploy 分支或主干；fe / be / test / ci 分支按小队路由合入 deploy 分支。

## 5. 凭据（禁止写入 Git，只写变量名）
全库默认变量名如下；本仓用不到的行删掉，值只由人工配置在运行时环境变量（或已 gitignore 的 `.env`）。
| 用途 | 环境变量名 |
| --- | --- |
| UI 登录态文件路径（默认角色） | APP_UI_STORAGE_STATE |
| UI 登录态文件路径（只读 / 低权限角色） | APP_UI_STORAGE_STATE_READONLY |
| UI 表单登录（仅 form_login 模式，且 §0 允许脚本填表登录：是） | APP_UI_USERNAME / APP_UI_PASSWORD |
| 接口令牌（默认角色，用户 A） | APP_API_TOKEN |
| 接口令牌（只读 / 低权限角色） | APP_API_TOKEN_READONLY |
| 接口令牌（与 A 同级的另一个用户 B，横向越权用例） | APP_API_TOKEN_OTHER |
| 后端连库（只读账号） | MACCMS_DB_URL / MACCMS_DB_USERNAME / MACCMS_DB_PASSWORD |
| MySQL 集成测试（本机有建库删库权限的账号） | MACCMS_IT_ADMIN_URL / MACCMS_IT_ADMIN_USERNAME / MACCMS_IT_ADMIN_PASSWORD |

## 已知问题（待处理）
- ⚠ 仓库是公开的：代码、`docs/analysis/` 里的安全问题分析任何人都能看到。凭据与生产配置（仓库外 `D:\Ai\MACCMS\prod-config\`）一律不进仓库、评论或日志。
- ⚠ Windows 运行机：本机 JDK 21 在 Temp 根目录建不了 AF_UNIX 套接字，不处理的话 `mvnw verify` 有 163 条报 `Unable to establish loopback connection`。App开发 工作区的智能体已在环境变量里设 `JDK_JAVA_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:\Tools\Java\afunix-tmp`（本机已建该目录），设好后 3005 条全过；在别处跑也要设同样的变量，指向一个专用目录。
- ⚠ 旧系统的 `php/vendor/` 不在 git 里（`php/vendor/.gitignore` 为 `*`），PHP 黄金样本要先按 `AGENTS.md`「本地运行与测试」（在 `php/` 下执行）从仓库外的生产备份 `D:\Ai\MACCMS\maccms10-prod-20260711.zip` 恢复；只有本机有这个文件。
- ⚠ 没有版本探针（见 §1），也没有登记测试环境。
- ⚠ 仓库里没有 GitHub Actions 流水线；智能体库的流水线 / 合并平台层目前只接了 CNB → G2.5（部署测试环境）、G4（合并）暂时 BLOCKED，等平台层换成 GitHub 或改人工部署。
- ⚠ main 没有分支保护（见 §0）：保护 main 之前 G4 BLOCKED。
- ⚠ 旧 maccms 工作区（MACC-*）的单留在原工作区作档案，B15–B29 等多张还在待验收、改动大多已在 main；35 个未合并的智能体分支只留在旧本地仓库 `D:\Ai\MACCMS\src\maccms`，没推到 GitHub。
