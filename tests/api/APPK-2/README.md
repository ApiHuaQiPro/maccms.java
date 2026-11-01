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

- `python -m pytest tests/api/APPK-2 --collect-only -q`：22 条用例（22 个文件）共收集 **24** 个测试函数（`INS-005`/`INS-006` 各多一个函数）；
  `-m install_wizard` 收集 7 条（含两个补充函数）、`-m input_limit` 收集 2 条（标记已注册，`--strict-markers` 通过）；
- `python tests/api/APPK-2/offline_check.py`：离线自检全部通过（导入无残留、安装向导判定函数仿真、
  CSRF-002 的来源拒绝判定仿真、API-041 业务体字段白名单、弱断言扫描）；
- `python -m compileall tests/api`：语法检查通过；
- `git diff --check`：无空白错误；
- 目录内不含任何账号、密码、令牌字面量；安装向导用例的密码/令牌是 `autotest-APPK-2-...` 合成值，
  **真实令牌在运行时从被测站第 3 步页面取得**（`INS-003`～`007`），不写死；
- 去敏判定（`conftest.assert_error_body_redacted`）用脱机样例验证过：对 `192.0.2.1`、`198.18.0.1`、`224.0.0.1`、
  `93.184.216.34`、`localhost`、`2130706433`、`0x7f000001`、`0177.0.0.1`、`::1`、`::ffff:127.0.0.1` 等输入，
  只要响应里出现完整 URL / 主机 / 等价 IP 书写 / 端口就判失败；对不泄露的合成错误体判通过。

## 安装向导用例的前置（第 2 轮修订）

`INS-003`～`007` 在用例开头自建前置，任一不成立就 `pytest.skip("BLOCKED：…")`，**不继续断言目标结果**：

1. 安装模式实例（INS-SEC-1）：`/install.php` 返回 404 → BLOCKED；
2. `install.lock` 不存在（INS-SEC-2）：返回 INSTALL_LOCKED 固定文本 → BLOCKED；
3. 同一会话第 3 步签发的真实令牌（INS-SEC-4）：取不到或不是 32 位小写十六进制 → BLOCKED，不合成令牌；
4. 第 4 步连接配置（向导状态）由环境决定，脚本不能建立。因此当目标分支位于 account 校验之前时
   （`INSTALL_NOT_TESTED`），用例按契约业务结果接受该分支，同时断言「没有被令牌拒绝、没有 400、
   没有命中其他分支」，避免把更早分支误判成目标分支。

逐条步骤与预期见 `docs/test/APPK-2/api-cases.md`。

## 已知边界（写在这里，T3 报告要照抄）

1. **OUT-1 连接侧的强证据不在自动化内**。自动化只断言「被拒 + 错误体不含目标信息」。
   「未建立任何连接」需要 `loopback-only` 模式下的 mock 服务连接日志（技术设计 V-26），
   该配置只在本机测试模式启用，生产不得开启，因此不在本脚本的断言范围内。
2. **安装向导用例依赖实例状态**。`INS-003`～`007` 需要「安装模式 + 未安装」的实例，已安装时分支不可达，
   脚本 `pytest.skip("BLOCKED：...")`，T3 记 BLOCKED 而不是 PASS；`INS-001` 需要正常模式实例，
   `INS-002` 需要「安装模式 + 已安装」实例。三类前置互斥，T3 至少需要两个实例（或重启切换模式）才能全覆盖。
   `INS-005`（令牌一次性）的判定与库状态解耦：第一次提交只要不是 `INSTALL_ACCESS_DENIED` 即证明令牌被接受。
3. **`collect/test` 是 CSRF C 类**（只取数、不入库），不受 SRC-2 约束，所以没有把它放进
   `TC-I-CSRF-001` 的 B 类清单。
