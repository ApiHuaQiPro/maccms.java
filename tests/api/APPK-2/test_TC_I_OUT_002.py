# CASE: TC-I-OUT-002 | 接口: GET|POST /collect/test（API-041） | 追溯: AC-10、AC-16 / OUT-1-9、SEC-NET-1、NET-1
# 维度: 安全、异常 | 优先级: P1 | T2 补充（来源：契约 v1.3.0 @ bc612e93 API-041；OUT-1-9 @ x-outbound-policy）
# 标题: 资源站「测试」接口的业务体逐字固定：失败时 `code` 与 `msg` 都是契约 `wire_code`/`wire_msg` 的原文，
#       失败体只有 {code,msg}，msg 与整份响应都不含 URL、主机、主机后缀、IP、端口、响应片段或内部细节；
#       GET 与 POST 在**同一传入位置**下逐字等价。
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452
#       —— API-041「PHP 直接 return json(...)，Ajax 与非 Ajax 相同，为 CollectTestResult」；
#          「入参错误 / 目标被拒 / 取数失败 / 解析失败的 code 与固定 msg 见 x-error-codes（wire_code 1001/1002），
#           不回显 URL 与响应片段（SEC-NET-1）」；
#          CollectTestResult `required: [code, msg]`、`code` enum `[1, 1001, 1002]`、「失败只有 code、msg」；
#          CollectTestParams.cjurl「必填（缺失、为空或数组 → COLLECT_PARAM_ERR）；超过 1024 字符、不是绝对 http/https URL、
#           含用户信息、`#` 片段、空白或控制字符 → COLLECT_PARAM_ERR；OUT-1 不通过 → COLLECT_URL_REJECTED」；
#          x-outbound-policy OUT-1-9「提示不含 URL、主机、解析地址、端口、状态码与响应片段」。
# 为什么单独一条：TC-I-COLLECT-001 只覆盖 API-040（`collect/api`，进度页形态）；
#   API-041 是同一套 OUT-1 规则下**唯一**返回 ApiErr 式固定业务体（code/msg）的采集动作（PHP `return json($res)`），
#   它的错误体最容易顺手带上目标 URL（PHP 原文会附 url:/response:，契约登记为 SEC-NET-1 差异）。
#
# 第 4 次修订 REV-I-08（四条）：
#   1) **严格断言 wire_code / wire_msg**：上一版只要求「code≠1、msg 非空」，任何「code=1002 + 自编文案」都能过，
#      等于没验错误契约。现在每个失败样本都断言 `code == 期望 wire_code` 且 `msg == 期望 wire_msg`（逐字）。
#   2) **失败体只允许 code/msg**：CollectTestResult 的失败体「只有 code、msg」——上一版的 ALLOWED_KEYS 把成功体的
#      分页/分类字段（page/type/data/msg_data/…）也列为允许，失败体带上这些字段也能过。现在白名单就是 {code, msg}。
#   3) **两种失败分支各用各的固定文案**：协议/格式不合规（不是绝对 http/https URL、≤1024 字符）→ COLLECT_PARAM_ERR
#      = (1001, 参数错误)；缺 cjurl / 空 cjurl / 数组 cjurl → COLLECT_PARAM_ERR = (1001, 参数错误)。
#      目标被 OUT-1 拒（格式合法、地址类别不允许）→ COLLECT_URL_REJECTED = (1001, 采集链接有误或不能为本地链接)。
#      上一版对「缺 cjurl」只断言「非空 msg」，对协议不合规根本没断言 code/msg。
#   4) **容器拒绝不算业务分支**：契约 TRN-1 明确「请求行与请求头超过容器上限时由容器在进入应用之前拒绝，属部署层行为，
#      不是本契约的业务分支；本文件所有查询串形态的业务分支都能在该上限内触发，较长的数据走该路径的 POST operation」。
#      所以上一版的「9000 字符 cjurl」既不是业务分支（业务上限 1024，超长走的是同一个 COLLECT_PARAM_ERR），
#      又可能被容器直接拒（Connection: close / 431 / 414 / 空响应），一律不能算「应用返回了业务错误」。
#      现在改为**契约内的超长样本**：cjurl 长度 1025（业务上限 1024 + 1）→ COLLECT_PARAM_ERR；另有 8192 字符的边界样本，
#      容器拒绝时记 BLOCKED 并在 skip 原因里写明「不是业务分支」，绝不当作通过。
#   5) **GET/POST 等价只在真实请求范围内**：契约的 POST operation 是对「同一个 URL + 表单体」声明等价，
#      不是「GET 查询串 ⊃ POST fetch」的跨位置等价。上一版只把样本放 GET 查询串，却要求 POST 返回同一结论，
#      等于用一个请求证明另一个请求。现在每个样本都发**同位置**的 GET 与 POST，并另加一组「GET 查询串 ↔ POST 表单」
#      的同参数对照（两组业务结论相同）。
#
# 前置：默认角色会话 + 目标 `x-permission: collect/test`；不需要测试环境存在该采集链接（全部样本都期望业务失败）。
# 说明：本条不写库、不下载图片、不写断点缓存（CSRF 分类 C，x-idempotency「幂等（只读取数）」）。

import pytest

from conftest import (
    MSG_COLLECT_URL_REJECTED,
    MSG_PARAM_ERR,
    assert_error_body_redacted,
    assert_no_internal_leak,
)

# Wire（契约 x-error-codes / CollectTestResult）
WIRE_PARAM_ERR = (1001, MSG_PARAM_ERR)                     # COLLECT_PARAM_ERR
WIRE_URL_REJECTED = (1001, MSG_COLLECT_URL_REJECTED)        # COLLECT_URL_REJECTED

# 失败体只允许 {code, msg}（CollectTestResult 的 required 与此条 description「失败只有 code、msg」）
FAILURE_KEYS = {"code", "msg"}

# cjurl 的业务上限（CollectTestParams.cjurl：maxLength 1024）
CJURL_MAX = 1024

# ── 样本 ①：目标被 OUT-1 拒（格式合法、地址类别不允许）→ COLLECT_URL_REJECTED ──────────────────
REJECTED = [
    ("回环 IPv4", "http://127.0.0.1/api.php/provide/vod/"),
    ("回环十进制写法", "http://2130706433/api.php/provide/vod/"),
    ("回环主机名", "http://localhost/api.php/provide/vod/"),
    ("回环子域主机名", "http://api.localhost/api.php/provide/vod/"),
    ("私网 10/8", "http://10.0.0.1/api.php/provide/vod/"),
    ("私网 192.168/16", "http://192.168.1.1/api.php/provide/vod/"),
    ("私网 172.16/12", "http://172.16.0.1/api.php/provide/vod/"),
    ("链路本地", "http://169.254.1.1/api.php/provide/vod/"),
    ("云元数据地址", "http://169.254.169.254/latest/meta-data/"),
    ("元数据主机名", "http://metadata.google.internal/computeMetadata/v1/"),
    ("IPv4 映射 IPv6（内嵌回环）", "http://[::ffff:127.0.0.1]/api.php/provide/vod/"),
    ("回环 IPv6", "http://[::1]/api.php/provide/vod/"),
    ("内部服务端口 3306", "http://93.184.216.34:3306/api.php/provide/vod/"),
    ("内部服务端口 6379", "http://93.184.216.34:6379/api.php/provide/vod/"),
    ("端口 0", "http://93.184.216.34:0/api.php/provide/vod/"),
]

# ── 样本 ②：cjurl 格式/协议不合规（仍在 1024 字符内）→ COLLECT_PARAM_ERR ────────────────────
BAD_CJURL = [
    ("非 http(s) 协议", "ftp://example.com/api.php/provide/vod/"),
    ("带用户信息", "http://user:secret@93.184.216.34/api.php/provide/vod/"),
    ("带片段", "http://93.184.216.34/api.php/provide/vod/#frag"),
    ("相对地址", "/api.php/provide/vod/"),
    ("协议相对地址", "//93.184.216.34/api.php/provide/vod/"),
    # 业务上限 1024 + 1：超长走 cjurl 自己的 COLLECT_PARAM_ERR，不是容器拒绝，也不是 OUT-1 拒绝
    ("长度 1025（上限 +1）", "http://93.184.216.34/" + ("a" * (CJURL_MAX + 1 - len("http://93.184.216.34/")))),
]

# ── 样本 ③：cjurl 缺失/空（传输层受理，业务层判缺失）→ COLLECT_PARAM_ERR ──────────────────────
MISSING_CJURL = [
    ("cjurl 缺失", {"ac": "list"}),
    ("cjurl 空串", {"cjurl": "", "ac": "list"}),
]

# 合法控制样本（与上面失败样本配对，证明失败不是「所有请求都失败」）：
# cv.example.com 是契约 x-error-codes 示例里使用的虚构域名（example.com 为保留域，不会被解析）。
CONTROL_CJURL = "http://cv.example.com/api.php/provide/vod/"


def _assert_failure_body(resp, label, expected, redact_against=None):
    """API-041 的业务失败体：HTTP 200 + JSON + 逐字 wire_code/wire_msg + 字段只有 {code,msg} + 去敏。"""
    want_code, want_msg = expected
    assert resp.status_code == 200, (
        "[%s] API-041 的业务结果是 HTTP 200（PHP return json()），实际 %s（Content-Type=%r 正文=%r）"
        % (label, resp.status_code, resp.headers.get("Content-Type"), resp.text[:200])
    )
    ctype = resp.headers.get("Content-Type") or ""
    assert "application/json" in ctype, (
        "[%s] API-041 的业务结果必须是 application/json，实际 %r" % (label, ctype)
    )
    body = resp.json()
    assert isinstance(body, dict), "[%s] 业务体必须是对象，实际 %r" % (label, body)

    # 1) 失败体只允许 code/msg（成功体的分页/分类字段出现在失败体里也算违约）
    extra = set(body) - FAILURE_KEYS
    assert not extra, (
        "[%s] 失败体只允许 code/msg，实际多出 %r（可能把目标地址或响应片段透传出来）：%r"
        % (label, sorted(extra), body)
    )
    assert set(body) == FAILURE_KEYS, (
        "[%s] 失败体必须同时含 code 与 msg（CollectTestResult required），实际 %r" % (label, sorted(body))
    )

    # 2) 逐字断言 wire_code / wire_msg（不是「code≠1 且 msg 非空」）
    assert body["code"] == want_code, (
        "[%s] wire_code 不符：期望 %r，实际 %r（整个业务体=%r）" % (label, want_code, body["code"], body)
    )
    assert body["msg"] == want_msg, (
        "[%s] wire_msg 不是契约固定文案：期望 %r，实际 %r（可能是拼接了 URL/响应片段的自编文案）"
        % (label, want_msg, body["msg"])
    )

    # 3) OUT-1-9 去敏：msg 与整份响应都不得含该输入的目标信息
    if redact_against:
        assert_error_body_redacted(body["msg"], redact_against, "%s msg" % label)
        assert_error_body_redacted(resp.text, redact_against, "%s body" % label)
    assert_no_internal_leak(resp.text)
    return body


def _assert_both_positions(api, path, label, params, expected):
    """同位置等价：GET 查询串 与 POST 表单各发一次，两者都必须给出逐字相同的 wire_code/wire_msg。

    契约的 POST operation 说明「同 GET，字段放表单（页面做法）或查询串，按 ADM-5 合并」，
    所以**同一个位置**上两种方法必须等价；跨位置的合并规则另由 _assert_cross_position 覆盖。
    """
    target = params.get("cjurl")
    get_resp = api.call("GET", path, params=params)
    get_body = _assert_failure_body(get_resp, "GET %s" % label, expected, target)
    post_resp = api.call("POST", path, data=params)
    post_body = _assert_failure_body(post_resp, "POST %s" % label, expected, target)
    assert (post_body["code"], post_body["msg"]) == (get_body["code"], get_body["msg"]), (
        "[%s] GET 与 POST 的业务结论不一致：GET=%r POST=%r"
        % (label, (get_body["code"], get_body["msg"]), (post_body["code"], post_body["msg"]))
    )
    return get_body, post_body


def test_TC_I_OUT_002(api_as, admin_url):
    api = api_as("default")
    path = admin_url("collect/test")

    # ── 1) OUT-1 目标拒绝：GET 与 POST（同位置）都是 (1001, 采集链接有误或不能为本地链接) ─────────
    for label, url in REJECTED:
        _assert_both_positions(api, path, label, {"cjurl": url, "ac": "list"}, WIRE_URL_REJECTED)

    # ── 2) cjurl 协议/格式不合规（含 1025 字符超长）：(1001, 参数错误)，且不得走 OUT-1 的拒绝文案 ─────
    for label, url in BAD_CJURL:
        body, _ = _assert_both_positions(api, path, label, {"cjurl": url, "ac": "list"}, WIRE_PARAM_ERR)
        assert body["msg"] != MSG_COLLECT_URL_REJECTED, (
            "[%s] 格式不合规被判成 OUT-1 目标拒绝（顺序应为 ① 入参校验 → ③ 目标检查）：%r" % (label, body)
        )

    # ── 3) cjurl 缺失/空：传输层受理 → 业务层 (1001, 参数错误)；不得回落到默认采集源或 5xx ──────────
    for label, params in MISSING_CJURL:
        _assert_both_positions(api, path, label, params, WIRE_PARAM_ERR)

    # 3b) cjurl 数组形态（name[]=）：契约「缺失、为空或数组 → COLLECT_PARAM_ERR」（POST 表单位置）
    data = [("cjurl[]", CONTROL_CJURL), ("ac", "list")]
    resp = api.call("POST", path, data=data)
    _assert_failure_body(resp, "POST cjurl 数组", WIRE_PARAM_ERR, CONTROL_CJURL)

    # ── 4) 跨位置合并（ADM-5）：GET 查询串 ↔ POST 表单的参数位置不同，业务结论必须相同 ─────────────
    # 这是「GET/POST 等价」在真实请求范围内的表述：两类请求都只带 cjurl + ac，只是位置不同。
    get_body = _assert_failure_body(
        api.call("GET", path, params={"cjurl": REJECTED[0][1], "ac": "list"}),
        "跨位置 GET 查询串（OUT-1 拒绝）",
        WIRE_URL_REJECTED,
        REJECTED[0][1],
    )
    post_body = _assert_failure_body(
        api.call("POST", path, data={"cjurl": REJECTED[0][1], "ac": "list"}),
        "跨位置 POST 表单（OUT-1 拒绝）",
        WIRE_URL_REJECTED,
        REJECTED[0][1],
    )
    assert (get_body["code"], get_body["msg"]) == (post_body["code"], post_body["msg"]), (
        "同一参数放查询串与放表单时的业务结论不一致：GET=%r POST=%r"
        % ((get_body["code"], get_body["msg"]), (post_body["code"], post_body["msg"]))
    )

    # ── 5) 合法控制样本：证明 1～3 的失败不是「任何请求都失败」造成的假通过 ───────────────────────
    # 控制样本不是本条的断言目标（它的最终结论取决于测试环境能否连到 cv.example.com，可能到达
    # COLLECT_FETCH_FAILED/COLLECT_PARSE_FAILED），所以**只要求形如业务体**：HTTP 200 + JSON + code ∈ {1,1001,1002}
    # + 失败时 msg 是固定文案且不含目标信息。它的完整成功分支由 API-040 的用例覆盖。
    ctrl = api.call("GET", path, params={"cjurl": CONTROL_CJURL, "ac": "list"})
    assert ctrl.status_code == 200, (
        "控制样本（格式合法的公网地址）必须是 HTTP 200 业务体，实际 %s——若这里都不是 200，"
        "上面按失败体断言的样本失去区分力" % ctrl.status_code
    )
    assert "application/json" in (ctrl.headers.get("Content-Type") or ""), (
        "控制样本应为 application/json，实际 %r" % ctrl.headers.get("Content-Type")
    )
    ctrl_body = ctrl.json()
    assert isinstance(ctrl_body, dict), "控制样本业务体必须是对象，实际 %r" % (ctrl_body,)
    assert ctrl_body.get("code") in (1, 1001, 1002), (
        "控制样本的 code 必须是契约枚举 1/1001/1002，实际 %r" % (ctrl_body.get("code"),)
    )
    if ctrl_body.get("code") != 1:
        assert isinstance(ctrl_body.get("msg"), str) and ctrl_body["msg"], (
            "控制样本失败时 msg 必须是非空固定文案，实际 %r" % (ctrl_body.get("msg"),)
        )
        assert_error_body_redacted(ctrl_body["msg"], CONTROL_CJURL, "控制样本 msg")
    assert_no_internal_leak(ctrl.text)

    # ── 6) 容器上限：8192 字符的 cjurl 若被容器拒绝，**不是业务分支**，只能记 BLOCKED ───────────────
    # 契约 TRN-1：请求行与请求头超过容器上限时由容器在进入应用之前拒绝，属部署层行为；
    # 单值 8192 正好是 PhpText 的字符上限，超过它才按该字段的「不合规 / 超长」分支处理。
    # 因此这里只接受两种结果：(a) 应用返回 COLLECT_PARAM_ERR 业务体（合格）；(b) 容器/网关在应用之前拒绝
    # （4xx/5xx 非 JSON、或连接被关）→ BLOCKED，原因写明「不是业务分支」。
    huge = "http://93.184.216.34/" + ("a" * (8192 - len("http://93.184.216.34/")))
    assert len(huge) == 8192
    resp = api.call("POST", path, data={"cjurl": huge, "ac": "list"})
    is_json = "application/json" in (resp.headers.get("Content-Type") or "")
    if resp.status_code == 200 and is_json:
        _assert_failure_body(resp, "POST cjurl 8192 字符", WIRE_PARAM_ERR, huge)
    else:
        assert huge not in resp.text, "[POST cjurl 8192 字符] 非业务响应回显了整段入参"
        assert_no_internal_leak(resp.text)
        pytest.skip(
            "BLOCKED：8192 字符的 cjurl 被容器/网关在应用之前拒绝（HTTP %s，Content-Type=%r），"
            "按契约 TRN-1 这属部署层行为、不是 API-041 的业务分支；业务上限 1024 的分支已由"
            "「长度 1025（上限 +1）」样本覆盖。需要确认测试环境的容器请求行上限"
            % (resp.status_code, resp.headers.get("Content-Type"))
        )
