#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""APPK-2 S3-03 测试资产自测：不连接真模板包、不执行 HTTP、不读凭据，只验证三个脚本本身。

为什么需要：S3-03 阶段真模板包（S3-01）还没入库，S3-03 又不允许发请求。如果只把脚本写完就交，
没人能证明它在**真包上出错时会不会报错**。这里用 `make_synthetic_package.py` 造合成夹具，
对每个脚本各跑一组「应当通过」和「应当报出具体问题」的用例。

这些用例只证明工具行为，不产生任何 AC- 的验收证据；真包上的结论要在 S3-05 用真包跑出来。

用法（工作目录 = 仓库根）::

    python tests/compare/APPK-2/selftest_offline.py            # 临时目录自建自删，不写仓库
    python tests/compare/APPK-2/selftest_offline.py --keep      # 保留临时目录以便观察

返回码：0 = 全部用例符合预期；1 = 有用例不符合预期；3 = 脚本自身异常。
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(HERE))
PY = sys.executable


def run(args: list, cwd: str) -> tuple:
    """执行一条命令，返回 (返回码, stdout+stderr)。用 UTF-8 解码，避免 Windows 控制台编码干扰。"""
    env = dict(os.environ)
    env["PYTHONIOENCODING"] = "utf-8"
    p = subprocess.run([PY] + args, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env)
    return p.returncode, p.stdout.decode("utf-8", "replace")


class Case:
    def __init__(self, name, expect_rc, must_contain=(), must_not_contain=()):
        self.name = name
        self.expect_rc = expect_rc
        self.must_contain = must_contain
        self.must_not_contain = must_not_contain


def main(argv: list) -> int:
    ap = argparse.ArgumentParser(description="APPK-2 S3-03 测试资产离线自测")
    ap.add_argument("--keep", action="store_true", help="保留临时目录")
    args = ap.parse_args(argv)

    tmp = tempfile.mkdtemp(prefix="appk2-s303-")
    results = []
    try:
        fx = os.path.join(tmp, "fx")
        rc, out = run([os.path.join(HERE, "make_synthetic_package.py"), "--out", fx], REPO_ROOT)
        results.append(("生成合成夹具", rc == 0, out))
        template = os.path.join(fx, "template")
        manifest = os.path.join(fx, "manifest.tsv")
        prd_manifest = os.path.join(REPO_ROOT, "docs", "test", "APPK-2", "fixtures",
                                    "template-package-manifest.tsv")

        verify = os.path.join(HERE, "verify_template_package.py")
        scan = os.path.join(HERE, "scan_templates.py")
        build = os.path.join(HERE, "build_template_manifest.py")

        # ---- C-9 校验器 -----------------------------------------------------
        cases = [
            Case("V-夹具按自身清单全部一致（默认口径）", 0, ["结论：OK"]),
            Case("V-夹具按自身清单全部一致（--use-git）", 0, ["结论：OK"]),
            Case("V-根目录形态（target=夹具根，template/ 在其下）", 0, ["结论：OK"]),
        ]
        for c in cases:
            extra = ["--use-git"] if "--use-git" in c.name else []
            target = fx if "夹具根" in c.name else fx
            rc, out = run([verify, "--target", target, "--manifest", manifest,
                           "--expect-synthetic"] + extra, REPO_ROOT)
            results.append((c.name, rc == c.expect_rc and all(s in out for s in c.must_contain), out))

        # 反例 1：改一个字节 → 必须报字节数与 SHA-1 不符
        fxbad = os.path.join(tmp, "fx-bad1")
        shutil.copytree(fx, fxbad)
        with open(os.path.join(fxbad, "template", "default_pc", "html", "index", "index.html"), "ab") as fh:
            fh.write(b"X")
        rc, out = run([verify, "--target", fxbad, "--manifest", manifest, "--expect-synthetic"], REPO_ROOT)
        ok = rc == 2 and "字节数" in out and "blob SHA-1" in out
        results.append(("V-改 1 字节必须报 MISMATCH（含字节数与 SHA-1）", ok, out))

        # 反例 2：多一个 default_wap/ads 目录 → 必须报目录边界
        fxads = os.path.join(tmp, "fx-ads")
        shutil.copytree(fx, fxads)
        os.makedirs(os.path.join(fxads, "template", "default_wap", "ads"))
        rc, out = run([verify, "--target", fxads, "--manifest", manifest, "--expect-synthetic"], REPO_ROOT)
        results.append(("V-default_wap/ads 存在必须报 MISMATCH", rc == 2 and "default_wap/ads 存在" in out, out))

        # 反例 3：多一个预期外目录 → 必须报
        fxdir = os.path.join(tmp, "fx-dir")
        shutil.copytree(fx, fxdir)
        os.makedirs(os.path.join(fxdir, "template", "default_extra"))
        rc, out = run([verify, "--target", fxdir, "--manifest", manifest, "--expect-synthetic"], REPO_ROOT)
        results.append(("V-template/ 下有预期外目录必须报 MISMATCH",
                        rc == 2 and "预期之外的目录" in out, out))

        # 反例 4：包内多一个文件（清单没有）→ 必须报
        fximg = os.path.join(tmp, "fx-extra")
        shutil.copytree(fx, fximg)
        with open(os.path.join(fximg, "template", "default_pc", "extra.txt"), "wb") as fh:
            fh.write(b"x")
        rc, out = run([verify, "--target", fximg, "--manifest", manifest, "--expect-synthetic"], REPO_ROOT)
        results.append(("V-清单外文件必须报 MISMATCH", rc == 2 and "目标里有、清单没有" in out, out))

        # 反例 5：默认口径（非 synthetic）拿 15 个文件的夹具当 274 行 → 必须报文件数不符
        rc, out = run([verify, "--target", fx, "--manifest", manifest], REPO_ROOT)
        results.append(("V-非 synthetic 口径下 15 个文件必须报文件数不符",
                        rc == 2 and "期望 274" in out, out))

        # 反例 6：清单缺失 → 返回 1（用法/输入错误），不能当成 PASS
        rc, out = run([verify, "--target", fx, "--manifest", os.path.join(tmp, "nope.tsv"),
                       "--expect-synthetic"], REPO_ROOT)
        results.append(("V-清单缺失返回 1 而不是 0", rc == 1, out))

        # 反例 7：入参不是模板包 → 返回 1
        rc, out = run([verify, "--target", os.path.join(tmp, "not-a-package"),
                       "--manifest", manifest, "--expect-synthetic"], REPO_ROOT)
        results.append(("V-输入路径不存在返回 1", rc == 1, out))

        # ---- 清单生成器 ------------------------------------------------------
        prd_e = os.path.join(tmp, "E.md")
        with open(prd_e, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("# 假 PRD 附表 E（格式与真附表 E.3 相同，内容是合成行）\n\n")
            fh.write("| 序号 | 路径 | 字节数 | git blob SHA-1 |\n| --- | --- | --- | --- |\n")
            fh.write("| 1 | `template/default_pc/a.html` | 12 | `%s` |\n" % ("a" * 40))
            fh.write("| 2 | `template/default_wap/b.html` | 34 | `%s` |\n" % ("b" * 40))
        out_tsv = os.path.join(tmp, "gen.tsv")
        rc, out = run([build, "--prd", prd_e, "--out", out_tsv, "--expect-count", "2"], REPO_ROOT)
        results.append(("M-生成清单（2 行）", rc == 0 and os.path.exists(out_tsv), out))
        rc, out = run([build, "--prd", prd_e, "--check", out_tsv, "--expect-count", "2"], REPO_ROOT)
        results.append(("M-同一份清单 --check 一致", rc == 0 and "逐行一致" in out, out))
        with open(out_tsv, "ab") as fh:
            fh.write(b"template/default_pc/c.html\t1\t" + b"c" * 40 + b"\n")
        rc, out = run([build, "--prd", prd_e, "--check", out_tsv, "--expect-count", "2"], REPO_ROOT)
        results.append(("M-清单副本被改过 → --check 返回 3", rc == 3 and "不一致" in out, out))
        rc, out = run([build, "--prd", prd_e, "--out", os.path.join(tmp, "x.tsv")], REPO_ROOT)
        results.append(("M-行数不是 274（默认期望）→ 返回 1", rc == 1 and "期望 274" in out, out))

        # ---- 扫描器 ----------------------------------------------------------
        scan_out = os.path.join(tmp, "scan")
        rc, out = run([scan, "--package", template, "--manifest", manifest,
                       "--out-dir", scan_out, "--markdown", os.path.join(scan_out, "draft.md")], REPO_ROOT)
        expect = [
            "E.4-EF-2-无新增 PHP 文件",
            "F.5-规则2-归属",
            "E.4-EF-3-default_pc",
        ]
        results.append(("S-扫描器输出关键检查项且返回 2（夹具不是真包）",
                        rc == 2 and all(e in out for e in expect), out))
        results.append(("S-扫描器产出 JSON 与 Markdown 初稿",
                        os.path.exists(os.path.join(scan_out, "scan-result.json"))
                        and os.path.exists(os.path.join(scan_out, "draft.md")), ""))
        with open(os.path.join(scan_out, "draft.md"), "rb") as fh:
            draft = fh.read().decode("utf-8")
        results.append(("S-Markdown 初稿含“初稿，未完成”声明，不得当作 PRE-5 完成",
                        "初稿，未完成" in draft and "归属未定" in draft, ""))

        # 扫描器必须抓到 by="rnd" 并给出 DATA-2（附表 F.6 已规定），而 date()/mac_day() 保持待归属
        import json  # noqa: PLC0415 —— 只在自测里用
        with open(os.path.join(scan_out, "scan-result.json"), encoding="utf-8") as fh:
            payload = json.load(fh)
        nondet = []
        for f in payload["files"]:
            for item in f["nondeterministic"]:
                nondet.append((f["path"], item["call"], item["owner"]))
        has_rnd = any(call == 'by="rnd"' and owner == "DATA-2" for _, call, owner in nondet)
        has_time_ownerless = any(call in ("date", "mac_day") and owner is None for _, call, owner in nondet)
        results.append(('S-by="rnd" 归属 DATA-2、时钟调用保持待归属（不自动填 EX-）',
                        has_rnd and has_time_ownerless, str(nondet)))

        # 过滤器写法的时间来源必须被抓到：真包里内联 date( 一处也没有，全靠 |date='...',### 与 |mac_day。
        # 这条用例防的是“只按调用名扫 → 得出非确定来源 0 处”的假结论。
        filt = [(p, i["call"], i.get("owner")) for f in payload["files"] for p in [f["path"]]
                for i in f["nondeterministic"] if i.get("form") == "filter"]
        has_date_data = any(call == "|date" and owner == "DATA-1" for _, call, owner in filt)
        has_macday_ownerless = any(call == "|mac_day" and owner is None for _, call, owner in filt)
        results.append(("S-过滤器时间来源：|date='..',### → DATA-1；|mac_day 待归属",
                        has_date_data and has_macday_ownerless, str(filt[:8])))

        # 扫描器必须抓到 {php} 与 <?php 两种写法
        php = [p for f in payload["files"] for p in f["php_code"]]
        kinds = sorted({p["kind"] for p in php})
        results.append(("S-同时识别 {php} 与 <?php", kinds == ["<?php", "{php}"], str(kinds)))

        # 扫描器必须报出 js/ 目录里的 {maccms 命中（EF-1）。
        # 注意夹具本身是“干净”的（与真包一致：js/css/ads 命中 0 个），所以这里必须**主动污染**一份副本，
        # 否则这条用例只验证了“正常的包不报警”，验证不了“脏的包会报警”。
        fxef = os.path.join(tmp, "fx-ef1")
        shutil.copytree(fx, fxef)
        with open(os.path.join(fxef, "template", "default_pc", "js", "app.js"), "ab") as fh:
            fh.write(b'{maccms:vod num="1"}')
        rc, out = run([scan, "--package", os.path.join(fxef, "template")], REPO_ROOT)
        results.append(("S-js 里混入 {maccms 必须报 EF-1 MISMATCH",
                        any(line.startswith("[MISMATCH] E.4-EF-1") for line in out.splitlines()), out))

        # 扫描器入参错误 → 返回 1；脚本异常 → 返回 3（不能和“校验失败 2”混一起）
        rc, out = run([scan, "--package", os.path.join(tmp, "nope")], REPO_ROOT)
        results.append(("S-扫描器输入错误返回 1", rc == 1, out))
    finally:
        if not args.keep:
            shutil.rmtree(tmp, ignore_errors=True)
        else:
            print("保留临时目录：%s" % tmp)

    width = max(len(n) for n, _, _ in results)
    failed = 0
    for name, ok, out in results:
        print(("%-*s  %s" % (width, name, "PASS" if ok else "FAIL")))
        if not ok:
            failed += 1
            for line in out.splitlines()[:20]:
                print("    " + line)
    print("")
    print("自测汇总：%d 项，%d 项 FAIL" % (len(results), failed))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except SystemExit:
        raise
    except Exception as exc:  # noqa: BLE001
        sys.stderr.write("自测脚本异常：%r\n" % (exc,))
        sys.exit(3)
