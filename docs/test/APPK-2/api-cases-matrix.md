# APPK-2 接口用例追溯矩阵（只写阶段）

用例文件在 `tests/api/APPK-2/`，**1 条用例对应 1 个测试文件、1 个测试函数**；24 条用例共 24 个测试函数
（第 4 次修订 REV-I-06 把上一版挂在 `TC-I-INS-005` / `TC-I-INS-006` 文件里的两个补充函数拆成独立 CASE
`TC-I-INS-009`、`TC-I-INS-010`，恢复「1 条 CASE = 1 个 test = 1 个文件」）。依据：T1 `docs/test/APPK-2/t1-cases.md` @ `a906fac`，
接口契约 `docs/api/APPK-2/openapi.yaml` @ `bc612e93`。

> **T2 补充（第 3 轮复核之后追加，来源见 `t2-coverage.md`）**：
> `TC-I-CSRF-002`、`TC-I-OUT-002`、`TC-I-INS-009`、`TC-I-INS-010` 四条为 T2 覆盖评估发现的「假覆盖」补丁——
> 前两条补 ADM-3「来源校验先于登录」的区分力（CSRF-001 的对照请求带会话，302 会被放行）
> 与 API-041 的 GET/POST 业务体同构 + OUT-1-9 去敏（契约里唯一返回固定业务体的采集动作）；
> 后两条原为 INS-005 / INS-006 文件里的第二个 test，因追溯对象与前置互斥而拆成独立 CASE。
> 原有 20 条用例的编号、断言均未修改。

> **第 5 轮修订 REV-I-16：AC 分母与追溯重建（回应复核项 2）**
>
> 1. **修正 AC- 追溯错误**。上一版把在线采集追溯到 `AC-10`、把横切安全用例追溯到 `AC-16`，与 T1 @ `a906fac`
>    的 AC 表不符：T1 的 **AC-10 = FR-8 手机模板与语言包**、**AC-13 = FR-11 在线采集与远程存储**、
>    **AC-16 = FR-13 原表原结构守护**。现在在线采集 / 图片同步一律追溯 **AC-13**，
>    安装向导一律追溯 **AC-12**，静态生成追溯 **AC-7**，后台权限追溯 **AC-8**。
> 2. **横切安全用例不再伪称 AC-**。方法策略、鉴权、来源校验（ADM-1/ADM-3/ADM-4/SRC-1/SRC-2）、
>    OUT-1-9 去敏、传输上限（TRN-1）在 T1 @ `a906fac` 里**没有对应的 AC- 判定项**
>    （T1 §2.14 明确 TC-F-SEC-001～003「无直接 AC-」；§4 也把设计层安全项列为「无 AC- 支撑，不计入验收分母」）。
>    按 T1 的同一口径，这些用例的追溯列写 **「无 AC-（设计契约条款，不计入 AC 分母）」**，
>    T3 结论单列，失败照样立缺陷单，但**不改变任何 AC- 的裁决**。上一版把它们写成 AC-16 是虚增覆盖。
> 3. **删除「每条 AC- 至少一正一反」的说法**。T1 §3 自检第 1 行的「一正一反」覆盖的是 **T1 功能用例**
>    （每条 AC- 都有 UI/手工正反用例）；本文件只覆盖其中的接口子集，其中
>    AC-10、AC-11、AC-14～AC-20 完全没有接口用例，AC-12 的判定主体（真实安装执行、快照比对）
>    也在 T1 的**手工**通道里。把「每条 AC- 至少一正一反」写进本文件会造成「接口用例已覆盖全部 AC-」的误读，
>    因此改为下表逐 AC- 标出「接口用例覆盖范围 / 未覆盖部分归属」。
> 4. **API-030 的用例数从 10 改为 9**：`TC-I-INS-008` **不存在**（上一版矩阵把它算进 API-030 的编号区间，
>    造成 10 条 vs 9 个文件的矛盾）。API-030 实际是 INS-001～007、009、010 共 9 条，
>    pytest 收集数与目录文件数一致（24 = 9 + 4 + 4 + 2 + 1 + 4 ... 见「按 API 汇总」）。
> 5. **脚本头部的追溯行与本文件同步**：`test_TC_I_*.py` 首行的 `追溯:` 与该用例在本矩阵的追溯列一致。

**配套文件**：逐条用例的前置、步骤与预期、判定说明在 `docs/test/APPK-2/api-cases.md`（G2 约定的用例清单文件名）。
两份文档的**用例编号集合与覆盖结论一致**，可以互相替代查找：编号集合、按 API / 按 AC- 汇总、覆盖状态以本矩阵为准；
每条用例的具体步骤与预期以 `api-cases.md` 为准（冲突时以 `api-cases.md` 为准并在 T2 改正本矩阵）。

## 测试函数清单（pytest 收集数 = 24）

每条用例的文件名与测试函数同名同编号，不一一列出：`TC-I-INS-001` → `test_TC_I_INS_001.py` → `test_TC_I_INS_001`。
`tests/api/APPK-2/README.md` 有同一张对照表。

## 按 API 汇总

| API | 路径 | 用例数 | 用例编号 |
| --- | --- | --- | --- |
| API-024 | `/make/{makeAction}` | 4 | TC-I-MAKE-001～004 |
| API-030 | `/install.php`（step=5） | 9 | TC-I-INS-001～007、009、010（**无 INS-008**） |
| API-040 | `/collect/api` | 2 | TC-I-COLLECT-001～002 |
| API-041 | `/collect/test` | 4 | TC-I-COLLECT-003～004、TC-I-OUT-002 |
| API-043 | `/images/sync` | 1 | TC-I-IMAGES-001 |
| 横切 | 方法策略 / 鉴权 / 来源校验 / 传输上限 | 5 | TC-I-METHOD-001、TC-I-AUTH-001、TC-I-CSRF-001、TC-I-CSRF-002、TC-I-LIMIT-001 |
| **合计** | | **24 条用例 / 24 个测试函数** | |

> 归类说明：OUT-002 的被测接口是 API-041，本版按**被测接口归属**放回 API-041（3 条）；横切行只留真正跨接口的 5 条。
> 核对：4（API-024）+ 9（API-030）+ 2（API-040）+ 3（API-041）+ 1（API-043）+ 5（横切）= **24**。

## 按 AC- 追溯（只标接口用例能判定的部分）

| AC- | 来源（T1 @ a906fac） | 接口用例 | 覆盖的契约条款 | 接口用例**未覆盖**的部分与归属 |
| --- | --- | --- | --- | --- |
| AC-7 | FR-6 静态生成 | TC-I-MAKE-001、TC-I-MAKE-003、TC-I-MAKE-004 | `MakeParams` 各字段 `x-business-rule`、`MAKE_PARAM_ERR`、SCH-1、进度页形态 | AC-7 的判定项是 K-01～K-12 的**逐字节产物比对**与两行恢复记录 → T1 `TC-F-GEN-001～003、005`（接口+手工，需 PHP 对照实例） |
| AC-8 | FR-7 后台模板管理 | TC-I-MAKE-002 | ADM-1（302）、ADM-3、ADM-4（200 无权限体）、`x-permission` | 保存即生效、`<?php` 拒绝、`../` 拒绝、并发冲突、W-01～W-10 的产物一致性 → T1 `TC-F-ADM-001～006、008、009` |
| AC-12 | FR-10 安装向导 | TC-I-INS-001～007、009、010（9 条） | INS-SEC-1～5、判定顺序、`INSTALL_*` 固定文案、SCH-1、TRN-1 | AC-12 的判定项（空库走完向导 + SP-2 快照、`install.sql` 哈希一致性、`it_app` 无 DDL、初始化数据一致）→ T1 `TC-F-INS-001～004`（手工，`general_log` + 快照取证）；本批接口用例只覆盖**拒绝路径与判定顺序**，不覆盖真实安装执行 |
| AC-13 | FR-11 在线采集与远程存储 | TC-I-COLLECT-001～004、TC-I-OUT-002 | OUT-1-1～9、`COLLECT_PARAM_ERR`/`COLLECT_URL_REJECTED`/`COLLECT_FETCH_FAILED`/`COLLECT_PARSE_FAILED` 固定文案、API-041 GET/POST 同位置等价 | 五项在线功能与 PHP 对**同一模拟数据**的结果一致、出站白名单与护栏 → T1 `TC-F-NET-001～003`（接口，需 mock 服务） |
| 无 AC-（设计契约条款） | — | TC-I-METHOD-001（ADM-6）、TC-I-AUTH-001（ADM-1/ADM-4）、TC-I-CSRF-001～002（SRC-1/SRC-2、ADM-3 顺序）、TC-I-OUT-002 的 OUT-1-9 去敏、TC-I-LIMIT-001（TRN-1） | ADM-1/3/4/6、SRC-1/2、OUT-1-9、TRN-1 | 按 T1 §2.14 与 §4 的同一口径：这些是**设计契约条款，不是 PRD AC- 判定项**，不进 AC 分母。T3 结论单列，失败照样立缺陷单，但不改变任何 AC- 的裁决 |

> 上一版在本节写的「每条 AC- 至少一正一反」已删除（REV-I-16 第 3 条）：它把 T1 功能用例的正反覆盖
> 误写成接口用例的性质。本文件只覆盖 AC-7、AC-8、AC-12、AC-13 的**接口可判定子集**；
> AC-1～AC-6、AC-9～AC-11、AC-14～AC-20 没有接口用例，其裁决完全依赖 T1 的 UI / 手工通道。

## 逐条明细

「追溯」列与 `api-cases.md` 的用例索引、`test_TC_I_*.py` 首行 `追溯:` 三处一致。

| 用例 | 追溯 | 维度 | 优先级 | 正/反 | 关键断言 |
| --- | --- | --- | --- | --- | --- |
| TC-I-INS-001 | AC-12 / INS-SEC-1 | 正常 | P1 | 反 | 非安装模式 `/install.php` → 404（API-005），无安装痕迹、无配置泄露 |
| TC-I-INS-002 | AC-12 / INS-SEC-2 | 安全、回归 | P1 | 反 | `install.lock` 存在 → 200 纯文本 `INSTALL_LOCKED` 原文，不建会话、不进写库阶段（全新会话即可，该分支位于方法/令牌之前） |
| TC-I-INS-003 | AC-12 / INS-SEC-3 | 异常、安全 | P1 | 反 | 安装模式 + 无 lock + 全新会话（INS-SEC-3 在环境/令牌之前，按其契约位置执行）：GET / JSON POST / text-plain POST → `INSTALL_ACCESS_DENIED`，不命中更早分支、无执行痕迹 |
| TC-I-INS-004 | AC-12 / INS-SEC-4 | 安全、权限 | P1 | 反 | **两相前置**：向导会话（第 3 步真实令牌）+ 对照「有效令牌 & install_dir=/sub」必须先命中 `INSTALL_DIR_UNSUPPORTED`（证明越过令牌）；对照停在更早分支 → BLOCKED、被令牌拒绝 → FAIL。省略令牌 / 令牌不符 / 数组令牌 → 各自 `INSTALL_ACCESS_DENIED`，且每个验证点后**补正确令牌必须再次越过**（排除伪拒绝）；不回显令牌与密码 |
| TC-I-INS-005 | AC-12 / INS-SEC-4 | 安全、权限 | P1 | 反 | 同一会话真实令牌 + install_dir=/sub，第一次必须命中 `INSTALL_DIR_UNSUPPORTED`（令牌之后、写库前的分支，不写库）；完全重放 → `INSTALL_ACCESS_DENIED`；再取新令牌 → 再次越过（对照） |
| TC-I-INS-006 | AC-12 / SCH-1 | 边界、异常 | P2 | 正/反 | SCH-1：`account` 数组必须命中 `INSTALL_ADMIN_REQUIRED`；`install_dir=/other` 标量对照必须命中 `INSTALL_DIR_UNSUPPORTED`；`install_dir[]` 数组**不得**命中 `INSTALL_DIR_UNSUPPORTED`（否则是按值判定），且必须命中一条**写库阶段之内或之后**的分支（命中不了 → BLOCKED）；`initdata` 非 1 与 1 命中的令牌之后分支集合完全相等。**前置**：写库闸门（一次性 `maccms_it_*` 空库 + 一次性站点根副本 + 精确 `yes` 授权）+ 第 4 步连接配置已完成，任一缺失 → BLOCKED |
| TC-I-INS-007 | AC-12 / TRN-1 | 边界、异常 | P2 | 反 | 正文 > 1 MiB → HTTP 500 固定系统错误页，不泄露异常细节、不回显正文 |
| TC-I-INS-009 | AC-12 / INS-SEC-2 | 权限、页面状态 | P1 | 反 | 已安装实例（`install.lock` 存在）的锁终态：5 组（GET step=5 / POST 表单 step=5 / POST JSON 体 / GET step=3 / GET 首页）**全部** HTTP 200 + `INSTALL_LOCKED` 固定文本 + 不建安装会话 + 不签发令牌 + 无执行痕迹；未安装实例上 BLOCKED（与 TC-I-INS-005 前置互斥） |
| TC-I-INS-010 | AC-12 / SCH-1、INS-SEC-3 | 边界、异常 | P2 | 正/反 | password 长度边界（6～20，`mb_strlen` 按**字符**）：5 字符、21 字符 → `INSTALL_ADMIN_INVALID` 且不得进入令牌之后分支；6 字符（5 汉字+1 字母，16 字节）、20 字符（19 汉字+1 字母，58 字节）→ **不得**命中 `INSTALL_ADMIN_INVALID`，且必须有**肯定证据**越过密码规则（`INSTALL_ACCESS_DENIED` 或令牌之后分支），否则 BLOCKED；全部样本不得是 400/413/414/415/422/431、不得是「参数错误」。**前置**：第 4 步连接配置已完成 |
| TC-I-MAKE-001 | AC-7 / ADM-6 | 权限、异常 | P1 | 反 | PUT/DELETE/OPTIONS → 405 + `Allow` 含 GET/POST（ADM-6） |
| TC-I-MAKE-002 | AC-8 / ADM-1、ADM-3、ADM-4 | 权限、安全 | P1 | 反 | 未登录 302 空正文；无权限 200「您没有权限访问此页面」；无来源头 → 非法请求 |
| TC-I-MAKE-003 | AC-7 / MAKE_PARAM_ERR | 异常、边界 | P1 | 反 | 20 组不合规取值（ps/tab/jump/ref/ac2/ids/label/num/start/数组）→ 全部 `MAKE_PARAM_ERR`，非 400 |
| TC-I-MAKE-004 | AC-7 / SCH-1 | 边界 | P2 | 正 | 逐组断言 200 + `text/html` + **进度页签名**（MakeService.STYLE 3 项 + progress.js 跳转 6 项），普通 200 HTML 错误页不算；`ac` 数组/全字段缺失无生成条目、无跳转；未列字段与对照组的生成条目/失败行/跳转地址与秒数逐项相等；反向对照 `ps=1001` 必为失败体 |
| TC-I-COLLECT-001 | AC-13 / OUT-1-1～4、OUT-1-9 | 安全 | P1 | 反 | 33 个格式合法但目标被 OUT-1 拒的地址 → `COLLECT_URL_REJECTED`；禁止片段按每个输入动态生成（完整 URL / 主机含子域 / IPv4 等价书写 / **IPv6 等价类：压缩与完全展开、括号、大小写、内嵌 IPv4** / 端口），msg 与整份响应都检查 |
| TC-I-COLLECT-002 | AC-13 / COLLECT_PARAM_ERR | 异常、边界 | P1 | 反 | `cjurl` 缺失/空/数组/超长/格式不合 + 其余字段规则 → 全部 `COLLECT_PARAM_ERR`，非 400 |
| TC-I-COLLECT-003 | AC-13 / OUT-1、OUT-1-9 | 安全 | P1 | 反 | API-041 的 OUT-1 拒绝：GET 与 POST 同为 `code=1001`；去敏断言与 COLLECT-001 同法（按输入动态生成禁止片段） |
| TC-I-COLLECT-004 | AC-13 / CollectTestParams | 异常 | P1 | 反 | API-041 字段规则（含 `ac` 只允许 list）→ `code=1001` + 参数错误；失败体只有 code/msg |
| TC-I-IMAGES-001 | AC-13 / IMAGES_PARAM_ERR | 异常、边界 | P1 | 正/反 | `limit=1` 与 `limit=100` 受理（正）；tab/col/range/date/opt/page/limit 不合规 → `IMAGES_PARAM_ERR`（反） |
| TC-I-METHOD-001 | 无 AC-（ADM-6） | 权限、异常 | P1 | 反 | 12 组（路径, 方法）逐条核对 `x-method-policy` → 405 + `Allow` 含受理方法 |
| TC-I-AUTH-001 | 无 AC-（ADM-1、ADM-4） | 权限、安全 | P1 | 正/反 | 6 个入口三档：未登录 302 / 无权限 200 无权限体（反）/ 有权限不出现无权限体（正） |
| TC-I-CSRF-001 | 无 AC-（SRC-1、SRC-2） | 安全、权限 | P1 | 反 | 每个请求显式声明形态并按形态断言：Ajax → 只接受 200 JSON「非法请求」（403 HTML 判失败）；非 Ajax → 只接受 403 提示页「跳转提示」+「非法请求」（200 JSON 判失败）；302/成功体一律失败；同源对照必须到达业务层（JSON 含 code 字段） |
| TC-I-CSRF-002 | 无 AC-（SRC-1、SRC-2、ADM-3 顺序） | 安全、权限 | P1 | 正/反 | ADM-3 顺序两侧对照（**无凭据**会话）：同源 → 必为未登录 302（证明来源已通过）；跨站 → 必为来源拒绝（Ajax 200 JSON / 非 Ajax 403 提示页），不得是 302；有会话 + 同源 → 按探针种类断言各自业务输出（`collect/test` → 200 JSON 含 code/msg；`make/make` + `ac=index` → 200 `text/html` 进度页样式签名 + 生成条目 + 跳转签名，且不含「参数错误」）；有会话 + 跨站 → 仍为来源拒绝；另以 `make/make` 缺 `ac` 单独断言契约的「只出样式、不生成、不是错误」。**前置**：`make/make?ac=index` 会真的生成站点首页（API-024 非幂等），须有 UI 侧写隔离（`MACCMS_UI_WRITE_AUTH=yes` + `MACCMS_UI_ONEOFF_ROOT`），否则 BLOCKED |
| TC-I-OUT-002 | AC-13 / OUT-1-9 | 安全、异常 | P1 | 反 | API-041：15 个 OUT-1 拒绝目标 → 逐字 `(1001, 采集链接有误或不能为本地链接)`；6 个 cjurl 格式/协议不合规（含 1025 字符 = 上限 +1）→ 逐字 `(1001, 参数错误)` 且不得落到 OUT-1 文案；cjurl 缺失/空/数组 → `(1001, 参数错误)`；**失败体字段集合恰为 `{code, msg}`**；每个样本发**同位置**的 GET 与 POST 并要求 code/msg 逐字相等，另加一组 GET 查询串 ↔ POST 表单的跨位置对照；msg 与整份响应按输入动态生成禁止片段做 OUT-1-9 去敏；**控制样本必须由环境提供**（`APP_COLLECT_CONTROL_CJURL`，未配置 → BLOCKED，不得用虚构域名或「任意 1001」顶替），控制样本取数/解析失败 → BLOCKED、其它业务拒绝 → FAIL；**8192 字符样本必须归因**：200 JSON → 断言业务体，30x → FAIL（会话失效/重定向），5xx 或 200 非 JSON → FAIL（应用缺陷），400/403/404/405/413/414/431 → BLOCKED（容器前置拒绝），其它 → FAIL，**不静默跳过** |
| TC-I-LIMIT-001 | 无 AC-（TRN-1） | 边界、异常 | P2 | 反 | 参数个数 > 1000（查询串 / 表单 / 合计）→ `INPUT_LIMIT_EXCEEDED`（200 + 参数错误） |

## 未覆盖与原因（T3 报告需照抄）

| 项 | 原因 | 归属 |
| --- | --- | --- |
| AC-1～AC-6、AC-9～AC-11、AC-14～AC-20 的裁决 | 本批接口用例不覆盖这些 AC-，其判定完全由 T1 的 UI / 手工通道承担（见「按 AC- 追溯」表） | T1 对应用例 |
| AC-12 的真实安装执行（空库走完 + SP-2 快照 + `install.sql` 哈希 + 初始化数据一致） | 接口用例只覆盖拒绝路径与判定顺序；真实写库需一次性实例 + `general_log` + 快照取证 | T1 `TC-F-INS-001～004`（手工） |
| AC-7 的逐字节产物比对 | 需要 PHP 对照实例与本机静态目录 | T1 `TC-F-GEN-001～003、005` |
| AC-13 与 PHP 对同一模拟数据的结果一致 | 需要 mock 资源站服务（生产禁止开启 `loopback-only`） | T1 `TC-F-NET-001～003` |
| OUT-1「未建立任何连接」的强证据 | 需要 `loopback-only` + mock 服务连接日志（技术设计 V-26），该配置生产不得开启 | T3 环境侧，非脚本可断言 |
| `TC-I-INS-003`～`007` 在已安装实例上 | `install.lock` 存在时分支不可达，脚本 skip → T3 记 BLOCKED | 需要一份未安装的安装模式实例 |
| `TC-I-INS-002` 在未安装实例上 | 需要 lock 已存在的实例，与 INS-003～007 需要的未安装实例互斥 | 需要两份额外的安装模式实例（一份有 lock、一份无 lock） |
| `TC-I-INS-004`～`007`、`010` 的令牌之后分支 | 需要「第 4 步连接配置已完成、仍未安装」的实例；脚本用对照请求核验该前置，拿不到即 BLOCKED | 环境 |
| `TC-I-INS-006` 的第 C、D 步（可能进入写库阶段） | 需一次性空库 `MACCMS_INSTALL_ONEOFF_DB`（`maccms_it_*`）+ 一次性站点根副本 `MACCMS_INSTALL_ONEOFF_ROOT` + `MACCMS_INSTALL_WRITE_AUTH=yes`；缺任一 → BLOCKED | 环境 + 明确授权 |
| `initdata` 非 1 确实没写初始数据 | 写库阶段之后才可观测；脚本只断言「非 1 与 1 在写库之前的判定完全一致」 | 环境/授权 |
| 向导第 4 步之后的真实安装执行 | 会写被测实例的库与文件系统 | 仅一次性实例 + 明确授权时执行 |
| `TC-I-CSRF-002` 的 `make/make?ac=index` 探针 | 会真的生成站点首页（API-024 非幂等）；需 `MACCMS_UI_WRITE_AUTH=yes` + `MACCMS_UI_ONEOFF_ROOT` | 环境 + 明确授权 |
| `TC-I-OUT-002` 的控制样本 | 需环境侧提供一个本环境可访问、有授权的合法采集地址（`APP_COLLECT_CONTROL_CJURL`） | 环境 |
| API-042 / API-044 | 不在本轮派发范围 | 后续单独派发 |
| 会员会话类接口（API-002/007） | 不在本轮派发范围 | 后续单独派发 |
