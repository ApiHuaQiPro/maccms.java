# APPK-2 接口用例（只写阶段）

本目录是 APPK-2 的接口用例与自动化脚本，**只写阶段成果**：写好后不执行、不发请求、不连任何环境。
执行（T3）在 G2.5 通过、智能调度在 Issue 写明「G2.5 PASS @提交号 + 测试环境地址」之后才进行。

## 依据

| 依据 | 版本 |
| --- | --- |
| T1 功能用例 | `docs/test/APPK-2/t1-cases.md` @ `a906fac5031e253a1af9d4bae037e5531d6a5411` |
| 接口契约 | `docs/api/APPK-2/openapi.yaml` @ `bc612e933aa67abab319b90534c5150c10a51452` |
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

- `python -m pytest tests/api/APPK-2 --collect-only`：20 条用例全部可收集，与 20 个文件 1:1；
- `python -m compileall tests/api`：语法检查通过；
- 目录内不含任何账号、密码、令牌字面量；安装向导用例的密码/令牌是 `autotest-APPK-2-...` 合成值；
- `git diff --check`：无空白错误。

## 已知边界（写在这里，T3 报告要照抄）

1. **OUT-1 连接侧的强证据不在自动化内**。自动化只断言「被拒 + 错误体不含目标信息」。
   「未建立任何连接」需要 `loopback-only` 模式下的 mock 服务连接日志（技术设计 V-26），
   该配置只在本机测试模式启用，生产不得开启，因此不在本脚本的断言范围内。
2. **安装向导用例依赖实例状态**。`INS-*` 需要安装模式的实例；已安装（`install.lock` 存在）时
   相关分支不可达，脚本 `pytest.skip("BLOCKED：...")`，T3 记 BLOCKED 而不是 PASS。
   `INS-005`（令牌一次性）刻意只断言「同一令牌第二次必被拒」这一与库状态无关的行为。
3. **`collect/test` 是 CSRF C 类**（只取数、不入库），不受 SRC-2 约束，所以没有把它放进
   `TC-I-CSRF-001` 的 B 类清单。
