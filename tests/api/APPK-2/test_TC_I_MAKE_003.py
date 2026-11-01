# CASE: TC-I-MAKE-003 | 接口: GET /make/make（API-024） | 追溯: AC-7 / MAKE_PARAM_ERR | 维度: 异常、边界 | 优先级: P1
# 标题: MakeParams 不合规取值都到达 MAKE_PARAM_ERR（参数错误）业务分支，不生成文件、不返回 400
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（MakeParams 各字段 x-business-rule）
# 判定: 每一项独立一个请求，断言同为 HTTP 200 + msg=参数错误（SCH-1：不返回 400）

from conftest import MSG_PARAM_ERR, assert_json_error

# 每一行都对应契约里写明 on-fail: MAKE_PARAM_ERR 的字段规则（MakeParams / IdList / CounterInt）
BAD_CASES = [
    # ps: pattern ^[0-9]{0,4}$、maximum 1000
    ("ps 超过 1000", {"ac": "rss", "ac2": "index", "ps": "1001"}),
    ("ps 非数字", {"ac": "rss", "ac2": "index", "ps": "abc"}),
    ("ps 5 位数字", {"ac": "rss", "ac2": "index", "ps": "12345"}),
    # tab: enum ["", vod, art]
    ("tab 不在枚举", {"ac": "type", "tab": "music"}),
    # jump / ref: enum ["", "0", "1"]
    ("jump 不在枚举", {"ac": "index", "jump": "2"}),
    ("ref 不在枚举", {"ac": "index", "ref": "yes"}),
    # ac2: 不在枚举
    ("ac2 不在枚举", {"ac": "index", "ac2": "unexpected"}),
    # ac2 在总枚举内但不在 rss 的 RSS 集合（index/baidu/google/so/sogou/bing/sm）
    ("rss 的 ac2 不在 RSS 集合", {"ac": "rss", "ac2": "wap"}),
    ("rss 的 ac2 为 day", {"ac": "rss", "ac2": "day"}),
    # IdList: item-pattern ^[0-9]{1,10}$、maxItems 500、maxLength 2048
    ("ids 含非数字项", {"ac": "info", "ids": "1,2,x"}),
    ("ids 单项超过 10 位", {"ac": "info", "ids": "12345678901"}),
    ("ids 超过 500 项", {"ac": "info", "ids": ",".join(str(i) for i in range(1, 502))}),
    (
        "ids 串长超过 2048",
        {"ac": "info", "ids": ",".join("12345678" for _ in range(300))},
    ),
    # label: maxItems 200、maxLength 2048、item-pattern ^[A-Za-z0-9_-]{1,64}$
    ("label 项含路径分隔符", {"ac": "label", "label": "../etc/passwd"}),
    ("label 项超过 64 字符", {"ac": "label", "label": "a" * 65}),
    ("label 超过 200 项", {"ac": "label", "label": ",".join("f%d" % i for i in range(201))}),
    # CounterInt: pattern ^[0-9]{0,7}$
    ("num 超过 7 位", {"ac": "index", "num": "12345678"}),
    ("num 非数字", {"ac": "index", "num": "abc"}),
    ("start 非数字", {"ac": "index", "start": "-1"}),
    # 数组形态：写明「数组 → MAKE_PARAM_ERR」的字段
    ("tab 为数组", {"ac": "type", "tab[]": "vod"}),
    ("ps 为数组", {"ac": "rss", "ac2": "index", "ps[]": "5"}),
]


def test_TC_I_MAKE_003(api_as, admin_url):
    api = api_as("default")
    path = admin_url("make/make")
    for label, params in BAD_CASES:
        resp = api.call("GET", path, params=params)
        assert resp.status_code == 200, (
            "[%s] SCH-1 要求不返回 400，实际 %s" % (label, resp.status_code)
        )
        # 后台动作类失败体：code=0 + msg=参数错误
        assert_json_error(resp, MSG_PARAM_ERR)
