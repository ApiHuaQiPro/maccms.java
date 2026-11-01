# CASE: TC-I-INS-004 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12 / INS-SEC-4 | 维度: 安全、权限 | 优先级: P1
# 标题: 具备向导状态的会话里「省略令牌」「令牌与签发值不符」「令牌以数组提交」都被 INSTALL_ACCESS_DENIED 拒绝，
#       且拒绝后补上正确令牌可以越过令牌检查（排除伪拒绝）；不回显令牌、密码与会话标识
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（INS-SEC-4、判定顺序）
#
# 前置（两相，任一不成立即 BLOCKED，不继续断言目标结果）：
#   相 1：安装模式实例（INS-SEC-1）、install.lock 不存在（INS-SEC-2）、同一会话第 3 步签发 32 位小写
#         十六进制 __token__（INS-SEC-4）—— 由 install_support.wizard_session 建立并核验；
#   相 2：该实例真的能走到令牌之后的分支 —— 用对照请求「有效令牌 + install_dir=/sub」观测
#         INSTALL_DIR_UNSUPPORTED（令牌之后、写库之前的第一个分支，不写库、不写 lock，可重复执行）。
#         契约判定顺序里 account/密码规则与「第 4 步连接配置」（INSTALL_NOT_TESTED）都在令牌之前，
#         只有相 2 成立，本用例的 INSTALL_ACCESS_DENIED 才只能来自令牌判定（REV-I-01 / REV-I-02）。
#         对照停在更早分支 → BLOCKED；对照被令牌拒绝 → FAIL。
#
# 本文件内所有表单值都是合成测试数据，不是任何真实凭据（见 install_support.py）。

import os
import sys

# 同目录共享模块 install_support.py（只做前置与断言工具，不执行用例）。
# pytest 以 --import-mode=importlib 运行，本目录不在 sys.path 上，这里显式加入。
_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import pytest

from install_support import (
    ACCOUNT,
    DIR_NOT_ROOT,
    MSG_DIR_UNSUPPORTED,
    MSG_SUCCESS,
    PASSWORD,
    TOKEN_FIELD,
    after_token_hits,
    assert_html_reply,
    assert_no_install_execution,
    assert_not_echoed,
    base_form,
    before_token_hits,
    control_proves_after_token,
    form_without,
    is_access_denied,
    post_step5,
    renew_token,
    step5_url,
    wizard_session,
)

CASE = "INS-004"
WRONG_TOKEN = "f" * 32


def _denied(resp, label):
    """INSTALL_ACCESS_DENIED 的固定形态（非 Ajax 安装向导提示页）。"""
    assert_html_reply(resp, "%s %s" % (CASE, label))
    assert is_access_denied(resp), (
        "[%s] 未返回 INSTALL_ACCESS_DENIED（「非法访问」）：%r" % (label, resp.text[:200])
    )
    assert_no_install_execution(resp, "%s %s" % (CASE, label))
    assert_not_echoed(resp, "%s %s" % (CASE, label))


def _assert_can_pass_token(sess, url, base_url, install_entry, label):
    """同一会话补上正确令牌后必须越过令牌检查（反向证明上面的拒绝不是伪拒绝）。

    正确令牌来自同一会话重新打开第 3 步页面（令牌一次性，被拒后会话里可能已无令牌）。
    """
    token = renew_token(sess, base_url, install_entry, "%s %s" % (CASE, label))
    after = post_step5(sess, url, base_form(token, install_dir=DIR_NOT_ROOT))
    assert_html_reply(after, "%s %s" % (CASE, label))
    assert MSG_DIR_UNSUPPORTED in after.text, (
        "[%s %s] 补上正确令牌后仍未越过令牌检查（命中=%r），"
        "「INSTALL_ACCESS_DENIED 来自令牌判定」缺少区分力：%r"
        % (CASE, label, before_token_hits(after.text) + after_token_hits(after.text), after.text[:200])
    )
    assert MSG_SUCCESS not in after.text, "[%s %s] install_dir 非法却完成了安装" % (CASE, label)


@pytest.mark.install_wizard
def test_TC_I_INS_004(base_url, install_entry):
    url = step5_url(base_url, install_entry)

    # ── 相 1 + 相 2：向导会话 +「令牌之后分支可达」对照 ──────────────────────────
    sess, token = wizard_session(base_url, install_entry, CASE)
    control_proves_after_token(sess, url, token, "前置对照", CASE)

    # ── 目标 1：会话有令牌，但表单里省略 ____token____ 字段 → INSTALL_ACCESS_DENIED ──
    sess, token = wizard_session(base_url, install_entry, CASE)
    resp = post_step5(sess, url, form_without(base_form(token), TOKEN_FIELD))
    _denied(resp, "省略令牌字段")
    assert token not in resp.text, "[%s] 响应回显了该会话签发的令牌" % CASE
    _assert_can_pass_token(sess, url, base_url, install_entry, "省略令牌后补正确令牌")

    # ── 目标 2：会话有令牌，但提交值与签发值不符 → INSTALL_ACCESS_DENIED ──────────
    sess, token = wizard_session(base_url, install_entry, CASE)
    assert WRONG_TOKEN != token, "[%s] 合成的不符令牌与签发值相同（概率极低），请重跑" % CASE
    resp = post_step5(sess, url, base_form(WRONG_TOKEN))
    _denied(resp, "令牌与签发值不符")
    assert WRONG_TOKEN not in resp.text, "[%s] 响应回显了提交的错误令牌" % CASE
    _assert_can_pass_token(sess, url, base_url, install_entry, "令牌不符后补正确令牌")

    # ── 目标 3：__token__ 以数组提交（PHP name[]=）按缺失处理 → INSTALL_ACCESS_DENIED ──
    sess, token = wizard_session(base_url, install_entry, CASE)
    data = [(k, v) for k, v in base_form(token).items() if k != TOKEN_FIELD]
    data.append((TOKEN_FIELD + "[]", token))
    resp = post_step5(sess, url, data)
    _denied(resp, "令牌数组形态")
    assert token not in resp.text, "[%s] 响应回显了数组形态提交的令牌" % CASE
    _assert_can_pass_token(sess, url, base_url, install_entry, "数组令牌后补正确令牌")

    # ── 拒绝响应不新发安装会话，且不回显密码 ────────────────────────────────────
    set_cookie = " ".join(v for k, v in resp.headers.items() if k.lower() == "set-cookie")
    assert "install" not in set_cookie.lower(), (
        "[%s] INSTALL_ACCESS_DENIED 分支不应新发安装会话：Set-Cookie=%r" % (CASE, set_cookie)
    )
    assert PASSWORD not in resp.text, "[%s] 响应回显了提交的密码" % CASE
    assert ACCOUNT + ":" not in resp.text, "[%s] 响应回显了账号与密码组合" % CASE
