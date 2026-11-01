# CASE: TC-I-INS-004 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12 / INS-SEC-4 | 维度: 安全、权限 | 优先级: P1
# 标题: 具备向导状态的会话里「省略令牌」「令牌与签发值不符」「令牌以数组提交」都被 INSTALL_ACCESS_DENIED 拒绝，
#       且不回显令牌、密码与会话标识
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（INS-SEC-4、判定顺序）
# 前置（install_support.wizard_session 建立并逐项核验，任一不成立即 BLOCKED，不继续断言目标结果）：
#   1) 安装模式实例（INS-SEC-1）；
#   2) install.lock 不存在（INS-SEC-2）；
#   3) 同一会话第 3 步页面签发了 32 位小写十六进制 __token__（INS-SEC-4），且第 3 步页面可打开（环境检测未失败）。
#      —— 令牌分支必须在**真的会走到令牌检查**的会话里验证，不用没有任何向导状态的新会话（REV-I-01）。
# 前置门槛（REV-I-01 / REV-I-02）：契约判定顺序里 account 的 ^[A-Za-z0-9]+$ 与密码 6～20 字符
#   （INSTALL_ADMIN_*）、以及 INS-SEC-5 的连接配置（INSTALL_NOT_TESTED）都排在令牌之前，
#   所以本用例的下发表单必须满足这些先行条件，否则请求停在更早分支。
#   核验方式 = 先下发一个「有效令牌 + install_dir=/sub」的对照请求：install_dir 校验紧跟在令牌之后，
#   只有它命中 INSTALL_DIR_UNSUPPORTED，才证明「account/密码合法 + 令牌通过」；
#   此时省略令牌 / 提交不符令牌 / 数组令牌，响应必然是 INSTALL_ACCESS_DENIED。
#   对照不成立 → BLOCKED（缺「第 4 步连接配置已完成」的向导前置实例），绝不把更早分支当目标分支通过。
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
    MSG_DIR_UNSUPPORTED,
    MSG_SUCCESS,
    PASSWORD,
    TOKEN_FIELD,
    after_token_hits,
    assert_html_reply,
    assert_no_install_execution,
    assert_not_echoed,
    before_token_hits,
    base_form,
    is_access_denied,
    post_step5,
    require_install_mode,
    require_not_locked,
    step5_url,
    wizard_session,
)

CASE = "INS-004"


def _denied(resp, label):
    """INSTALL_ACCESS_DENIED 的固定形态（非 Ajax 安装向导提示页）。"""
    assert_html_reply(resp, "%s %s" % (CASE, label))
    assert is_access_denied(resp), (
        "[%s] 未返回 INSTALL_ACCESS_DENIED（「非法访问」）：%r" % (label, resp.text[:200])
    )
    assert_no_install_execution(resp, "%s %s" % (CASE, label))
    assert_not_echoed(resp, "%s %s" % (CASE, label))


def _control_proves_token_gate(sess, url, token, label):
    """对照请求：有效令牌 + install_dir=/sub 必须命中 INSTALL_DIR_UNSUPPORTED（令牌之后的第一个分支）。"""
    control = post_step5(sess, url, base_form(token, install_dir="/sub"))
    assert_html_reply(control, "%s %s" % (CASE, label))
    if MSG_DIR_UNSUPPORTED not in control.text:
        pytest.skip(
            "BLOCKED：[%s %s] 有效令牌 + install_dir=/sub 未返回 INSTALL_DIR_UNSUPPORTED，"
            "说明 account/密码校验或连接配置（INSTALL_NOT_TESTED）先命中，目标分支不可达。"
            "令牌之前文案=%r、令牌之后分支=%r、是否 INSTALL_ACCESS_DENIED=%s、响应=%r。"
            "需要「第 4 步测试连接已完成」（MACCMS_DEPLOY_CONFIG 有连接信息）的安装模式实例。"
            % (
                CASE,
                label,
                before_token_hits(control.text),
                after_token_hits(control.text),
                is_access_denied(control),
                control.text[:200],
            )
        )
    assert MSG_SUCCESS not in control.text, "[%s %s] install_dir 非法却完成了安装" % (CASE, label)
    return control


@pytest.mark.install_wizard
def test_TC_I_INS_004(base_url, install_entry):
    url = step5_url(base_url, install_entry)

    # ── 前置：安装模式（INS-SEC-1）+ install.lock 不存在（INS-SEC-2） ─────────────
    probe = require_install_mode(base_url, install_entry)
    require_not_locked(probe, CASE)

    # ── 前置门槛：证明该前置下请求真的走到令牌检查（独立会话，对照用） ────────────
    sess_a, token_a = wizard_session(base_url, install_entry)
    _control_proves_token_gate(sess_a, url, token_a, "前置对照")

    # ── 目标 1：会话有令牌，但表单里省略 ____token____ 字段 → INSTALL_ACCESS_DENIED ──
    sess_b, token_b = wizard_session(base_url, install_entry)
    omitted = base_form(token_b)
    omitted.pop(TOKEN_FIELD)
    resp = post_step5(sess_b, url, omitted)
    _denied(resp, "省略令牌字段")
    assert token_b not in resp.text, "[%s] 响应回显了该会话签发的令牌" % CASE

    # ── 目标 2：会话有令牌，但提交值与签发值不符 → INSTALL_ACCESS_DENIED ──────────
    sess_c, token_c = wizard_session(base_url, install_entry)
    wrong = "f" * 32
    assert wrong != token_c, "[%s] 合成的不符令牌与签发值相同（概率极低），请重跑" % CASE
    resp = post_step5(sess_c, url, base_form(wrong))
    _denied(resp, "令牌与签发值不符")
    assert wrong not in resp.text, "[%s] 响应回显了提交的错误令牌" % CASE
    # 同一会话随后用正确令牌 + install_dir=/sub：仍应停在 INSTALL_DIR_UNSUPPORTED，
    # 证明上面的拒绝来自令牌不符，而不是会话/实例状态变化造成的伪拒绝。
    after_wrong = post_step5(sess_c, url, base_form(token_c, install_dir="/sub"))
    assert_html_reply(after_wrong, "%s 令牌不符后对照" % CASE)
    assert MSG_DIR_UNSUPPORTED in after_wrong.text, (
        "[%s 令牌不符后对照] 同一会话补上正确令牌后仍未越过令牌检查，"
        "「令牌不符 → INSTALL_ACCESS_DENIED」的断言失去区分力：%r" % (CASE, after_wrong.text[:200])
    )

    # ── 目标 3：__token__ 以数组提交（PHP name[]=）按缺失处理 → INSTALL_ACCESS_DENIED ──
    sess_d, token_d = wizard_session(base_url, install_entry)
    data = [(k, v) for k, v in base_form(token_d).items() if k != TOKEN_FIELD]
    data.append((TOKEN_FIELD + "[]", token_d))
    resp = post_step5(sess_d, url, data)
    _denied(resp, "令牌数组形态")
    assert token_d not in resp.text, "[%s] 响应回显了数组形态提交的令牌" % CASE

    # ── 拒绝响应不新发安装会话，且不回显密码 ────────────────────────────────────
    set_cookie = " ".join(v for k, v in resp.headers.items() if k.lower() == "set-cookie")
    assert "install" not in set_cookie.lower(), (
        "[%s] INSTALL_ACCESS_DENIED 分支不应新发安装会话：Set-Cookie=%r" % (CASE, set_cookie)
    )
    assert PASSWORD not in resp.text, "[%s] 响应回显了提交的密码" % CASE
    assert ACCOUNT + ":" not in resp.text, "[%s] 响应回显了账号与密码组合" % CASE
