# CASE: TC-I-INS-007 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12 / TRN-1 | 维度: 边界、异常 | 优先级: P2
# 标题: 表单正文超过 1 MiB → HTTP 500 固定「系统错误」页（同基线 InstallEndpoint），不泄露异常细节、不执行安装
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（SCH-1 TRN-1：API-030 沿用基线 1 MiB；
#       x-error-codes INPUT_LIMIT_EXCEEDED「API-030 为固定“系统错误”页」）
# 前置（install_support 建立并核验，缺一项即 BLOCKED，不继续断言目标结果）：
#   1) 安装模式实例（INS-SEC-1）：/install.php 返回 404 → BLOCKED
#   2) install.lock 不存在（INS-SEC-2）：INSTALL_LOCKED 固定文本 → BLOCKED
#   3) 同一会话第 3 步签发的真实令牌（INS-SEC-4）：正文长度上限在业务判定之前生效（TRN-1），
#      这里仍按契约带上会话令牌，保证「不是令牌分支造成的 500」；取不到令牌 → BLOCKED。
# 判定：HTTP 500、text/html 固定系统错误页；不含堆栈/框架/连接串/文件路径；不出现任何安装执行痕迹；
#      且不得把超限正文当作合法安装参数执行（不返回 200 业务体）。
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
    PASSWORD,
    PW_FIELD,
    TOKEN_FIELD,
    assert_not_echoed,
    before_token_hits,
    after_token_hits,
    require_install_mode,
    require_not_locked,
    step5_url,
    wizard_session,
)

CASE = "INS-007"
# 契约写明 API-030 的正文上限沿用基线 1 MiB
MAX_BODY_BYTES = 1024 * 1024


@pytest.mark.input_limit
@pytest.mark.install_wizard
def test_TC_I_INS_007(base_url, install_entry):
    step5 = step5_url(base_url, install_entry)

    # ── 前置：安装模式（INS-SEC-1）+ install.lock 不存在（INS-SEC-2） ─────────────
    probe = require_install_mode(base_url, install_entry)
    require_not_locked(probe, CASE)
    # ── 前置：同一会话第 3 步签发的真实令牌 ─────────────────────────────────────
    sess, token = wizard_session(base_url, install_entry)

    # 构造超过 1 MiB 的表单正文（一个无关的填充字段）
    payload = {
        "account": ACCOUNT,
        PW_FIELD: PASSWORD,
        "install_dir": "/",
        "initdata": "0",
        TOKEN_FIELD: token,
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
    assert_not_echoed(resp, "%s 超限 500 页" % CASE)
    # 不执行安装、不进入任何安装分支（令牌前后两组固定文案都不许出现）
    for leaked in ("系统安装成功", "is_ok", "管理员账号创建失败", "初始失败"):
        assert leaked not in body, "超限请求被执行或进入了安装分支：%r" % leaked
    assert not before_token_hits(body), "500 页出现了令牌之前的安装分支文案 %r" % before_token_hits(body)
    assert not after_token_hits(body), "500 页出现了令牌之后的安装分支文案 %r" % after_token_hits(body)
    # 不回显超限正文（回显超限表单会同时放大响应）
    assert "aaaaaaaa" not in lowered, "系统错误页回显了超限表单内容"
    # 不回显令牌与会话标识
    assert token not in body, "系统错误页回显了会话令牌"
