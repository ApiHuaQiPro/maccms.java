#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成**合成**模板包夹具，用来离线自测 `scan_templates.py` / `verify_template_package.py`。

为什么不是拿真包自测：真包（274 个文件）由 S3-01 入库，测试线在 S3-03 阶段不产出、也不改动它；
S3-03 又明确“不执行 HTTP 请求、不连接环境”，因此 S3-03 只能证明**工具本身**正确，
真包比对要等 S3-01 入库且 S3-04 复核后，在 S3-05 用真包跑。夹具与真包的关系写在
`docs/test/APPK-2/template-scan.md` 与 `docs/test/APPK-2/template-assets-plain.md` 里，
本脚本从不冒充真包：所有输出都带 `SYNTHETIC-` 前缀，且 `verify_template_package.py`
对合成夹具只做“按生成清单比对”，不会把它当成附表 E 的 274 行。

用法（工作目录 = 仓库根）::

    python tests/compare/APPK-2/make_synthetic_package.py --out .compare/APPK-2/fixture-package

输出：
* `<out>/template/{default_pc,default_wap}/…`：合成模板（含各类语法、PHP 代码、时钟/随机调用、CRLF 样本）
* `<out>/manifest.tsv`：该夹具自身的清单（同一 TSV 格式，供工具比对）
* 返回码 0 = 生成成功；1 = 参数/IO 错误

夹具故意包含的“陷阱”（每一条都对应自测用例，见 `scan_templates.py --self-test`）：

* `default_pc/html/index/index.html` 含 `{maccms:vod}`、`{include}`、`{:mac_url_type(...)}`、`{$maccms.path_tpl}`；
* `default_pc/html/public/jump.html` 含 `<?php echo($url);?>`（附表 E.4 EF-2 同类写法）；
* `default_pc/html/art/detail.html` 含 `{php}` 块；
* `default_pc/html/vod/detail.html` 含 `by="rnd"`（应被归属为 DATA-2）、`{:date("Y-m-d")}`（应待架构师归属）；
* `default_wap/html/user/include.html` 含 `{maccms:path}`、`{maccms:siteid}`（附表 E.4 EF-3 的包外标签）；
* `default_wap/ads/` **不生成**（附表 E.2 规定该目录不存在）；
* `default_pc/js/app.js` 里写一个 `{maccms:vod}` 字样，用来验证「js/css/ads 里没有模板语法」这条检查真的会报。
"""

from __future__ import annotations

import argparse
import hashlib
import os
import sys

FILES = {
    "default_pc/info.ini": "name = SYNTHETIC-PC\nadsdir = ads\n",
    "default_pc/ads/aaa.js": "// SYNTHETIC\n",
    "default_pc/css/style.css": "body{color:#000}\n",
    "default_pc/js/app.js": "var x = 1; // SYNTHETIC js must not carry template syntax\n",
    "default_pc/html/index/index.html": (
        "{include file=\"public/head\" /}\n"
        "<div>{:mac_url_type($type)}</div>\n"
        "<a href=\"{$maccms.path_tpl}css/style.css\">css</a>\n"
        "{maccms:vod num=\"8\" by=\"time\"}\n"
        "  <li>{$vo.vod_name}</li>\n"
        "{/maccms:vod}\n"
    ),
    "default_pc/html/public/head.html": "<head><title>SYNTHETIC</title></head>\n",
    "default_pc/html/public/jump.html": "<script>setTimeout(function(){location.href='<?php echo($url);?>'},1000)</script>\n",
    "default_pc/html/art/detail.html": "<p>{php}echo date('Y-m-d');{/php}</p>\n",
    "default_pc/html/vod/detail.html": (
        "{maccms:vod num=\"4\" by=\"rnd\"}\n"
        "  <li>{$vo.vod_name}</li>\n"
        "{/maccms:vod}\n"
        "{if condition=\"$obj.vod_id gt 0\"}ok{else /}no{/if}\n"
        "{:mac_day($obj.vod_time)}\n"
        # 真包里的实际写法是**过滤器**（内联 date( 一处也没有），夹具必须覆盖这两种形态：
        "{if condition=\"$vo.vod_time gt 0\"}{$vo.vod_time|date='Y-m-d',###}{/if}\n"
        "{$obj.user_end_time|mac_day}\n"
    ),
    "default_pc/html/vod/copyright.html": "<p><?php echo($obj['vod_jumpurl']);?></p>\n",
    # 附表 E.4 EF-2 的另两个已知文件：让夹具覆盖“6 个都齐全”这条正向用例。
    "default_pc/html/vod/plot.html": "<p><?php echo($obj['vod_plot']);?></p>\n",
    "default_wap/html/vod/copyright.html": "<p><?php echo($obj['vod_copyright']);?></p>\n",
    "default_wap/info.ini": "name = SYNTHETIC-WAP\nadsdir = ads\n",
    "default_wap/css/style.css": "body{color:#fff}\n",
    "default_wap/html/user/include.html": "{maccms:path}{maccms:siteid}\n",
    "default_wap/html/public/jump.html": "<p><?php echo($url);?></p>\n",
}

# 一个含 CRLF 的文件：验证 RISK-T1 检查（工作区被 autocrlf 改写）会报出来。
CRLF_FILE = "default_wap/js/app.js"


def sha1_git_blob(data: bytes) -> str:
    h = hashlib.sha1()
    h.update(b"blob %d\0" % len(data))
    h.update(data)
    return h.hexdigest()


def make_file(root: str, rel: str, data: bytes) -> None:
    full = os.path.join(root, *rel.split("/"))
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "wb") as fh:
        fh.write(data)


def main(argv: list) -> int:
    ap = argparse.ArgumentParser(description="生成合成模板包夹具（不触碰真包）")
    ap.add_argument("--out", required=True, help="输出目录（建议放在已 gitignore 的 .compare/APPK-2/ 下）")
    ap.add_argument("--no-crlf", action="store_true", help="不生成 CRLF 样本（用于“全部通过”的正向夹具）")
    args = ap.parse_args(argv)

    root = os.path.join(args.out, "template")
    if os.path.exists(root):
        sys.stderr.write("输出目录已存在，请换一个目录或先删除：%s\n" % root)
        return 1

    rows = []
    for rel, content in FILES.items():
        data = content.encode("utf-8")
        make_file(root, rel, data)
        rows.append(("template/" + rel, len(data), sha1_git_blob(data)))

    if not args.no_crlf:
        data = b"var y = 2;\r\n// SYNTHETIC CRLF sample\r\n"
        make_file(root, CRLF_FILE, data)
        rows.append(("template/" + CRLF_FILE, len(data), sha1_git_blob(data)))

    rows.sort()
    manifest_path = os.path.join(args.out, "manifest.tsv")
    with open(manifest_path, "wb") as fh:
        fh.write(b"# SYNTHETIC fixture manifest (not PRD appendix E)\n")
        fh.write(b"path\tbytes\tblob_sha1\n")
        for p, size, sha in rows:
            fh.write(("%s\t%d\t%s\n" % (p, size, sha)).encode("utf-8"))

    sys.stdout.write("已生成合成夹具：%s（%d 个文件），清单：%s\n"
                     % (root.replace(os.sep, "/"), len(rows), manifest_path.replace(os.sep, "/")))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
