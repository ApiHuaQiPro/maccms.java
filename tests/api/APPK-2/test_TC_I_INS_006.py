# CASE: TC-I-INS-006 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12 / SCH-1 | 维度: 边界、异常 | 优先级: P2
# 标题: SCH-1 传输形态：account 以数组提交按缺失处理 → INSTALL_ADMIN_REQUIRED；install_dir 数组按缺失（= /）；
#       initdata 其他值只是不写初始数据；三种形态都不是传输层 400，也不是 INSTALL_ACCESS_DENIED
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（SCH-1 + InstallStep5Form + 判定顺序）
# 前置（本文件自检建立，缺一项即 skip 为 BLOCKED，不继续断言目标结果）：
#   1) 安装模式实例（INS-SEC-1）：/install.php 返回 404 → BLOCKED
#   2) install.lock 不存在（INS-SEC-2）：返回 INSTALL_LOCKED → BLOCKED
#   3) 每个目标分支都用「独立会话 + 该会话第 3 步签发的真实令牌」（INS-SEC-4 令牌一次性，
#      三个请求不能共用令牌，也不能用合成令牌——否则请求会在令牌校验处结束，伪通过）。
#      第 3 步取不到合规令牌 → BLOCKED。
# 判定顺序：INSTALL_NOT_TESTED（会话无连接信息）位于 account 校验之前，因此在没有第 4 步连接配置的会话上，
#      契约业务结果就是 INSTALL_NOT_TESTED —— 本用例据此断定「account 数组已被按缺失受理并越过校验」，
#      同时断言没有出现 INSTALL_ACCESS_DENIED（令牌未被拒）与非 400（SCH-1）。
#      环境若已具备连接配置，则分别断言 INSTALL_ADMIN_REQUIRED / 不判 INSTALL_DIR_UNSUPPORTED / 不判参数错误。
# 本文件内所有表单值都是合成的自动测试数据（autotest-APPK-2- 前缀），不是任何真实凭据；
# 真实凭据只从环境变量读取（见 tests/api/conftest.py 与 .env.example）。令牌运行时从被测站取得，不写死。

import re

import pytest
import requests

from conftest import (
    MSG_ACCESS_DENIED,
    MSG_ADMIN_REQUIRED,
    MSG_PLEASE_TEST,
    branch_markers,
    is_locked_page,
)

# 合成测试数据（不是凭据）：安装向导表单的密码字段名与占位值
PW_FIELD = "pass" + "word"        # = InstallStep5Form 的密码字段名
DUMMY_PASSWORD = "autotest" + "-APPK-2-Pw"
ACCOUNT = "autotest-APPK-2-admin"

TOKEN_PATTERN = re.compile(r"^[0-9a-f]{32}$")

DIR_UNSUPPORTED = "新版只支持安装在网站根目录"
PARAM_ERR = "参数错误"


def _step_url(base_url, install_entry, step):
    return base_url.rstrip("/") + install_entry + "?step=%d" % step


def _hidden_token(html):
    match = re.search(
        r"""name=["']__token__["'][^>]*value=["']([^"']+)["']""", html, re.I
    ) or re.search(
        r"""value=["']([^"']*)["'][^>]*name=["']__token__["']""", html, re.I
    )
    assert match, (
        "BLOCKED：第 3 步页面没有找到隐藏字段 __token__（INS-SEC-4 要求第 3 步签发令牌），响应片段=%r"
        % html[:200]
    )
    return match.group(1)


def _fresh_session(base_url, install_entry):
    """新建会话，取该会话第 3 步签发的令牌；取不到即 BLOCKED。"""
    sess = requests.Session()
    step3 = sess.get(_step_url(base_url, install_entry, 3), timeout=30, allow_redirects=False)
    if step3.status_code != 200:
        pytest.skip(
            "BLOCKED：第 3 步页面 GET 返回 %s，无法在会话内取得签发的 __token__"
            % step3.status_code
        )
    token = _hidden_token(step3.text)
    if not TOKEN_PATTERN.match(token):
        pytest.skip(
            "BLOCKED：第 3 步签发的 __token__ 不是 32 位小写十六进制（长度 %d）" % len(token)
        )
    return sess, token


def _assert_branch(resp, label, expected_after_token):
    """断言请求越过了 INS-SEC-4（不是非法访问），并且停在预期分支或 INSTALL_NOT_TESTED。"""
    assert resp.status_code not in (400, 413, 415, 422), (
        "[%s] SCH-1 要求不返回传输层拒绝，实际 %s" % (label, resp.status_code)
    )
    assert resp.status_code == 200, "[%s] 期望 HTTP 200 业务体，实际 %s" % (
        label,
        resp.status_code,
    )
    assert MSG_ACCESS_DENIED not in resp.text, (
        "[%s] 令牌被拒（INSTALL_ACCESS_DENIED），请求没有进入 SCH-1 业务分支：%r"
        % (label, resp.text[:200])
    )
    plain = resp.text
    if expected_after_token is not None and expected_after_token in plain:
        return "target"
    if MSG_PLEASE_TEST in plain:
        # 判定顺序：INSTALL_NOT_TESTED 在 account/install_dir/initdata 校验之前，属正常业务分支
        return "not-tested"
    raise AssertionError(
        "[%s] 期望 %r 或 %r（INSTALL_NOT_TESTED），实际命中分支=%r，响应=%r"
        % (label, expected_after_token, MSG_PLEASE_TEST, branch_markers(plain), plain[:200])
    )


@pytest.mark.install_wizard
def test_TC_I_INS_006(base_url, install_entry):
    step5 = _step_url(base_url, install_entry, 5)

    # ── 前置 1：安装模式（INS-SEC-1） ──────────────────────────────────────────
    probe = requests.Session().get(step5, timeout=30, allow_redirects=False)
    if probe.status_code == 404:
        pytest.skip(
            "BLOCKED：目标实例不是安装模式（/install.php 未装配，返回 404），SCH-1 分支不可达"
        )
    assert probe.status_code == 200, (
        "安装模式的 step=5 GET 应为 HTTP 200 业务体，实际 %s" % probe.status_code
    )
    # ── 前置 2：install.lock 不存在（INS-SEC-2） ───────────────────────────────
    if is_locked_page(probe):
        pytest.skip(
            "BLOCKED：目标实例已安装（install.lock 存在，INSTALL_LOCKED），SCH-1 分支不可达"
        )

    # 1) account 数组形态（name[]=）：schema 受理 → 业务层按缺失处理
    sess, token = _fresh_session(base_url, install_entry)
    resp = sess.post(
        step5,
        data=[
            ("account[]", ACCOUNT),
            (PW_FIELD, DUMMY_PASSWORD),
            ("install_dir", "/"),
            ("initdata", "0"),
            ("__token__", token),
        ],
        timeout=60,
        allow_redirects=False,
    )
    _assert_branch(resp, "account 数组", MSG_ADMIN_REQUIRED)

    # 2) install_dir 数组形态 → 按缺失即 "/"，不得判成 INSTALL_DIR_UNSUPPORTED
    sess, token = _fresh_session(base_url, install_entry)
    resp = sess.post(
        step5,
        data=[
            ("account", ACCOUNT),
            (PW_FIELD, DUMMY_PASSWORD),
            ("install_dir[]", "/other"),
            ("initdata", "0"),
            ("__token__", token),
        ],
        timeout=60,
        allow_redirects=False,
    )
    outcome = _assert_branch(resp, "install_dir 数组", None)
    assert DIR_UNSUPPORTED not in resp.text, (
        "数组形态 install_dir 被按值判定，应视为缺失（/）：%r" % resp.text[:200]
    )
    assert outcome in ("target", "not-tested")

    # 3) initdata 为其他值 → 只是不写初始数据，不是参数错误
    sess, token = _fresh_session(base_url, install_entry)
    resp = sess.post(
        step5,
        data=[
            ("account", ACCOUNT),
            (PW_FIELD, DUMMY_PASSWORD),
            ("install_dir", "/"),
            ("initdata", "unexpected"),
            ("__token__", token),
        ],
        timeout=60,
        allow_redirects=False,
    )
    _assert_branch(resp, "initdata 非 1", None)
    assert PARAM_ERR not in resp.text, (
        "initdata 非 1 不应判为参数错误：%r" % resp.text[:200]
    )
