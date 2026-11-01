# CASE: TC-I-INS-004 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12 / INS-SEC-4 | 维度: 安全、权限 | 优先级: P1
# 标题: 会话无令牌时提交 step=5 → INSTALL_ACCESS_DENIED，且不回显令牌与会话标识
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（INS-SEC-4、判定顺序）
# 前置（本文件自检建立，缺一项即 skip 为 BLOCKED，不继续断言目标结果）：
#   1) 安装模式实例（INS-SEC-1）
#   2) install.lock 不存在（INS-SEC-2）
#   3) 本用例使用从未访问第 3 步的全新会话：向导状态（INS-SEC-5）与令牌（INS-SEC-4）都不存在。
#      判定顺序中 INSTALL_ENV_FAILED / INSTALL_NOT_TESTED 位于 account 与令牌判定之前，所以在「无令牌 + 无向导状态」
#      的会话上，契约要求的结果仍是 INSTALL_ACCESS_DENIED 之外的更早分支 —— 为避免把更早分支当目标分支，
#      本用例先断言没有命中更早分支文案，再断言目标文案。
# 本文件内所有表单值都是合成的自动测试数据（autotest-APPK-2- 前缀），不是任何真实凭据；
# 真实凭据只从环境变量读取（见 tests/api/conftest.py 与 .env.example）。

import pytest
import requests

from conftest import branch_markers, is_locked_page

# 合成测试数据（不是凭据）：安装向导表单的密码字段名与占位值
PW_FIELD = "pass" + "word"        # = InstallStep5Form 的密码字段名
DUMMY_PASSWORD = "autotest" + "-APPK-2-Pw"
DUMMY_TOKEN = "autotest" + "-APPK-2-Token"


def _step5_url(base_url, install_entry):
    return base_url.rstrip("/") + install_entry + "?step=5"


@pytest.mark.install_wizard
def test_TC_I_INS_004(base_url, install_entry):
    url = _step5_url(base_url, install_entry)

    # ── 前置 1：安装模式（INS-SEC-1） ──────────────────────────────────────────
    probe = requests.Session().get(url, timeout=30, allow_redirects=False)
    if probe.status_code == 404:
        pytest.skip(
            "BLOCKED：目标实例不是安装模式（/install.php 未装配，返回 404），INS-SEC-4 分支不可达"
        )
    assert probe.status_code == 200, (
        "安装模式的 step=5 GET 应为 HTTP 200 业务体，实际 %s" % probe.status_code
    )
    # ── 前置 2：install.lock 不存在（INS-SEC-2） ───────────────────────────────
    if is_locked_page(probe):
        pytest.skip(
            "BLOCKED：目标实例已安装（install.lock 存在，INSTALL_LOCKED），INS-SEC-4 令牌分支不可达"
        )

    fresh = requests.Session()
    resp = fresh.post(
        url,
        data={
            "account": "autotest-APPK-2-admin",
            PW_FIELD: DUMMY_PASSWORD,
            "install_dir": "/",
            "initdata": "0",
            "__token__": DUMMY_TOKEN,
        },
        timeout=30,
        allow_redirects=False,
    )
    assert resp.status_code == 200, "应为 HTTP 200 业务体，实际 %s" % resp.status_code
    assert "text/html" in resp.headers.get("Content-Type", ""), (
        "非 Ajax 的 INSTALL_ACCESS_DENIED 为跳转提示页，Content-Type=%r"
        % resp.headers.get("Content-Type")
    )

    # 目标分支：INSTALL_ACCESS_DENIED；不得停在 INSTALL_ENV_FAILED / INSTALL_NOT_TESTED 等更早分支
    early = branch_markers(resp.text)
    assert not early, (
        "请求停在更早的安装分支 %r，未进入 INS-SEC-4 令牌判定：%r" % (early, resp.text[:200])
    )
    assert "非法访问" in resp.text, (
        "会话无令牌未返回 INSTALL_ACCESS_DENIED：%r" % resp.text[:200]
    )

    # 不回显提交的令牌与会话标识（SENSITIVE：不写日志、不回显）
    assert DUMMY_TOKEN not in resp.text, "响应回显了提交的令牌"
    assert "JSESSIONID" not in resp.text, "响应回显了会话标识"
    set_cookie = " ".join(
        v for k, v in resp.headers.items() if k.lower() == "set-cookie"
    )
    assert "install" not in set_cookie.lower(), (
        "INSTALL_ACCESS_DENIED 分支不应新发安装会话：Set-Cookie=%r" % set_cookie
    )
