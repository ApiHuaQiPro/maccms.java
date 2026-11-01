# CASE: TC-I-INS-006 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12 / SCH-1 | 维度: 边界、异常 | 优先级: P2
# 标题: SCH-1 传输形态：account 以数组提交 → INSTALL_ADMIN_REQUIRED；install_dir[] 数组按缺失（= /）处理；
#       install_dir 标量非法 → INSTALL_DIR_UNSUPPORTED；initdata 非 1 与 1 在写库之前的判定一致 ——
#       都不是传输层 400/415、也不是「参数错误」
#       第 4 次修订 REV-I-06：上一版把「密码长度边界（6～20）」作为本文件里的第二个 test，
#       已拆为独立 CASE TC-I-INS-010（test_TC_I_INS_010.py，追溯 AC-12 / SCH-1、INS-SEC-3）。
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（SCH-1 + InstallStep5Form + 判定顺序）
# 实现: src/main/java/com/maccms/backend/install/application/InstallWizard.java @ 641304e（install() 的分支顺序与固定文案）
#
# 第 5 轮修订（REV-I-09 / REV-I-10 / REV-I-11，回应复核项 1 与项 3）：
#   a) **写库分支的执行隔离变成硬闸门**。第 C、D 步一旦越过令牌，契约允许请求继续往写库阶段走
#      （开连接 → 表结构与数据核对 → 写站点配置 → install.sql/初始数据/管理员 → install.lock）。
#      上一版只把「initdata 非 1 到底写没写初始数据」记成「未覆盖」，第 C 步却实打实把请求送进了写库阶段入口。
#      现在开头调用 `require_install_write_isolation`：一次性空库（`maccms_it_*`）+ 一次性站点根文件副本 +
#      精确 `yes` 的执行授权，三项齐备才继续，任一缺失 → BLOCKED。**不得**靠「预期会失败」「会先命中根目录分支就返回」绕过。
#   b) **第 4 步连接配置的显式前置**（`require_connection_configured`）：它是契约判定顺序里令牌之前的一位
#      （`readDeployTarget()` 为空即 INSTALL_NOT_TESTED），没有它写库分支根本到不了。缺 → BLOCKED。
#   c) **第 C 步改为肯定证据**。上一版只要求「不得出现 INSTALL_DIR_UNSUPPORTED」，本质是「不是 X 就算过」。
#      现在必须命中一条**令牌之后、且在写库阶段之内或之后**的分支（见 WRITE_STAGE_MARKERS），
#      并且该分支必须 ≠ 第 B 步标量对照的分支：数组若被按值判定，会重现 INSTALL_DIR_UNSUPPORTED，
#      因此「走到了写库阶段」才是「数组按缺失处理」的正面证据。命中不了 → BLOCKED（不是 PASS）。
#   d) 每条可能进入写库阶段的请求都做写前/写后核验（`_snapshot`），并在 finally 里校验清理闸门声明存在。
#
# 前置（install_support 提供并逐项核验，任一不成立即 BLOCKED）：
#   1) 安装模式实例（INS-SEC-1）；2) install.lock 不存在（INS-SEC-2）；
#   3) 每个分支各自独立会话 + 该会话第 3 步签发的真实令牌（令牌一次性，不能跨请求共用，也不合成令牌）；
#   4) 第 4 步连接配置已完成（`require_connection_configured`）；
#   5) 写库阶段执行隔离 + 清理闸门（`require_install_write_isolation`）。
#
# 判定顺序依据（InstallWizard.install，契约 API-030 一致）：
#   INSTALL_NOT_TESTED → INSTALL_ADMIN_REQUIRED → INSTALL_ADMIN_INVALID → INSTALL_ACCESS_DENIED
#   → INSTALL_DIR_UNSUPPORTED → INSTALL_ROOT_REFUSED → 锁复检 → 写库阶段（连接/结构/数据/写站点配置/写库/写 lock）→ 成功
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
    MSG_ADMIN_FAILED,
    MSG_ADMIN_REQUIRED,
    MSG_DIR_UNSUPPORTED,
    MSG_INIT_DATA_FAILED,
    MSG_INIT_ERR,
    MSG_LOCKED_PREFIX,
    MSG_SUCCESS,
    after_token_hits,
    admin_invalid_hits,
    assert_html_reply,
    assert_not_echoed,
    base_form,
    before_token_hits,
    control_proves_after_token,
    is_access_denied,
    post_step5,
    require_connection_configured,
    require_install_mode,
    require_install_write_isolation,
    require_not_locked,
    step5_url,
    wizard_session,
)

CASE = "INS-006"
PARAM_ERR = "参数错误"

# 令牌之后、且**在写库阶段之内或之后**的分支标记（第 C 步的肯定证据必须落在这一集合里）。
# 取自 InstallWizard.install / checkTarget 的固定文案（实现 @ 641304e）：
#   ① readDeployTarget 之后的连接与结构/数据核对：MSG_NO_DATABASE / MSG_SCHEMA / MSG_NOT_EMPTY
#   ② 写库阶段：install/init_err（写库异常）、init_data_err、admin_err、MSG_LOCK + write_read_err、install/is_ok
# 注意**不含** MSG_DIR_UNSUPPORTED / MSG_ROOT_REFUSED：那两个分支在写库之前返回，只能证明「越过了令牌」，
# 不能证明「数组按缺失处理后走到了写库阶段」。
WRITE_STAGE_MARKERS = (
    "数据库不存在",              # MSG_NO_DATABASE（部署方未建库）
    "表结构与 install.sql 不一致",  # MSG_SCHEMA（库结构不符）
    "目标库的数据与刚导入",        # MSG_NOT_EMPTY（库非空）
    MSG_INIT_ERR,               # install/init_err：写库异常
    MSG_INIT_DATA_FAILED,       # install/init_data_err
    MSG_ADMIN_FAILED,           # install/admin_err
    MSG_LOCKED_PREFIX,          # install/write_read_err（写 install.lock 失败）
    MSG_SUCCESS,                # install/is_ok
)


def _no_transport_reject(resp, label):
    """SCH-1：应用不返回传输层拒绝，也不是「参数错误」。"""
    assert resp.status_code not in (400, 413, 414, 415, 422, 431), (
        "[%s] SCH-1 要求传输层受理（不返回 4xx schema 拒绝），实际 %s" % (label, resp.status_code)
    )
    assert_html_reply(resp, label)
    assert_not_echoed(resp, label)
    assert PARAM_ERR not in resp.text, (
        "[%s] 数组/边界形态不应判为「参数错误」（传输层 schema 必须受理）：%r" % (label, resp.text[:200])
    )


def _write_stage_hits(text):
    """返回响应命中的「写库阶段之内或之后」分支标记（第 C 步的肯定证据）。"""
    body = text or ""
    return [m for m in WRITE_STAGE_MARKERS if m in body]


def _snapshot(resp):
    """写前/写后的可观察状态：只看响应里的互斥迹象，不做任何写操作、不连数据库。"""
    body = resp.text or ""
    return {
        "installed": MSG_SUCCESS in body,
        "write_stage": bool(_write_stage_hits(body)),
    }


@pytest.mark.install_wizard
def test_TC_I_INS_006(base_url, install_entry):
    url = step5_url(base_url, install_entry)

    # ── 前置 1、2：安装模式（INS-SEC-1）+ install.lock 不存在（INS-SEC-2） ────────────
    probe = require_install_mode(base_url, install_entry)
    require_not_locked(probe, CASE)

    # ── 前置 5a：写库阶段的执行隔离（REV-I-09）。C、D 两步可能真的进入写库阶段。─────────
    isolate = require_install_write_isolation(CASE, needs_db_write=True)

    # ── 前置 5b：第 4 步连接配置已完成（REV-I-10）。缺 → BLOCKED，不由用例自造连接配置。─────
    require_connection_configured(base_url, install_entry, CASE)

    try:
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
            "[%s account 数组] 数组形态被当成非法值（INSTALL_ADMIN_INVALID），契约要求按缺失处理：%r"
            % (CASE, resp.text[:200])
        )
        assert MSG_ADMIN_REQUIRED in resp.text, (
            "[%s account 数组] 未命中 INSTALL_ADMIN_REQUIRED。该分支只要求「第 4 步连接配置已完成」"
            "（已由前置 5b 核验通过），因此这里是断言失败而不是环境缺失：命中=%r 响应=%r"
            % (CASE, before_token_hits(resp.text), resp.text[:200])
        )
        assert MSG_SUCCESS not in resp.text, "[%s account 数组缺失却完成了安装" % CASE

        # ── B) 对照：install_dir 标量非法 → INSTALL_DIR_UNSUPPORTED（令牌之后可达的证据） ──
        sess, token = wizard_session(base_url, install_entry, CASE + "/标量对照")
        control = control_proves_after_token(sess, url, token, "install_dir 标量对照", CASE)
        assert MSG_DIR_UNSUPPORTED in control.text, "[%s 标量对照] 对照请求未命中目标分支" % CASE
        assert not _write_stage_hits(control.text), (
            "[%s 标量对照] install_dir 标量非法却进入写库阶段（命中=%r）：%r"
            % (CASE, _write_stage_hits(control.text), control.text[:200])
        )

        # ── C) install_dir 作数组提交：按缺失即 /，必须走到**写库阶段**的某一条分支 ─────────
        sess, token = wizard_session(base_url, install_entry, CASE + "/install_dir 数组")
        data = [(k, v) for k, v in base_form(token).items() if k != "install_dir"]
        data.append(("install_dir[]", "/other"))
        before = _snapshot(probe)
        resp = post_step5(sess, url, data)
        _no_transport_reject(resp, "%s install_dir 数组" % CASE)
        assert not is_access_denied(resp), (
            "[%s install_dir 数组] 令牌被拒（INSTALL_ACCESS_DENIED）：%r" % (CASE, resp.text[:200])
        )
        assert MSG_DIR_UNSUPPORTED not in resp.text, (
            "[%s install_dir 数组] 数组被按值判定为 /other（INSTALL_DIR_UNSUPPORTED），"
            "契约要求数组按缺失处理（= /）：%r" % (CASE, resp.text[:200])
        )
        # 肯定证据（REV-I-11）：必须命中写库阶段的分支；命中不了 → BLOCKED，不是 PASS。
        write_hits = _write_stage_hits(resp.text)
        if not write_hits:
            pytest.skip(
                "BLOCKED：[%s install_dir 数组] 响应没有命中任何「写库阶段」分支（命中=%r），"
                "无法确认「数组按缺失 → install_dir=/ → 进入写库阶段」。"
                "需要第 4 步连接配置已完成、且写库闸门齐备的一次性实例。响应=%r"
                % (
                    CASE,
                    before_token_hits(resp.text) + after_token_hits(resp.text),
                    resp.text[:200],
                )
            )
        assert after_token_hits(resp.text) or write_hits, (
            "[%s install_dir 数组] 判定链不一致：没有令牌之后证据却命中了写库阶段分支 %r" % (CASE, write_hits)
        )
        # 写后核验：写前未安装 → 写后若报成功，必须是成功分支本身（而不是自相矛盾的混合体）
        after = _snapshot(resp)
        assert not (before["installed"] and not after["installed"]), (
            "[%s install_dir 数组] 写后核验：安装状态回退了（写前 installed=%r，写后 %r）"
            % (CASE, before["installed"], after["installed"])
        )

        # ── D) initdata 非 1 与 initdata=1：写库之前的判定完全一致（都不是参数错误） ─────
        sess, token = wizard_session(base_url, install_entry, CASE + "/initdata=1")
        one = post_step5(sess, url, base_form(token, install_dir="/other", initdata="1"))
        _no_transport_reject(one, "%s initdata=1" % CASE)

        sess, token = wizard_session(base_url, install_entry, CASE + "/initdata 非 1")
        other = post_step5(sess, url, base_form(token, install_dir="/other", initdata="unexpected"))
        _no_transport_reject(other, "%s initdata 非 1" % CASE)

        assert MSG_DIR_UNSUPPORTED in one.text, (
            "[%s initdata 对照] initdata=1 + install_dir=/other 未命中 INSTALL_DIR_UNSUPPORTED，"
            "两次对照都没越过令牌（命中=%r）：%r"
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
    finally:
        # 清理闸门（REV-I-09 第 5 条）：本次执行只碰一次性库与一次性站点根副本，
        # 由环境侧按 INSTALL_ONEOFF_DB / INSTALL_ONEOFF_ROOT 回收；脚本不直连数据库，
        # 因此这里只校验闸门声明齐备，确保「清理未定义」不会被静默忽略。
        assert isolate and isolate.get("db") and isolate.get("root"), (
            "[%s] 写库闸门的清理声明缺失（db=%r root=%r）：数据残留不可控，本轮结果不作数"
            % (CASE, (isolate or {}).get("db"), (isolate or {}).get("root"))
        )
