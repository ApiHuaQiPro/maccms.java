# CASE: TC-I-OUT-002 | 接口: GET|POST /collect/test（API-041） | 追溯: AC-13 / OUT-1-9、SEC-NET-1、NET-1
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
# 第 5 轮修订 REV-I-14 / REV-I-15（回应复核项 4）：
#   a) **控制样本改为环境提供，不得自拟**。上一版把 `http://cv.example.com/...`（契约示例里的虚构域名）
#      当控制样本：它永远不可达，于是控制样本无论环境好坏都只会得到 COLLECT_FETCH_FAILED，
#      既证明不了「环境是好的」，也给不了「失败样本的拒绝来自被测逻辑」的区分力。
#      现在用 `require_reachable_control` 从 `APP_COLLECT_CONTROL_CJURL`（变量名登记在 MULTICA.md §5，
#      值由人工配置）取环境侧声明为本环境可控、可用、有授权的合法采集地址；未配置 → BLOCKED。
#      控制样本返回取数/解析失败 → BLOCKED（环境出网不可用）；返回其它业务失败 → FAIL（实现拒绝了环境声明的合法地址）。
#   b) **8192 字符样本必须归因，不许静默跳过**。上一版是「200 JSON → 断言，否则一律 BLOCKED」，
#      于是 302 登录跳转、应用 5xx、非 JSON 200 都被当成「容器拒绝」而跳过——应用缺陷与鉴权问题被
#      伪装成环境问题。现在按四类归因：(a) 200 JSON 业务体 → 断言 COLLECT_PARAM_ERR；
#      (b) 301/302/303/307/308 → FAIL（会话失效/鉴权重定向，与容器上限无关）；
#      (c) 5xx 或 200 非 JSON → FAIL（应用缺陷）；
#      (d) 400/403/404/405/413/414/431 → BLOCKED（容器/网关在应用之前拒绝，写明归因依据）；
#      其它未归因响应 → FAIL（不归成环境问题跳过）。
#   c) **跨位置对照的表述收敛**：契约的 POST operation 声明的是「同一位置」的 GET/POST 等价，
#      跨位置（查询串 ↔ 表单）只是 ADM-5 合并规则下「参数位置不同、业务结论相同」的对照，不是等价性本身。
#      文档与实现里的两处措辞已按此改。
#
# 前置：默认角色会话 + 目标 `x-permission: collect/test`；不需要测试环境存在该采集链接（全部样本都期望业务失败），
#   但环境必须提供一个可控的合法采集对照地址（`APP_COLLECT_CONTROL_CJURL`），否则对照组不成立 → BLOCKED。
# 说明：本条不写库、不下载图片、不写断点缓存（CSRF 分类 C，x-idempotency「幂等（只读取数）」）。
#   失败样本全部使用保留域 / 回环 / 私网地址，不会真正连到任何外部目标；唯一的外连是控制样本。

import os
import sys

import pytest

# 同目录共享模块 install_support.py（只做前置与断言工具，不执行用例）。
_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

from conftest import (
    MSG_COLLECT_FETCH_FAILED,
    MSG_COLLECT_PARSE_FAILED,
    MSG_COLLECT_URL_REJECTED,
    MSG_PARAM_ERR,
    assert_error_body_redacted,
    assert_no_internal_leak,
)
from install_support import require_reachable_control

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

# 合法控制样本：**由环境提供**，不自拟（REV-I-14）。
# `require_reachable_control` 从 `APP_COLLECT_CONTROL_CJURL` 取一个本测试环境可控、有授权、且必然被受理的
# 合法采集地址；变量名登记在 MULTICA.md §5，值由人工配置。未配置 → BLOCKED。
# 上一版直接写死 `http://cv.example.com/…`（契约示例里的虚构域名）：它**永远不可达**，于是「控制样本」
# 无论环境好坏都只会得到 COLLECT_FETCH_FAILED，根本不能证明「环境是好的、失败样本的拒绝来自被测逻辑」。


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
    # 数组样本用回环地址：无论实现是否把它当数组，结论都只能是 COLLECT_PARAM_ERR，
    # 不在「本该失败」的样本里引入真实可用的外部依赖。
    array_sample = REJECTED[0][1]
    data = [("cjurl[]", array_sample), ("ac", "list")]
    resp = api.call("POST", path, data=data)
    _assert_failure_body(resp, "POST cjurl 数组", WIRE_PARAM_ERR, array_sample)

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

    # ── 5) 合法控制样本（**环境提供**，REV-I-14）：证明 1～3 的失败不是「环境坏了」的假通过 ─────────
    # 控制样本必须是本测试环境可控、有授权、且必然被受理的合法采集地址（APP_COLLECT_CONTROL_CJURL，
    # 变量名登记在 MULTICA.md §5）。未配置 → BLOCKED，不得用「任意 1001 + 非空 msg」或虚构域名顶替：
    # 虚构域名永远不可达，那样得到的 COLLECT_FETCH_FAILED 与环境好坏无关，对照失去意义。
    control_url = require_reachable_control("OUT-002")
    ctrl = api.call("GET", path, params={"cjurl": control_url, "ac": "list"})
    assert ctrl.status_code == 200, (
        "控制样本（环境登记的合法采集地址）必须是 HTTP 200 业务体，实际 %s——若这里都不是 200，"
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
    # 控制样本的职责是「证明环境与权限链路是通的」：它若是 COLLECT_FETCH_FAILED / COLLECT_PARSE_FAILED，
    # 说明测试环境的出网或目标不可用，那本条的失败样本也无法区分「逻辑正确」与「环境坏了」→ BLOCKED。
    if ctrl_body.get("code") != 1:
        msg = ctrl_body.get("msg") or ""
        if isinstance(msg, str) and msg:
            assert_error_body_redacted(msg, control_url, "控制样本 msg")
        if msg == MSG_COLLECT_FETCH_FAILED or msg in MSG_COLLECT_PARSE_FAILED:
            pytest.skip(
                "BLOCKED：控制样本（环境登记的合法采集地址）返回了取数/解析失败（code=%r msg=%r），"
                "说明测试环境的出网或目标不可用；此时失败样本的拒绝无法与「环境坏了」区分，"
                "本条不作结论。需要环境侧提供一个本环境可访问的合法采集地址"
                % (ctrl_body.get("code"), ctrl_body.get("msg"))
            )
        # 其它失败文案（如 1001 参数错误）是真实的实现问题：环境说这个地址合法，实现却拒了它 → FAIL
        pytest.fail(
            "控制样本（环境登记的合法采集地址）被业务层拒绝：code=%r msg=%r。"
            "环境侧声明该地址可控且可用，实现不应拒绝或取数失败" % (ctrl_body.get("code"), ctrl_body.get("msg"))
        )
    assert_no_internal_leak(ctrl.text)

    # ── 6) 8192 字符：**必须归因**，不许静默跳过（REV-I-15）──────────────────────────────────
    # 契约 TRN-1：请求行/请求头超过容器上限时由容器在应用之前拒绝，属部署层行为；单值 8192 正好是
    # PhpText 的字符上限，超过它才按该字段的「不合规 / 超长」分支处理。
    # 上一版只有「200 JSON → 断言；否则 → BLOCKED」，任何非 JSON 响应（包括 302 登录跳转、应用 5xx）
    # 都被归成「容器拒绝」而跳过——那会把应用缺陷与鉴权问题**伪装成环境问题**。现在按四类归因：
    #   (a) 200 JSON 业务体           → 断言 COLLECT_PARAM_ERR（合格，业务分支）；
    #   (b) 302/303（跳转）           → **FAIL**：这是会话失效/鉴权重定向，与容器上限无关，属测试环境与用例前置不一致；
    #   (c) 5xx 或 JSON 但断言不过   → **FAIL**：应用内部错误，不是容器行为；
    #   (d) 4xx（413/414/431/400/403…）或连接被关 → 才是容器/网关在应用之前拒绝 → BLOCKED（写明归因依据）。
    huge = "http://93.184.216.34/" + ("a" * (8192 - len("http://93.184.216.34/")))
    assert len(huge) == 8192
    resp = api.call("POST", path, data={"cjurl": huge, "ac": "list"})
    ctype = resp.headers.get("Content-Type") or ""
    is_json = "application/json" in ctype
    if resp.status_code == 200 and is_json:
        _assert_failure_body(resp, "POST cjurl 8192 字符", WIRE_PARAM_ERR, huge)
    else:
        assert huge not in resp.text, "[POST cjurl 8192 字符] 非业务响应回显了整段入参"
        assert_no_internal_leak(resp.text)

        if resp.status_code in (301, 302, 303, 307, 308):
            pytest.fail(
                "[POST cjurl 8192 字符] 得到 %s 跳转（Location=%r），这是会话失效/鉴权重定向，"
                "与容器请求上限无关：既不能算通过，也不是环境缺失。请先修会话前置后重跑"
                % (resp.status_code, resp.headers.get("Location"))
            )
        if resp.status_code >= 500:
            pytest.fail(
                "[POST cjurl 8192 字符] 应用返回 %s（Content-Type=%r）：8192 字符在 PhpText 上限内，"
                "应由业务层判为 COLLECT_PARAM_ERR，5xx 是应用缺陷而不是容器拒绝"
                % (resp.status_code, ctype)
            )
        if resp.status_code == 200 and not is_json:
            pytest.fail(
                "[POST cjurl 8192 字符] HTTP 200 但不是 JSON（Content-Type=%r）：API-041 的业务结果"
                "恒为 CollectTestResult，返回 HTML 说明落到了错误页或提示页" % ctype
            )
        if resp.status_code in (400, 403, 404, 405, 413, 414, 431):
            pytest.skip(
                "BLOCKED：[POST cjurl 8192 字符] 被容器/网关在应用之前拒绝（HTTP %s，Content-Type=%r），"
                "按契约 TRN-1 这属部署层行为、不是 API-041 的业务分支；业务上限 1024 的分支已由"
                "「长度 1025（上限 +1）」样本覆盖。需要环境侧确认容器请求行上限后重跑"
                % (resp.status_code, ctype)
            )
        pytest.fail(
            "[POST cjurl 8192 字符] 得到未归因的响应（HTTP %s，Content-Type=%r）：既不是业务体、"
            "不是跳转、不是 5xx，也不是已知的容器前置拒绝码，不能归成环境问题跳过"
            % (resp.status_code, ctype)
        )
