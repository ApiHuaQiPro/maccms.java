# CASE: TC-I-CSRF-001 | 接口: B 类后台动作（API-024/040/043） | 追溯: 无 AC-（ADM-3、SRC-1/SRC-2；T1 §2.14/§4 口径，不计入 AC 分母） | 维度: 安全、权限 | 优先级: P1
# 标题: B 类入口：跨站来源（Sec-Fetch-Site: cross-site / 外站 Origin）被拒；三个来源头都缺失也被拒（SRC-2）；
#       拒绝响应的状态码、固定文案与结构**按请求形态分别断言**（Ajax↔200 JSON、非 Ajax↔403 提示页）；
#       同源对照必须真正通过来源校验（能到达业务结果）
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452
#       （ADM-3 SRC-1/SRC-2、x-csrf-policy classes.B、ADMIN_SOURCE_REJECTED）
# 前置: 默认角色管理员有对应权限（来源校验先于登录与权限判定，因此拒绝与权限无关）
# 第 2 次复核 REV-I-04 的修法：
#   - 不再按响应实际的 Content-Type 反推期望形态（那样 Ajax 收到 403 HTML、非 Ajax 收到 200 JSON 都能通过）；
#   - 每个请求都显式声明 mode（"ajax" / "page"），并断言该形态的契约输出：
#       ajax → HTTP 200 + application/json + code=0 + msg 固定「非法请求」；
#       page → HTTP 403 + text/html + 标题「跳转提示」+ 正文「非法请求」；
#   - 任何情况下都不接受 302（未登录跳转）、200 非拒绝体、成功结果（进度页/生成结果/采集结果）；
#     注（第 4 次修订 REV-I-07）：上一版这里写成「302 一律判失败」，与本文件的同源对照实际行为相反——
#     对照请求恒带有效会话，来源校验通过后应进入业务层；若实现回了 302，那恰恰说明未登录判定被放到了来源校验之前
#     或者会话没被认出来，**对照就该失败**，所以本文件对同源对照也断言「不得是 302」（见第 3 节）。
#     而 ADM-3 允许 302 的唯一场景是「来源校验通过 + 未登录」——那个场景由 TC-I-CSRF-002（无凭据会话）
#     用一条**肯定**断言（必须 302 且 Location 指向登录页）覆盖，不在本文件里以「允许」的形式出现。
#   - 同源对照：不只看「没有非法请求」，而是断言请求真的走完了来源校验并到达业务层
#     （collect/api 返回 JSON 业务体且 code 字段存在；不是 302、不是 403、不是来源拒绝文案）。

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

# 跳转提示页的标志（ADM-2 / AdminJumpPage：<title>跳转提示</title>）
JUMP_PAGE_MARKERS = ("跳转提示",)


def _reject(resp, label, mode):
    """按**请求形态**断言契约允许的唯一拒绝形态（不是按响应反推）。

    mode == "ajax"：ADM-3 要求 Ajax 请求的来源拒绝是 200 JSON（code=0，msg=非法请求）；
    mode == "page"：ADM-3 要求非 Ajax 请求的来源拒绝是 403 提示页（标题「跳转提示」+ 正文「非法请求」）。
    """
    assert mode in ("ajax", "page"), "用例内部错误：mode 必须是 ajax 或 page，实际 %r" % (mode,)
    assert resp.status_code != 302, (
        "[%s] 拒绝不能是 302 登录跳转（Location=%r）：未登录跳转不是来源拒绝"
        % (label, resp.headers.get("Location"))
    )
    ctype = resp.headers.get("Content-Type", "")

    if mode == "ajax":
        # Ajax 形态：只接受 200 JSON，收到 403/HTML 一律失败
        assert resp.status_code == 200, (
            "[%s] Ajax 请求的来源拒绝应为 HTTP 200 JSON，实际 %s（Content-Type=%r）——"
            "非 Ajax 的 403 提示页不能作为 Ajax 拒绝" % (label, resp.status_code, ctype)
        )
        assert "application/json" in ctype, (
            "[%s] Ajax 请求的来源拒绝必须是 application/json，实际 Content-Type=%r" % (label, ctype)
        )
        body = assert_json_error(resp, MSG_ILLEGAL_REQUEST)
        assert set(body) <= {"code", "msg", "data", "url", "wait"}, (
            "[%s] Ajax 来源拒绝体出现契约外字段：%r" % (label, sorted(body))
        )
        assert_no_success(resp, SUCCESS_MARKERS)
        return

    # 非 Ajax（页面/表单）形态：只接受 403 HTML 提示页，收到 200 JSON 一律失败
    assert resp.status_code == 403, (
        "[%s] 非 Ajax 请求的来源拒绝必须是 HTTP 403 提示页，实际 %s（Content-Type=%r）——"
        "200 JSON 不能作为非 Ajax 拒绝" % (label, resp.status_code, ctype)
    )
    assert "text/html" in ctype, "[%s] 403 提示页应为 text/html，实际 %r" % (label, ctype)
    page = resp.text
    for marker in JUMP_PAGE_MARKERS:
        assert marker in page, (
            "[%s] 403 提示页缺少标题标记 %r：%r" % (label, marker, page[:200])
        )
    assert MSG_ILLEGAL_REQUEST in page, (
        "[%s] 403 提示页正文应为固定「非法请求」，实际=%r" % (label, page[:200])
    )
    assert "login" not in resp.headers.get("Location", "").lower(), (
        "[%s] 403 不应携带登录跳转" % label
    )
    assert no_json_body(resp), "[%s] 非 Ajax 拒绝页不应是 JSON 体：%r" % (label, page[:200])
    assert_no_success(resp, SUCCESS_MARKERS)
    assert_no_internal_leak(page)


def no_json_body(resp):
    """403 提示页不得是 JSON（防止把后台 JSON 失败体伪装成 403）。"""
    try:
        resp.json()
    except ValueError:
        return True
    return False


def test_TC_I_CSRF_001(api_as, admin_url):
    # 1) 跨站来源（SRC-1）：Sec-Fetch-Site: cross-site → 拒绝
    for method, rel, params in B_CLASS:
        # 1a) Ajax 形态：200 JSON「非法请求」
        api = api_as("default")
        api.session.headers["Sec-Fetch-Site"] = "cross-site"
        resp = api.call(method, admin_url(rel), params=params)
        _reject(resp, "Ajax 跨站 %s %s" % (method, rel), "ajax")

        # 1b) 非 Ajax 形态：403 提示页「非法请求」
        api = api_as("default", ajax=False)
        api.session.headers["Sec-Fetch-Site"] = "cross-site"
        resp = api.call(method, admin_url(rel), params=params)
        _reject(resp, "非 Ajax 跨站 %s %s" % (method, rel), "page")

    # 1c) 无 Sec-Fetch-Site 但 Origin 指向其他站点（SRC-1 的回退分支）→ 拒绝
    for method, rel, params in B_CLASS:
        api = api_as("default", source_headers=False)
        api.session.headers["Origin"] = "https://evil.example.com"
        resp = api.call(method, admin_url(rel), params=params)
        _reject(resp, "Ajax 外站 Origin %s %s" % (method, rel), "ajax")

    # 1d) 同上但非 Ajax 形态 → 403 提示页
    for method, rel, params in B_CLASS:
        api = api_as("default", ajax=False, source_headers=False)
        api.session.headers["Origin"] = "https://evil.example.com"
        resp = api.call(method, admin_url(rel), params=params)
        _reject(resp, "非 Ajax 外站 Origin %s %s" % (method, rel), "page")

    # 2) SRC-2：三个来源头（Sec-Fetch-Site / Origin / Referer）都缺失 → 拒绝
    for method, rel, params in B_CLASS:
        # 2a) Ajax
        api = api_as("default", source_headers=False)
        for header in ("Origin", "Referer", "Sec-Fetch-Site"):
            api.session.headers.pop(header, None)
        resp = api.call(method, admin_url(rel), params=params)
        _reject(resp, "Ajax 无来源头 %s %s" % (method, rel), "ajax")

        # 2b) 非 Ajax（同 PHP 的表单/跳转调用形态）
        api = api_as("default", ajax=False, source_headers=False)
        for header in ("Origin", "Referer", "Sec-Fetch-Site"):
            api.session.headers.pop(header, None)
        resp = api.call(method, admin_url(rel), params=params)
        _reject(resp, "非 Ajax 无来源头 %s %s" % (method, rel), "page")

    # 3) 同源对照（REV-I-04）：必须证明请求真的通过了来源校验并到达业务层，
    #    而不是「没出现非法请求」就算通过。
    control_method, control_rel, control_params = B_CLASS[1]
    api = api_as("default")
    resp = api.call(control_method, admin_url(control_rel), params=control_params)
    assert resp.status_code != 302, (
        "同源对照不得被跳去登录（302，Location=%r）：来源校验通过后应进入登录/业务判定"
        % resp.headers.get("Location")
    )
    assert resp.status_code != 403, (
        "同源请求被判为 403 拒绝页，来源校验实现过严，SRC 断言失去区分力：%r" % resp.text[:200]
    )
    assert MSG_ILLEGAL_REQUEST not in resp.text, (
        "同源请求被判为来源拒绝，说明来源校验实现过严，SRC 断言失去区分力：%r" % resp.text[:200]
    )
    # 业务层证据：collect/api 无论是成功还是参数错误，都返回 {code,msg} JSON 业务体
    assert "application/json" in resp.headers.get("Content-Type", ""), (
        "同源对照未到达业务层：Content-Type=%r，响应=%r"
        % (resp.headers.get("Content-Type"), resp.text[:200])
    )
    payload = resp.json()
    assert isinstance(payload, dict) and "code" in payload, (
        "同源对照的响应缺少业务字段 code，无法证明请求越过了来源校验：%r" % (payload,)
    )
    # 同源对照允许业务失败（例如测试环境没有该采集链接），但不允许出现来源拒绝或权限拒绝
    assert MSG_ILLEGAL_REQUEST not in str(payload.get("msg", "")), (
        "同源对照的业务 msg 是来源拒绝文案：%r" % (payload,)
    )

    # 4) 再补一个「同源页面形态」对照：非 Ajax 请求通过来源校验后不应是 403 拒绝页
    api = api_as("default", ajax=False)
    resp = api.call(control_method, admin_url(control_rel), params=control_params)
    assert resp.status_code != 403, (
        "同源非 Ajax 请求命中 403（可能是来源拒绝），SRC 断言对非 Ajax 失去区分力：%r" % resp.text[:200]
    )
    assert MSG_ILLEGAL_REQUEST not in resp.text, (
        "同源非 Ajax 请求被判为来源拒绝：%r" % resp.text[:200]
    )
