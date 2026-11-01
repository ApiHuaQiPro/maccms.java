# CASE: TC-I-LIMIT-001 | 接口: 后台动作类（API-024、API-040、API-043） | 追溯: 无 AC-（TRN-1；T1 §2.14/§4 口径，不计入 AC 分母） | 维度: 边界、异常 | 优先级: P2
# 标题: TRN-1 传输上限：查询串与表单合计超过 1000 个参数 → INPUT_LIMIT_EXCEEDED（HTTP 200、code=0、msg=参数错误），
#       在 ADM-6 与 ADM-1 之后、令牌校验与取锁之前判定，不解析参数、不执行动作
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（SCH-1 TRN-1、INPUT_LIMIT_EXCEEDED）
# 前置: 默认角色管理员有对应权限
# 说明: 本条构造 1001 个参数，耗内存较小；单值超长、数组超项数不走本码（由各字段自身的不合规分支覆盖）

import pytest

from conftest import MSG_PARAM_ERR, assert_json_error

# TRN-1：参数个数上限
MAX_PARAMS = 1000

PATHS = ["make/make", "collect/api", "images/sync"]


@pytest.mark.input_limit
def test_TC_I_LIMIT_001(api_as, admin_url):
    api = api_as("default")

    # 1) 查询串参数超过 1000 个
    over = [("f%d" % i, "1") for i in range(MAX_PARAMS + 1)]
    for rel in PATHS:
        resp = api.call("GET", admin_url(rel), params=over)
        assert resp.status_code == 200, (
            "[%s 查询串] 期望 HTTP 200 业务失败体，实际 %s" % (rel, resp.status_code)
        )
        assert_json_error(resp, MSG_PARAM_ERR)

    # 2) 表单参数超过 1000 个（POST）
    resp = api.call("POST", admin_url("images/sync"), data=over)
    assert resp.status_code == 200, "表单参数超限应为 HTTP 200，实际 %s" % resp.status_code
    assert_json_error(resp, MSG_PARAM_ERR)

    # 3) 查询串与表单合计超过 1000 个（各 600 个，合计 1200）
    half = [("f%d" % i, "1") for i in range(600)]
    resp = api.call(
        "POST", admin_url("images/sync"), params=half, data=[("g%d" % i, "1") for i in range(600)]
    )
    assert resp.status_code == 200, "合计超限应为 HTTP 200，实际 %s" % resp.status_code
    assert_json_error(resp, MSG_PARAM_ERR)
