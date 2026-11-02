#!/usr/bin/env python3
"""APPK-12：附表 C 后台行（端=admin，210 行）→ Java 分派行 / 处理方法 / 测试 的机械映射（只读，不连库、不发请求）。

用法（工作目录 = 仓库根）：
    python docs/api/APPK-2/parity-java-map/gen_admin_map.py                 # 输出到 stdout
    python docs/api/APPK-2/parity-java-map/gen_admin_map.py --out docs/api/APPK-2/parity-java-map/admin-rows.md
发现任何定位失败（守卫/字面量/方法找不到、台账行数 != 379）返回 2。

规则（不猜）：
1. 行清单取自 docs/test/APPK-2/parity-stage-a-rows.md（测试台账），只处理 端=admin 的行。
2. 键 = 小写(控制器)/小写(动作)，与 AdminRoute.code() 一致。
3. 实现：
   a. admin/web/*.java 中字面量 "控制器/动作" 的 case 分支或 equals（排除视图模板选择行 t.adminPages…）；
   b. 先判控制器、再按动作 switch 的方法（SCOPED 表；脚本校验控制器守卫字面量确实存在）；
   c. system/* 按 SystemConfigService.GROUPS；make/* 按 AdminMakeActions.ACTIONS；
   d. 非路由的 Base 辅助方法与 Collect 内部方法见 OVERRIDES（行号按字面量现查）。
   都没有 → 缺实现（AdminController 会落入批次提示 pending 或 404）。
4. 测试：
   a. src/test/java 中直接出现 /admin/<控制器>/<动作> 或 "控制器/动作" 字面量的测试方法；
   b. src/test/resources 黄金夹具中 steps[].request.path = /admin/<控制器>/<动作> 的场景 → 读该夹具的测试类与回放方法。
   权限拒绝类（方法名含 Auth/Permission/Denied、AdminPermissionsGoldenTest、夹具场景 anonymous/auth-*）
   与只在辅助代码里出现的字面量标〔弱〕，不计入"实现+测试"。
5. 不给四值判定；证据状态只有：实现+测试 / 实现·缺测试 / 缺实现 / 显式不提供 / 间接实现·待核 / 非动作。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path.cwd()
ROWS = ROOT / "docs/test/APPK-2/parity-stage-a-rows.md"
MAIN = ROOT / "src/main/java/com/maccms/backend"
ADMIN_WEB = MAIN / "admin/web"
TEST_JAVA = ROOT / "src/test/java"
TEST_RES = ROOT / "src/test/resources"

MEMBER_DECL = re.compile(
    r"^    (?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|private|protected|static|final|synchronized|default)\s+)*"
    r"(?:<[^>]+>\s+)?[\w<>\[\],.?]+(?:<[^>]*>)?\s+(\w+)\s*\(")
TEST_ANN = re.compile(r"^\s*@(Test|ParameterizedTest|TestFactory|RepeatedTest)\b")
KEYWORDS = {"if", "for", "while", "switch", "return", "new", "catch", "synchronized"}
COMMENT = ("*", "//", "/*")

# 先判控制器、再按动作分派的方法：(文件, 方法, 控制器集合, 守卫字面量——须在文件里出现)
SCOPED = [
    ("AdminAddonActions.java", "handle", {"addon"}, 'equals("addon")'),
    ("AdminDatabaseActions.java", "handle", {"database"}, 'equals("database")'),
    ("AdminTemplateActions.java", "handle", {"template"}, 'equals("template")'),
    ("AdminMakeActions.java", "handle", {"make"}, 'equals("make")'),
    ("AdminPlayerActions.java", "player", {"vodplayer", "voddowner", "vodserver"}, "Kind.of(c)"),
    ("AdminPlayerActions.java", "domain", {"domain"}, 'c.equals("domain")'),
    ("AdminPlayerActions.java", "timming", {"timming"}, 'c.equals("timming")'),
]

# 非路由动作或间接分派的行：键 → (说明, [(main 相对路径, 定位字面量)], 状态)。行号按字面量现查，查不到 → 返回 2。
OVERRIDES = {
    "base/check_auth": ("旧 Base 的鉴权辅助（AdminPermissions 注释写明不算动作）；新版每次请求统一鉴权",
                        [("admin/web/AdminController.java", "AdminAuthorization.allowed("),
                         ("admin/application/AdminPermissions.java", "{@code check_auth}/{@code _cache_clear} 不算动作")],
                        "非动作（辅助方法）"),
    "base/_cache_clear": ("旧 Base 的清缓存辅助（不算动作）；新版由 index/clear 调播放器配置重写",
                          [("admin/web/AdminController.java", "playerCache.clearCache();"),
                           ("admin/application/PlayerAdminService.java", "public void clearCache()")],
                          "非动作（辅助方法）"),
}
for _k in ("vod", "art", "actor", "role", "website"):
    OVERRIDES[f"collect/{_k}"] = (
        f"旧 Collect::{_k}($param) 由 Collect::api 按 mid 内部调用；新版无独立 case（直接访问落入批次提示），"
        f"按类型处理在 collect/api → CollectPreviewService（ProvideKind.{_k.upper()}）",
        [("admin/web/AdminCollectActions.java", 'case "collect/api" -> api(ctx);'),
         ("admin/web/AdminCollectActions.java", "Optional<ProvideKind> kind = source.kind();"),
         ("collect/application/ProvideKind.java", f'{_k.upper()}("{_k}"')],
        "间接实现·待核")

OVERRIDES["system/test_cache"] = (
    "新版不连外部缓存，固定返回提示不做连接测试",
    [("admin/web/AdminSystemActions.java", 'code.equals("system/test_cache")'),
     ("admin/web/AdminSystemActions.java", "TEST_CACHE_DISABLED = ")],
    "显式不提供")

# 不经 URL、直接调服务的测试：控制器 → (测试目录, [正则模板，{a} = 动作])
SERVICE_TESTS = {
    "make": ("staticgen", [r'run\("{a}"', r'"ac",\s*"{a}"']),
    "system": ("member", [r'case "{a}" ->']),
}

# 只验证权限拒绝/菜单的用例：列出但不算行为测试
WEAK_METHOD = re.compile(r"(Auth|Permission|Denied)", re.I)
WEAK_CLASS = {"AdminPermissionsGoldenTest", "AdminAuthorizationTest"}
WEAK_LABEL = re.compile(r"^(anonymous|auth-)")


def die(msg: str) -> None:
    print(f"PROBLEM: {msg}", file=sys.stderr)
    sys.exit(2)


def short(p: Path) -> str:
    try:
        return p.relative_to(MAIN).as_posix()
    except ValueError:
        return p.name


def read_lines(p: Path) -> list[str]:
    return p.read_text(encoding="utf-8").splitlines()


def cell(s: str, n: int = 70) -> str:
    s = re.sub(r"\s+", " ", s.strip())
    s = (s[:n] + "…") if len(s) > n else s
    return s.replace("|", "\\|").replace("`", "'")


def enclosing_member(lines: list[str], idx: int) -> tuple[str | None, int | None]:
    for i in range(idx, -1, -1):
        m = MEMBER_DECL.match(lines[i])
        if m and m.group(1) not in KEYWORDS:
            return m.group(1), i
    return None, None


def next_member(lines: list[str], idx: int) -> str | None:
    for i in range(idx, min(idx + 8, len(lines))):
        m = MEMBER_DECL.match(lines[i]) or re.match(r"^\s+(?:\w+\s+)*void\s+(\w+)\s*\(", lines[i])
        if m and m.group(1) not in KEYWORDS:
            return m.group(1)
    return None


def is_test_method(lines: list[str], decl_idx: int) -> bool:
    if TEST_ANN.match(lines[decl_idx]):
        return True
    i = decl_idx - 1
    while i >= 0 and (lines[i].strip().startswith("@") or lines[i].strip() == "" or lines[i].strip().startswith("//")):
        if TEST_ANN.match(lines[i]):
            return True
        i -= 1
    return False


def method_body(lines: list[str], name: str) -> tuple[int, int]:
    start = None
    for i, ln in enumerate(lines):
        m = MEMBER_DECL.match(ln)
        if not m or m.group(1) in KEYWORDS:
            continue
        if start is None and m.group(1) == name:
            start = i
        elif start is not None:
            return start, i
    if start is None:
        die(f"找不到方法 {name}")
    return start, len(lines)


def locate(relpath: str, needle: str) -> str:
    for i, ln in enumerate(read_lines(MAIN / relpath)):
        if needle in ln:
            return f"`{relpath}:{i + 1}`"
    die(f"{relpath} 中找不到 {needle!r}")
    return ""


def load_rows() -> list[dict]:
    rows = []
    for line in ROWS.read_text(encoding="utf-8").splitlines():
        if not line.startswith("| R-"):
            continue
        c = [x.strip() for x in line.strip().strip("|").split("|")]
        rows.append({"id": c[0], "end": c[1], "file": c[2].strip("`"), "method": c[3].strip("`"), "state": c[5]})
    if len(rows) != 379:
        die(f"台账行数 {len(rows)} != 379")
    return rows


# ---------------------------------------------------------------- 实现

def find_literal(key: str, srcs: dict[Path, list[str]]) -> list[str]:
    pat = re.compile(r'(case\s[^\n>]*"' + re.escape(key) + r'"|equals\("' + re.escape(key) + r'"\))')
    hits = []
    for p, lines in srcs.items():
        for i, ln in enumerate(lines):
            if pat.search(ln) and not ln.strip().startswith(COMMENT) and "t.adminPages" not in ln:
                name, _ = enclosing_member(lines, i)
                rhs = ln.split("->", 1)[1] if "->" in ln else ln
                hits.append(f"`{short(p)}:{i + 1}` {p.stem}#{name} → `{cell(rhs)}`")
    return hits


def find_scoped(ctrl: str, action: str) -> list[str]:
    hits = []
    for fname, meth, ctrls, guard in SCOPED:
        if ctrl not in ctrls:
            continue
        p = ADMIN_WEB / fname
        lines = read_lines(p)
        if guard not in "\n".join(lines):
            die(f"{fname} 缺控制器守卫 {guard}")
        a, b = method_body(lines, meth)
        pat = re.compile(r'(case\s(?:"\w+",\s*)*"' + re.escape(action) + r'"[\s,]|equals\("' + re.escape(action) + r'"\))')
        for i in range(a, b):
            if pat.search(lines[i]) and not lines[i].strip().startswith(COMMENT) and guard not in lines[i]:
                rhs = lines[i].split("->", 1)[1] if "->" in lines[i] else lines[i]
                hits.append(f"`{short(p)}:{i + 1}` {p.stem}#{meth}（控制器 {ctrl}）→ `{cell(rhs)}`")
                break
    return hits


def set_literal(relpath: str, pattern: str) -> tuple[set[str], int]:
    text = (MAIN / relpath).read_text(encoding="utf-8")
    m = re.search(pattern, text, re.S)
    if not m:
        die(f"{relpath} 中找不到 {pattern}")
    return set(re.findall(r'"(\w+(?:/\w+)?)"', m.group(1))), text[: m.start()].count("\n") + 1


# ---------------------------------------------------------------- 测试

def fixture_index(tsrcs: dict[Path, list[str]]) -> dict[str, list[tuple[str, str]]]:
    """夹具相对路径 → [(测试类, 回放方法)]：测试类源码里出现该夹具路径（或经读取辅助类间接引用）。"""
    out: dict[str, list[tuple[str, str]]] = {}
    texts = {p: "\n".join(l) for p, l in tsrcs.items()}
    has_test = {p: any(TEST_ANN.match(x) for x in l) for p, l in tsrcs.items()}
    for fx in sorted(TEST_RES.rglob("*golden*.json")):
        r = fx.relative_to(TEST_RES).as_posix()
        users = []
        for p in tsrcs:
            if f'"{r}"' not in texts[p]:
                continue
            if has_test[p]:
                users.append(p)
            else:
                users += [q for q in tsrcs if q != p and has_test[q] and re.search(r"\b" + p.stem + r"\.", texts[q])]
        pairs = []
        for p in dict.fromkeys(users):
            lines = tsrcs[p]
            names = [next_member(lines, i) for i, x in enumerate(lines) if TEST_ANN.match(x)]
            replay = [n for n in names if n and "golden" in n.lower()] or [n for n in names if n]
            pairs.append((p.stem, replay[0] if replay else "?"))
        out[r] = pairs
    return out


def fixture_paths() -> dict[str, dict[str, set[str]]]:
    """夹具相对路径 → {请求 path（小写）: 场景 label 集合}。有 php80 版时 php73 版不重复列（两版场景相同）。"""
    out = {}
    for fx in sorted(TEST_RES.rglob("*golden*.json")):
        r = fx.relative_to(TEST_RES).as_posix()
        if "php73" in r and (TEST_RES / r.replace("php73", "php80")).exists():
            continue
        acc: dict[str, set[str]] = {}
        stack = [(json.loads(fx.read_text(encoding="utf-8")), None)]  # 迭代遍历（个别夹具嵌套很深）
        while stack:
            o, label = stack.pop()
            if isinstance(o, dict):
                if "steps" in o and isinstance(o.get("label"), str):
                    label = o["label"]
                req = o.get("request")
                if isinstance(req, dict) and isinstance(req.get("path"), str):
                    acc.setdefault(req["path"].lower(), set()).add(label or "?")
                stack.extend((v, label) for v in o.values())
            elif isinstance(o, list):
                stack.extend((v, label) for v in o)
        out[r] = acc
    return out


def find_tests(ctrl: str, action: str, tsrcs, fxidx, fxpaths) -> tuple[list[str], list[str]]:
    key = f"{ctrl}/{action}"
    url = re.compile(r"/admin/" + re.escape(ctrl) + "/" + re.escape(action) + r"(?![a-z0-9_])", re.I)
    lit = re.compile(r'"' + re.escape(key) + r'(?:[".?/])', re.I)
    found: dict[str, set[str]] = {}
    helper_only: set[str] = set()
    for p, lines in tsrcs.items():
        for i, ln in enumerate(lines):
            if not (url.search(ln) or lit.search(ln)) or ln.strip().startswith(COMMENT):
                continue
            name, decl = enclosing_member(lines, i)
            if name and decl is not None and is_test_method(lines, decl):
                found.setdefault(p.stem, set()).add(name)
            else:
                helper_only.add(p.stem)
    strong, weak = [], []
    for c in sorted(found):
        for m in sorted(found[c]):
            (weak if c in WEAK_CLASS or WEAK_METHOD.search(m) else strong).append(f"{c}#{m}")
    base = "/admin/" + key
    for fx, paths in fxpaths.items():
        labels: set[str] = set()
        for v, ls in paths.items():
            if v == base or v.startswith((base + "/", base + ".", base + "?")):
                labels |= ls
        if not labels:
            continue
        good = sorted(x for x in labels if not WEAK_LABEL.match(x))
        for cls, m in fxidx.get(fx, []):
            if good:
                shown = "、".join(good[:3]) + ("…" if len(good) > 3 else "")
                strong.append(f"{cls}#{m}（夹具 `{fx}` 场景 {shown}）")
            else:
                weak.append(f"{cls}#{m}（夹具 `{fx}` 仅权限场景）")
    if ctrl in SERVICE_TESTS:
        sub, pats = SERVICE_TESTS[ctrl]
        rx = re.compile("|".join(t.replace("{a}", re.escape(action)) for t in pats))
        seen = set()
        for p, lines in tsrcs.items():
            if f"/backend/{sub}/" not in p.as_posix():
                continue
            for i, ln in enumerate(lines):
                if rx.search(ln):
                    name, decl = enclosing_member(lines, i)
                    if not name or (p.stem, name) in seen:
                        continue
                    seen.add((p.stem, name))
                    tag = f"{p.stem}#{name}" + ("" if is_test_method(lines, decl) else "（回放辅助方法，由该类测试调用）")
                    strong.append(tag)
    for c in sorted(helper_only - set(found)):
        weak.append(f"{c}（字面量仅在辅助代码）")
    return strong, weak


# ---------------------------------------------------------------- 主流程

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out")
    a = ap.parse_args()
    rows = [r for r in load_rows() if r["end"] == "admin"]
    srcs = {p: read_lines(p) for p in sorted(ADMIN_WEB.glob("*.java"))}
    tsrcs = {p: read_lines(p) for p in sorted(TEST_JAVA.rglob("*.java"))}
    fxidx = fixture_index(tsrcs)
    fxpaths = fixture_paths()
    make_set, make_line = set_literal("admin/web/AdminMakeActions.java", r"ACTIONS\s*=\s*Set\.of\(([^;]*)\);")
    groups, _ = set_literal("admin/application/SystemConfigService.java", r"GROUPS\s*=\s*Map\.ofEntries\((.*?)\);")
    groups = {g for g in groups if "/" not in g}
    groups = set(re.findall(r'Map\.entry\("(\w+)"', (MAIN / "admin/application/SystemConfigService.java")
                            .read_text(encoding="utf-8").split("GROUPS", 1)[1].split(");", 1)[0]))
    not_provided, _ = set_literal("admin/application/AdminPermissions.java", r"NOT_PROVIDED\s*=\s*Set\.of\(([^;]*)\)")

    out = ["| 行号 | 旧方法 | 键 | Java 实现（分派行 → 处理） | 测试（测试类#方法；〔弱〕= 仅权限/辅助） | 证据状态 |",
           "| --- | --- | --- | --- | --- | --- |"]
    stats: dict[str, int] = {}
    for r in rows:
        ctrl = r["file"][:-4].lower()
        if r["state"] == "placeholder":
            out.append(f"| {r['id']} | `{r['file']}`（空类） | — | 类体无 public function（与台账占位一致） | — | 非动作（占位行） |")
            stats["非动作（占位行）"] = stats.get("非动作（占位行）", 0) + 1
            continue
        action = r["method"].lower()
        key = f"{ctrl}/{action}"
        hits = find_literal(key, srcs) + find_scoped(ctrl, action)
        if ctrl == "system" and action in groups:
            hits.append(f"{locate('admin/web/AdminSystemActions.java', 'SystemConfigService.GROUPS.containsKey(page)')} "
                        f"AdminSystemActions#handle（system/* 按 GROUPS 分组读写）；"
                        f"{locate('admin/application/SystemConfigService.java', 'Map.entry(' + chr(34) + action + chr(34))}"
                        f" GROUPS 含 `{action}`")
        if ctrl == "make" and action in make_set:
            hits.append(f"`admin/web/AdminMakeActions.java:{make_line}` AdminMakeActions#handle：ACTIONS 含 `{action}` → "
                        f"`MakeService.run(action, …)`")
        if key in not_provided:
            hits.append(f"{locate('admin/application/AdminPermissions.java', 'NOT_PROVIDED = Set.of(')} NOT_PROVIDED 含此键 → "
                        f"AdminController#notProvided")
        strong, weak = find_tests(ctrl, action, tsrcs, fxidx, fxpaths)
        ov = OVERRIDES.get(key)
        if ov:
            hits = [f"{ov[0]}：" + "、".join(locate(f, n) for f, n in ov[1])]
        cells = strong[:4] + (["…"] if len(strong) > 4 else []) + [f"〔弱〕{w}" for w in weak[:2]]
        if ov:
            state = ov[2]
        elif key in not_provided:
            state = "显式不提供"
        elif not hits:
            state = "缺实现"
        elif strong:
            state = "实现+测试"
        else:
            state = "实现·缺测试"
        stats[state] = stats.get(state, 0) + 1
        out.append(f"| {r['id']} | `{r['file']}::{r['method']}` | `{key}` | {'<br>'.join(hits) or '—'} | "
                   f"{'<br>'.join(cells) or '—'} | {state} |")
    summary = "；".join(f"{k} {v}" for k, v in sorted(stats.items()))
    text = "\n".join(out) + f"\n\n合计 {len(rows)} 行：{summary}\n"
    if a.out:
        Path(a.out).write_text(text, encoding="utf-8", newline="\n")
    else:
        sys.stdout.buffer.write(text.encode("utf-8"))
    sys.stderr.buffer.write(f"admin 行 {len(rows)}：{summary}\n".encode("utf-8"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
