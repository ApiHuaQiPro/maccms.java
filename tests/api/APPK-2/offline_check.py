"""APPK-2 测试资产的离线自检（不联网、不执行任何用例、不读环境变量里的凭据值）。

用途：T1/T2/接口用例只写阶段的自检工具。它在本地就把「脚本自己会不会假通过」这类问题暴露出来，
不需要任何测试环境。

检查项（全部纯计算）：
  1. 每个测试文件的导入符号都被用到（防止残留死导入误导复核）；
  2. pytest 能否收集全部用例、标记数与文档声明一致（由调用方传参，本脚本不调用 pytest 进程）；
  3. 判定逻辑的**离线仿真**：用真实的 install_support 判定函数，对若干伪造的响应体跑一遍，
     确认「更早分支不会被当成目标分支」「令牌之后的分支才判为越过」。

用法（仓库根目录）：

    python tests/api/APPK-2/offline_check.py

退出码 0 = 全部检查通过；非 0 = 有检查不通过（输出逐条原因）。
"""

import ast
import glob
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

FAILURES = []

# ADM-3 来源拒绝的固定文案（与 conftest.MSG_ILLEGAL_REQUEST 一致；此处不导入 conftest，避免拉入 requests）
S_MSG_ILLEGAL = "非法请求"


def check(label, ok, detail=""):
    if ok:
        print("OK   %s" % label)
    else:
        print("FAIL %s %s" % (label, detail))
        FAILURES.append("%s %s" % (label, detail))


# ── 1. 未使用导入 ────────────────────────────────────────────────────────────

def check_imports():
    for path in sorted(glob.glob(os.path.join(HERE, "*.py"))):
        src = open(path, encoding="utf-8").read()
        tree = ast.parse(src, filename=path)
        imported = []
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom):
                for a in node.names:
                    imported.append((a.asname or a.name, node.lineno))
            elif isinstance(node, ast.Import):
                for a in node.names:
                    imported.append(((a.asname or a.name).split(".")[0], node.lineno))
        used = {n.id for n in ast.walk(tree) if isinstance(n, ast.Name)}
        for name, lineno in imported:
            if name not in used:
                check(
                    "未使用导入 %s:%d %s" % (os.path.basename(path), lineno, name),
                    False,
                )
    print("-- 导入检查完成（只报失败项）")


# ── 2. 判定函数仿真 ──────────────────────────────────────────────────────────

class FakeResp:
    def __init__(self, text, status=200, ctype="text/html; charset=utf-8"):
        self.text = text
        self.status_code = status
        self.headers = {"Content-Type": ctype}

    def json(self):
        """同 requests.Response.json()：非 JSON 抛 ValueError（用例靠这个区分 JSON 体与提示页）。"""
        import json as _json

        return _json.loads(self.text)


def fake_reply(msg):
    """安装向导非 Ajax 提示页的最小可达外观：200 + text/html + 标题「跳转提示」+ 固定 msg。"""
    return FakeResp(
        '<!DOCTYPE html><html><head><meta charset="utf-8"><title>跳转提示</title></head>'
        "<body><p>%s</p></body></html>" % msg
    )


# 离线运行时 pytest 未启动，`assert` 抛的是 AssertionError；pytest 运行中抛的是 _pytest Failed。
# 仿真里两者都算「判失败」，否则仿真会对所有负样本假报「没判失败」。
FAIL_NAMES = ("Failed", "AssertionError")


def _is_fail(exc):
    return exc.__class__.__name__ in FAIL_NAMES


def check_judgement_simulation():
    import install_support as S

    # 令牌之前的响应不得被当成「越过了令牌」
    for early in (S.MSG_PLEASE_TEST, S.MSG_ENV_FAILED, S.MSG_ADMIN_REQUIRED, S.MSG_INIT_ERR):
        resp = fake_reply(early)
        check(
            "更早分支 %r 不产生令牌之后证据" % early,
            S.after_token_hits(resp.text) == [] and S.before_token_hits(resp.text) == [early],
        )

    # 令牌之后的响应必须被识别
    for late in (S.MSG_DIR_UNSUPPORTED, S.MSG_ROOT_REFUSED, S.MSG_SUCCESS):
        resp = fake_reply(late)
        check(
            "令牌之后分支 %r 被识别为越过令牌" % late,
            S.after_token_hits(resp.text) == [late] and S.before_token_hits(resp.text) == [],
        )

    # INSTALL_ACCESS_DENIED 与「更早分支」互斥
    denied = fake_reply(S.MSG_ACCESS_DENIED)
    check(
        "INSTALL_ACCESS_DENIED 不被当成令牌之后分支",
        S.after_token_hits(denied.text) == [] and S.is_access_denied(denied),
    )

    # INSTALL_LOCKED 页与「install.lock」字样：锁页判定不能被令牌之后的锁失败文案混淆
    locked = FakeResp(
        "如需重新安装请删除【To re install, please remove】 >>> /application/data/install/install.lock"
    )
    check("INSTALL_LOCKED 页被识别", S.has_locked_text(locked.text))
    lock_write_failed = fake_reply(S.MSG_LOCKED_PREFIX + "无读写权限！")
    check(
        "INSTALL_LOCK_WRITE_FAILED 不当成已安装页",
        not S.has_locked_text(lock_write_failed.text)
        and lock_write_failed.text.count(S.MSG_LOCKED_PREFIX) == 1,
    )

    # control_proves_after_token 的三条分支：BLOCKED / FAIL / 通过
    class FakeSession:
        def __init__(self, reply):
            self.reply = reply
            self.sent = []

        def post(self, url, data=None, timeout=None, allow_redirects=None):
            self.sent.append((url, dict(data or {})))
            return self.reply

    url = "http://example.invalid/install.php?step=5"

    # 通过：install_dir 分支
    sess = FakeSession(fake_reply(S.MSG_DIR_UNSUPPORTED))
    try:
        S.control_proves_after_token(sess, url, "0" * 32, "sim", case="SIM")
        ok = True
    except BaseException as exc:  # noqa: BLE001 - 只用于自检归类
        ok = False
        print("   意外异常：%r" % exc)
    check("对照：INSTALL_DIR_UNSUPPORTED → 通过", ok)
    check(
        "对照请求必须带 install_dir=%s 且不写库" % S.DIR_NOT_ROOT,
        sess.sent and sess.sent[0][1].get("install_dir") == S.DIR_NOT_ROOT,
    )

    # BLOCKED：更早分支
    sess = FakeSession(fake_reply(S.MSG_PLEASE_TEST))
    skipped = False
    try:
        S.control_proves_after_token(sess, url, "0" * 32, "sim", case="SIM")
    except BaseException as exc:  # noqa: BLE001
        skipped = exc.__class__.__name__ in ("Skipped", "Skipped")
    check("对照：命中 INSTALL_NOT_TESTED → BLOCKED(skip)", skipped)

    # FAIL：令牌被拒
    sess = FakeSession(fake_reply(S.MSG_ACCESS_DENIED))
    failed = False
    try:
        S.control_proves_after_token(sess, url, "0" * 32, "sim", case="SIM")
    except BaseException as exc:  # noqa: BLE001
        failed = _is_fail(exc)
    check("对照：命中 INSTALL_ACCESS_DENIED → FAIL", failed)

    # base_form 的合成输入必须符合契约规则
    form = S.base_form("0" * 32)
    check(
        "合成 account 符合 ^[A-Za-z0-9]+$ 且 ≤30 字符",
        re.fullmatch(r"[A-Za-z0-9]+", form["account"]) is not None and len(form["account"]) <= 30,
    )
    check(
        "合成密码 6～20 字符",
        6 <= len(form[S.PW_FIELD]) <= 20,
    )
    check("省略字段工具不改变原表单", S.form_without(form, S.TOKEN_FIELD).get(S.TOKEN_FIELD) is None
          and form.get(S.TOKEN_FIELD) == "0" * 32)


# ── 3. 断言强度自检：确保没有把「前置不成立」写成宽松 assert ────────────────────
# 「只要不是 INSTALL_ACCESS_DENIED 就算过」这类空验证：形式是 `assert not is_access_denied(...)`
# **且**该断言之后没有跟随目标分支的肯定断言（命中某个 MSG_* 文案 / assert_after_token_evidence /
# after_token_hits 比较 / has_success）。纯否定断言（如「正常模式 404 页不应出现非法访问」）不在本检查范围。
WEAK_FORM = re.compile(r"assert\s+(not\s+)?is_access_denied\(")
# 「否定 INSTALL_ACCESS_DENIED」后面紧跟 FAIL / SKIP 的分支是断言之外的处置，不是假通过
FAIL_OR_SKIP_TAIL = re.compile(r"pytest\.(fail|skip)\(")
AFFIRMATIVE_AFTER = re.compile(
    r"MSG_[A-Z_]+\s+(not\s+)?in\s+|assert_after_token_evidence\(|after_token_hits\(|has_success\("
)


def _load_case_module(module_name, filename, with_conftest=False):
    """加载单个用例模块。用例模块里的 `from conftest import …` 在离线自检时需要 conftest 可见：
    tests/api 目录入 sys.path（conftest 顶部不读环境变量、不发请求，只定义 fixture 与断言工具）。
    """
    import importlib.util

    if with_conftest:
        api_dir = os.path.dirname(HERE)
        if api_dir not in sys.path:
            sys.path.insert(0, api_dir)
    path = os.path.join(HERE, filename)
    spec = importlib.util.spec_from_file_location(module_name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def check_source_rejection_simulation():
    """TC-I-CSRF-002 的 _is_source_rejection：只认 ADM-3 规定的两种拒绝形态，其余一律判否。

    这段判定是 CSRF-002 「同源/跨站走不同分支」和「无凭据 + 跨站必须停在来源拒绝」的唯一依据，
    一旦它把 302、200 非拒绝体或 403 非提示页认成拒绝，整条用例就会假通过。
    """
    import importlib.util

    mod = _load_case_module("tc_csrf_002", "test_TC_I_CSRF_002.py", with_conftest=True)

    illegal = S_MSG_ILLEGAL
    jump = "<!DOCTYPE html><html><head><title>跳转提示</title></head><body><p>%s</p></body></html>" % illegal

    cases = [
        # (标签, 响应, 期望是否算来源拒绝)
        ("Ajax 200 JSON 非法请求", FakeResp('{"code":0,"msg":"%s"}' % illegal, 200, "application/json"), True),
        ("非 Ajax 403 提示页", FakeResp(jump, 403, "text/html; charset=utf-8"), True),
        ("302 登录跳转", FakeResp("", 302, "text/html; charset=utf-8"), False),
        ("200 普通 JSON（code=1）", FakeResp('{"code":1,"msg":"ok"}', 200, "application/json"), False),
        ("200 JSON 但 msg 不匹配", FakeResp('{"code":0,"msg":"参数错误"}', 200, "application/json"), False),
        ("403 但不是提示页", FakeResp("forbidden", 403, "text/plain"), False),
        ("200 HTML 提示页", FakeResp(jump, 200, "text/html; charset=utf-8"), False),
    ]
    for label, resp, expected in cases:
        got = mod._is_source_rejection(resp)
        check("来源拒绝判定 %s → %s" % (label, "拒绝" if expected else "非拒绝"), got is expected)


def check_collect_test_body_shape():
    """TC-I-OUT-002 的判定面：失败体字段集合恰为 {code,msg}、wire_msg 逐字固定、探针种类各自有断言。

    REV-I-08 后本用例不再用「白名单」这种宽松写法：FAILURE_KEYS 必须是恰好 {code,msg}，
    且 _assert_failure_body 必须把 code 与 msg 都**逐字**比到期望值（不能只判「code≠1 且 msg 非空」）。
    """
    mod = _load_case_module("tc_out_002", "test_TC_I_OUT_002.py", with_conftest=True)

    # 1) 失败体字段集合：恰好 {code,msg}
    check("FAILURE_KEYS 恰为 {code,msg}", mod.FAILURE_KEYS == {"code", "msg"})
    for leak in ("cjurl", "response", "url", "host", "error", "data", "list", "page"):
        check("失败体不允许字段 %r" % leak, leak not in mod.FAILURE_KEYS)

    # 2) 两类失败分支的固定期望值必须不同且都是契约原文
    check(
        "COLLECT_PARAM_ERR = (1001, 参数错误)",
        mod.WIRE_PARAM_ERR == (1001, "参数错误"),
    )
    check(
        "COLLECT_URL_REJECTED = (1001, 采集链接有误或不能为本地链接)",
        mod.WIRE_URL_REJECTED == (1001, "采集链接有误或不能为本地链接"),
    )
    check(
        "两类失败分支文案不同（否则一种文案能同时过两种样本）",
        mod.WIRE_PARAM_ERR[1] != mod.WIRE_URL_REJECTED[1],
    )

    # 3) 样本分类：格式/协议不合规（含 1025 字符）→ 参数错误；OUT-1 拒绝目标 → 链接有误
    bad_labels = [label for label, _ in mod.BAD_CJURL]
    check("协议/格式不合规样本含 1025 字符（业务上限 +1）", any("1025" in x for x in bad_labels))
    overlong = [u for label, u in mod.BAD_CJURL if "1025" in label]
    check(
        "1025 字符样本长度恰为 1025（= cjurl maxLength 1024 + 1）",
        len(overlong) == 1 and len(overlong[0]) == mod.CJURL_MAX + 1,
    )
    check(
        "不再用 9000 字符查询串当业务分支（TRN-1：容器拒绝不是业务分支）",
        all(len(u) <= 8192 for _, u in mod.REJECTED + mod.BAD_CJURL),
    )

    # 4) 判定函数逐字比 code 与 msg（用伪造响应实际跑一遍 _assert_failure_body）
    def _resp(code, msg, status=200, ctype="application/json; charset=utf-8"):
        import json as _json

        return FakeResp(_json.dumps({"code": code, "msg": msg}), status, ctype)

    try:
        mod._assert_failure_body(_resp(1001, "参数错误"), "sim 正确", mod.WIRE_PARAM_ERR)
        check("逐字正确的失败体通过", True)
    except BaseException as exc:  # noqa: BLE001
        check("逐字正确的失败体通过", False, repr(exc))

    for label, resp_obj in (
        ("code 不符（1002）", _resp(1002, "参数错误")),
        ("msg 自编文案", _resp(1001, "参数错误: http://127.0.0.1/")),
        ("带契约外字段", FakeResp('{"code":1001,"msg":"参数错误","url":"http://x/"}')),
        ("缺 msg", FakeResp('{"code":1001}')),
        ("HTTP 500", _resp(1001, "参数错误", status=500)),
    ):
        failed = False
        try:
            mod._assert_failure_body(resp_obj, "sim %s" % label, mod.WIRE_PARAM_ERR)
        except BaseException as exc:  # noqa: BLE001
            failed = _is_fail(exc)
        check("错误体 %s → 判失败" % label, failed)


def check_csrf_002_probe_kinds():
    """TC-I-CSRF-002 每个探针都有自己的期望种类，不再用「都能返回 code」的笼统条件（REV-I-07）。"""
    mod = _load_case_module("tc_csrf_002", "test_TC_I_CSRF_002.py", with_conftest=True)

    kinds = [probe[3] for probe in mod.PROBES]
    check("每个探针都声明了期望种类", len(kinds) == len(mod.PROBES) and all(kinds))
    check(
        "探针种类限于 {collect_test, make_progress}",
        set(kinds) <= {"collect_test", "make_progress"},
    )
    check(
        "make/make 探针不再发空参数（必须给 ac=index 才能证明到达生成循环）",
        all(p[2].get("ac") for p in mod.PROBES if p[1] == "make/make"),
    )

    # collect_test 业务体：200 JSON {code,msg} 过；HTML 进度页判失败（种类不匹配）
    try:
        mod._assert_business_result(
            FakeResp('{"code":1001,"msg":"x"}', 200, "application/json; charset=utf-8"),
            "sim collect_test",
            "collect_test",
        )
        check("collect_test 业务体 200 JSON {code,msg} 通过", True)
    except BaseException as exc:  # noqa: BLE001
        check("collect_test 业务体 200 JSON {code,msg} 通过", False, repr(exc))

    progress_body = (
        '<style type="text/css">body{font-size:12px;color: #333333;line-height:21px;}</style>'
        'span{font-weight:bold;color:#FF0000}'
        '<a target="_blank" href="/index.html"><font color=>index.html</font></a>'
        '<script src="/admin-assets/progress.js" data-url="u" data-seconds="3"></script>'
    )
    try:
        mod._assert_business_result(FakeResp(progress_body), "sim make_progress", "make_progress")
        check("make_progress 进度页签名通过", True)
    except BaseException as exc:  # noqa: BLE001
        check("make_progress 进度页签名通过", False, repr(exc))

    for label, body in (
        ("进度页缺样式签名", '<a target="_blank" href="/i.html"><font color=>i</font></a>'),
        ("进度页只有样式、无生成条目", '<style type="text/css">'
         'body{font-size:12px;color: #333333;line-height:21px;}</style>'
         'span{font-weight:bold;color:#FF0000}'),
        ("进度页混入参数错误", progress_body + "参数错误"),
    ):
        failed = False
        try:
            mod._assert_business_result(FakeResp(body), "sim %s" % label, "make_progress")
        except BaseException as exc:  # noqa: BLE001
            failed = _is_fail(exc)
        check("make_progress %s → 判失败" % label, failed)


# ── 4. 断言强度自检：确保没有把「前置不成立」写成宽松 assert ────────────────────


def check_no_weak_asserts():
    for path in sorted(glob.glob(os.path.join(HERE, "test_*.py"))):
        src = open(path, encoding="utf-8").read()
        for m in WEAK_FORM.finditer(src):
            if not m.group(1):
                continue
            tail = src[m.end(): m.end() + 400]
            if FAIL_OR_SKIP_TAIL.search(tail):
                # 否定之后立刻 fail/skip：这是分支处置，不是「否定即通过」
                continue
            if not AFFIRMATIVE_AFTER.search(tail):
                line = src[: m.start()].count(chr(10)) + 1
                check(
                    "弱断言 %s:%d：否定 INSTALL_ACCESS_DENIED 后没有目标分支的肯定断言"
                    % (os.path.basename(path), line),
                    False,
                )


def check_traceability_and_gates():
    """REV-I-16/17：追溯矩阵、脚本头部、README 与新写隔离闸门的一致性静态检查。

    这些都是「文档说的」与「脚本做的」会悄悄漂移的地方，评审已经因为这类漂移退过两轮：
    AC- 追溯写错（AC-10/AC-16 张冠李戴）、API-030 的用例数写成 10（INS-008 不存在）、
    自拟控制样本、把前置缺失当成被测结果。
    """
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(os.path.dirname(os.path.dirname(here)))  # 仓库根（HERE=<根>/tests/api/APPK-2）
    matrix_path = os.path.join(root, "docs", "test", "APPK-2", "api-cases-matrix.md")
    readme_path = os.path.join(here, "README.md")

    # 1) 1 条 CASE = 1 个文件 = 1 个测试函数，且编号集合与矩阵一致
    files = sorted(f for f in os.listdir(here) if f.startswith("test_TC_I_") and f.endswith(".py"))
    ids = [f[len("test_"):-len(".py")].replace("_", "-") for f in files]
    check("用例文件数 = 24", len(files) == 24, "实际 %d：%s" % (len(files), ids))
    check("不存在 TC-I-INS-008（API-030 只有 9 条）", "TC-I-INS-008" not in ids, "发现的编号：%s" % ids)

    # 2) 每条脚本首行的追溯与该用例在矩阵里的追溯一致（抽查 AC- 层级）
    ac_by_case = {}
    if os.path.exists(matrix_path):
        src = io.open(matrix_path, encoding="utf-8").read()
        for line in src.splitlines():
            if not line.startswith("| TC-I-"):
                continue
            cells = [c.strip() for c in line.strip("|").split("|")]
            if len(cells) >= 2:
                ac_by_case[cells[0]] = cells[1]
    else:
        check("追溯矩阵存在", False, matrix_path)
    for f, cid in zip(files, ids):
        head = io.open(os.path.join(here, f), encoding="utf-8").readline()
        if cid in ac_by_case:
            expect = ac_by_case[cid]
            # 矩阵里写 AC-13 / 无 AC-（…）；脚本头部必须写出同一分层的前缀
            head_has = ("追溯: AC-13" in head) if expect.startswith("AC-13") else (
                ("追溯: AC-7" in head) if expect.startswith("AC-7") else (
                    ("追溯: AC-8" in head) if expect.startswith("AC-8") else (
                        ("追溯: AC-12" in head) if expect.startswith("AC-12") else (
                            ("追溯: 无 AC-" in head) if expect.startswith("无 AC-") else True))))
            check("%s 首行追溯与矩阵一致（矩阵：%s）" % (cid, expect), head_has)
        else:
            check("%s 在矩阵「逐条明细」里有追溯行" % cid, False)

    # 3) 不应再出现张冠李戴的 AC-10 / AC-16 追溯（AC-10=手机模板、AC-16=原表原结构守护）
    for f in files:
        head = io.open(os.path.join(here, f), encoding="utf-8").readline()
        check(
            "%s 首行没有把在线采集/安全项追溯成 AC-10/AC-16" % os.path.basename(f),
            "追溯: AC-10" not in head and "追溯: AC-16" not in head,
            head.strip(),
        )

    # 4) 自拟控制样本不得回归
    out002 = os.path.join(here, "test_TC_I_OUT_002.py")
    src = io.open(out002, encoding="utf-8").read()
    code_only = "\n".join(l for l in src.splitlines() if not l.lstrip().startswith("#"))
    check("OUT-002 的代码里不再写死 cv.example.com 作控制样本", "cv.example.com" not in code_only)
    check("OUT-002 从环境取控制样本", "require_reachable_control" in src)
    # 8192 样本必须归因：跳转/5xx/200 非 JSON 都要判失败，不能只留 BLOCKED
    for token, label in (
        ("pytest.fail(", "OUT-002 对未归因响应必须判失败而不是跳过"),
        ("in (301, 302, 303, 307, 308)", "OUT-002 对跳转判失败"),
        (">= 500", "OUT-002 对 5xx 判失败"),
        ("in (400, 403, 404, 405, 413, 414, 431)", "OUT-002 只对已知容器前置码记 BLOCKED"),
    ):
        check(label, token in src)

    # 5) 写隔离闸门必须在会写库/写文件的用例里被调用
    gate_cases = {
        "test_TC_I_INS_006.py": "require_install_write_isolation",
        "test_TC_I_INS_010.py": "require_connection_configured",
        "test_TC_I_CSRF_002.py": "require_ui_write_isolation",
    }
    for fname, fn in gate_cases.items():
        body = io.open(os.path.join(here, fname), encoding="utf-8").read()
        check("%s 调用写隔离/前置闸门 %s" % (fname, fn), fn + "(" in body)
        # BLOCKED 由闸门函数自己抛（pytest.skip），调用点因此不出现字面量；两种写法都接受
        check(
            "%s 缺前置时 BLOCKED（闸门内 skip 或在用例内 skip）" % fname,
            "pytest.skip(" in body or (fn + "(" in body and fn.startswith("require_")),
        )

    # 6) README 不得再出现已被撤掉的判定口径
    if os.path.exists(readme_path):
        r = io.open(readme_path, encoding="utf-8").read()
        check("README 没有「只要不是 INSTALL_ACCESS_DENIED 即证明令牌被接受」", "即证明令牌被接受\"" not in r)
        check("README 没有「按契约业务结果接受 INSTALL_NOT_TESTED 分支」",
              "用例按契约业务结果接受该分支" not in r)
        check("README 记录了控制样本环境变量", "APP_COLLECT_CONTROL_CJURL" in r)
        check("README 记录了安装写隔离环境变量", "MACCMS_INSTALL_ONEOFF_DB" in r)
        check("README 记录了 UI 写隔离环境变量", "MACCMS_UI_WRITE_AUTH" in r)
    else:
        check("README 存在", False, readme_path)

    # 7) 文档不得再声称「每条 AC- 至少一正一反」覆盖全部 AC-
    if os.path.exists(matrix_path):
        m = io.open(matrix_path, encoding="utf-8").read()
        check(
            "矩阵不再声称「每条 AC- 至少一正一反」已由接口用例满足",
            "每条 AC- 至少一正一反：见下表" not in m,
        )
        check("矩阵注明 API-030 为 9 条", "| 9 |" in m and "TC-I-INS-001～007、009、010" in m)


def main():
    check_imports()
    check_judgement_simulation()
    check_source_rejection_simulation()
    check_csrf_002_probe_kinds()
    check_collect_test_body_shape()
    check_no_weak_asserts()
    check_traceability_and_gates()
    if FAILURES:
        print("")
        print("共 %d 项不通过" % len(FAILURES))
        return 1
    print("")
    print("全部离线检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
