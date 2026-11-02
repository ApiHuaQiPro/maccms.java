#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""APPK-2 C-66：表结构快照与比对（技术设计 v15 附录 A.1 SNAP-1～SNAP-8、V-21）。

技术设计原文（附录 A.1，摘要）：

* SNAP-1 `information_schema.SCHEMATA`：库的字符集与排序规则。
* SNAP-2 `information_schema.TABLES`：26 张 `mac_*` 表的引擎、行格式、字符集、排序规则等；
  **表数量必须恰为 26**。
* SNAP-3 `information_schema.COLUMNS`：列定义；基线共 **503 列**。
* SNAP-4 `information_schema.STATISTICS`：索引定义。
* SNAP-5 约束：`TABLE_CONSTRAINTS` + `KEY_COLUMN_USAGE` + `REFERENTIAL_CONSTRAINTS`；
  基线**外键 0 个**。
* SNAP-6 `TRIGGERS` / `VIEWS` / `ROUTINES` / `EVENTS`：基线**各 0 个**。
* SNAP-7 `SHOW CREATE TABLE`，把 `AUTO_INCREMENT=<n>` 抹掉后比对（数据量变化不算结构变化）。
* SNAP-8 环境头：MySQL 版本、`@@sql_mode`、`@@character_set_server` 等，**只记录不比对**
  （换机器必然变，比它只会制造假差异）。

判断口径（V-21）：`schema_snapshot.py` 在 SP-1 前后各跑一次，**diff 行数 0**；
同时 `./mvnw -B test -Dtest=SchemaGuardTest` 通过、仓库里没有 `schema.sql`/Flyway/Liquibase。

本脚本的三个子命令：

* `snapshot`  取一份快照存成 JSON（写文件，不改库）。
* `diff`      两份快照逐项比对，输出 `docs/test/APPK-2/schema-diff-<时点>.md`，
              返回 diff 行数（KPI-2 要求 0）。
* `selftest`  离线自测（不连库）。

红线：库名只从环境变量读，且只允许一次性测试库（`maccms_it_*` / `maccms_*_test`）；
不打印密码；`snapshot`/`diff` 会真的连库，属**执行**阶段动作——本单只写不执行。

用法（工作目录 = 仓库根）::

    # SP-1 前
    python tests/compare/APPK-2/schema_snapshot.py snapshot --out .compare/APPK-2/schema-before.json
    # SP-1 后
    python tests/compare/APPK-2/schema_snapshot.py snapshot --out .compare/APPK-2/schema-after.json
    # 比对并出报告
    python tests/compare/APPK-2/schema_snapshot.py diff \\
        --before .compare/APPK-2/schema-before.json --after .compare/APPK-2/schema-after.json
    python tests/compare/APPK-2/schema_snapshot.py selftest

返回码：

    0  成功 / 无差异
    1  用法或输入错误
    2  有差异（diff 行数 > 0）或快照本身不合基线（表数≠26、列数≠503、外键≠0）
    3  外部工具失败
    4  脚本自身异常
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))

# 附录 A.1 的基线常量
EXPECTED_TABLE_COUNT = 26
EXPECTED_COLUMN_COUNT = 503
EXPECTED_FOREIGN_KEY_COUNT = 0

TABLES = (
    "mac_actor", "mac_admin", "mac_annex", "mac_art",
    "mac_card", "mac_cash", "mac_cj_content", "mac_cj_history",
    "mac_cj_node", "mac_collect", "mac_comment", "mac_gbook",
    "mac_group", "mac_link", "mac_msg", "mac_order",
    "mac_plog", "mac_role", "mac_topic", "mac_type",
    "mac_ulog", "mac_user", "mac_visit", "mac_vod",
    "mac_vod_search", "mac_website",
)
assert len(TABLES) == EXPECTED_TABLE_COUNT

ALLOWED_DATABASE_PATTERNS = (
    re.compile(r"^maccms_it_[A-Za-z0-9_]+$"),
    re.compile(r"^maccms_[A-Za-z0-9_]+_test$"),
)

# SNAP-8：只记录不比对的环境头
ENV_HEADER_QUERIES = (
    ("mysql_version", "SELECT VERSION();"),
    ("sql_mode", "SELECT @@SESSION.sql_mode;"),
    ("character_set_server", "SELECT @@character_set_server;"),
    ("collation_server", "SELECT @@collation_server;"),
    ("lower_case_table_names", "SELECT @@lower_case_table_names;"),
    ("default_storage_engine", "SELECT @@default_storage_engine;"),
    ("session_time_zone", "SELECT @@SESSION.time_zone;"),  # SNAPD-4
)

# SNAP-7：抹掉 AUTO_INCREMENT=<n>
RE_AUTO_INCREMENT = re.compile(r"\s+AUTO_INCREMENT=\d+", re.IGNORECASE)


def die(code: int, msg: str) -> "None":
    sys.stderr.write(msg.rstrip() + "\n")
    raise SystemExit(code)


def require(ok: bool, msg: str) -> None:
    if not ok:
        raise AssertionError(msg)


# --------------------------------------------------------------------------
# 连接与查询
# --------------------------------------------------------------------------


def parse_mysql_target(env: dict) -> dict:
    url = env.get("MACCMS_DB_URL", "")
    user = env.get("MACCMS_DB_USERNAME", "")
    passwd = env.get("MACCMS_DB_PASSWORD", "")
    missing = [k for k, v in (("MACCMS_DB_URL", url), ("MACCMS_DB_USERNAME", user),
                              ("MACCMS_DB_PASSWORD", passwd)) if not v]
    require(not missing, "缺环境变量：%s" % ", ".join(missing))
    require(url.startswith("jdbc:mysql://"), "MACCMS_DB_URL 必须是 jdbc:mysql:// 形式（取值不打印）")
    rest = url[len("jdbc:mysql://"):]
    hostpart = rest.split("?", 1)[0]
    require("/" in hostpart, "MACCMS_DB_URL 缺库名（取值不打印）")
    hostport, database = hostpart.split("/", 1)
    database = database.strip()
    require(database != "", "MACCMS_DB_URL 缺库名（取值不打印）")
    host, port = (hostport.rsplit(":", 1) if ":" in hostport else (hostport, "3306"))
    require(host != "", "MACCMS_DB_URL 缺主机名（取值不打印）")
    assert_one_off_database(database)
    return {"host": host, "port": port, "database": database, "user": user, "password": passwd}


def assert_one_off_database(database: str) -> None:
    ok = any(p.match(database) for p in ALLOWED_DATABASE_PATTERNS)
    require(ok, "拒绝连接库 '%s'：只允许 maccms_it_* 或 maccms_*_test 形式的一次性测试库" % database)


def find_tool(name: str) -> str:
    found = shutil.which(name)
    if found:
        return found
    for cand in (
        os.path.join(os.environ.get("MYSQL_HOME", ""), "bin", name + ".exe"),
        r"D:\Tools\mysql\5.7\bin\%s.exe" % name,
        r"D:\Tools\mysql\8.0\bin\%s.exe" % name,
    ):
        if cand and os.path.isfile(cand):
            return cand
    raise RuntimeError("找不到 %s（请加入 PATH 或设 MYSQL_HOME）" % name)


def write_defaults_file(target: dict, tmpdir: str) -> str:
    path = os.path.join(tmpdir, "client.cnf")
    lines = ["[client]", "host=%s" % target["host"], "port=%s" % target["port"],
             "user=%s" % target["user"], "password=%s" % target["password"],
             "default-character-set=utf8"]
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass
    return path


def query_tsv(tool: str, defaults: str, database: str, sql: str) -> list:
    import subprocess

    p = subprocess.run([tool, "--defaults-file=" + defaults, "--batch", "--skip-column-names",
                        "--default-character-set=utf8", database, "-e", sql],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                       stdin=subprocess.DEVNULL, timeout=300)
    if p.returncode != 0:
        raise RuntimeError("mysql 执行失败（%d）：%s"
                           % (p.returncode, p.stderr.decode("utf-8", "replace").strip()[:400]))
    return [line.split("\t") for line in p.stdout.decode("utf-8", "replace").splitlines() if line != ""]


def normalize(value: str):
    """MySQL 客户端把 NULL 打成 'NULL'；统一成 None 便于比对。"""
    return None if value == "NULL" else value


# --------------------------------------------------------------------------
# 各 SNAP 的取数
# --------------------------------------------------------------------------


def collect_snap1(tool, defaults, db) -> dict:
    rows = query_tsv(tool, defaults, db,
                     "SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME "
                     "FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = DATABASE();")
    require(len(rows) == 1 and len(rows[0]) == 2, "SNAP-1 查询返回异常：%r" % (rows,))
    return {"charset": rows[0][0], "collation": rows[0][1]}


SNAP2_SQL = (
    "SELECT TABLE_NAME, ENGINE, ROW_FORMAT, TABLE_COLLATION, CREATE_OPTIONS "
    "FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' "
    "ORDER BY TABLE_NAME;"
)


def collect_snap2(tool, defaults, db) -> dict:
    rows = query_tsv(tool, defaults, db, SNAP2_SQL)
    out = {}
    for row in rows:
        require(len(row) == 5, "SNAP-2 列数异常：%r" % (row,))
        name = row[0]
        out[name] = {"engine": normalize(row[1]), "row_format": normalize(row[2]),
                     "collation": normalize(row[3]), "create_options": normalize(row[4])}
    return out


SNAP3_SQL = (
    "SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, COLUMN_TYPE, IS_NULLABLE, "
    "COLUMN_DEFAULT, EXTRA, CHARACTER_SET_NAME, COLLATION_NAME "
    "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
    "ORDER BY TABLE_NAME, ORDINAL_POSITION;"
)


def collect_snap3(tool, defaults, db) -> dict:
    rows = query_tsv(tool, defaults, db, SNAP3_SQL)
    out = {}
    for row in rows:
        require(len(row) == 9, "SNAP-3 列数异常：%r" % (row,))
        table, column = row[0], row[1]
        out.setdefault(table, {})[column] = {
            "position": int(row[2]),
            "type": row[3],
            "nullable": row[4],
            "default": normalize(row[5]),
            "extra": normalize(row[6]),
            "charset": normalize(row[7]),
            "collation": normalize(row[8]),
        }
    return out


SNAP4_SQL = (
    "SELECT TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE, INDEX_TYPE "
    "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
    "ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;"
)


def collect_snap4(tool, defaults, db) -> dict:
    rows = query_tsv(tool, defaults, db, SNAP4_SQL)
    out = {}
    for row in rows:
        require(len(row) == 6, "SNAP-4 列数异常：%r" % (row,))
        table, index = row[0], row[1]
        out.setdefault(table, {}).setdefault(index, []).append({
            "seq": int(row[2]),
            "column": normalize(row[3]),
            "non_unique": row[4],
            "type": row[5],
        })
    return out


SNAP5_SQL = (
    "SELECT tc.TABLE_NAME, tc.CONSTRAINT_NAME, tc.CONSTRAINT_TYPE "
    "FROM information_schema.TABLE_CONSTRAINTS tc "
    "WHERE tc.CONSTRAINT_SCHEMA = DATABASE() "
    "ORDER BY tc.TABLE_NAME, tc.CONSTRAINT_NAME;"
)

SNAP5_FK_SQL = (
    "SELECT rc.CONSTRAINT_NAME, rc.TABLE_NAME, rc.REFERENCED_TABLE_NAME, "
    "rc.UPDATE_RULE, rc.DELETE_RULE "
    "FROM information_schema.REFERENTIAL_CONSTRAINTS rc "
    "WHERE rc.CONSTRAINT_SCHEMA = DATABASE() ORDER BY rc.CONSTRAINT_NAME;"
)

SNAP5_KEY_SQL = (
    "SELECT TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME, ORDINAL_POSITION, REFERENCED_TABLE_NAME, "
    "REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
    "WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION;"
)


def collect_snap5(tool, defaults, db) -> dict:
    constraints = []
    for row in query_tsv(tool, defaults, db, SNAP5_SQL):
        require(len(row) == 3, "SNAP-5 TABLE_CONSTRAINTS 列数异常：%r" % (row,))
        constraints.append({"table": row[0], "name": row[1], "type": row[2]})
    keys = []
    for row in query_tsv(tool, defaults, db, SNAP5_KEY_SQL):
        require(len(row) == 6, "SNAP-5 KEY_COLUMN_USAGE 列数异常：%r" % (row,))
        keys.append({"table": row[0], "constraint": row[1], "column": normalize(row[2]),
                     "position": int(row[3]), "ref_table": normalize(row[4]),
                     "ref_column": normalize(row[5])})
    foreign_keys = []
    for row in query_tsv(tool, defaults, db, SNAP5_FK_SQL):
        require(len(row) == 5, "SNAP-5 REFERENTIAL_CONSTRAINTS 列数异常：%r" % (row,))
        foreign_keys.append({"name": row[0], "table": row[1], "ref_table": row[2],
                             "on_update": row[3], "on_delete": row[4]})
    return {"constraints": constraints, "keys": keys, "foreign_keys": foreign_keys}


SNAP6_OBJECTS = (
    ("triggers", "SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE();"),
    ("views", "SELECT TABLE_NAME FROM information_schema.VIEWS WHERE TABLE_SCHEMA = DATABASE();"),
    ("routines", "SELECT ROUTINE_NAME FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE();"),
    ("events", "SELECT EVENT_NAME FROM information_schema.EVENTS WHERE EVENT_SCHEMA = DATABASE();"),
)


def collect_snap6(tool, defaults, db) -> dict:
    out = {}
    for name, sql in SNAP6_OBJECTS:
        out[name] = sorted(r[0] for r in query_tsv(tool, defaults, db, sql))
    return out


def strip_auto_increment(create_sql: str) -> str:
    """SNAP-7：抹掉 AUTO_INCREMENT=<n>（数据量变化不算结构变化）。"""
    return RE_AUTO_INCREMENT.sub("", create_sql)


SNAP7_SQL_TEMPLATE = "SHOW CREATE TABLE `%s`;"


def collect_snap7(tool, defaults, db) -> dict:
    out = {}
    for table in TABLES:
        rows = query_tsv(tool, defaults, db, SNAP7_SQL_TEMPLATE % table)
        require(len(rows) == 1 and len(rows[0]) >= 2, "SNAP-7 %s 返回异常：%r" % (table, rows))
        out[table] = strip_auto_increment(rows[0][1])
    return out


def collect_snap8(tool, defaults, db) -> dict:
    out = {}
    for key, sql in ENV_HEADER_QUERIES:
        rows = query_tsv(tool, defaults, db, sql)
        require(len(rows) == 1 and rows[0], "SNAP-8 %s 返回异常：%r" % (key, rows))
        out[key] = normalize(rows[0][0])
    return out


def collect_snapshot() -> dict:
    import datetime

    target = parse_mysql_target(os.environ)
    tool = find_tool("mysql")
    with tempfile.TemporaryDirectory(prefix="appk2-schema-") as tmp:
        defaults = write_defaults_file(target, tmp)
        db = target["database"]
        snap = {
            "_comment": "APPK-2 SNAP-1～SNAP-8 表结构快照；由 tests/compare/APPK-2/schema_snapshot.py 生成",
            "at": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
            "database": db,
            "snap1_schema": collect_snap1(tool, defaults, db),
            "snap2_tables": collect_snap2(tool, defaults, db),
            "snap3_columns": collect_snap3(tool, defaults, db),
            "snap4_indexes": collect_snap4(tool, defaults, db),
            "snap5_constraints": collect_snap5(tool, defaults, db),
            "snap6_objects": collect_snap6(tool, defaults, db),
            "snap7_create": collect_snap7(tool, defaults, db),
            "snap8_env": collect_snap8(tool, defaults, db),
        }
    return snap


# --------------------------------------------------------------------------
# 基线自检：表数 26、列数 503、外键 0
# --------------------------------------------------------------------------


def check_baseline(snap: dict) -> list:
    problems = []
    tables = snap.get("snap2_tables", {})
    if len(tables) != EXPECTED_TABLE_COUNT:
        problems.append("SNAP-2 表数 %d != %d" % (len(tables), EXPECTED_TABLE_COUNT))
    missing = sorted(set(TABLES) - set(tables))
    extra = sorted(set(tables) - set(TABLES))
    if missing:
        problems.append("SNAP-2 缺表：%s" % ", ".join(missing))
    if extra:
        problems.append("SNAP-2 多表：%s" % ", ".join(extra))

    columns = sum(len(v) for v in snap.get("snap3_columns", {}).values())
    if columns != EXPECTED_COLUMN_COUNT:
        problems.append("SNAP-3 列数 %d != %d" % (columns, EXPECTED_COLUMN_COUNT))

    fks = snap.get("snap5_constraints", {}).get("foreign_keys", [])
    if len(fks) != EXPECTED_FOREIGN_KEY_COUNT:
        problems.append("SNAP-5 外键 %d != %d" % (len(fks), EXPECTED_FOREIGN_KEY_COUNT))

    for name, items in snap.get("snap6_objects", {}).items():
        if items:
            problems.append("SNAP-6 %s 应为 0 个，实际 %d 个" % (name, len(items)))
    return problems


# --------------------------------------------------------------------------
# diff
# --------------------------------------------------------------------------

# 逐项比对的口径：键为 "对象路径"，值为 (before, after)
COMPARE_SECTIONS = (
    ("snap1_schema", "SNAP-1 库字符集"),
    ("snap2_tables", "SNAP-2 表属性"),
    ("snap3_columns", "SNAP-3 列定义"),
    ("snap4_indexes", "SNAP-4 索引"),
    ("snap5_constraints", "SNAP-5 约束"),
    ("snap6_objects", "SNAP-6 触发器/视图/存储过程/事件"),
    ("snap7_create", "SNAP-7 建表语句（已抹 AUTO_INCREMENT）"),
)


def flatten(value, prefix: str, out: dict) -> None:
    """把嵌套 dict/list 摊平成 {路径: 标量}，便于逐项 diff。"""
    if isinstance(value, dict):
        for k in sorted(value):
            flatten(value[k], "%s.%s" % (prefix, k), out)
    elif isinstance(value, list):
        if not value:
            out[prefix] = "[]"
            return
        for i, item in enumerate(value):
            flatten(item, "%s[%d]" % (prefix, i), out)
    else:
        out[prefix] = value


def diff_snapshots(before: dict, after: dict) -> list:
    """返回差异行列表，每项 {"section":…, "path":…, "before":…, "after":…}。

    SNAP-8（环境头）**不参与比对**，只记录。
    """
    rows = []
    for section, _label in COMPARE_SECTIONS:
        b, a = {}, {}
        flatten(before.get(section, {}), "", b)
        flatten(after.get(section, {}), "", a)
        for path in sorted(set(b) | set(a)):
            if b.get(path, None) != a.get(path, None):
                rows.append({"section": section, "path": path.lstrip("."),
                             "before": b.get(path, "<缺>"), "after": a.get(path, "<缺>")})
    return rows


def _cell(value, limit: int = 200) -> str:
    """单元格取值：过长的文本截断并标注，避免一行几千字把报告撑爆。"""
    text = str(value)
    if len(text) <= limit:
        return text
    return "%s…（共 %d 字符，全文见快照 JSON）" % (text[:limit], len(text))


def render_diff_report(before: dict, after: dict, rows: list) -> str:
    lines = ["# APPK-2 V-21 表结构 diff 报告（schema_snapshot.py 自动生成）\n"]
    lines.append("| 项 | 值 |\n| --- | --- |")
    lines.append("| SP-1 前快照时点 | %s |" % before.get("at", "<未知>"))
    lines.append("| SP-1 后快照时点 | %s |" % after.get("at", "<未知>"))
    lines.append("| 库 | `%s` |" % before.get("database", "<未知>"))
    lines.append("| **diff 行数（KPI-2，要求 0）** | **%d** |" % len(rows))
    lines.append("")

    lines.append("## 基线自检（SNAP-2/3/5/6）\n")
    for label, snap in (("SP-1 前", before), ("SP-1 后", after)):
        problems = check_baseline(snap)
        if problems:
            lines.append("- %s：**不符** —— %s" % (label, "；".join(problems)))
        else:
            lines.append("- %s：表 %d 张、列 %d 个、外键 %d 个、触发器/视图/存储过程/事件各 0 个，符合基线"
                         % (label, len(snap.get("snap2_tables", {})),
                            sum(len(v) for v in snap.get("snap3_columns", {}).values()),
                            len(snap.get("snap5_constraints", {}).get("foreign_keys", []))))
    lines.append("")

    lines.append("## SNAP-8 环境头（只记录，不比对）\n")
    lines.append("| 键 | SP-1 前 | SP-1 后 |\n| --- | --- | --- |")
    b_env, a_env = before.get("snap8_env", {}), after.get("snap8_env", {})
    for key in sorted(set(b_env) | set(a_env)):
        lines.append("| `%s` | `%s` | `%s` |" % (key, b_env.get(key, "<缺>"), a_env.get(key, "<缺>")))
    lines.append("")

    if rows:
        lines.append("## 差异明细（共 %d 行）\n" % len(rows))
        lines.append("| # | 段 | 对象 | SP-1 前 | SP-1 后 |\n| --- | --- | --- | --- | --- |")
        for i, row in enumerate(rows, 1):
            # SNAP-7 的建表语句可能上万字：只标“有差异”，全文在两份快照 JSON 里
            if row["section"] == "snap7_create":
                lines.append("| %d | SNAP-7 建表语句 | `%s` | 与 SP-1 前不同 | 与 SP-1 前不同 |"
                             % (i, row["path"]))
            else:
                lines.append("| %d | %s | `%s` | `%s` | `%s` |"
                             % (i, row["section"], row["path"],
                                _cell(row["before"]), _cell(row["after"])))
        lines.append("")
    else:
        lines.append("## 差异明细\n\n无差异。SP-1 未改变表结构，V-21 的脚本部分满足。\n")
    return "\n".join(lines) + "\n"


def default_report_path() -> str:
    import datetime

    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    return os.path.join("docs", "test", "APPK-2", "schema-diff-%s.md" % stamp)


# --------------------------------------------------------------------------
# 子命令
# --------------------------------------------------------------------------


def do_snapshot(args, _env) -> int:
    out = args.out if os.path.isabs(args.out) else os.path.join(REPO_ROOT, args.out)
    try:
        snap = collect_snapshot()
    except AssertionError as exc:
        die(1, str(exc))
    except RuntimeError as exc:
        die(3, str(exc))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(snap, fh, ensure_ascii=False, indent=2, sort_keys=True)
        fh.write("\n")
    problems = check_baseline(snap)
    sys.stdout.write("快照已写入 %s（表 %d、列 %d、外键 %d）\n"
                     % (os.path.relpath(out, REPO_ROOT).replace(os.sep, "/"),
                        len(snap["snap2_tables"]),
                        sum(len(v) for v in snap["snap3_columns"].values()),
                        len(snap["snap5_constraints"]["foreign_keys"])))
    if problems:
        sys.stdout.write("基线自检不符：\n")
        for p in problems:
            sys.stdout.write("  - %s\n" % p)
        return 2
    return 0


def load_snapshot(path: str) -> dict:
    full = path if os.path.isabs(path) else os.path.join(REPO_ROOT, path)
    if not os.path.isfile(full):
        die(1, "快照文件不存在：%s" % path)
    with open(full, "r", encoding="utf-8") as fh:
        return json.load(fh)


def do_diff(args, _env) -> int:
    before = load_snapshot(args.before)
    after = load_snapshot(args.after)
    rows = diff_snapshots(before, after)
    report = render_diff_report(before, after, rows)
    path = args.report or default_report_path()
    full = path if os.path.isabs(path) else os.path.join(REPO_ROOT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(report)
    sys.stdout.write("差异报告已写入 %s\n" % os.path.relpath(full, REPO_ROOT).replace(os.sep, "/"))
    sys.stdout.write("diff 行数 = %d\n" % len(rows))
    if rows:
        for row in rows[:20]:
            if row["section"] == "snap7_create":
                sys.stdout.write("  - [snap7_create] %s：建表语句不同（全文见快照 JSON）\n" % row["path"])
            else:
                sys.stdout.write("  - [%s] %s：%s -> %s\n"
                                 % (row["section"], row["path"],
                                    _cell(row["before"], 80), _cell(row["after"], 80)))
        return 2
    baseline = check_baseline(after)
    if baseline:
        for p in baseline:
            sys.stdout.write("基线自检不符：%s\n" % p)
        return 2
    return 0


def do_selftest(_args, _env=None) -> int:
    failures = []
    checks = [0]

    def check(name, ok, detail=""):
        checks[0] += 1
        if not ok:
            failures.append("%s%s" % (name, ("：" + detail) if detail else ""))
        sys.stdout.write("%s %s%s\n" % ("PASS" if ok else "FAIL", name,
                                        ("：" + detail) if detail and not ok else ""))

    # ---- 基线常量 -------------------------------------------------------
    check("基线表数常量 = 26", EXPECTED_TABLE_COUNT == 26)
    check("基线列数常量 = 503", EXPECTED_COLUMN_COUNT == 503)
    check("基线外键常量 = 0", EXPECTED_FOREIGN_KEY_COUNT == 0)
    check("表清单恰 26 张", len(TABLES) == 26 and len(set(TABLES)) == 26)

    # ---- SNAP-7 抹 AUTO_INCREMENT ---------------------------------------
    create = ("CREATE TABLE `mac_vod` (\n  `vod_id` int(11) NOT NULL\n) "
              "ENGINE=MyISAM AUTO_INCREMENT=1234 DEFAULT CHARSET=utf8;")
    stripped = strip_auto_increment(create)
    check("SNAP-7 抹掉 AUTO_INCREMENT", "AUTO_INCREMENT" not in stripped, stripped)
    check("SNAP-7 其余内容不变", "ENGINE=MyISAM" in stripped and "`vod_id`" in stripped)
    check("SNAP-7 小写形式也抹掉", "AUTO_INCREMENT" not in strip_auto_increment(
        "CREATE TABLE t (id int) auto_increment=9"))

    # ---- flatten / diff -------------------------------------------------
    flat = {}
    flatten({"a": {"b": 1}, "c": [{"d": 2}, {"d": 3}], "e": []}, "", flat)
    check("flatten 处理嵌套 dict", flat.get(".a.b") == 1, str(flat))
    check("flatten 处理 list", flat.get(".c[0].d") == 2 and flat.get(".c[1].d") == 3, str(flat))
    check("flatten 空 list 留标记", flat.get(".e") == "[]", str(flat))

    def make_snap(n_tables=26, n_cols=503, fks=0, tz="+08:00"):
        snap = {
            "at": "2026-11-02T00:00:00+08:00",
            "database": "maccms_it_x",
            "snap1_schema": {"charset": "utf8", "collation": "utf8_general_ci"},
            "snap2_tables": {},
            "snap3_columns": {},
            "snap4_indexes": {},
            "snap5_constraints": {"constraints": [], "keys": [], "foreign_keys": []},
            "snap6_objects": {"triggers": [], "views": [], "routines": [], "events": []},
            "snap7_create": {},
            "snap8_env": {"session_time_zone": tz},
        }
        used_tables = list(TABLES)[:n_tables]
        per, rest = divmod(n_cols, n_tables)
        for i, t in enumerate(used_tables):
            snap["snap2_tables"][t] = {"engine": "MyISAM", "row_format": "Dynamic",
                                       "collation": "utf8_general_ci", "create_options": None}
            snap["snap3_columns"][t] = {
                "c%d" % j: {"position": j + 1, "type": "int(11)", "nullable": "NO",
                            "default": None, "extra": "", "charset": None, "collation": None}
                for j in range(per + (1 if i < rest else 0))}
            snap["snap7_create"][t] = "CREATE TABLE `%s` (...)" % t
        for i in range(fks):
            snap["snap5_constraints"]["foreign_keys"].append(
                {"name": "fk%d" % i, "table": TABLES[0], "ref_table": TABLES[1],
                 "on_update": "RESTRICT", "on_delete": "RESTRICT"})
        return snap

    base = make_snap()
    check("基线自检通过（26/503/0）", check_baseline(base) == [], str(check_baseline(base)))

    check("表数少 1 被检出", any("表数" in p for p in check_baseline(make_snap(n_tables=25))))
    check("列数不符被检出", any("列数" in p for p in check_baseline(make_snap(n_cols=502))))
    check("外键非 0 被检出", any("外键" in p for p in check_baseline(make_snap(fks=1))))

    multi = make_snap()
    multi["snap6_objects"]["triggers"] = ["t1"]
    check("SNAP-6 触发器非 0 被检出", any("triggers" in p for p in check_baseline(multi)))

    missing_t = make_snap()
    del missing_t["snap2_tables"]["mac_vod"]
    check("缺表被检出", any("缺表" in p for p in check_baseline(missing_t)))

    # ---- diff 判定 ------------------------------------------------------
    check("同一快照 diff 为 0", diff_snapshots(base, make_snap()) == [])

    rows_tz = diff_snapshots(base, make_snap(tz="SYSTEM"))
    check("SNAP-8 变化不产生 diff（只记录）", rows_tz == [], str(rows_tz))

    changed = make_snap()
    changed["snap3_columns"][TABLES[0]]["c0"]["type"] = "bigint(20)"
    rows = diff_snapshots(base, changed)
    check("列类型变化产出 1 行 diff", len(rows) == 1 and rows[0]["section"] == "snap3_columns", str(rows))
    check("diff 行带前后值", rows and rows[0]["before"] == "int(11)" and rows[0]["after"] == "bigint(20)",
          str(rows))

    changed2 = make_snap()
    changed2["snap7_create"][TABLES[3]] = "CREATE TABLE `mac_annex` (changed)"
    rows2 = diff_snapshots(base, changed2)
    check("建表语句变化产出 1 行 diff", len(rows2) == 1 and rows2[0]["section"] == "snap7_create", str(rows2))

    changed3 = make_snap()
    changed3["snap1_schema"]["collation"] = "utf8mb4_general_ci"
    rows3 = diff_snapshots(base, changed3)
    check("库排序规则变化产出 1 行 diff", len(rows3) == 1 and rows3[0]["section"] == "snap1_schema", str(rows3))

    added = make_snap()
    added["snap4_indexes"][TABLES[0]] = {"PRIMARY": [{"seq": 1, "column": "vod_id",
                                                      "non_unique": "0", "type": "BTREE"}]}
    rows4 = diff_snapshots(base, added)
    check("新增索引被检出", len(rows4) >= 1 and all(r["section"] == "snap4_indexes" for r in rows4), str(rows4))

    # ---- 报告渲染 -------------------------------------------------------
    report = render_diff_report(base, make_snap(), [])
    check("报告写明 diff 行数 0", "**0**" in report or "| **0** |" in report, report[:200])
    check("报告包含 SNAP-8 段（只记录）", "SNAP-8 环境头（只记录，不比对）" in report)
    check("报告包含基线自检段", "基线自检" in report)
    report2 = render_diff_report(base, changed, rows)
    check("有差异时报告含明细表", "差异明细（共 1 行）" in report2, report2[:300])
    norm = default_report_path().replace(os.sep, "/")
    check("默认报告名带时点", re.match(r"^docs/test/APPK-2/schema-diff-\d{8}-\d{6}\.md$",
                                        norm) is not None, norm)

    # ---- 库名白名单 -----------------------------------------------------
    for ok_db in ("maccms_it_20261102_xyz", "maccms_schema_2026_test"):
        try:
            assert_one_off_database(ok_db)
            check("白名单放行 %s" % ok_db, True)
        except AssertionError:
            check("白名单放行 %s" % ok_db, False, "竟然拒绝")
    for bad_db in ("maccms_dev", "mysql", "maccms", "maccms_prod"):
        try:
            assert_one_off_database(bad_db)
            check("白名单拒绝 %s" % bad_db, False, "竟然放行")
        except AssertionError:
            check("白名单拒绝 %s" % bad_db, True)

    total = checks[0]
    sys.stdout.write("\n%d / %d 项通过；失败 %d 项\n" % (total - len(failures), total, len(failures)))
    if failures:
        for f_ in failures:
            sys.stdout.write("  FAIL %s\n" % f_)
    return 0 if not failures else 1


def main(argv: list) -> int:
    ap = argparse.ArgumentParser(description="APPK-2 C-66 表结构快照与比对（SNAP-1～SNAP-8、V-21）")
    sub = ap.add_subparsers(dest="cmd", required=True)

    s1 = sub.add_parser("snapshot", help="取一份 SNAP-1～SNAP-8 快照（写文件，不改库）")
    s1.add_argument("--out", required=True, help="输出 JSON 路径")
    s1.set_defaults(func=do_snapshot)

    s2 = sub.add_parser("diff", help="比对两份快照并输出报告；返回 diff 行数")
    s2.add_argument("--before", required=True)
    s2.add_argument("--after", required=True)
    s2.add_argument("--report", default=None, help="报告路径（默认 docs/test/APPK-2/schema-diff-<时点>.md）")
    s2.set_defaults(func=do_diff)

    s3 = sub.add_parser("selftest", help="离线自测（不连库）")
    s3.set_defaults(func=do_selftest)

    args = ap.parse_args(argv)
    return args.func(args, os.environ)


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except SystemExit:
        raise
    except Exception as exc:  # noqa: BLE001
        sys.stderr.write("脚本自身异常：%r\n" % (exc,))
        sys.exit(4)
