#!/usr/bin/env python3
"""APPK-12：核对映射表的可检出性（只读）。工作目录 = 仓库根。

    python docs/api/APPK-2/parity-java-map/verify_map.py

检查：
1. site-rows.md + admin-rows.md + api-install-rows.md 的行号合起来恰好是台账 R-001～R-379，各出现一次；
2. 每行的「旧方法」与台账（docs/test/APPK-2/parity-stage-a-rows.md）的控制器文件一致；
3. 表中每个 `相对路径.java:行号` 引用：文件存在于 src/main/java/com/maccms/backend/ 下且行号不超过文件行数；
4. 表中每个 `类名#方法名`：src/main/java 或 src/test/java 中存在该类文件，且文件里有该方法名的声明；
5. 证据状态只取约定值。
任何一项不通过返回 1，并逐条列出。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path.cwd()
HERE = ROOT / "docs/api/APPK-2/parity-java-map"
FILES = ["site-rows.md", "admin-rows.md", "api-install-rows.md"]
LEDGER = ROOT / "docs/test/APPK-2/parity-stage-a-rows.md"
MAIN = ROOT / "src/main/java/com/maccms/backend"
STATES = {"实现+测试", "实现·缺测试", "缺实现", "显式不提供", "间接实现·待核", "非动作（占位行）", "非动作（辅助方法）"}

PATH_REF = re.compile(r"`([\w/]+\.java):(\d+)")
SHORT_REF = re.compile(r"`(\w+\.java):(\d+)")
METHOD_REF = re.compile(r"\b([A-Z]\w+)#(\w+)")


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")  # Windows 控制台默认代码页输出中文会报错
    ledger = {}
    for line in LEDGER.read_text(encoding="utf-8").splitlines():
        if line.startswith("| R-"):
            c = [x.strip() for x in line.strip().strip("|").split("|")]
            ledger[c[0]] = c[2].strip("`")
    classes: dict[str, list[Path]] = {}
    for p in list((ROOT / "src/main/java").rglob("*.java")) + list((ROOT / "src/test/java").rglob("*.java")):
        classes.setdefault(p.stem, []).append(p)
    problems, seen, states = [], {}, {}
    n_path = n_meth = 0
    for name in FILES:
        f = HERE / name
        if not f.exists():
            problems.append(f"缺文件 {name}")
            continue
        for line in f.read_text(encoding="utf-8").splitlines():
            if not line.startswith("| R-"):
                continue
            cells = [x.strip() for x in line.strip().strip("|").split("|")]
            rid = cells[0]
            seen[rid] = seen.get(rid, 0) + 1
            if rid not in ledger:
                problems.append(f"{rid}: 不在台账")
                continue
            if ledger[rid] not in cells[1]:
                problems.append(f"{rid}: 旧方法列 {cells[1]!r} 与台账控制器 {ledger[rid]!r} 不符")
            st = cells[-2] if name == "site-rows.md" else cells[-1]
            if st not in STATES:
                problems.append(f"{rid}: 证据状态 {st!r} 不在约定值内")
            states[st] = states.get(st, 0) + 1
            for m in PATH_REF.finditer(line):
                rel, ln = m.group(1), int(m.group(2))
                if "/" not in rel:
                    cands = classes.get(rel[:-5], [])
                    cands = [c for c in cands if "src/main/java" in c.as_posix()]
                    if len(cands) != 1:
                        problems.append(f"{rid}: 短路径 {rel} 无法唯一定位（{len(cands)} 个候选）")
                        continue
                    p = cands[0]
                else:
                    p = MAIN / rel
                if not p.exists():
                    problems.append(f"{rid}: 文件不存在 {rel}")
                    continue
                n_path += 1
                total = len(p.read_text(encoding="utf-8").splitlines())
                if ln < 1 or ln > total:
                    problems.append(f"{rid}: {rel}:{ln} 超出文件行数 {total}")
            for m in METHOD_REF.finditer(line):
                cls, meth = m.group(1), m.group(2)
                files = classes.get(cls)
                if not files:
                    problems.append(f"{rid}: 类 {cls} 不存在")
                    continue
                decl = re.compile(r"\b(?:void|[\w<>\[\],.?]+)\s+" + re.escape(meth) + r"\s*\(|\b" + re.escape(meth) + r"\s*\(")
                if not any(decl.search(x.read_text(encoding="utf-8")) for x in files):
                    problems.append(f"{rid}: {cls} 中找不到方法 {meth}")
                n_meth += 1
    missing = sorted(set(ledger) - set(seen))
    dup = sorted(k for k, v in seen.items() if v > 1)
    if missing:
        problems.append(f"缺行 {len(missing)}：{', '.join(missing[:20])}{' …' if len(missing) > 20 else ''}")
    if dup:
        problems.append(f"重复行：{', '.join(dup)}")
    print(f"行 {len(seen)} / 台账 {len(ledger)}；路径引用 {n_path} 处、类#方法引用 {n_meth} 处已核对")
    print("证据状态：" + "；".join(f"{k} {v}" for k, v in sorted(states.items())))
    for p in problems:
        print("PROBLEM:", p)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
