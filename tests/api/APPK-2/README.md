# APPK-2 接口用例（只写阶段）

本目录是 APPK-2 的接口用例与自动化脚本，**只写阶段成果**：写好后不执行、不发请求、不连任何环境。
执行（T3）在 G2.5 通过、智能调度在 Issue 写明「G2.5 PASS @提交号 + 测试环境地址」之后才进行。

## 依据

| 依据 | 版本 |
| --- | --- |
| T1 功能用例 | `docs/test/APPK-2/t1-cases.md` @ `a906fac5031e253a1af9d4bae037e5531d6a5411` |
| 接口契约 | `docs/api/APPK-2/openapi.yaml` @ `bc612e933aa67abab319b90534c5150c10a51452` |
| 用例清单 | `docs/test/APPK-2/api-cases.md`（逐条步骤与预期） |
| 追溯矩阵 | `docs/test/APPK-2/api-cases-matrix.md`（按 API / AC- 汇总） |
| 凭据变量名 | 产品仓 `MULTICA.md` §5 |

## 范围

只覆盖智能调度本轮派发的 API 与其契约边界：

- API-024 `/make/{makeAction}`（静态生成）
- API-030 `/install.php`（安装向导第 5 步）
- API-040 `/collect/api`、API-041 `/collect/test`（在线采集）
- API-043 `/images/sync`（图片同步）

横切面：SCH-1 传输形态、ADM-1/ADM-3/ADM-4/ADM-6（登录、来源校验、权限、方法）、
TRN-1 传输上限、INS-SEC-1～5、OUT-1-1～OUT-1-4 与 OUT-1-9（SSRF 与错误体去敏）、CSRF A/B/C 分类。

不在本条范围：API-001/002/007（前台）、API-010～018（数据库工具）、API-020～023/025（模板）、
API-042/044（定时采集与 timming，单独派发）。

## 目录与框架

```text
tests/api/
├── requirements.txt      pytest / requests / pytest-base-url / python-dotenv
├── pytest.ini            -ra --strict-markers --import-mode=importlib，testpaths=APPK-2
├── conftest.py           夹具、鉴权注入、失败体断言、脱敏工具
├── .env.example          只列变量名，值留空
└── APPK-2/
    └── test_TC_I_<模块>_<序号>.py   1 条用例 = 1 个文件 = 1 个测试函数
```

用例编号与文件名一一对应（`TC-I-INS-001` → `test_TC_I_INS_001.py` → `test_TC_I_INS_001`）。

## 凭据

脚本只从环境变量读取，变量名以 `MULTICA.md` §5 为准：

| 变量名 | 用途 |
| --- | --- |
| `APP_API_TOKEN` | 默认角色（有目标 `x-permission` 的管理员会话） |
| `APP_API_TOKEN_READONLY` | 只读 / 低权限角色（无目标 `x-permission`）：断言 200 无权限体 |
| `APP_API_TOKEN_OTHER` | 与默认角色同级的另一个管理员 |

后台会话的注入方式由人工配置 `ADMIN_AUTH_HEADER`（头名）或 `ADMIN_SESSION_COOKIE`（cookie 名）之一；
两者都留空时按 `Authorization: Bearer` 注入。两者同时配置 → 直接 BLOCKED（无法确定注入方式）。

变量未配置或为空 → `pytest.exit(..., 3)`，即 **BLOCKED**，不会跑出假的通过。

## 执行（仅 T3）

在仓库根目录：

```bash
python -m pip install -r tests/api/requirements.txt
python -m pytest tests/api/APPK-2 --base-url "<G2.5 判定的测试环境地址>" --admin-entry "/admin.php"
```

`--base-url` 缺失 → BLOCKED。`--base-url` 只接受 Issue 里最新 G2.5 PASS 行写明的测试环境地址。

## 本阶段的静态自检

- `python -m pytest tests/api/APPK-2 --collect-only -q`：**24** 条用例（24 个文件）共收集 **24** 个测试函数
  （1 条 CASE = 1 个文件 = 1 个测试函数；第 4 次修订 REV-I-06 把上一版挂在 `INS-005`/`INS-006` 文件里的两个
  补充函数拆成独立 CASE `INS-009`（锁终态）/ `INS-010`（密码长度边界））；
  `-m install_wizard` 收集 7 条（`INS-003`～`007`、`009`、`010`；`INS-001`/`002` 不需要向导前置，不带该标记）、`-m input_limit` 收集 2 条（标记已注册，`--strict-markers` 通过）；
- `python tests/api/APPK-2/offline_check.py`：离线自检全部通过（导入无残留、安装向导判定函数仿真、
  CSRF-002 的来源拒绝判定仿真与探针种类断言、API-041 业务体 `{code,msg}` 白名单与逐字 `wire_msg` 表、弱断言扫描）；
- `python -m compileall tests/api`：语法检查通过；
- `git diff --check`：无空白错误；
- 目录内不含任何账号、密码、令牌字面量；安装向导用例的密码/令牌是 `autotest-APPK-2-...` 合成值，
  **真实令牌在运行时从被测站第 3 步页面取得**（`INS-003`～`005`、`006`、`007`、`010`），不写死；
- 去敏判定（`conftest.assert_error_body_redacted`）用脱机样例验证过：对 `192.0.2.1`、`198.18.0.1`、`224.0.0.1`、
  `93.184.216.34`、`localhost`、`2130706433`、`0x7f000001`、`0177.0.0.1`、`::1`、`::ffff:127.0.0.1` 等输入，
  只要响应里出现完整 URL / 主机 / 等价 IP 书写 / 端口就判失败；对不泄露的合成错误体判通过。

## 安装向导用例的前置（第 2 轮修订）

`INS-003`～`007`、`010` 在用例开头自建前置，任一不成立就 `pytest.skip("BLOCKED：…")`，**不继续断言目标结果**：

1. 安装模式实例（INS-SEC-1）：`/install.php` 返回 404 → BLOCKED；
2. `install.lock` 不存在（INS-SEC-2）：返回 INSTALL_LOCKED 固定文本 → BLOCKED；
3. 同一会话第 3 步签发的真实令牌（INS-SEC-4）：取不到或不是 32 位小写十六进制 → BLOCKED，不合成令牌；
4. **第 4 步连接配置已完成**（`require_connection_configured`）。这一项由环境决定，脚本**不会**去建立它：
   发第 4 步的表单请求本身就是一次**写配置**的操作（PHP `install/index.php` step4 用 `mac_arr2file`
   落盘数据库配置），在只允许读的实例上是越界。缺该前置时，令牌之后的用例只会停在
   `INSTALL_NOT_TESTED`（"请先点击测试数据库连接！"）——那是**前置缺失**，不是被测分支。
   因此用例的判定是：控制请求（有效令牌 + 契约允许的入参）必须**越过** `INSTALL_NOT_TESTED`；
   越不过 → `pytest.skip("BLOCKED：第 4 步连接配置未完成…")`，绝不把该分支当作目标结果接受。

> **第 5 轮修订 REV-I-17（回应复核项 5）**：上一版第 4 条曾把「停在 `INSTALL_NOT_TESTED`」写成一种
> 可以接受的业务结果——这句话等于**把前置缺失当成了被测结果**，
> 只要环境没配连接，`INSTALL_NOT_TESTED` 就能让用例「通过」。现在改为：`INSTALL_NOT_TESTED` 只作为
> 前置缺失的证据（→ BLOCKED），令牌之后的用例必须拿到**越过该分支**的肯定证据才继续断言。
> 同时删掉第 2 条里「只要不是 `INSTALL_ACCESS_DENIED` 即证明令牌被接受」这种反证式写法（见下）。

逐条步骤与预期见 `docs/test/APPK-2/api-cases.md`。

## 已知边界（写在这里，T3 报告要照抄）

1. **OUT-1 连接侧的强证据不在自动化内**。自动化只断言「被拒 + 错误体不含目标信息」。
   「未建立任何连接」需要 `loopback-only` 模式下的 mock 服务连接日志（技术设计 V-26），
   该配置只在本机测试模式启用，生产不得开启，因此不在本脚本的断言范围内。
2. **安装向导用例依赖实例状态**。`INS-003`～`007`、`010` 需要「安装模式 + 未安装 + 第 4 步连接配置已完成」的实例，
   已安装时分支不可达、未配置连接时到不了令牌之后的分支，两种情况脚本都 `pytest.skip("BLOCKED：...")`，
   T3 记 BLOCKED 而不是 PASS；`INS-001` 需要正常模式实例，`INS-002`、`INS-009` 需要「安装模式 + 已安装」实例。
   三类前置互斥，T3 至少需要两个实例（或重启切换模式）才能全覆盖。
2a. **`INS-006` 第 C/D 步与 `INS-010` 的密码规则证据可能进入写库阶段**，必须有硬隔离：
   `MACCMS_INSTALL_ONEOFF_DB`（库名须匹配 `^maccms_it_[a-z0-9_]{1,40}$`，指向一次性库）、
   `MACCMS_INSTALL_ONEOFF_ROOT`（一次性站点根副本）、`MACCMS_INSTALL_WRITE_AUTH=yes`（精确值）。
   缺任一项 → BLOCKED；**不接受**「预期会失败所以可以写共享库」这类放宽。
   `TC-I-CSRF-002` 的 `make/make?ac=index` 探针会真的生成站点首页，需要 `MACCMS_UI_WRITE_AUTH=yes` +
   `MACCMS_UI_ONEOFF_ROOT`，缺则 BLOCKED。
2c. **`TC-I-OUT-002` 的控制样本必须由环境提供**（`APP_COLLECT_CONTROL_CJURL`，变量名登记在
   `MULTICA.md` §5）。未配置 → BLOCKED。不得用虚构域名（`cv.example.com` 永远不可达，
   控制样本只会得到 `COLLECT_FETCH_FAILED`，失去区分力），也不得用「任意 1001 + 非空 msg」顶替。
2b. **令牌是否被消耗，只能由「令牌之后的分支」证明。** 上一版 `INS-005` 写「第一次提交只要不是
   `INSTALL_ACCESS_DENIED` 即证明令牌被接受」——这是**反证**，而且方向错误：`INSTALL_ACCESS_DENIED` 是所有
   **令牌之前**分支的共同输出，`INSTALL_NOT_TESTED` 这类更早的分支同样不是 `INSTALL_ACCESS_DENIED`，
   于是一个根本没到令牌校验的请求也能"证明令牌被接受"。现在 `INS-004`/`INS-005` 的判定基准是
   **必须命中 `INSTALL_DIR_UNSUPPORTED`**（契约里位于令牌校验之后、写库之前的唯一可安全触达的分支），
   并为每个验证点补一次「正确令牌必须再次越过」的对照；重放（原令牌）则必须回到
   `INSTALL_ACCESS_DENIED`。停更早分支 → 对照失败 → **BLOCKED**；被令牌拒绝 → **FAIL**。
3. **`collect/test` 是 CSRF C 类**（只取数、不入库），不受 SRC-2 约束，所以没有把它放进
   `TC-I-CSRF-001` 的 B 类清单。
