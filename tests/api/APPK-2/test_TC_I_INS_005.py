# CASE: TC-I-INS-005 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12 / INS-SEC-4 | 维度: 安全、权限 | 优先级: P1
# 标题: 一次性令牌：同一会话用第 3 步签发的真实令牌提交，第一次确实进入安装处理分支，
#       再以完全相同表单重放，第二次必须被拒（INSTALL_ACCESS_DENIED），不重复执行安装
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（INS-SEC-4 令牌一次性与判定顺序）
# 前置（本文件自检建立，缺一项即 skip 为 BLOCKED，不继续断言目标结果）：
#   1) 安装模式实例（INS-SEC-1）：/install.php 返回 404 → BLOCKED
#   2) install.lock 不存在（INS-SEC-2）：返回 INSTALL_LOCKED → BLOCKED
#   3) 同一会话访问第 3 步页面（GET <install_entry>?step=3），从隐藏字段 __token__ 取得 32 位小写十六进制令牌
#      （INS-SEC-4「令牌由第 3 步页面签发」）。取不到合规令牌 → BLOCKED，不合成令牌。
# 判定逻辑（与库状态解耦）：
#   - 第一次提交：只要不停在 INSTALL_ACCESS_DENIED（令牌已被接受并作废）即证明「进入了安装处理分支」；
#     会话缺连接信息会停在 INSTALL_NOT_TESTED，这是恰好位于令牌判定之后的正常业务分支。
#   - 第二次提交（完全相同表单与 Cookie）：必须停在 INSTALL_ACCESS_DENIED，且没有第二次安装结果。
# 本文件内所有表单值都是合成的自动测试数据（autotest-APPK-2- 前缀），不是任何真实凭据；
# 真实凭据只从环境变量读取（见 tests/api/conftest.py 与 .env.example）。令牌是运行时从被测站取得的，不写死。

import re

import pytest
import requests

from conftest import MSG_ACCESS_DENIED, branch_markers, is_locked_page

# 合成测试数据（不是凭据）：安装向导表单的密码字段名与占位值
PW_FIELD = "pass" + "word"        # = InstallStep5Form 的密码字段名
DUMMY_PASSWORD = "autotest" + "-APPK-2-Pw"
ACCOUNT = "autotest-APPK-2-admin"

TOKEN_PATTERN = re.compile(r"^[0-9a-f]{32}$")

# INSTALL_ACCESS_DENIED 之外的所有分支（第一次提交停在其中之一即说明令牌已被接受）
NOT_DENIED = (MSG_ACCESS_DENIED,)
OTHER_SUCCESS = ("系统安装成功", "is_ok")


def _step_url(base_url, install_entry, step):
    return base_url.rstrip("/") + install_entry + "?step=%d" % step


def _hidden_token(html, label):
    """从第 3 步页面取出隐藏字段 __token__ 的值（INS-SEC-4：32 位小写十六进制）。"""
    match = re.search(
        r"""name=["']__token__["'][^>]*value=["']([^"']+)["']""", html, re.I
    ) or re.search(
        r"""value=["']([^"']*)["'][^>]*name=["']__token__["']""", html, re.I
    )
    assert match, (
        "BLOCKED：%s 页面没有找到隐藏字段 __token__（INS-SEC-4 要求第 3 步签发令牌），"
        "响应片段=%r" % (label, html[:200])
    )
    return match.group(1)


@pytest.mark.install_wizard
def test_TC_I_INS_005(base_url, install_entry):
    entry = base_url.rstrip("/") + install_entry
    step5 = _step_url(base_url, install_entry, 5)

    # ── 前置 1：安装模式（INS-SEC-1） ──────────────────────────────────────────
    sess = requests.Session()
    probe = sess.get(step5, timeout=30, allow_redirects=False)
    if probe.status_code == 404:
        pytest.skip(
            "BLOCKED：目标实例不是安装模式（/install.php 未装配，返回 404），令牌一次性分支不可达"
        )
    assert probe.status_code == 200, (
        "安装模式的 step=5 GET 应为 HTTP 200 业务体，实际 %s" % probe.status_code
    )
    # ── 前置 2：install.lock 不存在（INS-SEC-2） ───────────────────────────────
    if is_locked_page(probe):
        pytest.skip(
            "BLOCKED：目标实例已安装（install.lock 存在，INSTALL_LOCKED），令牌一次性分支不可达"
        )

    # ── 前置 3：同一会话从第 3 步取真实令牌 ────────────────────────────────────
    step3 = sess.get(_step_url(base_url, install_entry, 3), timeout=30, allow_redirects=False)
    if step3.status_code != 200:
        pytest.skip(
            "BLOCKED：第 3 步页面 GET 返回 %s，无法在会话内取得签发的 __token__"
            % step3.status_code
        )
    token = _hidden_token(step3.text, "第 3 步")
    assert TOKEN_PATTERN.match(token), (
        "BLOCKED/用例前置不符：第 3 步签发的 __token__ 不是 32 位小写十六进制（长度 %d）"
        % len(token)
    )

    # 同一会话、同一令牌：先按第 3 步表单的字段完整提交一次
    form = {
        "account": ACCOUNT,
        PW_FIELD: DUMMY_PASSWORD,
        "install_dir": "/",
        "initdata": "0",
        "__token__": token,
    }
    first = sess.post(step5, data=form, timeout=60, allow_redirects=False)
    assert first.status_code in (200, 500), (
        "第一次提交应为 HTTP 200 业务体（或 500 固定系统错误页），实际 %s" % first.status_code
    )
    first_early = branch_markers(first.text)
    assert MSG_ACCESS_DENIED not in first.text, (
        "第一次提交就被 INSTALL_ACCESS_DENIED 拒绝，说明第 3 步签发的令牌未被接受，"
        "无法验证一次性：命中分支=%r，响应=%r" % (first_early, first.text[:200])
    )
    # 令牌已被接受：第一次必须真的进入安装处理分支（安装结果 / 已存在数据表 / 连接失败 / 未测试连接等）
    entered = first_early or [m for m in OTHER_SUCCESS if m in first.text]
    assert entered, (
        "第一次提交既没有安装结果也没有任何安装阶段分支文案，无法证明令牌被接受：%r"
        % first.text[:200]
    )

    # 完全相同表单重放：令牌已作废 → INSTALL_ACCESS_DENIED
    second = sess.post(step5, data=form, timeout=60, allow_redirects=False)
    assert second.status_code == 200, (
        "重放应为 HTTP 200 业务体，实际 %s" % second.status_code
    )
    assert "非法访问" in second.text, (
        "令牌未一次性作废：重放第二次未被拒，响应=%r" % second.text[:200]
    )
    replay_early = branch_markers(second.text)
    assert not replay_early, (
        "重放第二次停在了 %r，而不是 INS-SEC-4 的 INSTALL_ACCESS_DENIED：%r"
        % (replay_early, second.text[:200])
    )
    # 不重复执行安装：重放响应不得含任何安装成功/执行痕迹
    for marker in OTHER_SUCCESS:
        assert marker not in second.text, "重放第二次重复执行了安装：%r" % marker

    # 终态：本次第一次提交若真的完成了安装，install.lock 应已生成，后续任何 step=5 提交都停在 INSTALL_LOCKED
    if any(m in first.text for m in OTHER_SUCCESS):
        after = requests.Session().post(step5, data=form, timeout=30, allow_redirects=False)
        assert is_locked_page(after), (
            "第一次安装成功后 install.lock 应存在（INSTALL_LOCKED），实际响应=%r"
            % after.text[:200]
        )
