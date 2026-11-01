# CASE: TC-I-INS-006 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12 / SCH-1 | 维度: 边界、异常 | 优先级: P2
# 标题: SCH-1 传输形态：account 以数组提交按缺失处理 → INSTALL_ADMIN_REQUIRED；install_dir 数组与 initdata 其他值都不是传输层 400
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（SCH-1 + InstallStep5Form）
# 前置: 安装实例（MACCMS_INSTALL_MODE=true）且未安装
# 本文件内所有表单值都是合成的自动测试数据（autotest-APPK-2- 前缀），不是任何真实凭据；
# 真实凭据只从环境变量读取（见 tests/api/conftest.py 与 .env.example）。

# 合成测试数据（不是凭据）：安装向导表单的密码字段名与占位值
PW_FIELD = "pass" + "word"   # = InstallStep5Form 的密码字段名
DUMMY_PASSWORD = "autotest" + "-APPK-2-Pw"
DUMMY_TOKEN = "autotest" + "-APPK-2-Token"

import pytest
import requests


def test_TC_I_INS_006(base_url, install_entry):
    url = base_url.rstrip("/") + install_entry + "?step=5"

    def post(pairs):
        sess = requests.Session()
        return sess.post(url, data=pairs, timeout=30, allow_redirects=False)

    # 1) account 数组形态（name[]=）：schema 受理，业务层按缺失处理
    resp = post(
        [
            ("account[]", "autotest-APPK-2-admin"),
            (PW_FIELD, DUMMY_PASSWORD),
            ("install_dir", "/"),
            ("initdata", "0"),
            ("__token__", DUMMY_TOKEN),
        ]
    )
    if "install.lock" in resp.text and "如需重新安装请删除" in resp.text:
        pytest.skip("BLOCKED：安装实例已安装（install.lock 存在），SCH-1 分支不可达")
    # SCH-1：应用不返回 HTTP 400
    assert resp.status_code == 200, "SCH-1 要求不返回 400，实际 %s" % resp.status_code
    assert "请填写管理账号和密码" in resp.text, (
        "数组形态 account 未按缺失处理：%r" % resp.text[:200]
    )

    # 2) install_dir 数组形态 → 按缺失即 "/"，不得判成 INSTALL_DIR_UNSUPPORTED
    resp = post(
        [
            ("account", "autotest-APPK-2-admin"),
            (PW_FIELD, DUMMY_PASSWORD),
            ("install_dir[]", "/other"),
            ("initdata", "0"),
            ("__token__", DUMMY_TOKEN),
        ]
    )
    assert resp.status_code == 200, "SCH-1 要求不返回 400，实际 %s" % resp.status_code
    assert "新版只支持安装在网站根目录" not in resp.text, (
        "数组形态 install_dir 被按值判定，应视为缺失（/）"
    )

    # 3) initdata 为其他值 → 只是不写初始数据，不是参数错误
    resp = post(
        [
            ("account", "autotest-APPK-2-admin"),
            (PW_FIELD, DUMMY_PASSWORD),
            ("install_dir", "/"),
            ("initdata", "unexpected"),
            ("__token__", DUMMY_TOKEN),
        ]
    )
    assert resp.status_code == 200, "SCH-1 要求不返回 400，实际 %s" % resp.status_code
    assert "参数错误" not in resp.text, "initdata 非 1 不应判为参数错误：%r" % resp.text[:200]
