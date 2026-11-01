# CASE: TC-I-OUT-002 | 接口: API-041 `collect/test`（GET 与 POST 等价） | 追溯: AC-13 / OUT-1-9 / SEC-NET-1 / NET-1 / NET-3
# 维度: 安全、异常、接口 | 优先级: P1 | T2 补充（来源：契约 v1.3.0 @ bc612e93 API-041；OUT-1-9 @ x-outbound-policy）
# 标题: 资源站「测试」接口：GET 与 POST 等价且都只返回 {code,msg,data} 业务体；
#       任何入参（含被 OUT-1 拒的目标、超大/畸形输入）都不得回显 URL、主机、主机后缀、IP、端口、响应片段或内部细节；
#       失败体的 msg 必须是固定文案（逐字取自语言包），不拼接入参。
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452
#       —— API-041「处理顺序同 API-040 的 ①～⑤；只取第 1 页，不入库、不下载图片、不写断点缓存（CSRF 分类 C）」；
#          「PHP 直接 return json(...)，Ajax 与非 Ajax 相同，为 CollectTestResult」；
#          「入参错误 / 目标被拒 / 取数失败 / 解析失败的 code 与固定 msg 见 x-error-codes，不回显 URL 与响应片段（SEC-NET-1）」；
#          x-outbound-policy OUT-1-9「提示不含 URL、主机、解析地址、端口、状态码与响应片段」。
# 为什么单独一条（T2 说明）：
#   TC-I-COLLECT-001 只覆盖 API-040（`collect/api`）的 OUT-1 目标拒绝，且强制 ac=list 的 B 类路径；
#   API-041 是同一套 OUT-1 规则下**唯一**返回 ApiErr 式固定业务体（code/msg）的采集动作（PHP `return json($res)`），
#   它的错误体最容易顺手带上目标 URL（PHP 原文会附 url:/response:，契约登记为 SEC-NET-1 差异）。
#   而且它的页面提交方式是 POST（admin-assets/collect-info.js:37 的 $.post），GET 只是同 PHP 的 input() 合并结果，
#   两种方法必须返回**同构**的业务体——只测 GET 会漏掉「POST 走了另一条返回分支」这类缺陷。
# 前置: 默认角色会话；不需要测试环境存在该采集链接（本条全部样本都期望业务失败，不依赖外部资源站可达）。

from conftest import MSG_COLLECT_URL_REJECTED, assert_error_body_redacted, assert_no_internal_leak

# 业务体允许出现的字段（PHP Admin 基类 json()：code/msg/data；CollectTestResult 附加分页/分类字段）
ALLOWED_KEYS = {
    "code", "msg", "data",
    # CollectTestResult 的分页与分类字段（成功时才出现，失败时不得附带）
    "page", "pagecount", "total", "limit", "list", "class", "type", "msg_data",
}

# 目标被 OUT-1 拒绝的样本（格式合法、地址类别被拒）：GET 与 POST 都必须返回同一固定 msg
REJECTED = [
    ("回环 IPv4", "http://127.0.0.1/api.php/provide/vod/"),
    ("回环十进制写法", "http://2130706433/api.php/provide/vod/"),
    ("回环主机名", "http://localhost/api.php/provide/vod/"),
    ("回环子域主机名", "http://api.localhost/api.php/provide/vod/"),
    ("私网 10/8", "http://10.0.0.1/api.php/provide/vod/"),
    ("私网 192.168/16", "http://192.168.1.1/api.php/provide/vod/"),
    ("链路本地", "http://169.254.1.1/api.php/provide/vod/"),
    ("云元数据地址", "http://169.254.169.254/latest/meta-data/"),
    ("元数据主机名", "http://metadata.google.internal/computeMetadata/v1/"),
    ("IPv4 映射 IPv6（内嵌回环）", "http://[::ffff:127.0.0.1]/api.php/provide/vod/"),
    ("回环 IPv6", "http://[::1]/api.php/provide/vod/"),
    ("内部服务端口 3306", "http://93.184.216.34:3306/api.php/provide/vod/"),
    ("内部服务端口 6379", "http://93.184.216.34:6379/api.php/provide/vod/"),
    ("端口 0", "http://93.184.216.34:0/api.php/provide/vod/"),
]

# 协议不合规（OUT-1-1）：不是绝对 http/https URL，必须被入参/目标检查拒绝，且不得尝试连接
BAD_SCHEME = [
    ("非 http(s) 协议", "ftp://example.com/api.php/provide/vod/"),
    ("带用户信息", "http://user:secret@93.184.216.34/api.php/provide/vod/"),
    ("带片段", "http://93.184.216.34/api.php/provide/vod/#frag"),
]


def _assert_failed_body(resp, label, url, expect_msg=None):
    """API-041 的业务失败体：HTTP 200 + application/json + code/msg，且按 OUT-1-9 脱敏。"""
    assert resp.status_code == 200, (
        "[%s] API-041 的业务结果必须是 HTTP 200（PHP return json()），实际 %s（Content-Type=%r）"
        % (label, resp.status_code, resp.headers.get("Content-Type"))
    )
    assert "application/json" in (resp.headers.get("Content-Type") or ""), (
        "[%s] API-041 必须是 application/json，实际 %r" % (label, resp.headers.get("Content-Type"))
    )
    body = resp.json()
    assert isinstance(body, dict), "[%s] 业务体必须是对象，实际 %r" % (label, body)
    unexpected = set(body) - ALLOWED_KEYS
    assert not unexpected, (
        "[%s] 业务体出现契约外字段 %r（可能把目标地址/响应片段透传出来）" % (label, sorted(unexpected))
    )
    assert "code" in body, "[%s] 业务体缺少 code：%r" % (label, body)
    assert body.get("code") != 1, (
        "[%s] 这些样本的目标都不可用，不得返回成功（code=1）：%r" % (label, body)
    )
    assert isinstance(body.get("msg"), str) and body["msg"], (
        "[%s] 失败体必须是固定文案（非空字符串），实际 %r" % (label, body.get("msg"))
    )
    if expect_msg is not None:
        assert body["msg"] == expect_msg, (
            "[%s] 固定文案不符：期望 %r，实际 %r" % (label, expect_msg, body["msg"])
        )
    # OUT-1-9：msg 与整段响应都不能含 URL/主机/主机后缀/等价 IP 写法/端口
    assert_error_body_redacted(body["msg"], url, "%s msg" % label)
    assert_error_body_redacted(resp.text, url, "%s body" % label)
    assert_no_internal_leak(resp.text)
    return body


def test_TC_I_OUT_002(api_as, admin_url):
    api = api_as("default")
    path = admin_url("collect/test")

    # ── 1) OUT-1 目标拒绝：GET 与 POST 返回同构的固定失败体 ─────────────────────────
    for label, url in REJECTED:
        params = {"cjurl": url, "ac": "list"}
        get_resp = api.call("GET", path, params=params)
        get_body = _assert_failed_body(get_resp, "GET %s" % label, url, MSG_COLLECT_URL_REJECTED)

        post_resp = api.call("POST", path, data=params)
        post_body = _assert_failed_body(post_resp, "POST %s" % label, url, MSG_COLLECT_URL_REJECTED)

        # 方法等价（契约 API-041：GET 与 POST 行为相同）：code 与 msg 必须逐字相同
        assert post_body.get("code") == get_body.get("code") and post_body.get("msg") == get_body.get("msg"), (
            "[%s] GET 与 POST 的业务结论不一致：GET=%r POST=%r"
            % (label, (get_body.get("code"), get_body.get("msg")), (post_body.get("code"), post_body.get("msg")))
        )

    # ── 2) 协议不合规同样只得到固定失败体，不回显入参 ────────────────────────────
    for label, url in BAD_SCHEME:
        resp = api.call("GET", path, params={"cjurl": url, "ac": "list"})
        _assert_failed_body(resp, "GET %s" % label, url)

    # ── 3) cjurl 缺失（入参错误分支）：固定失败体，不得回落到默认采集源或 500 ──────────
    resp = api.call("GET", path, params={"ac": "list"})
    body = _assert_failed_body(resp, "GET 缺 cjurl", "http://example.com/placeholder")
    assert body["msg"].strip(), "[GET 缺 cjurl] 失败体 msg 不能为空串"

    # ── 4) 超长单值（TRN-1 之外的字段自身分支）：不得 500、不得回显整段入参 ─────────────
    long_url = "http://93.184.216.34/" + ("a" * 9000)
    resp = api.call("GET", path, params={"cjurl": long_url, "ac": "list"})
    assert resp.status_code < 500, (
        "[GET 超长 cjurl] 不应 5xx（字段自身分支应返回固定失败体），实际 %s" % resp.status_code
    )
    if resp.status_code == 200 and "application/json" in (resp.headers.get("Content-Type") or ""):
        _assert_failed_body(resp, "GET 超长 cjurl", long_url)
    else:
        # 非 JSON 形态时至少不能回显入参
        assert long_url not in resp.text, "[GET 超长 cjurl] 响应回显了整段入参"
        assert_no_internal_leak(resp.text)
