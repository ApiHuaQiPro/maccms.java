# CASE: TC-I-AUTH-001 | 接口: API-040 / API-041 / API-043 / API-024 | 追溯: AC-16 / ADM-1、ADM-4 | 维度: 权限、安全 | 优先级: P1
# 标题: 每个受保护入口的三档鉴权：未登录 → 302 登录页；无对应权限 → HTTP 200「您没有权限访问此页面」；有权限 → 不返回无权限体
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（ADM-1、ADM-4、x-permission）
# 前置: APP_API_TOKEN_READONLY 为无 collect/images/make 权限的管理员会话

import pytest

from conftest import assert_json_error

# (方法, 后台相对路径, 该路径的 x-permission)
ENTRIES = [
    ("GET", "collect/api", "collect/api"),
    ("POST", "collect/test", "collect/test"),
    ("GET", "images/sync", "images/sync"),
    ("POST", "images/sync", "images/sync"),
    ("GET", "make/make", "make/make"),
    ("GET", "make/index", "make/index"),
]


def test_TC_I_AUTH_001(api_as, admin_url):
    for method, rel, perm in ENTRIES:
        # 1) 未登录 → 302 + Location 指向登录页，空正文
        resp = api_as(None).call(method, admin_url(rel))
        assert resp.status_code == 302, (
            "[未登录 %s %s] 期望 302，实际 %s" % (method, rel, resp.status_code)
        )
        assert "login" in resp.headers.get("Location", "").lower(), (
            "[未登录 %s %s] Location 应指向登录页，实际 %r"
            % (method, rel, resp.headers.get("Location"))
        )
        assert resp.text.strip() == "", "[未登录 %s %s] 302 应为空正文" % (method, rel)

        # 2) 有会话但无该 x-permission → HTTP 200 PHP 失败体
        resp = api_as("readonly").call(method, admin_url(rel))
        assert resp.status_code == 200, (
            "[无权限 %s %s] 期望 HTTP 200 业务体，实际 %s" % (method, rel, resp.status_code)
        )
        assert_json_error(resp, "您没有权限访问此页面")

        # 3) 有权限 → 不得返回无权限体
        resp = api_as("default").call(method, admin_url(rel))
        body_text = resp.text
        assert "您没有权限访问此页面" not in body_text, (
            "[有权限 %s %s] 出现了无权限体，x-permission=%s" % (method, rel, perm)
        )
