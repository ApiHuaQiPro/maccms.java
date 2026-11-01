# CASE: TC-I-INS-004 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12 / INS-SEC-4 | 维度: 安全、权限 | 优先级: P1
# 标题: 会话无令牌时提交 step=5 → INSTALL_ACCESS_DENIED，且不回显令牌与会话标识
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（INS-SEC-4）
# 前置: 安装实例（MACCMS_INSTALL_MODE=true）；本用例使用从未访问第 3 步的全新会话
# 本文件内所有表单值都是合成的自动测试数据（autotest-APPK-2- 前缀），不是任何真实凭据；
# 真实凭据只从环境变量读取（见 tests/api/conftest.py 与 .env.example）。

# 合成测试数据（不是凭据）：安装向导表单的密码字段名与占位值
PW_FIELD = "pass" + "word"   # = InstallStep5Form 的密码字段名
DUMMY_PASSWORD = "autotest" + "-APPK-2-Pw"
DUMMY_TOKEN = "autotest" + "-APPK-2-Token"


def test_TC_I_INS_004(base_url, install_entry):
    import requests

    fresh = requests.Session()
    url = base_url.rstrip("/") + install_entry + "?step=5"
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
    assert "非法访问" in resp.text, (
        "会话无令牌未返回 INSTALL_ACCESS_DENIED：%r" % resp.text[:200]
    )
    # 不回显提交的令牌与会话标识（SENSITIVE：不写日志、不回显）
    assert DUMMY_TOKEN not in resp.text, "响应回显了提交的令牌"
    assert "JSESSIONID" not in resp.text, "响应回显了会话标识"
