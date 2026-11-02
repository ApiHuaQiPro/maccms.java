#!/usr/bin/env python3
"""核对 gap-register.md / op-d-coverage.md 里写成 `R-nnn 端/文件::方法` 的引用，
与 parity-stage-a-rows.md（附表 C 台账）逐行一致。只读，不连库、不发请求。

用法: python check_gap_refs.py <parity-stage-a-rows.md> <文档1> [文档2 ...]
退出码: 0 全部一致；1 有不一致；2 参数/输入错误。
"""
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROW = re.compile(r"^\|\s*(R-\d{3})\s*\|\s*(\w+)\s*\|\s*`([^`]+)`\s*\|\s*`?([^|`]+?)`?\s*\|")
REF = re.compile(r"(R-\d{3})\s+(index|api|admin|install)/([A-Za-z]+\.php)::([\w-]+)")
ANY = re.compile(r"R-(\d{3})")


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    rows = {}
    for line in open(argv[1], encoding="utf-8"):
        m = ROW.match(line)
        if m:
            rows[m.group(1)] = (m.group(2), m.group(3), m.group(4))
    if len(rows) != 379:
        print("ERROR 台账行数 %d != 379" % len(rows))
        return 2
    bad = 0
    checked = 0
    for path in argv[2:]:
        text = open(path, encoding="utf-8").read()
        for m in REF.finditer(text):
            checked += 1
            rid, side, f, meth = m.groups()
            exp = rows.get(rid)
            if exp != (side, f, meth):
                bad += 1
                print("MISMATCH %s: 文档写 %s/%s::%s，台账 %s" % (rid, side, f, meth, exp))
        for m in ANY.finditer(text):
            n = int(m.group(1))
            if not 1 <= n <= 379:
                bad += 1
                print("OUT-OF-RANGE R-%s in %s" % (m.group(1), path))
    print("checked=%d mismatch=%d" % (checked, bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
