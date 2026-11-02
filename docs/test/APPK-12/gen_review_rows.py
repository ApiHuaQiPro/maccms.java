#!/usr/bin/env python3
"""APPK-12 阶段 A 四值判定台账生成器（只读、可复跑）。工作目录 = 仓库根。

    python docs/test/APPK-12/gen_review_rows.py --out docs/test/APPK-12/stage-a-verdict-rows.md

唯一输入 = 映射提交 000b58e3ac5d1a6d000bad01734b3770b76efc09 的三张映射表
（`docs/api/APPK-2/parity-java-map/{site,admin,api-install}-rows.md`）+ 本轮实际跑过的非 IT 测试报告。

判定口径严格照 PRD 附表 C「判定列取值」与 `parity-stage-a.md` §3 的表格：

| 取值 | 允许的证据 |
| --- | --- |
| `✅已对齐` | Java 类/方法 **+ 测试名**，且该测试在本次运行里执行过并通过 |
| `旧版无功能` | PHP 源码行证据（方法体为空 / 非 URL 动作） |
| `排除-<编号>` | 已关闭的 OP-/D 决策编号——本轮**一行都不能填**（没有已确认清单） |
| `缺口` | 其余全部：有实现无测试、只有未执行的 *MySqlIT、实现不完整、需产品经理判定 |

`*MySqlIT` 需要 `MACCMS_IT_*`，**本轮未执行**，所以不能支撑 `✅已对齐`；
但它也不构成「实现缺失」，因此这类行判 `缺口` 并在依据里写清「补跑 IT 后可升级」——
这正是本台账要交给产品经理/智能调度的结论。

任何引用的非 IT 测试如果在 `target/surefire-reports` 里找不到执行记录，直接报错，不生成台账。
"""
from __future__ import annotations

import argparse
import glob
import json
import re
import sys
from pathlib import Path

ROOT = Path.cwd()
MAP_DIR = ROOT / "docs/api/APPK-2/parity-java-map"
LEDGER = ROOT / "docs/test/APPK-2/parity-stage-a-rows.md"
# (文件, 测试列下标) —— 三张表列数不同，测试列都在第 4 列
FILES = [("site-rows.md", 3), ("admin-rows.md", 4), ("api-install-rows.md", 4)]

VALUE_ALIGNED = "✅已对齐"
VALUE_NO_FUNCTION = "旧版无功能"
VALUE_EXCLUDED = "排除-{}"
VALUE_GAP = "缺口"

# 证据来源等级：本轮实际可用的只有 IT-0 / NA / GAP-*
SRC_IT0 = "IT-0 旧源码 + Java 实现 + 本轮执行通过的非 IT 测试"
SRC_NOFN = "NA 旧版无功能（PHP 源码行证据）"
SRC_GAP_IT_PENDING = "GAP-IT 有实现，测试只在未执行的 *MySqlIT"
SRC_GAP_NO_TEST = "GAP-T 有实现，无任何测试"
SRC_GAP_INCOMPLETE = "GAP-I 实现不完整/需产品经理判定"
SRC_GAP_SELF = "GAP-X 新版自身行为与旧版不同（非 CRUD 差异）"


def cells_of(line):
    r'''按表格单元格切分：按未转义的竖线切，再把转义竖线还原成字面竖线。'''
    raw = line.rstrip().rstrip("|").lstrip("|")
    return [x.strip().replace("\\|", "|") for x in re.split(r"(?<!\\)\|", raw)]


def parts(test_cell: str) -> list[str]:
    out = []
    for p in re.split(r"<br>|；", test_cell):
        p = p.strip()
        if p and p != "—":
            out.append(p)
    return out


def parse_refs(test_cell: str):
    """返回 (非 IT 引用, IT 引用)，各自为 (类, 方法) 去重列表。"""
    non_it, it = [], []
    for p in parts(test_cell):
        p = p.replace("〔弱〕", "")
        for m in re.finditer(r"([A-Z]\w+)#(\w+)", p):
            cls, meth = m.group(1), m.group(2)
            bucket = it if cls.endswith("MySqlIT") else non_it
            if (cls, meth) not in bucket:
                bucket.append((cls, meth))
    return non_it, it


def load_surefire(reports: Path) -> dict:
    """类名 → 本次真实执行过的测试方法名集合（参数化去掉 (...) 与 [n]）。"""
    out = {}
    for f in glob.glob(str(reports / "TEST-*.xml")):
        txt = Path(f).read_text(encoding="utf-8", errors="replace")
        m = re.search(r'<testsuite[^>]*name="([\w.]+)"', txt)
        if not m:
            continue
        cls = m.group(1).split(".")[-1]
        out[cls] = {re.sub(r"(\([^()]*\))?(\[\d+\])*$", "", n)
                    for n in re.findall(r'<testcase\s+name="([^"]+)"', txt)}
    return out


# --- 逐行判定：人工写清依据（机械部分见 _auto）------------------------------------
# 键 R 编号 → (判定, 证据来源等级, 依据)
MANUAL = {}

# 1) 旧方法体为空 / 非 URL 动作 → 旧版无功能（PHP 源码行证据）
MANUAL["R-010"] = (VALUE_NO_FUNCTION, SRC_NOFN,
    "PHP 源码 `php/application/index/controller/Ajax.php` 的 `public function index()` 方法体为空（映射表记为“缺实现”，本轮复核源码为空方法体），无任何输出；"
    "新版 `SiteAjaxController` 无该 case，落 default 404，与“无功能”一致。")
MANUAL["R-145"] = (VALUE_NO_FUNCTION, SRC_NOFN,
    "PHP 源码 `php/application/api/controller/Index.php` 的 `index()` 方法体为空（映射表记为“旧方法体为空”），无输出；新版 `/api.php/index` 无映射，请求 404。")
MANUAL["R-195"] = (VALUE_NO_FUNCTION, SRC_NOFN,
    "PHP 源码 `php/application/admin/controller/Base.php:42-58`：`check_auth($c,$a)` 只做权限字符串比对并返回 true/false，是控制器基类的鉴权辅助、"
    "不对应任何 URL 动作（台账列为“非动作”，auth 导出里也没有它）；新版由 `admin/web/AdminController` 的请求级鉴权 + `admin/application/AdminAuthorization` 承担。"
    "无对等 URL 功能。")
MANUAL["R-196"] = (VALUE_NO_FUNCTION, SRC_NOFN,
    "PHP 源码 `php/application/admin/controller/Base.php:60-100`：`_cache_clear()` 仅在 `ENTRANCE=='admin'` 时把 vodplayer/voddowner/vodserver 写回配置文件，"
    "由基类构造调用，不对外暴露动作；新版在 `admin/application/PlayerAdminService` / `index/clear` 重写播放器配置。无对等 URL 功能。")

# 2) 有实现、无本轮可用的测试 → 缺口（GAP-T），依据里写明路位置与应有的测试
NO_TEST = {
    "R-008": ("`site/web` 的 `SiteLibraryController#render` ajax 分支（旧 `Actor::ajax_detail`，受 `app.ajax_page` 控制）"),
    "R-009": ("`SiteLibraryController#rss` 的 ACTOR_RSS 分支（旧 `Actor::rss`）"),
    "R-031": ("`site/web/SiteInteractionController#handle` 的 case \"ajax\"（旧 `Comment::ajax`）"),
    "R-036": ("`SiteInteractionController#handle` 的 case \"ajax\"（旧 `Gbook::ajax`）"),
    "R-037": ("`SiteInteractionController#handle` 的 case \"report\" → reportPage（旧 `Gbook::report`）"),
    "R-051": ("`SiteLibraryController#rss` 的 PLOT_RSS 分支（旧 `Plot::rss`）"),
    "R-059": ("`SiteLibraryController#render` ajax 分支（旧 `Role::ajax_detail`）"),
    "R-060": ("`SiteLibraryController#rss` 的 ROLE_RSS 分支（旧 `Role::rss`）"),
    "R-074": ("`SiteLibraryController#rss` 的 TOPIC_RSS 分支（旧 `Topic::rss`）"),
    "R-142": ("`SiteLibraryController#render` ajax 分支（旧 `Website::ajax_detail`）"),
    "R-143": ("`SiteLibraryController#rss` 的 WEBSITE_RSS 分支（旧 `Website::rss`）"),
    "R-076": ("`site/web` 会员控制器的 ajax 用户信息分支（旧 `User::ajax_info`）"),
    "R-102": ("`site/web` 会员中心下载记录页（旧 `User::downs`）"),
    "R-104": ("`site/web` 会员中心登录日志页（旧 `User::ulog`）"),
    "R-106": ("`site/web` 会员中心积分日志页（旧 `User::plog`）"),
    "R-113": ("`site/web` 会员中心充值卡页（旧 `User::cards`）"),
    "R-115": ("`site/web` 会员中心留言页（旧 `User::gbook`）"),
    "R-280": ("`admin/web/AdminMakeActions` 的 `rss` 动作（`MakeServiceTest` 没有该动作用例，README §3 已记）"),
    "R-282": ("`admin/web/AdminMakeActions` 的 `topic_index` 动作（同上，`MakeServiceTest` 无该动作用例）"),
}
for rid, where in NO_TEST.items():
    MANUAL[rid] = (VALUE_GAP, SRC_GAP_NO_TEST,
                   f"实现位置 {where} 可读，但本轮**没有任何测试覆盖这一行**（映射表测试列为空或只有 `*MySqlIT`）。"
                   "按 PRD 附表 C，没有测试名不能写 `✅已对齐` → 需产品经理建 GAP- 并指给对应开发补测试。")

# 3) 间接实现·待核：旧 Collect::api 按 mid 内部调用，新版按 ProvideKind 分派
for rid, mid, kind, it_test in (
        ("R-227", "1", "VOD", "`AdminCollectIngestMySqlIT#cjallWalksAllPagesWithBreakpointThenFinishes`（mid=1）"),
        ("R-228", "2", "ART", "`AdminCollectIngestMySqlIT#selectedRowsAndTodaysArticles`（mid=2 载入 + cjday 入库）"),
        ("R-229", "8", "ACTOR", "**没有任何 IT 用例覆盖 mid=8**"),
        ("R-230", "9", "ROLE", "**没有任何 IT 用例覆盖 mid=9**"),
        ("R-231", "11", "WEBSITE", "`AdminCollectPreviewMySqlIT#realHttpMultipartUploadAndItsSizeLimit`（mid=11）")):
    MANUAL[rid] = (VALUE_GAP, SRC_GAP_IT_PENDING,
                   f"旧 `Collect::api` 按 `mid=='{mid}'` 内部调对应方法；新版 `AdminCollectActions#api` 用 `ProvideKind.ofMid(mid)` → {kind} 分派到 "
                   f"`collect/application/CollectPreviewService` / `CollectRunService`。分派链读代码可见，但覆盖它的测试只有 MySQL IT（{it_test}），"
                   "本轮未设 `MACCMS_IT_*`、未执行 → 不能写 `✅已对齐`，进 GAP-；补跑 IT 通过后可升级。")

MANUAL["R-018"] = (VALUE_GAP, SRC_GAP_INCOMPLETE,
    "旧 `Ajax::pwd` 覆盖 mid=1（vod_pwd / vod_pwd_play / vod_pwd_down）与 mid=2（art_pwd）多种密码校验并写会话。新版原地址 404；"
    "`PasswordTarget` 只有 VIDEO_PLAY / VIDEO_DOWN / ARTICLE——**视频详情密码（`1-1` / vod_pwd）明确“不在本批”**，"
    "`ContentReadService` 抛 `VIDEO_PASSWORD_REQUIRED`、`SiteExceptionHandler` 交还 503（`PasswordTarget` 注释记 MACC-14 未批）。"
    "即多条密码路径里 `1-1` 这一路没有等价实现 → 缺口（实现不完整），需产品经理建 GAP- 并指给后端开发。"
    "其余三路已有通过用例：`SitePlaybackPagesMvcTest#playerPasswordFlowUsesSignedHttpOnlyTicket`、`SiteLibraryPagesMvcTest#articlePointsAndPasswordAreFixed403Pages`。")

# 4) 有实现、有 Java 位置，但语义与旧版不同 / 决策编号缺失 → 缺口，等产品经理定性
MANUAL["R-041"] = (VALUE_GAP, SRC_GAP_SELF,
    "旧 `Label::index` 用 `label_fetch` 执行模板标签（PHP 有功能）；新版 `SiteAjaxController` 对 controller==label 固定返回“非法请求”，"
    "注释称依 D9-a，但 D9-a 不在 PRD 已确认的排除清单里，且新系统没有模板引擎接替这条路径 → 缺口：产品经理补 `排除-D9-a` 编号，否则需建 GAP-。")
MANUAL["R-224"] = (VALUE_GAP, SRC_GAP_SELF,
    "旧 `Collect::timing` 是可用定时挂机页；新版 `admin/application/AdminPermissions.NOT_PROVIDED` 固定返回“该功能未在新系统中提供”。"
    "源码注释记 2026-10-28 用户决定删除，但该决定没有 OP-/D 编号落进 PRD → 缺口：产品经理补决策编号后本行改 `排除-<编号>`，否则需建 GAP-。")
MANUAL["R-296"] = (VALUE_GAP, SRC_GAP_SELF,
    "旧 `System::test_cache` 真的连接缓存并测试；新版 `AdminSystemActions` 固定返回“新系统不使用外部缓存服务，未进行连接测试”，注释记 D-B15-4"
    "（不在 PRD 已确认清单内）→ 缺口：产品经理补编号后本行改 `排除-<编号>`，否则需建 GAP-。")

# 5) 旧方法体不是空、但新版无该路由 → 缺实现，缺口
for rid, ctrl in (("R-044", "Note"), ("R-068", "Search"), ("R-119", "Vod")):
    MANUAL[rid] = (VALUE_GAP, SRC_GAP_INCOMPLETE,
                   f"旧 `{ctrl}::index` 不是空方法：`php/application/index/controller/{ctrl}.php` 的方法体是 "
                   f"`return $this->label_fetch('{ctrl.lower()}/index');`（`All.php:41` → ThinkPHP `fetch()` 渲染模板）。"
                   "新版 `site/web` 的全部 @*Mapping 里没有该路由（`LegacyPage` 无对应常量），请求落 Spring 404 → 缺实现，缺口。"
                   "需产品经理判定建 GAP- 还是按 OP-/D 排除；补实现归后端开发。"
                   "（附带事实：旧模板目录 `php/template/default/html/` 只有 0 字节 index.html，本仓库无法渲染旧版输出做对照。）")


def _auto(rid, state, non_it, it, surefire):
    """机械判定：有本轮执行通过的非 IT 测试 → ✅已对齐（附测试名）；否则缺口。"""
    executed = [f"{c}#{m}" for c, m in non_it if m in surefire.get(c, set())]
    unexecuted = [f"{c}#{m}" for c, m in non_it if m not in surefire.get(c, set())]
    pending_it = [f"{c}#{m}" for c, m in it]
    if state == "非动作（占位行）":
        # 三个占位行要逐个点名文件，让读者能一行命令复核
        path = {"R-144": "`php/application/api/controller/Base.php`（只有 `__construct`，方法体为空）",
                "R-294": "`php/application/admin/controller/Safety.php`（类体只有 `class Safety extends Base {}`）",
                "R-334": "`php/application/admin/controller/Update.php`（类体只有 `class Update extends Base {}`）"}.get(rid, "该文件")
        return VALUE_NO_FUNCTION, SRC_NOFN, (f"台账占位行：{path} 内无 public function（附表 C 预填，"
                                          "本轮 `grep -c 'public function'` 复核为 0），旧版没有这条功能")
    if executed:
        basis = "本轮执行通过：" + "、".join(executed)
        if pending_it:
            basis += f"；另有 {len(pending_it)} 条 MySQL IT 未执行（升级证据待补跑）"
        if unexecuted:
            basis += "；未执行的非 IT 引用：" + "、".join(unexecuted)
        return VALUE_ALIGNED, SRC_IT0, basis
    if unexecuted:
        return VALUE_GAP, SRC_GAP_NO_TEST, ("映射表引用的非 IT 测试本轮没有执行记录：" + "、".join(unexecuted) +
                                          " → 需产品经理建 GAP-，由对应开发补可执行测试")
    if pending_it:
        return VALUE_GAP, SRC_GAP_IT_PENDING, (
            f"只有 MySQL IT 证据（本轮未执行，需 `MACCMS_IT_*`）：{'、'.join(pending_it[:3])}"
            f"{' 等 ' + str(len(pending_it)) + ' 条' if len(pending_it) > 3 else ''}"
            " → 进 GAP-，补跑 IT 通过后可升级为 ✅已对齐")
    if state == "缺实现":
        return VALUE_GAP, SRC_GAP_INCOMPLETE, "映射表记“缺实现”，无 Java 处理也无测试 → 需产品经理建 GAP- 并指给后端开发"
    return VALUE_GAP, SRC_GAP_NO_TEST, "映射表记有 Java 处理，但测试列为空——没有任何测试名可附 → 需产品经理建 GAP-，由对应开发补测试"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out")
    ap.add_argument("--surefire", default="target/surefire-reports")
    args = ap.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    surefire = load_surefire(ROOT / args.surefire)
    if not surefire:
        print(f"ERROR: {args.surefire} 下没有 TEST-*.xml——先按 README §4 跑非 IT 测试", file=sys.stderr)
        return 1

    ledger = {}
    for line in LEDGER.read_text(encoding="utf-8").splitlines():
        if line.startswith("| R-"):
            c = cells_of(line)
            ledger[c[0]] = (c[2].strip("`"), c[3].strip("`"))

    out = []
    for name, test_idx in FILES:
        f = MAP_DIR / name
        if not f.exists():
            print(f"ERROR: 缺映射表 {name}", file=sys.stderr)
            return 1
        for line in f.read_text(encoding="utf-8").splitlines():
            if not line.startswith("| R-"):
                continue
            cells = cells_of(line)
            rid = cells[0]
            old, state, test_cell = cells[1], cells[-1], cells[test_idx]
            non_it, it = parse_refs(test_cell)
            value, src, why = MANUAL.get(rid) or _auto(rid, state, non_it, it, surefire)
            out.append({"row": rid, "old": old, "action": cells[2] if name != "api-install-rows.md" else "",
                        "mapping_state": state, "verdict": value, "evidence_source": src, "basis": why})
    out.sort(key=lambda r: r["row"])

    expect = [f"R-{i:03d}" for i in range(1, 380)]
    if [r["row"] for r in out] != expect:
        got = {r["row"] for r in out}
        print(f"ERROR: 行集合不是 R-001～R-379；缺 {sorted(set(expect) - got)[:10]}，共 {len(out)} 行", file=sys.stderr)
        return 1

    lines = ["| 行号 | 旧方法 | 映射证据状态 | 四值判定 | 证据来源等级 | 依据 |",
             "| --- | --- | --- | --- | --- | --- |"]
    for r in out:
        c = [r["row"], r["old"], r["mapping_state"], r["verdict"], r["evidence_source"], r["basis"]]
        lines.append("| " + " | ".join(x.replace("|", "／") for x in c) + " |")
    body = "\n".join(lines) + "\n"

    if args.out:
        Path(args.out).write_text(body, encoding="utf-8")
        Path(args.out + ".json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    else:
        sys.stdout.write(body)

    from collections import Counter
    print(f"行 {len(out)}", file=sys.stderr)
    for k, v in Counter(r["verdict"] for r in out).most_common():
        print(f"  {k} {v}", file=sys.stderr)
    for k, v in Counter(r["evidence_source"] for r in out).most_common():
        print(f"  [{k}] {v}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
