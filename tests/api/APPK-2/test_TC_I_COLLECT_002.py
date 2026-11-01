# CASE: TC-I-COLLECT-002 | 接口: GET /collect/api（API-040） | 追溯: AC-13 / COLLECT_PARAM_ERR | 维度: 异常、边界 | 优先级: P1
# 标题: cjurl 缺失/为空/数组/超长/格式不合 → COLLECT_PARAM_ERR；其余字段不合 x-business-rule 也一律 COLLECT_PARAM_ERR，不出站
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（CollectApiParams 各字段 x-business-rule）
# 前置: 默认角色管理员有 collect 权限
# 判定: HTTP 200 + code=0 + msg=参数错误；不返回 HTTP 400（SCH-1）；不含内部细节

from conftest import MSG_PARAM_ERR, assert_json_error

# 合法目标占位：本类用例必须在「格式/字段校验」阶段被拦下，绝不落到出站
OK_URL = "http://cv.example.com/api.php/provide/vod/"

# (说明, 字段字典) —— 除 cjurl 外都带一个格式合法的 cjurl，保证失败原因落在被断言的字段上
BAD = [
    # cjurl 自身规则
    ("cjurl 缺失", {}),
    ("cjurl 为空串", {"cjurl": ""}),
    ("cjurl 超长（1025 字符）", {"cjurl": "http://example.com/" + "a" * 1010}),
    ("cjurl 相对地址", {"cjurl": "/api.php/provide/vod/"}),
    ("cjurl 非 http scheme（file）", {"cjurl": "file:///etc/passwd"}),
    ("cjurl 非 http scheme（gopher）", {"cjurl": "gopher://127.0.0.1:6379/_info"}),
    ("cjurl 非 http scheme（ftp）", {"cjurl": "ftp://example.com/vod.xml"}),
    ("cjurl 带用户信息", {"cjurl": "http://u:p@example.com/vod.xml"}),
    ("cjurl 带片段", {"cjurl": "http://example.com/vod.xml#frag"}),
    ("cjurl 含空白", {"cjurl": "http://example.com/vo d.xml"}),
    ("cjurl 含控制字符", {"cjurl": "http://example.com/vod.xml\x01"}),
    # cjflag: ^$|^[0-9a-f]{32}$
    ("cjflag 非 32 位十六进制", {"cjurl": OK_URL, "cjflag": "abc123"}),
    ("cjflag 大写十六进制", {"cjurl": OK_URL, "cjflag": "A" * 32}),
    # mid: 1/2/8/9/11
    ("mid 不在枚举", {"cjurl": OK_URL, "mid": "3"}),
    # type: 1/2
    ("type 不在枚举", {"cjurl": OK_URL, "type": "3"}),
    # ac: list/cj/cjsel/cjday/cjall/videolist/detail
    ("ac 不在枚举", {"cjurl": OK_URL, "ac": "delete"}),
    # t: ^[0-9]{0,10}$
    ("t 非数字", {"cjurl": OK_URL, "t": "abc"}),
    ("t 超过 10 位", {"cjurl": OK_URL, "t": "1" * 11}),
    # pg / page: ^[0-9]{0,6}$
    ("pg 超过 6 位", {"cjurl": OK_URL, "pg": "1234567"}),
    ("pg 非数字", {"cjurl": OK_URL, "pg": "-1"}),
    ("page 超过 6 位", {"cjurl": OK_URL, "page": "1234567"}),
    # h / rday: ^[0-9]{0,5}$
    ("h 超过 5 位", {"cjurl": OK_URL, "h": "123456"}),
    ("rday 非数字", {"cjurl": OK_URL, "rday": "1d"}),
    # ids: maxItems 500、maxLength 4096、item-pattern ^[A-Za-z0-9_-]{1,32}$
    ("ids 含非法字符", {"cjurl": OK_URL, "ids": "1,2,<x>"}),
    ("ids 单项超过 32 字符", {"cjurl": OK_URL, "ids": "a" * 33}),
    ("ids 超过 500 项", {"cjurl": OK_URL, "ids": ",".join(str(i) for i in range(501))}),
    # wd: maxLength 100
    ("wd 超过 100 字符", {"cjurl": OK_URL, "wd": "w" * 101}),
    # param: maxLength 1024、base64 字母表、解码后 ≤ 768 字节
    ("param 含 base64 以外字符", {"cjurl": OK_URL, "param": "a$b"}),
    ("param 超长", {"cjurl": OK_URL, "param": "A" * 1025}),
    # opt / filter / sync_pic_opt: 0/1/2
    ("opt 不在枚举", {"cjurl": OK_URL, "opt": "3"}),
    ("filter 不在枚举", {"cjurl": OK_URL, "filter": "9"}),
    ("sync_pic_opt 不在枚举", {"cjurl": OK_URL, "sync_pic_opt": "3"}),
    # filter_from: maxLength 1024
    ("filter_from 超长", {"cjurl": OK_URL, "filter_from": "f" * 1025}),
]


def test_TC_I_COLLECT_002(api_as, admin_url):
    api = api_as("default")
    path = admin_url("collect/api")

    # 1) cjurl 数组形态（name[]=）：契约写明「缺失、为空或数组 → COLLECT_PARAM_ERR」
    resp = api.call("GET", path, params=[("cjurl[]", OK_URL), ("ac", "list")])
    assert resp.status_code == 200, "数组形态 cjurl 不应为 HTTP %s" % resp.status_code
    assert_json_error(resp, MSG_PARAM_ERR)

    # 2) 逐项字段规则
    for label, params in BAD:
        resp = api.call("GET", path, params=params)
        assert resp.status_code == 200, (
            "[%s] SCH-1 要求不返回 400，实际 %s" % (label, resp.status_code)
        )
        assert_json_error(resp, MSG_PARAM_ERR)
