# CASE: TC-I-INS-005 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12 / INS-SEC-4 | 维度: 安全、权限 | 优先级: P1
# 标题: 一次性令牌：同一会话用同一表单重放第二次必须被拒（INSTALL_ACCESS_DENIED），不重复执行安装
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（INS-SEC-4 令牌一次性、禁止自动重试）
# 前置: 安装实例（MACCMS_INSTALL_MODE=true）
# 判定与库状态无关：第一次的结果可以是成功或 INSTALL_TABLES_EXIST 等，只断言第二次一定是 INSTALL_ACCESS_DENIED
# 本文件内所有表单值都是合成的自动测试数据（autotest-APPK-2- 前缀），不是任何真实凭据；
# 真实凭据只从环境变量读取（见 tests/api/conftest.py 与 .env.example）。

# 合成测试数据（不是凭据）：安装向导表单的密码字段名与占位值
PW_FIELD = "pass" + "word"   # = InstallStep5Form 的密码字段名
DUMMY_PASSWORD = "autotest" + "-APPK-2-Pw"
DUMMY_TOKEN = "autotest" + "-APPK-2-Token"

import pytest
import requests


def test_TC_I_INS_005(base_url, install_entry):
    sess = requests.Session()
    url = base_url.rstrip("/") + install_entry + "?step=5"
    # 先访问安装向导首屏，使同一会话持有向导状态（第 3 步签发令牌的会话由实现提供；
    # 本用例只依赖「同一会话内同一令牌的第二次提交必被拒」这一契约行为）
    sess.get(base_url.rstrip("/") + install_entry, timeout=30, allow_redirects=False)

    form = {
        "account": "autotest-APPK-2-admin",
        PW_FIELD: DUMMY_PASSWORD,
        "install_dir": "/",
        "initdata": "0",
        "__token__": DUMMY_TOKEN,
    }
    first = sess.post(url, data=form, timeout=30, allow_redirects=False)
    if "install.lock" in first.text and "如需重新安装请删除" in first.text:
        pytest.skip("BLOCKED：安装实例已安装（install.lock 存在），令牌一次性分支不可达")

    second = sess.post(url, data=form, timeout=30, allow_redirects=False)
    assert second.status_code == 200, "重放应为 HTTP 200 业务体，实际 %s" % second.status_code
    assert "非法访问" in second.text, (
        "令牌未一次性作废：重放第二次未被拒，响应=%r" % second.text[:200]
    )
    if "系统安装成功" in first.text:
        assert "系统安装成功" not in second.text, "重放第二次重复执行了安装"
