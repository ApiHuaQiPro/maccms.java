"""APPK-2 附表 C 逐行判定用的**取证扫描器**（S3-22 阶段 A 的输入采集，不判结论、不写报告）。

它只做三件纯计算的事，全部离线、不联网、不连库、不读任何凭据：

1. **旧版侧**：按附表 C 的固定提取规则（`docs/prd/APPK-2/C-parity-registry.md` 顶部「提取规则」）
   从本仓库 `php/application/{index,api,admin}/controller/*.php` 与 `php/application/install/controller/Index.php`
   重新提取行：文件数、方法名、`public function` 的源码行号区间。用于核对登记簿的 379 行分母，
   并给出每一行在旧版里的**源码位置**（判定为「旧版无功能」时必须附的 PHP 行证据来源）。

2. **新版侧**：扫 `src/main/java/**` 里的路由声明（`@GetMapping` / `@PostMapping` / `@RequestMapping`
   的字面量路径、处理器方法名、文件与行号），以及后台的**实现批次登记**
   （`AdminPermissions` 的 `batchOf` 结论、`NOT_PROVIDED`、`WITHOUT_LEGACY_METHOD`、`EXTRA_ACTIONS`）。
   它只采集「这个入口在新版由谁承接、返回什么」，**不判断是否对齐**。

3. **比对**：把旧版行号 → 新版承接位置的候选映射打成表，并对每条候选给出**取证等级**
   （见 `EVIDENCE_LEVELS`）。等级只是「证据强度」，最终判定仍由人工逐行写入报告，扫描器不写判定词。

判定词、KPI 与报告格式不在本脚本里：见 `docs/test/APPK-2/parity-method.md`（S3-22 执行口径）
与 `docs/test/APPK-2/parity-report.md`（阶段 A 报告）。用例见 `t1-cases.md` 的
TC-F-PAR-001（376 行逐行判定）与 TC-F-PAR-002（缺口行必有 GAP-）。

用法（仓库根目录，Git Bash / PowerShell 同上）：

    python tests/compare/APPK-2/parity_scan.py                 # 表格摘要
    python tests/compare/APPK-2/parity_scan.py --json out.json # 机器可读输出（供报告生成与复核）
    python tests/compare/APPK-2/parity_scan.py --self-test     # 扫描器自检（见下）

退出码：0 = 采集完成；2 = 分母核对与登记簿不一致（属**阻断**，报告不得开始判定）；1 = 用法错误。

自检（`--self-test`）验证的是「扫描器不会假通过」：
  - 篡改一行旧版源码（在内存里注入一个 public function）必须被重新提取到（证明扫描器真的读源码）；
  - 从源码里删掉一个 public function 必须反映为分母减少；
  - 分母与登记簿不一致时必须以退出码 2 阻断，而不是照样出表；
  - 同一输入两次运行输出逐字节相同（可复现，供两份报告交叉核对）。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))

PHP_CONTROLLER_DIRS = ("index", "api", "admin")
PHP_INSTALL_CONTROLLER = os.path.join("php", "application", "install", "controller", "Index.php")

# 登记簿（附表 C）的分母口径，写死在这里而不是从 PRD 读，避免「用被测物证明被测物」。
EXPECTED_FILES = {"index": 23, "api": 6, "admin": 39}
EXPECTED_METHODS = {"index": 143, "api": 20, "admin": 208}
EXPECTED_INSTALL_STEPS = 5
EXPECTED_REGISTRY_ROWS = 379
EXPECTED_UNDETERMINED = 376
EXPECTED_OLD_NOFUNC = 3
EXPECTED_OLD_NOFUNC_IDS = ("R-144", "R-294", "R-334")

PUBLIC_FUNCTION_RE = re.compile(r"^\s*public\s+function\s+(\w+)\s*\(", re.MULTILINE)
REGISTRY_ROW_RE = re.compile(r"^\|\s*(R-\d{3})\s*\|", re.MULTILINE)

# 新版侧：Spring 路由注解（只取字面量路径，源码里拼出来的常量按「不可解析」记账，不猜）。
ROUTE_ANNOTATION_RE = re.compile(
    r"@(?P<kind>GetMapping|PostMapping|RequestMapping|PutMapping|DeleteMapping|PatchMapping)\s*"
    r"(?:\((?P<args>[^)]*)\))?",
    re.DOTALL,
)
PATH_LITERAL_RE = re.compile(r'"([^"\\]*)"')
HANDLER_METHOD_RE = re.compile(r"^\s*(?:public|private|protected)\s+[^;=()]+?\s+(\w+)\s*\(", re.MULTILINE)

EVIDENCE_LEVELS = {
    "E1": "旧版方法名在旧版控制器里存在，且新版有同名/近名的入口文件与方法（需人工确认语义）",
    "E2": "旧版控制器由新版同名包/控制器类整体承接（如 index/xxx → site/web/Site*Controller），需按方法逐个确认",
    "E3": "旧版动作在新版由后台实现批次登记（AdminPermissions.batchOf）承接，批次号即来源",
    "E4": "旧版动作在新版登记为「不提供」或「旧版无此方法」（NOT_PROVIDED / WITHOUT_LEGACY_METHOD）",
    "E5": "新版无任何承接线索（最可能是缺口，也必须由人工逐行确认后才写 GAP-）",
}


def repo_path(*parts: str) -> str:
    return os.path.join(REPO_ROOT, *parts)


# 全仓路径统一用 `/` 分隔（Windows 的 os.listdir/normpath 会给出反斜杠，拿它当字典键会让覆盖静默失效）。
def rel(path: str) -> str:
    return os.path.relpath(path, REPO_ROOT).replace(os.sep, "/")


def controller_rel(side: str, name: str) -> str:
    return f"php/application/{side}/controller/{name}"


def read_text(path: str) -> str:
    with open(path, encoding="utf-8", errors="replace") as fh:
        return fh.read()


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


# --------------------------------------------------------------------------- 旧版侧


def extract_old_side(overrides: dict | None = None) -> dict:
    """按附表 C 的规则提取旧版方法行。

    `overrides` 只给自检用（{相对路径: 源码文本}），生产调用不传。
    """
    overrides = overrides or {}
    out = {
        "files": {},
        "missing_dirs": [],
        "no_public_class": [],
        "rows": [],
        "counts": {},
        "file_sha256": {},
    }
    for side in PHP_CONTROLLER_DIRS:
        directory = repo_path("php", "application", side, "controller")
        if not os.path.isdir(directory):
            out["missing_dirs"].append(f"php/application/{side}/controller")
            continue
        names = sorted(n for n in os.listdir(directory) if n.endswith(".php"))
        out["files"][side] = names
        total = 0
        for name in names:
            rel_path = controller_rel(side, name)
            text = overrides.get(rel_path)
            if text is None:
                text = read_text(repo_path(*rel_path.split("/")))
            out["file_sha256"][rel_path] = sha256_text(text)
            methods = [
                (m.group(1), text[: m.start()].count("\n") + 1)
                for m in PUBLIC_FUNCTION_RE.finditer(text)
                if m.group(1) != "__construct"
            ]
            if not methods:
                out["no_public_class"].append({"side": side, "file": rel_path})
            total += len(methods)
            for name_m, line in methods:
                out["rows"].append({"side": side, "file": rel_path, "method": name_m, "php_line": line})
        out["counts"][side] = total

    install_rel = PHP_INSTALL_CONTROLLER.replace(os.sep, "/")
    if os.path.isfile(repo_path(*install_rel.split("/"))) or install_rel in overrides:
        text = overrides.get(install_rel)
        if text is None:
            text = read_text(repo_path(*install_rel.split("/")))
        out["file_sha256"][install_rel] = sha256_text(text)
        steps = [(m.group(1), text[: m.start()].count("\n") + 1)
                 for m in PUBLIC_FUNCTION_RE.finditer(text) if m.group(1) != "__construct"]
        out["counts"]["install_methods"] = len(steps)
        for name_m, line in steps:
            out["rows"].append({"side": "install", "file": install_rel, "method": name_m, "php_line": line})
    else:
        out["missing_dirs"].append(install_rel)
        out["counts"]["install_methods"] = 0

    # 安装向导一行 = 5 步（附表 C 提取规则第 4 条）：真实方法记 1 个，登记行记 5 行。
    out["counts"]["install"] = EXPECTED_INSTALL_STEPS if out["counts"].get("install_methods", 0) else 0
    out["counts"]["total_domain_rows"] = sum(
        out["counts"].get(k, 0) for k in ("index", "api", "admin")
    ) + out["counts"]["install"]
    return out


def load_registry() -> dict:
    """读附表 C（PRD 分支上的登记簿），只取行号与判定列，用于分母核对与差异比对。"""
    candidates = [
        repo_path("docs", "prd", "APPK-2", "C-parity-registry.md"),
    ]
    for path in candidates:
        if os.path.isfile(path):
            return _parse_registry(path, read_text(path))
    return {"available": False, "path": None, "rows": {}, "counts": {}}


def _parse_registry(path: str, text: str) -> dict:
    rows = {}
    for line in text.splitlines():
        m = REGISTRY_ROW_RE.match(line)
        if not m:
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        rows[m.group(1)] = {
            "side": cells[1],
            "file": cells[2].strip("`"),
            "method": cells[3].strip("`"),
            "verdict": cells[4],
        }
    counts = {
        "rows": len(rows),
        "undetermined": sum(1 for r in rows.values() if r["verdict"] == "未判定"),
        "old_no_func": sum(1 for r in rows.values() if r["verdict"] == "旧版无功能"),
    }
    return {"available": True, "path": rel(path),
            "rows": rows, "counts": counts}


# --------------------------------------------------------------------------- 新版侧


def extract_new_side() -> dict:
    main_dir = repo_path("src", "main", "java")
    routes = []
    unresolved = []
    for root, _dirs, files in os.walk(main_dir):
        for name in sorted(files):
            if not name.endswith(".java"):
                continue
            path = os.path.join(root, name)
            rel_path = rel(path)
            text = read_text(path)
            handlers = [(m.group(1), text[: m.start()].count("\n") + 1)
                        for m in HANDLER_METHOD_RE.finditer(text)]
            for m in ROUTE_ANNOTATION_RE.finditer(text):
                args = m.group("args") or ""
                line = text[: m.start()].count("\n") + 1
                paths = PATH_LITERAL_RE.findall(args)
                if not paths:
                    unresolved.append({"file": rel_path, "line": line, "kind": m.group("kind"),
                                       "reason": "路径由常量或表达式拼接，扫描器不解析"})
                    continue
                # 注解之后最近的一个方法声明就是它的处理器（源码里注解与方法相邻）。
                handler = next((h for h in handlers if h[1] >= line), None)
                routes.append({
                    "file": rel_path,
                    "line": line,
                    "kind": m.group("kind"),
                    "paths": paths,
                    "handler": handler[0] if handler else None,
                    "handler_line": handler[1] if handler else None,
                })
    return {"routes": routes, "unresolved_route_annotations": unresolved}


def extract_admin_registry() -> dict:
    """后台实现批次登记：从 `AdminPermissions` 源码读常量集合与映射表（不执行 Java）。"""
    path = repo_path("src", "main", "java", "com", "maccms", "backend", "admin",
                     "application", "AdminPermissions.java")
    if not os.path.isfile(path):
        return {"available": False, "path": None}
    text = read_text(path)

    def map_entries(name: str) -> dict:
        block = re.search(rf"{name}\s*=\s*Map\.ofEntries\((.*?)\);", text, re.DOTALL)
        if not block:
            return {}
        return dict(re.findall(r'Map\.entry\("([^"]+)",\s*"([^"]+)"\)', block.group(1)))

    def set_entries(name: str) -> list:
        block = re.search(rf"{name}\s*=\s*Set\.of\((.*?)\);", text, re.DOTALL)
        if not block:
            return []
        return re.findall(r'"([^"]+)"', block.group(1))

    return {
        "available": True,
        "path": rel(path),
        "sha256": sha256_text(text),
        "controller_batch": map_entries("CONTROLLER_BATCH"),
        "action_batch": map_entries("ACTION_BATCH"),
        "not_provided": set_entries("NOT_PROVIDED"),
        "without_legacy_method": set_entries("WITHOUT_LEGACY_METHOD"),
        "controllers": set_entries("CONTROLLERS"),
    }


# --------------------------------------------------------------------------- 比对


def admin_batch_for(registry: dict, controller: str, action: str) -> str | None:
    if not registry.get("available"):
        return None
    key = f"{controller.lower()}/{action.lower()}"
    via_action = registry["action_batch"].get(key)
    if via_action:
        return via_action
    return registry["controller_batch"].get(controller.lower())


def classify(old_row: dict, new_side: dict, admin_reg: dict) -> dict:
    """给出**候选承接位置 + 取证等级**；不写判定词。"""
    controller_stem = os.path.splitext(os.path.basename(old_row["file"]))[0]
    method = old_row["method"]
    evidence = []
    reason = ""

    if old_row["side"] == "admin":
        controller = controller_stem.lower()
        action = method.lower()
        lower = f"{controller}/{action}"
        if lower in (admin_reg.get("without_legacy_method") or []):
            return {"level": "E4", "reason": f"登记为旧版无此方法（WITHOUT_LEGACY_METHOD 含 {lower}）",
                    "targets": []}
        if lower in (admin_reg.get("not_provided") or []):
            return {"level": "E4", "reason": f"新版不提供（NOT_PROVIDED 含 {lower}）", "targets": []}
        batch = admin_batch_for(admin_reg, controller, action)
        if batch:
            return {"level": "E3", "reason": f"后台实现批次登记为 {batch}",
                    "targets": [admin_reg["path"], f"batchOf({lower}) = {batch}"]}
        reason = "后台实现批次登记里查不到该动作"

    if old_row["side"] == "install":
        return {"level": "E2", "reason": "安装向导由 install 包整体承接（按步骤逐个确认）",
                "targets": ["src/main/java/com/maccms/backend/install"]}

    # 前台/接口：按方法名在路由处理器里找同名入口，再按控制器名找同名承接方。
    for route in new_side["routes"]:
        handler = (route.get("handler") or "").lower()
        if handler and handler == method.lower():
            evidence.append(route)
    stem_tokens = re.findall(r"[A-Z][a-z0-9]+|[a-z0-9]+", controller_stem)
    side_tokens = [t.lower() for t in stem_tokens if len(t) > 2]
    same_controller = [
        r for r in new_side["routes"]
        if any(t in r["file"].lower() for t in side_tokens)
    ]
    if evidence:
        return {"level": "E1", "reason": "新版有同名处理器方法",
                "targets": [f"{r['file']}:{r['handler_line']} {r['kind']}" for r in sorted(evidence, key=lambda r: (r["file"], r["line"]))]}
    if same_controller:
        return {"level": "E2", "reason": f"新版有疑似同名承接方（控制器名含 {', '.join(side_tokens) or '—'}）",
                "targets": sorted({r["file"] for r in same_controller})}
    return {"level": "E5", "reason": reason or "新版无任何承接线索", "targets": []}


def build_report() -> dict:
    old = extract_old_side()
    new_side = extract_new_side()
    admin_reg = extract_admin_registry()
    registry = load_registry()

    rows = []
    for index, row in enumerate(old["rows"], start=1):
        stem = os.path.splitext(os.path.basename(row["file"]))[0]
        verdict = classify(row, new_side, admin_reg)
        rows.append({
            "seq": index,
            "side": row["side"],
            "file": row["file"],
            "controller": stem,
            "method": row["method"],
            "php_line": row["php_line"],
            "evidence_level": verdict["level"],
            "evidence_reason": verdict["reason"],
            "targets": verdict["targets"],
        })

    level_counts: dict[str, int] = {}
    for row in rows:
        level_counts[row["evidence_level"]] = level_counts.get(row["evidence_level"], 0) + 1

    # 分母核对（阻断条件，扫描器只报事实，不改判定）。
    blocking = []
    for side, expected in EXPECTED_FILES.items():
        got = len(old["files"].get(side, []))
        if got != expected:
            blocking.append(f"旧版 {side} 控制器文件数 {got} ≠ 登记簿 {expected}")
    for side, expected in EXPECTED_METHODS.items():
        got = old["counts"].get(side, 0)
        if got != expected:
            blocking.append(f"旧版 {side} 方法行 {got} ≠ 登记簿 {expected}")
    if old["counts"].get("install_methods", 0) and old["counts"]["install"] != EXPECTED_INSTALL_STEPS:
        blocking.append("安装向导按 5 行登记，但旧版控制器只有 1 个 public 方法（规则第 4 条）")
    if old["counts"]["total_domain_rows"] != EXPECTED_UNDETERMINED:
        blocking.append(
            f"真实功能行合计 {old['counts']['total_domain_rows']} ≠ 登记簿未判定分母 {EXPECTED_UNDETERMINED}"
        )
    no_public = {os.path.splitext(os.path.basename(c["file"]))[0].lower() for c in old["no_public_class"]}
    for expected_controller in ("base", "safety", "update"):
        if expected_controller not in no_public:
            blocking.append(f"旧版 {expected_controller} 应为无 public 方法的类，实际不是")
    if registry["available"]:
        if registry["counts"]["rows"] != EXPECTED_REGISTRY_ROWS:
            blocking.append(f"登记簿行数 {registry['counts']['rows']} ≠ {EXPECTED_REGISTRY_ROWS}")
        if registry["counts"]["undetermined"] != EXPECTED_UNDETERMINED:
            blocking.append(f"登记簿未判定行 {registry['counts']['undetermined']} ≠ {EXPECTED_UNDETERMINED}")
        if registry["counts"]["old_no_func"] != EXPECTED_OLD_NOFUNC:
            blocking.append(f"登记簿「旧版无功能」行 {registry['counts']['old_no_func']} ≠ {EXPECTED_OLD_NOFUNC}")
    else:
        blocking.append("登记簿文件不在主干工作树（docs/prd/APPK-2/C-parity-registry.md 未检出），"
                        "只能核对源码侧分母，行号映射需在 PRD 分支上复核")

    return {
        "tool": "tests/compare/APPK-2/parity_scan.py",
        "scope": "附表 C 分母核对 + 逐行候选承接位置与取证等级（不写判定词）",
        "old_side": {
            "files": old["files"],
            "counts": old["counts"],
            "no_public_class": old["no_public_class"],
            "file_sha256": old["file_sha256"],
            "missing_dirs": old["missing_dirs"],
        },
        "new_side": new_side,
        "admin_registry": {k: v for k, v in admin_reg.items() if k != "path"} | {"path": admin_reg.get("path")},
        "registry": {"available": registry["available"], "path": registry["path"],
                     "counts": registry.get("counts", {})},
        "rows": rows,
        "evidence_level_counts": level_counts,
        "evidence_levels": EVIDENCE_LEVELS,
        "blocking": blocking,
        "blocked": bool(blocking),
    }


# --------------------------------------------------------------------------- 自检


def self_test() -> int:
    global EXPECTED_METHODS
    failures = []

    base = extract_old_side()
    if base["counts"]["index"] != EXPECTED_METHODS["index"]:
        failures.append(f"基线 index 方法行 {base['counts']['index']} ≠ {EXPECTED_METHODS['index']}")

    # ① 注入一个 public function 必须被提取到（证明真的读源码，不是抄登记簿）。
    target = "php/application/index/controller/Index.php"
    text = read_text(repo_path(*target.split("/")))
    injected = text + "\nclass __Probe\n{\n    public function selftest_probe() {}\n}\n"
    grown = extract_old_side({target: injected})
    if grown["counts"]["index"] != base["counts"]["index"] + 1:
        failures.append("注入 public function 后 index 方法行没有 +1（扫描器没有真的读源码）")

    # ② 删掉一个 public function 必须反映为分母减少。
    shrunk_text = PUBLIC_FUNCTION_RE.sub(lambda m: m.group(0) if m.group(1) == "__construct" else "",
                                         text, count=0)
    shrunk = extract_old_side({target: shrunk_text})
    if shrunk["counts"]["index"] != base["counts"]["index"] - 1:
        failures.append("删掉 public function 后 index 方法行没有减 1（扫描器对缺失不敏感）")
    if not any(c["file"] == target for c in shrunk["no_public_class"]):
        failures.append("删掉唯一 public function 的文件没有被记入 no_public_class")

    # ③ 分母不一致必须阻断（而不是照样出表）。
    saved = dict(EXPECTED_METHODS)
    EXPECTED_METHODS["index"] = saved["index"] + 1
    try:
        if not build_report()["blocked"]:
            failures.append("人为把期望分母改错后没有阻断（分母核对形同虚设）")
    finally:
        EXPECTED_METHODS.clear()
        EXPECTED_METHODS.update(saved)

    # ④ 两次运行输出必须逐字节相同（可复现）。
    first = json.dumps(build_report(), ensure_ascii=False, sort_keys=True)
    second = json.dumps(build_report(), ensure_ascii=False, sort_keys=True)
    if first != second:
        failures.append("同一输入两次运行输出不一致（不可复现）")

    if failures:
        print("SELF-TEST FAILED")
        for f in failures:
            print(" -", f)
        return 1
    print("SELF-TEST PASSED（4 项：注入可见、删除可见、分母不一致阻断、输出可复现）")
    return 0


# --------------------------------------------------------------------------- CLI


def summarize(report: dict) -> str:
    lines = []
    lines.append(f"扫描器：{report['tool']}")
    lines.append(f"范围：{report['scope']}")
    counts = report["old_side"]["counts"]
    lines.append("")
    lines.append("旧版侧（按附表 C 提取规则重新提取）：")
    for side in PHP_CONTROLLER_DIRS:
        files = len(report["old_side"]["files"].get(side, []))
        lines.append(f"  {side:7s} 文件 {files:3d}  方法行 {counts.get(side, 0):3d}")
    lines.append(f"  install 步骤行 {counts.get('install', 0)}（真实 public 方法 "
                 f"{counts.get('install_methods', 0)} 个，按规则第 4 条拆 5 行）")
    lines.append(f"  真实功能行合计 {counts.get('total_domain_rows', 0)}（登记簿分母 376）")
    lines.append(f"  无 public 方法的类：{', '.join(c['file'] for c in report['old_side']['no_public_class']) or '—'}")
    lines.append("")
    lines.append("新版侧：")
    lines.append(f"  路由注解 {len(report['new_side']['routes'])} 处，"
                 f"路径由常量拼接未解析 {len(report['new_side']['unresolved_route_annotations'])} 处")
    reg = report["admin_registry"]
    if reg.get("available"):
        lines.append(f"  后台实现批次登记：控制器 {len(reg['controller_batch'])} 条、动作覆盖 "
                     f"{len(reg['action_batch'])} 条、NOT_PROVIDED {len(reg['not_provided'])}、"
                     f"WITHOUT_LEGACY_METHOD {len(reg['without_legacy_method'])}")
    else:
        lines.append("  后台实现批次登记：未找到 AdminPermissions.java")
    lines.append("")
    lines.append("逐行候选证据等级（只表示证据强度，判定词由报告人工写）：")
    for level in sorted(report["evidence_level_counts"]):
        lines.append(f"  {level}  {report['evidence_level_counts'][level]:3d}  {EVIDENCE_LEVELS[level]}")
    lines.append("")
    if report["blocked"]:
        lines.append("阻断项（必须先解决，报告不得开始判定）：")
        for item in report["blocking"]:
            lines.append(f"  - {item}")
    else:
        lines.append("分母核对：通过（源码侧与登记簿一致）")
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="APPK-2 附表 C 取证扫描器（只采集，不判结论）")
    parser.add_argument("--json", metavar="PATH", help="输出机器可读 JSON")
    parser.add_argument("--self-test", action="store_true", help="扫描器自检（不读凭据、不联网）")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    report = build_report()
    if args.json:
        with open(args.json, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(report, fh, ensure_ascii=False, indent=2, sort_keys=True)
            fh.write("\n")
    print(summarize(report))
    return 2 if report["blocked"] else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
