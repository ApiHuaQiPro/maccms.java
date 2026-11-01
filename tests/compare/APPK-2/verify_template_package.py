#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""APPK-2 C-9：模板包逐文件校验（路径、字节数、git blob SHA-1）+ 目录边界断言。

技术设计 v10 C-9 原文要求：

    输入一个站点根或 `third_party` 目录，对 `template/` 下文件逐个 `git hash-object`，
    与附表 E.3 的 274 行比对（路径、字节数、SHA-1），并断言 `template/` 下只有
    `default_pc`、`default_wap` 两个目录、`default_wap/ads` 不存在。

对照的 V-2 用法（PRE-2 对 `.compare/APPK-2/php-site` 与 `java-site` 各跑一次）。

边界（与 S3-03 派工说明一致）：

* 只读、离线：不联网、不启动 PHP/Java、不发请求、不读取凭据，只读输入目录。
* 本脚本**不判定 AC-1 通过**：它只产出证据。AC-1 ① 的判定在 T3 由测试人员按证据下结论。
* 三种输入形态都支持（`--target`）：
  - `<站点根>`（`template/` 在它下面）——DEC-2 放置后的对照站；
  - `third_party/maccms-template-v2020.1000.1035`（`template/` 在它下面）——DEC-1 的仓库内位置；
  - 直接给 `template/` 目录本身。
* `--manifest` 缺省用入库的 `docs/test/APPK-2/fixtures/template-package-manifest.tsv`
  （由 `build_template_manifest.py` 从 PRD 附表 E.3 生成）；也可以给 PRD 附表 E 的 Markdown，
  由 `--prd` 直接现算，避免清单副本被手改。
* `git hash-object` 用 Git 的 blob 算法在 Python 里算（`sha1("blob <len>\\0" + bytes)`），
  与本机 `git hash-object` 逐字节一致；`--use-git` 可以改用真的 `git hash-object` 交叉验证。
* `--expect-synthetic`：给 `make_synthetic_package.py` 生成的夹具用，只校验“按夹具清单一致 +
  目录边界”，**不**把夹具当成附表 E 的 274 行，也不因文件数不是 274 而报错。

返回码：

    0  全部通过（文件集合、字节数、SHA-1、目录边界都符合输入清单）
    1  用法或输入错误
    2  比对发现不符（**这是缺陷/环境问题信号，不是脚本崩溃**）
    3  脚本自身异常
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import subprocess
import sys

DEFAULT_MANIFEST = "docs/test/APPK-2/fixtures/template-package-manifest.tsv"
EXPECTED_DIRS = ("default_pc", "default_wap")
EXPECTED_TOTAL = 274
EXPECTED_PER_DIR = {"default_pc": 147, "default_wap": 127}

RE_E3_ROW = re.compile(r"^\|\s*(\d+)\s*\|\s*`([^`]+)`\s*\|\s*(\d+)\s*\|\s*`([0-9a-f]{40})`\s*\|\s*$")


def git_blob_sha1_py(data: bytes) -> str:
    h = hashlib.sha1()
    h.update(b"blob %d\0" % len(data))
    h.update(data)
    return h.hexdigest()


def git_blob_sha1_git(path: str) -> str:
    """用真的 `git hash-object` 计算。注意：它会对**工作区**文件应用 `.gitattributes` / `core.autocrlf`
    的过滤器，所以 `core.autocrlf=true` 时 CRLF 文件得到的 blob 是 LF 后的哈希（这正是 RISK-T1 的机制，
    也说明“拿 git hash-object 校验工作区”在有转换的机器上是自相矛盾的）；要用字节口径请加 `--no-filters`。"""
    out = subprocess.run(["git", "hash-object", "--no-filters", "--", path], check=True,
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return out.stdout.decode("ascii").strip()


def find_template_root(target: str) -> str:
    target = os.path.abspath(target)
    if os.path.isdir(os.path.join(target, "template")):
        return os.path.join(target, "template")
    if os.path.basename(target) == "template" and os.path.isdir(target):
        return target
    raise SystemExit("在 %s 下找不到 template/ 目录（也不是 template/ 本身）" % target)


def load_tsv(path: str) -> dict:
    rows = {}
    with open(path, "rb") as fh:
        raw = fh.read()
    if raw.startswith(b"\xef\xbb\xbf"):
        raw = raw[3:]
    for lineno, line in enumerate(raw.decode("utf-8").splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 3:
            raise SystemExit("清单第 %d 行不是三列 TSV：%r" % (lineno, line))
        p, size, sha = (x.strip() for x in parts)
        if p == "path":
            continue
        if not size.isdigit() or len(sha) != 40:
            raise SystemExit("清单第 %d 行格式不对：%r" % (lineno, line))
        rows[p] = {"bytes": int(size), "sha1": sha.lower()}
    if not rows:
        raise SystemExit("清单为空：%s" % path)
    return rows


def load_prd_md(path: str) -> dict:
    with open(path, "rb") as fh:
        raw = fh.read()
    if raw.startswith(b"\xef\xbb\xbf"):
        raw = raw[3:]
    rows = {}
    for line in raw.decode("utf-8").splitlines():
        m = RE_E3_ROW.match(line.strip())
        if m:
            _, p, size, sha = m.groups()
            rows[p] = {"bytes": int(size), "sha1": sha.lower()}
    if not rows:
        raise SystemExit("在 %s 里没有找到附表 E.3 的行" % path)
    return rows


def looks_like_text(data: bytes) -> bool:
    """粗判文本文件：无 NUL 且可按 UTF-8 解码。用于把“二进制里碰巧有 0D 0A”与真正的换行污染分开。"""
    if b"\x00" in data:
        return False
    try:
        data.decode("utf-8")
    except UnicodeDecodeError:
        return False
    return True


def walk_template(template_root: str) -> tuple:
    files = []
    dirs = set()
    anomalies = []
    top = sorted(os.listdir(template_root))
    for name in top:
        full = os.path.join(template_root, name)
        if os.path.isdir(full):
            dirs.add(name)
        else:
            anomalies.append("template/ 下直接放着文件：%s" % name)
    extra = sorted(dirs - set(EXPECTED_DIRS))
    if extra:
        anomalies.append("template/ 下存在预期之外的目录：%s" % ", ".join(extra))
    missing = sorted(set(EXPECTED_DIRS) - dirs)
    if missing:
        anomalies.append("template/ 下缺少目录：%s" % ", ".join(missing))
    if os.path.isdir(os.path.join(template_root, "default_wap", "ads")):
        anomalies.append("default_wap/ads 存在（附表 E.2 规定该目录不存在）")
    for d in sorted(dirs):
        for root, dirnames, filenames in os.walk(os.path.join(template_root, d)):
            dirnames.sort()
            for fn in sorted(filenames):
                full = os.path.join(root, fn)
                rel = os.path.relpath(full, template_root).replace(os.sep, "/")
                files.append(rel)
    files.sort()
    return files, dirs, anomalies


def main(argv: list) -> int:
    ap = argparse.ArgumentParser(description="APPK-2 C-9 模板包逐文件校验（只读、离线）")
    ap.add_argument("--target", required=True,
                    help="站点根 / third_party/maccms-template-v2020.1000.1035 / template 目录")
    ap.add_argument("--manifest", default=DEFAULT_MANIFEST,
                    help="清单 TSV（默认 %s）" % DEFAULT_MANIFEST)
    ap.add_argument("--prd", default=None,
                    help="可选：PRD 附表 E 的 Markdown；给了就用它现算期望，并顺带核对清单副本是否被改")
    ap.add_argument("--use-git", action="store_true",
                    help="用真的 `git hash-object --no-filters` 计算（交叉验证 Python 实现）")
    ap.add_argument("--expect-synthetic", action="store_true",
                    help="输入是合成夹具：只校验清单一致与目录边界，不套用 274/147/127")
    ap.add_argument("--label", default=None,
                    help="报告里显示的目标名（如 php-site / java-site），默认取 target 的 basename")
    args = ap.parse_args(argv)

    label = args.label or os.path.basename(os.path.abspath(args.target))
    try:
        template_root = find_template_root(args.target)
    except SystemExit as exc:
        sys.stderr.write("输入错误：%s\n" % exc)
        return 1

    try:
        expected = load_tsv(args.manifest)
    except OSError as exc:
        sys.stderr.write("清单缺失或不可读：%s\n" % exc)
        return 1

    failures = []
    notes = []

    try:
        autocrlf = subprocess.run(["git", "config", "--get", "core.autocrlf"],
                                  stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        autocrlf_value = autocrlf.stdout.decode("ascii", "replace").strip()
    except OSError:
        autocrlf_value = ""
    if autocrlf_value in ("true", "input"):
        notes.append("core.autocrlf=%s：本脚本按**工作区字节**算 blob SHA-1（`git hash-object --no-filters`），"
                     "否则会被 CRLF 转换改掉哈希（RISK-T1）" % autocrlf_value)

    if args.prd:
        try:
            from_prd = load_prd_md(args.prd)
        except OSError as exc:
            sys.stderr.write("PRD 不可读：%s\n" % exc)
            return 1
        if from_prd != expected:
            failures.append("清单副本 %s 与 PRD 附表 E.3 不一致（%d 行 vs %d 行）"
                            % (args.manifest, len(expected), len(from_prd)))
        else:
            notes.append("清单副本与 PRD 附表 E.3 逐行一致（%d 行）" % len(from_prd))
        expected = from_prd

    files, dirs, anomalies = walk_template(template_root)
    failures.extend(anomalies)

    if not args.expect_synthetic:
        if len(expected) != EXPECTED_TOTAL:
            failures.append("清单行数 %d，期望 %d" % (len(expected), EXPECTED_TOTAL))
        counts = {"default_pc": 0, "default_wap": 0}
        for rel in files:
            pkg = rel.split("/", 1)[0]
            if pkg in counts:
                counts[pkg] += 1
        if len(files) != EXPECTED_TOTAL:
            failures.append("template/ 下文件数 %d，期望 %d" % (len(files), EXPECTED_TOTAL))
        for pkg in EXPECTED_DIRS:
            if counts[pkg] != EXPECTED_PER_DIR[pkg]:
                failures.append("%s 文件数 %d，期望 %d" % (pkg, counts[pkg], EXPECTED_PER_DIR[pkg]))

    present = set(files)
    want = set(expected)
    if any(p.startswith("template/") for p in want):
        # 清单是以 `template/` 为前缀的相对路径（PRD 附表 E.3 就是这种写法），比对前去掉前缀；
        # 报告里仍用清单原文，便于和 PRD 逐行对照。
        want = {p[len("template/"):] if p.startswith("template/") else p for p in want}
    for rel in sorted(want - present):
        failures.append("清单有、目标里没有：template/%s" % rel)
    for rel in sorted(present - want):
        failures.append("目标里有、清单没有：template/%s" % rel)

    crlf = []
    checked = 0
    for rel in files:
        full = os.path.join(template_root, *rel.split("/"))
        with open(full, "rb") as fh:
            data = fh.read()
        # 只对**文本**文件看 CRLF：PNG/JPG 等二进制里出现 0x0D 0x0A 是正常字节序列，不是换行符。
        # （S3-03 实测：真包里 53 个图片文件含 0D 0A，但字节数与 blob SHA-1 都与附表 E.3 一致，
        #  若把它们当成“CRLF 污染”就会误报。CRLF 风险的判断口径是“字节/哈希不符”，不是“出现 0D 0A”。）
        if b"\r\n" in data and looks_like_text(data):
            crlf.append(rel)
        row = expected.get(rel) or expected.get("template/" + rel)
        if not row:
            continue
        checked += 1
        if len(data) != row["bytes"]:
            failures.append("%s 字节数 %d，清单 %d" % (rel, len(data), row["bytes"]))
        sha = git_blob_sha1_git(full) if args.use_git else git_blob_sha1_py(data)
        if sha != row["sha1"]:
            failures.append("%s blob SHA-1 %s，清单 %s" % (rel, sha, row["sha1"]))
    if crlf and not args.expect_synthetic:
        failures.append("有 %d 个**文本**文件含 CRLF（RISK-T1 / NOIGN-4，说明包被换行转换过或 .gitattributes 规则没生效）：%s"
                        % (len(crlf), ", ".join(crlf[:5])))

    print("目标：%s（%s）" % (label, template_root.replace(os.sep, "/")))
    print("检查：%d 个文件逐个比对；目录：%s" % (checked, ", ".join(sorted(dirs)) or "无"))
    for n in notes:
        print("  说明：" + n)
    if failures:
        for f in failures:
            print("  [MISMATCH] " + f)
        print("结论：MISMATCH（%d 项）——不是 PASS，须交上游处理" % len(failures))
        return 2
    print("结论：OK（路径、字节数、blob SHA-1、目录边界全部符合清单）")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except SystemExit:
        raise
    except Exception as exc:  # noqa: BLE001
        sys.stderr.write("脚本异常：%r\n" % (exc,))
        sys.exit(3)
