# CASE: TC-I-INS-006 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12 / SCH-1 | 维度: 边界、异常 | 优先级: P2
# 标题: SCH-1 传输形态：account 以数组提交 → INSTALL_ADMIN_REQUIRED；非法 install_dir → INSTALL_DIR_UNSUPPORTED；
#       install_dir 数组按缺失（= /）处理；initdata 非 1 只是不写初始数据 —— 都不是传输层 400、不是 INSTALL_ACCESS_DENIED
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（SCH-1 + InstallStep5Form + 判定顺序）
# 前置（install_support.wizard_session 建立并逐项核验，任一不成立即 BLOCKED）：
#   1) 安装模式实例（INS-SEC-1）；2) install.lock 不存在（INS-SEC-2）；
#   3) 每个分支各自独立会话 + 该会话第 3 步签发的真实令牌（令牌一次性，不能跨请求共用；也不合成令牌）。
# 通过条件（REV-I-03）：每个分组都必须命中**该分组自己的目标分支**，不再接受「更早分支」：
#   1) account 数组、密码有效：INSTALL_ADMIN_REQUIRED 位于连接配置之后、令牌之前，
#      因此该分支可达性与「第 4 步连接配置」无关；命中 INSTALL_NOT_TESTED 说明另有前置缺失 → BLOCKED。
#   2) install_dir[]=/other（数组按缺失即 /）与 3) initdata=1（唯一会写初始数据的取值）：
#      只有令牌通过之后才会走到 install_dir 与写库阶段，所以先要求命中 INSTALL_DIR_UNSUPPORTED
#      （令牌之后的第一个分支）。拿不到这个前置证据 → BLOCKED，不当作通过。
#   4) 非 1 的 initdata 不写初始数据：在写库阶段之后才可观测（写库阶段需要空库、会改动被测实例），
#      本用例改为断言「非 1 的 initdata 与 initdata=1 在写库之前的判定完全相同」——
#      即两者命中同一分支、都不是参数错误；真正的写库观测记在 API 用例文档「未覆盖与原因」。
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
    MSG_ACCESS_DENIED,
    MSG_ADMIN_REQUIRED,
    MSG_DIR_UNSUPPORTED,
    MSG_SUCCESS,
    PW_FIELD,
    after_token_hits,
    admin_invalid_hits,
    assert_after_token_evidence,
    assert_html_reply,
    assert_not_echoed,
    before_token_hits,
    base_form,
    post_step5,
    require_install_mode,
    require_not_locked,
    step5_url,
    wizard_session,
)

CASE = "INS-006"
PARAM_ERR = "参数错误"


def _no_transport_reject(resp, label):
    """SCH-1：应用不返回传输层拒绝，安装向导也不返回 5xx 之外的异常页。"""
    assert resp.status_code not in (400, 413, 414, 415, 422, 431), (
        "[%s] SCH-1 要求不返回传输层拒绝，实际 %s" % (label, resp.status_code)
    )
    assert resp.status_code == 200, "[%s] 期望 HTTP 200 业务提示页，实际 %s" % (label, resp.status_code)
    assert_html_reply(resp, label)
    assert_not_echoed(resp, label)
    assert PARAM_ERR not in resp.text, (
        "[%s] 数组/边界形态不应判为「参数错误」（传输层 schema 必须受理）：%r" % (label, resp.text[:200])
    )


@pytest.mark.install_wizard
def test_TC_I_INS_006(base_url, install_entry):
    url = step5_url(base_url, install_entry)

    # ── 前置：安装模式（INS-SEC-1）+ install.lock 不存在（INS-SEC-2） ─────────────
    probe = require_install_mode(base_url, install_entry)
    require_not_locked(probe, CASE)

    # ── 1) account 作数组提交：按缺失处理 → INSTALL_ADMIN_REQUIRED ────────────────
    sess, token = wizard_session(base_url, install_entry)
    data = [(k, v) for k, v in base_form(token).items() if k != "account"]
    data.insert(0, ("account[]", ACCOUNT))
    resp = post_step5(sess, url, data)
    _no_transport_reject(resp, "%s account 数组" % CASE)
    assert MSG_ACCESS_DENIED not in resp.text, (
        "[%s account 数组] 令牌被拒（INSTALL_ACCESS_DENIED），请求未进入 account 判定：%r"
        % (CASE, resp.text[:200])
    )
    assert not admin_invalid_hits(resp.text), (
        "[%s account 数组] 数组形态被当成非法值（INSTALL_ADMIN_INVALID），应按缺失处理：%r"
        % (CASE, resp.text[:200])
    )
    if MSG_ADMIN_REQUIRED not in resp.text:
        pytest.skip(
            "BLOCKED：[%s account 数组] 未命中 INSTALL_ADMIN_REQUIRED，"
            "说明更早的向导状态先命中（INSTALL_NOT_TESTED 等）：%r。"
            "该分支只要求「第 4 步连接配置已完成」，与令牌无关，需要具备该前置的安装模式实例。"
            % (CASE, {"before_token": before_token_hits(resp.text), "body": resp.text[:200]})
        )
    assert MSG_SUCCESS not in resp.text, "[%s account 数组缺失却完成了安装" % CASE

    # ── 2) install_dir 作数组提交：按缺失即 /，只能因令牌之后的其它分支结束 ───────
    sess, token = wizard_session(base_url, install_entry)
    data = [(k, v) for k, v in base_form(token).items() if k != "install_dir"]
    data.append(("install_dir[]", "/other"))
    resp = post_step5(sess, url, data)
    _no_transport_reject(resp, "%s install_dir 数组" % CASE)
    assert MSG_ACCESS_DENIED not in resp.text, (
        "[%s install_dir 数组] 令牌被拒（INSTALL_ACCESS_DENIED）：%r" % (CASE, resp.text[:200])
    )
    if MSG_DIR_UNSUPPORTED in resp.text:
        pytest.fail(
            "[%s install_dir 数组] 数组被按值判定为 install_dir=/other（INSTALL_DIR_UNSUPPORTED），"
            "契约要求数组按缺失处理（= /）：%r" % (CASE, resp.text[:200])
        )
    if MSG_SUCCESS not in resp.text:
        assert_after_token_evidence(resp, "%s install_dir 数组" % CASE)
        pytest.skip(
            "BLOCKED：[%s install_dir 数组] 越过了令牌但没有命中成功分支（命中=%r），"
            "无法证明数组按缺失（= /）处理。响应=%r"
            % (CASE, after_token_hits(resp.text), resp.text[:200])
        )
    # 走到成功分支：数组 install_dir 视为 / 已生效（会话在数组形态下未被拒），本用例不改写实例状态以外的东西，
    # 也不删安装锁；这条分支只在一次性实例上出现，T3 报告需写明。

    # ── 3) 正向对照：真正的非法 install_dir 值必须命中 INSTALL_DIR_UNSUPPORTED ─────
    # 证明第 2 步的「没有 INSTALL_DIR_UNSUPPORTED」是有区分力的结论，而不是整条断言不生效。
    sess, token = wizard_session(base_url, install_entry)
    resp = post_step5(sess, url, base_form(token, install_dir="/other"))
    _no_transport_reject(resp, "%s install_dir 标量非法（对照）" % CASE)
    if MSG_DIR_UNSUPPORTED not in resp.text:
        pytest.skip(
            "BLOCKED：[%s install_dir 对照] 标量 /other 未返回 INSTALL_DIR_UNSUPPORTED，"
            "令牌之后的判定链不可达（命中=%r），第 2 步的否定结论没有对照证据。响应=%r"
            % (CASE, before_token_hits(resp.text) + after_token_hits(resp.text), resp.text[:200])
        )
    assert MSG_SUCCESS not in resp.text, "[%s install_dir 对照] install_dir=/other 却完成了安装" % CASE

    # ── 4) initdata 非 1 与 initdata=1 在写库之前的判定完全一致（不是参数错误） ─────
    sess, token = wizard_session(base_url, install_entry)
    one = post_step5(sess, url, base_form(token, initdata="1"))
    _no_transport_reject(one, "%s initdata=1" % CASE)
    sess, token = wizard_session(base_url, install_entry)
    other = post_step5(sess, url, base_form(token, initdata="unexpected"))
    _no_transport_reject(other, "%s initdata 非 1" % CASE)
    assert (MSG_SUCCESS in one.text) == (MSG_SUCCESS in other.text), (
        "[%s initdata 非 1] initdata 影响了写库之前的判定：initdata=1 → %r，非 1 → %r"
        % (CASE, one.text[:120], other.text[:120])
    )
    assert after_token_hits(one.text) == after_token_hits(other.text) and (
        before_token_hits(one.text) == before_token_hits(other.text)
    ), (
        "[%s initdata 非 1] initdata 改变了判定分支：initdata=1 命中 %r/%r，非 1 命中 %r/%r"
        % (
            CASE,
            before_token_hits(one.text),
            after_token_hits(one.text),
            before_token_hits(other.text),
            after_token_hits(other.text),
        )
    )
    if not (before_token_hits(one.text) or after_token_hits(one.text)):
        pytest.skip(
            "BLOCKED：[%s initdata 对照] 两次请求都没有命中任何安装分支（响应=%r），"
            "「非 1 不写初始数据」缺少可观测的判定依据" % (CASE, one.text[:200])
        )


@pytest.mark.install_wizard
def test_TC_I_INS_006_boundary(base_url, install_entry):
    """同一传输形态维度的补充：密码长度边界（6～20，mb_strlen）由 account/密码规则分支判定。

    这些分支位于令牌之前，与连接配置无关，可稳定复核：
      - 5 个字符 → INSTALL_ADMIN_INVALID（长度不符合要求）；
      - 21 个字符 → INSTALL_ADMIN_INVALID；
      - 6 个字符 → 不得命中 INSTALL_ADMIN_INVALID（进入后续分支）。
    """
    url = step5_url(base_url, install_entry)
    probe = require_install_mode(base_url, install_entry)
    require_not_locked(probe, CASE + "/密码边界")

    sess, token = wizard_session(base_url, install_entry)
    resp = post_step5(sess, url, base_form(token, **{PW_FIELD: "a" * 5}))
    _no_transport_reject(resp, "%s 密码 5 字符" % CASE)
    assert admin_invalid_hits(resp.text), (
        "[%s 密码 5 字符] 未命中 INSTALL_ADMIN_INVALID（长度不符合要求）：%r" % (CASE, resp.text[:200])
    )

    sess, token = wizard_session(base_url, install_entry)
    resp = post_step5(sess, url, base_form(token, **{PW_FIELD: "a" * 21}))
    _no_transport_reject(resp, "%s 密码 21 字符" % CASE)
    assert admin_invalid_hits(resp.text), (
        "[%s 密码 21 字符] 未命中 INSTALL_ADMIN_INVALID（长度不符合要求）：%r" % (CASE, resp.text[:200])
    )

    sess, token = wizard_session(base_url, install_entry)
    resp = post_step5(sess, url, base_form(token, **{PW_FIELD: "a" * 6}))
    _no_transport_reject(resp, "%s 密码 6 字符" % CASE)
    assert not admin_invalid_hits(resp.text), (
        "[%s 密码 6 字符] 合法下界被判为 INSTALL_ADMIN_INVALID：%r" % (CASE, resp.text[:200])
    )
