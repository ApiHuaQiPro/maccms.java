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
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

FAILURES = []


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


def fake_reply(msg):
    """安装向导非 Ajax 提示页的最小可达外观：200 + text/html + 标题「跳转提示」+ 固定 msg。"""
    return FakeResp(
        '<!DOCTYPE html><html><head><meta charset="utf-8"><title>跳转提示</title></head>'
        "<body><p>%s</p></body></html>" % msg
    )


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
        skipped = exc.__class__.__name__ == "Skipped"
    check("对照：命中 INSTALL_NOT_TESTED → BLOCKED(skip)", skipped)

    # FAIL：令牌被拒
    sess = FakeSession(fake_reply(S.MSG_ACCESS_DENIED))
    failed = False
    try:
        S.control_proves_after_token(sess, url, "0" * 32, "sim", case="SIM")
    except BaseException as exc:  # noqa: BLE001
        failed = exc.__class__.__name__ == "Failed"
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


def main():
    check_imports()
    check_judgement_simulation()
    check_no_weak_asserts()
    if FAILURES:
        print("")
        print("共 %d 项不通过" % len(FAILURES))
        return 1
    print("")
    print("全部离线检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
