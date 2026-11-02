#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""APPK-2 C-8 / C-63：对照环境装配（ENV-1～ENV-5、SNAPD-4、SNAPD-5、PHPB-9）。

技术设计 v15 原文（C-8，v3；C-63 为 v3 增补）：

    C-8  按 §7.3 的 ENV-1～ENV-4 装配 `.compare/APPK-2/pristine/`，再复制出
         `php-site/`、`java-site/`；对 `maccms.php` 只做 C-7 列出键的**整行字面替换**
         （每个键在基线文件里恰好出现一次，替换前后都断言），不重新序列化文件；
         输出 13 个 CS-CONFIG 文件的 SHA-256。
    C-63 ENV-2 的 `database.php` 加会话时区（SNAPD-4）；`vendor/` 摘要（SNAPD-5）。

规则逐条对应：

* ENV-1  装配目录 `.compare/APPK-2/`（已 gitignore，C-6）。`pristine/` = 被测提交里 `php/`
  的**已跟踪文件**，用 `git -c core.autocrlf=false archive <提交> php/` 导出（不从工作区复制，
  避免本机 `core.autocrlf=true` 把 LF 改成 CRLF，触发 NOIGN-4），**去掉 `php/template/`**，
  再放入 DEC-1 的 `template/default_pc`、`template/default_wap`。
* ENV-2  `pristine/vendor/` 从仓库外生产备份恢复；`pristine/application/data/install/install.lock`
  写固定内容 `APPK-2`；`pristine/application/database.php` 从环境变量
  `MACCMS_DB_URL`/`MACCMS_DB_USERNAME`/`MACCMS_DB_PASSWORD` 生成（SNAPD-4：加
  `'params' => [PDO::MYSQL_ATTR_INIT_COMMAND => "SET time_zone='+08:00'"]`），**脚本不打印取值**。
* ENV-3  `pristine/application/extra/maccms.php` 只按 C-7 的 7 个键整行替换；其余 12 个
  CS-CONFIG 不改。装配后输出 13 个文件的 SHA-256。
* ENV-4  `php-site/`、`java-site/` 都由 `pristine/` 完整复制；两侧 13 个 CS-CONFIG 的
  SHA-256 逐个相同（PHPB-9）。
* ENV-5  （本脚本实现为 `--check-configs <侧站点根>`，供 C-64 每个请求单元开始时调用）
  13 个 CS-CONFIG 与 `pristine/` 比 SHA-256，不同则从 `pristine/` 复制回去并报告
  `restored` 清单，供恢复记录注明。
* SNAPD-5 基线报告记录 `vendor/` 的文件数与按路径排序后的 SHA-256 汇总值。

边界（本单是只写阶段，与 S3-03 派工说明一致）：

* **不连接数据库、不启动 PHP/Java、不发任何 HTTP 请求、不读凭据值到输出**。
  它只读 git 对象与仓库外备份文件（只列条目名与算哈希，不解压到仓库）。
* `--db-url` 等三项只从**环境变量**读取，用于生成 `database.php` 的取值；日志里只出现
  “已设置/未设置”，取值本身绝不打印、不写进报告。
* 生产备份 `D:\\Ai\\MACCMS\\maccms10-prod-20260711.zip` 只在本机；**缺失时 SNAPD-5 部分记
  BLOCKED，不假装已恢复**，其余部分照常装配并把结论写进报告。

用法（工作目录 = 仓库根）::

    python tests/compare/APPK-2/assemble_site.py assemble --commit <被测提交>
    python tests/compare/APPK-2/assemble_site.py assemble --commit <被测提交> \\
        --report docs/test/APPK-2/php-baseline-report.md
    python tests/compare/APPK-2/assemble_site.py check-configs --site .compare/APPK-2/php-site
    python tests/compare/APPK-2/assemble_site.py selftest

返回码：

    0  装配完成且全部断言通过（V-3 的 13 个文件两侧同哈希、C-7 的 7 个键各一处且取值正确）
    1  用法或输入错误（缺提交、缺工作区、装配目录已存在等）
    2  断言不符（**这是缺陷/环境问题信号，不是脚本崩溃**）
    3  BLOCKED：材料缺（缺生产备份 → SNAPD-5 无法完成）；装配本身已完成，报告里写明缺什么
    4  脚本自身异常
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
# tests/compare/APPK-2 → 上三级才是仓库根
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))

COMPARE_ROOT_REL = os.path.join(".compare", "APPK-2")
PRistine = "pristine"
PHP_SITE = "php-site"
JAVA_SITE = "java-site"

OVERLAY_PATH = os.path.join(HERE, "maccms-overlay.json")

# DEC-1：模板包在仓库内的位置（S3-01 导入）。
TEMPLATE_PKG_REL = os.path.join("third_party", "maccms-template-v2020.1000.1035", "template")

# D.2 CS-CONFIG：12 个 application/extra/*.php + application/route.php
CS_CONFIG_EXTRA = (
    "addons.php", "bind.php", "captcha.php", "domain.php", "maccms.php", "queue.php",
    "quickmenu.php", "timming.php", "version.php", "voddowner.php", "vodplayer.php",
    "vodserver.php",
)
CS_CONFIG = [("application/extra/" + n).replace("/", os.sep) for n in CS_CONFIG_EXTRA] + [
    os.path.join("application", "route.php")
]
assert len(CS_CONFIG) == 13

# SNAPD-4：PHP 侧会话时区写进 database.php 的 params。
SESSION_TIME_ZONE = "+08:00"
TIME_ZONE_PARAMS_LINE = (
    "    'params'          => [PDO::MYSQL_ATTR_INIT_COMMAND => \"SET time_zone='"
    + SESSION_TIME_ZONE
    + "'\"],\n"
)

# 仓库外生产备份（AGENTS.md「本地运行与测试」第 1 步）；只在本机。
PROD_BACKUP_DEFAULT = r"D:\Ai\MACCMS\maccms10-prod-20260711.zip"

INSTALL_LOCK_CONTENT = "APPK-2"

# ENV-1：导出时去掉 php/template/
EXCLUDED_ARCHIVE_PREFIXES = ("php/template/",)

RE_SITE_SECTION = re.compile(r"^(\s*)'site'\s*=>\s*$")


def die(code: int, msg: str) -> "None":
    sys.stderr.write(msg.rstrip() + "\n")
    raise SystemExit(code)


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def require(ok: bool, msg: str) -> None:
    if not ok:
        raise AssertionError(msg)


# --------------------------------------------------------------------------
# ENV-1：git archive 导出 php/ 的已跟踪文件
# --------------------------------------------------------------------------


def git_out(args: list, cwd: str = REPO_ROOT, binary: bool = False):
    p = subprocess.run(["git"] + args, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode != 0:
        raise RuntimeError("git %s 失败：%s" % (" ".join(args), p.stderr.decode("utf-8", "replace").strip()))
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def resolve_commit(commit: str) -> str:
    out = git_out(["rev-parse", "--verify", commit + "^{commit}"]).strip()
    return out


def export_php_tree(commit: str, dest: str) -> list:
    """ENV-1：git -c core.autocrlf=false archive <commit> php/，去掉 php/template/，展开到 dest。

    git archive 的条目名带 `php/` 前缀；ENV-1 要求 `pristine/` **就是站点根**（`pristine/application/...`），
    所以这里把前缀去掉再展开。
    """
    raw = git_out(["-c", "core.autocrlf=false", "archive", "--format=tar", commit, "php/"], binary=True)
    import io
    import tarfile

    prefix = "php/"
    extracted = []
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as tf:
        for member in tf.getmembers():
            name = member.name
            if name in ("php", "php/"):
                continue
            if name.startswith(EXCLUDED_ARCHIVE_PREFIXES):
                continue
            require(name.startswith(prefix), "git archive 条目不在 php/ 下：%s" % name)
            rel = name[len(prefix):]
            if not member.isfile():
                # 目录、符号链接等：ENV-1 只要普通文件；符号链接出现即为异常输入
                require(member.isdir(), "git archive 里出现非普通文件条目：%s" % name)
                continue
            require(rel and not os.path.isabs(rel) and ".." not in rel.split("/"),
                    "git archive 条目路径越界：%s" % name)
            target = os.path.join(dest, rel.replace("/", os.sep))
            os.makedirs(os.path.dirname(target), exist_ok=True)
            data = tf.extractfile(member).read()
            with open(target, "wb") as fh:
                fh.write(data)
            extracted.append(rel)
    return extracted


# --------------------------------------------------------------------------
# DEC-1：放入模板包
# --------------------------------------------------------------------------


def place_template_package(commit: str, pristine: str) -> dict:
    """把 DEC-1 的 template/default_pc、template/default_wap 放进 pristine/template/。"""
    tpl_dst = os.path.join(pristine, "template")
    raw = git_out(["-c", "core.autocrlf=false", "ls-tree", "-r", "-z", "--name-only", commit,
                   TEMPLATE_PKG_REL.replace(os.sep, "/")], binary=True)
    names = [n for n in raw.decode("utf-8").split("\0") if n]
    require(names, "提交 %s 里没有模板包目录 %s（S3-01 未合入？）" % (commit[:12], TEMPLATE_PKG_REL))

    count = 0
    per_dir = {}
    for full in names:
        rel = full[len(TEMPLATE_PKG_REL.replace(os.sep, "/")) + 1:]
        top = rel.split("/", 1)[0]
        require(top in ("default_pc", "default_wap"),
                "模板包出现预期外顶层目录：%s" % rel)
        per_dir[top] = per_dir.get(top, 0) + 1
        data = git_out(["show", commit + ":" + full], binary=True)
        dst = os.path.join(tpl_dst, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with open(dst, "wb") as fh:
            fh.write(data)
        count += 1
    return {"files": count, "per_dir": per_dir}


# --------------------------------------------------------------------------
# ENV-2：vendor/、install.lock、database.php
# --------------------------------------------------------------------------


def restore_vendor(backup_zip: str, pristine: str) -> dict:
    """SNAPD-5：从仓库外生产备份解压 vendor/，返回文件数与排序后的 SHA-256 汇总值。

    与 AGENTS.md「本地运行与测试」第 1 步同一算法：只写 vendor/ 内的文件，遇到解析后落在
    vendor/ 之外的条目立即中止；跳过 vendor/.gitignore。
    """
    vendor_root = os.path.realpath(os.path.join(pristine, "vendor"))
    os.makedirs(vendor_root, exist_ok=True)

    hashes = []
    with zipfile.ZipFile(backup_zip) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            parts = info.filename.split("/", 1)
            if len(parts) != 2 or not parts[1].startswith("vendor/"):
                continue
            if parts[1] == "vendor/.gitignore":
                continue
            dst = os.path.realpath(os.path.join(pristine, parts[1]))
            if not dst.startswith(vendor_root + os.sep) and dst != vendor_root:
                raise RuntimeError("拒绝解压到 vendor/ 之外的路径：%s" % info.filename)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            data = z.read(info)
            with open(dst, "wb") as fh:
                fh.write(data)
            rel = parts[1].replace("/", os.sep)
            hashes.append((rel, hashlib.sha256(data).hexdigest()))

    hashes.sort(key=lambda kv: kv[0])
    aggregate = hashlib.sha256()
    for rel, h in hashes:
        aggregate.update(("%s\t%s\n" % (rel.replace(os.sep, "/"), h)).encode("utf-8"))
    return {"files": len(hashes), "aggregate_sha256": aggregate.hexdigest()}


def write_install_lock(pristine: str) -> str:
    path = os.path.join(pristine, "application", "data", "install", "install.lock")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(INSTALL_LOCK_CONTENT.encode("ascii"))
    return os.path.relpath(path, pristine).replace(os.sep, "/")


def parse_jdbc_url(url: str) -> dict:
    """从 MACCMS_DB_URL 取出 host/port/database，并保留全部查询参数。

    支持形如 jdbc:mysql://127.0.0.1:3306/maccms_it_full?characterEncoding=UTF-8&useSSL=false
    """
    require(url.startswith("jdbc:mysql://"), "MACCMS_DB_URL 不是 jdbc:mysql:// 形式（取值不打印）")
    rest = url[len("jdbc:mysql://"):]
    if "?" in rest:
        hostpart, query = rest.split("?", 1)
    else:
        hostpart, query = rest, ""
    require("/" in hostpart, "MACCMS_DB_URL 缺少库名（取值不打印）")
    hostport, database = hostpart.split("/", 1)
    database = database.strip()
    require(database != "", "MACCMS_DB_URL 缺少库名（取值不打印）")
    if ":" in hostport:
        host, port = hostport.rsplit(":", 1)
    else:
        host, port = hostport, "3306"
    require(host != "", "MACCMS_DB_URL 缺少主机名（取值不打印）")
    return {"host": host, "port": port, "database": database, "query": query}


def php_quote(value: str) -> str:
    """PHP 单引号字符串转义（只转义 \\ 与 '，与 PHP 语义一致）。"""
    return value.replace("\\", "\\\\").replace("'", "\\'")


def generate_database_php(pristine: str, env: dict) -> dict:
    """ENV-2 + SNAPD-4：从 Java 同名环境变量生成 database.php，不打印取值。"""
    url = env.get("MACCMS_DB_URL", "")
    user = env.get("MACCMS_DB_USERNAME", "")
    passwd = env.get("MACCMS_DB_PASSWORD", "")
    missing = [k for k, v in (("MACCMS_DB_URL", url), ("MACCMS_DB_USERNAME", user),
                              ("MACCMS_DB_PASSWORD", passwd)) if not v]
    require(not missing, "ENV-2 缺环境变量：%s（组合装配需要它生成 database.php）" % ", ".join(missing))

    info = parse_jdbc_url(url)
    src = os.path.join(pristine, "application", "database.php")
    require(os.path.isfile(src), "pristine/application/database.php 不存在（ENV-1 导出失败？）")
    with open(src, "rb") as fh:
        text = fh.read().decode("utf-8")

    # 只替换取值，不重新序列化：每个键在基线文件里必须恰好出现一次。
    subs = [
        ("hostname", info["host"]),
        ("database", info["database"]),
        ("username", user),
        ("password", passwd),
        ("hostport", info["port"]),
    ]
    applied = []
    for key, value in subs:
        pattern = re.compile(r"^(\s*'%s'\s*=>\s*)'((?:[^'\\]|\\.)*)'(,?)\s*$" % re.escape(key), re.M)
        hits = pattern.findall(text)
        require(len(hits) == 1,
                "ENV-2 断言失败：database.php 里键 '%s' 出现 %d 次（必须恰好 1 次）" % (key, len(hits)))
        text = pattern.sub(lambda m: "%s'%s'%s\n" % (m.group(1), php_quote(value), m.group(3)), text, count=1)
        applied.append(key)

    # SNAPD-4：params 加会话时区（ENV-10 的 PHP 侧）。
    params_pattern = re.compile(r"^(\s*)'params'\s*=>\s*\[\]\s*,?\s*$", re.M)
    params_hits = params_pattern.findall(text)
    require(len(params_hits) == 1,
            "SNAPD-4 断言失败：database.php 里 'params' => [] 出现 %d 次（必须恰好 1 次）" % len(params_hits))
    text = params_pattern.sub(TIME_ZONE_PARAMS_LINE.rstrip("\n"), text, count=1)
    require(TIME_ZONE_PARAMS_LINE.rstrip("\n") in text, "SNAPD-4 断言失败：会话时区未写入 params")

    with open(src, "wb") as fh:
        fh.write(text.encode("utf-8"))
    return {"keys": applied, "session_time_zone": SESSION_TIME_ZONE, "database_php": "application/database.php"}


# --------------------------------------------------------------------------
# ENV-3：maccms.php 的 7 键整行替换
# --------------------------------------------------------------------------


def load_overlay(path: str = OVERLAY_PATH) -> dict:
    with open(path, "r", encoding="utf-8") as fh:
        data = json.load(fh)
    keys = data.get("keys")
    require(isinstance(keys, dict), "C-7 覆盖表缺 keys 对象")
    expected = {"template_dir", "html_dir", "mob_template_dir", "mob_html_dir",
                "ads_dir", "mob_ads_dir", "mob_status"}
    require(set(keys) == expected,
            "C-7 覆盖表键集合必须恰为 7 项，实际多出 %s、缺少 %s"
            % (sorted(set(keys) - expected) or "无", sorted(expected - set(keys)) or "无"))
    require(data.get("section") == "site", "C-7 覆盖表的 section 必须是 site")
    return data


def apply_overlay(pristine: str, overlay: dict) -> dict:
    """ENV-3：对 pristine/application/extra/maccms.php 只按 C-7 的 7 个键做整行字面替换。"""
    path = os.path.join(pristine, "application", "extra", "maccms.php")
    require(os.path.isfile(path), "pristine/application/extra/maccms.php 不存在（ENV-1 导出失败？）")
    with open(path, "rb") as fh:
        raw = fh.read()
    text = raw.decode("utf-8")
    lines = text.splitlines(keepends=True)

    # 定位 site 节的区间：'site' => 之后到下一个顶层节为止。
    start = None
    for i, line in enumerate(lines):
        m = RE_SITE_SECTION.match(line.rstrip("\r\n"))
        if m:
            require(start is None, "ENV-3 断言失败：maccms.php 里出现多个 'site' 节")
            start = i
    require(start is not None, "ENV-3 断言失败：maccms.php 里找不到 'site' 节")

    # 结构（var_export 格式，见 php/application/extra/maccms.php）：
    #   'site' => \n  array (\n    'k' => 'v',\n  ),\n
    # 先跳过紧随其后的 array ( 行，再按“子项缩进”取到收尾的 ), 为止。
    site_indent = len(lines[start]) - len(lines[start].lstrip())
    cursor = start + 1
    while cursor < len(lines) and not lines[cursor].strip():
        cursor += 1
    require(cursor < len(lines) and lines[cursor].strip() == "array (",
            "ENV-3 断言失败：'site' 节后不是 array ( 形式的 var_export 数组")
    cursor += 1

    child_indent = None
    end = None
    for i in range(cursor, len(lines)):
        line = lines[i]
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip())
        if line.strip() in (")", "),") and indent <= site_indent + 2:
            end = i + 1  # 保留收尾行
            break
        if indent <= site_indent:
            end = i
            break
        if child_indent is None:
            child_indent = indent
    if end is None:
        end = len(lines)

    section = "".join(lines[start:end])
    changed = []
    for key, value in overlay["keys"].items():
        pattern = re.compile(r"^(\s*)'%s'(\s*)=>\s*'((?:[^'\\]|\\.)*)'(\s*,?)[ \t]*$" % re.escape(key), re.M)
        hits = pattern.findall(section)
        require(len(hits) == 1,
                "ENV-3/C-7 断言失败：site 节里键 '%s' 出现 %d 次（必须恰好 1 次）" % (key, len(hits)))
        # 只换取值，缩进/箭头/逗号/行尾一律照原样（整行字面替换，不重新序列化）。
        section = pattern.sub(
            lambda m: "%s'%s'%s=> '%s'%s" % (m.group(1), key, m.group(2),
                                              php_quote(value), m.group(4)),
            section, count=1)
        changed.append(key)

    text = "".join(lines[:start]) + section + "".join(lines[end:])
    # 替换后复验：其余 12 个 CS-CONFIG 之外的同名键不得被动到，所以复验也只看 site 节。
    for key, value in overlay["keys"].items():
        pattern = re.compile(r"^(\s*)'%s'\s*=>\s*'((?:[^'\\]|\\.)*)'\s*,?\s*$" % re.escape(key), re.M)
        hits = pattern.findall(section)
        require(len(hits) == 1,
                "ENV-3/C-7 断言失败（替换后）：site 节里键 '%s' 出现 %d 次" % (key, len(hits)))
        require(hits[0][1] == php_quote(value),
                "ENV-3/C-7 断言失败（替换后）：键 '%s' 取值不是预期值" % key)

    with open(path, "wb") as fh:
        fh.write(text.encode("utf-8"))

    # V-3：除这 7 行外与基线逐字节相同 → diff 只出现 7 行改动。
    diff_lines = count_changed_lines(raw.decode("utf-8").splitlines(), text.splitlines())
    return {"keys": changed, "diff_lines": diff_lines}


def count_changed_lines(before: list, after: list) -> int:
    """用 difflib 数出改动行数（删除 + 新增各计一行，与 `diff` 的输出行数口径一致）。"""
    import difflib

    n = 0
    for line in difflib.unified_diff(before, after, n=0, lineterm=""):
        if line.startswith(("---", "+++", "@@")):
            continue
        if line.startswith(("+", "-")):
            n += 1
    return n


# --------------------------------------------------------------------------
# ENV-4 / V-3：13 个 CS-CONFIG 的 SHA-256，两侧逐个相同
# --------------------------------------------------------------------------


def config_hashes(site_root: str) -> dict:
    out = {}
    for rel in CS_CONFIG:
        path = os.path.join(site_root, rel)
        require(os.path.isfile(path), "CS-CONFIG 文件缺失：%s" % rel.replace(os.sep, "/"))
        out[rel.replace(os.sep, "/")] = sha256_file(path)
    return out


def compare_config_hashes(left: dict, right: dict, left_name: str, right_name: str) -> list:
    problems = []
    for rel in sorted(left):
        if left[rel] != right.get(rel):
            problems.append("%s(%s) != %s(%s)" % (left_name, rel, right_name, rel))
    return problems


def copy_side(pristine: str, side: str) -> None:
    """ENV-4：由 pristine/ 完整复制出该侧站点根。"""
    if os.path.isdir(side):
        shutil.rmtree(side)
    shutil.copytree(pristine, side)


# --------------------------------------------------------------------------
# ENV-5：单元开始前的配置回滚
# --------------------------------------------------------------------------


def check_configs(site_root: str, pristine: str) -> dict:
    """ENV-5：13 个 CS-CONFIG 与 pristine 比 SHA-256，不同则从 pristine 复制回去。"""
    require(os.path.isdir(pristine), "找不到 pristine：%s" % pristine)
    base = config_hashes(pristine)
    current = config_hashes(site_root)
    restored = []
    for rel, h in base.items():
        if current[rel] != h:
            src = os.path.join(pristine, rel.replace("/", os.sep))
            dst = os.path.join(site_root, rel.replace("/", os.sep))
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(src, dst)
            restored.append(rel)
    after = config_hashes(site_root)
    bad = compare_config_hashes(base, after, "pristine", "site")
    require(not bad, "ENV-5 回滚后仍不一致：%s" % "; ".join(bad))
    return {"restored": restored, "checked": len(base)}


# --------------------------------------------------------------------------
# 报告
# --------------------------------------------------------------------------


def render_report(result: dict) -> str:
    lines = []
    lines.append("# APPK-2 PRE-2 对照环境基线报告（assemble_site.py 自动生成）\n")
    lines.append("> 由 `tests/compare/APPK-2/assemble_site.py` 生成，只含脚本产物；"
                 "手写补充（PHP 版本、`php -m`、`ini_get` 实际值、`setlocale` 实际返回）"
                 "由 PRE-2 手工追加在本文件后半段，不被脚本覆盖的段落以“## 自动”开头标注。\n")
    lines.append("## 自动：装配摘要\n")
    lines.append("| 项 | 值 |\n| --- | --- |")
    lines.append("| 被测提交（ENV-1 php/ 来源） | `%s` |" % result["php_commit"])
    if result.get("template_commit") and result["template_commit"] != result["php_commit"]:
        lines.append("| 模板包提交（DEC-1 来源） | `%s` |" % result["template_commit"])
    lines.append("| 装配目录 | `.compare/APPK-2/` |")
    lines.append("| ENV-1 导出 php/ 文件数（已去掉 php/template/） | %s |"
                 % (result["archive_files"] if "archive_files" in result else "**未完成**"))
    tpl = result.get("template")
    lines.append("| DEC-1 模板包文件数 | %s |" % (
        "%d（default_pc %d / default_wap %d）" % (tpl["files"],
                                                 tpl["per_dir"].get("default_pc", 0),
                                                 tpl["per_dir"].get("default_wap", 0))
        if tpl else "**未完成**"))
    lines.append("| ENV-2 install.lock 内容 | `%s` |" % INSTALL_LOCK_CONTENT)
    db = result.get("db")
    lines.append("| SNAPD-4 会话时区 | %s |" % (
        "`%s`（写入 database.php 的 params）" % db["session_time_zone"] if db else "**未完成**"))
    lines.append("| SNAPD-4 database.php 已替换的键 | %s |"
                 % (", ".join("`%s`" % k for k in db["keys"]) if db else "**未完成**"))
    ov = result.get("overlay")
    lines.append("| ENV-3 C-7 替换的键（7 个） | %s |"
                 % (", ".join("`%s`" % k for k in ov["keys"]) if ov else "**未完成**"))
    lines.append("| ENV-3 maccms.php 与基线相比的 diff 行数（只计取值真的变了的键，每键 2 行） | %s |"
                 % (ov["diff_lines"] if ov else "**未完成**"))
    lines.append("")

    vendor = result.get("vendor")
    lines.append("## 自动：SNAPD-5 vendor/ 摘要\n")
    if vendor is None:
        lines.append("**BLOCKED**：仓库外生产备份 `%s` 不存在，`vendor/` 未恢复。"
                     "SNAPD-5 未完成——无法运行对照 PHP（验证码会 500），也无法记录 "
                     "`vendor/` 文件数与 SHA-256 汇总值。补料后重跑本脚本同一命令即可。\n"
                     % PROD_BACKUP_DEFAULT.replace("\\", "\\\\"))
    else:
        lines.append("| 项 | 值 |\n| --- | --- |")
        lines.append("| `vendor/` 文件数 | %d |" % vendor["files"])
        lines.append("| SHA-256 汇总值（按路径排序后逐行 `路径\\t文件SHA-256` 再取 SHA-256） | `%s` |"
                     % vendor["aggregate_sha256"])
        lines.append("")

    lines.append("## 自动：ENV-3/ENV-4 13 个 CS-CONFIG 文件的 SHA-256\n")
    lines.append("| 文件 | pristine / php-site / java-site（三者必须相同） |\n| --- | --- |")
    for rel in sorted(result.get("configs", {})):
        h = result["configs"][rel]
        lines.append("| `%s` | `%s` |" % (rel, h))
    lines.append("")

    lines.append("## 自动：V-3 判定\n")
    if result["problems"]:
        lines.append("**不符**（共 %d 项）：\n" % len(result["problems"]))
        for p in result["problems"]:
            lines.append("- %s" % p)
        lines.append("")
    else:
        lines.append("13 个 CS-CONFIG 文件在 `pristine/`、`php-site/`、`java-site/` 三方逐个 SHA-256 相同；"
                     "`maccms.php` 只改 C-7 的 7 行，其余行与基线逐字节相同。V-3 的脚本部分满足。\n")
    return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------
# assemble
# --------------------------------------------------------------------------


def do_assemble(args, env: dict) -> int:
    compare_root = os.path.join(REPO_ROOT, COMPARE_ROOT_REL)
    pristine = os.path.join(compare_root, PRistine)
    php_site = os.path.join(compare_root, PHP_SITE)
    java_site = os.path.join(compare_root, JAVA_SITE)
    report_path = args.report
    if report_path and not os.path.isabs(report_path):
        report_path = os.path.join(REPO_ROOT, report_path)

    try:
        php_commit = resolve_commit(args.php_commit or args.commit)
        tpl_commit = resolve_commit(args.template_commit or php_commit)
    except RuntimeError as exc:
        die(1, "无法解析提交 %s：%s" % (args.commit, exc))

    if os.path.isdir(pristine) and not args.force:
        die(1, "装配目录已存在：%s（确认要重建请加 --force）" % os.path.relpath(pristine, REPO_ROOT))
    if not os.path.isdir(pristine):
        os.makedirs(pristine, exist_ok=True)

    result = {"commit": php_commit, "php_commit": php_commit, "template_commit": tpl_commit,
              "problems": []}
    try:
        # ENV-1
        archived = export_php_tree(php_commit, pristine)
        result["archive_files"] = len(archived)

        # DEC-1 模板包
        result["template"] = place_template_package(tpl_commit, pristine)

        # ENV-2（database.php 需要三条环境变量；缺则整体判输入错误，不静默跳过）
        result["db"] = generate_database_php(pristine, env)
        result["install_lock"] = write_install_lock(pristine)

        # SNAPD-5
        backup = args.backup_zip or PROD_BACKUP_DEFAULT
        if os.path.isfile(backup):
            result["vendor"] = restore_vendor(backup, pristine)
        else:
            result["vendor"] = None

        # ENV-3
        overlay = load_overlay(args.overlay)
        result["overlay"] = apply_overlay(pristine, overlay)

        # ENV-4
        copy_side(pristine, php_site)
        copy_side(pristine, java_site)

        base = config_hashes(pristine)
        left = config_hashes(php_site)
        right = config_hashes(java_site)
        result["configs"] = base
        result["problems"] += compare_config_hashes(base, left, "pristine", "php-site")
        result["problems"] += compare_config_hashes(base, right, "pristine", "java-site")
        result["problems"] += compare_config_hashes(left, right, "php-site", "java-site")
    except AssertionError as exc:
        result["problems"].append(str(exc))
    except (RuntimeError, OSError) as exc:
        die(4, "装配过程异常：%s" % exc)

    text = render_report(result)
    if report_path:
        os.makedirs(os.path.dirname(report_path), exist_ok=True)
        with open(report_path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
        sys.stdout.write("报告已写入 %s\n" % os.path.relpath(report_path, REPO_ROOT).replace(os.sep, "/"))
    sys.stdout.write(text)

    if result["problems"]:
        sys.stdout.write("结论：MISMATCH（%d 项，见上文）\n" % len(result["problems"]))
        return 2
    if result["vendor"] is None:
        sys.stdout.write("结论：BLOCKED（SNAPD-5 缺仓库外生产备份；其余装配项通过）\n")
        return 3
    sys.stdout.write("结论：OK\n")
    return 0


# --------------------------------------------------------------------------
# selftest（离线，不需要 git 提交、数据库或仓库外备份）
# --------------------------------------------------------------------------


def do_selftest(_args) -> int:
    import tempfile

    failures = []
    checks = [0]

    def check(name, ok, detail=""):
        checks[0] += 1
        if not ok:
            failures.append("%s%s" % (name, ("：" + detail) if detail else ""))
        sys.stdout.write("%s %s%s\n" % ("PASS" if ok else "FAIL", name, ("：" + detail) if detail and not ok else ""))

    # ---- C-7 覆盖表 -----------------------------------------------------
    ov = load_overlay(OVERLAY_PATH)
    check("C-7 覆盖表恰 7 个键", len(ov["keys"]) == 7)
    check("C-7 section=site", ov["section"] == "site")
    check("C-7 template_dir=default_pc", ov["keys"]["template_dir"] == "default_pc")
    check("C-7 mob_template_dir=default_wap", ov["keys"]["mob_template_dir"] == "default_wap")
    check("C-7 mob_status=2", ov["keys"]["mob_status"] == "2")

    tmp = tempfile.mkdtemp(prefix="appk2-s303-assemble-")
    try:
        # 造一份最小 maccms.php（与仓库基线同缩进口径）
        minimal = (
            "<?php\nreturn array (\n  'site' => \n  array (\n"
            "    'site_name' => 'x',\n"
            "    'template_dir' => 'B1',\n"
            "    'html_dir' => 'B2',\n"
            "    'mob_status' => 'B3',\n"
            "    'mob_template_dir' => 'B4',\n"
            "    'mob_html_dir' => 'B5',\n"
            "    'ads_dir' => 'B6',\n"
            "    'mob_ads_dir' => 'B7',\n"
            "  ),\n  'app' => \n  array (\n    'template_dir' => 'not-site',\n  ),\n);\n"
        )
        pr = os.path.join(tmp, "pristine")
        os.makedirs(os.path.join(pr, "application", "extra"))
        with open(os.path.join(pr, "application", "extra", "maccms.php"), "w",
                  encoding="utf-8", newline="\n") as fh:
            fh.write(minimal)
        res = apply_overlay(pr, ov)
        with open(os.path.join(pr, "application", "extra", "maccms.php"), encoding="utf-8") as fh:
            after = fh.read()
        check("ENV-3 替换了 7 个键", sorted(res["keys"]) == sorted(ov["keys"]))
        check("ENV-3 diff 行数 = 14", res["diff_lines"] == 14, "实际 %d" % res["diff_lines"])
        check("ENV-3 取值已生效", "'template_dir' => 'default_pc'," in after
              and "'mob_status' => '2'," in after)
        check("ENV-3 未改 site 节以外的同名键", "'template_dir' => 'not-site'," in after)

        # 反例：同名键出现两次 → 必须断言失败
        dup = minimal.replace("    'html_dir' => 'B2',\n", "    'html_dir' => 'B2',\n    'html_dir' => 'B2',\n")
        pr2 = os.path.join(tmp, "p2")
        os.makedirs(os.path.join(pr2, "application", "extra"))
        with open(os.path.join(pr2, "application", "extra", "maccms.php"), "w",
                  encoding="utf-8", newline="\n") as fh:
            fh.write(dup)
        try:
            apply_overlay(pr2, ov)
            check("ENV-3 键重复必须断言失败", False, "竟然通过了")
        except AssertionError:
            check("ENV-3 键重复必须断言失败", True)

        # ---- database.php / SNAPD-4 --------------------------------------
        dbsrc = (
            "<?php\nreturn [\n"
            "    'type'            => 'mysql',\n"
            "    'hostname'        => '127.0.0.1',\n"
            "    'database'        => 'maccms_dev',\n"
            "    'username'        => 'root',\n"
            "    'password'        => 'old',\n"
            "    'hostport'        => '3306',\n"
            "    'params'          => [],\n"
            "    'charset'         => 'utf8',\n"
            "];\n"
        )
        pr3 = os.path.join(tmp, "p3")
        os.makedirs(os.path.join(pr3, "application"))
        with open(os.path.join(pr3, "application", "database.php"), "w",
                  encoding="utf-8", newline="\n") as fh:
            fh.write(dbsrc)
        env_fake = {
            "MACCMS_DB_URL": "jdbc:mysql://127.0.0.1:3307/maccms_it_full?characterEncoding=UTF-8&useSSL=false",
            "MACCMS_DB_USERNAME": "it_app",
            "MACCMS_DB_PASSWORD": "o'brien\\x",
        }
        info = generate_database_php(pr3, env_fake)
        with open(os.path.join(pr3, "application", "database.php"), encoding="utf-8") as fh:
            afterdb = fh.read()
        check("SNAPD-4 params 写入会话时区",
              "'params'          => [PDO::MYSQL_ATTR_INIT_COMMAND => \"SET time_zone='+08:00'\"]," in afterdb)
        check("ENV-2 hostname 从 URL 取值", "'hostname'        => '127.0.0.1'," in afterdb)
        check("ENV-2 hostport 从 URL 取值", "'hostport'        => '3307'," in afterdb)
        check("ENV-2 database 从 URL 取值", "'database'        => 'maccms_it_full'," in afterdb)
        check("ENV-2 密码按 PHP 单引号转义",
              "'password'        => 'o\\'brien\\\\x'," in afterdb)
        check("ENV-2 未打印取值到返回值", "o'brien" not in json.dumps(info, ensure_ascii=False))

        # 反例：缺环境变量 → 断言失败
        try:
            generate_database_php(pr3, {"MACCMS_DB_URL": env_fake["MACCMS_DB_URL"]})
            check("ENV-2 缺变量必须断言失败", False, "竟然通过了")
        except AssertionError:
            check("ENV-2 缺变量必须断言失败", True)

        # 反例：URL 不是 jdbc:mysql:// → 断言失败
        try:
            parse_jdbc_url("mysql://127.0.0.1/x")
            check("ENV-2 非 jdbc URL 必须断言失败", False, "竟然通过了")
        except AssertionError:
            check("ENV-2 非 jdbc URL 必须断言失败", True)

        # ---- ENV-5 回滚 ---------------------------------------------------
        base_dir = os.path.join(tmp, "base")
        site_dir = os.path.join(tmp, "site")
        for root in (base_dir, site_dir):
            for rel in CS_CONFIG:
                p = os.path.join(root, rel)
                os.makedirs(os.path.dirname(p), exist_ok=True)
                with open(p, "wb") as fh:
                    fh.write(b"orig-" + rel.encode("utf-8"))
        with open(os.path.join(site_dir, "application", "extra", "maccms.php"), "wb") as fh:
            fh.write(b"tampered")
        r = check_configs(site_dir, base_dir)
        check("ENV-5 检出并回滚被改的 1 个文件", r["restored"] == ["application/extra/maccms.php"],
              "实际 %s" % r["restored"])
        r2 = check_configs(site_dir, base_dir)
        check("ENV-5 第二次已无需回滚", r2["restored"] == [])

        # ---- 文件数（CS-CONFIG 恰 13 个） ----------------------------------
        check("CS-CONFIG 恰 13 个文件", len(CS_CONFIG) == 13)

        # ---- vendor 摘要（用合成 zip） ------------------------------------
        zpath = os.path.join(tmp, "fake.zip")
        with zipfile.ZipFile(zpath, "w") as z:
            # 真实备份的条目形如 maccms/vendor/...（2026-11-02 核实），夹具照此布局
            z.writestr("maccms/vendor/a.txt", b"aaa")
            z.writestr("maccms/vendor/sub/b.txt", b"bbb")
            z.writestr("maccms/vendor/.gitignore", b"*")
            z.writestr("maccms/other/c.txt", b"ccc")
        pr4 = os.path.join(tmp, "p4")
        os.makedirs(pr4)
        v = restore_vendor(zpath, pr4)
        check("SNAPD-5 只解 vendor/ 的 2 个文件（跳过 .gitignore 与 zip 外的条目）", v["files"] == 2,
              "实际 %d" % v["files"])
        check("SNAPD-5 汇总值与自身重算一致",
              v["aggregate_sha256"] == recompute_aggregate(pr4))

        # 反例：vendor 之外的路径穿越 → 必须中止
        zbad = os.path.join(tmp, "bad.zip")
        with zipfile.ZipFile(zbad, "w") as z:
            z.writestr("maccms/vendor/../../evil.txt", b"x")
        pr5 = os.path.join(tmp, "p5")
        os.makedirs(pr5)
        try:
            restore_vendor(zbad, pr5)
            check("SNAPD-5 路径穿越必须中止", False, "竟然通过了")
        except RuntimeError:
            check("SNAPD-5 路径穿越必须中止", True)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    total = checks[0]
    sys.stdout.write("\n%d / %d 项通过；失败 %d 项\n" % (total - len(failures), total, len(failures)))
    return 0 if not failures else 1


def recompute_aggregate(vendor_root: str) -> str:
    hashes = []
    for dirpath, _dirnames, filenames in os.walk(vendor_root):
        for name in filenames:
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, vendor_root).replace(os.sep, "/")
            hashes.append((rel, sha256_file(full)))
    hashes.sort(key=lambda kv: kv[0])
    agg = hashlib.sha256()
    for rel, h in hashes:
        agg.update(("%s\t%s\n" % (rel, h)).encode("utf-8"))
    return agg.hexdigest()


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------


def main(argv: list) -> int:
    ap = argparse.ArgumentParser(description="APPK-2 C-8/C-63 对照环境装配（只写，离线）")
    sub = ap.add_subparsers(dest="cmd", required=True)

    a = sub.add_parser("assemble", help="ENV-1～ENV-4 装配 .compare/APPK-2/")
    a.add_argument("--commit", required=True, help="被测提交（ENV-1 的 git archive 对象）")
    a.add_argument("--php-commit", default=None,
                   help="php/ 的导出提交（默认同 --commit）；S3-01 未合入时可用带模板包的提交分别指定")
    a.add_argument("--template-commit", default=None,
                   help="模板包（DEC-1）的提交（默认同 --php-commit）")
    a.add_argument("--overlay", default=OVERLAY_PATH, help="C-7 覆盖表（默认 tests/compare/APPK-2/maccms-overlay.json）")
    a.add_argument("--backup-zip", default=None, help="仓库外生产备份 zip（默认 %s）" % PROD_BACKUP_DEFAULT)
    a.add_argument("--report", default=None, help="把基线报告写到该路径（如 docs/test/APPK-2/php-baseline-report.md）")
    a.add_argument("--force", action="store_true", help="pristine 已存在时重建")
    a.set_defaults(func=do_assemble)

    c = sub.add_parser("check-configs", help="ENV-5：单元开始前回滚 13 个 CS-CONFIG")
    c.add_argument("--site", required=True, help="侧站点根（如 .compare/APPK-2/php-site）")
    c.add_argument("--pristine", default=None, help="pristine 目录（默认与 --site 同级的 pristine/）")
    c.set_defaults(func=do_check_configs)

    s = sub.add_parser("selftest", help="离线自测本脚本（不需要 git 提交/数据库/仓库外备份）")
    s.set_defaults(func=do_selftest)

    args = ap.parse_args(argv)
    if args.cmd == "assemble":
        return args.func(args, os.environ)
    return args.func(args)


def do_check_configs(args, _env) -> int:
    site = args.site if os.path.isabs(args.site) else os.path.join(REPO_ROOT, args.site)
    if not os.path.isdir(site):
        die(1, "找不到侧站点根：%s" % args.site)
    pristine = args.pristine or os.path.join(os.path.dirname(os.path.abspath(site)), PRistine)
    if not os.path.isabs(pristine):
        pristine = os.path.join(REPO_ROOT, pristine)
    try:
        res = check_configs(site, pristine)
    except AssertionError as exc:
        sys.stdout.write(json.dumps({"result": "MISMATCH", "detail": str(exc)}, ensure_ascii=False) + "\n")
        return 2
    sys.stdout.write(json.dumps({"result": "OK", "checked": res["checked"],
                                 "restored": res["restored"]}, ensure_ascii=False) + "\n")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except SystemExit:
        raise
    except Exception as exc:  # noqa: BLE001 — 顶层兜底，返回 4 与崩溃区分
        sys.stderr.write("脚本自身异常：%r\n" % (exc,))
        sys.exit(4)
