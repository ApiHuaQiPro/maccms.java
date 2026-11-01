# CASE: TC-I-CSRF-001 | 接口: B 类后台动作（API-024/040/043） | 追溯: AC-16 / ADM-3 SRC-1+SRC-2 | 维度: 安全、权限 | 优先级: P1
# 标题: B 类入口：跨站来源（Sec-Fetch-Site: cross-site）被拒；三个来源头都缺失也被拒（SRC-2）；
#       拒绝响应的状态码、固定文案与结构逐项检查；非 JSON 响应必须是 403 拒绝页而不是登录页或成功结果
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452
#       （ADM-3 SRC-1/SRC-2、x-csrf-policy classes.B、ADMIN_SOURCE_REJECTED）
# 前置: 默认角色管理员有对应权限（来源校验先于登录与权限判定，因此拒绝与权限无关）
# 判定（第 2 轮修订，逐项）：
#   - Ajax（X-Requested-With: XMLHttpRequest）→ HTTP 200 + application/json + code=0 + msg 固定「非法请求」；
#   - 非 Ajax → HTTP 403 + text/html 固定拒绝页（标题「跳转提示」、正文「非法请求」）；
#   - 任何情况下都不接受 302（未登录跳转）、200 非拒绝体、或成功结果（进度页/生成结果/采集结果）。
#   - 被拒请求不产生动作结果：进度页标记与 code=1 成功体都判失败。

from conftest import (
    MSG_ILLEGAL_REQUEST,
    assert_json_error,
    assert_no_internal_leak,
    assert_no_success,
)

# B 类且受 SRC-1 + SRC-2 约束的入口（collect/test 为 C 类，不在本条范围）
B_CLASS = [
    ("GET", "make/make", {"ac": "index"}),
    ("GET", "collect/api", {"cjurl": "http://cv.example.com/api.php/provide/vod/", "ac": "list"}),
    ("GET", "images/sync", {"tab": "vod"}),
    ("POST", "images/sync", {"tab": "vod"}),
]

# 成功结果的标记（进度页逐行输出与成功 JSON），拒绝响应里一个都不能出现
SUCCESS_MARKERS = ("正在生成", "生成完毕", "开始采集", "采集完成", "同步完毕", "code\" : 1", '"code": 1')


def _reject(resp, label):
    """逐项检查拒绝响应：只认契约允许的两种形态，其他一律失败。"""
    assert resp.status_code != 302, (
        "[%s] 拒绝不能是 302 登录跳转（Location=%r）：未登录跳转不是来源拒绝"
        % (label, resp.headers.get("Location"))
    )
    ctype = resp.headers.get("Content-Type", "")
    if "application/json" in ctype:
        assert resp.status_code == 200, (
            "[%s] Ajax 拒绝应为 HTTP 200 JSON，实际 %s" % (label, resp.status_code)
        )
        assert_json_error(resp, MSG_ILLEGAL_REQUEST)
        assert_no_success(resp, SUCCESS_MARKERS)
        return
    # 非 JSON：必须是 403 固定拒绝页，而不是登录页、成功页或普通错误页
    assert resp.status_code == 403, (
        "[%s] 非 JSON 的拒绝必须是 HTTP 403 提示页，实际 %s（Content-Type=%r）"
        % (label, resp.status_code, ctype)
    )
    assert "text/html" in ctype, "[%s] 403 提示页应为 text/html，实际 %r" % (label, ctype)
    body = resp.text
    assert MSG_ILLEGAL_REQUEST in body, (
        "[%s] 403 提示页正文应为固定「非法请求」，实际=%r" % (label, body[:200])
    )
    assert "跳转提示" in body or "提示" in body, (
        "[%s] 403 提示页缺少标题「跳转提示」：%r" % (label, body[:200])
    )
    assert "login" not in resp.headers.get("Location", "").lower(), (
        "[%s] 403 不应携带登录跳转" % label
    )
    assert_no_success(resp, SUCCESS_MARKERS)
    assert_no_internal_leak(body)


def test_TC_I_CSRF_001(api_as, admin_url):
    # 1) 跨站来源（SRC-1）：Sec-Fetch-Site: cross-site → 拒绝
    for method, rel, params in B_CLASS:
        # 1a) Ajax：200 JSON「非法请求」
        api = api_as("default")
        api.session.headers["Sec-Fetch-Site"] = "cross-site"
        resp = api.call(method, admin_url(rel), params=params)
        _reject(resp, "Ajax 跨站 %s %s" % (method, rel))

        # 1b) 非 Ajax：403 提示页「非法请求」
        api = api_as("default", ajax=False)
        api.session.headers["Sec-Fetch-Site"] = "cross-site"
        resp = api.call(method, admin_url(rel), params=params)
        _reject(resp, "非 Ajax 跨站 %s %s" % (method, rel))

    # 1c) 无 Sec-Fetch-Site 但 Origin 指向其他站点（SRC-1 的回退分支）→ 拒绝
    for method, rel, params in B_CLASS:
        api = api_as("default", source_headers=False)
        api.session.headers["Origin"] = "https://evil.example.com"
        resp = api.call(method, admin_url(rel), params=params)
        _reject(resp, "外站 Origin %s %s" % (method, rel))

    # 2) SRC-2：三个来源头（Sec-Fetch-Site / Origin / Referer）都缺失 → 拒绝
    for method, rel, params in B_CLASS:
        # 2a) Ajax
        api = api_as("default", source_headers=False)
        for header in ("Origin", "Referer", "Sec-Fetch-Site"):
            api.session.headers.pop(header, None)
        resp = api.call(method, admin_url(rel), params=params)
        _reject(resp, "Ajax 无来源头 %s %s" % (method, rel))

        # 2b) 非 Ajax（同 PHP 的表单/跳转调用形态）
        api = api_as("default", ajax=False, source_headers=False)
        for header in ("Origin", "Referer", "Sec-Fetch-Site"):
            api.session.headers.pop(header, None)
        resp = api.call(method, admin_url(rel), params=params)
        _reject(resp, "非 Ajax 无来源头 %s %s" % (method, rel))

    # 3) 对照：同源 Ajax 请求不得被来源校验拦下（防止「全都拒绝」的伪通过）
    api = api_as("default")
    resp = api.call("GET", admin_url("collect/api"), params=B_CLASS[1][2])
    assert "非法请求" not in resp.text, (
        "同源请求被判为来源拒绝，说明来源校验实现过严，SRC 断言失去区分力：%r" % resp.text[:200]
    )
