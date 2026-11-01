# CASE: TC-I-METHOD-001 | 接口: 后台采集/图片路径（API-040/041/042/043、API-014） | 追溯: 无 AC-（ADM-6；T1 §2.14/§4 口径，不计入 AC 分母） | 维度: 权限、异常 | 优先级: P1
# 标题: x-method-policy 逐行核对：未列出的方法 → 405 + Allow 头；GET-only 的路径（collect/api、collect/timing、database/import）收到 POST → 405
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（x-method-policy rows）
# 前置: 默认角色管理员有对应权限（权限判定在方法判定之后，所以 405 与权限无关）
# 说明: 405 判定在 ADM-6 先于令牌校验，因此这些断言不依赖令牌有效性；仍用已登录会话，避免 302 干扰

from conftest import assert_no_internal_leak

# (后台相对路径, 不受理的方法, 期望 Allow 必须包含的方法)
ROWS = [
    ("collect/api", "POST", ["GET"]),
    ("collect/api", "PUT", ["GET"]),
    ("collect/test", "PUT", ["GET", "POST"]),
    ("collect/test", "DELETE", ["GET", "POST"]),
    ("collect/timing", "POST", ["GET"]),
    ("images/sync", "PUT", ["GET", "POST"]),
    ("images/sync", "DELETE", ["GET", "POST"]),
    ("database/import", "GET", ["POST"]),
    ("database/recover", "GET", ["POST"]),
    ("database/acknowledge", "GET", ["POST"]),
    ("template/wizard", "POST", ["GET"]),
    ("template/ads", "POST", ["GET"]),
]


def test_TC_I_METHOD_001(api_as, admin_url):
    api = api_as("default")
    for rel, method, must_allow in ROWS:
        resp = api.call(method, admin_url(rel))
        assert resp.status_code == 405, (
            "[%s %s] 期望 405，实际 %s" % (method, rel, resp.status_code)
        )
        allow = resp.headers.get("Allow", "").upper()
        for needed in must_allow:
            assert needed in allow, (
                "[%s %s] Allow 头缺少 %s，实际 %r" % (method, rel, needed, allow)
            )
        assert_no_internal_leak(resp.text)
