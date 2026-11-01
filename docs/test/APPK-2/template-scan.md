# APPK-2 模板包校验与 PRE-5 扫描方案（S3-03 交付）

> 归属：测试人员-海外（S3-03）。状态：**工具已就绪；真包已用 S3-01 的入库提交在本地只读跑通 C-9 与 C-65**。
> 本文件不含“PRE-5 已完成 / AC-1 通过”这类结论：PRE-5 第 6、7 节仍需架构师补实现说明与归属（见第 5 节）。
> **S3-04 补全（架构师）见第 9 节**：PHP 代码逐处实现说明、80 处时间来源归属（`pre5-attribution.tsv`）、扫描口径、对照环境边界、源提交溯源与 168 个 `.html` 逐文件说明。第 0～8 节为 S3-03 原文，被第 9 节更正的说法以第 9 节为准。
> 依据版本：PRD v9 `8fa362c25b62b52ed5b16afd9eca7f6ccfcafb9f`；技术设计 v10 `08043003d67209d79a7c2334407def58a58d2d56`；
> 旧系统模板来源提交 `4c418c2703692148a937ad92893ab8a494bff742`（MacCMS `v2020.1000.1035`）。

## 0. 一句话结论（先看这里）

| 项 | 状态 | 依据 |
| --- | --- | --- |
| 逐文件校验工具（C-9） | 已交付，离线自测 23 项全过 | `tests/compare/APPK-2/verify_template_package.py`、`selftest_offline.py` |
| 274 行清单副本 | 已交付，从 PRD 附表 E.3 机械生成并 `--check` 校验一致 | `docs/test/APPK-2/fixtures/template-package-manifest.tsv` |
| PRE-5 扫描工具（C-65） | 已交付，离线自测全过 | `scan_templates.py` |
| **真包 C-9 校验** | **已跑，返回码 0**：274 文件的路径、字节数、blob SHA-1、目录边界全部符合附表 E.3 | 第 5.1 节 |
| **真包 PRE-5 机器数据** | **已跑，27 项检查 1 项非 OK**：唯一非 OK 是「归属未定」，属预期（等架构师填） | 第 5.2 节 |
| PRE-5 **完成判定** | **不成立**：第 6、7 节的实现说明与归属仍空 | 第 5.3 节 |
| AC-1 | **不由本文件判**：需 PRE-5 定稿 + S3-05 复核后判 | — |

## 1. 工具清单与职责边界

| 文件 | 技术设计条目 | 做什么 | 谁在什么阶段跑 |
| --- | --- | --- | --- |
| `tests/compare/APPK-2/build_template_manifest.py` | 支撑 C-9 | 从 PRD 附表 E.3 生成/校验 274 行清单副本 | 任何一方；`--check` 用于防手改 |
| `tests/compare/APPK-2/verify_template_package.py` | **C-9** | 逐文件 `git blob SHA-1` + 字节数 + 路径集合 + 目录边界（只 `default_pc`/`default_wap`，无 `default_wap/ads`） | S3-05 对 S3-01 的包跑；PRE-2 对 `php-site`/`java-site` 各跑一次 |
| `tests/compare/APPK-2/scan_templates.py` | **C-65** | PRE-5 需要的机器事实：标签计数、其他语法计数、`{:函数}`、`{$变量}`、PHP 代码位置、`{include}`、时钟/随机/会话来源台账 | S3-05 出数据；架构师在 S3-04 补实现说明与归属 |
| `tests/compare/APPK-2/make_synthetic_package.py` | 支撑自测 | 造合成夹具（**不是真包**，文件名带 SYNTHETIC 前缀） | 只用于自测 |
| `tests/compare/APPK-2/selftest_offline.py` | 支撑自测 | 23 条「应当通过 / 应当报错」的离线自测 | 任何一方在改过这四个脚本后重跑 |

三个约束，写给复核人核对：

1. **只读、离线。** 五个脚本都不联网、不启动 PHP/Java、不发 HTTP、不读凭据；对输入目录只 `open(..., 'rb')` 与 `os.scandir`，不写、不删输入目录里的任何文件。
2. **不冒充结论。** `scan_templates.py` 遇到无法归属的时钟/随机/会话来源时记 `owner=null` 并返回 2；**从不**为了让检查变绿而自动填一个 EX-。附表 F.6 已明文规定 `by="rnd"` → DATA-2，这是唯一由脚本预填的映射。
3. **返回码不混用。** `0` = 一致；`2` = 比对发现不符（缺陷/环境信号）；`1` = 用法或输入错误；`3` = 脚本自身异常。复核时看返回码不看措辞，可避免把“脚本崩了”读成“校验失败”。

## 2. `verify_template_package.py`（C-9）判定口径

| 检查 | 通过条件 | 不符时 |
| --- | --- | --- |
| 路径集合 | 目标 `template/` 下 274 个相对路径与清单逐一相同 | 逐条列出「清单有、目标没有」「目标有、清单没有」 |
| 字节数 | 每个文件与附表 E.3 的字节数相等 | 逐条列出实际值与期望值 |
| blob SHA-1 | `sha1("blob <len>\0" + bytes)` 与清单相等 | 逐条列出两个哈希 |
| 目录边界 | `template/` 下只有 `default_pc`、`default_wap`；`default_wap/ads` 不存在；`template/` 下没有裸文件 | 逐条列出 |
| 分目录计数 | `default_pc` 147、`default_wap` 127、总计 274 | 列出实际值 |
| CRLF | 无文件含 `\r\n` | 列出前 5 个（RISK-T1 / NOIGN-4：CRLF 会让 blob SHA-1 与官方不符） |

**关于 `git hash-object` 的一个坑（本机实测，必须写进验收口径）：** 本工作区 `core.autocrlf=true`。
`git hash-object <工作区文件>` 会先应用 CRLF 过滤器，于是 CRLF 文件得到的是 **LF 化之后**的哈希，
与“按字节校验”自相矛盾。实测：某 38 字节 CRLF 文件，`git hash-object` 给出的是 36 字节 LF 版本的
blob `950c42d…`，而按字节算是 `c98fd11…`。因此脚本默认在 Python 里按字节算，`--use-git` 走
`git hash-object --no-filters`；并在启动时把 `core.autocrlf` 的实际取值打印进报告，避免复核人
用交替口径争论。**这也是 RISK-T1 的机制本身**，S3-05 跑真包时若报出 CRLF，就是 `.gitattributes`
规则（C-5）没生效或包被转换过，属于要退回 S3-01 的问题。

三种输入形态（`--target`）都支持：`<站点根>`（`template/` 在其下）、
`third_party/maccms-template-v2020.1000.1035`、以及 `template/` 目录本身。

## 3. `scan_templates.py`（C-65）产出与 PRE-5 的关系

脚本输出两份东西：

* `scan-result.json`：**机器事实**（每文件的字节数、blob SHA-1、标签/语法/函数/变量/`{include}`/PHP 代码/非确定来源逐处行号），供 S3-05 的复核与后续 RT 用例引用；
* `template-scan-draft.md`：**PRE-5 表格初稿**（Markdown），其中“实现说明”与“归属”两列**留空**。

PRE-5 的完成判定（PRD §13）与初稿的差距，逐条写清楚：

| PRE-5 要求 | 初稿是否满足 | 谁补 |
| --- | --- | --- |
| 168 个 `.html`（92+76）各占一行以上 | 满足（第 8 节逐文件表，每行一个 `.html`） | — |
| 每处 PHP 代码有对应实现说明 | **不满足**：第 6 节列出文件与行号，说明列空 | 架构师（S3-04），写作条目 C-17～C-24 的实现依据 |
| 所有当前时间/日期、随机值、会话/token 位置逐处登记并归属 EX-1 / EX-2 / DATA-2 | **不满足**：第 7 节逐处登记，但只有 `by="rnd"` → DATA-2 有归属，其余 `owner=null` | 架构师；若需新增 EX-，按 BR-7 ⑤ 走 PRD 版本 +1 与发起人确认 |
| 没有“归属未定”的行 | **不满足**：脚本会返回 2 并列出未归属项 | 同上 |
| 与附表 E.4 EF-1～EF-7 事实一致 | 脚本逐条比对 EF-2/EF-3/EF-5 与目录结构，不一致即报 MISMATCH | 若真包报 MISMATCH，先查是扫描器写错还是 PRD 附表 E 需要更正（后者走 PRD 版本 +1） |

**因此：任何人不得把 `template-scan-draft.md` 当作 PRE-5 完成件提交。** 它只是把架构师要填的空格
先机械填好，减少手工抄错。

## 4. 扫描口径的已知取舍（供架构师 S3-04 确认或否决）

1. **标签/语法用正则近似，不是 PHP 词法分析。** 统计对象是 `{maccms:…}`、`{if}`、`{volist}`、`{include}` 等
   ThinkPHP 定界标签的**文本出现次数**，与 PRD 附表 E.4 的取证口径相同（附表 E.4 也是按文本出现统计）。
   若架构师认为 PRE-5 需要“按语法树统计”（例如嵌套标签内的条件判断分支数），工具需要重写——现在说清楚，别到 S3-05 才发现口径不一致。
2. **`{:函数()}` 与 `{$变量}` 只统计“出现次数 + 行号”，不判断该函数/变量在 PHP 里是否存在。** 后者属于
   RT-7 白名单与实现完备性的问题，不在扫描范围内。
3. **时钟/随机台账按两种形态识别**：内联调用名（`date`/`time`/`strtotime`/`mac_day`/`rand`/`mt_rand`/`uniqid`/`session`/`token`/`cookie` 等，常量 `TIME_RANDOM_CALLS`）与 **ThinkPHP 过滤器**（`{$x|date='Y-m-d',###}`、`{$x|mac_day}`，常量 `FILTER_NONDETERMINISTIC`）。
   两者都必须扫：真包实测内联 `date(`/`mac_day(` **一处也没有**，全部写成过滤器——只按调用名扫会得出“非确定来源 0 处”的假结论（本工具初版就踩了这个坑，现已修）。
   `|date='…',###` 的 `###` 表示“用数据里的时间戳”（由 DB 构造，记 DATA-1）；不带 `,###` 的 `|date='…'` 才是当前时间。
   若真包出现表外函数（例如 `now()`），脚本会漏记——S3-05 出数后请复核人抽查一次“含时间语义的名单是否完整”。
4. **`by="rnd"` 的识别只看属性文本**，包括 `{maccms:… by="rnd"}` 与 `by='rnd'`；若模板里写成 `by=$var` 之类动态值，不计入 DATA-2。
5. **不做模板渲染，也不判断“某标签是否被实现”。** 这是 T3 的事（页面级对比）。

## 5. BLOCKED：真包扫描与对照环境还缺什么

| 缺什么 | 影响 | 谁提供 | 现状证据 |
| --- | --- | --- | --- |
| 真模板包（274 文件）入库 | C-9 与 PRE-5 都**无真包可跑** | 后端开发（S3-01，C-1～C-5） | **已解除**：S3-01 已在分支 `agent/agent/appk-2-f59b49bb7f70` 提交 `8fca22d`（C-1～C-4）与 `3f01ff0`（C-5）入库，`git ls-tree -r` 可见 276 个 `third_party/` 路径 |
| 模板来源提交 `4c418c2…` 的本地对象 | 测试线无法直接从源提交取字节 | 后端开发（S3-01 负责导入并留取证） | **已绕过（不是已解除）**：本机仍无 `4c418c2…` 对象；S3-03 改用 `git archive agent/agent/appk-2-f59b49bb7f70 third_party` 从**S3-01 的入库提交**取字节，保证与入库件同一份。溯源仍应回到源提交，见 5.1 节 |
| 对照环境（PHP 7.3 + Java 两侧站点根）与执行权限 | 无法跑 PRE-2 的 `.compare/APPK-2/php-site`、`java-site` | 运营打包（部署）+ 架构师（S3-04 定边界） | 本机 PHP 7.3.33 存在但 S3-03 明确不允许连接环境；对照环境的地址/权限由 S3-04 与智能调度给 |
| 架构师对扫描口径与归属表的确认 | PRE-5 第 6、7 节无法定稿 | 架构师（S3-04） | 本文件第 3、4 节列出了待确认项 |

按“缺料 → BLOCKED，不猜、不用模拟顶替”的规定，S3-03 的结论是：**测试资产已交付；真包上的 C-9 已返回 0；PRE-5 的机器数据已出；PRE-5 定稿仍 BLOCKED（等架构师补说明与归属）**。
两项原本卡在 S3-01 的缺料（真包、取字节路径）已解决，剩下两项卡在 S3-04（扫描口径确认与归属）与部署/智能调度（对照环境、执行权限）。

## 5.1 真包 C-9 校验结果（返回码 0）

| 项 | 值 |
| --- | --- |
| 取字节方式 | `git archive agent/agent/appk-2-f59b49bb7f70 third_party \| tar -x`（**只读**，不 checkout、不改工作区） |
| 被校对象 | `third_party/maccms-template-v2020.1000.1035/template`（来自 S3-01 提交 `8fca22d` + `3f01ff0`） |
| 结果 | `verify_template_package.py` **返回码 0**：“OK（路径、字节数、blob SHA-1、目录边界全部符合清单）” |
| 覆盖 | 274 个文件逐个；`git hash-object --no-filters` 交叉验证也是 0 |
| 副产物 | 本机 `core.autocrlf=true`，已按 `--no-filters` 跳过过滤器（否则 RISK-T1 会自相矛盾，见第 2 节） |

**一句限制，别误读**：这证明“S3-01 入库的那份字节 = PRD 附表 E.3”，**不等于**“字节 = 源提交 `4c418c2…`”。
本机没有源提交对象，那一步溯源仍要 S3-01 或持有源仓库者补证。

## 5.2 真包 PRE-5 机器数据（27 项检查）

| 检查 | 结果 |
| --- | --- |
| E.2 总数/分目录/`.html` 计数、目录集合、无 `default_wap/ads` | 全部 OK（274 / 147 / 127 / 92 / 76） |
| E.3 清单比对 | OK（274 行，0 缺、0 多、0 字节差异、0 SHA-1 差异） |
| RISK-T1 CRLF | OK：**文本**文件含 CRLF 的 0 个（53 个图片文件的 `0D 0A` 不算；判断依据是字节/blob SHA-1 与 E.3 一致） |
| E.4-EF-1 js/css/ads 不含 `{maccms` | OK（106 个文件命中 0） |
| E.4-EF-2 已知 6 文件齐全 / 无新增 PHP 文件 | 均 OK |
| E.4-EF-3 标签计数（`default_pc`/`default_wap`） | 均 OK |
| E.4-EF-5 其他语法计数 | 均 OK（include 234、if 210、volist 35、else 35、elseif 18、empty 10、foreach 4、switch 1） |
| **F.5-规则2-归属** | **`UNATTRIBUTED`：22 处**，全部是 `|mac_day` 过滤器，等架构师归属 |

### 两个曾误报为 MISMATCH 的口径问题（已查清，不是数据错误）

修工具时发现附表 E.4 的**取证命令**本身会丢信息。列在这里，供架构师在 S3-04 确认扫描口径时一并定：

| 现象 | 根因（已实测确认） | 处理 |
| --- | --- | --- |
| EF-3 期望 `link` 6，而按完整标签名只得 `link` 1 + `link_gbook` 1 + `link_map_{baidu,google,rss,vod}` 4 | 附表 E.4 的取证命令是 `grep -rhoE '\{maccms:[a-z]+'`，**只取小写字母**，`link_gbook` 被截成 `link`。按该命令复跑真包，得到的正是 PRD 那组数字 | 扫描器同时输出两套统计：`maccms_tags`（完整标签名，实现要用）与 `maccms_tags_prd_prefix`（复刻 PRD 口径，用于核对附表 E）。EF-3 比对用后者 |
| EF-5 期望 `{else` 35，而 `grep -rhoE '\{else\b'` 得 77 | 真包里 `{else` 共三种写法：`{else/}` 42、`{else}` 23、`{else /}` 12。**35 = `{else}` 23 + `{else /}` 12**（ThinkPHP 块 else 的两种写法），不含 `{elseif`，也不含无空格的 `{else/}` | 扫描器按“块 else”口径计 35；无空格 `{else/}` 不计入该数值，但仍**逐处登记**到 JSON 的 `think_syntax_other`，避免“少算了却不说” |

两处都是**附表 E.4 取证命令的口径**问题，不是模板包数据错误，也不是实现缺陷，因此**不需要**提缺陷单，也不该因此让 PRD 版本 +1——除非架构师认为 APP 文档需要改成更精确的口径。

## 5.3 PRE-5 定稿还缺什么

| PRE-5 要求 | 真包数据下的现状 | 谁补 |
| --- | --- | --- |
| 168 个 `.html` 各占一行 | 已满足（第 8 节 168 行） | — |
| 每处 PHP 代码有实现说明 | **缺**：8 处（`art/detail.html:33`、`public/jump.html:18`×2/目录、`vod/copyright.html:19`/目录、`vod/plot.html:27`），说明列空 | 架构师（S3-04） |
| 时钟/随机/会话/token 逐处归属 | **缺 22 处** `|mac_day`（`default_pc/html/user/*` 为主）。另有 58 处 `|date='…',###` 已按“用数据时间戳”记 DATA-1（依赖 DB 构数据，不是当前时钟） | 架构师（S3-04） |
| 没有“归属未定”行 | **不满足**（就是上面 22 处） | 架构师 |

**一条要提前说清的风险**：真包里 `{$maccms.date}` 出现 **0 次**，`by="rnd"` 也是 **0 次**。
也就是说，动工时如果只按 PRD 举例的 `$maccms.date` → EX-1、`by="rnd"` → DATA-2 去建环境变量，
**覆盖不到真包里的时间来源**；真正的来源是 `|date` / `|mac_day` 过滤器 + 会员页的时间字段。
这条应在 S3-04 定归属时一并处理，否则“EX-1 已覆盖”会是假覆盖。

## 6. 复核人可以照着做的复现步骤

```bash
# 1) 自测：23 项应全部 PASS，返回码 0
python tests/compare/APPK-2/selftest_offline.py

# 2) 清单副本与 PRD 附表 E.3 是否逐行一致（PRD 附表 E 需先检出到可读路径）
python tests/compare/APPK-2/build_template_manifest.py \
    --prd <附表 E 文件> --check docs/test/APPK-2/fixtures/template-package-manifest.tsv

# 3) S3-01 入库后，对真包跑 C-9（返回 0 = 一致；2 = 不符，逐条看输出）
python tests/compare/APPK-2/verify_template_package.py \
    --target third_party/maccms-template-v2020.1000.1035 \
    --manifest docs/test/APPK-2/fixtures/template-package-manifest.tsv \
    --prd <附表 E 文件>

# 4) 出 PRE-5 数据（返回 2 是正常的：还有“归属未定”行要架构师填）
python tests/compare/APPK-2/scan_templates.py \
    --package third_party/maccms-template-v2020.1000.1035/template \
    --manifest docs/test/APPK-2/fixtures/template-package-manifest.tsv \
    --out-dir .compare/APPK-2/scan-out \
    --markdown .compare/APPK-2/scan-out/template-scan-draft.md
```

`.compare/` 由技术设计 C-6 加入 `.gitignore`（不在本交付里改 `.gitignore`：C-6 不是 S3-03 的条目，
现有 `.gitignore` 已忽略 `.compare/` 之外的运行产物；若 C-6 尚未落地，跑完请自行删除 `.compare/`）。

## 7. 不做什么（防止越界）

* 不修改 `tests/api/**`（接口用例与脚本归接口用例阶段，派工说明明确禁止）。
* 不修改 PRD、技术设计、API 契约、已通过的 T1 用例（`docs/test/APPK-2/t1-cases.md` 一行未动）。
* 不导入模板包（S3-01 的活），不改 `.gitattributes`/`.gitignore`（C-5/C-6 的活），不动 `deploy` 分支。
* 不因为“工具自测通过”就写 AC-1 通过——AC-1 的证据必须是真包上的返回码 0。

## 9. S3-04 架构师补全：PHP 代码实现说明、时间来源归属、扫描口径、环境边界、源提交溯源

> 归属：架构师-海外（S3-04）。本节只补 PRE-5 的缺料，不改第 0～8 节（S3-03 原文保留，其中被本节更正的说法见 9.4）。
> 依据（完整提交号）：S3-03 工具与初稿 `7cf9cbc085d79a8aac61b10190c6615005ab7b5e`（本节的父提交）；S3-01 已放行模板树 `200cffc5d01090faf4f61e153feb472b107b34df`；PRD v10 `9dd36a73febe8bac4c5d02b2638dd57c3e642a49`（已 G0 确认）；技术设计 v13 `9c73bf6ced044f40eeb87ef7861e616b6157499a`（已 G1）；PHP 基线 `a57e9cb2b94d3e500bea7dfe2c579daee093d969` 的 `php/`。
> 本节不改 PRD、技术设计、API、T1、模板资产、`src/main/**`、测试脚本与接口脚本；扫描器源码一行未改。

### 9.0 结论

| PRE-5 完成判定（PRD v10 §13 PRE-5） | S3-04 后的状态 | 证据 |
| --- | --- | --- |
| 168 个 `.html` 各占一行以上，说明每处在 Java 的实现方式 | 满足：9.8 逐文件表 168 行（`default_pc` 92、`default_wap` 76），实现说明按 9.7 的类别编号填写 | 9.7、9.8；复跑见 9.9 ③ |
| 每处 PHP 代码有对应实现说明 | 满足：8 处逐处写明（PHP-1～PHP-8） | 9.2 |
| 当前日期时间、随机值、会话、token 输出逐处登记并归属 | 满足：80 处，全部有归属——DATA-1 79 处、DATA-4 1 处；EX-1、EX-2、DATA-2 均 0 处；**不新增 EX-，不需要 PRD 版本 +1** | 9.3；`docs/test/APPK-2/pre5-attribution.tsv` |
| 没有“归属未定”的行 | 满足：归属表与扫描结果逐处对齐，缺 0、多 0、未归属 0 | 9.9 ④（扫描器本身仍返回 2，原因见 9.9 说明） |
| 与附表 E.4 EF-1～EF-7 一致 | 满足：27 项检查中 26 项 OK，唯一非 OK 就是上一行由本节归属的 22 处；EF-3、EF-5 两处口径已定 | 9.4 CAL-3、CAL-4 |

另有 1 项本节查出的**设计缺口**（GAP-1，9.5）：不影响 PRE-5 的完成判定，但会影响 S3-07 怎么实现，需要调度决定是否修订技术设计。

### 9.1 S3-04 的范围

| 本节处理 | 不处理（归属） |
| --- | --- |
| 8 处 PHP 代码的实现说明；80 处时间来源的归属（含 22 处 `\|mac_day`）；扫描口径（含 `\|date='…',###` 与附表 E.4 的两项统计）；函数清单的补充；对照环境的边界；S3-01 源提交溯源由谁负责 | 判 AC-1（测试，PRE-5 定稿并经 S3-05 复核后判）；搭对照环境、跑 PRE-2（测试按技术设计 §7.3 做，执行权限由调度给）；改 PRD（产品经理）；改技术设计（另派修订，见 9.5） |

### 9.2 PHP 代码逐处实现说明（PHP-1～PHP-8）

位置与 S3-03 初稿第 6 节一一对应（8 处、6 个文件，与附表 E.4 EF-2 的 6 个文件一致，没有新增）。`<?php … ?>` 与 `{php}…{/php}` 都原样进入编译产物（`Template.php` 的配置 `tpl_deny_php=false`；TPL-10），由解释器执行（RT-1）。

| 编号 | 位置 | 代码 | 变量来源（PHP） | Java 实现（技术设计 v13 条目） | 要点与黄金覆盖 |
| --- | --- | --- | --- | --- | --- |
| PHP-1 | `default_pc/html/art/detail.html:33-42` | `$where` 数组赋值；两次 `model('art')->infoData($where,'*',1)`；两行 `//` 注释 | `$obj` = 文章详情控制器 `assign` 的当前文章 | RT-1（数组下标赋值、`//` 注释、`model('<字面量>')->方法()`）；§6.21 MC-1～MC-6（`ModelCallHandler` → `TagModels`）；INFO-1～INFO-7、D1-FLD-1～5；C-18、C-19、C-28、C-29 | D-1 已在 v11～v13 设计完毕，本节不重复。取上一篇/下一篇只读 `mac_art`，不输出时间与会话值（D1-BND-1），所以不进 9.3。黄金：V-13 R-1、V-16 IA-1～IA-7。手机模板 `default_wap/html/art/detail.html` 没有这段代码（D1-BND-4） |
| PHP-2 | `default_pc/html/public/jump.html:18`（第 1 处） | `<?php echo($url);?>` | 前台 `$this->error()/success()` 的 `url`（`thinkphp/library/traits/controller/Jump.php:37-51,78-92`：`success` 的 `url` 为 null 且请求带 `Referer` 时取 `HTTP_REFERER`；`error` 的 `url` 为 null 时，非 ajax 请求取 `javascript:history.back(-1);`、ajax 请求取空串；其余情况（含 `success` 的 `url` 为 null 且请求不带 `Referer`）只要不是空串、不含 `://`、不以 `/` 开头，就走 `Url::build`） | C-70 `JumpPages.error/success` 组页面变量 `url`；RT-1 的 `echo`；RT-3 字符串化（null → 空串） | 原样输出，**不做 HTML 转义**（`&` 不能变成 `&amp;`，NOIGN-3）。`url` 的取值规则属于 C-70，按上面的 `Jump.php` 行号移植；`HTTP_REFERER` 由请求决定，CMP-1 已固定是否带 `Referer`，所以输出确定 |
| PHP-3 | `default_pc/html/public/jump.html:18`（第 2 处） | `<?php echo($wait);?>` | 同上的 `wait`，默认 3（整数） | 同 PHP-2 | 整数按 RT-3 输出十进制。页面里的倒计时脚本（第 23 行起）在浏览器执行，不是服务端输出 |
| PHP-4 | `default_pc/html/vod/copyright.html:19` | `<?php echo($obj['vod_jumpurl']);?>`（外层 `{if condition="$obj['vod_jumpurl'] neq ''"}`） | `$obj` = 视频版权页控制器（`index/controller/Vod.php:74` `copyright()`）`assign` 的视频行 | RT-1 `echo` + 数组下标取值；RT-4 `neq`（编译为 `!=`）弱比较；RT-5 未定义下标为 null；`$obj` 由该页控制器按 PHP `assign` 装配（§6.7 页面变量） | 原样输出数据库字段，不做转义。`vod_jumpurl` 为 null、空串时不进入该块（`null != ''` 为假，RT-4） |
| PHP-5 | `default_pc/html/vod/plot.html:27-41` | 分集剧情分页：给 `$__LIST__` 的 5 个键赋值，`count`、`mac_url_vod_plot`、`mac_page_param`、`intval`、`array_slice`、`if($start<0)`，结果写入 `$__PAGING__`、`$vod_plot_list` | `$obj` 带 `vod_plot_list`（视频模型拆分出的分集数组），`$param` 为请求参数（`index/controller/Vod.php:128` `plot()`） | RT-1（数组下标赋值、`if`、比较、算术、`//` 注释）；RT-7 函数：`count`、`intval`、`array_slice` 按 PHP 7.3 内置语义，`mac_page_param` 逐字移植（C-27），`mac_url_vod_plot` 委托旧地址生成（C-27）；RT-5 | `$param['page']` 缺省或为空时，`intval` 得 0，`$start` = -10，再被改成 0；黄金须覆盖 page 缺省、`'0'`、`'1'`、超出末页、非数字串五种情况（V-11/V-15）。随后的 `{include file="public/paging"}` 在编译期内联（C-20），读同一作用域的 `$__PAGING__`，Java 解释器须让 include 内容与本页共用变量作用域。手机模板 `default_wap/html/vod/plot.html` 没有这段代码 |
| PHP-6 | `default_wap/html/public/jump.html:18`（第 1 处） | 同 PHP-2 | 同 PHP-2 | 同 PHP-2 | 手机与桌面 `jump.html` 字节不同（文件其余部分不同），本行相同 |
| PHP-7 | `default_wap/html/public/jump.html:18`（第 2 处） | 同 PHP-3 | 同 PHP-3 | 同 PHP-3 | — |
| PHP-8 | `default_wap/html/vod/copyright.html:19` | 同 PHP-4 | 同 PHP-4 | 同 PHP-4 | 两个包的 `copyright.html` 字节不同，本行与外层 `{if}` 相同 |

8 处代码用到的语法都在 RT-1 内，用到的函数都在 9.4 CAL-5 的清单里，不需要新增解释器能力（GAP-1 发生在编译产物里，不在这 8 处代码中）。

### 9.3 时间来源归属（80 处；含 22 处 `|mac_day`）

逐处表：`docs/test/APPK-2/pre5-attribution.tsv`（80 行；列：文件、行、写法、变量、归属、依据编号）。下面按依据编号说明归属理由。

**两条源码事实**（取证见 9.9 ⑤）：

1. 过滤器怎么编译（`thinkphp/library/think/Template.php:923-933`）：`{$v|f='a',###}` 编译为 `f('a',$v)`；`{$v|f=a}`（参数里没有 `###`）编译为 `f($v,a)`；`{$v|f}` 编译为 `f($v)`。
2. `mac_day`（`application/common.php:238-249`）：`$t` 为 `empty()` 时返回 `''`；`$t` 是数字时先转成 `date('Y-m-d H:i:s',$t)`；**只有第二个参数等于 `'color'` 时**才读当前时间 `date('Y-m-d',time())`，用来判断 `$t` 是否包含当天日期，包含时外面加 `<font color="#FF0000">…</font>`；否则原样返回 `$t`。也就是说，不带 `color` 的 `mac_day` 输出只取决于数据；带 `color` 时，当前时间只决定是否包一层 `<font>`。

| 依据 | 写法与位置 | 处数 | 归属 | 理由 |
| --- | --- | --- | --- | --- |
| PRE5-T1 | `{$x\|date='Y-m-d',###}` 28、`'Y-m-d H:i:s'` 25、`'m-d'` 5 | 58 | DATA-1 | 编译为 `date('格式',$x)`，格式化的是数据库里的时间戳，不读当前时间；时区按 PHPB-4（PRC）。DATA-1 保证数据里没有对照当天的日期，因此 EX-1 的字面替换不会误中这些输出。58 处都不是登录写入的字段 |
| PRE5-T2 | `{$x\|mac_day}`，不带 `color`：`card_use_time`、`cash_time`、`user_end_time`、`user_reg_time`、`order_time`、`plog_time`、`ulog_time` | 19 | DATA-1 | 输出 = `date('Y-m-d H:i:s',$x)` 或数据原值，不读当前时间。这些字段在被测请求里都只是读取（CMP-2 每个单元前已恢复快照），值来自合成数据 |
| PRE5-T3 | `default_pc/html/user/index.html:48` `{$obj.user_login_time\|mac_day}` | 1 | DATA-4 | 登录时写入 `time()`。`$obj` 来自 `$GLOBALS['user']`，每次请求由 `common/model/User.php:422-452` 的 `checkLogin()` 从 `mac_user` 重新读取（不是会话缓存），所以 DATA-4 在被测请求前执行的 `UPDATE` 对本次输出生效。PRD v10 PRE-5 已点名本处登记 DATA-4。两个包里只有这 1 处输出 `user_login_time`（手机 `user/index.html` 不输出该字段） |
| PRE5-T4 | `default_pc/html/user/ajax_info.html:15`、`default_wap/html/user/ajax_info.html:15` `{$obj.user_last_login_time\|mac_day=color}` | 2 | DATA-1 | 编译为 `mac_day($obj['user_last_login_time'],color)`（`color` 的处理见 GAP-1）。登录把 `user_last_login_time` 写成登录前的 `user_login_time`，即快照里的合成值（DATA-4 的说明已写明不需处理）；DATA-1 保证该值不是对照当天，所以 `color` 分支在两侧都不成立，输出只是格式化后的时间串。PRD v10 PRE-5 已点名本处登记 DATA-1 |

**统计**：DATA-1 79 处（58 + 19 + 2），DATA-4 1 处；EX-1 0 处，EX-2 0 处（本表只登记时间、随机、会话来源，访问源不在其中），DATA-2 0 处（真包里 `by="rnd"` 0 处）。**随机值、会话、token 的输出 0 处**（9.4 CAL-6）。

**对 PRD 用词的一点说明（不影响结论）**：PRE-5 完成判定的句子写的是“归属 EX-1、EX-2 或 DATA-2、DATA-4”，但同一句又把 `ajax_info.html:15` 登记为 DATA-1；附表 F.5 规则 2 允许归属“F.6 的数据构造规则”，DATA-1 就在 F.6 里。因此 79 处 DATA-1 在 PRD v10 范围内，不需要修订 PRD；是否把句子补全成“DATA-1～DATA-4”，由产品经理在下次修订时自行决定。

**给实现与测试的约束**（来自本表，不另改设计）：

- Java 的 `mac_day` 判断“当天”时，要用 EX-1 `$maccms.date` 同一个时钟来源、同一时区（PRC）计算 `Y-m-d`，并按 PHP 的 `strpos(','.$t,$now)>0` 做子串判断。DATA-1 让这一分支在对照中走不到，所以它的正确性靠 S3-05 的函数黄金验证（固定时钟，覆盖“含当天”“不含当天”两种），不靠页面对照。
- `date(格式, 值)` 的取值边界（整数、数字串、`0`、`null`、空串、非数字串）按 PHP 7.3.33 的行为写进 S3-05 的函数黄金；其中非数字串时 PHP 返回 false 并只写日志，响应体输出空串。

### 9.4 扫描口径（CAL-1～CAL-7）

| 编号 | 口径 | 决定 |
| --- | --- | --- |
| CAL-1 | 标签和语法按正则统计文本出现次数，不做 PHP 词法分析（第 4 节第 1 条） | **接受**。附表 E.4 也按文本出现次数取证；编译是否正确由 V-12 逐字节比对编译产物来验证，不靠扫描计数 |
| CAL-2 | `\|date='…',###` 的判定 | **确认记 DATA-1**（9.3 PRE5-T1）。**更正第 4 节第 3 条与 `scan_templates.py:141` 注释里的一句话**：“不带 `,###` 的 `\|date='…'` 才是当前时间”不对。按 `Template.php:928`，它编译为 `date($x,'…')`，即把变量当格式、把格式串当时间戳，也不是“当前时间”。真包里这种写法 0 处（9.9 ⑥），不影响本次结论；今后若出现，按“归属未定”登记，不得自动记 EX-1 或 DATA-1。扫描器只是注释措辞有误，判定逻辑没有错（遇到这种写法不会自动归属），不需要改脚本 |
| CAL-3 | 附表 E.4 EF-3 的 `link` 6（`default_pc`） | **两套统计都保留，各管各的用途**：①附表 E.4 的数字由它写明的取证命令 `grep -rhoE '\{maccms:[a-z]+'` 定义（只取小写字母前缀），用来核对附表 E，结果一致，**PRD 不需要修订**；②实现和测试一律用完整标签名：`default_pc` 里真正的 `{maccms:link}` 标签只有 1 处（`vod/index.html:345`），另外 5 处 `{maccms:link_gbook}`、`{maccms:link_map_baidu}`、`…_google`、`…_rss`、`…_vod`（都在 `default_pc/html/user/foot.html:5`）**不是 `link` 标签**：`TagLib.php:202` 的标签正则是 `link\b`，`_` 属于单词字符，`\b` 匹配失败，所以它们按 TPL-11 原文输出，与 EF-4 的 `path`、`siteaid`、`sitetid`、`siteid` 是同一种情况。TPL-11 本身是通用规则（“标签库不认识的标签不被替换”），Java 照此实现自然正确；但 **V-12/V-15 的样例须把这 5 个名称和 EF-4 的 4 个一起覆盖**（`{maccms:link_gbook}` 原文出现在响应体里，PHP 就是这样输出的） |
| CAL-4 | 附表 E.4 EF-5 的 `else` 35 | **接受 S3-03 的解释**：35 = `{else}` 23 + `{else /}` 12；无空格的 `{else/}` 42 处不计入附表 E.4 这个数，PRD 不需要修订。**实现口径**：三种写法共 77 处都是 `else` 标签，Cx 编译器必须全部处理；`{elseif …}` 18 处另计。V-12 的编译产物比对覆盖全部 168 个文件，所以 77 处都在验证范围内 |
| CAL-5 | 函数清单（RT-7 白名单的输入）只统计了 `{:函数()}` | **补充**（第 4 节表格之外，RT-7 还必须包含下面三类，否则实现时会漏）：①**过滤器函数**（`{$x\|f…}` 编译为函数调用，Template.php:923-933），24 个：`date` 58、`str_replace` 42、`mac_url_img` 39、`mac_day` 22、`htmlspecialchars` 20、`mac_default` 20、`mac_url_create` 13、`mac_url_actor_detail` 11、`mac_substring` 11、`mac_url_page` 10、`long2ip` 9、`mac_get_user_portrait` 8、`ceil` 6、`mac_url_vod_detail` 4、`mac_em_replace` 4、`count` 3、`mac_filter_html` 2、`mac_url_content_img` 2、`intval` 2、`mac_long2ip` 2、`strip_tags` 2、`urlencode` 2、`mac_get_plog_type_text` 2、`explode` 1；②**块标签属性里的函数**：`in_array` 2（`user/plog.html` 两个包各 1）；③**PHP 代码里的函数**：`count`、`intval`、`array_slice`、`mac_page_param`、`mac_url_vod_plot`（PHP-5），以及 `model()->infoData`（PHP-1，经 `ModelCallHandler`，不进函数表）。另有 Cx/Maccms 编译器自己生成的内部函数，按 RT-7 以编译器代码为准。①～③的完整位置用 9.9 ⑦ 的命令复跑。**PRE-5 的函数清单 = 第 4 节 17 个 `{:}` 函数 + 本条三类** |
| CAL-6 | 时间/随机/会话来源的识别范围 | **确认扫描器的两种形态（调用名 + 过滤器）够用，并做了独立复核**：对 168 个 `.html` 另行检索 `$Think.*`、`{:date(`/`time(`/`rand(`/`session(`/`token(` 等、`__TOKEN__`、`$maccms.date`、`$maccms.time`、`$maccms.now`、`by`/`order` 取 `rnd`、`$_SESSION`、`$_COOKIE`、`$GLOBALS`，结果：`$GLOBALS` 36 处，全部是 `$GLOBALS['config'][…]` 配置值与 `$GLOBALS['user']['user_points']`、`$GLOBALS['user']['user_points_froze']`、`$GLOBALS['type_id']`、`$GLOBALS['type_pid']` 数据值，确定；其余全部 0 处；`by` 的取值只有 `hits_month`、`hits_week`、`id`、`score`、`sort`、`time`，`order` 只有 `asc`、`desc`。`Math.random()`（`default_wap/html/user/login.html:27` 验证码点击刷新）和 `new Date()`（6 个页面的百度分享脚本）是写在 `<script>` 里的字面文本，两侧输出的字节相同，在浏览器里才执行，**不是服务端的不确定输出，不登记**。编译文件头里的时间戳不进响应体（V-12 已规定比较时去掉）。控制器层的时间与会话写入（登录写 `user_login_time`、`user_random` 等）不属于模板扫描，由 CMP-2、DATA-4 管 |
| CAL-7 | “EX-1 已覆盖”是否成立（S3-03 第 5.3 节的风险） | **结论：EX-1 不需要修改，也不会出现假覆盖**。真包里 `{$maccms.date}` 0 处、`by="rnd"` 0 处属实；真包的时间输出全部来自数据（9.3 的 80 处），由 DATA-1、DATA-4 保证确定，不靠 EX-1 替换。EX-1 仍按附表 F.5 原样执行：对照中 EX-1 的命中数预期为 0，若某样本命中非 0，两侧命中数必须相等（NORM-5），否则按失败处理并查来源。实现方面，`PageVariables.labelMaccms` 仍须提供 `date` 键（§6.7；`All.php:65`），因为其他模板可能使用；本包不用它 |

### 9.5 本节查出的设计缺口（交调度决定）

| 编号 | 缺口 | 影响 | 建议 |
| --- | --- | --- | --- |
| GAP-1 | `user/ajax_info.html:15`（两个包各 1 处）编译产物为 `mac_day($obj['user_last_login_time'],color)`，`color` 是**没有定义的常量名**。PHP 7.3 把它当作字符串 `'color'`，同时报 E_WARNING；`application/common.php:12` 把 `error_reporting` 设为 `E_ERROR \| E_PARSE`，所以 `think\Error::appError`（`Error.php:66-76`）不抛异常，只写日志，页面正常输出。技术设计 v13 的 RT-1 没有列出“裸标识符（未定义常量）”这种写法，按 RT-1 末句会报不支持，返回 500 | 会员 `ajax_info` 片段在 Java 里会 500，与 PHP 不一致（违反 OP-4、BR-3）；AC-4 和 T3 页面对照会失败 | 技术设计修订一次（v14，小改）：RT-1 增加“裸标识符：不是 `true`/`false`/`null` 的常量名，按 PHP 7.3 取其名字作字符串，警告只进日志”，RT-5 同步；S3-07 的 V-11 增加对应向量；S3-05 黄金覆盖 `mac_day($t,color)`。经全包检索，过滤器参数里的裸标识符只有这 2 处（9.9 ⑧），不影响其他文件。S3-07 开工前处理即可，不阻塞 S3-05 |

### 9.6 对照环境的边界

PRE-5 是**纯静态扫描**：只读 S3-01 的模板字节，不需要 PHP 站点、数据库或网络。所以 PRE-5 定稿**不依赖**对照环境；对照环境是 PRE-2 和 T3 的前提。

| 编号 | 边界 | 内容 |
| --- | --- | --- |
| ENVB-1 | 已有的 | 模板包（S3-01，`200cffc5…`）；C-9 校验器与 C-65 扫描器（S3-03，`7cf9cbc`）；274 行清单副本 |
| ENVB-2 | 设计里还没交付的环境条目 | 技术设计 §7.4 的 S3-03 行规定由测试交付 C-6（`.gitignore` 加 `.compare/`）、C-7（7 键覆盖表）、C-8（站点装配）、C-10（对照 `php.ini`）、C-62（数据快照）、C-63、C-66（结构快照）。`7cf9cbc` 交付的是 C-9、C-65 及辅助工具（平台上的“S3-03”与设计 §7.4 的 S3-03/S3-04 编号不一致，但内容可以对上：C-9 属于设计 S3-03 的验证工具，C-65 属于设计 S3-04），上面这 7 项仍未交付，须由调度另行派给测试。其中 C-6 未落地，所以本次复跑时 `.compare/` 不在 `.gitignore` 里，跑完请删除（9.9 末尾） |
| ENVB-3 | 环境的固定口径（不在本节重定） | PHP 侧按附表 F.1 PHPB-1～PHPB-9（PHP 7.3.33 NTS x64、`php -S` 单进程、`date.timezone=PRC`、`LC_ALL=C`、`display_errors=Off` 等）；站点装配按技术设计 §7.3 ENV-1～ENV-10 与 DEC-2（站点根下 `template/` 只有 `default_pc`、`default_wap`）；数据按 CMP-2、DATA-1～DATA-4；对照前两侧各跑一次 C-9（V-2） |
| ENVB-4 | 谁提供、谁批准 | 装配脚本与执行：测试（ENVB-2 各项）。仓库外的输入：`vendor/` 来自生产备份（ENV-2，按 `AGENTS.md`「本地运行与测试」），数据库 `maccms_it_full` 与账号 `it_admin`、`it_app`（附表 A.3），连接信息只经环境变量 `MACCMS_DB_URL`、`MACCMS_DB_USERNAME`、`MACCMS_DB_PASSWORD` 提供，不写进仓库；PHP 安装目录由 PRE-2 登记，不写进仓库。**执行权限**：PRE-2 要启动 PHP/Java 站点并在 IT 库上恢复和写数据，属于“连环境、执行”，须由调度明确放行；现在人类已暂停接口测试线，PRE-2 是否属于暂停范围由调度判断。运营打包只在需要部署时介入；本期不部署（RISK-T6） |
| ENVB-5 | S3-05 的环境需求（提前说明） | C-33 的编译产物、值语义、函数黄金只需要 PHPB-1 的 PHP 7.3.33 命令行（`date.timezone=PRC`、`LC_ALL=C`、`precision=14`）和基线 `php/` 的归档，不需要 Web 站点；C-33 ④ 的 D-1 模型调用黄金需要在本机 MySQL 上加载 ART-FIX 夹具（V-16），这一步属于连数据库，同样须由调度放行 |

### 9.7 逐文件实现说明的类别编号（9.8 表格引用）

| 编号 | 含义 | Java 实现（技术设计 v13） | 验证 |
| --- | --- | --- | --- |
| IM-TAG | 有 19 个已定义 `{maccms:…}` 标签 | §6.6；C-22（Maccms 标签编译，TAGMAP-1～19）+ C-28 `TagModels` | V-12、V-19 |
| IM-TPL11 | 有标签库不认识的 `{maccms:…}`，原文输出 | TPL-11（9.4 CAL-3） | V-12、V-15 |
| IM-SYN | 有 `{if}`、`{volist}`、`{else}` 等 Cx 语法 | C-21（Cx 编译） | V-12 |
| IM-INC | 有 `{include}` | C-20（编译期内联，同一作用域） | V-12 |
| IM-FN | 有 `{:函数()}` 或过滤器函数 | RT-7 + C-27（清单见 9.4 CAL-5） | V-15 |
| IM-VAR | 有 `{$变量}` | §6.7 页面变量（`labelMaccms` + 各页控制器 `assign`） | AC-4、T3 |
| PHP-n | 有 PHP 代码 | 9.2 | 9.2 所列 |
| PRE5-Tn×k | 有 k 处时间来源 | 9.3 | 对照时按 DATA-1/DATA-4 构造数据 |
| IM-LIT | 只有普通文本，没有模板语法 | 编译产物等于原文 | V-12 |

### 9.8 逐文件清单（168 个 `.html`）

列：文件、`{maccms:…}` 标签名（去重）、`{include}` 个数、函数（`{:}` 调用 + `\|` 过滤器，去重）、PHP 代码处数、实现说明。表格由 9.9 ③ 的扫描结果生成，可复跑核对。

| 文件 | 标签 | include | 函数 | PHP | 实现说明 |
| --- | --- | --- | --- | --- | --- |
| `default_pc/html/actor/detail.html` | art, vod | 3 | mac_url, mac_url_art_detail, \|date, \|mac_url_img, \|mac_url_vod_detail | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_pc/html/actor/index.html` | actor | 3 | mac_url, \|mac_url_actor_detail, \|mac_url_img | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/actor/rss.html` | — | 0 | mac_url_actor_detail, \|mac_filter_html, \|mac_substring | 0 | IM-FN；IM-VAR |
| `default_pc/html/actor/show.html` | actor, foreach | 4 | mac_url, \|mac_url_actor_detail, \|mac_url_img | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/art/confirm.html` | — | 0 | url | 0 | IM-SYN；IM-FN；IM-VAR |
| `default_pc/html/art/detail.html` | art | 4 | mac_url_art_detail, mac_url_type, \|date, \|mac_url_content_img, \|mac_url_img | 1 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PHP-1；PRE5-T1×1 |
| `default_pc/html/art/detail_pwd.html` | — | 0 | — | 0 | IM-SYN；IM-VAR |
| `default_pc/html/art/index.html` | art, type | 3 | mac_url_art_detail, mac_url_art_index, \|date | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_pc/html/art/rss.html` | foreach | 0 | mac_url_art_detail, \|date, \|htmlspecialchars, \|mac_substring, \|str_replace | 0 | IM-TAG；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_pc/html/art/search.html` | — | 3 | — | 0 | IM-SYN；IM-INC；IM-VAR |
| `default_pc/html/art/type.html` | art | 4 | mac_url_art_detail, mac_url_type, \|date | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_pc/html/comment/ajax.html` | comment, for, foreach | 0 | \|date, \|intval, \|long2ip, \|mac_em_replace, \|mac_get_user_portrait | 0 | IM-TAG；IM-SYN；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_pc/html/comment/index.html` | comment, foreach | 3 | \|date, \|long2ip, \|mac_get_user_portrait | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_pc/html/gbook/index.html` | gbook | 4 | \|date, \|mac_long2ip | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×2 |
| `default_pc/html/gbook/report.html` | — | 0 | — | 0 | IM-SYN；IM-VAR |
| `default_pc/html/index/index.html` | — | 0 | mac_url | 0 | IM-FN |
| `default_pc/html/label/rank.html` | vod | 3 | mac_url_vod_detail | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/map/index.html` | vod | 3 | mac_url_img, mac_url_type, mac_url_vod_detail, \|date | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×2 |
| `default_pc/html/plot/detail.html` | vod | 3 | mac_url_img, mac_url_plot_detail, mac_url_type, mac_url_vod_detail | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/plot/index.html` | — | 0 | — | 0 | IM-LIT |
| `default_pc/html/public/browser.html` | — | 0 | — | 0 | IM-VAR |
| `default_pc/html/public/close.html` | — | 0 | — | 0 | IM-VAR |
| `default_pc/html/public/digg.html` | — | 0 | — | 0 | IM-VAR |
| `default_pc/html/public/foot.html` | type | 0 | mac_url, mac_url_type | 0 | IM-TAG；IM-FN；IM-VAR |
| `default_pc/html/public/head.html` | foreach, type | 0 | mac_url, mac_url_topic_index, mac_url_type, \|mac_url_img | 0 | IM-TAG；IM-SYN；IM-FN；IM-VAR |
| `default_pc/html/public/include.html` | — | 0 | — | 0 | IM-VAR |
| `default_pc/html/public/jump.html` | — | 0 | — | 2 | IM-VAR；PHP-2、PHP-3 |
| `default_pc/html/public/msg.html` | — | 0 | — | 0 | IM-VAR |
| `default_pc/html/public/paging.html` | foreach | 0 | \|mac_url_page | 0 | IM-TAG；IM-SYN；IM-FN；IM-VAR |
| `default_pc/html/public/score.html` | — | 0 | — | 0 | IM-VAR |
| `default_pc/html/public/star.html` | — | 0 | \|ceil | 0 | IM-FN；IM-VAR |
| `default_pc/html/public/star2.html` | — | 0 | \|ceil | 0 | IM-SYN；IM-FN；IM-VAR |
| `default_pc/html/role/detail.html` | — | 0 | — | 0 | IM-VAR |
| `default_pc/html/role/index.html` | — | 0 | — | 0 | IM-LIT |
| `default_pc/html/role/show.html` | — | 0 | — | 0 | IM-LIT |
| `default_pc/html/rss/baidu.html` | vod | 0 | mac_url_vod_detail, \|date | 0 | IM-TAG；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_pc/html/rss/bing.html` | vod | 0 | mac_url_vod_detail, \|date | 0 | IM-TAG；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_pc/html/rss/google.html` | vod | 0 | mac_url_vod_detail, \|date | 0 | IM-TAG；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_pc/html/rss/index.html` | vod | 0 | mac_url_vod_detail, \|date, \|htmlspecialchars, \|mac_substring, \|strip_tags | 0 | IM-TAG；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_pc/html/rss/sm.html` | vod | 0 | mac_url_vod_detail, \|date | 0 | IM-TAG；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_pc/html/rss/so.html` | vod | 0 | mac_url_vod_detail, \|date | 0 | IM-TAG；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_pc/html/rss/sogou.html` | vod | 0 | mac_url_vod_detail, \|date | 0 | IM-TAG；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_pc/html/topic/detail.html` | foreach | 3 | mac_url_art_detail, mac_url_img, mac_url_vod_detail, \|count, \|mac_url_img | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/topic/index.html` | topic | 4 | mac_url_topic_detail, \|count, \|explode, \|mac_url_img | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/user/ajax_info.html` | — | 0 | url, \|mac_day | 0 | IM-FN；IM-VAR；PRE5-T4×1 |
| `default_pc/html/user/ajax_login.html` | — | 0 | url | 0 | IM-SYN；IM-FN |
| `default_pc/html/user/bind.html` | — | 3 | url, \|mac_default, \|mac_url_img | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/user/buy.html` | — | 3 | url, \|mac_default, \|mac_url_img | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/user/cards.html` | — | 3 | url, \|mac_day, \|mac_default, \|mac_url_img, \|str_replace | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T2×1 |
| `default_pc/html/user/cash.html` | — | 3 | url, \|mac_day, \|mac_default, \|mac_url_img, \|str_replace | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T2×1 |
| `default_pc/html/user/downs.html` | — | 3 | url, \|mac_default, \|mac_url_img, \|str_replace | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/user/favs.html` | — | 3 | url, \|mac_default, \|mac_url_img, \|str_replace | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/user/findpass.html` | — | 2 | url | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/user/findpass_msg.html` | — | 2 | url | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/user/foot.html` | link_gbook, link_map_baidu, link_map_google, link_map_rss, link_map_vod | 0 | — | 0 | IM-TPL11(link_gbook,link_map_baidu,link_map_google,link_map_rss,link_map_vod)；IM-VAR |
| `default_pc/html/user/head.html` | — | 0 | url | 0 | IM-FN；IM-VAR |
| `default_pc/html/user/include.html` | — | 0 | — | 0 | IM-VAR |
| `default_pc/html/user/index.html` | — | 2 | mac_url, url, \|long2ip, \|mac_day, \|mac_default, \|mac_url_img | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T2×2；PRE5-T3×1 |
| `default_pc/html/user/info.html` | — | 3 | url, \|mac_default, \|mac_url_img | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/user/login.html` | — | 2 | url | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/user/orders.html` | — | 3 | url, \|mac_day, \|mac_default, \|mac_url_img, \|str_replace | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T2×1 |
| `default_pc/html/user/pay.html` | foreach | 3 | url, \|mac_default, \|mac_url_img | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/user/payment_weixin.html` | — | 3 | url, \|mac_default, \|mac_url_img, \|urlencode | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/user/plays.html` | — | 3 | url, \|mac_default, \|mac_url_img, \|str_replace | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/user/plog.html` | — | 3 | url, \|mac_day, \|mac_default, \|mac_get_plog_type_text, \|mac_url_img, \|str_replace | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T2×1 |
| `default_pc/html/user/popedom.html` | — | 2 | url, \|mac_day, \|mac_default, \|mac_url_img | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T2×1 |
| `default_pc/html/user/reg.html` | — | 2 | url | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/user/reward.html` | — | 3 | url, \|mac_day, \|mac_default, \|mac_url_img, \|str_replace | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T2×1 |
| `default_pc/html/user/upgrade.html` | — | 3 | url, \|mac_day, \|mac_default, \|mac_url_img | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T2×1 |
| `default_pc/html/vod/confirm.html` | — | 0 | url | 0 | IM-SYN；IM-FN；IM-VAR |
| `default_pc/html/vod/copyright.html` | — | 0 | — | 1 | IM-SYN；PHP-4 |
| `default_pc/html/vod/detail.html` | foreach, vod | 4 | mac_url_img, mac_url_type, mac_url_vod_detail, mac_url_vod_play, mac_url_vod_plot, \|ceil, \|date, \|mac_default, \|mac_url_content_img, \|mac_url_create, \|mac_url_img | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_pc/html/vod/detail_pwd.html` | — | 0 | — | 0 | IM-SYN；IM-VAR |
| `default_pc/html/vod/down.html` | — | 3 | mac_url_type | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/vod/downer.html` | — | 0 | url | 0 | IM-SYN；IM-FN；IM-VAR |
| `default_pc/html/vod/downer_pwd.html` | — | 0 | — | 0 | IM-SYN；IM-VAR |
| `default_pc/html/vod/index.html` | art, foreach, link, type, vod | 3 | mac_url, mac_url_art_detail, mac_url_img, mac_url_type, mac_url_vod_detail, \|date, \|mac_substring, \|mac_url_img | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×6 |
| `default_pc/html/vod/play.html` | foreach, vod | 3 | mac_url_img, mac_url_type, mac_url_vod_detail, mac_url_vod_play | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/vod/player.html` | — | 0 | url | 0 | IM-SYN；IM-FN；IM-VAR |
| `default_pc/html/vod/player_pwd.html` | — | 0 | — | 0 | IM-SYN；IM-VAR |
| `default_pc/html/vod/plot.html` | — | 4 | mac_url_plot_detail, mac_url_type, \|mac_substring | 1 | IM-SYN；IM-INC；IM-FN；IM-VAR；PHP-5 |
| `default_pc/html/vod/role.html` | — | 0 | mac_url_role_detail | 0 | IM-SYN；IM-FN；IM-VAR |
| `default_pc/html/vod/rss.html` | foreach | 0 | mac_url_vod_detail, mac_url_vod_play, \|date, \|htmlspecialchars, \|mac_substring | 0 | IM-TAG；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_pc/html/vod/search.html` | vod | 4 | mac_url_img, mac_url_search, mac_url_vod_detail, \|date, \|mac_default | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_pc/html/vod/show.html` | foreach, vod | 4 | mac_url_img, mac_url_type, mac_url_vod_detail | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/vod/type.html` | foreach, type, vod | 4 | mac_url_img, mac_url_type, mac_url_vod_detail, \|mac_default | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_pc/html/website/detail.html` | website | 4 | mac_url_img, mac_url_type, mac_url_website_detail, \|ceil, \|date, \|mac_url_create, \|mac_url_img | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_pc/html/website/index.html` | website | 3 | mac_url_img, mac_url_type, mac_url_website_detail, mac_url_website_index, \|date | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×2 |
| `default_pc/html/website/rss.html` | — | 0 | mac_url_website_detail, \|mac_filter_html, \|mac_substring | 0 | IM-FN；IM-VAR |
| `default_pc/html/website/search.html` | website | 4 | mac_url_img, mac_url_type, mac_url_website_detail, \|date | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×2 |
| `default_pc/html/website/show.html` | foreach, website | 4 | mac_url_img, mac_url_type, mac_url_website_detail, \|date | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×2 |
| `default_pc/html/website/type.html` | website | 4 | mac_url_img, mac_url_type, mac_url_website_detail, \|date | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×2 |
| `default_wap/html/art/confirm.html` | — | 0 | url | 0 | IM-SYN；IM-FN；IM-VAR |
| `default_wap/html/art/detail.html` | — | 3 | \|date | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_wap/html/art/detail_pwd.html` | — | 0 | — | 0 | IM-SYN；IM-VAR |
| `default_wap/html/art/rss.html` | foreach | 0 | mac_url_art_detail, \|date, \|htmlspecialchars, \|mac_substring, \|str_replace | 0 | IM-TAG；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_wap/html/art/search.html` | — | 3 | — | 0 | IM-SYN；IM-INC；IM-VAR |
| `default_wap/html/art/type.html` | art | 4 | mac_url_art_detail, \|date | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_wap/html/comment/ajax.html` | comment, for, foreach | 0 | \|date, \|intval, \|long2ip, \|mac_em_replace, \|mac_get_user_portrait | 0 | IM-TAG；IM-SYN；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_wap/html/comment/index.html` | comment, foreach | 3 | \|date, \|long2ip, \|mac_get_user_portrait | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_wap/html/gbook/index.html` | gbook | 4 | \|date, \|mac_long2ip, \|mac_url_img | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×2 |
| `default_wap/html/gbook/report.html` | — | 0 | — | 0 | IM-SYN；IM-VAR |
| `default_wap/html/index/index.html` | art, link, type, vod | 3 | mac_url, mac_url_art_detail, mac_url_img, mac_url_type, mac_url_vod_detail, \|mac_url_img | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_wap/html/label/rank.html` | vod | 3 | mac_url_vod_detail | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_wap/html/map/index.html` | vod | 3 | mac_url_vod_detail, \|date | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_wap/html/plot/detail.html` | — | 3 | mac_url_plot_detail | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_wap/html/plot/index.html` | — | 0 | — | 0 | IM-LIT |
| `default_wap/html/public/browser.html` | — | 0 | — | 0 | IM-VAR |
| `default_wap/html/public/close.html` | — | 0 | — | 0 | IM-VAR |
| `default_wap/html/public/digg.html` | — | 0 | — | 0 | IM-VAR |
| `default_wap/html/public/foot.html` | — | 0 | — | 0 | IM-VAR |
| `default_wap/html/public/head.html` | foreach, type | 0 | mac_url, mac_url_type | 0 | IM-TAG；IM-FN；IM-VAR |
| `default_wap/html/public/include.html` | — | 0 | — | 0 | IM-VAR |
| `default_wap/html/public/jump.html` | — | 0 | — | 2 | IM-VAR；PHP-6、PHP-7 |
| `default_wap/html/public/msg.html` | — | 0 | — | 0 | IM-VAR |
| `default_wap/html/public/paging.html` | foreach | 0 | \|mac_url_page | 0 | IM-TAG；IM-SYN；IM-FN；IM-VAR |
| `default_wap/html/public/score.html` | — | 0 | — | 0 | IM-VAR |
| `default_wap/html/public/star.html` | — | 0 | \|ceil | 0 | IM-FN；IM-VAR |
| `default_wap/html/public/star2.html` | — | 0 | \|ceil | 0 | IM-SYN；IM-FN；IM-VAR |
| `default_wap/html/rss/baidu.html` | vod | 0 | mac_url_vod_detail, \|date | 0 | IM-TAG；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_wap/html/rss/bing.html` | vod | 0 | mac_url_vod_detail, \|date | 0 | IM-TAG；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_wap/html/rss/google.html` | vod | 0 | mac_url_vod_detail, \|date | 0 | IM-TAG；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_wap/html/rss/index.html` | vod | 0 | mac_url_vod_detail, \|date, \|htmlspecialchars, \|mac_substring, \|strip_tags | 0 | IM-TAG；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_wap/html/rss/sm.html` | vod | 0 | mac_url_vod_detail, \|date | 0 | IM-TAG；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_wap/html/rss/so.html` | vod | 0 | mac_url_vod_detail, \|date | 0 | IM-TAG；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_wap/html/rss/sogou.html` | vod | 0 | mac_url_vod_detail, \|date | 0 | IM-TAG；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_wap/html/topic/detail.html` | foreach | 3 | mac_url_art_detail, mac_url_img, mac_url_vod_detail, \|count, \|mac_url_img | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_wap/html/topic/index.html` | topic | 5 | mac_url_topic_detail, \|mac_url_img | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_wap/html/user/ajax_info.html` | — | 0 | url, \|mac_day | 0 | IM-FN；IM-VAR；PRE5-T4×1 |
| `default_wap/html/user/ajax_login.html` | — | 0 | url | 0 | IM-SYN；IM-FN |
| `default_wap/html/user/bind.html` | — | 1 | url | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_wap/html/user/buy.html` | — | 1 | url | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_wap/html/user/cards.html` | — | 1 | url, \|mac_day | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T2×1 |
| `default_wap/html/user/cash.html` | — | 1 | url, \|mac_day | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T2×1 |
| `default_wap/html/user/downs.html` | — | 1 | mac_url_vod_detail, url, \|mac_day | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T2×1 |
| `default_wap/html/user/favs.html` | — | 1 | url, \|mac_day | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T2×1 |
| `default_wap/html/user/findpass.html` | — | 2 | url | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_wap/html/user/findpass_msg.html` | — | 2 | url | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_wap/html/user/foot.html` | — | 0 | — | 0 | IM-VAR |
| `default_wap/html/user/head.html` | — | 0 | — | 0 | IM-LIT |
| `default_wap/html/user/include.html` | path, siteaid, siteid, sitetid | 0 | — | 0 | IM-TPL11(path,siteaid,siteid,sitetid)；IM-VAR |
| `default_wap/html/user/index.html` | — | 1 | mac_url, url, \|date, \|mac_default, \|mac_url_img | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_wap/html/user/info.html` | — | 1 | url | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_wap/html/user/login.html` | — | 2 | url | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_wap/html/user/orders.html` | — | 1 | url, \|mac_day | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T2×1 |
| `default_wap/html/user/pay.html` | foreach | 1 | url | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_wap/html/user/payment_weixin.html` | — | 1 | url, \|urlencode | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_wap/html/user/plays.html` | — | 1 | url, \|mac_day | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T2×1 |
| `default_wap/html/user/plog.html` | — | 1 | url, \|mac_day, \|mac_get_plog_type_text | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T2×1 |
| `default_wap/html/user/popedom.html` | — | 1 | url, \|mac_day | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T2×1 |
| `default_wap/html/user/reg.html` | — | 2 | url, \|mac_url_img | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_wap/html/user/reward.html` | — | 1 | url, \|mac_day | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T2×1 |
| `default_wap/html/user/upgrade.html` | — | 1 | url, \|mac_day | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T2×1 |
| `default_wap/html/vod/confirm.html` | — | 0 | url | 0 | IM-SYN；IM-FN；IM-VAR |
| `default_wap/html/vod/copyright.html` | — | 0 | — | 1 | IM-SYN；PHP-8 |
| `default_wap/html/vod/detail.html` | foreach, vod | 3 | mac_url_img, mac_url_vod_detail, mac_url_vod_play, mac_url_vod_plot, \|date, \|mac_url_create | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×2 |
| `default_wap/html/vod/detail_pwd.html` | — | 0 | — | 0 | IM-SYN；IM-VAR |
| `default_wap/html/vod/down.html` | vod | 3 | mac_url_img, mac_url_vod_detail, \|date | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_wap/html/vod/downer.html` | — | 0 | url | 0 | IM-SYN；IM-FN；IM-VAR |
| `default_wap/html/vod/downer_pwd.html` | — | 0 | — | 0 | IM-SYN；IM-VAR |
| `default_wap/html/vod/play.html` | foreach, vod | 3 | mac_url_img, mac_url_vod_detail, mac_url_vod_play, \|date | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_wap/html/vod/player.html` | — | 0 | url | 0 | IM-SYN；IM-FN；IM-VAR |
| `default_wap/html/vod/player_pwd.html` | — | 0 | — | 0 | IM-SYN；IM-VAR |
| `default_wap/html/vod/plot.html` | — | 3 | mac_url_plot_detail, \|mac_substring | 0 | IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_wap/html/vod/rss.html` | foreach | 0 | mac_url_vod_detail, mac_url_vod_play, \|date, \|htmlspecialchars, \|mac_substring | 0 | IM-TAG；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_wap/html/vod/search.html` | vod | 4 | mac_url_img, mac_url_vod_detail, \|date | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR；PRE5-T1×1 |
| `default_wap/html/vod/show.html` | foreach, vod | 4 | mac_url_img, mac_url_type, mac_url_vod_detail | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR |
| `default_wap/html/vod/type.html` | foreach, vod | 4 | mac_url_img, mac_url_type, mac_url_vod_detail | 0 | IM-TAG；IM-SYN；IM-INC；IM-FN；IM-VAR |

### 9.9 复跑命令（在本提交的仓库根目录执行；Git Bash）

```bash
# ① 血缘与改动范围：父提交是 S3-03 的 7cf9cbc；只改说明文档
git rev-parse HEAD^                      # = 7cf9cbc085d79a8aac61b10190c6615005ab7b5e
git diff --name-status 7cf9cbc085d79a8aac61b10190c6615005ab7b5e HEAD
#   M docs/test/APPK-2/template-scan.md
#   A docs/test/APPK-2/pre5-attribution.tsv
git --attr-source=HEAD diff --check 7cf9cbc085d79a8aac61b10190c6615005ab7b5e HEAD   # 退出码 0

# ② 只读取出 S3-01 已放行的模板包（不 checkout、不改工作区）
mkdir -p .compare/APPK-2/pkg
git archive 200cffc5d01090faf4f61e153feb472b107b34df third_party | tar -x -C .compare/APPK-2/pkg
PKG=.compare/APPK-2/pkg/third_party/maccms-template-v2020.1000.1035/template

# ③ 扫描（Windows 控制台要加 -X utf8，否则输出中文时脚本异常退出、返回 3）
python -X utf8 -I tests/compare/APPK-2/scan_templates.py --package "$PKG" \
  --manifest docs/test/APPK-2/fixtures/template-package-manifest.tsv \
  --out-dir .compare/APPK-2/scan-out --markdown .compare/APPK-2/scan-out/template-scan-draft.md
#   预期：27 项检查 1 项非 OK（F.5-规则2-归属，22 处 |mac_day），返回 2。
#   扫描器没有读取归属表的参数，所以它对 |mac_day 永远报“归属未定”；归属以 ④ 为准（本节不改测试脚本）。

# ④ 归属表与扫描结果逐处对齐：预期 scan 80 unique 80 tsv 80 missing [] extra [] unowned []
python -X utf8 -I - <<'EOF'
import json
j = json.load(open('.compare/APPK-2/scan-out/scan-result.json', encoding='utf-8'))
scan = [(f['path'], x['line'], x['call']) for f in j['files'] for x in f['nondeterministic']]
rows = [l.rstrip('\n').split('\t') for l in open('docs/test/APPK-2/pre5-attribution.tsv', encoding='utf-8') if not l.startswith('#')][1:]
tsv = {(r[0], int(r[1]), r[2]): r[4] for r in rows}
ok = {'EX-1', 'EX-2', 'DATA-1', 'DATA-2', 'DATA-4'}
print('scan', len(scan), 'unique', len(set(scan)), 'tsv', len(rows),
      'missing', sorted(set(scan) - tsv.keys()), 'extra', sorted(tsv.keys() - set(scan)),
      'unowned', [k for k, v in tsv.items() if v not in ok])
EOF
cut -f5,6 docs/test/APPK-2/pre5-attribution.tsv | grep -v '^#' | sort | uniq -c
#   预期：58 DATA-1 PRE5-T1、19 DATA-1 PRE5-T2、2 DATA-1 PRE5-T4、1 DATA-4 PRE5-T3

# ⑤ 9.3、9.5 引用的 PHP 源码事实
git show a57e9cb2b94d3e500bea7dfe2c579daee093d969:php/thinkphp/library/think/Template.php | sed -n '921,935p'
git show a57e9cb2b94d3e500bea7dfe2c579daee093d969:php/application/common.php | sed -n '12p;238,249p'
git show a57e9cb2b94d3e500bea7dfe2c579daee093d969:php/application/common/model/User.php | sed -n '422,452p'
git show a57e9cb2b94d3e500bea7dfe2c579daee093d969:php/thinkphp/library/think/Error.php | sed -n '66,76p'
git show a57e9cb2b94d3e500bea7dfe2c579daee093d969:php/thinkphp/library/think/template/TagLib.php | sed -n '199,203p'
git show a57e9cb2b94d3e500bea7dfe2c579daee093d969:php/thinkphp/library/traits/controller/Jump.php | sed -n '37,51p;78,92p'

# ⑥ |date 的三种写法计数（预期 25 / 28 / 5，共 58）；不带 ### 的 |date= 预期 0 行
grep -rhoE "\|date='[^']*',###" --include=*.html "$PKG" | sort | uniq -c
grep -rnoE "\|date=[^}]*" --include=*.html "$PKG" | grep -v '###' | wc -l
grep -rn "mac_day" --include=*.html "$PKG" | wc -l          # 预期 22
grep -c "user_login_time" "$PKG/default_wap/html/user/index.html"   # 预期 0

# ⑦ 过滤器函数清单（9.4 CAL-5 ①），按次数列出
python -X utf8 -I - "$PKG" <<'EOF'
import os, re, sys, collections
c = collections.Counter()
for dp, _, fs in os.walk(sys.argv[1]):
    for f in fs:
        if f.endswith('.html'):
            t = open(os.path.join(dp, f), 'rb').read().decode('utf-8')
            for m in re.finditer(r"\{\$[^{}\n]*\}", t):
                for part in re.split(r"(?<!\|)\|(?!\|)", m.group(0)[2:-1])[1:]:
                    n = part.split('=', 1)[0].strip()
                    if re.fullmatch(r"[A-Za-z_]\w*", n): c[n] += 1
print(len(c), c.most_common())
EOF

# ⑧ 过滤器参数里的裸标识符（GAP-1），预期只有两个 ajax_info.html:15 的 color
grep -rnoE "\|[a-z_]+=[A-Za-z_][A-Za-z0-9_]*\}" --include=*.html "$PKG"

# ⑨ 时间/随机/会话的独立复核（9.4 CAL-6），预期每条 0 行
for p in '\$Think\.' '\{:(date|time|strtotime|rand|mt_rand|uniqid|session|cookie|token)\(' '__TOKEN__' \
         '\$maccms\.(date|time|now)' '(by|order)="rnd"' '\$_SESSION' '\$_COOKIE'; do
  printf '%s\t%s\n' "$p" "$(grep -rnE "$p" --include=*.html "$PKG" | wc -l)"; done
grep -rhoE '\b(by|order)="[^"]*"' --include=*.html "$PKG" | sort | uniq -c
grep -rhoE "GLOBALS[][A-Za-z_'\"]*" --include=*.html "$PKG" | sort | uniq -c

# ⑩ S3-01 源提交溯源（9.10）：在仓库外的空目录里执行
git init -q --bare /tmp/maccms10-src.git && cd /tmp/maccms10-src.git
git fetch -q --filter=blob:none --depth=1 https://github.com/magicblack/maccms10 \
  "refs/tags/v2020.1000.1035:refs/tags/v2020.1000.1035"
git rev-parse 'v2020.1000.1035^{commit}'          # = 4c418c2703692148a937ad92893ab8a494bff742
for p in template template/default_pc template/default_wap LICENSE; do
  echo "$p $(git rev-parse "v2020.1000.1035^{commit}:$p")"; done
cd - && for p in template template/default_pc template/default_wap LICENSE; do
  echo "$p $(git rev-parse "200cffc5d01090faf4f61e153feb472b107b34df:third_party/maccms-template-v2020.1000.1035/$p")"; done
#   两组四个哈希应逐个相同（9.10 表）

# 收尾：C-6 未落地，.compare/ 不在 .gitignore 里，跑完删除
rm -rf .compare
```

### 9.10 S3-01 源提交溯源：由谁负责、证据到哪一步

| 编号 | 内容 |
| --- | --- |
| SRC-1 | **溯源由 S3-01（后端开发）负责**：它入库模板包并写 `SOURCE.md`（C-4），“入库字节来自官方提交 `4c418c2…`”这个说法由它举证；它在交付评论里已记录取得命令与三棵树哈希，后端评审在 `200cffc5` 上复核过 |
| SRC-2 | **测试线只负责“入库字节 = 附表 E.3”**（C-9 返回 0，第 5.1 节）。测试线本机没有源提交对象不是缺料：Git 树哈希由其下全部文件的路径、模式和 blob 哈希逐层算出，两个提交里 `template` 的树哈希相同，就说明 274 个文件逐字节相同，不需要再从源提交取字节 |
| SRC-3 | **S3-04 已独立复核（2026-11-01）**：在仓库外的空裸仓库里只拉取官方标签的树（不下载文件内容），9.9 ⑩ 的结果如下，与 S3-01 的入库提交逐个相同 |
| SRC-4 | AC-1 仍由测试判（PRE-5 定稿并经 S3-05 复核后），本节不判 |

| 对象 | 官方标签 `v2020.1000.1035` → 提交 `4c418c2703692148a937ad92893ab8a494bff742` | S3-01 入库 `200cffc5…:third_party/maccms-template-v2020.1000.1035/` |
| --- | --- | --- |
| `template` | `9398a1ed539cc754b7513c76e12134b3b5b49a53` | `9398a1ed539cc754b7513c76e12134b3b5b49a53` |
| `template/default_pc` | `4c8d4ce6f8a68c343f94ccb15beda9157d77aea7` | `4c8d4ce6f8a68c343f94ccb15beda9157d77aea7` |
| `template/default_wap` | `a211f900e060c1d405f2c9d75523e02abac45b80` | `a211f900e060c1d405f2c9d75523e02abac45b80` |
| `LICENSE` | `261eeb9e9f8b2b4b0d119366dda99c6fd7d35c64` | `261eeb9e9f8b2b4b0d119366dda99c6fd7d35c64` |
| `template` 下文件数 | 274 | 274 |

### 9.11 PRE-5 之后谁做什么（供调度参考，本节不派工）

| 事项 | 归属 | 前提 |
| --- | --- | --- |
| 复核本节，判定 PRE-5 是否定稿，按 PRD v10 PRE-5 把清单交发起人知悉 | 调度 | — |
| GAP-1 是否修订技术设计（v14） | 调度决定；架构师执行 | 不阻塞 S3-05；须在 S3-07 开工前 |
| S3-05 黄金：按 9.2（PHP-5 的分页边界）、9.3（`mac_day` 固定时钟、`date` 取值边界）、9.4 CAL-3（9 个原文输出的标签名）、CAL-5（函数清单）补样例 | 测试 | PRE-5 定稿；连数据库部分须调度放行（ENVB-5） |
| ENVB-2 的 7 项环境条目 | 测试 | 调度派工；执行 PRE-2 须调度放行（ENVB-4） |
| 扫描器注释与第 4 节第 3 条的措辞（CAL-2），以及以后是否给扫描器加“读入归属表”的参数 | 测试 | 不急，下次修订扫描器时一起改 |
