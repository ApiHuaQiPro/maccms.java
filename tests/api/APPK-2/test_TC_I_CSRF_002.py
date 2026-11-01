# CASE: TC-I-CSRF-002 | 接口: ADM-3 来源校验前置（两侧对照） | 追溯: AC-16 / ADM-3 SRC-1+SRC-2
# 维度: 安全、权限 | 优先级: P1 | T2 补充（来源：契约 v1.3.0 @ bc612e93 ADM-3；后端实现 @ 6f78400）
# 标题: 同源请求与跨站请求走**不同分支**——同源到达业务层、跨站停在来源拒绝；
#       来源校验发生在**未登录判定之前**（无凭据的同源请求不能停在来源拒绝，无凭据的跨站请求必须停在来源拒绝）。
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452
#       —— ADM-3「网关顺序：控制器不存在 404 → 来源校验 SRC-1（403 提示页 / 200 JSON）→ 未登录 302 → 无权限 200 业务失败体」
# 为什么单独一条（T2 说明）：
#   TC-I-CSRF-001 的对照请求永远带有效会话，因此「请求被 302 跳到登录页」在 001 里是被允许的：
#   001 对任何 302 都不做业务层证明，而一个把来源校验放在登录之后、或干脆全部放行的实现，
#   在 001 下也可能靠「同源对照 302」保持绿。本条用**无凭据**的两种会话把它们分开：
#     · 无凭据 + 同源 → 必须停在 302（登录跳转），证明来源已通过；
#     · 无凭据 + 跨站 → 必须停在来源拒绝（Ajax 200 JSON / 非 Ajax 403 提示页），证明来源校验先于登录。
#   另把同源 + 有会话的对照从「不是来源拒绝」升级为「业务体字段逐项存在」（collect/test 成功体恒有 code，PHP
#   Collect::test 直接 json($res)），因此对照组必须有业务结论，不能只排除一种文案。
# 前置: 默认角色会话（APP_API_TOKEN，见 MULTICA.md §5）；来源校验与权限无关，故权限不构成前置。
# 说明: 本条不发起任何业务写操作；collect/api 的 GET 在 ac 缺省时落到「采集入库」分支且没有载入的本地文件时
#       直接返回失败体、不写库（实现 AdminCollectActions.ingest → CollectRunService.run 为空即返回），
#       为稳妥起见本条同源业务对照改用 collect/test（只读，契约 x-idempotency 明确「幂等（只读取数）」）。

from conftest import MSG_ILLEGAL_REQUEST, assert_json_error

# 同源 / 跨站的探针入口：(方法, 后台相对路径, 参数)
# 选 collect/test（C 类、只读，且实现里不连接资源站、恒返回 code=1001 业务体）与 make/make 各一个。
# make/make 在 ac 缺失时直接走参数错误分支（不入生成循环），因此本条的「有会话 + 同源」断言对两者都只要求
# 到达业务层（返回 JSON 且有 code），不预设成功/失败结论。
PROBES = [
    ("GET", "collect/test", {"cjurl": "http://cv.example.com/api.php/provide/vod/", "ac": "list"}),
    ("GET", "make/make", {}),
]

JUMP_PAGE_TITLE = "跳转提示"


def _is_source_rejection(resp):
    """响应是否是 ADM-3 的来源拒绝（Ajax：200 JSON「非法请求」；非 Ajax：403 提示页「非法请求」）。"""
    if resp.status_code == 403:
        return "text/html" in (resp.headers.get("Content-Type") or "") and MSG_ILLEGAL_REQUEST in resp.text
    if resp.status_code == 200 and "application/json" in (resp.headers.get("Content-Type") or ""):
        try:
            body = resp.json()
        except ValueError:
            return False
        return isinstance(body, dict) and body.get("msg") == MSG_ILLEGAL_REQUEST
    return False


def _assert_not_source_rejection(resp, label):
    assert not _is_source_rejection(resp), (
        "[%s] 期望请求已通过来源校验，实际停在来源拒绝（%s %r）"
        % (label, resp.status_code, resp.text[:200])
    )


def test_TC_I_CSRF_002(api_as, admin_url):
    for method, rel, params in PROBES:
        path = admin_url(rel)

        # ── 1) 无凭据 + 同源：必须停在「未登录 302」，证明来源校验已通过 ────────────────
        anon_same = api_as(None)
        resp = anon_same.call(method, path, params=params)
        _assert_not_source_rejection(resp, "无凭据同源 %s %s" % (method, rel))
        assert resp.status_code == 302, (
            "[%s %s] ADM-3 顺序要求：来源校验通过后、未登录时必须 302，实际 %s（Content-Type=%r 正文=%r）"
            % (method, rel, resp.status_code, resp.headers.get("Content-Type"), resp.text[:200])
        )
        location = (resp.headers.get("Location") or "").lower()
        assert location, "[%s %s] 未登录 302 必须带 Location" % (method, rel)
        assert "login" in location or "index" in location, (
            "[%s %s] 未登录 302 的 Location 不是登录入口：%r" % (method, rel, location)
        )

        # ── 2) 无凭据 + 跨站：必须停在来源拒绝（而不是 302）────────────────────────────
        anon_cross = api_as(None)
        anon_cross.session.headers["Sec-Fetch-Site"] = "cross-site"
        resp = anon_cross.call(method, path, params=params)
        assert resp.status_code != 302, (
            "[%s %s] 无凭据的跨站请求被判成未登录跳转，说明来源校验在登录之后（或缺失）：Location=%r"
            % (method, rel, resp.headers.get("Location"))
        )
        assert _is_source_rejection(resp), (
            "[%s %s] 无凭据 + 跨站必须是来源拒绝（Ajax 200 JSON / 非 Ajax 403 提示页），实际 %s %r"
            % (method, rel, resp.status_code, resp.text[:200])
        )

        # 2b) 同上、非 Ajax 形态 → 403 提示页
        anon_cross_page = api_as(None, ajax=False)
        anon_cross_page.session.headers["Sec-Fetch-Site"] = "cross-site"
        resp = anon_cross_page.call(method, path, params=params)
        assert resp.status_code == 403, (
            "[%s %s] 无凭据 + 非 Ajax 跨站必须是 403 提示页，实际 %s" % (method, rel, resp.status_code)
        )
        assert JUMP_PAGE_TITLE in resp.text and MSG_ILLEGAL_REQUEST in resp.text, (
            "[%s %s] 403 提示页缺少「%s」或「%s」：%r"
            % (method, rel, JUMP_PAGE_TITLE, MSG_ILLEGAL_REQUEST, resp.text[:200])
        )

        # ── 3) 有会话 + 同源：必须到达业务层（有业务结论，不是只排除来源拒绝）────────────
        # collect/test 是 C 类只读动作，契约要求恒返回 {code,msg} 业务体（PHP Collect::test 直接 json($res)）；
        # make/make 缺 ac 时是参数错误业务体。两者都必须带 code，且不能是来源拒绝文案。
        logged_in = api_as("default")
        resp = logged_in.call(method, path, params=params)
        _assert_not_source_rejection(resp, "有会话同源 %s %s" % (method, rel))
        assert resp.status_code == 200, (
            "[%s %s] 有会话 + 同源应到达业务层并返回 200 业务体，实际 %s" % (method, rel, resp.status_code)
        )
        assert "application/json" in (resp.headers.get("Content-Type") or ""), (
            "[%s %s] 业务层响应应为 application/json，实际 %r（正文=%r）"
            % (method, rel, resp.headers.get("Content-Type"), resp.text[:200])
        )
        body = resp.json()
        assert isinstance(body, dict) and "code" in body, (
            "[%s %s] 业务体缺少 code 字段，无法证明到达业务层：%r" % (method, rel, body)
        )
        # PHP Admin 基类的 json() 三个字段齐全；缺 msg 说明改造过程中换了返回契约
        assert "msg" in body, (
            "[%s %s] 业务体缺少 msg 字段（PHP Admin 基类 json($res) 恒带 code/msg/data）：%r"
            % (method, rel, body)
        )

    # ── 4) 有会话 + 跨站：仍必须被来源拒绝（登录态不能免除来源校验）──────────────────
    logged_cross = api_as("default")
    logged_cross.session.headers["Sec-Fetch-Site"] = "cross-site"
    resp = logged_cross.call("GET", admin_url("collect/test"), params=PROBES[0][2])
    assert _is_source_rejection(resp), (
        "有会话 + 跨站必须仍是来源拒绝，实际 %s %r" % (resp.status_code, resp.text[:200])
    )
    body = assert_json_error(resp, MSG_ILLEGAL_REQUEST)
    assert set(body) <= {"code", "msg", "data", "url", "wait"}, (
        "来源拒绝体出现契约外字段：%r" % (sorted(body),)
    )
