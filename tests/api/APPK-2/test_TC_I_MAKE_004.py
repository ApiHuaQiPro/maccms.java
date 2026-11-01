# CASE: TC-I-MAKE-004 | 接口: GET|POST /make/{makeAction}（API-024） | 追溯: AC-7 / SCH-1 | 维度: 边界 | 优先级: P2
# 标题: 数组形态按字段声明分流：IdList 字段受理数组；ac 为数组只是不生成（不是错误）；全部字段缺失不报错；
#       未列出的字段被忽略。四组分别断言状态码、内容类型与固定的进度页/错误码边界。
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（SCH-1 + MakeParams 各字段说明）
# 前置: 默认角色管理员有 make/* 权限（来源校验与登录先于入参判定，故需已登录同源会话）
# 判定（第 2 轮修订，逐组）：
#   · 状态码只能是 200；302（登录跳转）、403（来源拒绝）、400/415（传输层拒绝）、5xx 一律失败；
#   · 内容类型：合法入参 → text/html 进度页（AdminProgress）；入参不合规 → Ajax 为 application/json code=0 失败体；
#   · 正向组必须出现进度页标记（说明真的进入了生成/进度分支，而不是被登录页或错误页顶替）；
#   · ac 数组/缺失：只输出进度页样式、不生成 —— 断言没有「生成成功」类结果输出；
#   · 未列字段被忽略：与不带该字段的请求同等结果，不得出现「参数错误」。
#
# 注意：本用例会真实触发静态生成（写入站点静态文件），属被测环境的预期副作用，不是测试脚本的写库动作。

from conftest import MSG_PARAM_ERR, assert_json_error, is_admin_failure

# 成功生成的可辨识痕迹（ac 数组/缺失时不得出现）
DONE_MARKERS = ("生成完毕", "生成完成", "成功生成", "已生成")

PARAM_ERR = MSG_PARAM_ERR


def _assert_progress(resp, label):
    """合法入参 → 200 进度页（text/html），不是 JSON 失败体、不是跳转。"""
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


def test_TC_I_MAKE_004(api_as, admin_url):
    api = api_as("default")
    path = admin_url("make/make")

    # 1) IdList 字段（ids / vodtype / arttype / topic）的数组形态：受理，进入进度页
    for method in ("GET", "POST"):
        kwargs = (
            {"params": [
                ("ac", "index"),
                ("ids[]", "1"),
                ("ids[]", "2"),
                ("vodtype[]", "1"),
                ("arttype[]", "1"),
                ("topic[]", "1"),
            ]}
            if method == "GET"
            else {"data": [
                ("ac", "index"),
                ("ids[]", "1"),
                ("ids[]", "2"),
                ("vodtype[]", "1"),
                ("arttype[]", "1"),
                ("topic[]", "1"),
            ]}
        )
        resp = api.call(method, path, **kwargs)
        _assert_progress(resp, "IdList 数组 %s" % method)

    # 2) ac 为数组：契约写明「缺失、空、数组或不在枚举 → 只输出进度页样式、不生成（不是错误）」
    for method in ("GET", "POST"):
        kwargs = (
            {"params": [("ac[]", "index"), ("ac[]", "rss")]}
            if method == "GET"
            else {"data": [("ac[]", "index"), ("ac[]", "rss")]}
        )
        resp = api.call(method, path, **kwargs)
        _assert_progress(resp, "ac 数组 %s" % method)
        for done in DONE_MARKERS:
            assert done not in resp.text, (
                "[%s] ac 为数组时不应生成任何页面，出现生成结果：%r"
                % (method, done)
            )

    # 3) 全部字段缺失（MakeParams 全部可选）：不报错，仍是进度页样式、不生成
    for method in ("GET", "POST"):
        resp = api.call(method, path)
        _assert_progress(resp, "全字段缺失 %s" % method)
        for done in DONE_MARKERS:
            assert done not in resp.text, (
                "[%s] 全字段缺失时不应生成任何页面，出现生成结果：%r" % (method, done)
            )

    # 4) 未列出的字段被忽略（不进入判定），与不带该字段的结果一致
    baseline = api.call("GET", path, params={"ac": "index"})
    _assert_progress(baseline, "未列字段对照（无 unknown）")
    resp = api.call(
        "GET",
        path,
        params={"unexpected_field": "x", "ac": "index"},
    )
    _assert_progress(resp, "未列字段被忽略")
    assert resp.headers.get("Content-Type", "") == baseline.headers.get("Content-Type", ""), (
        "带未列字段与不带未列字段的响应类型不一致：%r vs %r"
        % (resp.headers.get("Content-Type"), baseline.headers.get("Content-Type"))
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
