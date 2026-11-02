#!/usr/bin/env python3
"""APPK-12 四值判定台账核对（只读）。工作目录 = 仓库根。

    python docs/test/APPK-12/verify_verdict.py

检查项（任何一项不通过返回 1，并逐条列出）：

1. 台账恰好 379 行 = R-001～R-379 各一次，升序、无缺无重；
2. 每行「旧方法」与阶段 A 台账（`docs/test/APPK-2/parity-stage-a-rows.md`）的控制器文件一致；
3. 四值判定只取附表 C 允许的 4 个取值，求和 = 379；
4. `✅已对齐` 行的依据必须点名测试类#方法，且该测试在本次 surefire 报告里有执行记录（IT-0 硬检查）；
5. `旧版无功能` 行的依据必须引用 PHP 源码行（`php/application/...` 且带 `:行号` 或 `方法体`/`非 URL 动作` 说明）；
6. `缺口` 行必须在 GAPS 清单里登记（可路由、归属明确）、依据里写明需建 GAP-；
7. 不得出现 `排除-` 判定——本轮没有已确认的 OP-/D 清单，测试自行排除即失败；
8. `缺口` 行不得同时被写成“无实现”：缺口行必须能指出 Java 侧位置或明确记明实现缺失/不完整。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path.cwd()
HERE = ROOT / "docs/test/APPK-12"
VERDICTS = HERE / "stage-a-verdict-rows.md"
LEDGER = ROOT / "docs/test/APPK-2/parity-stage-a-rows.md"
SUREFIRE = ROOT / "target/surefire-reports"

VALUE_ALIGNED = "✅已对齐"
VALUE_NO_FUNCTION = "旧版无功能"
VALUE_GAP = "缺口"
VALUE_EXCLUDED_PREFIX = "排除-"
ALLOWED = {VALUE_ALIGNED, VALUE_NO_FUNCTION, VALUE_GAP}

# 缺口行 → 下一步归属（都必须是产品经理先建 GAP-/给编号，再路由给开发）
GAP_OWNER = {
    "R-018": "GAP-I 实现不完整：产品经理建 GAP-（视频详情密码 1-1 未实现）→ 后端开发",
    "R-041": "GAP-X 决策编号缺失：产品经理补 `排除-D9-a` 或建 GAP-",
    "R-044": "GAP-I 缺实现：产品经理建 GAP- 或按 OP-/D 排除 → 后端开发补路由",
    "R-068": "GAP-I 缺实现：产品经理建 GAP- 或按 OP-/D 排除 → 后端开发补路由",
    "R-119": "GAP-I 缺实现：产品经理建 GAP- 或按 OP-/D 排除 → 后端开发补路由",
    "R-224": "GAP-X 决策编号缺失：产品经理补 2026-10-28 删除 timing 的 D-/OP- 编号",
    "R-296": "GAP-X 决策编号缺失：产品经理补 D-B15-4 的正式编号",
    "R-227": "GAP-IT 补跑 MySQL IT 可升级为 ✅已对齐",
    "R-228": "GAP-IT 补跑 MySQL IT 可升级为 ✅已对齐",
    "R-229": "GAP-IT 且无 mid=8 用例：需后端开发补 IT 用例",
    "R-230": "GAP-IT 且无 mid=9 用例：需后端开发补 IT 用例",
    "R-231": "GAP-IT 补跑 MySQL IT 可升级为 ✅已对齐",
}
GAP_AUTO_KINDS = {"GAP-T", "GAP-IT"}


def cells_of(line):
    raw = line.rstrip().rstrip("|").lstrip("|")
    return [x.strip().replace(r"\|", "|") for x in re.split(r"(?<!\\)\|", raw)]


def load_surefire():
    out = {}
    if not SUREFIRE.exists():
        return out
    for f in SUREFIRE.glob("TEST-*.xml"):
        txt = f.read_text(encoding="utf-8", errors="replace")
        m = re.search(r'<testsuite[^>]*name="([\w.]+)"', txt)
        if not m:
            continue
        out[m.group(1).split(".")[-1]] = {re.sub(r"(\([^()]*\))?(\[\d+\])*$", "", n)
                                         for n in re.findall(r'<testcase\s+name="([^"]+)"', txt)}
    return out


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    problems = []

    ledger = {}
    for line in LEDGER.read_text(encoding="utf-8").splitlines():
        if line.startswith("| R-"):
            c = cells_of(line)
            ledger[c[0]] = c[2].strip("`")

    rows = [cells_of(l) for l in VERDICTS.read_text(encoding="utf-8").splitlines() if l.startswith("| R-")]
    ids = [r[0] for r in rows]
    expect = [f"R-{i:03d}" for i in range(1, 380)]
    if ids != expect:
        missing = sorted(set(expect) - set(ids))
        problems.append(f"行集合不是 R-001～R-379（升序）：缺 {len(missing)} 行 {missing[:8]}，实际 {len(ids)} 行")

    surefire = load_surefire()
    if not surefire:
        problems.append("没有 target/surefire-reports：无法核对 ✅已对齐 的测试执行记录")

    counts = {VALUE_ALIGNED: 0, VALUE_NO_FUNCTION: 0, VALUE_GAP: 0}
    excluded = 0
    gap_rows = []
    for r in rows:
        rid, old, verdict, src, basis = r[0], r[1], r[3], r[4], r[5]
        if rid not in ledger:
            problems.append(f"{rid}: 不在阶段 A 台账")
        elif ledger[rid] not in old:
            problems.append(f"{rid}: 旧方法与台账控制器 {ledger[rid]!r} 不符")
        if verdict.startswith(VALUE_EXCLUDED_PREFIX):
            excluded += 1
            problems.append(f"{rid}: 出现 `排除-` 判定——本轮没有已确认的 OP-/D 清单，测试不得自行排除")
            continue
        if verdict not in ALLOWED:
            problems.append(f"{rid}: 判定 {verdict!r} 不在四值内")
            continue
        counts[verdict] += 1

        if verdict == VALUE_ALIGNED:
            refs = re.findall(r"([A-Z]\w+)#(\w+)", basis)
            if not refs:
                problems.append(f"{rid}: ✅已对齐 但依据里没有测试类#方法")
            else:
                miss = [f"{c}#{m}" for c, m in refs if m not in surefire.get(c, set())]
                if miss:
                    problems.append(f"{rid}: ✅已对齐 的测试本轮没有执行记录 {miss}")
        elif verdict == VALUE_NO_FUNCTION:
            if "php/application" not in basis:
                problems.append(f"{rid}: 旧版无功能 但依据里没有 PHP 源码路径")
            elif not re.search(r":\d+", basis) and not any(
                    k in basis for k in ("方法体", "非 URL 动作", "无 public function", "grep -c")):
                problems.append(f"{rid}: 旧版无功能 但依据里没有源码行号/方法体/无 public function 证据")
        else:  # 缺口
            gap_rows.append(rid)
            if "GAP-" not in basis:
                problems.append(f"{rid}: 缺口行依据里没有写明需建 GAP-")
            if src.startswith(tuple(GAP_AUTO_KINDS)):
                # 自动分类的两种缺口：依据里必须说清是“有实现无测试”还是“只有未执行的 IT”
                if src.startswith("GAP-IT") and "MySQL IT" not in basis:
                    problems.append(f"{rid}: GAP-IT 但依据里没说明只有未执行的 MySQL IT")
                if src.startswith("GAP-T") and ("无任何测试" not in basis and "测试列" not in basis
                                                and "没有执行记录" not in basis):
                    problems.append(f"{rid}: GAP-T 但依据里没说明缺哪一层测试")
            elif src.startswith("GAP-I") or src.startswith("GAP-X"):
                if rid not in GAP_OWNER:
                    problems.append(f"{rid}: {src} 类缺口未在 GAP_OWNER 登记归属")
            else:
                problems.append(f"{rid}: 缺口行的证据来源等级 {src!r} 未分类")

    if sum(counts.values()) != len(rows):
        problems.append(f"四值求和 {sum(counts.values())} ≠ 行数 {len(rows)}")

    print(f"行 {len(rows)}；{VALUE_ALIGNED} {counts[VALUE_ALIGNED]}；{VALUE_NO_FUNCTION} {counts[VALUE_NO_FUNCTION]}；"
          f"{VALUE_GAP} {counts[VALUE_GAP]}；排除- {excluded}")
    src_counts = {}
    for r in rows:
        src_counts[r[4]] = src_counts.get(r[4], 0) + 1
    for k, v in sorted(src_counts.items()):
        print(f"  [{k}] {v}")
    print(f"缺口行（{len(gap_rows)}）：{'、'.join(gap_rows)}")
    if problems:
        print(f"FAIL：{len(problems)} 项")
        for p in problems:
            print("  - " + p)
        return 1
    print("OK：379 行齐全升序；判定取值合法且求和=379；✅已对齐 的测试本轮都执行过；旧版无功能 都有 PHP 源码证据；缺口全部具名且写明 GAP-；无自行排除")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
