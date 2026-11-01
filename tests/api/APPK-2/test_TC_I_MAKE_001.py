# CASE: TC-I-MAKE-001 | 接口: GET|POST /make/{makeAction}（API-024） | 追溯: AC-7 / ADM-6 | 维度: 权限、异常 | 优先级: P1
# 标题: /make/{makeAction} 只受理 GET 与 POST；PUT/DELETE 返回 405 ADMIN_METHOD_NOT_ALLOWED 且带 Allow 头，不执行生成
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（ADM-6、x-method-policy）
# 前置: 默认角色管理员有 make/* 权限

from conftest import assert_no_internal_leak


def test_TC_I_MAKE_001(api_as, admin_url):
    api = api_as("default")
    path = admin_url("make/index")

    # 1) PUT：未列出的方法 → 405，Allow 列出受理的方法
    resp = api.call("PUT", path)
    assert resp.status_code == 405, "PUT 应为 405，实际 %s" % resp.status_code
    allow = resp.headers.get("Allow", "")
    assert "GET" in allow.upper() and "POST" in allow.upper(), (
        "Allow 头应列出 GET, POST，实际 %r" % allow
    )
    assert_no_internal_leak(resp.text)

    # 2) DELETE：同上
    resp = api.call("DELETE", path)
    assert resp.status_code == 405, "DELETE 应为 405，实际 %s" % resp.status_code
    assert "GET" in resp.headers.get("Allow", "").upper(), (
        "Allow 头缺失 GET：%r" % resp.headers.get("Allow")
    )

    # 3) OPTIONS 也不在本路径的方法表内（契约只列 GET/POST）
    resp = api.call("OPTIONS", path)
    assert resp.status_code == 405, "OPTIONS 应为 405，实际 %s" % resp.status_code
