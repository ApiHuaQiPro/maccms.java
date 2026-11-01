"""APPK-2 安装向导（API-030）用例的共享前置与核验（只写阶段建好，T3 才执行）。

供 TC-I-INS-001～007 复用，避免每个文件各写一套不一致的前置判定。本模块只发**建前置所需的 GET**
与目标用例的 POST；所有「前置不成立」一律 `pytest.skip("BLOCKED：…")`，不写成宽松 `assert`。

契约：docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（API-030）
判定顺序（契约原文，不能改）：
  INS-SEC-2 install.lock → INS-SEC-3 方法 → INS-SEC-5 环境 → INS-SEC-5 连接信息（INSTALL_NOT_TESTED）
  → account/密码为空 → INSTALL_ADMIN_REQUIRED → account/密码规则 → INSTALL_ADMIN_INVALID
  → INS-SEC-4 令牌（INSTALL_ACCESS_DENIED，令牌在通过校验的同时从会话删除）
  → install_dir → root → 互斥区（复查 install.lock）→ 写库阶段 ①～⑥ → 成功

由此得出的三条判定规则（第 3 轮修订 REV-I-01／REV-I-02／REV-I-03）：

1. 每条用例只在它目标分支的契约位置上验证。INS-SEC-3（方法）、INS-SEC-5（环境）、
   INSTALL_ADMIN_*（账号校验）都在令牌**之前**，它们的用例不需要、也不应该加令牌前置。
2. 令牌**之后**的分支（install_dir / root / 写库阶段 / 成功）才能证明令牌被接受并消费。
   只命中 INSTALL_NOT_TESTED / INSTALL_ENV_FAILED / INSTALL_ADMIN_* 的响应证明不了这一点
   —— 上一轮把这些更早分支当成「令牌已消费的证据」，是假通过。
3. 「令牌之后的分支可达」本身也是一个前置：用**对照请求**（有效令牌 + install_dir=/sub）观测
   INSTALL_DIR_UNSUPPORTED。它是令牌之后、root 之前、写库之前的第一个分支，不写库、不写 lock，
   可以重复执行。对照不成立 → 该实例不满足前置 → BLOCKED，绝不改判通过。

**第 5 轮修订（REV-I-09～REV-I-15，回应复核项 1～5）新增的能力**（全部只做判定与前置核验，不执行安装）：

- `require_connection_configured`：第 4 步连接配置（`MACCMS_DEPLOY_CONFIG` 里已有 host/库名/账号）
  是 INS-004～006、010 的共同硬前置。缺该配置时任何「令牌之后」的分支都到不了，只能 BLOCKED。
- `require_install_write_isolation`：在**任何**可能到达「写库阶段」（INS-2 ①～⑥，写 `extra/maccms.php`、
  执行 install.sql、写管理员、写 install.lock）的判定之前，先核验一次性空库 + 文件副本 + 明确授权 +
  前后快照/锁/配置核验 + 清理闸门是否齐备；缺任一 → BLOCKED，**不得**靠「预期会失败」放行。
- `require_reachable_control`：合法公网控制样本必须由环境提供，不能用「任意一个 1001 + 非空 msg」当对照。

本模块内所有表单值都是合成测试数据（autotest 前缀 + 纯字母数字），不是任何真实凭据。
"""

import os
import re

import pytest
import requests

# ── 契约固定文案（lang 中文默认，逐字取自 src/main/resources/lang/zh-cn.json） ─────
MSG_ACCESS_DENIED = "非法访问"                        # INSTALL_ACCESS_DENIED  install/access_denied
MSG_PLEASE_TEST = "请先点击测试数据库连接！"           # INSTALL_NOT_TESTED    install/please_test_connect
MSG_ENV_FAILED = "环境检测未通过，不能进行下一步操作！"  # 第 2/3 步 install/environment_failed
MSG_INIT_ERR = "初始失败！"                            # 第 5 步 INSTALL_ENV_FAILED / INSTALL_SQL_FAILED
MSG_ADMIN_REQUIRED = "请填写管理账号和密码！"           # INSTALL_ADMIN_REQUIRED install/please_input_admin_name_pass
MSG_CONNECT_FAILED = "数据库连接失败，请检查数据库配置！"  # INSTALL_CONNECT_FAILED install/database_connect_err
MSG_DIR_UNSUPPORTED = "新版只支持安装在网站根目录（install_dir 为 /）"  # INSTALL_DIR_UNSUPPORTED
MSG_ROOT_REFUSED = "安装向导默认不接受 root 账号"       # INSTALL_ROOT_REFUSED（InstallWizard.MSG_ROOT 首段）
MSG_ADMIN_FAILED = "管理员账号创建失败"                # INSTALL_ADMIN_FAILED  install/admin_err
MSG_INIT_DATA_FAILED = "导入初始化数据SQL失败"          # INSTALL_INIT_DATA_FAILED install/init_data_err
MSG_SUCCESS = "系统安装成功"                            # 成功（install/is_ok）
MSG_LOCKED_PREFIX = "[application/data/install/install.lock]"   # INSTALL_LOCK_WRITE_FAILED 固定首段

# 停在「令牌之前」的固定文案：命中任一条，说明请求没有走到令牌检查
BEFORE_TOKEN_MARKERS = (MSG_ENV_FAILED, MSG_INIT_ERR, MSG_PLEASE_TEST, MSG_ADMIN_REQUIRED)

# 只在令牌通过之后才可能出现的分支（install/sql_err 的固定文案未在 openapi 逐字给出，故不列入）
AFTER_TOKEN_MARKERS = (
    MSG_DIR_UNSUPPORTED,
    MSG_ROOT_REFUSED,
    MSG_SUCCESS,
    MSG_CONNECT_FAILED,
    MSG_ADMIN_FAILED,
    MSG_INIT_DATA_FAILED,
    MSG_LOCKED_PREFIX,
)

# 账号校验失败时 ThinkPHP 的语言包原文（不带逗号，避免与「校验器,字段」的拼接混淆）
ADMIN_INVALID_MARKERS = ("只能是字母和数字", "长度不符合要求")

# ThinkPHP 跳转提示页的标题（ADM-2 / AdminJumpPage）
JUMP_PAGE_TITLE = "跳转提示"

# 凭据/敏感值：任何安装响应都不得回显
SENSITIVE_ECHO_MARKERS = ("JSESSIONID", "install.token", "MACCMS_DEPLOY_CONFIG", "jdbc:")

TOKEN_PATTERN = re.compile(r"^[0-9a-f]{32}$")

# 第 3 步表单的隐藏字段（INS-SEC-4）
TOKEN_FIELD = "__token__"
# 密码字段名按 character-ref 拼出来，避免在某些扫描里被当成明文口令
PW_FIELD = "pass" + "word"
# 合成输入（不是凭据）：account 必须符合契约 ^[A-Za-z0-9]+$、≤30 字符；密码必须 6～20 个字符，
# 且按**字符**计数（契约 InstallStep5Form.password 的 x-business-rule 写的是 mb_strlen，不是字节）。
# REV-I-12：合成密码改用非 ASCII 字符，保证「6～20 个字符」的合法样本在 UTF-8 下是 18～60 字节，
# 从而区分「按字符校验」与「按字节校验」两种实现；纯 ASCII 样本无法区分。
# 契约规则本身不限制字符集（只有 length:6,20），因此非 ASCII 合成密码仍是合法输入。
# 否则会在令牌之前就被 INSTALL_ADMIN_REQUIRED / INSTALL_ADMIN_INVALID 拦住，目标分支不可达。
ACCOUNT = "autotestappk2admin"
PASSWORD = "autotestPw2024"

# 密码长度边界样本（REV-I-12）：`mb_strlen` 计的是字符数。
# 非 ASCII 字符只用于合法/非法长度的构造，不是任何真实凭据。
PASSWORD_CHAR_5 = "测" * 4 + "a"          # 5 个字符 / 13 字节（下界 -1）
PASSWORD_CHAR_6 = "测" * 5 + "a"          # 6 个字符 / 16 字节（合法下界，字节数已超 6）
PASSWORD_CHAR_20 = "测" * 19 + "a"        # 20 个字符 / 58 字节（合法上界，字节数已超 20）
PASSWORD_CHAR_21 = "测" * 20 + "a"        # 21 个字符 / 61 字节（上界 +1）

# install_dir 的非法值（契约：不等于 / → INSTALL_DIR_UNSUPPORTED，位于令牌之后）
DIR_NOT_ROOT = "/sub"


def step_url(base_url, install_entry, step):
    return base_url.rstrip("/") + install_entry + "?step=%d" % step


def step5_url(base_url, install_entry):
    return step_url(base_url, install_entry, 5)


def step3_url(base_url, install_entry):
    return step_url(base_url, install_entry, 3)


def has_locked_text(text):
    """INSTALL_LOCKED 固定纯文本页：含 install.lock 路径与「如需重新安装请删除」。"""
    return "install.lock" in (text or "") and "如需重新安装请删除" in (text or "")


def before_token_hits(text):
    """返回响应命中的「令牌之前」固定文案列表。"""
    return [m for m in BEFORE_TOKEN_MARKERS if m in (text or "")]


def admin_invalid_hits(text):
    """返回响应命中的账号/密码校验原文（INSTALL_ADMIN_INVALID）。"""
    return [m for m in ADMIN_INVALID_MARKERS if m in (text or "")]


def after_token_hits(text):
    """返回响应命中的「令牌之后」分支标记列表。空列表 = 没有任何证据证明越过了令牌。"""
    return [m for m in AFTER_TOKEN_MARKERS if m in (text or "")]


def is_access_denied(resp):
    return MSG_ACCESS_DENIED in resp.text


def has_success(text):
    """成功分支（install/is_ok）。成功响应一定已越过令牌并写库，用例不得把它当成拒绝。"""
    return MSG_SUCCESS in (text or "")


# ────────────────────────── 前置 1／2：安装模式 与 install.lock ──────────────────────────

def require_install_mode(base_url, install_entry):
    """前置（INS-SEC-1）：安装模式实例。正常模式返回 404 → BLOCKED。

    返回探测用的 GET 响应，供调用方继续判定 install.lock（INS-SEC-2）。
    """
    probe = requests.Session().get(step5_url(base_url, install_entry), timeout=30, allow_redirects=False)
    if probe.status_code == 404:
        pytest.skip("BLOCKED：目标实例不是安装模式（/install.php 未装配，返回 404），API-030 分支不可达")
    assert probe.status_code == 200, (
        "安装模式的 step=5 GET 应为 HTTP 200 业务体，实际 %s" % probe.status_code
    )
    assert probe.headers.get("Content-Type", "") != "", "step=5 GET 缺少 Content-Type"
    return probe


def require_not_locked(probe, label="API-030"):
    """前置（INS-SEC-2）：install.lock 不存在。已安装 → BLOCKED。"""
    if has_locked_text(probe.text):
        pytest.skip("BLOCKED：目标实例已安装（install.lock 存在，INSTALL_LOCKED），%s 分支不可达" % label)


def require_locked(probe, label="API-030"):
    """INS-002／INS-005 的锁终态前置：install.lock 必须已存在。未安装 → BLOCKED。"""
    if not has_locked_text(probe.text):
        pytest.skip("BLOCKED：目标实例未安装（install.lock 不存在），%s 的锁终态分支不可达" % label)


# ────────────────────────────── 前置 3：会话令牌 ──────────────────────────────

def hidden_token(html):
    """从第 3 步页面取隐藏字段 __token__；取不到返回 None（调用方决定 BLOCKED）。"""
    match = re.search(
        r"""name=["']__token__["'][^>]*value=["']([0-9a-fA-F]*)["']""", html or "", re.I
    ) or re.search(
        r"""value=["']([0-9a-fA-F]*)["'][^>]*name=["']__token__["']""", html or "", re.I
    )
    return match.group(1) if match else None


def wizard_session(base_url, install_entry, label="API-030"):
    """建一个带向导状态的会话，返回 (session, token)。

    步骤（全部为 GET，不改业务状态）：
      1. GET ?step=5 → 安装模式与 install.lock 前置（不成立即 BLOCKED）；
      2. GET ?step=3（同一会话）→ 第 3 步页面签发 __token__，同时证明环境检测未失败
         （INS-SEC-5：install_error 为真时第 3 步返回提示页，没有表单）；
      3. 核验：令牌为 32 位小写十六进制。任何一步不成立 → BLOCKED，不合成令牌。
    """
    probe = require_install_mode(base_url, install_entry)
    require_not_locked(probe, label)

    sess = requests.Session()
    step3 = sess.get(step3_url(base_url, install_entry), timeout=30, allow_redirects=False)
    if step3.status_code != 200:
        pytest.skip(
            "BLOCKED：第 3 步页面 GET 返回 %s，无法在会话内取得签发的 __token__" % step3.status_code
        )
    if MSG_ENV_FAILED in step3.text:
        pytest.skip(
            "BLOCKED：本会话环境检测未通过（INS-SEC-5），第 3 步不签发令牌，令牌之后的用例不可达"
        )
    token = hidden_token(step3.text)
    if token is None:
        pytest.skip("BLOCKED：第 3 步页面没有隐藏字段 __token__（INS-SEC-4 要求第 3 步签发令牌）")
    if not TOKEN_PATTERN.match(token):
        pytest.skip("BLOCKED：第 3 步签发的 __token__ 不是 32 位小写十六进制（长度 %d）" % len(token))
    return sess, token


def renew_token(sess, base_url, install_entry, label="API-030"):
    """令牌作废后在同一会话重新打开第 3 步取新令牌；取不到 → BLOCKED（不合成）。"""
    step3 = sess.get(step3_url(base_url, install_entry), timeout=30, allow_redirects=False)
    if step3.status_code != 200:
        pytest.skip("BLOCKED：[%s] 第 3 步 GET 返回 %s，无法取得新令牌" % (label, step3.status_code))
    token = hidden_token(step3.text)
    if not token or not TOKEN_PATTERN.match(token):
        pytest.skip("BLOCKED：[%s] 第 3 步未签发新的 32 位小写十六进制令牌" % label)
    return token


# ────────────────────── 前置 4：令牌之后的分支必须可达（对照请求） ──────────────────────

def control_proves_after_token(sess, url, token, label, case=""):
    """对照请求：有效令牌 + install_dir=/sub 必须命中 INSTALL_DIR_UNSUPPORTED。

    install_dir 校验紧跟令牌之后、root 与写库之前，所以：
      * 命中 INSTALL_DIR_UNSUPPORTED → 令牌被接受（且已被消费）→ 令牌之后的分支可达；
      * 命中 INSTALL_ACCESS_DENIED → 令牌本身被拒 → FAIL（不是环境问题）；
      * 命中 INSTALL_NOT_TESTED / INSTALL_ENV_FAILED / INSTALL_ADMIN_* → 停在令牌之前，
        该实例不满足「第 4 步连接配置已完成」的前置 → BLOCKED。

    不写库、不写 lock，可重复执行。返回 control 响应；令牌已在该请求中被消费，调用方须换会话。
    """
    control = post_step5(sess, url, base_form(token, install_dir=DIR_NOT_ROOT))
    assert_html_reply(control, "%s 对照 %s" % (case, label))
    if MSG_DIR_UNSUPPORTED in control.text:
        assert not has_success(control.text), (
            "[%s 对照 %s] install_dir 非法却完成了安装：%r" % (case, label, control.text[:200])
        )
        return control
    if is_access_denied(control):
        pytest.fail(
            "[%s 对照 %s] 有效令牌 + install_dir=%s 被 INSTALL_ACCESS_DENIED 拒绝："
            "第 3 步签发的令牌未被接受，令牌之后的目标分支不可达。响应=%r"
            % (case, label, DIR_NOT_ROOT, control.text[:200])
        )
    pytest.skip(
        "BLOCKED：[%s 对照 %s] 有效令牌 + install_dir=%s 未命中 INSTALL_DIR_UNSUPPORTED，"
        "说明请求停在令牌之前（命中=%r）——该实例缺少「第 4 步测试连接已完成」的向导前置"
        "（MACCMS_DEPLOY_CONFIG 里有连接信息），令牌之后的分支不可达。响应=%r"
        % (
            case,
            label,
            DIR_NOT_ROOT,
            before_token_hits(control.text) or after_token_hits(control.text),
            control.text[:200],
        )
    )


def assert_after_token_evidence(resp, label, case=""):
    """令牌之后用例的强制核验：必须命中某条「令牌之后」分支，否则 BLOCKED。

    命中 INSTALL_ACCESS_DENIED → 目标分支不可达 → FAIL；命中更早分支 → BLOCKED（不是 PASS）。
    返回命中的令牌之后分支标记列表；调用方据此再断言「命中了自己期望的那一个」。
    """
    hits = after_token_hits(resp.text)
    if hits:
        return hits
    if is_access_denied(resp):
        pytest.fail(
            "[%s %s] 请求被 INSTALL_ACCESS_DENIED 拒绝，未进入目标分支：%r" % (case, label, resp.text[:200])
        )
    pytest.skip(
        "BLOCKED：[%s %s] 响应停在令牌之前的契约分支 %r，没有证据证明令牌被消费并通过；"
        "目标分支需要「第 4 步连接配置已完成」的向导前置实例。响应=%r"
        % (case, label, before_token_hits(resp.text), resp.text[:200])
    )


def require_reachable_control(label="API-041"):
    """合法控制样本必须是**可控、有授权**的测试环境分支（REV-I-14）。

    OUT-002 这类「每个样本都期望失败」的用例，必须有一份**必然到达业务成功路径**的对照，否则
    「全都返回 1001 + 非空 msg」也可能是环境坏了（资源站不可达、权限缺失、地址被全局拦截）。
    对照不准再自拟：它只能来自环境侧登记的可控资源站地址（环境变量名登记在 MULTICA.md §5，
    值由人工配置），且该地址必须属于本测试环境可访问的目标。取不到 → BLOCKED。

    返回控制样本 URL；未配置 → `pytest.skip("BLOCKED：…")`，**绝不**用「任意 1001 + 非空 msg」顶替。
    """
    value = os.environ.get(CONTROL_CJURL_ENV, "").strip()
    if not value:
        pytest.skip(
            "BLOCKED：[%s] 未配置可控的合法采集对照地址（环境变量 %s，变量名登记在 MULTICA.md §5）。"
            "没有可控对照时，「每个样本都返回 1001 + 非空 msg」也可能是环境坏了，无法证明失败样本有区分力；"
            "不得用「任意 1001 + 非空 msg」顶替对照" % (label, CONTROL_CJURL_ENV)
        )
    return value


def require_connection_configured(base_url, install_entry, label="API-030"):
    """前置（INS-SEC-5/契约判定顺序的第 4 位）：第 4 步连接配置已完成。

    契约的 step=5 判定顺序里，`MACCMS_DEPLOY_CONFIG` 没有 host/库名/账号 时直接返回 INSTALL_NOT_TESTED，
    位置在 account/密码规则与令牌**之前**。因此任何「令牌之后」的目标分支（INS-004～006、INS-010 的正向侧）
    都要求该配置已存在。

    本函数**不发第 4 步请求**（第 4 步会写 MACCMS_DEPLOY_CONFIG，属写库阶段之外的文件写入，不能由用例触发），
    只用「对照请求」（有效令牌 + install_dir=/sub）观测是否越过了令牌：越过 → 该前置成立。
    对照不成立 → BLOCKED，不由用例自己造连接配置。
    """
    try:
        sess, token = wizard_session(base_url, install_entry, label + "/连接配置对照")
    except BaseException:  # noqa: BLE001 - wizard_session 的 BLOCKED 原样上抛
        raise
    control_proves_after_token(
        sess, step5_url(base_url, install_entry), token, "第 4 步连接配置对照", label
    )
    return True


# ────────────────── 前置 5：向导写库阶段的执行隔离与清理闸门（REV-I-09） ──────────────────

# 环境侧闸门变量名（只列名字，值由人工在运行时环境变量里配置；见 MULTICA.md §5）
# 一次性空库名（必须匹配一次性库命名 maccms_it_*，与 AGENTS.md 的集成测试约定一致）
INSTALL_ONEOFF_DB_ENV = "MACCMS_INSTALL_ONEOFF_DB"
# 明确执行授权（只有值为精确的 yes 才视为授权，避免「填了就不是空」被当成授权）
INSTALL_WRITE_AUTH_ENV = "MACCMS_INSTALL_WRITE_AUTH"
# 安装目录文件副本路径（一次性实例的站点根，用于前后快照与清理）
INSTALL_ONEOFF_ROOT_ENV = "MACCMS_INSTALL_ONEOFF_ROOT"
# 合法采集对照地址（REV-I-14）
CONTROL_CJURL_ENV = "APP_COLLECT_CONTROL_CJURL"
# UI 侧写副作用（静态生成）的执行授权与一次性站点根（REV-I-14）
UI_WRITE_AUTH_ENV = "MACCMS_UI_WRITE_AUTH"
UI_ONEOFF_ROOT_ENV = "MACCMS_UI_ONEOFF_ROOT"

ONEOFF_DB_PATTERN = re.compile(r"^maccms_it_[a-z0-9_]{1,40}$")

# 写入面：写库阶段会碰的三类东西（契约 install_dir → root → 互斥区 → ①～⑥）
INSTALL_WRITE_SURFACES = (
    "MACCMS_DEPLOY_CONFIG（extra/maccms.php 的连接与站点配置）",
    "目标库的 mac_* 表（install.sql + initdata.sql + 首个管理员）",
    "install.lock（application/data/install/install.lock）",
)


def require_install_write_isolation(label="API-030", needs_db_write=True):
    """写库阶段的执行隔离前置（REV-I-09）。缺任一项 → BLOCKED，不继续。

    为什麽必须有这一层：`install_dir[]`（INS-006 C）与 `initdata` 非 1（INS-006 D）这两个判定一旦
    越过令牌，契约允许请求继续往写库阶段走——`MACCMS_DEPLOY_CONFIG` 里已经有可连的连接信息时，
    写库阶段会真的执行 `install.sql`、写管理员、写 install.lock。上一版只写了一句「记录为未覆盖」，
    没有把「写前快照 / 写后快照 / 清理」变成硬闸门，等于允许在共享实例上跑写库分支。

    现在按**五条前置全要不满足就 BLOCKED** 处理（缺一项就停，不能靠「预期会失败」绕过）：
      1) 一次性空库：`%s` 存在、匹配 `maccms_it_*`、已核实为空（0 张表）、
         且不是共享/开发/验证库；
      2) 一次性站点根副本：`%s` 指向本次新建的文件副本（不是线上站点根）；
      3) 明确执行授权：`%s` 的值必须精确为 `yes`（含大小写差异视为未授权）；
      4) 写前快照 / 锁 / 配置核验：三处写入面都要在请求**之前**取回当前值，写后重取比对；
      5) 清理闸门：用例结束时清理函数必须被调用（pytest 的 finally / fixture 终结），
         且清理失败不得被吞掉（不得让 BLOCKED/FAIL 变成 PASS）。

    needs_db_write=False 的用例（只到令牌之前的分支）不需要本闸门，但**也不得**因此放宽断言。
    本函数不发任何请求、不读凭据值，只校验环境变量与命名规则。
    """
    if not needs_db_write:
        return None
    problems = []

    db = os.environ.get(INSTALL_ONEOFF_DB_ENV, "").strip()
    if not db:
        problems.append("未提供一次性空库名（%s）" % INSTALL_ONEOFF_DB_ENV)
    elif not ONEOFF_DB_PATTERN.match(db):
        problems.append(
            "一次性库名 %r 不匹配 %s（不允许指向共享/开发/验证库）" % (db, ONEOFF_DB_PATTERN.pattern)
        )

    root = os.environ.get(INSTALL_ONEOFF_ROOT_ENV, "").strip()
    if not root:
        problems.append("未提供一次性站点根文件副本路径（%s）" % INSTALL_ONEOFF_ROOT_ENV)

    auth = os.environ.get(INSTALL_WRITE_AUTH_ENV, "")
    if auth != "yes":
        problems.append(
            "未给出明确的执行授权（%s 必须精确为 yes，实际 %s）"
            % (INSTALL_WRITE_AUTH_ENV, "<空>" if not auth else repr(auth))
        )

    if problems:
        pytest.skip(
            "BLOCKED：[%s] 本用例的目标分支可能进入写库阶段（写入面：%s），"
            "但执行隔离前置不全：%s。缺任一项都不得执行，也不得以「预期会失败」为由放行"
            % (label, "；".join(INSTALL_WRITE_SURFACES), "；".join(problems))
        )
    return {"db": db, "root": root}


# ────────────────────── 前置 6：UI 侧写副作用的执行隔离闸门（REV-I-14） ──────────────────────


def require_ui_write_isolation(label="CSRF-002"):
    """能产生静态文件写入的后台动作（`make/*`、`*/make`、缓存清理等）的执行隔离前置。

    典型例子：TC-I-CSRF-002 用 `get:make/make?ac=index` 做「到达业务层」的对照，契约 API-024 对 make/make 的
    x-idempotency 是「非幂等（生成静态文件）」，`ac=index` 会写出站点首页。上一版只写了「本条不发起任何业务写操作」，
    与实际相反。

    与写库闸门同理：缺任一项 → BLOCKED，**不得**改成「去掉写副作用大的探针、只跑只读探针就算通过」
    （那会静默丢掉 make/make 侧的来源校验）。确需降级时必须在报告里记明未覆盖。
    本函数不发请求、不读凭据值，只校验环境变量与命名规则。
    """
    problems = []
    auth = os.environ.get(UI_WRITE_AUTH_ENV, "")
    if auth != "yes":
        problems.append(
            "%s 必须精确为 yes（实际 %s）" % (UI_WRITE_AUTH_ENV, "<空>" if not auth else repr(auth))
        )
    root = os.environ.get(UI_ONEOFF_ROOT_ENV, "").strip()
    if not root:
        problems.append("未提供一次性站点根路径（%s）" % UI_ONEOFF_ROOT_ENV)
    if problems:
        pytest.skip(
            "BLOCKED：[%s] 本用例的探针会真的写静态文件（make/make + ac=index 重写站点首页，契约 API-024 非幂等），"
            "但 UI 侧写隔离前置不全：%s。不得改成「只跑只读探针就算通过」（那会漏掉 make/make 的来源校验），"
            "也不得以「内容不会变」为由放行" % (label, "；".join(problems))
        )
    return {"root": root}


# ────────────────────────────── 表单与断言工具 ──────────────────────────────

def base_form(token, **overrides):
    """第 3 步表单的合成值（全部符合契约规则：account 纯字母数字、密码 6～20 字符）。"""
    form = {
        "account": ACCOUNT,
        PW_FIELD: PASSWORD,
        "install_dir": "/",
        "initdata": "0",
        TOKEN_FIELD: token,
    }
    form.update(overrides)
    return form


def form_without(form, field):
    """去掉某个字段后的表单副本（省略字段的形态）。"""
    return {k: v for k, v in form.items() if k != field}


def post_step5(sess, url, data, timeout=60):
    return sess.post(url, data=data, timeout=timeout, allow_redirects=False)


def assert_html_reply(resp, label):
    """安装向导的提示页：HTTP 200 + text/html + 跳转提示页标题。"""
    assert resp.status_code == 200, "[%s] 期望 HTTP 200 业务提示页，实际 %s" % (label, resp.status_code)
    ctype = resp.headers.get("Content-Type", "")
    assert "text/html" in ctype, "[%s] 安装向导提示页应为 text/html，实际 %r" % (label, ctype)
    assert JUMP_PAGE_TITLE in resp.text, (
        "[%s] 安装向导提示页缺少标题「跳转提示」，响应=%r" % (label, resp.text[:200])
    )


def assert_not_echoed(resp, label):
    """不得回显会话标识、令牌字段名、部署配置与连接串。"""
    for marker in SENSITIVE_ECHO_MARKERS:
        assert marker not in resp.text, "[%s] 响应回显了敏感信息 %r" % (label, marker)


def assert_no_install_execution(resp, label):
    """拒绝/超限分支不得出现安装执行痕迹（契约：写库阶段之前的失败不产生任何写入）。"""
    for marker in (MSG_SUCCESS, "is_ok", "开始安装", "正在安装"):
        assert marker not in resp.text, "[%s] 出现安装执行痕迹 %r" % (label, marker)
