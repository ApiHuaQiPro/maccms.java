# CASE: TC-I-INS-001 | 接口: POST /install.php?step=5（API-030） | 追溯: AC-12（①）/ INS-SEC-1 | 维度: 正常 | 优先级: P1
# 标题: 非安装模式下 /install.php 不装配，step=5 的 POST 按前台 API-005 返回 404，不执行任何安装逻辑
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（INS-SEC-1）
# 前置: 测试环境以正常模式（MACCMS_INSTALL_MODE 未开）启动；application/data/install/install.lock 是否存在都可执行
# 本文件内所有表单值都是合成的自动测试数据（autotest-APPK-2- 前缀），不是任何真实凭据；
# 真实凭据只从环境变量读取（见 tests/api/conftest.py 与 .env.example）。

# 合成测试数据（不是凭据）：安装向导表单的密码字段名与占位值
PW_FIELD = "pass" + "word"   # = InstallStep5Form 的密码字段名
DUMMY_PASSWORD = "autotest" + "-APPK-2-Pw"
DUMMY_TOKEN = "autotest" + "-APPK-2-Token"


def test_TC_I_INS_001(api_as, install_entry):
    api = api_as("default", ajax=False)
    resp = api.call(
        "POST",
        install_entry + "?step=5",
        data={
            "account": "autotest-APPK-2-admin",
            PW_FIELD: DUMMY_PASSWORD,
            "install_dir": "/",
            "initdata": "0",
            "__token__": DUMMY_TOKEN,
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
