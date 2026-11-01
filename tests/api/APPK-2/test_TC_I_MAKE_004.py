# CASE: TC-I-MAKE-004 | 接口: GET|POST /make/{makeAction}（API-024） | 追溯: AC-7 / SCH-1 | 维度: 边界 | 优先级: P2
# 标题: 数组形态按字段声明分流：IdList 字段受理数组；ac 为数组只是不生成（不是错误）；全部字段缺失不报错；
#       未列出的字段被忽略。四组分别断言状态码、内容类型与**进度页签名**，并把未知字段组与对照组的
#       业务结果逐项对比（不只比 Content-Type）。
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（SCH-1 + MakeParams 各字段说明）
# 前置: 默认角色管理员有 make/* 权限（来源校验与登录先于入参判定，故需已登录同源会话）
# 判定（第 3 轮修订 REV-I-04）：
#   · 状态码只能是 200；302（登录跳转）、403（来源拒绝）、400/415（传输层拒绝）、5xx 一律失败；
#   · 内容类型：合法入参 → text/html 进度页（AdminProgress / MakeService 的 echo(STYLE) + progress.js）；
#   · **进度页签名**（稳定、与数据无关，取自已批准实现）：
#       - 正文含 MakeService.STYLE 的特征：`<style type="text/css">` + `body{font-size:12px;color: #333333;line-height:21px;}`
#         与 `span{font-weight:bold;color:#FF0000}`；
#       - 含 progress.js 的跳转片段：`/admin-assets/progress.js`、`data-url=`、`data-seconds=`，
#         以及「如果您的浏览器没有自动跳转，请点击这里」与「暂停…秒后继续」。
#     只有 200 + text/html + 上述签名同时成立，才算「真的进入了生成/进度页面」，普通的 200 HTML 错误页不算；
#   · ac 数组/缺失：只输出进度页样式、不生成 —— 断言没有生成结果行（<a ... href=...> 生成条目 + 「生成失败：」）；
#   · 未列字段被忽略：与不带该字段的请求**业务结果一致**（同一个进度页签名、同样的生成结果行集合、
#     同样的跳转地址与秒数），不得出现「参数错误」。
#
# 注意：本用例会真实触发静态生成（写入站点静态文件），属被测环境的预期副作用，不是测试脚本的写库动作。

import re

from conftest import MSG_PARAM_ERR, assert_json_error, is_admin_failure

CASE = "MAKE-004"

# MakeService.STYLE（已批准实现 src/main/java/com/maccms/backend/staticgen/application/MakeService.java:48）
STYLE_MARKERS = (
    '<style type="text/css">',
    "body{font-size:12px;color: #333333;line-height:21px;}",
    "span{font-weight:bold;color:#FF0000}",
)
# mac_jump → progress.js（AdminMakeActions.progressJump，lang messages/pause + continue_in_second + browser_jump）
JUMP_MARKERS = (
    "/admin-assets/progress.js",
    "data-url=",
    "data-seconds=",
    "如果您的浏览器没有自动跳转，请点击这里",
    "暂停",
    "秒后继续",
)
# 生成结果行：<a target="_blank" href="..."><font color=>文件名</font></a> 或「生成失败：…」
GENERATED_LINK = re.compile(r'<a target="_blank" href="[^"]*"><font color=>(.*?)</font></a>', re.S)
FAILED_LINE = re.compile(r"生成失败：([^<]*)")

PARAM_ERR = MSG_PARAM_ERR
DONE_MARKERS = ("生成完毕", "生成完成", "成功生成", "已生成")


def _progress_signature(resp, label, require_jump=True):
    """校验进度页签名并返回可比较的业务结果快照。"""
    assert resp.status_code == 200, (
        "[%s] 期望 HTTP 200 进度页，实际 %s" % (label, resp.status_code)
    )
    assert not resp.headers.get("Location"), (
        "[%s] 不应是跳转响应（Location=%r）" % (label, resp.headers.get("Location"))
    )
    ctype = resp.headers.get("Content-Type", "")
    assert "application/json" not in ctype, (
        "[%s] 合法入参不应返回 JSON 失败体，Content-Type=%r，body=%r"
        % (label, ctype, resp.text[:200])
    )
    assert "text/html" in ctype, (
        "[%s] 进度页应为 text/html，Content-Type=%r" % (label, ctype)
    )
    assert not is_admin_failure(resp), "[%s] 合法入参不应返回 code=0 失败体" % label
    assert PARAM_ERR not in resp.text, "[%s] 合法入参不应判为参数错误：%r" % (
        label,
        resp.text[:200],
    )
    body = resp.text
    # 进度页签名 1：MakeService.STYLE
    for marker in STYLE_MARKERS:
        assert marker in body, (
            "[%s] 响应缺少进度页样式签名 %r，普通 200 HTML 错误页不能算进度页：%r"
            % (label, marker, body[:300])
        )
    if require_jump:
        # 进度页签名 2：progress.js 跳转片段（ac 合法时必然 jumpTo/jumpBack）
        for marker in JUMP_MARKERS:
            assert marker in body, (
                "[%s] 响应缺少进度页跳转签名 %r：%r" % (label, marker, body[:300])
            )
    return {
        "style": [m for m in STYLE_MARKERS if m in body],
        "generated": sorted(m.strip() for m in GENERATED_LINK.findall(body)),
        "failed": sorted(m.strip() for m in FAILED_LINE.findall(body)),
        "jumps": sorted(re.findall(r'data-url="([^"]*)"[^>]*data-seconds="([^"]*)"', body)),
        "ctype": ctype,
    }


def test_TC_I_MAKE_004(api_as, admin_url):
    api = api_as("default")
    path = admin_url("make/make")

    arrays = [
        ("ac", "index"),
        ("ids[]", "1"),
        ("ids[]", "2"),
        ("vodtype[]", "1"),
        ("arttype[]", "1"),
        ("topic[]", "1"),
    ]

    # 1) IdList 字段（ids / vodtype / arttype / topic）的数组形态：受理，进入进度页
    snapshots = {}
    for method in ("GET", "POST"):
        kwargs = (
            {"params": arrays}
            if method == "GET"
            else {"data": arrays}
        )
        resp = api.call(method, path, **kwargs)
        snapshots["IdList 数组 %s" % method] = _progress_signature(
            resp, "%s IdList 数组 %s" % (CASE, method)
        )

    # 2) ac 为数组：契约写明「缺失、空、数组或不在枚举 → 只输出进度页样式、不生成（不是错误）」
    for method in ("GET", "POST"):
        kwargs = (
            {"params": [("ac[]", "index"), ("ac[]", "rss")]}
            if method == "GET"
            else {"data": [("ac[]", "index"), ("ac[]", "rss")]}
        )
        resp = api.call(method, path, **kwargs)
        # ac 缺失/数组时不 jumpTo，因此不要求跳转签名，只要求样式签名 + 无生成结果
        snap = _progress_signature(resp, "%s ac 数组 %s" % (CASE, method), require_jump=False)
        assert not snap["generated"], (
            "[%s] ac 为数组时不应生成任何页面，出现了生成条目：%r"
            % (method, snap["generated"][:5])
        )
        assert not snap["failed"], (
            "[%s] ac 为数组时不应进入生成流程，出现了生成失败行：%r" % (method, snap["failed"][:5])
        )
        assert not snap["jumps"], (
            "[%s] ac 为数组时不应跳转（不生成就不 jumpTo）：%r" % (method, snap["jumps"])
        )
        for done in DONE_MARKERS:
            assert done not in resp.text, (
                "[%s] ac 为数组时不应生成任何页面，出现生成结果：%r" % (method, done)
            )

    # 3) 全部字段缺失（MakeParams 全部可选）：不报错，仍是进度页样式、不生成
    for method in ("GET", "POST"):
        resp = api.call(method, path)
        snap = _progress_signature(resp, "%s 全字段缺失 %s" % (CASE, method), require_jump=False)
        assert not snap["generated"], (
            "[%s] 全字段缺失时不应生成任何页面，出现了生成条目：%r" % (method, snap["generated"][:5])
        )
        assert not snap["jumps"], (
            "[%s] 全字段缺失时不应跳转：%r" % (method, snap["jumps"])
        )
        for done in DONE_MARKERS:
            assert done not in resp.text, (
                "[%s] 全字段缺失时不应生成任何页面，出现生成结果：%r" % (method, done)
            )

    # 4) 未列出的字段被忽略（不进入判定），与不带该字段的**业务结果**一致
    baseline = api.call("GET", path, params={"ac": "index"})
    base_snap = _progress_signature(baseline, "%s 未列字段对照（无 unknown）" % CASE)
    resp = api.call("GET", path, params={"unexpected_field": "x", "ac": "index"})
    unknown_snap = _progress_signature(resp, "%s 未列字段被忽略" % CASE)

    assert base_snap["ctype"] == unknown_snap["ctype"], (
        "[%s] 带未列字段与不带未列字段的响应类型不一致：%r vs %r"
        % (CASE, unknown_snap["ctype"], base_snap["ctype"])
    )
    assert base_snap["style"] == unknown_snap["style"], (
        "[%s] 进度页样式签名不一致：%r vs %r" % (CASE, unknown_snap["style"], base_snap["style"])
    )
    assert base_snap["jumps"] == unknown_snap["jumps"], (
        "[%s] 跳转地址/秒数不一致（未列字段影响了生成结果）：%r vs %r"
        % (CASE, unknown_snap["jumps"], base_snap["jumps"])
    )
    # 生成条目集合：两组都真的生成了首页，条目集合必须一致（证明未列字段没有改变业务结果）
    assert base_snap["generated"], (
        "[%s 未列字段对照] 对照组没有生成任何条目，无法比较业务结果：进度页=%r"
        % (CASE, base_snap)
    )
    assert base_snap["generated"] == unknown_snap["generated"], (
        "[%s] 带未列字段改变了生成结果：%r vs %r"
        % (CASE, unknown_snap["generated"], base_snap["generated"])
    )
    assert base_snap["failed"] == unknown_snap["failed"], (
        "[%s] 带未列字段改变了生成失败行：%r vs %r"
        % (CASE, unknown_snap["failed"], base_snap["failed"])
    )

    # 5) 反向对照：真正不合规的入参必须是失败体（保证上面的正向断言有区分力）
    bad = api.call("GET", path, params={"ac": "index", "ps": "1001"})
    assert bad.status_code == 200, (
        "ps=1001 应为 HTTP 200 业务失败体，实际 %s" % bad.status_code
    )
    if "application/json" in bad.headers.get("Content-Type", ""):
        assert_json_error(bad, PARAM_ERR)
    else:
        assert PARAM_ERR in bad.text, (
            "ps=1001 应在非 Ajax 跳转提示页里给出「参数错误」，实际=%r" % bad.text[:200]
        )
    # 反向对照也必须没有进度页签名里的「生成结果」，否则说明断言对象选错
    assert not GENERATED_LINK.findall(bad.text), (
        "ps=1001 失败体里出现了生成条目：%r" % bad.text[:200]
    )
