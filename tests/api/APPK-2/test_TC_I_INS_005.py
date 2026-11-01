# CASE: TC-I-INS-005 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12 / INS-SEC-4 | 维度: 安全、权限 | 优先级: P1
# 标题: 一次性令牌：同一会话第一次用真实令牌越过令牌检查（令牌在通过时即从会话删除）后，
#       完全相同的表单重放第二次被 INSTALL_ACCESS_DENIED 拒绝；重新打开第 3 步取新令牌可再次越过。
#       第 4 次修订 REV-I-06：上一版把「已安装实例的锁终态」作为本文件里的第二个 test，
#       已拆为独立 CASE TC-I-INS-009（test_TC_I_INS_009.py，追溯 AC-12 / INS-SEC-2），
#       本文件因此恢复「1 条 CASE = 1 个 test = 1 个文件」，且只追溯 AC-12 / INS-SEC-4。
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452
#       （INS-SEC-4「令牌在通过校验的同时从会话删除」、INS-SEC-2 install.lock、x-idempotency「不可重放」）
#
# 前置（两相，任一不成立即 BLOCKED）：
#   相 1：安装模式 + 无 install.lock + 同一会话第 3 步签发的 32 位小写十六进制令牌（wizard_session）；
#   相 2：令牌之后的分支可达 —— 第一次提交就是「有效令牌 + install_dir=/sub」，
#         必须命中 INSTALL_DIR_UNSUPPORTED（令牌之后、root 与写库之前的分支；不写库、不写 lock）。
#         只命中 INSTALL_NOT_TESTED / INSTALL_ENV_FAILED / INSTALL_ADMIN_* 说明停在令牌之前 → BLOCKED，
#         不能当成「令牌已通过」（第 3 轮复核 REV-I-01／REV-I-02 的假通过根源）。
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
    DIR_NOT_ROOT,
    MSG_DIR_UNSUPPORTED,
    MSG_SUCCESS,
    after_token_hits,
    assert_html_reply,
    assert_not_echoed,
    before_token_hits,
    is_access_denied,
    post_step5,
    renew_token,
    step5_url,
    wizard_session,
)

CASE = "INS-005"


@pytest.mark.install_wizard
def test_TC_I_INS_005(base_url, install_entry):
    url = step5_url(base_url, install_entry)

    # ── 相 1：安装模式 + 无 lock + 会话令牌 ────────────────────────────────────
    sess, token = wizard_session(base_url, install_entry, CASE)

    # ── 相 2 + 第一次提交：有效令牌 + install_dir=/sub → INSTALL_DIR_UNSUPPORTED ──
    # 这是契约里令牌通过之后的第一个分支，写库阶段一个字节都不会动（处置失败后的状态条款）。
    form = base_form(token, install_dir=DIR_NOT_ROOT)
    first = post_step5(sess, url, form)
    assert_html_reply(first, "%s 第一次提交" % CASE)
    if MSG_DIR_UNSUPPORTED not in first.text:
        assert not is_access_denied(first), (
            "[%s] 第一次提交（第 3 步刚签发的有效令牌）被 INSTALL_ACCESS_DENIED 拒绝，"
            "令牌未被接受，一次性无法验证：%r" % (CASE, first.text[:200])
        )
        pytest.skip(
            "BLOCKED：[%s 第一次提交] 未命中 INSTALL_DIR_UNSUPPORTED，需要「第 4 步测试连接已完成」"
            "（MACCMS_DEPLOY_CONFIG 有连接信息）的安装模式实例才能到达令牌之后的分支。"
            "命中=%r，响应=%r"
            % (CASE, before_token_hits(first.text) or after_token_hits(first.text), first.text[:200])
        )
    assert MSG_SUCCESS not in first.text, "[%s] install_dir 非法却完成了安装" % CASE

    # ── 完全相同表单与 Cookie 重放：令牌已在第一次通过时被消费 → INSTALL_ACCESS_DENIED ──
    second = post_step5(sess, url, form)
    assert_html_reply(second, "%s 重放" % CASE)
    assert is_access_denied(second), (
        "[%s] 令牌未一次性作废：重放第二次未被拒，响应=%r" % (CASE, second.text[:200])
    )
    replay_after = after_token_hits(second.text)
    assert not replay_after, (
        "[%s] 重放第二次命中了令牌之后的安装分支 %r，说明令牌被重复使用：%r"
        % (CASE, replay_after, second.text[:200])
    )
    for marker in (MSG_SUCCESS, MSG_DIR_UNSUPPORTED, "install.lock"):
        assert marker not in second.text, "[%s] 重放第二次重复执行了安装：%r" % (CASE, marker)
    assert_not_echoed(second, "%s 重放" % CASE)
    assert token not in second.text, "[%s] 重放响应回显了令牌" % CASE
    assert_no_install_execution(second, "%s 重放" % CASE)

    # ── 对照：同一会话重新打开第 3 步取新令牌，必须能再次越过令牌检查 ──────────────
    # 证明上面的拒绝来自「令牌已被消费」，而不是会话或实例状态变化造成的伪拒绝。
    token2 = renew_token(sess, base_url, install_entry, "%s 新令牌对照" % CASE)
    assert token2 != token, "[%s] 重新打开第 3 步签发了与已消费令牌相同的令牌" % CASE
    third = post_step5(sess, url, base_form(token2, install_dir=DIR_NOT_ROOT))
    assert_html_reply(third, "%s 新令牌对照" % CASE)
    assert MSG_DIR_UNSUPPORTED in third.text, (
        "[%s 新令牌对照] 新令牌未能越过令牌检查（命中=%r），"
        "「重放被拒来自令牌一次性」缺少区分力：%r"
        % (CASE, before_token_hits(third.text) + after_token_hits(third.text), third.text[:200])
    )
    assert MSG_SUCCESS not in third.text, "[%s 新令牌对照] install_dir 非法却完成了安装" % CASE
