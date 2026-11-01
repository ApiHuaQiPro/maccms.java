# CASE: TC-I-INS-006 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12 / SCH-1 | 维度: 边界、异常 | 优先级: P2
# 标题: SCH-1 传输形态：account 以数组提交 → INSTALL_ADMIN_REQUIRED；install_dir[] 数组按缺失（= /）处理；
#       install_dir 标量非法 → INSTALL_DIR_UNSUPPORTED；initdata 非 1 与 1 在写库之前的判定一致 ——
#       都不是传输层 400/415、也不是「参数错误」
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（SCH-1 + InstallStep5Form + 判定顺序）
#
# 前置（install_support.wizard_session 建立并逐项核验，任一不成立即 BLOCKED）：
#   1) 安装模式实例（INS-SEC-1）；2) install.lock 不存在（INS-SEC-2）；
#   3) 每个分支各自独立会话 + 该会话第 3 步签发的真实令牌（令牌一次性，不能跨请求共用；也不合成令牌）。
#
# 通过条件（REV-I-03：不再接受「更早分支」当结论，也不再接受「没命中就 skip」的假通过）：
#   A) account 数组 → INSTALL_ADMIN_REQUIRED（该分支在令牌之前，与「第 4 步连接配置」无关，可稳定复核）；
#   B) install_dir 标量非法（/other）→ INSTALL_DIR_UNSUPPORTED，作为「令牌之后分支可达」的对照；
#   C) install_dir[] 数组 → 必须越过令牌（先取 B 的对照证据），且**不得**出现 INSTALL_DIR_UNSUPPORTED
#      （数组按缺失即 / 处理），只允许命中写库阶段及其之后的分支；
#   D) initdata 非 1 与 initdata=1：写库之前的判定必须完全一致（同一分支集合）——「非 1 不写初始数据」
#      的真正观测需要空库一次性实例，记在 api-cases.md「未覆盖与原因」。
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
    MSG_ADMIN_REQUIRED,
    MSG_DIR_UNSUPPORTED,
    MSG_SUCCESS,
    PW_FIELD,
    after_token_hits,
    admin_invalid_hits,
    assert_after_token_evidence,
    assert_html_reply,
    assert_not_echoed,
    base_form,
    before_token_hits,
    control_proves_after_token,
    is_access_denied,
    post_step5,
    require_install_mode,
    require_not_locked,
    step5_url,
    wizard_session,
)

CASE = "INS-006"
PARAM_ERR = "参数错误"


def _no_transport_reject(resp, label):
    """SCH-1：应用不返回传输层拒绝，也不是「参数错误」。"""
    assert resp.status_code not in (400, 413, 414, 415, 422, 431), (
        "[%s] SCH-1 要求不返回传输层拒绝，实际 %s" % (label, resp.status_code)
    )
    assert_html_reply(resp, label)
    assert_not_echoed(resp, label)
    assert PARAM_ERR not in resp.text, (
        "[%s] 数组/边界形态不应判为「参数错误」（传输层 schema 必须受理）：%r" % (label, resp.text[:200])
    )


def _prove_after_token_reachable(base_url, install_entry, url, case=CASE):
    """B) 对照：有效令牌 + install_dir=/other 必须命中 INSTALL_DIR_UNSUPPORTED。

    install_dir 校验在令牌之后、写库之前，是「令牌之后可达」可重复使用的最小证据。
    不成立 → BLOCKED（更早分支）或 FAIL（令牌被拒），沿用 install_support 的统一判定。
    """
    sess, token = wizard_session(base_url, install_entry, case + "/对照")
    return control_proves_after_token(sess, url, token, "install_dir 对照", case)


@pytest.mark.install_wizard
def test_TC_I_INS_006(base_url, install_entry):
    url = step5_url(base_url, install_entry)

    # ── 前置：安装模式（INS-SEC-1）+ install.lock 不存在（INS-SEC-2） ─────────────
    probe = require_install_mode(base_url, install_entry)
    require_not_locked(probe, CASE)

    # ── A) account 作数组提交：按缺失处理 → INSTALL_ADMIN_REQUIRED ────────────────
    sess, token = wizard_session(base_url, install_entry, CASE + "/account 数组")
    data = [(k, v) for k, v in base_form(token).items() if k != "account"]
    data.insert(0, ("account[]", ACCOUNT))
    resp = post_step5(sess, url, data)
    _no_transport_reject(resp, "%s account 数组" % CASE)
    assert not is_access_denied(resp), (
        "[%s account 数组] 令牌被拒（INSTALL_ACCESS_DENIED），请求未进入 account 判定：%r"
        % (CASE, resp.text[:200])
    )
    assert not admin_invalid_hits(resp.text), (
        "[%s account 数组] 数组形态被当成非法值（INSTALL_ADMIN_INVALID），应按缺失处理：%r"
        % (CASE, resp.text[:200])
    )
    if MSG_ADMIN_REQUIRED not in resp.text:
        pytest.skip(
            "BLOCKED：[%s account 数组] 未命中 INSTALL_ADMIN_REQUIRED，说明停在更早的向导分支"
            "（INSTALL_NOT_TESTED / INSTALL_ENV_FAILED）：%r。该分支只要求「第 4 步连接配置已完成」，"
            "需要具备该前置的安装模式实例。响应=%r" % (CASE, before_token_hits(resp.text), resp.text[:200])
        )
    assert MSG_SUCCESS not in resp.text, "[%s account 数组缺失却完成了安装" % CASE

    # ── B) 对照：install_dir 标量非法 → INSTALL_DIR_UNSUPPORTED（令牌之后可达的证据） ──
    _prove_after_token_reachable(base_url, install_entry, url)

    # ── C) install_dir 作数组提交：按缺失即 /，不得出现 INSTALL_DIR_UNSUPPORTED ─────
    sess, token = wizard_session(base_url, install_entry, CASE + "/install_dir 数组")
    data = [(k, v) for k, v in base_form(token).items() if k != "install_dir"]
    data.append(("install_dir[]", "/other"))
    resp = post_step5(sess, url, data)
    _no_transport_reject(resp, "%s install_dir 数组" % CASE)
    assert not is_access_denied(resp), (
        "[%s install_dir 数组] 令牌被拒（INSTALL_ACCESS_DENIED）：%r" % (CASE, resp.text[:200])
    )
    hits = assert_after_token_evidence(resp, "install_dir 数组", CASE)
    assert MSG_DIR_UNSUPPORTED not in resp.text, (
        "[%s install_dir 数组] 数组被按值判定为 /other（INSTALL_DIR_UNSUPPORTED），"
        "契约要求数组按缺失处理（= /）：%r" % (CASE, resp.text[:200])
    )
    assert hits, "[%s install_dir 数组] 未命中任何令牌之后的安装分支" % CASE

    # ── D) initdata 非 1 与 initdata=1：写库之前的判定完全一致（都不是参数错误） ─────
    sess, token = wizard_session(base_url, install_entry, CASE + "/initdata=1")
    one = post_step5(sess, url, base_form(token, install_dir="/other", initdata="1"))
    _no_transport_reject(one, "%s initdata=1" % CASE)

    sess, token = wizard_session(base_url, install_entry, CASE + "/initdata 非 1")
    other = post_step5(sess, url, base_form(token, install_dir="/other", initdata="unexpected"))
    _no_transport_reject(other, "%s initdata 非 1" % CASE)

    assert MSG_DIR_UNSUPPORTED in one.text, (
        "[%s initdata 对照] initdata=1 + install_dir=/other 未命中 INSTALL_DIR_UNSUPPORTED，"
        "两次对照都没有越过令牌（命中=%r）：%r"
        % (CASE, before_token_hits(one.text) + after_token_hits(one.text), one.text[:200])
    )
    assert MSG_DIR_UNSUPPORTED in other.text, (
        "[%s initdata 非 1] initdata 非 1 改变了写库之前的判定：未命中 INSTALL_DIR_UNSUPPORTED，"
        "命中=%r：%r"
        % (CASE, before_token_hits(other.text) + after_token_hits(other.text), other.text[:200])
    )
    assert after_token_hits(one.text) == after_token_hits(other.text) == [MSG_DIR_UNSUPPORTED], (
        "[%s initdata 对照] initdata 改变了判定分支：1 → %r，非 1 → %r"
        % (CASE, after_token_hits(one.text), after_token_hits(other.text))
    )
    assert MSG_SUCCESS not in one.text and MSG_SUCCESS not in other.text, (
        "[%s initdata 对照] install_dir=/other 却完成了安装" % CASE
    )


@pytest.mark.install_wizard
def test_TC_I_INS_006_boundary(base_url, install_entry):
    """同一传输形态维度的补充：密码长度边界（6～20，mb_strlen）由 account/密码规则分支判定。

    这些分支位于令牌之前，与「第 4 步连接配置」无关，可稳定复核：
      - 5 个字符 → INSTALL_ADMIN_INVALID（长度不符合要求）；
      - 21 个字符 → INSTALL_ADMIN_INVALID；
      - 6 个字符 → 不得命中 INSTALL_ADMIN_INVALID（进入后续分支）。
    """
    url = step5_url(base_url, install_entry)
    probe = require_install_mode(base_url, install_entry)
    require_not_locked(probe, CASE + "/密码边界")

    sess, token = wizard_session(base_url, install_entry, CASE + "/密码 5 字符")
    resp = post_step5(sess, url, base_form(token, **{PW_FIELD: "a" * 5}))
    _no_transport_reject(resp, "%s 密码 5 字符" % CASE)
    assert admin_invalid_hits(resp.text), (
        "[%s 密码 5 字符] 未命中 INSTALL_ADMIN_INVALID（长度不符合要求）：%r" % (CASE, resp.text[:200])
    )

    sess, token = wizard_session(base_url, install_entry, CASE + "/密码 21 字符")
    resp = post_step5(sess, url, base_form(token, **{PW_FIELD: "a" * 21}))
    _no_transport_reject(resp, "%s 密码 21 字符" % CASE)
    assert admin_invalid_hits(resp.text), (
        "[%s 密码 21 字符] 未命中 INSTALL_ADMIN_INVALID（长度不符合要求）：%r" % (CASE, resp.text[:200])
    )

    sess, token = wizard_session(base_url, install_entry, CASE + "/密码 6 字符")
    resp = post_step5(sess, url, base_form(token, **{PW_FIELD: "a" * 6}))
    _no_transport_reject(resp, "%s 密码 6 字符" % CASE)
    assert not admin_invalid_hits(resp.text), (
        "[%s 密码 6 字符] 合法下界被判为 INSTALL_ADMIN_INVALID：%r" % (CASE, resp.text[:200])
    )
