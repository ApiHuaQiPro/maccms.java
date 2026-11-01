# CASE: TC-I-CSRF-002 | 接口: ADM-3 来源校验前置（两侧对照） | 追溯: 无 AC-（ADM-3、SRC-1/SRC-2；T1 §2.14/§4 口径，不计入 AC 分母）
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
#
# 第 4 次修订 REV-I-07（两条）：
#   1) make/make 的期望**按契约改正**：MakeParams.ac 的契约原文是「缺失、空、数组或不在枚举 → 同 PHP 只输出
#      进度页样式、不生成（不是错误）」，不是「参数错误」。上一版把「缺 ac 即参数错误」写进注释与断言，与契约相反。
#      本文件因此把每个探针的期望显式声明（collect/test → 200 JSON 业务体；make/make + ac 缺失 → 200 text/html
#      进度页样式、无生成条目、无跳转、无「参数错误」），并按探针各自断言，不再用「都能返回 code」这种笼统条件。
#   2) 探针输入改为**有效对照输入**：make/make 不再发 `{}`。虽然契约规定缺 ac 时只出样式不生成，但 `{}` 同时
#      缺 tab/vodtype 等字段，某些实现会先在 tab/view 配置上判 staticErr 或落到 jump，让「到达业务层」这一断言
#      事实上变成「返回什么都可以」。改为 `ac=index`（唯一必然产出进度页样式 + 跳转签名、写入量固定为站点首页的
#      生成动作），并在步骤 3 里用生成条目与跳转签名证明请求真的到达了生成循环；步骤 5 另用 `{}` 单独断言契约的
#      「缺 ac 不生成、也不报参数错误」。
#
# 第 5 轮修订 REV-I-14（回应复核项 1 的后半）：**把 `ac=index` 的写副作用写明并加以约束**。
#   上一版选了 `ac=index` 做探针，却没有在任何地方声明「它会真的生成站点首页」——契约 API-024 对 make/make
#   的 x-idempotency 是「非幂等（生成静态文件）」，`ac=index` 是固定为站点首页的生成动作。本用例原来的
#   「本条不发起任何业务写操作」于是与事实不符。现在明确两件事：
#   1) **写副作用声明**：`ac=index` 会重写站点首页及其附带文件（模板渲染产物：`index.html` 等）与生成缓存；
#      重复执行只会覆写为相同内容（页面数据不变），但文件 mtime/大小可能变，在共享测试实例上会干扰
#      其他用例的静态文件基线。
#   2) **执行隔离闸门**：写副作用只在授权的、可丢弃的一次性站点根上允许。开头调用 `require_ui_write_isolation`：
#      `MACCMS_UI_WRITE_AUTH` 必须精确为 `yes`，且 `MACCMS_UI_ONEOFF_ROOT` 必须指向一次性站点根；
#      缺任一项 → BLOCKED，**且不回退到「只跑 collect/test 就宣称通过」**（那等于漏掉 make/make 的来源校验）。
#      若确需在共享实例上跑，只能把 PROBES 里的 make_progress 探针去掉并**在报告里记明未覆盖**，不能静默降级。
#  另：本条不写库、不下载图片、不写断点缓存（collect/test 侧契约 x-idempotency 为「幂等（只读取数）」）；
#      make/make 侧的写面仅限静态生成产物，已由上面的闸门限定。
#      collect/api 的 GET 在 ac 缺省时会落到「采集入库」分支，为稳妥起见本条同源业务对照使用 collect/test。
import os
import re
import sys

# 同目录共享模块 install_support.py（只做前置与断言工具，不执行用例）。
_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

from conftest import MSG_ILLEGAL_REQUEST, MSG_PARAM_ERR, assert_json_error
from install_support import require_ui_write_isolation

# 同源 / 跨站的探针入口：(方法, 后台相对路径, 参数, 期望种类)
#   "collect_test"  → C 类只读，恒返回 {code,msg} 业务体（实现 AdminCollectActions.testRefused）；
#   "make_progress" → 合法 ac=index，必然进入生成循环（进度页样式 + 生成条目 + 跳转签名）。
PROBES = [
    (
        "GET",
        "collect/test",
        {"cjurl": "http://cv.example.com/api.php/provide/vod/", "ac": "list"},
        "collect_test",
    ),
    ("GET", "make/make", {"ac": "index"}, "make_progress"),
]

JUMP_PAGE_TITLE = "跳转提示"

# MakeService.STYLE（src/main/java/com/maccms/backend/staticgen/application/MakeService.java:48）与
# AdminMakeActions.progressJump 的跳转片段：进度页的稳定签名，与站点数据无关。
STYLE_MARKERS = (
    '<style type="text/css">',
    "body{font-size:12px;color: #333333;line-height:21px;}",
    "span{font-weight:bold;color:#FF0000}",
)
JUMP_MARKERS = ("/admin-assets/progress.js", "data-url=", "data-seconds=")
GENERATED_LINK = re.compile(r'<a target="_blank" href="[^"]*"><font color=>(.*?)</font></a>', re.S)
FAILED_LINE = re.compile(r"生成失败：([^<]*)")


def _assert_business_result(resp, label, kind):
    """按探针种类断言「请求真的到达了业务层」，返回业务体（JSON 探针）或 None（进度页探针）。"""
    assert resp.status_code == 200, (
        "[%s] 有会话 + 同源应到达业务层并返回 200 业务结果，实际 %s（正文=%r）"
        % (label, resp.status_code, resp.text[:200])
    )
    ctype = resp.headers.get("Content-Type") or ""
    if kind == "collect_test":
        assert "application/json" in ctype, (
            "[%s] collect/test 的业务结果是 CollectTestResult（application/json），实际 %r（正文=%r）"
            % (label, ctype, resp.text[:200])
        )
        body = resp.json()
        assert isinstance(body, dict) and "code" in body, (
            "[%s] 业务体缺少 code 字段，无法证明到达业务层：%r" % (label, body)
        )
        # PHP Admin 基类的 json() 三个字段齐全；缺 msg 说明改造过程中换了返回契约
        assert "msg" in body, (
            "[%s] 业务体缺少 msg 字段（PHP Admin 基类 json($res) 恒带 code/msg/data）：%r" % (label, body)
        )
        return body

    # make/make + 合法 ac=index：契约 AdminProgress 的 200 text/html 进度页
    assert "application/json" not in ctype, (
        "[%s] ac=index 是合法入参，返回了 JSON 失败体：Content-Type=%r 正文=%r"
        % (label, ctype, resp.text[:200])
    )
    assert "text/html" in ctype, "[%s] 进度页应为 text/html，实际 %r" % (label, ctype)
    for marker in STYLE_MARKERS:
        assert marker in resp.text, (
            "[%s] 缺少进度页样式签名 %r，普通 200 HTML 错误页不能算到达生成循环：%r"
            % (label, marker, resp.text[:300])
        )
    for marker in JUMP_MARKERS:
        assert marker in resp.text, (
            "[%s] 缺少 progress.js 跳转签名 %r（ac=index 必然 jumpTo）：%r"
            % (label, marker, resp.text[:300])
        )
    assert GENERATED_LINK.findall(resp.text), (
        "[%s] 进度页没有任何生成条目，说明请求没走完生成循环：%r" % (label, resp.text[:300])
    )
    assert MSG_PARAM_ERR not in resp.text, (
        "[%s] ac=index 是合法入参，不得判为参数错误：%r" % (label, resp.text[:200])
    )
    return None


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
    # 前置（REV-I-14）：make_progress 探针会真的生成站点首页（契约 API-024 非幂等），
    # 因此必须声明写侧隔离：一次性站点根 + 明确授权；缺任一项 → BLOCKED。
    require_ui_write_isolation("CSRF-002")

    for method, rel, params, kind in PROBES:
        path = admin_url(rel)
        # 下面的无凭据探针不注入令牌，所以 collect/test 的 cjurl 永远不会被执行；
        # make/make 的 ac=index 在无凭据下也到不了生成循环（未登录 302 / 来源拒绝），不会写文件。

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

        # ── 3) 有会话 + 同源：必须到达业务层（按探针种类断言各自的契约输出）──────────────────
        # collect/test：C 类只读，恒返回 {code,msg} 业务体（PHP Collect::test 直接 json($res)）；
        # make/make + ac=index：AdminProgress 的 200 text/html 进度页（样式签名 + 生成条目 + 跳转签名）。
        logged_in = api_as("default")
        resp = logged_in.call(method, path, params=params)
        _assert_not_source_rejection(resp, "有会话同源 %s %s" % (method, rel))
        _assert_business_result(resp, "有会话同源 %s %s" % (method, rel), kind)

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

    # ── 5) 契约反向对照：make/make 缺 ac → 只出进度页样式、不生成、**不报参数错误** ──────────
    # 契约 MakeParams.ac：「缺失、空、数组或不在枚举 → 同 PHP 只输出进度页样式、不生成（不是错误）」。
    # 上一版把它当成「参数错误」，是断言写错而不是实现缺陷（REV-I-07）。这里逐字按契约断言：
    # 样式签名必须是进度页（证明进了 make/make 而不是别的分支），且没有生成条目、没有跳转、没有「参数错误」、
    # 没有生成完成文案。本条不写任何文件（缺 ac ⇒ 不进任何生成分支）。
    api = api_as("default")
    resp = api.call("GET", admin_url("make/make"), params={})
    assert resp.status_code == 200, (
        "make/make 缺 ac：契约要求 HTTP 200 进度页样式，实际 %s" % resp.status_code
    )
    assert "text/html" in (resp.headers.get("Content-Type") or ""), (
        "make/make 缺 ac：契约要求 text/html 进度页样式，实际 %r" % resp.headers.get("Content-Type")
    )
    for marker in STYLE_MARKERS:
        assert marker in resp.text, (
            "make/make 缺 ac：缺少进度页样式签名 %r（契约：仍输出进度页样式）：%r"
            % (marker, resp.text[:300])
        )
    assert MSG_PARAM_ERR not in resp.text, (
        "make/make 缺 ac：契约写明「不是错误」，实际返回了参数错误：%r" % resp.text[:200]
    )
    assert not GENERATED_LINK.findall(resp.text), (
        "make/make 缺 ac：出现了生成条目，契约要求不生成：%r" % GENERATED_LINK.findall(resp.text)[:5]
    )
    assert not FAILED_LINE.findall(resp.text), (
        "make/make 缺 ac：出现了生成失败行，说明进入了生成流程：%r" % FAILED_LINE.findall(resp.text)[:5]
    )
    assert "data-url=" not in resp.text, (
        "make/make 缺 ac：出现了跳转片段，契约要求不生成也就不跳转：%r" % resp.text[:300]
    )
