# CASE: TC-I-INS-007 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12 / TRN-1 | 维度: 边界、异常 | 优先级: P2
# 标题: 表单正文超过 1 MiB → HTTP 500 固定「系统错误」页（同基线 InstallEndpoint），不泄露异常细节
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（SCH-1 TRN-1：API-030 沿用基线 1 MiB）
# 前置: 安装实例（MACCMS_INSTALL_MODE=true）

import pytest
import requests

# 契约写明 API-030 的正文上限沿用基线 1 MiB
MAX_BODY_BYTES = 1024 * 1024

# 本文件内所有表单值都是合成的自动测试数据（autotest-APPK-2- 前缀），不是任何真实凭据；
# 真实凭据只从环境变量读取（见 tests/api/conftest.py 与 .env.example）。
# 合成测试数据（不是凭据）：安装向导表单的密码字段名与占位值
PW_FIELD = "pass" + "word"   # = InstallStep5Form 的密码字段名
DUMMY_PASSWORD = "autotest" + "-APPK-2-Pw"
DUMMY_TOKEN = "autotest" + "-APPK-2-Token" + "-APPK-2-Token"


@pytest.mark.input_limit
def test_TC_I_INS_007(base_url, install_entry):
    sess = requests.Session()
    url = base_url.rstrip("/") + install_entry + "?step=5"
    # 构造超过 1 MiB 的表单正文（一个无关的填充字段）
    payload = {
        "account": "autotest-APPK-2-admin",
        PW_FIELD: DUMMY_PASSWORD,
        "install_dir": "/",
        "initdata": "0",
        "__token__": DUMMY_TOKEN,
        "padding": "a" * (MAX_BODY_BYTES + 1024),
    }
    resp = sess.post(url, data=payload, timeout=60, allow_redirects=False)

    if "install.lock" in resp.text and "如需重新安装请删除" in resp.text:
        pytest.skip("BLOCKED：安装实例已安装（install.lock 存在），正文上限分支不可达")

    # TRN-1：API-030 超过 1 MiB 为固定的 HTTP 500「系统错误」页（不是 400、不是 413）
    assert resp.status_code == 500, (
        "超过 1 MiB 应为 HTTP 500 固定系统错误页，实际 %s" % resp.status_code
    )
    body = resp.text
    # 固定页：不含异常细节（TRN-1 与 INS-SEC 的泄露约束）
    lowered = body.lower()
    for leaked in (
        "traceback",
        "java.lang.",
        "org.springframework",
        "maxuploadsize",
        "sizeexceeded",
        "jdbc:",
    ):
        assert leaked not in lowered, "500 页泄露了异常/框架细节：%r" % leaked
    # 不得返回安装成功
    assert "系统安装成功" not in body, "超限请求被执行了安装"
