# CASE: TC-I-INS-003 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12（③）/ INS-SEC-3 | 维度: 异常、安全 | 优先级: P1
# 标题: step=5 只接受 POST 表单；GET 与其他正文类型的 POST 返回 INSTALL_ACCESS_DENIED，不执行
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（INS-SEC-3）
# 前置: 安装实例（MACCMS_INSTALL_MODE=true）
# 本文件内所有表单值都是合成的自动测试数据（autotest-APPK-2- 前缀），不是任何真实凭据；
# 真实凭据只从环境变量读取（见 tests/api/conftest.py 与 .env.example）。


def test_TC_I_INS_003(base_url, install_entry):
    import requests

    sess = requests.Session()
    url = base_url.rstrip("/") + install_entry + "?step=5"

    # 1) GET：PHP 走「请先测试连接 / 请填写账号」分支，Java 收紧为直接拒绝（INS-SEC-3）
    get_resp = sess.get(url, timeout=30, allow_redirects=False)
    assert get_resp.status_code == 200, "GET 应为 HTTP 200 业务体，实际 %s" % get_resp.status_code
    assert "非法访问" in get_resp.text, (
        "GET 未返回 INSTALL_ACCESS_DENIED 文案：%r" % get_resp.text[:200]
    )

    # 2) POST 但正文类型不是 form-urlencoded（application/json）
    json_resp = sess.post(
        url,
        data='{"account":"autotest-APPK-2-admin"}',
        headers={"Content-Type": "application/json"},
        timeout=30,
        allow_redirects=False,
    )
    assert json_resp.status_code == 200, (
        "非表单 POST 应为 HTTP 200 业务体，实际 %s" % json_resp.status_code
    )
    assert "非法访问" in json_resp.text, (
        "非表单 POST 未返回 INSTALL_ACCESS_DENIED：%r" % json_resp.text[:200]
    )
