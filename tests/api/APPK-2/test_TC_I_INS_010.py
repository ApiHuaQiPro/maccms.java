# CASE: TC-I-INS-010 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12 / SCH-1、INS-SEC-3
# 维度: 边界、异常 | 优先级: P2 | T2 补充（来源：契约 v1.3.0 @ bc612e93 InstallStep5Form 字段规则 + SCH-1）
# 标题: password 长度边界（6～20，按 mb_strlen 计**字符**）：5 个字符、21 个字符 → INSTALL_ADMIN_INVALID
#       （「管理员密码不符合要求」），6 个字符与 20 个字符不得命中该分支，且必须**肯定地**越过密码规则；
#       全部样本都不得是传输层 400/413/414/415/422/431，也不得判为「参数错误」。
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452
#       —— InstallStep5Form.password 的 x-business-rule：「6～20 个字符（`mb_strlen`，按字符不按字节），
#          不满足 → INSTALL_ADMIN_INVALID（`lang(install/admin_invalid)`）」；
#          SCH-1：数组/边界形态照样穿过传输层 schema（`required: false`、不写 minLength/maxLength），
#          不是 HTTP 400，也不是 `lang(param_err)`。
# 实现: src/main/java/com/maccms/backend/install/application/InstallWizard.java:229（InstallValidation.length(…, 6, 20, …)）
#
# 第 5 轮修订（REV-I-10 / REV-I-12 / REV-I-13，回应复核项 3）：
#   a) **20 个字符上界补上**。上一版只测 5 / 21 / 6，20（合法上界）根本没测，而 20 与 21 正是
#      「按字符还是按字节」最容易分叉的地方。现在四个边界都测：5、6、20、21。
#   b) **样本改为非 ASCII（区分「字符」与「字节」）**。上一版用 `"a"*n`，ASCII 下字符数 = 字节数，
#      一个按字节实现的 length 也能全过，等于没验「mb_strlen 按字符」。现在用
#      `PASSWORD_CHAR_6 = "测"*5 + "a"`（6 字符 / 16 字节）与 `PASSWORD_CHAR_20 = "测"*19 + "a"`（20 字符 / 58 字节）：
#      按字节实现的规则会把 16 字节判成「超过 6」、把 58 字节判成「超过 20」从而误报 INSTALL_ADMIN_INVALID，
#      正好被本用例的「不得命中」断言抓住。
#   c) **合法长度必须有肯定证据，不能只判「没命中 INSTALL_ADMIN_INVALID」**。上一版第 3 步只有否定断言：
#      一个在密码规则之前就把请求拒掉的实现（INSTALL_ACCESS_DENIED / INSTALL_ADMIN_REQUIRED / 更早分支）
#      同样「没命中 ADMIN_INVALID」而假通过。现在合法样本必须**越过密码规则**，即命中
#      令牌判定（INSTALL_ACCESS_DENIED）或令牌之后的分支之一；停在密码规则之前的更早分支 → BLOCKED，
#      因为那说明合法样本根本没被送到密码规则里。
#   d) **第 4 步连接配置的显式前置**（`require_connection_configured`）。它是契约判定顺序里 password 规则
#      **之前**的一位（`readDeployTarget()` 为空即 INSTALL_NOT_TESTED），没有它连 INSTALL_ADMIN_INVALID
#      都到不了；缺配置时会表现为「未命中合法文案」，容易把环境问题误判成实现缺陷。
#   e) 每次请求用**独立会话 + 该会话自己的令牌**（令牌一次性），请求前核验实例未安装。
#
# 前置（任一不成立即 BLOCKED，不做宽松断言）：
#   1) 安装模式实例（INS-SEC-1）；2) install.lock 不存在（INS-SEC-2）；
#   3) 第 4 步连接配置已完成（`require_connection_configured`）；
#   4) 每个长度各自独立会话 + 该会话第 3 步签发的真实令牌（不合成令牌、不跨请求共用）。
# 说明：合法长度样本的表单保持 `install_dir=/` 默认值，令牌不在本请求被接受时自然会停在
#   INSTALL_ACCESS_DENIED；若被接受则走到令牌之后的分支。两者都证明「密码规则已放行」。
#
# 本文件内所有表单值都是合成测试数据，不是任何真实凭据（见 install_support.py）。

import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import pytest

from install_support import (
    MSG_ACCESS_DENIED,
    PASSWORD_CHAR_5,
    PASSWORD_CHAR_6,
    PASSWORD_CHAR_20,
    PASSWORD_CHAR_21,
    PW_FIELD,
    admin_invalid_hits,
    after_token_hits,
    assert_html_reply,
    assert_not_echoed,
    base_form,
    before_token_hits,
    post_step5,
    require_connection_configured,
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
        "[%s] SCH-1 要求传输层受理（不返回 4xx schema 拒绝），实际 %s" % (label, resp.status_code)
    )
    assert_html_reply(resp, label)
    assert_not_echoed(resp, label)
    assert PARAM_ERR not in resp.text, (
        "[%s] 边界形态不应判为「参数错误」（传输层 schema 必须受理）：%r" % (label, resp.text[:200])
    )


def _past_password_rule(resp):
    """响应是否是「密码规则已放行」的肯定证据（越过了 INS-SEC-3 的长度规则）。

    合法长度放行后，请求必然走到令牌判定：令牌未被接受时得到 INSTALL_ACCESS_DENIED；
    若被接受则继续到令牌之后的分支。两者都算「越过密码规则」。
    """
    if MSG_ACCESS_DENIED in (resp.text or ""):
        return True
    return bool(after_token_hits(resp.text))


@pytest.mark.install_wizard
def test_TC_I_INS_010(base_url, install_entry):
    url = step5_url(base_url, install_entry)
    probe = require_install_mode(base_url, install_entry)
    require_not_locked(probe, CASE)

    # 前置：第 4 步连接配置已完成（契约判定顺序里密码规则之前的一位）
    require_connection_configured(base_url, install_entry, CASE)

    # ── 1) 非法长度：5 个字符（下界 -1）与 21 个字符（上界 +1）→ INSTALL_ADMIN_INVALID ──────────
    for label, sample, expect_chars in (
        ("密码 5 字符（测×4+a）", PASSWORD_CHAR_5, 5),
        ("密码 21 字符（测×20+a）", PASSWORD_CHAR_21, 21),
    ):
        assert len(sample) == expect_chars, "[%s] 合成样本字符数不是 %d" % (label, expect_chars)
        sess, token = wizard_session(base_url, install_entry, "%s/%s" % (CASE, label))
        resp = post_step5(sess, url, base_form(token, **{PW_FIELD: sample}))
        _no_transport_reject(resp, "%s %s" % (CASE, label))
        assert admin_invalid_hits(resp.text), (
            "[%s %s] 未命中 INSTALL_ADMIN_INVALID（长度不符合要求）：更早命中=%r 响应=%r"
            % (CASE, label, before_token_hits(resp.text), resp.text[:200])
        )
        # 非法长度必须停在密码规则上，不得进入令牌之后的分支
        assert not after_token_hits(resp.text), (
            "[%s %s] 非法长度却越过令牌进入后续分支（命中=%r）：%r"
            % (CASE, label, after_token_hits(resp.text), resp.text[:200])
        )

    # ── 2) 合法长度：6 个字符（下界）与 20 个字符（上界）→ 必须**越过密码规则**（肯定证据）───
    for label, sample, expect_chars, expect_bytes in (
        ("密码 6 字符（测×5+a）", PASSWORD_CHAR_6, 6, 16),
        ("密码 20 字符（测×19+a）", PASSWORD_CHAR_20, 20, 58),
    ):
        assert len(sample) == expect_chars, "[%s] 合成样本字符数不是 %d" % (label, expect_chars)
        assert len(sample.encode("utf-8")) == expect_bytes, (
            "[%s] 合成样本 UTF-8 字节数不是 %d（样本必须按字节明显超限，才能区分按字符/按字节实现）"
            % (label, expect_bytes)
        )
        sess, token = wizard_session(base_url, install_entry, "%s/%s" % (CASE, label))
        resp = post_step5(sess, url, base_form(token, **{PW_FIELD: sample}))
        _no_transport_reject(resp, "%s %s" % (CASE, label))
        assert not admin_invalid_hits(resp.text), (
            "[%s %s] 合法长度（%d 个字符 / %d 字节）被判为 INSTALL_ADMIN_INVALID："
            "契约要求 mb_strlen 按**字符**计 6～20，按字节实现会在这里误报。响应=%r"
            % (CASE, label, expect_chars, expect_bytes, resp.text[:200])
        )
        if not _past_password_rule(resp):
            pytest.skip(
                "BLOCKED：[%s %s] 合法长度样本没有越过密码规则的肯定证据（既无 INSTALL_ACCESS_DENIED，"
                "也无令牌之后的分支；更早命中=%r），说明请求停在密码规则之前的向导分支，"
                "「密码规则放行」无法判定。响应=%r"
                % (CASE, label, before_token_hits(resp.text), resp.text[:200])
            )
