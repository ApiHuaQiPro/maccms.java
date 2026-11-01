# CASE: TC-I-INS-003 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12 / INS-SEC-3 | 维度: 异常、安全 | 优先级: P1
# 标题: step=5 只接受 POST + application/x-www-form-urlencoded；GET、其他正文类型的方法 → INSTALL_ACCESS_DENIED，不执行
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（INS-SEC-3；判定顺序 INS-SEC-2 → INS-SEC-3 → 其余）
# 前置（由 install_support.require_install_mode / require_not_locked 建立并核验）：
#   1) 安装模式实例（INS-SEC-1）：/install.php 未装配 → 404 → BLOCKED
#   2) install.lock 不存在（INS-SEC-2）：INSTALL_LOCKED 固定文本 → BLOCKED
# 判定位置：INS-SEC-3 在 INS-SEC-5（环境/连接配置）与 account/令牌之前，所以本用例在**全新会话**上执行，
#   只要求「命中 INSTALL_ACCESS_DENIED」且「没有命中更早分支」。这是契约链上该方法判定的正确位置，
#   不再要求比它更晚的令牌前置（复核意见第 2 次：方法拒绝按其目标在契约链中的位置设计）。
# 本文件内所有表单值都是合成测试数据，不是任何真实凭据（见 install_support.py）。

import os
import sys

# 同目录共享模块 install_support.py（只做前置与断言工具，不执行用例）。
# pytest 以 --import-mode=importlib 运行，本目录不在 sys.path 上，这里显式加入。
_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import pytest
import requests

from install_support import (
    PW_FIELD,
    assert_html_reply,
    assert_no_install_execution,
    assert_not_echoed,
    before_token_hits,
    is_access_denied,
    step5_url,
)
from install_support import require_install_mode, require_not_locked

CASE = "INS-003"
JSON_BODY = '{"account":"autotestappk2admin"}'
TEXT_BODY = "account=autotestappk2admin"


def _assert_denied(resp, label):
    assert_html_reply(resp, "%s %s" % (CASE, label))
    early = before_token_hits(resp.text)
    assert not early, "[%s] 命中了 INS-SEC-3 之前的固定文案 %r，方法分支未被判定：%r" % (
        label,
        early,
        resp.text[:200],
    )
    assert is_access_denied(resp), (
        "[%s] 未返回 INSTALL_ACCESS_DENIED（「非法访问」）：%r" % (label, resp.text[:200])
    )
    assert_no_install_execution(resp, "%s %s" % (CASE, label))
    assert_not_echoed(resp, "%s %s" % (CASE, label))


@pytest.mark.install_wizard
def test_TC_I_INS_003(base_url, install_entry):
    url = step5_url(base_url, install_entry)

    # ── 前置：安装模式（INS-SEC-1）+ install.lock 不存在（INS-SEC-2） ─────────────
    probe = require_install_mode(base_url, install_entry)
    require_not_locked(probe, CASE)

    sess = requests.Session()

    # 1) GET：契约收紧为直接拒绝（PHP 会走「请先测试连接 / 请填写账号」，Java 按 INS-SEC-3 拒绝）
    get_resp = sess.get(url, timeout=30, allow_redirects=False)
    _assert_denied(get_resp, "GET")

    # 2) POST 但正文类型是 application/json（不是 form-urlencoded）
    json_resp = sess.post(
        url,
        data=JSON_BODY,
        headers={"Content-Type": "application/json"},
        timeout=30,
        allow_redirects=False,
    )
    _assert_denied(json_resp, "POST application/json")

    # 3) POST 但正文类型是 text/plain
    text_resp = sess.post(
        url,
        data=TEXT_BODY,
        headers={"Content-Type": "text/plain"},
        timeout=30,
        allow_redirects=False,
    )
    _assert_denied(text_resp, "POST text/plain")

    # 4) HEAD：同样不是 POST 表单，契约 INS-SEC-3 只接受 POST（无正文，只判状态与头）
    head_resp = sess.head(url, timeout=30, allow_redirects=False)
    assert head_resp.status_code == 200, "[%s HEAD] 期望 HTTP 200，实际 %s" % (CASE, head_resp.status_code)

    # 5) 三个有正文的拒绝响应都不得带安装执行痕迹（不写库、不写锁、不建安装结果）
    for resp in (get_resp, json_resp, text_resp):
        assert "install.lock" not in resp.text, "[%s] 拒绝响应里出现了写锁提示" % CASE
