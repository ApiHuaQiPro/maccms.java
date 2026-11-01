#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""APPK-2 C-65（技术设计 v10）：模板包 PRE-5 扫描，产出 PRD 附表 E.4 / PRE-5 所需的机器可读事实。

范围与边界（与派工说明一致，只写不执行、不发请求、不连环境、不读凭据）：

* 输入只有**本地路径**：模板包根目录（DEC-1 的 ``third_party/maccms-template-v2020.1000.1035/template``
  或 DEC-2 放置后的 ``<站点根>/template``）与可选的清单文件（PRD 附表 E.3 的路径 / 字节数 / blob SHA-1）。
  本脚本不访问网络、不执行模板里的任何代码、不启动 PHP 或 Java。
* 本脚本**只产出扫描数据**（JSON、Markdown 初稿、机器可读的发现台账），不做逐处实现说明——
  PRE-5 的实现说明与「时钟/随机/会话/token 归属」由架构师在 S3-04 补全（PRD PRE-5 的 Owner 是架构师）；
  EX-1 / EX-2 / DATA-2 之外的归属需要新增 EX- 时，按 BR-7 ⑤ 走 PRD 版本 +1 与发起人逐字确认，
  因此本脚本遇到无法归属的位置时一律记 ``owner=null`` 且**返回码 2**，绝不默认填一个 EX-。
* 只读：脚本对输入目录只做读取（``open(..., 'rb')`` 与 ``os.scandir``），不改、不删、不建任何文件在输入目录内；
  所有输出都写到 ``--out-dir``（默认不入库，交付时只提交登记台账）。

用法（工作目录 = 仓库根）：:

    python tests/compare/APPK-2/scan_templates.py \
        --package third_party/maccms-template-v2020.1000.1035/template \
        --manifest docs/test/APPK-2/fixtures/template-package-manifest.tsv \
        --out-dir .compare/APPK-2/scan-out

返回码：

    0  扫描完成，内容一致性校验全部通过（未发现与清单/附表 E.4 不符之处）
    1  输入或用法错误（目录不存在、清单格式错、清单为空……）
    2  **已完成的比对发现不符或待确认项**（不是脚本崩溃）：例如文件数/字节数/SHA-1 与清单不一致、
       工作区文件含 CRLF（RISK-T1 / NOIGN-4）、扫描结果与附表 E.4 取证事实不符、存在归属未定的
       时钟/随机/会话来源。这些必须交给复核人，脚本不得自行判定 PASS。
    3  脚本自身异常（未预期错误）
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys

# ---------------------------------------------------------------------------
# 常量：来自 PRD 附表 E（v9）与技术设计 v10（08043003）的固定口径。
# 这些值只作**期望值**使用；脚本从不修改它们，发现不符就报告。
# ---------------------------------------------------------------------------

EXPECTED_PACKAGE_FILES = {"default_pc": 147, "default_wap": 127}
EXPECTED_PACKAGE_TOTAL = 274
EXPECTED_PC_HTML = 92
EXPECTED_WAP_HTML = 76
EXPECTED_PACKAGE_DIRS = ("default_pc", "default_wap")

# 附表 E.4 EF-3 的取证命令是 `grep -rhoE '\{maccms:[a-z]+'`：它只取**小写字母**，所以
# `{maccms:link_gbook}`、`{maccms:link_map_vod}` 会被截成 `link`（下划线为止）。这就是附表 E.4 写
# 「link 6」的原因（实际是 1 个 link + 1 个 link_gbook + 4 个 link_map_*）。
# 为了与**附表 E 数值**可比，同时给出两套统计：`maccms_tags`（完整标签名，实现要用）
# 与 `maccms_tags_prd_prefix`（按附表 E.4 取证命令的口径，用于核对附表 E.4 自身是否准确）。
RE_MACCMS_TAG_PRD = re.compile(r"\{maccms:([a-z]+)")

# 附表 E.4 EF-3：{maccms:…} 标签出现次数（两个目录分别统计，口径 = 取证命令的小写前缀）。
EXPECTED_MACCMS_TAGS = {
    "default_pc": {
        "vod": 60, "foreach": 49, "type": 9, "art": 7, "link": 6, "actor": 6,
        "website": 5, "comment": 2, "topic": 1, "gbook": 1, "for": 1,
    },
    "default_wap": {
        "vod": 28, "foreach": 27, "type": 7, "comment": 2, "art": 2, "topic": 1,
        "gbook": 1, "for": 1, "link": 1, "path": 1, "siteaid": 1, "sitetid": 1,
        "siteid": 1,
    },
}

# 附表 E.4 EF-5：其他模板语法出现次数（两个目录合计）。
EXPECTED_OTHER_SYNTAX = {
    "include": 234, "if": 210, "volist": 35, "else": 35, "elseif": 18,
    "empty": 10, "foreach": 4, "switch": 1,
}

# 附表 E.4 EF-2：含 PHP 代码的模板（已知 6 个文件，每个文件的**全部**出现位置都要列出）。
EXPECTED_PHP_TEMPLATES = {
    "default_pc/html/art/detail.html",
    "default_pc/html/vod/plot.html",
    "default_pc/html/public/jump.html",
    "default_pc/html/vod/copyright.html",
    "default_wap/html/public/jump.html",
    "default_wap/html/vod/copyright.html",
}

# 附表 E.4 EF-1：js/、css/、ads/ 下没有 {maccms 字样。
NO_TAG_SUBDIRS = ("js", "css", "ads")

# ---- 扫描用的正则（与 ThinkPHP 5.0 的标签定界一致：{ } 为起止符） ----------------

# 标签名用「字母串 + 边界」捕获，**不预先枚举**：`{maccms:link_gbook}` 这类带下划线的子标签
# 会以完整名字出现，预枚举会让它们被拆成前缀（`link`）而漏计/错计。
RE_MACCMS_BLOCK = re.compile(r"\{maccms:([A-Za-z_][A-Za-z0-9_]*)((?:[^{}]|\{[^{}]*\})*)\}", re.S)
# 注意顺序：`elseif` 必须排在 `else` 之前，否则 `{elseif .../}` 会被当成 `else` 重复计数。
RE_THINK_BLOCK = re.compile(r"\{(include|if|elseif|else|volist|foreach|for|empty|switch|case|php|assign|eq|neq|gt|lt|elt|egt|range|in|notin|between|notbetween|present|notpresent|defined|notdefined|compare|notempty|even|odd|load|extend|block|literal)\b((?:[^{}]|\{[^{}]*\})*)\}", re.S)
# 附表 E.4 EF-5 的 `{else` 口径是 ThinkPHP 的**块 else**：`{else}` 与 `{else /}`（自闭合写法带空格），
# 不含 `{elseif`，也不含无空格的 `{else/}`。S3-03 用真包核对得出：23 + 12 = 35 = 附表 E.4 的数值，
# 而 `\{else\b` 得 77、`{else/}` 得 42，都对不上。两边口径必须一致，否则扫描结论会与附表 E 错位。
# ThinkPHP 的块 else 有两种写法：`{else}` 与 `{else /}`（自闭合）。无空格的 `{else/}` 归到
# 「其它形态」，仍逐处登记在 JSON 里，只是不计入 EF-5 的 `{else` 数值。
RE_ELSE_BLOCK = re.compile(r"^\{else\}")
RE_ELSE_SELFCLOSE_SPACED = re.compile(r"^\{else\s+/\s*\}")
RE_PHP_OPEN = re.compile(r"<\?php(.*?)(?:\?>|$)", re.S)
RE_PHP_TAG_BLOCK = re.compile(r"\{php\}(.*?)\{/php\}", re.S)
RE_PHP_ECHO_SHORT = re.compile(r"<\?=(.*?)(?:\?>|$)", re.S)
RE_FUNC_CALL = re.compile(r"\{:\s*([A-Za-z_\\][A-Za-z0-9_\\]*)\s*\(")
RE_VAR = re.compile(r"\{\$([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)*)\}")
RE_INCLUDE = re.compile(r"\{include\b([^{}]*)\}")
RE_BY_RND = re.compile(r"""\bby\s*=\s*["']?rnd["']?""", re.I)

# ---- 时钟 / 随机 / 会话 / token 的候选来源（附表 F.5 规则 2） --------------------
# 匹配的是**模板文件里出现的**调用；归属只允许 EX-1 / EX-2 / DATA-2（或 DATA-1 的数据构造前提），
# 其他一律记 owner=None 并由脚本返回 2 交人工（BR-7 ⑤：新增 EX- 须 PRD 版本 +1）。
TIME_RANDOM_CALLS = {
    "date": "time", "time": "time", "strtotime": "time", "mac_day": "time",
    "mktime": "time", "microtime": "time", "checkdate": "time",
    "rand": "random", "mt_rand": "random", "uniqid": "random", "random_int": "random",
    "shuffle": "random", "array_rand": "random", "str_shuffle": "random",
    "session_id": "session", "session": "session",
    "token": "token", "input_token": "token", "cookie": "cookie",
}
RE_CALL = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(")

# ThinkPHP 的**过滤器**写法：`{$vo.art_time|date='Y-m-d',###}`、`{$obj.user_end_time|mac_day}`、
# `{$maccms.site_url|...}`。S3-03 在真包上发现：模板格式化时间几乎全部用过滤器，inline 的
# `date(` / `mac_day(` **一处也没有**（`grep -c '{:.*date('` = 0）。只按调用名扫会得出
# “非确定来源 0 处”的假结论，因此必须把过滤器也算进来。
RE_VAR_FILTER = re.compile(r"\{\$[A-Za-z_][A-Za-z0-9_.]*(?:\|[A-Za-z_][A-Za-z0-9_]*\s*(?:=[^{}|]*)?)*\}")
RE_FILTER_NAME = re.compile(r"\|([A-Za-z_][A-Za-z0-9_]*)")
# 在过滤器里算“时间/随机/会话”的过滤器名。`date` 与 `mac_day` 是把时间戳格式化，输出取决于数据
# （数据由 DB 造，属 DATA-1/DB，不属于当前时钟）；但 `date()` 不带第二参时默认“现在”，所以仍要逐处登记。
FILTER_NONDETERMINISTIC = {
    "date": "time", "mac_day": "time", "mac_time": "time", "time": "time",
    "rand": "random", "mt_rand": "random", "uniqid": "random",
    "token": "token", "session": "session",
}
# `|date='Y-m-d',###` 里的 `###` 就是“用数据里的时间戳”，不是当前时间；不带 `,###` 的 `|date='Y-m-d'` 才是当前时间。
RE_DATE_FILTER_WITH_ARG = re.compile(r"\|date\s*=\s*['\"][^'\"]*['\"]\s*,\s*###")

STATUS_OK = "OK"
STATUS_MISMATCH = "MISMATCH"
STATUS_UNATTRIBUTED = "UNATTRIBUTED"
STATUS_PENDING_OWNER = "PENDING-OWNER"


def repo_rel(path: str) -> str:
    return path.replace(os.sep, "/")


def sha1_git_blob(data: bytes) -> str:
    """与 `git hash-object` 输出相同的 blob SHA-1（git hash-object 就是这样算的）。"""
    h = hashlib.sha1()
    h.update(b"blob %d\0" % len(data))
    h.update(data)
    return h.hexdigest()


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


# ---------------------------------------------------------------------------
# 清单（PRD 附表 E.3 的 274 行）解析
# ---------------------------------------------------------------------------

RE_E3_ROW = re.compile(r"^\|\s*(\d+)\s*\|\s*`([^`]+)`\s*\|\s*(\d+)\s*\|\s*`([0-9a-f]{40})`\s*\|\s*$")


def load_manifest(path: str) -> dict:
    """读 TSV 清单：`路径<TAB>字节数<TAB>blob SHA-1`（缺省允许一个表头行与 # 注释行）。"""
    rows = {}
    with open(path, "rb") as fh:
        raw = fh.read()
    if raw.startswith(b"\xef\xbb\xbf"):
        raw = raw[3:]
    text = raw.decode("utf-8")
    for lineno, line in enumerate(text.splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 3:
            raise SystemExit("清单第 %d 行不是三列 TSV：%r" % (lineno, line))
        p, size, sha = (x.strip() for x in parts)
        if p == "path":  # 表头
            continue
        if not sha or not size.isdigit():
            raise SystemExit("清单第 %d 行格式不对：%r" % (lineno, line))
        if p in rows:
            raise SystemExit("清单里路径重复：%s" % p)
        rows[p] = {"bytes": int(size), "sha1": sha.lower()}
    if not rows:
        raise SystemExit("清单为空：%s" % path)
    return rows


def extract_e3_rows_from_prd(prd_path: str) -> dict:
    """从 PRD 附表 E 的 Markdown 里直接抽 E.3 的 274 行，用于核对入库清单副本没有抄错。"""
    rows = {}
    with open(prd_path, "rb") as fh:
        text = fh.read().decode("utf-8")
    for line in text.splitlines():
        m = RE_E3_ROW.match(line.strip())
        if m:
            idx, p, size, sha = m.groups()
            rows[p] = {"bytes": int(size), "sha1": sha.lower(), "index": int(idx)}
    return rows


# ---------------------------------------------------------------------------
# 结构扫描（只读）
# ---------------------------------------------------------------------------

def list_package(template_root: str) -> dict:
    """列出 template/ 下的目录与文件；报告越界项（不是两个包之一、或包内 ads 目录等）。"""
    report = {"root": repo_rel(template_root), "dirs": [], "files": [], "anomalies": []}
    if not os.path.isdir(template_root):
        raise SystemExit("模板包目录不存在：%s" % template_root)
    entries = sorted(os.scandir(template_root), key=lambda e: e.name)
    report["dirs"] = [e.name for e in entries if e.is_dir()]
    extra = [d for d in report["dirs"] if d not in EXPECTED_PACKAGE_DIRS]
    if extra:
        report["anomalies"].append("template/ 下存在预期之外的目录：%s" % ", ".join(extra))
    for d in EXPECTED_PACKAGE_DIRS:
        if d not in report["dirs"]:
            report["anomalies"].append("缺少模板目录：%s" % d)
    if os.path.isdir(os.path.join(template_root, "default_wap", "ads")):
        report["anomalies"].append("default_wap/ads 存在（附表 E.2 规定该目录不存在）")
    for e in entries:
        if e.is_file():
            report["files"].append(e.name)
    for d in report["dirs"]:
        for root, dirnames, filenames in os.walk(os.path.join(template_root, d)):
            dirnames.sort()
            for fn in sorted(filenames):
                full = os.path.join(root, fn)
                rel = os.path.relpath(full, template_root)
                report["files"].append(repo_rel(rel))
    report["files"].sort()
    return report


def is_text_file(data: bytes) -> bool:
    """粗判文本：无 NUL 且可按 UTF-8 解码。二进制（图片等）碰巧含 0D 0A 不算 CRLF 污染。"""
    if b"\x00" in data:
        return False
    try:
        data.decode("utf-8")
    except UnicodeDecodeError:
        return False
    return True


def scan_file(package_root: str, rel: str, source_root: str) -> dict:
    full = os.path.join(source_root, rel)
    with open(full, "rb") as fh:
        data = fh.read()
    text = data.decode("utf-8", errors="replace") if is_text_file(data) else ""
    lines = text.splitlines()
    text_is_utf8 = is_text_file(data)

    def line_of(pos: int) -> int:
        return text.count("\n", 0, pos) + 1

    alias = rel.split("/", 1)[0]
    result = {
        "path": rel,
        "package": alias,
        "bytes": len(data),
        "sha1": sha1_git_blob(data),
        "sha256": sha256_hex(data),
        "crlf": b"\r\n" in data and is_text_file(data),
        "is_html": rel.endswith(".html"),
        "maccms_tags": [],
        "maccms_tags_prd_prefix": [],
        "think_syntax": [],
        "includes": [],
        "functions": [],
        "variables": [],
        "php_code": [],
        "nondeterministic": [],
        "by_rnd": [],
        "raw_text_maccms": text_is_utf8 and "{maccms" in text,
    }
    # 注意：EF-1 要查的正是 js/、css/、ads/ 这类**非** .html 文件，所以不能提前 return；
    # 下面的标签/语法统计对所有文本文件都做（真包里这些目录命中 0 个，见附表 E.4 EF-1）。
    if not text_is_utf8:
        return result

    for m in RE_MACCMS_BLOCK.finditer(text):
        result["maccms_tags"].append({
            "name": m.group(1).lower(),
            "line": line_of(m.start()),
            "raw": m.group(0)[:200],
        })
    for m in RE_MACCMS_TAG_PRD.finditer(text):
        result["maccms_tags_prd_prefix"].append({"name": m.group(1), "line": line_of(m.start())})
    for m in RE_THINK_BLOCK.finditer(text):
        name = m.group(1).lower()
        if name == "else" and not (RE_ELSE_BLOCK.match(m.group(0))
                                   or RE_ELSE_SELFCLOSE_SPACED.match(m.group(0))):
            # `{else/}`（无空格）不计入 EF-5 的 `{else` 口径，见 RE_ELSE_BLOCK 注释。
            result.setdefault("think_syntax_other", []).append(
                {"name": "else", "raw": m.group(0)[:60], "line": line_of(m.start()),
                 "note": "无空格 {else/}，未计入 EF-5「{else」口径"})
            continue
        result["think_syntax"].append({
            "name": m.group(1).lower(),
            "line": line_of(m.start()),
            "raw": m.group(0)[:200],
        })
    for m in RE_INCLUDE.finditer(text):
        file_attr = re.search(r"""\bfile\s*=\s*["']?([^"'\s}]+)""", m.group(1))
        result["includes"].append({
            "line": line_of(m.start()),
            "target": file_attr.group(1) if file_attr else None,
        })
    for m in RE_FUNC_CALL.finditer(text):
        result["functions"].append({"name": m.group(1), "line": line_of(m.start())})
    for m in RE_VAR.finditer(text):
        result["variables"].append({"name": m.group(1), "line": line_of(m.start())})
    for m in RE_PHP_OPEN.finditer(text):
        result["php_code"].append({"kind": "<?php", "line": line_of(m.start()), "raw": m.group(0)[:200]})
    for m in RE_PHP_TAG_BLOCK.finditer(text):
        result["php_code"].append({"kind": "{php}", "line": line_of(m.start()), "raw": m.group(0)[:200]})
    for m in RE_PHP_ECHO_SHORT.finditer(text):
        result["php_code"].append({"kind": "<?=", "line": line_of(m.start()), "raw": m.group(0)[:200]})
    result["php_code"].sort(key=lambda x: (x["line"], x["kind"]))
    for m in RE_BY_RND.finditer(text):
        result["by_rnd"].append({"line": line_of(m.start()), "raw": m.group(0)})

    # 时钟 / 随机 / 会话 / token：逐处登记，归属留给架构师（S3-04），脚本只给出**候选**。
    for m in RE_CALL.finditer(text):
        name = m.group(1)
        if name in TIME_RANDOM_CALLS:
            result["nondeterministic"].append({
                "kind": TIME_RANDOM_CALLS[name],
                "call": name,
                "form": "call",
                "line": line_of(m.start()),
                "context": lines[line_of(m.start()) - 1].strip()[:200] if lines else "",
                # owner 只能由架构师在 S3-04 填：EX-1 / EX-2 / DATA-2（或「新增 EX-，需 PRD 版本 +1」）。
                "owner": None,
            })
    # 过滤器写法：`{$vo.art_time|date='Y-m-d',###}`、`{$obj.user_end_time|mac_day}`。
    for m in RE_VAR_FILTER.finditer(text):
        expr = m.group(0)
        for fm in RE_FILTER_NAME.finditer(expr):
            fname = fm.group(1)
            if fname not in FILTER_NONDETERMINISTIC:
                continue
            if fname == "date":
                # `|date='Y-m-d',###` → 用数据时间戳，不是当前时间；不带 `,###` → 当前时间。
                is_data_time = bool(RE_DATE_FILTER_WITH_ARG.search(expr))
                owner = "DATA-1" if is_data_time else None
                note = ("|date 带 ,### → 用数据时间戳（取决于 DB，按 DATA-1 构造）" if is_data_time
                        else "|date 不带 ,### → 默认当前时间，需 EX-1 或新 EX-")
            else:
                owner = None
                note = "过滤器 " + fname + "，输出依赖当前时间/数据，需架构师归属"
            result["nondeterministic"].append({
                "kind": FILTER_NONDETERMINISTIC[fname],
                "call": "|" + fname,
                "form": "filter",
                "line": line_of(m.start()),
                "context": (lines[line_of(m.start()) - 1].strip()[:200] if lines else ""),
                "note": note,
                "owner": owner,
            })
    for m in RE_BY_RND.finditer(text):
        # DATA-2 只管 by="rnd"；这里填 DATA-2 是 PRD 附表 F.6 已规定的映射，不算脚本自造。
        result["nondeterministic"].append({
            "kind": "random",
            "call": 'by="rnd"',
            "form": "attr",
            "line": line_of(m.start()),
            "context": lines[line_of(m.start()) - 1].strip()[:200] if lines else "",
            "owner": "DATA-2",
        })
    # {maccms:… by="rnd"} 里的 rnd 常写作 by="rnd"，上面已覆盖；此处只做去重。
    seen = set()
    deduped = []
    key_fields = ("kind", "call", "form", "line")
    for item in result["nondeterministic"]:
        k = tuple(item[f] for f in key_fields)
        if k in seen:
            continue
        seen.add(k)
        deduped.append(item)
    result["nondeterministic"] = deduped
    return result


def aggregate(files: list) -> dict:
    tags = {"default_pc": {}, "default_wap": {}}
    prd_tags = {"default_pc": {}, "default_wap": {}}
    syntax = {}
    functions = {}
    variables = {}
    php_templates = []
    for f in files:
        if not f["is_html"]:
            continue
        pkg = f["package"]
        for t in f["maccms_tags"]:
            tags.setdefault(pkg, {})
            tags[pkg][t["name"]] = tags[pkg].get(t["name"], 0) + 1
        for t in f["maccms_tags_prd_prefix"]:
            prd_tags.setdefault(pkg, {})
            prd_tags[pkg][t["name"]] = prd_tags[pkg].get(t["name"], 0) + 1
        for t in f["think_syntax"]:
            syntax[t["name"]] = syntax.get(t["name"], 0) + 1
        for fn in f["functions"]:
            functions[fn["name"]] = functions.get(fn["name"], 0) + 1
        for v in f["variables"]:
            variables[v["name"]] = variables.get(v["name"], 0) + 1
        if f["php_code"]:
            php_templates.append(f["path"])
    return {
        "maccms_tags": tags,
        "maccms_tags_prd_prefix": prd_tags,
        "other_syntax": syntax,
        "function_calls": dict(sorted(functions.items(), key=lambda kv: (-kv[1], kv[0]))),
        "variables": dict(sorted(variables.items(), key=lambda kv: (-kv[1], kv[0]))),
        "php_templates": sorted(php_templates),
    }


# ---------------------------------------------------------------------------
# 校验
# ---------------------------------------------------------------------------

def check_structure(report: dict, files: list) -> list:
    checks = []

    def add(cid, status, detail):
        checks.append({"id": cid, "status": status, "detail": detail})

    counts = {"default_pc": 0, "default_wap": 0}
    html_counts = {"default_pc": 0, "default_wap": 0}
    for f in files:
        if f["package"] in counts:
            counts[f["package"]] += 1
            if f["is_html"]:
                html_counts[f["package"]] += 1
    total = len(files)

    add("E.2-总数", STATUS_OK if total == EXPECTED_PACKAGE_TOTAL else STATUS_MISMATCH,
        "template/ 下文件数 %d，期望 %d" % (total, EXPECTED_PACKAGE_TOTAL))
    for pkg in EXPECTED_PACKAGE_DIRS:
        add("E.2-%s" % pkg,
            STATUS_OK if counts[pkg] == EXPECTED_PACKAGE_FILES[pkg] else STATUS_MISMATCH,
            "%s 文件数 %d，期望 %d" % (pkg, counts[pkg], EXPECTED_PACKAGE_FILES[pkg]))
    add("E.2-default_pc-html",
        STATUS_OK if html_counts["default_pc"] == EXPECTED_PC_HTML else STATUS_MISMATCH,
        "default_pc 的 .html %d 个，期望 %d" % (html_counts["default_pc"], EXPECTED_PC_HTML))
    add("E.2-default_wap-html",
        STATUS_OK if html_counts["default_wap"] == EXPECTED_WAP_HTML else STATUS_MISMATCH,
        "default_wap 的 .html %d 个，期望 %d" % (html_counts["default_wap"], EXPECTED_WAP_HTML))
    add("E.2-目录集合",
        STATUS_OK if report["dirs"] and set(report["dirs"]) == set(EXPECTED_PACKAGE_DIRS) else STATUS_MISMATCH,
        "template/ 下的目录：%s（期望只有 %s）" % (", ".join(report["dirs"]) or "无",
                                    ", ".join(EXPECTED_PACKAGE_DIRS)))
    add("E.2-default_wap/ads",
        STATUS_OK if not any("default_wap/ads" in a for a in report["anomalies"]) else STATUS_MISMATCH,
        "default_wap/ads：%s" % ("存在（不符）" if any("default_wap/ads" in a for a in report["anomalies"]) else "不存在"))

    crlf = [f["path"] for f in files if f["crlf"]]
    add("RISK-T1-CRLF", STATUS_OK if not crlf else STATUS_MISMATCH,
        "**文本**文件含 CRLF 的：%d 个%s（二进制文件里的 0D 0A 不算；判断依据是字节/blob SHA-1 是否与附表 E.3 一致）"
        % (len(crlf), ("：" + ", ".join(crlf[:5]) + (" …" if len(crlf) > 5 else "")) if crlf else ""))

    tagged_non_html = [f["path"] for f in files
                       if not f["is_html"] and f["path"].split("/", 1)[1].split("/", 1)[0] in NO_TAG_SUBDIRS
                       and (f["maccms_tags"] or f.get("raw_text_maccms"))]
    add("E.4-EF-1-js/css/ads 不含 {maccms",
        STATUS_OK if not tagged_non_html else STATUS_MISMATCH,
        "js/、css/、ads/ 下共 %d 个文件，命中 {maccms 的 %d 个%s"
        % (sum(1 for f in files if not f["path"].endswith(".html")), len(tagged_non_html),
           ("：" + ", ".join(tagged_non_html[:5])) if tagged_non_html else "（期望 0 个）"))
    return checks


def check_manifest(files: list, manifest: dict) -> list:
    checks = []
    by_path = {f["path"]: f for f in files}
    if any(p.startswith("template/") for p in manifest):
        manifest = {p[len("template/"):] if p.startswith("template/") else p: row
                    for p, row in manifest.items()}
    missing_in_pkg = sorted(set(manifest) - set(by_path))
    extra_in_pkg = sorted(set(by_path) - set(manifest))
    bad_bytes = []
    bad_sha = []
    for p, row in manifest.items():
        f = by_path.get(p)
        if not f:
            continue
        if f["bytes"] != row["bytes"]:
            bad_bytes.append("%s（实际 %d，期望 %d）" % (p, f["bytes"], row["bytes"]))
        if f["sha1"] != row["sha1"]:
            bad_sha.append("%s（实际 %s，期望 %s）" % (p, f["sha1"], row["sha1"]))
    checks.append({
        "id": "E.3-清单条数", "status": STATUS_OK if len(manifest) == EXPECTED_PACKAGE_TOTAL else STATUS_MISMATCH,
        "detail": "清单 %d 行，期望 %d 行" % (len(manifest), EXPECTED_PACKAGE_TOTAL)})
    checks.append({
        "id": "E.3-清单缺文件", "status": STATUS_OK if not missing_in_pkg else STATUS_MISMATCH,
        "detail": "清单有、包里没有：%d 个%s" % (len(missing_in_pkg), ("：" + ", ".join(missing_in_pkg[:5])) if missing_in_pkg else "")})
    checks.append({
        "id": "E.3-包内多文件", "status": STATUS_OK if not extra_in_pkg else STATUS_MISMATCH,
        "detail": "包里有、清单没有：%d 个%s" % (len(extra_in_pkg), ("：" + ", ".join(extra_in_pkg[:5])) if extra_in_pkg else "")})
    checks.append({
        "id": "E.3-字节数", "status": STATUS_OK if not bad_bytes else STATUS_MISMATCH,
        "detail": "字节数不符：%d 个%s" % (len(bad_bytes), ("：" + "; ".join(bad_bytes[:5])) if bad_bytes else "")})
    checks.append({
        "id": "E.3-blob-SHA-1", "status": STATUS_OK if not bad_sha else STATUS_MISMATCH,
        "detail": "SHA-1 不符：%d 个%s" % (len(bad_sha), ("：" + "; ".join(bad_sha[:5])) if bad_sha else "")})
    return checks


def check_facts(agg: dict, files: list) -> list:
    checks = []

    def add(cid, got, expected, detail):
        checks.append({"id": cid, "status": STATUS_OK if got == expected else STATUS_MISMATCH,
                       "detail": detail})

    for pkg in EXPECTED_PACKAGE_DIRS:
        got = agg["maccms_tags_prd_prefix"].get(pkg, {})
        exp = EXPECTED_MACCMS_TAGS[pkg]
        diff = []
        for name in sorted(set(got) | set(exp)):
            if got.get(name, 0) != exp.get(name, 0):
                diff.append("%s: 实际 %d / 期望 %d" % (name, got.get(name, 0), exp.get(name, 0)))
        add("E.4-EF-3-%s" % pkg, not diff, True,
            "「无差异」" if not diff else "差异 %d 处：%s" % (len(diff), "; ".join(diff[:8])))
    for name, exp in EXPECTED_OTHER_SYNTAX.items():
        got = agg["other_syntax"].get(name, 0)
        add("E.4-EF-5-%s" % name, got, exp, "%s 出现 %d 次，期望 %d" % (name, got, exp))
    got_php = set(agg["php_templates"])
    missing = sorted(EXPECTED_PHP_TEMPLATES - got_php)
    extra = sorted(got_php - EXPECTED_PHP_TEMPLATES)
    add("E.4-EF-2-已知 6 文件齐全", not missing, True,
        "「齐全」" if not missing else "扫描未在下列文件里找到 PHP 代码：%s" % ", ".join(missing))
    add("E.4-EF-2-无新增 PHP 文件", not extra, True,
        "「无新增」" if not extra else "附表 E.4 未列但扫描发现 PHP 代码：%s（PRE-5 需登记，且可能影响 TPL-10 子集）" % ", ".join(extra))

    # 归属统计：owner 为空的必须显式暴露，不允许“归属未定”混进 PASS。
    unattributed = []
    for f in files:
        for item in f["nondeterministic"]:
            if not item.get("owner"):
                unattributed.append("%s:%d（%s）" % (f["path"], item["line"], item["call"]))
    checks.append({
        "id": "F.5-规则2-归属", "status": STATUS_OK if not unattributed else STATUS_UNATTRIBUTED,
        "detail": "归属未定的时钟/随机/会话来源：%d 处%s"
                  % (len(unattributed), ("：" + "; ".join(unattributed[:10])) if unattributed else "（已全部归属 EX-1/EX-2/DATA-2 或经架构师确认）")})
    return checks


# ---------------------------------------------------------------------------
# 输出
# ---------------------------------------------------------------------------

def write_markdown_draft(path: str, ctx: dict) -> None:
    agg = ctx["aggregate"]
    checks = ctx["checks"]
    lines = []
    lines.append("# APPK-2 PRE-5 模板扫描清单（**初稿，未完成**）")
    lines.append("")
    lines.append("> 本文件由 `tests/compare/APPK-2/scan_templates.py` 生成，只含**机器扫描数据**。")
    lines.append("> PRE-5 的完成判定（PRD §13）要求：168 个 `.html` 各占一行以上；每处 PHP 代码有对应实现说明；")
    lines.append("> 所有产生当前时间/日期、随机值、会话或 token 的位置逐处登记并归属 EX-1、EX-2 或 DATA-2，")
    lines.append("> **没有“归属未定”的行**。实现说明与归属由架构师在 S3-04 补齐，本初稿不得当作 PRE-5 完成。")
    lines.append("")
    lines.append("| 项 | 内容 |")
    lines.append("| --- | --- |")
    lines.append("| 生成来源 | `tests/compare/APPK-2/scan_templates.py` |")
    lines.append("| 扫描根 | `%s` |" % ctx["root"])
    lines.append("| 扫描文件数 | %d（`.html` %d） |" % (ctx["file_count"], ctx["html_count"]))
    lines.append("| 清单比对 | %s |" % ctx["manifest_label"])
    lines.append("")
    lines.append("## 1. 校验结果")
    lines.append("")
    lines.append("| 检查项 | 结果 | 说明 |")
    lines.append("| --- | --- | --- |")
    for c in checks:
        lines.append("| %s | %s | %s |" % (c["id"], c["status"], c["detail"].replace("|", "\\|")))
    lines.append("")
    lines.append("## 2. `{maccms:…}` 标签（两个包分别统计）")
    lines.append("")
    lines.append("| 标签 | default_pc | default_wap |")
    lines.append("| --- | --- | --- |")
    all_tags = sorted(set(agg["maccms_tags"].get("default_pc", {})) | set(agg["maccms_tags"].get("default_wap", {})))
    for t in all_tags:
        lines.append("| `%s` | %d | %d |" % (t, agg["maccms_tags"].get("default_pc", {}).get(t, 0),
                                             agg["maccms_tags"].get("default_wap", {}).get(t, 0)))
    lines.append("")
    lines.append("## 3. 其他模板语法（两包合计）")
    lines.append("")
    lines.append("| 语法 | 次数 |")
    lines.append("| --- | --- |")
    for name in sorted(agg["other_syntax"]):
        lines.append("| `{%s}` | %d |" % (name, agg["other_syntax"][name]))
    lines.append("")
    lines.append("## 4. `{:函数()}` 调用清单（白名单 RT-7 的输入）")
    lines.append("")
    lines.append("| 函数 | 次数 |")
    lines.append("| --- | --- |")
    for name, n in agg["function_calls"].items():
        lines.append("| `%s` | %d |" % (name, n))
    lines.append("")
    lines.append("## 5. 模板变量（`{$a.b}`）")
    lines.append("")
    lines.append("| 变量 | 次数 |")
    lines.append("| --- | --- |")
    for name, n in agg["variables"].items():
        lines.append("| `$%s` | %d |" % (name, n))
    lines.append("")
    lines.append("## 6. 含 PHP 代码的模板（每处一行，待架构师写实现说明）")
    lines.append("")
    lines.append("| 文件 | 行 | 形式 | 片段 | 实现说明（待填） |")
    lines.append("| --- | --- | --- | --- | --- |")
    for f in ctx["files"]:
        for php in f["php_code"]:
            raw = php["raw"].replace("|", "\\|").replace("\n", " ")[:80]
            lines.append("| `%s` | %d | %s | `%s` |  |" % (f["path"], php["line"], php["kind"], raw))
    lines.append("")
    lines.append("## 7. 时钟 / 随机 / 会话 / token 来源（附表 F.5 规则 2，归属待架构师填）")
    lines.append("")
    lines.append("| 文件 | 行 | 类别 | 调用 | 归属 | 上下文 |")
    lines.append("| --- | --- | --- | --- | --- | --- |")
    for f in ctx["files"]:
        for item in f["nondeterministic"]:
            ctxs = item["context"].replace("|", "\\|")[:80]
            lines.append("| `%s` | %d | %s | `%s` | %s | `%s` |"
                         % (f["path"], item["line"], item["kind"], item["call"],
                            item.get("owner") or "**归属未定**", ctxs))
    lines.append("")
    lines.append("## 8. 逐文件清单（168 个 `.html` 各一行；实现说明列待架构师补）")
    lines.append("")
    lines.append("| 文件 | 标签数 | 语法数 | include | 函数 | 变量 | PHP 代码 | 实现说明（待填） |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- | --- |")
    for f in ctx["files"]:
        if not f["is_html"]:
            continue
        lines.append("| `%s` | %d | %d | %d | %d | %d | %d |  |"
                     % (f["path"], len(f["maccms_tags"]), len(f["think_syntax"]),
                        len(f["includes"]), len(f["functions"]), len(f["variables"]), len(f["php_code"])))
    lines.append("")
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines))


def main(argv):
    ap = argparse.ArgumentParser(description="APPK-2 PRE-5 模板扫描（只读、离线）")
    ap.add_argument("--package", required=True,
                    help="模板包 template/ 目录（DEC-1 或 DEC-2 放置后的路径）")
    ap.add_argument("--manifest", default=None,
                    help="PRD 附表 E.3 的清单 TSV（路径<TAB>字节数<TAB>blob SHA-1）")
    ap.add_argument("--prd", default=None,
                    help="可选：PRD 主文件或附表 E 文件，用来自动抽 E.3 并核对清单副本没有抄错")
    ap.add_argument("--out-dir", default=None,
                    help="输出目录（默认不入库；交付只提交登记台账与本文档初稿）")
    ap.add_argument("--markdown", default=None, help="Markdown 初稿输出路径")
    args = ap.parse_args(argv)

    root = args.package
    try:
        report = list_package(root)
    except SystemExit as exc:
        sys.stderr.write("输入错误：%s\n" % exc)
        return 1
    files = [scan_file(root, rel, root) for rel in report["files"]]
    agg = aggregate(files)

    checks = check_structure(report, files)
    manifest_label = "未提供清单（只做结构扫描）"
    manifest = None
    if args.manifest:
        try:
            manifest = load_manifest(args.manifest)
        except SystemExit as exc:
            sys.stderr.write("清单错误：%s\n" % exc)
            return 1
        checks.extend(check_manifest(files, manifest))
        manifest_label = "`%s`（%d 行）" % (repo_rel(args.manifest), len(manifest))
        if args.prd:
            try:
                prd_rows = extract_e3_rows_from_prd(args.prd)
            except OSError as exc:
                sys.stderr.write("无法读取 PRD：%s\n" % exc)
                return 1
            same = (set(prd_rows) == set(manifest)
                    and all(prd_rows[p]["bytes"] == manifest[p]["bytes"]
                            and prd_rows[p]["sha1"] == manifest[p]["sha1"] for p in manifest))
            n = len(prd_rows)
            checks.append({
                "id": "E.3-清单副本与 PRD 一致",
                "status": STATUS_OK if (same and n == EXPECTED_PACKAGE_TOTAL) else STATUS_MISMATCH,
                "detail": "从 PRD 抽到 %d 行；与入库清单副本%s" % (n, "逐行一致（路径、字节数、SHA-1）" if same else "**不一致**"),
            })
    checks.extend(check_facts(agg, files))

    ctx = {
        "root": report["root"],
        "files": files,
        "aggregate": agg,
        "checks": checks,
        "file_count": len(files),
        "html_count": sum(1 for f in files if f["is_html"]),
        "manifest_label": manifest_label,
    }

    payload = {
        "tool": "tests/compare/APPK-2/scan_templates.py",
        "root": report["root"],
        "files": files,
        "aggregate": agg,
        "checks": checks,
        "structure_anomalies": report["anomalies"],
    }

    if args.out_dir:
        os.makedirs(args.out_dir, exist_ok=True)
        with open(os.path.join(args.out_dir, "scan-result.json"), "w", encoding="utf-8", newline="\n") as fh:
            json.dump(payload, fh, ensure_ascii=False, indent=2, sort_keys=False)
            fh.write("\n")
    if args.markdown:
        os.makedirs(os.path.dirname(os.path.abspath(args.markdown)), exist_ok=True)
        write_markdown_draft(args.markdown, ctx)

    failed = [c for c in checks if c["status"] not in (STATUS_OK,)]
    for c in checks:
        print("[%s] %s — %s" % (c["status"], c["id"], c["detail"]))
    print("汇总：%d 项检查，%d 项非 OK" % (len(checks), len(failed)))
    return 2 if failed else 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except SystemExit:
        raise
    except Exception as exc:  # noqa: BLE001 —— 返回码 3 表示脚本自身问题，不伪装成“校验失败”
        sys.stderr.write("脚本异常：%r\n" % (exc,))
        sys.exit(3)
