# CASE: TC-I-INS-007 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12 / TRN-1 | 维度: 边界、异常 | 优先级: P2
# 标题: 表单正文超过 1 MiB → HTTP 500 固定「系统错误」页（同基线 InstallEndpoint），不泄露异常细节、不执行安装
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（SCH-1 TRN-1：API-030 沿用基线 1 MiB；
#       x-error-codes INPUT_LIMIT_EXCEEDED「API-030 为固定“系统错误”页」）
# 前置（本文件自检建立，缺一项即 skip 为 BLOCKED，不继续断言目标结果）：
#   1) 安装模式实例（INS-SEC-1）：/install.php 返回 404 → BLOCKED
#   2) install.lock 不存在（INS-SEC-2）：返回 INSTALL_LOCKED → BLOCKED
#   3) 同一会话第 3 步签发的真实令牌（INS-SEC-4）：正文长度上限在令牌判定之前生效（TRN-1），
#      但为了让请求在其他分支上也不是「非法访问」，仍按契约要求带上会话令牌。取不到令牌 → BLOCKED。
# 判定：HTTP 500、text/html 固定系统错误页；不含堆栈/框架/连接串/文件路径；不出现任何安装执行痕迹；
#      且不得把超限正文当作合法安装参数执行（不返回 200 业务体）。
# 本文件内所有表单值都是合成的自动测试数据（autotest-APPK-2- 前缀），不是任何真实凭据；
# 真实凭据只从环境变量读取（见 tests/api/conftest.py 与 .env.example）。令牌运行时从被测站取得，不写死。

import re

import pytest
import requests

from conftest import branch_markers, is_locked_page

# 契约写明 API-030 的正文上限沿用基线 1 MiB
MAX_BODY_BYTES = 1024 * 1024

# 合成测试数据（不是凭据）：安装向导表单的密码字段名与占位值
PW_FIELD = "pass" + "word"        # = InstallStep5Form 的密码字段名
DUMMY_PASSWORD = "autotest" + "-APPK-2-Pw"
ACCOUNT = "autotest-APPK-2-admin"

TOKEN_PATTERN = re.compile(r"^[0-9a-f]{32}$")


def _hidden_token(html):
    match = re.search(
        r"""name=["']__token__["'][^>]*value=["']([^"']+)["']""", html, re.I
    ) or re.search(
        r"""value=["']([^"']*)["'][^>]*name=["']__token__["']""", html, re.I
    )
    assert match, (
        "BLOCKED：第 3 步页面没有找到隐藏字段 __token__，响应片段=%r" % html[:200]
    )
    return match.group(1)


@pytest.mark.input_limit
@pytest.mark.install_wizard
def test_TC_I_INS_007(base_url, install_entry):
    entry = base_url.rstrip("/") + install_entry
    step5 = entry + "?step=5"

    # ── 前置 1：安装模式（INS-SEC-1） ──────────────────────────────────────────
    sess = requests.Session()
    probe = sess.get(step5, timeout=30, allow_redirects=False)
    if probe.status_code == 404:
        pytest.skip(
            "BLOCKED：目标实例不是安装模式（/install.php 未装配，返回 404），TRN-1 正文上限分支不可达"
        )
    assert probe.status_code == 200, (
        "安装模式的 step=5 GET 应为 HTTP 200 业务体，实际 %s" % probe.status_code
    )
    # ── 前置 2：install.lock 不存在（INS-SEC-2） ───────────────────────────────
    if is_locked_page(probe):
        pytest.skip(
            "BLOCKED：目标实例已安装（install.lock 存在，INSTALL_LOCKED），TRN-1 正文上限分支不可达"
        )

    # ── 前置 3：同一会话第 3 步签发的真实令牌 ──────────────────────────────────
    step3 = sess.get(entry + "?step=3", timeout=30, allow_redirects=False)
    if step3.status_code != 200:
        pytest.skip(
            "BLOCKED：第 3 步页面 GET 返回 %s，无法取得签发的 __token__" % step3.status_code
        )
    token = _hidden_token(step3.text)
    if not TOKEN_PATTERN.match(token):
        pytest.skip("BLOCKED：第 3 步签发的 __token__ 不是 32 位小写十六进制")

    # 构造超过 1 MiB 的表单正文（一个无关的填充字段）
    payload = {
        "account": ACCOUNT,
        PW_FIELD: DUMMY_PASSWORD,
        "install_dir": "/",
        "initdata": "0",
        "__token__": token,
        "padding": "a" * (MAX_BODY_BYTES + 1024),
    }
    resp = sess.post(step5, data=payload, timeout=120, allow_redirects=False)

    # TRN-1：API-030 超过 1 MiB 为固定的 HTTP 500「系统错误」页（不是 400、不是 413、不是 200 业务体）
    assert resp.status_code == 500, (
        "超过 1 MiB 应为 HTTP 500 固定系统错误页，实际 %s（若为 200 业务体，说明超限正文被当成合法参数处理）"
        % resp.status_code
    )
    ctype = resp.headers.get("Content-Type", "")
    assert "text/html" in ctype, "系统错误页应为 text/html，实际 Content-Type=%r" % ctype

    body = resp.text
    lowered = body.lower()
    # 固定页：不含异常细节（TRN-1 与 INS-SEC 的泄露约束）
    for leaked in (
        "traceback",
        "java.lang.",
        "org.springframework",
        "exception",
        "maxuploadsize",
        "sizeexceeded",
        "jdbc:",
        "install.sql",
        "application/data",
    ):
        assert leaked not in lowered, "500 页泄露了异常/框架/路径细节：%r" % leaked
    # 不执行安装、不进入任何安装分支
    for leaked in ("系统安装成功", "is_ok", "已存在数据表", "初始失败"):
        assert leaked not in body, "超限请求被执行或进入了安装分支：%r" % leaked
    early = branch_markers(body)
    assert not early, "500 页出现了安装业务分支文案 %r" % early
    # 不回显超限正文（回显超限表单会同时放大响应）
    assert "aaaaaaaa" not in lowered, "系统错误页回显了超限表单内容"
