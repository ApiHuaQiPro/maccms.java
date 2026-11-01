# CASE: TC-I-INS-010 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12 / SCH-1、INS-SEC-3
# 维度: 边界、异常 | 优先级: P2 | T2 补充（来源：契约 v1.3.0 @ bc612e93 InstallStep5Form 字段规则 + SCH-1）
# 标题: password 长度边界（6～20，按 mb_strlen）：5 个字符、21 个字符 → INSTALL_ADMIN_INVALID
#       （“管理员密码不符合要求”），6 个字符不得命中该分支；三者都不得是传输层 400/415/413/414/431，
#       也不得判为「参数错误」。
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452
#       —— InstallStep5Form.password 的 x-business-rule：「6～20 个字符（`mb_strlen`，按字符不按字节），
#          不满足 → INSTALL_ADMIN_INVALID（`lang(install/admin_invalid)`）」；
#          SCH-1：数组/边界形态照样穿过传输层 schema（`required: false`、不写 minLength/maxLength），
#          不是 HTTP 400，也不是 `lang(param_err)`。
# 为什么单独一条（第 4 次修订 REV-I-06）：
#   上一版把它作为 `test_TC_I_INS_006_boundary` 追加在 TC-I-INS-006（数组/传输形态）文件里，破坏「1 条 CASE =
#   1 个 test」，且两组的判定对象不同：INS-006 断言「数组形态按缺失处理」，本条断言「标量的字符长度边界」。
#   拆开后各自可独立 BLOCKED/FAIL，报告里不会再把边界失败算到数组形态头上。
#
# 前置（任一项不成立即 BLOCKED，不做宽松断言）：
#   1) 安装模式实例（INS-SEC-1）；2) install.lock 不存在（INS-SEC-2）；
#   3) 每个长度各自独立会话 + 该会话第 3 步签发的真实令牌（令牌一次性，不能跨请求共用，也不合成令牌）。
#   密码规则分支位于令牌之前（INS-SEC-3 → INSTALL_ADMIN_*），与「第 4 步连接配置」无关，可稳定复核。
#
# 本文件内所有表单值都是合成测试数据，不是任何真实凭据（见 install_support.py）。

import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import pytest

from install_support import (
    PW_FIELD,
    admin_invalid_hits,
    assert_html_reply,
    assert_not_echoed,
    base_form,
    post_step5,
    require_install_mode,
    require_not_locked,
    step5_url,
    wizard_session,
)

CASE = "INS-010"
PARAM_ERR = "参数错误"


def _no_transport_reject(resp, label):
    """SCH-1：应用不返回传输层拒绝，也不是「参数错误」。"""
    assert resp.status_code not in (400, 413, 414, 415, 422, 431), (
        "[%s] SCH-1 要求不返回传输层拒绝，实际 %s" % (label, resp.status_code)
    )
    assert_html_reply(resp, label)
    assert_not_echoed(resp, label)
    assert PARAM_ERR not in resp.text, (
        "[%s] 边界形态不应判为「参数错误」（传输层 schema 必须受理）：%r" % (label, resp.text[:200])
    )


@pytest.mark.install_wizard
def test_TC_I_INS_010(base_url, install_entry):
    url = step5_url(base_url, install_entry)
    probe = require_install_mode(base_url, install_entry)
    require_not_locked(probe, CASE)

    # 1) 5 个字符（下界 -1）→ INSTALL_ADMIN_INVALID
    sess, token = wizard_session(base_url, install_entry, CASE + "/密码 5 字符")
    resp = post_step5(sess, url, base_form(token, **{PW_FIELD: "a" * 5}))
    _no_transport_reject(resp, "%s 密码 5 字符" % CASE)
    assert admin_invalid_hits(resp.text), (
        "[%s 密码 5 字符] 未命中 INSTALL_ADMIN_INVALID（长度不符合要求）：%r" % (CASE, resp.text[:200])
    )

    # 2) 21 个字符（上界 +1）→ INSTALL_ADMIN_INVALID
    sess, token = wizard_session(base_url, install_entry, CASE + "/密码 21 字符")
    resp = post_step5(sess, url, base_form(token, **{PW_FIELD: "a" * 21}))
    _no_transport_reject(resp, "%s 密码 21 字符" % CASE)
    assert admin_invalid_hits(resp.text), (
        "[%s 密码 21 字符] 未命中 INSTALL_ADMIN_INVALID（长度不符合要求）：%r" % (CASE, resp.text[:200])
    )

    # 3) 6 个字符（合法下界）→ 不得判为 INSTALL_ADMIN_INVALID（会进入后续分支）
    sess, token = wizard_session(base_url, install_entry, CASE + "/密码 6 字符")
    resp = post_step5(sess, url, base_form(token, **{PW_FIELD: "a" * 6}))
    _no_transport_reject(resp, "%s 密码 6 字符" % CASE)
    assert not admin_invalid_hits(resp.text), (
        "[%s 密码 6 字符] 合法下界被判为 INSTALL_ADMIN_INVALID：%r" % (CASE, resp.text[:200])
    )
