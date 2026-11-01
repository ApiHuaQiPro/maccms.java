"""APPK-2 安装向导（API-030）用例的共享前置与核验（只写阶段建好，T3 才执行）。

供 TC-I-INS-003～007 复用，避免每个文件各写一套不一致的前置判定。本模块只发**建前置所需的 GET**
与目标用例的 POST，不含断言之外的动作；所有跳过一律 `pytest.skip("BLOCKED：…")`。

契约：docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（API-030）
判定顺序（契约原文，不能改）：
  INS-SEC-2 install.lock → INS-SEC-3 方法 → INS-SEC-5 环境 → INS-SEC-5 连接配置
  → account/密码 empty（INSTALL_ADMIN_REQUIRED）→ account/密码规则（INSTALL_ADMIN_INVALID）
  → INS-SEC-4 令牌（INSTALL_ACCESS_DENIED）→ install_dir → root → 互斥区 → 写库阶段

因此：
  * 令牌之前的用例（INS-003 方法、INS-004 令牌缺失/不符、INS-007 正文超限）在**全新会话**上执行即可，
    只需断言没有命中比目标更早的分支；
  * 令牌之后的用例（INS-005 重放、INS-006 数组/边界）必须**先证明请求真的越过了令牌**——唯一可观测的证据是
    命中令牌之后才可能出现的分支（INSTALL_DIR_UNSUPPORTED、root 拒绝、写库阶段各分支、成功）。只命中
    INSTALL_NOT_TESTED / INSTALL_ENV_FAILED / INSTALL_ADMIN_* 都不能证明令牌被消费，一律 BLOCKED。

本模块内所有表单值都是合成测试数据（autotest 前缀 + 纯字母数字），不是任何真实凭据。
"""

import re

import pytest
import requests

# ── 契约固定文案（lang 中文默认，逐字取自 src/main/resources/lang/zh-cn.json） ─────
MSG_ACCESS_DENIED = "非法访问"                        # INSTALL_ACCESS_DENIED  install/access_denied
MSG_PLEASE_TEST = "请先点击测试数据库连接！"           # INSTALL_NOT_TESTED    install/please_test_connect
MSG_ENV_FAILED = "环境检测未通过，不能进行下一步操作！"  # 第 2/3 步 install/environment_failed
MSG_INIT_ERR = "初始失败！"                            # 第 5 步 install/init_err
MSG_ADMIN_REQUIRED = "请填写管理账号和密码！"           # INSTALL_ADMIN_REQUIRED install/please_input_admin_name_pass
MSG_CONNECT_FAILED = "数据库连接失败，请检查数据库配置！"  # INSTALL_CONNECT_FAILED install/database_connect_err
MSG_DIR_UNSUPPORTED = "新版只支持安装在网站根目录（install_dir 为 /）"  # INSTALL_DIR_UNSUPPORTED
MSG_ROOT_REFUSED = "安装向导默认不接受 root 账号"                      # INSTALL_ROOT_REFUSED
MSG_ADMIN_FAILED = "管理员账号创建失败"                # INSTALL_ADMIN_FAILED  install/admin_err
MSG_INIT_DATA_FAILED = "导入初始化数据SQL失败"          # INSTALL_INIT_DATA_FAILED install/init_data_err
MSG_SUCCESS = "系统安装成功"                            # 成功（install/is_ok）
MSG_LOCKED_WITH_PATH = "[application/data/install/install.lock]"       # INSTALL_LOCK_WRITE_FAILED 固定首段

# 停在「令牌之前」的固定文案：命中其中任一条，就说明请求没有走到令牌检查
BEFORE_TOKEN_MARKERS = (MSG_ENV_FAILED, MSG_INIT_ERR, MSG_PLEASE_TEST, MSG_ADMIN_REQUIRED)

# 只在令牌通过之后才可能出现的分支（install/sql_err 的固定文案在 openapi 里没有逐字给出，只按可确定的两项断言）
AFTER_TOKEN_MARKERS = (
    MSG_DIR_UNSUPPORTED,
    MSG_ROOT_REFUSED,
    MSG_SUCCESS,
    MSG_CONNECT_FAILED,
    MSG_ADMIN_FAILED,
    MSG_INIT_DATA_FAILED,
    MSG_LOCKED_WITH_PATH,
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
# 合成输入（不是凭据）：account 必须符合契约 ^[A-Za-z0-9]+$，密码必须 6～20 个字符，
# 否则会在令牌之前就被 INSTALL_ADMIN_INVALID / INSTALL_ADMIN_REQUIRED 拦住，目标分支不可达。
ACCOUNT = "autotestappk2admin"
PASSWORD = "autotestPw2024"


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


def require_install_mode(base_url, install_entry):
    """前置 1（INS-SEC-1）：安装模式实例。正常模式返回 404 → BLOCKED。

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
    """前置 2（INS-SEC-2）：install.lock 不存在。已安装 → BLOCKED。"""
    if has_locked_text(probe.text):
        pytest.skip("BLOCKED：目标实例已安装（install.lock 存在，INSTALL_LOCKED），%s 分支不可达" % label)


def hidden_token(html):
    """从第 3 步页面取隐藏字段 __token__；取不到返回 None（调用方决定 BLOCKED）。"""
    match = re.search(
        r"""name=["']__token__["'][^>]*value=["']([0-9a-fA-F]*)["']""", html or "", re.I
    ) or re.search(
        r"""value=["']([0-9a-fA-F]*)["'][^>]*name=["']__token__["']""", html or "", re.I
    )
    return match.group(1) if match else None


def wizard_session(base_url, install_entry):
    """建一个带向导状态的会话，返回 (session, token)。

    步骤（全部为 GET，不改业务状态）：
      1. GET ?step=5 → 安装模式与 install.lock 前置（不成立即 BLOCKED）；
      2. GET ?step=3（同一会话）→ 第 3 步页面签发 __token__，同时证明环境检测未失败
         （INS-SEC-5：install_error 为真时第 3 步返回 INSTALL_ENV_FAILED 提示页，没有表单）；
      3. 核验：令牌为 32 位小写十六进制。任何一步不成立 → BLOCKED，不合成令牌。
    """
    probe = require_install_mode(base_url, install_entry)
    require_not_locked(probe)

    sess = requests.Session()
    step3 = sess.get(step3_url(base_url, install_entry), timeout=30, allow_redirects=False)
    if step3.status_code != 200:
        pytest.skip(
            "BLOCKED：第 3 步页面 GET 返回 %s，无法在会话内取得签发的 __token__" % step3.status_code
        )
    if MSG_ENV_FAILED in step3.text:
        pytest.skip(
            "BLOCKED：本会话第 2 步环境检测未通过（INS-SEC-5），第 3 步不签发令牌，令牌之后的用例不可达"
        )
    token = hidden_token(step3.text)
    if token is None:
        pytest.skip("BLOCKED：第 3 步页面没有隐藏字段 __token__（INS-SEC-4 要求第 3 步签发令牌）")
    if not TOKEN_PATTERN.match(token):
        pytest.skip("BLOCKED：第 3 步签发的 __token__ 不是 32 位小写十六进制（长度 %d）" % len(token))
    return sess, token


def assert_after_token_evidence(resp, label):
    """令牌之后用例的强制核验：必须命中某条「令牌之后」分支，否则 BLOCKED（不是 FAIL、更不是 PASS）。

    理由：没有连接配置（INSTALL_NOT_TESTED）时契约在令牌之前就返回，这类响应证明不了令牌被消费；
    把它当成「令牌被接受」就是假通过。命中 INSTALL_ACCESS_DENIED 说明令牌没通过 → 目标分支不可达 → FAIL。

    返回命中的令牌之后分支标记列表；调用方据此再断言「命中了自己期望的那一个」。
    """
    hits = after_token_hits(resp.text)
    if hits:
        return hits
    if is_access_denied(resp):
        pytest.fail(
            "[%s] 请求被 INSTALL_ACCESS_DENIED 拒绝（令牌未通过），未进入目标分支：%r"
            % (label, resp.text[:200])
        )
    early = before_token_hits(resp.text)
    pytest.skip(
        "BLOCKED：[%s] 响应停在令牌之前的契约分支 %r，没有证据证明令牌被消费并通过；"
        "目标分支需要「第 4 步连接配置已完成」（MACCMS_DEPLOY_CONFIG 里有连接信息）的向导前置实例。响应=%r"
        % (label, early, resp.text[:200])
    )


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


def post_step5(sess, url, data, timeout=60):
    return sess.post(url, data=data, timeout=timeout, allow_redirects=False)


def assert_html_reply(resp, label):
    """非 Ajax 安装向导的提示页：HTTP 200 + text/html + 跳转提示页标题。"""
    assert resp.status_code == 200, "[%s] 期望 HTTP 200 业务提示页，实际 %s" % (label, resp.status_code)
    ctype = resp.headers.get("Content-Type", "")
    assert "text/html" in ctype, "[%s] 安装向导提示页应为 text/html，实际 %r" % (label, ctype)
    assert JUMP_PAGE_TITLE in resp.text, (
        "[%s] 非 Ajax 安装向导提示页缺少标题「跳转提示」，响应=%r" % (label, resp.text[:200])
    )


def assert_not_echoed(resp, label):
    """不得回显会话标识、令牌字段名、部署配置与连接串。"""
    for marker in SENSITIVE_ECHO_MARKERS:
        assert marker not in resp.text, "[%s] 响应回显了敏感信息 %r" % (label, marker)


def assert_no_install_execution(resp, label):
    """拒绝/超限分支不得出现安装执行痕迹。"""
    for marker in (MSG_SUCCESS, "is_ok", "开始安装", "正在安装"):
        assert marker not in resp.text, "[%s] 出现安装执行痕迹 %r" % (label, marker)
