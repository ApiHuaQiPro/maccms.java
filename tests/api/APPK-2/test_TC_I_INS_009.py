# CASE: TC-I-INS-009 | 接口: GET|POST /install.php?step=*（API-030） | 追溯: AC-12 / INS-SEC-2
# 维度: 权限、页面状态 | 优先级: P1 | T2 补充（来源：契约 v1.3.0 @ bc612e93 INS-SEC-2 + API-030 处置失败后的状态条款）
# 标题: 已安装实例（install.lock 存在）的锁终态：任何方法、任何 step 都只返回 INSTALL_LOCKED 固定文本，
#       不读参数、不建安装会话、不签发令牌、不进入写库阶段。
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452
#       —— INS-SEC-2：install.lock 存在时所有安装步骤恒返回 INSTALL_LOCKED（`lang(install/lock` 固定文本），
#          不读参数、不建会话、不写库；API-030「处置失败后的状态」再次要求锁检查在参数判定之前。
# 为什么单独一条（第 4 次修订 REV-I-06）：
#   上一版把本组断言作为 `test_TC_I_INS_005_installed_lock_terminal_state` 追加在 TC-I-INS-005
#   （一次性令牌）文件里，造成三个问题：
#     1. 「1 条 CASE = 1 个 test」被破坏（同一文件两个 test，两条 CASE 编号对不上测试名）；
#     2. 追溯错位——本组追溯 AC-12/INS-SEC-2（锁终态），不是 AC-12/INS-SEC-4（令牌一次性）；
#     3. 前置互斥：TC-I-INS-005 要求**未安装**实例，本组要求**已安装**实例。放在同一文件里时
#        其中一条永远 skip，测试报告里看像是「用例没跑」，而不是「前置互斥」。
#   现在拆成独立 CASE/独立文件/独立追溯，两条前置互斥在矩阵里显式标注。
#
# 前置（任一不成立即 BLOCKED，不做宽松断言）：
#   相 1：目标实例处于安装模式（`GET /install.php` 不是 404——不是安装模式时 /install.php 未装配，锁终态不可达）；
#   相 2：目标实例**已安装**（`install.lock` 存在，基准请求命中 INSTALL_LOCKED 固定文本）。
#         未安装实例上本 CASE BLOCKED；同一断言在未安装实例上的对应面由 TC-I-INS-002 覆盖。
#
# 本文件内所有表单值都是合成测试数据，不是任何真实凭据（见 install_support.py）。

import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import pytest
import requests

from install_support import (
    TOKEN_FIELD,
    assert_no_install_execution,
    base_form,
    has_locked_text,
    step3_url,
    step5_url,
)

CASE = "INS-009"


@pytest.mark.install_wizard
def test_TC_I_INS_009(base_url, install_entry):
    # ── 相 1：安装模式 ───────────────────────────────────────────────────────
    probe = requests.Session().get(
        step5_url(base_url, install_entry), timeout=30, allow_redirects=False
    )
    if probe.status_code == 404:
        pytest.skip("BLOCKED：目标实例不是安装模式（/install.php 未装配，返回 404），锁终态不可达")

    # ── 相 2：已安装（install.lock 存在）────────────────────────────────────
    if not has_locked_text(probe.text):
        pytest.skip(
            "BLOCKED：目标实例未安装（install.lock 不存在），锁终态分支不可达；"
            "同一断言在未安装实例上由 TC-I-INS-002 覆盖"
        )

    url = step5_url(base_url, install_entry)
    sess = requests.Session()
    attempts = [
        ("GET step=5", lambda: sess.get(url, timeout=30, allow_redirects=False)),
        (
            "POST step=5 表单",
            lambda: sess.post(
                url, data=base_form("0" * 32), timeout=60, allow_redirects=False
            ),
        ),
        (
            "POST step=5 非表单体",
            lambda: sess.post(
                url,
                data='{"account":"autotestappk2admin"}',
                headers={"Content-Type": "application/json"},
                timeout=30,
                allow_redirects=False,
            ),
        ),
        (
            "GET step=3",
            lambda: sess.get(step3_url(base_url, install_entry), timeout=30, allow_redirects=False),
        ),
        (
            "GET 首页",
            lambda: sess.get(base_url.rstrip("/") + install_entry, timeout=30, allow_redirects=False),
        ),
    ]
    for label, call in attempts:
        resp = call()
        assert resp.status_code == 200, "[%s %s] INSTALL_LOCKED 应为 HTTP 200，实际 %s" % (
            CASE,
            label,
            resp.status_code,
        )
        assert has_locked_text(resp.text), (
            "[%s %s] 已安装实例未返回 INSTALL_LOCKED 固定文本，响应=%r" % (CASE, label, resp.text[:200])
        )
        assert "JSESSIONID" not in resp.headers.get("Set-Cookie", ""), (
            "[%s %s] INSTALL_LOCKED 分支不应创建安装会话" % (CASE, label)
        )
        assert_no_install_execution(resp, "%s %s" % (CASE, label))
        assert TOKEN_FIELD not in resp.text, "[%s %s] 锁终态不应签发令牌字段" % (CASE, label)
