# CASE: TC-I-INS-002 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12（②）/ INS-SEC-2 | 维度: 安全、回归 | 优先级: P1
# 标题: install.lock 存在时 step=5 只返回 INSTALL_LOCKED 固定文本，不读参数、不建会话、不进入写库阶段
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（INS-SEC-2）
# 前置: 安装实例以 MACCMS_INSTALL_MODE=true 启动，且 application/data/install/install.lock 已存在
# 本文件内所有表单值都是合成测试数据，不是任何真实凭据（见 install_support.py）。
# INSTALL_LOCKED 判定（INS-SEC-2）位于方法、环境、account、令牌之前，所以本用例在全新会话上执行即可，
# 提交值只需符合 InstallStep5Form 的类型约束（这里用符合 ^[A-Za-z0-9]+$ 的合成账号）。

import os
import sys

# 同目录共享模块 install_support.py（只做前置与断言工具，不执行用例）。
# pytest 以 --import-mode=importlib 运行，本目录不在 sys.path 上，这里显式加入。
_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

from install_support import (
    ACCOUNT,
    PASSWORD,
    PW_FIELD,
    TOKEN_FIELD,
    has_locked_text,
    step5_url,
)

CASE = "INS-002"


def test_TC_I_INS_002(base_url, install_entry):
    import requests

    sess = requests.Session()
    resp = sess.post(
        step5_url(base_url, install_entry),
        data={
            "account": ACCOUNT,
            PW_FIELD: PASSWORD,
            "install_dir": "/",
            "initdata": "1",
            TOKEN_FIELD: "unused" + "0" * 24,   # 合成占位值，本分支不读令牌
        },
        timeout=30,
        allow_redirects=False,
    )
    assert resp.status_code == 200, "INSTALL_LOCKED 应为 HTTP 200，实际 %s" % resp.status_code
    assert "text/html" in resp.headers.get("Content-Type", ""), (
        "INSTALL_LOCKED 为纯文本页，Content-Type=%r" % resp.headers.get("Content-Type")
    )
    body = resp.text
    # INS-SEC-2：固定纯文本页原文（wire_msg 含 install.lock 路径与「如需重新安装请删除」）
    assert has_locked_text(resp.text), "INSTALL_LOCKED 固定文案不符：%r" % body[:200]
    # 未进入写库阶段
    for leaked in ("系统安装成功", "管理员账号创建失败", "导入初始化数据SQL失败"):
        assert leaked not in body, "已安装时出现了写库阶段提示：%r" % leaked
    # 不建会话（INS-SEC-2：不读参数、不建会话）
    assert "JSESSIONID" not in resp.headers.get("Set-Cookie", ""), (
        "INSTALL_LOCKED 分支不应创建安装会话"
    )
