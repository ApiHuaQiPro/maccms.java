# CASE: TC-I-INS-001 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12（①）/ INS-SEC-1 | 维度: 正常 | 优先级: P1
# 标题: 非安装模式下 /install.php 不装配，step=5 的 POST 按前台 API-005 返回 404，不执行任何安装逻辑
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（INS-SEC-1）
# 前置: 测试环境以正常模式（MACCMS_INSTALL_MODE 未开）启动；application/data/install/install.lock 是否存在都可执行
# 本文件内所有表单值都是合成测试数据，不是任何真实凭据（见 install_support.py：
# account 必须符合契约 ^[A-Za-z0-9]+$，密码 6～20 字符，否则请求在任何判定之前就被形态规则拦掉）。
# 本用例只要求正常模式下 404，请求体是否合规不影响结论；但仍按契约给合规合成值。

import os
import sys

# 同目录共享模块 install_support.py（只做前置与断言工具，不执行用例）。
# pytest 以 --import-mode=importlib 运行，本目录不在 sys.path 上，这里显式加入。
_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

from install_support import ACCOUNT, PASSWORD, PW_FIELD, TOKEN_FIELD

CASE = "INS-001"


def test_TC_I_INS_001(api_as, install_entry):
    api = api_as("default", ajax=False)
    resp = api.call(
        "POST",
        install_entry + "?step=5",
        data={
            "account": ACCOUNT,
            PW_FIELD: PASSWORD,
            "install_dir": "/",
            "initdata": "0",
            TOKEN_FIELD: "unused" + "0" * 24,   # 合成占位值，正常模式不装配该入口
        },
    )
    # INS-SEC-1：正常模式不装配该入口 → 前台 404 页（API-005），不是安装向导的 INSTALL_* 业务体
    assert resp.status_code == 404, "正常模式下 /install.php 应 404，实际 %s" % resp.status_code
    body = resp.text
    # 不得出现任何安装阶段的成功或写库提示
    for leaked in ("系统安装成功", "is_ok", "install.lock"):
        assert leaked not in body, "正常模式响应出现安装逻辑痕迹：%r" % leaked
    # 不得泄露部署配置与连接信息
    for leaked in ("MACCMS_DEPLOY_CONFIG", "jdbc:", "password"):
        assert leaked not in body.lower(), "响应疑似泄露配置项：%r" % leaked
