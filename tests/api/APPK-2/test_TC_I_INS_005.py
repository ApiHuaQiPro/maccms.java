# CASE: TC-I-INS-005 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12 / INS-SEC-4 | 维度: 安全、权限 | 优先级: P1
# 标题: 一次性令牌：同一会话用第 3 步签发的真实令牌，第一次在写库之前被拒也必须作废令牌，
#       完全相同表单重放第二次被 INSTALL_ACCESS_DENIED 拒绝；安装成功后任意请求停在 INSTALL_LOCKED
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452
#       （INS-SEC-4「令牌在通过校验的同时从会话删除」、INS-SEC-2 install.lock、x-idempotency「不可重放」）
# 前置（install_support.wizard_session 建立并逐项核验，任一不成立即 BLOCKED）：
#   1) 安装模式实例（INS-SEC-1）；2) install.lock 不存在（INS-SEC-2）；
#   3) 同一会话第 3 步签发的 32 位小写十六进制 __token__（INS-SEC-4）。
# 第一次提交的判定（REV-I-02）：契约里令牌通过之后的第一个分支是 install_dir 校验。本用例第一次提交
#   故意用 install_dir=/sub 的非 / 值，于是「令牌通过」的唯一可观测结果是 INSTALL_DIR_UNSUPPORTED，
#   写库阶段一个字节都不会动（契约「写库阶段之前的失败不产生任何写入」）。
#   只命中 INSTALL_NOT_TESTED / INSTALL_ENV_FAILED / INSTALL_ADMIN_* 的响应证明不了令牌被消费，
#   一律 BLOCKED，不当作「令牌被接受」（上一轮把 INSTALL_NOT_TESTED 当成消费证据，是假通过）。
# 覆盖的终态：
#   A. 锁不存在时的令牌重放（主用例，不改变库状态，可重复执行）；
#   B. 同一会话在令牌已作废后重新打开第 3 步取新令牌 → 可再次越过令牌检查；
#   C. 实例已安装（install.lock 存在）时的锁终态：任何方法、任何 step 只返回 INSTALL_LOCKED。
#      安装成功后写成锁的状态由 TC-I-INS-002（已安装实例）覆盖，本用例不改写被测实例。
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
    MSG_ACCESS_DENIED,
    TOKEN_PATTERN,
    hidden_token,
    MSG_DIR_UNSUPPORTED,
    MSG_SUCCESS,
    after_token_hits,
    assert_html_reply,
    assert_no_install_execution,
    assert_not_echoed,
    before_token_hits,
    base_form,
    has_locked_text,
    is_access_denied,
    post_step5,
    require_install_mode,
    step3_url,
    step5_url,
    wizard_session,
)

CASE = "INS-005"


@pytest.mark.install_wizard
def test_TC_I_INS_005(base_url, install_entry):
    url = step5_url(base_url, install_entry)

    # ── 前置：安装模式（INS-SEC-1）+ install.lock 不存在（INS-SEC-2） ─────────────
    probe = require_install_mode(base_url, install_entry)
    if has_locked_text(probe.text):
        pytest.skip(
            "BLOCKED：目标实例已安装（install.lock 存在，INSTALL_LOCKED），锁不存在时的令牌重放分支不可达"
        )
    sess, token = wizard_session(base_url, install_entry)

    # ── 第一次提交：有效令牌 + install_dir=/sub（令牌之后、写库之前的第一个分支） ──
    form = base_form(token, install_dir="/sub")
    first = post_step5(sess, url, form)
    assert_html_reply(first, "%s 第一次提交" % CASE)
    assert MSG_ACCESS_DENIED not in first.text, (
        "[%s] 第一次提交就被 INSTALL_ACCESS_DENIED 拒绝：第 3 步签发的令牌未被接受，"
        "一次性无法验证。令牌之前文案=%r，响应=%r"
        % (CASE, before_token_hits(first.text), first.text[:200])
    )
    if MSG_DIR_UNSUPPORTED not in first.text:
        pytest.skip(
            "BLOCKED：[%s 第一次提交] 未命中 INSTALL_DIR_UNSUPPORTED，无法证明令牌通过并已消费。"
            "令牌之前文案=%r、令牌之后分支=%r、响应=%r。"
            "需要「第 4 步测试连接已完成」的安装模式实例（否则契约在令牌之前返回 INSTALL_NOT_TESTED）。"
            % (
                CASE,
                before_token_hits(first.text),
                after_token_hits(first.text),
                first.text[:200],
            )
        )
    assert MSG_SUCCESS not in first.text, "[%s] install_dir 非法却完成了安装" % CASE

    # ── 完全相同表单与 Cookie 重放：令牌已作废 → INSTALL_ACCESS_DENIED ───────────
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

    # ── 对照：同一会话重新打开第 3 步取得新令牌，必须能再次越过令牌检查 ──────────
    # 证明上面的拒绝来自「令牌已被消费」，而不是会话或实例状态变化造成的伪拒绝。
    step3 = sess.get(step3_url(base_url, install_entry), timeout=30, allow_redirects=False)
    if step3.status_code != 200:
        pytest.skip(
            "BLOCKED：[%s 对照] 令牌作废后第 3 步 GET 返回 %s，无法取得新令牌，"
            "「重放被拒来自令牌一次性」缺少对照证据" % (CASE, step3.status_code)
        )
    token2 = hidden_token(step3.text)
    if not token2 or not TOKEN_PATTERN.match(token2):
        pytest.skip(
            "BLOCKED：[%s 对照] 第 3 步未签发新的 32 位小写十六进制令牌，缺少对照证据" % CASE
        )
    third = post_step5(sess, url, base_form(token2, install_dir="/sub"))
    assert_html_reply(third, "%s 新令牌对照" % CASE)
    assert MSG_DIR_UNSUPPORTED in third.text, (
        "[%s 新令牌对照] 新令牌未能越过令牌检查（命中=%r），"
        "「重放被拒来自令牌一次性」缺少区分力：%r"
        % (CASE, before_token_hits(third.text) + after_token_hits(third.text), third.text[:200])
    )


@pytest.mark.install_wizard
def test_TC_I_INS_005_installed_lock_terminal_state(base_url, install_entry):
    """C 部分：已安装实例（install.lock 存在）的锁终态。

    与主用例的前置互斥（主用例要求未安装），所以在已安装实例上单独判定：
      - 任何方法、任何 step 都只返回 INSTALL_LOCKED 固定文本（不读参数、不建会话、不进入写库阶段）；
      - 未安装实例上本函数 BLOCKED（由 TC-I-INS-002 覆盖同一断言）。
    """
    probe = requests.Session().get(step5_url(base_url, install_entry), timeout=30, allow_redirects=False)
    if probe.status_code == 404:
        pytest.skip("BLOCKED：目标实例不是安装模式（/install.php 未装配，返回 404），锁终态不可达")
    if not has_locked_text(probe.text):
        pytest.skip(
            "BLOCKED：目标实例未安装（install.lock 不存在），锁终态分支不可达；"
            "本断言在已安装实例上由 TC-I-INS-002 覆盖"
        )

    url = step5_url(base_url, install_entry)
    sess = requests.Session()
    attempts = [
        ("GET step=5", lambda: sess.get(url, timeout=30, allow_redirects=False)),
        ("POST step=5 表单", lambda: sess.post(url, data=base_form("0" * 32), timeout=30, allow_redirects=False)),
        (
            "POST step=5 非法方法体",
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
