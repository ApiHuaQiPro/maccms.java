# CASE: TC-I-COLLECT-004 | 接口: GET|POST /collect/test（API-041） | 追溯: AC-13 / COLLECT_PARAM_ERR | 维度: 异常 | 优先级: P1
# 标题: API-041 入参不合规则 → JSON code=1001 + msg=参数错误；成功体 code 只会是 1/1001/1002，不出现其他 code 与内部细节
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（CollectTestParams、CollectTestResult）
# 前置: 默认角色管理员有 collect 权限

from conftest import MSG_PARAM_ERR

OK_URL = "http://cv.example.com/api.php/provide/vod/"

BAD = [
    ("cjurl 缺失", {}),
    ("cjurl 为空串", {"cjurl": ""}),
    ("cjurl 相对地址", {"cjurl": "/provide/vod/"}),
    ("cjurl 非 http scheme", {"cjurl": "gopher://127.0.0.1:6379/_info"}),
    ("cjurl 带用户信息", {"cjurl": "http://u:p@example.com/vod.xml"}),
    ("cjurl 带片段", {"cjurl": "http://example.com/vod.xml#x"}),
    ("cjurl 超长", {"cjurl": "http://example.com/" + "a" * 1010}),
    # CollectTestParams.ac 只允许 list（与 API-040 的 7 个取值不同）
    ("ac 为 cj（API-041 只允许 list）", {"cjurl": OK_URL, "ac": "cj"}),
    ("cjflag 非 32 位十六进制", {"cjurl": OK_URL, "cjflag": "zz"}),
    ("type 不在枚举", {"cjurl": OK_URL, "type": "3"}),
    ("mid 不在枚举", {"cjurl": OK_URL, "mid": "3"}),
    ("param 含 base64 以外字符", {"cjurl": OK_URL, "param": "%%%"}),
]


def test_TC_I_COLLECT_004(api_as, admin_url):
    api = api_as("default")
    path = admin_url("collect/test")

    # 1) cjurl 数组形态 → COLLECT_PARAM_ERR
    resp = api.call("POST", path, data=[("cjurl[]", OK_URL)])
    assert resp.status_code == 200, "应为 HTTP 200，实际 %s" % resp.status_code
    body = resp.json()
    assert body.get("code") == 1001, "数组形态 cjurl 期望 code=1001，实际 %r" % body.get("code")
    assert body.get("msg") == MSG_PARAM_ERR, "期望 msg=参数错误，实际 %r" % body.get("msg")

    # 2) 逐项字段规则
    for label, params in BAD:
        resp = api.call("POST", path, data=params)
        assert resp.status_code == 200, (
            "[%s] SCH-1 要求不返回 400，实际 %s" % (label, resp.status_code)
        )
        body = resp.json()
        assert body.get("code") == 1001, (
            "[%s] 期望 code=1001，实际 %r" % (label, body.get("code"))
        )
        assert body.get("msg") == MSG_PARAM_ERR, (
            "[%s] 期望 msg=参数错误，实际 %r" % (label, body.get("msg"))
        )
        # 失败体只有 code 与 msg（CollectTestResult：失败只有 code、msg）
        assert set(body.keys()) <= {"code", "msg"}, (
            "[%s] 失败体多出字段：%r" % (label, sorted(body.keys()))
        )
