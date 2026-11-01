#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 PRD 附表 E（`docs/prd/APPK-2/E-template-package.md`）抽出 E.3 的 274 行，生成入库清单副本。

为什么需要它：`verify_template_package.py`（技术设计 C-9，S3-03 交付）与 `scan_templates.py`（C-65）
都要按「路径 + 字节数 + git blob SHA-1」逐文件比对。把这份清单固定成仓库里的 TSV，比对就不再依赖
PRD 文件是否被搬运；同时 `--check` 可以反复确认副本与 PRD 原文一致（防抄错、防手改）。

用法（工作目录 = 仓库根，PRD 附表 E 在 `--prd` 指定路径上，可以是检出后的文件或 `git show` 导出的临时文件）::

    python tests/compare/APPK-2/build_template_manifest.py \
        --prd <附表 E 文件> \
        --out docs/test/APPK-2/fixtures/template-package-manifest.tsv

    python tests/compare/APPK-2/build_template_manifest.py \
        --prd <附表 E 文件> --check docs/test/APPK-2/fixtures/template-package-manifest.tsv

返回码：0 = 一致/生成成功；1 = 输入错误；3 = `--check` 发现不一致（差异逐条打印）。
本脚本不联网、不读取模板包本体、不执行任何模板代码。
"""

from __future__ import annotations

import argparse
import io
import re
import sys

RE_E3_ROW = re.compile(r"^\|\s*(\d+)\s*\|\s*`([^`]+)`\s*\|\s*(\d+)\s*\|\s*`([0-9a-f]{40})`\s*\|\s*$")
HEADER = "# APPK-2 附表 E.3 模板包清单副本：path<TAB>bytes<TAB>git blob SHA-1（274 行）\n" \
         "# 由 tests/compare/APPK-2/build_template_manifest.py 从 PRD 附表 E 生成，勿手改；\n" \
         "# 改动必须回到 PRD 与官方提交，并走 PRD 版本 +1（BR-11）。\n"


def read_text(path: str) -> str:
    if path == "-":
        return sys.stdin.read()
    with open(path, "rb") as fh:
        raw = fh.read()
    if raw.startswith(b"\xef\xbb\xbf"):
        raw = raw[3:]
    return raw.decode("utf-8")


def parse(prd_text: str) -> list:
    rows = []
    for line in prd_text.splitlines():
        m = RE_E3_ROW.match(line.strip())
        if m:
            idx, path, size, sha = m.groups()
            rows.append((int(idx), path, int(size), sha.lower()))
    rows.sort(key=lambda r: r[0])
    return rows


def render(rows: list) -> str:
    out = io.StringIO()
    out.write(HEADER)
    out.write("path\tbytes\tblob_sha1\n")
    for _, path, size, sha in rows:
        out.write("%s\t%d\t%s\n" % (path, size, sha))
    return out.getvalue()


def main(argv: list) -> int:
    ap = argparse.ArgumentParser(description="生成/校验 APPK-2 模板包清单副本（附表 E.3）")
    ap.add_argument("--prd", required=True, help="PRD 附表 E 的 Markdown 文件（或 - 表示 stdin）")
    ap.add_argument("--out", default=None, help="输出 TSV 路径")
    ap.add_argument("--check", default=None, help="校验已有 TSV 与 PRD 是否逐行一致")
    ap.add_argument("--expect-count", type=int, default=274, help="期望行数（默认 274）")
    args = ap.parse_args(argv)

    try:
        rows = parse(read_text(args.prd))
    except OSError as exc:
        sys.stderr.write("读取 PRD 失败：%s\n" % exc)
        return 1
    if not rows:
        sys.stderr.write("在 %s 里没有找到附表 E.3 的行（表头形如 | 序号 | `路径` | 字节数 | `sha1` |）\n" % args.prd)
        return 1
    if len(rows) != args.expect_count:
        sys.stderr.write("附表 E.3 抽到 %d 行，期望 %d 行\n" % (len(rows), args.expect_count))
        return 1
    idx = [r[0] for r in rows]
    if idx != list(range(1, len(rows) + 1)):
        sys.stderr.write("附表 E.3 的序号不连续：期望 1..%d\n" % len(rows))
        return 1

    content = render(rows)
    if args.out:
        with open(args.out, "wb") as fh:
            fh.write(content.encode("utf-8"))
        sys.stdout.write("已写出 %s（%d 行）\n" % (args.out, len(rows)))

    if args.check:
        try:
            current = read_text(args.check)
        except OSError as exc:
            sys.stderr.write("读取清单副本失败：%s\n" % exc)
            return 1
        if current == content:
            sys.stdout.write("清单副本与 PRD 附表 E.3 逐行一致（%d 行）\n" % len(rows))
            return 0
        a = current.splitlines()
        b = content.splitlines()
        diffs = []
        for i in range(max(len(a), len(b))):
            x = a[i] if i < len(a) else "<缺行>"
            y = b[i] if i < len(b) else "<多行>"
            if x != y:
                diffs.append("第 %d 行：副本=%r PRD=%r" % (i + 1, x, y))
        sys.stderr.write("清单副本与 PRD 不一致，差异 %d 处：\n" % len(diffs))
        for d in diffs[:20]:
            sys.stderr.write("  " + d + "\n")
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
