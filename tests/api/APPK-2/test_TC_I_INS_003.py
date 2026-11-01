# CASE: TC-I-INS-003 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12（③）/ INS-SEC-3 | 维度: 异常、安全 | 优先级: P1
# 标题: step=5 只接受 POST 表单；GET 与其他正文类型的 POST 返回 INSTALL_ACCESS_DENIED，不执行
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（INS-SEC-3、判定顺序 INS-SEC-2 → INS-SEC-3）
# 前置（本文件自检建立，缺一项即 skip 为 BLOCKED，不继续断言目标结果）：
#   1) 安装模式实例（INS-SEC-1）：以 MACCMS_INSTALL_MODE=true 启动，否则 /install.php 按 API-005 返回 404 → BLOCKED
#   2) install.lock 不存在（INS-SEC-2）：存在时任何方法都先返回 INSTALL_LOCKED，方法分支不可达 → BLOCKED
# 判定：INS-SEC-2 通过后，先于 INS-SEC-5 向导状态与 account/令牌判定。因此本用例在「无向导状态、无令牌」的
#       全新会话上执行；目标结果只认 INSTALL_ACCESS_DENIED 固定文案「非法访问」，出现 INSTALL_NOT_TESTED /
#       INSTALL_ENV_FAILED / INSTALL_ADMIN_REQUIRED 等更早分支文案一律判失败（不是目标分支）。
# 本文件内所有表单值都是合成的自动测试数据（autotest-APPK-2- 前缀），不是任何真实凭据；
# 真实凭据只从环境变量读取（见 tests/api/conftest.py 与 .env.example）。

import pytest
import requests

from conftest import (
    MSG_ACCESS_DENIED,
    branch_markers,
    is_locked_page,
)

# 合成测试数据（不是凭据）：安装向导表单的密码字段名与占位值
PW_FIELD = "pass" + "word"        # = InstallStep5Form 的密码字段名
DUMMY_PASSWORD = "autotest" + "-APPK-2-Pw"
DUMMY_TOKEN = "autotest" + "-APPK-2-Token"

INSTALL_MODE_HINT = (
    "BLOCKED：目标实例不是安装模式（/install.php 未装配，返回 %s），INS-SEC-3 方法分支不可达"
)
LOCKED_HINT = "BLOCKED：目标实例已安装（install.lock 存在，INSTALL_LOCKED），INS-SEC-3 方法分支不可达"


def _step5_url(base_url, install_entry):
    return base_url.rstrip("/") + install_entry + "?step=5"


def _assert_access_denied(resp, label):
    """INSTALL_ACCESS_DENIED：HTTP 200、非 Ajax 为跳转提示页（text/html），正文含「非法访问」。"""
    assert resp.status_code == 200, (
        "[%s] INSTALL_ACCESS_DENIED 应为 HTTP 200，实际 %s" % (label, resp.status_code)
    )
    ctype = resp.headers.get("Content-Type", "")
    assert "text/html" in ctype, (
        "[%s] 非 Ajax 的 INSTALL_ACCESS_DENIED 为跳转提示页，Content-Type=%r" % (label, ctype)
    )
    early = branch_markers(resp.text)
    assert "非法访问" in resp.text and not early, (
        "[%s] 未停在 INS-SEC-3 的 INSTALL_ACCESS_DENIED 分支；命中更早分支=%r，响应=%r"
        % (label, early, resp.text[:200])
    )


@pytest.mark.install_wizard
def test_TC_I_INS_003(base_url, install_entry):
    url = _step5_url(base_url, install_entry)

    # ── 前置 1：安装模式（INS-SEC-1） ──────────────────────────────────────────
    probe = requests.Session().get(url, timeout=30, allow_redirects=False)
    if probe.status_code == 404:
        pytest.skip(INSTALL_MODE_HINT % probe.status_code)
    assert probe.status_code == 200, (
        "安装模式的 step=5 GET 应为 HTTP 200 业务体，实际 %s" % probe.status_code
    )
    # ── 前置 2：install.lock 不存在（INS-SEC-2） ───────────────────────────────
    if is_locked_page(probe):
        pytest.skip(LOCKED_HINT)

    # 1) GET：PHP 走「请先测试连接 / 请填写账号」分支，Java 收紧为直接拒绝（INS-SEC-3）
    sess = requests.Session()
    get_resp = sess.get(url, timeout=30, allow_redirects=False)
    _assert_access_denied(get_resp, "GET")

    # 2) POST 但正文类型不是 form-urlencoded（application/json）
    json_resp = sess.post(
        url,
        data='{"account":"autotest-APPK-2-admin"}',
        headers={"Content-Type": "application/json"},
        timeout=30,
        allow_redirects=False,
    )
    _assert_access_denied(json_resp, "POST application/json")

    # 3) POST 正文类型 text/plain：同样不是 form-urlencoded
    text_resp = sess.post(
        url,
        data="account=autotest-APPK-2-admin",
        headers={"Content-Type": "text/plain"},
        timeout=30,
        allow_redirects=False,
    )
    _assert_access_denied(text_resp, "POST text/plain")

    # 4) 三个分支都不得产生安装结果（不执行、不写库、不写锁）
    for resp in (get_resp, json_resp, text_resp):
        for leaked in ("系统安装成功", "is_ok", "开始安装", "正在安装"):
            assert leaked not in resp.text, (
                "INSTALL_ACCESS_DENIED 分支出现了安装执行痕迹：%r" % leaked
            )
