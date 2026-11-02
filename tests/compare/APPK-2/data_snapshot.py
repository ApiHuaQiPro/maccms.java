#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""APPK-2 C-62：数据快照与还原（技术设计 v15 §6.20 SNAPD-1～SNAPD-3）。

技术设计原文（SNAPD-1～SNAPD-3，摘要）：

* SNAPD-1  **快照**。用固定参数 `mysqldump --no-create-info --skip-triggers
  --complete-insert --hex-blob --skip-extended-insert` 导出 26 张 `mac_*` 表的**数据**
  （不含建表语句），落到 `.compare/APPK-2/data-snapshot.sql`。同时把
  `CHECKSUM TABLE` 结果与每表 `AUTO_INCREMENT` 记进 `docs/test/APPK-2/data-snapshot.json`。
* SNAPD-2  **还原**。`DELETE FROM` 26 张表 → 执行快照 SQL → 按 JSON 里的值
  `ALTER TABLE ... AUTO_INCREMENT=<n>` → 重新 `CHECKSUM TABLE` 与 JSON 比对。
  不一致时**重试一次**；第二次仍不一致即判“环境失败”，不进入后续比对。
* SNAPD-3  **逐单元恢复记录**。每个请求单元（C-64）开始前调用一次还原，把
  “何时还原、还原前后 checksum、耗时、是否重试”追加到恢复记录，供事后判读差异来源。

红线（与 S3-03 派工说明一致，也是 PRE-1 的口径）：

* 只允许连**一次性测试库**。`--allow-database` 必须显式给出库名，且库名必须匹配
  `maccms_it_*` 或 `maccms_*_test`；本脚本拒绝连 `maccms_dev`、`mac_*` 之外的库名、
  以及任何不含库名的 URL。
* 库名与账号只从环境变量读；**脚本不打印密码**，日志里出现的只有“已设置/未设置”。
* `dump`/`restore` 会真的读写数据库，属于**执行**动作：按派工说明，本单（只写阶段）
  **不执行**它们，只做离线自测；真正执行属 T3。
* `verify` 只读（`CHECKSUM TABLE`、`information_schema`），也仍属执行阶段动作。

用法（工作目录 = 仓库根）::

    # 1. 快照（执行阶段）
    python tests/compare/APPK-2/data_snapshot.py dump \\
        --snapshot .compare/APPK-2/data-snapshot.sql \\
        --meta docs/test/APPK-2/data-snapshot.json

    # 2. 还原 + 校验（执行阶段；C-64 每个单元开始前）
    python tests/compare/APPK-2/data_snapshot.py restore \\
        --snapshot .compare/APPK-2/data-snapshot.sql \\
        --meta docs/test/APPK-2/data-snapshot.json \\
        --log .compare/APPK-2/restore-log.jsonl

    # 3. 只校验当前库与 JSON 是否一致（执行阶段，只读）
    python tests/compare/APPK-2/data_snapshot.py verify --meta docs/test/APPK-2/data-snapshot.json

    # 4. 离线自测（不需要数据库）
    python tests/compare/APPK-2/data_snapshot.py selftest

凭据（只从环境变量，缺失即拒绝运行）::

    MACCMS_DB_URL       jdbc:mysql://host:port/<一次性库名>?...
    MACCMS_DB_USERNAME
    MACCMS_DB_PASSWORD

返回码：

    0  成功（快照已落盘 / 还原后 checksum 与 JSON 一致 / verify 一致）
    1  用法或输入错误（缺环境变量、库名不合规、文件不存在）
    2  校验不一致（**还原后仍不一致 → 按 SNAPD-2 判“环境失败”**）
    3  外部工具失败（mysqldump/mysql 非零退出、超时）
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
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))

# SNAPD-1 的表清单：旧站 26 张表（AGENTS.md「本地运行与测试」第 2 步：导入 install.sql 得 26 张）。
# 顺序固定，保证快照与 JSON 可逐行比对。
TABLES = (
    "mac_actor", "mac_admin", "mac_annex", "mac_art",
    "mac_card", "mac_cash", "mac_cj_content", "mac_cj_history",
    "mac_cj_node", "mac_collect", "mac_comment", "mac_gbook",
    "mac_group", "mac_link", "mac_msg", "mac_order",
    "mac_plog", "mac_role", "mac_topic", "mac_type",
    "mac_ulog", "mac_user", "mac_visit", "mac_vod",
    "mac_vod_search", "mac_website",
)
assert len(TABLES) == 26

# SNAPD-1 的 mysqldump 固定参数（技术设计逐字给出，不得增删）
DUMP_FLAGS = (
    "--no-create-info",
    "--skip-triggers",
    "--complete-insert",
    "--hex-blob",
    "--skip-extended-insert",
)

# 允许连接的一次性库名白名单（只写前缀匹配，避免误连共享库）
ALLOWED_DATABASE_PATTERNS = (
    re.compile(r"^maccms_it_[A-Za-z0-9_]+$"),
    re.compile(r"^maccms_[A-Za-z0-9_]+_test$"),
)


def die(code: int, msg: str) -> "None":
    sys.stderr.write(msg.rstrip() + "\n")
    raise SystemExit(code)


def require(ok: bool, msg: str) -> None:
    if not ok:
        raise AssertionError(msg)


# --------------------------------------------------------------------------
# 连接参数：只从环境变量，且必须是一次性库
# --------------------------------------------------------------------------


def parse_mysql_target(env: dict) -> dict:
    """从 MACCMS_DB_URL 解析出 host/port/database/user/password，并做库名白名单校验。"""
    url = env.get("MACCMS_DB_URL", "")
    user = env.get("MACCMS_DB_USERNAME", "")
    passwd = env.get("MACCMS_DB_PASSWORD", "")
    missing = [k for k, v in (("MACCMS_DB_URL", url), ("MACCMS_DB_USERNAME", user),
                              ("MACCMS_DB_PASSWORD", passwd)) if not v]
    require(not missing, "缺环境变量：%s" % ", ".join(missing))

    require(url.startswith("jdbc:mysql://"),
            "MACCMS_DB_URL 必须是 jdbc:mysql:// 形式（取值不打印）")
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
    """只放行一次性测试库；`maccms_dev` 与任何其它库名一律拒绝。"""
    ok = any(p.match(database) for p in ALLOWED_DATABASE_PATTERNS)
    require(ok, "拒绝连接库 '%s'：只允许 maccms_it_* 或 maccms_*_test 形式的一次性测试库"
                % database)


# --------------------------------------------------------------------------
# 外部工具：mysql / mysqldump
# --------------------------------------------------------------------------


def find_tool(name: str) -> str:
    """在 PATH 与常见 MySQL 安装位置里找 mysql/mysqldump。"""
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
    """写一份 600 的临时 my.cnf，密码不经过命令行参数。"""
    path = os.path.join(tmpdir, "client.cnf")
    lines = [
        "[client]",
        "host=%s" % target["host"],
        "port=%s" % target["port"],
        "user=%s" % target["user"],
        "password=%s" % target["password"],
        "default-character-set=utf8",
    ]
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass
    return path


def run_tool(argv: list, timeout: int = 600, stdin_path: str = None) -> subprocess.CompletedProcess:
    stdin = open(stdin_path, "rb") if stdin_path else subprocess.DEVNULL
    try:
        return subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              stdin=stdin, timeout=timeout)
    finally:
        if stdin_path:
            stdin.close()


# --------------------------------------------------------------------------
# SNAPD-1：dump
# --------------------------------------------------------------------------


def build_dump_argv(tool: str, defaults: str, database: str) -> list:
    return [tool, "--defaults-file=" + defaults] + list(DUMP_FLAGS) + [database] + list(TABLES)


# CHECKSUM TABLE 是语句不是函数，只能逐表单条执行（见 collect_checksums）；
# AUTO_INCREMENT 从 information_schema 一次取回。

CHECKSUM_QUERY = (
    "SELECT TABLE_NAME, AUTO_INCREMENT FROM information_schema.TABLES "
    "WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME;"
)


def query_rows(tool_mysql: str, defaults: str, database: str, sql: str) -> list:
    p = run_tool([tool_mysql, "--defaults-file=" + defaults, "--batch", "--skip-column-names",
                  "--default-character-set=utf8", database, "-e", sql], timeout=120)
    if p.returncode != 0:
        raise RuntimeError("mysql 执行失败（%d）：%s"
                           % (p.returncode, p.stderr.decode("utf-8", "replace").strip()[:400]))
    out = p.stdout.decode("utf-8", "replace")
    return [line.split("\t") for line in out.splitlines() if line != ""]


def collect_checksums(tool_mysql: str, defaults: str, database: str) -> dict:
    """返回 {表名: {"checksum": int|None, "auto_increment": int|None}}。"""
    result = {t: {"checksum": None, "auto_increment": None} for t in TABLES}

    for table in TABLES:
        rows = query_rows(tool_mysql, defaults, database, "CHECKSUM TABLE `%s`;" % table)
        require(len(rows) == 1 and len(rows[0]) >= 2,
                "CHECKSUM TABLE %s 返回行数异常：%r" % (table, rows))
        name, value = rows[0][0], rows[0][1]
        # CHECKSUM TABLE 返回的是 `库名.表名`（MySQL 5.7 行为），只取表名部分比对
        bare = name.rsplit(".", 1)[-1].strip("`")
        require(bare == table, "CHECKSUM TABLE 返回的表名不符：%r != %r" % (name, table))
        result[table]["checksum"] = None if value.upper() == "NULL" else int(value)

    rows = query_rows(tool_mysql, defaults, database, CHECKSUM_QUERY)
    present = set()
    for row in rows:
        require(len(row) >= 2, "information_schema 返回列数异常：%r" % (row,))
        name, auto = row[0], row[1]
        if name not in result:
            continue
        present.add(name)
        result[name]["auto_increment"] = None if auto.upper() == "NULL" else int(auto)

    missing = sorted(set(TABLES) - present)
    require(not missing, "库里缺表：%s" % ", ".join(missing))
    return result


def do_dump(args, env: dict) -> int:
    target = parse_mysql_target(env)
    snapshot = args.snapshot if os.path.isabs(args.snapshot) else os.path.join(REPO_ROOT, args.snapshot)
    meta = args.meta if os.path.isabs(args.meta) else os.path.join(REPO_ROOT, args.meta)

    tool_dump = find_tool("mysqldump")
    tool_mysql = find_tool("mysql")

    with tempfile.TemporaryDirectory(prefix="appk2-snap-") as tmp:
        defaults = write_defaults_file(target, tmp)
        os.makedirs(os.path.dirname(snapshot), exist_ok=True)

        # 快照先写临时文件，成功后再原子替换，避免半截文件被后续步骤当成有效快照。
        part = snapshot + ".part"
        with open(part, "wb") as fh:
            p = subprocess.run(build_dump_argv(tool_dump, defaults, target["database"]),
                               stdout=fh, stderr=subprocess.PIPE, stdin=subprocess.DEVNULL)
        if p.returncode != 0:
            os.unlink(part)
            die(3, "mysqldump 失败（%d）：%s"
                % (p.returncode, p.stderr.decode("utf-8", "replace").strip()[:400]))
        os.replace(part, snapshot)

        try:
            checks = collect_checksums(tool_mysql, defaults, target["database"])
        except (RuntimeError, AssertionError) as exc:
            die(3, "取 CHECKSUM/AUTO_INCREMENT 失败：%s" % exc)

    payload = {
        "_comment": "APPK-2 SNAPD-1 数据快照元信息；由 tests/compare/APPK-2/data_snapshot.py 生成",
        "database": target["database"],
        "snapshot": os.path.relpath(snapshot, REPO_ROOT).replace(os.sep, "/"),
        "snapshot_sha256": sha256_file(snapshot),
        "dump_flags": list(DUMP_FLAGS),
        "tables": checks,
    }
    os.makedirs(os.path.dirname(meta), exist_ok=True)
    with open(meta, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2, sort_keys=True)
        fh.write("\n")

    sys.stdout.write("快照已写入 %s（%d 张表，%d 字节，sha256=%s）\n"
                     % (os.path.relpath(snapshot, REPO_ROOT).replace(os.sep, "/"),
                        len(TABLES), os.path.getsize(snapshot), payload["snapshot_sha256"][:16]))
    sys.stdout.write("元信息已写入 %s\n" % os.path.relpath(meta, REPO_ROOT).replace(os.sep, "/"))
    return 0


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# --------------------------------------------------------------------------
# SNAPD-2：restore + verify
# --------------------------------------------------------------------------

DELETE_SQL = "SET FOREIGN_KEY_CHECKS=0;\n" + "".join(
    "DELETE FROM `%s`;\n" % t for t in TABLES) + "SET FOREIGN_KEY_CHECKS=1;\n"


def build_restore_sql(meta: dict) -> str:
    """SNAPD-2 的收尾：按快照里的 AUTO_INCREMENT 复位（只对有值的表发 ALTER）。"""
    out = ["SET FOREIGN_KEY_CHECKS=0;"]
    for table in TABLES:
        entry = meta["tables"].get(table)
        require(entry is not None, "快照 JSON 里缺表 %s" % table)
        auto = entry.get("auto_increment")
        if auto is not None:
            out.append("ALTER TABLE `%s` AUTO_INCREMENT=%d;" % (table, int(auto)))
    out.append("SET FOREIGN_KEY_CHECKS=1;")
    return "\n".join(out) + "\n"


def diff_checksums(expected: dict, actual: dict) -> list:
    """逐表逐项比对，返回人类可读的差异行（SNAPD-2 的判定依据）。"""
    problems = []
    for table in TABLES:
        e, a = expected.get(table, {}), actual.get(table, {})
        for field in ("checksum", "auto_increment"):
            if e.get(field) != a.get(field):
                problems.append("%s.%s 期望 %r 实际 %r" % (table, field, e.get(field), a.get(field)))
    return problems


def do_restore(args, env: dict) -> int:
    target = parse_mysql_target(env)
    snapshot = args.snapshot if os.path.isabs(args.snapshot) else os.path.join(REPO_ROOT, args.snapshot)
    meta_path = args.meta if os.path.isabs(args.meta) else os.path.join(REPO_ROOT, args.meta)
    if not os.path.isfile(snapshot):
        die(1, "快照文件不存在：%s" % args.snapshot)
    if not os.path.isfile(meta_path):
        die(1, "快照元信息不存在：%s" % args.meta)
    with open(meta_path, "r", encoding="utf-8") as fh:
        meta = json.load(fh)

    expected_sha = meta.get("snapshot_sha256")
    if expected_sha:
        actual_sha = sha256_file(snapshot)
        if actual_sha != expected_sha:
            die(2, "快照文件与元信息记录的 sha256 不符（快照被改过 / 版本不对）")

    tool_mysql = find_tool("mysql")
    attempts = []
    problems = []
    final = None
    for attempt in range(1, 3):  # SNAPD-2：首次 + 重试一次
        with tempfile.TemporaryDirectory(prefix="appk2-snap-") as tmp:
            defaults = write_defaults_file(target, tmp)
            # DELETE FROM
            p = run_tool([tool_mysql, "--defaults-file=" + defaults, "--default-character-set=utf8",
                          target["database"], "-e", DELETE_SQL], timeout=300)
            if p.returncode != 0:
                die(3, "DELETE FROM 失败（%d）：%s"
                    % (p.returncode, p.stderr.decode("utf-8", "replace").strip()[:400]))
            # 快照 SQL
            p = run_tool([tool_mysql, "--defaults-file=" + defaults, "--default-character-set=utf8",
                          target["database"]], timeout=900, stdin_path=snapshot)
            if p.returncode != 0:
                die(3, "导入快照失败（%d）：%s"
                    % (p.returncode, p.stderr.decode("utf-8", "replace").strip()[:400]))
            # AUTO_INCREMENT 复位
            p = run_tool([tool_mysql, "--defaults-file=" + defaults, "--default-character-set=utf8",
                          target["database"], "-e", build_restore_sql(meta)], timeout=300)
            if p.returncode != 0:
                die(3, "复位 AUTO_INCREMENT 失败（%d）：%s"
                    % (p.returncode, p.stderr.decode("utf-8", "replace").strip()[:400]))
            # 校验
            try:
                actual = collect_checksums(tool_mysql, defaults, target["database"])
            except (RuntimeError, AssertionError) as exc:
                die(3, "还原后取 checksum 失败：%s" % exc)

        problems = diff_checksums(meta["tables"], actual)
        attempts.append({"attempt": attempt, "problems": len(problems)})
        final = actual
        if not problems:
            break

    record = {
        "at": now_iso(),
        "database": target["database"],
        "attempts": attempts,
        "ok": not problems,
        "problems": problems,
        "problems_detail": problems[:20],
    }
    if args.log:
        log_path = args.log if os.path.isabs(args.log) else os.path.join(REPO_ROOT, args.log)
        os.makedirs(os.path.dirname(log_path), exist_ok=True)
        with open(log_path, "a", encoding="utf-8", newline="\n") as fh:
            fh.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")
        sys.stdout.write("恢复记录已追加 %s\n" % os.path.relpath(log_path, REPO_ROOT).replace(os.sep, "/"))

    if problems:
        sys.stdout.write("还原后仍不一致（重试 1 次后），按 SNAPD-2 判“环境失败”：\n")
        for line in problems[:20]:
            sys.stdout.write("  - %s\n" % line)
        return 2
    sys.stdout.write("还原后 26 张表的 checksum 与 AUTO_INCREMENT 与快照一致（尝试 %d 次）\n"
                     % len(attempts))
    return 0


def do_verify(args, env: dict) -> int:
    target = parse_mysql_target(env)
    meta_path = args.meta if os.path.isabs(args.meta) else os.path.join(REPO_ROOT, args.meta)
    if not os.path.isfile(meta_path):
        die(1, "快照元信息不存在：%s" % args.meta)
    with open(meta_path, "r", encoding="utf-8") as fh:
        meta = json.load(fh)
    tool_mysql = find_tool("mysql")
    with tempfile.TemporaryDirectory(prefix="appk2-snap-") as tmp:
        defaults = write_defaults_file(target, tmp)
        try:
            actual = collect_checksums(tool_mysql, defaults, target["database"])
        except (RuntimeError, AssertionError) as exc:
            die(3, "取 checksum 失败：%s" % exc)
    problems = diff_checksums(meta["tables"], actual)
    if problems:
        sys.stdout.write("不一致（%d 项）：\n" % len(problems))
        for line in problems[:20]:
            sys.stdout.write("  - %s\n" % line)
        return 2
    sys.stdout.write("一致：26 张表的 checksum 与 AUTO_INCREMENT 均与快照相同\n")
    return 0


def now_iso() -> str:
    import datetime

    return datetime.datetime.now().astimezone().isoformat(timespec="seconds")


# --------------------------------------------------------------------------
# selftest（离线；不连库、不调 mysqldump）
# --------------------------------------------------------------------------


def do_selftest(_args, _env=None) -> int:
    failures = []
    checks = [0]

    def check(name, ok, detail=""):
        checks[0] += 1
        if not ok:
            failures.append("%s%s" % (name, ("：" + detail) if detail else ""))
        sys.stdout.write("%s %s%s\n" % ("PASS" if ok else "FAIL", name,
                                        ("：" + detail) if detail and not ok else ""))

    # ---- 表清单 ---------------------------------------------------------
    check("表清单恰 26 张", len(TABLES) == 26)
    check("表清单无重复", len(set(TABLES)) == 26)
    check("表清单全部以 mac_ 开头", all(t.startswith("mac_") for t in TABLES))

    # ---- mysqldump 参数（技术设计逐字给出，不得增删） --------------------
    with tempfile.TemporaryDirectory() as tmp:
        argv = build_dump_argv("/usr/bin/mysqldump", os.path.join(tmp, "c.cnf"), "maccms_it_x")
        joined = " ".join(argv)
        for flag in DUMP_FLAGS:
            check("SNAPD-1 参数含 %s" % flag, flag in argv)
        check("SNAPD-1 数量恰为技术设计给出的 5 个",
              len([a for a in argv if a.startswith("--") and "defaults-file" not in a]) == 5,
              "实际 %r" % [a for a in argv if a.startswith("--") and "defaults-file" not in a])
        check("SNAPD-1 库名在表清单之前", argv.index("maccms_it_x") < argv.index(TABLES[0]))
        check("SNAPD-1 无建表语句（--no-create-info）", "--no-create-info" in argv)
        check("SNAPD-1 密码不在命令行里", "password" not in joined.lower())

    # ---- 库名白名单 -----------------------------------------------------
    for ok_db in ("maccms_it_20261102_abc", "maccms_full_2026_test"):
        try:
            assert_one_off_database(ok_db)
            check("白名单放行 %s" % ok_db, True)
        except AssertionError:
            check("白名单放行 %s" % ok_db, False, "竟然拒绝")
    for bad_db in ("maccms_dev", "maccms", "mysql", "maccms_prod", "information_schema"):
        try:
            assert_one_off_database(bad_db)
            check("白名单拒绝 %s" % bad_db, False, "竟然放行")
        except AssertionError:
            check("白名单拒绝 %s" % bad_db, True)

    # ---- 连接参数解析 ---------------------------------------------------
    env_ok = {"MACCMS_DB_URL": "jdbc:mysql://10.0.0.5:3307/maccms_it_full?useSSL=false",
              "MACCMS_DB_USERNAME": "u", "MACCMS_DB_PASSWORD": "p"}
    t = parse_mysql_target(env_ok)
    check("解析 host", t["host"] == "10.0.0.5")
    check("解析 port", t["port"] == "3307")
    check("解析 database", t["database"] == "maccms_it_full")
    check("解析结果带着凭据但不出现在任何输出", t["password"] == "p")

    try:
        parse_mysql_target({"MACCMS_DB_URL": env_ok["MACCMS_DB_URL"]})
        check("缺变量必须拒绝", False, "竟然通过")
    except AssertionError:
        check("缺变量必须拒绝", True)

    try:
        parse_mysql_target({"MACCMS_DB_URL": "jdbc:mysql://h:3306/maccms_dev",
                            "MACCMS_DB_USERNAME": "u", "MACCMS_DB_PASSWORD": "p"})
        check("拒绝连 maccms_dev", False, "竟然通过")
    except AssertionError:
        check("拒绝连 maccms_dev", True)

    try:
        parse_mysql_target({"MACCMS_DB_URL": "jdbc:mysql://h:3306/",
                            "MACCMS_DB_USERNAME": "u", "MACCMS_DB_PASSWORD": "p"})
        check("缺库名必须拒绝", False, "竟然通过")
    except AssertionError:
        check("缺库名必须拒绝", True)

    # ---- defaults-file 不含明文密码之外的输出 ---------------------------
    with tempfile.TemporaryDirectory() as tmp:
        d = write_defaults_file({"host": "h", "port": "3306", "user": "u", "password": "secret"},
                                tmp)
        with open(d, encoding="utf-8") as fh:
            body = fh.read()
        check("defaults-file 含 [client]", "[client]" in body)
        check("defaults-file 含 host/port/user", all(k in body for k in ("host=", "port=", "user=")))
        check("defaults-file 含 password 段", "password=secret" in body)

    # ---- 还原 SQL / diff ------------------------------------------------
    meta = {"tables": {t_: {"checksum": 100 + i, "auto_increment": 10 + i}
                       for i, t_ in enumerate(TABLES)}}
    # 把一张表的 AUTO_INCREMENT 改成 None，验证不发 ALTER
    meta["tables"]["mac_msg"]["auto_increment"] = None
    sql = build_restore_sql(meta)
    check("还原 SQL 为 25 张表发 ALTER（NULL 的不发）", sql.count("ALTER TABLE") == 25,
          "实际 %d" % sql.count("ALTER TABLE"))
    check("还原 SQL 关掉外键检查", "SET FOREIGN_KEY_CHECKS=0;" in sql and "SET FOREIGN_KEY_CHECKS=1;" in sql)
    check("DELETE 覆盖 26 张表", DELETE_SQL.count("DELETE FROM") == 26)

    check("完全一致时无差异", diff_checksums(meta["tables"], meta["tables"]) == [])
    drifted = {t_: dict(v) for t_, v in meta["tables"].items()}
    drifted["mac_vod"]["checksum"] = 1
    diff = diff_checksums(meta["tables"], drifted)
    check("checksum 变化被检出", len(diff) == 1 and diff[0].startswith("mac_vod.checksum"), str(diff))
    drifted2 = {t_: dict(v) for t_, v in meta["tables"].items()}
    drifted2["mac_user"]["auto_increment"] = 99
    diff2 = diff_checksums(meta["tables"], drifted2)
    check("AUTO_INCREMENT 变化被检出",
          len(diff2) == 1 and diff2[0].startswith("mac_user.auto_increment"), str(diff2))

    missing = dict(meta["tables"])
    del missing["mac_link"]
    diff3 = diff_checksums(meta["tables"], missing)
    # 缺一张表 → checksum 与 auto_increment 两项都缺，所以是 2 行差异
    check("缺表被检出（2 行：checksum + auto_increment）",
          len(diff3) == 2 and all(d.startswith("mac_link.") for d in diff3), str(diff3))

    # ---- 快照文件的 sha256 一致性 --------------------------------
    with tempfile.TemporaryDirectory() as tmp:
        f = os.path.join(tmp, "s.sql")
        with open(f, "wb") as fh:
            fh.write(b"-- x\n")
        check("sha256 稳定", sha256_file(f) == sha256_file(f))

    total = checks[0]
    sys.stdout.write("\n%d / %d 项通过；失败 %d 项\n" % (total - len(failures), total, len(failures)))
    if failures:
        for f_ in failures:
            sys.stdout.write("  FAIL %s\n" % f_)
    return 0 if not failures else 1


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------


def main(argv: list) -> int:
    ap = argparse.ArgumentParser(description="APPK-2 C-62 数据快照与还原（SNAPD-1～SNAPD-3）")
    sub = ap.add_subparsers(dest="cmd", required=True)

    d = sub.add_parser("dump", help="SNAPD-1：导出 26 张表的数据快照 + checksum/AUTO_INCREMENT")
    d.add_argument("--snapshot", default=os.path.join(".compare", "APPK-2", "data-snapshot.sql"))
    d.add_argument("--meta", default=os.path.join("docs", "test", "APPK-2", "data-snapshot.json"))
    d.set_defaults(func=do_dump)

    r = sub.add_parser("restore", help="SNAPD-2/3：还原并校验，失败重试一次")
    r.add_argument("--snapshot", default=os.path.join(".compare", "APPK-2", "data-snapshot.sql"))
    r.add_argument("--meta", default=os.path.join("docs", "test", "APPK-2", "data-snapshot.json"))
    r.add_argument("--log", default=os.path.join(".compare", "APPK-2", "restore-log.jsonl"))
    r.set_defaults(func=do_restore)

    v = sub.add_parser("verify", help="只读校验当前库与快照是否一致")
    v.add_argument("--meta", default=os.path.join("docs", "test", "APPK-2", "data-snapshot.json"))
    v.set_defaults(func=do_verify)

    s = sub.add_parser("selftest", help="离线自测（不连库）")
    s.set_defaults(func=do_selftest)

    args = ap.parse_args(argv)
    try:
        return args.func(args, os.environ)
    except AssertionError as exc:
        # 输入/合规断言（库名白名单、缺变量等）：判用法错误，不当作脚本崩溃
        die(1, str(exc))


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except SystemExit:
        raise
    except Exception as exc:  # noqa: BLE001
        sys.stderr.write("脚本自身异常：%r\n" % (exc,))
        sys.exit(4)
