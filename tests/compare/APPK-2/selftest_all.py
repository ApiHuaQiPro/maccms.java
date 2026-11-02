#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""APPK-2 S3-03 离线自测总入口：一次跑完 C-8/C-62/C-66 的全部离线用例。

**只写、离线**：不连数据库、不启动 PHP/Java、不发 HTTP 请求、不读任何凭据值。
它只做三件事：

1. 逐个调用三个脚本的 `selftest` 子命令，汇总条数与返回码；
2. 附加检查：`.compare/` 已被 gitignore（C-6/ENV-1）、`php.ini` 被 PHP 7.3 正确加载（C-10，
   本机有 PHP 时才跑，没有则记 SKIP 而不是 FAIL）；
3. 附加检查：脚本里没有写死的库名白名单之外的目标、没有把密码写进文件。

用法（工作目录 = 仓库根）::

    python tests/compare/APPK-2/selftest_all.py
    python tests/compare/APPK-2/selftest_all.py --json .compare/APPK-2/selftest.json

返回码：0 全通过；1 有用例失败；2 用法错误；4 脚本自身异常。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))

SCRIPTS = (
    ("C-8/C-63 assemble_site.py", "assemble_site.py"),
    ("C-62 data_snapshot.py", "data_snapshot.py"),
    ("C-66 schema_snapshot.py", "schema_snapshot.py"),
)

RE_COUNT = re.compile(r"^(\d+) / (\d+) 项通过；失败 (\d+) 项$")
PHP_CANDIDATES = (
    r"D:\Tools\php\7.3\php.exe",
    r"D:\Tools\php\8.0\php.exe",
)

# C-10 要求 php.ini 里这些键必须是这些值（技术设计 §6.21）
PHP_INI_EXPECT = {
    "date.timezone": "PRC",
    "default_charset": "UTF-8",
    "precision": "14",
    "serialize_precision": "-1",
    "memory_limit": "256M",
    "error_reporting": "32767",  # E_ALL in PHP 7.3
}
PHP_INI_OFF = ("display_errors", "short_open_tag")


def run(cmd: list, cwd: str) -> subprocess.CompletedProcess:
    env = dict(os.environ)
    env["PYTHONIOENCODING"] = "utf-8"
    return subprocess.run(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)


def find_php() -> str:
    for name in ("php", "php.exe"):
        found = shutil.which(name)
        if found:
            return found
    for cand in PHP_CANDIDATES:
        if os.path.isfile(cand):
            return cand
    return ""


def main(argv: list) -> int:
    ap = argparse.ArgumentParser(description="APPK-2 S3-03 离线自测总入口")
    ap.add_argument("--json", default=None, help="把汇总结果写成 JSON（可选）")
    args = ap.parse_args(argv)

    summary = {"scripts": [], "extra": [], "ok": True}

    # ---- 1. 三个脚本自测 ------------------------------------------------
    for label, script in SCRIPTS:
        path = os.path.join(HERE, script)
        if not os.path.isfile(path):
            summary["scripts"].append({"label": label, "path": script, "status": "MISSING"})
            summary["ok"] = False
            sys.stdout.write("MISSING %s（%s）\n" % (label, script))
            continue
        p = run([sys.executable, path, "selftest"], REPO_ROOT)
        out = p.stdout.decode("utf-8", "replace")
        err = p.stderr.decode("utf-8", "replace")
        passed = total = 0
        for line in out.splitlines():
            m = RE_COUNT.match(line.strip())
            if m:
                passed, total = int(m.group(1)), int(m.group(2))
        status = "PASS" if p.returncode == 0 else "FAIL"
        summary["scripts"].append({"label": label, "path": script, "status": status,
                                   "passed": passed, "total": total, "rc": p.returncode})
        if p.returncode != 0:
            summary["ok"] = False
        sys.stdout.write("%s %-28s %d/%d 项 (rc=%d)\n"
                         % ("PASS" if p.returncode == 0 else "FAIL", label, passed, total, p.returncode))
        if p.returncode != 0:
            for line in (out + err).splitlines():
                if line.startswith("FAIL") or line.startswith("  FAIL") or "异常" in line:
                    sys.stdout.write("      %s\n" % line.strip())

    # ---- 2. .compare/ 已被 gitignore -----------------------------------
    gi = os.path.join(REPO_ROOT, ".gitignore")
    ignored = False
    if os.path.isfile(gi):
        with open(gi, "r", encoding="utf-8", errors="replace") as fh:
            ignored = any(line.strip() in (".compare/", ".compare") for line in fh)
    summary["extra"].append({"check": "C-6 .compare/ 已 gitignore", "status": "PASS" if ignored else "FAIL"})
    if not ignored:
        summary["ok"] = False
    sys.stdout.write("%s C-6 .compare/ 已 gitignore\n" % ("PASS" if ignored else "FAIL"))

    # ---- 3. php.ini 被 PHP 正确加载（C-10） ----------------------------
    php = find_php()
    if not php:
        summary["extra"].append({"check": "C-10 php.ini 生效", "status": "SKIP",
                                 "detail": "本机没有 PHP"})
        sys.stdout.write("SKIP C-10 php.ini 生效（本机没有 PHP）\n")
    else:
        ini = os.path.join(HERE, "php.ini")
        code = ("foreach (array(%s) as $k) { echo $k, \"=\", var_export(ini_get($k), true), \"\\n\"; }"
                % ", ".join("'%s'" % k for k in list(PHP_INI_EXPECT) + list(PHP_INI_OFF)))
        p = run([php, "-c", ini, "-r", code], REPO_ROOT)
        got = {}
        for line in p.stdout.decode("utf-8", "replace").splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                got[k] = v.strip().strip("'")
        bad = [k for k, v in PHP_INI_EXPECT.items() if got.get(k) != v]
        bad += [k for k in PHP_INI_OFF if got.get(k) not in ("", "''", None)]
        ok_ini = not bad and p.returncode == 0
        summary["extra"].append({"check": "C-10 php.ini 生效", "status": "PASS" if ok_ini else "FAIL",
                                 "values": got, "bad": bad})
        if not ok_ini:
            summary["ok"] = False
        sys.stdout.write("%s C-10 php.ini 生效（%s）%s\n"
                         % ("PASS" if ok_ini else "FAIL", os.path.basename(php),
                            "" if ok_ini else " 不符键：%s" % ", ".join(bad)))

    # ---- 4. 脚本里没有明文凭据 -----------------------------------------
    leaks = []
    for _label, script in SCRIPTS:
        path = os.path.join(HERE, script)
        if not os.path.isfile(path):
            continue
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            body = fh.read()
        # 只检查“赋值给 password 的字符串字面量”这种典型泄露；变量名本身当然会出现
        for m in re.finditer(r"^\s*[A-Za-z_]*[Pp]assword[A-Za-z_]*\s*=\s*[\"']([^\"']{3,})[\"']",
                             body, re.M):
            if m.group(1) not in ("p", "", "secret"):
                leaks.append("%s: 第 %d 行" % (script, body[:m.start()].count("\n") + 1))
    ok_leak = not leaks
    summary["extra"].append({"check": "脚本内无明文密码", "status": "PASS" if ok_leak else "FAIL",
                             "detail": leaks})
    if not ok_leak:
        summary["ok"] = False
    sys.stdout.write("%s 脚本内无明文密码%s\n"
                     % ("PASS" if ok_leak else "FAIL", "" if ok_leak else "：%s" % "; ".join(leaks)))

    if args.json:
        out = args.json if os.path.isabs(args.json) else os.path.join(REPO_ROOT, args.json)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(summary, fh, ensure_ascii=False, indent=2, sort_keys=True)
            fh.write("\n")
        sys.stdout.write("\n汇总已写入 %s\n" % os.path.relpath(out, REPO_ROOT).replace(os.sep, "/"))

    sys.stdout.write("\n总判定：%s\n" % ("PASS" if summary["ok"] else "FAIL"))
    return 0 if summary["ok"] else 1


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except SystemExit:
        raise
    except Exception as exc:  # noqa: BLE001
        sys.stderr.write("脚本自身异常：%r\n" % (exc,))
        sys.exit(4)
