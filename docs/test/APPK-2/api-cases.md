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
| TC-I-LIMIT-001 | 后台动作路径 | 边界、异常 | P2 | AC-16 / TRN-1 | `test_TC_I_LIMIT_001.py` |

## 安装向导用例的前置与判定（第 2 轮修订重点）

API-030 的判定是一条有序分支链（契约 INS-SEC-1～5 + 判定顺序）。**如果前置不成立，请求会停在更早的分支，
把「更早分支的固定文案」误当成目标分支，就是假通过。** 因此 `TC-I-INS-003`～`007` 的每条用例开头都自建前置并显式判定：

| 前置 | 建立方式 | 不成立时的处理 |
| --- | --- | --- |
| 安装模式实例（INS-SEC-1） | 目标实例以 `MACCMS_INSTALL_MODE=true` 启动；`GET /install.php?step=5` 返回 404 即为正常模式 | `pytest.skip("BLOCKED：…不是安装模式…")` |
| `install.lock` 不存在（INS-SEC-2） | 同一 GET 返回 INSTALL_LOCKED 固定文本（`install.lock` + 「如需重新安装请删除」） | `pytest.skip("BLOCKED：…已安装…")` |
| 会话令牌（INS-SEC-4） | 同一会话 `GET <install_entry>?step=3`，从隐藏字段 `__token__` 取 32 位小写十六进制 | 取不到 / 格式不符 → `pytest.skip("BLOCKED：…无法取得签发的 __token__")`，**不合成令牌** |
| 第 4 步连接配置（向导状态，INS-SEC-5） | 环境决定，脚本无法建立 | 目标分支是 `INSTALL_NOT_TESTED` 时按契约业务结果接受（见下） |

判定顺序相关结论（写进逐条用例，避免误判）：

- `INSTALL_NOT_TESTED` 与 `INSTALL_ENV_FAILED` 位于 account / install_dir / initdata 校验**之前**。因此在没有第 4 步
  连接配置的会话上，「account 数组按缺失受理」等用例的契约业务结果就是 `INSTALL_NOT_TESTED`；用例据此断言
  「越过了令牌，且没有出现 `INSTALL_ACCESS_DENIED` / 400」，同时断言没有停在目标分支之外的其他分支。
- 每个请求用**独立会话 + 该会话第 3 步签发的令牌**（令牌一次性，不能跨请求共用，也不能用合成令牌）。
- `TC-I-INS-005`（令牌一次性）与库状态解耦：第一次提交只要**不是** `INSTALL_ACCESS_DENIED`，即证明令牌被接受并作废；
  第二次用**完全相同的表单与 Cookie** 重放，必须停在 `INSTALL_ACCESS_DENIED`，且没有第二次安装结果。

## 逐条明细

### TC-I-INS-001（正常 / P1 / AC-12 / INS-SEC-1）

- 前置：目标实例为**正常模式**（未开启安装模式）。
- 步骤与预期：
  1. `POST /install.php?step=5`（表单含 account、密码字段、install_dir、initdata、`__token__`）→ HTTP 404 前台页（API-005）。
  2. 正文不含安装阶段痕迹（`系统安装成功`、`is_ok`、`install.lock`）。
  3. 正文不含部署配置与连接串（`MACCMS_DEPLOY_CONFIG`、`jdbc:`）。

### TC-I-INS-002（安全、回归 / P1 / AC-12 / INS-SEC-2）

- 前置：安装模式实例，且 `application/data/install/install.lock` 已存在。
- 步骤与预期：
  1. `POST /install.php?step=5` → HTTP 200、`text/html`。
  2. 正文含 `install.lock` 与「如需重新安装请删除」（INSTALL_LOCKED 原文）。
  3. 正文不含写库阶段文案（`系统安装成功`、`已存在数据表`、`文件哈希不一致`）。
  4. 响应 `Set-Cookie` 不含 `JSESSIONID`（不建会话）。
- 若实例未安装（无 lock），本条不成立：环境不具备 → T3 记 BLOCKED（由 Ins-002 的镜像用例 INS-003 起在未安装实例上执行）。

### TC-I-INS-003（异常、安全 / P1 / AC-12 / INS-SEC-3）

- 前置：安装模式（`GET ?step=5` 非 404）；`install.lock` 不存在（非 INSTALL_LOCKED 页）；全新会话（无向导状态、无令牌）。
- 步骤与预期（同一会话连续三次，互不依赖令牌）：
  1. `GET /install.php?step=5` → HTTP 200、`text/html` 跳转提示页，正文含「非法访问」（INSTALL_ACCESS_DENIED）。
  2. `POST` 且 `Content-Type: application/json` → 同上。
  3. `POST` 且 `Content-Type: text/plain` → 同上。
  4. 三个响应都不得命中更早分支文案（`请先点击测试数据库连接`、`初始失败`、`请填写管理账号和密码`、`已存在数据表`），
     都不得含安装执行痕迹（`系统安装成功`、`is_ok`、`开始安装`、`正在安装`）。

### TC-I-INS-004（安全、权限 / P1 / AC-12 / INS-SEC-4）

- 前置：安装模式；`install.lock` 不存在；**从未访问第 3 步的全新会话**（向导状态与令牌都不存在）。
- 步骤与预期：
  1. 该会话 `POST ?step=5`（含合成 `__token__`）→ HTTP 200、`text/html`，正文含「非法访问」。
  2. 不得命中更早分支文案（同上四项）。
  3. 正文不回显提交的令牌；不含 `JSESSIONID`；`Set-Cookie` 不新发 `install*` 会话。

### TC-I-INS-005（安全、权限 / P1 / AC-12 / INS-SEC-4 令牌一次性）

- 前置：安装模式；`install.lock` 不存在；同一会话从第 3 步取得真实 32 位小写十六进制 `__token__`（取不到 → BLOCKED）。
- 步骤与预期：
  1. 同一会话用该令牌按第 3 步表单字段完整提交 → 不得停在 `INSTALL_ACCESS_DENIED`（证明令牌被接受）。
  2. 第一次提交必须命中**某个安装阶段分支**（安装结果、`已存在数据表`、`初始失败`、`请先点击测试数据库连接` 之一），
     否则视为无法证明令牌被接受 → 失败（用例前置/实现问题）。
  3. 用完全相同的表单与 Cookie 重放 → HTTP 200、正文含「非法访问」，且不命中任何其他分支（令牌已作废）。
  4. 重放响应不含安装成功痕迹（不重复执行安装）。
  5. 若第一次真的完成安装：随后任意新会话提交 `?step=5` 必须停在 INSTALL_LOCKED（lock 已生成）。

### TC-I-INS-006（边界、异常 / P2 / AC-12 / SCH-1）

- 前置：安装模式；`install.lock` 不存在；**每个请求各自独立会话 + 该会话第 3 步签发的令牌**（取不到 → BLOCKED）。
- 步骤与预期：
  1. `account[]=…`（数组）→ 不是 400/413/415/422；不是 `INSTALL_ACCESS_DENIED`；结果为 `请填写管理账号和密码`
     （INSTALL_ADMIN_REQUIRED），环境无第 4 步连接配置时为 `请先点击测试数据库连接`（INSTALL_NOT_TESTED）。
  2. `install_dir[]=/other`（数组）→ 按缺失即 `/`；不得出现「新版只支持安装在网站根目录」（INSTALL_DIR_UNSUPPORTED）。
  3. `initdata=unexpected` → 不是「参数错误」，同样只落在 INSTALL_ADMIN_REQUIRED 之前的 INSTALL_NOT_TESTED 或安装阶段分支。

### TC-I-INS-007（边界、异常 / P2 / AC-12 / TRN-1）

- 前置：安装模式；`install.lock` 不存在；同一会话第 3 步签发的真实令牌（取不到 → BLOCKED）。
- 步骤与预期：
  1. `POST ?step=5`，表单正文 > 1 MiB（填充字段 1 MiB + 1 KiB）→ HTTP 500、`text/html` 固定系统错误页。
  2. 正文不含异常细节（`traceback`、`java.lang.`、`org.springframework`、`exception`、`maxuploadsize`、
     `sizeexceeded`、`jdbc:`、`install.sql`、`application/data`）。
  3. 正文不含安装执行/阶段文案，不回显超限表单内容。

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
- 步骤与预期（每步都断言：状态码 200、无 `Location`、`Content-Type` 为 `text/html`、不是 JSON 失败体、无「参数错误」）：
  1. `ids[]`/`vodtype[]`/`arttype[]`/`topic[]` 数组形态（GET 与 POST）→ 进度页。
  2. `ac[]=index&ac[]=rss`（数组）→ 只出进度页样式，不生成（无 `生成完毕`/`生成完成`/`成功生成`/`已生成`）。
  3. 全字段缺失（GET 与 POST）→ 不报错，进度页样式，不生成。
  4. 未列出的字段（`unexpected_field`）被忽略：与不带该字段的响应类型一致。
  5. 反向对照：`ps=1001` → HTTP 200 失败体（JSON 为 `code=0`「参数错误」，非 Ajax 为跳转页含「参数错误」），
     保证第 1～4 步的正向断言有区分力。

### TC-I-COLLECT-001（安全 / P1 / AC-10 / OUT-1-1～4、OUT-1-9）

- 前置：默认角色管理员有 collect 权限；出站模式 open。
- 步骤与预期：33 个「格式合法但目标被 OUT-1 拒」的地址逐一 `GET /collect/api?cjurl=…&ac=list`
  → HTTP 200 + `code=0` +「采集链接有误或不能为本地链接」；**msg 与整份响应**都不得含该输入的
  完整 URL、主机名（含子域写法）、该主机的 IPv4/IPv6 等价书写、显式端口。
  禁止片段按**每个输入动态生成**（不是固定清单），原文与 URL 解码后文本都检查。

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
- 步骤与预期（逐项，拒绝响应只认契约允许的两种形态）：
  1. `Sec-Fetch-Site: cross-site`，Ajax → HTTP 200 + `application/json` + `code=0` +「非法请求」；非 Ajax → HTTP 403 +
     `text/html` + 「非法请求」+ 提示页标题；**302 一律判失败**（未登录跳转不是来源拒绝）。
  2. 无 `Sec-Fetch-Site`、`Origin` 指向外站 → 同上拒绝。
  3. 三个来源头（`Sec-Fetch-Site`/`Origin`/`Referer`）全缺失（SRC-2），Ajax 与非 Ajax → 同上拒绝。
  4. 所有拒绝响应都不得含成功结果（进度页/采集完成/code=1），非 JSON 响应不得是登录页。
  5. 对照：同源 Ajax 请求不得出现「非法请求」，防止「全都拒绝」的伪通过。

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
| API-042 / API-044（定时采集、timming） | 不在本轮派发范围 | 后续单独派发 |
| 会员会话类接口（API-002/007） | 不在本轮派发范围 | 后续单独派发 |
