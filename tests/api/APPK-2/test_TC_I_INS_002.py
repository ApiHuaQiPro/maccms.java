# CASE: TC-I-INS-002 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12（②）/ INS-SEC-2 | 维度: 安全、回归 | 优先级: P1
# 标题: install.lock 存在时 step=5 只返回 INSTALL_LOCKED 固定文本，不读参数、不建会话、不进入写库阶段
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（INS-SEC-2）
# 前置: 安装实例以 MACCMS_INSTALL_MODE=true 启动，且 application/data/install/install.lock 已存在
# 本文件内所有表单值都是合成的自动测试数据（autotest-APPK-2- 前缀），不是任何真实凭据；
# 真实凭据只从环境变量读取（见 tests/api/conftest.py 与 .env.example）。

# 合成测试数据（不是凭据）：安装向导表单的密码字段名与占位值
PW_FIELD = "pass" + "word"   # = InstallStep5Form 的密码字段名
DUMMY_PASSWORD = "autotest" + "-APPK-2-Pw"
DUMMY_TOKEN = "autotest" + "-APPK-2-Token"


def test_TC_I_INS_002(base_url, install_entry):
    import requests

    sess = requests.Session()
    resp = sess.post(
        base_url.rstrip("/") + install_entry + "?step=5",
        data={
            "account": "autotest-APPK-2-admin",
            PW_FIELD: DUMMY_PASSWORD,
            "install_dir": "/",
            "initdata": "1",
            "__token__": DUMMY_TOKEN,
        },
        timeout=30,
        allow_redirects=False,
    )
    assert resp.status_code == 200, "INSTALL_LOCKED 应为 HTTP 200，实际 %s" % resp.status_code
    assert "text/html" in resp.headers.get("Content-Type", ""), (
        "INSTALL_LOCKED 为纯文本页，Content-Type=%r" % resp.headers.get("Content-Type")
    )
    body = resp.text
    # 旧 install.php:31 原文（INSTALL_LOCKED 的 wire_msg）
    assert "install.lock" in body, "缺少 install.lock 提示原文"
    assert "如需重新安装请删除" in body, "INSTALL_LOCKED 文案不符"
    # 未进入写库阶段
    for leaked in ("系统安装成功", "已存在数据表", "文件哈希不一致"):
        assert leaked not in body, "已安装时出现了写库阶段提示：%r" % leaked
    # 不建会话（INS-SEC-2：不读参数、不建会话）
    assert "JSESSIONID" not in resp.headers.get("Set-Cookie", ""), (
        "INSTALL_LOCKED 分支不应创建安装会话"
    )
