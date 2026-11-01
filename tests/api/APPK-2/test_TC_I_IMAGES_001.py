# CASE: TC-I-IMAGES-001 | 接口: GET|POST /images/sync（API-043） | 追溯: AC-13 / IMAGES_PARAM_ERR | 维度: 异常、边界 | 优先级: P1
# 标题: ImagesSyncParams 不合规取值（tab/col/range/date/opt/page/limit）一律 IMAGES_PARAM_ERR，不查库、不出站
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（ImagesSyncParams 各字段 x-business-rule）
# 前置: 默认角色管理员有 images/sync 权限
# 判定: HTTP 200 + code=0 + msg=参数错误（后台失败体），不返回 400

from conftest import MSG_PARAM_ERR, assert_json_error

# (说明, 字段字典)
BAD = [
    # tab: enum vod/art/topic/actor/role/website
    ("tab 不在枚举", {"tab": "user"}),
    ("tab 为空", {"tab": ""}),
    # col: 1/2
    ("col 不在枚举", {"tab": "vod", "col": "3"}),
    # range: 1/2
    ("range 不在枚举", {"tab": "vod", "range": "3"}),
    # date: ^$|^YYYY-MM-DD$（页面也用 | 分隔）
    ("date 非日期格式", {"tab": "vod", "range": "2", "date": "2026/01/01"}),
    ("date 缺位", {"tab": "vod", "range": "2", "date": "2026-1-1"}),
    ("date 含时间", {"tab": "vod", "range": "2", "date": "2026-01-01 00:00:00"}),
    # opt: 0/1/2/3
    ("opt 不在枚举", {"tab": "vod", "opt": "4"}),
    # page: ^[0-9]{0,6}$
    ("page 非数字", {"tab": "vod", "page": "abc"}),
    ("page 超过 6 位", {"tab": "vod", "page": "1234567"}),
    # limit: ^[0-9]{0,3}$ 且 maximum 100（SEC-IN-1）
    ("limit 超过 100", {"tab": "vod", "limit": "101"}),
    ("limit 非数字", {"tab": "vod", "limit": "ten"}),
    ("limit 4 位数字", {"tab": "vod", "limit": "1000"}),
]


def test_TC_I_IMAGES_001(api_as, admin_url):
    api = api_as("default")
    path = admin_url("images/sync")

    # 1) 合法最小值：page=1、limit=1 是可受理边界（不应判为参数错误）
    resp = api.call("GET", path, params={"tab": "vod", "page": "1", "limit": "1"})
    assert resp.status_code == 200, "合法边界不应为 HTTP %s" % resp.status_code
    if resp.headers.get("Content-Type", "").startswith("application/json"):
        assert resp.json().get("msg") != MSG_PARAM_ERR, (
            "page=1/limit=1 是合法边界，不应判为参数错误"
        )

    # 2) limit=100 是上限，仍受理
    resp = api.call("GET", path, params={"tab": "vod", "limit": "100"})
    assert resp.status_code == 200, "limit=100 不应为 HTTP %s" % resp.status_code
    if resp.headers.get("Content-Type", "").startswith("application/json"):
        assert resp.json().get("msg") != MSG_PARAM_ERR, (
            "limit=100 是上限，不应判为参数错误"
        )

    # 3) 逐项规则（GET 与 POST 行为相同）
    for label, params in BAD:
        for method in ("GET", "POST"):
            resp = api.call(method, path, params=params)
            assert resp.status_code == 200, (
                "[%s %s] SCH-1 要求不返回 400，实际 %s" % (method, label, resp.status_code)
            )
            assert_json_error(resp, MSG_PARAM_ERR)
