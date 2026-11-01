# CASE: TC-I-MAKE-002 | 接口: GET|POST /make/{makeAction}（API-024） | 追溯: AC-8 / ADM-1、ADM-3、ADM-4 | 维度: 权限、安全 | 优先级: P1
# 标题: 未登录 → 302 到登录页；无 make/* 权限 → HTTP 200 无权限体；三个来源头都缺失 → 非法请求（SRC-2）
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（ADM-1、ADM-3 SRC-1/SRC-2、x-csrf-class B）
# 前置: APP_API_TOKEN_READONLY 为无 make/* 权限的管理员会话

from conftest import MSG_ILLEGAL_REQUEST, assert_json_error


def test_TC_I_MAKE_002(api_as, admin_url):
    path = admin_url("make/index")

    # 1) 未登录 → 302 + Location 指向登录页，空正文（ADM-1；Ajax 请求同样 302）
    resp = api_as(None).call("GET", path)
    assert resp.status_code == 302, "未登录应为 302，实际 %s" % resp.status_code
    location = resp.headers.get("Location", "")
    assert "login" in location.lower(), "Location 应指向登录页，实际 %r" % location
    assert resp.text.strip() == "", "302 应为空正文"

    # 2) 有会话但无 make/* 权限 → HTTP 200 PHP 失败体（ADMIN_PERMISSION_DENIED）
    resp = api_as("readonly").call("GET", path)
    assert_json_error(resp, "您没有权限访问此页面")

    # 3) B 类 SRC-2：三个来源头都缺失 → 非法请求，不执行
    resp = api_as("default", source_headers=False).call("GET", path)
    assert_json_error(resp, MSG_ILLEGAL_REQUEST)
