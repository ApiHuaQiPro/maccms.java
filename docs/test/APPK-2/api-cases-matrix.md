# APPK-2 接口用例追溯矩阵（只写阶段）

用例文件在 `tests/api/APPK-2/`，1 条用例 = 1 个测试函数。依据：T1 `docs/test/APPK-2/t1-cases.md` @ `a906fac`，
接口契约 `docs/api/APPK-2/openapi.yaml` @ `bc612e93`。

**配套文件**：逐条用例的前置、步骤与预期、判定说明在 `docs/test/APPK-2/api-cases.md`（G2 约定的用例清单文件名）。
两份文档的**用例编号集合与覆盖结论一致**，可以互相替代查找：编号集合、按 API / 按 AC- 汇总、覆盖状态以本矩阵为准；
每条用例的具体步骤与预期以 `api-cases.md` 为准（冲突时以 `api-cases.md` 为准并在 T2 改正本矩阵）。

## 按 API 汇总

| API | 路径 | 用例数 | 用例编号 |
| --- | --- | --- | --- |
| API-024 | `/make/{makeAction}` | 4 | TC-I-MAKE-001～004 |
| API-030 | `/install.php`（step=5） | 7 | TC-I-INS-001～007 |
| API-040 | `/collect/api` | 2 | TC-I-COLLECT-001～002 |
| API-041 | `/collect/test` | 2 | TC-I-COLLECT-003～004 |
| API-043 | `/images/sync` | 1 | TC-I-IMAGES-001 |
| 横切 | 方法策略 / 鉴权 / 来源校验 / 传输上限 | 4 | TC-I-METHOD-001、TC-I-AUTH-001、TC-I-CSRF-001、TC-I-LIMIT-001 |
| **合计** | | **20** | |

## 按 AC- 追溯

| AC- | 覆盖用例 | 覆盖的契约条款 |
| --- | --- | --- |
| AC-7（静态生成） | TC-I-MAKE-001～004 | MakeParams 各字段 `x-business-rule`、`MAKE_PARAM_ERR`、SCH-1 |
| AC-8（后台权限） | TC-I-MAKE-002、TC-I-AUTH-001 | ADM-1（302）、ADM-4（200 无权限体）、`x-permission` |
| AC-10（在线采集） | TC-I-COLLECT-001～004 | OUT-1-1～4、OUT-1-9、`COLLECT_URL_REJECTED`、`COLLECT_PARAM_ERR` |
| AC-12（安装向导） | TC-I-INS-001～007 | INS-SEC-1～5、判定顺序、`INSTALL_*` |
| AC-13（图片同步） | TC-I-IMAGES-001 | `ImagesSyncParams`、`IMAGES_PARAM_ERR`、SEC-IN-1 |
| AC-16（安全与运行约束） | TC-I-METHOD-001、TC-I-CSRF-001、TC-I-LIMIT-001 | ADM-6、SRC-1/SRC-2、TRN-1、CSRF 分类 |

每条 AC- 至少一正一反：见下表「正/反」列。

## 逐条明细

| 用例 | 维度 | 优先级 | 正/反 | 关键断言 |
| --- | --- | --- | --- | --- |
| TC-I-INS-001 | 正常 | P1 | 反 | 非安装模式 `/install.php` → 404（API-005），无安装痕迹、无配置泄露 |
| TC-I-INS-002 | 安全、回归 | P1 | 反 | `install.lock` 存在 → 200 纯文本 `INSTALL_LOCKED` 原文，不建会话、不进写库阶段 |
| TC-I-INS-003 | 异常、安全 | P1 | 反 | 安装模式 + 无 lock + 全新会话（无向导状态/令牌）：GET 与非表单 POST → `INSTALL_ACCESS_DENIED`（INS-SEC-3），不命中更早分支、无执行痕迹 |
| TC-I-INS-004 | 安全、权限 | P1 | 反 | 全新会话无令牌提交 → `INSTALL_ACCESS_DENIED`；不命中更早分支；不回显令牌与会话标识 |
| TC-I-INS-005 | 安全、权限 | P1 | 反 | 同一会话第 3 步取真实 32 位十六进制令牌：第一次不被拒（确实进入安装处理），完全相同表单重放必被拒；不重复安装 |
| TC-I-INS-006 | 边界、异常 | P2 | 正/反 | SCH-1：`account` 数组按缺失 → `INSTALL_ADMIN_REQUIRED`（无向导状态时为 `INSTALL_NOT_TESTED`）；`install_dir` 数组 = `/`；`initdata` 非 1 不是错误；均非 400 且不被令牌拒绝；每个分支用独立会话令牌 |
| TC-I-INS-007 | 边界、异常 | P2 | 反 | 正文 > 1 MiB → HTTP 500 固定系统错误页，不泄露异常细节、不回显正文 |
| TC-I-MAKE-001 | 权限、异常 | P1 | 反 | PUT/DELETE/OPTIONS → 405 + `Allow` 含 GET/POST（ADM-6） |
| TC-I-MAKE-002 | 权限、安全 | P1 | 反 | 未登录 302 空正文；无权限 200「您没有权限访问此页面」；无来源头 → 非法请求 |
| TC-I-MAKE-003 | 异常、边界 | P1 | 反 | 20 组不合规取值（ps/tab/jump/ref/ac2/ids/label/num/start/数组）→ 全部 `MAKE_PARAM_ERR`，非 400 |
| TC-I-MAKE-004 | 边界 | P2 | 正 | 逐组断言状态码 200（非 302/403/400/415/5xx）、`text/html` 进度页（非 JSON 失败体、非参数错误）：IdList 数组受理；`ac` 数组/全字段缺失只出进度页不生成；未列字段忽略；反向对照 `ps=1001` 必为失败体 |
| TC-I-COLLECT-001 | 安全 | P1 | 反 | 33 个格式合法但目标被 OUT-1 拒的地址 → `COLLECT_URL_REJECTED`；禁止片段按每个输入动态生成（完整 URL / 主机含子域 / IPv4·IPv6 等价书写 / 端口），msg 与整份响应都检查 |
| TC-I-COLLECT-002 | 异常、边界 | P1 | 反 | `cjurl` 缺失/空/数组/超长/格式不合 + 其余字段规则 → 全部 `COLLECT_PARAM_ERR`，非 400 |
| TC-I-COLLECT-003 | 安全 | P1 | 反 | API-041 的 OUT-1 拒绝：GET 与 POST 同为 `code=1001`；去敏断言与 COLLECT-001 同法（按输入动态生成禁止片段） |
| TC-I-COLLECT-004 | 异常 | P1 | 反 | API-041 字段规则（含 `ac` 只允许 list）→ `code=1001` + 参数错误；失败体只有 code/msg |
| TC-I-IMAGES-001 | 异常、边界 | P1 | 正/反 | `limit=1` 与 `limit=100` 受理（正）；tab/col/range/date/opt/page/limit 不合规 → `IMAGES_PARAM_ERR`（反） |
| TC-I-METHOD-001 | 权限、异常 | P1 | 反 | 12 组（路径, 方法）逐条核对 `x-method-policy` → 405 + `Allow` 含受理方法 |
| TC-I-AUTH-001 | 权限、安全 | P1 | 正/反 | 6 个入口三档：未登录 302 / 无权限 200 无权限体（反）/ 有权限不出现无权限体（正） |
| TC-I-CSRF-001 | 安全、权限 | P1 | 反 | B 类入口跨站来源与外站 Origin 与三头全缺 → Ajax：200 JSON「非法请求」；非 Ajax：403 提示页「非法请求」；302/成功体/登录页一律判失败；含同源对照（SRC-1、SRC-2） |
| TC-I-LIMIT-001 | 边界、异常 | P2 | 反 | 参数个数 > 1000（查询串 / 表单 / 合计）→ `INPUT_LIMIT_EXCEEDED`（200 + 参数错误） |

## 未覆盖与原因（T3 报告需照抄）

| 项 | 原因 | 归属 |
| --- | --- | --- |
| OUT-1「未建立任何连接」的强证据 | 需要 `loopback-only` + mock 服务连接日志（技术设计 V-26），该配置生产不得开启 | T3 环境侧，非脚本可断言 |
| `TC-I-INS-003`～`007` 在已安装实例上 | `install.lock` 存在时分支不可达，脚本 skip → T3 记 BLOCKED | 需要一份未安装的安装模式实例 |
| `TC-I-INS-002` 在未安装实例上 | 需要 lock 已存在的实例，与上一条互斥 | 需要两份额外的安装模式实例（一份有 lock、一份无 lock） |
| 向导第 4 步之后的真实安装执行 | 会写被测实例的库与文件系统 | 仅一次性实例 + 明确授权时执行 |
| OUT-1 连接侧强证据（V-26 mock 连接日志） | 生产禁止开启 `loopback-only` | T3 环境侧 |
| API-042 / API-044 | 不在本轮派发范围 | 后续单独派发 |
| 会员会话类接口（API-002/007） | 不在本轮派发范围 | 后续单独派发 |
