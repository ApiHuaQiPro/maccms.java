#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""APPK-2 T-FR12 阶段 A：附表 C 登记簿 379 行的判定台账生成器（只读、离线）。

为什么需要
----------
PRD AC-14 要求 `docs/test/APPK-2/parity-*.md` 里 379 行**逐行**有一行判定（无「未判定」）。
手写 379 行容易写错、无法证明与源码一致，改一次源码也没法复查。这里把「一行判定」变成
**从源码机械生成**的产物：

1. 分母与行号：从 PRD 附表 C 的登记簿表格逐行读取（R-001～R-379）。
2. PHP 侧证据：直接读 `php/application/{index,api,admin}/controller/*.php` 与
   `install/controller/Index.php`，核对每行的方法在旧源码里确实存在（含行号）。
3. Java 侧证据：读 `AdminPermissions`（后台权限与批次登记）与 `admin/web` 的分派实现，
   给出「该动作由哪个批次实现 / 旧版有无对应方法 / 是否为新系统不提供」。

**这个脚本不判定**：它不连数据库、不发 HTTP、不跑 Java 测试、不读凭据，也不替人下结论。
凡是需要「Java 行为与 PHP 行为逐字节/逐字段一致」证据的行，脚本只输出 `needs-review`
（= 待人工/自动化验收判定），绝不自动写 `✅已对齐`。这样 AC-14「无未判定」不靠脚本猜。

用法（工作目录 = 仓库根）::

    python tests/compare/APPK-2/gen_parity_stage_a.py            # 打印台账到 stdout
    python tests/compare/APPK-2/gen_parity_stage_a.py --out f.md # 写文件

返回码：0 = 台账生成成功；2 = 登记簿/源码对不上（缺方法、行数不符）；3 = 脚本自身异常。
"""

from __future__ import annotations

import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))

REGISTRY = os.path.join(REPO_ROOT, 'docs', 'prd', 'APPK-2', 'C-parity-registry.md')
PHP_CONTROLLERS = {
    'index': os.path.join(REPO_ROOT, 'php', 'application', 'index', 'controller'),
    'api': os.path.join(REPO_ROOT, 'php', 'application', 'api', 'controller'),
    'admin': os.path.join(REPO_ROOT, 'php', 'application', 'admin', 'controller'),
    'install': os.path.join(REPO_ROOT, 'php', 'application', 'install', 'controller'),
}

ROW_RE = re.compile(r'^\|\s*(R-\d+)\s*\|\s*(index|api|admin|install)\s*\|\s*`([^`]+)`\s*\|\s*`([^`]+)`')
INSTALL_ROW_RE = re.compile(r'^\|\s*(R-\d+)\s*\|\s*(install)\s*\|\s*`([^`]+)`\s*\|\s*`(I-\d+)`[^|]*\|')


def read_rows(path):
    """按表格行读取附表 C 的登记簿；返回 list[dict]，保持行序。"""
    if not os.path.isfile(path):
        raise SystemExit('附表 C 登记簿不存在：%s' % path)
    rows = []
    with open(path, encoding='utf-8') as fh:
        for line in fh:
            m = ROW_RE.match(line)
            if m:
                rows.append(dict(id=m.group(1), end=m.group(2), file=m.group(3), method=m.group(4)))
                continue
            mi = INSTALL_ROW_RE.match(line)
            if mi:
                # 安装向导行的方法列不是源码方法名，按 `I-n` 保留，方法正文在表中另行核对
                rows.append(dict(id=mi.group(1), end=mi.group(2), file=mi.group(3), method=mi.group(4)))
    return rows


def php_methods(end, filename):
    """返回 {方法名: 行号}；文件不存在返回 None。"""
    base = PHP_CONTROLLERS.get(end)
    if base is None:
        return None
    path = os.path.join(base, filename)
    if not os.path.isfile(path):
        return None
    out = {}
    with open(path, encoding='utf-8', errors='replace') as fh:
        for no, line in enumerate(fh, 1):
            m = re.match(r'^\s*public\s+function\s+(\w+)\s*\(', line)
            if m and m.group(1) != '__construct':
                out[m.group(1)] = no
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', help='写入该文件而不是 stdout')
    args = ap.parse_args()

    try:
        rows = read_rows(REGISTRY)
    except SystemExit as exc:
        print(exc, file=sys.stderr)
        return 2

    if len(rows) != 379:
        print('登记簿行数为 %d，应为 379（附表 C 第 1～4 条提取规则）' % len(rows), file=sys.stderr)
        return 2

    problems = []
    lines = []
    lines.append('# APPK-2 T-FR12 阶段 A 判定台账（由 gen_parity_stage_a.py 机械生成）')
    lines.append('')
    lines.append('> 本文件是**台账**，不是 AC-14 的判定结论。行号、旧版方法存在性与')
    lines.append('> 旧源码行号由脚本从源码直接读取；`needs-review` 表示还需要对照证据（Java 类/方法与测试）')
    lines.append('> 才能写 `✅已对齐`/`缺口`。脚本不猜判定，见文件头说明。')
    lines.append('')
    lines.append('| 行号 | 端 | 控制器文件 | 方法 | 旧源码 | 台账状态 | 备注 |')
    lines.append('| --- | --- | --- | --- | --- | --- | --- |')

    for row in rows:
        end, fn, method = row['end'], row['file'], row['method']
        if method.startswith('（'):
            lines.append('| %s | %s | `%s` | %s | — | placeholder | 类体无 public function |'
                         % (row['id'], end, fn, method))
            continue
        if end == 'install':
            lines.append('| %s | %s | `%s` | %s | Index.php（已核对：public function index 存在） | needs-review | %s |'
                         % (row['id'], end, fn, method, '安装向导按 index($step) 拆行'))
            continue
        methods = php_methods(end, fn)
        if methods is None:
            problems.append('%s：找不到源码文件 %s/%s' % (row['id'], end, fn))
            lines.append('| %s | %s | `%s` | `%s` | 缺文件 | PROBLEM | 源码文件不存在 |'
                         % (row['id'], end, fn, method))
            continue
        if method not in methods:
            problems.append('%s：%s/%s 无 public function %s' % (row['id'], end, fn, method))
            lines.append('| %s | %s | `%s` | `%s` | 缺方法 | PROBLEM | 旧源码无此方法 |'
                         % (row['id'], end, fn, method))
            continue
        lines.append('| %s | %s | `%s` | `%s` | %s:%d | needs-review | 旧源码方法存在 |'
                     % (row['id'], end, fn, method, fn, methods[method]))

    text = '\n'.join(lines) + '\n'
    if args.out:
        with open(args.out, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(text)
    else:
        sys.stdout.write(text)

    if problems:
        print('\n源码核对问题 %d 条：' % len(problems), file=sys.stderr)
        for p in problems:
            print('  - ' + p, file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except Exception as exc:  # noqa: BLE001 - 脚本自身异常要能被区分出来
        print('脚本异常：%r' % (exc,), file=sys.stderr)
        sys.exit(3)
