# APPK-2 接口用例（只写阶段）

本文件是 APPK-2 接口用例的**用例清单**（编号、维度、前置、步骤与预期、追溯 AC-、自动化通道、优先级），
与 `docs/test/APPK-2/api-cases-matrix.md`（追溯矩阵，按 API / AC- 汇总与覆盖状态）配套使用，两者内容一致：
**用例编号集合与覆盖结论以矩阵为准，逐条步骤与预期以本文件为准**。
若两者出现冲突，以本文件逐条明细为准，并在 T2 覆盖评估中把矩阵改正。

- 阶段：**只写**。写完不执行、不发请求、不连任何环境；执行（T3）在 G2.5 通过之后。
- 依据：T1 功能用例 `docs/test/APPK-2/t1-cases.md` @ `a906fac`；接口契约 `docs/api/APPK-2/openapi.yaml` @ `bc612e93`；
  凭据变量名：产品仓 `MULTICA.md` §5。
- 自动化通道：pytest，1 条用例 = 1 个文件 = 1 个测试函数，位于 `tests/api/APPK-2/`，写法见
  `tests/api/APPK-2/README.md` 与 `multica-test-t3-api-automation`。

## 用例索引

| 用例编号 | 接口 | 维度 | 优先级 | 追溯 | 自动化文件 |
| --- | --- | --- | --- | --- | --- |
| TC-I-INS-001 | API-030 `/install.php?step=5` | 正常 | P1 | AC-12 / INS-SEC-1 | `test_TC_I_INS_001.py` |
| TC-I-INS-002 | API-030 | 安全、回归 | P1 | AC-12 / INS-SEC-2 | `test_TC_I_INS_002.py` |
| TC-I-INS-003 | API-030 | 异常、安全 | P1 | AC-12 / INS-SEC-3 | `test_TC_I_INS_003.py` |
| TC-I-INS-004 | API-030 | 安全、权限 | P1 | AC-12 / INS-SEC-4 | `test_TC_I_INS_004.py` |
| TC-I-INS-005 | API-030 | 安全、权限 | P1 | AC-12 / INS-SEC-4 | `test_TC_I_INS_005.py` |
| TC-I-INS-006 | API-030 | 边界、异常 | P2 | AC-12 / SCH-1 | `test_TC_I_INS_006.py` |
| TC-I-INS-007 | API-030 | 边界、异常 | P2 | AC-12 / TRN-1 | `test_TC_I_INS_007.py` |
| TC-I-MAKE-001 | API-024 `/make/{makeAction}` | 权限、异常 | P1 | AC-7 / ADM-6 | `test_TC_I_MAKE_001.py` |
| TC-I-MAKE-002 | API-024 | 权限、安全 | P1 | AC-8 / ADM-1、ADM-3、ADM-4 | `test_TC_I_MAKE_002.py` |
| TC-I-MAKE-003 | API-024 | 异常、边界 | P1 | AC-7 / MAKE_PARAM_ERR | `test_TC_I_MAKE_003.py` |
| TC-I-MAKE-004 | API-024 | 边界 | P2 | AC-7 / SCH-1 | `test_TC_I_MAKE_004.py` |
| TC-I-COLLECT-001 | API-040 `/collect/api` | 安全 | P1 | AC-10 / OUT-1-1～4、OUT-1-9 | `test_TC_I_COLLECT_001.py` |
| TC-I-COLLECT-002 | API-040 | 异常、边界 | P1 | AC-10 / COLLECT_PARAM_ERR | `test_TC_I_COLLECT_002.py` |
| TC-I-COLLECT-003 | API-041 `/collect/test` | 安全 | P1 | AC-10 / OUT-1、OUT-1-9 | `test_TC_I_COLLECT_003.py` |
| TC-I-COLLECT-004 | API-041 | 异常 | P1 | AC-10 / CollectTestParams | `test_TC_I_COLLECT_004.py` |
| TC-I-IMAGES-001 | API-043 `/images/sync` | 异常、边界 | P1 | AC-13 / IMAGES_PARAM_ERR | `test_TC_I_IMAGES_001.py` |
| TC-I-METHOD-001 | 后台动作路径 | 权限、异常 | P1 | AC-16 / ADM-6 | `test_TC_I_METHOD_001.py` |
| TC-I-AUTH-001 | API-040/041/043/024 | 权限、安全 | P1 | AC-16 / ADM-1、ADM-4 | `test_TC_I_AUTH_001.py` |
| TC-I-CSRF-001 | B 类后台动作 | 安全、权限 | P1 | AC-16 / SRC-1、SRC-2 | `test_TC_I_CSRF_001.py` |
| TC-I-CSRF-002 | API-041/024（探针） | 安全、权限 | P1 | AC-16 / ADM-3 顺序（来源校验先于登录） | `test_TC_I_CSRF_002.py` |
| TC-I-OUT-002 | API-041 `/collect/test` | 安全、异常 | P1 | AC-10、AC-16 / OUT-1-9、API-041 | `test_TC_I_OUT_002.py` |
| TC-I-LIMIT-001 | 后台动作路径 | 边界、异常 | P2 | AC-16 / TRN-1 | `test_TC_I_LIMIT_001.py` |

## 安装向导用例的前置与判定（第 3 轮修订：按契约判定顺序设计）

API-030 的判定是一条**有序**分支链（契约 `docs/api/APPK-2/openapi.yaml` @ `bc612e93`，INS-SEC-1～5 + 判定顺序）：

```
install.lock（INS-SEC-2）→ 方法（INS-SEC-3）→ 环境（INS-SEC-5）→ 第 4 步连接配置（INSTALL_NOT_TESTED）
→ account/密码存在（INSTALL_ADMIN_REQUIRED）→ account/密码规则（INSTALL_ADMIN_INVALID）
→ 令牌（INS-SEC-4）→ install_dir → root → 互斥区 → 写库阶段（INS-2 ①～⑥）→ 成功
```

**核心原则：一条用例只能在它目标的那个分支位置验证；命中更早分支不是通过，是 BLOCKED。**

| 目标分支 | 位置 | 用例 | 需要的前置 | 前置不成立时 |
| --- | --- | --- | --- | --- |
| 方法拒绝（INS-SEC-3） | install.lock 之后、环境之前 | INS-003 | 安装模式 + 无 lock（全新会话即可） | BLOCKED |
| 令牌拒绝（INS-SEC-4） | account/密码规则之后、install_dir 之前 | INS-004 | 安装模式 + 无 lock + 会话内第 3 步令牌 + **第 4 步连接配置** | BLOCKED |
| 令牌一次性（INS-SEC-4） | 令牌通过后（用 install_dir 分支观测） | INS-005 | 同上 | BLOCKED |
| account 数组按缺失（SCH-1） | INSTALL_ADMIN_REQUIRED 分支 | INS-006 | 安装模式 + 无 lock + 会话令牌 + 第 4 步连接配置 | BLOCKED |
| install_dir 数组 = `/` 与 initdata 非 1 | install_dir / 写库之前 | INS-006 | 同上（须先命中 INSTALL_DIR_UNSUPPORTED 证明越过了令牌） | BLOCKED |
| 正文上限（TRN-1） | 读正文时，业务判定之前 | INS-007 | 安装模式 + 无 lock + 会话令牌 | BLOCKED |

前置建立方式（统一在 `tests/api/APPK-2/install_support.py`，`TC-I-INS-001`～`007` 共用，不再各写一套）：

| 前置 | 建立方式 | 不成立时的处理 |
| --- | --- | --- |
| 安装模式（INS-SEC-1） | `GET <install_entry>?step=5`；404 即正常模式 | `pytest.skip("BLOCKED：…不是安装模式…")` |
| `install.lock` 不存在（INS-SEC-2） | 同一 GET 返回 INSTALL_LOCKED 固定文本（`install.lock` + 「如需重新安装请删除」） | `pytest.skip("BLOCKED：…已安装…")` |
| 会话令牌（INS-SEC-4） | 同一会话 `GET <install_entry>?step=3`，从隐藏字段 `__token__` 取 32 位小写十六进制；第 3 步页面打不开或环境检测失败也在此判定 | `pytest.skip("BLOCKED：…")`，**不合成令牌**、不写成 `assert` |
| 第 4 步连接配置（向导状态） | 环境决定（`MACCMS_DEPLOY_CONFIG` 里已有连接信息），脚本不发送第 4 步请求（会改部署配置） | 目标分支不可达 → **BLOCKED** |
| 令牌真的被消费 | 只有命中**令牌之后**的分支才算证据（`INSTALL_DIR_UNSUPPORTED`、root 拒绝、写库阶段各分支、成功） | 命中 `INSTALL_NOT_TESTED` / `INSTALL_ENV_FAILED` / `INSTALL_ADMIN_*` → BLOCKED |
| 令牌之后的分支可达 | 对照请求「有效令牌 + `install_dir=/sub`」命中 `INSTALL_DIR_UNSUPPORTED`（`control_proves_after_token`） | 未命中 → BLOCKED；被 `INSTALL_ACCESS_DENIED` 拒绝 → FAIL |

固定文案（逐字取自 `src/main/resources/lang/zh-cn.json`，`install_support.py` 里集中定义）：

| 错误码 | 文案 |
| --- | --- |
| `INSTALL_ACCESS_DENIED` | `非法访问` |
| `INSTALL_NOT_TESTED` | `请先点击测试数据库连接！` |
| 第 2/3 步环境失败 | `环境检测未通过，不能进行下一步操作！` |
| 第 5 步 `INSTALL_ENV_FAILED` | `初始失败！` |
| `INSTALL_ADMIN_REQUIRED` | `请填写管理账号和密码！` |
| `INSTALL_DIR_UNSUPPORTED` | `新版只支持安装在网站根目录（install_dir 为 /）` |
| `INSTALL_ROOT_REFUSED` | `安装向导默认不接受 root 账号…` |
| `INSTALL_CONNECT_FAILED` | `数据库连接失败，请检查数据库配置！` |
| `INSTALL_INIT_DATA_FAILED` | `导入初始化数据SQL失败…` |
| `INSTALL_ADMIN_FAILED` | `管理员账号创建失败` |
| 成功 | `系统安装成功，欢迎您使用苹果CMS建站` |

合成输入（不是凭据，`install_support.py` 内定义）：`account=autotestappk2admin`（符合 `^[A-Za-z0-9]+$`、
≤30 字符）、密码为 6～20 字符的合成串、`install_dir=/`、`initdata=0`。**不再使用带连字符的 `autotest-APPK-2-admin`**
（会先触发 `INSTALL_ADMIN_INVALID`，到不了令牌分支）。

### 离线自检工具（`tests/api/APPK-2/offline_check.py`）

只写阶段无法连测试环境，但「脚本自己会不会假通过」可以离线验证。`offline_check.py` 不联网、不发请求、
不读凭据值，纯计算地检查：

1. 每个测试文件的导入符号都被用到（残留死导入会误导复核）；
2. **判定函数仿真**：用真实的 `install_support` 判定函数对伪造响应体逐一验证 ——
   「令牌之前」文案（`请先点击测试数据库连接！`/`环境检测未通过…`/`请填写管理账号和密码！`/`初始失败！`）
   不得产生令牌之后证据；令牌之后文案（`INSTALL_DIR_UNSUPPORTED`/root 拒绝/成功）必须被识别；
   `INSTALL_ACCESS_DENIED` 与两侧都不混淆；`INSTALL_LOCKED` 页与「写 lock 失败」文案不互相误判；
   `control_proves_after_token` 的三条分支（通过 / BLOCKED / FAIL）走对；合成 account 与密码符合契约规则；
3. `assert not is_access_denied(...)` 后面必须跟目标分支的肯定断言或 `pytest.fail/skip` 处置，
   否则报「弱断言」。

运行：`python tests/api/APPK-2/offline_check.py`（当前输出全部 OK，退出码 0）。

## 逐条明细

### TC-I-INS-001（正常 / P1 / AC-12 / INS-SEC-1）

- 前置：目标实例为**正常模式**（未开启安装模式）。
- 步骤与预期：
  1. `POST /install.php?step=5`（合规合成表单）→ HTTP 404 前台页（API-005）。
  2. 正文不含安装阶段痕迹（`系统安装成功`、`is_ok`、`install.lock`）。
  3. 正文不含部署配置与连接串（`MACCMS_DEPLOY_CONFIG`、`jdbc:`）。

### TC-I-INS-002（安全、回归 / P1 / AC-12 / INS-SEC-2）

- 前置：安装模式实例，且 `application/data/install/install.lock` 已存在（与 INS-003～007 所需前置互斥）。
- 步骤与预期：
  1. `POST /install.php?step=5` → HTTP 200、`text/html`。
  2. 正文含 `install.lock` 与「如需重新安装请删除」（INSTALL_LOCKED 原文）。
  3. 正文不含写库阶段文案（`系统安装成功`、`管理员账号创建失败`、`导入初始化数据SQL失败`）。
  4. 响应 `Set-Cookie` 不含 `JSESSIONID`（不建会话）。
- `INSTALL_LOCKED` 位于方法/环境/account/令牌之前，故本条在全新会话上执行即可，不要求向导状态。

### TC-I-INS-003（异常、安全 / P1 / AC-12 / INS-SEC-3）

- 前置：安装模式（`GET ?step=5` 非 404）；`install.lock` 不存在（非 INSTALL_LOCKED 页）；全新会话。
  **不要求令牌前置**：INS-SEC-3 在环境、account、令牌之前判定，这是它在契约链上的正确位置。
- 步骤与预期（同一会话）：
  1. `GET ?step=5` → HTTP 200、`text/html` 跳转提示页（标题「跳转提示」），正文含「非法访问」。
  2. `POST` + `Content-Type: application/json` → 同上。
  3. `POST` + `Content-Type: text/plain` → 同上。
  4. `HEAD ?step=5` → HTTP 200（无正文，只判状态）。
  5. 三个有正文的拒绝响应都不得命中更早分支文案（`环境检测未通过，不能进行下一步操作！`、`请先点击测试数据库连接！`、
     `请填写管理账号和密码！`），不得含安装执行痕迹（`系统安装成功`、`is_ok`、`开始安装`、`正在安装`），
     不得回显 `JSESSIONID` / `install.token` / `jdbc:` / `MACCMS_DEPLOY_CONFIG`。

### TC-I-INS-004（安全、权限 / P1 / AC-12 / INS-SEC-4）

- 前置（**两相**，任一不成立即 BLOCKED）：
  - 相 1：安装模式；`install.lock` 不存在；同一会话第 3 步取得真实 32 位小写十六进制令牌（`wizard_session`）。
  - 相 2：**令牌之后的分支可达** —— 对照请求「有效令牌 + `install_dir=/sub`」必须命中 `INSTALL_DIR_UNSUPPORTED`
    （令牌之后、root 与写库之前，不写库、不写 lock）。契约判定顺序里 account/密码规则与「第 4 步连接配置」都在令牌之前，
    只有相 2 成立，本用例的「非法访问」才只能来自令牌判定。
    对照停在更早分支 → BLOCKED（skip 原因里写明命中了哪些令牌之前文案）；对照被令牌拒绝 → FAIL。
- 步骤与预期（每个验证点各用**独立会话**，互不消费对方的令牌）：
  1. 会话有令牌但表单**省略** `__token__` → HTTP 200、`text/html`、正文「非法访问」；不回显该会话令牌；
     随后同一会话补上正确令牌 + `install_dir=/sub` → 必须命中 `INSTALL_DIR_UNSUPPORTED`（排除伪拒绝）。
  2. 提交值与签发值**不符**（32 位 `f`）→ 同上形态，回显检查 + 补正确令牌的对照同上。
  3. `__token__[]=<token>`（数组形态）→ 按缺失处理，同样「非法访问」；同样补正确令牌的对照。
  4. 三条拒绝响应都不新发 `install*` 会话、不回显密码、不回显「账号:密码」。

### TC-I-INS-005（安全、权限 / P1 / AC-12 / INS-SEC-4 令牌一次性）

- 前置：安装模式；`install.lock` 不存在；同一会话第 3 步真实令牌（取不到 → BLOCKED）；第 4 步连接配置已完成。
- 主用例步骤与预期（`test_TC_I_INS_005`）：
  1. 第一次提交：**有效令牌 + `install_dir=/sub`**（令牌之后、写库之前的第一个分支）
     → 必须命中 `INSTALL_DIR_UNSUPPORTED`；命中 `INSTALL_ACCESS_DENIED` 判失败，
     命中 `INSTALL_NOT_TESTED` / 环境失败 / account 分支 → **BLOCKED**（证明不了令牌被消费）。
     该分支不写库、不写 lock，用例可重复执行。
     （注：上一轮把 `INSTALL_NOT_TESTED` / `INSTALL_ENV_FAILED` 当成「令牌已消费」的证据，属假通过，第 3 轮已删除。）
  2. 用**完全相同的表单与 Cookie** 重放 → HTTP 200、「非法访问」；不得再命中任何令牌之后的分支、不得出现安装结果。
  3. 对照：同一会话重新 `GET ?step=3` 取得**新令牌**，再提交「新令牌 + `install_dir=/sub`」→ 必须再次命中
     `INSTALL_DIR_UNSUPPORTED`（证明第 2 步的拒绝来自令牌一次性，不是会话/实例状态变化）。
- 补充用例（`test_TC_I_INS_005_installed_lock_terminal_state`）：锁终态。实例已安装时
  `GET ?step=5`、`POST ?step=5`（表单）、`POST ?step=5`（JSON 正文）、`GET ?step=3` 全部只返回 INSTALL_LOCKED 固定文本、
  不建会话、无安装痕迹；实例未安装 → BLOCKED（同一断言由 INS-002 在已安装实例上覆盖）。
- 「安装成功后自动生成 lock」由 INS-002（已安装实例）覆盖，本用例不改写被测实例。

### TC-I-INS-006（边界、异常 / P2 / AC-12 / SCH-1）

- 前置：安装模式；`install.lock` 不存在；**每个分支各自独立会话 + 该会话第 3 步签发的令牌**；第 4 步连接配置已完成。
- 步骤与预期：
  1. `account[]=…`（数组）→ 不是 400/413/414/415/422/431、不是参数错误、不被令牌拒绝、不是 `INSTALL_ADMIN_INVALID`
     → 必须命中 `请填写管理账号和密码！`（INSTALL_ADMIN_REQUIRED，位于连接配置之后、令牌之前）；
     命中 `INSTALL_NOT_TESTED` → BLOCKED。
  2. 对照（先跑）：`install_dir=/other`（标量）必须命中 `INSTALL_DIR_UNSUPPORTED`，否则 BLOCKED
     （这是「令牌之后可达」的可重复证据，保证第 3 步的否定结论有区分力）。
  3. `install_dir[]=/other`（数组，按缺失即 `/`）→ 必须命中**某条令牌之后的分支**（`assert_after_token_evidence`），
     且**不得**出现 `INSTALL_DIR_UNSUPPORTED`（出现即判失败：数组被按值判定）；令牌被拒 → 失败；
     一条令牌之后分支都没命中 → BLOCKED。
  4. `initdata=1` 与 `initdata=unexpected`（都带 `install_dir=/other` 走到令牌之后同一个分支）：
     两次都必须命中 `INSTALL_DIR_UNSUPPORTED`，且命中的令牌之后分支集合**完全相等**
     （initdata 不影响写库之前的判定）。「非 1 不写初始数据」在写库之后才可观测，列入「未覆盖与原因」。
- 补充用例（`test_TC_I_INS_006_boundary`）：密码长度边界 5 / 21 字符 → `INSTALL_ADMIN_INVALID`（长度不符合要求）；
  6 字符 → 不得命中 `INSTALL_ADMIN_INVALID`。这些分支在令牌之前，与连接配置无关。

### TC-I-INS-007（边界、异常 / P2 / AC-12 / TRN-1）

- 前置：安装模式；`install.lock` 不存在；同一会话第 3 步签发的真实令牌（取不到 → BLOCKED）。
- 步骤与预期：
  1. `POST ?step=5`，表单正文 > 1 MiB（填充字段 1 MiB + 1 KiB）→ HTTP 500、`text/html` 固定系统错误页。
  2. 正文不含异常细节（`traceback`、`java.lang.`、`org.springframework`、`exception`、`maxuploadsize`、
     `sizeexceeded`、`jdbc:`、`install.sql`、`application/data`）。
  3. 正文不含安装执行/阶段文案（令牌前后两组固定文案都检查），不回显超限表单内容，不回显会话令牌。

### TC-I-MAKE-001（权限、异常 / P1 / AC-7 / ADM-6）

- 前置：默认角色管理员有 make 权限。
- 步骤与预期：
  1. `PUT /admin.php/make/index` → 405，`Allow` 含 `GET` 与 `POST`，正文不含内部细节。
  2. `DELETE` → 405，`Allow` 含 `GET`。
  3. `OPTIONS` → 405（契约方法表只列 GET/POST）。

### TC-I-MAKE-002（权限、安全 / P1 / AC-8 / ADM-1、ADM-3、ADM-4）

- 步骤与预期：
  1. 未登录 `GET /make/make` → 302，`Location` 指向登录页，空正文。
  2. `APP_API_TOKEN_READONLY`（无 make 权限）→ HTTP 200 + `application/json` + `code=0` + 「您没有权限访问此页面」。
  3. 无来源头的 Ajax 请求 → 200 JSON「非法请求」（SRC-1）。
  4. 默认角色 → 不出现无权限体。

### TC-I-MAKE-003（异常、边界 / P1 / AC-7 / MAKE_PARAM_ERR）

- 步骤与预期：20 组不合规取值（`ps`、`tab`、`jump`、`ref`、`ac2`、`ids`、`label`、`num`、`start`、数组形态）逐一请求
  → HTTP 200（不 400）+ `code=0` +「参数错误」。

### TC-I-MAKE-004（边界 / P2 / AC-7 / SCH-1）

- 前置：默认角色管理员，Ajax 同源会话。
- **进度页签名**（REV-I-04：普通 200 HTML 错误页不能算进度页）。取自已批准实现的稳定特征，与站点数据无关：
  - `MakeService.STYLE`（`src/main/java/com/maccms/backend/staticgen/application/MakeService.java:48`）：
    `<style type="text/css">`、`body{font-size:12px;color: #333333;line-height:21px;}`、`span{font-weight:bold;color:#FF0000}`；
  - `mac_jump` → progress.js（`AdminMakeActions.progressJump`）片段：`/admin-assets/progress.js`、`data-url=`、
    `data-seconds=`、「如果您的浏览器没有自动跳转，请点击这里」、「暂停…秒后继续」。
- 步骤与预期（每步都断言：状态码 200、无 `Location`、`Content-Type` 为 `text/html`、不是 JSON 失败体、无「参数错误」、
  **样式签名 3 项齐全**）：
  1. `ids[]`/`vodtype[]`/`arttype[]`/`topic[]` 数组形态（GET 与 POST）→ 进度页，且跳转签名 6 项齐全（ac 合法必 `jumpTo`）。
  2. `ac[]=index&ac[]=rss`（数组）→ 只出进度页样式，不生成：无生成条目（`<a target="_blank" href=…><font color=…>`）、
     无「生成失败：」行、无跳转片段、无 `生成完毕`/`生成完成`/`成功生成`/`已生成`。
  3. 全字段缺失（GET 与 POST）→ 不报错，样式签名齐全，同样不生成、不跳转。
  4. 未列出的字段（`unexpected_field`）被忽略：与不带该字段的请求**业务结果逐项一致** ——
     `Content-Type`、样式签名、**生成条目集合**、**生成失败行集合**、**跳转地址与秒数**全部相等，
     且对照组必须真的产出了生成条目（否则判失败，避免空集合相等造成的假通过）。
  5. 反向对照：`ps=1001` → HTTP 200 失败体（JSON 为 `code=0`「参数错误」，非 Ajax 为跳转页含「参数错误」），
     且失败体里没有生成条目，保证第 1～4 步的正向断言有区分力。

### TC-I-COLLECT-001（安全 / P1 / AC-10 / OUT-1-1～4、OUT-1-9）

- 前置：默认角色管理员有 collect 权限；出站模式 open。
- 步骤与预期：33 个「格式合法但目标被 OUT-1 拒」的地址逐一 `GET /collect/api?cjurl=…&ac=list`
  → HTTP 200 + `code=0` +「采集链接有误或不能为本地链接」；**msg 与整份响应**都不得含该输入的
  完整 URL、主机名（含子域写法）、该主机的 IPv4/IPv6 等价书写、显式端口。
  禁止片段按**每个输入动态生成**（不是固定清单），原文与 URL 解码后文本都检查。
  IPv6 按**等价类**禁用（REV-I-05）：原样、去/加方括号、大小写、`ipaddress` 规范化压缩形式、完全展开形式
  （`fc00::1` ↔ `fc00:0000:0000:0000:0000:0000:0000:0001`）、内嵌 IPv4 的两种写法（`::ffff:7f00:1` / `::ffff:127.0.0.1`）。
  该函数纯离线计算，T3 执行前已做离线自检（见回复中的静态检查输出）。

### TC-I-COLLECT-002（异常、边界 / P1 / AC-10 / COLLECT_PARAM_ERR）

- 步骤与预期：
  1. `cjurl[]` 数组形态 → HTTP 200 +「参数错误」。
  2. 逐项字段规则（cjurl 缺失/空/超长/相对/非 http/带用户信息/带片段/含空白/含控制字符；`cjflag`、`mid`、`type`、`ac`、
     `t`、`pg`、`page`、`h`、`rday`、`ids`、`wd`、`param`、`opt`、`filter`、`sync_pic_opt`、`filter_from`）
     → 每条都是 HTTP 200（不 400）+ `code=0` +「参数错误」。

### TC-I-COLLECT-003（安全 / P1 / AC-10 / OUT-1、OUT-1-9）

- 步骤与预期：13 个被 OUT-1 拒的目标，`GET` 与 `POST /collect/test` 各一遍 → HTTP 200 + `application/json` +
  `code=1001` +「采集链接有误或不能为本地链接」；msg 与整份响应按**每个输入动态生成**的禁止片段检查去敏。

### TC-I-COLLECT-004（异常 / P1 / AC-10 / CollectTestParams）

- 步骤与预期：`ac` 只允许 `list`；其余字段不合 x-business-rule 逐项请求 → `code=1001` +「参数错误」；
  失败体只有 code/msg。

### TC-I-IMAGES-001（异常、边界 / P1 / AC-13 / IMAGES_PARAM_ERR）

- 步骤与预期：
  1. 正：`page=1&limit=1`、`limit=100` → HTTP 200 且不判「参数错误」。
  2. 反：tab/col/range/date/opt/page/limit 逐项不合规（GET 与 POST）→ HTTP 200 + `code=0` +「参数错误」。

### TC-I-METHOD-001（权限、异常 / P1 / AC-16 / ADM-6）

- 步骤与预期：12 组（路径, 方法）逐条请求 → 405 + `Allow` 含该路径受理的方法，正文不含内部细节。

### TC-I-AUTH-001（权限、安全 / P1 / AC-16 / ADM-1、ADM-4）

- 步骤与预期：6 个入口三档——
  1. 未登录 → 302 + `Location` 含 `login` + 空正文。
  2. `APP_API_TOKEN_READONLY` → HTTP 200 +「您没有权限访问此页面」。
  3. 默认角色 → 不出现无权限体。

### TC-I-CSRF-001（安全、权限 / P1 / AC-16 / SRC-1、SRC-2）

- 前置：默认角色管理员；B 类入口 = `make/make`、`collect/api`、`images/sync`（GET 与 POST）。
- 步骤与预期（REV-I-04：**每个请求显式声明形态并断言该形态的契约输出**，不再按响应实际的 Content-Type 反推）：
  1. `Sec-Fetch-Site: cross-site`：
     - Ajax 形态 → 只接受 HTTP 200 + `application/json` + `code=0` +「非法请求」+ 字段集合 ⊆ {code,msg,data,url,wait}；
       收到 403/HTML 判**失败**。
     - 非 Ajax 形态 → 只接受 HTTP 403 + `text/html` + 标题「跳转提示」+ 正文「非法请求」+ 不是 JSON 体；
       收到 200 JSON 判**失败**。
     - 302 一律判失败（未登录跳转不是来源拒绝）。
  2. 无 `Sec-Fetch-Site`、`Origin` 指向外站（SRC-1 回退分支）：Ajax 与非 Ajax 各一遍，形态要求同上。
  3. 三个来源头（`Sec-Fetch-Site`/`Origin`/`Referer`）全缺失（SRC-2）：Ajax 与非 Ajax 各一遍，形态要求同上。
  4. 所有拒绝响应都不得含成功结果（进度页/采集完成/code=1）。
  5. 同源对照**必须证明请求真的通过了来源校验并到达业务层**（不是「没出现非法请求」就算过）：
     `collect/api` 同源 Ajax 请求 → 非 302、非 403、无「非法请求」，且 `Content-Type` 为 `application/json`、
     响应体含业务字段 `code`；同源非 Ajax 请求 → 非 403、无「非法请求」。

### TC-I-CSRF-002（安全、权限 / P1 / AC-16 / ADM-3 顺序）

> T2 补充用例（第 3 轮复核后追加）：`TC-I-CSRF-001` 的对照请求恒带有效会话，302 在 001 里是被允许的，
> 因此「来源校验被放到登录之后」或「来源校验全放行」的实现在 001 下仍可能保持绿。本条用**无凭据**会话把两者分开。

- 前置：无（不需要令牌）；探针入口 `collect/test`（C 类只读，实现恒返回 `code`）与 `make/make`（缺 `ac` 即参数错误，不入生成循环）。
- 步骤与预期（对每个探针）：
  1. 无凭据 + 同源（`Sec-Fetch-Site: same-origin`，不注入令牌）→ **必须是 302** 且 `Location` 含 `login`/`index`；
     不得是来源拒绝（证明来源已先于登录通过）。
  2. 无凭据 + 跨站（`Sec-Fetch-Site: cross-site`）→ Ajax 形态必为 200 JSON `msg=非法请求`；
     非 Ajax 形态必为 403 + `text/html` + 标题「跳转提示」+ 正文「非法请求」；**不得是 302**。
  3. 有会话 + 同源 → 200 + `application/json` + 业务体含 `code` 与 `msg`（不是只排除「非法请求」文案）。
  4. 有会话 + 跨站 → 仍为来源拒绝，且拒绝体字段不超过 `code/msg/data/url/wait`。
- 失败判据：“同源无凭据请求停在来源拒绝”说明实现过严而非过宽，但同样与 ADM-3 顺序不符，按 FAIL 处理（交实现层确认是顺序问题还是用例假设问题）。

### TC-I-OUT-002（安全、异常 / P1 / AC-10、AC-16 / OUT-1-9、API-041）

> T2 补充用例。`TC-I-COLLECT-001` 只覆盖 API-040（B 类、`ac=list`、页面形态），
> API-041 是同一套 OUT-1 下唯一返回**固定业务体**（`{code,msg}`，PHP `return json($res)`）的采集动作，
> 也是错误体最容易顺手带上目标 URL 的地方（PHP 原文会附 `url:`/`response:`，契约登记为 SEC-NET-1 差异）。
> 另外它的页面提交方式是 POST（`collect-info.js:37` 的 `$.post`），只测 GET 会漏掉「POST 走了另一条返回分支」。

- 前置：默认角色会话；不要求测试环境真有该采集链接（全部样本都期望业务失败）。
- 样本：14 个 OUT-1 拒绝目标（回环/私网/链路本地/云元数据/元数据主机名/IPv4 映射 IPv6/内部服务端口/端口 0）、
  3 个协议不合规（`ftp:`、带用户信息、带 `#` 片段）、缺 `cjurl`、9000 字符超长 `cjurl`。
- 步骤与预期：
  1. 每个拒绝目标先 GET、再 POST → 都是 HTTP 200 + `application/json` + `code≠1` + 非空固定 `msg`；
  2. GET 与 POST 的 `code`、`msg` 逐字相等（契约：GET 与 POST 等价）；
  3. 业务体字段限于白名单（`code/msg/data` 与成功体的分页分类字段），**不得出现** `url`/`host`/`response`/`cjurl`；
  4. `msg` 与整份响应按 `assert_error_body_redacted(msg, cjurl)` 做 OUT-1-9 去敏（完整 URL、主机及子域、
     IPv4 等价书写、IPv6 等价类、显式端口）——禁止片段按每个输入动态生成，不是固定字符串表；
  5. 缺 `cjurl` 与超长 `cjurl` → 不得 5xx；JSON 形态按 1～4 断言，非 JSON 形态至少不得回显整段入参。

### TC-I-LIMIT-001（边界、异常 / P2 / AC-16 / TRN-1）

- 步骤与预期：
  1. 查询串 1001 个参数（`make/make`、`collect/api`、`images/sync`）→ HTTP 200 + `code=0` +「参数错误」。
  2. 表单 1001 个参数（`POST images/sync`）→ 同上。
  3. 查询串 600 + 表单 600（合计 1200）→ 同上。

## 未覆盖与原因（T3 报告需照抄）

| 项 | 原因 | 归属 |
| --- | --- | --- |
| OUT-1「未建立任何连接」的强证据 | 需要 `loopback-only` + mock 服务连接日志（技术设计 V-26），该配置生产不得开启 | T3 环境侧，非脚本可断言 |
| `TC-I-INS-002` 在未安装实例上 | 需要 `install.lock` 已存在的实例，与 INS-003～007 需要的未安装实例互斥，需两份实例 | 环境 |
| `TC-I-INS-003`～`007` 在已安装实例上 | `install.lock` 存在时分支不可达，脚本 `skip` → T3 记 BLOCKED | 需要一份未安装的安装模式实例 |
| 安装向导第 4 步之后的真实安装执行（建表、写配置、写 lock） | 会改动被测实例的库与文件系统，T3 仅在一次性实例上执行且需明确授权 | 环境/授权 |
| `initdata` 非 1 时「确实没有写入初始数据」 | 只有写库阶段之后才可观测（需空库一次性实例）；脚本只断言「非 1 与 1 在写库之前的判定完全一致」 | 环境/授权 |
| `TC-I-INS-004`～`007` 的令牌之后分支 | 需要「第 4 步连接配置已完成」但仍未安装的实例；缺该前置 → BLOCKED | 环境 |
| API-042 / API-044（定时采集、timming） | 不在本轮派发范围 | 后续单独派发 |
| 会员会话类接口（API-002/007） | 不在本轮派发范围 | 后续单独派发 |
