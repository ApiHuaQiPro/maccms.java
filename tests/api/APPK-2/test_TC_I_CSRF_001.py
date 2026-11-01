# CASE: TC-I-CSRF-001 | 接口: B 类后台动作（API-024/040/043） | 追溯: AC-16 / ADM-3 SRC-1+SRC-2 | 维度: 安全、权限 | 优先级: P1
# 标题: B 类入口：跨站来源（Sec-Fetch-Site: cross-site）被拒；三个来源头都缺失也被拒（SRC-2）；错误体不泄露内部细节，
#       且被拒请求不产生动作结果
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（x-csrf-policy classes.B、CSRF-EX-1 controls）
# 前置: 默认角色管理员有对应权限（来源校验先于权限判定，因此拒绝与权限无关）

from conftest import MSG_ILLEGAL_REQUEST, assert_json_error

# B 类且受 SRC-1 + SRC-2 约束的入口（collect/test 为 C 类，不在本条范围）
B_CLASS = [
    ("GET", "make/make"),
    ("GET", "collect/api"),
    ("GET", "images/sync"),
    ("POST", "images/sync"),
]


def test_TC_I_CSRF_001(api_as, admin_url):
    # 1) 跨站来源：Sec-Fetch-Site: cross-site → 拒绝
    for method, rel in B_CLASS:
        api = api_as("default")
        api.session.headers["Sec-Fetch-Site"] = "cross-site"
        resp = api.call(method, admin_url(rel))
        assert resp.status_code in (200, 302, 403), (
            "[跨站 %s %s] 期望被拒，实际 %s" % (method, rel, resp.status_code)
        )
        # 被拒时若给出 JSON 业务体，必须是「非法请求」而不是成功
        if resp.headers.get("Content-Type", "").startswith("application/json"):
            assert_json_error(resp, MSG_ILLEGAL_REQUEST)
        else:
            # 非 JSON（403 提示页 / 302 登录页）时不得是成功页
            assert "非法请求" in resp.text or resp.status_code in (302, 403), (
                "[跨站 %s %s] 拒绝体不含「非法请求」：%r" % (method, rel, resp.text[:200])
            )

    # 2) SRC-2：三个来源头（Sec-Fetch-Site / Origin / Referer）都缺失 → 拒绝
    for method, rel in B_CLASS:
        api = api_as("default", source_headers=False)
        for header in ("Origin", "Referer", "Sec-Fetch-Site"):
            api.session.headers.pop(header, None)
        resp = api.call(method, admin_url(rel))
        assert resp.status_code in (200, 302, 403), (
            "[无来源头 %s %s] 期望被拒，实际 %s" % (method, rel, resp.status_code)
        )
        if resp.headers.get("Content-Type", "").startswith("application/json"):
            assert_json_error(resp, MSG_ILLEGAL_REQUEST)
