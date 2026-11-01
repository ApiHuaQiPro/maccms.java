# APPK-2 PRE-5 模板扫描清单（**初稿，未完成**）

> 本文件由 `tests/compare/APPK-2/scan_templates.py` 生成，只含**机器扫描数据**。
> PRE-5 的完成判定（PRD §13）要求：168 个 `.html` 各占一行以上；每处 PHP 代码有对应实现说明；
> 所有产生当前时间/日期、随机值、会话或 token 的位置逐处登记并归属 EX-1、EX-2 或 DATA-2，
> **没有“归属未定”的行**。实现说明与归属由架构师在 S3-04 补齐，本初稿不得当作 PRE-5 完成。

| 项 | 内容 |
| --- | --- |
| 生成来源 | `tests/compare/APPK-2/scan_templates.py` |
| 扫描根 | `.compare/APPK-2/fx1/template` |
| 扫描文件数 | 15（`.html` 8） |
| 清单比对 | `.compare/APPK-2/fx1/manifest.tsv`（15 行） |

## 1. 校验结果

| 检查项 | 结果 | 说明 |
| --- | --- | --- |
| E.2-总数 | MISMATCH | template/ 下文件数 15，期望 274 |
| E.2-default_pc | MISMATCH | default_pc 文件数 10，期望 147 |
| E.2-default_wap | MISMATCH | default_wap 文件数 5，期望 127 |
| E.2-default_pc-html | MISMATCH | default_pc 的 .html 6 个，期望 92 |
| E.2-default_wap-html | MISMATCH | default_wap 的 .html 2 个，期望 76 |
| E.2-目录集合 | OK | template/ 下的目录：default_pc, default_wap（期望只有 default_pc, default_wap） |
| E.2-default_wap/ads | OK | default_wap/ads：不存在 |
| RISK-T1-CRLF | MISMATCH | 工作区文件含 CRLF 的：1 个：default_wap/js/app.js |
| E.4-EF-1-js/css/ads 不含 {maccms | MISMATCH | js/、css/、ads/ 下共 7 个文件，其中命中 {maccms 的 5 个：default_pc/ads/aaa.js, default_pc/css/style.css, default_pc/js/app.js, default_wap/css/style.css, default_wap/js/app.js |
| E.3-清单条数 | MISMATCH | 清单 15 行，期望 274 行 |
| E.3-清单缺文件 | OK | 清单有、包里没有：0 个 |
| E.3-包内多文件 | OK | 包里有、清单没有：0 个 |
| E.3-字节数 | OK | 字节数不符：0 个 |
| E.3-blob-SHA-1 | OK | SHA-1 不符：0 个 |
| E.4-EF-3-default_pc | MISMATCH | 差异 11 处：actor: 实际 0 / 期望 6; art: 实际 0 / 期望 7; comment: 实际 0 / 期望 2; for: 实际 0 / 期望 1; foreach: 实际 0 / 期望 49; gbook: 实际 0 / 期望 1; link: 实际 0 / 期望 6; topic: 实际 0 / 期望 1 |
| E.4-EF-3-default_wap | MISMATCH | 差异 11 处：art: 实际 0 / 期望 2; comment: 实际 0 / 期望 2; for: 实际 0 / 期望 1; foreach: 实际 0 / 期望 27; gbook: 实际 0 / 期望 1; link: 实际 0 / 期望 1; siteaid: 实际 0 / 期望 1; sitetid: 实际 0 / 期望 1 |
| E.4-EF-5-include | MISMATCH | include 出现 1 次，期望 234 |
| E.4-EF-5-if | MISMATCH | if 出现 1 次，期望 210 |
| E.4-EF-5-volist | MISMATCH | volist 出现 0 次，期望 35 |
| E.4-EF-5-else | MISMATCH | else 出现 1 次，期望 35 |
| E.4-EF-5-elseif | MISMATCH | elseif 出现 0 次，期望 18 |
| E.4-EF-5-empty | MISMATCH | empty 出现 0 次，期望 10 |
| E.4-EF-5-foreach | MISMATCH | foreach 出现 0 次，期望 4 |
| E.4-EF-5-switch | MISMATCH | switch 出现 0 次，期望 1 |
| E.4-EF-2-已知 6 文件齐全 | MISMATCH | 扫描未在下列文件里找到 PHP 代码：default_pc/html/vod/plot.html, default_wap/html/vod/copyright.html |
| E.4-EF-2-无新增 PHP 文件 | OK | 「无新增」 |
| F.5-规则2-归属 | UNATTRIBUTED | 归属未定的时钟/随机/会话来源：2 处：default_pc/html/art/detail.html:1（date）; default_pc/html/vod/detail.html:5（mac_day） |

## 2. `{maccms:…}` 标签（两个包分别统计）

| 标签 | default_pc | default_wap |
| --- | --- | --- |
| `path` | 0 | 1 |
| `siteid` | 0 | 1 |
| `vod` | 2 | 0 |

## 3. 其他模板语法（两包合计）

| 语法 | 次数 |
| --- | --- |
| `{else}` | 1 |
| `{if}` | 1 |
| `{include}` | 1 |
| `{php}` | 1 |

## 4. `{:函数()}` 调用清单（白名单 RT-7 的输入）

| 函数 | 次数 |
| --- | --- |
| `mac_day` | 1 |
| `mac_url_type` | 1 |

## 5. 模板变量（`{$a.b}`）

| 变量 | 次数 |
| --- | --- |
| `$vo.vod_name` | 2 |
| `$maccms.path_tpl` | 1 |

## 6. 含 PHP 代码的模板（每处一行，待架构师写实现说明）

| 文件 | 行 | 形式 | 片段 | 实现说明（待填） |
| --- | --- | --- | --- | --- |
| `default_pc/html/art/detail.html` | 1 | {php} | `{php}echo date('Y-m-d');{/php}` |  |
| `default_pc/html/public/jump.html` | 1 | <?php | `<?php echo($url);?>` |  |
| `default_pc/html/vod/copyright.html` | 1 | <?php | `<?php echo($obj['vod_jumpurl']);?>` |  |
| `default_wap/html/public/jump.html` | 1 | <?php | `<?php echo($url);?>` |  |

## 7. 时钟 / 随机 / 会话 / token 来源（附表 F.5 规则 2，归属待架构师填）

| 文件 | 行 | 类别 | 调用 | 归属 | 上下文 |
| --- | --- | --- | --- | --- | --- |
| `default_pc/html/art/detail.html` | 1 | time | `date` | **归属未定** | `<p>{php}echo date('Y-m-d');{/php}</p>` |
| `default_pc/html/vod/detail.html` | 5 | time | `mac_day` | **归属未定** | `{:mac_day($obj.vod_time)}` |
| `default_pc/html/vod/detail.html` | 1 | random | `by="rnd"` | DATA-2 | `{maccms:vod num="4" by="rnd"}` |

## 8. 逐文件清单（168 个 `.html` 各一行；实现说明列待架构师补）

| 文件 | 标签数 | 语法数 | include | 函数 | 变量 | PHP 代码 | 实现说明（待填） |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `default_pc/html/art/detail.html` | 0 | 1 | 0 | 0 | 0 | 1 |  |
| `default_pc/html/index/index.html` | 1 | 1 | 1 | 1 | 2 | 0 |  |
| `default_pc/html/public/head.html` | 0 | 0 | 0 | 0 | 0 | 0 |  |
| `default_pc/html/public/jump.html` | 0 | 0 | 0 | 0 | 0 | 1 |  |
| `default_pc/html/vod/copyright.html` | 0 | 0 | 0 | 0 | 0 | 1 |  |
| `default_pc/html/vod/detail.html` | 1 | 2 | 0 | 1 | 1 | 0 |  |
| `default_wap/html/public/jump.html` | 0 | 0 | 0 | 0 | 0 | 1 |  |
| `default_wap/html/user/include.html` | 2 | 0 | 0 | 0 | 0 | 0 |  |
