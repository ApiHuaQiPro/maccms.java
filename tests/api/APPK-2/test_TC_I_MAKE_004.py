# CASE: TC-I-MAKE-004 | 接口: GET|POST /make/{makeAction}（API-024） | 追溯: AC-7 / SCH-1 | 维度: 边界 | 优先级: P2
# 标题: 数组形态按字段声明分流：IdList 字段受理数组；ac 为数组只是不生成（不是错误）；全部字段缺失不报错
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（SCH-1 + MakeParams 各字段说明）
# 判定: 只断言「不是传输层 400/500」以及 ac 数组时的明确行为；具体生成结果不在本条判定


def test_TC_I_MAKE_004(api_as, admin_url):
    api = api_as("default")
    path = admin_url("make/make")

    # 1) IdList 字段（ids / vodtype / arttype / topic）的数组形态：受理，不 400/500
    resp = api.call(
        "GET",
        path,
        params=[
            ("ac", "info"),
            ("ids[]", "1"),
            ("ids[]", "2"),
            ("vodtype[]", "1"),
            ("arttype[]", "1"),
            ("topic[]", "1"),
        ],
    )
    assert resp.status_code not in (400, 500), (
        "IdList 字段的数组形态不应产生传输层 400/500，实际 %s" % resp.status_code
    )

    # 2) ac 为数组：契约写明「缺失、空、数组或不在枚举 → 只输出进度页样式、不生成（不是错误）」
    resp = api.call("POST", path, data=[("ac[]", "index"), ("ac[]", "rss")])
    assert resp.status_code not in (400, 500), (
        "ac 数组不应产生 400/500，实际 %s" % resp.status_code
    )
    if resp.headers.get("Content-Type", "").startswith("application/json"):
        body = resp.json()
        assert body.get("msg") != "参数错误", "ac 数组不应判为参数错误（契约写明不是错误）"

    # 3) 全部字段缺失（MakeParams 全部 required: false）：不 400/500
    resp = api.call("GET", path)
    assert resp.status_code not in (400, 500), (
        "全部字段缺失时不应 400/500，实际 %s" % resp.status_code
    )

    # 4) 未列出的字段被忽略，不进入判定（不应因此报参数错误）
    resp = api.call("GET", path, params={"unexpected_field": "x", "ac": "index"})
    assert resp.status_code not in (400, 500), (
        "未列出的字段不应导致 400/500，实际 %s" % resp.status_code
    )
